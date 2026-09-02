package com.moon.aiphone

import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Moment(
    val id: Int, val aiId: String, val name: String, val avatarUri: String,
    val content: String, val translatedText: String, val imageDesc: String,
    val timestamp: Long, var likes: String = "", var comments: String = "",
    var translatedComments: String = ""
)

class MomentAdapter(private val momentList: List<Moment>, private val onDataChanged: () -> Unit) : RecyclerView.Adapter<MomentAdapter.ViewHolder>() {

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = momentList[position].id.toLong()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivMomentAvatar)
        val tvName: TextView = view.findViewById(R.id.tvMomentName)
        val tvContent: TextView = view.findViewById(R.id.tvMomentContent)
        val tvTranslateBtn: TextView = view.findViewById(R.id.tvMomentTranslateBtn)
        val tvTranslatedText: TextView = view.findViewById(R.id.tvMomentTranslatedText)
        val ivRealImage: ImageView = view.findViewById(R.id.ivMomentRealImage)
        val rvImages: RecyclerView = view.findViewById(R.id.rvMomentImages)
        val tvImage: TextView = view.findViewById(R.id.tvMomentImage)
        val tvTime: TextView = view.findViewById(R.id.tvMomentTime)
        val btnLike: TextView = view.findViewById(R.id.btnMomentLike)
        val btnComment: TextView = view.findViewById(R.id.btnMomentComment)
        val layoutInteraction: LinearLayout = view.findViewById(R.id.layoutInteraction)
        val tvLikes: TextView = view.findViewById(R.id.tvMomentLikes)
        val tvComments: TextView = view.findViewById(R.id.tvMomentComments)
        val tvCommentsTransBtn: TextView = view.findViewById(R.id.tvMomentCommentsTransBtn)
        // 单张视频用的控件
        val layoutSingle: FrameLayout = view.findViewById(R.id.layoutMomentSingleMedia)
        val ivSinglePlay: ImageView = view.findViewById(R.id.ivMomentRealImagePlay)
        val tvSingleDuration: TextView = view.findViewById(R.id.tvMomentRealImageDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_moment, parent, false))

    override fun getItemCount() = momentList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val moment = momentList[position]

        // ✅【核心修复 1】：把丢失的名字亲手交还给界面！
        holder.tvName.text = moment.name.ifEmpty { "神秘人" }
        holder.tvContent.text = moment.content

        val hasTrans = moment.translatedText.isNotEmpty() && moment.translatedText != moment.content
        if (hasTrans) {
            holder.tvTranslateBtn.visibility = View.VISIBLE
            holder.tvTranslatedText.text = moment.translatedText
            holder.tvTranslatedText.setTextColor(android.graphics.Color.parseColor("#666666"))
            holder.tvTranslatedText.visibility = View.GONE
            holder.tvTranslateBtn.text = "查看翻译"
            holder.tvTranslateBtn.setOnClickListener {
                holder.tvTranslatedText.visibility =
                    if (holder.tvTranslatedText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                holder.tvTranslateBtn.text =
                    if (holder.tvTranslatedText.visibility == View.VISIBLE) "收起翻译" else "查看翻译"
            }
        } else {
            holder.tvTranslateBtn.visibility = View.GONE
            holder.tvTranslatedText.visibility = View.GONE
        }

        // 图片/视频渲染：0 个隐藏；1 个大图（视频=首帧+播放按钮）；2-9 个九宫格；非 [REAL_IMG] 走 AI 文字描述
        val allMedia = parseMomentMediaList(moment.imageDesc)
        if (allMedia.isNotEmpty()) {
            holder.tvImage.visibility = View.GONE
            if (allMedia.size == 1) {
                val only = allMedia[0]
                // 单张：保留原大图样式（视频展示首帧+播放按钮+时长）
                holder.rvImages.visibility = View.GONE
                holder.layoutSingle.visibility = View.VISIBLE
                holder.ivSinglePlay.visibility = View.GONE
                holder.tvSingleDuration.visibility = View.GONE
                when (only.type) {
                    MomentMedia.Type.IMAGE -> {
                        try {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(only.path)
                            if (bitmap != null) holder.ivRealImage.setImageBitmap(bitmap)
                            else holder.ivRealImage.setBackgroundColor(android.graphics.Color.LTGRAY)
                        } catch (e: Exception) {
                            holder.ivRealImage.setBackgroundColor(android.graphics.Color.LTGRAY)
                        }
                        holder.ivRealImage.setOnClickListener { openImageViewer(holder.itemView.context, only.path) }
                    }
                    MomentMedia.Type.VIDEO -> {
                        val ctx = holder.itemView.context
                        try {
                            val thumb = getVideoThumbnail(Uri.fromFile(File(only.path)), ctx)
                            if (thumb != null) holder.ivRealImage.setImageBitmap(thumb)
                            else holder.ivRealImage.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                            val sec = getVideoDurationSec(Uri.fromFile(File(only.path)), ctx)
                            if (sec > 0) {
                                holder.tvSingleDuration.text = formatDuration(sec)
                                holder.tvSingleDuration.visibility = View.VISIBLE
                            }
                            holder.ivSinglePlay.visibility = View.VISIBLE
                        } catch (e: Exception) {
                            holder.ivRealImage.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                        }
                        holder.ivRealImage.setOnClickListener { openVideoPlayer(holder.itemView.context, only.path) }
                    }
                }
            } else {
                // 多张：九宫格
                holder.layoutSingle.visibility = View.GONE
                holder.rvImages.visibility = View.VISIBLE
                val spanCount = if (allMedia.size <= 4) 2 else 3
                val ctx = holder.itemView.context
                // 动态测量每个格子的边长（让九宫格是正方形），根据父宽/spanCount
                holder.rvImages.post {
                    val parentWidth = (holder.rvImages.parent as? View)?.width ?: 0
                    if (parentWidth > 0) {
                        val sizePerItem = (parentWidth - (spanCount + 1) * 4.dpToPx(ctx)) / spanCount
                        val lm = GridLayoutManager(ctx, spanCount)
                        holder.rvImages.layoutManager = lm
                        holder.rvImages.adapter = MomentGridImageAdapter(allMedia, sizePerItem)
                    } else {
                        holder.rvImages.layoutManager = GridLayoutManager(ctx, spanCount)
                        holder.rvImages.adapter = MomentGridImageAdapter(allMedia, 0)
                    }
                }
            }
        } else if (moment.imageDesc.isNotEmpty()) {
            // AI 生成的纯文字画面描述
            holder.layoutSingle.visibility = View.GONE
            holder.rvImages.visibility = View.GONE
            holder.tvImage.visibility = View.VISIBLE
            holder.tvImage.text = "🖼️ [图片] ${moment.imageDesc}"
        } else {
            holder.tvImage.visibility = View.GONE
            holder.layoutSingle.visibility = View.GONE
            holder.rvImages.visibility = View.GONE
        }

        holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(moment.timestamp))

        // ✅【核心修复 2】：剔除 onBind 内部的耗时数据库查询，直接利用传入的绑好的数据渲染头像，丝滑流畅！
        holder.ivAvatar.setImageBitmap(null)
        holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
        if (moment.avatarUri.isNotEmpty()) {
            try {
                val bitmap = if (moment.avatarUri.startsWith("/")) {
                    android.graphics.BitmapFactory.decodeFile(moment.avatarUri)
                } else {
                    holder.itemView.context.contentResolver.openInputStream(Uri.parse(moment.avatarUri))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
                if (bitmap != null) holder.ivAvatar.setImageBitmap(bitmap)
            } catch (e: Exception) {
                holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
            }
        }

        var myName = "我"
        var myId = "my_id"
        try {
            val db = DatabaseHelper(holder.itemView.context).readableDatabase
            val cur = db.query("MyProfile", null, null, null, null, null, null)
            if (cur.moveToFirst()) {
                val savedName = cur.getSafeString("myName")
                if (savedName.isNotEmpty()) myName = savedName
                val savedId = cur.getSafeString("myId")
                if (savedId.isNotEmpty()) myId = savedId
            }
            cur.close()
        } catch (e: Exception) {}

        var rawCtx: Context = holder.itemView.context
        while (rawCtx is android.content.ContextWrapper && rawCtx !is android.app.Activity) {
            rawCtx = rawCtx.baseContext
        }
        val safeContext: Context = rawCtx

// 点赞和评论区域
        holder.layoutInteraction.visibility =
            if (moment.likes.isNotEmpty() || moment.comments.isNotEmpty())
                View.VISIBLE
            else
                View.GONE

        if (moment.likes.isNotEmpty()) {
            holder.tvLikes.visibility = View.VISIBLE

            val iLiked = try {
                val db = DatabaseHelper(holder.itemView.context).readableDatabase
                val cur = db.rawQuery(
                    "SELECT id FROM Likes WHERE momentId=? AND userId=?",
                    arrayOf(moment.id.toString(), myId)
                )
                val result = cur.moveToFirst()
                cur.close()
                result
            } catch (e: Exception) {
                false
            }

            holder.tvLikes.text = "${if (iLiked) "❤️" else "🤍"} ${moment.likes}"
        } else {
            holder.tvLikes.visibility = View.GONE
        }

        holder.tvComments.text = moment.comments
        holder.tvComments.visibility =
            if (moment.comments.isNotEmpty()) View.VISIBLE else View.GONE

        if (moment.translatedComments.isNotEmpty()) {
            holder.tvCommentsTransBtn.visibility = View.VISIBLE
            holder.tvCommentsTransBtn.text = "查看评论翻译"
            holder.tvCommentsTransBtn.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                val m = momentList[pos]
                if (holder.tvCommentsTransBtn.text == "查看评论翻译") {
                    holder.tvComments.text = m.translatedComments
                    holder.tvCommentsTransBtn.text = "收起评论翻译"
                } else {
                    holder.tvComments.text = m.comments
                    holder.tvCommentsTransBtn.text = "查看评论翻译"
                }
            }
        } else {
            holder.tvCommentsTransBtn.visibility = View.GONE
        }

// 点赞按钮
        holder.btnLike.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener

            val currentMoment = momentList[currentPos]

            try {
                val db = DatabaseHelper(holder.itemView.context).writableDatabase
                val checkCur = db.rawQuery(
                    "SELECT id FROM Likes WHERE momentId=? AND userId=?",
                    arrayOf(currentMoment.id.toString(), myId)
                )
                val alreadyLiked = checkCur.moveToFirst()
                checkCur.close()

                if (alreadyLiked) {
                    db.delete(
                        "Likes",
                        "momentId=? AND userId=?",
                        arrayOf(currentMoment.id.toString(), myId)
                    )

                    currentMoment.likes = currentMoment.likes
                        .split(", ")
                        .filter { it.isNotBlank() && it != myName }
                        .joinToString(", ")
                } else {
                    val values = ContentValues().apply {
                        put("momentId", currentMoment.id)
                        put("userId", myId)
                        put("userName", myName)
                    }

                    db.insert("Likes", null, values)

                    currentMoment.likes =
                        if (currentMoment.likes.isEmpty()) myName
                        else "${currentMoment.likes}, $myName"
                }

                notifyItemChanged(currentPos)
                onDataChanged()
            } catch (e: Exception) {
                android.util.Log.e("MOMENT_LIKE_ERROR", e.stackTraceToString())
            }
        }

// 评论按钮
        holder.btnComment.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener

            val currentMoment = momentList[currentPos]
            if (safeContext !is android.app.Activity) return@setOnClickListener
            val commentActivity = safeContext as android.app.Activity
            if (commentActivity.isFinishing || commentActivity.isDestroyed) return@setOnClickListener

            try {
                DatabaseHelper(safeContext).writableDatabase.execSQL(
                    "ALTER TABLE Comments ADD COLUMN replyToId INTEGER DEFAULT 0"
                )
            } catch (_: Exception) {}

            try {
                DatabaseHelper(safeContext).writableDatabase.execSQL(
                    "ALTER TABLE Comments ADD COLUMN replyToName TEXT DEFAULT ''"
                )
            } catch (_: Exception) {}

            val input = EditText(safeContext)

            AlertDialog.Builder(safeContext)
                .setTitle("评论 ${currentMoment.name} 的动态")
                .setView(input)
                .setPositiveButton("发送") { _, _ ->
                    val text = input.text.toString().trim()

                    if (text.isNotEmpty()) {
                        try {
                            val db = DatabaseHelper(safeContext).writableDatabase

                            val values = ContentValues().apply {
                                put("momentId", currentMoment.id)
                                put("userId", myId)
                                put("userName", myName)
                                put("content", text)
                                put("translatedText", "")
                                put("timestamp", System.currentTimeMillis())
                                put("isReplied", 0)
                                put("replyToId", 0)
                                put("replyToName", "")
                            }

                            val newCommentId = db.insert("Comments", null, values)

                            val memValues = ContentValues().apply {
                                put("aiId", currentMoment.aiId)
                                put(
                                    "memoryText",
                                    "[系统提示：在动态『${currentMoment.content}』中，$myName 刚评论：『$text』。]"
                                )
                                put("category", "shared_event")
                                put("insertTime", System.currentTimeMillis())
                            }

                            db.insert("MemoryBank", null, memValues)

                            if (currentMoment.aiId.isNotEmpty()) {
                                triggerAiReaction(
                                    safeContext,
                                    currentMoment,
                                    myName,
                                    text,
                                    newCommentId
                                )
                            }

                            onDataChanged()
                        } catch (e: Exception) {
                            Toast.makeText(
                                safeContext,
                                "❌ 崩溃：${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

// 长按评论管理
        holder.tvComments.setOnLongClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener true

            val currentMoment = momentList[currentPos]
            if (safeContext !is android.app.Activity) return@setOnLongClickListener true
            val longPressActivity = safeContext as android.app.Activity
            if (longPressActivity.isFinishing || longPressActivity.isDestroyed) return@setOnLongClickListener true

            try {
                val db = DatabaseHelper(safeContext).readableDatabase
                val cursor = db.query(
                    "Comments",
                    null,
                    "momentId=?",
                    arrayOf(currentMoment.id.toString()),
                    null,
                    null,
                    "timestamp ASC"
                )

                val commentIds = mutableListOf<Int>()
                val commentTexts = mutableListOf<String>()
                val commentUserNames = mutableListOf<String>()

                while (cursor.moveToNext()) {
                    commentIds.add(cursor.getSafeInt("id"))

                    val uName = cursor.getSafeString("userName").ifEmpty { "匿名用户" }
                    val cContent = cursor.getSafeString("content")

                    commentUserNames.add(uName)
                    commentTexts.add("$uName: $cContent")
                }

                cursor.close()

                if (commentTexts.isNotEmpty()) {
                    AlertDialog.Builder(safeContext)
                        .setTitle("选择一条评论")
                        .setItems(commentTexts.toTypedArray()) { _, which ->
                            val selectedId = commentIds[which]
                            val selectedUserName = commentUserNames[which]
                            val selectedText = commentTexts[which]

                            AlertDialog.Builder(safeContext)
                                .setTitle("对这条评论做什么？")
                                .setItems(arrayOf("🗑️ 删除", "↩ 回复")) { _, action ->
                                    when (action) {
                                        0 -> {
                                            try {
                                                DatabaseHelper(safeContext).writableDatabase.delete(
                                                    "Comments",
                                                    "id=?",
                                                    arrayOf(selectedId.toString())
                                                )

                                                Toast.makeText(
                                                    safeContext,
                                                    "🩸 蒸发了",
                                                    Toast.LENGTH_SHORT
                                                ).show()

                                                onDataChanged()
                                            } catch (_: Exception) {}
                                        }

                                        1 -> {
                                            val replyInput = EditText(safeContext)
                                            replyInput.hint = "回复 $selectedUserName..."

                                            AlertDialog.Builder(safeContext)
                                                .setTitle("↩ 回复 $selectedUserName")
                                                .setView(replyInput)
                                                .setPositiveButton("发送") { _, _ ->
                                                    val replyText =
                                                        replyInput.text.toString().trim()

                                                    if (replyText.isNotEmpty()) {
                                                        try {
                                                            val writeDb =
                                                                DatabaseHelper(safeContext).writableDatabase

                                                            val replyValues =
                                                                ContentValues().apply {
                                                                    put("momentId", currentMoment.id)
                                                                    put("userId", myId)
                                                                    put("userName", myName)
                                                                    put("content", replyText)
                                                                    put("translatedText", "")
                                                                    put("timestamp", System.currentTimeMillis())
                                                                    put("isReplied", 0)
                                                                    put("replyToId", selectedId)
                                                                    put("replyToName", selectedUserName)
                                                                }

                                                            val newReplyId =
                                                                writeDb.insert(
                                                                    "Comments",
                                                                    null,
                                                                    replyValues
                                                                )

                                                            if (
                                                                currentMoment.aiId.isNotEmpty()
                                                                && selectedUserName != myName
                                                            ) {
                                                                val replyContext =
                                                                    "（$myName 回复了你的评论『$selectedText』）：$replyText"

                                                                triggerAiReaction(
                                                                    safeContext,
                                                                    currentMoment,
                                                                    myName,
                                                                    replyContext,
                                                                    newReplyId
                                                                )
                                                            }

                                                            onDataChanged()
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                                .setNegativeButton("取消", null)
                                                .show()
                                        }
                                    }
                                }
                                .setNegativeButton("放过", null)
                                .show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (_: Exception) {}

            true
        }

        holder.itemView.setOnLongClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener true

            val currentMoment = momentList[currentPos]

            if (safeContext !is android.app.Activity)
                return@setOnLongClickListener true
            if (safeContext !is android.app.Activity) return@setOnLongClickListener true

            AlertDialog.Builder(safeContext).setTitle("⚠️ 赛博执法").setMessage("要超度吗？")
                .setPositiveButton("物理超度！") { _, _ ->
                    try {
                        val db = DatabaseHelper(safeContext).writableDatabase
                        db.delete("Moments", "id=?", arrayOf(currentMoment.id.toString()))
                        db.delete("Likes", "momentId=?", arrayOf(currentMoment.id.toString()))
                        db.delete("Comments", "momentId=?", arrayOf(currentMoment.id.toString()))
                        Toast.makeText(safeContext, "🗑️ 骨灰扬了", Toast.LENGTH_SHORT).show()
                        onDataChanged()
                    } catch (e: Exception) {}
                }.setNegativeButton("留它一命", null).show()
            true
        }
    }

    private fun splitTextAndTranslation(raw: String): Pair<String, String> {
        var text = raw.trim()
            .replace(Regex("[\\[【]\\s*(评论翻译|私聊翻译|翻译|译文|译)\\s*[\\]】]", RegexOption.IGNORE_CASE), "【翻译】")
        var trans = ""
        val tagMatch = Regex("【(?:评论翻译|私聊翻译|翻译|译文|译)】[：:]?\\s*([\\s\\S]*?)(?=\\s*<\\|SPLIT\\|>|\\s*【(?:评论|私聊|点赞)】|\\s*$)").find(text)
        if (tagMatch != null) {
            trans = tagMatch.groupValues[1]
                .replace(Regex("【[^】]*】"), "")
                .trim()
            text = text.substring(0, tagMatch.range.first).trim()
            text = text.replace(Regex("【(?:评论|私聊)】[：:]?"), "").trim()
            return Pair(text, trans)
        }
        val bracketMatch = Regex("[（(]([^（）()]{4,500})[）)]\\s*$").find(text)
        if (bracketMatch != null) {
            trans = bracketMatch.groupValues[1].trim()
            text = text.removeRange(bracketMatch.range).trim()
        }
        return Pair(text, trans)
    }

    // ✅【核心修复 3】：替你完美补齐由于刚才输入长度限制而被截断的 AI 自动评论生成逻辑
    private fun triggerAiReaction(context: Context, moment: Moment, myName: String, myComment: String, commentId: Long) {
        val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: ""
        val key = sharedPref.getString("apiKey", "") ?: ""
        val model = sharedPref.getString(
            "modelName",
            ""
        )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
        val mainHandler = Handler(Looper.getMainLooper())
        fun notifyReactionFailure(message: String) {
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
        if (url.isEmpty() || key.isEmpty()) {
            notifyReactionFailure("AI\u56de\u5e94\u6682\u4e0d\u53ef\u7528")
            return
        }
        mainHandler.post { Toast.makeText(context, "\u8fde\u7ebf\u4e2d...", Toast.LENGTH_SHORT).show() }
        Thread {
            try {
                val db = DatabaseHelper(context).readableDatabase
                var aiName = "未知"
                var aiPersona = ""
                var myId = "my_id"
                try {
                    db.rawQuery("SELECT myId FROM MyProfile LIMIT 1", null).use { c ->
                        if (c.moveToFirst()) myId = c.getString(0) ?: "my_id"
                    }
                } catch (_: Exception) {}

                val isUserMoment = moment.aiId == myId
                val targetAiId = if (isUserMoment) {
                    val allAi = db.rawQuery(
                        """
    SELECT userId
    FROM Contacts
    WHERE userId IS NOT NULL
      AND TRIM(userId) <> ''
      AND id IN (
          SELECT MAX(id)
          FROM Contacts
          WHERE userId IS NOT NULL
            AND TRIM(userId) <> ''
          GROUP BY userId
      )
    ORDER BY RANDOM()
    LIMIT 5
    """.trimIndent(),
                        null
                    )
                    val ids = mutableListOf<String>()
                    while (allAi.moveToNext()) ids.add(allAi.getString(0))
                    allAi.close()
                    ids.randomOrNull() ?: moment.aiId
                } else moment.aiId

                val cursor = db.rawQuery(
                    """
    SELECT *
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(targetAiId)
                )
                if (cursor.moveToFirst()) {
                    aiName = cursor.getSafeString("realName").ifEmpty { "AI助手" }
                    aiPersona = cursor.getSafeString("identityInfo")
                }
                cursor.close()

                var cyberMemory = ""
                try {
                    db.rawQuery("SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 15", arrayOf(targetAiId)).use { c ->
                        val sb = StringBuilder()
                        while (c.moveToNext()) sb.append(c.getString(0)).append("\n")
                        cyberMemory = sb.toString().trim()
                    }
                } catch (e: Exception) {}

                var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                if (!finalUrl.endsWith("/chat/completions")) finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
                val aiLang = sharedPref.getString("aiLang_$targetAiId", "默认 (中文)") ?: "默认 (中文)"
                val requireTrans = sharedPref.getBoolean("autoTrans_$targetAiId", false)

                val langNote = if (aiLang != "默认 (中文)") "【语言规则：评论和私聊内容必须用${aiLang}，禁止出现中文${if (requireTrans) "，每段内容末尾加【翻译】中文翻译" else "" }】" else ""

                // 查询 AI 与发帖者之间的关系图谱
                val relNote: String = if (!isUserMoment) {
                    // targetAiId 在回应 moment.aiId 发的动态
                    val charRel = getCharacterRelationshipText(db, targetAiId, moment.aiId)
                    if (charRel != null) "【你和${moment.name}的关系】：$charRel（严格遵守，不得超出关系范围做任何暧昧或情感行为）\n" else ""
                } else ""

                val momentCtx = if (isUserMoment)
                    "$myName 发了朋友圈『${moment.content}』，$myName 刚评论：『$myComment』"
                else
                    "你发了朋友圈『${moment.content}』，$myName 刚评论：『$myComment』"
                val sysPrompt = "你是 $aiName，人设：$aiPersona。\n【核心记忆】：$cyberMemory\n${relNote}$momentCtx。请立刻反应！\n$langNote\n【规则】：\n1. 必须回评论，严格格式：【评论】评论内容${if (aiLang != "默认 (中文)" && requireTrans) "【翻译】中文翻译" else ""}\n2. 可选额外私聊，严格格式：【私聊】私聊内容${if (aiLang != "默认 (中文)" && requireTrans) "【翻译】中文翻译" else ""}\n3. 【评论】和【私聊】必须分开，翻译只跟在各自内容后面，不要混淆。\n4. 严禁任何动作描写、*号动作、括号动作，只输出说出口的话。\n5. 评论要简短自然，不超过20字。评论语气必须严格符合你们的关系设定，不要超出关系范围。"

                // 把朋友圈配图（多张）也发给 LLM，让 AI 真正“看见”图片再决定怎么回复评论
                val imagePaths = parseMomentImagePaths(moment.imageDesc)
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.85)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            if (imagePaths.isNotEmpty()) {
                                val contentArray = JSONArray()
                                contentArray.put(JSONObject().apply { put("type", "text"); put("text", sysPrompt) })
                                for (path in imagePaths) {
                                    try {
                                        val inputStream = if (path.startsWith("/")) {
                                            java.io.FileInputStream(path)
                                        } else {
                                            context.contentResolver.openInputStream(android.net.Uri.parse(path))
                                        }
                                        val bytes = inputStream?.readBytes(); inputStream?.close()
                                        if (bytes != null) {
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
                val req = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                val client = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()

                if (!resp.isSuccessful || body == null) {
                    android.util.Log.e(
                        "AI_REACTION_ERROR",
                        "API失败 code=${resp.code} body=${body ?: ""}"
                    )
                    notifyReactionFailure("AI\u56de\u5e94\u5931\u8d25")
                    return@Thread
                }

                val rawReply = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                var cmtStr = ""
                var dmStr = ""
                val hasCmt = rawReply.contains("【评论】")
                val hasDm = rawReply.contains("【私聊】")
                if (hasCmt && hasDm) {
                    if (rawReply.indexOf("【评论】") < rawReply.indexOf("【私聊】")) {
                        cmtStr = rawReply.substringAfter("【评论】").substringBefore("【私聊】").trim()
                        dmStr = rawReply.substringAfter("【私聊】").trim()
                    } else {
                        dmStr = rawReply.substringAfter("【私聊】").substringBefore("【评论】").trim()
                        cmtStr = rawReply.substringAfter("【评论】").trim()
                    }
                } else if (hasDm) {
                    dmStr = rawReply.substringAfter("【私聊】").trim()
                } else {
                    cmtStr = rawReply.substringAfter("【评论】").trim().ifEmpty { rawReply.trim() }
                }

                var cmtTrans = ""
                var dmTrans = ""
                val cmtPair = splitTextAndTranslation(cmtStr)
                cmtStr = cmtPair.first
                cmtTrans = cmtPair.second

                val dmPair = splitTextAndTranslation(dmStr)
                dmStr = dmPair.first
                dmTrans = dmPair.second

                val writeDb = DatabaseHelper(context).writableDatabase

                // 完美复活：将 AI 触发的二级回复写回数据库并刷新
                if (cmtStr.isNotEmpty()) {
                    val replyValues = ContentValues().apply {
                        put("momentId", moment.id)
                        put("userId", targetAiId)
                        put("userName", aiName)
                        put("content", cmtStr)
                        put("translatedText", cmtTrans)
                        put("timestamp", System.currentTimeMillis())
                        put("isReplied", 1)
                        put("replyToId", commentId)
                        put("replyToName", myName)
                    }
                    writeDb.insert("Comments", null, replyValues)
                    mainHandler.post { onDataChanged() }
                }
            } catch (e: Exception) {
                android.util.Log.e("AI_REACTION_ERROR", e.stackTraceToString())
                notifyReactionFailure("AI\u56de\u5e94\u5931\u8d25")
            }
        }.start()
    }
}

// —— 打开图片查看器（系统 Intent） ——
private fun openImageViewer(ctx: Context, path: String) {
    try {
        val f = File(path)
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            f
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(ctx.packageManager) != null) ctx.startActivity(intent)
    } catch (e: Exception) {
        // 没 FileProvider 的时候兜底 ACTION_VIEW 直接用
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(File(path)), "image/*")
            }
            ctx.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(ctx, "无法打开图片：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// —— 打开视频播放器（系统 Intent） ——
private fun openVideoPlayer(ctx: Context, path: String) {
    try {
        val f = File(path)
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            f
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(ctx.packageManager) != null) {
            ctx.startActivity(intent)
            return
        }
    } catch (e: Exception) {}
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(File(path)), "video/*")
        }
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "无法打开视频：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// —— 一些扩展工具 ——
private fun Int.dpToPx(ctx: Context): Int =
    (this * ctx.resources.displayMetrics.density).toInt()

/** 解析朋友圈 imageDesc 字段为真实图片路径列表（兼容旧数据）—— 只返回图片，不给视频 */
fun parseMomentImagePaths(imageDesc: String): List<String> =
    parseMomentMediaList(imageDesc).filter { it.type == MomentMedia.Type.IMAGE }.map { it.path }

/** 朋友圈列表里的多图九宫格 Adapter：支持图+视频混排，点击跳播放器/查看器 */
class MomentGridImageAdapter(
    private val items: List<MomentMedia>,
    private val itemSizePx: Int
) : RecyclerView.Adapter<MomentGridImageAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val iv: ImageView = view.findViewById(R.id.ivGridImage)
        val ivPlay: ImageView = view.findViewById(R.id.ivGridPlay)
        val tvDuration: TextView = view.findViewById(R.id.tvGridDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_moment_image, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.itemView.context
        // 正方形尺寸（如果传了就强制）
        if (itemSizePx > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams?.apply {
                width = itemSizePx
                height = itemSizePx
            }
            holder.iv.layoutParams = holder.iv.layoutParams?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        val m = items[position]
        holder.ivPlay.visibility = View.GONE
        holder.tvDuration.visibility = View.GONE
        when (m.type) {
            MomentMedia.Type.IMAGE -> {
                try {
                    val bm = android.graphics.BitmapFactory.decodeFile(m.path)
                    if (bm != null) holder.iv.setImageBitmap(bm)
                    else holder.iv.setBackgroundColor(android.graphics.Color.LTGRAY)
                } catch (e: Exception) { holder.iv.setBackgroundColor(android.graphics.Color.LTGRAY) }
                holder.itemView.setOnClickListener { openImageViewer(ctx, m.path) }
            }
            MomentMedia.Type.VIDEO -> {
                try {
                    val thumb = getVideoThumbnail(Uri.fromFile(File(m.path)), ctx)
                    if (thumb != null) holder.iv.setImageBitmap(thumb)
                    else holder.iv.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                    val sec = getVideoDurationSec(Uri.fromFile(File(m.path)), ctx)
                    if (sec > 0) {
                        holder.tvDuration.text = formatDuration(sec)
                        holder.tvDuration.visibility = View.VISIBLE
                    }
                    holder.ivPlay.visibility = View.VISIBLE
                } catch (e: Exception) {
                    holder.iv.setBackgroundColor(android.graphics.Color.parseColor("#444444"))
                }
                holder.itemView.setOnClickListener { openVideoPlayer(ctx, m.path) }
            }
        }
    }
}
