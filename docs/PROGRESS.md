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
