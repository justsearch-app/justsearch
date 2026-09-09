# C1 cancellation and queued-root proof sweep

The durable foreground-load regression now holds two distinct admitted works. Repeated detach
and close of the first leave exactly one foreground hold until the second finishes. A counter
clamped at zero can no longer conceal a duplicate release that consumes another work's hold.

The shutdown wiring test supplies the same real EngineAdmissionController as both the operation
lease authority and admission authority, as production composition does. Running the actual
ordered sequence freezes admission, cancels the interactive work with restart, leaves durable
work uncancelled, and makes a later admission fail specifically FROZEN. Stage B's obsolete
shutdown-cancellation limitation is removed from its current remaining-work list.

The initial watched-root test now holds the actual root-walk worker occupied while the request
enqueues its scan, then exits the entering request and tracing scopes before releasing the
worker. The queue contains one item with three remaining physical slots. The actual ingest
service sees the original work identity, OTel trace and MDC request ID, both in CallContext and
the running thread. This proves capture across a real queued root, beyond direct scan dispatch.
No production behavior changes in this item.

## Verification

Windows11, Temurin25.0.2; base7ede2befd plus this test/document overlay.

- Initial372 passes18 selected cases. Expanded restored374 passes24: foreground load6,
  queued roots4, context/port propagation6, shutdown wiring8; zero failures/errors/skips.
- Four independent adverse373 runs fail for their intended reasons. Double-decrement changes
  the surviving work's load from1 to0. Removing the real shutdown freeze yields ENGINE_LIMIT
  instead of FROZEN. Removing queued trace capture or request-ID capture yields null for the
  respective value. Root reread the corresponding XML failure messages; no compile failure is
  counted as a behavioral refutation. Each production file is restored byte-for-byte in finally.
- Restored374 includes the unchanged direct-port correlation tests. Full build375 passes in21s
  with test Error Prone enabled. This focused proof does not replace the remaining full stress,
  final live and hosted C1 acceptance.

Evidence: `tmp/c1-cancellation-sweep-372.txt`, `tmp/c1-cancellation-results-372/`,
`tmp/c1-cancellation-adverse-373-double-release.txt`,
`tmp/c1-cancellation-adverse-373-freeze.txt`, `tmp/c1-cancellation-adverse-373-trace.txt`,
`tmp/c1-cancellation-adverse-373-request.txt`,
`tmp/c1-cancellation-adverse-results-373-double-release/`,
`tmp/c1-cancellation-adverse-results-373-freeze/`,
`tmp/c1-cancellation-adverse-results-373-trace/`,
`tmp/c1-cancellation-adverse-results-373-request/`,
`tmp/c1-cancellation-restored-374.txt`, `tmp/c1-cancellation-results-374/`,
`tmp/c1-cancellation-build-375.txt`.
