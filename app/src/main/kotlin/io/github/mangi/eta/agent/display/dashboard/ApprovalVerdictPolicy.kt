package io.github.mangi.eta.agent.display.dashboard

/**
 * 审批卡裁决的语义表：把「用户点了哪个出口」翻译成「工具该不该执行」。
 *
 * 为什么单独抽一个纯 object：这套判定原先散在 [AgentConfirmGuard.skip] /
 * [AgentConfirmGuard.chooseFreeform] / `AgentLocalTools` 三处，三处口径不一致就出现了
 * fail-open——用户点「跳过」本意是「别做这一步」，但 `skip()` 没把 `rejected` 传下去，
 * 工具侧读到 `rejected=false` 就把被拦的工具当真执行了（2026-11-26 审核 P0-2）。
 *
 * 抽出来之后有两点好处：
 * 1. 判定是纯函数，可以在纯 JVM 单测里逐条钉死，不依赖 Compose / Android 桩，
 *    也就不会被 arm64 上 Robolectric 的 `AssumptionViolatedException` 静默跳过；
 * 2. 三个出口共用同一张表，不会再出现「卡上说的是拒绝、工具收到的却是放行」。
 */
internal object ApprovalVerdictPolicy {

    /**
     * 「跳过」是否必须以拒绝语义（`ok=false` + `USER_REJECTED`）收尾。
     *
     * 两种来源语义完全不同，不能一刀切：
     * - guard 卡：卡是系统拦下来的**危险动作**，「跳过」= 我不同意做这一步 → 必须拒绝；
     * - ask 卡：卡是模型自己拿不准的**提问**，「跳过」= 这题我不想答，你自己往下走 → 保持放行。
     *
     * 判定用「不是 ask 就当 guard」的 fail-closed 方向：[PendingApproval.source] 的默认值
     * 与 [PendingPromptStore] 反序列化的缺省值都是 [PendingApproval.SOURCE_GUARD]，
     * 因此任何来源不明 / 旧数据 / 未来新增来源都会落到「拒绝」这一侧，而不是悄悄放行。
     */
    fun skipRejects(source: String): Boolean = source != PendingApproval.SOURCE_ASK

    /**
     * 「自由输入」是否必须以拒绝语义收尾。
     *
     * guard 卡上用户写的是**修改意见**，不是许可：原工具仍不能执行，必须让模型按意见
     * 重新规划、重新过一遍闸门。做成「带了文本就放行」正是这条 P0 的另一半——
     * 用户写了「别写 /system，改写到 /sdcard」，结果命令照样执行，等于意见被当成了批准。
     *
     * ask 卡上用户写的是**答案**，按答案继续即可，保持放行。
     */
    fun freeformRejects(source: String): Boolean = source != PendingApproval.SOURCE_ASK

    /**
     * 多选提交是否必须以拒绝语义收尾。
     *
     * guard 卡只有「批准 / 拒绝 / 我自己来 / 这类以后不问」四个内置项，勾中任何一个批准类
     * 选项才算放行；只勾拒绝类（或勾了但没勾批准类）一律按拒绝处理。
     * ask 卡的多选是模型自己给的选项，语义由模型定义，保持放行。
     *
     * 当前 guard 卡不开放多选（`allowMultiple` 恒为 false），这里是同类 fail-open 的兜底：
     * 将来放开多选时不必再回来补一次。
     */
    fun manyRejects(source: String, optionIds: List<String>): Boolean {
        if (source == PendingApproval.SOURCE_ASK) return false
        return optionIds.none { it == PromptOptionId.APPROVE || it == PromptOptionId.REMEMBER }
    }

    /** 工具侧对一次裁决的处置。 */
    enum class Disposition {
        /** 用户批准：放行执行原工具。 */
        EXECUTE,

        /** 用户拒绝 / 手动接管：以失败语义收尾，绝不执行。 */
        REJECTED,

        /** 没有有效裁决（超时 / 取消 / 回灌为空）：以 NEED_CONFIRM 收尾。 */
        NO_VERDICT,
    }

    /**
     * 把裁决对象翻译成工具侧该做什么。
     *
     * 判定顺序刻意与 `AgentLocalTools` 原有分支保持一致（先看回灌是否为空，再看 rejected），
     * 这样把散落的 if 收敛成一处时不改变既有行为，只补齐 `rejected` 这条真正该走的路径。
     */
    fun dispositionOf(outcome: VerdictOutcome?): Disposition = when {
        outcome == null -> Disposition.NO_VERDICT
        outcome.reply.isNullOrBlank() -> Disposition.NO_VERDICT
        outcome.rejected -> Disposition.REJECTED
        else -> Disposition.EXECUTE
    }

    /**
     * 「跳过」回灌给模型的文案。
     *
     * guard 卡用与 [AgentConfirmGuard.choose] 的 `REJECT` 分支同一套措辞：明确「没执行」，
     * 并显式禁止换方式重试——否则模型会把「跳过」理解成「没意见，继续」，换个坐标 / 换条命令
     * 再撞一次闸门，用户看到的就是「点了几次跳过，它还在做」。
     */
    fun skipReply(source: String, title: String): String = if (skipRejects(source)) {
        "用户拒绝了「$title」，本次调用已中止、没有执行。请停止这个操作，" +
            "不要换方式重试、不要用相同参数再次发起；说明当前进度和接下来可行的替代做法等他。"
    } else {
        "用户跳过了「$title」，没有选也没有回答。" +
            "不要重复问同一个问题，按你认为最合理的方式继续；若这一步不可撤销，就停下来说明为什么需要他决定。"
    }

    /**
     * 「自由输入」回灌给模型的文案。
     *
     * guard 卡：把用户写的内容当**修改意见**转达，同时钉死「原操作没有执行」，
     * 避免模型把意见里的动词当成新指令直接执行。
     * ask 卡：把用户写的内容当**答案**转达，与既有语义一致。
     */
    fun freeformReply(
        source: String,
        title: String,
        answer: String,
        extraLabels: List<String> = emptyList(),
    ): String {
        if (!freeformRejects(source)) {
            return if (extraLabels.isEmpty()) {
                "用户对「$title」自己填写了：$answer。请按这个回答继续，不要再问一遍。"
            } else {
                "用户对「$title」勾选了「${extraLabels.joinToString("、")}」，另外补充：$answer。" +
                    "请把勾选项和这段补充一起当作他的答案。"
            }
        }
        val picked = if (extraLabels.isEmpty()) "" else "他勾选了「${extraLabels.joinToString("、")}」，"
        return "用户拒绝了「$title」的这次执行，原操作没有执行。${picked}另外给出修改意见：$answer。" +
            "请把这段意见当作对方案的修改要求，重新规划；若仍要动这一步，按新方案重新发起" +
            "（会重新过一遍确认），不要用原参数直接重试。"
    }
}
