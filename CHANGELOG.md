# Changelog

All notable changes to Memo. Format: Milestone · date · summary.
Per `AGENTS.md`, decisions live in `docs/ENGINEERING_LOG.md`.

## 1.0.0 (in development)

### M0 · 2026-10-26 · Repository bootstrap

- Standing rule adopted: everything learned/decided is written to
  `docs/ENGINEERING_LOG.md` in the same commit (repo `AGENTS.md` + global rule).
- Asset synthesis complete: five prototypes + one HTML product-law document →
  unified architecture. Source mapping below.
- Gradle 8.14.6 wrapper (official SHA pinned), AGP 8.13.0, Kotlin 2.4.21,
  JDK 17, minSdk 30 / target + compile 35.
- CI pipeline `build-memo-apk.yml`: structure checks → JDK 17 → preinstalled-SDK
  sdkmanager discovery → SDK packages (android-35, build-tools 35.0.0) →
  `testDebugUnitTest lintDebug assembleDebug` → `memo-apk` artifact (14 days,
  error if missing).
- Minimal real app shell (brand splash, version surfaced) so the first commit
  already produces an installable APK.

### Source app → Memo mapping (from D1's CHANGELOG requirement)

| Source app | What was extracted | Where it now lives |
|---|---|---|
| **SynthOS App Runtime** (app-release) | Workflow step primitives + `WorkflowPolicy` blocklists | `core` plan/policy (merged into typed-step DSL) |
| | Strict plan-proposal parsing, evidence-grounding, 8-char task refs | `core/plan` parsers (retired the 8-char trick; replaced by bounded refs with checksums) |
| | Run state machine (9 statuses, checkpoint, VERIFIED postconditions) | `core/engine` run/step semantics |
| | Skill registry (versioned, always-on policy/evidence skills) | `core/prompts` (skills as prompt modules, not fake permissions) |
| | Accessibility executor safety guards (lock-pause, wrong-app pause, bounded retries, password exclusion) | `app/automation/MemoAutomationService` |
| | Remote-runner protocol (HTTPS, bearer vault, approved:true gate) | Roadmap slot (v1 excluded); its gate discipline inherited by engine approvals |
| | CI workflow patterns (sdkmanager discovery, artifact checks) | `.github/workflows/build-memo-apk.yml` (ZIP logic dropped per delivery change) |
| | Design/motion ladder (durations, press-scale, animator-scale respect) | `app/ui/MemoMotion.kt` |
| **SynthOS Fieldwork 3.1** (fieldwork-2) | Hardened WebView browser + in-page script suite (prepare/send/verify/poll, no-resend, manual takeover) | `app/browser/MemoBrowserSession` + `BrowserScript` string builders |
| | Frontier provider registry + prompt compiler (roles, untrusted-output framing, budgets) | `core/domain/ProviderRegistry`, `core/prompts/FrontierComposer` |
| | Work graph (entities/relations/review lifecycle) + budgeted snapshot codec | `core/domain` + `core/codec/WorkspaceCodec` |
| | Verbatim-evidence outcome parsing + capability-gated follow-up factory | `core/plan/OutcomeParser`, engine persistence steps use its law |
| | AES-GCM vault with AAD + AtomicFile; separate credential store | `app/vault/MemoVault`, `app/vault/CredentialStore` |
| | LiteRT-LM GPU→CPU fallback runner + model catalog lifecycle | `app/ai/LocalAssistant`, `app/models/ModelManager` |
| | Consent-gated OpenAI-compatible client (opt-in API channel) | `app/ai/compatible/OpenAiCompatibleClient` (guarded) |
| | Compose tokens (SynthOsTheme) | superseded by unified Paper+Mint `app/ui/MemoTheme.kt` |
| **SynthOS Pocket** (synthos-pocket) | `SYN1` AES-GCM store discipline (atomic, never-overwrite) + capture `originalContent` provenance taxonomy | `app/vault/MemoVault`, `core/domain` provenance fields |
| | Versioned backup schema w/ migrations + strict validation | `core/codec/WorkspaceCodec` |
| | Bounded prompt composition (selected-sources-only, caps, truncation markers, anti-injection, honesty contract) | `core/prompts/ContextPacket` |
| | Staged model download (.part/.previous rollback, https-after-redirect) | `app/models/ModelManager` |
| | Bounded share/attachment import (5 files/2MB/6MB rolling) | `app/engine/IntakeReceiver` (share intents) |
| | Floating capture bubble + ShareTargetChosenReceiver | M2/M3 tiles + share-in; overlay bubble = opt-in (v1 uses Quick Settings tile) |
| | `DESIGN_MOTION.md` (palette/type/spacing, 180ms doctrine, "never animate a fake response") | `app/ui/MemoTheme.kt`, `MemoMotion.kt` |
| **Threadline 1.6.0** (threadline-vo) | Voice session (on-device recognizer, re-listen loop, offline-TTS-only, presets) | `app/voice/VoiceEngine` |
| | Auth-bound vault (wrapped data key + BiometricPrompt CryptoObject) + TLBK passphrase backups | `app/vault/MemoVault`, `BackupCrypto` |
| | SHA-256-pinned model downloads + rollback + RAM preflight + thinking routing | `app/models/ModelManager` + `core/prompts` |
| | Typed assistant actions validated against real IDs (never inferred from prose) | `core/plan` action schema |
| | Design system + motion constants + PulseOrb/Shimmer math | `app/ui/MemoTheme.kt`, `MemoMotion.kt` (Compose ports) |
| | Content-free reminder notifications + boot reschedule | `app/scheduler/*` |
| | Tap-only screenshot→OCR accessibility bridge (never passive) | `app/automation/ScreenCaptureBridge` (M3) |
| | Deterministic command parser before LLM | `core/plan/CommandGrammar` |
| **Threadline "2.0.0"** (threadline-app v1) | Pending-actions queue with ID-ack surviving app death | `core/engine` checkpoint queue |
| | Evidence-gated interpretation (verbatim containment before approval) | `core/plan/OutcomeParser` law |
| | User-defined automation rules + run history + dedupe keys | `core/domain/WorkflowTemplate` (seeds) |
| | Source mirror (SHA-256 change detection, resumable cursor batches, version retention) | scheduler roadmap slot (v1.1) |
| | CRM capture + outreach handoff (no-auto-send) | Work screen flows (v1.0), intents preserved |
| | Weekly model-catalogue discovery | `app/scheduler` roadmap slot |
| | Multi-language en/pl/tr dictionaries | seeded resource roadmap slot (v1.1) |
| | WebView-as-app architecture | explicitly rejected |
| **Memo v2 prototype** (HTML) | Product law: conversation-wrapped persistent system; checkpoint semantics; content-bound approvals; routing trace; component contracts; Paper+lime design seeds | enforced across engine/gates/UI; visual language merged with_Threadline_v2 into Paper+Mint |

Un-processed/vaulted ideas intentionally kept from sources but not in v1.0:
extra channel adapters (X/Facebook), morning briefing digest, global search,
end/tr/po localization, remote runner, live-passive observation.
