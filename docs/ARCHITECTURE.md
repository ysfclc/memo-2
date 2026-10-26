# Memo — Architecture & Design Document

One app unifying five prototypes + one HTML product-law document into
`app.memo`, a voice-first, checkpoint-driven personal agent for Android.
Everything below is enforced by code tracked in this repo; decisions and
rationale live in `docs/ENGINEERING_LOG.md`.

## 1. Product laws (inherited from the Memo v2 prototype Wiring)

1. **Conversation wraps a persistent workspace.** Chat turns are temporary;
   artifacts they produce (contacts, drafts, posts, metrics) are permanent,
   versioned registry records.
2. **Every intent becomes a typed workflow.** No free-floating "agent": plans
   are explicit step lists parsed from strict JSON (`core/plan`).
3. **Approval binds to content.** A gate stores the artifact's `contentHash`
   at approval time; the executor re-derives and re-compares before acting
   (TOCTOU guard). Mismatch -> `NEEDS_REVIEW`, never silent drift.
4. **Routing is visible.** Every plan shows which channel each step runs on
   (local model / frontier site / native app / API / human gate).
5. **Fill before asking.** Approval gates present filled artifacts + verbatim
   evidence, never "should I do something?" with empty hands.
6. **Reuse first.** Registry lookup by natural key before creation; version
   bumps only on real change.
7. **Measure everything outbound.** Publishing actions record metric samples
   that feed the next plan (metrics loop).
8. **Honesty.** No fake progress animations, no silent listeners, no silent
   network egress. Redaction before any prompt leaves theworkspace. A hard
   kill switch freezes all executors.

## 2. Layer map

```
UI (Compose, Paper + Mint): Live . Talk . Work . Studio . Browser . Approvals . Models . Settings
 |
 +- app/voice      VoiceEngine (on-device STT loop, TTS, command grammar handoff)
 +- app/engine     RunManager (WorkManager-backed resume of core engine runs)
 +- app/browser    MemoBrowserSession + BrowserScripts (hardened WebView executor)
 +- app/automation MemoAutomationService (accessibility executor + AI-app discovery)
 +- app/ai         LocalAssistant (LiteRT-LM warm-resident engine, GPU->CPU fallback)
 +- app/models     ModelManager (SHA-256 pinned catalog, .part/.previous/.active)
 +- app/vault      MemoVault (AES-256-GCM/Keystore) + CredentialStore + BackupCrypto
 +- app/scheduler  WorkManager workers (reminders, metrics, daily brief, boot resync)
 |
 +- core (pure Kotlin, JVM-tested; imports NO Android)
     domain   Person/Company/Job/Application/ContentDraft/Post/MetricSample/Evidence/...
     registry reuse-first versioned store with quarantined rows
     plan     PlanProposalParser + WorkflowPolicy + CommandGrammar + OutcomeParser
     engine   WorkflowEngine (step kinds, statuses, checkpoints, approvals queue)
     prompts  ContextPacket + orchestrator/frontier composers + Prompts
     codec    SYN envelope serialize/migrate/validate
     redact   Pattern pack + scope policy
     audit    HashChainAudit (SHA-256 append-only chain)
     Limits   bounds for text sizes, budgets, retries, polling
 |
 executors implement core/engine StepExecutor interfaces (core stays Android-free)
```

## 3. Workflow DSL v1

Step kinds are exactly these eight (schemaVersion 1):

| kind              | Purpose                                  | Side effect | Law & caps |
|-------------------|------------------------------------------|-------------|------------|
| reason            | Local/frontier model derivation          | No          | Sequential chain <= 3; side-effect-free consults may run parallel |
| browser_automation| Frontier website interaction in WebView  | Yes on submit | Every submit accompanies a human_approval gate unless declared CAPTURE (read) |
| app_automation    | Native app actions via accessibility     | Yes         | <= 4 actions/step; confirm-to-type; never password fields |
| human_approval    | Gate step                                | No          | *Mandatory* before publish/send/payment steps |
| data_persist      | Registry upsert (natural key + version)  | Internal    | Dedupe by natural key required |
| metric_collect    | Capture one MetricSample (read-only net) | No          | Channel opt-in; URL allowlist |
| branch            | Deterministic switch on prior results    | No          | Conditions may reference recorded evidence only |
| retry             | Bounded re-execution with backoff        | Inherits     | <= 3 attempts; backoff 4s -> 30s |

Sequential execution only. `CONSULT_AND_COMPARE` parallelism is allowed solely
between side-effect-free `reason` consults.

### Status machine

```
PENDING -> RUNNING -> COMPLETED | FAILED | SKIPPED | NEEDS_REVIEW
RUN special state:   WAITING_USER (gate opened)
Process death:       INTERRUPTED
```

Side-effect steps NEVER auto-resume after process death: on continuation the
engine re-verifies context; if not verifiable the step lands in
`NEEDS_REVIEW` for human decision.

### Idempotency & no-resend

Every attempt carries id `runId:stepIndex:attempt`. Executors keep a
recent-execution cache; the browser layer refuses resend after a verified
submission (composer cleared AND a new user message visible = submitted).

## 4. Channels & routing

| Channel     | Executor                     | Entry                    | Consent                 | Notes |
|-------------|------------------------------|--------------------------|-------------------------|-------|
| local       | LocalAssistant (0.6B default)| Always available         | Bundled weights         | Thinking budget OFF for routine steps |
| frontier    | MemoBrowserSession           | Provider picker (8 sites)| Per submission          | Verify submission visible before trusting |
| automation  | Accessibility executor       | User enables in Settings | Per-run + confirm-to-type | Bounded action budgets |
| api (opt-in)| OpenAI-compatible client     | User adds base URL + key | Per-request flag        | Never a default channel |
| human       | Gate UI                      | Automatic                | contentHash binding     | Never auto-resumes |

Routing preference: cheapest channel that can complete the step.
`CommandGrammar` short-circuits before any model (deterministic voiced intents
like "remind me X" parse without an LLM at all).

## 5. Registry (reuse-first)

Natural keys: `Person(fullName lowercase + phone digits)`,
`Company(domain)`, `Job(companyKey + roleKey)`, `Post(url)`,
`ContentDraft(kind + topic + created date)`, `MetricSample(postKey + bucket)`.
Records carry `schemaVersion`, timestamps, and a version that bumps ONLY when
content meaningfully changes. Codec migration chain `vN -> vN+1`; unparseable
rows go to a `quarantine` list instead of crashing.

## 6. Persistence & codec

- Workspace envelope `SYN1` (Pocket law): `schemaVersion, createdAt, entries[]`
  strict validate + migrate on load; invalid writes are rejected rather than
  clobbering good data.
- Vault: per-collection AES-256-GCM with AAD `memo:<type>:<id>:<v>`, atomic
  tmp+fsync+rename, rolling `.bak` x3 self-heal.
- Opt-in auth-bound key wrapping (BiometricPrompt CryptoObject; StrongBox if
  present). Kill switch zeroes in-memory caches.
- Backups: TLBK passphrase envelope (PBKDF2-SHA256, 600k iters, >= 12 chars);
  restore validates MAC then schema then migrates.
- Outbound audit: append-only JSONL `{ts, runId, channel, target, actionSummary,
  contentHash, prevHash}` chained with SHA-256; Approvals/Settings screen can
  verify the full chain locally.

## 7. Security gates

- NetworkPolicy allowlist with per-channel toggles; cleartext HTTP never.
- Redaction: (1) secret regex pack (keys, tokens, cards, seed phrases) never
  leaves device; (2) scope-limited ContextPacket only carries fields the
  channel needs; (3) frontier outputs arrive wrapped in untrusted framing.
- Kill switch: Quick Settings tile + notification action; freezes executors,
  flushes audit chain, posts a confirm notification.
- FLAG_SECURE on sensitive screens; 60 s background relock when lock enabled.
- No analytics / crash / ads SDKs of any kind.

## 8. Voice

- On-device STT loop (API 31+, 650 ms re-listen), TTS speaks accept/confirm
  states. Offline TTS configured.
- No hidden wake word: Android exposes no third-party locked-screen hotword
  API. Degrade ladder (documented in Settings): Quick Settings tile,
  lock-screen content-free notification action with biometric handoff,
  opt-in overlay bubble, opt-in keyboard-filter trigger.
- `CommandGrammar` deterministic intents parse first; model only sees the rest.

## 9. Local AI (fast-first)

- Catalog: Qwen3-0.6B (~345 MB, default), Qwen3-1.7B (~1 GB),
  Qwen3-4B-Thinking (~2.5 GB). SHA-256 + exact size pinned; staging
  `.part -> .previous -> active` with rollback.
- Warm-resident engine: session kept loaded; KV/prefix caches reused across
  turns; decode streams with a TTFT target < 500 ms (measured via
  metric_collect on self).
- GPU-first backend probe with CPU thread-plan fallback (Fieldwork pattern).
- Token budgets: orchestrator in <= 2k / out <= 700; frontier briefs in <= 4k /
  out <= 1.2k. Budget exhaustion -> `NEEDS_REVIEW`, never hallucinated output.
- Thinking budget OFF for routine/chat; ON only for the 4B deep profile.

## 10. Design system — Paper + Mint

Warm paper surfaces `#F4F2EC` family; ink `#222920`; evergreen primary
`#26524A`; mint action `#B2EFCF`; teal links `#276B56`; amber caution
`#B25A24`; dark remaps from the Threadline dark set. Type scale
32/28/22/18/16/14/13/12/11 sp; corner shapes 8/12/18/24/30 dp; 4 dp rhythm.

Motion ladder: press scale 0.965 @ 85-170 ms `PathInterpolator(0.2,0,0,1)`
+ `KEYBOARD_TAP` haptic; entrance 230 ms +7 dp `PathInterpolator(0.16,1,0.3,1)`;
stagger 28 ms x 9; crossfade 180 ms; PulseOrb live breathing 1650 ms
0.78 -> 1.12; skeleton shimmer 1150 ms. Always respect
`ValueAnimator.areAnimatorsEnabled()`; **never animate a fake response**.

Canonical tokens: `app/src/main/java/app/memo/ui/MemoTheme.kt` +
`MemoMotion.kt`.

## 11. Accessibility automation safety

Lock-pause (stop when device locked), wrong-app-pause (stop when the expected
package changes), confirm-to-type, never type into password fields, bounded
retry budgets with step-level action caps, post-action screen-state
verification. Device AI-app discovery uses the launcher inventory against
known signatures — `QUERY_ALL_PACKAGES` is not requested.

## 12. Scheduling & metrics

- `ReminderWorker` content-free notifications; `MetricCollectorWorker`
  (public URL patterns + manual paste fallback); `DailyBriefWorker`
  (counts only); `BootReceiver` reschedules.
- `MetricSample(metricKey, url, metric, value, capturedAt, source)` rows feed
  sparklines on Post/Company cards and the next plan's expectation blocks.

## 13. Testing strategy

- `core`: JVM JUnit tests per module area (CI gate).
- `app`: lint advisory in v1 (tighten to `abortOnError=true` in M4);
  JVM-unit-testable fragments (command grammar glue, token curves) get tests,
  device-only flows are exercised on hardware, not in CI.
- CI is the compiler: the dev machine has no JDK/SDK (constraint C-001), so
  every push runs `testDebugUnitTest lintDebug assembleDebug` on GitHub Actions
  and must go green before the milestone is "done".

## 14. Roadmap

- M0 scaffold + CI + docs (this commit)
- M1 core schemas + registry + DSL + engine + prompts + redaction + audit
- M2 Android executors: vault, browser runtime, automation, voice, models
- M3 Compose UI shell + schedulers + share intake
- M4 lint tightening + acceptance walkthrough (UC-1..UC-5 vs code) + STATUS
