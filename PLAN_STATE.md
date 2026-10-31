# PLAN STATE
CHAIN: Claude(plan) -> GPT leg1 -> GLM leg2 -> GLM leg3 -> Claude leg4
UPDATED: 2026-10-31 (leg-4, gap G1)
AUDIT + GAP REGISTER (authoritative detail): ~/.claude/plans/use-glm-5-4-free-staged-koala.md
RULES: AGENTS.md rule 10 (in-place updates, shared debug keystore), rule 11 (strict implementation; /implement)

STEP STATUS:
  M0: DONE (M0.A2 closed by G2) except M0.C2 INCOMPLETE (plaintext voice WAVs, G6), M0 close UNVERIFIED (G11)
  M1: A2/B2/C1 DONE; A1/C2 UNVERIFIED (device); A3 INCOMPLETE (FGS, G7); B1 DRIFTED (Llama 3.1 8B seed, G8); B3 DONE; M1 close INCOMPLETE (G11)
  M2: B0a/B0b/A1 DONE; B0c/B0d/A3 UNVERIFIED; A2 INCOMPLETE (in-app voice download, G5); B1 INCOMPLETE (E2/E3 uncommitted, G3/G4/G9); C1-C3, U1-U5 MISSING
  M3+: MISSING (order revised per Relay delta in the plan file; pending PD3/PD4)

GAP REGISTER:
  G1 INCOMPLETE D-048 keystore/rules: CLOSED - CI 41177528061 green on 1b77d52; CI APK signer SHA-256 9e526ee8...20be2e = committed keystore
  G2 DRIFTED M0.A2 WorkflowAuditTest: CLOSED - CI 41180102118 green on c93398d; WorkflowAuditTest 4/0 fail; implementer glm-5.3-flash-free (D-050)
  G3 M2.B1 E3 ReadAloud merge: OPEN
  G4 M2.B1 E2 voice bubble merge: OPEN
  G9 M2.B1 missing tests: OPEN
  G5 M2.A2 in-app voice model download: OPEN
  G6 M0.C2 encrypt voice WAVs: OPEN
  G7 M1.A3 inference FGS wiring: OPEN
  G8 M1.B1 Llama 3.1 8B seed: OPEN
  G10 docs/hygiene sync: OPEN
  G11 device batch (user's phone): OPEN

CURRENT: G3
NEXT ACTION: G3 - review the uncommitted E3 ReadAloud work in ~/.local/share/opencode/worktree/1a05db/e3-read-aloud (task/e3-read-aloud @ df9932b): read full diff, rebase onto milestone/M1 head, renumber its D-047 to the next free D-number, remove its false "one commit" PROGRESS claim, B-STD, commit, CI, merge
VERIFY BASELINE: B-STD `.\gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest` -> core 429/0 fail, app 230/0 fail (2026-10-31, after G2)
OPEN BLOCKERS: user decisions PD1 (unused kit components), PD2 (Piper), PD3 (vision re-grounding), PD4 (bulk approval)
