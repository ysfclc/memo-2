# Memo — Status (honest, per AGENTS.md rule 5)

Legend: ✅ verified (CI or device evidence) · 🟡 implemented, verification pending (needs a device) · ⛔ planned, not in this build.

| Area | State | Notes |
|---|---|---|
| CI producing debug APK | ✅ | [Run 40518483948](https://github.com/ysfclc/memo-2/actions/runs/40518483948) passed on commit `97bc990`; core/app tests, lint, APK verification, native libraries and artifact upload passed. |
| Repo / wrapper integrity | ✅ | Gradle 8.14.6 wrapper with official `distributionSha256Sum`; AGP 8.13.0; Kotlin 2.4.21 |
| Pure-Kotlin core (schemas, engine, registry, audit, redaction) | ✅ | JVM tests in `core/src/test`, now run in CI (`:core:test`) |
| Async engine steps + audit chain wiring | ✅ | CI tests (M0.A1, M0.A8) |
| Registry v2: semver, 16 registries, v1→v2 migration | ✅ | CI tests (M0.A3, M0.A4) |
| Action policy (en/pl/tr/de/es/fr), channel switches, kill switch (core) | ✅ | CI tests; pl/tr terms need native review |
| Model router, rate limits, circuit breaker (core) | ✅ | CI tests (M0.A6) |
| Platform interfaces (`core/platform`) | ✅ | CI (M0.A7) |
| Encrypted Keystore vault + plaintext migration | 🟡 | CI tests on the format and migration logic; Keystore on device unverified |
| Native build: llama.cpp b11460 JNI, sherpa-onnx 1.13.8, LiteRT-LM dep | 🟡 | Compiles and packages in CI; LiteRT-LM ran Qwen3 inference on the test AVD, while llama.cpp/sherpa device paths remain untested. |
| Runtime wiring (`MemoRuntime`) | 🟡 | Workspace (SYN envelope), runs and hash-chained audit persisted to app-private files |
| Planner (text/voice → gated workflow) | 🟡 | Person, task, reminder, draft: fill → HUMAN_APPROVAL → DATA_PERSIST |
| Compose UI shell | 🟡 | Live, Work, Studio, Settings; Talk and Models screens rendered on clean Android 14 test AVD. Talk shows Qwen3 0.6B/LiteRT-LM, the Stop cancellation fix (3fa7826, CI-green) has not been re-checked on any device yet. Physical-phone check pending. |
| Local model download | 🟡 | Qwen3 0.6B (344.7 MB) downloaded and shown Installed and verified on `Memo_API34_CHAT`; other planned models remain pending. |
| App identity + in-place updates | 🟡 | Package `app.memo2` (debug `app.memo2.debug`), label "Memo" (D-053/D-054); every debug APK signed by the committed shared key (SHA-256 9e526ee8…20be2e, verified on the CI APK with apksigner). Installed side by side with the old `app.memo.debug` and launched on the emulator. Install on the user's phone UNVERIFIED. |
| Voice models download in-app | 🟡 | Models → Voice: streaming ASR, Whisper, Kokoro, Silero VAD with progress/retry (gap G5, VoiceModelDownloadsTest). Real download on a phone UNVERIFIED. |
| Voice messages (Text tab) | 🟡 | Hold to record → transcript as editable draft + clip attached → Send; bubble with playback; clip-only messages reach the model as their transcript (G4, G4b, G12; tests). Clips stored encrypted in the vault, played from memory (D-056). Device UNVERIFIED. |
| Read-aloud | 🟡 | Any chat message, Kokoro via sherpa-onnx only (D-051, ReadAloudTest). Device listening UNVERIFIED. |
| Background generation service | 🟡 | InferenceService promoted after 10 s of generation (G7). Device UNVERIFIED. |
| Local model discovery / import | 🟡 | Browse, Import, Scan device and the Android picker were inspected on the test AVD; scan reported no files and the picker was canceled without selecting anything. Import of a model file remains unverified. |
| On-device inference (LiteRT-LM + llama.cpp) | 🟡 | Qwen3 0.6B via LiteRT-LM returned `Hello` on the test AVD. A prior QEMU host crash did not recur on the controlled retry; its cause remains UNVERIFIED. Stop: code fix 3fa7826 (CI-green), device UNVERIFIED; llama.cpp and physical-phone inference are pending. A 'screen flicker during model runs' report is open (ENGINEERING_LOG, UNVERIFIED). |
| Browser automation (frontier sites) | ⛔ | Executor returns an explicit failure; planner never emits browser steps |
| App automation (accessibility) | ⛔ | Executor returns an explicit failure; no accessibility service declared |
| Metrics collectors | ⛔ | Executor returns an explicit failure |
| Device-wide AI app discovery | ⛔ | Not in this build |
| Wake-word from locked screen | ⛔ | No public Android API; degrade ladder planned (D-010) |
| Remote-runner (Python ADB guest) | ⛔ | Roadmap (D-009) |

This file is updated at every milestone; overclaiming here is a bug.
