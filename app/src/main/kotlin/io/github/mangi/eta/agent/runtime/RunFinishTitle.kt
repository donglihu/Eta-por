package io.github.mangi.eta.agent.runtime

/**
 * 结束通知标题的三级回落。
 *
 * 顺序不能随便换：点停止时终态由 cancelRun 当场发布，它已经撤卡并摘掉 runningSessionInfo，
 * 执行线程事后排进主线程收尾时那两处都可能是空的，只有派发时随会话带走的快照还在。
 * 抽成纯函数是为了能在纯 JVM 单测里钉住优先级，不用起 Robolectric。
 */
internal object RunFinishTitle {
    fun resolve(sessionTitle: String, cardTitle: String, liveTitle: String): String =
        sessionTitle.ifBlank { cardTitle }.ifBlank { liveTitle }
}
