# C1 independent review correction ledger

The [2026-09-09 independent review](independent-review-2026-09-09.md) is part of C1 acceptance.
The owner requires its order: observer urgency and interrupted handoff first, then runtime close,
OCR close/pool ownership, Engine client cleanup and late failures, sandbox reader correlation,
health-monitor retries, CORS, and the named governance/test debts. C2 cannot start until these
fixes and the record-keeping are complete and reviewed. Only root edits this worktree; reviewers
are read-only. Later commits must be build-green individually and pushed immediately.

## Blockers implemented and independently reviewed

1. **Observer urgency at the production front.** RequestEngineContext stamps GET/HEAD status,
   health and diagnostic observers BACKGROUND. Caller attribution and survival are preserved;
   cooperative headers cannot choose urgency. Mutations and searches remain foreground.
   EngineObserverPacingTest uses real loopback HTTP, the installed API filters, shared admission,
   real EngineKnowledgeClient.getStatus and the real ForegroundLoad gauge. Both WEBVIEW and
   MCP_CLIENT observer requests leave startedTotal unchanged; a real search through the same
   front increments it. This closes the production defect, not merely its test observer.
   Adverse250 fails on the first real status poll. Restored251 passes the observer test plus
   context/transport regressions. ADR-0048 has a named front-exclusion probe and its historical
   producer-move note is restored, followed by the newer amendment. Registry check254 passes;
   that structural check does not replace execution of the referenced test.
2. **Interrupted handoff is failure, not clean truncation.** Both interrupted publish and
   interrupted delivery report through fail(), retaining the terminal error. drainAndClose only
   reports success when delivered >= accepted; closed alone is no evidence of delivery.
   Adverse249 fails the interrupted-delivery and explicitly-closed-undelivered tests. Restored251
   passes the complete BoundedHandoff suite. ScanRootWork's existing failure path now receives the
   interrupted delivery instead of returning its last partial frame as an unqualified success.
   The independent final review found no remaining production defect and requested the missing
   interrupted-publisher regression. That regression now fills a BLOCK queue, observes the producer
   actually waiting, interrupts it, and asserts false return, restored interruption, one terminal
   InterruptedException, refused later publication, and an undelivered tail that cannot drain or
   deliver later. Mutation277 restores publish's old close() and fails at the missing terminal
   InterruptedException assertion. Restored278 passes all13 handoff tests and PMD; final280 passes
   the producer test after making its wait-state observation immune to sampling races. Build281
   passes. The reviewed front fixture exercises GET with real installed filters/handlers; HEAD is
   inspected routing proof. These tests do not inject delivery interruption through a complete
   production scan port.

Logs: `tmp/c1-review-blockers-before-249.txt`, `tmp/c1-observer-before-250.txt`,
`tmp/c1-review-blockers-restored-251.txt`, `tmp/c1-observer-adr-coverage-254.txt`.
Publisher proof: `tmp/c1-publisher-interrupt-before-277.txt`,
`tmp/c1-publisher-interrupt-results-277/manifest.json`,
`tmp/c1-publisher-interrupt-restored-278.txt`, `tmp/c1-publisher-final-280.txt`,
`tmp/c1-publisher-final-build-281.txt`. Build279 also passed before the wait-state sampling correction:
`tmp/c1-publisher-build-279.txt`.
Run249 initially also encountered an inaccessible test constructor;250 corrected the fixture to
share the actual public client's admission owner and then demonstrated the production failure.
Neither compile failure is claimed as adverse behavioral proof.

## Remaining work, required in the owner's order

Runtime close is implemented and independently reviewed. It rejects late retains promptly, bounds
generation/timer/NRT waits, retains live resources on timeout, and retries through the server and
Engine root without releasing the enclosing index lock early. A registered NRT close task handles
the real zero-delay Lucene loop; reload and close share bounded serialization. Run267 is full green
before the reload-lock correction; final271 has one fanout test timing failure, now corrected with
an explicit parent-cleanup owner and passing focused274. Full299 at cleanbad1a7622 now passes,
including all five supervised recovery cases. Build273 is retained historical proof. See
[runtime-close.md](runtime-close.md) and [fanout-parent-exit.md](fanout-parent-exit.md).

- OCR component ownership is implemented: one reusable pool, bounded waits and document owners,
  real structured text preserved on refusal, and retryable enclosing service cleanup. Focused296
  passes61 cases; see [OCR evidence](ocr-component-close.md). Its review exposed uncontained native
  descendants when the parser JVM is forcibly recycled. Mandatory Windows parser Job containment
  now closes that boundary locally: all three recycling/close regressions fail without bootstrap,
  and restored tests plus the isolated Engine-crash native witness pass. See
  [parser containment](parser-containment.md). Continue with the next client item; final C1 review
  and integrated/live/hosted proof remain required.
- EngineKnowledgeClient registration close is aggregated and locally proved: both injected
  RuntimeException and Error cases retain all failures, retire all seven base/transport names and
  allow replacement registration. Adverse310 fails; restored311 and build312 pass. See
  [client failure cleanup](client-failures.md). Late failures are also ERROR logged after cleanup,
  with Error rethrown through the actual executor thread. Adverse313/315 separately refute absent
  logging and absent fatal propagation; restored314/316 pass14 cases and build317 passes.
- Sandbox reader refusal now retires the written-to child; schema2 request IDs prevent stale
  responses reaching later documents. A surviving retired child keeps its slot until actual
  termination. Adverse322 refutes all three guards; restored323 passes41 cases and build324
  passes. See [sandbox response ownership](sandbox-response-ownership.md).
- Re-arm health-monitor ticks on capacity refusal; distinguish owner CLOSED.
- Exempt OPTIONS from admission and expose Retry-After, with real transport tests.
- Register the routing sandbox logic seam and executor consult region; remove the completed B row;
  reconcile architecture floors, aggregate-refusal oracle, schema version, double-release and real
  shutdown admission tests.
- Correct the remaining Windows supervisor rename under concurrent readers; hosted34356123502
  still fails this production boundary after publisher serialization. Preserve both failed writer
  attempts even though the advisory system job passes on its third attempt.
- Complete independent review of all fixes; run full stress-enabled verification and final live
  standard-model pacing/admission, then obtain and record current hosted green including the
  advisory system tier. A successful overall workflow does not excuse a failed advisory job.

## Record correction

Original E0.1, Q2, section0 runbook and section16 aggregate sentences are restored. Dated
amendments state the current decisions; the two55-minute windows are preserved and an additional
10-minute run supplies120 measured minutes. The owner delegates Q2's decision, so it is not gated
on another reply. C2/D2 §1 now explicitly inherit the preliminary scan-expiry, CANCELLED and
agent-history paths. Earlier dependency-split WIP commits were not individually green; only
their combined tested revisions have evidence.

[Raw evidence SHA-256 inventory](raw-evidence-sha256.json) lists every literal temporary-path
citation, brace/glob expansion and available file hash, including directory contents. The
[last completed full-run summary](last-full-run-summary.json) records integrated271's failure
honestly; focused green is not substituted for that full run. Inventory generation resolved all
literal raw-file references; bare tmp/ citations denote storage roots, not individual artifacts.

Full267 completed with zero failures. Full271 has one fanout parent/child release timing assertion;
its immutable reports remain preserved even after focused274 passes the stronger test. The last
full-run JSON is not rewritten to green based on that focused result.

Full252 requested the entire stress-enabled build and isolated integration tier. It stopped on
two PMD tasks in the pending root-scan correction: one named-but-unused scope variable and two
unnecessary fully qualified test types. All represented test XML is green, but not every requested
gate executed. Both mechanical issues are corrected;255 still caught the remaining scope name,
then build rerun256 passes the final source, including PMD, in29 seconds. This is build-green,
not a substitute for the required full stress-enabled run after all ordered fixes. Raw logs and snapshot:
`tmp/c1-review-integrated-252.txt`, `tmp/c1-review-integrated-results-252/manifest.json`,
`tmp/c1-review-compile-255.txt`, `tmp/c1-review-compile-restored-256.txt`.
