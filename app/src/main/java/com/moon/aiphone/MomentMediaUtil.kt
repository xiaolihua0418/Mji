package com.moon.aiphone

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 朋友圈媒体类型工具类
 *
 * 设计：imageDesc 字符串按「[REAL_IMG]」「[REAL_VIDEO]」前缀分类
 * 每个条目里仍用 ||| 分隔多个文件路径
 * 这样完全兼容旧数据（旧数据只有 [REAL_IMG]，没视频前缀就都是图片）
 */

/** 媒体条目数据类：区分图片/视频 + 存储路径 */
data class MomentMedia(
    val type: Type,   // IMAGE or VIDEO
    val path: String  // 持久化的本地绝对路径
) {
    enum class Type { IMAGE, VIDEO }
}

/** 在 imageDesc 里用的两个前缀：单条 path 前面拼上这个 */
private const val PREFIX_IMG = "I:"   // 兼容：短前缀，存数据库不占地方
private const val PREFIX_VID = "V:"

/** 把单个 path 按类型打包成带前缀的字符串 */
fun MomentMedia.toTagged(): String = when (type) {
    MomentMedia.Type.IMAGE -> PREFIX_IMG + path
    MomentMedia.Type.VIDEO -> PREFIX_VID + path
}

/** 把一组带 ||| 分隔、带前缀的字符串拆开成类型+路径列表 */
fun parseMomentMediaList(imageDesc: String): List<MomentMedia> {
    if (!imageDesc.startsWith("[REAL_IMG]")) return emptyList()
    val raw = imageDesc.removePrefix("[REAL_IMG]")
    if (raw.isEmpty()) return emptyList()
    return raw.split("|||")
        .map { it.trim() }
        .filter { it.length > 2 }
        .mapNotNull { tagged ->
            when {
                tagged.startsWith(PREFIX_VID) -> MomentMedia(
                    MomentMedia.Type.VIDEO,
                    tagged.removePrefix(PREFIX_VID)
                )
                tagged.startsWith(PREFIX_IMG) -> MomentMedia(
                    MomentMedia.Type.IMAGE,
                    tagged.removePrefix(PREFIX_IMG)
                )
                else -> {
                    // 兼容旧数据：没有前缀 → 默认都是图片
                    MomentMedia(MomentMedia.Type.IMAGE, tagged)
                }
            }
        }
}

/** 只拿图片路径列表（给旧代码 LLM 编码 base64 用） */
fun parseMomentImagePathsOnly(imageDesc: String): List<String> =
    parseMomentMediaList(imageDesc).filter { it.type == MomentMedia.Type.IMAGE }.map { it.path }

/** 只拿视频路径列表 */
fun parseMomentVideoPathsOnly(imageDesc: String): List<String> =
    parseMomentMediaList(imageDesc).filter { it.type == MomentMedia.Type.VIDEO }.map { it.path }

/** 把一组 MomentMedia 打包成 imageDesc 存数据库的字符串 */
fun buildImageDesc(medias: List<MomentMedia>): String {
    if (medias.isEmpty()) return ""
    val inner = medias.joinToString("|||") { it.toTagged() }
    return "[REAL_IMG]$inner"
}

/** 根据 Uri 判断是不是视频（根据 MIME 或文件后缀） */
fun isVideoUri(ctx: Context, uri: Uri): Boolean {
    val mime = ctx.contentResolver.getType(uri)
    if (mime != null && mime.startsWith("video/")) return true
    val lower = uri.toString().lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".mov") ||
            lower.endsWith(".m4v") || lower.endsWith(".3gp") ||
            lower.endsWith(".mkv") || lower.endsWith(".webm")
}

/** 读取视频首帧作为缩略图（Bitmap） */
fun getVideoThumbnail(uri: Uri, ctx: Context): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (uri.toString().startsWith("file://") || uri.toString().startsWith("/")) {
            val realPath = if (uri.toString().startsWith("file://")) uri.path else uri.toString()
            retriever.setDataSource(realPath)
        } else {
            retriever.setDataSource(ctx, uri)
        }
        retriever.frameAtTime
    } catch (e: Exception) {
        null
    } finally {
        try { retriever.release() } catch (e: Exception) {}
    }
}

/** 获取视频时长（秒） */
fun getVideoDurationSec(uri: Uri, ctx: Context): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        if (uri.toString().startsWith("file://") || uri.toString().startsWith("/")) {
            val realPath = if (uri.toString().startsWith("file://")) uri.path else uri.toString()
            retriever.setDataSource(realPath)
        } else {
            retriever.setDataSource(ctx, uri)
        }
        val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        (timeStr?.toLongOrNull() ?: 0L).toInt() / 1000
    } catch (e: Exception) {
        0
    } finally {
        try { retriever.release() } catch (e: Exception) {}
    }
}

/** 把秒数格式化成 mm:ss */
fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val m = sec / 60
    val s = sec % 60
    return String.format("%02d:%02d", m, s)
}

/** 根据 Uri 打开输入流（file:// / content:// / 绝对路径 都支持） */
fun openInputStreamSafe(ctx: Context, uri: Uri): InputStream? = openInputStreamSafe(ctx, uri.toString())
fun openInputStreamSafe(ctx: Context, path: String): InputStream? {
    return try {
        when {
            path.startsWith("file://") -> FileInputStream(path.removePrefix("file://"))
            path.startsWith("/") -> FileInputStream(path)
            path.startsWith("content:") -> ctx.contentResolver.openInputStream(Uri.parse(path))
            else -> FileInputStream(path)
        }
    } catch (e: Exception) { null }
}

/** 把 Uri 转成绝对持久化路径（拷贝到 filesDir 内，防止权限被回收） */
fun persistUriToInternalFile(ctx: Context, uri: Uri, defaultExt: String = "jpg"): String {
    val name = "media_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.$defaultExt"
    val destFile = File(ctx.filesDir, name)
    try {
        openInputStreamSafe(ctx, uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) { }
    return destFile.absolutePath
}
