# Memo — Engineering Log

Living notebook per `AGENTS.md` standing rule 1. Append as you go; never rewrite history.

---

## DECISIONS

**D-001 · 2026-10-26 · Asset inventory resolved.** The 8 workspace files are 6 distinct assets: `all.zip` is an aggregate container (discard); the workspace-root `index.html`/`explainer.html` are byte-identical duplicates (81,730 / 19,704 bytes) of `memo-v2-prototype.zip`; distinct products are (1) SynthOS App Runtime (Java, v0.3.0 — execution spine), (2) SynthOS Fieldwork 3.1 (Compose — browser-frontier runtime + work graph), (3) SynthOS Pocket 1.5.0 (capture-first encrypted workspace + context policy), (4) Threadline 1.6.0 (voice surface + design/motion + pinned models + auth-bound vault), (5) Threadline "2.0.0" older revision (ideas vault: live feed, source mirror, action queue), (6) Memo v2 HTML prototype (product law / contracts). Consequences: build synthesis targets defined; Threadline "2.0.0" WebView-monolith architecture explicitly rejected.

**D-002 · 2026-10-26 · Delivery model.** Ship as a git repo at github.com/ysfclc/memo-2 with CI building APKs per push; supersedes the earlier "single zip + YAML" plan (user: "you dont have to zip"). CHANGELOG.md still maps source apps → new homes.

**D-003 · 2026-10-26 · Identity and modules.** App id `app.memo`, label "Memo". Two modules: `:core` (pure Kotlin, JVM-testable, zero Android imports) and `:app` (Android shell). Desktop/web later mounts `core`.

**D-004 · 2026-10-26 · Toolchain pins.** Gradle **8.14.6** (wrapper with distributionSha256Sum), AGP **8.13.0**, Kotlin **2.4.21** + `org.jetbrains.kotlin.plugin.compose` 2.4.21 (litertlm 0.18.x metadata pairs with Kotlin 2.4.2x — pocket audit note), compileSdk/targetSdk **35**, minSdk **30**, JDK **17**, LiteRT-LM **0.18.1**, Compose BOM **2024.12.01** (material3 1.3.1 via BOM), WorkManager **2.10.0**, biometric **1.1.0**, ML Kit text-recognition **16.0.1**, Gson **2.11.0**, org.json **20240303** (JVM-grade JSON for core), coroutines **1.9.0** (app-side), JUnit **4.13.2** + kotlin-test.

**D-005 · 2026-10-26 · Local model catalog = 3 pinned models.** Qwen3-0.6B dynamic-wi4b32 (~345MB), Qwen3-1.7B (~977MB), Qwen3-4B-Thinking (~2.48GB) — exact-size + SHA-256 verification, `.part → .previous → active` staged rollback (Threadline pattern). Orchestrator default = 0.6B; deep reasoning = 1.7B/4B on explicit choice. Thinking budget: off for routine steps/chat; on only for the 4B deep-planner profile (this also resolves the Threadline `chat()` vs `complete()` discrepancy).

**D-006 · 2026-10-26 · Frontier routing is browser-first.** Frontier AIs are reached through their real websites in Memo's hardened WebView (Fieldwork `BrowserSession`/`BrowserScripts` port); installed AI apps via accessibility automation as second channel; OpenAI-compatible API channel exists but is opt-in with explicit key + consent per request. No provider is assumed to have an API. Provider registry seeded with 8: chatgpt.com, claude.ai, gemini.google.com/app, grok.com, chat.deepseek.com, perplexity.ai, chat.mistral.ai/chat, copilot.microsoft.com.

**D-007 · 2026-10-26 · Security posture.** `MemoVault` AES-256-GCM per-collection with AAD binding (`memo:<type>:<id>:<v>`), atomic tmp+fsync+rename writes, 3-deep rolling `.bak` self-heal; credential store separate; opt-in auth-bound wrapping (Threadline `setUserAuthenticationRequired` + CryptoObject pattern); TLBK passphrase backups (PBKDF2-SHA256 600k, ≥12 chars); hash-chained append-only outbound audit; redaction pipeline (scope-limited prompts + pattern pack); hard kill switch (Quick Settings tile + notification action); permission repair loop per channel; FLAG_SECURE + 60s background relock; no analytics/crash SDKs.

**D-008 · 2026-10-26 · Design language = Paper + Mint (merged).** Warm paper surfaces (#F4F2EC family), ink #222920, evergreen primary #26524A, mint action #B2EFCF, teal links #276B56, amber caution #B25A24; dark remaps from Threadline dark set. Type 32→11sp scale; shapes 8/12/18/24/30; 4dp base rhythm. Motion ladder: press 0.965/85-170ms `PathInterpolator(0.2,0,0,1)` + KEYBOARD_TAP haptic; entrance 230ms +7dp `PathInterpolator(0.16,1,0.3,1)`; stagger 235ms/28ms ×9; crossfade 180ms; PulseOrb live breathing 1650ms 0.78→1.12; shimmer skeleton 1150ms (zero-alloc port); respect `ValueAnimator.areAnimatorsEnabled()` + reduced motion; **never animate a fake response** (pocket law).

**D-009 · 2026-10-26 · Remote-runner protocol excluded from v1.** The Python ADB guest runner stays a roadmap item; v1 executes on-device + in-app browser only. Protocol discipline (validated payloads, `approved:true`, postconditions) is inherited by the workflow engine's gates.

**D-010 · 2026-10-26 · Voice & locked-screen honesty.** Android exposes no third-party locked-screen hotword API; Memo states this in Settings and ships the degrade ladder: in-app voice session (on-device recognizer, API 31+, 650ms re-listen), Quick Settings tile, lock-screen notification action (content-free, biometric handoff), opt-in overlay bubble tap, opt-in key-filter accessibility trigger. No hidden listening, ever.

**D-011 · 2026-10-26 · Workflow DSL v1.** Step kinds exactly: `reason · browser_automation · app_automation · human_approval · data_persist · metric_collect · branch · retry`. Sequential execution; `CONSULT_AND_COMPARE`-style parallelism only between side-effectless reason consults. Status machine: `PENDING→RUNNING→COMPLETED|FAILED|SKIPPED|NEEDS_REVIEW→WAITING_USER`; process death → `INTERRUPTED` (never auto-resumes side-effectful steps; resume re-verifies context). Approvals bind to artifact `contentHash` (TOCTOU guard) — memo-v2 prototype law: "approval is bound to content".

**D-012 · 2026-10-26 · Engine admission = projection round-trip.** `WorkflowPolicy.validateSpec` (the check every engine `createRun` performs) validates a compiled spec by feeding ONLY the parser's input contract (title + steps) back through `PlanProposalParser.parse` — never the full `WorkflowSpec.toJson` envelope, whose `id`/`createdAt`/`schemaVersion` bookkeeping is unknown top-level text to the parser (as written it rejected every VALID spec; caught by tests before a single commit). One law, two doors: model text → parser; user spec → projection → same parser.

**D-013 · 2026-10-26 · Branch-on-failure rescue.** A step that FAILs without a retry policy does NOT immediately fail the run when a LATER BRANCH step explicitly watches that step's FAILED outcome: the engine diverts to that branch (steps between are SKIPPED with "run diverged"); unwatched failures fail the run as before. This makes `metric fails → fallback persist` plans executable while preserving fail-fast.

**D-014 · 2026-10-26 · RETRY declarations are honored, never defaulted.** `{"kind":"retry","target":"x","attemptsMax":N}` folds N verbatim onto the target (bounds-checked against `MIN_STEP_ATTEMPTS..MAX_STEP_ATTEMPTS`; out-of-bounds = rejected plan). The first implementation silently discarded N and substituted defaults — caught by `foldsRetryDeclarationsOntoTargets`.

**D-015 · 2026-10-26 · `JSONObject.NULL` is a first-class null in CanonicalJson.** org.json silently REMOVES a key when `put(key, null)` is called, and its `JSONObject.NULL` sentinel otherwise falls through the writer and renders as the STRING `"null"` — inside the hash pipeline that is silent chain poisoning. The canon writer now treats the sentinel as JSON `null`; tests pin both behaviors.

---

**D-016 · 2026-10-26 · Full-product build plan adopted (`docs/BUILD_PLAN.md`).** Supersedes the M2/M3 scope in D-010 and M2_DESIGN where they conflict. Key choices: (1) local inference via BOTH LiteRT-LM 0.18.0 (`.litertlm`) and llama.cpp b11460 via JNI (GGUF), with live Hugging Face discovery pinned by revision + per-file SHA-256 from the tree API; (2) fully local voice via sherpa-onnx v1.13.8 (streaming Zipformer/Whisper ASR, Kokoro/Piper TTS, Silero VAD, keyword spotter). The system `RecognizerIntent` pop-up and default TTS are rejected by the user; (3) locked-screen path = default-assistant role (VoiceInteractionService) + opt-in "Hey Memo" keyword spotter in a microphone foreground service, which refines D-010; (4) engine gains `Attempt.Pending` + `deliverExternal` for async browser/app steps, and the missing `audit.record` wiring is fixed; (5) implementation by Haiku subagents, one small task each, with every task verified by the lead before merge. Alternatives considered: LiteRT-only (rejected: GGUF ecosystem coverage), system speech (rejected by user), porting reference apps (rejected: poor implementations, API evidence only). Consequences: NDK/CMake in CI, bigger APK (arm64-v8a + x86_64 only), milestone merges M0…M6.

## CONSTRAINTS

**C-001 · Build machine has no JDK / no Android SDK / no Gradle** (`java`, `gradle` absent; `ANDROID_HOME` unset; `%LOCALAPPDATA%\Android\Sdk` missing). All compilation happens on GitHub Actions («ubuntu-latest»). Working loop = careful code + push + `gh run watch` → read logs → fix → push. Local APK impossible here; APKs come from CI artifacts.

**C-002 · Push credentials verified.** `gh` logged in as **ysfclc**, scopes include `repo` + `workflow` (required to push `.github/workflows/*`); git identity `ysfclc <in4idea@gmail.com>`.

**C-003 · CI SDK install quirk.** `android-actions/setup-android@v3` attempts the removed SDK `tools` package on hosted runners — configure the **preinstalled SDK** and locate `sdkmanager` under `cmdline-tools` (pattern validated in source audit of SynthOS runtime CI).

**C-004 · Kotlin vs LiteRT-LM metadata.** Keep Kotlin in the 2.4.2x line when using litertlm-android 0.18.1; if metadata mismatch errors appear in CI, adjust Kotlin patch version before touching model code.

**C-005 · Gradle release verification.** Wrapper must include `distributionSha256Sum`; fetch the official `gradle-X-bin.zip.sha256` from services.gradle.org rather than trusting truncated SHA strings from anywhere.

**C-006 · Kotlin 2.4 removed the `kotlinOptions` DSL from build scripts.** First M0 CI run (40332432835) failed with `Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL`. In `.gradle.kts` files, `import org.jetbrains.kotlin.gradle.dsl.JvmTarget` goes at the very top (before `plugins {}`), and JVM target moves to a top-level `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` block. Any new module must use this DSL, not `kotlinOptions`.

**C-007 · 2026-10-26 · Reliability repair pass.** Replaced invalid Compose theme/scaffold placeholders with Material 3 APIs, fixed the duplicate motion implementation, corrected Kotlin `Result` and JSON validation usage, fixed packet size accounting, and hardened browser bridge state to bind callbacks to the active outbound event. Local verification is UNVERIFIED because this sandbox has no JDK/Gradle/Android SDK; GitHub Actions remains the authoritative build path.

---

## NOTES BY MILESTONE

### M0 · Recon & synthesis (pre-code, this repo empty)

Security flags inherited from the source apps and their Memo resolutions (full analysis lives in the approved Architecture & Design Document conversation; the essentials):

| # | Origin | Finding | Resolution |
|---|---|---|---|
| 1 | app-release | README claims "no INTERNET permission" but manifest declares it | Memo declares INTERNET consciously: NetworkPolicy + per-channel toggles + hash-chained outbound audit + kill switch |
| 2 | app-release | Remote runner: no pinning, no idempotency keys, bearer = whole trust domain | v1 excludes the runner (D-009); engine inherits its `approved:true` + postcondition discipline |
| 3 | app-release | Custom `gradlew` downloads Gradle with no checksum | Real wrapper + `distributionSha256Sum` |
| 4 | fieldwork | Keystore keys not auth-bound | Opt-in auth-bound wrapping (Threadline pattern) |
| 5 | fieldwork | `window.__synthosSubmitState` is page-attacker-reachable; blanket per-workflow consent | Verify submission by visible conversation change (composer cleared + new user message); consent enforced per-submission via gate steps |
| 6 | fieldwork | No kill-switch/outbound log/redaction; browser workflow state not persisted | Built fresh; browser runs journaled step-by-step with `WAITING_USER` takeover |
| 7 | threadline-vo | `chat()` vs `complete()` thinking-config discrepancy; duplicated RAM thresholds | Single ModelProfile source of truth in catalog (D-005) |
| 8 | threadline-v1 | Continuous passive live feed + plaintext exports | Observation becomes opt-in tap-triggered only; exports default encrypted (TLBK), plaintext export requires explicit confirm |
| 9 | all | Stale version strings everywhere | `BuildConfig` is the single source; screens render `VERSION_NAME` |
| 10 | pocket | DESIGN_MOTION.md says "no internet permission" while manifest has it | Same resolution as #1 — a doc-vs-manifest divergence is a release blocker; Memo's docs must always match the shipped manifest |

Extraction map (source → Memo home):

- app-release `WorkflowPolicy/PlanProposalParser/SkillRegistry/RunRecord` → `core/plan`, `core/engine`
- app-release `AutomationAccessibilityService` → `app/automation/MemoAutomationService`
- app-release CI YAML → `.github/workflows/build-memo-apk.yml` (ZIP logic removed per D-002)
- fieldwork `BrowserSession/BrowserScripts/BrowserWorkflow` → `app/browser/*`
- fieldwork `EncryptedVault/ProviderSettingsStore/AppSnapshot` → `app/vault/MemoVault`, `app/vault/CredentialStore`, `core/codec`
- fieldwork `OutcomeParser/FollowUpTaskFactory/RuntimeContracts` → `core/plan/OutcomeParser`, `core/engine` (factories become engine primitives), `core/domain/RuntimeRegistry`
- pocket `SecureStore/CaptureCodec/PromptComposer/MissionContextPolicy/ModelDownloader` → `app/vault/MemoVault`, `core/codec/WorkspaceCodec`, `core/prompts/*`, `app/models/ModelManager`
- threadline-vo `OnDeviceAi/SecureVault/BackupCrypto/AssistantModels/ThreadlineDesignSystem/ThreadlineMotion/PulseOrbView/ShimmerSkeletonView/ReminderScheduler` → `app/ai/LocalAssistant`, `app/vault/MemoVault`, `app/vault/BackupCrypto`, `core/plan` (typed actions), `app/ui/MemoTheme.kt`, `app/ui/MemoMotion.kt` (Compose ports)
- threadline-v1 `AssistantStore/action-queue/deterministic commands/evidence gates/SourceMirrorWorker/ModelCatalogueWorker` → `core/engine/pending queue`, `core/plan/CommandGrammar`, `core/plan (evidence law)`, `app/scheduler/DiscoveryWorker`, `app/scheduler/ModelCatalogWorker`
- memo-v2 prototype → applied as product law (checkpoint semantics, content-bound approvals, routing trace, component contracts); not shipped as HTML

(End of M0.)

### M1 · `:core` — domain, registry, plan DSL, engine, prompts, audit, redaction

Shipped (all under `core/src/main/kotlin/app/memo/core/`; pure JVM, each class compiles and carries its law in the doc header):

- `Limits.kt` — the single source of hard bounds (plans, packets, outputs, evidence, audit, registry capacity). Engine refuses instead of truncating.
- `util/Sha256Hex.kt` — one UTF-8 SHA-256 helper so digests are identical everywhere they are produced.
- `codec/CanonicalJson.kt` — sorted-key, plain-number deterministic JSON; every hash input (natural keys, content signatures, gate bindings, audit links, run ids) goes through it. `JSONObject.NULL` handled as first-class null (D-015).
- `codec/SynCodec.kt` — the SYN envelope: strict validate → migrate → load. A bad envelope is rejected WHOLE; bad rows inside a valid envelope quarantine per registry law. Migration chain headed at v1.
- `domain/` — `Evidence` (verbatim law), `People`/`Work`/`Content` (the eight registry entities with `contentJson` + `isInvalid`), `Workflow` (8-step DSL, `StepSpec`/`StepState`/`AgentRun` with JSON persist/restore), `Audit` (`OutboundEvent` + chained `AuditEntry`).
- `registry/` — `NaturalKeys` (stable short-hash ids, state/timestamp free), `Registry` (Created / Reused / Bumped(v+1) / Failed; load conflicts + blank keys quarantine, never overwrite), `Schemes`, `Workspace` (eight registries in canonical order).
- `plan/` — `Parsing` (string-aware balanced-brace locator), `OutcomeParser` (whitespace-normalized case-SENSITIVE verbatim containment; `extractNumber` rounds DOWN, k/M/B flagged approximate), `CommandGrammar` (zero-model commands: cancel/reminder/task/crm-person/draft; clock-time rollovers past times to tomorrow), `PlanProposalParser` (strict compiler: no-JSON rejection, unknown-field refusal, per-kind requirements, gate law, provider whitelist, step-id shape, duplicate ids, caps, RETRY folding honoring declared attemptsMax — D-014), `WorkflowPolicy` (side-effect discipline, branch ref integrity, `validateSpec` projection round-trip — D-012).
- `prompts/` — `ContextPacket` (bounded scope-limited packet, visible `[packet truncated at N chars]` markers), `Prompts` (orchestrator + frontier system/envelope; ARTIFACT_KINDS are the shared constant the parser also accepts).
- `catalog/` — `ModelCatalog` (three pinned litert-community models with VERBATIM byte sizes and SHA-256 pins and `/resolve/<rev>/` URLs; `MIN_MODEL_BYTES` sanity floor) and `ProviderRegistry` (eight frontier providers, byte-verified hosts + start URLs).
- `engine/WorkflowEngine.kt` — the checkpoint executor: sequential pointer advance; gate steps open `WAITING_USER` and never auto-close; approvals bind `contentHash` (deliver re-verifies, mismatch → `NEEDS_REVIEW`); veto skips side effects only up to the NEXT gate; retry backoff writes explicit `resumeAt` (clock-honored, no wall-clock assumptions); attempt ids `runId:stepId:attempt` bound in-memory (app journal owns re-execution safety); interrupted side effects NEVER auto-resume; metric steps must return `Measured` and persist `metric_sample` + `post` shell; `data_persist` routes through `DataStore` with natural keys; branch steps switch on prior outcomes with failure-rescue semantics (D-013); one `OutboundEvent` per completed side effect, hash-chained.
- `redact/Redactor.kt` — pattern pack (PEM blocks, `sk-`/`ghp_`/`AKIA…`/JWT/bearer/secret-assignments/card runs), visible `[REDACTED_*]` markers, overlap resolution by start-then-length.
- `audit/HashChainAudit.kt` — append-only SHA-256 chain over `CanonicalJson` links (genesis `000…0`), full-snapshot sink, `verify()` localizing tamper (sequence break / chain break / hash mismatch), restorable from entries.

Tests: 11 JVM suites under `core/src/test/kotlin` run via `:core:test` (JUnit 4 + kotlin-test): `CanonicalJsonTest`, `SynCodecTest`, `RegistryTest`, `OutcomeParserTest`, `PlanProposalParserTest`, `CommandGrammarTest`, `WorkflowEngineTest` (13 flows: gate bind/mismatch, veto-to-next-gate, branch rescue incl. retry fold, interruption both ways, restore-persist-continue, metric persist, backoff clock, cancel punctuation), `RedactorTest`, `HashChainAuditTest`, `CatalogTest` (pins are the contract), `PromptContractTest` (prompt↔parser single-document law).

Bugs the suite caught before a single commit was pushed (ci-is-the-compiler):

1. `WorkflowPolicy.validateSpec` round-tripped the FULL compiled-spec envelope through the parser — `id`/`createdAt`/`schemaVersion` are unknown top-level fields, so EVERY valid spec (and therefore every engine `createRun`) would have been rejected. Fixed via title+steps projection (D-012).
2. RETRY declarations discarded the author's `attemptsMax`, silently substituting defaults — parser now folds it, bounds-checked (D-014).
3. org.json `put(key, null)` REMOVES the key, and the `JSONObject.NULL` sentinel rendered as the string `"null"` — a hash input printing the wrong bytes is silent chain poisoning. Explicit sentinel branch (D-015), tests pin both.
4. Three `failState(...)` calls in `persistMetric` lost their `now` argument in a mid-write edit — wrong-arity, caught by re-reading the whole engine file, not by hope.
5. Gate steps would have been routed to the executor as ordinary work (closing as COMPLETED) instead of opening `WAITING_USER` — dispatch order fix.
6. Cancel commands with trailing punctuation ("Never mind.") missed the cancel set — grammar strips `.!?` before matching.
7. A fixture step id `persist-draft` (hyphen) hit the parser's strict step-id shape law `^[a-z][a-z0-9_]{0,23}$` — law kept, fixture renamed; the law is now documented here.

Notes for M2+: the executor interfaces (ReasonExecutor, BrowserExecutor, AppAutomationExecutor, MetricExecutor, ArtifactBuilder, DataStore, OutboundAudit) are the EXACT seams the Android app layer must fill; the engine's attempt cache is in-memory only — process-death re-execution safety is the app-side journal's responsibility (idempotency keys are provided); launcher manifest still has zero permissions (notification channel and work scheduling arrive with M2).

(End of M1.)

### M1 fix pass (same day, first CI red on 24ff4af)

Four compile errors, all caught by `:core:compileKotlin` on the runner in 22s — the loop works exactly as designed:

1. `BigDecimal` has no `toLongOrNull` (stdlib Scoping on String/Number subclasses; BigDecimal is neither) — overflow-safe DOWN-rounded extraction now goes through `longValueExact()` inside `runCatching`.
2. The registry quarantine list is `quarantined`; five mutator sites had been typed `quarantine +=` — one name, many call sites, compiler catches every one.
3. Version bumps out of generic `T` need scheme knowledge: new `RegScheme.versionOf(item)` (8 one-line implementations) keeps the "bump by exactly one" law visible at the single call site instead of leaking `existing.version` into untyped code.
4. `whenContains` used `now` without carrying it as a parameter — pure-clock discipline means every time-dependent helper takes `now` explicitly.

(End of M1 fix pass.)

### M0 (build plan) · domain v2

- Eight v2 entities in `domain/Knowledge.kt` (Project, Note) and `domain/Library.kt` (WorkflowTemplate, PromptTemplate, ArchitecturalPrinciple, ContentPreference, ChannelAccount, ProviderAdapter), each with the v1 codec shape: `toJson`, `contentJson`, `isInvalid`, `fromJson` returning null on garbage, `version` defaulting to 1.
- Natural keys in `NaturalKeys` (`pj:`, `nt:`, `wt:`, `pt:`, `pr:`, `cp:`, `ca:`, `ad:`); blank input yields `""` so the registry quarantines it. Note keys include the project (blank = unfiled), the title slug and the creation day. ChannelAccount keys strip a leading `@` from the handle.
- Two `Limits` additions only: `MAX_LIST_ITEMS = 32` bounds every string list, and `MAX_JSON_PAYLOAD_CHARS = 64_000` bounds plan and adapter JSON (12 steps times 4000-char prompts plus framing).
- Design choices: `ChannelAccount.enabled` defaults to false (no silent outbound action); `ProviderAdapter.channel` is validated against `web`/`app` and `adapterVersion` against `major.minor.patch` text; `WorkflowTemplate` and `ProviderAdapter` require their JSON payloads to parse as objects, and a plan additionally needs a title and 1 to MAX_STEPS_PER_RUN steps.
- Schemes for all eight entities are added in `Schemes.kt`. `Workspace.kt` and `SynCodec.kt` are untouched; registering these in the workspace and the v1→v2 migration belong to M0.A4.
- Tests: `DomainV2Test` covers round-trip, invalid and garbage decoding, key normalisation and blank keys, and the Created→Reused→Bumped registry path for Project and WorkflowTemplate.
