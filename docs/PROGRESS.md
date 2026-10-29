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
TIMESTAMP: 2026-10-26T23:37:02+01:00
STEP: 1 — establish full B-STD verification baseline
STATUS: blocked
FILES CHANGED: docs/PROGRESS.md
VERIFICATION: `./gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest lintDebug assembleDebug` -> failed before tasks on both attempts; first wrapper lock access was denied, escalated retry could not establish Gradle loopback connection. No tests or build tasks ran.
NEXT STEP: Diagnose the Gradle wrapper lock and loopback connection failures, then rerun the same B-STD command before resuming CHAT and VOICE.
NOTES: No code changed. Latest Claude Code task output confirms M0 on `main` passed at `00a8a9e` and the `memo-apk` artifact is 75,555,680 bytes. The existing CHAT and VOICE worktrees remain untouched.
---

---
TIMESTAMP: 2026-10-27T16:02:39+01:00
STEP: 2 — B-STD local baseline established on milestone/M1 (c293c25)
RESUMED FROM: STEP 1 above (blocked baseline); execution layer per user = GPT-app Codex (cloud sandbox), plan layer = Claude (docs/BUILD_PLAN.md + TASKS.md)
STATUS: done
FILES CHANGED: docs/PROGRESS.md (this entry only)
VERIFICATION: `.\gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest` -> BUILD SUCCESSFUL in 1m 22s (30 tasks; :core:test and testDebugUnitTest executed and passed)
NEXT STEP: Verify the uncommitted VOICE M2.A2 slice in worktree `.claude/worktrees/agent-af96ee867896a60ae` (branch wp/voice) with the same commands there; no edits unless a real failure, then secure it (commit, push, CI) per B-STD.
NOTES: STEP 1's blockers (wrapper lock denial, Gradle loopback) do NOT reproduce in an unsandboxed shell — they were the prior agent's sandbox constraints; environment verified: JDK 17 on PATH, wrapper 8.14.6 unpacked, no zombie daemons. 3 pre-existing Kotlin warnings recorded (ModelHub.kt:280 cast, LiteRtLmRuntime.kt:288 callback param name, Persistence.kt:230 always-true) — owned by HUB/LLM/M0 code, deliberately untouched. CI truth: wp/chat tip c357270 green (run 40521058967); milestone/M1 integration green (run 40405377602); ENGINEERING_LOG numbering: wp/chat took D-038/D-039; WP VOICE records its dependency decision as D-038 in its own branch space — the lead renumbers on merge (wave-1 precedent).
---

---
TIMESTAMP: 2026-10-29T14:57:27+01:00
CHAIN: Claude (plan) -> GPT-app Codex leg1 -> GLM leg2 (wp/voice, wp/chat) -> GLM leg3 (this session)
STEP: A — resume housekeeping: secure the uncommitted handoff log, ignore the native build cache
PLAN REF: TASKS §0.9 lead-inline items (docs, hygiene); engineering-log rule 1
STATUS: done
FILES CHANGED: docs/PROGRESS.md (leg-1/leg-2 STEP 1–2 entries now committed, plus this entry), PLAN_STATE.md (NEW, tracked), .gitignore (app/.cxx/)
VERIFICATION: git status clean after commit; no code changed.
NEXT STEP: B — dispatch CI on wp/voice tip 5343db1 (gh workflow run build-memo-apk.yml -R ysfclc/memo-2 --ref wp/voice, watch to green); then C — merge wp/chat into milestone/M1.
NOTES: User decisions 2026-10-29: commons-compress 1.28.0 pin ACCEPTED on leg-2's D-038 evidence (version-age law documented); merges delegated to the agent, lead-style, CI-gated. wp/voice tip 5343db1 still has NO green CI run (last green covers 32c7d5c) — B closes that before any merge. Renumber-on-merge heads-up: wp/voice D-038/D-039 collide with wp/chat D-038/D-039; resolved at STEP D. No code changed in this step.
---

---
TIMESTAMP: 2026-10-29T15:06:43+01:00
CHAIN: Claude (plan) -> GPT-app Codex leg1 -> GLM leg2 (wp/voice, wp/chat) -> GLM leg3 (this session)
STEP: B — close leg-2's open verification: CI green on wp/voice tip 5343db1
PLAN REF: TASKS §0.4 CI; AGENTS.md rule 7 (green CI = definition of done)
STATUS: done
FILES CHANGED: PLAN_STATE.md (checkpoint rewrite only)
VERIFICATION: run 40887088494 (workflow_dispatch @ 5343db17539f42dff9bec3fabea886379b9edf58) -> ✓ Build Android APK in 6m42s; Build (core tests, app unit tests, lint, debug APK) ✓; Verify APK ✓; List APK native libraries ✓; Upload APK artifact ✓. Annotations: CI-platform noise only (node20 deprecation, ubuntu-latest migration).
NEXT STEP: C — merge wp/chat into milestone/M1 (B-STD, push, CI on milestone/M1), then D — wp/voice (renumber D-038/D-039 on the merge).
NOTES: wp/chat pre-merge facts verified before dispatch: 10 ahead / 1 behind milestone/M1, branch worktree clean, origin in sync. No source or docs changed this step.
---

---
TIMESTAMP: 2026-10-27T05:48:24Z
STEP: 2 — Preserve corrupt chat drafts
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/ChatRepository.kt; app/src/test/kotlin/app/memo/runtime/ChatRepositoryTest.kt
VERIFICATION: git diff --check -> pass; .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests app.memo.runtime.ChatRepositoryTest.firstDraftSaveDoesNotOverwriteCorruptDraftsEntry -> blocked before task execution by Gradle loopback connection failure
NEXT STEP: Finish M1.C2 by wiring ChatRepository and ChatService into MemoRuntime, then add the chat screen.
NOTES: readDraftsOnWriter can set draftsBlocked while returning an empty fallback; saveDraftOnWriter must re-check that flag before writing. Test asserts original corrupt bytes remain unchanged. Local baseline Gradle failure is environmental and unverified in CI.
---

---
TIMESTAMP: 2026-10-27T05:49:10Z
STEP: 3 — Specify encrypted per-model settings
STATUS: partial
FILES CHANGED: app/src/test/kotlin/app/memo/runtime/ChatSettingsRepositoryTest.kt
VERIFICATION: .\gradlew.bat --no-daemon --stacktrace :app:testDebugUnitTest --tests app.memo.runtime.ChatSettingsRepositoryTest -> not run yet; the previous targeted test was blocked by Gradle wrapper lock access, and the full baseline also failed loopback initialization
NEXT STEP: Implement ChatSettingsRepository against Vault and satisfy these tests.
NOTES: Tests require missing-value defaults, strict decoding, no overwrite of invalid JSON, and catalog-only natural keys. Keep settings in KeystoreVault.
---

---
TIMESTAMP: 2026-10-27T05:49:45Z
STEP: 4 — Store chat settings in the encrypted vault
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/ChatSettingsRepository.kt
VERIFICATION: git diff --check -> pending; :app:testDebugUnitTest --tests app.memo.runtime.ChatSettingsRepositoryTest -> blocked until Gradle can start
NEXT STEP: Wire the chat repository, service, and settings repository into MemoRuntime.
NOTES: Settings use catalog IDs for validated vault names. Existing JSON is decoded before a write, so malformed settings and vault read failures leave stored bytes unchanged.
---

---
TIMESTAMP: 2026-10-27T05:50:30Z
STEP: 5 — Wire chat to verified local inference
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/MemoRuntime.kt
VERIFICATION: git diff --check -> pending; :app:compileDebugKotlin and ChatServiceTest -> blocked pending CI because Gradle cannot acquire its wrapper lock in this environment
NEXT STEP: Add a Compose chat screen that uses these runtime services and the existing ChatController.
NOTES: ModelRef is constructed only from ModelCatalog plus ModelHub.installedFile, which checks the pinned size and SHA sidecar. Frontier providers are not routed through a fake local runtime.
---

---
TIMESTAMP: 2026-10-27T05:52:00Z
STEP: 6 — Bind composer send state to inference
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatController.kt
VERIFICATION: git diff --check -> pending; ComposerStateTest and ChatServiceTest -> blocked pending Gradle/CI
NEXT STEP: Render the conversation thread and real streaming response in Compose.
NOTES: The controller now refuses a send while ChatService.streaming is non-null, preserving the draft during the service's one-request-at-a-time guard.
---

---
TIMESTAMP: 2026-10-27T05:54:00Z
STEP: 7 — Render streaming Markdown blocks
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatMarkdown.kt
VERIFICATION: git diff --check -> pending; :core:test --tests app.memo.core.chat.MarkdownParserTest and :app:compileDebugKotlin -> CI required
NEXT STEP: Add message rows, real streaming display, scroll handling, and message actions.
NOTES: Rendering re-parses the current stream snapshot using MarkdownParser; links are only activated for HTTPS URLs. Compose compiler compatibility is UNVERIFIED locally.
---

---
TIMESTAMP: 2026-10-27T05:56:10Z
STEP: 8 — Add chat composer, stop, and bounded attachment input
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatComposer.kt
VERIFICATION: git diff --check -> pending; ComposerStateTest and app compilation -> CI required
NEXT STEP: Render conversation messages and real streaming output with actions and scroll-to-bottom behavior.
NOTES: Picker reads at most 200 KB + 1 byte; `Attachments.fromBytes` rejects oversize, binary, and invalid UTF-8 content. Local voice control is disabled until the M2 local speech UI exists.
---

---
TIMESTAMP: 2026-10-27T05:57:20Z
STEP: 8a — Keep attachment errors accurate
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatComposer.kt; app/src/main/kotlin/app/memo/ui/chat/ChatController.kt
VERIFICATION: git diff --check -> pending; Compose compilation -> CI required
NEXT STEP: Render conversation messages and real streaming output with actions and scroll-to-bottom behavior.
NOTES: File-open errors now report a read failure instead of manufacturing an oversize attachment result. Corrected the runtime ChatService import and remove icon.
---

---
TIMESTAMP: 2026-10-27T06:00:00Z
STEP: 9 — Render thread, stream, and message actions
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatThread.kt
VERIFICATION: git diff --check -> pending; MarkdownParserTest, ChatServiceTest, and app compilation -> CI required
NEXT STEP: Add chat history/model sheets and connect the new screen to the Talk route.
NOTES: Stream bubbles are shown only when their conversation ID matches the open document. New stream text auto-scrolls only while the reader is at the bottom; otherwise a Latest control appears.
---

---
TIMESTAMP: 2026-10-27T06:02:00Z
STEP: 9a — Correct Compose coroutine and renderer wiring
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatComposer.kt; app/src/main/kotlin/app/memo/ui/chat/ChatMarkdown.kt; app/src/main/kotlin/app/memo/ui/chat/ChatThread.kt
VERIFICATION: git diff --check -> pending; Compose compile -> CI required
NEXT STEP: Add chat history/model sheets and connect the new screen to the Talk route.
NOTES: Scroll animation now runs in a remembered coroutine scope; renderer builder and composer spacing imports are explicit. Removed unused message action parameters found in static review.
---

---
TIMESTAMP: 2026-10-27T06:04:00Z
STEP: 10 — Add history and model picker sheets
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatSheets.kt
VERIFICATION: git diff --check -> pending; app compile and chat test suites -> CI required
NEXT STEP: Assemble ChatScreen, wire the Talk route, and expose undo feedback.
NOTES: History search uses ChatController's asynchronous message index; picker lists only ModelEntry values already verified as installed by the caller. Frontier route is explicitly deferred to M4.
---

---
TIMESTAMP: 2026-10-27T06:06:00Z
STEP: 11 — Assemble chat screen
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatScreen.kt
VERIFICATION: git diff --check -> pending; :app:compileDebugKotlin and chat tests -> CI required
NEXT STEP: Add draft flush support, then replace the legacy Talk body with ChatScreen.
NOTES: Screen reads verified models through ModelStore state and ChatController; it flushes a draft on disposal and uses actual ChatService StateFlows for stream and model status.
---

---
TIMESTAMP: 2026-10-27T06:08:30Z
STEP: 11a — Protect draft and screen lifecycle state
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatController.kt; app/src/main/kotlin/app/memo/ui/chat/ChatThread.kt; app/src/main/kotlin/app/memo/ui/chat/ChatScreen.kt
VERIFICATION: git diff --check -> pending; Compose compile and controller behavior -> CI required
NEXT STEP: Replace the legacy Talk screen body with ChatScreen and remove Android recognizer popup behavior.
NOTES: New-chat draft is restored after async vault load; pending debounce is flushed on disposal; corrupt settings produce a visible notice before defaults are used. Model install state is read from ModelStore's observable snapshot.
---

---
TIMESTAMP: 2026-10-27T06:10:30Z
STEP: 12 — Connect TALK to local chat
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/MemoRoot.kt; app/src/main/kotlin/app/memo/ui/chat/ChatSheets.kt
VERIFICATION: git diff --check -> pending; app compile -> CI required
NEXT STEP: Implement the M1.B3 model browser sections, fit/license/gated details, and per-model settings sheet.
NOTES: `TalkScreen` delegates to `ChatScreen`; Android RecognizerIntent speech was removed. M2 local speech remains a later task. Removed an invalid/nonessential scope-extension import from the history sheet.
---

---
TIMESTAMP: 2026-10-27T06:12:00Z
STEP: 12a — Fix chat search and active-stream actions
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatController.kt; app/src/main/kotlin/app/memo/ui/chat/ChatThread.kt; app/src/main/kotlin/app/memo/ui/chat/ChatSheets.kt
VERIFICATION: git diff --check -> pending; Compose compile -> CI required
NEXT STEP: Implement M1.B3 model sections and settings sheet.
NOTES: Search index is observable and debounced; edit/delete/regenerate actions are hidden while any inference request is active.
---

---
TIMESTAMP: 2026-10-27T06:14:00Z
STEP: 13 — Specify safe inference unload
STATUS: partial
FILES CHANGED: app/src/test/kotlin/app/memo/runtime/InferenceManagerTest.kt
VERIFICATION: .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests app.memo.runtime.InferenceManagerTest -> blocked before task execution by wrapper lock/loopback environment
NEXT STEP: Add idle-only unload to InferenceManager and ChatService, then use it before uninstalling a model.
NOTES: Tests require unload to release a resident runtime only with no active or queued inference; active generation must remain untouched.
---

---
TIMESTAMP: 2026-10-27T06:16:00Z
STEP: 14 — Unload inference only while idle
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/InferenceManager.kt; app/src/main/kotlin/app/memo/runtime/ChatService.kt; docs/ENGINEERING_LOG.md
VERIFICATION: InferenceManagerTest idle/busy cases -> blocked pending CI; git diff --check -> pending
NEXT STEP: Serialize model download/uninstall file operations and expose removal through ModelStore.
NOTES: `InferenceManager.unloadIfIdle` holds the request lock while unloading; queued/active work refuses. ChatService clears its model status only after successful unload. Decision D-038 records the safety contract.
---

---
TIMESTAMP: 2026-10-27T06:18:00Z
STEP: 15 — Serialize hub download and uninstall
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/hub/ModelHub.kt
VERIFICATION: git diff --check -> pending; app compile and WorkManager/device uninstall behavior -> CI/device required
NEXT STEP: Add a ModelStore uninstall wrapper that unloads inference first.
NOTES: Uninstall awaits unique-work cancellation, then acquires the same per-model mutex as runDownload. It checks the verified install before deleting hub-owned files and refreshes state if filesystem removal fails.
---

---
TIMESTAMP: 2026-10-27T06:20:00Z
STEP: 16 — Expose uninstall through ModelStore
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pending; app compile -> CI required
NEXT STEP: Build the model screen sections, cards, settings editor, and uninstall confirmation.
NOTES: The runtime-idle callback gates removal; successful deletion clears the persisted active/default model selection. Results return on the main Compose scope.
---

---
TIMESTAMP: 2026-10-27T06:22:00Z
STEP: 17 — Specify discovered model pin persistence
STATUS: partial
FILES CHANGED: core/src/test/kotlin/app/memo/core/catalog/ModelEntryCodecTest.kt
VERIFICATION: :core:test --tests app.memo.core.catalog.ModelEntryCodecTest -> blocked pending Gradle; local wrapper lock and loopback failures prevent task execution
NEXT STEP: Implement a strict versioned ModelEntryCodec and persist discovered pins from ModelStore.
NOTES: Tests use seed ModelEntry values to verify all catalog fields round-trip, and require exact envelope keys.
---

---
TIMESTAMP: 2026-10-27T06:23:30Z
STEP: 18 — Add strict model entry codec
STATUS: partial
FILES CHANGED: core/src/main/kotlin/app/memo/core/catalog/ModelEntryCodec.kt
VERIFICATION: git diff --check -> pending; ModelEntryCodecTest -> blocked pending Gradle
NEXT STEP: Persist discovered entries in ModelStore and resolve them for chat.
NOTES: Codec caps records at 500 entries/1 MB, requires exact envelope and entry fields, rejects duplicate ids, and delegates pin shape validation to ModelEntry.
---

---
TIMESTAMP: 2026-10-27T06:26:30Z
STEP: 19 — Persist live-discovered model pins
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: ModelEntryCodecTest and app compile -> blocked pending CI; git diff --check -> pending
NEXT STEP: Resolve dynamic model entries in ChatController/MemoRuntime and connect model settings to the screen.
NOTES: Candidate IDs are looked up before save; same ID with changed revision updates only when repo/file identity matches. Invalid or conflicting saved data is preserved and blocks candidate persistence; download requires an exact persisted entry.
---

---
TIMESTAMP: 2026-10-27T06:28:30Z
STEP: 20 — Resolve dynamic model pins in chat
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/MemoRuntime.kt; app/src/main/kotlin/app/memo/ui/chat/ChatController.kt; app/src/main/kotlin/app/memo/ui/chat/ChatScreen.kt; app/src/main/kotlin/app/memo/runtime/ChatSettingsRepository.kt
VERIFICATION: ChatServiceTest, ChatSettingsRepositoryTest, and app compile -> CI required; local Gradle remains blocked
NEXT STEP: Implement the five M1.B3 model sections and model card settings/download/default/delete actions.
NOTES: ModelRef resolution still passes through Hub.installedFile, which requires the current pinned file size and digest. Settings keys accept only safe slug IDs that fit VaultName limits.
---

---
TIMESTAMP: 2026-10-27T06:30:30Z
STEP: 21 — Implement model browser and settings sheet
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: FitBadgeTest, ModelEntryCodecTest, app compile -> CI required; local Gradle wrapper/loopback failures persist
NEXT STEP: Replace the old ModelsScreen body with this browser, wire it to MemoRoot, and correct static compile issues from source review.
NOTES: Browse is debounced live search; unpersisted candidates are view-only. Settings use ChatSettingsRepository; downloads show actual ModelStore progress; uninstall requires ChatService idle-unload.
---
---
TIMESTAMP: 2026-10-27T06:11:24Z
STEP: 22 — Guard discovered model IDs and Compose state
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; Gradle app/test tasks remain blocked by the recorded wrapper-lock/loopback environment failures
NEXT STEP: Wire ModelsBrowserScreen into the existing ModelsScreen route and remove the superseded screen implementation.
NOTES: Static seed IDs now remain authoritative; mismatching live metadata is reported as a collision, and only dynamic IDs can update by stable repo/file identity. Added Compose delegated-state imports required by discoveryError.
---
---
TIMESTAMP: 2026-10-27T06:11:50Z
STEP: 23 — Import vertical scrolling for model settings
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: git diff --check -> pass; source review confirms verticalScroll extension import is used by settings sheet
NEXT STEP: Replace the superseded ModelsScreen implementation with a thin browser entry point.
NOTES: Full Compose compilation remains pending CI because local Gradle is blocked by the recorded environment failures.
---
---
TIMESTAMP: 2026-10-27T06:12:12Z
STEP: 24 — Replace the old Models screen body
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsScreen.kt
VERIFICATION: git diff --check -> pass; wrapper delegates to ModelsBrowserScreen(runtime)
NEXT STEP: Update MemoRoot to pass MemoRuntime into ModelsScreen.
NOTES: Removed the superseded static catalog/import-only UI as required by the repo's no-dead-code rule; navigation call is the next isolated step.
---
---
TIMESTAMP: 2026-10-27T06:12:28Z
STEP: 25 — Wire the Models route to the shared runtime
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/MemoRoot.kt
VERIFICATION: git diff --check -> pass; rg confirms the only ModelsScreen call now passes MemoRuntime
NEXT STEP: Review and correct model-card edge cases from M1.B3, including default selection and gated/download states.
NOTES: Models settings, model catalog resolution, and idle-unload now share the same runtime used by the chat screen.
---
---
TIMESTAMP: 2026-10-27T06:13:10Z
STEP: 26 — Honor the selected default model in new chats
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/chat/ChatScreen.kt
VERIFICATION: git diff --check -> pass; source inspection confirms a verified active ID sorts first, otherwise installed catalog order remains
NEXT STEP: Correct the settings repository test to assert rejection of unsafe IDs while permitting valid live-discovered IDs.
NOTES: ModelStore.activeId already validates that the persisted selection is currently installed before ChatController receives it.
---
---
TIMESTAMP: 2026-10-27T06:13:31Z
STEP: 27 — Align settings ID coverage with discovered models
STATUS: partial
FILES CHANGED: app/src/test/kotlin/app/memo/runtime/ChatSettingsRepositoryTest.kt
VERIFICATION: git diff --check -> pass; source inspection confirms a safe dynamic ID gets defaults and slash IDs are rejected; Gradle remains blocked
NEXT STEP: Finish source review of the model card states and ensure gated/download actions match the pinned model policy.
NOTES: Dynamic IDs are intentionally not restricted to the static catalog; vault-name validation still prevents unsafe record names.
---
---
TIMESTAMP: 2026-10-27T06:14:15Z
STEP: 28 — Show gated access clearly in model cards
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: git diff --check -> pass; source inspection confirms fit, license, and gated lock/access indicators share a horizontally scrollable row
NEXT STEP: Review discovery sequencing and model card download eligibility for stale-search or unpinned candidates.
NOTES: Gated downloads remain disabled; this change makes the reason visible without adding any inline colors.
---
---
TIMESTAMP: 2026-10-27T06:14:43Z
STEP: 29 — Ignore out-of-order model search results
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: git diff --check -> pass; source inspection confirms only the latest section/query generation can update result state
NEXT STEP: Review the chat UI and storage against the M1.C1/M1.C2 acceptance list, then resolve any remaining plan gaps.
NOTES: The generation also invalidates outstanding callbacks when leaving Browse. Candidate pin persistence remains in ModelStore.
---
---
TIMESTAMP: 2026-10-27T06:15:57Z
STEP: 30 — Keep alternate model families selectable
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: git diff --check -> pass; family chips now derive from query/format candidates before family selection
NEXT STEP: Inspect the full uncommitted diff for cross-file consistency and remaining unchecked M1 acceptance items.
NOTES: Search and format filters still narrow the available family list; selecting one family no longer hides its alternatives.
---
---
TIMESTAMP: 2026-10-27T06:17:30Z
STEP: 31 — Serialize discovery pin persistence
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; source inspection confirms the merge, codec commit, and concurrent-map update share a single lock
NEXT STEP: Ensure discovery waits until stored pins have loaded before accepting live search results.
NOTES: Network discovery remains concurrent; only the natural-ID merge/persistence transaction is serialized. This prevents one search from losing another search's pins.
---
---
TIMESTAMP: 2026-10-27T06:18:00Z
STEP: 32 — Gate live discovery on saved pin initialization
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; source inspection confirms discovery awaits completion of hub load and persisted-entry validation
NEXT STEP: Review M1 acceptance items and inspect all newly added source for static/API mismatches before requesting CI access.
NOTES: Initialization failure completes the gate exceptionally, which returns a fixed search-unavailable message instead of persisting against an incomplete snapshot.
---
---
TIMESTAMP: 2026-10-27T06:18:36Z
STEP: 33 — Clear the stored default after model removal
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; source inspection confirms uninstall compares the raw persisted active ID after file deletion
NEXT STEP: Record the uninstall/default-selection gotcha in the engineering notebook, then continue the consistency review.
NOTES: activeId intentionally returns null for an uninstalled file, so it cannot be used to detect which stored preference must be cleared after removal.
---
---
TIMESTAMP: 2026-10-27T06:19:44Z
STEP: 34 — Support settings for long live model IDs
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/ChatSettingsRepository.kt
VERIFICATION: git diff --check -> pass; source review confirms short keys remain unchanged and 64-character IDs map under the 64-character vault limit
NEXT STEP: Extend the settings repository test to round-trip a 64-character discovered ID.
NOTES: Long IDs use a stable 192-bit digest suffix only when the readable prefixed key would exceed VaultName limits. No existing short-ID record names change.
---
---
TIMESTAMP: 2026-10-27T06:20:07Z
STEP: 35 — Cover 64-character settings ID round-trip
STATUS: partial
FILES CHANGED: app/src/test/kotlin/app/memo/runtime/ChatSettingsRepositoryTest.kt
VERIFICATION: git diff --check -> pass; test source checks defaults, save/load round-trip, 64-character vault key, and unsafe slash rejection; Gradle remains blocked
NEXT STEP: Record the settings-key boundary in the engineering notebook, then run a whole-diff source and task checklist review.
NOTES: The test uses a 64-character valid slug, matching ModelEntry's maximum ID length.
---
---
TIMESTAMP: 2026-10-27T06:21:09Z
STEP: 36 — Surface model import outcomes
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; source inspection confirms verified, unverified, and fixed-error outcomes are posted to Compose state
NEXT STEP: Display the import outcome on ModelsBrowserScreen.
NOTES: Exception messages remain hidden; only verified imports can become the active model.
---
---
TIMESTAMP: 2026-10-27T06:21:28Z
STEP: 37 — Show the model import result
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/ui/ModelsBrowser.kt
VERIFICATION: git diff --check -> pass; source inspection confirms import status is a uniquely keyed list item and failures use theme attention color
NEXT STEP: Record the import feedback behavior and perform the full M1 source checklist review.
NOTES: Unverified files are explicitly described as non-loadable; verified files point users to Installed.
---
---
TIMESTAMP: 2026-10-27T06:22:20Z
STEP: 38 — Reject stale metadata for static model IDs
STATUS: partial
FILES CHANGED: app/src/main/kotlin/app/memo/models/ModelStore.kt
VERIFICATION: git diff --check -> pass; source inspection confirms persisted static IDs must match the full seed entry before discovery writes are allowed
NEXT STEP: Complete the M1 acceptance checklist and static source review; then request the required CI path if local verification remains unavailable.
NOTES: This includes revision, digest, license, and other metadata, not just repository/file identity. Conflicting saved bytes remain unchanged.
---
---
TIMESTAMP: 2026-10-27T06:23:18Z
STEP: 39 — Complete M1 static acceptance review
STATUS: partial
FILES CHANGED: docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; inline-color scan -> no matches in chat/model UI; named M1 codec/order/search/fit/markdown/composer tests are present; Gradle CI and device checks remain unverified
NEXT STEP: After the user approves the remote action, push wp/chat to origin and run the required milestone CI; then fix any failures before moving to VOICE.
NOTES: Source review covered chat/store wiring, pinned dynamic model resolution, model uninstall safety, settings, import feedback, and plan checklists. The task remains partial until CI is green and M1 device checks are handled.
---
---
TIMESTAMP: 2026-10-27T06:27:00Z
STEP: 40 — Sync CHAT status across plan and checklists
STATUS: partial
FILES CHANGED: docs/BUILD_PLAN.md; docs/TASKS.md; docs/FEATURES.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pending; git fetch origin -> pass; origin/milestone/M1 at c293c25 is already an ancestor of wp/chat
NEXT STEP: Stage the reviewed CHAT implementation and documentation as a local checkpoint commit, then push wp/chat and run CI.
NOTES: M1.B3, M1.C1, M1.C2, F1, and F2 are marked in progress; acceptance boxes remain incomplete until CI/device evidence exists. Plan scope/order are unchanged.
---
---
TIMESTAMP: 2026-10-27T06:45:00Z
STEP: 41 — Attempt CI APK install on emulator
STATUS: partial
FILES CHANGED: none (device install rejected; existing package/data untouched)
VERIFICATION: `adb install -r app-debug.apk` -> fail (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); CI run `40461314345` -> success
NEXT STEP: Inspect available emulator options; if supported, launch a read-only clone and install the CI APK there.
NOTES: The installed `app.memo.debug` has a different signing certificate. Do not uninstall or clear its data without explicit approval.
---
---
TIMESTAMP: 2026-10-27T06:49:43Z
STEP: 42 — Confirm safe emulator clone option
STATUS: done
FILES CHANGED: none (read-only inspection)
VERIFICATION: emulator -list-avds -> Memo_API34; emulator -help -> -read-only supported; active AVD identified as Memo_API34
NEXT STEP: Start a read-only clone of Memo_API34 and install the CI APK there; leave the existing emulator instance untouched.
NOTES: Read-only emulator instances cannot save snapshots and are supported by the installed emulator.
---
---
TIMESTAMP: 2026-10-27T06:50:43Z
STEP: 43 — Start a read-only emulator clone
STATUS: partial
FILES CHANGED: none
VERIFICATION: Start-Process -> PID 35832; adb devices -l -> only existing emulator-5554; no second emulator process found
NEXT STEP: Retry once with emulator diagnostics visible in the command output; do not change or remove the existing emulator.
NOTES: The launch command returned, but the clone did not register. The original AVD and app remain active.
---
---
TIMESTAMP: 2026-10-27T06:52:51Z
STEP: 44 — Retry read-only emulator with diagnostics
STATUS: blocked
FILES CHANGED: docs/PROGRESS.md
VERIFICATION: emulator -avd Memo_API34 -read-only -no-window -no-snapshot -no-boot-anim -verbose -> exit 1: Another emulator instance is running. Please close it or run all emulators with -read-only flag.
NEXT STEP: Use a fresh test AVD, or get approval to remove the existing debug app and its data before installing the CI APK. Do not retry the current AVD clone.
NOTES: The existing emulator was launched writable. No app uninstall, data clearing, or source changes were made. Two clone attempts failed; stop per recovery rule.
---
---
TIMESTAMP: 2026-10-27T06:55:13Z
STEP: 45 — Sync TASKS.md with CI and device status
STATUS: done
FILES CHANGED: docs/TASKS.md; docs/PROGRESS.md
VERIFICATION: `git diff --check` -> pass; focused diff records CI run 40461314345 and leaves device acceptance pending
NEXT STEP: Update BUILD_PLAN continuation status with the same CI and emulator evidence.
NOTES: M1.B3 acceptance follows BUILD_PLAN (CI plus token review); M1.C1 named tests passed in the green CI run.
---
---
TIMESTAMP: 2026-10-27T06:55:35Z
STEP: 46 — Sync BUILD_PLAN continuation status
STATUS: done
FILES CHANGED: docs/BUILD_PLAN.md; docs/PROGRESS.md
VERIFICATION: `git diff --check` -> pass; M1.B3/C1/C2 notes cite run 40461314345 and retain the C2 device blocker
NEXT STEP: Update FEATURES.md with verified CHAT feature status and remaining device scope.
NOTES: BUILD_PLAN remains authoritative: B3 acceptance uses CI and token review; the broader M1 device checklist remains open.
---
---
TIMESTAMP: 2026-10-27T06:56:05Z
STEP: 47 — Sync FEATURES.md CHAT status
STATUS: done
FILES CHANGED: docs/FEATURES.md; docs/PROGRESS.md
VERIFICATION: `git diff --check` -> pass; E6 stays partial and F1/F2 cite CI run 40461314345 with emulator validation pending
NEXT STEP: Update STATUS.md to separate CI evidence from unverified device inference.
NOTES: No feature was marked fully verified without device evidence.
---
---
TIMESTAMP: 2026-10-27T06:56:56Z
STEP: 48 — Sync STATUS.md verification levels
STATUS: done
FILES CHANGED: STATUS.md; docs/PROGRESS.md
VERIFICATION: `git diff --check` -> pass; CI is linked and actual model generation is explicitly UNVERIFIED on device
NEXT STEP: Add the CI/device signing constraint to ENGINEERING_LOG.md.
NOTES: ChatService is wired to LiteRT-LM and llama.cpp runtimes; no claim of successful on-device generation is made.
---
---
TIMESTAMP: 2026-10-27T06:57:55Z
STEP: 49 — Record CI and emulator findings in engineering log
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: `git diff --check` -> pass; C-009 and M1 checkpoint note record the observed blocker and mark real model execution UNVERIFIED
NEXT STEP: Audit the documentation diff, confirm latest origin/milestone/M1, then commit and push the docs checkpoint.
NOTES: Existing emulator app data was not removed; the CI APK install remains blocked by signature mismatch.
---
---
TIMESTAMP: 2026-10-27T06:58:50Z
STEP: 50 — Normalize appended documentation line endings
STATUS: done
FILES CHANGED: docs/PROGRESS.md; docs/ENGINEERING_LOG.md
VERIFICATION: git ls-files --eol -> both files use workspace CRLF; git diff --check -> pass
NEXT STEP: Review the complete documentation diff and repository status, then commit and push the checkpoint.
NOTES: Repaired mixed line endings introduced by the appended checkpoint entries; no text content changed.
---

---
TIMESTAMP: 2026-10-27T08:32:18Z
STEP: 51 — Record documentation checkpoint CI
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; gh run watch 40473402964 -> pass in 5m9s
NEXT STEP: Use a fresh test AVD to install the CI APK and complete the smallest-model demo and M1 device checklist; ask before removing the existing app/data.
NOTES: CI ran on f665323; platform notices are documented in ENGINEERING_LOG.md C-010.
---

---
TIMESTAMP: 2026-10-27T08:34:43Z
STEP: 52 — Create clean test AVD
STATUS: done
FILES CHANGED: local AVD Memo_API34_CHAT; docs/PROGRESS.md
VERIFICATION: avdmanager create avd -> exit 0; emulator -list-avds -> Memo_API34 and Memo_API34_CHAT
NEXT STEP: Boot Memo_API34_CHAT, wait for sys.boot_completed=1, then install the CI APK.
NOTES: The existing Memo_API34 and its app data were left untouched.
---

---
TIMESTAMP: 2026-10-27T08:37:25Z
STEP: 54 — Install CI APK on clean AVD
STATUS: done
FILES CHANGED: app.memo.debug on emulator-5556; docs/PROGRESS.md
VERIFICATION: adb -s emulator-5556 install -r app-debug.apk -> Success; pm path returned /data/app/.../base.apk
NEXT STEP: Launch Memo on emulator-5556, inspect the chat/model screens, and check crash logs.
NOTES: Used the artifact from green CI run 40461314345; it contains the same source as the docs-only follow-up commits. Existing emulator-5554 was not changed.
---

---
TIMESTAMP: 2026-10-27T08:38:17Z
STEP: 55 — Launch CHAT app on clean AVD
STATUS: done
FILES CHANGED: app.memo.debug on emulator-5556; docs/PROGRESS.md
VERIFICATION: monkey -> Events injected: 1; dumpsys -> app.memo.debug/app.memo.MainActivity resumed; logcat -b crash -> empty
NEXT STEP: Capture the screen and inspect the Talk/chat and Models navigation.
NOTES: App launch produced no crash on Android 14.
---

---
TIMESTAMP: 2026-10-27T08:38:52Z
STEP: 56 — Capture initial screen
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/launch.png (ignored); docs/PROGRESS.md
VERIFICATION: adb pull -> 94,803 bytes; visual inspection -> Live screen renders with Talk and Models navigation
NEXT STEP: Open Talk and Models on emulator-5556 and inspect their visible screens.
NOTES: Screenshot came from the clean Android 14 test AVD; no account data was present.
---

---
TIMESTAMP: 2026-10-27T08:39:33Z
STEP: 57 — Inspect Talk on clean AVD
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/talk.png (ignored); docs/PROGRESS.md
VERIFICATION: Talk selected; MainActivity resumed; screenshot shows empty state, model chooser, composer, attach and mic controls
NEXT STEP: Open Models and inspect installed/recommended cards, search/filter controls and import entry point.
NOTES: No local model is installed; actual generation remains pending the smallest-model download.
---

---
TIMESTAMP: 2026-10-27T08:40:14Z
STEP: 58 — Inspect Recommended models screen
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/models.png (ignored); docs/PROGRESS.md
VERIFICATION: Models selected; screenshot shows Recommended cards, Qwen3 0.6B at 344.7 MB, fit/license badges, Download and settings controls
NEXT STEP: Inspect Installed, Browse, Import and per-model settings; then try the smallest-model download.
NOTES: The screen renders on the clean Android 14 AVD; no model is installed yet.
---

---
TIMESTAMP: 2026-10-27T08:40:57Z
STEP: 59 — Inspect Browse and filters
STATUS: partial
FILES CHANGED: app/build/chat-smoke-f665323/browse.png (ignored); docs/PROGRESS.md
VERIFICATION: screenshot shows Browse selected, search, format/family filters, fit/license badges and download actions; source lookup still displays Checking pinned model sources
NEXT STEP: Inspect per-model settings and the remaining model tabs; retry live source state after a short wait.
NOTES: UI is present; the live pinned-source refresh remains UNVERIFIED on this emulator.
---

---
TIMESTAMP: 2026-10-27T08:41:36Z
STEP: 60 — Inspect per-model settings
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/settings.png (ignored); docs/PROGRESS.md
VERIFICATION: screenshot shows system prompt, temperature 0.3, context size 4096 and thinking toggle
NEXT STEP: Inspect Installed and Import sections without saving any settings.
NOTES: No settings were changed or saved.
---

---
TIMESTAMP: 2026-10-27T13:17:26Z
STEP: 61 — Inspect Installed empty state
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/installed.png (ignored); docs/PROGRESS.md
VERIFICATION: Installed selected; screenshot shows No installed models and 9106.4 MB free for model files
NEXT STEP: Reveal the rightmost model tabs and inspect Voice/Import entry points.
NOTES: Empty state is expected on the fresh AVD.
---

---
TIMESTAMP: 2026-10-27T13:18:10Z
STEP: 62 — Reveal Voice and Import tabs
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/tabs-right.png (ignored); docs/PROGRESS.md
VERIFICATION: horizontal swipe moves the category row and shows Recommended, Browse, Voice and Import
NEXT STEP: Open Import and inspect the system file picker without selecting a file.
NOTES: The category row is scrollable; no model data changed.
---

---
TIMESTAMP: 2026-10-27T13:19:20Z
STEP: 63 — Inspect Import section
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/import.png (ignored); docs/PROGRESS.md
VERIFICATION: Import selected; screen shows pinned SHA-256 rule, Pick file, Scan device and free-space indicator
NEXT STEP: Open the Android picker and cancel without selecting or importing a file.
NOTES: Import screen warns that unpinned hashes remain unverified; no file was selected.
---
---
TIMESTAMP: 2026-10-27T13:21:08Z
STEP: 64 — Open system file picker
STATUS: partial
FILES CHANGED: app/build/chat-smoke-f665323/picker.png (ignored); docs/PROGRESS.md
VERIFICATION: picker screenshot visually shows Android DocumentsUI Recent screen; adb start-server -> failed to connect to daemon, return to Memo not verified
NEXT STEP: Restart the clean Memo_API34_CHAT AVD, verify it boots, then confirm Memo resumes after dismissing the picker.
NOTES: No file was selected. ADB and emulator processes were absent after capture; existing Memo_API34 was not touched.
---
---
TIMESTAMP: 2026-10-27T13:25:43Z
STEP: 65 — Recover clean AVD and confirm Memo foreground
STATUS: done
FILES CHANGED: local AVD Memo_API34_CHAT; docs/PROGRESS.md
VERIFICATION: adb -s emulator-5556 shell getprop sys.boot_completed -> 1; dumpsys -> app.memo.debug/app.memo.MainActivity resumed
NEXT STEP: Inspect Scan device from the Import screen and record its empty-device result.
NOTES: Step 64 picker screenshot showed DocumentsUI Recent with no selection; after emulator recovery MainActivity is foreground. Sandbox ADB needed OS-level permission because fdevent socketpair creation failed in the restricted process.
---
---
TIMESTAMP: 2026-10-27T13:26:40Z
STEP: 66 — Record Windows device-check constraint
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: C-011 inspected in place; git diff --check -> pass
NEXT STEP: Inspect Scan device from Import and record its empty-device result.
NOTES: The clean AVD resumed with Memo in the foreground after the Android file picker was opened without selecting a file.
---
---
TIMESTAMP: 2026-10-27T13:27:45Z
STEP: 67 — Verify Scan device empty state
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/scan-device.png and scan-device-result.png (ignored); docs/PROGRESS.md
VERIFICATION: screenshot after Scan device shows No model files found; MainActivity resumed; crash-buffer search returned no fatal entries
NEXT STEP: Open Recommended and start the pinned Qwen3 0.6B download for the smallest-model demo.
NOTES: The emulator has no imported model files; free space shown as 9103.9 MB. No file was selected, imported, or deleted.
---
---
TIMESTAMP: 2026-10-27T13:29:26Z
STEP: 68 — Confirm smallest recommended model
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/recommended-current.png (ignored); docs/PROGRESS.md
VERIFICATION: screenshot shows Qwen3 0.6B dynamic wi4b32, 344.7 MB, Fits this phone, apache-2.0, Not installed, and Download
NEXT STEP: Start the pinned Qwen3 0.6B download and observe its initial progress state.
NOTES: This is the smallest visible recommended model; the app labels it as the default orchestrator.
---
---
TIMESTAMP: 2026-10-27T13:30:12Z
STEP: 69 — Start smallest-model download
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/download-start.png (ignored); docs/PROGRESS.md
VERIFICATION: Qwen3 0.6B card changed from Not installed/Download to Connecting with a Pause control; MainActivity remained resumed
NEXT STEP: Check whether the pinned model download moves beyond Connecting and reports progress.
NOTES: The download was initiated through the app; expected size is 344.7 MB and install remains subject to the pinned size/SHA-256 verification.
---
---
TIMESTAMP: 2026-10-27T13:31:09Z
STEP: 70 — Verify active model-download progress
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/download-progress-70.png (ignored); docs/PROGRESS.md
VERIFICATION: after 20 seconds the UI reported 304.4 MB of 344.7 MB with Pause available; MainActivity remained resumed
NEXT STEP: Wait briefly for completion, then verify the model is installed after size and SHA-256 checks.
NOTES: The app displayed real byte progress over the pinned model download.
---
---
TIMESTAMP: 2026-10-27T13:32:07Z
STEP: 71 — Verify Qwen3 download and integrity install
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/download-complete-71.png (ignored); docs/PROGRESS.md
VERIFICATION: card changed to Installed and verified after progress reached 304.4/344.7 MB; Use as default appeared; MainActivity resumed and crash search was empty
NEXT STEP: Set Qwen3 0.6B as default, open Talk, and verify a real local response.
NOTES: The app reports its pinned model as verified; no manual file was imported.
---
---
TIMESTAMP: 2026-10-27T13:33:03Z
STEP: 72 — Set Qwen3 as the test default
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/default-model.png (ignored); docs/PROGRESS.md
VERIFICATION: Qwen3 card now shows Default and Installed and verified; MainActivity stayed resumed
NEXT STEP: Open Talk and confirm the local model is selected before sending a prompt.
NOTES: Preference changed only on the isolated Memo_API34_CHAT AVD.
---
---
TIMESTAMP: 2026-10-27T13:33:40Z
STEP: 73 — Open Talk with Qwen3 selected
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/talk-qwen-default.png (ignored); docs/PROGRESS.md
VERIFICATION: Talk header shows Qwen3 0.6B; runtime badge shows LiteRT-LM; composer and send control are visible
NEXT STEP: Send a short local prompt and verify a non-empty model response.
NOTES: Conversation screen confirms the default model is wired to the LiteRT-LM path on device.
---
---
TIMESTAMP: 2026-10-27T13:34:56Z
STEP: 74 — Verify local prompt draft
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/prompt-draft.png and prompt-draft-verified.png (ignored); docs/PROGRESS.md
VERIFICATION: after dismissing the keyboard, screenshot shows the unsent draft “Say hello in a short sentence.” and enabled send control
NEXT STEP: Send the visible prompt and capture the first actual model response.
NOTES: Keyboard initially obscured the composer; hiding it exposed the unchanged draft.
---
---
TIMESTAMP: 2026-10-27T13:38:34Z
STEP: 75 — Run smallest-model inference
STATUS: partial
FILES CHANGED: test AVD Memo_API34_CHAT; docs/PROGRESS.md (no source files)
VERIFICATION: FAIL — response screenshot absent; adb devices -l has no device; Windows Application Error at 14:35:44 records qemu-system-x86_64-headless.exe exception 0xc0000005, fault offset 0
NEXT STEP: Restart Memo_API34_CHAT without wiping data, verify the installed model and conversation state, then decide whether one controlled inference retry is safe.
NOTES: The request was sent from Talk with Qwen3 0.6B selected. The host QEMU process crashed, so guest app logs could not be collected. AVD config reports 2G RAM; causal link is unverified. Repository source is unchanged.
---
---
TIMESTAMP: 2026-10-27T13:40:12Z
STEP: 76 — Record inference-attempt host crash
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: C-011 and C-012 inspected under CONSTRAINTS; git diff --check -> pass
NEXT STEP: Restart Memo_API34_CHAT without wiping data and verify whether the pinned model remains installed.
NOTES: QEMU fault is verified; its underlying cause and any link to model loading are UNVERIFIED. Guest log could not be collected.
---
---
TIMESTAMP: 2026-10-27T13:41:30Z
STEP: 77 — Cold-boot AVD after QEMU crash
STATUS: partial
FILES CHANGED: local AVD Memo_API34_CHAT; docs/PROGRESS.md
VERIFICATION: `adb -s emulator-5554 emu avd name` -> Memo_API34_CHAT; `getprop sys.boot_completed` -> 1; top resumed activity -> FallbackHome
NEXT STEP: Launch app.memo.debug on emulator-5554 and verify Qwen3 0.6B still appears Installed and verified.
NOTES: Existing test AVD data was preserved. Its serial changed from emulator-5556 to emulator-5554; no generation retry yet.
---
---
TIMESTAMP: 2026-10-27T13:43:53Z
STEP: 78 — Verify AVD data survived QEMU crash
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/recovery-installed.png, recovery-loaded.png, recovery-models.png (ignored); docs/PROGRESS.md
VERIFICATION: package path exists; Memo relaunched and MainActivity resumed; Models screenshot still shows Qwen3 0.6B Installed and verified and Default; crash-buffer search returned no fatal entries
NEXT STEP: Inspect Talk after recovery to determine whether the sent prompt was persisted before the emulator crash.
NOTES: The same AVD and app data survived cold boot; ADB serial is emulator-5554.
---
---
TIMESTAMP: 2026-10-27T13:45:28Z
STEP: 79 — Inspect Talk after emulator recovery
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/recovery-talk.png (ignored); docs/PROGRESS.md
VERIFICATION: Talk reopened with Qwen3 0.6B and LiteRT-LM selected; screenshot shows empty-state suggestions and an empty composer; MainActivity resumed
NEXT STEP: Make one instrumented inference retry with app log capture; if QEMU crashes again, stop this device step.
NOTES: No user message or assistant response is visible after recovery, so the prior send/persistence outcome is indeterminate.
---
---
TIMESTAMP: 2026-10-27T13:46:48Z
STEP: 80 — Prepare controlled inference retry
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/retry-prompt.png (ignored); docs/PROGRESS.md
VERIFICATION: screenshot shows the exact unsent draft “Say hello in a short sentence.”; Talk header shows Qwen3 0.6B and LiteRT-LM
NEXT STEP: Start app-scoped log capture and send the prompt once; stop device inference work if QEMU crashes again.
NOTES: Draft remains on the clean AVD only; no second inference attempt has started yet.
---
---
TIMESTAMP: 2026-10-27T13:47:23Z
STEP: 81 — Start app-scoped inference log capture
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/inference-retry-app.log and inference-retry-adb.err.log (ignored); docs/PROGRESS.md
VERIFICATION: app.memo.debug PID 3987 identified; adb logcat reader PID 37916 remained running
NEXT STEP: Send the prepared prompt once; if the AVD exits again, stop inference retries and preserve the logs.
NOTES: Log capture is limited to the foreground Memo process and remains in ignored build output.
---
---
TIMESTAMP: 2026-10-27T13:50:07Z
STEP: 82 — Controlled Qwen3 inference retry
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/retry-response.png, retry-followup.png, inference-retry-app.log, inference-retry-adb.err.log (ignored); docs/PROGRESS.md
VERIFICATION: local prompt “Say hello in a short sentence.” produced assistant response “Hello”; screenshot at 25 seconds shows completed response; emulator-5554 stayed online; no new QEMU WER event and no Android crash-buffer entry
NEXT STEP: Test Stop during a deliberately long local generation on the verified Qwen3 model.
NOTES: App log shows LiteRT model initialization and WebGPU selecting AMD Radeon integrated graphics. A `nativeCheckLoaded()` JNI lookup error was logged before initialization, but generation succeeded. The earlier QEMU failure was not reproduced; its cause remains UNVERIFIED.
---
---
TIMESTAMP: 2026-10-27T13:51:39Z
STEP: 83 — Prepare long prompt for stop test
STATUS: partial
FILES CHANGED: app/build/chat-smoke-f665323/stop-prompt-review.png (ignored); docs/PROGRESS.md
VERIFICATION: FAIL — after the keyboard-dismiss keyevent the screenshot shows Live, not Talk; no prompt is visible and nothing was sent
NEXT STEP: Reopen Talk, recheck the composer position, and enter the long draft once.
NOTES: The previous tap did not produce a verifiable draft. No message or model state changed.
---
---
TIMESTAMP: 2026-10-27T13:52:58Z
STEP: 84 — Reopen Talk after failed draft attempt
STATUS: done
FILES CHANGED: app/build/chat-smoke-f665323/stop-talk-reopened.png (ignored); docs/PROGRESS.md
VERIFICATION: Talk shows Qwen3 0.6B and LiteRT-LM with composer visible
NEXT STEP: Inspect the visible Stop state and cancel or verify completion before starting another prompt.
NOTES: Conversation area is empty but the send control still shows Stop after the prior short response; active/stale generation state is unresolved.
---
---
TIMESTAMP: 2026-10-27T13:54:09Z
STEP: 85 — Check visible Stop control after recovery
STATUS: partial
FILES CHANGED: app/build/chat-smoke-f665323/stop-state-after-tap.png (ignored); docs/PROGRESS.md
VERIFICATION: FAIL — after one tap, the screen still shows an empty conversation and square Stop control; MainActivity remains resumed; crash-buffer search is empty
NEXT STEP: Inspect the Talk control binding and generation lifecycle to determine whether the UI state is stale before one targeted retry.
NOTES: No additional prompt was sent. The short response had already appeared before navigation; the current screen does not expose an active assistant bubble.
---

---
TIMESTAMP: 2026-10-27T14:00:17Z
STEP: 86 — Retry Talk Stop with verified bounds
STATUS: partial
FILES CHANGED: app/build/chat-smoke-f665323/stop-state-targeted-retry.png (ignored); docs/PROGRESS.md
VERIFICATION: FAIL — accessibility tree confirmed enabled clickable parent bounds [864,1921][1017,2047]; one tap at (940,1984), followed by a 4-second wait, still showed Stop response at [917,1961][964,2008]; app PID 3987 remained alive
NEXT STEP: Before another device attempt or code edit, trace why LiteRT cancellation/completion is not clearing ChatService.streaming; two Stop attempts have failed, so pause emulator retries.
NOTES: Screenshot shows the empty Talk state with Stop still visible. No prompt was sent and no source changed. This is the second failed Stop verification; stop this device step per recovery rules. The controlled Qwen3 “Hello” response remains verified independently.
---

---
TIMESTAMP: 2026-10-27T14:01:02Z
STEP: 87 — Record M1 inference and Stop evidence
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; M1 engineering note distinguishes the verified emulator inference from the unresolved Stop callback behavior
NEXT STEP: Update M1 plan/status notes to record the successful Qwen3 emulator response and leave stop cancellation plus the remaining M1 device checklist pending.
NOTES: C-012 is preserved as historical evidence; this dated note supersedes its pending-inference status without changing the earlier event record. No source changed.
---

---
TIMESTAMP: 2026-10-27T14:01:44Z
STEP: 88 — Refresh BUILD_PLAN M1.C2 status
STATUS: done
FILES CHANGED: docs/BUILD_PLAN.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; M1.C2 status now records emulator inference, unresolved Stop cancellation, and the unchanged remaining acceptance checks
NEXT STEP: Update the executable M1 task runbook with the same evidence and a cold-start resume action.
NOTES: The planned scope and ordering are unchanged; M1.C2 and M1 close remain open.
---

---
TIMESTAMP: 2026-10-27T14:02:42Z
STEP: 89 — Refresh M1 task runbook checkpoint
STATUS: done
FILES CHANGED: docs/TASKS.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; M1.C2 and M1 close both retain open status and now list the callback trace plus remaining device checks
NEXT STEP: Update FEATURES.md to reflect Qwen3 emulator inference while keeping the incomplete chat-stop acceptance visible.
NOTES: No task order, test scope, or acceptance criteria changed.
---

---
TIMESTAMP: 2026-10-27T14:03:22Z
STEP: 90 — Refresh F1/F2 evidence in FEATURES
STATUS: done
FILES CHANGED: docs/FEATURES.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; F1/F2 remain partial and state both the verified emulator response and unresolved Stop behavior
NEXT STEP: Update STATUS.md to separate verified emulator inference from incomplete cancellation and phone coverage.
NOTES: Feature scope and milestone ordering are unchanged.
---

---
TIMESTAMP: 2026-10-27T14:04:09Z
STEP: 91 — Refresh STATUS.md device evidence
STATUS: done
FILES CHANGED: STATUS.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; STATUS distinguishes Qwen3 emulator inference from unresolved Stop cancellation, import, llama.cpp and phone checks
NEXT STEP: Review the full documentation diff, confirm only intended checkpoint files changed, then prepare the approved docs checkpoint commit and CI verification.
NOTES: No source files changed; CI status reflects the previously verified passing run 40474306177.
---

---
TIMESTAMP: 2026-10-27T14:05:58Z
STEP: 92 — Clarify native runtime device coverage
STATUS: done
FILES CHANGED: STATUS.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; STATUS now separates LiteRT-LM emulator use from untested llama.cpp/sherpa paths
NEXT STEP: Complete final diff and branch-freshness checks before committing the documentation checkpoint.
NOTES: No implementation or acceptance scope changed.
---

---
TIMESTAMP: 2026-10-27T14:16:34Z
STEP: 94 — Record green CI for checkpoint commit
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: `gh run watch 40518483948` -> pass in 6m10s; build, tests, lint, APK verification, native listing and artifact upload all passed; git diff --check -> pass
NEXT STEP: Update STATUS.md to cite the fresh CI run for commit 97bc990, then commit and push this verification note.
NOTES: Workflow required manual dispatch because direct pushes to wp/chat do not trigger it. No source files changed.
---

---
TIMESTAMP: 2026-10-27T14:16:55Z
STEP: 95 — Refresh STATUS.md CI evidence
STATUS: done
FILES CHANGED: STATUS.md; docs/PROGRESS.md
VERIFICATION: git diff --check -> pass; the CI row now links run 40518483948 on commit 97bc990
NEXT STEP: Commit and push the CI verification note; confirm the worktree is clean and CI remains green.
NOTES: The run completed all checks successfully before this status edit; code and verification scope are unchanged.
---

---
TIMESTAMP: 2026-10-27T14:23:23Z
STEP: 96 — Save final CI result and resume point
STATUS: done
FILES CHANGED: docs/ENGINEERING_LOG.md; docs/PROGRESS.md
VERIFICATION: `gh run watch 40519945444` -> pass in 3m57s; tests, lint, APK verification, native listing and upload passed; git diff --check -> pass
NEXT STEP: Trace how LiteRtLmRuntime cancellation reaches onDone/onError and how ChatService.finish clears streaming; make no further emulator attempt or source edit until that path is understood.
NOTES: Both pushed documentation checkpoints have green CI. Current Stop failure is still unresolved; the planned M1 model/download/import device checklist remains open.

---

---
TIMESTAMP: 2026-10-27T17:55:00+01:00
STEP: 97 — cancellation trace complete (fulfils the STEP 96 directive)
PLAN REF: STEP 96 next-step; TASKS.md M1.C2 `~`
STATUS: done — trace only; no source edit, no emulator attempt
FILES CHANGED: docs/ENGINEERING_LOG.md (Stop/cancellation trace: path, verified engine contract, root cause, proposed micro-fix), docs/PROGRESS.md (this entry)
VERIFICATION: code trace of the whole Stop chain (ChatController → ChatService → InferenceManager → LiteRtLmRuntime → ChatService.finish) plus the upstream LiteRT-LM `Conversation.kt` contract: a cancelled generation ends in `onError(CancellationException)` (statusCode 1 = kCancelled), and `cancelProcess()` is a documented no-op when no inference is ongoing. Root cause of the emulator symptom identified at code level: a Stop during load/prefill never reaches `cancelProcess`, so the engine generates to natural completion.
NEXT STEP: lead decision on the proposed two-point micro-fix in `LiteRtLmRuntime.runOnce`/`onMessage` (post-send and on-arrival `cancelProcess`), then a single emulator probe to verify Stop latency.
NOTES: The trace answers the STEP 96 open question — ChatService does still own an active request in the failing case; the runtime never stops the engine. M1.C2 stays `~` until the fix is applied and probed.
---

---


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
---

TIMESTAMP: 2026-10-28T02:05:00+01:00
STEP: 13 - M2.B1 mini-task 1 (engine half): voice-message recorder
PLAN REF: TASKS.md M2.B1 mini-task 1 "release sends (audio + transcript)"
STATUS: engine compiles; mini-task still unticked until a surface sends a real message end to end (composer hook needs the wp/chat merge; LiveConversationScreen is B1-3). Recorder tests deferred per user.
FILES CHANGED: runtime/RecorderPorts.kt (NEW), runtime/VoiceMessageRecorder.kt (NEW), platform/android/voice/Pcm16.kt (NEW), platform/android/voice/SherpaLoopAdapters.kt (+3 adapters), docs/PROGRESS.md, docs/ENGINEERING_LOG.md
VERIFICATION: :app:compileDebugKotlin BUILD SUCCESSFUL EXIT=0. Two compile errors fixed: Int->Long on the two elapsed/duration computations.
NEXT STEP: B1-3 runtime/VoiceSession.kt (installer pins -> VoiceLoop.forDevice) + ui/voice/LiveConversationScreen.kt on the existing TALK route (PresenceOrb, partial transcript, captions, tap-to-interrupt, end, permission + model gating).
NOTES: Recorded.Untranscribed carries a nullable file: ASR failure keeps the playable WAV, store failure means neither exists - the bubble can say so honestly. Progress callbacks arrive on background threads; Compose callers write mutableStateOf directly (snapshot writes are thread-safe). First commit attempt also hit the PowerShell UTF8-BOM-in-commit-subject gotcha; Set-Content -Encoding Ascii for message files.
---

TIMESTAMP: 2026-10-28T02:40:00+01:00
STEP: 14 - M2.B1 mini-task 3: live conversation surface on the Talk tab
PLAN REF: TASKS.md M2.B1 mini-task 3 "live mode full screen: PresenceOrb driven by level, partial transcript, speaking captions, tap to interrupt, end"
STATUS: compiles + 200 unit tests green; mini-task stays unticked until a device conversation runs (deferred probes per user). The old M0 Talk placeholder (system speech recognizer) is DELETED - the TASKS check grep -rn RecognizerIntent app/src is now empty.
FILES CHANGED: runtime/VoiceSession.kt (NEW), runtime/InferenceChatRuntime.kt (NEW), ui/voice/LiveConversationScreen.kt (NEW), runtime/VoiceLoop.kt (Listener + onMicLevel/onSpeakingSentence; public interrupt(); forDevice internal), runtime/MemoRuntime.kt (appContext, one InferenceManager, lazy VoiceSession), ui/MemoRoot.kt (Talk route swap, placeholder deleted), app/src/test/.../VoiceLoopTest.kt (fake gains no-op methods), docs
VERIFICATION: :app:compileDebugKotlin BUILD SUCCESSFUL EXIT=0; :app:testDebugUnitTest 200 tests 0 failures (VoiceLoopTest 7/7); RecognizerIntent grep empty; TextToSpeech grep empty.
NEXT STEP: dispatch CI on this head and watch; then B1-4 leftovers (read-aloud + voice-message bubble + composer hooks wait for the wp/chat merge - lead-owned). Deferred-to-end list unchanged (device probes, WaveformBucketsTest/RecorderGestureStateTest, cancel words, silence timeout, echo guard).
NOTES: InferenceChatRuntime bridges the loop's raw LlmRuntime contract onto the InferenceManager FIFO, so chat UI and voice share the one resident model; load/unload/close are honest no-ops because residency belongs to the manager's owner. VoiceSession builds engines once on its own executor (installer pins: zipformer streaming + Kokoro + Silero VAD), gates Unavailable on missing chat model or missing voice models with the reason in UiState. VoiceLoop.Listener grew onMicLevel (orb) and onSpeakingSentence (caption); interrupt() = the focus-loss barge-in path via tap.
---

TIMESTAMP: 2026-10-28T03:20:00+01:00
STEP: 15 - M2.A3 mini-task 4 logic: silence timeout + cancel words (en/pl/tr) + echo guard
PLAN REF: TASKS.md M2.A3 mini-tasks 4-5
STATUS: implemented and fake-verified on the JVM; mini-tasks stay unticked until the device probe (the echo-floor VALUE and endpoint behaviour on hardware are the deferred part - floor 0.04f is UNVERIFIED). Branch fresh: milestone/M1 unchanged since the merge base (rev-list count 0).
FILES CHANGED: runtime/VoiceLoop.kt (silence window + echo-gated barge-in + cancel phrases + injectable now()), app/src/test/.../VoiceLoopTest.kt (+6 cases, rms-aware deliver), docs/PROGRESS.md, docs/ENGINEERING_LOG.md
VERIFICATION: :app:testDebugUnitTest 206 tests 0 failures (VoiceLoopTest 13/13: silence ends / silence not while talking / cancel utterance ends without LLM / content utterance still reaches the LLM / 3-language bare matching / echo floor blocks barge-in; existing 7 untouched except deliver rms default).
NEXT STEP: dispatch CI and watch; remaining M2.B1 surfaces (bubble, composer hooks, read-aloud) wait for the lead's wp/chat merge; end-of-M2 batch then probes these three on device and ticks A3 mini-tasks 4-5.
NOTES: ASR is never fed during SPEAKING, so there is no echo feedback loop into the transcript path - a cancel word spoken over the answer first barges in via VAD (energy-gated), then ends at the endpoint. 'ends or cancels' resolved as END: after a barge-in the user wants silence, and the 8 s window would end the talk anyway. Echo guard = AEC (platform, already on) + per-frame rms floor on the SPEAKING barge-in.

---


---
TIMESTAMP: 2026-10-29T14:44:02Z
CHAIN: Claude (plan) -> GPT-app Codex leg1 -> GLM leg2 (wp/voice, wp/chat) -> GLM leg3 (this session)
STEP: D - STEP D (leg-3) - merge milestone/M1 (4be0b43) into wp/voice: ONE resident model for chat + voice; Talk = voice-first combined surface
PLAN REF: TASKS.md M1.A3 (single resident model, unload on switch); BUILD_PLAN L223 (Talk: voice and text with Memo); AGENTS.md standing rule 9 (fresh head, no dead code)
STATUS: code + local tests done; branch CI pending after this commit (push + gh run watch next)
FILES CHANGED: app/src/main/kotlin/app/memo/runtime/InferenceManager.kt (+ addListener/removeListener, fan-out in publish()), app/src/main/kotlin/app/memo/runtime/ChatService.kt (shared-manager injection; close() detaches, never closes the shared manager), app/src/main/kotlin/app/memo/runtime/MemoRuntime.kt (one manager feeds chatService + VoiceSession; duplicate ModelRef import dropped), app/src/main/kotlin/app/memo/ui/MemoRoot.kt (Route.TALK -> TalkScreen: voice-first switch Voice/Text -> LiveConversationScreen | ChatScreen), app/src/main/kotlin/app/memo/platform/android/llm/RuntimeFactory.kt (DELETED - no caller), app/src/test/kotlin/app/memo/runtime/{ChatServiceTest,InferenceManagerTest}.kt, docs/PROGRESS.md + docs/ENGINEERING_LOG.md (additive resolution + renumber: voice D-038/039/040 -> D-040/041/042, voice C-009 -> C-013)
VERIFICATION: first compile found 2 merge-authored errors (duplicate ModelRef import from both legs; named-arg 'chats' where the ctor param is 'repository') - both fixed in-step; then :app:compileDebugKotlin + :app:compileDebugUnitTestKotlin BUILD SUCCESSFUL in 1m 40s with ZERO new warnings (only pre-existing ModelHub.kt:307 cast); testDebugUnitTest for InferenceManagerTest + ChatServiceTest + VoiceLoopTest + NavGraphTest BUILD SUCCESSFUL in 42s (incl. 2 new listener tests and the pinned tabOrderMatchesTheBrief)
NEXT STEP: merge commit here; push wp/voice; CI watch; then the same branch merges milestone/M1 -> CI green; APK goes to the user from the milestone run
NOTES: Additive docs resolution: chat chain (wave-1 + STEPs 41..97) kept from theirs, voice chain (STEPS 1..15) kept from ours, e188ecf's PROGRESS held only the voice chain so it adds nothing unique here (recorded to prevent re-dropping chat entries on the next merge). InferenceManager's direct state set in unloadIfIdle does NOT publish - ChatService.unloadIfIdle resets its own modelStatus, documented in the KDoc. The e188ecf-era Stop-under-load bug stays open deliberately (chat NOTES STEP 97); its two-point micro-fix is a separate micro-task. Docs repair in this merge: the voice-side ENGINEERING_LOG copy had lost the `## CONSTRAINTS` heading (C-001..C-008 sat headerless under DECISIONS); it is restored in the union to match the base/chat structure.
---
