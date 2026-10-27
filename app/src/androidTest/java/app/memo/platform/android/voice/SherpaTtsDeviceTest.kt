package app.memo.platform.android.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The M2.A2 device Build step: "emulator: synthesize a sentence to a WAV in app files, pull it
 * with adb, listen."
 *
 * Drives the real product path end to end on the device: [VoiceModelInstaller] downloads the
 * pinned Kokoro archive over the app's own HTTPS policy, verifies the SHA-256 pin and unpacks it
 * under `filesDir/voice/` behind [TarBz2Extractor]'s path guard; then [SherpaTts] loads the model
 * from the app's private dir (never assets) and synthesizes a sentence to a WAV in `filesDir/`.
 *
 * Blocking throughout, so it runs on the instrumentation thread only.
 */
@RunWith(AndroidJUnit4::class)
class SherpaTtsDeviceTest {

    @Test
    fun kokoroInstallsAndSynthesizesWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = VoiceModels.entries.single { it.id == "tts-kokoro-en-v0-19" }
        val installer = VoiceModelInstaller(File(context.filesDir, "voice"))

        val outcome = installer.install(model) { done, total ->
            println("SHERPA_TTS_DEVICE_TEST download $done/$total")
        }
        val dir = when (outcome) {
            is VoiceModelInstaller.Outcome.Installed -> outcome.dir
            is VoiceModelInstaller.Outcome.Failed -> throw AssertionError("install failed: $outcome")
        }
        assertTrue("installer did not mark the model installed", installer.isInstalled(model))

        // The installer renames staging/<archiveRoot> onto root/<id>, so outcome.dir IS the
        // model dir: files/voice/<id>/model.onnx, no archiveRoot level below it.
        val modelDir = dir
        SherpaTts.kokoro(modelDir).use { tts ->
            assertTrue("unexpected speaker count: ${tts.numSpeakers}", tts.numSpeakers >= 1)

            val text = "Hello from Memo. This sentence was synthesized on the device."
            val audio = tts.generate(text)
            val seconds = audio.samples.size / audio.sampleRate.toFloat()
            assertTrue("empty audio for: $text", audio.samples.isNotEmpty())
            assertTrue("implausible duration ${seconds}s at ${audio.sampleRate} Hz", seconds in 1f..30f)

            val outDir = File(context.filesDir, "voice-test").apply { mkdirs() }
            val wav = File(outDir, "kokoro-hello.wav")
            assertTrue("GeneratedAudio.save reported failure", audio.save(wav.absolutePath))
            assertTrue("wav file missing or empty: $wav", wav.isFile && wav.length() > 0)
            println("SHERPA_TTS_DEVICE_TEST wav=${wav.absolutePath} seconds=$seconds " +
                "sampleRate=${audio.sampleRate} bytes=${wav.length()}")
        }
    }
}
