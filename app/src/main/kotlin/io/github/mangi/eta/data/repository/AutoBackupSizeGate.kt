package io.github.mangi.eta.data.repository

/**
 * 自动备份的体积闸门。
 *
 * 为什么需要它：全量导出会在一次广播回调里扫完整个私有目录。一旦用户勾了「含完整 Linux 环境」，
 * 扫的就是 chroot（本机实测 16 GB），必然超出广播的 10 秒预算，系统直接 ANR 杀进程，
 * 表现成「启动一会就闪退」且无限循环。这个闸门把「要不要真的打容器」从「用户勾了就打」
 * 改成「体积在预算内才打」，自动触发时超限降级为 JSON 备份，手动触发仍按用户意图执行。
 *
 * 阈值取 256 MB：本机 JSON 备份实测 25–58 MB，给到 4 倍余量；
 * 而 chroot 动辄数 GB，两者差两个数量级，不存在误判空间。
 */
internal object AutoBackupSizeGate {

    /** 自动触发的全量导出上限。超了不打包容器，只导 JSON。 */
    const val AUTOMATIC_FULL_LIMIT_BYTES: Long = 256L * 1024L * 1024L

    sealed interface Decision {
        /** 按请求的形态导出（全量或 JSON）。 */
        data object Full : Decision

        /** 降级成 JSON 备份，附上判定依据。 */
        data class DegradeToJson(val estimatedBytes: Long, val limitBytes: Long) : Decision
    }

    /**
     * [requestedFull] 用户是否要全量包；[automatic] 是否由闹钟/启动自动触发；
     * [estimatedBytes] 预计要打包的字节数，无法估算时传 0（按放行处理，避免误伤）。
     */
    fun decide(
        requestedFull: Boolean,
        automatic: Boolean,
        estimatedBytes: Long,
        limitBytes: Long = AUTOMATIC_FULL_LIMIT_BYTES,
    ): Decision {
        if (!requestedFull) return Decision.Full
        if (!automatic) return Decision.Full
        if (estimatedBytes <= 0L) return Decision.Full
        if (estimatedBytes <= limitBytes) return Decision.Full
        return Decision.DegradeToJson(estimatedBytes, limitBytes)
    }
}
