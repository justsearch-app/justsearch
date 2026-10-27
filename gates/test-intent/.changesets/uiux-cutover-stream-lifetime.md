---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-cutover-stream-lifetime-builder-18
---

# Test-intent entries

New scoped checks implement agent-adopted SL1–19 and private release-owner contract2 (delegated-choice, never owner-agreed). Candidate Java compileTestJava passed on 2026-10-27. Portable Java tests compiled and ran on clean before e11a279f0c; all 27 new Java cases passed without skips in the initial candidate full UI task. Static compilation completed, but PMD found five fixture issues: one unused shared reservation, two unused producer references and two redundant qualifiers. Meaningful resource-owner assertions and imported names correct those issues without suppressions; the final affected-fixture rerun passed all 19 candidate cases; before produced 16 expected failures and three preservation passes with no skips. The held-exchange observer additionally waits for actual projection entry and records the still-closed release gate inside that callback, removing an entry-order race; complete compile/integration-compile/PMD now passes with strict Error Prone tests enabled, and the before witness still fails at missing release entry. Its final focused candidate rerun passed without skips, including the inside-callback projection/gate witness. Candidate frontend typecheck/lint and all 7897 unit cases passed. Real proxy before/current uses ordinary Node/Vitest HTTP, not a browser or installed Engine. Evidence is generated only by the official runner; no commit, author acceptance or independent verification is performed here. The full UI task had two existing failures and one optional corpus skip. The frontend refresh check fails identically before because plain npm ci lacks esbuild; its dependency/test source paths are unchanged. The NVIDIA availability check passes before, so its candidate full-suite failure remains unresolved rather than classified as environmental. Both proxy evidence files record candidate passes; the repair file has no official before section yet. Three Java evidence files remain blocked by the runner conflicts with machine rules. Security/quota policy and governance runners are unchanged.

One fixed per-concrete-server daemon platform release worker is an explicitly adopted exception, independent of retiring executor/Jetty queues; metadata monitor and exact request records only, no generic queue/framework. LIVE creating-Context identity is cleared at capture, all handle calls/projection/callbacks outside the monitor, actual ACK/FAILED retained, no false drain or producer cancellation. First observable negative cleanup acknowledgement refuses NEW early per-server tracking registrations, including unmatched GET404/static paths when engine admission is configured; true GET health/OPTIONS remain exempt and existing LIVE cleanup/producers continue. This broader sticky fail-closed scope is root D1, an agent-adopted design choice under the existing delegation, not an owner quotation. Successful cleanup after handler500 does not poison the owner. Internally swallowed/logged projection IOException is not claimed observable. Adjacent in-flight metric/span/Scope gap stays deferred underSL18; no tracing after queue is moved/replayed. All module/runtime/producer/reference/security and final independent Claude verification obligations remain. No GitHub issue action.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/apiProxyLifetime.test.js"
      ],
      "class": "New",
      "behaviour": "The existing actual Vite dev/preview proxy cancels only its departed downstream exchange before headers, during established SSE and during incomplete upload. Completed GET/upload body is not response termination. Upstream failure before headers keeps502; late failure ends a started response. A later request uses the same live proxy. Clean merge-base e11a279f0c before has eight lifetime assertion failures and two finite-policy preservation passes; candidate has ten passes, including the same two preservation cases. No browser/Engine/producer or every historical429 claim.",
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
      "behaviour": "Finite JSON/POST bytes, local rewritten Host, denied non-local DNS/IP Host, well-known forwarding, retained end-to-end headers and stripped hop response headers preserve existing policy in actual Vite dev and preview. Both preservation cases pass on clean merge-base e11a279f0c and candidate; official candidate preservation evidence is generated. No new CORS, credential or quota policy.",
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
      "behaviour": "Real HTTP/filter repairs cover exceptional user futures before/after committed headers, cancellation, configured async timeout, fresh-status serialization, genuinely exceptional first SSE writes after TCP departure, and native MCP late admission. Actual terminal cleanup releases only the request-owned front work, status reservation and mutation lease; unrelated references remain live and a later request works without restart or limit changes. Observable release failure attempts every release, retains its negative acknowledgement, and refuses new relevant tracking/admission without acquiring resources or growing failed records; already-live cleanup/producers and exempt health/OPTIONS continue. A finite close-then-throw case reaches this refusal independently of an earlier leak, including ordinary unknown GET404 becoming INVALID_STATE500 after the negative acknowledgement. Actual static middleware probing remains unverified. SL13 stale old-after lookup is a Deliver repair, already broken before this package; ordinary concurrent/keep-alive reuse remains preservation. The test requires actual timeout and same-socket Jetty request recycling before delayed old-after lookup, then asserts the newer front remains usable; it pauses before unrelated old Javalin response-writing machinery. File projection keeps exact opId removal, unrelated leases, updatedAt and the current servlet-status outcome rule: committed wire200 with internal500 projects FAILURE, never producer success. All 15 repair cases passed without skips on candidate. All 15 fail with the intended assertions on clean before e11a279f0c; the stale-after check explicitly wraps the observed WORK_FINISHED retain failure with assertDoesNotThrow. Compile, missing-fixture and unexpected-exception failures are not accepted as assertion fail-before.",
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
      "behaviour": "Normal finite/async success and actual successful file-backed mutation projection retain their existing response, exact lease removal/updatedAt/SUCCESS rule and cleanup; a handler failure with successful cleanup leaves later requests recoverable. A separate real synchronous committed-prefix/gated POST uses only existing baseline APIs: actual wire200, observed AFTER/current servlet500, exact HTTP opId removal, unrelated sole lease, changed updatedAt and projected FAILURE preserve on before e11a279f0c and candidate. It does not borrow the repaired exceptional-future path or producer-result meaning. All three preservation checks passed without skips on clean merge-base e11a279f0c and candidate.",
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
