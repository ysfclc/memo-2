# Memo — Feature list & checklist

Every requirement from the original product spec and from the build conversation, each mapped to a task in `docs/BUILD_PLAN.md`. The lead updates this file whenever a task merges.

Legend:
- `[x]` done, with evidence (CI run, or device check by the user)
- `[~]` in progress, or partially done (note says what is missing)
- `[ ]` not started

"Device ✔" means the user confirmed it on a phone.

## A. Product core (original spec)
- [ ] A1 Voice-first intent capture, including from a locked phone · M2.A3, M2.C1, M2.C2
- [ ] A2 Intent → typed workflow, **generated dynamically by the local orchestrator** (never hardcoded per use case) · M3.A1
- [~] A3 Workflow DSL with step kinds reason, browser_automation, app_automation, human_approval, data_persist, metric_collect, branch, retry · core v1 done (tested); async steps M0.A1 in verification
- [~] A4 Workflows persisted, resumable, versioned, re-runnable · runs persisted (plaintext, 2b43e36); encryption M0.C2; versioned templates M0.A4; re-run M3.5.B2
- [ ] A5 Route each step: local orchestration / frontier AI (web or app) / browser automation / app automation / human gate · M0.A6, M4.*
- [ ] A6 Local model = orchestrator only; frontier AIs reason via their apps or web UIs (no API keys assumed) · M0.A6, M3.A1, M4.A2, M4.B3
- [ ] A7 Pluggable `ModelRouter` that picks the frontier target per step and records which one and why · M0.A6, M4.C2
- [~] A8 Fill the artifact before approval; approvals show the **completed** artifact · gates bound to content hash (core); JSON view only today; renderers M3.B1
- [~] A9 Approvals checkpointed, persisted and auditable · core done; audit wiring fixed in M0.A1 (verification)
- [ ] A10 Execute approved workflows end to end, checkpointing at each gate · M3.A3, M4.*, M5.*
- [ ] A11 Instrument everything: logs, tracking, metrics feeding back into planning · M5.C1, M3.A2, M6.2
- [~] A12 Versioned schema and artifact registry with semantic versions, reuse-first, first-class migrations · registry v1 done; v2 entities M0.A3 (verification); semver + migration M0.A4
- [ ] A13 Schemas: Person, Company, Job, ContentDraft, Post, MetricSample, WorkflowTemplate, Prompt, ArchitecturalPrinciple (+ Project, Note, ContentPreference, ChannelAccount, ProviderAdapter) · v1 done for the first 6; rest M0.A3/M0.A4

## B. Use cases (acceptance scenarios)
- [ ] B1 **UC-1** Locked-screen voice brainstorm. Must do all of: wake → parse → workflow with AI reasoning + automation steps → choose frontier models and explain why → compose prompts + architectural principles → run → save outputs into Project X → append next steps as tasks · M5.A1
- [ ] B2 **UC-2** CRM capture: create person, link or create company, follow-up tasks, research missing details (browser or app), fill before showing · M3.C2 (+ M4.A2 research)
- [ ] B3 **UC-3** Job discovery: list jobs as entities for approval; per approved job, apply via browser or native app with a tailored cover letter; log the application state · M5.B1
- [ ] B4 **UC-4** Content: pick source idea → apply content preferences → draft **text / image / carousel / video** → finished draft with target channels → approve → publish (browser or app) → auto-provisioned engagement tracking → logged for strategy · M5.C1, M5.C1b
- [ ] B5 **UC-5** Arbitrary plans: "play this game", repetitive UI chores, multi-app sequences, all decomposed and checkpointed · M5.C2

## C. Automation (browser, apps, agent loop)
- [ ] C1 Everything runs **inside Memo**: no external browser, remote runner, PC or ADB · §2a, §2b
- [ ] C2 In-Memo web runtime (Fieldwork pattern) running **headless in the background**; the agent works without bothering the user · M4.A1
- [ ] C3 Per-service session persistence and cookie isolation; login once; human takeover handoff · M4.A1, M4.A3
- [ ] C4 Frontier AI consults inside Memo (ChatGPT, Claude, Gemini, Grok, DeepSeek, Perplexity, Le Chat, Copilot) with robust inject/send/complete/extract · M4.A2
- [ ] C5 **Native device apps operated by Memo** (SynthOS Runtime pattern): launch app, accessibility service drives it, Memo console on top · M4.B1, M4.D1
- [ ] C6 App automation: node traversal, tap/type/scroll/swipe gestures, wait/read, screenshot + OCR fallback · M4.B1, M4.B2
- [ ] C7 Safety guards: destructive-action filter, lock and wrong-app pause, password/OTP never touched, bounded retries, commit never auto-retried · M0.A5, M4.A0, M4.B1
- [ ] C8 Agent loop OBSERVE→DECIDE→CHECK→ACT→VERIFY→RECORD, with a closed action vocabulary and strict locators · M4.A0
- [ ] C9 Approved action envelope bound to the gate hash; any deviation → takeover or re-approval · M4.A0
- [ ] C10 **Human-like actions to avoid bans**: timing, typing cadence, curved gestures, pacing, daily caps, working hours, ban-signal backoff · §2d, M3.5.A2
- [ ] C11 Device-wide AI discovery: installed packages + signature registry + WebView detection + accessibility probe; Settings toggles; **no folder selection** · M4.C1, M4.C2
- [ ] C12 AI-app adapters for driving frontier AI mobile apps · M4.B3
- [ ] C13 Emulator-based instrumented tests in CI for web and app automation (fixtures, refusal paths) · M4.0, M4.A0b

## D. Floating console & surfaces outside the app
- [ ] D1 Floating Memo bubble/console over any app. **Not just capture:** talk, act on this screen, background work, approvals, quick actions, kill switch · M2.C3, M4.D2
- [ ] D2 Ongoing notification with running/pending work and approve actions (side effects need unlock) · M2.C3
- [ ] D3 Quick Settings tile · M2.C3
- [ ] D4 Home-screen widget for approvals · M3.B3

## E. Local AI & models
- [~] E1 One-tap download from available sources, with verification · pinned Qwen3 ×3 one-tap with SHA, resume and import (2b43e36); hub expansion M1.B1–B3
- [~] E2 Discovery of models: live Hugging Face catalog + on-device scan/import · device scan/import done (basic); HF live discovery M1.B2
- [ ] E3 **Any open local model**: Llama, Gemma, Qwen, Phi, Mistral, DeepSeek, SmolLM, Granite, Falcon… via LiteRT-LM **and** llama.cpp/GGUF · M1.A1, M1.A2, M1.B1
- [ ] E4 On-device inference running (streaming, stop, GPU→CPU fallback, RAM preflight) · M1.A1, M1.A2, M1.A3
- [ ] E5 Downloads survive app close (foreground worker), resume, notification progress, optional HF token · M1.B2
- [ ] E6 Per-model settings (system prompt, temperature, context, thinking) · M1.B3

## F. Chat & voice
- [ ] F1 Proper chat interface: threads, streaming markdown, stop/regenerate/edit/copy/delete · M1.C1, M1.C2
- [ ] F2 Model picker (local models + frontier via Memo's browser) · M1.C2
- [ ] F3 Voice messages (hold to record, waveform, transcript, playback) · M2.B1
- [ ] F4 Live voice conversation with interruption (barge-in) · M2.A3, M2.B1
- [ ] F5 **Natural voice, no Android speech pop-up, no robotic TTS** (sherpa-onnx local ASR + Kokoro/Piper) · M2.A2
- [ ] F6 Read aloud per message · M2.B1
- [ ] F7 **Wake by voice with the screen off and locked** ("Hey Memo") · M2.C2, §2f
- [ ] F8 Default-assistant role: long-press power opens a Memo session over the lock screen · M2.C1
- [ ] F9 Clear summary of what ran and what is pending; unlock confirmation for side effects · M2.C1

## G. Full control
- [ ] G1 **Nothing read-only**: create, edit, update, archive and delete every record type manually, with version history · M3.5.B1
- [ ] G2 **Workflow editor**: create from scratch or a template, edit generated plans, reorder and edit steps, validate live, save as template, schedule, re-run · M3.5.B2
- [ ] G3 Autonomy settings: per action class "always ask / once per workflow / auto + summary / auto silent"; payments, passwords and OTP always human · M3.5.A1, M3.5.C1
- [ ] G4 **Complete settings with full control** (profile, autonomy, channels, human-like behaviour, models, voice, wake, notifications, console, privacy, data, appearance, advanced, permissions) · M3.5.C1
- [ ] G5 Profile and content preferences that feed planning · M3.C1

## H. Security & trust
- [ ] H1 Encrypted local DB (Keystore AES-GCM vault) with migration from plaintext · M0.C1, M0.C2
- [ ] H2 Encrypted credential/token vault · M0.C1, M1.B2
- [~] H3 Redaction before anything leaves the device · core Redactor done; wired into consults in M4.A2
- [~] H4 User-auditable outbound log (hash-chained) · chain exists; wiring fixed in M0.A1; viewer M6.2
- [~] H5 Kill switch · basic "stop all runs" done; global/per-channel M0.A5, M3.5.C1
- [ ] H6 Per-channel enable/disable · M0.A5, M4.C2
- [ ] H7 Scoped permissions with rationale (permission centre) · M6.5
- [ ] H8 App lock (biometric), FLAG_SECURE option, encrypted backup/export · M6.3, M6.4

## I. UX & "live" feel
- [~] I1 One design system (tokens, type, spacing, components) · Paper+Mint tokens exist; full system M2.B0
- [ ] I2 Motion: workflow spawn, step progression, approval transitions, live status pulse · M2.B0, M3.B2
- [ ] I3 PresenceOrb (idle / listening / thinking / speaking / working / waiting) across app, bubble and notification · M2.B0
- [ ] I4 Live Environment: running workflows, waiting approvals, recent outputs, metric health, "since you were away" · M3.B2
- [ ] I5 Artifact renderers: person/company cards, post preview with channels, job + cover letter, etc.; edit before approve · M3.B1
- [ ] I6 Inline plan cards and approvals inside chat · M3.A3
- [ ] I7 Haptics on gate open and approve · M3.B1

## J. Reliability & engineering (self-audit items)
- [ ] J1 Offline queue + boot recovery · M6.1
- [ ] J2 Rate limiting per provider/app · M0.A6, §2d
- [ ] J3 Error recovery: retry/backoff, takeover, NEEDS_REVIEW · core + M4.*
- [ ] J4 Observability: local diagnostics, crash log, run journal (no analytics) · M6.2
- [ ] J5 Platform boundary (`core/platform`) for a later desktop build · M0.A7
- [~] J6 CI produces a clean APK on every merge · ✅ main green; core tests added to CI in M0 (verification)
- [ ] J7 Lint strict, test sweep · M6.6, M6.7

## K. Delivery & docs
- [x] K1 Pull latest GitHub state before building · done (b8c6ebd → main)
- [x] K2 Build directly on GitHub (no zip/YAML deliverables); merge each green milestone · in effect since PR #2
- [x] K3 Plan stored in the repo, small tasks, Haiku implementers, lead verifies · `docs/BUILD_PLAN.md`, PR #3
- [x] K4 Feature list + checklist kept current · this file
- [ ] K5 CHANGELOG mapping each reference app → what was extracted → where it lives · M6.8
- [ ] K6 BUILD.md (repo → CI → download APK → install; local fallback; troubleshooting) · M6.8
- [ ] K7 Final acceptance walkthrough UC-1…UC-5 with evidence · M6.9
