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

## Local dispatch and host deadline checkpoint — 2026-09-08

Supersedes the transport remaining-work paragraph above. The Engine no longer has a
production request writer or controller/watcher verification coupling. Upgrade resolves
its bound action before acknowledgement, flushes the response, then dispatches locally.
Failed flush permits retry/cancel. Host files cannot authorize a prepared receipt.
The watcher and JVM hook are registered before local shutdown actions are exposed.

Both hosts read the existing v2 handoff for their admitted PID and instance. Stopping
latches one deadline; later stale identity, disappearance or refreshed timestamps cannot
cancel or extend it. Pending, ready and incomplete handoffs all bound exit, including
native hooks after ordered close. Handoff observation never grants a free restart;
local deadline expiry becomes a charged hang before kill. The pending publication is
now the first ordered step. Migration callers/publication-failure behavior remain open.

Independent Java review found and closed the startup exposure race. Independent host
review found no blocker. Tauri conformance exercises the shared production supervision
loop with FakeEngineActuator; production ShellActuator's extra EngineHost identity check
is source-reviewed, not an installed AppHandle proof. Installer validation stays in E.

Verification:

- Fresh `gradlew.bat test --no-build-cache --rerun-tasks --console=plain`: 9m1s,
  9405 tests, zero failures/errors, 25 skipped, 1525 XML files across 34 modules.
  XML preserved in `tmp/b15-local-transport-full-xml` before filtered reruns.
  Versus B13: app-engine +1 test; UI -1 net (consolidated obsolete transport cases
  plus the intervening handoff lifetime proof); every other module count unchanged.
- Focused Java controller, lifecycle, production wiring, watcher and parser tests green.
  After the full run, the parser fixture tests gained explicit production decode
  assertions; the parser class and its PMD passed again. No production change followed
  the full run. Initial build found three unnecessary test qualifiers; fixed; final
  build excluding tests passed (325 tasks, 11s).
- Rust 77/77; explicitly rebuilt conformance binary; Node self-test 34/34;
  dev-runner helper tests green; both adapters 17/17.
- Closure: 3417 files, zero violations. Recoverability: 44 authorities/6 stores, green.
- Negative: ignoring local handoff in the Node supervisor left the responsive child
  in running/incarnation 1 and failed the narrowed churn case. Restoring the exact
  source passed 1/1. This is a narrowed falsification pair, not another full adapter run.

Raw logs are ignored; SHA-256 inventory follows.

| File | SHA-256 |
| --- | --- |
| `tmp/b15-local-dispatch-java-final.txt` | `cc897b705b1a3f6fa6828a4ecda4e6d1f9575ed6c87cffa6b7b8f28e9a9364af` |
| `tmp/b15-local-transport-parser-final.txt` | `4f2e320342a9ceb7dc96c24c4ccc15dcbb911722fa78c0327a8239cf24426877` |
| `tmp/b15-local-transport-build-final.txt` | `0d4ff814ed3dc963ad25ef23ffb362b65d5ffb09de042234ca2486ec3e868d56` |
| `tmp/b15-local-transport-full.txt` | `4076757c67571c5fa4c47bcd9bdac2a13606011089701c8d7396ac421301f1f1` |
| `tmp/b15-local-transport-full-xml/summary.json` | `bce9af3fe1eca2a116b35d66c7c1d4f11c70d3977ca2efa938c6d49c31649eec` |
| `tmp/b15-local-transport-full-xml/inventory.json` | `46dc0c841bb45f95eafaa71eabed85a8d48ed8b2ec6222be2538732f1f6b9bc9` |
| `tmp/b15-handoff-rust-final.txt` | `496513fcbcdc120ba430ee6b48f9e56d8c11e2c32fa7ff2045ea863f46db63d8` |
| `tmp/b15-handoff-bin-final.txt` | `d40e39dc8f8c55054cbcbdccd0cb8bfdc0aa9abdb54514118e571d43d1e374dd` |
| `tmp/b15-handoff-self-test.txt` | `ae016a7e81b7ae279cbc8e4e8a12f8030d8ef89da901cfeb21dc883fcad7438c` |
| `tmp/b15-handoff-dev-unit.txt` | `1c6d9f17f3e7dc7e2c1bbbfefb29e3277bddc29dd1731c4167cbfe76b2f5e357` |
| `tmp/b15-handoff-dev.txt` | `6a804ad96bc69c8899d023f92493c79f67d4c6d873efe6b7dbb78ce5988bb43c` |
| `tmp/b15-handoff-tauri.txt` | `d4917f0c82cc87e4ed56ddaa18a410f9e46f56d0e5c8620ddda28279065f059b` |
| `tmp/b15-handoff-negative.txt` | `8f2d6a744aadd0fd700f6af9710d93e6a749f48dcd6ecd695c9bec62ffafbd7a` |
| `tmp/b15-handoff-restored.txt` | `a1a92af146503431126a71460f06643cd46c38fc9c82e3d353874d70a76426c3` |
| `tmp/b15-local-transport-closure.txt` | `128d1612506b1e123bc0dbc2ef3c6369a9edc50be0e170edd7a16cb6edb4b7f8` |
| `tmp/b15-local-transport-stores.txt` | `bbd2dca5fc9f39cf55be22fd731465c9eddf464a44558ebbc3f1aacd0e66fc9d` |
