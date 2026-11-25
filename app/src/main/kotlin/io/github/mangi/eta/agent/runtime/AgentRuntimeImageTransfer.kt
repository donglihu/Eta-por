package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Base64InputStream
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_SCREEN_IMAGE_BYTES
import io.github.mangi.eta.agent.media.ModelImageBudget
import io.github.mangi.eta.agent.media.SCREEN_ENCODE_MAX_BYTES
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.core.MemoryPressure
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把模型图片正文从 Messenger Bundle 中移出。
 *
 * 发送端只在自己的缓存目录暂存图片，并通过只读文件描述符交给 Runtime；接收端在后台读取后，
 * 才恢复成模型协议需要的 data URL。远程 HTTP(S) URL 不落盘，直接透传。
 */
internal object AgentRuntimeImageTransfer {
    internal const val MAX_IMAGE_COUNT = 8

    /**
     * 单张图片的跨进程传输上限，必须与截图/原图合同 [MAX_SCREEN_IMAGE_BYTES]（32 MiB）对齐。
     *
     * 此前写死 12 MiB：原图改成「落盘 + 只读 fd 传输」后，12 MiB 以上的手机原片会在传输层被直接拒掉，
     * 用户选图即失败。传输上限只负责兜住 Messenger 的体积，真正的模型字节预算由接收端
     * [ModelImageBudget] 按渠道收缩（Anthropic 3.5 MiB / 其它 8 MiB），两层不能互相打架。
     */
    internal const val MAX_IMAGE_BYTES = MAX_SCREEN_IMAGE_BYTES

    /** 单次请求图片总量：8 张 × 32 MiB 的最坏情况不再按 32 MiB 拒收，取 64 MiB 兜底。 */
    internal const val MAX_TOTAL_IMAGE_BYTES = 64 * 1024 * 1024
    private const val MAX_REMOTE_URL_CHARS = 16 * 1024

    /**
     * 内联 data URL 的字符上限。
     *
     * 内联的大头只有 screenshot 的 PNG（[SCREEN_ENCODE_MAX_BYTES] = 12 MiB，Base64 后恰好 16Mi 字符），
     * 所以卡到 16Mi + 64Ki 字符，而不是 2× [MAX_IMAGE_BYTES]（64Mi）：旧值会让人以为能内联 64 MB。
     * 也不能再压到 8Mi 字符（约 6 MiB）：6 MiB 以上的截图会在这里被误判「内联图片数据过大」。
     */
    internal const val MAX_ENCODED_IMAGE_CHARS = SCREEN_ENCODE_MAX_BYTES * 4 / 3 + 64 * 1024
    private const val CACHE_DIRECTORY = "agent-runtime-transfer"
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class ImageTransferException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    class PreparedImages internal constructor(
        val images: List<AgentRuntimeWire.WireImage>,
        private val files: List<File>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            images.forEach { image -> runCatching { image.fileDescriptor?.close() } }
            files.forEach { file -> runCatching { file.delete() } }
        }
    }

    fun prepare(
        context: Context,
        images: List<AgentModelClient.ModelImage>,
    ): PreparedImages {
        if (images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException("一次最多支持 $MAX_IMAGE_COUNT 张图片")
        }

        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw ImageTransferException("无法创建图片传输缓存")
        }
        cleanupStaleFiles(cacheDirectory)

        val files = mutableListOf<File>()
        val wireImages = mutableListOf<AgentRuntimeWire.WireImage>()
        var totalBytes = 0L
        try {
            images.forEach { image ->
                if (image.reference.isRemoteUrl()) {
                    if (image.reference.length > MAX_REMOTE_URL_CHARS) {
                        throw ImageTransferException("远程图片链接过长")
                    }
                    wireImages += image.toWireImage(remoteUrl = image.reference)
                    return@forEach
                }

                val directDescriptor = openDirectDescriptor(context, image.reference)
                val directSize = directDescriptor?.statSize ?: -1L
                if (directSize > MAX_IMAGE_BYTES) {
                    directDescriptor?.close()
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                val descriptor: ParcelFileDescriptor
                val imageBytes: Long
                if (directDescriptor != null && directSize > 0L) {
                    descriptor = directDescriptor
                    imageBytes = directSize
                } else {
                    directDescriptor?.close()
                    val transferFile = File(
                        cacheDirectory,
                        "image-${UUID.randomUUID()}.bin",
                    )
                    files += transferFile
                    copyReferenceToFile(context, image.reference, transferFile)
                    imageBytes = transferFile.length()
                    descriptor = ParcelFileDescriptor.open(
                        transferFile,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                }
                if (imageBytes <= 0L) {
                    descriptor.close()
                    throw ImageTransferException("图片内容为空")
                }
                if (imageBytes > MAX_IMAGE_BYTES) {
                    descriptor.close()
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                totalBytes += imageBytes
                if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                    descriptor.close()
                    throw ImageTransferException("图片总大小不能超过 ${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                wireImages += image.toWireImage(
                    fileDescriptor = descriptor,
                    bytes = imageBytes.toInt(),
                )
            }
            return PreparedImages(wireImages, files)
        } catch (throwable: Throwable) {
            PreparedImages(wireImages, files).close()
            // OOM 不是「图片读不出来」：原样上抛，交给调用方按内存不足处理。
            if (throwable is OutOfMemoryError) throw throwable
            if (throwable is ImageTransferException) throw throwable
            throw ImageTransferException("无法读取待发送图片", throwable)
        }
    }

    /**
     * 必须在 Runtime 后台线程调用；会消费并关闭 [incoming] 持有的文件描述符。
     *
     * [providerType] 决定接收端的字节预算：Anthropic 系要压到 3.5 MiB（Base64 后仍低于 5 MB 硬上限），
     * 其它渠道 8 MiB。默认取请求自带 config 的渠道，调用方无需额外传参也不会退回 STANDARD。
     */
    fun materialize(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        providerType: String? = incoming.request.config.providerType,
    ): AgentRuntimeWire.RunRequest = incoming.use { request ->
        if (request.images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException("一次最多支持 $MAX_IMAGE_COUNT 张图片")
        }
        val budget = ModelImageBudget.forProviderType(providerType)
        // 图片是这套应用里最大的一次性峰值：32 MiB 原图 + Base64 字符（UTF-16 下再翻一倍）
        // + 一次整图解码位图。2026-11-25 的三次 OOM 全都发生在堆已贴顶之后，
        // 所以这里按水位于入口收口：临界时直接给可读错误（模型能读到、用户能重试），
        // 而不是走到 OutOfMemoryError 把整个进程带走。
        val pressure = MemoryPressure.snapshot()
        val pressureLevel = MemoryPressure.classify(pressure)
        if (pressureLevel == MemoryPressure.Level.Critical) {
            AndroidAgentLogger.warn("Agent image rejected: memory ${MemoryPressure.describe(pressure)}")
            throw ImageTransferException(
                "设备内存紧张（${MemoryPressure.describe(pressure)}），本次图片传输已中止，请稍后重试"
            )
        }
        // 偏低时把总量上限砍半：还要留出请求体序列化时的第二份拷贝。
        val totalImageLimit = when (pressureLevel) {
            MemoryPressure.Level.Low -> MAX_TOTAL_IMAGE_BYTES / 2
            else -> MAX_TOTAL_IMAGE_BYTES
        }
        AndroidAgentLogger.debug {
            "Agent image action=materialize_budget provider=${providerType ?: "unset"} " +
                "budget_bytes=${budget.maxEncodedBytes} images=${request.images.size}"
        }
        EtaTrace.log(
            "image.transfer",
            "action=materialize_budget provider=${providerType ?: "unset"} " +
                "budget_bytes=${budget.maxEncodedBytes} images=${request.images.size}",
        )

        var totalBytes = 0L
        val images = request.images.mapIndexed { index, image ->
            image.fileDescriptor?.let { descriptor ->
                val startedAt = SystemClock.elapsedRealtime()
                val statSize = descriptor.statSize
                if (statSize <= 0L) {
                    throw ImageTransferException("图片文件描述符无有效大小")
                }
                if (statSize > MAX_IMAGE_BYTES) {
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                // statSize 已校验过上限：按实际大小一次性分配并读满，去掉 BAOS + toByteArray 的第二份拷贝
                // （32 MiB 上限处原先瞬时 64 MB，正好叠在解码位图峰值之前）。
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readExactly(statSize.toInt())
                }
                // 防御性复核：readExactly 已在 EOF 处抛异常，正常情况下这里恒等，保留只为兜住
                // 未来若换回「按上限读」的实现时不会静默放过短读。
                if (bytes.size.toLong() != statSize) {
                    throw ImageTransferException("图片传输不完整")
                }
                totalBytes += bytes.size
                if (totalBytes > totalImageLimit) {
                    throw ImageTransferException(
                        "图片总大小不能超过 ${totalImageLimit / 1024 / 1024} MiB（当前内存水位 $pressureLevel）"
                    )
                }
                val materialized = if (image.preserveOriginal) {
                    AgentImageCodec.fromScreenBytes(bytes, image.source, image.mimeType)
                } else {
                    AgentImageCodec.fromAttachmentBytes(
                        bytes = bytes,
                        source = image.source,
                        mimeHint = image.mimeType,
                        budget = budget,
                    )
                }
                return@mapIndexed materialized.also {
                    AndroidAgentLogger.debug {
                        "Agent image action=materialize index=$index " +
                            "input_bytes=${bytes.size} output_bytes=${materialized.bytes} " +
                            "output=${materialized.width}x${materialized.height} " +
                            "budget_bytes=${budget.maxEncodedBytes} provider=${providerType ?: "unset"} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                    }
                }
            }

            val reference = image.remoteUrl.orEmpty()
            if (reference.isRemoteUrl()) {
                if (reference.length > MAX_REMOTE_URL_CHARS) {
                    throw ImageTransferException("远程图片链接过长")
                }
                return@mapIndexed AgentModelClient.ModelImage(
                    reference = reference,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = image.source,
                    preserveOriginal = image.preserveOriginal,
                )
            }
            if (reference.length > MAX_ENCODED_IMAGE_CHARS) {
                throw ImageTransferException("内联图片数据过大")
            }
            AgentImageCodec.fromReference(
                context = null,
                value = reference,
                source = image.source,
                preserveOriginal = image.preserveOriginal,
                budget = budget,
            ) ?: throw ImageTransferException("无法读取旧协议中的图片")
        }
        request.materializeText().copy(images = images)
    }

    private fun AgentModelClient.ModelImage.toWireImage(
        remoteUrl: String? = null,
        fileDescriptor: ParcelFileDescriptor? = null,
        bytes: Int = this.bytes,
    ): AgentRuntimeWire.WireImage = AgentRuntimeWire.WireImage(
        remoteUrl = remoteUrl,
        fileDescriptor = fileDescriptor,
        mimeType = mimeType,
        bytes = bytes,
        width = width,
        height = height,
        source = source,
        preserveOriginal = preserveOriginal,
    )

    private fun copyReferenceToFile(
        context: Context,
        reference: String,
        outputFile: File,
    ) {
        val input = when {
            reference.startsWith("data:image/", ignoreCase = true) ->
                reference.openDataUrlStream()

            Uri.parse(reference).scheme in setOf("content", "file") ->
                context.contentResolver.openInputStream(Uri.parse(reference))
                    ?: throw ImageTransferException("无法打开本地图片")

            else -> {
                val file = File(reference)
                if (!file.isFile) throw ImageTransferException("图片引用不可读")
                file.inputStream()
            }
        }
        input.use { source ->
            FileOutputStream(outputFile).use { target ->
                source.copyToLimited(target, MAX_IMAGE_BYTES)
            }
        }
    }

    private fun openDirectDescriptor(
        context: Context,
        reference: String,
    ): ParcelFileDescriptor? = runCatching {
        val uri = Uri.parse(reference)
        when (uri.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")
            "file" -> uri.path
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            null, "" -> File(reference)
                .takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            else -> null
        }
    }.getOrElse { failure ->
        // 打开描述符同样处在内存压力路径上：OOM 原样上抛，其余失败按「引用不可读」返回 null。
        if (failure is OutOfMemoryError) throw failure
        null
    }

    private fun String.openDataUrlStream(): InputStream {
        val separator = indexOf(',')
        if (separator <= 0 || !substring(0, separator).endsWith(";base64", ignoreCase = true)) {
            throw ImageTransferException("不支持的内联图片格式")
        }
        val encoded = substring(separator + 1)
        if (encoded.length > MAX_ENCODED_IMAGE_CHARS) {
            throw ImageTransferException("内联图片数据过大")
        }
        return Base64InputStream(
            ByteArrayInputStream(encoded.toByteArray(Charsets.US_ASCII)),
            Base64.DEFAULT,
        )
    }

    private fun InputStream.copyToLimited(
        output: FileOutputStream,
        maxBytes: Int,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw ImageTransferException("单张图片不能超过 ${maxBytes / 1024 / 1024} MiB")
            }
            output.write(buffer, 0, read)
        }
    }

    /** 已知长度的一次性读取：只分配一份，读满即返回，不再复制第二份。 */
    private fun InputStream.readExactly(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(out, offset, size - offset)
            if (read < 0) throw ImageTransferException("图片传输不完整")
            offset += read
        }
        return out
    }

    private fun String.isRemoteUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        runCatching {
            directory.listFiles().orEmpty()
                .asSequence()
                .filter { file -> file.isFile && file.lastModified() < cutoff }
                .forEach { file -> file.delete() }
        }
    }
}
