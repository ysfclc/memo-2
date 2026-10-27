# Memo — Full Product Build Plan

> This file is committed to the repo as `docs/BUILD_PLAN.md`. It is the single source of truth for building Memo end to end.
> Implementation: **Haiku subagents**, one small task each. The lead (orchestrator) **verifies every task personally** before it counts as done.

---

## 0. Context

### Where we are (main @ 2b43e36, CI green, APK artifact `memo-apk`)
- **`core/` (pure Kotlin/JVM, tested):**
  - workflow DSL with 8 step kinds
  - `WorkflowEngine`: sequential; gates bound to content hash; veto semantics; bounded retry; branch rescue
  - versioned `Registry` (Created/Reused/Bumped)
  - `SynCodec` envelope (schema v1), `Redactor`, `HashChainAudit`, `CommandGrammar`, `PlanProposalParser`, `WorkflowPolicy`, `Prompts`, `ContextPacket`, `ModelCatalog` (3 pinned Qwen3), `ProviderRegistry` (8 frontier sites)
- **`app/`:**
  - `MemoRuntime`: plaintext JSON persistence, single thread
  - `Planner`: hardcoded fill→approve→save
  - `ModelStore`: one-tap pinned download with SHA verify, import, discovery
  - Compose screens: Live/Talk/Work/Studio/Models/Settings

### Known defects in core
1. `WorkflowEngine` never calls `audit.record()`, so the outbound chain is always empty.
2. Executors are synchronous (`Attempt` = Completed/Measured/Failed). Async browser/app work is impossible.
3. `ContextPacket` is never supplied by the app.
4. The `SynCodec` migration map is identity-only.

### What the product must be (user spec, condensed)
Memo is a voice-first, checkpoint-driven personal agent:
- intent (even from a locked phone) → dynamically generated typed workflow → routed steps:
  - local orchestrator
  - frontier AI via its web UI or mobile app
  - browser automation
  - app automation
  - human approval
- Artifacts are filled before approval; workflows execute end to end with checkpoints.
- Everything is measured (e.g. post engagement), and schemas/artifacts are reused and versioned.

**Use cases:**
- UC-1 locked-screen voice brainstorm into a Project with frontier consults and next-step tasks
- UC-2 CRM capture with research fill
- UC-3 job discovery with gated auto-apply
- UC-4 content draft → approve → publish → auto metric tracking
- UC-5 arbitrary plan execution (UI chores, multi-app, "play this game")

**Hard requirements:**
1. Workflow engine
2. Model router
3. Browser, app and device-wide AI discovery channels
4. Filled-artifact approvals
5. Versioned registry with migrations
6. Metrics loops
7. Encryption, redaction, outbound log, kill switch, per-channel toggles
8. Wake word / locked-screen activation
9. Android only, with a `platform/` boundary
10. One design system, motion, Live environment

### Additional user requirements (this session)
- A proper chat interface: streaming, voice messages, live conversation, model picker, message actions.
- Download **any open local model**: LiteRT-LM (`.litertlm`) **and** llama.cpp (GGUF): Llama, Gemma, Qwen, Phi, Mistral, DeepSeek, etc. Live discovery, one tap.
- **Natural voice. No Android speech pop-up and no robotic TTS.** Fully local ASR/TTS.
- Merge each green milestone to `main`.
- No zip and no YAML deliverables: the repo plus the existing CI is the deliverable.
- The reference apps (`reference/*.zip`) are **poor implementations of the goals**. They are API evidence only; never copy their structure.

### Lessons from reference recon (do not repeat)
- Fieldwork's browser injected prompts with `textContent`, which breaks ProseMirror/Quill. It shared one global cookie jar. It had English-only send/stop detection.
- Threadline's accessibility service was always-on surveillance, with no lock or wrong-app checks and no destructive filter. It also exposed the decrypted vault through a JS bridge.
- SynthOS's destructive regex was narrower than its runner's list.
- Keystore keys were not auth-bound; WebViews allowed file access.
- Model discovery was unpinned. Downloads died with the ViewModel. OOM escaped `catch (Exception)`. Mic foreground service was started at `onCreate`.

---

## 1. Verified external facts (pins)

| Item | Pin | Evidence / API notes |
|---|---|---|
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` | Google Maven lists ≤0.18.1. `Engine(EngineConfig(modelPath, backend = Backend.GPU()/Backend.CPU(), cacheDir, maxNumTokens))`; `initialize()` (do **not** `close()` an engine whose init threw); `createConversation(ConversationConfig(systemInstruction = Contents.of(s), initialMessages = listOf(Message.user/model), samplerConfig = SamplerConfig(topK, topP, temperature)))`; `sendMessageAsync(text)` → Flow of chunks (also a callback overload); `cancelProcess()`; `Engine.setNativeMinLogSeverity(LogSeverity.ERROR)`. GPU needs `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and `libvndksupport.so`. cacheDir must exist. |
| llama.cpp | tag `b11460` (2026-10-07) source tarball `https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11460.tar.gz`; record the SHA-256 at integration | CMake `FetchContent` with `URL_HASH SHA256=…`; JNI written against **that tag's** `include/llama.h` |
| sherpa-onnx | GitHub release `v1.13.8`, asset `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`, 38,691,998 B, sha256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | URL `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`. Offline ASR (streaming Zipformer, Whisper, SenseVoice, Moonshine, Parakeet), TTS (Kokoro, Piper/VITS, Matcha), Silero VAD, keyword spotter. |
| HF model API | `GET https://huggingface.co/api/models?author=litert-community&limit=200&sort=downloads`; `GET /api/models?search=<q>&filter=gguf`; `GET /api/models/{repo}` (`sha` = revision, `gated`); `GET /api/models/{repo}/tree/{sha}` → per-file `path`, `size`, `lfs.oid` (= SHA-256) | Pinned download `https://huggingface.co/{repo}/resolve/{sha}/{path}`. Gated repos need `Authorization: Bearer <HF token>`. |
| litert-community chat models (seed list) | gemma-4-E2B-it-litert-lm, gemma-4-E4B-it-litert-lm, Gemma3-1B-IT, gemma-3-270m-it, Llama-3.2-1B, Llama-3.2-3B, Qwen3-0.6B, Qwen3-1.7B, Qwen3-4B, Qwen3-4B-Instruct-2507, Qwen3-4B-Thinking-2507, Qwen3.5-0.8B/2B/4B, Qwen2.5-1.5B-Instruct, Qwen2.5-Coder-1.5B/3B, Phi-4-mini-instruct, Phi-4-mini-reasoning, DeepSeek-R1-Distill-Qwen-1.5B, Ministral-3-3B-Instruct-2512, SmolLM3-3B, SmolLM2-360M/135M, LFM2.5-1.2B-Instruct/Thinking, granite-4.0-350m-litert-lm, Falcon-H1-3B-Instruct, TinyLlama-1.1B-Chat-v1.0 | The curated list resolves sha/size from the tree API at build time and **commits pinned values** |
| Other deps | WorkManager `androidx.work:work-runtime-ktx:2.10.0`; ML Kit `com.google.mlkit:text-recognition:16.0.1`; `androidx.biometric:biometric:1.1.0`; coroutines `1.9.0`; `androidx.webkit:webkit` (latest ≥2 weeks old, verify on Google Maven); Glance `androidx.glance:glance-appwidget` (verify); `androidx.core:core-ktx:1.15.0`; Compose BOM `2024.12.01` | Every new pin is checked on Maven, must be ≥2 weeks old, and is logged in `docs/ENGINEERING_LOG.md` |
| Toolchain | Gradle 8.14.6 wrapper, AGP 8.13.0, Kotlin 2.4.21, compile/target 35, minSdk 30, JDK 17; NDK and CMake installed in CI by sdkmanager (exact versions chosen in M0.B1 and logged) | No local SDK: **CI is the compiler** |

---

## 2. Architecture

```
core/                     pure JVM: ALL policy + logic, unit-tested
  domain/ registry/ codec/  entities, versioned registry, SYN envelope + migrations
  engine/                   WorkflowEngine (+ async Pending), executors' contracts
  plan/                     grammar, parser, policy, orchestrator contract, templates
  route/                    ModelRouter, rate limits, provider health
  policy/                   destructive filter, channel switches, kill switch
  prompts/ redact/ audit/   prompts, packet builder, redaction, hash chain
  metrics/                  series queries, health, feedback packet
  platform/                 INTERFACES: LlmRuntime, SpeechIn, SpeechOut, Vault, BrowserChannel,
                            AppChannel, PackageInspector, Scheduler, Notifier, Clock
app/
  platform/android/         implementations of core/platform (desktop can add its own later)
    vault/ llm/litert/ llm/llama/ voice/ browser/ automation/ discovery/ scheduler/ notify/
  runtime/                  MemoRuntime (wires core + platform), repositories
  hub/                      model + voice-model hub (catalog, HF discovery, downloads)
  ui/                       design system (tokens, motion, components), screens
  native/                   CMake + JNI for llama.cpp
```

**Laws:**
- The app never decides policy; core does.
- Side effects only run after a completed gate whose hash matches.
- Nothing leaves the device without a redacted, audited `OutboundEvent`.
- No fake progress; no hidden listening.

---

## 2a. Automation engineering spec (browser + app + agent loop)

**Execution locus (non-negotiable).** Every automation runs **inside Memo**:
- Web work runs in Memo's own WebView profiles (never an external browser, never a remote runner, ADB or PC).
- App work runs through Memo's own `MemoAccessibilityService` on the same device.
- Agent reasoning uses Memo's in-app local model, or a frontier AI reached through Memo's WebView or that AI's app on the device.
- Nothing is delegated outside the Memo process boundary except the target website or app itself.

### Agent loop (one web/app step)
`OBSERVE → DECIDE → CHECK → ACT → VERIFY → RECORD`, repeated until the step's postcondition holds or a bound trips.

1. **OBSERVE**
   - Web: a structured DOM snapshot (visible interactive elements: role, accessible name, label, placeholder, text, test id, bounds), capped.
   - App: the accessibility tree of the target package (bounded walk).
   - Fallback: screenshot + OCR boxes.
   - Each observation gets a content hash.
2. **DECIDE**
   - Deterministic adapter rules come first.
   - Only when no rule applies does the local model pick **one action from the closed action vocabulary**, as JSON validated by core.
   - The model never emits code or free-form JS.
3. **CHECK** (core, pure). The action must pass all of:
   - (a) `ActionPolicy` (destructive or sensitive → BLOCKED unless it is the envelope's final approved action)
   - (b) the **approved action envelope**
   - (c) channel switches, the kill switch and rate limits
   - (d) the target host or package equals the envelope target
4. **ACT.** Execute exactly one action with a resolved locator.
5. **VERIFY.** The action's postcondition: field value equals the intended value, the expected element appeared or disappeared, the URL changed as expected, or the confirmation text is present. A failed verify is retried a bounded number of times **only for non-side-effect actions**.
6. **RECORD.** Append to the run journal: action, locator, before/after observation hashes, result, evidence quote or screenshot hash. Outbound sends go into the hash-chained audit.

### Approved action envelope (binds the gate to behaviour, not just content)
The HUMAN_APPROVAL artifact for any web or app side effect contains:
- the target (host or package)
- the ordered list of intended actions, with every value to be typed or submitted
- the single final commit action (e.g. "click 'Submit application'")
- the expected confirmation signal

The envelope is part of the artifact JSON, so it is covered by the content hash. At runtime the executor **refuses any action not in the envelope**: no other values, no other hosts or packages, no extra submits. A deviation, such as a new required field or an unexpected dialog, pauses the step for **takeover or re-approval** and never improvises.

### Closed action vocabulary
- **web:** `navigate(url in envelope hosts)`, `fill(locator, value)`, `select(locator, option)`, `check(locator, bool)`, `click(locator)`, `scroll(dir)`, `wait_for(locator|text, timeoutMs)`, `extract(locator|region) -> text`, `commit(locator)` (final, gated, at most once)
- **app:** `launch(pkg)`, `tap(locator)`, `type(locator, value)`, `scroll(dir)`, `swipe(path)`, `back()`, `wait_text(text, timeoutMs)`, `read(region) -> text`, `commit(locator)` (final, gated, at most once)

### Locators
A multi-strategy locator, tried in this order: test id → role + accessible name → label → placeholder → exact text → CSS/view id (adapter-provided).
- Each strategy must resolve to **exactly one** visible, enabled element.
- Zero or several matches → no action. Escalate to the next strategy, then to takeover. **Never guess, never click by coordinates on the web.**
- On apps, a coordinate tap via an OCR box is allowed only for non-commit actions when no node matches.

### Bounds and failure semantics
- Per step: ≤ N actions (adapter default 25, hard cap 60), wall-clock cap (default 120 s, thinking-model consults 300 s), and the same observation hash 3× in a row = no progress.
- Any bound tripping → the step goes to WAITING_USER (takeover) with the reason.
- `commit` is **never auto-retried**. An uncertain outcome (no confirmation signal) → NEEDS_REVIEW with evidence. Before any re-commit, an idempotency pre-check looks for an existing confirmation or submission.
- Lock screen, wrong foreground app/host, or kill switch → immediate pause; resume only after re-observing.
- Password/OTP/payment fields are never read or typed by automation. These always hand over to the human.

### Frontier consults (agent ↔ web/app AI)
These follow the same loop, using a provider adapter: `fill(composer, prompt)`, `commit(send)` (a consult send counts as an outbound side effect, gated by the run's consult approval and redaction preview), `wait_for(completion)`, then `extract(latest answer)`. Answers are DATA: they are verified with `OutcomeParser` evidence checks and never treated as instructions.

### Verification of automation code (beyond normal review)
1. **Core unit tests** (pure):
   - locator resolution over recorded DOM/a11y fixtures
   - envelope enforcement: every out-of-envelope action is refused
   - policy, bounds and no-progress detection
   - commit idempotency
   - JSON action validation
2. **Instrumented tests in CI on an Android emulator** (`reactivecircus/android-emulator-runner@v2.38.0`, API 34 x86_64 with KVM, in a separate CI job):
   - **Web:** Memo's real WebView automation drives bundled fixture pages in `app/src/androidTest/assets/fixtures/`:
     - a ProseMirror-style contenteditable chat composer with streaming answer
     - a job application form with required fields, a confirmation page and an unexpected-dialog variant
     - a social post composer
     - a metrics page
     - a page that changes its DOM mid-run, to test deviation → takeover
   - **App:** Memo's real accessibility service drives a bundled test activity in `androidTest`: tap, type, scroll, wait_text, a commit button with a confirmation, a password field that must be refused, and a wrong-app switch.
   - The tests assert the outcome **and** that refused actions were refused.
3. **Real sites and apps:** adapters are versioned registry data and are validated on device through the milestone checklist. A failing adapter pauses for takeover and never improvises.
4. **Review:** the lead reviews every automation diff line by line. Automation tasks are kept small (one concern each) so Haiku output stays reviewable.

## 2b. Inside Memo, in the background: the headless web runtime + the floating console

**User requirement:** services open *inside Memo* (as SynthOS Fieldwork did with its in-app browser), and the agent works with them **in the background without bothering the user**. A floating Memo surface lets the user act from anywhere, and it does more than capture.

### Channel priority
1. **In-Memo web runtime (primary).** Every service with a web app runs inside Memo's own WebView sessions: frontier AIs, LinkedIn/X, job boards, webmail, metrics pages. These run **headless in the background**. The user is involved only at approval gates and at takeover moments (login, captcha, 2FA, unexpected page). The router always prefers this channel when an adapter exists.
2. **Native app automation (first-class, §2c).** On stock Android, accessibility automation can only act on an app that is on screen. These steps therefore run in a **screen session**: the user starts it with one tap from the bubble or notification, the bubble console stays on top, and Memo returns itself to the front when the step ends. The planner marks such steps "needs your screen" so they are batched and never surprise the user.

### Headless web runtime (architecture)
- **`WebWorkService`:**
  - a foreground service (`dataSync` type; `specialUse` with a subtype if a run exceeds dataSync limits)
  - persistent notification "Memo is working on N tasks" with a Stop action
  - owns every automation WebView while runs are active, keeping the process alive
- **WebView host window:**
  - Automation WebViews are attached to an **invisible host window**: `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, alpha 0, fixed virtual viewport (e.g. 412×915 dp), so pages keep their layout, `requestAnimationFrame` and timers running.
  - This uses the same overlay permission as the bubble.
  - Degraded mode without overlay permission: an offscreen WebView with manual `measure`/`layout` to the virtual viewport and no `pauseTimers`. The UI warns that some sites may stall, and a stalled step goes to takeover.
- **Profiles:** one WebView profile per service (androidx.webkit multi-profile, feature-checked), so each service keeps its own cookies and session. Sessions persist across runs; login happens once, by the user, in the visible Browser tab.
- **Visible on demand:** the Browser tab (or the overlay console) **re-parents** the live WebView of a running step into the visible UI for watching or takeover, then hands it back to the host window. The run never restarts.
- **Concurrency:** at most 2 automation WebViews active at once (memory guard); the others are queued. Each run's steps stay sequential (engine law).
- **Throttling resistance:** no `onPause()`/`pauseTimers()` on automation WebViews. A watchdog detects stalled pages (no DOM change, no network) and escalates to takeover.
- **Lifecycle:** the service stops when no run needs a WebView. Kill switch, low battery, or thermal throttling → pause all and notify.
- **Security:** no JS bridge; results come only through `evaluateJavascript`. HTTPS only, Safe Browsing, no file/content access. Every outbound send is redacted and audited.

### Floating console ("Memo Overlay")
- **Collapsed:** a draggable PresenceOrb bubble (`TYPE_APPLICATION_OVERLAY`) showing live state: idle, listening, thinking, working in background (with a count), waiting for you. It edge-snaps; drag it to the dismiss target to hide it.
- **Expanded:** a panel over any app, with:
  - **Talk:** voice (sherpa, no system pop-up) and text with Memo, continuing the same conversation as the Talk tab.
  - **Act on this screen:** Memo observes the current foreground app (one-shot consent per invocation) and proposes a plan (reply, fill this form, add this person to CRM…). It runs the full §2a loop and envelope rules, with approvals in the panel.
  - **Background work:** live step rails of running workflows, including web sessions working headless; watch, take over, pause, stop.
  - **Approvals:** filled artifact + envelope, approve/veto. Side effects need the device unlocked.
  - **Quick actions:** new task, capture screen/selection to a project, start a workflow template, open Memo.
  - **Kill switch:** always visible.
- **Lifecycle and safety:**
  - started only by user action (in-app toggle, QS tile, assist session)
  - overlay permission requested with a rationale screen
  - hidden on the lock screen and in secure windows; never captures password fields
  - never taps itself (no tapjacking); approve buttons need a deliberate tap

## 2c. Native apps on the device: the SynthOS Runtime pattern, done properly

**User requirement:** Memo operates the **mobile apps installed on the phone** (not their websites), the way the SynthOS Runtime did:
1. Memo launches the target app.
2. `MemoAccessibilityService` drives it.
3. Memo's floating console (§2b) sits on top as the control and inspector surface.

The user doesn't operate anything; the agent works on its own and only surfaces approvals or takeovers. This is a **first-class channel**, used alongside the in-Memo web runtime. The router picks per step: native app when the user prefers the app or no web adapter exists, web otherwise.

**Improvements over the SynthOS Runtime** (which had: launch, tap-text, wait-text, back, delay only; English regex policy; no gestures/typing; static singletons; overlay built in the service):
- The full §2a loop and closed vocabulary: launch, tap, type, scroll, swipe, back, wait_text, read, commit. Multi-strategy locators. The envelope is bound to the approved gate.
- Guards: lock pause, wrong-app pause with auto-resume, `ActionPolicy` before every action, password/OTP fields never touched, bounded retries, UNVERIFIED unless the postcondition is observed.
- Screen state: needs the screen on and unlocked while acting. Memo batches native steps. When the phone is locked, they wait, and the console/notification says "ready to run N app steps — unlock to let Memo work". After unlock they run without further prompts unless a gate needs approval.
- Memo returns itself (or the previous app) to the front after the session.

## 2d. Human-like interaction (anti-ban)

**Requirement:** automation must behave like a person, to minimise the chance of platform bans. This is risk reduction, not a guarantee. Platform terms still apply, and Memo shows this note once in Settings. Implemented as a pure `core/automation/Humanizer` (seeded RNG, fully unit-tested) used by both the app and web executors.

- **Timing:** delays come from a log-normal distribution (not constant), with think pauses before decisions and reading pauses proportional to the visible text length (~200–300 wpm). The minimum gap between actions is never under 250 ms.
- **Typing:**
  - App: chunked `ACTION_SET_TEXT` that grows the text in 1–4 character bursts with per-character cadence (~40–90 wpm), occasional micro-pauses, and no instant full-paste for long text unless the user enables "fast mode".
  - Web: per-chunk `execCommand('insertText')` at the same cadence.
- **Touch:** taps land at a random point inside the element's bounds (biased to the centre, never on the edge), with a press duration of 40–120 ms. Swipes and scrolls use curved Bézier `dispatchGesture` paths with variable speed, slight overshoot, and natural fling lengths.
- **Navigation:** realistic scrolling while reading lists, with no teleporting jumps. Occasional idle dwell.
- **Session pacing:**
  - per-app and per-site action rate limits
  - daily caps (e.g. job applications/day, posts/day, connection requests/day), all configurable
  - optional working-hours window
  - cooldowns after errors
  - automatic backoff when a site shows rate-limit or "unusual activity" signals; those signals pause the channel and notify
- **Consistency:** one stable session per service (same profile and cookies). No parallel sessions on the same account. No automation of logins (the user logs in once).
- **Tests:** distribution bounds, monotonic typing growth, gesture path continuity, and pacing/cap enforcement.

## 2e. Full control: everything editable, workflow editor, settings, autonomy

**Requirement:** nothing is read-only. The user can create, edit, update and delete everything manually, and has full control over settings and how much the agent asks.

- **CRUD everywhere:** People, Companies, Tasks, Projects, Notes, Drafts, Posts, Metrics (manual samples), Job postings/applications, Workflow templates, Prompt templates, Principles, Content preferences, Channel accounts, Provider/app adapters (advanced).
  - Every edit goes through `Registry.upsert`: reuse-first, version bump on real change, history kept.
  - Edits are audited locally. Delete is soft (archive) with undo; purge is an explicit confirmation.
- **Workflow editor:**
  - Create a workflow from scratch or from a template, or edit a generated plan before running.
  - Add, remove and reorder steps. Edit every step field (kind, title, prompt, provider/app, envelope actions, artifact type, metric URL, retry, branches), with live validation via `PlanProposalParser`/`WorkflowPolicy`. Errors show inline per step.
  - Save as a template (versioned), duplicate, run, schedule (one-off or recurring via WorkManager).
  - The run history per workflow lets the user edit and re-run.
- **Autonomy and approval policy** (Settings → Autonomy), defaults first:
  - Per action class (read/capture, draft, persist, consult frontier AI, send message, publish, apply to job, app UI action, purchase/payment), a level:
    - *Always ask*
    - *Ask once per workflow* (one approval covers the run's envelope)
    - *Auto, summarise after*
    - *Auto, silent*
  - Defaults: read/capture/draft/persist = Auto, summarise; consult = Ask once per workflow; send/publish/apply/app UI = Always ask.
  - **Payments, passwords, OTP and account-security actions are permanently Always ask / human-only** and cannot be relaxed.
  - Auto levels still build the envelope and write the audit; "auto" means the engine delivers the gate itself with the user's standing rule recorded as the approver. That is a new `GateApprover.STANDING_RULE` value in core, so the audit shows which rule approved it.
  - Per-channel and per-provider overrides; a quiet-hours window during which nothing interrupts (approvals queue).
- **Settings** (complete, searchable):
  - Profile
  - Autonomy and approvals
  - Channels (web services, device apps, discovery toggles)
  - Human-like behaviour (speed presets, caps, working hours)
  - Models (defaults per role, per-model parameters, storage)
  - Voice (ASR/TTS models, voice, speed, wake word, sensitivity, screen-off wake)
  - Notifications
  - Floating console
  - Privacy and security (vault, app lock, redaction rules editor, outbound log, kill switch)
  - Data (backup/export/import, storage, purge)
  - Appearance (light/dark/system, density, motion reduce)
  - Advanced (adapters, diagnostics, developer log)
  - About and permissions centre

## 2f. Wake by voice with the screen off

**Requirement:** say "Hey Memo" while the phone is idle, with the screen off and locked, and Memo wakes up.

- `WakeWordService`:
  - a foreground service (`microphone` type) running the sherpa keyword spotter continuously with low-power settings (small KWS model, 16 kHz, VAD-gated)
  - holds a `PARTIAL_WAKE_LOCK` only while listening is enabled
  - started from the foreground (in-app toggle) and kept alive by the FGS
- On detection:
  - acquire a screen-on wake (`turnScreenOn` + `showWhenLocked` on `AssistActivity`)
  - play an earcon and speak through the voice loop
  - pure steps run while the phone is locked; side effects queue behind "unlock to approve" (or run under standing autonomy rules for allowed classes)
- **Honest limits** (stated in Settings):
  - Android 14+ forbids starting a microphone FGS from boot or background, so after a reboot Memo posts a notification "tap to re-arm Hey Memo".
  - Some OEMs kill background services, so Memo shows OEM-specific battery-optimisation guidance and a "don't optimise" deep link.
  - Battery cost is measured and shown in Settings.
  - Thermal and low-battery auto-pause.
- The default-assistant role (long-press power) remains the zero-battery path.

## 2g. Local agent engine + media engine

**User requirement:** upgrade the local agent for advanced work. It must be able to create content assets itself: text, images, carousels, video. The user can also route any such task to a frontier AI through the in-Memo web runtime. This **refines A6/D-016**: the local model is no longer orchestrator-only. Routing per task class is a user setting (Settings → Autonomy/Routing), defaulting to "local first, frontier when the local result fails quality checks or the task class is marked heavy".

### Local agent (tool-using)
- **`AgentEngine`** (core, pure; app supplies `LlmRuntime`) runs a bounded loop:
  1. The model receives the goal, ContextPacket and tool schemas, and replies with ONE JSON tool call or a final answer.
  2. Core validates the call against the tool's schema; invalid calls get up to 2 repairs.
  3. The tool executes.
  4. The observation is appended (capped).
  5. Bounds: ≤ 12 tool calls, token and wall-clock budgets, no-progress detection.
- Every side-effecting tool is a **workflow step** (gated or envelope-bound per §2a/§2e). The agent cannot bypass the engine.
- **Tools:**
  - `search_workspace`, `get_record`
  - `create_record` / `update_record` (gated per autonomy)
  - `draft_text`, `summarize`, `extract_json`
  - `generate_image`, `render_carousel`, `compose_video`
  - `consult_frontier` (routed via ModelRouter → web or app)
  - `browse_capture` (web runtime, read-only)
  - `run_workflow`, `schedule_workflow`
  - `ask_user` (queues a question in Live and the console)
- **Models:** the agent runs on the user's chosen local model, recommended Gemma 4 E4B / Qwen3-4B / Phi-4-mini (thinking where supported), on either runtime. Smaller models fall back to a planner-only mode.
- **Quality checks:**
  - drafts: length/format/forbidden-claims checks against ContentPreference
  - JSON: schema
  - images: safety + resolution checks
  - A failure escalates to the frontier route if the user's routing allows it.

### Media engine
- **Images:**
  1. Local: MediaPipe `com.google.mediapipe:tasks-vision-image-generator:0.10.26.1` (Stable-Diffusion-class model, downloaded through the Hub, pinned + SHA-verified; prompt, negative prompt, steps, seed). The API is verified from the AAR's classes at implementation time.
  2. Advanced local (capability-gated): litert-community diffusion pipelines (FLUX.2-klein-4B-LiteRT, Z-Image-Turbo-LiteRT) via the LiteRT runtime, following each model's published pipeline. Enabled only on devices passing a RAM/accelerator check.
  3. Frontier: image generation in ChatGPT/Gemini inside the in-Memo web runtime. The generated image is downloaded from the page as an artifact (a consult, gated by autonomy).
- **Carousels:** slides rendered locally from design tokens and templates (title, body, image slots) to PNG + PDF.
- **Video:** local MediaCodec/MediaMuxer H.264 composer: scenes (images/slides) with Ken-Burns motion, captions, transitions, and a Kokoro voice-over track mixed to AAC. Storyboards are written by the agent.
- **Gates** show the real rendered assets with target channels. Assets are stored encrypted, then exported to app-private cache only for upload.

## 2h. Languages: English, Polish, Turkish (first-class)

**User requirement:** Polish and Turkish language options alongside English, across the whole product.

- **App UI:**
  - all strings in resources: `values/` (en), `values-pl/`, `values-tr/`
  - no hard-coded UI text in Kotlin (lint `HardcodedText` + a grep check)
  - `res/xml/locales_config.xml` and an in-app language picker (Settings → Language: System / English / Polski / Türkçe), using `LocaleManager` on API 33+ and a configuration-context wrapper on API 30–32
  - plurals and date/number formatting via `Locale`
  - Turkish casing: always `Locale.ROOT` for internal keys, and the user locale only for display
- **Voice:**
  - **ASR:** multilingual Whisper (sherpa-onnx; small/base/turbo per device) for pl/tr. Live mode runs VAD-segmented Whisper with near-live partials. Streaming Zipformer is used for English where available.
  - **TTS:** Kokoro for English. Piper/VITS voices for Polish and Turkish (sherpa-onnx `vits-piper-pl_PL-*`, `vits-piper-tr_TR-*`; the most natural available voice is chosen per language, pinned + SHA-verified in the Hub). Voice picker per language.
  - The wake word "Hey Memo" works in any UI language. Custom wake phrases are allowed.
- **Commands:**
  - `CommandGrammar` gains Polish and Turkish patterns (reminders, tasks, CRM add, drafts, cancel), with locale-aware time phrases ("za 10 minut", "o 17:30", "10 dakika sonra", "saat 17:30'da").
  - Anything else goes to the local agent, which understands all three languages.
- **Agent and prompts:** the agent replies in the user's language (detected per message, overridable). Frontier consult envelopes ask for answers in the user's language. Templates and preferences are language-tagged.
- **Automation:**
  - `ActionPolicy` terms include pl/tr (plus de/es/fr).
  - Provider adapters' send/stop/busy detection and locators include pl/tr labels.
  - Humanizer reading speed is per language.
- **Tests:** string-resource parity (every key exists in all 3 locales; a unit test parses the XML), grammar tests per language, policy tests per language.

## 2i. Alive, professional, fast: motion language + performance

**User requirement:** the app must feel alive, with proper motion graphics and transitions, and be professional and fast.

### Motion principles
1. Motion explains state: something spawned, progressed, waits for you, is done.
2. Physics over curves: springs for anything the user touches, tweens only for ambient loops.
3. Fast first. Interactions respond within one frame. Transitions run 180–320 ms, never block input, and are interruptible.
4. Never fake progress: a progress animation always maps to real engine or download state.
5. Respect the system animator scale and the "reduce motion" setting, with a static fallback for every animation.

### Motion tokens (`MemoMotion`)
- **Springs:**
  - `snappy` (dampingRatio 0.85, stiffness 700): presses, toggles, chips
  - `smooth` (0.9, 380): cards, sheets, layout changes
  - `gentle` (1.0, 200): large surfaces, page transitions
  - `bouncy` (0.6, 500): only celebratory moments (approval done)
- **Durations:**
  - micro 90 ms, short 180 ms, medium 260 ms, long 360 ms
  - stagger 28 ms per item (max 9 items, then all at once)
  - ambient orb breathing 1650 ms, shimmer 1150 ms
- **Easing:** emphasized decelerate (0.05, 0.7, 0.1, 1) for entrances; emphasized accelerate (0.3, 0, 0.8, 0.15) for exits.
- **Haptics** (via `HapticFeedbackConstants`, gated by setting):
  - tick on press
  - confirm on approve
  - reject on veto
  - long-press on drag start
  - a gentle "attention" pattern when a gate opens while the app is in the foreground

### Transition catalogue (all implemented, no placeholders)
- **Navigation:**
  - `SharedTransitionLayout` + `AnimatedContent` between tabs and detail screens
  - shared-element transitions for record card → record detail, workflow card → run view, model card → model detail, and chat message → full-screen artifact
  - **predictive back** with a scale/fade preview (`PredictiveBackHandler`) on every screen and sheet
- **Lists:** `Modifier.animateItem()` for insert, remove and reorder; staggered first-appear; swipe-to-archive with spring settle and an undo snackbar.
- **Workflow spawn:** a plan card unfolds from the message that created it. Steps cascade in (stagger), each step's kind icon morphs in, and the rail line draws progressively (path trim).
- **Step progression:**
  - the running step's node pulses (scale 1.0↔1.08 plus a glow ring)
  - on completion the node morphs into a check (path morph) and the rail segment fills with an animated gradient sweep to the next node
  - failure gives a short horizontal shake plus the amber tint
  - waiting for you gives a breathing amber halo
- **Approval transitions:**
  - the gate card rises with a shared-element expansion into the full artifact view
  - approve: the card compresses into a check, flies into the run rail and the rail continues (choreographed ~420 ms)
  - veto: the card slides out with fade and desaturate
  - editing an artifact crossfades fields and re-computes the hash badge, which flips
- **Live status:**
  - PresenceOrb: a shader-based gradient blob (`RuntimeShader` on API 33+, Canvas radial gradient fallback) that reacts to state, mic level and TTS amplitude
  - counters tick with animated digit rolls
  - sparklines draw in
  - the "since you were away" digest stacks cards in
- **Chat:**
  - streaming text appears per token with a soft caret, and no layout jumps (text measured incrementally)
  - code blocks fade in when closed
  - voice-message waveform animates live while recording, then collapses into a playable bubble
  - live-conversation mode morphs from the composer mic button into a full-screen orb (shared bounds)
- **Models and downloads:** a progress ring with real bytes, and on finish the ring morphs into an "installed" badge. Skeleton shimmer while catalogs load.
- **Floating console:** the bubble expands into the panel with a container transform from the bubble's bounds, and collapses back. Drag uses fling physics and edge snapping.
- **Sheets and dialogs:** spring-driven bottom sheets with scrim fade; never instant pop-ins.

### Performance (fast app)
- **Targets:**
  - cold start < 900 ms to first frame on a mid-range phone; warm start < 300 ms
  - 0 janky frames in scroll, navigation and streaming benchmarks (≥ 60/120 fps)
  - typing latency < 16 ms
- **Engineering:**
  - heavy work never on the main thread (inference, crypto, file I/O, JSON parse of large blobs)
  - Compose stability: immutable UI models, `@Immutable`/`@Stable`, `derivedStateOf`, keys on lazy lists
  - no allocation in draw lambdas
  - images decoded at display size
  - lazy model and catalog loading
  - startup work deferred via `androidx.startup` and lazy singletons
- **Release build:**
  - a **release variant**: R8 full mode, resource shrinking, `debuggable=false`, with keep rules for LiteRT-LM, sherpa-onnx, llama JNI and ML Kit
  - signed with a **persistent release key** held in GitHub Actions secrets (`MEMO_KEYSTORE_B64`, `MEMO_KEYSTORE_PASSWORD`, `MEMO_KEY_ALIAS`, `MEMO_KEY_PASSWORD`), so updates install over each other
  - CI uploads `memo-release-apk`, which becomes the APK the user installs; the debug APK stays for diagnostics
- **Baseline profiles:**
  - `androidx.baselineprofile` + a macrobenchmark module generated on the CI emulator, covering startup, tab switching, chat streaming and list scroll
  - `androidx.profileinstaller` in the app
- **Benchmarks in CI** (emulator): startup and frame-timing macrobenchmarks, with results posted to the run summary and regressions flagged.

## 2j. Everyday product completeness

These are the capabilities a full product needs that the spec implied but didn't list, plus everything found in the reference apps (cross-checked in `docs/FEATURES.md` §R).

### Notifications, reminders, timers, alarms
- **Reminders:**
  - exact alarms (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` with a rationale flow, falling back to inexact with a visible note)
  - one-off and recurring (daily, weekdays, weekly, custom RRULE subset)
  - snooze (5/10/30 min, custom); actions Done / Snooze / Open
  - re-armed after boot (`BOOT_COMPLETED`) and after time or zone changes
  - lock-screen privacy: content hidden unless the user allows it
- **Timers and stopwatches:** by voice ("timer 10 minutes" in en/pl/tr) or UI; foreground countdown notification with a live chronometer; alarm sound with full-screen intent on the lock screen (`USE_FULL_SCREEN_INTENT`); multiple concurrent timers.
- **Alarms:** wake-up alarms with sound and vibration, dismiss/snooze (full-screen).
- **Notification channels** (user-configurable): Reminders, Timers & alarms, Approvals, Takeover needed, Background work, Downloads, Digest, Wake word.
- **Approvals and takeovers** notify with deep links. **Daily digest** and **"since you were away"** are notifications too.
- **Quiet hours and nudge caps:** at most N interruptions per hour (configurable); approvals queue during quiet hours, except alarms and timers.
- Reminders and timers are also exposed to the agent and workflows as a `notify` step via `Scheduler`/`Notifier`.

### Capture into Memo from anywhere
- **Share target:** `SEND` / `SEND_MULTIPLE` for text, URLs, images, PDFs and files (bounded sizes). The user picks: chat about it, add to project, CRM, task, or source.
- **"Ask Memo" on selected text** (`PROCESS_TEXT`) in any app.
- **Camera capture:**
  - business-card or badge scan → OCR → filled Person/Company → gate (UC-2 fast path)
  - document/whiteboard photo → note
- **Clipboard helper** in the console (explicit tap only; never background clipboard reads).
- **Share out:** export a draft, note or artifact (text, PDF, image, MP4) through the Android share sheet.

### Knowledge: Sources, Memory, Search, Today
- **Sources:**
  - import files and notes (SAF document picker, user-chosen documents; this is not used for AI discovery)
  - web pages captured in the in-Memo browser
  - chat transcripts
  - consult answers
  - all with provenance, deduplicated by content hash, chunked and indexed
- **Memory graph:** a visual graph of linked records (people ↔ companies ↔ projects ↔ notes ↔ jobs ↔ posts) with an explanation of each link ("from source X, quote Y"). Facts, inferences and sources are kept distinct.
- **Global search:** across every registry, chats, sources and runs. On-device full-text index, plus semantic search with an embedding model (EmbeddingGemma via LiteRT-LM, from the Hub).
- **Today:** the one thing to move now; due tasks and reminders; waiting approvals; running work; yesterday's outputs; metric movements.
- **Briefings:** a morning briefing (spoken or as a notification).
- **Driving mode:** voice-only, large UI, no visual approvals; side effects queue.

### Multi-AI collaboration modes
Council modes in UC-1/UC-5 consults:
- **compare** (independent answers)
- **critique & refine** (chained: first answer → skeptic → refiner)
- **converge** (proposal → critique → reconcile)

The local agent synthesises an agreement/disagreement map. **Entities are extracted from answers only when backed by verbatim evidence** (`OutcomeParser`), then reuse-first into the registry.

### Optional API channel (opt-in)
For users who *do* have keys, an `API_OPTIN` channel supports OpenAI-compatible endpoints and Anthropic/Gemini official APIs (keys in the vault). Requirements: HTTPS only, explicit per-run cloud consent, redaction plus audit, and never the default. The router treats it as another frontier target.

### App capability map
When Memo operates a device app (§2c), it records an **AppCapability** profile: screens seen, actions that worked, field locators, versioned. Future runs reuse it (reuse-first). The user can view and edit it in Settings → Channels.

### Onboarding, help, updates
- **First run:**
  1. language
  2. what Memo is (short motion intro)
  3. permissions centre (each with its rationale; skippable)
  4. pick and download a local model (recommended for this phone)
  5. voice setup (download ASR/TTS for the chosen language, voice sample)
  6. wake word opt-in
  7. floating console opt-in
  8. optional sign-in to services in the in-Memo browser
- **Help:** contextual tips plus a "What can Memo do?" gallery of ready workflow templates.
- **In-app updates:**
  - Memo checks the repo's latest GitHub **release** for a newer signed APK (CI publishes releases on `v*` tags)
  - it shows the changelog, downloads, verifies the SHA-256 and signer, and installs via `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`)
  - the update check is opt-in, with no telemetry

### Memo's own accessibility and quality
- TalkBack labels, a 48 dp minimum touch target, font scaling up to 200%, contrast-checked colours, and RTL-safe layouts.
- Every screen has empty, loading, error and offline states. Errors are human-readable with a recovery action.
- Contacts import into the CRM (`READ_CONTACTS`, opt-in) and an optional place tag for "met at P" (coarse location, opt-in).

## 2k. Capability Compiler: assemble the competence a task needs, at the moment it needs it

**Idea (user):** before an AI executes a job, the machine works out what the job needs: instructions, persona, skills, knowledge, data, experience, tools, constraints, and how success is judged. It then **stitches those components together on demand** into a task-specific execution package. Memo does not keep a zoo of fixed agents. It keeps a **library of typed, versioned capability components** and a **compiler** that assembles them per task. The executing model (local, or frontier via web/app/API) becomes interchangeable.

### Pipeline (core, pure; models plug in through `LlmRuntime` / ModelRouter)
`Objective → TaskSpec → RequirementGraph → Assembly → Readiness → ExecutionPackage → Execute → Evaluate → Diagnose → Repair | Finish → Learn`

1. **TaskSpec.** Turn the user intent into: objective, success definition, constraints, deliverables, deadline, risk class, and language. Produced by the **task architect** (an LLM role, routed per §2g; it may ask a second AI to propose the spec, then a different AI critiques it: the user's "ask another AI for the prompt" loop). The output is strict JSON validated by core.
2. **RequirementGraph.** A typed DAG of needs. Node kinds: `knowledge`, `skill/procedure`, `data`, `tool`, `persona`, `constraint`, `evaluator`, `experience`, `human_input`. Requirements can spawn sub-requirements; expansion is bounded by depth ≤ 4 and ≤ 40 nodes. Each node is critical or optional and carries an uncertainty note.
3. **Assembly (linker, deterministic).** Match nodes against the **Capability Library** by declared `provides` / `requires` / `compatibleWith` / `conflictsWith`, language, and trust level. It resolves dependencies, removes conflicts, and checks permissions against `ChannelSwitches` and autonomy. Unmatched nodes can be resolved by:
   - (a) adapting a near-match component (creating a new *draft* version)
   - (b) generating a candidate component with an LLM, which enters as `UNTRUSTED`
   - (c) asking the user (`ask_user`)
   - (d) declaring a **capability gap**: never pretending to have access or data it lacks
4. **Readiness validator** (deterministic): every critical node is covered, required tools are available and permitted, the evaluator can actually test the deliverable, and the token/time/cost budget fits. If not ready, the run reports exactly what is missing.
5. **ExecutionPackage.** A structured manifest: persona, procedure steps, knowledge excerpts (loaded lazily, packet-budgeted), data bindings, tool list with permissions, constraints, evaluator spec, chosen executor(s) per stage, and budgets. It is **rendered** into the actual prompt for the chosen model and size (compact for small local models, fuller for frontier). Rendering is deterministic from the manifest.
6. **Execute** through the existing engine: the package becomes a `WorkflowSpec` (REASON / agent tool loop / web / app / gates), so all gates, envelopes, audit and humanizer rules apply unchanged.
7. **Evaluate.** Evaluator components run: schema checks, evidence checks (`OutcomeParser`), rubric scoring by a *different* model than the executor where routing allows, deterministic tests, and user feedback.
8. **Diagnose + repair.** Classify failures (missing knowledge, bad procedure, broken tool, insufficient evidence, executor weakness, ambiguous spec). Repair **only the responsible component(s)**, then re-run, within a hard budget (≤ 2 repair cycles, ≤ N tokens). Never append prompt text endlessly.
9. **Learn.** Record an **Experience** row: task class, components and versions used, executor, outcome scores, cost, failure modes. Component reliability stats update per (component, model, task class). Components are promoted `UNTRUSTED → TRIAL → TRUSTED` only on evidence (N successful evaluated uses), never because an AI wrote them.

### Capability Library (registry kinds, versioned, reuse-first)
- `Persona`: role, perspective, tone, language
- `Skill`: procedure steps, preconditions, outputs (extends the PromptTemplate idea)
- `KnowledgePack`: domain notes and references, with sources
- `DataBinding`: a query over Memo registries or sources, or required user input
- `ToolSpec`: maps to the closed action vocabulary / agent tools, with required permissions
- `Constraint`
- `Evaluator`: schema, rubric, tests, required evidence
- `Experience`: outcome records
- `ArchitecturalPrinciple` (exists)

Every component has `provides`, `requires`, `compatibleWith`, `conflictsWith`, `languages`, `trust`, `stats`, and a semver. Components are **editable by the user** (§2e), and the user can pin or ban components.

### Seed library
Shipped components cover the core use cases (all `TRUSTED` after tests): research, CRM enrichment, job application, CV tailoring, content drafting, metric reading, brainstorm council, web form filling, app chores, plus evaluators for each.

### UI ("Compiler" view inside a run)
- the TaskSpec
- the requirement graph, animated as it expands
- the components chosen and why, with their trust badges
- gaps and questions
- the rendered prompt (inspectable)
- evaluation results and repairs

Users can edit the spec or graph and recompile.

### Safety
AI interprets; deterministic code enforces schemas, permissions, budgets, trust levels and validation. Untrusted components can never authorise side effects, and they are sandboxed to read-only tools until promoted.

## 2l. The Self Model: representing the user, and generating artifacts from it

**Requirement (user):** Memo must represent the user as a person, built from their website, LinkedIn, CVs and other material, and keep it in sync both ways. From it Memo generates **personalised CVs and other artifacts**, with proper tools. The key use case is applying for jobs with a CV tailored to each one.

### Self Model (encrypted, user-owned, versioned)
- **Sections:** identity (name, headline, locations, languages, contact), summary/positioning variants, experience (roles, dates, organisations, responsibilities, **achievements with metrics**), projects, education, certifications, skills (with evidence and proficiency), publications/talks, awards, volunteering, references (opt-in), preferences (target roles, industries, locations, salary band, remote/hybrid), voice and tone samples, and the visual brand (colours, fonts, photo).
- **Every fact carries provenance:** the source (website URL, LinkedIn section, CV file, user entry), a capture date, and a confidence level. Conflicting values from different sources are surfaced for the user to resolve; they are never silently merged.

### Ingestion (all inside Memo)
- **Website:** captured via the in-Memo web runtime (read-only), parsed into sections.
- **LinkedIn:** the user's own profile, viewed in the in-Memo web runtime under the user's logged-in session (read-only capture of their own sections), or imported from LinkedIn's official data export archive (ZIP via the document picker).
- **CVs and documents:** PDF, DOCX or text, imported via the picker and parsed (on-device; OCR fallback for scanned PDFs).
- **Manual editing** of everything (§2e).
- Re-ingestion produces a **diff**: new, changed and conflicting facts are presented for approval before the Self Model updates (a HUMAN_APPROVAL gate with the diff as the artifact).

### Vice versa (outbound sync)
Memo proposes updates *to* the website or LinkedIn from the Self Model (e.g. a new role or achievement). The proposal shows the exact text per field as an envelope-bound side effect, is approved, and is then applied via the web runtime or the LinkedIn app (§2c/§2d humanized). It never runs automatically unless the autonomy settings allow it, and profile edits are Always ask by default.

### Artifact Studio (tools)
- **CV generator:**
  - templates (ATS-safe single column, modern two-column, academic, Europass-style)
  - languages en/pl/tr
  - length targets (1 or 2 pages)
  - export to **PDF** (on-device renderer), **DOCX** (OOXML writer), plain text, and JSON Resume
- **Per-job tailoring** (Capability Compiler task class `cv_tailoring`):
  1. Parse the job posting into requirements.
  2. Map them to Self Model evidence and score the fit.
  3. Select and reorder experience and achievements.
  4. Rewrite bullets in the posting's language and keywords **using only facts present in the Self Model**. Every bullet links to its source facts.
  5. Flag gaps honestly ("no evidence for Kubernetes").
  6. Run an ATS keyword-coverage check.
  7. Show a diff against the base CV.
- **Cover letters, bios, LinkedIn headline/about variants, portfolio one-pagers, intro emails**, all generated with the same evidence discipline.
- **Fabrication guard (evaluator):** an evaluator rejects any claim, date, metric, employer or skill that is not in the Self Model. The user must add a fact to the Self Model before it can appear.
- **Versioning:** every generated CV and letter is a versioned artifact linked to its JobPosting and JobApplication. UC-3 gates show the job, the tailored CV, the cover letter and the exact form fields.
- **Editing:** a rich editor for generated artifacts. Edits can be written back to the Self Model as new facts (with confirmation).

## 2m. Teach & Select: DOM and app-element selection tools for workflow creation, plus versioned, regenerable automation schemas

**Requirement (user):** workflow creation needs proper DOM and element selection tools. What the user selects or demonstrates becomes **versioned schemas** that are reused, and that the **local AI can regenerate** when a site or app changes.

### Artifacts (registry kinds, semver, reuse-first, user-editable)
- **`ExtractionSchema`:**
  - target entity schema (e.g. `job_posting`, `person`, `metric`, or a custom schema)
  - field rules: field → locator set (multi-strategy, §2a), transform (trim, number parse via `OutcomeParser`, date parse, URL absolutise, regex capture), required flag, type validation
  - list mode: container locator + item locator, so one selected item generalises to its repeating siblings, with pagination ("next" locator or scroll-to-load)
  - sample values (for validation and self-healing)
  - DOM or a11y **structural fingerprint**
  - target host or package
- **`ActionRecipe`:**
  - an ordered list of steps in the closed action vocabulary (§2a), each with a locator set, **parameters** (`{{cover_letter}}`, `{{cv_file}}`, `{{post_text}}`), wait conditions and postconditions
  - the commit step marked explicitly
  - target host or package, and preconditions (logged in, on page X)
- **`CustomSchema`:** user-defined entity schemas (fields, types, required), so the user can extract things Memo didn't ship with. They are versioned, and migrations are generated when fields change.

All are linked from `ProviderAdapter` / `AppCapability` and usable from any workflow ("Run recipe", "Extract with schema") and from the Capability Compiler as `ToolSpec`/`DataBinding` components.

### Web element picker (in the in-Memo browser)
- **Inspect mode toggle.** An injected, isolated picker script highlights the element under the finger with a box, label and size. The selection is made from a **visual tap plus a breadcrumb** (ancestors), with expand/shrink-selection controls (parent/child) and "select similar" for lists.
- **Locator generation:** produces a multi-strategy locator set (test id, role + accessible name, label, placeholder, text, stable attributes, CSS path). Each candidate is scored for stability. Unstable signals (generated class names, positional indices) are penalised. A **live match count** shows "1 match" (green) or "N matches" (list), and ambiguous single-target locators are rejected.
- **Field mapping panel:** choose a schema field for the selection, see the extracted value preview live, and pick a transform. In list mode, every item is previewed in a table.
- **Shadow DOM and iframes:** the picker traverses open shadow roots and same-origin iframes. Cross-origin iframes are reported as not selectable; there is no workaround hack.
- **Secure:** the picker never captures password field values, and it is removed when inspect mode ends. Picker scripts are data-driven, with no `eval` of model output.

### App element picker (device apps, §2c)
- In a native-app session, an **overlay inspector** draws the accessibility node bounds of the target app. Tap a node to see resource-id, text, content-description, class, clickable/editable flags and its ancestor chain. Selection works the same way (expand/shrink, similar siblings).
- Generates the app locator set (view id → text/desc → class + position-within-parent as a last resort) with a live match count.
- Password or secure fields are shown as "protected" with no value.

### "Show Memo" recorder (teach by demonstration)
- In the in-Memo browser or in a native-app session, the user performs the task once. Memo records the actions (click, input, select, navigation, scroll) as recipe steps, with locator sets resolved at record time. Typed values become **parameters**, with a suggested name and the value as an example.
- The **local AI generalises the recording:**
  - it names steps
  - it detects the commit action
  - it proposes waits and postconditions
  - it parameterises values
  - it removes noise (accidental taps)
- The user reviews and edits everything in the workflow editor, then saves the recipe as a version.

### Self-healing and regeneration (local AI)
1. When a locator fails at runtime, or a structural fingerprint changes, the step pauses. **The commit action is never retried silently.**
2. The **regenerator** gives the local agent the stored schema or recipe step (field meaning, sample values, previous locators, fingerprint) plus a fresh observation. The agent proposes new locator candidates.
3. Core validates the candidates deterministically: match count, the extracted value type-checks against the field, and the samples still parse.
4. A passing set becomes a new **patch version** with `TRIAL` trust. It applies automatically for read-only extraction steps if autonomy allows. Action and commit steps need user approval of the new version.
5. Every regeneration is recorded as Experience. A repeatedly failing schema is flagged for re-teaching.
6. When a site or app changes too much, Memo asks the user to re-select only the broken fields, not to rebuild the workflow.

### Workflow editor integration (§2e)
- In any web or app step: "Pick on page" / "Pick in app" opens the picker in context and returns the locator set to the step.
- "Extract…" opens the schema builder.
- "Record…" starts Show Memo.
- Steps show their recipe or schema version, trust badge and last success, and can be upgraded or pinned to a version.

## 2n. Council: role-based debate between local models and frontier AIs

User requirement (2026-10-26): chats with local AIs are not one model at a time, and not only local models talking to each other. They are discussions between local models **and** frontier AIs reached through the in-Memo web runtime (§2b) or device apps (§2c). Every participant gets a **relevant role**, and the discussion is a real debate.

### Participants
- **Seats:** a participant is a seat `{seatId, target, role, persona, stance?}`. `target` is a local model (LiteRT-LM or GGUF), a frontier via web (provider id), a frontier via app (package), or the optional API channel (§2j). The user is always a seat and can speak at any time.
- **Role library** (a versioned registry kind `CouncilRole`, user-editable, reuse-first):
  - Moderator: runs the agenda and keeps time. It is the local model by default, so control stays on the device.
  - Proposer
  - Domain expert (persona generated for the topic by the Capability Compiler, §2k)
  - Skeptic / red team
  - Devil's advocate (argues an assigned stance)
  - Fact-checker (must cite sources or verbatim evidence)
  - User advocate (uses the Self Model, §2l; local only)
  - Synthesizer / judge
- **Role assignment by relevance:**
  1. The Moderator reads the topic and builds a TaskSpec (§2k).
  2. It proposes a seat plan: which roles the topic needs, and which target fills each role. Targets are scored by ModelRouter role affinity, health, rate limits and the privacy class of the content.
  3. Private data (Self Model, vault, CRM) is only given to local seats. Frontier seats get the redacted, audited packet only.
  4. The user can accept the seat plan or edit any seat (add, remove, swap model, change role or stance) before the debate starts and between rounds.

### Debate protocol (pure state machine in core, `Council`)
- **Formats:** `compare` (independent answers), `critique_refine` (chain), `converge` (propose → critique → reconcile), `debate` (opening → rebuttal rounds → closing → judgment), `round_table` (moderated turns, free order), `red_team` (attack a plan; the defender responds).
- **Rounds:** each round has an agenda item, a turn order, and a speaking budget per seat (tokens and wall time).
  - Every turn sees the transcript so far, filtered to what that seat may see.
  - A seat must address the previous claims by id. Claims are tagged with ids such as `C3`; rebuttals reference them (`↳ C3`).
- **Claims ledger:** every turn is parsed into claims `{id, seat, text, kind: assertion|rebuttal|concession|question|evidence, refs[], evidence?}`.
  - A fact-checker or judge can mark a claim `supported`, `contested`, `refuted` or `unsupported`.
  - Entities and facts enter registries only with verbatim evidence (`OutcomeParser`, §2j).
- **Stopping rules:**
  - consensus reached (the judge sees no open contested claims)
  - max rounds
  - budget exhausted
  - no-progress detection (the same claims repeat)
  - the user ends the debate
- **Output:** the Synthesizer produces a `CouncilOutcome`:
  - decision or recommendation
  - agreement/disagreement map
  - open questions
  - minority positions kept explicitly, never averaged away
  - cited claims
  - proposed next steps (gated as Tasks)
- **Anti-sycophancy:**
  - Opening answers are generated blind: seats don't see each other's openings.
  - The devil's advocate's stance is fixed by the moderator.
  - The judge is a different model from the proposer when routing allows.
  - Frontier answers are labelled with their source and never presented as Memo's own.

### Execution
- Each frontier turn is an async step: web turns go through the `FrontierExecutor` (§2a/§2b, headless, in the background); app turns go through a native-app session (§2c, batched into one screen session).
- They use `Attempt.Pending` + `deliverExternal`. Every outbound prompt is redacted and audited, and needs consent per run (a standing rule can allow it, §2e).
- Local turns stream through `InferenceManager`, one model resident at a time. The orchestrator orders turns to minimise model swaps. Turns that don't depend on each other run in parallel: local and web at the same time.
- A failed or blocked seat (login wall, rate limit, CAPTCHA → takeover) is marked absent for that round. The debate continues and says so.

### UI ("Council" in Talk)
- A group thread. Each seat has an avatar, a model badge (local/web/app) and a role chip. Seats speak with their own streaming bubble and a subtle per-seat accent from the token palette.
- Above the thread:
  - the seat plan sheet (drag roles onto models)
  - a round indicator
  - the agenda
- Claim ids are tappable chips; a rebuttal draws a link to its target claim.
- **Live claims ledger panel:** supported, contested and refuted, with the evidence.
- The user can:
  - interject, pause, skip a seat, or ask a specific seat
  - "steelman X"
  - end the debate early and get the synthesis
- **Voice mode:** each seat speaks with a distinct Kokoro voice, and the user can barge in (§2f/M2.A3).
- **Outcome card:** agreement map, minority report, next steps (Run as workflow / Save to Project).
- Saved councils can be re-run with updated models (versioned `CouncilSession` + `CouncilTemplate`).

## 2o. Live view: watch the AI work in real time

User requirement (2026-10-26): "I want to see the real state on the screen in those sessions. Every time the AI does something (e.g. I say 'go to web'), it shows me in real time what the AI does."

**Law:**
- Every automation session (web, app, or a council seat) is **watchable live**, and showing the real state is the default.
- Background mode (§2b) is something the user opts into for a run or a standing rule. It never hides anything: a live preview is always one tap away.

### What "live" means
- **Real pixels, not a reconstruction.** The live view shows the actual WebView the agent is driving, or the actual app on screen. There are no mock-ups or summaries pretending to be the page.
- **Web sessions:**
  - When a run is started by the user (voice or chat: "go to the web", "check LinkedIn"), Memo opens the **Live Session view** in the foreground, and the agent's WebView is attached there, visible and interactive. This is the §2b re-parenting, without a restart.
  - When the user leaves, the WebView moves back to the headless host and keeps going.
  - While headless, a **live preview** is drawn from the real WebView at ≤ 4 fps (render to a bitmap on the host, downscaled) and shown in:
    - the bubble / Memo Overlay (§2b)
    - the Live tab run card
    - the council seat tile (§2n)
    - the ongoing notification (thumbnail updated ≤ 1/s)
- **App sessions (§2c):** the app is on screen by definition. Memo's console HUD sits on top.
- **Council:** every frontier seat has a live tile of its page while it types and the answer streams. Tapping the tile opens it full screen.

### Action visualisation (every action, as it happens)
- **Ghost cursor/finger:** before each action, an animated pointer moves to the target and the element gets a highlight ring:
  - web: from `getBoundingClientRect` via `evaluateJavascript`, drawn as a native overlay above the WebView, not injected into the page
  - app: from the node bounds, drawn in the overlay window
  - The ring stays for the action's duration. It shows a tap ripple, typing happens visibly character by character (the humanizer's cadence, §2d), and scrolls show their movement.
- **Narration strip:** a one-line caption, live, in the user's language (en/pl/tr):
  - "Opening linkedin.com" → "Typing 'product manager' in Search" → "Reading 24 results" → "Waiting for your approval to apply"
  - It is generated deterministically from the action envelope (`ActionNarrator`, pure core), never by the model. What it says is exactly what happens.
  - It is optionally spoken in voice mode.
- **Step timeline:** a scrubbable list of every action, with:
  - its narration line
  - a before/after thumbnail (kept only for the run, encrypted, auto-purged per settings)
  - the locator used
  - its result
  - Tapping an entry shows the snapshot.
- **State badge:** Navigating, Reading, Typing, Waiting for page, Waiting for you, Paused, Taken over, Done, Failed. Each state has a distinct motion (§2i).

### Control from the live view
- Pause / resume; step mode (approve each action); take over (the user drives, then hands back with "continue from here"); stop. The kill switch is always visible.
- The user's own touches in the live view pause the agent automatically. The agent never fights the user.
- Approvals appear inline in the live view, at the exact moment the agent reaches a gate.

### Privacy
- Password fields, payment fields and OTP fields are masked in every thumbnail and snapshot. The live view itself shows the real page, because the user is the owner.
- Snapshots are never sent off the device, and they are purged when the run's retention ends.
- `FLAG_SECURE` windows are never captured.

### Performance
- Preview capture is throttled (≤ 4 fps when visible, 0 fps when no preview is on screen) and done off the main thread.
- Live view attach/detach takes < 150 ms, without reloading the page.

## 2p. Professional UX layer: favourites, saved tabs, pins, recents, fast navigation

User requirement (2026-10-26): "a professional UI/UX enhancement where necessary, e.g. saved tabs, favorite apps and so on."

**Principles:**
- Memo remembers where you were and what you use.
- Everything frequent is one tap away.
- Every list can be pinned, reordered, searched and multi-selected.
- Nothing is lost: undo everywhere, and state is restored after process death.

### Navigation shell (replaces the 7-tab bar)
- The bottom bar has **4 user-chosen primary destinations + More**. The defaults are Live · Talk · Work · Browser.
  - The user reorders or swaps destinations with long press → drag, or in Settings → Navigation.
  - On tablets and foldables (WindowSizeClass medium and expanded) the bar becomes a navigation rail or a permanent drawer showing every destination, with list–detail panes.
- **Command palette / universal search:** pull down on any screen, or tap the search pill. It searches:
  - destinations, settings and actions ("new reminder", "start council")
  - records, chats, runs, sources and models
  - recent items
  - It runs typed commands through CommandGrammar (en/pl/tr).
- **State restoration:** every screen keeps its scroll position, open sheet, filters and drafts across tab switches and process death (`rememberSaveable` + a small encrypted `UiStateStore`). On launch Memo resumes the last screen, unless a run needs attention; in that case it opens that run.

### Favourites and pins (one model, everywhere)
`Favorites` is a registry kind: `{kind, targetId, label, icon, order, pinnedAt}`. It is versioned, user-editable and synced into the command palette. Favourites cover:
- **Favourite apps:** device apps Memo can drive (§2c) or open inside a session.
  - Shown as an app dock in Live, in the Memo Overlay (§2b) and in the workflow editor's "Pick in app".
  - Each one shows its capability badge from the AppCapability map, its last-used time and its sign-in state.
  - Long-press actions: Act on it, Open in session, Capability map, Unpin.
- **Favourite web destinations:** bookmarks with folders, tags and per-profile assignment. They're shown in the Browser start page and the palette.
- **Favourite models:** quick switch in the chat model picker.
- **Favourite prompts and workflows:** shown on the Talk empty state and in the palette, and each can be run with voice ("run my weekly review").
- **Favourite contacts, projects and councils:** pinned to the top of their lists.

### Saved tabs and sessions (Browser, §2b/§2o)
- **Tabs per profile:** a live tab strip, plus a tab overview grid with live thumbnails. Tabs can be dragged to reorder.
- **Saved tab groups:** "Job search", "Content research". Each group can be saved, renamed, reopened in one tap, shared with a workflow as its starting context, and auto-restored after a restart.
- **Recently closed:** a list with restore and undo.
- **History:** per profile and searchable, with a one-tap clear. Incognito-style ephemeral profile: no cookies or history persisted.
- **Reading list / capture:** any page can be saved as a Source (§2j) with one tap.
- Agent sessions appear as tabs with a "Memo is working" badge. The user can watch any of them, which opens the live view (§2o).

### Recents and continuity
- A "Jump back in" row on Live: the last chats, records, runs, tabs and apps.
- **Per-entity recents:** the last 10 people, companies, projects and so on, at the top of pickers.
- **Drafts never lost:** composer and editor drafts autosave, encrypted.

### Interaction quality
- **Lists:**
  - swipe actions (archive, pin, done), each with undo
  - long-press context menus
  - multi-select with bulk actions
  - sticky section headers and fast scroll
  - pull to refresh where data is remote
- **States:** skeleton loading, never blank; meaningful empty states with one primary action; inline errors with retry; an offline banner.
- **Feedback:** haptics on commit actions. Snackbars with undo for every destructive or bulk action; a confirm dialog only for irreversible actions.
- **Customisation:**
  - theme (system, light, dark, AMOLED black)
  - accent choice from the token palette
  - density (comfortable or compact)
  - font scale preview
  - Live dashboard cards that can be reordered and hidden
- **Gestures and shortcuts:**
  - edge swipe between adjacent primary destinations: optional, off by default
  - keyboard shortcuts on tablets and ChromeOS (Ctrl+K palette, Ctrl+N new chat)
  - app shortcuts (launcher long press): New chat, Voice, Capture, Today
- **Accessibility:** every custom component has semantics, 48 dp targets, a visible focus ring and TalkBack labels (M6.10).

## 2q. Data, automations, trackers, analytics and improvement paths

User requirement (2026-10-26): "data, and ability to create automations, e.g. to track LinkedIn activity, browse social media, start a session with X app or web; more importantly their analytics and data; improvement paths and all."

### Pipeline
Memo turns what it sees (web, apps, exports, manual entry) into **owned, versioned, analysable data**. It then turns that data into **insights → goals → experiments → plans**:

```
source (web/app session, official export, notification, manual)
  → Tracker (what to observe, how often, with which ExtractionSchema §2m)
  → Observation rows (typed, provenance + evidence snapshot)
  → Dataset (versioned schema, time-series + tables, encrypted, on-device)
  → Analytics (metrics, trends, anomalies, comparisons, attribution)
  → Insights (evidence-cited, local AI; council review for big calls §2n)
  → Goals + Experiments + Improvement plan (gated tasks/workflows)
  → measured again (closed loop)
```

### Automations (user-creatable, editable, versioned)
- **Automation** = trigger + conditions + a workflow (§2e editor) + an output (dataset rows, a digest, notification or tasks). It is a registry kind with semver, built in the workflow editor or by voice ("every morning check my LinkedIn post stats").
- **Triggers:**
  - schedule (cron-like, with quiet hours)
  - interval with jitter (the humanizer, §2d)
  - after an event (post published → +1h/+24h/+72h/+7d)
  - a notification from app X (NotificationListener, opt-in, package allow-list)
  - an app opened, a location or Wi-Fi, charging + idle
  - manual (button, voice, palette, widget)
- **Session launcher:** "Start a session with X" is one tap from Favourites (§2p), the palette or voice. It opens the web profile or device app inside Memo with a goal, a timer, the live view (§2o) and a capture tray. Captures go straight into the matching dataset.
- **Recipe gallery** (seed templates, editable copies, en/pl/tr):
  - Track my LinkedIn activity: profile views, followers, post impressions/reactions/comments/reposts, search appearances, SSI if available.
  - Track my posts on X / Instagram / YouTube / TikTok / Threads.
  - Daily social browse digest: read-only scroll of chosen feeds or lists, summarised, saved as Sources.
  - Competitor/peer watch: public profiles the user lists.
  - Job market watch: saved searches → JobPostings (UC-3).
  - Inbox/notification digest: from an opt-in notification listener.
  - Weekly review: all datasets → a report + improvement plan.
- **Data source priority** (most reliable and safest first):
  1. official exports and analytics downloads: LinkedIn data export and post analytics XLSX/CSV, X analytics CSV, YouTube Studio CSV, Instagram "download your information"; parsed by import adapters
  2. the platform's own analytics pages in the user's session (read-only extraction schemas, §2m)
  3. the app via accessibility (§2c)
  4. manual entry
  Each row records its source and an evidence snapshot.

### Datasets (core, pure model; app storage encrypted)
- `Dataset {id, name, schemaRef (ExtractionSchema/CustomSchema semver), grain (event | daily | weekly), keys, retention, provenance}`. Rows are append-only with `observedAt`, `sourceRef` and `evidenceRef`.
- Corrections create new rows, never edits in place. An audit of changes is kept.
- Schema evolution through §2m migrations, so old rows stay readable after an extraction schema heals or changes.
- **Storage:** an on-device columnar store per dataset (SQLite via the encrypted vault key, SQLCipher-equivalent through Android Keystore-wrapped keys). Retention and purge are per dataset.
- **Export:** CSV, JSON and XLSX (gated: it leaves the app only by a user action). **Import:** CSV/XLSX mapping wizard.

### Analytics engine (core, pure, deterministic, tested)
- **Metrics:** totals, deltas, rates (engagement rate = interactions/impressions), rolling averages (7/28 d), growth (followers per week), percentiles per post, time-to-peak, decay curves.
- **Trends:** linear and robust (Theil–Sen) slopes with confidence. **Seasonality:** day-of-week and hour-of-day heatmaps, giving the best time to post. **Anomalies:** robust z-score/MAD spikes and drops, each with its likely cause from the event log (post published, profile change).
- **Comparisons:** post vs. own median; format (text/carousel/video) vs. format; topic vs. topic (topics tagged by the local model, evidence-checked); you vs. watched peers (public data only).
- **Attribution (simple, honest):** compare matched windows before and after an action, labelled "correlation, not proof".
- **Goals:** a target metric, baseline, target, deadline and progress, with an on-track/off-track forecast.

### Insights and improvement paths
- **Insight generator:** the local model writes short insights from analytics results only. Each one cites the numbers and rows behind it, and the fabrication guard (§2l) rejects unsupported claims.
  - Example: "Carousels got 2.3× your median impressions over 6 posts (rows …)."
  - For strategy changes, the council (§2n) can review the evidence: skeptic plus domain expert.
- **Improvement plan:** a list of hypotheses ("post carousels on Tue 8–9am"), each with an **Experiment**:
  - variant, metric, duration, minimum sample, stop rule
  - It is scheduled as tasks and workflows, with drafts via UC-4, and measured automatically.
  - Results update the ContentPreference and Capability Experience (§2k), so Memo learns what works for this user.
- **Weekly and monthly review:** a generated report (Studio artifact, PDF export) covering what changed, why that's likely, what to try next, and progress on goals.

### UI ("Insights" destination; also inside Work/Studio)
- **Dashboards:** build-your-own cards (KPI tile, line, bar, heatmap, table, funnel) per dataset. Seed dashboards come per recipe. Cards are reorderable and their filters persist (§2p). Tap a point to see the exact rows and evidence snapshot. All charts follow the design tokens and motion (§2i): draw-in, crossfade on filter change.
- **Tracker manager:** list, status (last run, next run, health, rows added), pause, edit, run now, history. Failures are explained, and self-healing (§2m) is offered.
- **Goals and experiments board:** progress rings with real numbers only, an experiment timeline and a results verdict.

### Safety and platform rules
- **Read-only by default.** Trackers never like, follow, comment or post. Any write is a gated side effect (§2e, §2a envelope).
- **Respect platform terms and limits:**
  - the user's own account and session only, human pacing (§2d)
  - per-site daily caps (strict defaults: LinkedIn ≤ 2 tracker runs/day, ≤ 30 page views per run), no parallel sessions on one account, back-off on any challenge or CAPTCHA (takeover, §2o)
  - official exports preferred
  - peer watch is limited to public pages and low frequency
  - The UI states that automated access may conflict with a platform's terms, and the user decides per tracker (consent recorded).
- Datasets never leave the device without an explicit export. Frontier consults over analytics get aggregated, redacted numbers, not raw rows, unless the user allows it per run.

## 3. Process (applies to every task)

### Roles
- **Lead (orchestrator model):**
  - writes each task brief from this file
  - launches **Haiku** implementation subagents (`model: "haiku"`), each with `isolation: "worktree"` on its own branch `task/<id>`
  - **verifies every task personally** (see Verification gate)
  - integrates and merges
- **Implementer (Haiku subagent):**
  - does exactly one task
  - touches only the files listed in the task
  - pushes the branch, dispatches CI with `gh workflow run build-memo-apk.yml -R ysfclc/memo-2 --ref task/<id>`, watches, fixes until green
  - reports: changed files, CI run URL, how acceptance is met, anything it could not do

### Brief template (lead → Haiku)
- task id and goal
- exact files owned (create/modify), and files it may read
- the relevant sections of this plan, quoted
- the core API facts needed
- acceptance checks
- standing rules:
  - "CI is the compiler"
  - "no placeholders/TODO stubs"
  - "reference zips are API evidence only"
  - step ids match `^[a-z][a-z0-9_]{0,23}$`
  - no new step kinds unless the task says so
  - tokens only from `ui/theme`
  - log decisions in `docs/ENGINEERING_LOG.md` (D-### format)
  - never edit `STATUS.md`; the lead owns it

### Verification gate (lead, before a task counts as done)
1. Read the full diff (`git diff main...task/<id>`). Check the scope is only the owned files, there are no stubs or fake success, and policy laws hold.
2. Confirm the branch CI is green from the run itself (`gh run view <id> --json conclusion`), not from the agent's report.
3. Check every acceptance item: read the tests, run `grep`, inspect behaviour paths.
4. If anything fails, send it back to the same agent (SendMessage) with the exact defects. Repeat until it passes.
5. Merge into `milestone/Mx` once the gate passes.

### Feature checklist
- `docs/FEATURES.md` lists every requirement with its task IDs.
- The lead updates it on every task merge (status + evidence link).
- At milestone close the lead checks that every item mapped to that milestone is ticked or explicitly carried over.

### Milestone close
- Integrate all tasks into `milestone/Mx`, CI green, then a code-review subagent pass and fixes.
- Open a PR to `main`; merge on green.
- Update STATUS.md (✅ only with evidence), ENGINEERING_LOG.md and CHANGELOG.md.
- Give the user the per-milestone device checklist.

### Parallelism
- At most 3 Haiku agents at once, only on tasks with disjoint file ownership and no unmet dependencies (see `deps`).
- **Work packages (2026-10-26, supersedes one agent per task):** tasks that touch the same area, or that share a priority, are bundled into 34 work packages. The waves and the mapping are in `docs/TASKS.md` §0.9.
  - One agent per package.
  - One commit per task ID.
  - LVG once per package.
  - Small items are done inline by the lead.

---

## 4. Tasks

Format: **ID — title** · deps · files owned · steps · acceptance

### M0 — Foundation

**M0.0 — Commit this plan**
- deps: none
- files: `docs/BUILD_PLAN.md`, `AGENTS.md` (add a link to it)
- steps: copy this file into the repo; add a pointer in AGENTS.md
- acceptance: the file is on `main`
- done by: the lead directly

**M0.A1 — Async external steps in the engine**
- deps: M0.0
- files: `core/.../engine/WorkflowEngine.kt`, `core/.../domain/Workflow.kt`, `core/src/test/.../engine/WorkflowEngineAsyncTest.kt`
- steps:
  1. Add `Attempt.Pending(ticket: String, pollAfterMs: Long)`.
  2. Add `StepState.externalTicket` (JSON round-trip).
  3. On Pending, the step stays RUNNING with its ticket, and the run returns RUNNING.
  4. Add `deliverExternal(runId, stepId, ticket, attempt)`: a wrong ticket is rejected; Completed/Failed follow the normal path (retry, outbound, advance).
  5. `resumeInterrupted`: a pending side-effect step goes to NEEDS_REVIEW; a pending pure step goes back to PENDING.
  6. Calling `advance` while a step is pending leaves it untouched.
- acceptance: tests cover happy path, wrong ticket, failure + retry, process-death for both step types, and pending side effect without a gate (still refused).

**M0.A2 — Audit wiring fix**
- deps: M0.A1
- files: `WorkflowEngine.kt`, `core/src/test/.../engine/WorkflowAuditTest.kt`
- steps: call `audit?.record(event)` exactly once per completed side-effect/outbound step (including `deliverExternal` completions).
- acceptance: the test asserts the chain grows by 1 per side effect, 0 for pure steps, and that `HashChainAudit.verify()` is ok.

**M0.A3 — Domain v2 entities**
- deps: M0.0
- files: `core/.../domain/Knowledge.kt` (Project, Note), `core/.../domain/Library.kt` (WorkflowTemplate, PromptTemplate, ArchitecturalPrinciple, ContentPreference, ChannelAccount, ProviderAdapter), tests
- steps:
  - each entity is a data class with `toJson`/`fromJson`/`contentJson`/`isInvalid` and `version`, in the existing style
  - add natural keys in `NaturalKeys`:
    - project: slug(name)
    - note: project + slug(title) + day
    - template: slug(name)
    - prompt: slug(name)
    - principle: slug(title)
    - preference: scope
    - channel account: platform + handle
    - adapter: target id
- acceptance: round-trip and key tests for each entity.

**M0.A4 — Semantic versions + registry v2 + SynCodec migration v1→v2**
- deps: M0.A3
- files: `core/.../registry/*`, `core/.../codec/SynCodec.kt`, `Limits.kt`, `Version.kt`, tests
- steps:
  1. `SemVer(major, minor, patch)` on rows: a content change bumps patch; a schema change bumps major (`Registry.upsert` takes the scheme's `schemaMajor`).
  2. Integer versions map to `1.0.n`.
  3. Register the new registries in `Workspace`.
  4. `WORKSPACE_SCHEMA_VERSION = 2`, with a real migration v1→v2: version ints become semver, and the new empty registries are added.
- acceptance: a v1 fixture parses with `migrated=true` and re-serializes as v2; existing tests stay green.

**M0.A5 — Policy module**
- deps: M0.0
- files: `core/.../policy/ActionPolicy.kt`, `core/.../policy/ChannelSwitches.kt`, tests
- steps:
  - destructive/sensitive regex, the union of: pay, payment, purchase, buy, checkout, transfer, send money, withdraw, cash out, delete, remove account, unsubscribe all, submit, publish, post now, book, password, passcode, security code, one-time code, OTP, captcha, credential, disable security, factory reset, grant permission
  - `classify(label)` returns SAFE / NEEDS_APPROVAL / BLOCKED
  - `ChannelSwitches` (per channel and provider, plus a global kill switch) as immutable state with JSON
- acceptance: table-driven tests in English plus a few multilingual variants (tr/de/es/fr words for send/delete/pay).

**M0.A6 — ModelRouter + rate limits**
- deps: M0.A5
- files: `core/.../route/ModelRouter.kt`, `core/.../route/RateLimiter.kt`, `core/.../route/ProviderHealth.kt`, tests
- steps:
  1. Inputs: `StepRole {RESEARCH, DRAFT, CAPTURE, CRITIQUE, SYNTHESIZE}`, the available targets (web provider ids + app packages, with enabled flags), health stats (success rate, last failure, latency) and rate-limit state.
  2. Output: `RouteDecision(targetId, channel, rationale)`.
  3. Deterministic scoring: a role-affinity table, minus health penalties, minus rate-limited targets; ties broken by stable order.
  4. `RateLimiter` enforces minimum interval and daily cap per target.
  5. The local model is never chosen for heavy roles unless no frontier target is enabled, and the rationale says so.
- acceptance: tests for preference, fallback, all-disabled → local with rationale, and the rate-limit window.

**M0.A7 — Platform interfaces**
- deps: M0.0
- files: `core/.../platform/*.kt` (interfaces + data types only)
- steps: define `LlmRuntime` (`load(ModelRef)`, `chat(List<ChatTurn>, GenParams): Flow`-free callback API `ChatSink` so core stays coroutine-free, `cancel()`, `unload()`, `info()`), `SpeechIn`, `SpeechOut`, `Vault` (`read`/`write` bytes by name), `BrowserChannel`, `AppChannel`, `PackageInspector`, `Scheduler`, `Notifier`, `Clock`.
- acceptance: compiles; KDoc states each contract, including threading and cancellation.

**M0.B1 — Native + dependency build infra**
- deps: M0.0
- files: `app/build.gradle.kts`, `gradle.properties`, `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/memo_llama.cpp` (JNI entry with a version probe only), `app/src/main/kotlin/app/memo/platform/android/llm/llama/LlamaNative.kt`, `.github/workflows/build-memo-apk.yml`, `.gitignore`
- steps:
  1. CI installs `ndk;<ver>` and `cmake;<ver>` (pick versions present on the runner, and log them).
  2. `externalNativeBuild { cmake }`, with `abiFilters` arm64-v8a and x86_64.
  3. `FetchContent` llama.cpp b11460 with `URL_HASH`, building static `llama` + `ggml` with CPU only and `-DGGML_NATIVE=OFF`.
  4. JNI `nativeVersion()` returns the llama.cpp build info.
  5. A Gradle task `fetchSherpaAar` downloads the pinned AAR into `app/libs/` (gitignored), verifies the SHA-256, and fails on mismatch. `implementation(files(...))` depends on it via `preBuild`.
  6. Add the LiteRT-LM, WorkManager, coroutines, webkit and ML Kit deps.
  7. Add the `uses-native-library` entries in the manifest.
- acceptance:
  - CI green
  - the APK contains `lib/arm64-v8a/libmemo_llama.so` and the sherpa `.so` files (CI step lists the APK contents with `unzip -l`)
  - build time is logged

**M0.C1 — Encrypted vault**
- deps: M0.A7
- files: `app/.../platform/android/vault/KeystoreVault.kt`, `app/.../platform/android/vault/VaultFormat.kt` (pure: framing `MV1|ivLen|iv|ct`), `app/src/test/.../VaultFormatTest.kt`
- steps:
  1. AES-256-GCM with a Keystore key `memo_vault_v1` and AAD `memo:<name>:v1`.
  2. Atomic writes: temp file, `fd.sync`, rename.
  3. 3 rolling `.bak` files; on a corrupt read, fall back to the newest valid `.bak` and report it.
  4. Never create a new key when decrypting existing data (fail closed and surface the error).
- acceptance: unit tests for framing, tamper detection on a JVM AES-GCM key, and `.bak` selection logic.

**M0.C2 — Runtime onto the vault + plaintext migration**
- deps: M0.C1, M0.A2, M0.A4
- files: `app/.../runtime/MemoRuntime.kt`, `app/.../runtime/Persistence.kt`
- steps:
  1. Workspace, runs, audit and settings go through the Vault.
  2. On first run, if the plaintext `workspace.syn.json`/`runs.json`/`audit.json` exist, import them, write them encrypted, and securely delete the plaintext.
  3. Pass a `packetProvider` built from the workspace.
  4. Wire the engine's audit to `HashChainAudit`.
- acceptance:
  - CI green
  - code review confirms no plaintext writes remain (grep for `writeText` / `FileOutputStream` outside the vault)

**M0 close:** milestone PR. Device checklist: the app opens; existing data is still present after upgrade; the Settings audit shows the chain verified.

### M1 — Local AI + Chat

**M1.A1 — LiteRT-LM runtime**
- deps: M0
- files: `app/.../platform/android/llm/litert/LiteRtLmRuntime.kt`
- steps:
  1. A dedicated thread, with one engine resident.
  2. Try GPU first; on init failure, use CPU, and also retry the first generation on CPU.
  3. Catch `Throwable`, including OOM, and map it to a typed error.
  4. Streaming via `sendMessageAsync`.
  5. Stop collecting at the output cap and `cancelProcess()`.
  6. Thinking toggle: strip `<think>` blocks and expose them separately.
  7. A RAM preflight using `ActivityManager.MemoryInfo`.
  8. Create cacheDir.
- acceptance: CI green; the lead reviews the threading and cancellation paths.

**M1.A2 — llama.cpp JNI runtime**
- deps: M0.B1
- files: `app/src/main/cpp/memo_llama.cpp`, `LlamaNative.kt`, `app/.../platform/android/llm/llama/LlamaCppRuntime.kt`
- steps:
  1. JNI: load the model (mmap), create the context (n_ctx, n_threads from cores), apply the chat template from GGUF metadata, tokenize, and run a decode loop with a sampler chain (temp, top-k, top-p, min-p, repeat penalty).
  2. Per-token callback to Kotlin; atomic cancel flag; free resources.
  3. Map native errors to exceptions.
  4. All API names must be taken from the pinned `llama.h`.
  5. **Performance:** native code is always built Release (M0 integration). For arm64, enable the ARM CPU feature path at the pinned tag: `GGML_CPU_ALL_VARIANTS=ON` + `GGML_BACKEND_DL=ON` if supported there, so the best variant (dotprod / i8mm / sve) is chosen at runtime. Otherwise build with `-march=armv8.2-a+dotprod+fp16`. Verify against the tag's `ggml/src/ggml-cpu/CMakeLists.txt`.
  6. Report tokens/s in `GenStats`; the device check records tokens/s for a 1B and a 3B Q4_K_M model.
- acceptance:
  - CI green
  - the native code compiles for both ABIs
  - a unit test for the Kotlin-side template/params mapping

**M1.A3 — Inference service + runtime selector**
- deps: M1.A1, M1.A2
- files: `app/.../runtime/InferenceManager.kt`, `app/.../platform/android/llm/InferenceService.kt`, manifest entry
- steps:
  1. Choose the runtime by model format.
  2. Single resident model; unload when switching.
  3. Run as a foreground service (`specialUse` with a subtype) during generation longer than 10 s.
  4. Queue requests (one at a time); cancel.
- acceptance: CI green.

**M1.B1 — Model catalog (pure)**
- deps: M0.A4
- files: `core/.../catalog/ModelCatalog.kt` (rewrite to `ModelFormat {LITERTLM, GGUF}`, `ModelEntry` + `family`, `params`, `quant`, `license`, `gated`, `minRamBytes`), `core/.../catalog/HfParsers.kt`, tests + fixtures `core/src/test/resources/hf/*.json`
- steps:
  1. Parsers for the HF list, model and tree JSON (recorded fixtures) into candidate entries with `sha256`/`size`/`revision`.
  2. A GGUF quant ranking (Q4_K_M > Q4_K_S > Q5_K_M > Q8_0…).
  3. Curated seed list: litert-community chat models (§1), plus GGUF picks for Llama 3.2 1B/3B, Llama 3.1 8B, Gemma 3 1B/4B, Qwen3 1.7B/4B, Phi-4-mini, Mistral 7B Instruct, DeepSeek-R1-Distill 1.5B/7B, SmolLM3 (repos verified via the API at implementation time; sha/size **pinned in code**).
- acceptance:
  - parser tests pass
  - every seed entry has a 64-hex SHA, a size and a revision
  - the lead re-checks 3 random entries against the live API

**M1.B2 — Hub downloads (WorkManager)**
- deps: M1.B1, M0.C1
- files: `app/.../hub/ModelHub.kt`, `app/.../hub/DownloadWorker.kt`, `app/.../hub/HfClient.kt`
- steps:
  1. A foreground `CoroutineWorker` (`dataSync` type) with notification progress.
  2. Range resume; free-space check; HTTPS-only redirect check.
  3. Streaming SHA-256 with size verification; `.part` → atomic install; `.previous` rollback.
  4. Cancel and pause.
  5. Live discovery: litert-community list plus GGUF search, using an optional HF token (stored in the vault).
  6. Device import (SAF) with hash match, and storage scan.
  7. Migrate the existing `ModelStore` installs.
- acceptance: CI green; the lead reviews the resume and verify paths.

**M1.B3 — Models UI**
- deps: M1.B2, M2.B0d
- files: `app/.../ui/models/*`
- steps:
  1. Sections: Installed / Recommended for this phone / Browse (live search, format filter, family chips) / Voice models (filled in M2) / Import.
  2. Each card shows size, RAM fit badge, licence, gated lock, a one-tap Download with progress ring, and Use/Default/Delete actions.
  3. Per-model settings sheet: system prompt, temperature, context, thinking.
- acceptance: CI green; screenshots are not possible, so the lead reviews the composables against the design tokens.
- continuation status (2026-10-27): implementation is in `wp/chat`; CI run `40461314345` passed on `a1a7ec4`, and the lead's token review is complete. This meets the planned acceptance; see `docs/PROGRESS.md` for the device status of the broader M1 close checklist.

**M1.C1 — Conversation store**
- deps: M0.C1
- files: `core/.../chat/Conversation.kt` (Conversation, ChatMessage{role, text, modelId, voiceClipRef?, createdAt, status}), `app/.../runtime/ChatRepository.kt`, tests
- steps:
  1. Encrypted persistence, one file per conversation.
  2. Titles generated from the first message, with a local summarisation fallback.
  3. Search.
- acceptance: tests for codec and ordering.
- continuation status (2026-10-27): implementation and named tests are present in `wp/chat`; CI run `40461314345` passed on `a1a7ec4`.

**M1.C2 — Chat UI**
- deps: M1.C1, M1.A3, M2.B0
- files: `app/.../ui/chat/*`
- steps:
  1. Conversation list (drawer/sheet) and thread view.
  2. Streaming bubbles with a markdown renderer (bold, italic, code blocks with copy, lists, links).
  3. Composer: auto-grow, send/stop, attach (text/file context), mic (hold for voice message; tap for live mode, wired in M2).
  4. Top-bar **model picker** listing installed local models (by format) plus "Frontier via browser" providers (wired in M4), with model info.
  5. Message actions: copy, regenerate, edit & resend, read aloud (M2), delete.
  6. Empty-state suggestions; scroll-to-bottom handling.
- acceptance: CI green; the lead checks tokens-only styling and that no fake streaming exists.
- continuation status (2026-10-27): implementation is in `wp/chat`; CI run `40461314345` passed on `a1a7ec4`. The smallest-model emulator demo remains unverified: the CI APK was rejected over the existing app's signing certificate, and a read-only clone could not start beside the writable AVD. Frontier provider execution remains wired by the planned M4 work, and voice controls by M2.

**M1 close:** PR. Device checklist:
- download Gemma 4 E2B (LiteRT) and Llama 3.2 3B Q4_K_M (GGUF)
- chat with both
- stop mid-generation
- switch models

### M2 — Voice

**M2.B0 — Design system + motion foundation (prerequisite for UI tasks)**
Split into small tasks, all following §2i.

**M2.B0a — Tokens + typography + theme**
- deps: M0
- files: `app/.../ui/theme/MemoTokens.kt`, `MemoTypography.kt`, `MemoTheme.kt` (replaces `Theme.kt`)
- steps: colour roles (light/dark), spacing (4 dp grid), radii, elevation, type scale, and semantic colours (success, attention, danger).
- acceptance: grep finds no `Color(0x` outside `ui/theme`.

**M2.B0b — Motion tokens + haptics**
- deps: M2.B0a
- files: `app/.../ui/motion/MemoMotion.kt`, `Haptics.kt`, `ReduceMotion.kt`
- steps: §2i springs, durations and easings; haptic vocabulary gated by a setting; reduce-motion and animator-scale awareness.
- acceptance: unit tests for reduce-motion fallbacks (pure functions).

**M2.B0c — Component kit**
- deps: M2.B0b
- files: `app/.../ui/components/*`
- steps:
  - MemoButton (press spring + haptic), MemoCard, Pill/Chip, SectionHeader, EmptyState, StatTile with digit-roll counter, Skeleton/Shimmer, MemoSheet (spring), Snackbar with undo, ProgressRing (real progress), Sparkline (draw-in)
  - StepRail: path-trim line, pulsing node, check morph, failure shake, waiting halo
  - PresenceOrb: `RuntimeShader` on API 33+ with a Canvas fallback; states plus audio-reactive input
- acceptance: CI green; the lead reviews every component against §2i values.

**M2.B0d — Navigation shell with shared transitions + predictive back**
- deps: M2.B0c
- files: `app/.../ui/nav/*`, `MainActivity.kt`
- steps: `SharedTransitionLayout` app shell, tab ↔ detail shared-element transitions, `PredictiveBackHandler` on all screens and sheets, and migrating the existing screens onto the kit.
- acceptance: CI green; every navigation path has an animated transition (the lead walks the nav graph).

**M2.A1 — Audio I/O**
- deps: M0.B1
- files: `app/.../platform/android/voice/AudioCapture.kt`, `AudioPlayback.kt`
- steps:
  1. `AudioRecord` at 16 kHz mono PCM16, using VOICE_COMMUNICATION with AcousticEchoCanceler/NoiseSuppressor where available.
  2. Level metering.
  3. `AudioTrack` playback with audio focus and ducking.
  4. Permission handling with rationale.
- acceptance: CI green.

**M2.A2 — Speech engine (sherpa-onnx)**
- deps: M2.A1
- files: `app/.../platform/android/voice/SherpaAsr.kt`, `SherpaTts.kt`, `SherpaVad.kt`, `SherpaKws.kt`, `VoiceModels.kt`
- steps:
  1. Streaming ASR (Zipformer) with partials.
  2. Offline ASR (Whisper base/small or SenseVoice) for voice messages.
  3. TTS: Kokoro (multi-speaker; voice choice and speed) with Piper as fallback, streaming sentence-by-sentence synthesis.
  4. Silero VAD.
  5. Keyword spotter with the custom keyword "hey memo".
  6. Voice model entries (URLs from the sherpa-onnx releases page, with sha/size pinned), downloaded via the Hub.
  7. All sherpa API names come from the pinned AAR's classes (`unzip` the AAR in CI or locally to read the Kotlin API).
- acceptance: CI green; the lead verifies API usage against the AAR's class list.

**M2.A3 — Conversation loop controller**
- deps: M2.A2, M1.A3
- files: `app/.../runtime/VoiceLoop.kt`, `core/.../voice/TurnTaking.kt` (pure state machine) + tests
- steps:
  1. States: idle → listening → (VAD end) → thinking → speaking → listening.
  2. Barge-in: user speech during speaking stops TTS and starts listening.
  3. The LLM token stream is cut into sentences and fed to TTS as they arrive.
  4. Silence timeout; "stop"/"cancel" voice commands.
- acceptance: state machine tests (including barge-in and timeout).

**M2.B1 — Voice messages + live conversation UI**
- deps: M2.A3, M1.C2
- files: `app/.../ui/voice/*`, plus small hooks in `ui/chat/Composer.kt` (owned)
- steps:
  1. Hold-to-record shows a live waveform; release sends; slide to cancel.
  2. The message stores both audio and transcript, with playback.
  3. Live mode is full screen: orb, live transcript (partials), speaking captions, interrupt by voice or tap, end.
  4. Read-aloud per message.
  5. **No `RecognizerIntent` anywhere** (grep-checked).
- acceptance: CI green; grep shows no `RecognizerIntent`.

**M2.C1 — Assistant role + lock-screen session**
- deps: M2.A3
- files: `app/.../platform/android/assist/MemoVoiceInteractionService.kt`, `MemoSessionService.kt`, `MemoSession.kt`, `AssistActivity.kt` (`showWhenLocked`, `turnScreenOn`), `res/xml/interaction_service.xml`, manifest
- steps:
  1. Request `RoleManager.ROLE_ASSISTANT` from Settings.
  2. The assist gesture opens a voice session over the lock screen.
  3. While locked, only pure steps run; side effects queue with an "Unlock to approve" prompt via `requestDismissKeyguard`.
  4. The session summary shows what ran and what is pending.
- acceptance: CI green; manifest review.

**M2.C2 — "Hey Memo" wake listener (works with the screen off and locked, §2f)**
- deps: M2.A2
- files: `app/.../platform/android/assist/WakeWordService.kt`, manifest, settings hook
- steps:
  1. Foreground service, `microphone` type, started only while in the foreground, with a persistent notification and a pause action.
  2. Battery guard: stop below a threshold, and on overheating.
  3. On wake, open AssistActivity.
  4. After reboot, show a notification asking to re-arm (Android 14+ forbids starting a mic foreground service from boot).
- acceptance: CI green; the lead reviews the FGS start constraints.

**M2.C3 — Quick Settings tile, floating bubble, ongoing notification**
- deps: M2.C1
- files: `app/.../platform/android/surface/MemoTileService.kt`, `BubbleService.kt` (overlay, `specialUse`), `LiveNotification.kt`
- steps:
  1. The tile starts a voice session.
  2. Bubble: the collapsed PresenceOrb overlay only (the foundation). The full floating console is M4.D2 (§2b).
  3. Ongoing notification shows running/pending counts with approve actions; side effects require unlock.
- acceptance: CI green.

**M2 close:** PR. Device checklist:
- install Kokoro + Zipformer
- voice message
- live conversation with interruption
- assist gesture from the lock screen
- "Hey Memo"
- tile and bubble

### M3 — Orchestrator + Live environment

**M3.A1 — Orchestrator planner**
- deps: M1.A3, M0.A4
- files: `core/.../plan/Orchestrator.kt` (pure: prompt assembly, repair prompt, decision "chat reply vs plan"), `core/.../plan/TemplateLibrary.kt` (seed WorkflowTemplates for UC-1..5 as few-shot examples), `app/.../runtime/PlannerService.kt`, tests
- steps:
  1. Intent classification: chat / plan / command; grammar fast-path.
  2. Plan via the local model, using templates + ContextPacket.
  3. Parse/validate with up to 2 repairs, citing violations.
  4. Fallback to the deterministic planner, labelled as such.
  5. Reuse-first: look up an existing template or project before creating.
- acceptance: tests with a fake LlmRuntime: a good plan, a repaired plan, a fallback, and a reuse hit.

**M3.A4 — Local agent engine (tool-using)**
- deps: M3.A1, M1.A3
- files: `core/.../agent/AgentEngine.kt`, `core/.../agent/Tools.kt` (tool schemas + strict arg validation), `app/.../runtime/AgentTools.kt` (tool implementations wired to engine, registries, media, router), tests with a fake LlmRuntime
- steps: implement §2g's agent loop, tool set, bounds, repairs and quality-check escalation. Side-effecting tools become workflow steps.
- acceptance: tests for valid calls, invalid → repair → success, budget exhaustion, no-progress stop, gated tool queueing, and escalation to frontier when routing allows it.

**M3.A2 — Context retrieval**
- deps: M3.A1
- files: `core/.../prompts/PacketBuilder.kt`, tests
- steps:
  1. Select relevant rows by mention, natural key and keyword overlap across all registries plus recent chat.
  2. Budget-capped.
  3. Redacted.
- acceptance: tests for budget, redaction and relevance ordering.

**M3.A3 — Chat ↔ workflow bridge**
- deps: M3.A1, M1.C2
- files: `app/.../runtime/ChatOrchestration.kt`, `app/.../ui/chat/PlanCard.kt`
- steps:
  1. When the assistant decides "plan", an inline plan card appears (steps cascade in) with Run / Edit / Discard.
  2. The run shows a live step rail inside the thread; gates appear inline with the artifact renderer.
- acceptance: CI green.

**M3.B1 — Artifact renderers + approval UX**
- deps: M2.B0
- files: `app/.../ui/artifact/*`
- steps:
  1. Renderers: Person, Company, Task, Project/Note, Draft (post preview with channel chips and character counts), JobPosting + cover letter, Application, MetricSummary, and a JSON fallback.
  2. Edit-before-approve re-builds the artifact and its hash, and the gate re-opens.
  3. Approve/Veto transition with haptic.
- acceptance: CI green; a renderer exists for every `ARTIFACT_KINDS` entry.

**M3.B2 — Live tab**
- deps: M3.B1
- files: `app/.../ui/live/*`
- steps: running workflows with animated StepRail (spawn cascade, progression, pulse on running, check on done), approvals carousel, recent outputs, metric health sparklines, "since you were away" digest, kill switch.
- acceptance: CI green.

**M3.B3 — Glance widget**
- deps: M3.B2
- files: `app/.../surface/ApprovalsWidget.kt`, xml
- steps: pending approvals count + top item; tap opens Live.
- acceptance: CI green.

**M3.C1 — Profile + preferences**
- deps: M0.C2
- files: `core/.../domain/Profile.kt`, `app/.../ui/settings/ProfileScreen.kt`
- steps: CV facts, skills, target roles, content preferences (tone, channels, cadence), and voice preferences; all encrypted and fed into the packet.
- acceptance: tests + CI green.

**M3.C2 — UC-2 CRM enrichment**
- deps: M3.A1, M4.A2 (browser capture; until then, local-only fill marked "research pending")
- files: `core/.../plan/templates/CrmCapture.kt`, `app/.../runtime/CrmFlows.kt`
- steps:
  1. Parse person + place + company.
  2. Reuse an existing Person/Company if found.
  3. Generate follow-up Tasks.
  4. Research step for missing fields.
  5. Gate on the full record.
- acceptance: an end-to-end JVM test with fake executors.

**M3 close:** PR. Device checklist: say "I met Ada at Web Summit, add to CRM"; see the plan card, the filled record, approve it, and find it in Work.

### M3.5 — Full control (CRUD, workflow editor, autonomy, settings)

**M3.5.A1 — Standing-rule approvals in core**
- deps: M0.A1, M0.A5
- files: `core/.../policy/AutonomyPolicy.kt`, `WorkflowEngine.kt` (a `GateApprover` field on gate delivery and in the audit), tests
- steps:
  1. Action classes and levels per §2e. Human-only classes are not configurable.
  2. The engine can auto-deliver a gate when the standing rule allows it, recording `approver = STANDING_RULE:<ruleId>`.
  3. Quiet hours.
- acceptance: tests show human-only classes can never be auto-approved, and the audit records the approver.

**M3.5.A2 — Humanizer**
- deps: M4.A0
- files: `core/.../automation/Humanizer.kt`, tests
- steps: §2d timing, typing, touch and gesture paths, pacing and caps, all with a seeded RNG.
- acceptance: the tests listed in §2d.

**M3.5.B1 — Editors for every record type**
- deps: M3.B1
- files: `app/.../ui/edit/*` (generic form engine driven by field descriptors + per-entity descriptors), `app/.../runtime/EditRepository.kt`
- steps: create, edit, archive, undo and purge for every §2e entity via `Registry.upsert`, with validation messages from the entity's `isInvalid` rules. A version history view per record.
- acceptance: CI green; every registry kind has create/edit/archive reachable from the UI (the lead checks the descriptor list against `Workspace.all()`).

**M3.5.B2 — Workflow editor**
- deps: M3.A1, M3.5.B1
- files: `app/.../ui/workflow/*`, `app/.../runtime/WorkflowLibrary.kt`
- steps: §2e workflow editor (step list with drag-reorder, per-kind step forms, live validation, save as template, duplicate, run, schedule one-off/recurring, per-workflow run history with edit and re-run).
- acceptance: CI green; a unit test that editor-produced specs pass `WorkflowPolicy.validateSpec`.

**M3.5.C1 — Settings suite + autonomy UI**
- deps: M3.5.A1
- files: `app/.../ui/settings/*`, `app/.../runtime/SettingsStore.kt` (encrypted)
- steps: every §2e settings section, searchable, with the autonomy matrix UI, per-channel overrides and quiet hours. Each setting is wired to its consumer; no dead toggles.
- acceptance: CI green; the lead verifies every setting key has a reader (grep).

### M3.6 — Languages (en / pl / tr)

**M3.6.A1 — Multilingual command grammar**
- deps: M0.A8
- files: `core/.../plan/CommandGrammar.kt` (language-aware), `core/.../plan/grammar/{En,Pl,Tr}Grammar.kt`, tests
- steps:
  1. Patterns and time phrases per §2h.
  2. Language detection or explicit locale.
  3. Turkish-safe casing.
- acceptance: ≥15 cases per language, including time resolution.

**M3.6.B1 — App localisation + language picker**
- deps: M2.B0
- files: `app/src/main/res/values-pl/strings.xml`, `values-tr/strings.xml`, `res/xml/locales_config.xml`, `app/.../ui/settings/LanguageScreen.kt`, `app/src/test/.../StringParityTest.kt`
- steps:
  1. Move every UI string into resources.
  2. Write full Polish and Turkish translations: natural phrasing, not word-for-word.
  3. Add the picker, wired to the API 33+ locale manager and the fallback.
- acceptance:
  - the parity test passes
  - grep finds no hard-coded `Text("...")` literals outside previews
  - the lead spot-checks 20 strings per language for natural phrasing

**M3.6.C1 — Voice in Polish and Turkish**
- deps: M2.A2
- files: `app/.../platform/android/voice/VoiceModels.kt` (Whisper multilingual + Piper pl/tr entries, pinned), `SherpaAsr.kt` / `SherpaTts.kt` (language routing)
- steps: per-language ASR/TTS model selection, a voice picker per language, and VAD-segmented Whisper "live" mode.
- acceptance: CI green; device check holds a Polish and a Turkish live conversation.

### M3.7 — Everyday product (§2j)

**M3.7.A1 — Reminders, timers, alarms engine**
- deps: M0.C2, M0.A7
- files: `core/.../time/Recurrence.kt` (RRULE subset, next-occurrence, DST and zone safe), `core/.../time/TimerModel.kt`, tests, `app/.../platform/android/notify/{AlarmScheduler,ReminderReceiver,BootReceiver,TimerService,AlarmActivity}.kt`, `NotificationChannels.kt`, manifest permissions
- steps: implement everything in §2j "Notifications, reminders, timers, alarms", including exact-alarm permission flow, snooze, boot/time-change re-arm, full-screen alarms, concurrent timers and channels.
- acceptance:
  - recurrence/DST tests
  - CI green
  - device check: a reminder fires with the screen off, snooze works, a 1-minute timer rings on the lock screen, and the reminder survives a reboot

**M3.7.A2 — Voice and agent hooks for time**
- deps: M3.7.A1, M3.6.A1
- files: `CommandGrammar` time intents (timer, alarm, reminder, recurring, in en/pl/tr), the agent tool `set_reminder` / `start_timer`, and the workflow `notify` step via `Scheduler`
- acceptance: grammar tests in 3 languages.

**M3.7.B1 — Capture from anywhere**
- deps: M3.B1
- files: `app/.../capture/{ShareReceiverActivity,ProcessTextActivity,CameraCapture,CardScanner}.kt`, manifest intent filters
- steps: share target (bounded), PROCESS_TEXT, camera business-card scan → OCR → Person/Company gate, document photo → note, share-out export.
- acceptance: CI green; a unit test for the card-text → Person parser (en/pl/tr formats).

**M3.7.B2 — Sources + global search + embeddings**
- deps: M1.A1, M0.C2
- files: `core/.../knowledge/{Source,Chunker,Index}.kt`, `app/.../platform/android/search/{FtsIndex,EmbeddingIndex}.kt`, `app/.../ui/sources/*`, `app/.../ui/search/*`
- steps: source import with provenance, hash dedupe and chunking; FTS index; EmbeddingGemma embeddings via the Hub; global search UI with filters.
- acceptance: tests for the chunker, dedupe and ranking; CI green.

**M3.7.B3 — Memory graph view**
- deps: M3.7.B2, M2.B0c
- files: `app/.../ui/memory/*`
- steps: force-directed graph (Canvas, spring physics), link explanations with evidence, tap a node → shared-element transition to the record.
- acceptance: CI green.

**M3.7.C1 — Today, briefings, driving mode, quiet hours**
- deps: M3.B2, M3.7.A1, M2.A3
- files: `app/.../ui/today/*`, `app/.../runtime/Briefing.kt`, `app/.../runtime/AttentionPolicy.kt` (nudge caps, quiet hours), `app/.../ui/drive/*`
- acceptance: tests for the attention policy; CI green.

**M3.7.C2 — Onboarding + help + templates gallery**
- deps: M2.B0d, M1.B3, M2.A2
- files: `app/.../ui/onboarding/*`, `app/.../ui/help/*`
- steps: the §2j first-run flow plus contextual tips and a templates gallery.
- acceptance: CI green; the device check walks a fresh install.

**M3.7.C3 — In-app updates**
- deps: M2.5.A1
- files: `app/.../update/{UpdateChecker,UpdateInstaller}.kt`, `.github/workflows/release.yml` (on a `v*` tag: build the signed release, create a GitHub release with the APK, SHA-256 and changelog)
- acceptance: CI release dry-run; a unit test for the version compare and SHA check.

**M3.7.C4 — Contacts import + place tag**
- deps: M3.5.B1
- files: `app/.../platform/android/contacts/ContactsImport.kt`, `app/.../platform/android/location/PlaceTag.kt`
- steps: opt-in, reuse-first merge into Person/Company.
- acceptance: CI green.

**M4.A4 — Council (§2n), split into four tasks**
- **M4.A4a — Council core (pure)**
  - deps: M3.A4, M0.A6, M3.8.A2
  - files: `core/.../council/{CouncilRole,Seat,SeatPlanner,Council,ClaimsLedger,ClaimParser,CouncilOutcome,CouncilSession,CouncilTemplate}.kt`, registry schemes + migration, tests
  - steps: the role library with seeds, relevance seat planning (router affinity + privacy class), all 6 formats as one state machine, visibility filtering, the claims ledger with ids and refs, stopping rules including no-progress, blind openings, and outcome synthesis with the minority report
  - acceptance: tests for every format's turn order; blind openings never see peers; private data never reaches a non-local seat; a rebuttal must reference an existing claim; no-progress stops the debate; an absent seat lets the round continue; the judge differs from the proposer when ≥2 targets exist; an unsupported claim is never extracted
- **M4.A4b — Council runtime**
  - deps: M4.A4a, M4.A2, M1.A3, M4.D1
  - files: `app/.../runtime/CouncilService.kt`
  - steps: seats are dispatched to InferenceManager / FrontierExecutor / app session; parallel independent turns; swap-minimising order; per-run consent; audit; takeover → the seat is absent
  - acceptance: a JVM test with fake channels for a 3-seat debate (local + web + app); emulator test with a fixture "AI" page as a web seat
- **M4.A4c — Council UI**
  - deps: M4.A4b, M2.B0c, M1.C2
  - files: `app/.../ui/council/*`
  - steps: the group thread, seat plan sheet, claim chips and links, ledger panel, interject/pause/steelman, outcome card, voice mode with a distinct voice per seat
  - acceptance: CI green; emulator screenshots; tokens only
- **M4.A4d — Evidence-backed entity extraction**
  - deps: M4.A4a
  - files: `core/.../agent/EntityExtraction.kt`, tests
  - acceptance: tests including a rejected unsupported claim

**M4.A5 — Optional API channel**
- deps: M0.C1, M0.A6
- files: `app/.../platform/android/api/{OpenAiCompatibleClient,AnthropicClient,GeminiClient}.kt`, Settings section
- steps: HTTPS only, keys in the vault, per-run consent, redaction + audit, router integration (never the default).
- acceptance: tests for request building and redaction; CI green.

**M4.B4 — App capability map**
- deps: M4.B1, M0.A3
- files: `core/.../domain/AppCapability.kt` (+ registry scheme), `app/.../platform/android/automation/CapabilityRecorder.kt`, the Settings view and editor
- acceptance: tests + CI green.

**M6.10 — Memo's own accessibility audit:** TalkBack pass, touch targets, 200% font scale, contrast; fix everything found. Every screen has empty, loading, error and offline states.

### M3.8 — Capability Compiler (§2k)

**M3.8.A1 — Capability library schemas**
- deps: M0.A4
- files: `core/.../capability/{Persona,Skill,KnowledgePack,DataBinding,ToolSpec,Constraint,Evaluator,Experience}.kt` + registry schemes + Workspace registration + a SynCodec v2→v3 migration, tests
- steps:
  - fields per §2k: provides / requires / compatibleWith / conflictsWith / languages / trust / stats / semver
  - natural keys
  - trust state machine UNTRUSTED → TRIAL → TRUSTED with evidence thresholds
- acceptance: round-trip, key, trust-transition and migration tests.

**M3.8.A2 — TaskSpec + RequirementGraph + Assembler + Readiness (pure)**
- deps: M3.8.A1, M0.A5, M0.A6
- files: `core/.../capability/{TaskSpec,RequirementGraph,Assembler,Readiness}.kt`, tests
- steps:
  - strict JSON parsing of TaskSpec and graph
  - bounded expansion
  - deterministic matching with dependency resolution, conflict removal and permission checks
  - gap reporting and the readiness verdict
- acceptance: tests for covered, conflict, missing-tool gap, untrusted-component restriction, and bounds.

**M3.8.A3 — ExecutionPackage + renderer + compile-to-workflow**
- deps: M3.8.A2, M3.A4
- files: `core/.../capability/{ExecutionPackage,PromptRenderer,PackageCompiler}.kt`, tests
- steps:
  - the manifest
  - a deterministic renderer per executor size class
  - compile to a `WorkflowSpec` (agent loop / web / app / gates) that passes `WorkflowPolicy.validateSpec`
- acceptance: golden tests of rendered prompts per size class; compiled specs validate.

**M3.8.A4 — Evaluate / diagnose / repair / learn loop**
- deps: M3.8.A3
- files: `core/.../capability/{EvaluationRunner,Diagnoser,RepairPlanner,ExperienceRecorder}.kt`, app wiring in `app/.../runtime/CompilerService.kt`, tests with a fake LlmRuntime
- steps:
  - evaluator execution, with a cross-model rubric where routing allows
  - failure classification
  - targeted component repair within the budget
  - Experience rows and reliability stats
  - promotion rules
- acceptance: tests for repair success, budget exhaustion, a no-endless-loop guarantee, and promotion only on evidence.

**M3.8.A5 — Architect/critic loop**
- deps: M3.8.A2, M4.A2
- files: `core/.../capability/ArchitectLoop.kt`, tests
- steps: an architect model proposes the TaskSpec and graph; a different model critiques; at most 2 rounds; the result is strict JSON. The models are routed per §2g, either local or frontier via web.
- acceptance: tests with fake models, including disagreement resolution and round limits.

**M3.8.B1 — Seed capability library**
- deps: M3.8.A1
- files: `core/src/main/resources/capabilities/*.json` + loader + tests
- steps: seed components for every use case in §2k, plus evaluators, each with a test case.
- acceptance: every seed loads and validates, and each has an evaluator.

**M3.8.C1 — Compiler UI**
- deps: M3.8.A4, M2.B0c
- files: `app/.../ui/compiler/*`
- steps: the §2k view: animated requirement graph, component cards with trust badges, gaps and questions, inspectable rendered prompt, evaluation and repair timeline, edit + recompile.
- acceptance: CI green.

### M3.9 — Self Model + Artifact Studio (§2l)

**M3.9.A1 — Self Model schema + provenance + conflict resolution**
- deps: M0.A4, M0.C1
- files: `core/.../self/{SelfModel,Fact,Provenance,Conflict,SelfDiff}.kt`, tests
- steps:
  - typed sections
  - per-fact provenance and confidence
  - diff between an ingestion result and the current model
  - conflicts surfaced, never silently merged
- acceptance: tests for diff, conflict and merge-after-approval.

**M3.9.A2 — Ingestion: website, LinkedIn, CV/docs**
- deps: M3.9.A1, M4.A1, M3.7.B2
- files: `app/.../self/{WebsiteIngest,LinkedInIngest,LinkedInExportImport,DocumentIngest}.kt`, `core/.../self/parse/*` (pure section parsers with fixtures)
- steps:
  1. Read-only capture through the in-Memo web runtime (the user's own profile, own session).
  2. LinkedIn official export ZIP import.
  3. PDF/DOCX/text parsing on-device, with an OCR fallback.
  4. Every ingestion produces a gated SelfDiff.
- acceptance: parser tests on fixtures (website HTML, LinkedIn export CSVs, a sample CV text); CI green.

**M3.9.B1 — Artifact Studio: CV generator + exporters**
- deps: M3.9.A1, M2.B0c
- files: `app/.../studio/cv/{CvTemplates,CvLayout,PdfRenderer,DocxWriter,JsonResumeExport}.kt`, `app/.../ui/studio/cv/*`, tests
- steps:
  - 4 templates in 3 languages
  - 1- and 2-page fitting
  - PDF (Android `PdfDocument`) and DOCX (minimal OOXML ZIP writer) export
  - JSON Resume export
  - a rich editor with write-back to the Self Model (confirmed)
- acceptance: tests that DOCX opens as a valid OOXML ZIP (structure validated) and PDF page count fitting; a device check opens the exports.

**M3.9.B2 — Tailoring + fabrication guard**
- deps: M3.9.B1, M3.8.A4
- files: `core/.../self/{JobRequirements,FitScorer,TailoringPlan,FabricationGuard,AtsCoverage}.kt`, a `cv_tailoring` seed capability, tests
- steps:
  1. Job → requirements.
  2. Evidence mapping and fit score.
  3. Bullet selection and rewrite linked to facts.
  4. The guard rejects unsupported claims.
  5. ATS keyword coverage.
  6. Diff against the base CV.
- acceptance: the guard test rejects an invented employer, metric and skill; a coverage test; an end-to-end test from fake job to tailored CV.

**M3.9.B3 — Other artifacts:** cover letter, bio, LinkedIn headline/about variants, portfolio one-pager, intro email. Same evidence discipline, templates in en/pl/tr. Tests for the guard per artifact type.

**M3.9.C1 — Outbound profile sync**
- deps: M3.9.A2, M4.A1, M4.D1
- files: `app/.../self/ProfileSyncPlanner.kt`
- steps: propose website/LinkedIn updates from the Self Model as an envelope-bound gated side effect (Always ask by default), executed via the web runtime or app with the humanizer.
- acceptance: tests for envelope building; the device check applies a test edit to the user's own site or profile.

### M4 — Channels

**M4.0 — Emulator test job in CI**
- deps: M0.B1
- files: `.github/workflows/build-memo-apk.yml` (new job `instrumented`), `app/build.gradle.kts` (androidTest deps: `androidx.test.ext:junit`, `androidx.test:runner`, `androidx.test:rules`, `androidx.test.uiautomator`; versions verified ≥2 weeks old), `app/src/androidTest/kotlin/app/memo/SmokeTest.kt`
- steps:
  1. Enable KVM.
  2. Run `reactivecircus/android-emulator-runner@v2.38.0`: API 34, x86_64, `google_apis`, run `./gradlew connectedDebugAndroidTest`.
  3. Upload test reports.
- acceptance: the smoke test (app launches, WebView loads an asset page) is green in CI.

**M4.A0 — Automation contracts in core**
- deps: M0.A1, M0.A5
- files: `core/.../automation/ActionVocabulary.kt`, `ActionEnvelope.kt`, `Locator.kt`, `Observation.kt`, `AgentLoopPolicy.kt` (bounds, no-progress, commit rules), `ActionJson.kt` (strict parse of model-chosen actions), tests with DOM/a11y JSON fixtures
- steps: implement §2a exactly as pure logic, with no Android dependencies.
- acceptance:
  - all §2a rules have unit tests, including every refusal path
  - the lead verifies each rule against §2a line by line

**M4.A0b — Web/app fixtures**
- deps: M4.0
- files: `app/src/androidTest/assets/fixtures/*.html`, `app/src/androidTest/kotlin/app/memo/fixtures/TestTargetActivity.kt` + manifest in `androidTest`
- steps: build the fixture pages and the test activity listed in §2a Verification 2.
- acceptance: the fixtures load in the emulator smoke test.

**M4.A1 — Headless in-Memo web runtime**
- deps: M0.A1, M0.A7, M4.0
- files: `app/.../platform/android/browser/WebWorkService.kt`, `WebHostWindow.kt`, `WebProfiles.kt`, `SafeWebViewClient.kt`, `WebSessionRegistry.kt`, manifest
- steps: implement §2b's headless web runtime exactly:
  1. Foreground service with the "working on N tasks" notification.
  2. Invisible overlay host window with a fixed virtual viewport; offscreen degraded mode.
  3. Per-service profiles.
  4. Re-parenting into the visible UI and back without restarting.
  5. At most 2 active WebViews, plus a queue.
  6. Stall watchdog leading to takeover.
  7. Hardened settings.
  8. Kill switch, battery and thermal pause.
  9. Results delivered via `Attempt.Pending` + `deliverExternal`.
- acceptance:
  - emulator test: a fixture page completes a fill + extract **while Memo's activity is in the background** (home pressed)
  - re-parent to visible and back keeps page state
  - the lead reviews the code line by line against §2a and §2b

**M4.A2 — Provider adapters + frontier executor**
- deps: M4.A1, M0.A6
- files: `core/.../adapter/ProviderAdapterPack.kt` (selectors/scripts as data, versioned), `app/.../platform/android/browser/FrontierExecutor.kt`, `app/.../platform/android/browser/Scripts.kt`, tests
- steps:
  1. Inject via focus + select + `execCommand('insertText')`, with a fallback to the native value setter.
  2. Send-button detection is multilingual and attribute-based, with an Enter fallback only when the adapter allows it.
  3. Send verification.
  4. Completion: no busy signal + text stable for N polls + minimum length; thinking-model aware (a longer stability window); hard timeout leads to takeover.
  5. Extraction is capped and returns the text plus a quote of the evidence.
  6. Return `Attempt.Pending`, then `deliverExternal`.
  7. Every send goes through a redaction preview and the audit.
- acceptance: JS-string unit tests (escaping, selector tables) + CI green.

**M4.L — Live view (§2o), split into four tasks**
- **M4.L1 — ActionNarrator + session state (pure)**
  - deps: M4.A0
  - files: `core/.../automation/{ActionNarrator,SessionState,ActionTimeline}.kt`, tests
  - acceptance:
    - narration in en/pl/tr for every action in the closed vocabulary
    - masked values never appear in narration
    - state transitions are tested
    - timeline retention and purge
- **M4.L2 — Web live view**
  - deps: M4.A1, M4.L1, M2.B0c
  - files: `app/.../ui/live/session/{LiveSessionScreen,GhostCursorOverlay,NarrationStrip,StepTimeline}.kt`, `app/.../platform/android/browser/{PreviewCapturer,TargetRects}.kt`
  - steps:
    - auto-open on user-started web runs
    - attach/detach of the real WebView
    - throttled preview capture
    - ghost cursor and highlight from target rects
    - visible typing
    - pause, step, take over, stop
    - touch auto-pause
    - inline approvals
    - masked snapshots
  - acceptance:
    - emulator test: a fixture run opened in the live view shows the highlight at the target rect
    - detach/attach keeps the page state with no reload
    - a user touch pauses the agent
    - preview fps is 0 when nothing is visible
- **M4.L3 — App live HUD**
  - deps: M4.D1, M4.L1
  - files: `app/.../ui/host/{TargetHighlight,ScreenSessionHud}.kt`
  - steps: highlight ring at the node bounds, narration and timeline in the console, touch auto-pause
  - acceptance: emulator test against the test target activity
- **M4.L4 — Previews everywhere**
  - deps: M4.L2, M4.D2, M3.B2, M4.A4c
  - files: live tiles in the overlay, the Live tab, council seats and the notification
  - acceptance:
    - CI green
    - a device check watches a background run from the bubble, then expands it to full screen

**M4.A3 — Browser tab (visible sessions, login once, watch/take over background runs)**
- deps: M4.A1
- files: `app/.../ui/browser/*`
- steps: tabs per profile, address bar (HTTPS only), "capture page as source", "discuss in chat", takeover banner, provider sign-in status.
- acceptance: CI green.

**M4.B1 — Accessibility service**
- deps: M0.A5, M0.A1
- files: `app/.../platform/android/automation/MemoAccessibilityService.kt`, `AutomationBus.kt`, `res/xml/memo_accessibility.xml`, manifest
- steps:
  1. The service is idle unless a run is active; it only observes the approved target package.
  2. Guards: lock pause, wrong-app pause, `ActionPolicy.classify` before every tap, never reads or types into password fields, bounded retries (≤18 at 450 ms).
  3. Actions: launch, tap (text/id/desc with clickable-ancestor walk), type (focused editable via `ACTION_SET_TEXT`), scroll, swipe via `dispatchGesture`, back, wait-text, read-screen (bounded node walk).
  4. Results go through `Attempt.Pending` + `deliverExternal`.
  5. Status is UNVERIFIED unless a wait-text confirms it.
- acceptance: CI green; the lead audits the guards.

**M4.B2 — Screenshot + OCR fallback**
- deps: M4.B1
- files: `app/.../platform/android/automation/ScreenReader.kt`
- steps:
  1. `takeScreenshot` (API 30+) / `takeScreenshotOfWindow` (34+), only while a step is active and never with a password field present.
  2. ML Kit OCR; tap by OCR bounding box only when no node matches and the policy allows it.
- acceptance: CI green.

**M4.B3 — AI-app adapters**
- deps: M4.B1, M4.C1
- files: `core/.../adapter/AppAdapterPack.kt`, tests
- steps: per AI app, define the input field heuristics, send action, response region and completion signal, with versions; the executor uses them for frontier-via-app steps.
- acceptance: tests + CI green.

**M4.C1 — Device-wide AI discovery**
- deps: M0.A7
- files: `core/.../discovery/SignatureRegistry.kt` (package id → kind/provider, each **verified via its Play listing URL returning 200** at implementation time), `app/.../platform/android/discovery/DeviceInspector.kt`, manifest `QUERY_ALL_PACKAGES` (rationale string)
- steps:
  1. Scan installed packages and match them against the registry (AI apps, browsers, local-AI apps).
  2. Detect the current WebView provider.
  3. Optional accessibility probe when the app is foreground.
  4. Rescan on package add/remove.
- acceptance: registry tests + CI green; the lead spot-checks 5 package ids.

**M4.C2 — Settings: channels + router wiring**
- deps: M4.C1, M0.A6
- files: `app/.../ui/settings/ChannelsScreen.kt`, `app/.../runtime/RoutingService.kt`
- steps:
  1. Toggles per discovered app, web provider and channel; kill switch; rate-limit settings.
  2. Router decisions are shown on each step ("why this provider").
- acceptance: CI green.

**M4.D1 — Native-app sessions (SynthOS Runtime pattern, §2c)**
- deps: M4.B1, M2.C3
- files: `app/.../platform/android/automation/ScreenSession.kt`, `app/.../ui/host/ScreenSessionHud.kt`
- steps:
  1. The planner marks native-app steps "needs your screen" and batches them.
  2. The user starts the session with one tap (bubble or notification).
  3. Memo launches the target app with the bubble console on top showing the step HUD.
  4. The §2a loop runs; Memo returns itself to the front when the session ends.
  5. Refuse to act if the console is not visible.
- acceptance:
  - emulator test against the test target activity
  - the lead reviews the code against §2a and §2b

**M4.D2 — Memo Overlay console**
- deps: M2.C3, M3.A3, M3.B1, M4.A0, M4.B1
- files: `app/.../platform/android/surface/OverlayService.kt`, `app/.../ui/overlay/*` (collapsed orb, expanded panel: Talk, Act on this screen, Running work, Approvals, Quick actions, kill switch)
- steps: implement §2b's floating console exactly. "Act on this screen" means one-shot observe of the foreground app → orchestrator plan → §2a loop with envelope approvals in the panel. Includes the acting-state HUD, tap-to-pause, lock-screen/secure-window hiding, and no self-taps.
- acceptance:
  - CI green
  - emulator test: the overlay is shown over the test target activity, and "act on this screen" runs a safe fill action with approval
  - the lead reviews the diff line by line against §2a and §2b

### M4.E — Teach & Select (§2m)

**M4.E0 — Schemas in core**
- deps: M4.A0, M0.A4
- files: `core/.../automation/{ExtractionSchema,ActionRecipe,CustomSchema,LocatorSet,Fingerprint,Transforms}.kt` + registry schemes + migration, tests
- steps:
  - strict validation
  - semver
  - locator-set stability scoring
  - transform functions
  - CustomSchema field-change → migration generation
- acceptance: round-trip, validation, scoring, transform and migration tests.

**M4.E1 — Web element picker + locator generator**
- deps: M4.A1, M4.E0
- files: `app/src/main/assets/picker/picker.js` (isolated, data-driven), `app/.../platform/android/browser/PickerBridge.kt`, `app/.../ui/browser/InspectMode.kt`, an androidTest fixture page, and instrumented tests
- steps: everything in §2m "Web element picker". Results return only through `evaluateJavascript` polling; there is no JS interface.
- acceptance: emulator tests on fixtures: a single element gets a unique locator; a list item generalises to N siblings; the shadow DOM element is selectable; the password value is never captured; the live match count is correct.

**M4.E2 — Schema builder UI (field mapping + list mode + preview)**
- deps: M4.E1
- files: `app/.../ui/teach/SchemaBuilder*.kt`
- acceptance: the emulator test builds a JobPosting extraction schema on a fixture list page and extracts all items correctly.

**M4.E3 — Web "Show Memo" recorder**
- deps: M4.E1, M3.A4
- files: `app/src/main/assets/picker/recorder.js`, `app/.../platform/android/browser/Recorder.kt`, `core/.../automation/RecordingGeneralizer.kt` (deterministic part) + agent prompt, tests
- acceptance: the emulator test records a fixture form fill, producing a recipe with parameterised values and a detected commit step; replay passes.

**M4.E4 — App element picker + recorder**
- deps: M4.B1, M4.D1, M4.E0
- files: `app/.../platform/android/automation/{NodeInspector,AppRecorder}.kt`, `app/.../ui/teach/AppInspectOverlay.kt`
- acceptance: the emulator test against the test target activity covers node selection, the locator set, recording and replay, and the protected password field.

**M4.E5 — Self-healing regenerator**
- deps: M4.E0, M3.A4, M3.8.A4
- files: `core/.../automation/{Regenerator,CandidateValidator}.kt`, `app/.../runtime/HealingService.kt`, tests + emulator fixture where the DOM changes between versions
- acceptance:
  - core tests for candidate validation
  - emulator test: the fixture v2 changes class names, and a read-only schema heals to a new TRIAL patch version automatically
  - the commit step requires approval
  - Experience is recorded

**M4.E6 — Workflow editor integration**
- deps: M4.E2, M4.E3, M4.E4, M3.5.B2
- files: `app/.../ui/workflow/*` (step pickers, recipe/schema version badges, upgrade/pin)
- acceptance: CI green; the device check builds a workflow by picking on a real page.

**Humanizer:** every web and app executor action goes through `Humanizer` (§2d), and the emulator tests assert non-constant timing.

**Automation acceptance (all of M4.A1–M4.D2):** the §2a emulator tests for that component are green, and the lead has reviewed the diff line by line against §2a.

**M4 close:** PR. Device checklist: sign in to ChatGPT in the Browser tab; run a consult step; take over and resume; enable accessibility and run "open Calculator and type 2+2" (safe app); check discovery lists the installed AI apps.

### M5 — Use cases end to end

**M5.A1 — UC-1 brainstorm**
- deps: M3, M4
- files: `core/.../plan/templates/Brainstorm.kt`, `app/.../runtime/ProjectFlows.kt`, `app/.../ui/work/ProjectScreen.kt`, tests
- steps:
  1. Parse project, idea and constraints.
  2. Create or reuse the Project, and attach principles.
  3. Compose prompts with `Prompts.frontierSystem`.
  4. Two or three routed consults, independent then critique.
  5. Local synthesis with evidence-checked quotes.
  6. Gate.
  7. Save a Note into the Project and append next-step Tasks.
  8. Works from the lock-screen session (side effects deferred).
- acceptance: an E2E JVM test with fake channels + CI green.

**M5.B1 — UC-3 jobs**
- deps: M3, M4, M3.9.B2, M3.8.A4
- files: `core/.../plan/templates/Jobs.kt`, `app/.../runtime/JobFlows.kt`, `app/.../ui/work/JobsScreen.kt`, tests
- steps:
  1. Discovery: browser capture of job search pages produces JobPostings (CAPTURE role, evidence-checked).
  2. List them for approval.
  3. For each approved job, spawn an application run: tailored cover letter (DRAFT) → gate (job + letter + the exact fields to submit) → browser/app apply.
  4. Log the JobApplication state.
  5. Never auto-submit without the gate.
- acceptance: E2E test + CI green.

**M5.C1 — UC-4 content → publish → measure**
- deps: M3, M4
- files: `core/.../plan/templates/Content.kt`, `core/.../metrics/*`, `app/.../runtime/ContentFlows.kt`, `app/.../platform/android/scheduler/MetricWorker.kt`, `app/.../ui/studio/*`, tests
- steps:
  1. Pick a source idea and apply ContentPreference.
  2. Draft (text, or carousel outline as slides).
  3. Gate shows the finished post + target channels.
  4. Publish via the browser adapter (LinkedIn/X composer) and capture the post URL into a SocialPost.
  5. Auto-provision METRIC_COLLECT runs via WorkManager at +1h/+24h/+72h/+7d, with a manual entry fallback.
  6. Studio: per-post charts plus strategy hints fed back into the packet.
- acceptance: E2E test + CI green.

**M5.C1a — Local image generation runtime**
- deps: M1.B2
- files: `app/.../platform/android/media/ImageGenRuntime.kt` (MediaPipe ImageGenerator 0.10.26.1; API taken from the AAR classes), `app/.../platform/android/media/LiteRtDiffusion.kt` (capability-gated FLUX.2-klein / Z-Image pipelines per their model cards), Hub entries for the image models (pinned + SHA)
- steps:
  1. Load the model, generate with prompt/negative/steps/seed, and report progress.
  2. Cancel.
  3. RAM/accelerator capability check.
  4. Save the output as an encrypted artifact.
- acceptance: CI green; device check generates a 512×512 image.

**M5.C1b — UC-4 media assets (image / carousel / video)**
- deps: M5.C1, M5.C1a, M3.A4, M4.A2, M2.A2
- files: `core/.../content/MediaSpec.kt` (asset specs: image prompt, carousel slides, video storyboard with scenes, captions, duration), `app/.../platform/android/media/CarouselRenderer.kt` (slides rendered from design tokens to PNG + PDF), `app/.../platform/android/media/VideoComposer.kt` (MediaCodec/MediaMuxer H.264 slideshow with Ken-Burns motion, captions, Kokoro voice-over), `app/.../runtime/ImageGeneration.kt` (frontier image generation via the in-Memo web runtime; the downloaded image is saved as an artifact), tests
- steps:
  1. The draft step chooses the asset type from ContentPreference.
  2. Carousels and videos are rendered locally.
  3. Images come from a frontier web consult (gated like any consult) or from user-supplied files.
  4. The gate shows the actual rendered assets plus target channels.
  5. Publishing uploads the files through the web/app adapter.
- acceptance:
  - tests for the spec → slides/storyboard mapping
  - CI green
  - device check: a carousel PDF and an MP4 render

**M5.C2 — UC-5 arbitrary plans**
- deps: M5.C1, M4.B2
- files: `core/.../plan/templates/Generic.kt`, tests
- steps:
  1. Generic browser/app sequences under the per-step caps (≤4 app actions per step, max steps).
  2. Game/chore loop: observe (screen read/OCR) → decide (local model) → act → verify, capped by iterations and wall time, always stoppable.
- acceptance: tests + CI green.

**M5 close:** PR. Device checklist: run UC-1, UC-3, UC-4 and UC-5 on a real phone.

### M2.5 — Speed (release build, profiles, benchmarks)

**M2.5.A1 — Release variant + persistent signing**
- deps: M0.B1
- files: `app/build.gradle.kts`, `app/proguard-rules.pro`, `.github/workflows/build-memo-apk.yml`, `docs/BUILD.md` (signing section)
- steps:
  1. Release build: R8 full mode, resource shrinking, keep rules for LiteRT-LM, sherpa-onnx, llama JNI and ML Kit.
  2. Signing config from env vars.
  3. A one-time `workflow_dispatch` job `create-release-key` that generates a keystore with `keytool` on the runner and prints setup instructions. The lead then stores the key as repo secrets (`MEMO_KEYSTORE_B64` etc.) with `gh secret set`; the key file is never committed.
  4. The build job assembles `assembleRelease` when the secrets exist and uploads `memo-release-apk`.
- acceptance: CI green with a signed release APK artifact; `apksigner verify` runs in CI.

**M2.5.A2 — Baseline profiles + macrobenchmarks**
- deps: M2.5.A1, M4.0, M2.B0d
- files: new module `:baselineprofile` (macrobenchmark), `app/build.gradle.kts` (profileinstaller, baselineprofile plugin), CI emulator job
- steps:
  1. Generate the profile on the CI emulator, covering startup, tab switches, chat streaming and list scroll.
  2. Add startup and frame-timing benchmarks, with results in the run summary.
- acceptance: the profile is bundled in the release APK, and benchmark numbers are reported against the §2i targets.

**M2.5.A3 — Performance pass**
- deps: M2.5.A2
- files: any UI file flagged by benchmarks or Compose reports
- steps:
  1. Enable Compose compiler metrics/reports in CI.
  2. Fix unstable params and main-thread I/O (StrictMode in debug).
  3. Meet the §2i targets.
- acceptance: benchmarks meet the targets on the CI emulator, and a device check confirms the app feels fast.

### M6 — Hardening + release

**M6.1 — Offline queue + boot recovery:** a WorkManager network-constrained resume; `resumeInterrupted` on boot.
**M6.2 — Diagnostics:** local event log, crash handler to an encrypted file, diagnostics screen, and outbound log viewer (filter and redaction view).
**M6.3 — Encrypted backup/export/import:** PBKDF2 with 600k iterations, passphrase ≥12 characters.
**M6.4 — App lock:** biometric with optional auth-bound key, `FLAG_SECURE` toggle, 60 s relock.
**M6.5 — Permission centre:** each permission with its rationale, status and a deep link.
**M6.6 — Lint strict** (`abortOnError = true`); fix all lint errors.
**M6.7 — Test sweep;** close coverage gaps in core.
**M6.8 — Docs:** README, BUILD.md (repo + CI + install; no zip/YAML), CHANGELOG (reference app → what was extracted → where it lives), STATUS.md.
**M6.9 — Final acceptance:** walk through UC-1…UC-5 against the code, with evidence links.

---

## 5. Verification summary
- Every task: the lead verification gate (§3) and green branch CI.
- Every milestone: green CI on `main` + `memo-apk` artifact, a code-review subagent pass, and the device checklist handed to the user.
- STATUS.md marks ✅ only with CI or device evidence.
