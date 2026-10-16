---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b24-one-quit-repair-20261008
---

# One quit repair test intent

The orchestrator's 2026-10-08 repair brief (agent wording under the owner's end-to-end delegation,
cited below as delegated choices) supersedes the previous delegated legacy-request
choice. The independent rejection in b6d536-one-quit.md remains intact. No builder
acceptance is claimed. External before XML and logs are in
F:/agent-sandbox/design-b6d536/codex/b24-one-quit-repair/report.md.
The D4 evidence is machine-produced. For its before run, TMP and TEMP point inside
the required worktree directory, and the candidate's real node_modules directory
is temporarily moved to an ignored local holding directory and restored afterwards.
The runner therefore creates no node_modules junction in its before worktree.
No governance tooling is modified. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java", "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppBootQuitTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Unaddressed requests are STALE; a nonce-less request is deleted without invoking shutdown at after-lock, before-index and child-capable checkpoints. Expired requests are deleted at every checkpoint. Watcher admission refuses old or unaddressed requests. Valid own addressed requests still exit zero at all three checkpoints; restart remains available to the watcher. This replaces the former legacy acceptance expectation.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Boot honours only a quit request addressed to this Engine incarnation; requests from earlier incarnations, without nonce, or expired are deleted and not honoured.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 builder repair brief Q2'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-one-quit-repair.json",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java", "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppBootQuitTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/HeadlessAppExtractionQuitTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A real active extraction child, whether it accepts or ignores EOF, has exactly one owner STOP_INTENT and CHILD_EXIT, REQUESTED with reason QUIT, before the fatal exit callback when the ingest drain is abandoned. The quit stays within its budget and refuses new extraction children. The same fixture runs the original binding on b1cfcac8e and fails the assertion for the absent exit record before halt. The extraction child uses the current JVM executable on every OS; its EOF behaviours, quit budget and ownership assertions are unchanged.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Every extraction child the Engine spawns must have its exit recorded by its owner (Spec B ledger) as intended with reason or unexpected, including when the quit abandons its unit.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 builder repair brief S15'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-one-quit-repair.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/HeadlessAppExtractionQuitTest.java"]
    },
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableQuitReplayTest.java"],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation: an actual configured-root SYSTEM_INTERNAL ingest with persistent structural authorization, a real blocked extraction child and nonyielding enumeration acknowledgement leaves parent and child RUNNING on fatal JVM halt. The next actual EngineRoot replays each exactly once to COMPLETE (attempts 2, completed 1, failed 0), leaves one DONE file job, indexes multiple real chunks with unique IDs and unique parent/ordinal pairs, and never relaunches the abandoned Engine. This passes on b1cfcac8e too: no fail-before or replay defect is claimed. Production-boundary live verification is separate. CI portability repair: both the Engine probe and its extraction parser use the current JVM command on every OS instead of assuming java.exe under java.home. The deliberate interrupted extraction, real quit/replay process boundary, exact attempt counts, duplicate rejection and DONE assertions remain.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "D1's replay applies to units whose authorisation survives a restart.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 builder repair brief D1'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-one-quit-repair.json",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableQuitReplayTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/HeadlessAppUpgradeShutdownWiringTest.java"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The ordinary host request fixture now includes an injected matching incarnation and requestNonce. The complete original oracle remains: plain dispatch, no upgrade close, and no forged receipt. Receipt-bearing host requests remain refused. No assertion, observation, fault injection, condition or tag is removed.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A nonce-less request is deleted and not honoured at every checkpoint.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 builder repair brief Q2'; the text does not appear in the owner's turns of session 886833dd"}]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:7e1c8c2cedd98b1100d40092b4164d1bb44fd7ae42b07b8a0fb761fd490427d1"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "reject",
  "digest": "sha256:7e1c8c2cedd98b1100d40092b4164d1bb44fd7ae42b07b8a0fb761fd490427d1"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:5a1101bbdf572c65e2fe9f5b9ca44b1fa7569c2b990425b2ab7e78869af5d88c",
  "note": "Reviewed the current replay fixture/evidence changes and selected candidate passes. The exact declared b1cfcac8e before run matches all five recorded check outcomes: three assertion classifications and two preservation passes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS6a-20261016",
  "verdict": "accept",
  "digest": "sha256:6fbbaa3a654cd4f6823e10ee674645876402803c93468679b7d97a53ac922dc3",
  "note": "Reviewed the portable JVM launcher without any weakened quit, extraction or replay assertion. All candidate checks pass, and the declared b1cfcac8e replay reproduces three assertion classifications and two preservation passes with nonzero cases."
}
```
