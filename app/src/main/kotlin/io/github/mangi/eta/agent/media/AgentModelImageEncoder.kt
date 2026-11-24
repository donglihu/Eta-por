package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_SCREEN_IMAGE_BYTES = 32 * 1024 * 1024

/**
 * 兼容别名（保留旧名与旧值 12 MB）：上游 `36b1c77f` 已把截图/原图合同改名为
 * [MAX_SCREEN_IMAGE_BYTES]（32 MB），但本地既有消费方仍按旧名表达「业务缓存上限」：
 * - `agent/media/AgentChatImageStore.kt` 的聊天图缓存上限
 * - `agent/tool/ChatImageSearchTools.kt` 的聊天图扫描体积过滤
 * 为缩小改动面且不改变这两处既有语义，这里保留旧名旧值；新代码一律使用 [MAX_SCREEN_IMAGE_BYTES]。
 */
internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024

/**
 * 截图编码闸门：12 MiB，只约束 [AgentModelImageEncoder.screen] 的整张 PNG 编码结果。
 *
 * 刻意不复用原图合同 [MAX_SCREEN_IMAGE_BYTES]（32 MiB）：那个上限回答的是「整张原图能不能传」，
 * 而截图走整张 PNG → Base64，不经 [ModelImageBudget] 压缩，闸门必须更紧。
 * 截图落盘属新设计，本轮不实现，只把闸门收敛并留日志。
 */
internal const val SCREEN_ENCODE_MAX_BYTES = 12 * 1024 * 1024

/**
 * 截图编码前的像素上界：400 万像素（ARGB_8888 位图约 16 MB）。
 *
 * [SCREEN_ENCODE_MAX_BYTES] 只约束「进 Base64 的字节」，管不到压缩前的峰值：位图 + BAOS + toByteArray 拷贝
 * + Base64 串会叠加成上百 MB。编码前先把像素数卡死，超出直接拒绝，不给压缩阶段把进程推到 OOM 的机会。
 */
internal const val SCREEN_MAX_PIXELS = 4_000_000L

/**
 * 聊天预览单边长边上限。
 *
 * 本地相对上游父提交 `ec9bfb67`（1920）调低到 1440，同时把像素上限提到约 207 万（1440×1440）、
 * 质量降到 86：目标是在 1080p～1440p 屏上全屏查看一张图时不发虚，又不解码出更大的位图。
 * 再往上加（例如 1600/1920）单张解码内存会超过 10 MB，多图时得不偿失。
 */
internal const val CHAT_PREVIEW_MAX_LONG_EDGE = 1_440

/**
 * 解码采样时允许的最小单边比例：解码边长不得低于目标的 85%。
 *
 * 低于它就要靠上采样补齐，84% 对应 1.18 倍线性放大，肉眼基本无感；
 * 再往下降（例如 0.9 → 0.8 之外继续降）会明显发糊，所以这里不追求「正好等于目标」。
 */
internal const val SAMPLE_MIN_EDGE_RATIO = 0.85

/** 解码面积不得超过目标面积的倍数：超出就继续降采样，宁可轻微上采样也不让峰值翻倍。 */
internal const val SAMPLE_MAX_DECODED_PIXEL_FACTOR = 2L

/**
 * 模型发送前的图片预算约束：将「用户原图大小」与「模型协议上限」解耦。
 *
 * - 使用总像素预算 + 单边硬顶，避免普通照片无谓降采样，也避免手机长截图被压成细条。
 * - 总像素预算同时是**解码峰值预算**：ARGB_8888 下 1 px = 4 B，解码面积被面积上界钉在目标面积的
 *   2 倍以内，即 4 MP 目标下单张解码 ≤ 32 MB（典型 11–24 MB，源面积落在 (8 M, 10.7 M] 时贴 32 MB 上限）；
 *   原先 8 MP 会让 12 MP 常见原片几乎不降采样（解码 48 MB、25 MP 原片 100 MB），Runtime 进程
 *   贴着 512 MB 堆上限时，这类单张峰值就是压垮小分配的那根稻草。
 * - 按渠道区分字节预算：Anthropic / Bedrock / Vertex Claude 要求 Base64 <= 5 MB（raw 约 3.75 MB），
 *   取 3.5 MiB 留足协议余量；OpenAI / Gemini 兼容端点取 8 MiB。
 */
internal data class ModelImageBudget(
    val maxEncodedBytes: Int,
    val maxPixels: Long = DEFAULT_MAX_PIXELS,
    val maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    val initialQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    init {
        require(maxEncodedBytes > 0)
        require(maxPixels > 0L)
        require(maxEdgePx > 0)
        require(initialQuality in 1..100)
    }

    companion object {
        const val DEFAULT_MAX_PIXELS = 4_000_000L
        const val DEFAULT_MAX_EDGE_PX = 7_680
        const val DEFAULT_JPEG_QUALITY = 95
        const val ANTHROPIC_MAX_BYTES = 3_670_016
        const val STANDARD_MAX_BYTES = 8 * 1024 * 1024

        val ANTHROPIC = ModelImageBudget(maxEncodedBytes = ANTHROPIC_MAX_BYTES)
        val STANDARD = ModelImageBudget(maxEncodedBytes = STANDARD_MAX_BYTES)
        val DEFAULT = STANDARD

        fun forProviderType(providerType: String?): ModelImageBudget =
            if (providerType == ProviderTypes.ANTHROPIC) ANTHROPIC else STANDARD
    }
}

/** 负责将任意来源的图片流式解码并编码为符合目标预算的模型图片或聊天预览。 */
internal object AgentModelImageEncoder {
    /** 聊天预览质量：本地取 86（上游 88），与 [CHAT_PREVIEW_MAX_LONG_EDGE] 的取舍配套。 */
    private const val PREVIEW_JPEG_QUALITY = 86
    private const val PREVIEW_MAX_BYTES = 2 * 1024 * 1024
    /** 约 207 万像素（1440×1440），与 [CHAT_PREVIEW_MAX_LONG_EDGE] 配套。 */
    private const val PREVIEW_MAX_PIXELS = 2_073_600L
    private const val HEADER_MAGIC_BYTES = 16
    private const val MAX_SHRINK_ROUNDS = 6
    private const val SHRINK_FACTOR = 0.75
    private const val MIN_SHRINK_EDGE_PX = 256
    private const val MIN_EMERGENCY_EDGE_PX = 32

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val previewBudget = ModelImageBudget(
        maxEncodedBytes = PREVIEW_MAX_BYTES,
        maxPixels = PREVIEW_MAX_PIXELS,
        maxEdgePx = CHAT_PREVIEW_MAX_LONG_EDGE,
        initialQuality = PREVIEW_JPEG_QUALITY,
    )

    fun attachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = encodeStreamForModel(
        openStream = { ByteArrayInputStream(bytes) },
        source = source,
        mimeHint = mimeHint,
        budget = budget,
    )

    fun encodeFileForModel(
        file: File,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? {
        if (!file.isFile || file.length() <= 0L) return null
        return encodeStreamForModel(
            openStream = { file.inputStream() },
            source = source,
            mimeHint = mimeHint,
            budget = budget,
        )
    }

    /** 针对可 seek 的文件描述符（如跨进程传入的本地原图）直接流式采样解码，避免整图读入堆内存。 */
    fun encodeSeekableDescriptorForModel(
        inputStream: FileInputStream,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = encodeStreamForModel(
        openStream = {
            inputStream.channel.position(0L)
            NonClosingInputStream(inputStream)
        },
        source = source,
        mimeHint = mimeHint,
        budget = budget,
    )

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage {
        // Bitmap 尚无文件编码，仅序列化一次 PNG；不缩放、不铺底、不转换像素。
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels > SCREEN_MAX_PIXELS) {
            EtaTrace.log(
                "image.screen",
                "action=reject reason=pixels pixels=$pixels limit=$SCREEN_MAX_PIXELS " +
                    "size=${bitmap.width}x${bitmap.height} source=$source",
            )
        }
        require(pixels <= SCREEN_MAX_PIXELS) {
            "图片像素过多：${bitmap.width}x${bitmap.height}"
        }
        val initialCapacity = minOf(
            bitmap.width * bitmap.height / 4,
            2 * 1024 * 1024,
        ).coerceAtLeast(32 * 1024)
        // 有界输出流：压缩一旦越过 12 MiB 立即中断，不再等 compress 跑完再事后判 size。
        val output = object : ByteArrayOutputStream(initialCapacity) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (size() + len > SCREEN_ENCODE_MAX_BYTES) abort(size() + len)
                super.write(b, off, len)
            }

            override fun write(b: Int) {
                if (size() + 1 > SCREEN_ENCODE_MAX_BYTES) abort(size() + 1)
                super.write(b)
            }

            private fun abort(pending: Int): Nothing {
                EtaTrace.log(
                    "image.screen",
                    "action=abort bytes=$pending limit=$SCREEN_ENCODE_MAX_BYTES " +
                        "size=${bitmap.width}x${bitmap.height} source=$source",
                )
                throw IllegalArgumentException("图片数据过大：$pending")
            }
        }
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片编码失败" }
        val encoded = output.toByteArray()
        if (encoded.isEmpty() || encoded.size > SCREEN_ENCODE_MAX_BYTES) {
            EtaTrace.log(
                "image.screen",
                "action=reject bytes=${encoded.size} limit=$SCREEN_ENCODE_MAX_BYTES " +
                    "size=${bitmap.width}x${bitmap.height} source=$source",
            )
        }
        require(encoded.isNotEmpty() && encoded.size <= SCREEN_ENCODE_MAX_BYTES) {
            "图片数据过大：${encoded.size}"
        }
        return AgentModelClient.ModelImage(
            reference = "data:image/png;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
            mimeType = "image/png",
            bytes = encoded.size,
            width = bitmap.width,
            height = bitmap.height,
            source = source,
            preserveOriginal = true,
        )
    }

    /** 文件工具图片在发送模型前流式采样并按目标渠道预算编码为 JPEG。 */
    fun toolVision(
        file: File,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = runCatching {
        encodeFileForModel(file, source, mimeHint, budget)
    }.getOrElse { failure ->
        // OOM 不是「文件不是图片」，必须上抛，不能被 getOrNull 静默改写成 null。
        if (failure is OutOfMemoryError) throw failure
        null
    }

    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = runCatching {
        attachment(bytes, source, mimeHint, budget)
    }.getOrElse { failure ->
        // 同上：解码 OOM 必须冒泡，调用方才能按内存不足处理而不是报「不是图片」。
        if (failure is OutOfMemoryError) throw failure
        null
    }

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val bounds = inspectBounds(openStream, image.mimeType) ?: return@runCatching null
        val width = image.width?.takeIf { it > 0 } ?: bounds.width
        val height = image.height?.takeIf { it > 0 } ?: bounds.height
        val target = targetSize(width, height, previewBudget)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmapWithinBudget(
                bitmap = decoded,
                source = image.source,
                budget = previewBudget,
                flattenAlpha = true,
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrElse { failure ->
        // 与 toolVision 同口径：解码 / 压缩 OOM 必须上抛，不能被 getOrNull 说成「图片读取失败」。
        if (failure is OutOfMemoryError) throw failure
        null
    }

    internal fun encodeStreamForModel(
        openStream: () -> InputStream,
        source: String,
        mimeHint: String,
        budget: ModelImageBudget,
    ): AgentModelClient.ModelImage? {
        val header = readHeaderMagic(openStream) ?: return null
        if (!header.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(openStream, mimeHint) ?: return null
        val target = targetSize(bounds.width, bounds.height, budget)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        return try {
            encodeBitmapWithinBudget(
                bitmap = decoded,
                source = source,
                budget = budget,
                flattenAlpha = !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun encodeBitmapWithinBudget(
        bitmap: Bitmap,
        source: String,
        budget: ModelImageBudget,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        var current = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, budget),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialQualities = listOf(budget.initialQuality, 88, 80, 72, 62, 50)
                .filter { it <= budget.initialQuality }
                .distinct()
            var lastEncoded = compressJpeg(current, initialQualities.first())
            if (lastEncoded.size <= budget.maxEncodedBytes) {
                return buildJpegModelImage(lastEncoded, current.width, current.height, source)
            }
            for (quality in initialQualities.drop(1)) {
                lastEncoded = compressJpeg(current, quality)
                if (lastEncoded.size <= budget.maxEncodedBytes) {
                    return buildJpegModelImage(lastEncoded, current.width, current.height, source)
                }
            }

            // 极端高噪点图像在最低常规质量下仍超字节预算时，逐步等比缩小直至单调收敛。
            val shrinkQualities = listOf(72, 58, 45)
            for (round in 0 until MAX_SHRINK_ROUNDS) {
                val minWidth = minOf(MIN_SHRINK_EDGE_PX, current.width)
                val minHeight = minOf(MIN_SHRINK_EDGE_PX, current.height)
                val nextWidth = (current.width * SHRINK_FACTOR).toInt().coerceAtLeast(minWidth)
                val nextHeight = (current.height * SHRINK_FACTOR).toInt().coerceAtLeast(minHeight)
                if (nextWidth == current.width && nextHeight == current.height) break
                val resized = renderBitmap(current, TargetSize(nextWidth, nextHeight), flattenAlpha = false)
                if (current !== bitmap && !current.isRecycled) current.recycle()
                current = resized
                for (quality in shrinkQualities) {
                    lastEncoded = compressJpeg(current, quality)
                    if (lastEncoded.size <= budget.maxEncodedBytes) {
                        return buildJpegModelImage(lastEncoded, current.width, current.height, source)
                    }
                }
            }

            // 兜底一步缩放，确保在极严苛预算下依然收敛。
            val emergencyRatio = sqrt(budget.maxEncodedBytes.toDouble() / lastEncoded.size.coerceAtLeast(1)) * 0.9
            val emergencyWidth = (current.width * emergencyRatio).toInt()
                .coerceIn(minOf(MIN_EMERGENCY_EDGE_PX, current.width), current.width)
            val emergencyHeight = (current.height * emergencyRatio).toInt()
                .coerceIn(minOf(MIN_EMERGENCY_EDGE_PX, current.height), current.height)
            if (emergencyWidth < current.width || emergencyHeight < current.height) {
                val resized = renderBitmap(current, TargetSize(emergencyWidth, emergencyHeight), flattenAlpha = false)
                if (current !== bitmap && !current.isRecycled) current.recycle()
                current = resized
                lastEncoded = compressJpeg(current, 40)
            }
            return buildJpegModelImage(lastEncoded, current.width, current.height, source)
        } finally {
            if (current !== bitmap && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val initialCapacity = minOf(
            bitmap.width * bitmap.height / 4,
            2 * 1024 * 1024,
        ).coerceAtLeast(32 * 1024)
        val output = ByteArrayOutputStream(initialCapacity)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片编码失败" }
        val encoded = output.toByteArray()
        require(encoded.isNotEmpty()) { "图片内容为空" }
        return encoded
    }

    private fun buildJpegModelImage(
        encoded: ByteArray,
        width: Int,
        height: Int,
        source: String,
    ): AgentModelClient.ModelImage = AgentModelClient.ModelImage(
        reference = "data:image/jpeg;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
        mimeType = "image/jpeg",
        bytes = encoded.size,
        width = width,
        height = height,
        source = source,
    )

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    private fun readHeaderMagic(openStream: () -> InputStream): ByteArray? = openStream().use { input ->
        val header = ByteArray(HEADER_MAGIC_BYTES)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) break
            offset += read
        }
        if (offset == 0) null else header.copyOf(offset)
    }

    private fun inspectBounds(openStream: () -> InputStream, mimeHint: String): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun targetSize(
        width: Int,
        height: Int,
        budget: ModelImageBudget,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = min(1.0, budget.maxEdgePx.toDouble() / maxOf(width, height))
        val scaledPixels = width.toDouble() * height * scale * scale
        if (scaledPixels > budget.maxPixels) {
            scale *= sqrt(budget.maxPixels.toDouble() / scaledPixels)
        }
        return TargetSize(
            width = (width * scale).toInt().coerceIn(1, width),
            height = (height * scale).toInt().coerceIn(1, height),
        )
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int = chooseSampleSize(
        width = width,
        height = height,
        targetWidth = target.width,
        targetHeight = target.height,
    )

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_SCREEN_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("无法打开本地图片")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> File(trimmed)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() = Unit
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}

/**
 * 解码采样倍数（纯算术，无 Android 依赖，供纯 JVM 单测直接验证）。
 *
 * 两条约束同时参与，但**面积上界优先**：
 * 1. 单边下限 [SAMPLE_MIN_EDGE_RATIO]（best-effort）：优先让解码边长 ≥ 目标边长 × 0.85；
 *    一旦面积上界要求更激进的降采样，这条会让位，最坏上采样 1.41 倍（源面积落在 (8 M, 10.7 M] 与 (32 M, 46 M] 区间时）。
 * 2. 面积上界 [SAMPLE_MAX_DECODED_PIXEL_FACTOR]：解码面积 ≤ 目标面积 × 2，超出继续降采样，这条不可让位。
 *
 * 只靠第 1 条时，12 MP 原片降到 4 MP 目标仍会停在 sample=1（解码 48 MB）：因为「再降一半」
 * 后单边只有目标的 0.866 倍，原先的 0.9 阈值恰好把它拦掉。第 2 条把峰值钉在目标面积的 2 倍以内。
 */
internal fun chooseSampleSize(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sample <= 64) {
        val next = sample * 2
        if (
            width.toDouble() / next < targetWidth * SAMPLE_MIN_EDGE_RATIO ||
            height.toDouble() / next < targetHeight * SAMPLE_MIN_EDGE_RATIO
        ) {
            break
        }
        sample = next
    }
    val maxDecodedPixels =
        maxOf(1L, targetWidth.toLong() * targetHeight.toLong()) * SAMPLE_MAX_DECODED_PIXEL_FACTOR
    while (sample < 64) {
        val decodedPixels = (width / sample).toLong() * (height / sample).toLong()
        if (decodedPixels <= maxDecodedPixels) break
        sample *= 2
    }
    return sample
}
