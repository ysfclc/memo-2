# PLAN STATE
CHAIN: Claude(plan) -> GPT leg1 -> GLM leg2 -> GLM leg3 -> Claude leg4
UPDATED: 2026-10-31 (leg-4; ENTITIES ENT.1-ENT.4 done on milestone/M3)
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
  G3 M2.B1 E3 ReadAloud merge: CLOSED - CI 41183393240 green on 9014aba; ReadAloudTest 12/0; any-message drift fixed (D-051)
  G4 M2.B1 E2 voice bubble merge: CLOSED - CI 41184305022 green on 694c516 (D-052); bubble reachable only after G4b
  G9 M2.B1 missing tests: CLOSED - CI 41186974106 green
  G5 M2.A2 in-app voice model download: CLOSED - CI 41187606281 green
  G4b-1 core VoiceClipDraft: CLOSED - CI 41186623683 green
  G4b-2 app send-with-clip wiring: CLOSED - CI 41187965182 green
  G12 clip-only message dropped from model history (defect found in review): CLOSED - CI 41189276754 green
  G6 M0.C2 encrypt voice WAVs: CLOSED (G6a+G6b, D-056) - CI 41188846864 green
  G7 M1.A3 inference FGS wiring: CLOSED - CI 41184362573 green on 8ab13ed (GLM)
  G8 M1.B1 Llama 3.1 8B seed: CLOSED - CI 41186628822 green
  G10 docs/hygiene sync: part 1+2 done (TASKS/FEATURES/STATUS); OPEN: remove old opencode e2/e3 worktrees (fully ported, uncommitted files - needs user OK) and merged wp/llm, wp/hub worktrees
  G11 device batch (user's phone): OPEN
  ID app.memo2 / label Memo (D-053, D-054): app-id CI 41185263209 green; label CI 41186450339 on 33b86e1 pending

CURRENT: milestone/M3 a5e9a0d - BRAIN + APPROVALS (plan card) + ENTITIES ENT.1 (all 9 kinds persisted), ENT.2 (generic entities/relations + provenance, 18 registries), ENT.3 (evidence-backed extraction), ENT.4a/4b (approve selection; Extract action + review sheet) merged, CI green; user APK CI 41205370594
NEXT ACTION: ENT.5 CRM capture (person + company + place, reuse, follow-up tasks) on ENT.1-4; M3.B1b edit-before-approve rehash; then AUTO-CORE (M4.A0, M3.5.A1, M3.5.A2), WEB, TEACH-WEB, PORTER (D-057). Implementer order (user): opencode free -> Copilot CLI free -> Haiku; opencode + Copilot free quotas exhausted 2026-10-31 -> Haiku. Follow-ups list: per-run Stop; engine createRun(start=false); planning indicator; voice status hint; artifact key->name resolution; agent transcript cap; REASON query packet; extracted typed records carry no provenance (schema has no field). Machine: emulator off, one Gradle build at a time, lead B-STD in the FOREGROUND.
VERIFY BASELINE: B-STD `.\gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest` -> 697 tests 0 fail (2026-10-31, after G4+G7+ID change)
OPEN BLOCKERS: G11 needs the phone (user: uninstall the other-key Memo not needed any more after app.memo2). User decisions PD1 (unused kit components), PD2 (Piper), PD3 (vision re-grounding), PD4 (bulk approval)
