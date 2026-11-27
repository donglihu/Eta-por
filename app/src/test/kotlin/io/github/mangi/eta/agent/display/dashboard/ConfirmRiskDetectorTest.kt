package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmRiskDetectorTest {

    private fun draftOf(command: String): ConfirmDraft? =
        ConfirmRiskDetector.inspectTool("terminal", JSONObject().put("command", command))

    /** 带起始 cwd 的判定：`terminal` schema 有 `cwd` 参数，绝对路径时是可信线索。 */
    private fun draftWithCwd(cwd: String, command: String): ConfirmDraft? =
        ConfirmRiskDetector.inspectTool(
            "terminal",
            JSONObject().put("command", command).put("cwd", cwd),
        )

    /** 逐条断言「拦下」，失败信息带上真实命令，避免批量用例里看不出是哪一条漏了。 */
    private fun assertBlocked(command: String) {
        val draft = draftOf(command)
        assertNotNull("应拦下：$command", draft)
    }

    /** 逐条断言「放行」，失败信息带上误拦的标题，方便判断是哪条规则过宽。 */
    private fun assertPassed(command: String) {
        val draft = draftOf(command)
        assertNull("不应拦下：$command（实际命中：${draft?.title}）", draft)
    }

    @Test
    fun moneyTapCarriesAmountAndPayee() {
        val draft = ConfirmRiskDetector.inspect(
            target = "立即支付",
            texts = listOf("订单确认", "收款方 星海便利店", "应付 ¥28.50", "立即支付"),
        )

        assertEquals(ConfirmRisk.MONEY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "金额" && it.value.contains("28.50") })
        assertTrue(draft.fields.any { it.label == "收款方" })
    }

    @Test
    fun smsSendIsIrreversible() {
        val draft = ConfirmRiskDetector.inspect(
            target = "发送",
            texts = listOf("编辑短信", "晚点到家", "发送"),
        )

        assertEquals(ConfirmRisk.IRREVERSIBLE, draft?.risk)
    }

    @Test
    fun verificationCodeIsPrivacy() {
        val draft = ConfirmRiskDetector.inspect(
            target = "获取验证码",
            texts = listOf("登录", "手机号 13800000000", "获取验证码"),
        )

        assertEquals(ConfirmRisk.PRIVACY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "手机号" })
    }

    @Test
    fun harmlessTapIsNotBlocked() {
        assertNull(ConfirmRiskDetector.inspect(target = "下一页", texts = listOf("商品列表", "下一页")))
    }

    @Test
    fun combinedShortOptionStillRecursesIntoWrappedCommand() {
        // `bash -lc '<danger>'`：早先只认字面 -c，套一层 -lc 整条命令不被验算。
        val wrapped = listOf("-lc", "-ec", "-xc", "--command")
        wrapped.forEach { flag ->
            val draft = draftOf("bash $flag 'rm -rf /system/app'")
            assertTrue("$flag 应被识别为 -c", draft != null)
        }
    }

    @Test
    fun plainShellWithoutDashCIsStillAnalyzed() {
        val draft = draftOf("rm -rf /system/app")
        assertEquals(ConfirmRisk.IRREVERSIBLE, draft?.risk)
    }

    @Test
    fun writeVerbsIntoSystemPartitionAreBlocked() {
        val dangerous = listOf(
            "cp evil.so /system/lib64/evil.so",
            "mv /sdcard/x /system/app/x",
            "tee /system/build.prop",
            "ln -s /data/local/tmp/x /system/bin/x",
            "sed -i 's/a/b/' /system/build.prop",
            "chmod 777 /system/bin/sh",
            "echo x > /system/build.prop",
            "echo x >/system/build.prop",
            "cmd 2>/system/err.log",
        )
        dangerous.forEach { command -> assertBlocked(command) }
    }

    @Test
    fun writeVerbsInsideDataAdbAreBlocked() {
        // /data/adb 下的任何写入都等于模块开机自启或提权。
        val dangerous = listOf(
            "cp payload.sh /data/adb/service.d/payload.sh",
            "mkdir -p /data/adb/modules/x",
            "echo 'x' > /data/adb/post-fs-data.sh",
        )
        dangerous.forEach { command -> assertBlocked(command) }
    }

    @Test
    fun ordinaryWritesAndReadsAreNotBlocked() {
        // 往共享存储和临时目录写文件是日常操作，不该弹卡，否则用户会无脑点批准。
        val safe = listOf(
            "cp report.md /sdcard/Download/report.md",
            "mv a.txt /storage/emulated/0/Documents/a.txt",
            "echo hello > /data/local/tmp/eta/note.txt",
            "sed -n '1,10p' /system/build.prop",
            "grep -r foo /system/etc",
            "cat /system/build.prop",
        )
        safe.forEach { command -> assertPassed(command) }
    }

    // ── P0-1 缺陷 1：cd 后相对路径目标漏判（11 条）────────────────────────────

    @Test
    fun cdIntoSystemThenRelativeCopyIsBlocked() {
        // `cd /system` 后 `y` 落在 /system；早先逐段独立判定，normalizePath("y") 不以 / 开头，直接放过。
        assertBlocked("cd /system && cp /sdcard/x y")
    }

    @Test
    fun cdIntoSystemThenDotRelativeCopyIsBlocked() {
        assertBlocked("cd /system && cp /sdcard/x ./y")
    }

    @Test
    fun cdIntoSystemSubdirThenRelativeMoveIsBlocked() {
        assertBlocked("cd /system/app && mv /sdcard/x y")
    }

    @Test
    fun cdIntoSystemThenSemicolonRedirectIsBlocked() {
        // `;` 也是段分隔符，cwd 必须跨 `;` 传递。
        assertBlocked("cd /system; echo hi > build.prop")
    }

    @Test
    fun cdIntoDataAdbThenRelativeCopyIsBlocked() {
        assertBlocked("cd /data/adb && cp /sdcard/x service.sh")
    }

    @Test
    fun cdIntoSystemThenRelativeTeeIsBlocked() {
        assertBlocked("cd /system && tee y")
    }

    @Test
    fun cdIntoSystemThenRelativeInlineRedirectIsBlocked() {
        // 无空格重定向 `>y`：目标靠 inlineRedirect 提取，再按 cwd 解析。
        assertBlocked("cd /system && echo x >y")
    }

    @Test
    fun shDashCWrappedCdThenRelativeCopyIsBlocked() {
        // 内层 `&&` 属于内层命令：拆段必须在引号外，否则递归拿到的是残缺命令。
        assertBlocked("sh -c 'cd /system && cp /sdcard/x y'")
    }

    @Test
    fun suDashCWrappedCdThenRelativeCopyIsBlocked() {
        assertBlocked("su -c 'cd /system && cp /sdcard/x y'")
    }

    @Test
    fun cdIntoSystemThenRelativeSymlinkIsBlocked() {
        assertBlocked("cd /system && ln -s /sdcard/x y")
    }

    @Test
    fun cdIntoDataAdbThenRelativeMkdirIsBlocked() {
        assertBlocked("cd /data/adb && mkdir modules")
    }

    @Test
    fun cdEscapeThroughDotDotIsStillBlocked() {
        // `cd /system` 后 `../system/y` 必须收敛回 /system/y，不能靠多绕一层逃逸。
        assertBlocked("cd /system && cp /sdcard/x ../system/y")
    }

    @Test
    fun cdIntoSystemThenDotRelativeSubdirCopyIsBlocked() {
        assertBlocked("cd /system && cp /sdcard/x ./app/y")
    }

    // ── P0-1 缺陷 2：核心系统级危险动作（10 条 + 变体）────────────────────────

    @Test
    fun mountRemountReadWriteIsBlocked() {
        val variants = listOf(
            "mount -o remount,rw /system",
            "mount -o rw,remount /vendor",
            "mount -o remount /system",
            "mount -o rw,remount /",
        )
        variants.forEach { command -> assertBlocked(command) }
    }

    @Test
    fun setenforcePermissiveIsBlocked() {
        assertBlocked("setenforce 0")
        assertBlocked("setenforce permissive")
    }

    @Test
    fun rebootVariantsAreBlocked() {
        // reboot 没有路径参数，靠路径永远判不出来，只能按命令名判。
        assertBlocked("reboot")
        assertBlocked("svc power reboot")
        assertBlocked("am restart")
    }

    @Test
    fun initServiceStopAndStartAsStandaloneCommandsAreBlocked() {
        assertBlocked("stop")
        assertBlocked("start")
    }

    @Test
    fun pmUninstallAndDisableVariantsAreBlocked() {
        // 选项插在子命令前也要认出来：`pm --user 0 uninstall x` 走 subcommandOf 跳过带值选项。
        assertBlocked("pm uninstall io.github.mangi.eta")
        assertBlocked("pm disable-user com.android.settings")
        assertBlocked("pm disable com.android.settings")
        assertBlocked("pm clear io.github.mangi.eta")
        assertBlocked("pm --user 0 uninstall io.github.mangi.eta")
    }

    @Test
    fun settingsPutIsBlocked() {
        assertBlocked("settings put global adb_enabled 1")
    }

    @Test
    fun kernelModuleCommandsAreBlocked() {
        assertBlocked("insmod /data/local/tmp/x.ko")
        assertBlocked("rmmod x")
        assertBlocked("modprobe x")
    }

    @Test
    fun procAndSysWritesAreBlocked() {
        // 内核伪文件系统：任何写入都拦，且必须与 /system 判定区分开（`/system`.startsWith(`/sys`) 为真）。
        val writes = listOf(
            "echo 1 > /proc/sys/kernel/randomize_va_space",
            "echo 1 > /sys/x",
            "tee /proc/sys/kernel/randomize_va_space",
            "tee /sys/x",
            "cd /proc/sys/kernel && echo 1 > randomize_va_space",
        )
        writes.forEach { command -> assertBlocked(command) }
    }

    @Test
    fun blockDeviceWritesRemainBlocked() {
        // 既有实现已拦，修复不能把它们弄丢。
        assertBlocked("dd if=/dev/zero of=/dev/block/by-name/boot")
        assertBlocked("dd if=/dev/zero of=/dev/block/mmcblk0")
        assertBlocked("chmod -R 777 /system")
    }

    // ── 不误伤：日常操作必须继续放行（6 条 + 边界）────────────────────────────

    @Test
    fun readingSystemPartitionIsNotBlocked() {
        // 读系统写共享存储是合理操作：只有「写入系统分区」才弹卡。
        assertPassed("cp /system/x /sdcard/y")
        assertPassed("ls /system")
    }

    @Test
    fun sharedStorageWritesAreNotBlocked() {
        assertPassed("cp /sdcard/x /sdcard/y")
        assertPassed("cat /sdcard/a.txt")
        assertPassed("mkdir /sdcard/newdir")
        assertPassed("echo hi > /sdcard/a.txt")
    }

    @Test
    fun cdIntoSharedStorageThenRelativeWriteIsNotBlocked() {
        // cd 状态传递不能反向造成误伤：落到 /sdcard 的相对写入仍属日常操作。
        assertPassed("cd /sdcard && cp /sdcard/x y")
        assertPassed("cd /sdcard && mkdir newdir")
    }

    @Test
    fun stopAsArgumentIsNotBlocked() {
        // `stop` 只有在作为独立命令时才是 init 服务开关；作为参数不该拦。
        assertPassed("grep stop /system/build.prop")
    }

    @Test
    fun relativePathWithoutCdClueIsNotBlocked() {
        // 没有 cd 线索时 cwd 未知，相对路径不解析成绝对路径，保持修复前口径。
        assertPassed("cp a.txt b.txt")
        assertPassed("cd /system && cat build.prop")
        assertPassed("ls /proc/sys/kernel")
        assertPassed("cat /proc/cpuinfo")
        assertPassed("svc power stayon true")
    }

    // ── P0-1 收口：任务点名口径逐条落地（相对路径 + cd 上下文）────────────────

    @Test
    fun cdIntoVendorThenRelativeTeeIsBlocked() {
        // /vendor 与 /system 同属系统分区，`tee foo` 落在 /vendor/foo。
        assertBlocked("cd /vendor && tee foo")
    }

    @Test
    fun cdIntoSystemEtcThenRelativeSedInPlaceIsBlocked() {
        // sed -i 的改写目标是它读的文件；`hosts` 落在 /system/etc/hosts。
        assertBlocked("cd /system/etc && sed -i 's/a/b/' hosts")
    }

    @Test
    fun cdIntoDataAdbThenRelativeCopyShortFormIsBlocked() {
        // 最短复现形态：源与目标都是相对路径，只有 cd 给出落点线索。
        assertBlocked("cd /data/adb && cp a b")
    }

    @Test
    fun cdElsewhereThenAbsoluteSystemWriteStaysBlocked() {
        // 回归保护：cd 到非系统目录后仍按绝对路径写系统分区，拦截不能被修法破坏。
        assertBlocked("cd /tmp && cp a /system/b")
    }

    @Test
    fun cdIntoSharedStorageThenPlainRelativeCopyIsNotBlocked() {
        // 不误报：cd 到共享存储后的相对写入是日常操作。
        assertPassed("cd /sdcard && cp a b")
    }

    @Test
    fun cdIntoSystemThenRelativeReadOnlyCatIsNotBlocked() {
        // 不误报：cd 只改状态，读系统文件不该弹卡。
        assertPassed("cd /system && cat foo")
    }

    @Test
    fun cdDashThenRelativeWriteIsTreatedAsSuspicious() {
        // `cd -` 落点静态不可知，但「确实换过目录」；相对路径写入按可疑处理，不放行。
        assertBlocked("cd - && cp a b")
    }

    @Test
    fun cdIntoVariableThenRelativeWriteIsTreatedAsSuspicious() {
        // `cd $DIR` 同样无法静态求值，保守拦截而不是放过。
        assertBlocked("cd \$DIR && cp a b")
    }

    @Test
    fun wrappedShellInheritsOuterCdThenRelativeWriteIsBlocked() {
        // `sh -c` 子进程继承父 shell 的 cwd：`cd /system` 后内层 `cp /sdcard/x y` 实际写 /system/y。
        assertBlocked("cd /system && sh -c 'cp /sdcard/x y'")
    }

    @Test
    fun wrappedShellInheritsOuterSharedStorageCdWithoutFalsePositive() {
        // 继承 cwd 不能反向造成误伤：父 shell 在 /sdcard 时内层相对写入仍放行。
        assertPassed("cd /sdcard && sh -c 'cp a b'")
    }

    @Test
    fun cdDashThenRelativeReadIsNotBlocked() {
        // 保守策略只针对写入；`cd -` 后只读不受影响。
        assertPassed("cd - && cat foo")
    }

    // ── 必修 1 回归：相对 cd 在未知基准下不得升级为可疑（2026-11-27 审核）──────────
    //
    // 旧实现把「相对 cd + cwd 未知」也标成 movedUnknownCwd，于是任何
    // `cd <相对路径> && <写>` 在首段执行时一律弹确认卡（审核实测 6/6 误拦）。
    // 卡一多用户就无脑点批准，闸门比漏判更早失效——所以这条误拦是阻断级。

    @Test
    fun relativeCdWithUnknownBaseDoesNotBlockPlainWrite() {
        assertPassed("cd sub && cp a b")
        assertPassed("cd .. && cp a b")
        assertPassed("cd src && mv old.kt new.kt")
        assertPassed("cd app && touch build.log")
        assertPassed("cd build && echo done > out.txt")
        assertPassed("cd sub && mkdir -p out")
        assertPassed("cd sub && sed -i s/a/b/ f.txt")
    }

    @Test
    fun relativeCdWithUnknownBaseStillAllowsReadOnly() {
        // 修误拦不能把只读一起带歪：相对 cd 后的读取本来就是放行口径。
        assertPassed("cd sub && cat a")
        assertPassed("cd sub && ls -la")
        assertPassed("cd sub && grep x y")
    }

    @Test
    fun unresolvableCdTargetsStayBlocked() {
        // 这三条确实算不出落点，拦截是合理的；修误拦时不能顺手放开。
        assertBlocked("cd - && cp a b")
        assertBlocked("cd \$DIR && cp a b")
        assertBlocked("cd */x && cp a b")
    }

    @Test
    fun absoluteCdStillResolvesRealLandingSpot() {
        // 绝对 cd 的落点可求值：写系统分区要拦，写共享存储与只读要放。
        assertBlocked("cd /system && cp /sdcard/x y")
        assertBlocked("cd /vendor && tee foo")
        assertBlocked("cd /tmp && cp a /system/b")
        assertPassed("cd /sdcard && cp a b")
        assertPassed("cd /system && cat foo")
        assertPassed("cd /tmp && cp a b")
        assertPassed("cd ~/Download && cp a b")
    }

    @Test
    fun absoluteToolCwdResolvesRelativeWrite() {
        // 工具参数里的绝对 cwd 是可信线索：cwd=/system + `cp a b` 的 b 落在 /system。
        assertNotNull("cwd=/system 的相对写入应拦下", draftWithCwd("/system", "cp a b"))
        assertNotNull("cwd=/system/app 的相对写入应拦下", draftWithCwd("/system/app", "mv /sdcard/x y"))
        assertNull("cwd=/tmp 的相对写入应放行", draftWithCwd("/tmp", "cp a b"))
        assertNull("cwd=/tmp 下的相对 cd 应放行", draftWithCwd("/tmp", "cd sub && cp a b"))
        assertNull("cwd=/sdcard 的相对写入应放行", draftWithCwd("/sdcard", "cp a b"))
    }

    @Test
    fun relativeToolCwdFallsBackToUnknownBase() {
        // 相对 cwd 又依赖另一个未知基准，不能当线索用；退回未知基准后按放行口径。
        assertNull(draftWithCwd("sub", "cp a b"))
        assertNull(draftWithCwd("./sub", "cd deep && cp a b"))
        // 但绝对目标仍按绝对路径判，不因 cwd 相对而漏判。
        assertNotNull(draftWithCwd("sub", "cp a /system/b"))
    }

    /**
     * 相对路径穿越必须拦：`/data/adb` 是核心记忆 1.3 的禁止写入面，root 直接可写、无需 remount。
     * 早先「相对 cd + 相对目标」能整条绕过（2026-11-27 审核 P0）。
     */
    @Test
    fun relativeTraversalIntoSystemIsBlocked() {
        assertBlocked("cd sub && cp m ../../../../system/b")
        assertBlocked("cd ../../../../system && cp a b")
        assertBlocked("mkdir -p ../../../adb/modules/x")
    }

    /** `cd` 本身带穿越也要拦：`cd ../../../adb` 从 Linux 工作区退三层正好落在 `/data/adb`。 */
    @Test
    fun relativeCdDoesNotWhitelistRelativeTraversal() {
        assertBlocked("cd ../../../adb && mkdir -p modules/x")
        assertBlocked("cd ../../../adb && cp m modules/x")
    }

    /** 防误拦回归：日常相对 cd + 普通写入在任一候选基准下都不落系统分区，必须继续放行。 */
    @Test
    fun dailyRelativeWritesStayPassing() {
        assertPassed("cd .. && cp a b")
        assertPassed("cd sub && cp a b")
        assertPassed("cd src && mv old.kt new.kt")
        assertPassed("cd app && touch build.log")
        assertPassed("cd build && echo done > out.txt")
        assertPassed("cd sub && mkdir -p out")
        assertPassed("cd sub && sed -i s/a/b/ f.txt")
        assertPassed("cd sub && cat a")
        assertPassed("mkdir -p out/nested")
    }

    /** 防误拦回归：绝对路径写入与 cd 哨兵一条都不能松。 */
    @Test
    fun absoluteAndSentinelWritesStayBlocked() {
        assertBlocked("cd - && cp a b")
        assertBlocked("cd \$DIR && cp a b")
        assertBlocked("cd */x && cp a b")
        assertBlocked("cd /system && cp /sdcard/x y")
        assertBlocked("cd /vendor && tee foo")
        assertBlocked("cd /tmp && cp a /system/b")
        assertBlocked("cd /data/adb && mkdir -p modules/x")
        assertBlocked("mkdir -p /data/adb/modules/x")
    }

    /** 共享存储与只读不受影响。 */
    @Test
    fun sharedStorageAndReadOnlyStayPassing() {
        assertPassed("cd /sdcard && cp a b")
        assertPassed("cd /system && cat foo")
        assertPassed("cd .. && cat foo")
        assertPassed("cp a b")
    }
}
