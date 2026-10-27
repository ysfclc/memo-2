package app.memo.platform.android.voice

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin hygiene: every catalog entry must be a policy-allowed HTTPS pin with a real digest and a
 * byte size the installer can verify. The digests themselves were computed from the release
 * assets (ENGINEERING_LOG D-039); this test asserts the shape [VoiceModelInstaller] relies on.
 */
class VoiceModelsPinTest {

    @Test
    fun idsAreUniqueAndFolderSafe() {
        val ids = VoiceModels.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
        ids.forEach { id -> assertTrue(Regex("[a-z0-9-]+").matches(id), id) }
    }

    @Test
    fun pinsAreHttpsAndAllowedByTheDownloadPolicy() {
        VoiceModels.entries.forEach { m ->
            assertTrue(m.url.startsWith("https://"), m.url)
            assertEquals(null, VoiceDownloadPolicy.refusal(URL(m.url)), m.url)
        }
    }

    @Test
    fun digestsAreSixtyFourHexAndSizesArePositive() {
        VoiceModels.entries.forEach { m ->
            assertTrue(Regex("[0-9a-f]{64}").matches(m.sha256), m.id)
            assertTrue(m.sizeBytes > 0, m.id)
        }
    }

    @Test
    fun archiveEntriesNameTheirRootAndRequiredFiles() {
        VoiceModels.entries.filter { it.packaging == VoicePackaging.TAR_BZ2 }.forEach { m ->
            assertTrue(m.archiveRoot.isNotEmpty(), m.id)
            assertTrue(m.requiredFiles.isNotEmpty(), m.id)
            m.requiredFiles.forEach { f ->
                assertTrue(!f.contains("..") && !f.startsWith("/") && f.isNotEmpty(), f)
            }
        }
    }

    @Test
    fun singleFileEntriesCarryExactlyOneRequiredFile() {
        VoiceModels.entries.filter { it.packaging == VoicePackaging.SINGLE_FILE }.forEach { m ->
            assertEquals(1, m.requiredFiles.size, m.id)
            assertTrue(m.requiredFiles.single().isNotEmpty(), m.id)
        }
    }
}
