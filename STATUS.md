# Memo — Status (honest, per AGENTS.md rule 5)

Legend: ✅ verified (CI or device evidence) · 🟡 implemented, verification pending (needs a device) · ⛔ planned, not in this build.

| Area | State | Notes |
|---|---|---|
| CI producing debug APK | ✅ | `.github/workflows/build-memo-apk.yml` — unit tests + lint + assembleDebug, artifact `memo-apk` |
| Repo / wrapper integrity | ✅ | Gradle 8.14.6 wrapper with official `distributionSha256Sum`; AGP 8.13.0; Kotlin 2.4.21 |
| Pure-Kotlin core (schemas, engine, registry, audit, redaction) | ✅ | JVM tests in `core/src/test`, now run in CI (`:core:test`) |
| Async engine steps + audit chain wiring | ✅ | CI tests (M0.A1, M0.A8) |
| Registry v2: semver, 16 registries, v1→v2 migration | ✅ | CI tests (M0.A3, M0.A4) |
| Action policy (en/pl/tr/de/es/fr), channel switches, kill switch (core) | ✅ | CI tests; pl/tr terms need native review |
| Model router, rate limits, circuit breaker (core) | ✅ | CI tests (M0.A6) |
| Platform interfaces (`core/platform`) | ✅ | CI (M0.A7) |
| Encrypted Keystore vault + plaintext migration | 🟡 | CI tests on the format and migration logic; Keystore on device unverified |
| Native build: llama.cpp b11460 JNI, sherpa-onnx 1.13.8, LiteRT-LM dep | 🟡 | Compiles and packages in CI; not exercised on device yet |
| Runtime wiring (`MemoRuntime`) | 🟡 | Workspace (SYN envelope), runs and hash-chained audit persisted to app-private files |
| Planner (text/voice → gated workflow) | 🟡 | Person, task, reminder, draft: fill → HUMAN_APPROVAL → DATA_PERSIST |
| Compose UI shell | 🟡 | Live (runs + approvals with filled artifact), Talk (text + system speech recognizer), Work, Studio, Models, Settings |
| Local model download | 🟡 | One tap per pinned catalog model; resumable; size + SHA-256 verified before use; free-space check |
| Local model discovery / import | 🟡 | Scan app storage for model files; pick any file via the system picker; only hash-matched files are installed |
| On-device inference (LiteRT-LM) | ⛔ | Models download and verify, but are not yet loaded; drafts use a deterministic outline |
| Browser automation (frontier sites) | ⛔ | Executor returns an explicit failure; planner never emits browser steps |
| App automation (accessibility) | ⛔ | Executor returns an explicit failure; no accessibility service declared |
| Metrics collectors | ⛔ | Executor returns an explicit failure |
| Device-wide AI app discovery | ⛔ | Not in this build |
| Wake-word from locked screen | ⛔ | No public Android API; degrade ladder planned (D-010) |
| Remote-runner (Python ADB guest) | ⛔ | Roadmap (D-009) |

This file is updated at every milestone; overclaiming here is a bug.
