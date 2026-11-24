package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.security.MessageDigest

/**
 * 会话消息图片的落盘仓库（11-22 OOM 修复）。
 *
 * 背景：`conversation_messages.images_json` 历史上整段存 base64 data URL（实测 61M 字符），
 * #006 把启动加载收敛到「只加载选中会话」后，单个巨型会话最坏仍要 30MB+ 常驻堆。
 * 这里把图片正文落到应用专属外部目录，库里只存文件路径；渲染与模型链路都按路径按需读取。
 *
 * 文件名取图片字节 SHA-256 前 16 位：同一张图重复落盘只写一次，写入天然幂等，
 * 保存路径的「前后实体对比」多次触发 materialize 也不会产生重复文件。
 */
internal object AgentChatImageStore {
    private const val DIRECTORY_NAME = "chat-images"
    private const val MAX_IMAGE_BYTES = MAX_AGENT_IMAGE_BYTES

    /** 图片落盘目录：优先应用专属外部存储，不可用时回落内部 filesDir。 */
    fun directory(context: Context): File {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        return File(base, DIRECTORY_NAME)
    }

    /**
     * 引用是否已经是文件路径（`file://` 或绝对路径），落盘时原样透传。
     * data URL、远程 URL 与 content URI 都不算路径引用。
     */
    fun isPathReference(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("data:", ignoreCase = true)) return false
        if (trimmed.startsWith("http://", ignoreCase = true)) return false
        if (trimmed.startsWith("https://", ignoreCase = true)) return false
        if (trimmed.startsWith("content://", ignoreCase = true)) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return true
        return trimmed.startsWith("/")
    }

    /** data URL 的 mime → 扩展名；未知 mime 一律按 JPEG 落盘（与附件编码口径一致）。 */
    fun extensionForMime(mime: String): String = when {
        mime.startsWith("image/png", ignoreCase = true) -> "png"
        mime.startsWith("image/webp", ignoreCase = true) -> "webp"
        mime.startsWith("image/gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }

    /** 按落盘文件扩展名反推 mime；路径引用给模型链路用。 */
    fun mimeForReference(value: String): String = when (value.trim().substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /**
     * 把 data URL 图片正文写成文件，返回绝对路径。
     * 已是路径 / 远程 URL / content URI 的引用原样返回；落盘失败保留原引用（不静默丢图）。
     */
    fun materialize(context: Context, reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.isBlank() || isPathReference(trimmed)) return trimmed
        if (!trimmed.startsWith("data:image/", ignoreCase = true)) return trimmed
        val separator = trimmed.indexOf(',')
        if (separator <= 0) return trimmed
        val mime = trimmed.substring("data:".length, separator).substringBefore(';')
        val encoded = trimmed.substring(separator + 1)
        if (encoded.isBlank() || encoded.length > MAX_IMAGE_BYTES * 2) return trimmed

        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.isNotEmpty()) { "图片内容为空" }
            val directory = directory(context)
            if (!directory.isDirectory && !directory.mkdirs()) error("无法创建图片落盘目录")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val name = "img-" + digest.joinToString("") { "%02x".format(it) }.substring(0, 16) +
                "." + extensionForMime(mime)
            val target = File(directory, name)
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                // 先写临时文件再改名：进程半路被杀也不会留下写了一半的图片。
                val partial = File(directory, "$name.part")
                try {
                    partial.writeBytes(bytes)
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                } catch (writeFailure: Exception) {
                    partial.delete()
                    throw writeFailure
                }
            }
            target.absolutePath
        }.getOrElse { failure ->
            AndroidAgentLogger.error(
                "Agent chat image materialize failed: type=${failure.safeLogType()}", failure,
            )
            trimmed
        }
    }

    /** 批量落盘：新消息入库前把 data URL 引用换成文件路径，库里不再存 base64。 */
    fun persistImages(context: Context, images: List<String>): List<String> =
        images.map { materialize(context, it) }

    /**
     * 文件路径引用 → 面向模型协议的 data URL（编辑/续跑回填历史用）。
     * `AgentConversationCodec.userMessage` 要求 provider 引用（http/data），路径必须先物化；
     * [AgentImageCodec.fromReference] 保字节读取，转出的 data URL 再落盘会得到同一个哈希文件。
     */
    fun toProviderDataUrl(context: Context, reference: String): String {
        val trimmed = reference.trim()
        if (!isPathReference(trimmed)) return trimmed
        val file = if (trimmed.startsWith("file://", ignoreCase = true)) {
            trimmed.removePrefix("file://")
        } else {
            trimmed
        }
        if (!File(file).isFile) return trimmed
        return runCatching {
            AgentImageCodec.fromReference(context, file, source = "chat_history")?.reference ?: trimmed
        }.getOrElse { failure ->
            AndroidAgentLogger.error(
                "Agent chat image provider reference failed: type=${failure.safeLogType()}", failure,
            )
            trimmed
        }
    }
}
