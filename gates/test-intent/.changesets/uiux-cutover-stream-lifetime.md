---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-cutover-stream-lifetime-builder-18
---

# Test-intent entries

New scoped checks implement agent-adopted SL1–19 and private release-owner contract2 (delegated-choice, never owner-agreed). Round 2 generated all five official-format evidence artifacts at candidate ee92a253a7 against main 784f94fd04: 37 selected candidate cases pass without skips (15 HTTP repair, 3 HTTP preservation, 9 current-owner controls, 8 proxy repair, 2 proxy preservation). All 23 repair cases fail before with assertions, and all 5 preservation cases pass before. Current-owner helper/transport controls remain candidate-only; they are not fail-before or before-preservation proof. The adapted runner lives outside scripts/: its retained patch at gates/test-intent/evidence/http-lifetime/runner-machine-rules.patch follows main's accepted machine-rule adapter, preserves official result selection/classification/digests, invokes Gradle through js-gradle-slot without init scripts, strips inherited display/JVM/JustSearch variables, disables CUDA and uses a retained TMPDIR shared before clone with independent plain npm ci. Evidence tree hashing uses private Git objects with the source objects as a read-only alternate. No production code or scripts/governance files changed. The focused candidate NVIDIA availability check passes (1 test, no skip); the full main UI run also reproduces its prior failure, so it is classified pre-existing or load-dependent under the round-2 decision. StatusIsolation's frontend refresh fails with missing esbuild on both candidate and main, whose test/dependency/frontend paths are unchanged; it is pre-existing. Exact commands, counts, failures and raw classification logs are retained under gates/test-intent/evidence/http-lifetime/. Round-1 module suites, compile/integration compile, strict Error Prone/PMD and frontend typecheck/lint/unit results are carried forward, not rerun in round 2. Real proxy checks use ordinary Node/Vitest HTTP. No commit, author acceptance, independent verification or GitHub issue action is performed here; independent acceptance remains outstanding.

One fixed per-concrete-server daemon platform release worker is an explicitly adopted exception, independent of retiring executor/Jetty queues; metadata monitor and exact request records only, no generic queue/framework. LIVE creating-Context identity is cleared at capture, all handle calls/projection/callbacks outside the monitor, actual ACK/FAILED retained, no false drain or producer cancellation. First observable negative cleanup acknowledgement refuses NEW early per-server tracking registrations, including unmatched GET404/static paths when engine admission is configured; true GET health/OPTIONS remain exempt and existing LIVE cleanup/producers continue. This broader sticky fail-closed scope is root D1, an agent-adopted design choice under the existing delegation, not an owner quotation. Successful cleanup after handler500 does not poison the owner. Internally swallowed/logged projection IOException is not claimed observable. Adjacent in-flight metric/span/Scope gap stays deferred underSL18; no tracing after queue is moved/replayed. All module/runtime/producer/reference/security and final independent Claude verification obligations remain. No GitHub issue action.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/apiProxyLifetime.test.js"
      ],
      "class": "New",
      "behaviour": "The existing actual Vite dev/preview proxy cancels only its departed downstream exchange before headers, during established SSE and during incomplete upload. Completed GET/upload body is not response termination. Upstream failure before headers keeps502; late failure ends a started response. A later request uses the same live proxy. Clean main 784f94fd04 before has eight lifetime assertion failures and two finite-policy preservation passes; candidate has ten passes, including the same two preservation cases. No browser/Engine/producer or every historical429 claim.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work you mentioned.",
          "choice": "Agent-adopted SL1\u201319 request/proxy lifetime contract and private per-concrete-server release owner: capture exact front references at real after/terminal boundaries, true ACK/FAILED retention, bounded existing transport stop and sticky refusal of new relevant acquisitions after observable cleanup failure. Preserve actual producers and existing policy; no tracing after-queue replay.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-stream-proxy-repair.json",
      "newChecks": [
        "modules/ui-web/src/apiProxyLifetime.test.js#dev: actual API proxy owns only the departed exchange complete GET departure before response headers cancels its exact upstream; later request works",
        "modules/ui-web/src/apiProxyLifetime.test.js#dev: actual API proxy owns only the departed exchange incremental SSE remains alive after completed GET; one observer closure leaves another live",
        "modules/ui-web/src/apiProxyLifetime.test.js#dev: actual API proxy owns only the departed exchange incomplete upload departure closes upstream without completing its body",
        "modules/ui-web/src/apiProxyLifetime.test.js#dev: actual API proxy owns only the departed exchange upstream failure before headers is502; late failure terminates started response",
        "modules/ui-web/src/apiProxyLifetime.test.js#preview: actual API proxy owns only the departed exchange complete GET departure before response headers cancels its exact upstream; later request works",
        "modules/ui-web/src/apiProxyLifetime.test.js#preview: actual API proxy owns only the departed exchange incremental SSE remains alive after completed GET; one observer closure leaves another live",
        "modules/ui-web/src/apiProxyLifetime.test.js#preview: actual API proxy owns only the departed exchange incomplete upload departure closes upstream without completing its body",
        "modules/ui-web/src/apiProxyLifetime.test.js#preview: actual API proxy owns only the departed exchange upstream failure before headers is502; late failure terminates started response"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/apiProxyLifetime.test.js"
      ],
      "class": "New",
      "behaviour": "Finite JSON/POST bytes, local rewritten Host, denied non-local DNS/IP Host, well-known forwarding, retained end-to-end headers and stripped hop response headers preserve existing policy in actual Vite dev and preview. Both preservation cases pass on main 784f94fd04 and candidate; official candidate and before preservation evidence is generated. No new CORS, credential or quota policy.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work you mentioned.",
          "choice": "Agent-adopted SL1\u201319 request/proxy lifetime contract and private per-concrete-server release owner: capture exact front references at real after/terminal boundaries, true ACK/FAILED retention, bounded existing transport stop and sticky refusal of new relevant acquisitions after observable cleanup failure. Preserve actual producers and existing policy; no tracing after-queue replay.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-stream-proxy-preservation.json",
      "newChecks": [
        "modules/ui-web/src/apiProxyLifetime.test.js#dev: actual API proxy owns only the departed exchange finite JSON/POST, Host rewrite, well-known forwarding and Host refusal stay exact",
        "modules/ui-web/src/apiProxyLifetime.test.js#preview: actual API proxy owns only the departed exchange finite JSON/POST, Host rewrite, well-known forwarding and Host refusal stay exact"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusTerminalAdmissionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/SseTerminalAdmissionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAfterReuseTest.java"
      ],
      "class": "New",
      "behaviour": "Real HTTP/filter repairs cover exceptional user futures before/after committed headers, cancellation, configured async timeout, fresh-status serialization, genuinely exceptional first SSE writes after TCP departure, and native MCP late admission. Actual terminal cleanup releases only the request-owned front work, status reservation and mutation lease; unrelated references remain live and a later request works without restart or limit changes. Observable release failure attempts every release, retains its negative acknowledgement, and refuses new relevant tracking/admission without acquiring resources or growing failed records; already-live cleanup/producers and exempt health/OPTIONS continue. A finite close-then-throw case reaches this refusal independently of an earlier leak, including ordinary unknown GET404 becoming INVALID_STATE500 after the negative acknowledgement. Actual static middleware probing remains unverified. SL13 stale old-after lookup is a Deliver repair, already broken before this package; ordinary concurrent/keep-alive reuse remains preservation. The test requires actual timeout and same-socket Jetty request recycling before delayed old-after lookup, then asserts the newer front remains usable; it pauses before unrelated old Javalin response-writing machinery. File projection keeps exact opId removal, unrelated leases, updatedAt and the current servlet-status outcome rule: committed wire200 with internal500 projects FAILURE, never producer success. All 15 repair cases passed without skips on candidate. All 15 fail with the intended assertions on main 784f94fd04; the stale-after check explicitly wraps the observed WORK_FINISHED retain failure with assertDoesNotThrow. Compile, missing-fixture and unexpected-exception failures are not accepted as assertion fail-before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work you mentioned.",
          "choice": "Agent-adopted SL1\u201319 request/proxy lifetime contract and private per-concrete-server release owner: capture exact front references at real after/terminal boundaries, true ACK/FAILED retention, bounded existing transport stop and sticky refusal of new relevant acquisitions after observable cleanup failure. Preserve actual producers and existing policy; no tracing after-queue replay.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-stream-http-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#exceptionalUserFutureReleasesOnlyItsHttpWorkAndMutationLease",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#failedOrCancelledStatusUserFutureReleasesOnlyItsReservation",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#exceptionalLeaseProjectionKeepsExactOpRemovalUpdatedAtAndHttpStatusOutcome",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#configuredTestServerTimeoutReleasesRequestResourcesAtActualCompletion",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#nativeMcpAdmissionInstalledInsideHandlerIsReleasedOnExceptionalFuture",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#negativeCleanupAcknowledgementRefusesNewWorkButExistingCleanupAndProducerRemainOwned",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#throwingReleaseDoesNotPreventOtherReleasesAndRefusesNewAcquisitions",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusTerminalAdmissionTest.java#exceptionalFreshStatusSerializationReleasesItsReservationWithoutRetiringTheSharedLane",
        "modules/ui/src/test/java/io/justsearch/ui/api/SseTerminalAdmissionTest.java#departedClientExceptionalFirstPrefixReleasesOnlyItsRequest",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAfterReuseTest.java#oldAfterLookupCannotCloseNewerLiveFrontOnTheSameRecycledHttpRequest"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java"
      ],
      "class": "New",
      "behaviour": "Normal finite/async success and actual successful file-backed mutation projection retain their existing response, exact lease removal/updatedAt/SUCCESS rule and cleanup; a handler failure with successful cleanup leaves later requests recoverable. A separate real synchronous committed-prefix/gated POST uses only existing baseline APIs: actual wire200, observed AFTER/current servlet500, exact HTTP opId removal, unrelated sole lease, changed updatedAt and projected FAILURE preserve on before main 784f94fd04 and candidate. It does not borrow the repaired exceptional-future path or producer-result meaning. All three preservation checks passed without skips on main 784f94fd04 and candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work you mentioned.",
          "choice": "Agent-adopted SL1\u201319 request/proxy lifetime contract and private per-concrete-server release owner: capture exact front references at real after/terminal boundaries, true ACK/FAILED retention, bounded existing transport stop and sticky refusal of new relevant acquisitions after observable cleanup failure. Preserve actual producers and existing policy; no tracing after-queue replay.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-stream-http-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#successfulAsyncAndOrdinaryFiniteResponsesReleaseTheirOwnReferences",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#successfulLeaseProjectionKeepsExactOpRemovalUpdatedAtAndHttpStatusOutcome",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestAdmissionCompletionTest.java#synchronousCommittedWire200PreservesInternal500LeaseFailure"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseTransportTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java"
      ],
      "class": "New",
      "behaviour": "Current-candidate mechanism and real transport controls for the new private owner: blocked projection stays RELEASING while a connector listener appended after owner startup observes the held exchange itself after actual projection entry and while its release gate remains closed, and a second actual terminal callback/normal after progresses; actual DefaultEngineExecutorRegistry closure precedes exceptional cleanup; real live body/producer and a delayed stop remain owned across the unchanged bounded stop/retry. The recoverable fixture explicitly consumes its injected interrupt after the stop gate with a true interruption premise; a separate restore-interrupt negative asserts actual JavalinException/InterruptedException, unclean nonzero shutdown and FAILED/notSTOPPED/no fabricated drain, retaining the incomplete graph until the isolated worker ends. An observer is confirmed waiting on the original still-running attempt before that negative gate opens. Real Runnable exit owns acknowledgement; failed acknowledgements remain and bound retained records, exempt/refused traffic adds none. Deterministic metadata races verify Context identity before handle reads, one fanout and exact old status outcome. Later SERVER_STARTING failure and real failed bind/fresh ephemeral server drain separate workers. The four transport controls execute before but fail; one cannot establish a wait on the new owner stack, so these are current-mechanism controls, not additional repair or before-preservation claims. All five helper-type controls are current-only: overlaying their test file on an independent clean before clone fails compilation because RequestReleaseOwner is absent (19 errors); this is explicitly unverified before, never fail-before proof and never repaired APIs imported into the old product; separate public HTTP tests provide that proof. Actual Thread.start failure injection has no allowed provider and remains explicitly unverified. All nine controls passed without skips in the candidate full UI task; no body exit inferred from Future cancellation, no shutdown/queue/executor option or allowlist relaxation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work you mentioned.",
          "choice": "Agent-adopted SL1\u201319 request/proxy lifetime contract and private per-concrete-server release owner: capture exact front references at real after/terminal boundaries, true ACK/FAILED retention, bounded existing transport stop and sticky refusal of new relevant acquisitions after observable cleanup failure. Preserve actual producers and existing policy; no tracing after-queue replay.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-stream-owner-controls.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseTransportTest.java#blockedProjectionLeavesTerminalCallbackAndAnotherAfterFreeToProgress",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseTransportTest.java#registryClosureBeforeExceptionalTerminalDoesNotRetireTheReleaseOwner",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseTransportTest.java#liveBodyAndStillRunningStopAttemptRemainOwnedAcrossBoundedRetry",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseTransportTest.java#genuineInterruptedTransportStopRemainsUncleanWithoutFabricatedDrain",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java#blockedReleaseRetainsItsRecordWhileTerminalCaptureAndAnotherAfterProgress",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java#oldAfterCannotReadNewRegistrationOnTheRecycledRequest",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java#firstNegativeAcknowledgementBoundsFailedRecordsWithoutStoppingExistingCleanup",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java#laterStartupListenerFailureDrainsWorkerWithoutAnAcceptingTransport",
        "modules/ui/src/test/java/io/justsearch/ui/api/RequestReleaseOwnerTest.java#failedBindDrainsOldWorkerAndFreshEphemeralServerOwnsItsOwnWorker"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "8c330fb4-0ab8-439d-8de2-6a42f0669e2b",
  "verdict": "accept",
  "digest": "sha256:fe06aee1a1a655d5449561cc47937429335a037b57b73caf207cadb9ab207cd9",
  "note": "Two independent verifiers on fee60d6075 (main a23a9186ea merged), separate sessions with no builder context. Codex verifier: SL1-SL8, SL10-SL15, SL17-SL19 pass on the real interface (installed Engines, real Vite dev and preview proxies, headless Chromium); 19 meaningful repair failures and 3 preservation passes on main; 7,277 Java and 7,897 web tests pass. Claude security verifier: SL9 145 cases identical on both, refused credentials take no admission, SL13 isolation under 900 hostile resets, SL19 fails closed, proxy filtering unchanged, 16 of 16 abrupt SSE departures release within 2 s versus 0 of 16 on main; 119 existing security tests pass on both. SL18 adjacent tracing gap stays deferred; Windows and native Tauri unverified."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-4we7c_p2",
  "verdict": "accept",
  "digest": "sha256:fe06aee1a1a655d5449561cc47937429335a037b57b73caf207cadb9ab207cd9",
  "note": "All five entries match New, stay within the supplied delegated contracts, and have consistent evidence and digests; removing Registration.failure changes no runtime behavior."
}
```
