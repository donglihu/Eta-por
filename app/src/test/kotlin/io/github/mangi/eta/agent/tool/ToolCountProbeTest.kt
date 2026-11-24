package io.github.mangi.eta.agent.tool

import org.junit.Test

/** 临时探针：打印 AgentToolRequirements.toolNames 的真实大小与清单，用于逆向比对口径校准。 */
class ToolCountProbeTest {
    @Test
    fun printToolNames() {
        val names = AgentToolRequirements.toolNames
        println("=== TOOL_COUNT_START ===")
        println("COUNT=${names.size}")
        names.sorted().forEach { println("TOOL=$it") }
        println("=== TOOL_COUNT_END ===")
    }
}
