# C1 sandbox response and retired-child ownership

## Correction

A reader refusal after the parent writes a frame leaves a response on the parser pipe. Reusing
that slot can give the previous document's text to a later caller. PersistentExtractionSandbox
now retires/discards that child and classifies the refusal as SANDBOX_FAILED. Fatal submission
Errors also attempt retirement and are rethrown, retaining cleanup failures.

Protocol schema2 carries a fresh UUID requestId per request. Every production and fixture child
response echoes the received bounded opaque ID; the parent validates schema and exact equality
before turning a response into an artifact. Missing, mismatched, malformed and legacy responses
retire the parser. Schema2 is deliberately an Engine/bundled-parser upgrade together, not a
backward-compatible interpretation of an uncorrelated response. A custom command that cannot
speak schema2 fails the existing boot probe; process-routed documents never fall back in-process.
The request record owns the protocol version and ID bound; the response projects both. The byte
ceiling includes96 UTF-16 units at six JSON bytes each, preserving its under-frame-limit guard.

Root also found a structural resource leak in the same discard path: finishDiscard cleared a
slot after a five-second kill wait even if the process remained alive. Its persisted PID could
remain, but the Java owner and pool capacity were lost. One retirement reason now stays on the
existing child before kill. A surviving child keeps its exact slot, streams and cleanup owner;
next acquisition retries retirement before any request or replacement spawn. Only confirmed
process death clears that slot. This avoids an additional retired-child registry. Incomplete
retirement retains the original classification cause as suppressed failure evidence.

Sources: PersistentExtractionSandbox.java:303 (request identity), :319 (reader refusal),
:379 (response validation), :415 (retirement marking), :443 (confirmed-exit requirement),
:474 (retry before reuse); SandboxExtractionRequest/Response; ExtractionSandboxChild.
Design section0 assigns these changes to C1-7/C1-11.

## Verification

Environment: Windows11, Temurin25.0.2. Base837847a7c plus this item.

- Run320 compiled and passed every new behavior case, but the existing exact wire-size test
  expected the schema1 bound. Its intended limit guard is preserved; schema2 adds576 bytes,
  changing66,452,608 to66,453,184. This expected-value update is not adverse behavior proof.
- Run321 passes39 selected worker cases and PMD. The duplicate-response case returns the first
  frame successfully, then proves its duplicate cannot become the next file's text. Missing-ID
  and schema1 frames are separately rejected and followed by a successful fresh-parser request.
- The reader-refusal case saturates an actual one-thread/one-queue reader pool, then sends a
  real parser request. It requires SANDBOX_FAILED, the original rejection cause, a dead old PID,
  a restart count and correct subsequent text from a fresh parser.
- The surviving-retirement case wraps a real process with a controlled kill refusal. It proves
  both timeout and later acquisition retain the same live PID, no second spawn occurs, and
  allowing termination enables a new parser and successful next document.
- Adverse322 removes the reader-refusal catch, exact ID comparison and confirmed-exit guard.
  All three corresponding regressions fail: unclassified rejection, stale response accepted,
  and timeout disposal despite a live process. Source is restored immediately afterward.
- Restored323 passes all39 worker cases plus the existing Engine extraction chaos case and
  isolated Engine-crash native-descendant case (41 cases, no failures/errors/skips). It proves
  schema2 through the composed Engine and custom chaos command. Engine16956, parser4816 and
  native37652 were witnessed before Engine kill; parser and native both exited, parser in503ms.
- Full build324 passes in32s with test Error Prone enabled. Canonical index, skill embedding,
  link, module-graph and runtime-configuration checks325 pass. Both skill copies were reviewed;
  no embedded canonical source content required a change.

Logs: `tmp/c1-sandbox-protocol-320.txt`, `tmp/c1-sandbox-protocol-321.txt`,
`tmp/c1-sandbox-adverse-322.txt`, `tmp/c1-sandbox-engine-323.txt`,
`tmp/c1-sandbox-build-324.txt`, `tmp/c1-sandbox-docs-325.txt`.
Immutable reports: `tmp/c1-sandbox-results-320/`, `tmp/c1-sandbox-results-321/`,
`tmp/c1-sandbox-results-322/`, `tmp/c1-sandbox-results-323/`.

Root reviewed frame ordering, factory/probe behavior, correlated success/failure responses,
confirmed process exit and retry/capacity ownership. The late worker Error policy remains in
[client failure evidence](client-failures.md). Next is health-monitor capacity recovery, then
CORS and the remaining ordered debts. Final C1 integrated/live/hosted proof and review remain
required; this is a per-item checkpoint, not a stage completion claim.
