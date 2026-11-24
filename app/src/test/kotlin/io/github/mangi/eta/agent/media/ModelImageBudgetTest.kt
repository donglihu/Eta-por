package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.runtime.AgentRuntimeImageTransfer
import io.github.mangi.eta.data.model.ProviderTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 用例（不经 Robolectric）：验证上游 `36b1c77f` 引入的图片预算常量与本地位移是否真的编进产物。
 *
 * 背景：arm64 上 Robolectric native runtime 没有对应二进制，`AgentImageCodecTest` 整类会被
 * `AssumptionViolatedException` 跳过（`skipped=1`）。这里只覆盖不依赖 Android 图形栈的常量与预算映射，
 * 保证「Anthropic 3.5 MB / 标准 8 MB / 截图 32 MB / 聊天预览本地 1440」这四项合同能被真实执行到。
 */
class ModelImageBudgetTest {
    @Test
    fun providerBudgetSplitsAnthropicFromStandardChannels() {
        val anthropic = ModelImageBudget.forProviderType(ProviderTypes.ANTHROPIC)
        val standard = ModelImageBudget.forProviderType(ProviderTypes.OPENAI_COMPATIBLE)
        val unknown = ModelImageBudget.forProviderType(null)

        assertEquals(3_670_016, anthropic.maxEncodedBytes)
        assertEquals(8 * 1024 * 1024, standard.maxEncodedBytes)
        assertEquals(standard.maxEncodedBytes, unknown.maxEncodedBytes)
        assertEquals(ModelImageBudget.STANDARD, ModelImageBudget.DEFAULT)

        // Anthropic / Bedrock / Vertex Claude 的 5 MB Base64 硬上限：raw 用 4/3 膨胀后必须留余量。
        val base64Estimate = (anthropic.maxEncodedBytes.toLong() * 4L) / 3L + 4L
        assertTrue("Anthropic 预算膨胀后必须低于 5 MB，实际=$base64Estimate", base64Estimate < 5L * 1024L * 1024L)
        assertTrue(standard.maxEncodedBytes > anthropic.maxEncodedBytes)
    }

    @Test
    fun defaultBudgetKeepsPixelAndEdgeCeilings() {
        assertEquals(4_000_000L, ModelImageBudget.DEFAULT_MAX_PIXELS)
        assertEquals(7_680, ModelImageBudget.DEFAULT_MAX_EDGE_PX)
        assertEquals(95, ModelImageBudget.DEFAULT_JPEG_QUALITY)
        assertEquals(4_000_000L, ModelImageBudget.DEFAULT.maxPixels)
        assertEquals(7_680, ModelImageBudget.DEFAULT.maxEdgePx)
        assertEquals(95, ModelImageBudget.DEFAULT.initialQuality)
    }

    @Test
    fun screenCapIsRaisedWhileLocalPreviewTuningIsPreserved() {
        assertEquals(32 * 1024 * 1024, MAX_SCREEN_IMAGE_BYTES)
        // 兼容别名保留旧名旧值，供 AgentChatImageStore / ChatImageSearchTools 沿用。
        assertEquals(12 * 1024 * 1024, MAX_AGENT_IMAGE_BYTES)
        // 本地相对上游父提交 ec9bfb67 的 1920 调低到 1440（像素 207 万、质量 86），移植后必须仍是 1440。
        assertEquals(1_440, CHAT_PREVIEW_MAX_LONG_EDGE)
    }

    @Test
    fun screenEncodeGateStaysBelowOriginalImageContract() {
        // 截图闸门只约束 screen() 的整张 PNG 编码结果，必须独立于 32 MiB 原图合同。
        assertEquals(12 * 1024 * 1024, SCREEN_ENCODE_MAX_BYTES)
        assertEquals(32 * 1024 * 1024, MAX_SCREEN_IMAGE_BYTES)
        assertNotEquals(SCREEN_ENCODE_MAX_BYTES, MAX_SCREEN_IMAGE_BYTES)
        assertTrue(SCREEN_ENCODE_MAX_BYTES < MAX_SCREEN_IMAGE_BYTES)
    }

    @Test
    fun providerTypeStringMapsAnthropicOnly() {
        // 跨进程物化用字符串渠道名选预算：除 anthropic 外一律 STANDARD，绝不能默认落 ANTHROPIC。
        assertSame(ModelImageBudget.ANTHROPIC, ModelImageBudget.forProviderType("anthropic"))
        assertSame(ModelImageBudget.STANDARD, ModelImageBudget.forProviderType(ProviderTypes.OPENAI_COMPATIBLE))
        assertSame(ModelImageBudget.STANDARD, ModelImageBudget.forProviderType("gemini"))
        assertSame(ModelImageBudget.STANDARD, ModelImageBudget.forProviderType(""))
        assertSame(ModelImageBudget.STANDARD, ModelImageBudget.forProviderType(null))
    }

    @Test
    fun runtimeTransferCeilingFollowsScreenContract() {
        // 传输层单张上限必须等于截图/原图合同，不能再写死 12 MiB 把大原片拒在跨进程边界外。
        assertEquals(MAX_SCREEN_IMAGE_BYTES, AgentRuntimeImageTransfer.MAX_IMAGE_BYTES)
        assertTrue(
            AgentRuntimeImageTransfer.MAX_TOTAL_IMAGE_BYTES > AgentRuntimeImageTransfer.MAX_IMAGE_BYTES,
        )
    }

    @Test
    fun decodeSamplingKeepsPeakMemoryWithinTwiceTheTargetArea() {
        // 12 MP 常见手机原片 → 4 MP 目标：必须取 2（解码 3.0 MP ≈ 12 MB），不能停在 1（12 MP = 48 MB）。
        assertEquals(2, chooseSampleSize(4000, 3000, 2309, 1732))
        // 25 MP → 4 MP：同样取 2（6.25 MP ≈ 25 MB），不是 1（100 MB）。
        assertEquals(2, chooseSampleSize(5000, 5000, 2000, 2000))
        // 已经小于目标的图不做任何降采样。
        assertEquals(1, chooseSampleSize(1000, 800, 1000, 800))
        // 宽扁目标（长截图类）：单边阈值会停在 1，面积上界必须把它压到 2，否则解码 12 MP = 48 MB。
        assertEquals(2, chooseSampleSize(4000, 3000, 3000, 1125))
        // 8–11 MP 原片落在「面积比略小于 2×」的区间：面积上界把 sample 从 1 压到 2
        // （解码 32 MB → 11 MB），代价是 1.20 倍上采样；这是面积上界优先于单边下限的实证。
        assertEquals(2, chooseSampleSize(4080, 2720, 2449, 1633))
        // 常量本身也要锁住语义：单边下限 85%（best-effort）、解码面积 ≤2 倍目标面积（不可让位）。
        assertEquals(0.85, SAMPLE_MIN_EDGE_RATIO, 0.000001)
        assertEquals(2L, SAMPLE_MAX_DECODED_PIXEL_FACTOR)
        // 非法尺寸不崩，退回不降采样。
        assertEquals(1, chooseSampleSize(0, 3000, 2309, 1732))
    }
}
