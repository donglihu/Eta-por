package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPrefixedModelNamesTest {
    private fun model(modelId: String, displayName: String = modelId) = Model(
        id = "m-$modelId",
        modelId = modelId,
        displayName = displayName,
        ownedBy = null,
    )

    private val gateway = CustomProviderSetting(
        id = "custom-gateway",
        name = "gateway",
        baseUrl = "http://127.0.0.1:8788/v1",
    )

    @Test
    fun appendsProviderSuffixToGatewayDisplayName() {
        assertEquals(
            "DeepSeek-V4.1-Flash（vendor-a）",
            ProviderPrefixedModelNames.format("DeepSeek-V4.1-Flash", "vendor-a/deepseek-v4.1-flash"),
        )
    }

    @Test
    fun gatewayCatalogKeepsSameNameDistinguishableBySuffix() {
        val renamed = ProviderPrefixedModelNames.apply(
            gateway,
            listOf(
                model("vendor-a/GLM-5.3-Flash", "GLM-5.3-Flash"),
                model("vendor-b/GLM-5.3-Flash", "GLM-5.3-Flash"),
                model("vendor-c/glm-5.3-flash", "GLM-5.3-Flash"),
            ),
        )
        assertEquals(
            listOf("GLM-5.3-Flash（vendor-a）", "GLM-5.3-Flash（vendor-b）", "GLM-5.3-Flash（vendor-c）"),
            renamed.map { it.displayName },
        )
        assertEquals(
            listOf("vendor-a/GLM-5.3-Flash", "vendor-b/GLM-5.3-Flash", "vendor-c/glm-5.3-flash"),
            renamed.map { it.modelId },
        )
    }

    @Test
    fun remoteDisplayNameEqualToRawIdDoesNotRepeatPrefix() {
        assertEquals(
            "sn-model-x-1017（vendor-c）",
            ProviderPrefixedModelNames.format("vendor-c/sn-model-x-1017", "vendor-c/sn-model-x-1017"),
        )
    }

    @Test
    fun multiSegmentIdUsesFirstSegmentAsProvider() {
        assertEquals(
            "reflection/beam-1.0-501b-a23b:free（vendor-a）",
            ProviderPrefixedModelNames.format(
                "reflection/beam-1.0-501b-a23b:free",
                "vendor-a/reflection/beam-1.0-501b-a23b:free",
            ),
        )
    }

    @Test
    fun repeatedPullIsIdempotent() {
        val once = ProviderPrefixedModelNames.format("DeepSeek-V4.1-Flash", "vendor-a/deepseek-v4.1-flash")
        assertEquals(once, ProviderPrefixedModelNames.format(once, "vendor-a/deepseek-v4.1-flash"))
    }

    @Test
    fun idsWithoutProviderPrefixAreUntouched() {
        assertEquals("GPT-6.1 Sol", ProviderPrefixedModelNames.format("GPT-6.1 Sol", "gpt-6.1-sol"))
        assertEquals("glm-4.6", ProviderPrefixedModelNames.format("glm-4.6", "glm-4.6"))
        assertEquals("vendor-a", ProviderPrefixedModelNames.format("vendor-a", "vendor-a/"))
    }

    @Test
    fun officialProviderKeepsModelFamilyPrefix() {
        val siliconflow = OpenAiCompatibleProviderSetting(
            id = "builtin-siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            sourceType = ProviderSourceTypes.SILICONFLOW,
        )
        assertEquals(
            listOf("Qwen/Qwen2.5-7B-Instruct"),
            ProviderPrefixedModelNames.apply(siliconflow, listOf(model("Qwen/Qwen2.5-7B-Instruct")))
                .map { it.displayName },
        )
    }
}