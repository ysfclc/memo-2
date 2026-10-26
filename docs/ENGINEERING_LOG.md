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

---

## CONSTRAINTS

**C-001 · Build machine has no JDK / no Android SDK / no Gradle** (`java`, `gradle` absent; `ANDROID_HOME` unset; `%LOCALAPPDATA%\Android\Sdk` missing). All compilation happens on GitHub Actions («ubuntu-latest»). Working loop = careful code + push + `gh run watch` → read logs → fix → push. Local APK impossible here; APKs come from CI artifacts.

**C-002 · Push credentials verified.** `gh` logged in as **ysfclc**, scopes include `repo` + `workflow` (required to push `.github/workflows/*`); git identity `ysfclc <in4idea@gmail.com>`.

**C-003 · CI SDK install quirk.** `android-actions/setup-android@v3` attempts the removed SDK `tools` package on hosted runners — configure the **preinstalled SDK** and locate `sdkmanager` under `cmdline-tools` (pattern validated in source audit of SynthOS runtime CI).

**C-004 · Kotlin vs LiteRT-LM metadata.** Keep Kotlin in the 2.4.2x line when using litertlm-android 0.18.1; if metadata mismatch errors appear in CI, adjust Kotlin patch version before touching model code.

**C-005 · Gradle release verification.** Wrapper must include `distributionSha256Sum`; fetch the official `gradle-X-bin.zip.sha256` from services.gradle.org rather than trusting truncated SHA strings from anywhere.

**C-006 · Kotlin 2.4 removed the `kotlinOptions` DSL from build scripts.** First M0 CI run (40332432835) failed with `Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL`. In `.gradle.kts` files, `import org.jetbrains.kotlin.gradle.dsl.JvmTarget` goes at the very top (before `plugins {}`), and JVM target moves to a top-level `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` block. Any new module must use this DSL, not `kotlinOptions`.

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
