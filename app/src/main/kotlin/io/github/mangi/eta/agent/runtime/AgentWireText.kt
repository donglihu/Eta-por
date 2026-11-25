package io.github.mangi.eta.agent.runtime

import android.os.Binder
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** 大文本通过已取消目录链接的只读文件传输；Binder 只携带描述符与长度。 */
internal object AgentWireText {
    private const val INLINE_CHARS = 16_384
    private const val FD_SUFFIX = "_eta_text_fd"
    private const val SIZE_SUFFIX = "_eta_text_bytes"
    // 单次物化的内存保护，超过时拒绝交付，不截断已落盘的历史。
    private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024
    // 分块读的字符缓冲：一次只驻留 64K 字符，不再分配与文件等长的 ByteArray。
    private const val READ_CHUNK_CHARS = 64 * 1024
    // StringBuilder 初始容量上限，避免按 64MB 文件预分配 64M 字符的数组。
    private const val INITIAL_BUILDER_CHARS = 1 shl 20

    /**
     * 流式写出，不再物化整份 byte[]。
     *
     * 旧实现 `text.toByteArray(Charsets.UTF_8)` 再整体 write，堆上同时存在「原 String + 完整 byte[]」两份；
     * 历史 JSON 动辄数 MB，正好对上崩溃日志里大 byte[] / AbstractStringBuilder 的 OOM。
     * 改成 writer 逐块编码后，峰值只多一个 StreamEncoder 的小缓冲，不再有与正文等长的副本。
     *
     * SIZE_SUFFIX 必须写字节数而不是字符数：read() 用 descriptor.statSize 与它比对做完整性校验，
     * 而 statSize 是文件字节长度。中文在 UTF-8 下 1 字符 = 3 字节，用 text.length 会差约 3 倍并必然误报，
     * 所以长度取流关闭后的 file.length()（真实落盘字节数），而不是 text.length。
     */
    fun put(bundle: Bundle, key: String, text: String, directory: File?) {
        if (directory == null || text.length <= INLINE_CHARS) {
            bundle.putString(key, text)
            return
        }
        // 字符数上限先快速拒绝；它只是内存保护的粗筛，精确的字节数在写完后再校验。
        require(text.length <= MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
        val file = File.createTempFile("eta-context-", ".json", directory)
        try {
            file.outputStream().buffered().writer(Charsets.UTF_8).use { writer -> writer.write(text) }
            val written = file.length()
            // 流式写不占堆，但落盘字节仍要守住上限；超限时临时文件在 finally 里删掉。
            require(written <= MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            bundle.putParcelable(key + FD_SUFFIX, descriptor)
            bundle.putLong(key + SIZE_SUFFIX, written)
        } finally {
            check(file.delete()) { "Cannot unlink history transfer file" }
        }
    }

    fun hasDescriptor(bundle: Bundle, key: String): Boolean = bundle.containsKey(key + FD_SUFFIX)

    /**
     * 分块读回，不再分配整份 ByteArray。
     *
     * 旧实现 `ByteArray(size.toInt())` + `bytes.toString(Charsets.UTF_8)`，峰值同时驻留
     * 「完整 byte[] + 完整 String」两份；这里用 UTF-8 reader 循环读进 StringBuilder，
     * 峰值是「最终 String + 64K 字符缓冲」。
     *
     * 完整性判定不变，仍以「statSize 与 SIZE_SUFFIX 相等」+「流读尽」为准：
     * UTF-8 字符数与字节数不相等，绝不能拿 StringBuilder.length 去比 size。
     */
    fun read(bundle: Bundle, key: String): String? {
        val descriptor = bundle.getParcelable(key + FD_SUFFIX, ParcelFileDescriptor::class.java)
            ?: return bundle.getString(key)
        bundle.remove(key + FD_SUFFIX)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size = bundle.getLong(key + SIZE_SUFFIX, -1)
            require(size in 0..MAX_TRANSFER_BYTES && descriptor.statSize == size) {
                "Invalid history transfer descriptor"
            }
            val reader = input.reader(Charsets.UTF_8)
            val builder = StringBuilder(size.toInt().coerceAtMost(INITIAL_BUILDER_CHARS))
            val buffer = CharArray(READ_CHUNK_CHARS)
            while (true) {
                val read = reader.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                builder.append(buffer, 0, read)
            }
            // 读完必须真的到流尾：reader 读到 -1 说明底层也返回过 -1，这里再确认一次，防止静默截断。
            check(input.read() == -1) { "History transfer size mismatch" }
            builder.toString()
        }
    }

    /** 同进程 Messenger 不经过 Parcel；必须显式复制接收端的描述符所有权。 */
    fun send(target: Messenger?, message: Message) {
        val original = message.data
        var localCopy: Bundle? = null
        try {
            if (target == null) return
            if (target.binder is Binder) {
                val copy = Bundle(original)
                localCopy = copy
                original.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
                    copy.putParcelable(key, original.getParcelable(key, ParcelFileDescriptor::class.java)!!.dup())
                }
                message.data = copy
            }
            target.send(message)
            localCopy = null
        } finally {
            localCopy?.let(::close)
            close(original)
        }
    }

    fun close(bundle: Bundle) {
        bundle.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
            bundle.getParcelable(key, ParcelFileDescriptor::class.java)?.close()
            bundle.remove(key)
        }
    }
}
