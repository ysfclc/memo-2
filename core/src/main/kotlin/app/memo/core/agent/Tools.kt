package app.memo.core.agent

enum class ParamType { STRING, INT, BOOLEAN, STRING_LIST }

data class ToolParam(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String,
    val maxChars: Int = 2000,
) {
    init {
        require(NAME_SHAPE.matches(name)) { "param name \"$name\" must match ${NAME_SHAPE.pattern}" }
        require(maxChars > 0) { "param \"$name\" maxChars must be positive" }
    }
}

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val sideEffecting: Boolean,
) {
    init {
        require(NAME_SHAPE.matches(name)) { "tool name \"$name\" must match ${NAME_SHAPE.pattern}" }
        val duplicates = params.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "tool \"$name\" has duplicate params: ${duplicates.sorted()}" }
    }
}

object ToolCatalog {

    private const val TEXT_CHARS = 8000

    val all: List<ToolSpec> = listOf(
        // Read-only tools.
        ToolSpec(
            name = "search_workspace",
            description = "Search Memo records and artifacts for a text query.",
            params = listOf(
                ToolParam("query", ParamType.STRING, required = true, description = "text to search for"),
                ToolParam("kind", ParamType.STRING, required = false, description = "optional record kind to restrict the search"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "get_record",
            description = "Read one record by kind and key.",
            params = listOf(
                ToolParam("kind", ParamType.STRING, required = true, description = "record kind"),
                ToolParam("key", ParamType.STRING, required = true, description = "record key"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "draft_text",
            description = "Draft text from an instruction.",
            params = listOf(
                ToolParam("instruction", ParamType.STRING, required = true, description = "what the text should say"),
                ToolParam("max_words", ParamType.INT, required = false, description = "approximate word limit"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "summarize",
            description = "Summarize a text.",
            params = listOf(
                ToolParam("text", ParamType.STRING, required = true, description = "text to summarize", maxChars = TEXT_CHARS),
                ToolParam("max_words", ParamType.INT, required = false, description = "approximate word limit"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "extract_json",
            description = "Extract the named fields from a text as JSON.",
            params = listOf(
                ToolParam("text", ParamType.STRING, required = true, description = "source text", maxChars = TEXT_CHARS),
                ToolParam("fields", ParamType.STRING_LIST, required = true, description = "field names to extract"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "browse_capture",
            description = "Read a page from a web provider and capture what it shows (read-only).",
            params = listOf(
                ToolParam("provider", ParamType.STRING, required = true, description = "provider id"),
                ToolParam("what", ParamType.STRING, required = true, description = "what to capture"),
            ),
            sideEffecting = false,
        ),
        ToolSpec(
            name = "ask_user",
            description = "Ask the user a question and wait for the answer.",
            params = listOf(
                ToolParam("question", ParamType.STRING, required = true, description = "question for the user"),
            ),
            sideEffecting = false,
        ),
        // Side-effecting tools (each runs as a gated workflow step).
        ToolSpec(
            name = "create_record",
            description = "Create a record of the given kind from JSON fields.",
            params = listOf(
                ToolParam("kind", ParamType.STRING, required = true, description = "record kind"),
                ToolParam("fields_json", ParamType.STRING, required = true, description = "record fields as a JSON object string", maxChars = TEXT_CHARS),
            ),
            sideEffecting = true,
        ),
        ToolSpec(
            name = "update_record",
            description = "Update fields of an existing record.",
            params = listOf(
                ToolParam("kind", ParamType.STRING, required = true, description = "record kind"),
                ToolParam("key", ParamType.STRING, required = true, description = "record key"),
                ToolParam("fields_json", ParamType.STRING, required = true, description = "changed fields as a JSON object string", maxChars = TEXT_CHARS),
            ),
            sideEffecting = true,
        ),
        ToolSpec(
            name = "consult_frontier",
            description = "Send a prompt to a frontier AI provider through the web runtime.",
            params = listOf(
                ToolParam("provider", ParamType.STRING, required = true, description = "frontier provider id"),
                ToolParam("prompt", ParamType.STRING, required = true, description = "prompt to send", maxChars = TEXT_CHARS),
            ),
            sideEffecting = true,
        ),
        ToolSpec(
            name = "run_workflow",
            description = "Run a workflow template now.",
            params = listOf(
                ToolParam("template", ParamType.STRING, required = true, description = "workflow template id"),
            ),
            sideEffecting = true,
        ),
        ToolSpec(
            name = "schedule_workflow",
            description = "Schedule a workflow template to run at a given time.",
            params = listOf(
                ToolParam("template", ParamType.STRING, required = true, description = "workflow template id"),
                ToolParam("at_epoch_ms", ParamType.INT, required = true, description = "run time as epoch milliseconds"),
            ),
            sideEffecting = true,
        ),
    )

    private val byNameMap: Map<String, ToolSpec> = all.associateBy { it.name }

    init {
        require(byNameMap.size == all.size) { "tool names must be unique" }
    }

    fun byName(name: String): ToolSpec? = byNameMap[name]

    /** Compact plain-text tool list plus the output contract the model must follow. */
    fun schemaPrompt(tools: List<ToolSpec> = all): String = buildString {
        appendLine("Tools:")
        for (tool in tools) {
            val params = tool.params.joinToString(", ") { p ->
                val optional = if (p.required) "" else " (optional)"
                "${p.name}: ${typeName(p.type)}$optional"
            }
            appendLine("${tool.name}($params) - ${tool.description}")
        }
        append(OUTPUT_CONTRACT)
    }

    const val OUTPUT_CONTRACT: String =
        "Reply with ONE JSON object only: {\"tool\":\"<name>\",\"args\":{...}} to call a tool, " +
            "or {\"final\":\"<answer>\"} when done."

    private fun typeName(type: ParamType): String = when (type) {
        ParamType.STRING -> "string"
        ParamType.INT -> "int"
        ParamType.BOOLEAN -> "boolean"
        ParamType.STRING_LIST -> "string[]"
    }
}

private val NAME_SHAPE = Regex("^[a-z][a-z0-9_]{0,31}$")
