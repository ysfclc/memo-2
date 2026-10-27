package app.memo.platform.android.voice

/**
 * How a pinned voice model ships.
 *
 * - [TAR_BZ2]: a `.tar.bz2` release archive whose [VoiceModel.archiveRoot] folder is installed
 *   under `filesDir/voice/<id>/`.
 * - [SINGLE_FILE]: one bare file (for example the Silero VAD `silero_vad.onnx`) installed directly.
 */
enum class VoicePackaging {
    TAR_BZ2,
    SINGLE_FILE,
}

/**
 * A pinned voice model: one sherpa-onnx release asset that [VoiceModelInstaller] downloads,
 * verifies against the pin and places under `filesDir/voice/<id>/`.
 *
 * The pin is authoritative: [url] is a `https://github.com/` release URL, [sha256] is the
 * 64-hex digest of the exact asset, [sizeBytes] its byte length. A file is installed only when
 * both match, as in the model hub.
 */
data class VoiceModel(
    /** Stable identifier, used as the install folder name and by the registry/UI. */
    val id: String,
    /** Release URL on an allowed host, per [VoiceDownloadPolicy]. */
    val url: String,
    /** 64-hex SHA-256 of the asset. */
    val sha256: String,
    /** Exact asset size in bytes. */
    val sizeBytes: Long,
    /** How the asset is packaged. */
    val packaging: VoicePackaging,
    /**
     * For [VoicePackaging.TAR_BZ2]: the folder inside the archive that becomes
     * `filesDir/voice/<id>`. Ignored for [VoicePackaging.SINGLE_FILE].
     */
    val archiveRoot: String,
    /**
     * Files that must exist when the model counts as installed. For [VoicePackaging.SINGLE_FILE]
     * exactly that one file.
     */
    val requiredFiles: List<String>,
)

/**
 * The pinned voice-model catalog: the exact sherpa-onnx release assets the engines use.
 *
 * Pins are evidence, not guesses: every URL and byte size comes from the k2-fsa/sherpa-onnx
 * release API, and every SHA-256 was computed from the downloaded asset itself (ENGINEERING_LOG
 * D-039). [VoiceModelInstaller] verifies size and digest before anything is installed.
 */
object VoiceModels {

    /** The pinned models, in engine order: streaming ASR, offline ASR, TTS, VAD, wake word. */
    val entries: List<VoiceModel> = listOf(
        VoiceModel(
            id = "asr-zipformer-en-20m-mobile",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2",
            sha256 = "753a5362538539212442efbfb8dd5748db82e35e7deaec5330f49c56e81e40fd",
            sizeBytes = 107_569_151,
            packaging = VoicePackaging.TAR_BZ2,
            archiveRoot = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile",
            requiredFiles = listOf(
                "tokens.txt",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
            ),
        ),
        VoiceModel(
            id = "asr-whisper-base",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            sha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
            sizeBytes = 207_557_382,
            packaging = VoicePackaging.TAR_BZ2,
            archiveRoot = "sherpa-onnx-whisper-base",
            requiredFiles = listOf(
                "base-tokens.txt",
                "base-encoder.onnx",
                "base-decoder.onnx",
            ),
        ),
        VoiceModel(
            id = "tts-kokoro-en-v0-19",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
            sha256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            sizeBytes = 319_625_534,
            packaging = VoicePackaging.TAR_BZ2,
            archiveRoot = "kokoro-en-v0_19",
            requiredFiles = listOf(
                "model.onnx",
                "tokens.txt",
                "voices.bin",
            ),
        ),
        VoiceModel(
            id = "vad-silero-v5",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx",
            sha256 = "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396",
            sizeBytes = 2_313_101,
            packaging = VoicePackaging.SINGLE_FILE,
            archiveRoot = "",
            requiredFiles = listOf("silero_vad_v5.onnx"),
        ),
        VoiceModel(
            id = "kws-zipformer-gigaspeech-3-3m-mobile",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
            sha256 = "2e6ac2577310bfa2f4b6b5fab0478b868c9d0b2cb2c51b3e13b50581b588864d",
            sizeBytes = 15_667_804,
            packaging = VoicePackaging.TAR_BZ2,
            archiveRoot = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile",
            requiredFiles = listOf(
                "tokens.txt",
                "keywords.txt",
                "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            ),
        ),
    )
}
