package app.memo.runtime

import app.memo.platform.android.voice.VoiceModel
import app.memo.platform.android.voice.VoiceModelInstaller
import app.memo.platform.android.voice.VoiceModels
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Voice section's download state machine on the JVM: a fake [VoiceInstallPort] and a
 * same-thread executor (submitted work runs inline on the test thread) pin the behaviour the
 * real app gets on its dedicated download thread — one install per model, second downloads
 * ignored while busy, Downloading progress towards Installed, failure reporting, retries and
 * the user-facing model list.
 */
class VoiceModelDownloadsTest {

    // --- fakes -----------------------------------------------------------------------------------

    /** Same-thread executor: submitted work runs inline, in deterministic sequence. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    /** Configurable port: hook during install, outcome to return, message to throw, progress. */
    private class FakePort(vararg val progress: Pair<Long, Long>) : VoiceInstallPort {
        val installs = mutableListOf<VoiceModel>()
        var outcome: VoiceModelInstaller.Outcome? = null
        var throwMessage: String? = null

        /** Runs from inside install while the status is already [VoiceModelStatus.Downloading]. */
        var duringInstall: ((VoiceModel) -> Unit)? = null

        override fun isInstalled(model: VoiceModel): Boolean = installs.any { it.id == model.id }

        override fun install(model: VoiceModel, onProgress: (Long, Long) -> Unit): VoiceModelInstaller.Outcome {
            installs += model
            duringInstall?.invoke(model)
            throwMessage?.let { throw IllegalStateException(it) }
            progress.forEach { (done, total) ->
                onProgress(done, total)
                duringInstall?.invoke(model)
            }
            return outcome ?: VoiceModelInstaller.Outcome.Installed(File(model.id))
        }
    }

    // --- acceptance ------------------------------------------------------------------------------

    @Test
    fun refreshMarksInstalledModelsAndTheOthers() {
        val port = FakePort()
        port.installs += USER_FACING.first { it.id == "asr-zipformer-en-20m-mobile" }
        port.installs += USER_FACING.first { it.id == "tts-kokoro-en-v0-19" }
        val downloads = VoiceModelDownloads(port, DirectExecutor)

        downloads.refresh()

        assertIs<VoiceModelStatus.Installed>(downloads.statusOf("asr-zipformer-en-20m-mobile"))
        assertIs<VoiceModelStatus.NotInstalled>(downloads.statusOf("asr-whisper-base"))
        assertIs<VoiceModelStatus.Installed>(downloads.statusOf("tts-kokoro-en-v0-19"))
        assertIs<VoiceModelStatus.NotInstalled>(downloads.statusOf("vad-silero-v5"))
    }

    @Test
    fun downloadGoesThroughDownloadingStatesToInstalledWithOneInstall() {
        val asr = USER_FACING.first { it.id == "asr-zipformer-en-20m-mobile" }
        val port = FakePort(asr.sizeBytes / 4 to asr.sizeBytes, asr.sizeBytes / 2 to asr.sizeBytes)
        var holder: VoiceModelDownloads? = null
        val seen = mutableListOf<VoiceModelStatus>()
        port.duringInstall = { model -> holder?.let { seen += it.statusOf(model.id) } }
        val downloads = VoiceModelDownloads(port, DirectExecutor)
        holder = downloads

        downloads.download(asr.id)

        assertIs<VoiceModelStatus.Installed>(downloads.statusOf(asr.id))
        assertEquals(1, port.installs.size)
        assertEquals(asr, port.installs.single())
        // Two throttled progress slices (25%, 50%) plus the initial pre-install read: the
        // mid-install status is always Downloading.
        assertTrue(seen.isNotEmpty())
        seen.forEach { state -> assertIs<VoiceModelStatus.Downloading>(state) }
        seen.filterIsInstance<VoiceModelStatus.Downloading>().forEach { state ->
            assertEquals(asr.sizeBytes, state.totalBytes)
        }
    }

    @Test
    fun downloadWhileInstalledIsIgnored() {
        val port = FakePort()
        val downloads = VoiceModelDownloads(port, DirectExecutor)

        downloads.download("asr-zipformer-en-20m-mobile")
        downloads.download("asr-zipformer-en-20m-mobile")

        assertIs<VoiceModelStatus.Installed>(downloads.statusOf("asr-zipformer-en-20m-mobile"))
        assertEquals(1, port.installs.size)
    }

    @Test
    fun downloadWhileDownloadingIsIgnoredByItself() {
        val asr = USER_FACING.first { it.id == "asr-zipformer-en-20m-mobile" }
        val port = FakePort()
        var holder: VoiceModelDownloads? = null
        port.duringInstall = { model -> holder?.download(model.id) }
        val downloads = VoiceModelDownloads(port, DirectExecutor)
        holder = downloads

        downloads.download(asr.id)

        // The reentrant download ran while the status was Downloading, so the install was
        // dispatched exactly once and the job still completed.
        assertIs<VoiceModelStatus.Installed>(downloads.statusOf(asr.id))
        assertEquals(1, port.installs.size)
    }

    @Test
    fun installFailureBecomesFailedAndRetryCanSucceed() {
        val port = FakePort()
        port.outcome = VoiceModelInstaller.Outcome.Failed("x")
        val downloads = VoiceModelDownloads(port, DirectExecutor)

        downloads.download("tts-kokoro-en-v0-19")
        val first = downloads.statusOf("tts-kokoro-en-v0-19")
        assertIs<VoiceModelStatus.Failed>(first)
        assertEquals("x", first.reason)

        port.outcome = null
        downloads.download("tts-kokoro-en-v0-19")
        assertIs<VoiceModelStatus.Installed>(downloads.statusOf("tts-kokoro-en-v0-19"))
        assertEquals(2, port.installs.size)
    }

    @Test
    fun installThrowingBecomesFailedWithTheMessage() {
        val port = FakePort()
        port.throwMessage = "disk went away"
        val downloads = VoiceModelDownloads(port, DirectExecutor)

        downloads.download("vad-silero-v5")

        val status = downloads.statusOf("vad-silero-v5")
        assertIs<VoiceModelStatus.Failed>(status)
        assertEquals("disk went away", status.reason)
    }

    @Test
    fun userFacingIsExactlyTheFourVoiceModels() {
        assertEquals(
            listOf(
                "asr-zipformer-en-20m-mobile",
                "asr-whisper-base",
                "tts-kokoro-en-v0-19",
                "vad-silero-v5",
            ),
            USER_FACING.map { it.id },
        )
        assertTrue(USER_FACING.all { model -> VoiceModels.entries.contains(model) })
    }
}
