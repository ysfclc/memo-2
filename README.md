# Memo

A voice-first, checkpoint-driven personal agent for Android. One design language,
one workflow engine, one data model, one approval surface.

Memo accepts intent (even from a locked screen, within Android's honest limits),
plans it as a typed workflow — `reason · browser_automation · app_automation ·
human_approval · data_persist · metric_collect · branch · retry` — routes every
step to the right executor (local orchestrator, frontier AI websites through a
hardened embedded browser, installed AI apps through accessibility automation,
opt-in APIs, or a human gate), **fills artifacts before asking for approval**,
executes with checkpoints, and measures outcomes so the next plan is smarter.

It is the unification of five earlier prototypes into one product — see
`CHANGELOG.md` for the source-app → Memo mapping and `docs/ENGINEERING_LOG.md`
for every decision made along the way.

## Status

See `STATUS.md`. Security posture and design system are specified in
`docs/ARCHITECTURE.md`.

## Build an APK without installing anything

1. Push any commit to `main` (or run the workflow manually).
2. Open **Actions → Build Memo APK → latest run → Artifacts → `memo-apk`**.
3. Install `app-debug.apk` on an Android 11+ device.

For a local build, see `BUILD.md`.

## Repository layout

```
core/   pure Kotlin: schemas, registry, workflow DSL + engine, prompts, redaction, audit
app/    Android shell: vault, browser runtime, automation, voice, scheduler, Compose UI
.github/workflows/build-memo-apk.yml   CI: tests → lint → debug APK artifact
docs/   ENGINEERING_LOG.md (living notebook), ARCHITECTURE.md, STATUS.md
```
