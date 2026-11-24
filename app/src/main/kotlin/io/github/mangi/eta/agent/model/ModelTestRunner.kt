package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 模型测速执行器：向模型端点发一个最小请求，测连接/发送/处理/下载/总计五段耗时，
 * 对齐 miniAI 的「测速」按钮效果。
 *
 * 只支持 API Key 认证的 openai 兼容 / anthropic / gemini 三类端点；
 * OAuth 与借用厂商助手的模型暂不支持测速，返回明确说明。
 */
internal object ModelTestRunner {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val TEST_MESSAGE = "ping"

    suspend fun run(
        provider: ProviderSetting,
        modelId: String,
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val providerType = provider.runtimeProviderType
        when {
            !isTestableAuth(provider.authMode) -> ModelTestResult(
                success = false,
                latencyMs = 0,
                message = "暂不支持该认证方式的测速",
                errorDetail = "authMode=${provider.authMode}",
            )
            providerType == ProviderTypes.ANTHROPIC -> runAnthropic(provider, modelId)
            providerType == ProviderTypes.GEMINI -> runGemini(provider, modelId)
            else -> runOpenAiCompatible(provider, modelId)
        }
    }

    private fun isTestableAuth(authMode: String): Boolean =
        authMode == ProviderAuthModes.API_KEY

    private fun runOpenAiCompatible(provider: ProviderSetting, modelId: String): ModelTestResult {
        val url = ProviderUrls.openAiChatCompletionsUrl(provider.baseUrl)
        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", TEST_MESSAGE)
            }))
            put("max_tokens", 16)
        }
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, body.toString()) { responseBody ->
            responseBody.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }
    }

    private fun runAnthropic(provider: ProviderSetting, modelId: String): ModelTestResult {
        val url = ProviderUrls.anthropicMessagesUrl(provider.baseUrl)
        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 16)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", TEST_MESSAGE)
            }))
        }
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, body.toString()) { responseBody ->
            responseBody.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        }
    }

    private fun runGemini(provider: ProviderSetting, modelId: String): ModelTestResult {
        val base = ProviderUrls.normalizeBaseUrl(provider.baseUrl)
        val url = "$base/models/$modelId:generateContent"
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("text", TEST_MESSAGE)))
            }))
        }
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { if (provider.apiKey.isNotBlank()) header("x-goog-api-key", provider.apiKey) }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, body.toString()) { responseBody ->
            responseBody.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        }
    }

    private fun execute(
        request: Request,
        requestBody: String,
        replyExtractor: (JSONObject) -> String,
    ): ModelTestResult {
        val timings = PhaseTimingRecorder()
        val client = AgentHttpClient.client.newBuilder()
            .eventListenerFactory(EventListener.Factory { timings })
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBodyText = response.body?.string().orEmpty()
                val responseJson = runCatching { JSONObject(responseBodyText) }.getOrNull()
                ModelTestResult(
                    success = response.isSuccessful,
                    latencyMs = timings.totalMs(),
                    message = if (response.isSuccessful) "连接正常" else "HTTP ${response.code}",
                    requestUrl = request.url.toString(),
                    requestHeaders = redactHeaders(request.headers),
                    requestBody = redactBody(requestBody),
                    responseCode = response.code,
                    responseHeaders = redactHeaders(response.headers),
                    responseBody = responseBodyText.take(2048),
                    replyText = responseJson?.let(replyExtractor).orEmpty(),
                    timings = timings.toTimings(),
                )
            }
        } catch (failure: Throwable) {
            ModelTestResult(
                success = false,
                latencyMs = timings.totalMs(),
                message = "执行失败",
                requestUrl = request.url.toString(),
                requestHeaders = redactHeaders(request.headers),
                requestBody = redactBody(requestBody),
                errorDetail = failure.message ?: failure.javaClass.simpleName,
                timings = timings.toTimings(),
            )
        }
    }

    /**
     * 敏感请求头打码。
     *
     * 测速详情弹窗会把请求头原样显示给用户，里面含 `Authorization: Bearer <API Key>` /
     * `x-api-key` / `x-goog-api-key`——等于把密钥摆在屏幕上，截图就能带走。
     * 这里只保留头名，值一律打码；要核对的是「带了哪个头」，不是「密钥是什么」。
     */
    private fun redactHeaders(headers: okhttp3.Headers): Map<String, String> =
        headers.toMultimap().mapValues { (name, values) ->
            if (isSecretHeader(name)) "***" else values.joinToString()
        }

    private fun isSecretHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "authorization" ||
            lower == "x-api-key" ||
            lower == "x-goog-api-key" ||
            lower == "api-key" ||
            lower.endsWith("-api-key") ||
            lower.contains("token") ||
            lower.contains("secret")
    }

    /**
     * 请求体打码：把 JSON 里常见的密钥字段值替换成 ***。
     *
     * 请求体本身由本函数构造（只有 model / messages / max_tokens），不含密钥；
     * 这里仍做一遍兜底，防止后续有人往 body 里加字段时把密钥带出去。
     */
    private fun redactBody(body: String): String = runCatching {
        val json = JSONObject(body)
        redactJson(json)
        json.toString()
    }.getOrElse { body.take(512) }

    private fun redactJson(node: Any?): Unit {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                keys.forEach { key ->
                    val value = node.opt(key)
                    if (value is JSONObject || value is org.json.JSONArray) {
                        redactJson(value)
                    } else if (isSecretHeader(key)) {
                        node.put(key, "***")
                    }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until node.length()) redactJson(node.opt(index))
            }
        }
    }

    private class PhaseTimingRecorder : EventListener() {
        private var callStartNanos = 0L
        private var callEndNanos = 0L
        private var connectStartNanos = 0L
        private var connectEndNanos = 0L
        private var requestBodyStartNanos = 0L
        private var requestBodyEndNanos = 0L
        private var responseHeadersStartNanos = 0L
        private var responseBodyEndNanos = 0L

        override fun callStart(call: Call) {
            callStartNanos = System.nanoTime()
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStartNanos = System.nanoTime()
        }

        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            connectEndNanos = System.nanoTime()
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStartNanos = System.nanoTime()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBodyEndNanos = System.nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            responseHeadersStartNanos = System.nanoTime()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBodyEndNanos = System.nanoTime()
        }

        override fun callEnd(call: Call) {
            callEndNanos = System.nanoTime()
        }

        fun totalMs(): Long = elapsed(callStartNanos, callEndNanos)

        fun toTimings(): NetworkPhaseTimings {
            val connect = elapsed(connectStartNanos, connectEndNanos)
            val send = elapsed(requestBodyStartNanos, requestBodyEndNanos)
            val process = elapsed(requestBodyEndNanos, responseHeadersStartNanos)
            val download = elapsed(responseHeadersStartNanos, responseBodyEndNanos)
            val total = elapsed(callStartNanos, callEndNanos)
            return NetworkPhaseTimings(
                connectMs = connect,
                sendMs = send,
                processMs = process,
                downloadMs = download,
                totalMs = total,
                connectionReused = connect == 0L && total > 0L,
            )
        }

        private fun elapsed(start: Long, end: Long): Long =
            if (start > 0L && end >= start) (end - start) / 1_000_000L else 0L
    }
}
