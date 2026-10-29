# PLAN STATE

CHAIN: Claude (plan) -> GPT leg1 -> GLM leg2 -> GLM leg3 (this session)
UPDATED: 2026-10-29T15:02:32Z (STEP D merge commit bc2a4ce)

STEP STATUS:
  M0 foundation + R1/R2 reviews + fixes: DONE (merged to origin/main 00a8a9e, milestone/M0 2439013)
  M1 wave 1 (UI-KIT M2.B0b-d, LLM M1.A1-A3, HUB M1.B1-B2): DONE (inside milestone/M1, CI green 40405377602)
  WP CHAT (M1.C1, M1.C2, M1.B3): DONE — merged into milestone/M1 (9f15308 + click-guard fix 4be0b43, CI green 40889886051); docs chains now ride on milestone/M1
  WP VOICE (M2.A1 done, M2.A2 ~done incl. emulator Kokoro, M2.A3 mini-tasks 1-5 built, M3.6.C1 deferred to M3.6): DONE-BY-LEG2; STEP D merge commit bc2a4ce on wp/voice (merged milestone/M1 4be0b43 in): ONE shared InferenceManager for chat + voice (ChatService consumes MemoRuntime's manager via addListener/removeListener; close() detaches only), TalkScreen voice-first Voice/Text switch over LiveConversationScreen | ChatScreen, RuntimeFactory.kt deleted (zero callers). Local compile + targeted tests green (zero new warnings). Log renumber: voice D-038/039/040 -> D-040/041/042, voice C-009 -> C-013.
  M2.B1: mini-tasks 1-3 on wp/voice (CI green via 40887088494); B1-4 leftovers (composer hooks, ReadAloud.kt, voice-message bubble) are next after STEP D closes; device probes = end-of-M2 batch (first audio <1.2 s, echo floor 0.04f UNVERIFIED)
  WP AUTO-CORE (M3.5.A1/A2, M4.A0, M4.L1): NOT STARTED
  M1 close / M2 close (MCP): NOT STARTED (device checklists — user's emulator/phone)

CURRENT STEP: D — close it: push bc2a4ce, dispatch build-memo-apk.yml on wp/voice, gh run watch green
NEXT ACTION: after green -> merge wp/voice into milestone/M1 (B-STD, push, CI on milestone/M1), then STEP E = M2.B1 leftovers one micro-task at a time; device probes stay end-of-M2 batch.
OPEN BLOCKERS: none. Resolved by user 2026-10-29: (1) commons-compress 1.28.0 accepted (leg-2 evidence; D-040 after renumber); (2) agent performs the CI-gated merges per TASKS §0.9; (3) parallel CI/watch approved by user.
VERIFY BASELINE: B-STD `.\gradlew.bat --no-daemon --stacktrace :core:test testDebugUnitTest`
  - c293c25 green (leg-2, PROGRESS STEP 2); milestone/M1 CI green 40405377602; merged-chat-side CI green 40889886051 (4be0b43);
    wp/voice tip 5343db1 CI green 40887088494 (rule 7 closed for WP VOICE work at that tip);
    STEP D local: :app:compileDebugKotlin + :app:compileDebugUnitTestKotlin BUILD SUCCESSFUL (1m40s, zero new warnings), targeted testDebugUnitTest (InferenceManagerTest, ChatServiceTest, VoiceLoopTest, NavGraphTest) BUILD SUCCESSFUL (42s).
  - re-run at each merge as the no-new-failures gate.
