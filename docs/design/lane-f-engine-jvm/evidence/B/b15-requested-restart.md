# B15 requested restart — 2026-09-08

This checkpoint implements the clean-completion exit, not all of B15. EngineExit 4
means a clean ordered RESTART completed. Both hosts restart it without spending the
crash budget even without a request file. An observed restart request does not excuse
exit 1: failed close remains charged. Quit, upgrade and hang keep host priority.

Independent read-only review found the initial request-precedence bug; the corrected
cut passed the second review with no blocker. The shared adverse case supplies both
an observed restart request and exit 1. Both real adapters assert the exit record,
second incarnation and budget; the pure case pins request observation even when a
process fixture might miss the transient file. Fileless absence is checked at recovery,
not monitored continuously.

Verification: focused Java EngineExit, EngineShutdownSequence and policy tests green;
Java build excluding tests green; Rust 76/76; harness self-test 33/33; dev-runner and
explicitly rebuilt Tauri adapter each 13/13. The Java commands preceded the host review
correction, which changed no Java. The last full multi-module suite remains the B13
9405/0 checkpoint; this is not a post-B15 full-suite claim.

Negative control: an isolated source copy without the clean-completion guard returned
an uncounted zero-cooldown restart for the adverse case, conflicting with its expected
counted restart. Production decision table then had zero mismatches. An earlier negative
invocation omitted the policy and is invalid evidence; only the valid log below is cited.

Remaining B15: retire the Engine request-file writer and controller/watcher coupling,
bound Engine-originated close using current-incarnation manifest handoff, connect
migration start/rollback/promotion to requested restart, wire/API projection and proof
that the promoted generation serves after restart. B17 remains separately open.

Raw output is ignored under `tmp/`; SHA-256 inventory:

| File | SHA-256 |
| --- | --- |
| `tmp/b15-fileless-java.txt` | `7183570d7455e69b0fd40f31ae33be70045f78bcec1fc57ce9bf3f2f521de7f8` |
| `tmp/b15-fileless-build.txt` | `8e736851300a5afd7b5268e1305cd1ba8fb0fd7680f34de38c8a2e799f5db993` |
| `tmp/b15-clean-restart-rust.txt` | `ec7dcf5c8b422c6031bfb07a0f20dfd8dc0139fbaa3613da36261fbdd28df30e` |
| `tmp/b15-clean-restart-bin.txt` | `7c9f276c5196551d45c5f0edbfe5527b11a35de6edc78c608f08c3615648044e` |
| `tmp/b15-clean-restart-self-test.txt` | `d0548da071c0a64a5df8b29a294648e417914b3b53aeff93a547077677ae43bf` |
| `tmp/b15-clean-restart-dev-final.txt` | `58e0869b48c9d9f697e9170cfa2b913ff73a5ee1faf9fdb2e19d3dcdccab9714` |
| `tmp/b15-clean-restart-tauri.txt` | `a15a34e8a7fd615fd8dead5320022783ec4568bbadb492bd44eb918be6a8747c` |
| `tmp/b15-clean-restart-negative-valid.txt` | `b91f37f2c92f70024c001d672211a52e5ee077c2f61ba697902dfe2bf3c5161b` |
