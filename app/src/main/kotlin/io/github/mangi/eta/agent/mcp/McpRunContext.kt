package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.security.MessageDigest
import io.github.mangi.eta.agent.tool.AgentToolGuard
import org.json.JSONArray
import org.json.JSONObject

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

/** 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
    /** 超出 [MAX_RUN_TOOLS] 被截断的工具数，按 serverId 记录；空 map 表示无截断。 */
    val omittedToolsByServer: Map<String, Int> = emptyMap(),
) {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray, openedGroupIds: Set<String>? = null) {
        tools.forEach { tool ->
            // 按需加载投影：openedGroupIds 为 null 表示全量下发（OFF 档兼容旧调用）；
            // 非 null 时只下发「所属 server 组已打开」的工具，未打开的 server 整组不下发。
            // 裁剪只发生在 append 阶段：resolve/contains 仍走全量 tools 列表，执行不受影响。
            if (openedGroupIds != null && tool.groupId() !in openedGroupIds) return@forEach
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    append("。 ").append(it)
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    /** MCP 工具所属的动态组 id：`mcp:<serverPart8>`。派生与 [companion.modelToolName] 的 serverPart 完全一致。 */
    fun McpRunTool.groupId(): String = "$MCP_GROUP_PREFIX${serverGroupPart(server.id)}"

    /**
     * 每个已连接 server 一个动态组：id=`mcp:<serverId>` → 该 server 的模型侧工具名集合。
     * 供 `AgentToolGroups` 的动态组判定与 `tools_use_group` 开组回执使用。
     */
    fun serverGroups(): Map<String, Set<String>> =
        tools.groupBy { it.groupId() }
            .mapValues { (_, members) -> members.mapTo(LinkedHashSet()) { it.modelName } }

    /** 每个动态组的一行摘要：server 展示名 + 前 6 个工具名（供组索引与开组回执）。 */
    fun serverGroupSummaries(): Map<String, String> = buildMap {
        tools.groupBy(McpRunTool::server).forEach { (server, members) ->
            val names = members.take(MCP_SUMMARY_TOOL_LIMIT).joinToString("、") { it.definition.name }
            val more = (members.size - MCP_SUMMARY_TOOL_LIMIT).takeIf { it > 0 }?.let { " 等 ${members.size} 个工具" } ?: ""
            put("$MCP_GROUP_PREFIX${serverGroupPart(server.id)}", "MCP 服务器「${server.name}」：$names$more")
        }
    }

    /** 截断上报的一行正文：任一 server 有被丢弃的工具时返回说明，否则 null。 */
    fun truncationNotice(): String? =
        omittedToolsByServer.entries.takeIf { it.isNotEmpty() }?.joinToString("；") { (serverId, count) ->
            "服务器 $serverId 有 $count 个工具超出单 run 上限未加载"
        }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(): McpRunSnapshot {
            val projected = mutableListOf<McpRunTool>()
            val omittedToolsByServer = linkedMapOf<String, Int>()
            val now = System.currentTimeMillis()
            McpServerRepository.enabledServers().forEach { configured ->
                val bearerToken = McpServerRepository.bearerToken(configured.id)
                val needsRefresh = configured.lastProtocolVersion == null ||
                    configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                    (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
                val server = if (needsRefresh) {
                    runCatching {
                        McpServerManager.discover(configured, bearerToken).also {
                            McpServerRepository.update(it)
                        }
                    }.getOrNull()
                        ?: return@forEach
                } else {
                    configured
                }
                var omitted = 0
                server.activeTools.forEach { tool ->
                    // 超限不再静默丢弃：计数并记进 snapshot，让组摘要/回执显式上报截断。
                    if (projected.size >= MAX_RUN_TOOLS) {
                        omitted += 1
                        return@forEach
                    }
                    projected += McpRunTool(
                        modelName = modelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
                if (omitted > 0) omittedToolsByServer[serverGroupPart(server.id)] = omitted
            }
            return McpRunSnapshot(projected, omittedToolsByServer)
        }

        /** 组 id 与截断统计共用的 serverPart：字母数字前 8 位，空则 server。与 modelToolName 同源。 */
        fun serverGroupPart(serverId: String): String =
            serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }

        private fun modelToolName(serverId: String, toolName: String): String {
            val serverPart = serverGroupPart(serverId)
            val normalized = toolName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$toolName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "mcp_${serverPart}_${normalized}_$digest"
        }

        private const val MAX_RUN_TOOLS = 64

        /** MCP 动态组 id 前缀：`mcp:<serverId>`。与 `AgentToolGroups.MCP_GROUP_PREFIX` 保持一致。 */
        const val MCP_GROUP_PREFIX = "mcp:"

        /** 组摘要里最多列出的工具名数，超出以「等 N 个工具」收尾。 */
        const val MCP_SUMMARY_TOOL_LIMIT = 6
    }
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpHttpClient>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        return runCatching {
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                clients[tool.server.id] ?: McpHttpClient(
                    server = tool.server,
                    bearerToken = tool.bearerToken,
                ).also { clients[tool.server.id] = it }
            }
            adaptResult(tool, client.callTool(tool.definition, arguments))
        }.getOrElse {
            failure("MCP_CALL_FAILED", "MCP 工具调用失败")
        }
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "当前版本暂不支持 MCP 工具在执行中请求补充输入",
            )
        }
        if (tool.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val mcp: McpToolExecutor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        // 直连入口也要过逐工具开关：子智能体的 sub 调用直接落到这里，不经过 AgentLoop，
        // 而且 MCP 工具与本地工具同名时同样要能被能力页关掉。
        AgentToolGuard.rejectionOrNull(toolCall.name)?.let { return it }
        return if (mcp.contains(toolCall.name)) mcp.execute(toolCall) else local.execute(toolCall)
    }

    override fun close() {
        runCatching { mcp.close() }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
