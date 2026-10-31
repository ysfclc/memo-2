package app.memo.ui.artifact

import app.memo.core.domain.Company
import app.memo.core.domain.ContentDraft
import app.memo.core.domain.DraftKind
import app.memo.core.domain.JobApplication
import app.memo.core.domain.JobApplicationState
import app.memo.core.domain.JobPosting
import app.memo.core.domain.Note
import app.memo.core.domain.NoteKind
import app.memo.core.domain.Person
import app.memo.core.domain.Project
import app.memo.core.domain.ProjectStatus
import app.memo.core.domain.SocialPost
import app.memo.core.domain.Task
import app.memo.core.domain.TaskState
import app.memo.core.prompts.Prompts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RendererCoverageTest {

    /** Realistic filled artifacts, serialized by the real domain `toJson()`. */
    private val realJson: Map<String, String> = mapOf(
        "person" to Person(
            fullName = "Ada Lovelace",
            email = "ada@example.com",
            phone = "+44 20 7946 0000",
            companyKey = "analytical.com",
            role = "Engineer",
            notes = "Met at the analytical engine meetup",
        ).toJson().toString(),
        "company" to Company(
            domain = "analytical.com",
            displayName = "Analytical Engines Ltd",
            notes = "Hiring for the platform team",
        ).toJson().toString(),
        "job_posting" to JobPosting(
            companyKey = "analytical.com",
            role = "Staff Engineer",
            url = "https://analytical.com/jobs/42",
            summary = "Platform team, remote-first",
        ).toJson().toString(),
        "application" to JobApplication(
            jobKey = "analytical.com|Staff Engineer",
            state = JobApplicationState.APPLIED,
            appliedAt = 1_700_000_000_000L,
            contactKey = "ada@example.com",
            notes = "Referred by Ada",
        ).toJson().toString(),
        "task" to Task(
            title = "Send follow-up",
            state = TaskState.OPEN,
            dueAt = 1_700_000_000_000L,
            notes = "Ask about the team",
        ).toJson().toString(),
        "draft" to ContentDraft(
            kind = DraftKind.POST,
            title = "Launch note",
            body = "Checkpoints beat vibes.",
        ).toJson().toString(),
        "post" to SocialPost(
            url = "https://x.com/example/status/1",
            platform = "x",
            excerpt = "Checkpoints beat vibes.",
            postedAt = 1_700_000_000_000L,
        ).toJson().toString(),
        "project" to Project(
            name = "Memo launch",
            summary = "Ship the first private beta",
            status = ProjectStatus.ACTIVE,
            tags = listOf("launch", "beta"),
            principleKeys = listOf("local-first"),
        ).toJson().toString(),
        "note" to Note(
            projectKey = "memo-launch",
            title = "Pricing idea",
            body = "Charge per workspace, not per seat.",
            kind = NoteKind.IDEA,
            sourceRef = "https://example.com/pricing",
            createdAt = 1_700_000_000_000L,
        ).toJson().toString(),
    )

    @Test
    fun dedicatedRendererCoversEveryArtifactKind() {
        for (kind in Prompts.ARTIFACT_KINDS) {
            assertTrue(kind in ArtifactFields.dedicated, "missing dedicated renderer for kind '$kind'")
        }
    }

    @Test
    fun realJsonFixturesCoverEveryArtifactKind() {
        assertEquals(Prompts.ARTIFACT_KINDS, realJson.keys)
    }

    @Test
    fun everyKindRendersRealDomainJsonWithoutFallback() {
        for ((kind, json) in realJson) {
            val rendered = ArtifactFields.render(kind, json)
            assertFalse(rendered.isFallback, "kind '$kind' fell back to raw rendering")
            assertTrue(rendered.title.isNotBlank(), "kind '$kind' has a blank title")
            assertTrue(rendered.fields.isNotEmpty(), "kind '$kind' has no fields")
            assertEquals(kind, rendered.kind)
        }
    }

    @Test
    fun unknownKindFallsBackWithItsKeys() {
        val rendered = ArtifactFields.render("spaceship", """{"fuel":"lots","crew":"3"}""")
        assertTrue(rendered.isFallback)
        assertEquals("spaceship", rendered.title)
        assertEquals(listOf("crew", "fuel"), rendered.fields.map { it.label })
        assertEquals("3", rendered.fields.first { it.label == "crew" }.value)
    }

    @Test
    fun invalidJsonFallsBackWithoutThrowing() {
        val rendered = ArtifactFields.render("person", "{oops")
        assertTrue(rendered.isFallback)
        assertEquals("person", rendered.title)
        assertEquals(listOf("Raw"), rendered.fields.map { it.label })
    }

    @Test
    fun knownKindWithNoRecognisedFieldsFallsBack() {
        val rendered = ArtifactFields.render("person", """{"unrelated":"x"}""")
        assertTrue(rendered.isFallback)
        assertEquals(listOf("unrelated"), rendered.fields.map { it.label })
    }

    @Test
    fun fallbackTruncatesLongValues() {
        val long = "a".repeat(1000)
        val rendered = ArtifactFields.render("spaceship", """{"blob":"$long"}""")
        assertTrue(rendered.fields.single().value.length <= 280)
    }

    @Test
    fun draftCharactersEqualBodyLength() {
        val body = "Checkpoints beat vibes."
        val json = ContentDraft(kind = DraftKind.POST, title = "Launch", body = body).toJson().toString()
        val rendered = ArtifactFields.render("draft", json)
        assertEquals(body.length.toString(), rendered.fields.first { it.label == "Characters" }.value)
    }

    @Test
    fun postCharactersEqualExcerptLength() {
        val excerpt = "Hello there, world"
        val json = SocialPost(url = "https://x.com/example/status/2", excerpt = excerpt).toJson().toString()
        val rendered = ArtifactFields.render("post", json)
        assertEquals(excerpt.length.toString(), rendered.fields.first { it.label == "Characters" }.value)
    }

    @Test
    fun taskDueDateFormatsInUtc() {
        val rendered = ArtifactFields.render("task", realJson.getValue("task"))
        assertEquals("2023-11-14 22:13", rendered.fields.first { it.label == "Due" }.value)
    }
}
