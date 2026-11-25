package io.github.mangi.eta.agent.automation

/**
 * 保活的状态判定，单独抽成纯函数。
 *
 * 为什么要抽：这两条判定原先内联在 [AgentKeepaliveService] 的 `onStartCommand` /
 * `onTaskRemoved` 里，跟 Android 生命周期绑定，既没法回归、也容易被后来人改回去。
 * 抽出来以后行为是可断言的：同样的输入给同样的结论，单测能锁住语义。
 */
internal object KeepalivePolicy {

    /**
     * `onStartCommand` 里的自停判定。
     *
     * 三个条件必须同时成立：显式指令（intent 不为空）、未开常驻、没有在跑的会话。
     * 系统按 `START_STICKY` 重建时 intent 为 null，绝不能走这条——否则重建即自停，
     * STICKY 就白设了。
     */
    fun shouldStopAfterCommand(
        hasExplicitAction: Boolean,
        persistent: Boolean,
        sessionCount: Int,
    ): Boolean = hasExplicitAction && !persistent && sessionCount <= 0

    /**
     * `onTaskRemoved`（用户从最近任务里划掉）是否停服。
     *
     * 常驻开关是用户显式打开的，那就尊重它：划掉任务不停服。
     * 没开常驻时保持原语义——划掉就是明确要它停，不硬撑。
     */
    fun shouldStopOnTaskRemoved(keepaliveEnabled: Boolean): Boolean = !keepaliveEnabled
}