package io.github.mangi.eta.ui.app

import android.content.Context

/**
 * 按会话 ID 存的输入草稿。
 *
 * 之前草稿只活在内存里（切会话时写回 conversationsById），只要那个会话状态从数据库重载一次，
 * 没发出去的文字就没了。这里补一层落盘，切回来时贴回输入框。
 */
internal object ChatDraftStore {
    private const val FILE = "eta_chat_drafts"
    private const val MAX_DRAFTS = 200

    /**
     * 草稿淘汰顺序（最近写入的在末尾），只存会话 id，与草稿本身分开。
     *
     * 单独一列的原因：SharedPreferences 不记录写入时间，而 `prefs.all.keys` 是无序集合，
     * 拿它 take() 淘汰等于随机删草稿。
     */
    private const val KEY_ORDER = "__draft_order__"

    fun load(context: Context, conversationId: String): String {
        if (conversationId.isBlank() || conversationId == KEY_ORDER) return ""
        return runCatching { prefs(context).getString(conversationId, null) }.getOrNull().orEmpty()
    }

    fun save(context: Context, conversationId: String, text: String) {
        if (conversationId.isBlank()) return
        runCatching {
            val prefs = prefs(context)
            if (text.isBlank()) {
                prefs.edit().remove(conversationId).apply()
            } else {
                prefs.edit().putString(conversationId, text).apply()
                // 超上限时按「最后修改时间」淘汰最旧的草稿。
                // 早先用 prefs.all.keys.toList() 直接 take，SharedPreferences 的 keys 是
                // 无序 Hash 集合，删哪条完全随机——可能刚删掉用户正在用的那份
                // （2026-11-26 审核）。SharedPreferences 不记写入时间，这里用一个显式的
                // 顺序键维护：每次 save 把该会话推到末尾，淘汰时从头部开始。
                val order = prefs.getString(KEY_ORDER, "").orEmpty()
                    .split('\n').filter { it.isNotBlank() && it != conversationId }
                    .toMutableList()
                order.add(conversationId)
                if (order.size > MAX_DRAFTS) {
                    order.take(order.size - MAX_DRAFTS).forEach { evicted ->
                        prefs.edit().remove(evicted).apply()
                    }
                    while (order.size > MAX_DRAFTS) order.removeAt(0)
                }
                prefs.edit().putString(KEY_ORDER, order.joinToString("\n")).apply()
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
