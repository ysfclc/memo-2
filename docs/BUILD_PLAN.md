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

### Milestone close
- Integrate all tasks into `milestone/Mx`, CI green, then a code-review subagent pass and fixes.
- Open a PR to `main`; merge on green.
- Update STATUS.md (✅ only with evidence), ENGINEERING_LOG.md and CHANGELOG.md.
- Give the user the per-milestone device checklist.

### Parallelism
- At most 3 Haiku agents at once, only on tasks with disjoint file ownership and no unmet dependencies (see `deps`).

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
- deps: M1.B2, M2.B0
- files: `app/.../ui/models/*`
- steps:
  1. Sections: Installed / Recommended for this phone / Browse (live search, format filter, family chips) / Voice models (filled in M2) / Import.
  2. Each card shows size, RAM fit badge, licence, gated lock, a one-tap Download with progress ring, and Use/Default/Delete actions.
  3. Per-model settings sheet: system prompt, temperature, context, thinking.
- acceptance: CI green; screenshots are not possible, so the lead reviews the composables against the design tokens.

**M1.C1 — Conversation store**
- deps: M0.C1
- files: `core/.../chat/Conversation.kt` (Conversation, ChatMessage{role, text, modelId, voiceClipRef?, createdAt, status}), `app/.../runtime/ChatRepository.kt`, tests
- steps:
  1. Encrypted persistence, one file per conversation.
  2. Titles generated from the first message, with a local summarisation fallback.
  3. Search.
- acceptance: tests for codec and ordering.

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

**M1 close:** PR. Device checklist:
- download Gemma 4 E2B (LiteRT) and Llama 3.2 3B Q4_K_M (GGUF)
- chat with both
- stop mid-generation
- switch models

### M2 — Voice

**M2.B0 — Design system + motion (prerequisite for UI tasks)**
- deps: M0
- files: `app/.../ui/theme/MemoTokens.kt`, `MemoTypography.kt`, `MemoMotion.kt`, `app/.../ui/components/*` (MemoCard, Pill, MemoButton with press scale, Shimmer, SectionHeader, EmptyState, StatTile, StepRail, PresenceOrb)
- steps:
  - D-008 values: press 0.965 at 85–170 ms; entrance 230 ms with +7 dp and easing (0.16, 1, 0.3, 1); stagger 28 ms ×9; crossfade 180 ms; orb breathing 1650 ms (scale 0.78→1.12); shimmer 1150 ms; respect `areAnimatorsEnabled`
  - PresenceOrb states: idle, listening (level-reactive), thinking, speaking (audio-reactive), waiting
  - migrate existing screens onto the tokens
- acceptance: grep shows no `Color(0x` outside `ui/theme`.

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

**M2.C2 — "Hey Memo" wake listener**
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
  2. Bubble: draggable orb; tap opens a mini voice panel.
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

**M4.A1 — Browser runtime**
- deps: M0.A1, M0.A7
- files: `app/.../platform/android/browser/BrowserRuntime.kt`, `WebProfiles.kt`, `SafeWebViewClient.kt`
- steps:
  1. A WebView per profile, using androidx.webkit multi-profile with a feature check. Fallback: a shared jar plus a visible warning and per-provider clear.
  2. Hardened settings: no file/content access, HTTPS only, Safe Browsing, no JS bridge (results come only through `evaluateJavascript`).
  3. Session persistence.
  4. Human takeover: the step goes to a "needs you" state, with a notification and the Browser tab; the run continues with `deliverExternal`.
- acceptance: CI green; the lead audits the settings.

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

**M4.A3 — Browser tab**
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

**Automation acceptance (all of M4.A1–M4.B3):** the §2a emulator tests for that component are green, and the lead has reviewed the diff line by line against §2a.

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
- deps: M3, M4
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

**M5.C2 — UC-5 arbitrary plans**
- deps: M5.C1, M4.B2
- files: `core/.../plan/templates/Generic.kt`, tests
- steps:
  1. Generic browser/app sequences under the per-step caps (≤4 app actions per step, max steps).
  2. Game/chore loop: observe (screen read/OCR) → decide (local model) → act → verify, capped by iterations and wall time, always stoppable.
- acceptance: tests + CI green.

**M5 close:** PR. Device checklist: run UC-1, UC-3, UC-4 and UC-5 on a real phone.

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
