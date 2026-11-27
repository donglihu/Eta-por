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
    /**
     * 挂卡后由 [io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard.register] 回填的审批 token：
     * 工具线程用它阻塞等待用户裁决，点选后以裁决文本继续同一 run。
     */
    val token: String = "",
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

    /**
     * 未知工作目录哨兵（空串）。
     *
     * 空串表示「没有可信的 cd 线索」，此时相对路径退回修复前的口径（不解析成绝对路径）。
     * 用空串而不是 `/` 是有意的：若当成 `/`，`cd .. && cp a b` 会把 `b` 解析成 `/b`
     * 再按「根目录」判成危险，属于误伤；当成未知则放行，与旧行为一致。
     */
    private const val unknownCwd = ""

    /**
     * `cd` 不带参数 / `cd ~` 的落点：应用私有家目录。
     *
     * 它不是系统分区，所以 `cd && cp a b` 这种写法不会被拦。写死常量是因为这里
     * 只做纯字符串判定，不读环境变量（判定要在任意线程、任意时刻可重放）。
     */
    private const val homeCwd = "/data/user/0/io.github.mangi.eta"

    /**
     * 「执行过 cd，但落点静态不可知」哨兵。
     *
     * 与 [unknownCwd] 的区别是语义：空串表示**没有 cd 线索**，相对路径退回修复前口径放行；
     * 本哨兵表示**确实换过目录**（`cd -`、`cd $DIR`），只是算不出在哪。
     * 后者的相对路径写入可能落在任何分区，按核心记忆「无法确定时视为可疑而不是放过」处理，
     * 只拦写入、不拦只读（`cd - && cat foo` 不受影响）。
     *
     * 取值含 NUL，[normalizePath] 永远产出不了它，不会被外部命令构造成同一值。
     */
    private const val movedUnknownCwd = "\u0000unresolved-cwd"

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
     * 只算「改系统」的写入位置（不含 /sdcard、/storage）。
     *
     * 共享存储是用户自己的地界，往里放文件不是提权面；若按 [protectedWritePrefixes]
     * 判，`cp report.md /sdcard/Download/` 这类日常操作每次都要弹卡。卡一多用户就会
     * 无脑点批准，等于把闸门关掉。删除（rm）仍按原口径，那里误删的代价不一样。
     */
    private val systemWritePrefixes = listOf(
        "/system", "/vendor", "/product", "/boot", "/data/adb",
        "/init.rc", "/default.prop", "/metadata",
    )

    /**
     * 内核伪文件系统：往 /proc、/sys 写等于直接改内核运行时行为。
     *
     * 单独一档而不是塞进 [systemWritePrefixes]：口径不同——这里「任何写入都拦」，
     * 而系统分区还要区分读与写（`cat /proc/cpuinfo`、`ls /sys/class` 属日常只读）。
     * 前缀匹配必须带边界，否则 `/system` 会被 `/sys` 误命中。
     */
    private val kernelPseudoPrefixes = listOf("/proc", "/sys")

    /** 写类动词：按「动词 + 目标落在系统位置」判，不再逐个枚举参数形态。 */
    private val writeVerbs = setOf(
        "cp", "mv", "ln", "tee", "install", "rsync", "sed", "chmod", "chown", "truncate",
        // 建目录/建文件本身就是 /data/adb 持久化的第一步：`mkdir -p /data/adb/modules/x`
        // 之后模块目录结构就位，重启即生效。只判系统前缀，共享存储不受影响。
        "mkdir", "rmdir", "touch", "mknod",
    )

    /** `>`、`>>`、`2>`、`2>>`：重定向目标就是写入位置。 */
    private val redirectToken = Regex("""^\d*>>?$""")

    /** 无空格写法：`>/system/x`、`2>/system/x`。 */
    private val inlineRedirect = Regex("""^\d*>>?(.+)$""")

    /** 重启类命令：本身就是系统级不可逆动作，与参数无关。 */
    private val rebootCommands = setOf("reboot", "poweroff", "halt", "shutdown")

    /** init 服务开关：`stop`/`start` 直接停启系统服务（`grep stop` 不受影响，判定看命令名）。 */
    private val initServiceCommands = setOf("stop", "start")

    /** 内核模块装载：改的是内核态行为，用户态回滚不了。 */
    private val kernelModuleCommands = setOf("insmod", "rmmod", "modprobe")

    /** `pm` 下会改变应用状态（卸载 / 冻结 / 清数据）的子命令。 */
    private val pmDestructiveSubcommands = setOf("uninstall", "disable", "disable-user", "clear")

    /** 带值的选项：解析子命令时要连值一起跳过，否则 `pm --user 0 uninstall` 会漏判。 */
    private val optionsWithValue = setOf("--user", "-u", "--user-type", "--display")

    /** `svc power` 下的关机类动作。 */
    private val svcPowerDestructive = setOf("reboot", "shutdown", "restart")

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
            return analyzeCommand(command, startCwdOf(args))
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
     * 工具调用声明的起始工作目录。
     *
     * `terminal` 系工具的 schema 带 `cwd`（见 `AgentTerminalToolCatalog.executionProperties`），
     * 传绝对路径时它是可信线索：`cwd=/system` + `cp a b` 的 `b` 实际落在 `/system/b`，
     * 不读它就等于漏判。
     *
     * 相对 cwd（`cwd=sub`）不可信——它又依赖另一个未知基准，继续追只会猜错方向，
     * 所以退回 [unknownCwd]：相对路径保持不解析，与「没有 cd 线索」同口径。
     */
    private fun startCwdOf(args: JSONObject): String {
        val raw = args.optString("cwd").trim()
        return if (raw.startsWith("/")) normalizePath(raw) else unknownCwd
    }

    /**
     * 命令判定：先按引号感知的规则拆段，再逐段判，并让 `cd` 的工作目录跨段传递。
     *
     * 早先是「整条命令 contains 某个片段」，于是
     * `rm -rf /data/local/tmp/x && rm -rf /system` 只要命中临时目录豁免就整条放过。
     *
     * 现在多两件事：
     * 1. **拆段要在引号外**。`su -c 'cd /system && cp x y'` 里的 `&&` 属于内层命令，
     *    按字面拆会把壳命令和内层命令切碎，导致递归入口拿到的不是完整内层命令。
     * 2. **cd 状态跨段传递**。`cd /system && cp /sdcard/x y` 的 `y` 落在 /system，
     *    早先逐段独立判定、`normalizePath("y")` 不以 `/` 开头，于是直接放过
     *    （2026-11-26 审核 P0-1）。cwd 只在本次 analyzeCommand 内有效，
     *    递归进 `su -c`/`sh -c` 的内层命令时重新从「未知」起算——内层 shell 是新进程，
     *    外层 cd 不影响它，反过来也不影响。
     */
    private fun analyzeCommand(command: String, initialCwd: String = unknownCwd): ConfirmDraft? {
        var cwd = initialCwd
        splitSegments(command).forEach { raw ->
            val tokens = tokenize(raw)
            if (tokens.isEmpty()) return@forEach
            // cd 先处理：它只改状态，本身不是危险动作（`cd /system` 只读）。
            val cwdUpdate = cdUpdateOf(tokens, cwd)
            if (cwdUpdate != null) {
                cwd = cwdUpdate
                return@forEach
            }
            val hit = analyzeSegment(tokens, raw, cwd)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 单段判定。判定优先级（命中即返回，后面的不再看）：
     *
     * 1. **内核伪文件写入**（`> /proc/...`、`tee /sys/...`）——与动词无关，先看落点。
     * 2. **系统级不可逆动作**：remount rw、setenforce 0、reboot、init 服务开关、
     *    内核模块、`pm uninstall/disable/clear`、`settings put`。这些与路径无关，
     *    按「命令名 + 子命令」判，才挡得住 `mount -o rw,remount`、`pm --user 0 uninstall`
     *    这类参数变体（只加字符串黑名单会被选项顺序绕过）。
     * 3. **破坏性文件操作**：rm -rf、mkfs、dd of=、chmod -R /、base64 -d。
     * 4. **写系统分区**：写动词 + 目标解析后落在 [systemWritePrefixes]。
     *
     * 为什么不把第 2 类做成「路径黑名单」：`setenforce 0`、`reboot` 没有路径参数，
     * 靠路径永远判不出来；而 `mount` 的可写重挂载目标可以是任意分区，
     * 按「任意分区」拦才符合核心记忆 §3 的口径。
     */
    private fun analyzeSegment(tokens: List<String>, segment: String, cwd: String): ConfirmDraft? {
        // 重定向先判：`echo x > /system/build.prop` 的写入位置在重定向符号之后，
        // 与动词无关；`> /system/x` 这种没有任何动词的写法也要拦。
        // 目标按 cwd 解析：`cd /system && echo hi > build.prop` 写的是 /system/build.prop。
        redirectTarget(tokens)?.let { target ->
            val resolved = resolvePath(target, cwd)
            if (isKernelPseudoWritePath(resolved)) return draftOf("确认写入内核参数", segment)
            if (isSystemWritePath(resolved)) return draftOf("确认写入系统目录", segment)
            if (isUnresolvedRelativeWrite(target, cwd)) return draftOf("确认写入未知目录", segment)
        }
        var start = 0
        while (start < tokens.size && tokens[start] in commandPrefixes) start++
        if (start >= tokens.size) return null
        val cmd = tokens[start].substringAfterLast('/').lowercase()
        val rest = tokens.drop(start + 1)
        // 组合短选项：`bash -lc '…'`、`sh -ec '…'`、`su -xc '…'` 都等价于 -c。
        // 早先只认字面 -c，套一层 -lc 就能让危险命令整条不被验算（2026-11-26 审核）。
        val inner = rest.indexOfFirst { it == "--command" || isCommandFlag(it) }
        if (inner >= 0 && cmd in shellWrappers && inner + 1 < rest.size) {
            // su -c "rm -rf /" / sh -c '…'：里面的字符串才是真实命令。
            // 子进程继承父 shell 的 cwd，所以把当前 cwd 传进去：`cd /system && sh -c 'cp /sdcard/x y'`
            // 的 `y` 实际落在 /system，早先从「未知」起算于是整条放过（2026-11-26 审核 P0-1 续）。
            return analyzeCommand(rest.drop(inner + 1).joinToString(" "), cwd)
        }
        val flags = rest.filter { it.startsWith("-") }
        val targets = rest.filterNot { it.startsWith("-") }
        return when {
            // ── 2. 系统级不可逆动作：与路径无关，按命令名/子命令判 ──
            cmd in rebootCommands -> draftOf("确认重启设备", segment)
            cmd == "svc" && svcPowerDestructive.any { sub ->
                rest.take(2).map { it.lowercase() } == listOf("power", sub)
            } -> draftOf("确认重启设备", segment)
            cmd == "am" && rest.firstOrNull()?.lowercase() == "restart" -> draftOf("确认重启设备", segment)
            // 仅当 `stop`/`start` 是独立命令时拦；`grep stop file` 的 cmd 是 grep，不受影响。
            cmd in initServiceCommands -> draftOf("确认停启系统服务", segment)
            cmd == "setenforce" && targets.firstOrNull()?.lowercase() in setOf("0", "permissive") ->
                draftOf("确认关闭 SELinux 强制模式", segment)
            cmd == "mount" && mountRemountsWritable(rest) -> draftOf("确认以可写方式重挂系统分区", segment)
            cmd in kernelModuleCommands -> draftOf("确认装载内核模块", segment)
            cmd == "pm" && subcommandOf(rest) in pmDestructiveSubcommands ->
                draftOf("确认改变应用安装状态", segment)
            cmd == "cmd" && rest.take(2).map { it.lowercase() } == listOf("package", "clear") ->
                draftOf("确认清空应用数据", segment)
            cmd == "cmd" && rest.firstOrNull()?.lowercase() == "package" &&
                subcommandOf(rest.drop(1)) in pmDestructiveSubcommands ->
                draftOf("确认改变应用安装状态", segment)
            cmd == "settings" && targets.firstOrNull()?.lowercase() == "put" ->
                draftOf("确认改写系统设置", segment)
            // ── 3. 破坏性文件操作 ──
            cmd == "rm" -> {
                val recursive = flags.any { recursiveFlag.matches(it) }
                val force = flags.any { forceFlag.matches(it) }
                if (!recursive && !force) null
                else if (targets.any { isProtectedPath(resolvePath(it, cwd)) }) draftOf("确认执行高危命令", segment)
                else null
            }
            cmd == "mkfs" || cmd.startsWith("mkfs.") -> draftOf("确认格式化分区", segment)
            cmd == "dd" && rest.any { it.startsWith("of=/dev/") } -> draftOf("确认写块设备", segment)
            cmd == "fastboot" &&
                rest.firstOrNull()?.lowercase() in setOf("flash", "erase", "format", "oem", "update") ->
                draftOf("确认刷写设备", segment)
            cmd == "wipe" && rest.firstOrNull()?.lowercase() in setOf("data", "userdata") ->
                draftOf("确认擦除数据", segment)
            cmd == "chmod" && flags.any { recursiveFlag.matches(it) } &&
                targets.any { normalizePath(it) == "/" } -> draftOf("确认批量改权限", segment)
            cmd == "base64" && rest.any { it == "-d" || it == "--decode" } ->
                draftOf("确认解码后再执行", segment)
            // ── 4. 写动词 + 目标落在系统位置 ──
            // `cp`/`mv`/`ln -s`/`tee`/`sed -i`/`chown` 等同样能完成持久化，
            // 早先只拦 rm/mkfs/dd，这批全放行（2026-11-26 审核）。
            cmd in writeVerbs && sedWrites(rest, cmd) &&
                writeTargets(rest, cmd).any {
                    isSystemWritePath(resolvePath(it, cwd)) || isUnresolvedRelativeWrite(it, cwd)
                } ->
                draftOf("确认改写系统文件", segment)
            cmd in writeVerbs && sedWrites(rest, cmd) &&
                writeTargets(rest, cmd).any { isKernelPseudoWritePath(resolvePath(it, cwd)) } ->
                draftOf("确认写入内核参数", segment)
            cmd == "dd" && rest.firstOrNull { it.startsWith("of=") }
                ?.let { isSystemWritePath(resolvePath(it.substringAfter("of="), cwd)) } == true ->
                draftOf("确认写入系统文件", segment)
            segment.contains("> /dev/block") || segment.contains(">/dev/block") -> draftOf("确认写块设备", segment)
            else -> null
        }
    }

    /** `sed` 只有带 `-i`（原地改写）才算写操作；不带 `-i` 只往 stdout 输出。 */
    private fun sedWrites(rest: List<String>, cmd: String): Boolean =
        cmd != "sed" || rest.any { it == "-i" || it.startsWith("-i") || it == "--in-place" || it.startsWith("--in-place=") }

    /** `-c`、`-lc`、`-ec`、`-xc`、`--command`：任意含 c 的组合短选项都算「执行命令串」。 */
    private fun isCommandFlag(token: String): Boolean =
        token.length >= 2 && token[0] == '-' && token[1] != '-' && 'c' in token.substring(1)

    /**
     * 子命令提取：跳过选项与带值选项的值，取第一个真正的子命令。
     *
     * `pm uninstall x` → `uninstall`；`pm --user 0 uninstall x` → `uninstall`。
     * 早先只取 `rest.firstOrNull()`，前面插一个选项就整条漏判。
     */
    private fun subcommandOf(rest: List<String>): String? {
        var index = 0
        while (index < rest.size) {
            val token = rest[index]
            if (token in optionsWithValue) {
                index += 2
                continue
            }
            if (token.startsWith("-")) {
                index += 1
                continue
            }
            return token.lowercase()
        }
        return null
    }

    /**
     * `mount` 是否在把某个分区挂成可写。
     *
     * 覆盖三种写法：`-o remount,rw`、`-o rw,remount`、`-o remount`（无显式 rw）。
     * 只要 `-o` 选项里出现 `remount` 或 `rw` 就算——remount rw 是核心记忆 §3
     * 明令禁止的动作，且目标可以是任意分区，所以不按路径过滤。
     * 已知代价：`mount -o rw,loop img /mnt` 也会弹卡；这类写法在手机侧几乎不出现，
     * 宁可多弹一次也不放过 remount。
     */
    private fun mountRemountsWritable(rest: List<String>): Boolean {
        rest.forEachIndexed { index, token ->
            val optionValue = when {
                token == "-o" || token == "--options" -> rest.getOrNull(index + 1)
                token.startsWith("-o") && token.length > 2 -> token.substring(2)
                token.startsWith("--options=") -> token.substringAfter("--options=")
                else -> null
            } ?: return@forEachIndexed
            val options = optionValue.split(',')
            if (options.any { it == "remount" || it == "rw" }) return true
        }
        return rest.any { it.contains("remount") }
    }

    /** 重定向目标：`> /system/x` 里的 `/system/x`；`>file` 与 `2>/dev/null` 同样提取。 */
    private fun redirectTarget(tokens: List<String>): String? {
        tokens.forEachIndexed { index, token ->
            if (!redirectToken.matches(token)) return@forEachIndexed
            val next = tokens.getOrNull(index + 1)
            if (!next.isNullOrBlank()) return next
        }
        // 无空格写法：`echo x >/system/build.prop`、`cmd 2>/system/x`
        tokens.forEach { token ->
            val inline = inlineRedirect.find(token)?.groupValues?.get(1)
            if (!inline.isNullOrBlank()) return inline
        }
        return null
    }

    /**
     * 写动词的作用目标。
     *
     * `cp`/`mv`/`install`/`rsync`/`ln` 的目标是最后一个非选项参数；
     * `tee` 的目标是所有非选项参数；`sed -i` 改的是它读的文件；
     * `chmod`/`chown` 的第一个非选项参数是权限表达式，其后才是文件。
     */
    private fun writeTargets(rest: List<String>, cmd: String): List<String> {
        val operands = rest.filterNot { it.startsWith("-") }
        return when (cmd) {
            "ln", "cp", "mv", "install", "rsync" -> operands.lastOrNull()?.let { listOf(it) }.orEmpty()
            "chmod", "chown" -> operands.drop(1)
            else -> operands
        }
    }

    /**
     * 相对路径的可能真实基准。
     *
     * `terminal` 省略 `cwd` 时 shell 从真实工作区启动，而这个工作区随执行环境不同：
     * Linux 环境是 `/data/local/tmp/eta`，Android 默认身份是 App 私有 `files/terminal-user/workspace`。
     * 判定层拿不到当前身份，所以把已知的几种基准都算一遍——**任一落点命中系统路径就按可疑处理**。
     *
     * 只做「向上跳几层」的近似是不够的：`cd ../../../adb` 从 `/data/local/tmp/eta` 退三层正好落在
     * `/data/adb`（危险），从 App 工作区退三层只到 App 私有目录（安全）。同一串命令两种落点，
     * 取「任一危险即拦」才不会漏（2026-11-27 审核：相对穿越可绕过 `/data/adb` 写入拦截）。
     */
    private val candidateBaseDirs = listOf(
        "/data/local/tmp/eta",
        "/data/user/0/io.github.mangi.eta/files/terminal-user/workspace",
        homeCwd,
    )

    /**
     * 相对路径按每个候选基准解析，任一落点落在系统分区或内核伪文件系统即返回 true。
     *
     * 只在「基准未知」时使用：基准已知时相对路径能唯一解析，走 [isSystemWritePath] 即可。
     */
    private fun relativeHitsSystemPath(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("/")) return false
        if (trimmed == "~" || trimmed.startsWith("~/")) return false
        return candidateBaseDirs.any { base ->
            val resolved = normalizePath("$base/$trimmed")
            isSystemWritePath(resolved) || isKernelPseudoWritePath(resolved)
        }
    }

    /** 写入位置落在系统分区（不含共享存储）：与删除判定分开，避免日常操作被拦。 */
    private fun isSystemWritePath(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        return systemWritePrefixes.any { normalized == it || normalized.startsWith("$it/") }
    }

    /**
     * 落点是否在内核伪文件系统里。
     *
     * 必须带边界：`"/system".startsWith("/sys")` 为真，裸 startsWith 会把整个
     * /system 分区当成 /sys 的子路径，两个判定就串味了。
     */
    private fun isKernelPseudoWritePath(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        return kernelPseudoPrefixes.any { normalized == it || normalized.startsWith("$it/") }
    }

    /**
     * 相对路径按当前工作目录解析成绝对路径。
     *
     * 只有「已知 cwd」才解析相对路径；cwd 未知时保持原样（与修复前口径一致），
     * 这样 `cp a b` 这种没有 cd 线索的命令不会被误判。
     * `~` 归一到家目录；含 `$`/通配符的路径无法静态求值，保持原样（见已知边界）。
     */
    private fun resolvePath(raw: String, cwd: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "~" || trimmed.startsWith("~/")) {
            return normalizePath(homeCwd + trimmed.removePrefix("~"))
        }
        if (trimmed.startsWith("/")) return normalizePath(trimmed)
        if (trimmed.startsWith("$") || trimmed.contains('*') || trimmed.contains('?')) {
            return normalizePath(trimmed)
        }
        if (cwd == unknownCwd || cwd == movedUnknownCwd) return normalizePath(trimmed)
        return normalizePath("$cwd/$trimmed")
    }

    /**
     * 相对路径 + 「cd 过但落点未知」= 可疑写入。
     *
     * 绝对路径与 `~` 路径落点已知，各有自己的判定（[isSystemWritePath]），这里只管
     * 那些靠 cwd 才能定位、而 cwd 又算不出来的目标。只读命令不走这个分支，
     * 所以 `cd - && cat foo` 不会被误拦。
     */
    private fun isUnresolvedRelativeWrite(raw: String, cwd: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("/")) return false
        if (trimmed == "~" || trimmed.startsWith("~/")) return false
        // 确实 cd 过但落点算不出：相对写入可能落在任何分区，按可疑处理。
        if (cwd == movedUnknownCwd) return true
        // 基准未知不等于「一定安全」：`mkdir -p ../../../adb/modules/x` 的 `..` 能穿到 /data/adb。
        // 早先这里直接放行，等于给相对穿越开了后门（2026-11-27 审核 P0）。
        // 现在按候选基准解析，任一落点命中系统路径就拦；不含 `..` 的日常目标仍然放行。
        if (cwd == unknownCwd) return relativeHitsSystemPath(trimmed)
        return false
    }

    /**
     * `cd` 段的工作目录更新；不是 cd 命令时返回 null。
     *
     * `cd` / `cd ~` 去家目录；`cd -` 回上一个目录——这里不追历史，直接标记为未知
     * （宁可少判一次也不猜错方向）。相对参数按当前 cwd 解析，于是
     * `cd /system && cd ..` 会收敛到 `/`，`cd /system && cp x ../system/y` 会收敛到
     * `/system/y`（路径规范化吃掉 `..`），逃逸写法不会因为多绕一层就漏判。
     *
     * 无法求值的只有四种：`cd -`、`cd $DIR`、`cd` 带通配符（`*`、`?`）的目标标
     * [movedUnknownCwd]（确实换过目录、落点算不出，按可疑处理）；
     * 相对 cd 且 cwd 已不可知时保持 [unknownCwd]
     * （相对路径不解析，按无线索处理），否则日常 `cd sub` 会全部误拦。
     */
    private fun cdUpdateOf(tokens: List<String>, cwd: String): String? {
        var start = 0
        while (start < tokens.size && tokens[start] in commandPrefixes) start++
        if (start >= tokens.size) return null
        if (tokens[start].substringAfterLast('/').lowercase() != "cd") return null
        val args = tokens.drop(start + 1)
        // `cd -` 的 `-` 会被下面的选项过滤吃掉，必须先单独认出来，
        // 否则它退化成「cd 无参数」被当成家目录，`cd - && cp a b` 就整条放过了。
        if (args.firstOrNull() == "-") return movedUnknownCwd
        val operands = args.filterNot { it.startsWith("-") }
        val target = operands.firstOrNull() ?: return homeCwd
        if (target == "~" || target.startsWith("~/")) {
            return normalizePath(homeCwd + target.removePrefix("~"))
        }
        if (target.startsWith("/")) return normalizePath(target)
        // `cd $DIR` / `cd */x`：落点不可静态求值，但确实换了目录，标为可疑。
        if (target.startsWith("$") || target.contains('*') || target.contains('?')) return movedUnknownCwd
        // 相对 cd 且当前 cwd 未知：落点取决于 shell 启动目录，算不出绝对位置。
        // 这里必须**保持未知**而不是升级成 movedUnknownCwd：任何 `cd sub && cp a b`
        // 在首段执行时 cwd 都是未知，升级就等于把日常写入全部弹确认卡
        // （2026-11-27 审核实测 6/6 误拦：cd .. / cd sub / cd src / cd app / cd build …）。
        // 卡一多用户就无脑点批准，闸门比漏判更早失效。
        if (cwd == unknownCwd) {
            // 相对 cd 且基准未知：按候选基准解析落点。
            // `cd ..` \/ `cd sub` 在任一基准下都不落系统分区，继续放行（日常写入不误拦）；
            // `cd ../../../adb` 在 Linux 工作区下正好落 /data/adb，升级为可疑，后续写入被拦。
            return if (relativeHitsSystemPath(target)) movedUnknownCwd else unknownCwd
        }
        // 已经是「cd 过但落点不可知」时继续保持可疑：`cd - && cd .. && cp a b`
        // 的落点仍然算不出来，降级成未知等于把已知的怀疑洗掉。
        if (cwd == movedUnknownCwd) return movedUnknownCwd
        return normalizePath("$cwd/$target")
    }

    /**
     * 按引号拆段：`&&`、`||`、`;`、`|`、换行在引号内不算分隔符。
     *
     * 引号字符保留在段里，交给 [tokenize] 去配对；否则
     * `su -c 'cd /system && cp x y'` 会被字面 `&&` 切成两段，
     * 递归拿到的内层命令不完整，cd 状态和内层写入全丢。
     */
    private fun splitSegments(command: String): List<String> {
        val segments = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < command.length) {
            val ch = command[index]
            when {
                quote != null -> {
                    if (ch == quote) quote = null
                    current.append(ch)
                    index++
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    current.append(ch)
                    index++
                }
                ch == '\\' && index + 1 < command.length -> {
                    current.append(ch).append(command[index + 1])
                    index += 2
                }
                ch == '&' && command.getOrNull(index + 1) == '&' -> {
                    segments.add(current.toString())
                    current.setLength(0)
                    index += 2
                }
                ch == '|' && command.getOrNull(index + 1) == '|' -> {
                    segments.add(current.toString())
                    current.setLength(0)
                    index += 2
                }
                ch == '|' || ch == ';' || ch == '\n' -> {
                    segments.add(current.toString())
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(ch)
                    index++
                }
            }
        }
        segments.add(current.toString())
        return segments
    }

    /**
     * 按空白分词，引号内不切分，引号本身剥掉。
     *
     * 与 [splitSegments] 配套：`su -c 'cd /system && cp x y'` 切成
     * `[su, -c, cd /system && cp x y]`，内层命令作为一个整体交给递归。
     * 这样去引号与分词合成一步，不再有「先无脑 replace 引号再 split」把内层命令切碎的问题。
     */
    private fun tokenize(segment: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < segment.length) {
            val ch = segment[index]
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                    index++
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    started = true
                    index++
                }
                ch == '\\' && index + 1 < segment.length -> {
                    current.append(segment[index + 1])
                    started = true
                    index += 2
                }
                ch == ' ' || ch == '\t' || ch == '\r' -> {
                    if (started || current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        started = false
                    }
                    index++
                }
                else -> {
                    current.append(ch)
                    started = true
                    index++
                }
            }
        }
        if (started || current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun draftOf(title: String, segment: String) = ConfirmDraft(
        risk = ConfirmRisk.IRREVERSIBLE,
        title = title,
        fields = listOf(ConfirmField("命令", segment.trim().take(80))),
    )

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
