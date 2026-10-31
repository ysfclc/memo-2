package app.memo.core.registry

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
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactStoreTest {

    private val now = 1_700_000_000_000L

    /** One approvable kind: the original payload, a same-key payload with one changed field, and the registry it lands in. */
    private class Fixture(
        val kind: String,
        val registryKind: String,
        val original: JSONObject,
        val changed: JSONObject,
    )

    private val fixtures = listOf(
        Fixture(
            kind = "person",
            registryKind = "people",
            original = Person(
                fullName = "Ada Lovelace",
                email = "ada@example.com",
                phone = "+44 20 7946 0000",
                companyKey = "analytical.com",
                role = "Engineer",
                notes = "Met at the meetup",
            ).toJson(),
            changed = Person(
                fullName = "Ada Lovelace",
                email = "ada@example.com",
                phone = "+44 20 7946 0000",
                companyKey = "analytical.com",
                role = "Engineer",
                notes = "Met again",
            ).toJson(),
        ),
        Fixture(
            kind = "company",
            registryKind = "companies",
            original = Company(domain = "analytical.com", displayName = "Analytical Engines Ltd").toJson(),
            changed = Company(domain = "analytical.com", displayName = "Analytical Engines plc").toJson(),
        ),
        Fixture(
            kind = "job_posting",
            registryKind = "job_postings",
            original = JobPosting(
                companyKey = "analytical.com",
                role = "Staff Engineer",
                url = "https://analytical.com/jobs/42",
                summary = "Platform team",
            ).toJson(),
            changed = JobPosting(
                companyKey = "analytical.com",
                role = "Staff Engineer",
                url = "https://analytical.com/jobs/42",
                summary = "Platform team, remote-first",
            ).toJson(),
        ),
        Fixture(
            kind = "application",
            registryKind = "applications",
            original = JobApplication(
                jobKey = "analytical.com|Staff Engineer",
                state = JobApplicationState.APPLIED,
                appliedAt = now,
                contactKey = "ada@example.com",
                notes = "Referred",
            ).toJson(),
            changed = JobApplication(
                jobKey = "analytical.com|Staff Engineer",
                state = JobApplicationState.APPLIED,
                appliedAt = now,
                contactKey = "ada@example.com",
                notes = "Referred by Ada",
            ).toJson(),
        ),
        Fixture(
            kind = "task",
            registryKind = "tasks",
            original = Task(title = "Send follow-up", state = TaskState.OPEN, dueAt = now, notes = "Ask").toJson(),
            changed = Task(title = "Send follow-up", state = TaskState.OPEN, dueAt = now, notes = "Ask about the team").toJson(),
        ),
        Fixture(
            kind = "draft",
            registryKind = "drafts",
            original = ContentDraft(
                kind = DraftKind.POST,
                title = "Launch note",
                body = "Checkpoints beat vibes.",
                createdAt = now,
            ).toJson(),
            changed = ContentDraft(
                kind = DraftKind.POST,
                title = "Launch note",
                body = "Checkpoints beat vibes.",
                audienceKey = "analytical.com",
                createdAt = now,
            ).toJson(),
        ),
        Fixture(
            kind = "post",
            registryKind = "posts",
            original = SocialPost(
                url = "https://x.com/example/status/1",
                platform = "x",
                excerpt = "Checkpoints beat vibes.",
                postedAt = now,
            ).toJson(),
            changed = SocialPost(
                url = "https://x.com/example/status/1",
                platform = "x",
                excerpt = "Checkpoints beat vibes, always.",
                postedAt = now,
            ).toJson(),
        ),
        Fixture(
            kind = "project",
            registryKind = "projects",
            original = Project(
                name = "Memo launch",
                summary = "Ship the first beta",
                status = ProjectStatus.ACTIVE,
                tags = listOf("launch"),
            ).toJson(),
            changed = Project(
                name = "Memo launch",
                summary = "Ship the first private beta",
                status = ProjectStatus.ACTIVE,
                tags = listOf("launch"),
            ).toJson(),
        ),
        Fixture(
            kind = "note",
            registryKind = "notes",
            original = Note(
                projectKey = "memo-launch",
                title = "Pricing idea",
                body = "Charge per workspace.",
                kind = NoteKind.IDEA,
                createdAt = now,
            ).toJson(),
            changed = Note(
                projectKey = "memo-launch",
                title = "Pricing idea",
                body = "Charge per workspace, not per seat.",
                kind = NoteKind.IDEA,
                createdAt = now,
            ).toJson(),
        ),
    )

    @Test
    fun kindsEqualThePromptArtifactKinds() {
        assertEquals(Prompts.ARTIFACT_KINDS, ArtifactStore.kinds)
        assertEquals(9, ArtifactStore.kinds.size)
    }

    @Test
    fun everyKindIsCreatedThenReusedThenBumpedInItsOwnRegistry() {
        assertEquals(ArtifactStore.kinds, fixtures.map { it.kind }.toSet())
        for (f in fixtures) {
            val ws = Workspace()

            assertEquals(
                ArtifactStore.Result.Saved("created"),
                ArtifactStore.persistResult(ws, f.kind, f.original.toString(), now),
                "first save of '${f.kind}'",
            )
            assertEquals(listOf(f.registryKind), filledRegistries(ws), "registry for '${f.kind}'")

            assertEquals(
                ArtifactStore.Result.Saved("reused"),
                ArtifactStore.persistResult(ws, f.kind, f.original.toString(), now + 1),
                "identical re-save of '${f.kind}'",
            )
            assertEquals(
                ArtifactStore.Result.Saved("bumped"),
                ArtifactStore.persistResult(ws, f.kind, f.changed.toString(), now + 2),
                "changed re-save of '${f.kind}'",
            )

            val registry = ws.all().single { it.kind == f.registryKind }
            assertEquals(1, registry.size, "still one row for '${f.kind}'")
            assertEquals(2, registry.toRows().getJSONObject(0).getInt("version"), "bumped version for '${f.kind}'")
        }
    }

    @Test
    fun persistReturnsEmptyStringOnSuccessAndTheReasonOtherwise() {
        val ws = Workspace()
        val person = fixtures.first { it.kind == "person" }
        assertEquals("", ArtifactStore.persist(ws, "person", person.original.toString(), now))
        assertEquals("", ArtifactStore.persist(ws, "person", person.original.toString(), now))
        assertEquals("payload is not JSON", ArtifactStore.persist(ws, "person", "{oops", now))
    }

    @Test
    fun invalidJsonIsRejectedWithoutThrowing() {
        val ws = Workspace()
        for (payload in listOf("", "{oops", "[]", "null", "42")) {
            assertEquals(
                ArtifactStore.Result.Rejected("payload is not JSON"),
                ArtifactStore.persistResult(ws, "person", payload, now),
                "payload '$payload'",
            )
        }
        assertEquals(0, ws.people.size)
    }

    @Test
    fun invalidEntityIsRejectedWithItsKind() {
        val ws = Workspace()
        assertEquals(
            ArtifactStore.Result.Rejected("invalid project"),
            ArtifactStore.persistResult(ws, "project", """{"summary":"no name"}""", now),
        )
        assertEquals(
            ArtifactStore.Result.Rejected("invalid note"),
            ArtifactStore.persistResult(ws, "note", """{"body":"no title"}""", now),
        )
        assertEquals(
            ArtifactStore.Result.Rejected("invalid person"),
            ArtifactStore.persistResult(ws, "person", """{"notes":"no name"}""", now),
        )
        assertEquals(0, filledRegistries(ws).size)
    }

    @Test
    fun unknownKindIsRejectedWithItsName() {
        val ws = Workspace()
        assertEquals(
            ArtifactStore.Result.Rejected("kind 'spaceship' cannot be saved"),
            ArtifactStore.persistResult(ws, "spaceship", """{"fuel":"lots"}""", now),
        )
        assertEquals("kind 'spaceship' cannot be saved", ArtifactStore.persist(ws, "spaceship", "{}", now))
    }

    @Test
    fun registryFailureReasonIsPassedThrough() {
        // A name made only of symbols yields a blank natural key: the registry quarantines it and refuses.
        val ws = Workspace()
        val result = ArtifactStore.persistResult(ws, "project", """{"name":"!!!"}""", now)
        assertEquals(ArtifactStore.Result.Rejected("blank natural key"), result)
        assertEquals(1, ws.projects.quarantinedRows().size)
        assertTrue(ws.projects.items().isEmpty())
    }

    private fun filledRegistries(ws: Workspace): List<String> =
        ws.all().filter { it.size > 0 }.map { it.kind }
}
