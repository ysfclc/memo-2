package app.memo.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCallParserTest {

    private fun invalid(output: String, allowed: List<ToolSpec> = ToolCatalog.all): List<String> {
        val reply = ToolCallParser.parse(output, allowed)
        assertIs<AgentReply.Invalid>(reply, "expected Invalid for: $output")
        return reply.violations
    }

    private fun assertMentions(violations: List<String>, fragment: String) {
        assertTrue(
            violations.any { it.contains(fragment) },
            "no violation mentions \"$fragment\": $violations",
        )
    }

    @Test
    fun validCallWithRequiredAndOptionalArgsIsTyped() {
        val reply = ToolCallParser.parse(
            """{"tool":"extract_json","args":{"text":"Invoice 42 from Acme","fields":["invoice","vendor"]}}""",
        )
        assertIs<AgentReply.Call>(reply)
        assertEquals("extract_json", reply.tool.name)
        assertEquals("Invoice 42 from Acme", reply.args["text"])
        assertEquals(listOf("invoice", "vendor"), reply.args["fields"])

        val withOptional = ToolCallParser.parse(
            """{"tool":"draft_text","args":{"instruction":"short welcome note","max_words":40}}""",
        )
        assertIs<AgentReply.Call>(withOptional)
        assertEquals(40L, withOptional.args["max_words"])
        assertEquals("short welcome note", withOptional.args["instruction"])
    }

    @Test
    fun omittedOptionalArgIsAbsentFromCall() {
        val reply = ToolCallParser.parse("""{"tool":"search_workspace","args":{"query":"invoices"}}""")
        assertIs<AgentReply.Call>(reply)
        assertEquals(mapOf<String, Any>("query" to "invoices"), reply.args)
    }

    @Test
    fun finalAnswerIsReturnedAndProseAroundJsonIsTolerated() {
        assertEquals(
            AgentReply.Final("done"),
            ToolCallParser.parse("""{"final":"done"}"""),
        )
        assertEquals(
            AgentReply.Final("all set"),
            ToolCallParser.parse("""Sure, here is my reply: {"final":"all set"} Thanks!"""),
        )
    }

    @Test
    fun malformedJsonIsInvalid() {
        val violations = invalid("""{"tool" "ask_user", "args": {}}""")
        assertMentions(violations, "invalid JSON")
    }

    @Test
    fun outputWithoutAnyObjectIsInvalid() {
        assertEquals(listOf("no JSON object found"), invalid("I will search the workspace now."))
    }

    @Test
    fun extraTopLevelFieldAndExtraArgFieldAreBothReported() {
        val violations = invalid(
            """{"tool":"ask_user","args":{"question":"Ready?","priority":"high"},"reason":"checking"}""",
        )
        assertMentions(violations, "reason")
        assertMentions(violations, "priority")
        assertEquals(2, violations.size, "expected both violations: $violations")
    }

    @Test
    fun wrongTypesAreEachReported() {
        val violations = invalid(
            """{"tool":"draft_text","args":{"instruction":"x","max_words":"5"}}""",
        )
        assertMentions(violations, "\"max_words\" must be an int")

        assertMentions(
            invalid("""{"tool":"draft_text","args":{"instruction":"x","max_words":5.5}}"""),
            "\"max_words\" must be an int",
        )
        assertMentions(
            invalid("""{"tool":"schedule_workflow","args":{"template":"t","at_epoch_ms":"1700000000000"}}"""),
            "\"at_epoch_ms\" must be an int",
        )
        assertMentions(
            invalid("""{"tool":"extract_json","args":{"text":"x","fields":["a",7]}}"""),
            "\"fields\" must be a list of strings",
        )
        assertMentions(
            invalid("""{"tool":"ask_user","args":{"question":true}}"""),
            "\"question\" must be a string",
        )
    }

    @Test
    fun wrongTypesForBooleanAreReported() {
        val boolTool = ToolSpec(
            name = "toggle",
            description = "test tool with a boolean",
            params = listOf(ToolParam("enabled", ParamType.BOOLEAN, required = true, description = "flag")),
            sideEffecting = false,
        )
        assertMentions(
            invalid("""{"tool":"toggle","args":{"enabled":"true"}}""", listOf(boolTool)),
            "\"enabled\" must be a boolean",
        )
        assertIs<AgentReply.Call>(ToolCallParser.parse("""{"tool":"toggle","args":{"enabled":true}}""", listOf(boolTool)))
    }

    @Test
    fun missingRequiredArgIsNamed() {
        val violations = invalid("""{"tool":"get_record","args":{"kind":"contact"}}""")
        assertMentions(violations, "\"key\"")
        assertEquals(1, violations.size, "expected only the missing key: $violations")
    }

    @Test
    fun missingArgsObjectIsOkOnlyWithoutRequiredParams() {
        val optionalOnly = ToolSpec(
            name = "ping",
            description = "tool with no required args",
            params = listOf(ToolParam("note", ParamType.STRING, required = false, description = "optional note")),
            sideEffecting = false,
        )
        val reply = ToolCallParser.parse("""{"tool":"ping"}""", listOf(optionalOnly))
        assertIs<AgentReply.Call>(reply)
        assertEquals(emptyMap(), reply.args)

        assertMentions(invalid("""{"tool":"search_workspace"}"""), "missing required arg \"query\"")
    }

    @Test
    fun unknownToolIsInvalid() {
        assertEquals(listOf("unknown tool \"launch_rocket\""), invalid("""{"tool":"launch_rocket","args":{}}"""))
    }

    @Test
    fun toolOutsideAllowedListIsInvalid() {
        val restricted = listOf(ToolCatalog.byName("ask_user")!!)
        val violations = invalid(
            """{"tool":"create_record","args":{"kind":"contact","fields_json":"{}"}}""",
            restricted,
        )
        assertMentions(violations, "\"create_record\" is not allowed here")
        assertIs<AgentReply.Call>(
            ToolCallParser.parse("""{"tool":"ask_user","args":{"question":"Ready?"}}""", restricted),
        )
    }

    @Test
    fun bothToolAndFinalAreInvalid() {
        val violations = invalid("""{"tool":"ask_user","args":{"question":"Ready?"},"final":"done"}""")
        assertMentions(violations, "not both")
    }

    @Test
    fun neitherToolNorFinalIsInvalid() {
        assertMentions(invalid("""{"args":{}}"""), "missing \"tool\" or \"final\"")
    }

    @Test
    fun finalMustBeNonBlankStringWithinLimit() {
        assertMentions(invalid("""{"final":"   "}"""), "must not be blank")
        assertMentions(invalid("""{"final":42}"""), "\"final\" must be a string")
        val tooLong = "a".repeat(4001)
        assertMentions(invalid("""{"final":"$tooLong"}"""), "exceeds 4000 chars")
    }

    @Test
    fun argsMustBeAnObject() {
        assertMentions(invalid("""{"tool":"ask_user","args":["Ready?"]}"""), "\"args\" must be a JSON object")
    }

    @Test
    fun allViolationsAreCollectedTogether() {
        val violations = invalid(
            """{"tool":"draft_text","args":{"max_words":"many","extra":1},"note":"x"}""",
        )
        assertMentions(violations, "unknown top-level field(s): note")
        assertMentions(violations, "unknown arg(s) for draft_text: extra")
        assertMentions(violations, "missing required arg \"instruction\"")
        assertMentions(violations, "\"max_words\" must be an int")
    }

    @Test
    fun catalogNamesAreUniqueAndSideEffectingSetIsExact() {
        val names = ToolCatalog.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate tool names: $names")

        val sideEffecting = ToolCatalog.all.filter { it.sideEffecting }.map { it.name }.toSet()
        assertEquals(
            setOf("create_record", "update_record", "consult_frontier", "run_workflow", "schedule_workflow"),
            sideEffecting,
        )
    }

    @Test
    fun schemaPromptListsEveryToolAndTheOutputContract() {
        val prompt = ToolCatalog.schemaPrompt()
        ToolCatalog.all.forEach { assertTrue(prompt.contains(it.name), "prompt misses ${it.name}") }
        assertTrue(
            prompt.contains(
                "Reply with ONE JSON object only: {\"tool\":\"<name>\",\"args\":{...}} to call a tool, " +
                    "or {\"final\":\"<answer>\"} when done.",
            ),
        )
    }

    @Test
    fun toolSpecRejectsBadNamesAndDuplicateParams() {
        val param = ToolParam("query", ParamType.STRING, required = true, description = "q")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ToolSpec("Bad-Name", "x", listOf(param), sideEffecting = false)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ToolSpec("ok_tool", "x", listOf(param, param), sideEffecting = false)
        }
    }
}
