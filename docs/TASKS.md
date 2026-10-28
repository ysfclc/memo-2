# Memo — Master runbook: every task from zero to end, with tests, checks and build steps

This is the executable breakdown of `docs/BUILD_PLAN.md`:
- Each task is one Haiku-sized unit: one branch `task/<id>`, one green CI result, one lead verification.
- Specs live in the BUILD_PLAN section shown as [§x]. Features map in `docs/FEATURES.md`. Running notes are in `docs/PROGRESS.md`.

**Status:** `[x]` done and verified · `[~]` in progress · `[ ]` to do.

Each task lists:
- **Files**
- **Mini-tasks** (the implementation steps)
- **Tests** (named, all must exist and pass)
- **Checks** (what the lead verifies personally)
- **Build** (anything beyond the standard procedure, B-STD)

---

# PART 0 — From zero

## 0.1 Repository and branches
- [x] Repository `github.com/ysfclc/memo-2`, default branch `main`, public. Local clone: `C:\Users\yusufculcu\Desktop\gpt\memo`.
- [x] Modules: `:core` (pure Kotlin/JVM, all policy and logic) and `:app` (Android, Compose).
- [x] Branch model:
  - `main` always has a green CI run and an installable APK
  - `milestone/Mx` is the integration branch per milestone
  - `task/<id>` is one task, branched from `origin/milestone/Mx`
  - agents work in isolated git worktrees under `.claude/worktrees/` (git-ignored)
- [x] Never `git stash -u` in the main checkout: it moves live agent worktrees.

## 0.2 Toolchain (pinned; record any change as a D-### entry in ENGINEERING_LOG)
| Tool | Version | Where |
|---|---|---|
| JDK | Temurin 17 (17.0.20.1 locally) | CI `actions/setup-java@v5`; local `%USERPROFILE%\Android\jdk-17` |
| Gradle wrapper | 8.14.6 (SHA-pinned) | `gradle/wrapper` |
| AGP / Kotlin | 8.13.0 / 2.4.21 (+ compose plugin) | root `build.gradle.kts` |
| compile/target/min SDK | 35 / 35 / 30 | `app/build.gradle.kts` |
| NDK / CMake | 27.2.12479018 / 3.22.1 | CI sdkmanager; local Android CLI |
| llama.cpp | tag b11460, tarball SHA-256 `80c50949a5f4c9b63fb216bd362289f4e8ae64e47625eb7a87e8381c0f88b36a` | CMake FetchContent |
| sherpa-onnx | 1.13.8 static-link AAR, SHA-256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | `fetchSherpaAar` task |
| LiteRT-LM | 0.18.0 | Google Maven |
| Other deps | WorkManager 2.10.0, coroutines 1.9.0, webkit 1.17.1, ML Kit text-recognition 16.0.1, biometric 1.1.0, Glance 1.2.0, Compose BOM 2024.12.01 | `app/build.gradle.kts` |

## 0.3 Local environment (Windows, no admin)
- [x] JDK 17 in `%USERPROFILE%\Android\jdk-17`; `JAVA_HOME` set (user env).
- [x] Android SDK in `%USERPROFILE%\Android\Sdk`: cmdline-tools (new Android CLI `android.exe`), platform-tools, emulator, `platforms/android-35`, `build-tools/35.0.0`, `cmake/3.22.1`, `ndk/27.2.12479018`, `system-images/android-34/google_apis/x86_64`. `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `PATH` set (user env).
  - The new CLI uses **slash** package names: `android --sdk=<sdk> sdk install build-tools/35.0.0`. The old `sdkmanager` forwards to it.
- [x] Emulator AVD `Memo_API34` (Pixel 7, Android 14, x86_64), with WHPX acceleration verified (`emulator -accel-check`).
- [x] Launcher: `Desktop\gpt\run-memo-emulator.cmd [apk]` boots the AVD, optionally installs an APK, then launches Memo.
- [ ] `local.properties` with `sdk.dir` (git-ignored), created on the first local build.

## 0.4 CI pipeline (`.github/workflows/build-memo-apk.yml`), stage by stage
1. Checkout.
2. JDK 17 (Temurin).
3. Verify the project structure (settings, build files, wrapper).
4. Enable AndroidX (`gradle.properties`).
5. Configure the preinstalled SDK (locate `sdkmanager`).
6. Install `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`.
7. Restore the Gradle cache (`~/.gradle`, `app/libs`).
8. **Build:** `./gradlew --no-daemon --stacktrace :core:test testDebugUnitTest lintDebug assembleDebug`.
9. Verify the APK exists and is non-empty; print its sha256.
10. List APK native libs: fail if `lib/arm64-v8a/libmemo_llama.so` or `lib/x86_64/libmemo_llama.so` is missing.
11. Upload the artifact `memo-apk` (14 days, `if-no-files-found: error`).
- Triggers: push to `main` and tags `v*.*.*`, PRs to `main`, `workflow_dispatch`. Task and milestone branches use `workflow_dispatch`.
- Added later: an `instrumented` emulator job (M4.0), a release job (M2.5.A1), the benchmark job (M2.5.A2), `release.yml` (M3.7.C3).
- `publish-preview.yml` (dispatch only) copies a green run's APK to a pre-release. It validates inputs, provenance and the source sha. It needs the user's approval, because a pre-release is public.

## 0.5 Standard build and test procedure (B-STD), used by every task
1. `git fetch origin && git checkout -b task/<id> origin/milestone/<Mx>`
2. Implement only the files the task owns.
3. **Local (optional, now possible):**
   - core and unit tests: `.\gradlew.bat :core:test testDebugUnitTest`
   - full: `.\gradlew.bat :core:test testDebugUnitTest lintDebug assembleDebug`
   - install and run: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, then `adb shell monkey -p app.memo.debug -c android.intent.category.LAUNCHER 1`
   - instrumented tests (emulator running): `.\gradlew.bat connectedDebugAndroidTest`
4. Commit with the attribution trailer lines (Co-Authored-By + Claude-Session).
5. `git push -u origin task/<id>`
6. `gh workflow run build-memo-apk.yml -R ysfclc/memo-2 --ref task/<id>`, then `gh run list -R ysfclc/memo-2 --branch task/<id> --limit 1`, then `gh run watch <run> -R ysfclc/memo-2 --exit-status`
7. On red: `gh run view <run> -R ysfclc/memo-2 --log-failed | grep -B2 -A20 -E "FAILED|e: |error:"`. Fix and repeat.
8. Report: files, run URL, acceptance mapping, anything not done.

## 0.6 Lead verification gate (LVG), applied to every task
1. `git diff origin/milestone/<Mx>...origin/task/<id> --stat`: only the owned files changed.
2. Read the full diff of the logic: no stubs, TODOs, fake success or swallowed errors; policy laws hold (gates, envelopes, redaction, audit, no plaintext, no main-thread I/O).
3. `gh run view <run> --json conclusion,headSha`: success, and the sha equals the branch head.
4. Every named test exists and covers the acceptance item. Grep checks pass (e.g. no `Color(0x` outside the theme, no `RecognizerIntent`).
5. A failure goes back to the same agent with the exact defects. Repeat.
6. Merge into `milestone/<Mx>`. Resolve `ENGINEERING_LOG.md` conflicts by keeping both sides. Push. Update FEATURES and PROGRESS.

## 0.7 Milestone close procedure (MCP)
1. Integration CI on `milestone/<Mx>` is green.
2. **An independent review subagent** reviews the whole milestone diff: correctness, data loss, security, threading. All high/medium findings are fixed through fix tasks plus LVG, then reviewed again.
3. PR `milestone/<Mx>` → `main`; the PR CI is green; merge.
4. CI on `main` is green and the `memo-apk` artifact is present.
5. Update STATUS.md (✅ only with CI or device evidence), FEATURES, PROGRESS and CHANGELOG.
6. Local emulator smoke test: install the `main` APK, launch it, `adb logcat -b crash` is empty, screenshot.
7. Hand the user the milestone's device checklist.

## 0.8 Test layers
| Layer | Where | Runs in |
|---|---|---|
| Core unit tests (pure JVM) | `core/src/test` | CI `:core:test` |
| App JVM unit tests | `app/src/test` | CI `testDebugUnitTest` |
| Lint | Android lint | CI `lintDebug` (advisory until M6.6, then `abortOnError = true`) |
| Instrumented (emulator) | `app/src/androidTest` + fixtures | CI `instrumented` job (from M4.0); local `connectedDebugAndroidTest` |
| Benchmarks | `:baselineprofile` | CI benchmark job (M2.5.A2) |
| Device checks | user's phone | per milestone checklist |
| Independent review | review subagent | every milestone close |


---

## 0.9 Work packages (how the tasks are actually run)
User direction (2026-10-26): "bundle tasks that are touching same area or priority… I don't wanna run 160+ agents. You can even do small things inline along the way."

- **One agent per work package (WP), not per task.**
  - The task IDs below stay the checklist inside a package.
  - The agent makes one commit per task ID on one branch `wp/<name>` and runs CI after each commit.
  - The LVG is applied once per package, against every task's tests and checks.
- **The lead does inline**, with no agent:
  - CI/YAML edits
  - docs, FEATURES/PROGRESS/STATUS
  - small wiring (≲60 lines)
  - fixes found during LVG
  - merges and conflict resolution
  - milestone reviews are still a separate review agent
- At most 3 packages run at once. Packages within a wave own disjoint areas.
- Each package merges into the milestone branch it belongs to. A milestone goes to `main` once all its packages are merged and its review is clean.

| Wave | WP | Tasks bundled | Area | Milestone |
|---|---|---|---|---|
| 1 | **UI-KIT** | M2.B0b, M2.B0c, M2.B0d | `ui/motion`, `ui/components`, `ui/nav` | M1 |
| 1 | **LLM** | M1.A1, M1.A2, M1.A3 | `platform/android/llm`, `cpp/`, `runtime/InferenceManager` | M1 |
| 1 | **HUB** | M1.B1, M1.B2 | `core/catalog`, `app/hub` | M1 |
| 2 | **CHAT** | M1.C1, M1.C2, M1.B3 | `core/chat`, `ui/chat`, `ui/models` | M1 |
| 2 | **VOICE** | M2.A1, M2.A2, M2.A3, M3.6.C1 | `platform/android/voice`, `core/voice` | M2 |
| 2 | **AUTO-CORE** | M3.5.A1, M3.5.A2, M4.A0, M4.L1 | `core/policy/Autonomy`, `core/automation` (pure) | M3 |
| 3 | **UX-SHELL** [§2p] | M2.U1, M2.U2, M2.U3, M2.U4, M2.U5 | `ui/nav`, `ui/palette`, `core/domain/Favorite`, `runtime/UiStateStore`, kit list/state components | M1 |
| 3 | **VOICE-SURFACES** | M2.B1, M2.C1, M2.C2, M2.C3 | `ui/voice`, `platform/android/assist`, `surface` | M2 |
| 3 | **BRAIN** | M3.A1, M3.A4, M3.A2 | `core/plan`, `core/agent`, `core/prompts`, `runtime/Planner*` | M3 |
| 3 | **SPEED** | M2.5.A1, M2.5.A2, M2.5.A3, M3.7.C3 | build, `:baselineprofile`, `update/`, `release.yml` | M2 |
| 4 | **APPROVALS** | M3.A3, M3.B1, M3.B2, M3.B3, M3.C1 | `ui/artifact`, `ui/live`, `ui/chat/PlanCard`, widget | M3 |
| 4 | **EDITORS** | M3.5.B1, M3.5.B2, M3.5.C1 | `ui/edit`, `ui/workflow`, `ui/settings` | M3 |
| 4 | **LANG** | M3.6.A1, M3.6.B1, M3.7.A2 | `core/plan/grammar`, `res/values-*` | M3 |
| 5 | **TIME** | M3.7.A1, M3.7.C1 | `core/time`, `platform/android/notify`, `ui/today`, `ui/drive` | M3 |
| 5 | **KNOWLEDGE** | M3.7.B1, M3.7.B2, M3.7.B3, M3.7.C4 | `capture/`, `core/knowledge`, `search/`, `ui/memory` | M3 |
| 5 | **COMPILER-CORE** | M3.8.A1, M3.8.A2, M3.8.A3, M3.8.B1 | `core/capability` | M3 |
| 6 | **COMPILER-LOOP** | M3.8.A4, M3.8.A5, M3.8.C1 | `core/capability` loop, `ui/compiler` | M3 |
| 6 | **SELF** | M3.9.A1, M3.9.A2 | `core/self`, `app/self` | M3 |
| 6 | **WEB** | M4.0, M4.A0b, M4.A1, M4.A3 | CI emulator job, fixtures, `platform/android/browser` runtime, `ui/browser` | M4 |
| 7 | **STUDIO** | M3.9.B1, M3.9.B2, M3.9.B3, M3.9.C1 | `studio/cv`, `core/self` tailoring | M3 |
| 7 | **FRONTIER** | M4.A2, M4.A5, M4.C2 | adapters, `FrontierExecutor`, API channel, routing settings | M4 |
| 7 | **LIVE-VIEW** | M4.L2 | `ui/live/session`, preview capture | M4 |
| 8 | **APPS** | M4.B1, M4.B2, M4.D1, M4.L3 | `platform/android/automation`, `ui/host` | M4 |
| 8 | **DISCOVERY** | M4.C1, M4.B3, M4.B4 | `core/discovery`, `core/adapter/AppAdapterPack`, capability map | M4 |
| 8 | **COUNCIL-CORE** | M4.A4a, M4.A4d | `core/council`, `core/agent/EntityExtraction` | M4 |
| 9 | **OVERLAY** | M4.D2, M4.L4 | `surface/OverlayService`, `ui/overlay`, previews | M4 |
| 9 | **COUNCIL-APP** | M4.A4b, M4.A4c | `runtime/CouncilService`, `ui/council` | M4 |
| 9 | **TEACH-WEB** | M4.E0, M4.E1, M4.E2, M4.E3 | `core/automation` schemas, `assets/picker`, `ui/teach` | M4 |
| 10 | **TEACH-APP** | M4.E4, M4.E5, M4.E6 | `automation/NodeInspector`, healing, `ui/workflow` pickers | M4 |
| 10 | **UC-CORE** | M3.C2, M5.A1, M5.C2 | CRM, brainstorm, generic plans | M5 |
| 10 | **ONBOARDING** | M3.7.C2, M6.5 | `ui/onboarding`, `ui/help`, permission centre | M6 |
| 11 | **UC-JOBS** | M5.B1 | jobs flow | M5 |
| 11 | **UC-CONTENT** | M5.C1, M5.C1a, M5.C1b | content, metrics, media, image gen | M5 |
| 11 | **HARDEN** | M6.1, M6.2, M6.3, M6.4 | recovery, diagnostics, backup, app lock | M6 |
| 12 | **QUALITY** | M6.6, M6.7, M6.10 | lint strict, coverage sweep, accessibility audit | M6 |
| — | lead inline | M0.R2 fixes, M6.8 docs, M6.9 acceptance, every milestone MCP, small fixes | — | all |

**UX-SHELL tasks [§2p]:**

### [ ] M2.U1 Adaptive nav shell · deps M2.B0d
- Mini-tasks: [ ] 4 user-chosen primary destinations + More sheet · [ ] long-press drag reorder; Settings → Navigation · [ ] WindowSizeClass: rail (medium), permanent drawer + list–detail (expanded) · [ ] launcher app shortcuts (New chat, Voice, Capture, Today) · [ ] tablet keyboard shortcuts (Ctrl+K, Ctrl+N)
- Tests: `NavConfigTest` (pure: exactly 4 primaries, More holds the rest, invalid config → defaults, migration from the 7-tab state), `AdaptiveLayoutTest` (size class → layout)

### [ ] M2.U2 Favourites + recents (core + store) · deps M0.A4
- Files: `core/.../domain/Favorite.kt` + scheme + Workspace registration (v2 additive, no migration break), `core/.../domain/Recents.kt` (bounded per kind)
- Tests: `FavoriteCodecTest`, `FavoriteOrderTest` (reorder stable, dedupe by kind+target), `RecentsTest` (cap 10 per kind, most recent first)

### [ ] M2.U3 Command palette / universal search · deps M2.U2
- Files: `ui/palette/*`, `core/.../search/PaletteIndex.kt` (pure ranking over destinations, settings, actions, records, favourites, recents)
- Tests: `PaletteRankingTest` (prefix > fuzzy, favourites boosted, recents boosted, Turkish/Polish folding)

### [ ] M2.U4 State restoration + drafts · deps M2.B0d
- Files: `runtime/UiStateStore.kt` (encrypted, vault-backed), screen hooks
- Tests: `UiStateStoreTest` (round-trip, size cap, corrupt → defaults); emulator: kill process (`adb shell am kill`) → reopen → same tab, scroll and draft

### [ ] M2.U5 List and state kit · deps M2.B0c
- Files: `ui/components/{SwipeRow,ContextMenu,MultiSelect,StickyHeader,FastScroller,ErrorState,OfflineBanner}.kt`; migrate Work/Studio lists; "Jump back in" row on Live
- Tests: `MultiSelectStateTest`, `SwipeThresholdTest` (pure)

**Data, automations and analytics packages [§2q]:**

| Wave | WP | Tasks bundled | Area | Milestone |
|---|---|---|---|---|
| 5 | **DATA-CORE** | M3.10.A1, M3.10.A2, M3.10.A3 | `core/data` (datasets, analytics, goals/experiments), `platform/android/data` store | M3 |
| 7 | **AUTOMATIONS** | M3.10.B1, M3.10.B2, M3.10.B3 | `core/automation/Automation`, triggers, session launcher, tracker manager | M4 |
| 8 | **INSIGHTS** | M3.10.C1, M3.10.C2, M3.10.C3 | insight generator, improvement plans, dashboards, review report | M4 |
| 10 | **SOCIAL** | M3.10.D1, M3.10.D2 | export importers, recipe gallery + seed extraction schemas | M5 |

### [ ] M3.10.A1 Datasets (core + store) · deps M0.A4, M4.E0 (schema refs; until then CustomSchema-lite)
- Files: `core/.../data/{Dataset,ObservationRow,Retention,DatasetCodec}.kt`, `app/.../platform/android/data/DatasetStore.kt` (SQLite, key from Keystore-wrapped secret, per-dataset tables), CSV/JSON/XLSX export + CSV/XLSX import mapper
- Tests: `DatasetCodecTest`, `AppendOnlyCorrectionTest` (correction = new row, history kept), `RetentionPurgeTest`, `SchemaEvolutionReadTest` (old rows readable after semver bump), `CsvImportMappingTest`, `XlsxExportStructureTest`
### [ ] M3.10.A2 Analytics engine (pure) · deps M3.10.A1
- Files: `core/.../data/analytics/{Metrics,Rolling,Trend,Seasonality,Anomaly,Compare,Attribution}.kt`
- Tests: golden fixtures per function: engagement rate, 7/28 d rolling, Theil–Sen vs known slope, day×hour heatmap, MAD anomaly on injected spike, before/after window, empty/sparse series behaviour (no NaN, honest "not enough data")
### [ ] M3.10.A3 Goals + experiments (pure) · deps M3.10.A2
- Tests: `GoalForecastTest` (on/off track), `ExperimentStopRuleTest` (min sample, duration, verdict: better/worse/inconclusive)

### [ ] M3.10.B1 Automation model + triggers · deps M3.5.B2, M3.7.A1
- Files: `core/.../automation/{Automation,Trigger,TriggerEval}.kt`, `app/.../platform/android/automation/{TriggerScheduler,NotificationTrigger(NotificationListenerService, opt-in allow-list),AppOpenedTrigger,PlaceTrigger}.kt`
- Tests: `TriggerEvalTest` (cron next fire, jitter bounds, quiet hours, after-event offsets, charging+idle), `AutomationCodecTest`
### [ ] M3.10.B2 Session launcher · deps M4.A1, M4.D1, M4.L2, M2.U2
- Mini-tasks: one tap from Favourites/palette/voice → web profile or app session with goal, timer, live view, capture tray → dataset rows
- Tests: `SessionGoalTimerTest`; emulator: launch fixture session, capture element → row in dataset
### [ ] M3.10.B3 Tracker manager + safety caps · deps M3.10.B1, M4.A2
- Files: `ui/trackers/*`, `core/.../automation/SitePolicy.kt` (per-site caps, read-only default, back-off on challenge, consent record)
- Tests: `SitePolicyTest` (LinkedIn caps enforced, write action refused without gate, challenge → back-off + takeover, consent required)

### [ ] M3.10.C1 Insight generator · deps M3.10.A2, M3.A4, M3.9.B2 (fabrication guard)
- Tests: `InsightGroundingTest` (every number in the text exists in the analytics result; unsupported claim rejected), council-review hook test
### [ ] M3.10.C2 Improvement plans + review report · deps M3.10.A3, M3.10.C1, M3.5.B2
- Tests: `ImprovementPlanTest` (hypothesis → experiment → scheduled tasks/workflows pass `validateSpec`), report PDF page structure test
### [ ] M3.10.C3 Dashboards UI · deps M3.10.A2, M2.B0c, M2.U4
- Files: `ui/insights/*` (KPI, line, bar, heatmap, table, funnel cards; tap → rows + evidence)
- Tests: `ChartScaleTest`, `HeatmapBinningTest` (pure); emulator screenshots

### [ ] M3.10.D1 Official export importers · deps M3.10.A1
- Files: `app/.../data/import/{LinkedInExport,LinkedInPostAnalyticsXlsx,XAnalyticsCsv,YouTubeStudioCsv,InstagramDyi}.kt` + fixtures (synthetic, structure from the platforms' documented formats; verify current column names at implementation time and log them)
- Tests: one fixture test per importer, unknown/renamed columns → mapping wizard, never silent drop
### [ ] M3.10.D2 Recipe gallery + seed schemas · deps M3.10.B1, M4.E2, M3.10.C3
- Files: `core/src/main/resources/recipes/*.json` (LinkedIn activity, X/IG/YouTube/TikTok/Threads post tracking, social browse digest, peer watch, job watch, notification digest, weekly review) with seed ExtractionSchemas (TRIAL trust, self-healing)
- Tests: every recipe loads, validates, has a seed dashboard and a site policy; device check: LinkedIn activity tracker against the user's own account (watched live, §2o)

**Total:** 39 package agents, plus one review agent per milestone (7), instead of ~160 task agents.

**Merge order to `main`:**
- M0 now
- M1 after waves 1–2 (UI-KIT, LLM, HUB, CHAT)
- M2 after VOICE, VOICE-SURFACES, SPEED
- M3, M4, M5, M6 as their packages land

# PART 1 — M0 Foundation (`milestone/M0`)

### [x] M0.0 Commit the plan
- Files: `docs/BUILD_PLAN.md`, `AGENTS.md`, `docs/ENGINEERING_LOG.md` (D-016)
- Checks: the plan is on `main` (PR #3).

### [x] M0.A1 Async external steps [§4]
- Files: `core/.../engine/WorkflowEngine.kt`, `core/.../domain/Workflow.kt`
- Mini-tasks:
  - [x] `Attempt.Pending(ticket, pollAfterMs)`; a blank or oversized ticket becomes Failed
  - [x] `StepState.externalTicket` JSON round-trip, with legacy-safe defaults
  - [x] Pending keeps the step RUNNING; `advance` stops and never re-invokes the executor
  - [x] `deliverExternal`, sharing `settleAttempt` with synchronous results
  - [x] `resumeInterrupted` rules for ticketed steps; `cancel`/`resolveReview` clear tickets
- Tests (`WorkflowEngineAsyncTest`):
  - pendingThenDeliveredCompletionFinishesRun
  - wrongTicketAndNonFinalResultsAreRejected
  - deliveredFailureFollowsRetryPolicy
  - deliveredFailureWithoutRetryFailsRun
  - advanceWhilePendingDoesNotReinvokeExecutor
  - pendingSideEffectGoesToNeedsReviewOnResume
  - pendingPureStepReRunsOnResume
  - sideEffectWithoutGateIsRefusedEvenIfExecutorWouldReturnPending
  - externalTicketSurvivesJsonRoundTrip
  - cancelClearsPendingTicket
  - blankTicketIsTreatedAsFailure
  - deliveredMeasurementPersistsMetricSample
  - deliveredSideEffectRecordsOutboundEventOnce
- Checks: shared settlement path; gate re-checked on delivery.

### [x] M0.A2 Audit wiring (folded into M0.A1)
- Checks: `outbound` is recorded exactly once per completed side effect (existing gateFlow/persistedRun tests plus deliveredSideEffectRecordsOutboundEventOnce).

### [x] M0.A3 Domain v2 entities
- Files: `core/.../domain/{Knowledge,Library}.kt`, `NaturalKeys.kt`, `Schemes.kt`, `Limits.kt`
- Mini-tasks: [x] Project, Note · [x] WorkflowTemplate, PromptTemplate, ArchitecturalPrinciple, ContentPreference, ChannelAccount, ProviderAdapter · [x] 8 natural keys · [x] 8 schemes · [x] rework: `useCount` removed
- Tests (`DomainV2Test`): round-trip, isInvalid and garbage → null for each entity; key normalisation and blank → ""; Registry Created → Reused → Bumped for Project and WorkflowTemplate; workflowTemplateIdenticalUpsertIsReusedAndKeepsVersionOne
- Checks: a version bumps only on real content change.

### [x] M0.A4 Semver + workspace v2 + first migration
- Files: `core/.../registry/{SemVer,Registry,Workspace}.kt`, `codec/SynCodec.kt`, `Limits.kt`, `Version.kt`
- Mini-tasks: [x] SemVer · [x] schemaMajor/schemaMinor, semVerOf, schemaVersion · [x] 16 registries · [x] `schemas` map · [x] migration 1→2 · [x] lead fix: DSL schema version in compiled specs
- Tests:
  - RegistryTest: semVerFormats…, semVerParsesStrict…, semVerRejectsInvalidText, semVerNegativePartsAreRefused, semVerCompareOrders…, contentChangeBumpsPatchByOne, identicalContentReusesSameSemVer, schemaVersionReportsMajorDotMinor, schemeOverridesDriveSemVer…, workspaceHasSixteenRegistries…
  - MigrationV1toV2Test: v1FixtureParsesAsMigratedFromVersionOne, allOldRowsAreIntact…, newV2RegistriesAreEmpty…, reserializing…WritesV2…, migratedWorkspaceRoundTrips…, v2RowsInNewRegistriesRoundTrip, envelopeNewerThanV2IsRejected
  - SynCodecTest updated

### [x] M0.A5 Policy module
- Files: `core/.../policy/{ActionPolicy,ChannelSwitches}.kt`
- Tests: ActionPolicyTest (~150 table rows across en/tr/de/es/fr/pl, false-positive guards, precedence, context raising, folding); ChannelSwitchesTest (matrix, kill switch, JSON)
- Checks: İ/ı, ł, ß folding; fail-closed bare "code"/"pin".

### [x] M0.A6 Router
- Files: `core/.../route/{ModelRouter,RateLimiter,ProviderHealth,RoutingPreferences}.kt`
- Tests: ModelRouterTest (every mode, pins, API opt-in, kill switch, ties, rationale, JSON); RateLimiterTest (Wait, CapReached, reset, JSON); ProviderHealthTest (breaker opens, half-open, re-trip, score, JSON)

### [x] M0.A7 Platform interfaces
- Files: `core/.../platform/{Llm,Speech,Vault,Channels,Device,Scheduling}.kt`
- Tests: PlatformContractsTest (17: validation plus in-memory fakes for every interface)
- Checks: no android/androidx/coroutines imports in core (grep).

### [x] M0.A8 Hidden core test failures
- Mini-tasks: [x] `:core:test` in CI · [x] parser `attemptsMax` · [x] HardFail → branch rescue · [x] provider id case · [x] PromptContractTest assertion
- Checks: the FAILED branch keeps the failure message; unwatched failures still fail fast.

### [x] M0.B1 Native + dependency infrastructure
- Files: `app/build.gradle.kts`, `app/src/main/cpp/{CMakeLists.txt,memo_llama.cpp}`, `LlamaNative.kt`, manifest, `.gitignore`, CI
- Checks: tarball and AAR hashes pinned; AAR fails closed; CI lists `libmemo_llama.so` for both ABIs; lead fix: `-DCMAKE_BUILD_TYPE=Release` (APK 75 MB, lib 5.2 MB).

### [x] M0.C1 Keystore vault
- Files: `app/.../platform/android/vault/{VaultFormat,KeystoreVault}.kt`
- Tests (`VaultFormatTest`, 15): round-trip, framing, unique IV, tamper, wrong name (AAD), wrong key, bad magic, bad ivLen, truncation ×3, backupNames, selectReadable ×4
- Checks: never a new key over existing data; atomic write; tmp cleanup.

### [x] M0.C2 Runtime onto the vault
- Files: `app/.../runtime/{MemoRuntime,Persistence,PacketBuilder}.kt`
- Tests (`PersistenceTest`): migratesV1PlaintextIntoVault, migrationRunsOnlyOnce, corruptRunsKeepsPlaintextAndReportsProblem, failedVaultWriteKeepsPlaintext, saveAndLoadRoundTripForAllThree, emptyVaultLoadsEmptyWorkspace
- Checks: parse → write → read back and verify → wipe and delete → marker last; read-only on failure.

### [x] M0.R1 Independent milestone review (11 findings, recorded in PROGRESS)

### [x] M0.F1 Engine safety fixes
- Tests (`WorkflowEngineSafetyTest`, 12): the G1/A/Br/G2/C plan is rejected; a skipped nearest gate refuses the side effect at runtime; deliverExternal uses the nearest gate; checkpoint is called once before a side effect with an inflight ticket; a checkpoint exception means the executor is not called; recoverAfterRestart → NEEDS_REVIEW with no executor call; a pure step does not checkpoint

### [x] M0.F3 Core review fixes
- Tests: QuarantinePreservationTest (10); ModelRouterTest (frontier switch ×4, pins vs mode ×3); ActionPolicyTest (~85 review misses, stems, guards, NFKC/Cf, classifyControl)

### [x] M0.F4 publish-preview hardening (lead)

### [x] M0.F2 App review fixes
- Files: `MemoRuntime.kt`, `Persistence.kt`, `KeystoreVault.kt`, tests
- Mini-tasks:
  - [ ] checkpoint → synchronous runs save; read-only makes it throw; runs saved before the audit
  - [ ] recoverAfterRestart at bootstrap
  - [ ] `.tmp` chosen only when the primary is missing or invalid and the `.tmp` parses
  - [ ] plaintext removal retried when the marker exists
  - [ ] read-only blocks approvals that lead to outbound actions
  - [ ] lastRecovery surfaced, read-only until confirmed; rotation throttled to 10 min
  - [ ] unparseable runs/audit rows → load problem, never re-save a reduced list
- Tests (PersistenceTest additions): newer bad tmp + good primary → primary imported; missing primary + good tmp → tmp imported; marker present still removes plaintext; corrupt run row → problem and save refused; checkpoint throws when read-only

### [ ] M0.R2 Second independent review, then fixes if needed

### [ ] M0 close (MCP)
- Device checklist (user):
  - the app opens
  - data from the previous build is still there
  - Settings shows the audit chain verified
  - kill switch works


---

# PART 2 — Design system (M2.B0, starts in parallel with M0) and M1 Local AI + Chat

### [x] M2.B0a Tokens + typography + theme [§2i]
- Files: `app/.../ui/theme/{MemoTokens,MemoTypography,MemoTheme}.kt`; delete `ui/Theme.kt`; migrate `MemoRoot.kt`, `ModelsScreen.kt` and `MainActivity.kt`
- Mini-tasks:
  - [ ] `MemoColorScheme` (canvas, surface, raised, sunken, outline, ink, inkMuted, inkFaint, accent, onAccent, accentSoft, attention, danger, success, scrim, focusRing), light and dark
  - [ ] CompositionLocal + Material3 mapping
  - [ ] spacing (4 dp grid), radii, elevation
  - [ ] type scale (Display…Mono) in sp, mapped to Material3
  - [ ] `MemoTheme(themeMode)`, edge-to-edge bar icons, no dynamic colour
- Tests: `TokensContrastTest`: ink/canvas, ink/surface, inkMuted/surface ≥ 4.5; onAccent/accent and attention/surface ≥ 3 (light and dark)
- Checks: `grep -rn "Color(0x" app/src/main/kotlin` matches only inside `ui/theme`.

### [ ] M2.B0b Motion tokens + haptics · deps M2.B0a
- Files: `app/.../ui/motion/{MemoMotion,Haptics,ReduceMotion}.kt`
- Mini-tasks:
  - [ ] springs: snappy (0.85/700), smooth (0.9/380), gentle (1.0/200), bouncy (0.6/500)
  - [ ] durations 90/180/260/360 ms; stagger 28 ms (max 9); orb 1650 ms; shimmer 1150 ms; emphasized easings
  - [ ] haptics: tick, confirm, reject, longPress, attention, all gated by a setting
  - [ ] reduce motion: animator scale 0 or the user setting → static specs
- Tests: `ReduceMotionTest` (pure: specs collapse to snap or none when reduced; stagger capped)
- Checks: no raw `tween(`/`spring(` literals outside `ui/motion` (grep).

### [ ] M2.B0c Component kit · deps M2.B0b
- Files: `app/.../ui/components/*`
- Mini-tasks:
  - [ ] MemoButton (press scale 0.965 + haptic)
  - [ ] MemoCard, Pill/Chip, SectionHeader, EmptyState
  - [ ] StatTile + DigitRoll; Skeleton/Shimmer; MemoSheet (spring + scrim); UndoSnackbar
  - [ ] ProgressRing (takes real progress only); Sparkline (draw-in)
  - [ ] StepRail (path-trim line, pulse running, check morph, failure shake, waiting halo)
  - [ ] PresenceOrb (RuntimeShader API 33+, Canvas fallback; states idle/listening/thinking/speaking/working/waiting; level input)
- Tests: `StepRailStateTest` (pure state → visual-state mapping); `SparklineMathTest` (normalisation, empty and flat series)
- Checks: every component reads tokens only; animations go through MemoMotion; content descriptions are set.

### [ ] M2.B0d Nav shell + shared transitions + predictive back · deps M2.B0c
- Files: `app/.../ui/nav/*`, `MainActivity.kt`, existing screens migrated
- Mini-tasks:
  - [ ] SharedTransitionLayout + AnimatedContent shell; tabs Live · Talk · Work · Studio · Browser · Models · Settings
  - [ ] shared-element keys for record, run, model and message→artifact
  - [ ] PredictiveBackHandler on every screen and sheet
  - [ ] back on the root tab goes to Live, then exits (fixes today's exit-on-back)
- Tests: `NavGraphTest` (pure: every route has a back target; root behaviour)
- Build: emulator smoke test (B-STD step 3); screenshots of every tab sent to the user.

### [ ] M1.A1 LiteRT-LM runtime [§4] · deps M0
- Files: `app/.../platform/android/llm/litert/LiteRtLmRuntime.kt`
- Mini-tasks:
  - [ ] dedicated thread + executor; cacheDir created
  - [ ] GPU init, CPU fallback (never `close()` a failed init); retry the first generation on CPU
  - [ ] catch Throwable/OOM → `LlmError`
  - [ ] `sendMessageAsync` → ChatSink tokens; cap → `cancelProcess()`
  - [ ] thinking on/off and `<think>` split into `onThinking`
  - [ ] RAM preflight (`ActivityManager.MemoryInfo`)
  - [ ] `info()`: backend, model, context
- Tests: `ThinkSplitterTest` (streaming `<think>` split across chunk boundaries); `LiteRtErrorMappingTest` (exception → LlmError)
- Checks: no generation on the main thread; cancel path; one-at-a-time enforced.
- Build: device check generates with Gemma 3 1B or Qwen3 0.6B.

### [ ] M1.A2 llama.cpp JNI runtime · deps M0.B1
- Files: `app/src/main/cpp/{memo_llama.cpp,CMakeLists.txt}`, `LlamaNative.kt`, `LlamaCppRuntime.kt`
- Mini-tasks:
  - [ ] verify every API name against `include/llama.h` at b11460
  - [ ] load the model (mmap); context (n_ctx, n_threads = big cores)
  - [ ] chat template from GGUF metadata; tokenise
  - [ ] decode loop + sampler chain (temp, top-k, top-p, min-p, repeat penalty)
  - [ ] per-token JNI callback; atomic cancel; free everything on unload
  - [ ] native errors → Java exceptions
  - [ ] ARM CPU variants (`GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, or dotprod flags)
- Tests: `LlamaParamsMappingTest` (GenParams → native params); `ChatTemplateFallbackTest` (no template → ChatML fallback)
- Checks: no leaks on the unload path (code read); JNI exceptions are not swallowed.
- Build: CI lists both ABIs; device check records tokens/s for Llama 3.2 1B and 3B Q4_K_M.

### [ ] M1.A3 Inference manager + service · deps M1.A1, M1.A2
- Files: `app/.../runtime/InferenceManager.kt`, `app/.../platform/android/llm/InferenceService.kt`, manifest
- Mini-tasks: [ ] runtime selected by format · [ ] single resident model, unload on switch · [ ] request queue (one at a time), cancel · [ ] specialUse FGS (subtype text) when generation lasts > 10 s
- Tests: `InferenceManagerTest` with a fake LlmRuntime: switching unloads; queue order; cancel
- Checks: the FGS starts only from the foreground.

### [ ] M1.B1 Model catalog (pure) · deps M0.A4
- Files: `core/.../catalog/{ModelCatalog,HfParsers}.kt`, fixtures `core/src/test/resources/hf/*.json`
- Mini-tasks:
  - [ ] ModelFormat, ModelEntry fields
  - [ ] parsers: list, model (sha, gated), tree (size, lfs.oid)
  - [ ] GGUF quant ranking
  - [ ] curated seed list (litert-community chat models + GGUF picks), pinned from the live API by the agent and re-checked by the lead
- Tests: `HfParsersTest` (fixtures: list, model, tree, gated); `QuantRankingTest`; `SeedCatalogTest` (every entry: 64-hex sha, size > 0, revision, https URL with `resolve/<rev>/`)
- Checks: the lead re-verifies 3 random entries against the live HF API.

### [ ] M1.B2 Hub downloads · deps M1.B1, M0.C1
- Files: `app/.../hub/{ModelHub,DownloadWorker,HfClient}.kt`
- Mini-tasks:
  - [ ] HfClient (HTTPS-only redirects, token header from the vault)
  - [ ] DownloadWorker (foreground dataSync, notification progress, Range resume, free-space check)
  - [ ] streaming SHA-256 + size; `.part` → atomic install; `.previous` rollback; pause/cancel
  - [ ] live discovery; device scan; SAF import with hash match; migrate ModelStore installs
- Tests: `RangeResumeLogicTest` (pure offsets, 200-vs-206 handling); `InstallSwapTest` (rollback on failure, temp dir); `HfClientRedirectPolicyTest`
- Checks: verify happens before install; a token never appears in logs.

### [ ] M1.B3 Models UI · deps M1.B2, M2.B0d
- Files: `app/.../ui/models/*`
- Mini-tasks: [ ] Installed / Recommended / Browse (search, filters) / Voice / Import sections · [ ] cards with fit badge, licence, gated lock, one-tap progress ring · [ ] per-model settings sheet
- Tests: `FitBadgeTest` (RAM/size → badge)
- Checks: tokens only; real progress only.
- Build: emulator screenshots.

### [ ] M1.C1 Conversation store · deps M0.C1
- Files: `core/.../chat/Conversation.kt`, `app/.../runtime/ChatRepository.kt`
- Tests: `ConversationCodecTest`, `ConversationOrderingTest`, `ChatSearchTest`

### [ ] M1.C2 Chat UI · deps M1.C1, M1.A3, M2.B0d
- Files: `app/.../ui/chat/*`
- Mini-tasks:
  - [ ] conversation list + thread
  - [ ] streaming markdown renderer (bold/italic/code + copy/lists/links), measured incrementally
  - [ ] composer (auto-grow, send/stop, attach, mic hold/tap)
  - [ ] model picker (local by format + frontier); message actions
  - [ ] empty state; scroll-to-bottom
- Tests: `MarkdownParserTest` (incremental chunks never break earlier spans); `ComposerStateTest`
- Checks: no fake streaming; stop cancels the runtime.
- Build: emulator demo with the smallest model.

### [ ] M1 close (MCP)
- Device checklist:
  - download Gemma 4 E2B (LiteRT) and Llama 3.2 3B Q4_K_M (GGUF)
  - chat with each
  - stop mid-answer
  - switch models
  - pause and resume a download
  - import a file


---

# PART 3 — M2 Voice (`milestone/M2`) and M2.5 Speed

### [x] M2.A1 Audio I/O [§2f] · deps M0.B1
- Files: `app/.../platform/android/voice/{AudioCapture,AudioPlayback,AudioLevel,MicPermission}.kt`
- Mini-tasks:
  - [x] `AudioRecord` 16 kHz mono PCM16, source VOICE_COMMUNICATION; AEC/NS/AGC attached when `isAvailable()`
  - [x] capture on a dedicated thread with a ring buffer; frames of 20 ms; a callback API that never blocks the reader
  - [x] RMS/peak level metering → 0..1 (`AudioLevel`, pure)
  - [x] `AudioTrack` streaming playback (float or PCM16), audio focus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, ducking, stop on focus loss
  - [x] mic permission flow with rationale; refuse to capture without permission (no silent start)
  - [x] release everything on stop; no leaked AudioRecord
- Tests: `AudioLevelTest` (silence → 0, full scale → 1, clipping, empty frame); `RingBufferTest` (wrap, overrun drops oldest, reader sees order)
- Checks: no capture starts outside an explicit user action or an armed wake service; no main-thread reads.

### [ ] M2.A2 Speech engine (sherpa-onnx) · deps M2.A1, M1.B2
- Files: `app/.../platform/android/voice/{SherpaAsr,SherpaOfflineAsr,SherpaTts,SherpaVad,SherpaKws,VoiceModels}.kt`, `core/.../voice/SentenceChunker.kt`
- Mini-tasks:
  - [x] **first:** list the AAR classes (`unzip -l app/libs/sherpa-onnx-*.aar` + `javap` on `classes.jar`) and save the API list in ENGINEERING_LOG; every call uses those names
  - [x] streaming Zipformer ASR with partials and endpoints
  - [x] offline ASR (Whisper base / SenseVoice) for voice messages
  - [ ] Kokoro TTS (speaker id, speed), Piper fallback; sentence-by-sentence streaming into AudioPlayback
  - [x] Silero VAD; keyword spotter with "hey memo" keywords file
  - [x] VoiceModels entries pinned (URL, sha256, size) from the sherpa-onnx releases page; downloaded via the Hub; archives unpacked with path-traversal guard
- Tests: `SentenceChunkerTest` (abbreviations, decimals, emoji, pl/tr punctuation, flush on end); `VoiceModelsPinTest` (64-hex sha, https, size > 0); `ArchiveGuardTest` (`../` entries rejected)
- Checks: lead diffs every sherpa call against the saved class list; models load only from the app's private dir.
- Build: emulator: synthesize a sentence to a WAV in app files, pull it with adb, listen.

### [ ] M2.A3 Conversation loop · deps M2.A2, M1.A3
- Files: `core/.../voice/TurnTaking.kt`, `app/.../runtime/VoiceLoop.kt`
- Mini-tasks:
  - [x] pure state machine: IDLE → LISTENING → THINKING → SPEAKING → LISTENING; ENDED
  - [ ] barge-in: VAD speech while SPEAKING → stop TTS, cancel queued sentences, LISTENING
  - [ ] the LLM stream → SentenceChunker → TTS queue (first audio < 1.2 s after first sentence)
  - [ ] silence timeout; "stop"/"cancel"/"stop it" in en/pl/tr ends or cancels
  - [ ] echo guard: ignore VAD while our own TTS is the only signal (AEC + energy threshold)
- Tests: `TurnTakingTest` (happy loop, barge-in, timeout, cancel words in 3 languages, error from LLM → LISTENING with message, double end is idempotent)
- Checks: cancel reaches the runtime (`InferenceManager.cancel`); no state is updated off the loop thread.

### [ ] M2.B1 Voice messages + live conversation UI · deps M2.A3, M1.C2, M2.B0c
- Files: `app/.../ui/voice/{VoiceRecorderBar,Waveform,LiveConversationScreen,ReadAloud}.kt`, `ui/chat/Composer.kt` hooks
- Mini-tasks:
  - [ ] hold to record with a live waveform; slide to cancel; release sends (audio + transcript)
  - [ ] voice message bubble with playback and transcript
  - [ ] live mode full screen: PresenceOrb driven by level, partial transcript, speaking captions, tap to interrupt, end
  - [ ] read-aloud action on any message
- Tests: `WaveformBucketsTest` (downsampling, empty); `RecorderGestureStateTest` (hold/slide/cancel/release)
- Checks: `grep -rn RecognizerIntent app/src` is empty; `grep -rn "TextToSpeech" app/src` is empty (no system robot voice).
- Build: emulator: record with the host mic (`-allow-host-audio`), screenshots.

### [ ] M2.C1 Assistant role + lock-screen session · deps M2.A3
- Files: `app/.../platform/android/assist/{MemoVoiceInteractionService,MemoSessionService,MemoSession,AssistActivity}.kt`, `res/xml/interaction_service.xml`, manifest
- Mini-tasks: [ ] RoleManager ROLE_ASSISTANT request from Settings · [ ] session over the lock screen (`showWhenLocked`, `turnScreenOn`) · [ ] locked: pure steps only; side effects queue with "Unlock to approve" (`requestDismissKeyguard`) · [ ] session summary
- Tests: `LockedExecutionPolicyTest` (core: locked → side-effect steps deferred, pure allowed)
- Checks: manifest exports only what the role needs; no data shown on the lock screen beyond the summary.

### [ ] M2.C2 "Hey Memo" wake word, screen off [§2f] · deps M2.A2
- Files: `app/.../platform/android/assist/{WakeWordService,WakeArming,BatteryGuard}.kt`, manifest, settings hook
- Mini-tasks: [ ] FGS type microphone, started only from the foreground; persistent notification with Pause · [ ] KWS on a low-power loop; wake → AssistActivity over the lock screen · [ ] battery/thermal guard · [ ] after reboot: notification "tap to re-arm" (no FGS from boot)
- Tests: `WakeArmingTest` (pure: start allowed only when foreground; battery thresholds; reboot → needs re-arm)
- Checks: lead reviews FGS start paths against Android 14 rules.
- Build: device check with the screen off.

### [ ] M2.C3 Tile, bubble, ongoing notification · deps M2.C1
- Files: `app/.../platform/android/surface/{MemoTileService,BubbleService,LiveNotification}.kt`
- Mini-tasks: [ ] tile starts a voice session · [ ] bubble = collapsed PresenceOrb overlay (drag, snap to edge, dismiss target) · [ ] ongoing notification with running/pending counts and approve (unlock required for side effects)
- Tests: `BubbleSnapTest` (pure edge snapping); `NotificationContentTest` (counts, no secrets in text)

### [ ] M2 close (MCP)
- Device checklist: install Kokoro + Zipformer · send a voice message · live conversation with an interruption · assist gesture from the lock screen · "Hey Memo" with the screen off · tile and bubble.

---

### [ ] M2.5.A1 Release variant + persistent signing · deps M0.B1
- Files: `app/build.gradle.kts`, `app/proguard-rules.pro`, `.github/workflows/build-memo-apk.yml`, `docs/BUILD.md`
- Mini-tasks: [ ] R8 full mode + resource shrinking; keep rules for LiteRT-LM, sherpa-onnx, JNI (`LlamaNative`), ML Kit · [ ] signing from env · [ ] `create-release-key` dispatch job · [ ] lead stores secrets with `gh secret set` (needs the user's OK) · [ ] `assembleRelease` + `apksigner verify` + upload `memo-release-apk`
- Checks: release APK launches on the emulator and runs a chat (R8 did not strip JNI).

### [ ] M2.5.A2 Baseline profiles + macrobenchmarks · deps M2.5.A1, M4.0, M2.B0d
- Files: `:baselineprofile` module, `app/build.gradle.kts`, CI
- Tests: `StartupBenchmark` (cold start), `TabSwitchBenchmark`, `ChatScrollBenchmark` (frame timing)
- Checks: profile is inside the release APK (`assets/dexopt/baseline.prof`).

### [ ] M2.5.A3 Performance pass · deps M2.5.A2
- Mini-tasks: [ ] Compose compiler reports in CI · [ ] fix unstable params · [ ] StrictMode in debug, fix violations · [ ] meet §2i targets (cold start < 1 s on CI emulator profile; jank < 1%)
- Checks: numbers recorded in PROGRESS with the run link.

---

# PART 4 — M3 Orchestrator + Live, M3.5 Full control, M3.6 Languages, M3.7 Everyday, M3.8 Compiler, M3.9 Self Model

## M3 — Orchestrator + Live environment (`milestone/M3`)

### [ ] M3.A1 Orchestrator planner · deps M1.A3, M0.A4
- Files: `core/.../plan/{Orchestrator,TemplateLibrary}.kt`, `app/.../runtime/PlannerService.kt`
- Mini-tasks: [ ] intent: chat / plan / command (grammar fast-path first) · [ ] plan prompt = templates (few-shot) + ContextPacket · [ ] parse + `WorkflowPolicy.validateSpec`, ≤2 repairs citing violations · [ ] deterministic fallback, labelled · [ ] reuse-first lookup of template/project
- Tests: `OrchestratorTest` (fake LlmRuntime: good plan, repaired plan, 2 failed repairs → fallback, reuse hit, command fast-path skips the model)
- Checks: model output is never executed without validation; fallback is labelled in the UI.

### [ ] M3.A4 Local agent engine (tool-using) [§2g] · deps M3.A1, M1.A3
- Files: `core/.../agent/{AgentEngine,Tools,ToolCallParser}.kt`, `app/.../runtime/AgentTools.kt`
- Mini-tasks: [ ] strict tool schemas + arg validation · [ ] loop with step/token/time budgets · [ ] invalid call → repair prompt · [ ] no-progress stop · [ ] side-effecting tools become gated workflow steps · [ ] quality check → escalate to frontier when routing allows
- Tests: `AgentEngineTest` (valid call, invalid → repair → success, budget exhausted, no-progress, gated tool queued not run, escalation), `ToolCallParserTest` (malformed JSON, extra fields, wrong types)

### [ ] M3.A2 Context retrieval · deps M3.A1
- Files: `core/.../prompts/PacketBuilder.kt`
- Tests: `PacketBuilderTest` (budget cap, redaction applied, mention > natural key > keyword ordering, recent chat included)

### [ ] M3.A3 Chat ↔ workflow bridge · deps M3.A1, M1.C2
- Files: `app/.../runtime/ChatOrchestration.kt`, `app/.../ui/chat/PlanCard.kt`
- Mini-tasks: [ ] inline plan card with cascade-in steps, Run / Edit / Discard · [ ] live StepRail in the thread · [ ] gates inline with artifact renderer · [ ] web/app steps show the live preview tile (§2o, wired in M4.L4)
- Tests: `PlanCardStateTest` (pure mapping run → card state)

### [ ] M3.B1 Artifact renderers + approval UX · deps M2.B0c
- Files: `app/.../ui/artifact/*`
- Mini-tasks: [ ] renderers for every `ARTIFACT_KINDS` entry + JSON fallback · [ ] edit-before-approve rebuilds artifact + hash; gate re-opens · [ ] approve/veto with haptics
- Tests: `RendererCoverageTest` (every ARTIFACT_KINDS has a renderer); `EditRehashTest` (edited artifact → new hash, old approval invalid)

### [ ] M3.B2 Live tab · deps M3.B1
- Files: `app/.../ui/live/*`
- Mini-tasks: running workflows (animated StepRail), approvals carousel, recent outputs, metric sparklines, "since you were away", kill switch
- Tests: `SinceAwayDigestTest` (pure grouping)

### [ ] M3.B3 Glance widget · deps M3.B2
- Files: `app/.../surface/ApprovalsWidget.kt`, xml · Checks: no secrets rendered on the widget.

### [ ] M3.C1 Profile + preferences · deps M0.C2 (superseded in depth by M3.9; keep content preferences + voice prefs here)
- Tests: `ProfileCodecTest`

### [ ] M3.C2 UC-2 CRM enrichment · deps M3.A1 (M4.A2 for research)
- Files: `core/.../plan/templates/CrmCapture.kt`, `app/.../runtime/CrmFlows.kt`
- Tests: `CrmCaptureE2ETest` (fake executors: parse "I met Ada at Web Summit from Acme", reuse existing Acme, tasks generated, research pending marked, gate on full record, approve → saved once)

### [ ] M3 close (MCP) — device: "I met Ada at Web Summit, add to CRM" → plan card → filled record → approve → in Work.

## M3.5 — Full control

### [ ] M3.5.A1 Standing-rule approvals · deps M0.A1, M0.A5
- Files: `core/.../policy/AutonomyPolicy.kt`, `WorkflowEngine.kt`
- Tests: `AutonomyPolicyTest` (payments/passwords/OTP never auto-approvable, rule scope match, quiet hours, approver recorded in audit as `STANDING_RULE:<id>`)

### [ ] M3.5.A2 Humanizer [§2d] · deps M4.A0
- Files: `core/.../automation/Humanizer.kt`
- Tests: `HumanizerTest` (seeded RNG determinism, delays within bounds and never constant, typing cadence with bursts/pauses, curved gesture paths stay on target, per-site daily caps)

### [ ] M3.5.B1 Editors for every record type · deps M3.B1
- Files: `app/.../ui/edit/*`, `app/.../runtime/EditRepository.kt`
- Tests: `DescriptorCoverageTest` (every `Workspace.all()` kind has a descriptor), `EditRepositoryTest` (create/edit bumps version only on change, archive, undo, purge)

### [ ] M3.5.B2 Workflow editor · deps M3.A1, M3.5.B1
- Files: `app/.../ui/workflow/*`, `app/.../runtime/WorkflowLibrary.kt`
- Tests: `EditorSpecValidityTest` (editor ops → spec passes `validateSpec`; reorder that breaks gate binding is rejected with a message)

### [ ] M3.5.C1 Settings suite + autonomy UI · deps M3.5.A1
- Files: `app/.../ui/settings/*`, `app/.../runtime/SettingsStore.kt`
- Tests: `SettingsStoreTest` (encrypted round-trip, defaults, migration)
- Checks: every settings key has a reader (grep script in PROGRESS); search finds every setting.

## M3.6 — Languages

### [ ] M3.6.A1 Multilingual command grammar · deps M0.A8
- Tests: `GrammarEnTest`, `GrammarPlTest`, `GrammarTrTest` (≥15 each, time phrases, Turkish İ/ı casing)
### [ ] M3.6.B1 App localisation + picker · deps M2.B0
- Tests: `StringParityTest` (same keys and placeholders in en/pl/tr)
- Checks: grep no `Text("` literals outside previews; lead spot-checks 20 strings per language; native-speaker review requested from the user.
### [ ] M3.6.C1 Voice in pl/tr · deps M2.A2 — Build: device Polish and Turkish live conversations.

## M3.7 — Everyday product

### [ ] M3.7.A1 Reminders/timers/alarms · deps M0.C2, M0.A7
- Tests: `RecurrenceTest` (RRULE subset, DST forward/back, zone change, end conditions), `TimerModelTest` (concurrent, pause/resume)
- Build: device: reminder screen off, snooze, 1-minute timer on the lock screen, survives reboot.
### [ ] M3.7.A2 Voice/agent time hooks · deps M3.7.A1, M3.6.A1 — Tests: time intents in en/pl/tr.
### [ ] M3.7.B1 Capture from anywhere · deps M3.B1 — Tests: `CardTextParserTest` (en/pl/tr business cards).
### [ ] M3.7.B2 Sources + search + embeddings · deps M1.A1, M0.C2 — Tests: `ChunkerTest`, `DedupeTest`, `RankingTest`.
### [ ] M3.7.B3 Memory graph · deps M3.7.B2, M2.B0c — Tests: `ForceLayoutTest` (pure: converges, bounded iterations).
### [ ] M3.7.C1 Today, briefings, driving, quiet hours · deps M3.B2, M3.7.A1, M2.A3 — Tests: `AttentionPolicyTest`.
### [ ] M3.7.C2 Onboarding + help + templates gallery · deps M2.B0d, M1.B3, M2.A2 — Build: fresh-install walk on the emulator.
### [ ] M3.7.C3 In-app updates · deps M2.5.A1 — Tests: `VersionCompareTest`, `UpdateShaCheckTest`; CI release dry-run.
### [ ] M3.7.C4 Contacts import + place tag · deps M3.5.B1 — Tests: `ContactMergeTest` (reuse-first).

## M3.8 — Capability Compiler

### [ ] M3.8.A1 Library schemas + v2→v3 migration — Tests: round-trip, keys, trust transitions, `MigrationV2toV3Test`.
### [ ] M3.8.A2 TaskSpec/graph/assembler/readiness — Tests: covered, conflict, missing tool gap, untrusted restriction, expansion bound.
### [ ] M3.8.A3 ExecutionPackage + renderer + compile — Tests: golden prompts per size class; compiled specs validate.
### [ ] M3.8.A4 Evaluate/diagnose/repair/learn — Tests: repair success, budget, no endless loop, promotion only on evidence.
### [ ] M3.8.A5 Architect/critic loop — Tests: disagreement resolution, round limit (shares seat/role types with the Council, §2n).
### [ ] M3.8.B1 Seed library — Tests: every seed loads, validates, has an evaluator.
### [ ] M3.8.C1 Compiler UI — Build: emulator screenshots.

## M3.9 — Self Model + Artifact Studio

### [ ] M3.9.A1 Self Model schema — Tests: diff, conflict surfaced, merge-after-approval.
### [ ] M3.9.A2 Ingestion — Tests: fixtures for website HTML, LinkedIn export CSVs, CV text; every ingestion → gated SelfDiff.
### [ ] M3.9.B1 CV generator + exporters — Tests: DOCX valid OOXML structure, PDF page fitting, JSON Resume schema. Build: device opens the exports.
### [ ] M3.9.B2 Tailoring + fabrication guard — Tests: guard rejects invented employer/metric/skill; ATS coverage; fake job → tailored CV E2E.
### [ ] M3.9.B3 Other artifacts — Tests: guard per artifact type; templates in en/pl/tr.
### [ ] M3.9.C1 Outbound profile sync — Tests: envelope building. Build: device test edit on the user's own site/profile (watched live, §2o).

---

# PART 5 — M4 Channels, Council, Live view, Teach & Select (`milestone/M4`)

Every task in M4.A1–M4.L4 also passes the **automation gate**: its §2a emulator tests are green, every action goes through `Humanizer` (non-constant timing asserted), every action is narrated and visible (§2o), and the lead reads the diff line by line against §2a/§2b/§2c/§2o.

### [ ] M4.0 Emulator job in CI · deps M0.B1
- Files: CI `instrumented` job, androidTest deps (versions verified ≥2 weeks old), `SmokeTest.kt`
- Mini-tasks: [ ] KVM enable · [ ] `reactivecircus/android-emulator-runner@v2.38.0` API 34 x86_64 google_apis · [ ] `connectedDebugAndroidTest` · [ ] upload reports
- Tests: `SmokeTest` (launch, WebView loads an asset page)
- Build: local equivalent: `.\gradlew.bat connectedDebugAndroidTest` with `Memo_API34` running.

### [ ] M4.A0 Automation contracts (pure) · deps M0.A1, M0.A5
- Files: `core/.../automation/{ActionVocabulary,ActionEnvelope,Locator,Observation,AgentLoopPolicy,ActionJson}.kt`
- Tests: one test per §2a rule, including every refusal path (unknown action, out-of-envelope target, commit without approval, password field, bounds, no-progress); fixtures for DOM and a11y JSON
- Checks: lead ticks each §2a rule against a test name in PROGRESS.

### [ ] M4.A0b Fixtures · deps M4.0 — fixture HTML pages (form, list+pagination, shadow DOM, contenteditable/ProseMirror-like, fake "AI chat" page with streaming answer, login wall, DOM v1/v2) + `TestTargetActivity`.

### [ ] M4.A1 Headless web runtime [§2b] · deps M0.A1, M0.A7, M4.0
- Mini-tasks: FGS + notification · invisible host window + fixed viewport · per-service profiles · re-parent without restart · ≤2 WebViews + queue · stall watchdog → takeover · hardened settings · kill/battery/thermal pause · Pending + deliverExternal
- Tests (emulator): `BackgroundFillExtractTest` (home pressed, still completes), `ReparentKeepsStateTest`, `HardenedSettingsTest` (file access off, mixed content blocked, no JS interface)

### [ ] M4.L1 ActionNarrator + session state (pure) [§2o] · deps M4.A0
- Tests: `ActionNarratorTest` (every vocabulary action × en/pl/tr; masked values never in text), `SessionStateTest`, `ActionTimelineTest` (retention, purge)

### [ ] M4.A2 Provider adapters + FrontierExecutor · deps M4.A1, M0.A6
- Tests: `ScriptsEscapingTest`, `AdapterPackTest` (selector tables, versions), emulator `FakeAiConsultTest` (fixture AI page: inject into contenteditable, send verified, completion by stability, extraction with evidence quote)

### [ ] M4.L2 Web live view [§2o] · deps M4.A1, M4.L1, M2.B0c
- Mini-tasks: auto-open on user-started runs · attach/detach real WebView · `PreviewCapturer` (≤4 fps, 0 when hidden, off main thread) · `TargetRects` + ghost cursor + highlight ring · visible typing · narration strip · step timeline with masked snapshots · pause/step/take over/stop · touch auto-pause · inline approvals
- Tests: `PreviewThrottleTest` (pure scheduler); emulator `LiveHighlightTest` (ring at target rect ±4 dp), `LiveAttachNoReloadTest`, `TouchPausesAgentTest`, `MaskedSnapshotTest` (password field pixels masked)

### [ ] M4.A3 Browser tab [§2p] · deps M4.A1, M2.U2
- Mini-tasks:
  - [ ] tabs per profile: tab strip + overview grid with live thumbnails, drag to reorder
  - [ ] saved tab groups (save/rename/reopen, attach as workflow context, auto-restore after restart)
  - [ ] recently closed + undo; searchable history per profile, clear; ephemeral profile
  - [ ] bookmarks with folders/tags → Favorites; start page with favourites
  - [ ] HTTPS-only address bar; capture page as Source; discuss in chat; takeover banner; sign-in status
  - [ ] agent sessions shown as tabs with a "Memo is working" badge → live view (§2o)
- Tests: `AddressBarPolicyTest`, `TabGroupCodecTest`, `TabRestoreTest` (pure: restore order, dedupe, ephemeral never persisted), `HistorySearchTest`

### [ ] M4.B1 Accessibility service · deps M0.A5, M0.A1
- Tests: `A11yGuardsTest` (pure guard logic: lock pause, wrong app, policy classify, password field, retry bound ≤18 @450 ms); emulator `TestTargetActionsTest` (tap/type/scroll/wait-text on TestTargetActivity)

### [ ] M4.B2 Screenshot + OCR · deps M4.B1 — Tests: `OcrTapPolicyTest` (OCR tap only when no node matches and policy allows; never with password present).
### [ ] M4.B3 AI-app adapters · deps M4.B1, M4.C1 — Tests: `AppAdapterPackTest`.
### [ ] M4.B4 App capability map · deps M4.B1, M0.A3 — Tests: codec, reuse-first.
### [ ] M4.C1 Device-wide AI discovery + favourite apps dock [§2p] · deps M0.A7, M2.U2 — discovery feeds the favourite-apps dock (Live, Overlay, workflow editor) with capability badge, last used, sign-in state, long-press Act on it / Open in session / Capability map / Unpin. Tests: `SignatureRegistryTest`, `AppDockModelTest`; lead spot-checks 5 package ids on Play.
### [ ] M4.C2 Channels settings + router wiring · deps M4.C1, M0.A6 — Test: `RouteRationaleTest` (shown text matches decision).

### [ ] M4.D1 Native-app sessions [§2c] · deps M4.B1, M2.C3 — Tests: emulator `ScreenSessionTest` (launch target, console visible, refuses when console hidden, returns to Memo).
### [ ] M4.L3 App live HUD [§2o] · deps M4.D1, M4.L1 — Tests: emulator `NodeHighlightTest`, `AppTouchPausesAgentTest`.
### [ ] M4.D2 Memo Overlay console [§2b] · deps M2.C3, M3.A3, M3.B1, M4.A0, M4.B1 — Tests: emulator `ActOnThisScreenTest` (safe fill with approval), `OverlayHiddenOnSecureWindowTest`, `NoSelfTapTest`.

### [ ] M4.A4a Council core (pure) [§2n] · deps M3.A4, M0.A6, M3.8.A2
- Files: `core/.../council/{CouncilRole,Seat,SeatPlanner,Council,ClaimsLedger,ClaimParser,CouncilOutcome,CouncilSession,CouncilTemplate}.kt` + schemes + migration
- Mini-tasks: [ ] role library seeds (moderator, proposer, domain expert, skeptic, devil's advocate, fact-checker, user advocate, synthesizer/judge) · [ ] SeatPlanner (topic → TaskSpec → roles → target by router affinity, health, rate limits, privacy class) · [ ] 6 formats as one state machine · [ ] visibility filter per seat · [ ] claims ledger (ids, refs, kinds, marks) · [ ] stopping rules incl. no-progress · [ ] outcome with minority report and next steps
- Tests: `CouncilFormatsTest` (turn order for compare, critique_refine, converge, debate, round_table, red_team), `BlindOpeningTest`, `PrivacyClassTest` (Self Model/CRM rows never in a non-local seat's prompt), `ClaimRefsTest` (rebuttal must ref an existing claim), `NoProgressStopTest`, `AbsentSeatTest`, `JudgeDiffersTest`, `MinorityReportTest`, `CouncilCodecTest`
### [ ] M4.A4d Evidence-backed extraction · deps M4.A4a — Tests: `EntityExtractionTest` (unsupported claim rejected, verbatim quote required).
### [ ] M4.A4b Council runtime · deps M4.A4a, M4.A2, M1.A3, M4.D1
- Mini-tasks: dispatch seats to InferenceManager / FrontierExecutor / app session · parallel independent turns (local ∥ web) · swap-minimising order · per-run consent · audit every outbound turn · takeover → seat absent
- Tests: `CouncilServiceTest` (fake channels: 3-seat debate local + web + app, one seat fails → absent, outcome produced); emulator `CouncilWebSeatTest` (fixture AI page as a web seat)
### [ ] M4.A4c Council UI · deps M4.A4b, M2.B0c, M1.C2, M4.L2
- Mini-tasks: group thread with avatars, model badges, role chips · seat plan sheet (drag roles onto models) · round indicator + agenda · claim chips + rebuttal links · ledger panel · interject / ask seat / steelman / pause / end · live tile per frontier seat (§2o) · outcome card (Run as workflow / Save to Project) · voice mode with a Kokoro voice per seat
- Tests: `SeatAccentTest` (distinct token accents), `ClaimLinkLayoutTest` (pure)
- Build: emulator screenshots of a debate with a fixture web seat.

### [ ] M4.A5 Optional API channel · deps M0.C1, M0.A6 — Tests: request building, redaction, HTTPS-only.

### [ ] M4.E0 Schemas · deps M4.A0, M0.A4 — Tests: round-trip, validation, stability scoring, transforms, migration generation.
### [ ] M4.E1 Web element picker · deps M4.A1, M4.E0 — emulator: unique locator, list generalises, shadow DOM, password never captured, live count.
### [ ] M4.E2 Schema builder UI · deps M4.E1 — emulator: JobPosting schema extracts all fixture items.
### [ ] M4.E3 Web recorder · deps M4.E1, M3.A4 — emulator: record form fill → parameterised recipe, commit detected, replay passes.
### [ ] M4.E4 App picker + recorder · deps M4.B1, M4.D1, M4.E0 — emulator against TestTargetActivity.
### [ ] M4.E5 Self-healing · deps M4.E0, M3.A4, M3.8.A4 — emulator: DOM v2 heals to a TRIAL patch; commit step needs approval.
### [ ] M4.E6 Workflow editor integration · deps M4.E2–E4, M3.5.B2 — device: build a workflow by picking on a real page.

### [ ] M4.L4 Previews everywhere [§2o] · deps M4.L2, M4.D2, M3.B2, M4.A4c — device: watch a background run from the bubble, expand to full screen.

### [ ] M4 close (MCP)
- Device checklist: sign in to ChatGPT in the Browser tab · say "go to the web and find X" → the live view opens and you watch every action with narration · send Memo to the background and watch the bubble preview · take over and hand back · run a council (local model + ChatGPT web + one AI app) on a topic, check roles, rebuttals, minority report · accessibility "open Calculator and type 2+2" with the HUD highlight · discovery lists installed AI apps.

---

# PART 6 — M5 Use cases, M6 Hardening + release, cross-cutting checks

## M5 — Use cases end to end (`milestone/M5`)

### [ ] M5.A1 UC-1 brainstorm · deps M3, M4
- Mini-tasks: parse project/idea/constraints · create or reuse Project + principles · **council consult** (§2n: default `converge` with proposer + skeptic + domain expert, local moderator) · local synthesis with evidence-checked quotes · gate · Note + next-step Tasks · works from the lock-screen session (side effects deferred)
- Tests: `BrainstormE2ETest` (fake channels; reuse hit; deferred side effects when locked)
### [ ] M5.B1 UC-3 jobs · deps M3, M4, M3.9.B2, M3.8.A4
- Tests: `JobsE2ETest` (capture → postings evidence-checked → approve → tailored CV + letter → gate shows exact fields → apply; never submits without the gate)
### [ ] M5.C1 UC-4 content → publish → measure · deps M3, M4
- Tests: `ContentE2ETest`, `MetricScheduleTest` (+1h/+24h/+72h/+7d, manual fallback)
### [ ] M5.C1a Local image generation · deps M1.B2 — API names from the AAR; Build: device 512×512 image.
### [ ] M5.C1b Media assets · deps M5.C1, M5.C1a, M3.A4, M4.A2, M2.A2 — Tests: spec → slides/storyboard; Build: carousel PDF + MP4 on device.
### [ ] M5.C2 UC-5 arbitrary plans · deps M5.C1, M4.B2 — Tests: `GenericLoopBoundsTest` (iterations, wall time, stop always wins, ≤4 app actions per step).
### [ ] M5 close — device: UC-1, UC-3, UC-4, UC-5 on a real phone, each watched in the live view (§2o).

## M6 — Hardening + release (`milestone/M6`)

### [ ] M6.1 Offline queue + boot recovery — Tests: `ResumePolicyTest`; Build: kill the app mid-run on the emulator, reboot, run recovers to NEEDS_REVIEW.
### [ ] M6.2 Diagnostics — Tests: `CrashFileRedactionTest`; outbound log viewer filters.
### [ ] M6.3 Encrypted backup/export/import — Tests: `BackupCryptoTest` (PBKDF2 600k, passphrase ≥12, wrong passphrase, tamper, round-trip across versions).
### [ ] M6.4 App lock — Tests: `RelockTimerTest`; device: biometric, FLAG_SECURE toggle.
### [ ] M6.5 Permission centre — Test: every manifest permission has a rationale entry (`PermissionCoverageTest`).
### [ ] M6.6 Lint strict — `abortOnError = true`; zero errors.
### [ ] M6.7 Test sweep — core coverage report (Kover) in CI; close gaps in engine, policy, codec, council, automation to ≥85% lines.
### [ ] M6.8 Docs — README, BUILD.md, CHANGELOG (reference app → extracted → where), STATUS.md.
### [ ] M6.9 Final acceptance — UC-1…UC-5 against the code with evidence links; every FEATURES item ticked with its proof.
### [ ] M6.10 Accessibility audit — TalkBack pass, 48 dp targets, 200% font, contrast; empty/loading/error/offline states on every screen.
### [ ] v1.0.0 — tag `v1.0.0` → `release.yml` builds the signed APK, GitHub release with SHA-256 (public: user approval required).

## Cross-cutting checks (run at every milestone close)
| Check | Command / method |
|---|---|
| No inline colours | `grep -rn "Color(0x" app/src/main/kotlin` only under `ui/theme` |
| No raw animation specs | `grep -rnE "tween\(|spring\(" app/src/main/kotlin` only under `ui/motion` |
| No system speech | `grep -rnE "RecognizerIntent|android.speech.tts" app/src` empty |
| No plaintext persistence | `grep -rnE "writeText|FileOutputStream" app/src/main` only in vault/hub/media writers that are reviewed |
| No cleartext | `usesCleartextTraffic=false`; no `http://` literals outside tests |
| No JS bridge | `grep -rn addJavascriptInterface app/src` empty |
| Core stays pure | `grep -rnE "import (android|androidx|kotlinx.coroutines)" core/src/main` empty |
| No secrets in logs | `grep -rnE "Log\.(d|i|v).*(token|key|password)" app/src` empty |
| Every action visible | each executor action emits a narration + timeline entry (test per executor) |
| Every outbound audited | audit chain verifies in Settings after a milestone's device run |
| Strings parity | `StringParityTest` green |
| Crash-free smoke | emulator install + launch + `adb logcat -b crash -d` empty |

---

