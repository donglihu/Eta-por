package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录正在运行的子智能体与它的运行控制器，并标出当前占用灵屏的那一个。
 *
 * 用途：把「人工接管结束」这类**只对当前灵屏持有者有意义**的消息，定向投递给正在干活的
 * 子智能体，而不是一律塞给父 Agent。父 Agent 收到时子代理还在跑，等于没说。
 */
internal object SubagentRunRegistry {

    private val controllers = ConcurrentHashMap<String, AgentRunController>()

    /** 子智能体所属会话：输入框的目标胶囊必须按会话过滤，否则会串到别的对话里。 */
    private val conversationOf = ConcurrentHashMap<String, String>()

    /** 处于暂停态的子智能体 id（只用于界面回显，真正的挂起由控制器内部完成）。 */
    private val pausedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 当前占用灵屏的子智能体 id；同一时刻只可能有一个（灵屏是单例）。 */
    @Volatile
    private var screenOwnerId: String? = null

    /** 想用屏幕但还没轮到的子智能体（FIFO，先到先得）。 */
    private val screenQueue = ArrayDeque<String>()

    /** 声明过要屏幕的子智能体：只用于界面/诊断，不参与排队判定。 */
    private val screenWanted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 屏幕槽位的唯一锁：register / unregister / acquire / release 全在这里同步，别混用 synchronized(this)。 */
    private val screenLock = Any()

    fun register(
        conversationKey: String,
        subagentId: String,
        controller: AgentRunController,
        ownsScreen: Boolean,
    ) {
        controllers[subagentId] = controller
        conversationOf[subagentId] = conversationKey
        // 只登记「想要屏幕」，真正占用在第一次调用屏幕工具时由 acquireScreen 争抢：
        // 否则并行派发的三个 screen 子代理会同时认为自己持有灵屏。
        synchronized(screenLock) { if (ownsScreen) screenWanted.add(subagentId) }
    }

    fun unregister(subagentId: String) {
        controllers.remove(subagentId)
        conversationOf.remove(subagentId)
        pausedIds.remove(subagentId)
        val next: String?
        synchronized(screenLock) {
            screenWanted.remove(subagentId)
            screenQueue.remove(subagentId)
            next = if (screenOwnerId == subagentId) promoteLocked() else null
        }
        // 唤醒必须在锁外做：steer 会回调控制器的锁，持着 screenLock 调会造出锁序反转。
        if (next != null) postSteer(next, "屏幕已空闲，归你使用")
    }

    /**
     * 持有者退场后的交棒：出队 → 设新 owner → 维护 wanted。
     *
     * 调用方必须已持有 [screenLock]。release 与 unregister 共用这一处，避免「出队了但没人唤醒」
     * 的半截实现：队首要过 SCREEN_BUSY，不通知它就以为屏幕还被占着，会把灵屏闲置到它整轮结束。
     *
     * @return 被扶正的 id；队列为空时返回 null。
     */
    private fun promoteLocked(): String? {
        val next = screenQueue.removeFirstOrNull()
        screenOwnerId = next
        // 它仍在等着用屏幕，所以保留在 screenWanted 里；诊断面板靠它列出排队意图。
        if (next != null) screenWanted.add(next)
        return next
    }

    /**
     * 申请屏幕槽位。
     *
     * @return true 表示当前归它用（无主或本来就是它）；false 表示已入队，本次调用不该执行。
     */
    fun acquireScreen(subagentId: String): Boolean = synchronized(screenLock) {
        val owner = screenOwnerId
        when {
            owner == null || owner == subagentId -> {
                screenOwnerId = subagentId
                true
            }
            screenQueue.contains(subagentId) -> false
            else -> {
                screenQueue.addLast(subagentId)
                false
            }
        }
    }

    /** 归还屏幕槽位：持有者交棒给队首并定向唤醒它；非持有者只把自己从队列里摘掉。 */
    fun releaseScreen(subagentId: String) {
        val next: String?
        synchronized(screenLock) {
            next = if (screenOwnerId == subagentId) {
                promoteLocked()
            } else {
                screenQueue.remove(subagentId)
                null
            }
        }
        if (next != null) postSteer(next, "屏幕已空闲，归你使用")
    }

    /** 排队人数：给「已排队第 N 位」这类提示用。 */
    fun screenQueueSize(): Int = synchronized(screenLock) { screenQueue.size }

    private fun postSteer(subagentId: String, text: String) {
        controllers[subagentId]?.steer(text)
    }

    /**
     * 暂停指定子智能体：它在下一个检查点（下一轮开始 / 下一步之前）挂起，进度不丢。
     *
     * @return false 表示它已经结束。
     */
    fun pause(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.pause()
        pausedIds.add(subagentId)
        return true
    }

    /** 继续执行被暂停的子智能体；返回 false 表示它已经结束。 */
    fun resume(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.resume()
        pausedIds.remove(subagentId)
        return true
    }

    fun isPaused(subagentId: String): Boolean = pausedIds.contains(subagentId)

    /** 每个子智能体最近一次进度快照（进程内）。会话切走导致消息更新丢失时，界面靠它兜底。 */
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    /** toolCallId → 最新快照：界面拿到的是工具消息 id，用它反查更可靠。 */
    private val snapshotByToolCall = ConcurrentHashMap<String, String>()

    fun publishSnapshot(subagentId: String, toolCallId: String?, snapshotJson: String) {
        latestSnapshots[subagentId] = snapshotJson
        if (!toolCallId.isNullOrBlank()) snapshotByToolCall[toolCallId] = snapshotJson
    }

    fun latestSnapshot(subagentId: String): String? = latestSnapshots[subagentId]

    fun snapshotForToolCall(toolCallId: String): String? = snapshotByToolCall[toolCallId]

    fun screenOwner(): String? = screenOwnerId

    /**
     * 指定会话里存活的子智能体 id 列表。
     *
     * 必须按会话过滤：注册表是进程级的，不过滤会把别的对话正在跑的子代理也列到当前输入框上。
     */
    fun liveIds(conversationKey: String?): List<String> {
        val key = conversationKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return controllers.keys.filter { id ->
            conversationOf[id] == key && isRunning(id)
        }
    }

    /** 快照已进入终态的视为已结束：避免执行完但 unregister 漏掉时，胶囊一直挂在输入框上。 */
    private fun isRunning(subagentId: String): Boolean {
        val json = latestSnapshots[subagentId] ?: return true
        val status = runCatching { SubagentActivitySnapshot.decode(json)?.status }.getOrNull()
        return status == null || status == SubagentActivitySnapshot.STATUS_RUNNING
    }

    /** 该子智能体是否还活着（控制器仍在册）。 */
    fun isAlive(subagentId: String): Boolean = controllers.containsKey(subagentId)

    /**
     * 向指定子智能体投递一条补充指令。
     *
     * 和定向接管同一条通道：在下一个回合边界注入，不打断正在执行的工具批次。
     *
     * @return false 表示它已经结束（或 id 不存在），调用方应提示用户。
     */
    fun steer(subagentId: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val controller = controllers[subagentId] ?: return false
        return controller.steer(trimmed)
    }

    /**
     * 把一条补充指令投给当前占用灵屏的子智能体。
     *
     * 走的是它自己的 steering 通道：不打断正在执行的工具批次，在下一个回合边界注入。
     *
     * @return true 表示已投递（或有子代理占用灵屏但投递失败时不适用），false 表示当前没有
     *         占用灵屏的子智能体，调用方应回退到父 Agent。
     */
    fun steerScreenOwner(text: String): Boolean {
        val id = screenOwnerId ?: return false
        val controller = controllers[id] ?: return false
        return controller.steer(text)
    }
}
