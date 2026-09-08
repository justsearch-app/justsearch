# B13: updater ownership while the Engine is unavailable

Date: 2026-09-08. Candidate: primary lane F above `c7a0aae41`.

## Implemented and independently reviewed

The production post-staging updater coordinator holds Engine replacement and joins
its current supervision loop before stopping anything. An available API retains
prepare/commit, nonce-bound receipt and final process-death verification. A failed
normal-path RPC cannot fall back to the unavailable-Engine path. With no admitted
API, the host stops and reaps its owned child, then validates and reconciles the
manifest's registered children before installer launch. It never writes the Engine's
manifest. PID/start-instant/executable mismatch leaves an unrelated process alone;
unknown identity or unconfirmed termination prevents installation.

The existing durable intent carries distinct `ENGINE_UNRECOVERABLE` stop evidence
through launching and reconciliation. It cannot contain a fabricated preparation,
nonce or receipt. Java attestation checks the evidence kind and exact attempt;
normal receipt/nonce validation remains intact. Installer launch failure resumes
one owned child and one supervision loop. Uncertain stop, cleanup or persistence
keeps replacement held and exposes repair-required state.

The independent read-only reviewer found no remaining source blockers after the
root fixed supervisor-installation versus hold ordering and retained an already
exited owned child's PID. A second read-only review approved the two B11 integration
test corrections and test-only store classification. The manifest HTTP fixture now
uses the current v2 while retaining pinned v2 schema assertions. The ordered-close
test verifies pending ownership before teardown, completion after all resources,
and completion before exit.

## Bounded host proof

`cargo test --lib --locked` passed 76 tests on the integrated primary tree. The
new coordinator fixture calls the production post-authenticated-staging function;
its real EngineHost owns a real process and supervision thread. A Windows case
also owns a real registered process and exercises the production identity parser
and termination helper before the installer-launch edge. Only HTTP responses and
OS installer launch are injected. Negative control removing the coordinator hold
failed at the fixture's hold assertion; restoring it passed. The fixture additionally
covers launch recovery, mixed evidence, malformed/future manifests, commit and
receipt failure, tampered staging, and witness/intent persistence failures.

The Rust policy override test had a process-global environment race exposed by
parallel execution. It now tests the production loader through an injected lookup;
production still reads the environment. No assertion or product policy was weakened.

## Verification

- Build excluding tests passed (325 tasks); Java formatting correction preceded it.
- Frontend typecheck and 6,462 unit tests in 482 files passed; all 27 frontend gates passed.
- Dev-runner supervisor helper scenarios passed. Runtime closure scanned 3,416 files
  without violations. Store recoverability passed with the new cfg(test)-only
  fixture classified; production store classifications were not relaxed.
- Three affected UI contract classes passed after the full run exposed two stale
  B11 assertions. The first integrated full invocation failed on those two tests;
  its exact task XML was preserved before any filtered rerun. It is not acceptance
  evidence. Final complete-suite inventory is recorded below after it finishes.
- Sandbox coverage derivation passed with `upgrade-dead-engine-recovery` registered.

## Installer deferral and remaining Stage B scope

This is the branch host-level proof permitted by design section 0's dated B-stage
sandbox decision. It is not a signed installer, packaged AppHandle, or recovered
user-store proof. Scheduling amendment, 2026-09-08: stage E owns this final-validation
exercise; if main-only signing prevents a final candidate artifact, run it on the
first eligible signed installer after the final merge and carry the gap through
stage F until it passes. The signed installer round must induce a
non-transient Engine boot failure, update through the pre-API recovery UI, verify
child reconciliation, and open/search all inherited stores. The exact procedure is
registered in `governance/sandbox-coverage.v1.json` and remains owed until executed.

B15 requested restart and B17 hostile-lock/replay proofs remain separate. D1 live
component replacement and the suspended shared request-slot protocol are not part
of this batch. Raw output stays in ignored `tmp/`; hashes identify the original files.

## Artifact hashes

| Primary worktree artifact | SHA-256 |
| --- | --- |
| `tmp/b13-integrated-rust.txt` | `7ba6ce0ad469cff9b6abba407e0bf9edaf81a8e5133c04449484294f24350339` |
| `tmp/b13-integrated-build.txt` | `4cd540aec8ff54ba7906c63e616be3ae402164efdc7772745c86417d870545b7` |
| `tmp/b13-integrated-ui-typecheck.txt` | `596373b383c6e3421b378952b2fbf2d04b8a1bcef36fd19dc1812d88a565e100` |
| `tmp/b13-integrated-ui-tests.txt` | `b4d7faa67783fd07d100f6587340c4927b717ddc22b0d6f82761563ce8f2e1a4` |
| `tmp/b13-integrated-node.txt` | `7163cb69f6163d33c3a28062dfddd7424573341992100337db9f11a018e048c9` |
| `tmp/b13-integrated-ui-gates.txt` | `200df96c553990f84cfbae112065255e0562c792f847063bea6ec2341434ccc5` |
| `tmp/b13-integrated-store.txt` | `bbd2dca5fc9f39cf55be22fd731465c9eddf464a44558ebbc3f1aacd0e66fc9d` |
| `tmp/b13-integrated-closure.txt` | `b793c4a08b2c7bb2efaf1b082fea86965c6c3a11da386bf7719d04b61efa51bb` |
| `tmp/b13-integration-contract-tests.txt` | `c7fc26e36f32928f4a9e2a27a2d7dbedd579137d8ef4029a05a83a87ac0d173e` |
| `tmp/b13-sandbox-coverage.txt` | `849f2ce9c0bea6cf7e817c7ab50bf11e4115adc6bd9439d8aa35e317c34af730` |

The hold negative control is retained in the preserved checkpoint worktree at
`../lane-F-b14-local-recovery/tmp/b13-hold-negative.txt` (SHA-256
`e6cd27504b8692e9e4801e7e5b09f6b98181971805e353f72d4037c3aea04be0`).

## Final integrated Java inventory

`./gradlew.bat test --no-build-cache --rerun-tasks` passed in 8m52s: all 190 tasks
executed, 9,405 tests, zero failures/errors, 25 skips, 1,525 XML files in 34 modules.
The inventory includes only `modules/*/build/test-results/test/TEST-*.xml`, excluding
stale output from schema generation or another test task. It was copied before
any later filtered rerun. Stress and installed-process integration are separate tiers.

| Module | Tests | Skips |
| --- | ---: | ---: |
| adapters-lucene | 709 | 0 |
| ai-backend | 62 | 0 |
| api-contract-projection-java | 21 | 0 |
| app-agent | 661 | 0 |
| app-agent-api | 228 | 0 |
| app-api | 195 | 0 |
| app-config | 2 | 0 |
| app-engine | 128 | 0 |
| app-inference | 308 | 0 |
| app-launcher | 60 | 0 |
| app-observability | 379 | 0 |
| app-services | 2513 | 3 |
| app-util | 16 | 0 |
| benchmarks | 104 | 0 |
| configuration | 267 | 0 |
| core | 77 | 0 |
| core-contracts | 38 | 0 |
| dead-code-audit | 2 | 0 |
| extension-substrate | 7 | 0 |
| gpu-bridge | 77 | 0 |
| indexer-worker | 365 | 12 |
| indexing | 84 | 0 |
| infra-core | 3 | 0 |
| ipc-common | 4 | 0 |
| ort-common | 164 | 0 |
| prompt-support | 5 | 0 |
| reranker | 68 | 0 |
| ssot-tools | 17 | 0 |
| system-tests | 96 | 0 |
| telemetry | 88 | 1 |
| test-support | 13 | 0 |
| ui | 1059 | 1 |
| worker-core | 335 | 6 |
| worker-services | 1250 | 2 |

Both integrated conformance adapters passed 11/11. The first Tauri invocation
used a pre-existing 06:41 executable and failed the new essential-stability case:
this adapter does not build on demand. Explicitly rebuilding the binary from the
integrated source and rerunning passed; no timeout or assertion was weakened.

| Additional artifact | SHA-256 |
| --- | --- |
| `tmp/b13-integrated-full-suite-final.txt` | `4b80f0b570d2532eda52b53ad0a817e707e09438e78abab1b57f476d5b79ddaf` |
| `tmp/b13-integrated-full-final-xml/summary.json` | `46833b5c0ac1ff9abf988cafd9e405cd02c4f41dc6f45bbf2684d515110a8113` |
| `tmp/b13-integrated-full-final-xml/inventory.json` | `778aab509d3b8ebe6fb8707c79dcde847cdc05059b9691e4da64f771a3aef395` |
| `tmp/b13-integrated-dev-conformance.txt` | `c19b2e4f64ab2d9c4fa9d48c9ce0a6a2b969ccb2733192b7eab1fb43d7e9ad77` |
| `tmp/b13-integrated-conformance-build.txt` | `ff584ae847cc0f3a15bef65979f1ba43b7950b8c13fb2a236a9f8f596427fcfa` |
| `tmp/b13-integrated-tauri-conformance-final.txt` | `c5bcbfcc373bce7a0fde224bbe5be01f52ed4169748363b26a4cc37219bf0223` |
| `tmp/b13-integrated-tauri-conformance.txt` | `f4a12bee05eb39407f5ab5000e774d9bf58a53ed4391437179ca92cbdf9dc21b` |

Final post-integration build (`./gradlew.bat build -x test --no-build-cache`) passed
in 21s, 325 tasks. `tmp/b13-integrated-build-final.txt` SHA-256:
`b8eb75c0d9834ab2eaad89c9b412bcf3a9310aaeb611c5adb86d6db8fd5f249a`.
