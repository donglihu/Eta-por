package io.github.mangi.eta.agent.tool

/**
 * 逐工具开关的纯逻辑：目录过滤与执行侧拒绝共用同一份判定。
 *
 * 这里刻意不依赖 Android 与 org.json：工具目录的过滤发生在 Schema 数组上，但「某个工具名
 * 该不该出现」本身与 JSON 无关，抽出来才能被纯 JVM 单测覆盖（arm64 上 Robolectric 会被
 * AssumptionViolatedException 静默跳过，落在 Robolectric 上的断言等于没写）。
 */
internal object AgentToolAvailability {

    /**
     * 运行时骨架工具：即使被写进禁用集合也必须保留。
     *
     * 计划清单、主动提问与记忆读写是模型「交付与自保」的最小闭环——被关掉之后会话会卡在
     * 出错就重试、需要用户拍板却无法提问的死循环里，用户也没法从工具能力页修回来。
     */
    val PROTECTED_TOOLS: Set<String> = setOf("update_plan", "ask_user", "memory_get", "memory_write")

    /** 工具是否可用：保护工具永远可用，其余看禁用集合（默认空集合 = 全开）。 */
    fun isToolEnabled(
        name: String,
        disabledTools: Set<String>,
        protectedTools: Set<String> = PROTECTED_TOOLS,
    ): Boolean = name.isNotBlank() && (name in protectedTools || name !in disabledTools)

    /** 按原顺序过滤工具名，去掉被禁用且不受保护的那些。 */
    fun filterEnabled(
        names: List<String>,
        disabledTools: Set<String>,
        protectedTools: Set<String> = PROTECTED_TOOLS,
    ): List<String> = names.filter { isToolEnabled(it, disabledTools, protectedTools) }
}
