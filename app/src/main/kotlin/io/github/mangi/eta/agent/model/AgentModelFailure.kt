package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.StableLogType
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), StableLogType {

    /** R8 会重命名类名，日志类型走固定 token，避免线上出现 `type=sp`。 */
    override val stableLogType: String get() = "AgentModelFailure"

    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
        )

        /** 上下文超限错误码：上层据此给出压缩或新开会话的建议。 */
        const val CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"

        /** 上下文超限：给一句可操作的中文建议（工具档调低 / 新开会话）。 */
        private const val CONTEXT_OVERFLOW_HINT =
            "模型上下文超过容量限制。可把顶栏「工具档」调低（标准或极简），或新开会话继续。"

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun http(status: Int, body: String, includeParameterDetails: Boolean = false): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CONTEXT_OVERFLOW, false, CONTEXT_OVERFLOW_HINT,
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "模型请求参数无效（HTTP 400；$details），请检查模型配置。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CONTEXT_OVERFLOW, false, CONTEXT_OVERFLOW_HINT,
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        /**
         * 把 provider 边界抛出的异常收敛成带稳定错误码的失败。
         *
         * 2026-11-22 修复：原来 `SSLException`/`ProtocolException`/未知异常返回 null，
         * 调用方 `?: throw failure` 直接抛原异常，线上日志因此出现 `model_code=` 空值——
         * 证书错误、网关改写协议、未知异常三种原因在日志里完全无法区分。
         * 现在每个分支都给稳定错误码，`retryable` 按「重试是否可能改变结果」判定。
         */
        fun transport(failure: Exception): AgentModelFailure = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            // 证书校验失败、握手被中间设备拒绝：重试握手不会改变证书结论。
            is SSLException -> AgentModelFailure(
                "MODEL_TLS_FAILED", false,
                "模型连接 TLS 握手失败（证书校验或加密协商被拒）。请检查系统时间、" +
                    "代理/网关是否替换了证书，以及自定义 CA 是否已安装。",
                failure,
            )
            // HTTP 协议层被改写（响应不合规、状态行异常）：先修网关，重试无收益。
            is ProtocolException -> AgentModelFailure(
                "MODEL_PROTOCOL_ERROR", false,
                "模型接口 HTTP 协议层错误（响应不符合协议，可能被代理或网关改写）。" +
                    "请检查接口地址、代理与中转网关。",
                failure,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            // 未知异常不重试：没有分类依据的重试会放大故障（可能是状态损坏或编程错误）。
            else -> AgentModelFailure(
                "MODEL_UNEXPECTED", false,
                "模型请求发生未分类异常，未自动重试；若再次出现请导出诊断日志定位。",
                failure,
            )
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("too many tokens") ||
                message.contains("request payload size exceeds") ||
                ((message.contains("input token count") || message.contains("total token count") ||
                    message.contains("prompt token")) && message.contains("exceed")) ||
                (message.contains("exceeds the maximum") && message.contains("input tokens"))
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "模型接口认证失败，请检查 API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
