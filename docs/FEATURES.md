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
- [x] A3 Workflow DSL with step kinds reason, browser_automation, app_automation, human_approval, data_persist, metric_collect, branch, retry, plus async external steps (`Attempt.Pending`/`deliverExternal`) · M0.A1, M0.A8 · CI run 40374975745
- [~] A4 Workflows persisted (encrypted, M0.C2), resumable (M0.A1), versioned (semver registry, M0.A4); re-run UI M3.5.B2
- [ ] A5 Route each step: local orchestration / frontier AI (web or app) / browser automation / app automation / human gate · M0.A6, M4.*
- [ ] A6 Local agent is capable (tool-using, advanced work); frontier AIs reached via their apps or web UIs (no API keys assumed); routing per task class is a user setting · M0.A6, M3.A1, M3.A4, M4.A2, M4.B3
- [~] A7 Pluggable `ModelRouter` that picks the frontier target per step and records which one and why · core done (M0.A6); UI M4.C2
- [~] A8 Fill the artifact before approval; approvals show the **completed** artifact · gates bound to content hash (core); JSON view only today; renderers M3.B1
- [x] A9 Approvals checkpointed, persisted and auditable (hash-bound gates; outbound events now recorded in the audit chain) · M0.A1 · CI run 40374975745
- [ ] A10 Execute approved workflows end to end, checkpointing at each gate · M3.A3, M4.*, M5.*
- [ ] A11 Instrument everything: logs, tracking, metrics feeding back into planning · M5.C1, M3.A2, M6.2
- [x] A12 Versioned schema and artifact registry with semantic versions, reuse-first, first-class migrations (first real migration v1→v2) · M0.A3, M0.A4
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
- [~] C7 Safety guards (ActionPolicy + ChannelSwitches done, M0.A5): destructive-action filter, lock and wrong-app pause, password/OTP never touched, bounded retries, commit never auto-retried · M0.A5, M4.A0, M4.B1
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

## E+. Local agent & media engine
- [ ] E7 Tool-using local agent engine (search/create/update records, draft, summarise, generate image, render carousel, compose video, consult frontier, browse, run/schedule workflows, ask user) · M3.A4
- [ ] E8 Local image generation (MediaPipe ImageGenerator; advanced FLUX.2-klein / Z-Image via LiteRT on capable devices) · M5.C1a
- [ ] E9 Carousel rendering (PNG + PDF) and video composition (slides/images, motion, captions, Kokoro voice-over) · M5.C1b
- [ ] E10 Route media/heavy tasks to frontier AIs via the in-Memo web runtime when chosen · M3.A4, M4.A2

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
- [~] H1 Encrypted local DB (Keystore AES-GCM vault) with verified migration from plaintext · M0.C1, M0.C2 · CI ✔; device check pending
- [ ] H2 Encrypted credential/token vault · M0.C1, M1.B2
- [~] H3 Redaction before anything leaves the device · core Redactor done; wired into consults in M4.A2
- [~] H4 User-auditable outbound log (hash-chained) · chain exists; wiring fixed in M0.A1; viewer M6.2
- [~] H5 Kill switch · basic "stop all runs" done; global/per-channel M0.A5, M3.5.C1
- [~] H6 Per-channel enable/disable · core ChannelSwitches done (M0.A5); UI M4.C2
- [ ] H7 Scoped permissions with rationale (permission centre) · M6.5
- [ ] H8 App lock (biometric), FLAG_SECURE option, encrypted backup/export · M6.3, M6.4

## I. UX, motion, speed ("alive, professional, fast")
- [~] I1 One design system (tokens, type, spacing, components) · Paper+Mint tokens exist; full system M2.B0a–c
- [ ] I2 Motion language with spring physics, haptics and reduce-motion fallbacks · M2.B0b
- [ ] I3 Shared-element transitions + predictive back on every screen · M2.B0d
- [ ] I4 Workflow spawn cascade, step progression (pulse, check morph, rail fill), approval choreography · M2.B0c, M3.B2, M3.B1
- [ ] I5 PresenceOrb (shader, state- and audio-reactive) across app, bubble and notification · M2.B0c
- [ ] I6 Live Environment: running workflows, waiting approvals, recent outputs, metric health, "since you were away" · M3.B2
- [ ] I7 Artifact renderers + edit before approve · M3.B1
- [ ] I8 Inline plan cards and approvals in chat; streaming without layout jumps · M3.A3, M1.C2
- [ ] I9 Release build (R8, signed with a persistent key) as the APK to install · M2.5.A1
- [ ] I10 Baseline profiles + startup/frame benchmarks meeting the targets (cold < 900 ms, no jank) · M2.5.A2, M2.5.A3

## J. Reliability & engineering (self-audit items)
- [ ] J1 Offline queue + boot recovery · M6.1
- [~] J2 Rate limiting per provider/app · core RateLimiter + circuit breaker done (M0.A6); humanizer pacing M3.5.A2
- [ ] J3 Error recovery: retry/backoff, takeover, NEEDS_REVIEW · core + M4.*
- [ ] J4 Observability: local diagnostics, crash log, run journal (no analytics) · M6.2
- [x] J5 Platform boundary (`core/platform`) for a later desktop build: LLM, speech, vault, web/app channels, device, scheduler interfaces · M0.A7 · CI run 40374975745
- [x] J6 CI produces a clean APK on every merge, and now runs **all** core tests (7 hidden failures found and fixed) · M0.A8 · CI run 40374975745
- [ ] J7 Lint strict, test sweep · M6.6, M6.7

## L. Languages
- [ ] L1 English, **Polish**, **Turkish** UI with an in-app language picker · M3.6.B1
- [ ] L2 Voice in pl/tr: Whisper ASR + Piper TTS voices; English via Zipformer + Kokoro · M3.6.C1
- [ ] L3 Voice and text commands understood in pl/tr (grammar + agent) · M3.6.A1, M3.A4
- [ ] L4 Agent replies, and asks frontier AIs to reply, in the user's language · M3.A4, M4.A2
- [~] L5 Safety terms and automation labels in pl/tr · ActionPolicy with en/pl/tr/de/es/fr done (M0.A5, CI run 40374975745; native-speaker review pending); adapters M4.A2

## M. Everyday product (§2j)
- [ ] M1 Reminders: exact, recurring, snooze, notification actions, survive reboot, private on lock screen · M3.7.A1
- [ ] M2 Timers & stopwatch: voice or UI, live countdown notification, rings on the lock screen, several at once · M3.7.A1, M3.7.A2
- [ ] M3 Alarms with full-screen dismiss/snooze · M3.7.A1
- [ ] M4 Notification channels (user-configurable); approvals, takeovers and digest notifications · M3.7.A1, M2.C3
- [ ] M5 Quiet hours + interruption caps · M3.7.C1
- [ ] M6 Share into Memo (text, links, images, PDFs, files) and "Ask Memo" on selected text · M3.7.B1
- [ ] M7 Camera: business-card scan → CRM, document photo → note · M3.7.B1
- [ ] M8 Share out / export drafts, notes and media · M3.7.B1
- [ ] M9 Sources library with provenance · M3.7.B2
- [ ] M10 Global search (full-text + semantic embeddings) · M3.7.B2
- [ ] M11 Memory graph with explained links · M3.7.B3
- [ ] M12 Today view, morning briefing, driving mode · M3.7.C1
- [ ] M13 Onboarding (language, permissions, model, voice, wake word, console) + help + templates gallery · M3.7.C2
- [ ] M14 In-app updates from GitHub releases (signed, verified) · M3.7.C3
- [ ] M15 Contacts import and "met at" place tag (opt-in) · M3.7.C4
- [ ] M16 Council modes + evidence-backed entity extraction → expanded into section S (§2n) · M4.A4a–d
- [ ] M17 Optional API channel (OpenAI-compatible / Anthropic / Gemini keys), opt-in only · M4.A5
- [ ] M18 App capability map (learned per device app, reusable, editable) · M4.B4
- [ ] M19 Memo's own accessibility (TalkBack, font scale, contrast) + empty/loading/error/offline states everywhere · M6.10

## R. Reference cross-check (every capability found in the reference apps)
| Reference app | Capability | Covered by |
|---|---|---|
| Fieldwork | On-device LLM (LiteRT-LM, GPU→CPU) | E3, E4 |
| Fieldwork | In-app browser sessions for frontier AIs (consult/compare, critique/refine) | C2–C4, M16 |
| Fieldwork | Local orchestrator brief + agreement map + evidence-checked entity extraction | M16, E7 |
| Fieldwork | Council of models (analyst/skeptic/practitioner/reviewer, synthesizer fallback) | M16 |
| Fieldwork | OpenAI-compatible remote endpoint with encrypted key, consent | M17 |
| Fieldwork | Encrypted vault, biometric lock | H1, H8 |
| Fieldwork | Share target (text) | M6 |
| Fieldwork | Model download with backup swap | E1, E5 |
| Fieldwork | Google sign-in warning → open externally | C3 (takeover/login flow) |
| Pocket | Floating capture bubble | D1 (console, more than capture) |
| Pocket | Share target for many types + multiple; attachment import limits | M6 |
| Pocket | Share-out handoff to chosen app | M8 |
| Pocket | Mission/council brief composer (collaborate/compete/converge) | M16 |
| Pocket | Backup codec with schema migration | H8, A12 |
| Pocket | FLAG_SECURE, 60 s relock | H8 |
| Threadline | Tasks, projects, reminders (+ boot re-arm, daily nudge) | G1, M1, M12 |
| Threadline | Studio drafts | B4, I7 |
| Threadline | Floating voice assistant + conversational TTS | D1, F4, F5 |
| Threadline | Screen observation / OCR capture | C6, D1 ("act on this screen") |
| Threadline | Source folder mirror (SAF) | M9 (user-picked documents) |
| Threadline | Model catalogue worker (new-model notifications) | E2, M4 |
| Threadline | Translate | L4 (agent translation) |
| Threadline | Today screen, nudge caps, quiet hours, driving mode | M5, M12 |
| Threadline | PROCESS_TEXT | M6 |
| Threadline | Career pipeline, outreach | B3, B5 |
| SynthOS Runtime | Launch device app + accessibility automation + floating inspector | C5, C6, D1 |
| SynthOS Runtime | Lock and wrong-app pause, policy filter, verified-only success | C7, C9 |
| SynthOS Runtime | App capability mapping / surface snapshot | M18 |
| SynthOS Runtime | Versioned skill registry | A12 (PromptTemplate), E7 |
| SynthOS Runtime | Job compiler (installed apps + snapshot as context) | A2, M3.A2 |
| SynthOS Runtime | Run history with verification status | A4, J4 |
| SynthOS Runtime | Remote ADB guest runner | intentionally excluded: everything runs inside Memo (C1) |
| memo-v2 prototype | Conversation with context chips + entity cards | F1, I8 |
| memo-v2 prototype | Work ledger with resumable task states | A4, G1 |
| memo-v2 prototype | Browser runtime with "talk about this page" | C2, M9 |
| memo-v2 prototype | Memory graph with link provenance | M11 |
| memo-v2 prototype | Sources and ingestion pipeline | M9, M10 |
| memo-v2 prototype | Models + routing trace view | E1–E6, A7 (trace shown per step) |
| memo-v2 prototype | "How Memo works" explainer | M13 (motion intro, help) |

## N. Capability Compiler (§2k)
- [ ] N1 Task architect → TaskSpec (objective, success definition, constraints, deliverables) · M3.8.A2
- [ ] N2 Requirement graph (knowledge, skills, data, tools, persona, constraints, evaluators, experience; can spawn sub-requirements; bounded) · M3.8.A2
- [ ] N3 Capability Library: typed, versioned, user-editable components with dependencies, conflicts and trust levels · M3.8.A1, M3.8.B1
- [ ] N4 Deterministic assembler + readiness validator; honest capability gaps · M3.8.A2
- [ ] N5 Execution package rendered per executor (local small/large, frontier), compiled into gated workflows · M3.8.A3
- [ ] N6 Evaluate → diagnose → repair only the failing component → learn (Experience, reliability stats, evidence-based promotion) · M3.8.A4
- [ ] N7 "Ask another AI for the prompt" loop: architect + critic models, bounded rounds · M3.8.A5
- [ ] N8 Compiler view: animated requirement graph, chosen components, gaps, rendered prompt, evaluations; edit + recompile · M3.8.C1

## O. Self Model & Artifact Studio (§2l)
- [ ] O1 Self Model: identity, experience, achievements with metrics, projects, education, skills with evidence, preferences, voice, brand; per-fact provenance · M3.9.A1
- [ ] O2 Ingest from website, LinkedIn (own profile in Memo's browser, or official export) and CVs/documents, as a gated diff with conflicts surfaced · M3.9.A2
- [ ] O3 Outbound sync: propose website/LinkedIn updates from the Self Model (approved, humanized) · M3.9.C1
- [ ] O4 CV generator: templates, en/pl/tr, 1–2 page fitting, PDF/DOCX/text/JSON Resume export, rich editor · M3.9.B1
- [ ] O5 Per-job tailored CVs with evidence links, fit score, gap flags, ATS coverage, diff vs base · M3.9.B2
- [ ] O6 Fabrication guard: nothing appears that isn't in the Self Model · M3.9.B2
- [ ] O7 Cover letters, bios, LinkedIn headline/about, portfolio one-pager, intro emails · M3.9.B3
- [ ] O8 UC-3 job applications use the tailored CV + letter, shown at the gate · M5.B1, M3.9.B2

## P. Teach & Select (§2m)
- [ ] P1 Web element picker in Memo's browser: tap-highlight, breadcrumb, expand/shrink, select similar, live match count, stable multi-strategy locators · M4.E1
- [ ] P2 Extraction schema builder: map elements to schema fields, transforms, list mode with pagination, live preview · M4.E2
- [ ] P3 Custom user-defined schemas, versioned with generated migrations · M4.E0
- [ ] P4 "Show Memo" recorder (web): demonstrate once, get a parameterised, reviewable recipe · M4.E3
- [ ] P5 App element picker + recorder for device apps (accessibility nodes) · M4.E4
- [ ] P6 Versioned ExtractionSchema / ActionRecipe artifacts, reusable in any workflow and by the Capability Compiler · M4.E0
- [ ] P7 Self-healing: the local AI regenerates broken locators from the stored schema; validated; new patch version; commit steps need approval · M4.E5
- [ ] P8 Workflow editor: Pick on page / Pick in app / Extract / Record, with version badges, upgrade and pin · M4.E6

## S. Council: role-based debate between local models and frontier AIs (§2n)
- [ ] S1 Mixed seats: local models (LiteRT/GGUF), frontiers via the in-Memo web runtime, frontiers via device apps, optional API, and the user · M4.A4a/b
- [ ] S2 Role library (moderator, proposer, domain expert, skeptic, devil's advocate, fact-checker, user advocate, synthesizer/judge), versioned and editable · M4.A4a
- [ ] S3 Relevance-based seat plan (topic → TaskSpec → roles → best target per role), editable before and between rounds · M4.A4a, M4.A4c
- [ ] S4 Formats: compare, critique & refine, converge, debate (opening/rebuttals/closing/judgment), round table, red team · M4.A4a
- [ ] S5 Claims ledger with ids and rebuttal references; fact-check marks; evidence-only extraction · M4.A4a, M4.A4d
- [ ] S6 Privacy: private data only to local seats; frontier turns redacted, audited, consented · M4.A4a/b
- [ ] S7 Anti-sycophancy: blind openings, fixed devil's-advocate stance, judge ≠ proposer, sources labelled · M4.A4a
- [ ] S8 Stopping rules (consensus, rounds, budget, no-progress, user) and an outcome with a minority report and next steps · M4.A4a
- [ ] S9 Background execution: web seats headless, app seats batched into one screen session, local and web turns in parallel, absent-seat handling · M4.A4b
- [ ] S10 Council UI: group thread, role chips, claim links, ledger panel, interject/steelman/pause, outcome card; voice mode with a voice per seat · M4.A4c
- [ ] S11 Saved councils and templates, re-runnable with updated models · M4.A4a

## T. Live view: watch the AI work in real time (§2o)
- [ ] T1 User-started web runs ("go to the web…") open a live view of the real WebView; leaving keeps the run going headless · M4.L2
- [ ] T2 Live previews of background runs in the bubble/overlay, Live tab, council seats and notification (real pixels, throttled) · M4.L4
- [ ] T3 Ghost cursor + highlight ring at the real target for each action; visible typing, tap ripple and scrolling · M4.L2, M4.L3
- [ ] T4 Deterministic live narration of each action in en/pl/tr (optionally spoken) · M4.L1
- [ ] T5 Step timeline with before/after snapshots (masked, encrypted, auto-purged) · M4.L1, M4.L2
- [ ] T6 Controls: pause, step mode, take over and hand back, stop; the user's touch auto-pauses the agent; inline approvals · M4.L2, M4.L3
- [ ] T7 App sessions show the HUD, target highlight and narration on top of the real app · M4.L3

## K. Delivery & docs
- [x] K1 Pull latest GitHub state before building · done (b8c6ebd → main)
- [x] K2 Build directly on GitHub (no zip/YAML deliverables); merge each green milestone · in effect since PR #2
- [x] K3 Plan stored in the repo, small tasks, Haiku implementers, lead verifies · `docs/BUILD_PLAN.md`, PR #3
- [x] K4 Feature list + checklist kept current · this file
- [ ] K5 CHANGELOG mapping each reference app → what was extracted → where it lives · M6.8
- [ ] K6 BUILD.md (repo → CI → download APK → install; local fallback; troubleshooting) · M6.8
- [ ] K7 Final acceptance walkthrough UC-1…UC-5 with evidence · M6.9
