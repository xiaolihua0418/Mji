package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import com.yalantis.ucrop.UCrop
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AddMomentActivity : AppCompatActivity() {
    companion object {
        private const val MAX_MEDIA = 9        // 支持图片+视频混发，总数上限9
        private const val IMG_SEPARATOR = "|||"
    }

    // 统一存 Media：区分图片/视频；视频不裁剪，直接拷贝到 filesDir
    private val selectedMedia = mutableListOf<MomentMedia>()
    private lateinit var imageAdapter: AddImageAdapter
    // 记录已选对应的原始 Uri（预览用，拷贝完就落盘成持久化 path）

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val resultUri = UCrop.getOutput(data)
            if (resultUri != null) {
                if (selectedMedia.size < MAX_MEDIA) {
                    // 裁剪后图片：拷贝到 filesDir
                    val persistPath = persistUriToInternalFile(this, resultUri, "jpg")
                    selectedMedia.add(MomentMedia(MomentMedia.Type.IMAGE, persistPath))
                    imageAdapter.notifyDataSetChanged()
                }
            } else {
                Toast.makeText(this, "图片裁剪失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            if (selectedMedia.size >= MAX_MEDIA) {
                Toast.makeText(this, "最多只能发 $MAX_MEDIA 个媒体啦", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val destUri = Uri.fromFile(File(filesDir, "moment_crop_${System.currentTimeMillis()}.jpg"))
            val options = UCrop.Options()
            options.setFreeStyleCropEnabled(true)
            options.setToolbarTitle("裁剪配图")
            val uCropIntent = UCrop.of(uri, destUri).withOptions(options).getIntent(this)
            cropImage.launch(uCropIntent)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            if (selectedMedia.size >= MAX_MEDIA) {
                Toast.makeText(this, "最多只能发 $MAX_MEDIA 个媒体啦", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            try {
                // 视频直接拷贝到 filesDir，不要裁剪
                val ext = when (contentResolver.getType(uri)?.lowercase()) {
                    "video/quicktime" -> "mov"
                    "video/webm" -> "webm"
                    "video/3gpp" -> "3gp"
                    else -> "mp4"
                }
                val persistPath = persistUriToInternalFile(this, uri, ext)
                if (File(persistPath).exists() && File(persistPath).length() > 0) {
                    selectedMedia.add(MomentMedia(MomentMedia.Type.VIDEO, persistPath))
                    imageAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "✅ 视频已添加（${formatDuration(getVideoDurationSec(Uri.fromFile(File(persistPath)), this))}）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "视频拷贝失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "添加视频失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_moment)
        findViewById<TextView>(R.id.btnCancel).setOnClickListener { finish() }

        val rvImages = findViewById<RecyclerView>(R.id.rvAddImages)
        rvImages.layoutManager = GridLayoutManager(this, 3)
        imageAdapter = AddImageAdapter(selectedMedia,
            onAddImageClick = {
                if (selectedMedia.size >= MAX_MEDIA) {
                    Toast.makeText(this, "最多只能发 $MAX_MEDIA 个媒体啦", Toast.LENGTH_SHORT).show()
                } else {
                    pickImage.launch(arrayOf("image/*"))
                }
            },
            onAddVideoClick = {
                if (selectedMedia.size >= MAX_MEDIA) {
                    Toast.makeText(this, "最多只能发 $MAX_MEDIA 个媒体啦", Toast.LENGTH_SHORT).show()
                } else {
                    pickVideo.launch(arrayOf("video/*"))
                }
            },
            onDeleteClick = { pos ->
                if (pos in selectedMedia.indices) {
                    // 删除时顺便删掉 filesDir 内持久化的文件（不占空间）
                    try {
                        val f = File(selectedMedia[pos].path)
                        if (f.exists()) f.delete()
                    } catch (e: Exception) {}
                    selectedMedia.removeAt(pos)
                    imageAdapter.notifyDataSetChanged()
                }
            }
        )
        rvImages.adapter = imageAdapter

        findViewById<TextView>(R.id.btnPublish).setOnClickListener {
            val content = findViewById<EditText>(R.id.etMomentContent).text.toString().trim()
            if (content.isEmpty() && selectedMedia.isEmpty()) {
                Toast.makeText(this, "老板，好歹写点字或发张图/视频吧！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var myId = "my_id"
            var myName = "我"
            try {
                val db = DatabaseHelper(this).readableDatabase
                val cursor = db.query("MyProfile", null, null, null, null, null, null)
                if (cursor.moveToFirst()) {
                    myId = cursor.getSafeString("myId")
                    myName = cursor.getSafeString("myName").ifEmpty { "我" }
                }
                cursor.close()
            } catch (e: Exception) {}
            try {
                val writeDb = DatabaseHelper(this).writableDatabase
                try { writeDb.execSQL("ALTER TABLE Moments ADD COLUMN translatedText TEXT") } catch (e: Exception) {}

                // 🛑【已移除高危自毁行】：不再盲目 delete "Contacts" 表里的玩家数据，保住你的名字！

                // 用 buildImageDesc 打包：I:path1|||V:path2|||...
                val finalImageDesc = buildImageDesc(selectedMedia)
                val values = ContentValues().apply {
                    put("aiId", myId) // 这里代表是由玩家自己发的朋友圈
                    put("content", content)
                    put("translatedText", "")
                    put("imageDesc", finalImageDesc)
                    put("timestamp", System.currentTimeMillis())
                }
                val newMomentId = writeDb.insertOrThrow("Moments", null, values)
                val mediaCountStr = buildString {
                    val imgs = selectedMedia.count { it.type == MomentMedia.Type.IMAGE }
                    val vids = selectedMedia.count { it.type == MomentMedia.Type.VIDEO }
                    if (imgs > 0) append("${imgs}张图")
                    if (vids > 0) {
                        if (isNotEmpty()) append(" + ")
                        append("${vids}个视频")
                    }
                }
                Toast.makeText(this, "✅ 动态已上墙！${mediaCountStr} 正在后台挨个敲门查房...", Toast.LENGTH_LONG).show()

                // 传入 myId 作为发帖人 ID (authorId)
                notifyHarem(applicationContext, newMomentId.toInt(), content, finalImageDesc, myName, myId, myId)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 暴毙了！死因：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 核心拆分函数：将单条消息拆分为【纯台词（含内心）】和【翻译】
     */
    private fun splitTextAndTranslation(raw: String): Pair<String, String> {
        val trimmedRaw = raw.trim()
        var text = trimmedRaw
        var trans = ""

        // 优化后的正则：允许【翻译】标签前后有任意空格/换行，且不强制用 $ 锁死末尾，容错率极高
        val translateRegex = Regex("【(?:评论翻译|私聊翻译|翻译|译)】[：:]?\\s*([\\s\\S]+?)(?=\\s*<\\|SPLIT\\|>|\\s*【(?:角色ID|评论|私聊|点赞)】|\\s*$)")
        val tagMatch = translateRegex.find(trimmedRaw)

        if (tagMatch != null) {
            // 成功提取翻译内容
            trans = tagMatch.groupValues[1].trim()
            // 抹去【翻译】及其后面的所有内容，保留前面的台词和内心
            text = trimmedRaw.substring(0, tagMatch.range.first).trim()
            return Pair(text, trans)
        }

        // 兜底逻辑：匹配括号形式的翻译
        val bracketMatch = Regex("[（(]([^（）()]{4,500})[）)]\\s*$").find(trimmedRaw)
        if (bracketMatch != null) {
            trans = bracketMatch.groupValues[1].trim()
            text = trimmedRaw.removeRange(bracketMatch.range).trim()
        }

        return Pair(text, trans)
    }

    /**
     * 清洗函数：只在最终渲染“台词气泡”和“内心卡片”时使用！
     * 绝对不要在 splitTextAndTranslation 之前调用它！
     */
    private fun cleanDialogContent(raw: String): String {
        // 移除可能残留在台词文本里的【内心】标签，且不再粗暴地在这里清洗【翻译】
        return raw
            .replace(Regex("【(?:评论翻译|私聊翻译|翻译|译)】[\\s\\S]*$"), "")
            .replace(Regex("【?(内心|评论|私聊)】?[：:]?"), "")
            .trim()
    }

    // 🌟 升级版通知函数：引入 authorId (发布动态的人的ID) 确保多角色生态关系正确
    private fun notifyHarem(context: Context, momentId: Int, momentContent: String, imageDesc: String, myName: String, myId: String, authorId: String) {
        val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: ""
        val key = sharedPref.getString("apiKey", "") ?: ""
        val model = sharedPref.getString("modelName", "gemini-3.1-pro") ?: "gemini-3.1-pro"
        if (url.isEmpty() || key.isEmpty()) return
        var finalUrl = url
        while (finalUrl.endsWith("/")) finalUrl = finalUrl.substring(0, finalUrl.length - 1)
        if (!finalUrl.endsWith("/chat/completions")) finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

        Thread {
            try {
                val db = DatabaseHelper(context).readableDatabase
                val mainHandler = Handler(Looper.getMainLooper())
                fun notifyAutoReplyFailure(message: String) {
                    mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
                }
                val client = Http.client.newBuilder().connectTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()

                // 获取发帖人的名字
                var authorName = myName
                if (authorId != myId) {
                    val authorCur = db.rawQuery("SELECT realName FROM Contacts WHERE userId = ?", arrayOf(authorId))
                    if (authorCur.moveToFirst()) {
                        authorName = authorCur.getSafeString("realName").ifEmpty { "神秘人" }
                    }
                    authorCur.close()
                }

                // 读取所有角色信息
                val cursor = db.rawQuery("SELECT * FROM Contacts WHERE userId != ?", arrayOf(myId))
                data class AiInfo(val aiId: String, val aiName: String, val aiPersona: String, val aiLang: String, val requireTrans: Boolean, val memory: String, val recentChats: String, val relationship: String)
                val aiList = mutableListOf<AiInfo>()
                while (cursor.moveToNext()) {
                    val aiId = cursor.getSafeString("userId")
                    val aiName = cursor.getSafeString("realName").ifEmpty { "神秘人" }
                    val aiPersona = cursor.getSafeString("identityInfo")

                    // ⚠️【核心加锁 1】：从 Contacts 表里读取这个 AI 角色与玩家/其他人的设定关系
                    // 如果你的表里字段叫别的（比如 relation），请微调这里的字段名。没有就默认“朋友”
                    val relationFromDb = try { cursor.getSafeString("relationship") } catch(e: Exception){ "" }
                    val relationship = relationFromDb.ifEmpty { "朋友" }

                    val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                    val requireTrans = sharedPref.getBoolean("autoTrans_$aiId", false)
                    var memory = ""
                    var recentChats = ""
                    try {
                        val memCur = db.query("MemoryBank", null, "aiId=?", arrayOf(aiId), null, null, "insertTime DESC", "15")
                        val sb = StringBuilder()
                        while (memCur.moveToNext()) {
                            sb.append(memCur.getSafeString("memoryText")).append("\n")
                        }
                        memory = sb.toString().trim()
                        memCur.close()

                        val chatCur = db.rawQuery("SELECT isFromMe, content FROM ChatHistory WHERE aiId=? ORDER BY timestamp DESC LIMIT 10", arrayOf(aiId))
                        val chatList = mutableListOf<String>()
                        while (chatCur.moveToNext()) {
                            val speaker = if (chatCur.getInt(0) == 1) myName else aiName
                            chatList.add("$speaker: ${chatCur.getString(1)}")
                        }
                        chatCur.close()
                        recentChats = chatList.reversed().joinToString("\n")
                    } catch (e: Exception) {}
                    aiList.add(AiInfo(aiId, aiName, aiPersona, aiLang, requireTrans, memory, recentChats, relationship))
                }
                cursor.close()

                // 构造包含了明确关系链的角色设定环境
                val allCharInfo = aiList.joinToString("\n---\n") { ai ->
                    // 1. 判断是否需要翻译，并定义目标语言
                    val isChinese = ai.aiLang == "默认 (中文)"

                    // 2. 纯粹注入语言和翻译的“绝对意志”，不要在这里写松散的格式描述
                    val langLine = when {
                        isChinese -> "【语言核心要求】：当前必须全中文对话，绝对禁止输出任何外语。"
                        ai.requireTrans -> "【语言核心要求】：当前必须使用 ${ai.aiLang} 进行对话！同时，你必须严格遵循底部的【格式指令】，为每一条外语台词单独输出【翻译】标签，绝对禁止混淆！"
                        else -> "【语言核心要求】：当前必须完全使用 ${ai.aiLang} 进行对话，且不需要输出任何中文翻译。"
                    }

                    val memLine = if (ai.memory.isNotEmpty()) "记忆：${ai.memory.take(500)}" else ""
                    val chatLine = if (ai.recentChats.isNotEmpty()) "最近聊天：${ai.recentChats.take(500)}" else ""

                    // 别忘了把 isChinese 状态也传递给你最外层的 System Prompt 动态拼装
                    // 确保最底部的格式块能精准切换！ // 根据动态真正属于谁，动态刷新社会关系
                    val actualRelation = if (authorId == myId) {
                        "你与发帖人（玩家 ${myName}）的关系：${ai.relationship}"
                    } else {
                        val charRel = getCharacterRelationshipText(db, ai.aiId, authorId)
                        if (charRel != null) "你与发帖人（角色 ${authorName}）的关系：$charRel"
                        else "你与发帖人（角色 ${authorName}）的关系：普通认识"
                    }

                    "⚠️角色ID（必须原样输出）：${ai.aiId}\n角色名：${ai.aiName}\n人设：${ai.aiPersona}\n⚡${actualRelation}\n$langLine\n$memLine\n$chatLine"
                }

                // ⚠️【核心加锁 2】：升级全套 Prompt，严厉框死社交红线，切断暧昧调情
                val sysPrompt = """
你现在要同时扮演以下${aiList.size}个角色，分别对【$authorName】发的朋友圈动态做出反应。

朋友圈发布者：$authorName
朋友圈内容：「$momentContent」
${run {
    val medias = parseMomentMediaList(imageDesc)
    val imgCount = medias.count { it.type == MomentMedia.Type.IMAGE }
    val vidCount = medias.count { it.type == MomentMedia.Type.VIDEO }
    if (medias.isNotEmpty()) buildString {
        append("（本次动态配有")
        if (imgCount > 0) append(" $imgCount 张图片")
        if (vidCount > 0) append(" $vidCount 个视频")
        append("）")
    } else ""
}}

各角色背景及社交关系：
$allCharInfo

【❌ 社交行为红线（铁律违者出局）】：
1. 必须根据你与发布者【$authorName】的真实【社交关系】来进行扮演！
2. 如果你在背景里查到你与发布者的关系是“兄弟”、“死党”、“普通朋友”或“同事”，你的评论和私聊语气必须符合直男互损、搞笑、吐槽或纯粹的兄弟情谊。【绝对禁止】出现任何越界的暧昧、调情、撒娇、说情话或恋爱脑行为！
3. 只有当你与发布者的关系明确是“恋人”或者发布者是玩家 $myName 且人设允许时，才可以有亲昵或暧昧举动。

【输出规则】：
1. 必须按顺序输出每个角色的反应，每个角色之间用 <|SPLIT|> 严格分隔。
2. 每个角色必须有独立个性，评论和私聊不能同时为空（至少有一个有内容）。
3. 必须严格执行【各角色背景】中指定的语言要求（中文或外语）。

【每个角色的绝对标准输出格式】：
若该角色要求用【中文】：
【角色ID】原始角色ID
【评论】纯中文评论内容（若不评论则留空，禁止包含任何动作、旁白或翻译标签）
【私聊】纯中文私聊内容（若不私聊则留空，禁止包含任何动作、旁白或翻译标签）

若该角色要求用【外语】（如英文/日文等）：
【角色ID】原始角色ID
【评论】纯外语评论内容（若不评论则留空）
【评论翻译】对应上一行外语评论的纯中文翻译（若评论留空，则此行也留空）
【私聊】纯外语私聊内容（若不私聊则留空）
【私聊翻译】对应上一行外语私聊的纯中文翻译（若私聊留空，则此行也留空）

【绝对禁止】：
1. 严禁多个角色混在一起输出，必须用 <|SPLIT|> 严格隔开。
2. 严禁把【评论翻译】或【私聊翻译】的文本混进【评论】和【私聊】标签内部。
3. 严禁自行篡改、删减或漏掉任何格式标签。
""".trimIndent()

                // 图片 + 视频分别处理：图片原图base64；视频首帧缩略图base64（避免上传几十MB视频）
                val allMedia = parseMomentMediaList(imageDesc)
                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", sysPrompt) })
                    if (allMedia.isNotEmpty()) {
                        val contentArray = JSONArray()
                        contentArray.put(JSONObject().apply { put("type", "text"); put("text", "请各角色立刻回应这条朋友圈。图片和视频如下（视频仅提供首帧缩略图）：") })
                        for (m in allMedia) {
                            try {
                                val bytes = when (m.type) {
                                    MomentMedia.Type.IMAGE -> {
                                        openInputStreamSafe(context, m.path)?.readBytes()
                                    }
                                    MomentMedia.Type.VIDEO -> {
                                        // 视频：只提取首帧，压缩成 JPEG 256KB 以内的缩略图
                                        val bitmap = getVideoThumbnail(Uri.fromFile(File(m.path)), context)
                                        if (bitmap != null) {
                                            val baos = java.io.ByteArrayOutputStream()
                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                                            baos.toByteArray()
                                        } else null
                                    }
                                }
                                if (bytes != null && bytes.size > 0) {
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    contentArray.put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$base64") }) })
                                }
                            } catch (e: Exception) {}
                        }
                        put(JSONObject().apply { put("role", "user"); put("content", contentArray) })
                    } else {
                        put(JSONObject().apply { put("role", "user"); put("content", "请各角色立刻回应。") })
                    }
                }

                val jsonBody = JSONObject().apply {
                    put("model", model); put("temperature", 0.82); put("messages", messagesArray)
                }
                val request = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    notifyAutoReplyFailure("朋友圈自动回应失败")
                    return@Thread
                }

                val rawReply = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                val blocks = rawReply.split("<|SPLIT|>").map { it.trim() }.filter { it.isNotEmpty() }

                val writeDb = DatabaseHelper(context).writableDatabase
                for ((index, block) in blocks.withIndex()) {
                    val roleIdMatch = Regex("【?角色ID】?[：:]?\\s*(.+?)(?=\\n|【评论】|【私聊】|$)").find(block)
                    val blockAiId = roleIdMatch?.groupValues?.get(1)?.trim()?.removePrefix("：")?.removePrefix(":")?.trim() ?: ""

                    val ai = aiList.find { it.aiId == blockAiId }
                        ?: aiList.find { blockAiId.isNotEmpty() && (blockAiId.contains(it.aiId) || it.aiId.contains(blockAiId)) }
                        ?: aiList.find { block.contains(it.aiName) }

                    if (ai == null) continue

                    val realAiId = ai.aiId
                    var cmtStr = Regex("【评论】(.*?)(?=【私聊】|$)", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    var dmStr = Regex("【私聊】(.*?)$", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""

                    val cmtPair = splitTextAndTranslation(cmtStr)
                    cmtStr = cmtPair.first
                    val cmtTrans = cmtPair.second

                    val dmPair = splitTextAndTranslation(dmStr)
                    dmStr = dmPair.first
                    val dmTrans = dmPair.second
                    val allowDm = (1..100).random() <= 35

                    val cleanDm = cleanDialogContent(dmStr)
                    val cleanCmt = cleanDialogContent(cmtStr)

                    if (cleanDm.isNotEmpty() && allowDm) {
                        val chatValues = ContentValues().apply {
                            put("aiId", realAiId)
                            put("content", cleanDm)
                            put("isFromMe", 0)
                            put("isRead", 0)
                            put("isVoice", 0)
                            put("voiceDuration", 0)
                            put("translatedText", dmTrans)
                            put("timestamp", System.currentTimeMillis())
                            put("groupId", "")
                            put("msgTime", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                        }
                        writeDb.insert("ChatHistory", null, chatValues)
                        context.sendBroadcast(Intent("CYBER_NEW_MSG"))
                        mainHandler.post {
                            Toast.makeText(context, "💬 ${ai.aiName} 悄悄给你发了微信！", Toast.LENGTH_LONG).show()
                        }
                    }

                    if (cleanCmt.isNotEmpty()) {
                        val existCheck = writeDb.rawQuery("SELECT id FROM Comments WHERE momentId=? AND userId=?", arrayOf(momentId.toString(), realAiId))
                        val alreadyCommented = existCheck.moveToFirst()
                        existCheck.close()
                        if (!alreadyCommented) {
                            val cmtValues = ContentValues().apply {
                                put("momentId", momentId); put("userId", realAiId); put("userName", ai.aiName)
                                put("content", cleanCmt); put("translatedText", cmtTrans); put("timestamp", System.currentTimeMillis())
                            }
                            writeDb.insert("Comments", null, cmtValues)
                            try {
                                val checkLike = writeDb.rawQuery("SELECT id FROM Likes WHERE momentId=? AND userId=?", arrayOf(momentId.toString(), realAiId))
                                if (!checkLike.moveToFirst()) {
                                    writeDb.insert("Likes", null, ContentValues().apply { put("momentId", momentId);put("userId", realAiId); put("userName", ai.aiName) })
                                }
                                checkLike.close()
                            } catch (e: Exception) {}
                            mainHandler.post { Toast.makeText(context, "🔔 ${ai.aiName} 评论了你的动态！", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "朋友圈自动回应失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

/** 发布页九宫格媒体选择 Adapter：支持图片 + 视频混发，末尾追加【加图】【加视频】两个按钮 */
class AddImageAdapter(
    private val items: MutableList<MomentMedia>,
    private val onAddImageClick: () -> Unit,
    private val onAddVideoClick: () -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<AddImageAdapter.VH>() {
    companion object {
        private const val TYPE_ITEM = 0   // 已选媒体（图/视频）
        private const val TYPE_ADD_IMG = 1
        private const val TYPE_ADD_VID = 2
        private const val MAX_ITEMS = 9
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivPicked: ImageView = view.findViewById(R.id.ivPicked)
        val layoutAdd: View = view.findViewById(R.id.layoutAdd)
        val ivDelete: ImageView = view.findViewById(R.id.ivDelete)
        val ivPlay: ImageView = view.findViewById(R.id.ivPlay)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        /** 给 layoutAdd 这个格子里的图标/文字动态改色，区分加图 vs 加视频 */
        val addIcon: ImageView? = (layoutAdd as? LinearLayout)?.getChildAt(0) as? ImageView
        val addText: TextView? = (layoutAdd as? LinearLayout)?.getChildAt(1) as? TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_add_moment_image, parent, false)
        return VH(view)
    }

    override fun getItemViewType(position: Int): Int = when {
        position < items.size -> TYPE_ITEM
        position == items.size -> TYPE_ADD_IMG
        else -> TYPE_ADD_VID
    }

    override fun getItemCount(): Int {
        if (items.size >= MAX_ITEMS) return items.size
        // 两个添加按钮：加图 + 加视频
        return items.size + 2
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (getItemViewType(position)) {
            TYPE_ADD_IMG -> {
                applyAddStyle(holder, "加图片", android.graphics.Color.parseColor("#999999"))
                holder.itemView.setOnClickListener { onAddImageClick() }
            }
            TYPE_ADD_VID -> {
                applyAddStyle(holder, "加视频", android.graphics.Color.parseColor("#FF6F00"))
                holder.itemView.setOnClickListener { onAddVideoClick() }
            }
            else -> {
                // 已选的媒体
                holder.layoutAdd.visibility = View.GONE
                holder.ivPicked.visibility = View.VISIBLE
                holder.ivDelete.visibility = View.VISIBLE
                val media = items[position]
                when (media.type) {
                    MomentMedia.Type.IMAGE -> {
                        holder.ivPlay.visibility = View.GONE
                        holder.tvDuration.visibility = View.GONE
                        try {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(media.path)
                            if (bitmap != null) holder.ivPicked.setImageBitmap(bitmap)
                            else holder.ivPicked.setBackgroundColor(android.graphics.Color.LTGRAY)
                        } catch (e: Exception) {
                            holder.ivPicked.setBackgroundColor(android.graphics.Color.LTGRAY)
                        }
                    }
                    MomentMedia.Type.VIDEO -> {
                        holder.ivPlay.visibility = View.VISIBLE
                        holder.tvDuration.visibility = View.VISIBLE
                        val ctx = holder.itemView.context
                        try {
                            val thumb = getVideoThumbnail(Uri.fromFile(java.io.File(media.path)), ctx)
                            if (thumb != null) holder.ivPicked.setImageBitmap(thumb)
                            else holder.ivPicked.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                            val sec = getVideoDurationSec(Uri.fromFile(java.io.File(media.path)), ctx)
                            holder.tvDuration.text = formatDuration(sec)
                        } catch (e: Exception) {
                            holder.ivPicked.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                            holder.tvDuration.text = ""
                        }
                    }
                }
                holder.itemView.setOnClickListener(null)
                holder.ivDelete.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < items.size) onDeleteClick(pos)
                }
            }
        }
    }

    private fun applyAddStyle(holder: VH, label: String, tintColor: Int) {
        holder.ivPicked.visibility = View.GONE
        holder.ivDelete.visibility = View.GONE
        holder.ivPlay.visibility = View.GONE
        holder.tvDuration.visibility = View.GONE
        holder.layoutAdd.visibility = View.VISIBLE
        holder.addIcon?.setColorFilter(tintColor)
        holder.addText?.text = label
        holder.addText?.setTextColor(tintColor)
    }
}
