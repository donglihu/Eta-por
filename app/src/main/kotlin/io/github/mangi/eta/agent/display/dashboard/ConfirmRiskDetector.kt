package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject

/**
 * 决策拦截器：在注入前判一次「这一下会不会花钱 / 泄隐私 / 不可逆」。
 *
 * 刻意做成纯函数：输入是「即将点到的控件文本」+「当前屏可见文本」，
 * 输出是一份待确认草稿。这样悬浮窗、流体云、单元测试共用同一套判定，
 * 不会出现「浮窗拦了、通知没拦」的口径分裂。
 *
 * 风险优先级：MONEY > PRIVACY > IRREVERSIBLE。命中即拦截，不做「大概没事就放行」。
 */
internal data class ConfirmDraft(
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
)

internal object ConfirmRiskDetector {

    private val moneyPattern = Regex("""(?:¥|￥|\$|USD|RMB)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""")
    private val accountPattern = Regex("""\b\d{12,19}\b""")
    private val phonePattern = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

    private val privacyWords = listOf("身份证", "验证码", "银行卡", "收货地址", "手机号", "密码", "人脸", "定位")
    /**
     * 不可撤销动作词表：**唯一一份**。
     *
     * 之前点击判定和页面级兜底各写一套，结果「立即付款」在兜底里能拦、点按钮时漏拦
     * （2026-11-06 真机实测踩到）。要加词只加这里，别在别的文件另起一份。
     */
    internal val irreversibleWords = listOf(
        "发送", "提交订单", "提交订单并支付", "确认提交", "同意并",
        "立即支付", "确认支付", "去支付", "立即付款", "确认付款", "去付款", "付款",
        "去结算", "结算", "立即购买", "确认下单", "下单",
        "转账", "删除", "清空", "注销", "退订", "解绑", "解约", "发布",
    )

    /** 供页面级兜底复用同一份词表。 */
    internal fun isIrreversible(text: String): Boolean = irreversibleWords.any { text.contains(it) }

    /** 终端类工具：命令本身就是判定依据。 */
    private val terminalTools = setOf("terminal", "terminal_job", "terminal_daemon")

    /** 命令分段：&&、||、;、| 与换行都要拆开逐段判，整条命令级豁免是漏洞。 */
    private val commandSeparators = Regex("""(?:&&|\|\||;|\n|\|)""")

    /** 递归/强制删除的两种写法：-r -R --recursive / -f --force。 */
    private val recursiveFlag = Regex("""^-(?=[A-Za-z]*[rR])[A-Za-z]+$|^--recursive$""")
    private val forceFlag = Regex("""^-(?=[A-Za-z]*f)[A-Za-z]+$|^--force$""")

    /** 命令前缀：跳过后再看真正的可执行名。 */
    private val commandPrefixes = setOf("sudo", "busybox", "env", "nice", "nohup", "time", "command")

    /** 会带 -c 的壳：里面的字符串才是真实命令，必须递归判定。 */
    private val shellWrappers = setOf("su", "sh", "bash", "ash", "dash", "zsh")

    /** 写这些位置等于改系统，出错不可回滚。 */
    private val protectedWritePrefixes = listOf(
        "/system", "/vendor", "/product", "/boot", "/data/adb",
        "/init.rc", "/default.prop", "/metadata", "/sdcard", "/storage",
    )

    /**
     * 非 GUI 工具的危险判定：只看工具名与参数。
     *
     * GUI 那条线由 [inspect] 处理（要屏上文本）；这里是另一条独立入口——
     * terminal 里一条 `rm -rf /data` 的破坏力不比误点「确认支付」小，
     * 而它原先完全不在拦截范围内。
     */
    fun inspectTool(toolName: String, args: JSONObject): ConfirmDraft? {
        if (toolName in terminalTools) {
            val command = args.optString("command").trim()
            if (command.isEmpty()) return null
            return analyzeCommand(command)
        }
        if (toolName == "write_file" || toolName == "edit_file") {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return null
            // 先规范化再比对：/data/./adb/x、/data//adb/x 都要还原成 /data/adb/x。
            // 早先是裸 startsWith，两个斜杠就能绕过（2026-11-08 审核发现）。
            val normalized = normalizePath(path)
            if (!isProtectedPath(normalized)) return null
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认写入系统目录",
                fields = listOf(ConfirmField("路径", path.take(80))),
            )
        }
        return null
    }

    /**
     * 命令判定：逐段拆、逐段判。
     *
     * 早先是「整条命令 contains 某个片段」，于是
     * `rm -rf /data/local/tmp/x && rm -rf /system` 只要命中临时目录豁免就整条放过。
     */
    private fun analyzeCommand(command: String): ConfirmDraft? {
        command.split(commandSeparators).forEach { raw ->
            val hit = analyzeSegment(normalizeCommand(raw))
            if (hit != null) return hit
        }
        return null
    }

    private fun analyzeSegment(segment: String): ConfirmDraft? {
        if (segment.isEmpty()) return null
        val tokens = segment.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var start = 0
        while (start < tokens.size && tokens[start] in commandPrefixes) start++
        if (start >= tokens.size) return null
        val cmd = tokens[start].substringAfterLast('/').lowercase()
        val rest = tokens.drop(start + 1)
        val inner = rest.indexOfFirst { it == "-c" || it == "--command" }
        if (inner >= 0 && cmd in shellWrappers && inner + 1 < rest.size) {
            // su -c "rm -rf /" / sh -c '…'：里面的字符串才是真实命令。
            return analyzeCommand(rest.drop(inner + 1).joinToString(" "))
        }
        val flags = rest.filter { it.startsWith("-") }
        val targets = rest.filterNot { it.startsWith("-") }
        return when {
            cmd == "rm" -> {
                val recursive = flags.any { recursiveFlag.matches(it) }
                val force = flags.any { forceFlag.matches(it) }
                if (!recursive && !force) null
                else if (targets.any { isProtectedPath(normalizePath(it)) }) draftOf("确认执行高危命令", segment)
                else null
            }
            cmd == "mkfs" || cmd.startsWith("mkfs.") -> draftOf("确认格式化分区", segment)
            cmd == "dd" && rest.any { it.startsWith("of=/dev/") } -> draftOf("确认写块设备", segment)
            cmd == "pm" && rest.firstOrNull()?.lowercase() == "clear" -> draftOf("确认清空应用数据", segment)
            cmd == "cmd" && rest.take(2).map { it.lowercase() } == listOf("package", "clear") ->
                draftOf("确认清空应用数据", segment)
            cmd == "fastboot" &&
                rest.firstOrNull()?.lowercase() in setOf("flash", "erase", "format", "oem", "update") ->
                draftOf("确认刷写设备", segment)
            cmd == "wipe" && rest.firstOrNull()?.lowercase() in setOf("data", "userdata") ->
                draftOf("确认擦除数据", segment)
            cmd == "chmod" && flags.any { recursiveFlag.matches(it) } &&
                targets.any { normalizePath(it) == "/" } -> draftOf("确认批量改权限", segment)
            cmd == "base64" && rest.any { it == "-d" || it == "--decode" } ->
                draftOf("确认解码后再执行", segment)
            segment.contains("> /dev/block") || segment.contains(">/dev/block") -> draftOf("确认写块设备", segment)
            else -> null
        }
    }

    private fun draftOf(title: String, segment: String) = ConfirmDraft(
        risk = ConfirmRisk.IRREVERSIBLE,
        title = title,
        fields = listOf(ConfirmField("命令", segment.take(80))),
    )

    /** 去引号、压空白：`rm  -rf   /` 与 `rm -rf /` 必须同判。 */
    private fun normalizeCommand(raw: String): String =
        raw.replace(Regex("""["']"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * 纯字符串路径规范化：吃掉 `//`、`./`、`../`。
     * 不碰文件系统，所以符号链接逃逸仍需真机侧另行校验；这里先堵住字符串绕过。
     */
    internal fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "~" || trimmed.startsWith("~/")) return trimmed
        val absolute = trimmed.startsWith("/")
        val stack = ArrayList<String>()
        trimmed.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> when {
                    stack.isNotEmpty() && stack.last() != ".." -> stack.removeAt(stack.size - 1)
                    !absolute -> stack.add("..")
                }
                else -> stack.add(part)
            }
        }
        val joined = stack.joinToString("/")
        return if (absolute) "/$joined" else joined.ifEmpty { "." }
    }

    /** 危险目标判定：根、家目录、变量、通配，以及 /data 整体；/data/local/tmp 属常规清理。 */
    private fun isProtectedPath(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        if (normalized == "/" || normalized == "*" || normalized == "/*" ||
            normalized == "~" || normalized.startsWith("~/") || normalized == ".."
        ) return true
        if (normalized.startsWith("$")) return true
        if (normalized.startsWith("/data/local/tmp")) return false
        if (normalized == "/data" || normalized.startsWith("/data/")) return true
        return protectedWritePrefixes.any { normalized == it || normalized.startsWith("$it/") }
    }

    fun inspect(target: String?, texts: List<String>): ConfirmDraft? {
        val tapped = target.orEmpty().trim()
        val visible = texts.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val haystack = (listOf(tapped) + visible).joinToString(" ")

        val money = moneyPattern.find(haystack)?.value
        val tappedIsSensitive = privacyWords.any { tapped.contains(it) } ||
            irreversibleWords.any { tapped.contains(it) }

        if (money != null && (tappedIsSensitive || irreversibleWords.any { tapped.contains(it) })) {
            return ConfirmDraft(
                risk = ConfirmRisk.MONEY,
                title = "确认支付",
                fields = collect(visible, keep = payeeOf(visible) + listOf("金额" to money) + amountLineOf(visible, money)),
            )
        }
        if (privacyWords.any { tapped.contains(it) } || phonePattern.containsMatchIn(tapped) || accountPattern.containsMatchIn(tapped)) {
            return ConfirmDraft(
                risk = ConfirmRisk.PRIVACY,
                title = "确认提交个人信息",
                fields = collect(
                    visible,
                    keep = listOf("动作" to tapped) + privacyOf(visible) + phoneOrAccountOf(haystack),
                ),
            )
        }
        if (isIrreversible(tapped)) {
            // 至少要写清「点在什么上」：屏上文本可能是空的（没 observe 过就直接点），
            // 那时 keep 里这一条就是用户唯一能看到的依据。
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认不可撤销操作",
                fields = collect(visible, keep = listOf("动作" to tapped)),
            )
        }
        return null
    }

    /** 最多 4 条，去重、截断，避免确认卡片自己变成一屏长文。 */
    private fun collect(visible: List<String>, keep: List<Pair<String, String>>): List<ConfirmField> {
        val fields = LinkedHashMap<String, String>()
        keep.forEach { (label, value) ->
            if (value.isNotBlank()) fields.putIfAbsent(label, value)
        }
        if (fields.size < 4) {
            visible.forEach { line ->
                if (fields.size >= 4) return@forEach
                val trimmed = line.take(60)
                if (trimmed.length in 2..60) fields.putIfAbsent("屏上", trimmed)
            }
        }
        return fields.entries.map { ConfirmField(it.key, it.value) }.take(4)
    }

    private fun payeeOf(visible: List<String>): List<Pair<String, String>> =
        visible.firstOrNull { it.contains("收款") || it.contains("商家") || it.contains("店铺") }
            ?.let { listOf("收款方" to it.take(40)) }
            .orEmpty()

    private fun amountLineOf(visible: List<String>, amount: String): List<Pair<String, String>> =
        visible.firstOrNull { it.contains(amount) && it != amount }
            ?.let { listOf("订单" to it.take(40)) }
            .orEmpty()

    private fun privacyOf(visible: List<String>): List<Pair<String, String>> =
        visible.mapNotNull { line ->
            when {
                phonePattern.containsMatchIn(line) -> "手机号" to (phonePattern.find(line)?.value ?: line)
                accountPattern.containsMatchIn(line) -> "账号" to (accountPattern.find(line)?.value ?: line)
                else -> null
            }
        }.distinctBy { it.first }.take(2)

    private fun phoneOrAccountOf(haystack: String): List<Pair<String, String>> {
        phonePattern.find(haystack)?.let { return listOf("手机号" to it.value) }
        accountPattern.find(haystack)?.let { return listOf("账号" to it.value) }
        return emptyList()
    }
}
