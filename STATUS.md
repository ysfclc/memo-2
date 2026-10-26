# Memo — Status (honest, per AGENTS.md rule 5)

Legend: ✅ verified (CI or device evidence) · 🟡 implemented, verification pending · ⛔ planned, not in this build.

| Area | State | Notes |
|---|---|---|
| CI producing debug APK | ✅ | `.github/workflows/build-memo-apk.yml` — unit tests + lint + assembleDebug, artifact `memo-apk` |
| Repo / wrapper integrity | ✅ | Gradle 8.14.6 wrapper with official `distributionSha256Sum`; AGP 8.13.0; Kotlin 2.4.21 |
| Pure-Kotlin core (schemas, engine, registry) | 🟡 | lands in M1 commit; JVM tests in same commit |
| Encrypted vault (AES-GCM/Keystore, self-heal) | 🟡 | M2 |
| Browser runtime (frontier sites) | 🟡 | M2 — port of Fieldwork `BrowserSession`/`BrowserScripts` discipline |
| App automation (accessibility executor) | 🟡 | M2 — lock-pause, wrong-app-pause, bounded retries, confirm-to-type |
| Voice capture + command grammar | 🟡 | M2 — on-device recognizer, deterministic commands bypass the model |
| Approvals with content-hash binding | 🟡 | M2 |
| Metrics collectors + sparklines | 🟡 | M3 — URL-pattern collector + manual paste fallback |
| Full Compose UI shell | 🟡 | M3 — Live / Talk / Work / Studio / Browser / Approvals / Models / Settings |
| Wake-word from locked screen | ⛔ | No public Android API; degrade ladder shipped instead (D-010) |
| Remote-runner (Python ADB guest) | ⛔ | Roadmap (D-009) |

This file is updated at every milestone; overclaiming here is a bug.
