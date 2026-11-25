package io.github.mangi.eta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.ui.model.toolCardRequirementOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frida 卡与图标覆盖的纯 JVM 断言（不加 @RunWith，arm64 上不会被 assume 跳过）。
 *
 * ToolCatalogUiTest 整类落在 Robolectric 上，本机直接 skipped=1，等于没跑；
 * 这里把其中与 frida 卡相关的两条纯逻辑捞出来单独钉住。
 */
class ToolIconCoverageTest {

    @Test
    fun `frida card has no execution requirement but has a dedicated icon`() {
        // 后端未集成：查不到执行要求，所以「当前设备」视图不会列它，只出现在「全部能力」。
        assertNull(toolCardRequirementOrNull("frida"))
        assertNull(AgentToolRequirements.find("frida"))
        // 但卡片本身有图标，不能落在 Build 兜底上（ToolCatalogUiTest 的图标断言同口径）。
        assertNotEquals(Icons.Rounded.Build, iconForTool("frida"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }
}
