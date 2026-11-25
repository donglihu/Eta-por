package io.github.mangi.eta.agent.device

/**
 * 屏幕观察元数据：决定无障碍服务不可用时能否用 uiautomator dump 兜底。
 *
 * 上游（62e58edd 起）把它独立成 object 并用于 waitForText / scroll 的防回退判断；
 * 本地不引入上游 virtualdisplay 包（本地虚拟屏实现不同），只保留本会话会用到的
 * [mayUseUiAutomator]，其余展示/审计函数不搬（本地无 ShellActionOutcomePolicy）。
 */
internal object ScreenObservationMetadata {
    fun mayUseUiAutomator(
        requested: Boolean,
        accessibilityConnected: Boolean,
        rootAvailable: Boolean,
        virtualDisplay: Boolean,
    ): Boolean = requested && !accessibilityConnected && rootAvailable && !virtualDisplay
}