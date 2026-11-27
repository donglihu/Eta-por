package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按需加载的补充 schema：`tool_call` 转发过来的内层真名必须能过参数预检。
 *
 * 纯 JVM 用例（不引 Robolectric）：arm64 上 Robolectric 会被静默跳过，等于没写。
 */
class AgentToolCallValidatorExtraSchemasTest {

    @Test
    fun innerToolIsValidatedAgainstItsOwnDeferredSchema() {
        val validator = AgentToolCallValidator(
            tools = JSONArray().put(schema(AgentToolGroupCatalog.CALL, callParameters())),
            extraSchemas = mapOf("terminal" to terminalParameters()),
        )

        // 内层真名：能查到 schema，参数合规就通过。
        assertNull(
            validator.validate(
                AgentModelClient.ToolCall(
                    id = "c1",
                    name = "terminal",
                    argumentsJson = """{"action":"exec","command":"ls"}""",
                ),
            ),
        )
        // 缺必填、类型不对都要被拦下来，说明用的确实是 terminal 自己的 schema，
        // 而不是被当成「不在目录里」一刀切。
        assertNotNull(
            validator.validate(
                AgentModelClient.ToolCall(id = "c2", name = "terminal", argumentsJson = """{"action":"exec"}"""),
            ),
        )
        assertNotNull(
            validator.validate(
                AgentModelClient.ToolCall(id = "c3", name = "terminal", argumentsJson = """{"command":123}"""),
            ),
        )
    }

    @Test
    fun toolsMissingFromBothSourcesStillReportTheCatalogMessage() {
        val validator = AgentToolCallValidator(
            tools = JSONArray().put(schema(AgentToolGroupCatalog.CALL, callParameters())),
            extraSchemas = mapOf("terminal" to terminalParameters()),
        )
        val message = validator.validate(
            AgentModelClient.ToolCall(id = "c4", name = "make_coffee", argumentsJson = "{}"),
        )
        assertNotNull(message)
        assertEquals(true, message!!.contains("不在本次运行的工具目录里"))
    }

    @Test
    fun directorySchemaWinsOverTheDeferredCopy() {
        // 同一工具名同时出现在目录与补充表里时，以目录为准（目录那份可能是被投影改写过的，
        // 例如无 Root 时 identity 收窄成 user）。这里用不同 required 证明取的是目录那份。
        val strictDirectory = terminalParameters().put("required", JSONArray().put("command"))
        val validator = AgentToolCallValidator(
            tools = JSONArray().put(schema("terminal", strictDirectory)),
            extraSchemas = mapOf("terminal" to terminalParameters()),
        )
        assertNotNull(
            "目录里的必填项要生效：${validator.validate(
                AgentModelClient.ToolCall(id = "c5", name = "terminal", argumentsJson = """{"action":"exec"}"""),
            )}",
            validator.validate(
                AgentModelClient.ToolCall(id = "c5", name = "terminal", argumentsJson = """{"action":"exec"}"""),
            ),
        )
    }

    private fun terminalParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("action", JSONObject().put("type", "string"))
                .put("command", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray().put("action").put("command"))

    private fun callParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("tool", JSONObject().put("type", "string"))
                .put("arguments", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray().put("tool"))

    private fun schema(name: String, parameters: JSONObject): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", name)
                .put("parameters", parameters),
        )
}
