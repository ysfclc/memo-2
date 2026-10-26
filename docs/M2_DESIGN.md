# M2 — `:app` Android Application Layer

## Scope
Connect the `core:jvm` module to the device: vault persistence, browser/webview, voice command parsing, model dispatch, automation bridge, and the checkpoint-driven approval UI. All logic stays in `core`; the app layer only wires seams and UI.

## Seams (core → app)

### 1. MemoVault / DataStore persistence
- **Core type**: `Workspace` (8 registries: people/companies/tasks/drafts/posts/samples/apps/jobPostings)
- **App responsibility**: serialize `Workspace` → `SynCodec` envelope (strict validate → migrate → load); on startup, `HashChainAudit.verify()` against last persisted envelope; quarantine & restore per audit law.
- **App entry point**: `MemoVault.save(workspace)` → `SynCodec.serialize(workspace, exportedAt)` → write per-collection file; `MemoVault.load()` → `SynCodec.parse(text)` → `HashChainAudit` restore or quarantine → rebuild `Workspace`.
- **Verification**: every append hands the full chain to `InMemoryAuditSink`; the app's persistent sink must grow as `[1,2,3,...,n]`.

### 2. BrowserExecutor (hardened WebView)
- **Core contract**: `OutboundChannel.FRONTIER_SITE` + `OutboundEvent` (runId/stepId/target/action/contentHash). Hash re-verification on `deliverGate` mismatch → `NEEDS_REVIEW`.
- **App responsibility**: 
  - WebView with `setJavaScriptEnabled(true)` + `addJavascriptInterface` for limited bridge (no eval, only `window.Memo.artifactReady(hash)` + `window.Memo.questAnswer(question)`).
  - Provider whitelist from `ProviderRegistry` (8 frontier providers, byte-verified hosts + start URLs).
  - JS-side: each navigation loads `https://<provider>.com/` inside the hardened WebView; any `target` that doesn't match a whitelisted host is rejected before leave the page.
  - Post‑run: deliverGate compares `contentHash` (CanonicalJson) against the artifact the JS returned; if mismatch → `NEEDS_REVIEW`; if ok → `COMPLETED`.
- **Test hook**: the engine already captures one `OutboundEvent` per completed side effect; the app's sink must record every append as a growing list.

### 3. Voice / LocalAssistant
- **Core contract**: `CommandGrammar.parse(raw, now, zone)` → `VoiceCommand` (Cancel / Reminder / CaptureTask / CapturePerson / ComposeDraft). `PlanProposalParser.parse(json, now)` → `PlanParseResult`. `WorkflowPolicy.validateSpec(spec)` projection round‑trip.
- **App responsibility**:
  - UI: a microphone button that records user speech, sends the raw string to `CommandGrammar.parse`.
  - On `VoiceCommand.Reminder` → set Android alarm/notification (outside the hash‑chain; clearly marked “outside device”).
  - On `VoiceCommand.CaptureTask` / `CapturePerson` → populate a `StepSpec` and feed it into the engine as the next step.
  - On `VoiceCommand.ComposeDraft` → show a small Compose screen where the user edits the topic; the final plan is submitted to the engine.
- **Design note**: all model‑only derivation stays in `ReasonExecutor`; no side effects leave the device without a gate.

### 4. AppAutomationExecutor (native app steps)
- **Core contract**: steps of kind `APP_AUTOMATION` — the engine never executes these directly; it passes them to the app which uses Android `UiAutomation` or accessibility services to drive the target app.
- **App responsibility**: implement a service (`MemoAutomationService`) bound to `BIND_AUTO_CREATE`; receive a `StepSpec` (id, kind=APP_AUTOMATION, provider, artifactHint, requiresGate) and execute the corresponding UI gestures; on completion, return `Attempt.Completed(outputJson)` or `Attempt.Failed(reason)` to the engine.
- **Safety**: the engine only ever dispatches APP_AUTOMATION after a `HUMAN_APPROVAL` gate has bound the `contentHash`; the app must not start an automation until the gate is `COMPLETED`.

### 5. ModelManager (litertlm‑android models)
- **Core contract**: `ModelCatalog.entries` (3 pinned models with verbatim sizes, SHA‑256 pins, `/resolve/<rev>/` URLs); `MIN_MODEL_BYTES` sanity floor; `ModelRole` (ORCHESTRATOR / CHAT / DEEP); `isRunnableSize`.
- **App responsibility**:
  - On first launch, read `ModelCatalog`; if no model on device meets `isRunnableSize`, display “download model” UI that fetches the exact URL + SHA‑256 and stores the jar/files in the app’s private directory.
  - `LocalAssistant` orchestrator prompt always selects from the pinned list; the app loads the chosen model and routes inference calls through it.
  - The orchestrator system prompt (Prompts.orchestratorSystem()) expects the model to output exactly ONE JSON plan — the app enforces this by discarding any non‑JSON output and re‑prompting.
- **Note**: the 3 pinned models are the only ones the on‑device AI ever trusts; any size/SHA change requires a new audit entry and a new model catalog release.

### 6. RunJournal / re‑execution safety
- **Core contract**: idempotency key `runId:stepId:attempt` (`String`); `attemptResults` in‑memory map; on device restart, the journal file (JSONL) restores `attemptResults` and re‑runs interrupted steps.
- **App responsibility**: 
  - On `INTERRUPTED` return from `executeStepAt`, write a journal entry `{runId, stepId, attempt, status: "INTERRUPTED", timestamp}` to a private file.
  - On app start, read the journal; for every `INTERRUPTED` entry where `resumeAt <= clock()`, re‑execute the step via the appropriate executor; the engine’s `advance` will then see `PENDING` and continue.
  - The engine’s `attemptResults` cache is **in‑memory only** — the journal owns cross‑process safety.

### 7. Approval screen / gate UI
- **Core contract**: gate steps open `WAITING_USER`; `deliverGate(runId, stepId, approved, contentHash, note)` re‑verifies the hash; mismatch → `NEEDS_REVIEW`; `veto` skips side effects up to the next gate.
- **App responsibility**:
  - UI: when a step status becomes `WAITING_USER`, show the artifact preview + "Approve / Veto / Review" buttons.
  - Approve → call `engine.deliverGate(id, "gate", true, contentHash, "task ok")`; if the engine returns `COMPLETED`, advance to the next step.
  - Veto → call `engine.deliverGate(id, "gate", false, "", "not my words")`; the engine will skip to the next gate and the run continues.
  - Review → show the hash comparison (expected vs. actual) and let the user decide.

## Module boundaries

```
app/
 └─ MainActivity          (Compose UI shell, one screen at a time)
 └─ MemoViewModel         (holds current Run status, side‑effect state)
 └─ Repository            (wraps MemoVault + ModelManager + BrowserExecutor + AppAutomationExecutor)
 └─ BrowserScreen         (WebView + provider whitelist)
 └─ VoiceScreen          (microphone + CommandGrammar dispatch)
 └─ ApprovalScreen        (gate + veto buttons + hash comparison)
 └─ ModelDownloadScreen   (fetch & verify pinned model)
 └─ AutomationService     (UiAutomation bridge)
 └─ RunJournal            (JSONL file, INTERRUPTED restores)
 └─ ThemeKit              (Paper+Mint tokens: paperBackground/evergreen/mint/ink/mutedInk)
 └─ MemoMotion            (Compose motion primitives)
```

## Design invariants (from M1 law)

| Law / Decision | App implication |
|---|---|
| **D-012** `validateSpec` projection | The app never passes a full `WorkflowSpec.toJson()` to the parser; always build a `{title, steps}` projection first.
| **D-013** branch‑on‑failure rescue | The app’s back‑hand for a FAILED step is to check whether a later BRANCH step watches that FAILED outcome; if so, divert to that branch (skip intervening steps). Otherwise, present a "plan failed" UI.
| **D-014** RETRY attemptsMax | Any `{"kind":"retry","target":"x","attemptsMax":N}` folded by the parser must have N ∈ [1,5]; the app should never see a default‑3 fallback — if it does, it’s a bug.
| **D-015** `JSONObject.NULL` | The app never stores `put(key, null)` in canonical JSON; if a model output contains a null, the app routes it through `CanonicalJson.canonical()` which now emits proper `null` instead of the string `"null"`.
| **cancel strips .!?** | Any voice command the user speaks should have trailing `.!?` stripped before `CommandGrammar.parse` — the parser does this internally, but the app UI should not add extra punctuation.
| **step-id shape `^[a-z][a-z0-9_]{0,23}$`** | Step ids generated by the app (e.g., from voice or automation) must not contain hyphens; the parser will reject them.
| **Green CI = definition of done** | After every push, monitor `gh run watch` until green; the app source is only considered “merged” when the CI suite (11 JVM tests + APK build) passes.

## M2 deliverables (what to commit)

1. `app/src/main/java/app/memo/...` — the full app source (activities, viewmodels, repository implementations).
2. `app/src/androidTest/...` — UI instrumentation tests that verify the vault round‑trip and the approval flow (mock the engine with a `RecordingAudit`).
3. `docs/M2_DESIGN.md` — this design note.
4. Update `app/build.gradle.kts` if any new dependencies are needed (e.g., `androidx.window:window-extension` for side‑panel, or `androidx.lifecycle` viewmodel‑savedstate).

All core‑level laws remain invariant: the app layer is a thin wiring layer; the core is the compiler. No new plan DSL elements, no new step kinds, no changes to `STEP_ID_SHAPE` or `MAX_STEPS_PER_RUN`. The only app‑visible changes are the seams listed above.

## Next steps after M2 commit

- M3: Compose UI built on `MemoTheme.kt` + `MemoMotion.kt` tokens; replace the `MemoSplash` with Live / Talk / Work / Studio / Browser / Approvals / Models / Settings screens.
- M4: Tighten `lint { abortOnError = true }`; add `STATUS.md` acceptance checklist; device‑farm run on physical Android 13+ to verify the browser WebView hash‑binding and the vault persistence under process death.