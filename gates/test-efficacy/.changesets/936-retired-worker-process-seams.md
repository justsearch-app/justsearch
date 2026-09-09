---
classification: seam-retraction
tempdoc: 936
---

Retire the stale worker-supervision and worker-liveness-suicide mutation baseline rows.
Stage A already removed their production authorities, tests and logic-seam entries:
SupervisionDecision in A11 (1a72bbf91), WorkerLivenessDecision in A10 (649f5992d).
The index half now lives inside Engine, so the old Head-to-Worker restart and heartbeat-suicide
decisions no longer execute. Stage B owns host-to-Engine supervision and its conformance proofs.

This completes the superseded baseline teardown missed at those deletions. It does not exempt
any active seam or lower its strength floor or uncovered-mutant ceiling. All17 current registered
seams must still be measured. See docs/design/lane-f-engine-jvm/evidence/C1/governance-sweep.md.
