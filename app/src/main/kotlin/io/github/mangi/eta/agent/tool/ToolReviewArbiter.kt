package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AssistantStopReason
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用审查「智能」档的决策模型裁决器。
 *
 * 规则引擎（资金 / 隐私 / 不可撤销）没命中的灰色地带，交给这里做一次轻量模型调用拍板：
 * 返回 true = 拦下问用户，false = 放行，null = 无结论（放行）。
 *
 * [AgentProviderClient.complete] 是阻塞调用，这里用 runBlocking + async(Dispatchers.IO) +
 * withTimeoutOrNull 包一层：超时、取消或协议异常一律回退到「无结论」，绝不让决策模型把工具执行卡死。
 * 裁决失败永远偏「放行」，不新增阻断面。
 */
internal class ToolReviewArbiter(
    private val config: AgentModelClient.ModelConfig,
    private val providerFor: (AgentModelClient.ModelConfig) -> AgentProviderClient =
        ProviderClientFactory::getClient,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** 返回 true = 拦下问用户，false = 放行，null = 无结论（放行）。 */
    fun decide(toolName: String, args: JSONObject): Boolean? {
        val controller = AgentRunController()
        val messages = buildPrompt(toolName, args)
        return try {
            runBlocking {
                val work = async(Dispatchers.IO) {
                    providerFor(config).complete(
                        ProviderRequest(
                            config = config,
                            messages = messages,
                            tools = JSONArray(),
                            purpose = ProviderRequestPurpose.TOOL_REVIEW,
                        ),
                        controller,
                    )
                }
                try {
                    val response = withTimeoutOrNull(timeoutMs) { work.await() }
                    when {
                        response == null -> {
                            controller.cancel()
                            null
                        }
                        response.stopReason != AssistantStopReason.END_TURN -> null
                        else -> parseDecision(response.assistantMessage.optString("content"))
                    }
                } catch (cancelled: CancellationException) {
                    controller.cancel()
                    throw cancelled
                } catch (other: Exception) {
                    controller.cancel()
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (other: Exception) {
            null
        }
    }

    private fun buildPrompt(toolName: String, args: JSONObject): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        .put(
            JSONObject().put("role", "user").put(
                "content",
                "工具：${toolName}\n参数：${truncate(args.toString(), MAX_ARGS_CHARS)}",
            ),
        )

    /** 只认 YES / NO 首词；其余一律无结论（放行），避免模型自由发挥被误判为「拦」。 */
    private fun parseDecision(raw: String): Boolean? {
        val first = raw.trim().substringBefore(' ').substringBefore('\n')
            .trim('"', '\'', '`', '.', '。', '，', ',', '：', ':')
            .lowercase()
        // 中文模型常回「是／需要／不需要」而不是 YES／NO；只认英文会让安全闸门在
        // 最需要拦的时候静默放行。这里放宽到首词包含判定，仍以「词首」为准避免误判。
        return when {
            first == "yes" || first.startsWith("yes") -> true
            first == "no" || first.startsWith("no") -> false
            first == "是" || first.startsWith("是") || first.startsWith("需要") -> true
            first.startsWith("不需要") || first.startsWith("否") -> false
            else -> null
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "…"

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val MAX_ARGS_CHARS = 1_200

        val SYSTEM_PROMPT = """
            你是工具调用风险裁决器。给定一个即将执行的工具及其参数，判断这次调用是否需要先征得用户确认。
            只在「可能造成资金损失、隐私泄露、不可撤销的修改、对外发送消息、或与用户目标明显冲突」时输出 YES；
            普通查询、读取、常规操作一律输出 NO。
            只输出一个词：YES 或 NO，不要解释。
        """.trimIndent()
    }
}
