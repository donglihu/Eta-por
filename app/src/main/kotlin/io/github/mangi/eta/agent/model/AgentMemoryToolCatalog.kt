package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/** 声明持久记忆的有界读取与原子局部更新工具。 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. Core memory and the heading index are already provided at run start, so call this only to inspect details, answer what is remembered, or refresh after a conflict. Use query for just-in-time retrieval instead of reading the whole file. Pass scope=notes to read NOTES.md, the non-core scratch file that is never injected.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "scope",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("core").put("notes"))
                                        .put(
                                            "description",
                                            "core reads MEMORY.md (default); notes reads NOTES.md, the non-core scratch file that is never injected into the prompt.",
                                        ),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Optional case-insensitive text to search for in the full memory file."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "1-based first line for paged reading when query is omitted; default 1."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
        if (!writable) return
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent memory. Default scope=core writes MEMORY.md: store only durable cross-conversation facts, preferences, relationships, and ongoing project context; never store secrets, credentials, verification codes, or transient requests. Keep '# 核心记忆' concise and inside the injection budget: growth that pushes the core section past memory_core_budget_chars is rejected with MEMORY_BUDGET_EXCEEDED. Project progress, run logs, TODOs and other non-core material belong in scope=notes (NOTES.md), which is never injected. Use the revision from the most recent memory_write or memory_get result, or the session memory snapshot if neither has run.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "scope",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("core").put("notes"))
                                        .put(
                                            "description",
                                            "core (default) updates MEMORY.md under the injection budget; notes appends a timestamped entry to NOTES.md and needs no revision. When scope is omitted on an append, content that looks like progress/log material is routed to notes automatically — pass core explicitly for durable facts.",
                                        ),
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put("description", "replace_range edits inclusive 1-based lines; append adds a distinct section; clear removes all memory."),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put("description", "Exact SHA-256 revision from the most recent memory_write or memory_get result, or the session memory snapshot. Required for scope=core; ignore it for scope=notes."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put("description", "Replacement or appended Markdown, at most 3500 characters. Empty content deletes a replace_range."),
                                ),
                        )
                        .put("required", JSONArray().put("mode")),
                ),
            )
    }
}
