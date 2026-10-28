# Memo — Lead's running notes

Chronological notes kept by the lead while building. They cover what the user asked, what was decided, what each agent delivered, how it was verified, and what is still open. They complement `BUILD_PLAN.md` (what to build), `FEATURES.md` (the checklist) and `ENGINEERING_LOG.md` (formal decisions D-###). Newest entries are at the bottom.

## 2026-10-26

### Session start
- User: the app didn't compile and only two screens showed; finish the product from `reference/`; pull GitHub first; produce a clean APK.
- Found: the live repo is `ysfclc/memo-2` and the local clone was behind. Pulled to b8c6ebd.
- Built the app layer (runtime, planner, model store, 6 screens), merged as PR #2. CI green, APK `memo-apk`.
- User: local AI download should be one click, with discovery. Done in `ModelStore` (pinned Qwen3 × 3, SHA-verified, resume, import, scan).

### Full-product plan
- User: build everything, use subagents, plan properly.
  - Then: the reference apps are poor implementations; architect from first principles.
  - Haiku implementers, small steps, the lead verifies everything.
- Plan committed as `docs/BUILD_PLAN.md` (PR #3). Requirements added since, in the order the user asked:
  - §2a automation spec: in-app only, agent loop, action envelope, emulator tests (PR #4)
  - §2b headless in-Memo web runtime (the Fieldwork pattern) plus the floating console (PRs #5, #6)
  - §2c native device apps (the SynthOS Runtime pattern, in `reference/app-release.zip`)
  - §2d human-like actions (anti-ban)
  - §2e full CRUD everywhere, workflow editor, autonomy levels, complete settings
  - §2f "Hey Memo" wake with the screen off
  - §2g tool-using local agent + media engine (local image gen via MediaPipe; carousel; video)
  - §2h English / Polish / Turkish everywhere
  - §2i motion language + performance (release build, baseline profiles)
  - §2j reminders/timers/alarms + notifications, capture/share, sources/search/memory, Today, council modes, API opt-in, onboarding, in-app updates
  - §2k Capability Compiler (the user's "stitch the competence on demand" idea)
  - §2l Self Model from website/LinkedIn/CVs, plus Artifact Studio (tailored CVs, fabrication guard)
  - §2m Teach & Select: web/app element pickers, schema builder, Show Memo recorder, self-healing versioned schemas
- User asked for a sanity check and a separate checklist. Created `docs/FEATURES.md` with a reference cross-check table (§R).

### M0 — Foundation (branch `milestone/M0`)
| Task | Result | Lead verification |
|---|---|---|
| M0.A1 async engine (`Attempt.Pending`, `deliverExternal`) + audit wiring | merged | Read the full engine diff: shared settlement path, gate re-check on delivery, pending side effect → NEEDS_REVIEW after a crash |
| M0.A3 domain v2 entities | merged after rework | Sent back once: `useCount` bumped template versions on reuse. Removed from the entity |
| M0.B1 llama.cpp b11460 + sherpa-onnx 1.13.8 + deps | merged | Hashes pinned and the AAR fails closed. **Lead fix:** native code forced to Release (debug llama.cpp would be unusably slow; APK 209 MB → 75 MB) |
| M0.A8 repair of 7 hidden core test failures | merged | Discovered that CI never ran `:core:test`, and added it. Reviewed each fix: retry key missing from the parser, HardFail skipping branch rescue, provider id case, one wrong test assertion |
| M0.A5 ActionPolicy (en/pl/tr/de/es/fr) + ChannelSwitches | merged | Folding handles İ/ı, ł, ß. Polish/Turkish terms need a native-speaker review (open item) |
| M0.A7 core/platform interfaces | merged | No Android or coroutine imports in core |
| M0.C1 Keystore vault | merged | Fails closed on a missing key, atomic writes, rolling backups, recovery reported |
| M0.A4 semver + workspace v2 + first real migration | merged | **Lead fix:** compiled specs had started writing the workspace schema version instead of the DSL one |
| M0.A6 ModelRouter / RateLimiter / ProviderHealth | merged | SYNTHESIZE kept LOCAL_FIRST per §2g |
| M0.C2 runtime onto vault + plaintext migration | merged | Read the migration: parse all, write, read back and verify, then wipe and delete plaintext, marker last; read-only mode on any failure (never overwrites data) |

- milestone/M0 CI was green (run 40374975745) before A4/A6/C1 merged. It will be re-run before the PR to `main`.

### Lessons (process)
- Never `git stash -u` in the main checkout: it moves `.claude/worktrees` (live agent worktrees). Restored immediately; `.claude/` is now in `.gitignore`.
- Branch CI does not prove core tests unless `:core:test` is in the command (fixed).
- Agents branch from `origin/milestone/M0`. Engineering-log merge conflicts are always additive, so keep both sides.

### Local environment
- User wants to run Memo locally and get a phone link.
- Installing a JDK 17 + Android SDK + emulator into `%USERPROFILE%\Android` (no admin rights needed).
- The network is slow (~0.3 MB/s), so the installer is reordered: emulator and system image first, build toolchain after.
- The phone link will be a GitHub pre-release with the APK, once the artifact download completes.

### Open items
- Native-speaker review of the pl/tr policy terms.
- Device checks: every milestone checklist needs the user's phone.
- M0 close: the PR to `main`, then the device checklist for the user.

### Later the same day
- The user wanted a phone link. A public GitHub pre-release was blocked by the permission system: it would publish the APK publicly, so the user must decide. The direct file send failed (197 MB > 30 MB limit). The `publish-preview.yml` workflow was added to the milestone so a pre-release can be made server-side once the user approves.
- Local SDK: the old `sdkmanager` now forwards to the new Android CLI (`android sdk install`), which uses **slash** package names (`build-tools/35.0.0`). The first installer "succeeded" without installing anything because its output was hidden. Rewritten with the new CLI and visible output. The network sped up: the system image downloads at ~12 MB/s.
- The user proposed the **Capability Compiler** (assemble the competence per task) and the **Self Model** (website/LinkedIn/CVs → tailored CVs). Designed as §2k and §2l. Teach & Select (§2m) was added for DOM/app element selection and regenerable schemas.
- M0 all tasks merged; final milestone CI dispatched (run 40378577135).

### M0 independent review (before merging to main)
- Ran a review subagent (Sonnet) over the whole milestone diff. CI was green, yet it found 11 real defects. **Lesson: per-task verification plus CI is not enough; the milestone review stays mandatory.**
- Confirmed by the lead:
  - HIGH: a branch jump or failure rescue skipping a gate let a later side effect run under an older approval.
  - HIGH: quarantined rows were destroyed on the next save.
  - HIGH: no durable "in flight" marker before a side effect, so a crash could duplicate it.
  - HIGH: a stale `.tmp` could block the migration forever.
  - MEDIUM: plaintext was left behind if removal failed.
  - MEDIUM: read-only mode still ran side effects without a durable audit.
  - MEDIUM: vault recovery was silent and backups rotated too fast.
  - MEDIUM: the router ignored the "frontier" switch.
  - MEDIUM: a pin overrode LOCAL_ONLY.
  - MEDIUM: policy misses (check out, PayPal, plurals, NFKC/zero-width obfuscation, unknown submit buttons).
  - MEDIUM: injection and provenance gaps in `publish-preview.yml`.
- Fix pass:
  - M0.F1 (engine: nearest-gate binding, branch-over-gate rejection, checkpoint before side effects, `recoverAfterRestart`)
  - M0.F3 (quarantine preserved, router switches/pins, policy hardening)
  - M0.F2 (app persistence and runtime) after F1
  - `publish-preview.yml` fixed by the lead
- Local run: Memo installed and launched on the emulator (`Memo_API34`, WHPX acceleration) with no crash. Settings shows "0 outbound entries · chain verified", so the vault load works on a real Android 14 system. Launcher: `Desktop/gpt/run-memo-emulator.cmd`.

### 2026-10-26 — Runbook + two new requirements
- `docs/TASKS.md` written: the full runbook from zero to end (Part 0 environment/CI/B-STD/LVG/MCP; Parts 1–6 every task M0→M6 with mini-tasks, named tests, checks, build steps; cross-cutting grep checks).
- User: "local AI chats are … between them and frontiers through web or app automation, and they all get relevant roles in the discussion, proper debating." Designed as **§2n Council** (mixed seats, role library, relevance seat plan, 6 formats incl. debate/red team, claims ledger, anti-sycophancy, privacy class) → tasks M4.A4a–d, FEATURES section S.
- User: "I want to see the real state on the screen in those sessions… it shows me real time what AI does." Designed as **§2o Live view** (real WebView attached in foreground for user-started runs, ≤4 fps live previews of background runs, ghost cursor + highlight at the real target, deterministic narration en/pl/tr, step timeline, take over / touch auto-pause) → tasks M4.L1–L4, FEATURES section T. Background mode is now opt-in and never hides state.
- M0.F2 and M2.B0a agents hit the Haiku session limit (resets 21:50 Warsaw); work is saved in their worktrees, to be resumed with SendMessage.
- Wave 1 verified and merged into milestone/M1: UI-KIT (run 40403569428), LLM (run 40404098087; three native libraries present in the APK), HUB (run 40404219197; lead re-checked Qwen3-4B GGUF, Gemma 4 E2B LiteRT and Mistral 7B GGUF against the live HF API: size and sha256 match). Engineering-log decision numbers were renumbered on merge (LLM D-028..031, HUB D-032..034). Integration CI green (run 40405377602).
- User: "professional UI/UX… saved tabs, favorite apps and so on" → §2p, FEATURES U, package UX-SHELL (M2.U1–U5). Browser saved tabs go into M4.A3; the favourite-apps dock into M4.C1.
- User: "data… create automations… track LinkedIn activity, browse social media, start a session with X app or web… analytics… improvement paths" → §2q, FEATURES V, packages DATA-CORE, AUTOMATIONS, INSIGHTS, SOCIAL (M3.10.*). Official exports are preferred, trackers are read-only by default with strict caps, and the user records consent per tracker. Total is now 39 packages.
- M0.F2 verified by the lead (diff read, run 40382967279 green on bf6c1e3) and merged. Lead fix inline: `storageError` was never shown and `confirmRecovery()` had no caller (a recovered vault would stay read-only forever). Added a Settings "Storage" card with a "resume saving" button (`Snapshot.recoveryPending`).
- M2.B0a verified (run 40382823007 green on fca665e; contrast test pairs checked; no `Color(0x` outside `ui/theme`) and merged into milestone/M0.
- User: bundle tasks by area and priority; don't run 160+ agents; do small things inline. → 34 work packages in TASKS.md §0.9.
- Wave 1 launched on `milestone/M1` (branched from milestone/M0 be83c7e): WP UI-KIT, WP LLM, WP HUB. M0.R2 second review running in parallel.
- M0.R2 second review (Sonnet): 10 of the first 11 issues are fully or mostly fixed; #7 is partly fixed. New findings:
  - HIGH: a vetoed capture still saves (DATA_PERSIST is not skipped on veto). Confirmed by the lead in WorkflowEngine veto branch.
  - MEDIUM:
    - no run driver (RetryLater and recovered runs hang)
    - side-effect auto-retry uses a new idempotency key
    - JSON exception text echoes user data into logs/UI
    - no vault escape hatch
    - policy bypass via Hangul fillers / Cc / Cyrillic confusables
    - vocabulary gaps (donate, share, reply…)
  - LOW: ASCII-only slugs, unknown enums default silently, backup rotation copies a corrupt primary, preview tag can start with '-', libc++ pickFirst, 16 KB pages, no dotprod.
- Fix routing:
  - preview tag regex fixed inline by the lead
  - 16 KB / libc++ / dotprod sent to WP LLM
  - everything else in M0.F5 (one agent, groups A engine, B data hygiene, C policy, D app runtime)
- Deferred: ModelStore init I/O on the main thread → WP HUB (owns ModelStore). Unpinned action SHAs + build attestation → lead at M2.5.
- M0.F5 verified by the lead (run 40405257386 green on 9167f22; engine veto/persist diff read) and merged.
- Open decision "non-ASCII re-key", resolved by the lead: `Registry.load` derives every natural key from row content (`scheme.id(decoded)`); keys are not stored. Rows re-key on load and no migration is needed. Rows that previously had a blank key were quarantined, and can now be released with `releaseQuarantined`.
- UI follow-ups from F5 (reset confirmation, review/resume buttons, displayed-hash `decide`) are lead inline on milestone/M1 after M0 merges in.

---
TIMESTAMP: 2026-10-27T16:05:32+01:00
CHAIN: Claude (plan) -> Codex from the GPT app (cloud-sandboxed execution) -> Continuity resumption in the wp/voice worktree
RESUMED FROM: wp/voice worktree `agent-af96ee867896a60ae` with uncommitted M2.A2 groundwork (installer, policy, guard, extractor, core chunker, commons-compress dep) left uncommitted and unverified by the prior agent
STEP: 1 — repair the broken M2.A2 groundwork (missing VoiceModel/VoicePackaging definitions)
PLAN REF: TASKS.md M2.A2 (owned files include `app/.../platform/android/voice/VoiceModels.kt`)
STATUS: done
FILES CHANGED: app/src/main/kotlin/app/memo/platform/android/voice/VoiceModels.kt (NEW — enum `VoicePackaging`, data class `VoiceModel` with exactly the members the call-sites reference)
VERIFICATION: before the fix `:app:compileDebugKotlin` FAILED (VoiceModelInstaller.kt ~40 unresolved refs: VoiceModel, VoicePackaging, id, sha256, sizeBytes, url, archiveRoot, requiredFiles); after the fix `.\gradlew.bat --no-daemon :app:compileDebugKotlin` -> BUILD SUCCESSFUL in 52s. milestone/M1 baseline (main checkout) is green: `:core:test testDebugUnitTest` -> BUILD SUCCESSFUL in 1m 22s.
NEXT STEP: add the named M2.A2 tests (ArchiveGuardTest done next; VoiceModelsPinTest after pins land), then commit the M2.A2 groundwork with ENGINEERING_LOG D-038 and push wp/voice for CI.
NOTES: The broken state was Codex's half-applied patch — it wrote call-sites whose types were never created. Repair restored its intent with zero redesign. The prior agent's local Gradle 'wrapper lock / loopback' failures do NOT reproduce in an unsandboxed shell (they were its cloud-sandbox constraints; JDK 17 + wrapper 8.14.6 verified healthy here).
---

---
TIMESTAMP: 2026-10-27T16:08:20+01:00
STEP: 2 — ArchiveGuardTest (named M2.A2 test: `../` entries rejected)
PLAN REF: TASKS.md M2.A2 Tests: `ArchiveGuardTest`
STATUS: done
FILES CHANGED: app/src/test/kotlin/app/memo/platform/android/voice/ArchiveGuardTest.kt (NEW)
VERIFICATION: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests app.memo.platform.android.voice.ArchiveGuardTest` -> BUILD SUCCESSFUL in 43s (filter matches, failures would fail the build)
NEXT STEP: voice-model pins: 5 seeded assets (streaming zipformer en 20M mobile, whisper base, kokoro en v0_19, silero_vad_v5.onnx, kws gigaspeech 3.3M mobile) are downloading to %TEMP%\opencode\sherpa-pins for SHA-256 + size verification against the release API; then extend VoiceModels.kt with the pinned `VoiceModels` catalog and add `VoiceModelsPinTest`.
NOTES: Seed selection follows M2.A2/M2.A3 engine needs; pl/tr voices deliberately deferred to M3.6.C1. ENGINEERING_LOG D-038 (commons-compress) written; build.gradle comment re-pointed to D-038 (was a stale D-036 reference left by the prior agent).
---

---
TIMESTAMP: 2026-10-27T16:24:00+01:00
STEP: 3 — M2.A2 first mini-task: sherpa-onnx AAR API list saved in ENGINEERING_LOG
PLAN REF: TASKS.md M2.A2 mini-task "first: list the AAR classes ... and save the API list in ENGINEERING_LOG"
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md (new NOTES "M2 · WP VOICE" section), docs/TASKS.md (first mini-task ticked)
VERIFICATION: AAR expanded with Expand-Archive; `jar tf classes.jar` inventory (engine-relevant classes present: OnlineRecognizer/Config/Result, OnlineStream, OfflineRecognizer/Config/Result, OfflineStream, OfflineWhisperModelConfig, OfflineSenseVoiceModelConfig, OfflineTts/Config/ModelConfig/Kokoro/Vits, GeneratedAudio, Vad/Config/SileroVadModelConfig/SpeechSegment, KeywordSpotter/Config/Result, FeatureConfig, EndpointConfig/Rule); `javap -p` signatures captured for every engine-relevant config and entry point. Key gotchas recorded: no `OnlineRecognizerStream` type (`OnlineStream` only); `OnlineStream.acceptWaveform(FloatArray, sampleRate)` vs `Vad.acceptWaveform(FloatArray)`; `KeywordSpotterConfig` reuses `OnlineModelConfig`; TTS `generateWithCallback` is the per-chunk streaming path.
NEXT STEP: STEP 4 pins (5 assets downloading in the background, SHA-256 + size verify against the release API, `tar -tf` for archiveRoot/requiredFiles), then VoiceModels catalog + VoiceModelsPinTest; then full worktree baseline and the single M2.A2-groundwork commit.
NOTES: AssetManager-null file-mode construction is flagged UNVERIFIED in the log until the first engine compiles and runs. TASKS.md large mini-tasks 506-510 stay unticked until their engines/pins exist.
---

---
TIMESTAMP: 2026-10-27T16:58:00+01:00
STEP: 4+5 — voice-model pins verified from downloaded bytes; pinned VoiceModels catalog + VoiceModelsPinTest
PLAN REF: TASKS.md M2.A2 mini-task "VoiceModels entries pinned (URL, sha256, size)"; named test `VoiceModelsPinTest`
STATUS: done
FILES CHANGED: app/src/main/kotlin/app/memo/platform/android/voice/VoiceModels.kt (catalog appended), app/src/test/kotlin/app/memo/platform/android/voice/VoiceModelsPinTest.kt (NEW), docs/ENGINEERING_LOG.md (D-039), docs/TASKS.md (pins mini-task ticked)
VERIFICATION: 5 assets downloaded to the sanctioned temp dir; every byte size matched the release-API asset size exactly (zipformer 107,569,151 / whisper 207,557,382 / kokoro 319,625,534 / silero 2,313,101 / kws 15,667,804); SHA-256 computed per asset (certutil); archive listings via tar -tf gave archiveRoot + requiredFiles (int8 encoder/joiner + fp32 decoder layout for both transducer models; kokoro dataDir=espeak-ng-data confirmed in-archive). First `:core:test testDebugUnitTest` run FAILED on `idsAreUniqueAndFolderSafe` — the kokoro id carried an underscore; id renamed to `tts-kokoro-en-v0-19`; re-run: BUILD SUCCESSFUL in 40s, 193 tests green.
NEXT STEP: one commit (M2.A2 groundwork + pins + docs + D-038/D-039 + API list) with attribution trailers, push wp/voice, `gh workflow run build-memo-apk.yml -R ysfclc/memo-2 --ref wp/voice`, watch to green.
NOTES: The installer/Hub divergence (RangeResume/InstallSwap reuse instead of the hub DownloadWorker) is recorded in D-039 for the lead's LVG rather than silently accepted. WP VOICE branch still unpushed at this point; CI is the remaining gate for M2.A1+A2-groundwork status.
---

---
TIMESTAMP: 2026-10-27T17:22:00+01:00
STEP: 6 — CI green on wp/voice; M2.A1 ticked
PLAN REF: AGENTS.md rule 7 (green CI is the definition of done); TASKS.md M2.A1
STATUS: done
FILES CHANGED: docs/TASKS.md (M2.A1 header + mini-tasks ticked), docs/PROGRESS.md (this entry)
VERIFICATION: `gh run watch` on run 40532557529 (`wp/voice` @ aad007f): Build Android APK PASSED in 5m40s — core tests, app unit tests, lint, APK verify + artifact upload all green. Annotations are infra deprecation notices (actions Node 20→24, ubuntu-latest migration), not failures.
NEXT STEP: docs commit + CI re-dispatch to keep the branch tip green, then Path B: read-only trace of LiteRtLmRuntime cancellation → onDone/onError → ChatService.finish in the wp/chat worktree (inherited checkpoint from the prior agent; no source edits until the path is understood).
NOTES: M2.A1 ticked because its contract (named tests + code-level checks + CI) is met; runtime audio behavior on a device (AEC actually attached, no leaked AudioRecord in practice) stays UNVERIFIED until M2.B1's emulator run. M2.A2 header stays unticked: engine mini-tasks 506-509 and the emulator WAV build step remain.
---

---
TIMESTAMP: 2026-10-27T18:20:00+01:00
STEP: 7 — sherpa engine wrappers: SherpaAsr, SherpaOfflineAsr, SherpaTts, SherpaVad, SherpaKws
PLAN REF: TASKS.md M2.A2 mini-tasks (streaming/offline ASR, TTS, VAD+KWS)
STATUS: done (engines written; device Build step still open)
FILES CHANGED: app/src/main/kotlin/app/memo/platform/android/voice/{SherpaAsr,SherpaOfflineAsr,SherpaTts,SherpaVad,SherpaKws}.kt (all NEW), docs/ENGINEERING_LOG.md (construction note retired to bytecode-verified), docs/TASKS.md (506/507/509 ticked; 508 stays open on the AudioPlayback hookup), docs/PROGRESS.md (this entry)
VERIFICATION: each wrapper compiled immediately after writing (`:app:compileDebugKotlin` EXIT=0 per step); null-AssetManager file-mode dispatch bytecode-verified (javap -c, ifnull -> newFromFile) for Vad, OfflineRecognizer and OfflineTts; full worktree baseline `:core:test testDebugUnitTest` -> BUILD SUCCESSFUL in 47s, EXIT=0.
NEXT STEP: one commit + push + CI dispatch; then the M2.A2 emulator Build step (synthesize a sentence to a WAV) is the next plan item, needing the emulator session.
NOTES: Config discipline: paths + sample rate + thread counts only; everything else stays at AAR defaults until device tuning. SherpaTts's chunk callback (true = stop) is the M2.A3 barge-in hook. KWS uses the archive keywords until M2.C2 authors "hey memo".
---

---
TIMESTAMP: 2026-10-27T18:10:00+01:00
STEP: 8 — M2.A2 emulator Build step: Kokoro TTS verified on-device
PLAN REF: TASKS.md M2.A2 "Build: emulator: synthesize a sentence to a WAV in app files, pull it with adb, listen"; D-040
STATUS: done (test PASS; WAV pulled; human listen pending the lead's ears)
FILES CHANGED: app/build.gradle.kts (testInstrumentationRunner + androidx.test.ext:junit 1.3.0, androidx.test:runner 1.7.0), app/src/androidTest/java/app/memo/platform/android/voice/SherpaTtsDeviceTest.kt (NEW — repo's first androidTest), docs/ENGINEERING_LOG.md (D-040 vehicle, C-009 Windows ninja PATH, installer↔engine layout contract), docs/PROGRESS.md (this entry)
VERIFICATION: `connectedDebugAndroidTest` PASS 1/1 on Memo_API34_CHAT (API 34 x86_64), 14m43s, EXIT=0 — the full product path: VoiceModelInstaller downloaded the pinned 319,625,534 B kokoro archive through the app's HTTPS/Range-resume/pin-verify chain, unpacked behind the path guard, SherpaTts.kokoro loaded from filesDir and synthesized. Logcat evidence: wav seconds=3.9739583 sampleRate=24000 bytes=190794. First run FAILED with the layout lesson (test passed archiveRoot subpath; installer renames it away — see log). Manual `am instrument` rerun OK (1 test) 688.8s so the WAV survived for pulling; `run-as` pull byte-identical 190,790 B; header verified locally: RIFF/WAVE PCM mono 24000 Hz 16-bit. WAV kept at %TEMP%\opencode\kokoro-hello.wav for the lead to listen (quality judgment is human, pending).
NEXT STEP: commit + CI; then M2.A3 Conversation loop — pure TurnTaking state machine first (TASKS order).
NOTES: Only the kokoro pack is device-exercised; the other four pins stay engine-compile-verified with the same wrapper pattern (device probes come with their own milestones). The androidTest never runs on CI (no emulator) — CI green ≠ device-verified; this entry is the device record. 320 MB re-downloads on every clean install; the installer's resume+marker keeps reruns incremental within one install.
---

---
TIMESTAMP: 2026-10-28T09:30:00+01:00
STEP: 9 — M2.A3 mini-task 1: pure TurnTaking state machine
PLAN REF: TASKS.md M2.A3 "pure state machine: IDLE → LISTENING → THINKING → SPEAKING → LISTENING; ENDED"
STATUS: done (core tests green)
FILES CHANGED: core/src/main/kotlin/app/memo/core/voice/TurnTaking.kt (NEW), core/src/test/kotlin/app/memo/core/voice/TurnTakingTest.kt (NEW), docs/TASKS.md (mini-task ticked), docs/PROGRESS.md (this entry)
VERIFICATION: `:core:test` BUILD SUCCESSFUL in 41s, EXIT=0 — 10 tests pin the accepted transitions (happy path, second exchange, barge-in effects, barge-in ignored elsewhere, empty-answer path, EndRequest/Error from every active state, no-ops from IDLE, ENDED terminal, mistimed events).
NEXT STEP: M2.A3 mini-task 2+3 in the loop: VoiceLoop wires SherpaAsr partials/endpoints, the LLM stream → SentenceChunker → SherpaTts queue, and executes the InterruptSpeech/ReleaseAudio directives; then commit + CI.
NOTES: Barge-in's transition + directive live in the machine (SPEAKING + VadSpeechDetected → LISTENING, InterruptSpeech = stop TTS + cancel queued sentences, mic keeps running); the TTS/cancel execution itself is VoiceLoop work, so mini-task 2 stays open until the loop exists. SpeechFinished from THINKING models the empty-answer path (answer ended with no speech). Mistimed events are no-ops by design so a late engine callback can never crash the loop.
---

---
TIMESTAMP: 2026-10-28T11:05:00+01:00
STEP: 10 — M2.A3 mini-tasks 2+3 wiring: VoiceLoop
PLAN REF: TASKS.md M2.A3 "barge-in: VAD speech while SPEAKING → stop TTS, cancel queued sentences, LISTENING" and "the LLM stream → SentenceChunker → TTS queue (first audio < 1.2 s after first sentence)"
STATUS: wired + compile-verified; NOT device-verified — mini-tasks stay unticked (the <1.2 s first-audio gate is device-measured)
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/VoiceLoop.kt (NEW), core/src/main/kotlin/app/memo/core/voice/TurnTaking.kt (@Volatile state + doc), docs/ENGINEERING_LOG.md (thread model + API lessons), docs/PROGRESS.md (this entry)
VERIFICATION: `:core:test` + `:app:compileDebugKotlin` BUILD SUCCESSFUL, EXIT=0 — 10 machine tests still green after the @Volatile change. No unit test for the loop itself (impure: Context, engines, threads); the pure parts it composes carry the tests. Two compile errors found and fixed on the first pass: a stale `signalLoop` reference after the data-race restructure, and `OnlineStream.close()` (does not exist — recognizer owns streams).
NEXT STEP: device probe of the full loop on emulator-5554 (mic audio needs host routing — decide the probe method first), measure the <1.2 s first-audio gate, tick mini-tasks 2+3 on evidence; then M2.B1 voice UI per plan order.
NOTES: Thread model and barge-in path recorded in the engineering log. Remaining M2.A3 mini-tasks: silence timeout, cancel words in en/pl/tr (needs ASR decoding during SPEAKING — design at that step), echo guard (AEC from VOICE_COMMUNICATION source plus `AudioPlayback.level` exist; explicit guard not wired). VoiceLoop keeps its own conversation history; sharing it with the chat store is M2.B1 integration.
---

---
TIMESTAMP: 2026-10-28T12:10:00+01:00
STEP: 11 — M2.A3 loop testability: ports + JVM fakes; LLM-error discipline fixed to plan
PLAN REF: TASKS.md M2.A3 Tests line "error from LLM → LISTENING with message", "double end is idempotent"; M2.A3 mini-tasks 2+3 (wiring, still device-gated)
STATUS: done (tests green on CI-to-follow run; loop itself still NOT device-verified)
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/LoopPorts.kt (NEW — LoopCapture/LoopPlayback/ListenEngine/BargeInEngine/SpeakEngine), app/src/main/kotlin/app/memo/platform/android/voice/SherpaLoopAdapters.kt (NEW — device wiring), app/src/main/kotlin/app/memo/runtime/VoiceLoop.kt (consumes ports; answerFailed; requestEnd transition-guard), app/src/test/kotlin/app/memo/runtime/VoiceLoopTest.kt (NEW — 7 tests), app/build.gradle.kts (testOptions isReturnDefaultValues for android.util.Log on JVM), docs/PROGRESS.md, docs/ENGINEERING_LOG.md
VERIFICATION: `:app:testDebugUnitTest` BUILD SUCCESSFUL EXIT=0 — 200 tests, 7 new VoiceLoopTest (happy exchange + history accumulation, barge-in mid-speech with a blocked write, LLM error → apology → LISTENING + survival, requestEnd releases + fires once, double end idempotent, start twice refused, mic failure ends, empty transcript ignored). Two real defects the tests caught: a stale `releaseAudio` on every queued requestEnd (double onEnded) and — by plan re-read — the LLM error path ended the conversation where TASKS says LISTENING with a message.
NEXT STEP: device probe of the full loop on emulator-5554 (real mic; decide host-audio routing), measure the <1.2 s first-audio gate, tick mini-tasks 2+3 on device evidence; then M2.B1 voice UI per plan order.
NOTES: Ports live in app/runtime (they reference AudioCapture.Listener / AudioPlayback.Open — app-module types, so core was not an option); fakes implement the ports pure-JVM, so the loop's threading discipline is regression-guarded on CI and the device probe only has to validate the real DSP. The apology string is a constant for now — i18n comes with the M2.B1 UI wiring.
---
---

TIMESTAMP: 2026-10-28T01:49:00+01:00
STEP: 12 - M2.B1 mini-task 1 (UI half): Waveform + VoiceRecorderBar
PLAN REF: TASKS.md M2.B1 mini-task 1 "hold to record with a live waveform; slide to cancel; release sends (audio + transcript)"; branch survey on user request (wp/chat carries the only unmerged work - M1.C1+C2 chat UI; its composer mic is the placeholder these pieces will replace)
STATUS: UI primitives compile-verified; mini-task stays unticked until the B1-2 recorder engine delivers audio+transcript on release. Per the user's order, WaveformBucketsTest/RecorderGestureStateTest are deferred to the end-of-M2 test pass.
FILES CHANGED: app/src/main/kotlin/app/memo/ui/voice/Waveform.kt (NEW), app/src/main/kotlin/app/memo/ui/voice/VoiceRecorderBar.kt (NEW), docs/PROGRESS.md (this entry), docs/ENGINEERING_LOG.md (gesture pattern note)
VERIFICATION: :app:compileDebugKotlin BUILD SUCCESSFUL in 2m 1s, EXIT=0, no new warnings. Pure logic split out (WaveformMath.buckets, RecorderGestureState, formatRecorderElapsed) so the deferred tests can pin it directly. CI on the previous head e6a528f green (run 40609941701, 6m52s).
NEXT STEP: B1-2 recorder engine: AudioCapture -> WAV file + live meter levels, SherpaOfflineAsr (whisper-base pin) transcript on release; VoiceRecorderBar then gets real levels, and composer/sent-bubble wiring follows once wp/chat merges.
NOTES: Gesture semantics: press starts recording immediately, quick release (<350 ms) = TAP (composer will open the live conversation), slide up about one bar height arms cancel with a reject haptic, release after that cancels, otherwise sends. If the tracked pointer vanishes from the stream the bar falls back to cancel, never send. Waveform max-pools meter frames so peaks survive downsampling.
