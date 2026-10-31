package app.memo.core.agent

import app.memo.core.plan.Parsing
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One model reply in the agent loop: a validated tool call, a final answer, or a violation list. */
sealed interface AgentReply {
    /** [args] values are String, Long (for INT), Boolean or List<String>; omitted optional args are absent. */
    data class Call(val tool: ToolSpec, val args: Map<String, Any>) : AgentReply
    data class Final(val answer: String) : AgentReply
    data class Invalid(val violations: List<String>) : AgentReply
}

/**
 * Strict parser for one model reply (product law: every tool call is checked
 * against its schema before anything runs). Locates the first JSON object in
 * the output and collects ALL violations rather than stopping at the first.
 */
object ToolCallParser {

    private val TOP_LEVEL_KEYS = setOf("tool", "args", "final")
    private const val FINAL_MAX_CHARS = 4000
    private const val LIST_MAX_ITEMS = 50

    fun parse(output: String, allowed: List<ToolSpec> = ToolCatalog.all): AgentReply {
        val located = Parsing.locateFirstJsonObject(output)
            ?: return AgentReply.Invalid(listOf("no JSON object found"))
        val root = try {
            JSONObject(located)
        } catch (e: JSONException) {
            return AgentReply.Invalid(listOf("invalid JSON: ${e.message}"))
        }

        val violations = mutableListOf<String>()

        val unknownTop = root.keySet().filterNot { it in TOP_LEVEL_KEYS }.sorted()
        if (unknownTop.isNotEmpty()) {
            violations += "unknown top-level field(s): ${unknownTop.joinToString(", ")}"
        }

        val hasTool = root.has("tool")
        val hasFinal = root.has("final")
        when {
            hasTool && hasFinal -> violations += "send either \"tool\" or \"final\", not both"
            !hasTool && !hasFinal -> violations += "missing \"tool\" or \"final\""
        }

        val finalAnswer = if (hasFinal) readFinal(root.opt("final"), violations) else null
        val call = if (hasTool) readCall(root, allowed, violations) else null

        if (violations.isNotEmpty()) return AgentReply.Invalid(violations)
        if (call != null) return call
        return AgentReply.Final(checkNotNull(finalAnswer) { "exactly one of tool/final is present when no violations" })
    }

    private fun readFinal(raw: Any?, violations: MutableList<String>): String? = when {
        raw !is String -> {
            violations += "\"final\" must be a string"
            null
        }
        raw.isBlank() -> {
            violations += "\"final\" must not be blank"
            null
        }
        raw.length > FINAL_MAX_CHARS -> {
            violations += "\"final\" exceeds $FINAL_MAX_CHARS chars"
            null
        }
        else -> raw
    }

    private fun readCall(root: JSONObject, allowed: List<ToolSpec>, violations: MutableList<String>): AgentReply.Call? {
        val toolName = root.opt("tool")
        if (toolName !is String) {
            violations += "\"tool\" must be a string"
            return null
        }
        val spec = allowed.firstOrNull { it.name == toolName }
        if (spec == null) {
            violations += if (ToolCatalog.byName(toolName) != null) {
                "tool \"$toolName\" is not allowed here"
            } else {
                "unknown tool \"$toolName\""
            }
            return null
        }

        val args: JSONObject = when {
            !root.has("args") -> JSONObject()
            root.opt("args") is JSONObject -> root.getJSONObject("args")
            else -> {
                violations += "\"args\" must be a JSON object"
                return null
            }
        }

        val known = spec.params.map { it.name }.toSet()
        val unknownArgs = args.keySet().filterNot { it in known }.sorted()
        if (unknownArgs.isNotEmpty()) {
            violations += "unknown arg(s) for ${spec.name}: ${unknownArgs.joinToString(", ")}"
        }

        val values = LinkedHashMap<String, Any>()
        for (param in spec.params) {
            if (!args.has(param.name)) {
                if (param.required) violations += "missing required arg \"${param.name}\" for ${spec.name}"
                continue
            }
            readArg(param, args.opt(param.name), violations)?.let { values[param.name] = it }
        }
        return AgentReply.Call(spec, values)
    }

    /** Returns the typed value, or null after recording a violation. */
    private fun readArg(param: ToolParam, raw: Any?, violations: MutableList<String>): Any? {
        val name = param.name
        return when (param.type) {
            ParamType.STRING -> when {
                raw !is String -> typeError(name, "a string", violations)
                raw.length > param.maxChars -> {
                    violations += "arg \"$name\" exceeds ${param.maxChars} chars"
                    null
                }
                else -> raw
            }
            ParamType.INT -> when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> typeError(name, "an int", violations)
            }
            ParamType.BOOLEAN -> if (raw is Boolean) raw else typeError(name, "a boolean", violations)
            ParamType.STRING_LIST -> readStringList(param, raw, violations)
        }
    }

    private fun readStringList(param: ToolParam, raw: Any?, violations: MutableList<String>): List<String>? {
        val name = param.name
        if (raw !is JSONArray) return typeError(name, "a list of strings", violations)
        if (raw.length() > LIST_MAX_ITEMS) {
            violations += "arg \"$name\" exceeds $LIST_MAX_ITEMS items"
            return null
        }
        val items = ArrayList<String>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item !is String) {
                violations += "arg \"$name\" must be a list of strings (item $i is not a string)"
                return null
            }
            if (item.length > param.maxChars) {
                violations += "arg \"$name\" item $i exceeds ${param.maxChars} chars"
                return null
            }
            items += item
        }
        return items
    }

    private fun typeError(name: String, expected: String, violations: MutableList<String>): Nothing? {
        violations += "arg \"$name\" must be $expected"
        return null
    }
}
