# AGENTS.md — Memo repository rules

This file is loaded into every AI session working in this repo. Obey it without being asked.

**Build plan:** `docs/BUILD_PLAN.md` is the single source of truth for what to build, in which order, and how each task is verified. Read it before starting any task. `docs/TASKS.md` is the executable runbook (every task from zero to end with mini-tasks, named tests, checks and build steps). `docs/FEATURES.md` is the feature checklist; the lead keeps it current. `docs/PROGRESS.md` is the lead's running notes (requests, decisions, verification, open items), updated at every step.

## Standing rule 1 — Engineering notebook (permanent)

Every meaningful thing learned or decided while working here goes into `docs/ENGINEERING_LOG.md` **in the same commit** as the work that produced it.

- New decision → `## DECISIONS` entry: `D-### · yyyy-mm-dd · decision · alternatives considered · consequences`.
- Changed decision → new dated entry referencing the superseded one. Never rewrite history.
- Environment/toolchain constraints → `## CONSTRAINTS`.
- Gotchas, version conflicts, CI lessons → `## NOTES BY MILESTONE`.
- Unverified claims must be labeled `UNVERIFIED` with the verification path.
- No secrets in the log, ever.

Other standing rules:

2. **Reuse-first registry law.** Before creating any artifact (person, job, draft, template, prompt…) the code path and the human both: look up by natural key, confirm, and only bump a versioned record on real change. No silent re-creation.
3. **No placeholders.** Every committed class must compile and, where logic is pure, ship with tests. Empty shells are failure, not scaffolding.
4. **Security before convenience.** No cleartext HTTP, no plaintext secrets, no silent outbound transmission; hash-chained audit for anything that leaves the device.
5. **Honest status.** `STATUS.md` states what is verified (CI/device) vs assumed. Overclaiming is a bug.
6. **One design language.** Colors/type/motion come from `app/src/main/java/app/memo/ui/MemoTheme.kt` + `MemoMotion.kt` only; no inline colors outside token definitions.
7. **Green CI is the definition of done** for delivered code: after pushing, monitor `gh run watch` until `pass`; loop on failures until green.
8. **Voice-first, checkpoint-driven.** Features serve intent → plan → route → fill artifact → approve → execute → measure, in that discipline.
