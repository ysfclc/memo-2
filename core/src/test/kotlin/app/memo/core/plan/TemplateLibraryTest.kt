package app.memo.core.plan

import app.memo.core.domain.StepKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateLibraryTest {

    private val now = 1_700_000_000_000L

    private fun kindsOf(name: String): List<StepKind> {
        val seed = TemplateLibrary.seeds.single { it.name == name }
        val result = PlanProposalParser.parse(seed.planJson, now)
        assertTrue(result.ok, "$name did not parse: ${result.violations}")
        return result.spec!!.steps.map { it.kind }
    }

    @Test
    fun exactlyFiveSeedsWithUniqueNames() {
        assertEquals(
            listOf("uc1_brainstorm", "uc2_crm_capture", "uc3_job_apply", "uc4_content_publish", "uc5_generic_plan"),
            TemplateLibrary.seeds.map { it.name },
        )
        assertEquals(5, TemplateLibrary.seeds.map { it.name }.toSet().size)
    }

    @Test
    fun everySeedParsesAndPassesPolicy() {
        for (seed in TemplateLibrary.seeds) {
            val result = PlanProposalParser.parse(seed.planJson, now)
            assertTrue(result.ok, "${seed.name} rejected by parser: ${result.violations}")
            val spec = result.spec!!
            val policy = WorkflowPolicy.validateSpec(spec)
            assertTrue(policy.isEmpty(), "${seed.name} rejected by policy: $policy")
        }
    }

    @Test
    fun everySeedHasThreeLowercaseUniqueIntentExamples() {
        val seen = mutableSetOf<String>()
        for (seed in TemplateLibrary.seeds) {
            assertTrue(seed.intentExamples.size >= 3, "${seed.name} has fewer than 3 intent examples")
            for (example in seed.intentExamples) {
                assertTrue(example.isNotBlank(), "${seed.name} has a blank intent example")
                assertEquals(example.lowercase(), example, "${seed.name} intent example is not lowercase: '$example'")
                assertTrue(seen.add(example), "${seed.name} repeats intent example '$example' from another seed")
            }
        }
    }

    @Test
    fun uc3AndUc4GateTheirSideEffectSteps() {
        for (name in listOf("uc3_job_apply", "uc4_content_publish")) {
            val steps = PlanProposalParser.parse(
                TemplateLibrary.seeds.single { it.name == name }.planJson, now,
            ).spec!!.steps
            val sideEffectIndex = steps.indexOfFirst { it.isSideEffect }
            assertTrue(sideEffectIndex >= 0, "$name has no side-effect step: ${steps.map { it.kind }}")
            assertTrue(
                steps.subList(0, sideEffectIndex).any { it.kind == StepKind.HUMAN_APPROVAL },
                "$name side-effect step is not preceded by HUMAN_APPROVAL: ${steps.map { it.kind }}",
            )
        }
    }

    @Test
    fun seedsHaveTheirPlannedStepKindOrder() {
        assertEquals(
            listOf(StepKind.REASON, StepKind.BROWSER_AUTOMATION, StepKind.REASON, StepKind.HUMAN_APPROVAL, StepKind.DATA_PERSIST),
            kindsOf("uc1_brainstorm"),
        )
        assertEquals(
            listOf(StepKind.BROWSER_AUTOMATION, StepKind.REASON, StepKind.HUMAN_APPROVAL, StepKind.DATA_PERSIST),
            kindsOf("uc2_crm_capture"),
        )
        assertEquals(
            listOf(
                StepKind.BROWSER_AUTOMATION, StepKind.REASON, StepKind.HUMAN_APPROVAL,
                StepKind.BROWSER_AUTOMATION, StepKind.DATA_PERSIST,
            ),
            kindsOf("uc3_job_apply"),
        )
        assertEquals(
            listOf(StepKind.REASON, StepKind.HUMAN_APPROVAL, StepKind.BROWSER_AUTOMATION, StepKind.METRIC_COLLECT),
            kindsOf("uc4_content_publish"),
        )
        assertEquals(
            listOf(StepKind.REASON, StepKind.HUMAN_APPROVAL, StepKind.DATA_PERSIST),
            kindsOf("uc5_generic_plan"),
        )
    }
}
