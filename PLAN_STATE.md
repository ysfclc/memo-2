# PLAN STATE
CHAIN: Claude(plan) -> GPT leg1 -> GLM leg2 -> GLM leg3 -> Claude leg4
UPDATED: 2026-10-31 (leg-4, parallel GLM batch)
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
  G9 M2.B1 missing tests: GLM running (worktree ../memo-wt/g9)
  G5 M2.A2 in-app voice model download: GLM running (worktree ../memo-wt/g5)
  G4b-1 core VoiceClipDraft: CI pending 41186623683 (task/m2-b1-clip-draft, GLM, lead-verified)
  G4b-2 app send-with-clip wiring: OPEN (after G4b-1 merges)
  G6 M0.C2 encrypt voice WAVs: OPEN (after G4b-2; MediaPlayerClipPlayer must switch to a decrypting MediaDataSource)
  G7 M1.A3 inference FGS wiring: CLOSED - CI 41184362573 green on 8ab13ed (GLM)
  G8 M1.B1 Llama 3.1 8B seed: CI pending 41186628822 (task/m1-b1-llama31-seed, GLM, lead-verified)
  G10 docs/hygiene sync: OPEN
  G11 device batch (user's phone): OPEN
  ID app.memo2 / label Memo (D-053, D-054): app-id CI 41185263209 green; label CI 41186450339 on 33b86e1 pending

CURRENT: parallel - G8 + G4b-1 in CI, G9 + G5 with GLM; then G4b-2, G6, G10
NEXT ACTION: as each GLM run finishes: review diff, lead B-STD, commit, merge origin/milestone/M1 (union merge on logs, D-055), push, dispatch CI without waiting; merge each green branch into milestone/M1; then brief G4b-2 (ChatController.send attaches composer.voiceClip; ChatComposer Recorded.Message/Untranscribed -> attach clip; chip with remove; ChatThread skips ChatMarkdown when text == transcript)
VERIFY BASELINE: B-STD `.\gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest` -> 697 tests 0 fail (2026-10-31, after G4+G7+ID change)
OPEN BLOCKERS: G11 needs the phone (user: uninstall the other-key Memo not needed any more after app.memo2). User decisions PD1 (unused kit components), PD2 (Piper), PD3 (vision re-grounding), PD4 (bulk approval)
