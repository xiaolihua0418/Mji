package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yalantis.ucrop.UCrop
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class DiscoverFragment : Fragment() {
    private val momentList = mutableListOf<Moment>()
    private lateinit var adapter: MomentAdapter
    private var currentCoverUri: Uri? = null
    private val cropCoverImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val ctx = context ?: return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val resultUri = UCrop.getOutput(data)
            if (resultUri != null) {
                currentCoverUri = resultUri
                val sharedPref = ctx.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                sharedPref.edit().putString("myCoverUri", resultUri.toString()).apply()
                view?.findViewById<ImageView>(R.id.ivCover)?.let { iv ->
                    try {
                        val inputStream = ctx.contentResolver.openInputStream(resultUri)
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        iv.setImageBitmap(bitmap)
                        inputStream?.close()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "\u5c01\u9762\u9884\u89c8\u5931\u8d25", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "\u5c01\u9762\u88c1\u526a\u5931\u8d25", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val pickCoverImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val destinationUri = Uri.fromFile(File(requireContext().filesDir, "cover_${System.currentTimeMillis()}.jpg"))
            val options = UCrop.Options()
            options.setCircleDimmedLayer(false)
            options.setShowCropGrid(true)
            options.setToolbarTitle("装修朋友圈壁纸")
            val uCropIntent = UCrop.of(uri, destinationUri).withAspectRatio(4f, 3f).withMaxResultSize(1200, 900).withOptions(options).getIntent(requireContext())
            cropCoverImage.launch(uCropIntent)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_discover, container, false)
        try {
            val helper = DatabaseHelper(requireContext())
            val db = helper.writableDatabase
            fun execSafe(sql: String) { try { db.execSQL(sql) } catch (_: Exception) {} }
            execSafe("ALTER TABLE Moments ADD COLUMN translatedText TEXT")
            execSafe("CREATE TABLE IF NOT EXISTS Likes (id INTEGER PRIMARY KEY AUTOINCREMENT, momentId INTEGER, userId TEXT, userName TEXT)")
            execSafe("CREATE TABLE IF NOT EXISTS Comments (id INTEGER PRIMARY KEY AUTOINCREMENT, momentId INTEGER, userId TEXT, userName TEXT, content TEXT, translatedText TEXT DEFAULT '', timestamp INTEGER, isReplied INTEGER DEFAULT 0, replyToId INTEGER DEFAULT 0, replyToName TEXT DEFAULT '')")
            execSafe("ALTER TABLE Comments ADD COLUMN translatedText TEXT")
            execSafe("ALTER TABLE Comments ADD COLUMN isReplied INTEGER DEFAULT 0")
            execSafe("ALTER TABLE Comments ADD COLUMN replyToId INTEGER DEFAULT 0")
            execSafe("ALTER TABLE Comments ADD COLUMN replyToName TEXT DEFAULT ''")
            execSafe("UPDATE Comments SET isReplied = 1 WHERE isReplied IS NULL")
            helper.repairLegacyTextArtifacts()
        } catch (e: Exception) {}

        val tvMyName = view.findViewById<TextView>(R.id.tvMyName)
        val ivMyAvatar = view.findViewById<ImageView>(R.id.ivMyAvatar)
        val ivCover = view.findViewById<ImageView>(R.id.ivCover)
        val sharedPref = requireContext().getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val coverPath = sharedPref.getString("myCoverUri", "")
        if (!coverPath.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(coverPath)
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                ivCover?.setImageBitmap(android.graphics.BitmapFactory.decodeStream(inputStream))
                inputStream?.close()
            } catch (e: Exception) { ivCover?.setBackgroundColor(android.graphics.Color.parseColor("#333333")) }
        }
        ivCover.setOnClickListener { pickCoverImage.launch(arrayOf("image/*")) }

        try {
            val db = DatabaseHelper(requireContext()).readableDatabase
            val cursor = db.query("MyProfile", null, null, null, null, null, null)
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex("myName")
                if (nameIdx != -1) tvMyName?.text = cursor.getString(nameIdx)
                val avatarIdx = cursor.getColumnIndex("myAvatarUri")
                if (avatarIdx != -1) {
                    val avatarPath = cursor.getString(avatarIdx)
                    if (avatarPath.isNotEmpty()) {
                        val bitmap = if (avatarPath.startsWith("/")) {
                            android.graphics.BitmapFactory.decodeFile(avatarPath)
                        } else {
                            requireContext().contentResolver.openInputStream(Uri.parse(avatarPath))
                                ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        }
                        if (bitmap != null) ivMyAvatar?.setImageBitmap(bitmap)
                    }
                }
            }
            cursor.close()
        } catch (e: Exception) {}

        val rvMoments = view.findViewById<RecyclerView>(R.id.rvMoments)
        rvMoments.layoutManager = LinearLayoutManager(requireContext())
        adapter = MomentAdapter(momentList) { loadMoments() }
        rvMoments.adapter = adapter
        loadMoments()

        view.findViewById<ImageView>(R.id.btnGenerateMoment).setOnClickListener {
            Toast.makeText(requireContext(), "🔮 魔法阵启动！", Toast.LENGTH_SHORT).show()
            generateAiMoment()
        }
        view.findViewById<ImageView>(R.id.btnAddMoment).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), AddMomentActivity::class.java))
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        loadMoments()
    }

    private fun triggerOtherAiReactions(context: Context, moment: Moment, posterAiId: String) {
        Thread {
            try {
                val db = DatabaseHelper(context).readableDatabase
                val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val url = sharedPref.getString("apiUrl", "") ?: ""
                val key = sharedPref.getString("apiKey", "") ?: ""
                val model = sharedPref.getString("modelName", "gemini-3.1-pro") ?: "gemini-3.1-pro"
                if (url.isEmpty() || key.isEmpty()) return@Thread

                var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                if (!finalUrl.endsWith("/chat/completions"))
                    finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                // 取除了发帖者之外的所有 AI，随机选1-2个来互动
                val others = mutableListOf<Triple<String, String, String>>() // aiId, name, persona
                val cur = db.rawQuery(
                    "SELECT userId, realName, identityInfo FROM Contacts WHERE userId != ? ORDER BY RANDOM() LIMIT 2",
                    arrayOf(posterAiId)
                )
                while (cur.moveToNext()) {
                    others.add(Triple(cur.getString(0), cur.getString(1), cur.getString(2)))
                }
                cur.close()

                loop@ for ((otherAiId, otherAiName, otherPersona) in others) {
                    Thread.sleep(1500) // 错开时间，不要同时涌入

                    // 读取这个 AI 的记忆
                    var cyberMemory = ""
                    try {
                        db.rawQuery(
                            "SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 30",
                            arrayOf(otherAiId)
                        ).use { c ->
                            val sb = StringBuilder()
                            while (c.moveToNext()) sb.append(c.getString(0)).append("\n")
                            cyberMemory = sb.toString().trim()
                        }
                    } catch (e: Exception) {}

                    val aiLang = sharedPref.getString("aiLang_$otherAiId", "默认 (中文)") ?: "默认 (中文)"
                    val requireTrans = sharedPref.getBoolean("autoTrans_$otherAiId", false)
                    val langNote = if (aiLang != "默认 (中文)") {
                        val transRule = if (requireTrans) "，内容末尾加【翻译】中文翻译" else ""
                        "【语言规则：必须用${aiLang}，禁止中文${transRule}】"
                    } else ""
                    val charRel = getCharacterRelationshipText(db, otherAiId, posterAiId)
                    val relationshipNote = if (charRel != null)
                        "【你和${moment.name}的关系】：$charRel（严格遵守此关系，不得超出关系范围做任何暧昧或情感举动）"
                    else ""
                    val sysPrompt = """
你是 $otherAiName，人设：$otherPersona。
【核心记忆】：$cyberMemory
${if (relationshipNote.isNotEmpty()) "$relationshipNote\n" else ""}你在朋友圈看到 ${moment.name} 发了动态：『${moment.content}』
$langNote
请自然地反应，选择以下一种或两种：
1. 点赞：输出【点赞】
2. 评论：输出【评论】评论内容${if (aiLang != "默认 (中文)" && requireTrans) "【翻译】中文翻译" else ""}
只选你觉得符合性格的，不必两个都做。评论语气必须符合你们的关系，不要超出关系范围。评论要简短自然，像真实朋友圈评论。
""".trimIndent()

                    try {
                        // 朋友圈配图（图片 + 视频首帧）也发给 LLM，让 AI 真正“看见”
                        val allMedia = parseMomentMediaList(moment.imageDesc)
                        val bodyJson = JSONObject().apply {
                            put("model", model)
                            put("temperature", 0.65)
                            put("messages", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    if (allMedia.isNotEmpty()) {
                                        val contentArray = JSONArray()
                                        contentArray.put(JSONObject().apply { put("type", "text"); put("text", sysPrompt) })
                                        for (m in allMedia) {
                                            try {
                                                val bytes = when (m.type) {
                                                    MomentMedia.Type.IMAGE -> openInputStreamSafe(context, m.path)?.readBytes()
                                                    MomentMedia.Type.VIDEO -> {
                                                        // 视频：首帧缩略图压缩 JPEG，别传几十MB的视频base64
                                                        val bitmap = getVideoThumbnail(Uri.fromFile(File(m.path)), context)
                                                        if (bitmap != null) {
                                                            val baos = java.io.ByteArrayOutputStream()
                                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, baos)
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
                                        put("content", contentArray)
                                    } else {
                                        put("content", sysPrompt)
                                    }
                                })
                            })
                        }
                        val req = Request.Builder().url(finalUrl)
                            .addHeader("Authorization", "Bearer $key")
                            .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                        val resp = Http.client.newBuilder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build().newCall(req).execute()
                        val rawReply = JSONObject(resp.body?.string() ?: continue)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()

                        val writeDb = DatabaseHelper(context).writableDatabase

                        // 处理点赞
                        if (rawReply.contains("【点赞】")) {

                            val exists = writeDb.rawQuery(
                                "SELECT id FROM Likes WHERE momentId=? AND userId=?",
                                arrayOf(
                                    moment.id.toString(),
                                    otherAiId
                                )
                            ).use {
                                it.moveToFirst()
                            }

                            if (!exists) {
                                val likeValues = ContentValues().apply {
                                    put("momentId", moment.id)
                                    put("userId", otherAiId)
                                    put("userName", otherAiName)
                                }

                                writeDb.insert("Likes", null, likeValues)
                            }
                        }

                        // 处理评论
                        val normalizedReply = rawReply.replace(
                            Regex("[\\[【]\\s*(评论翻译|翻译|译文|译)\\s*[\\]】]", RegexOption.IGNORE_CASE),
                            "【翻译】"
                        )
                        val cmtMatch = Regex("【评论】([\\s\\S]*?)(?=【(?:评论翻译|翻译|译文|译)】|【点赞】|$)").find(normalizedReply)
                        if (cmtMatch != null) {
                            var cmtStr = cmtMatch.groupValues[1].trim()
                            val cmtTrans = Regex("【(?:评论翻译|翻译|译文|译)】[：:]?\\s*([\\s\\S]*?)(?=【点赞】|$)", RegexOption.DOT_MATCHES_ALL)
                                .find(normalizedReply)?.groupValues?.get(1)
                                ?.replace(Regex("【[^】]*】"), "")
                                ?.trim() ?: ""
                            if (cmtStr.isNotEmpty()) {
                                val cmtValues = ContentValues().apply {
                                    put("momentId", moment.id)
                                    put("userId", otherAiId)
                                    put("userName", otherAiName)
                                    put("content", cmtStr)
                                    put("translatedText", cmtTrans)
                                    put("timestamp", System.currentTimeMillis())
                                    put("replyToId", 0)
                                    put("replyToName", "")
                                }
                                writeDb.insert("Comments", null, cmtValues)
                            }
                        }
                    } catch (e: Exception) {}
                    // 写完后刷新朋友圈UI
                    activity?.runOnUiThread {
                        loadMoments()
                    }
                }

                // 全部完成后刷新列表
                activity?.runOnUiThread {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        loadMoments()
                    }, 1000)
                }
            } catch (e: Exception) {}
        }.start()
    }
    private fun loadMoments() {
        // 不要先 clear 再慢慢填：AI 回复延迟到达时 Fragment 可能已 detach，
        // requireContext() 抛异常被吞掉后列表已清空但 adapter 未通知，
        // RecyclerView 一重绘就 IndexOutOfBounds 闪退。先建临时列表，最后原子替换。
        val ctx = context ?: return
        val newList = mutableListOf<Moment>()
        try {
            val db = DatabaseHelper(ctx).readableDatabase
            var myId = "my_id"
            var myName = "我"
            var myAvatarStr = ""
            try {
                val pCur = db.query("MyProfile", null, null, null, null, null, null)
                if (pCur.moveToFirst()) {
                    // ✅ 1. 安全获取自己的信息
                    myId = pCur.getSafeString("myId")
                    myName = pCur.getSafeString("myName").ifEmpty { "我" }
                    myAvatarStr = pCur.getSafeString("myAvatarUri")
                }
                pCur.close()
            } catch (e: Exception) {}

            val cursor = db.rawQuery("SELECT m.*, c.realName, c.avatarUri FROM Moments m LEFT JOIN Contacts c ON m.aiId = c.userId ORDER BY m.timestamp DESC", null)
            while (cursor.moveToNext()) {
                // ✅ 2. 核心循环：全员换成安全扩展函数，彻底干掉外层 try-catch
                val id = cursor.getSafeInt("id")
                val aiId = cursor.getSafeString("aiId")
                val content = cursor.getSafeString("content")
                val imageDesc = cursor.getSafeString("imageDesc")
                val timestamp = cursor.getSafeLong("timestamp")

                // ✅ 3. 新增的翻译文本字段，没有就返回空字符串，一行搞定
                val trans = cursor.getSafeString("translatedText")

                var name = "未知"
                var avatarUri = ""
                if (aiId == myId) {
                    name = myName
                    avatarUri = myAvatarStr
                } else {
                    // ✅ 4. 安全读取联表查出来的联系人名字和头像，绝不卡死
                    name = cursor.getSafeString("realName").ifEmpty { "未知" }
                    avatarUri = cursor.getSafeString("avatarUri")
                }

                var likesStr = ""
                try {
                    val lCur = db.rawQuery("SELECT userName FROM Likes WHERE momentId=?", arrayOf(id.toString()))
                    val lList = mutableListOf<String>()
                    while (lCur.moveToNext()) lList.add(lCur.getString(0))
                    lCur.close()
                    likesStr = lList.joinToString(", ")
                } catch (e: Exception) {}

                var cmtsStr = ""
                var transCmtsStr = ""
                try {
                    // ===== 修复：改用安全查询，replyToName 列不存在时不崩溃 =====
                    val cCur = db.rawQuery("SELECT userName, content, translatedText FROM Comments WHERE momentId=? ORDER BY timestamp ASC", arrayOf(id.toString()))
                    val cList = mutableListOf<String>()
                    val tcList = mutableListOf<String>()
                    while (cCur.moveToNext()) {
                        val cUser = cCur.getString(0)
                        val cRawContent = cCur.getString(1) ?: ""
                        var cContent = cRawContent.replace(Regex("（[^）]{0,500}）"), "").trim()
                        var cTrans = ""
                        try { cTrans = cCur.getString(2) ?: "" } catch (e: Exception) {}
                        // 清洗残留的翻译标签变体（如"【评论翻译：内容】"或裸标签），避免评论只显示标签没有正文
                        Regex("^【(?:评论翻译|私聊翻译|翻译|译文|译)[：:]\\s*([\\s\\S]+?)】?$").find(cContent)?.let {
                            if (cTrans.isEmpty()) cTrans = it.groupValues[1].trim()
                            cContent = ""
                        }
                        cContent = cContent.replace(Regex("【(?:评论翻译|私聊翻译|翻译|译文|译)】[：:]?"), "").trim()
                        if (cContent.isEmpty() && cTrans.isNotEmpty()) cContent = cTrans
                        // replyToName 单独查，列不存在时静默跳过；用数据库原始内容匹配
                        var replyToName = ""
                        try {
                            val rCur = db.rawQuery("SELECT replyToName FROM Comments WHERE momentId=? AND userName=? AND content=? LIMIT 1", arrayOf(id.toString(), cUser, cRawContent))
                            if (rCur.moveToFirst()) replyToName = rCur.getString(0) ?: ""
                            rCur.close()
                        } catch (e: Exception) {}
                        val prefix = if (replyToName.isNotEmpty()) "↩ 回复 $replyToName：" else ""
                        cList.add("$cUser: $prefix$cContent")
                        if (cTrans.isNotEmpty()) {
                            tcList.add("$cUser: $prefix$cTrans")
                        }
                    }
                    cCur.close()
                    cmtsStr = cList.joinToString("\n")
                    transCmtsStr = tcList.joinToString("\n")
                    // ===== 修复结束 ======
                } catch (e: Exception) {}
                newList.add(Moment(id, aiId, name, avatarUri, content, trans, imageDesc, timestamp, likesStr, cmtsStr, transCmtsStr))
            }
            cursor.close()
            activity?.runOnUiThread {
                momentList.clear()
                momentList.addAll(newList)
                adapter.notifyDataSetChanged()
            }
        } catch (e: Exception) {}
    }

    private fun generateAiMoment() {
        val sharedPref = requireContext().getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: ""
        val key = sharedPref.getString("apiKey", "") ?: ""
        val model = sharedPref.getString("modelName", "gemini-3.1-pro") ?: "gemini-3.1-pro"
        if (url.isEmpty() || key.isEmpty()) return
        Thread {
            try {
                val db = DatabaseHelper(requireContext()).readableDatabase
                var myId = "my_id"
                val pCur = db.query("MyProfile", null, null, null, null, null, null)
                if (pCur.moveToFirst()) {
                    // ✅ 1. 安全读取我自己的 ID
                    myId = pCur.getSafeString("myId")
                }
                pCur.close()

                // 按角色设置的朋友圈概率权重抽取。0% 表示不参与，默认 50%。
                data class MomentPoster(val id: String, val name: String, val persona: String, val weight: Int)
                val candidates = mutableListOf<MomentPoster>()
                db.rawQuery("SELECT userId, realName, identityInfo FROM Contacts WHERE userId != ?", arrayOf(myId)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val weight = sharedPref.getInt("momentPostProb_$id", 50).coerceIn(0, 100)
                        if (weight > 0) {
                            candidates.add(
                                MomentPoster(
                                    id,
                                    (cursor.getString(1) ?: "").ifEmpty { "神秘人" },
                                    cursor.getString(2) ?: "",
                                    weight
                                )
                            )
                        }
                    }
                }
                val totalWeight = candidates.sumOf { it.weight }
                if (totalWeight <= 0) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "所有角色的发朋友圈概率都为 0%", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                var draw = (1..totalWeight).random()
                val poster = candidates.first { candidate ->
                    draw -= candidate.weight
                    draw <= 0
                }

// ✅ 2. 安全读取按权重抽中的 AI 信息
                val aiId = poster.id
                val aiName = poster.name
                val aiPersona = poster.persona

                // ===== 新增：读取小窗记忆 =====
                // 读取记忆（取最近15条，防止token超限）
                var cyberMemory = ""
                try {
                    val memCursor = db.rawQuery(
                        "SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 15",
                        arrayOf(aiId)
                    )
                    val sb = StringBuilder()
                    while (memCursor.moveToNext()) {
                        sb.append(memCursor.getString(0)).append("\n")
                    }
                    memCursor.close()
                    cyberMemory = sb.toString().trim()
                } catch (e: Exception) {}
                // ===== 新增结束 =====

                // ===== 新增：当前时间和时段描述 =====
                val nowTime = java.text.SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeContext = when (hour) {
                    in 5..8 -> "清晨，大多数人刚起床或准备出门"
                    in 9..11 -> "上午，正常工作学习时间"
                    in 12..13 -> "中午，午饭或午休时间"
                    in 14..16 -> "下午，正常活动时间"
                    in 17..18 -> "傍晚，下班放学时间"
                    in 19..21 -> "晚上，大多数人在休息或娱乐"
                    in 22..23 -> "深夜，大多数人已入睡或准备睡觉"
                    else -> "凌晨，绝大多数人在睡觉"
                }
                // ===== 新增结束 =====

                var finalUrl = url
                while (finalUrl.endsWith("/")) finalUrl = finalUrl.substring(0, finalUrl.length - 1)
                if (!finalUrl.endsWith("/chat/completions")) finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                val requireTranslation = sharedPref.getBoolean("autoTrans_$aiId", false)

                // ===== 修复：统一使用【翻译】标签格式 =====
                var langPrompt = ""
                if (aiLang != "默认 (中文)") {
                    langPrompt = if (requireTranslation)
                        "【最高语言指令：正文必须100%纯${aiLang}！绝不出现汉字！需在正文末尾加【翻译】中文翻译。】"
                    else
                        "【语言指令：纯用${aiLang}！绝不准出现中文！】"
                }
                // ===== 修复结束 =====

                // ===== 修改：sysPrompt 加入时间和记忆 =====
                val memoryLine = if (cyberMemory.isNotEmpty()) "\n【关于她的记忆】：$cyberMemory" else ""
                val sysPrompt = "你现在是 $aiName，人设：$aiPersona。$memoryLine\n【当前时间】：$nowTime（$timeContext）\n请根据当前时间和你的人设，发一条活人的微信朋友圈动态。绝不准提你是AI。必须符合人设和时间氛围。$langPrompt\n格式：根据内容思考是否需要配图，配图不是必须有，如果有配图，必须在最后另起一行严格写【图片：画面描述】。"
                // ===== 修改结束 =====

                val jsonBody = JSONObject()
                jsonBody.put("model", model)
                jsonBody.put("temperature", 0.7)
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", sysPrompt)
                })
                jsonBody.put("messages", messagesArray)
                val request = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                val client = Http.client.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            try {
                                val reply = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                                var textContent = reply
                                var imageDesc = ""
                                var translatedText = ""
                                if (textContent.contains("【图片：")) {
                                    val parts = textContent.split("【图片：")
                                    textContent = parts[0].trim()
                                    imageDesc = parts[1].replace("】", "").trim()
                                }
                                // ===== 修复：使用【翻译】标签解析，不再用|翻译: =====
                                val transTagMatch = Regex("【(?:翻译|译)】[：:]?\\s*([\\s\\S]+)$", RegexOption.DOT_MATCHES_ALL).find(textContent)
                                if (transTagMatch != null) {
                                    translatedText = transTagMatch.groupValues[1].trim()
                                    textContent = textContent.substring(0, transTagMatch.range.first).trim()
                                }
                                // ===== 修复结束 =====
                                val writeDb = DatabaseHelper(requireContext()).writableDatabase
                                val values = ContentValues().apply {
                                    put("aiId", aiId)
                                    put("content", textContent)
                                    put("translatedText", translatedText)
                                    put("imageDesc", imageDesc)
                                    put("timestamp", System.currentTimeMillis())
                                }
                                val newMomentId = writeDb.insert("Moments", null, values)

                                // 如果有图片描述且开启了生图，自动生成真实图片替换文字描述
                                val imgEnabled = requireContext().getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                                    .getBoolean("imgEnable_moments", false)
                                if (imageDesc.isNotEmpty() && imgEnabled) {
                                    Thread {
                                        try {
                                            var appearance = ""
                                            try {
                                                db.rawQuery("SELECT appearance FROM Contacts WHERE userId=?", arrayOf(aiId)).use { c ->
                                                    if (c.moveToFirst()) appearance = c.getString(0) ?: ""
                                                }
                                            } catch (_: Exception) {}

                                            val appearanceHint = if (appearance.isNotEmpty()) "Character appearance: $appearance. " else ""
                                            val fullPrompt = "$appearanceHint$imageDesc"
                                            val localPath = ImageGenManager.generate(requireContext(), fullPrompt)
                                            if (localPath != null) {
                                                val cleanPath = if (localPath.startsWith("[REAL_IMG]")) localPath.removePrefix("[REAL_IMG]") else localPath
                                                val savedPath = "[REAL_IMG]$cleanPath"
                                                val freshDb = DatabaseHelper(requireContext()).writableDatabase
                                                freshDb.execSQL(
                                                    "UPDATE Moments SET imageDesc=? WHERE id=?",
                                                    arrayOf(savedPath, newMomentId)
                                                )
                                                activity?.runOnUiThread { loadMoments() }
                                            }
                                        } catch (_: Exception) {}
                                    }.start()
                                }

// 让其他 AI 来评论/点赞
                                val newMoment = Moment(
                                    id = newMomentId.toInt(),
                                    aiId = aiId,
                                    name = aiName,
                                    avatarUri = "",
                                    content = textContent,
                                    translatedText = translatedText,
                                    imageDesc = imageDesc,
                                    timestamp = System.currentTimeMillis()
                                )
                                triggerOtherAiReactions(requireContext(), newMoment, aiId)

                                activity?.runOnUiThread {
                                    Toast.makeText(requireContext(), "🎉 $aiName 发了一条新动态！", Toast.LENGTH_LONG).show()
                                    loadMoments()
                                }
                            } catch (e: Exception) {}
                        }
                    }
                })
            } catch (e: Exception) {}
        }.start()
    }
}
