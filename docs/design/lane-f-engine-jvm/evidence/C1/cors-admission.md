# C1 CORS admission correction

ApiSecurityFilters.setupEngineAdmission exempts OPTIONS before calling the Engine admission
owner. Host validation, MCP Origin validation, CORS origin resolution and mutation-token policy
keep their existing order and predicates. Preflight is transport negotiation and reserves no
Engine work, including while the aggregate is full or upgrade admission is frozen.

For an already-allowed origin, Access-Control-Expose-Headers now includes Retry-After alongside
Deprecation, Sunset and Link. The retry value still comes from the existing admission authority;
foreign origins receive no CORS reading grant or exposed-header list.

## Verification

Windows11, Temurin25.0.2. Baseb9e1abe1c plus this item.

- Run339 failed test compilation because the fixture called an absent inFlight method. It was
  corrected to the existing activeWorkCount projection; no production API was added.
- Run340 passes39 cases across actual HTTP admission, CORS, Host validation, MCP Origin validation
  and security filters, plus affected main/test PMD. The new preflight test fills both aggregate
  slots, sends allowed/foreign preflights to search, MCP and protected run routes, and verifies
  the real admission spy receives no admit call. Existing works stay occupied. The same allowed
  preflight succeeds while the real upgrade freeze is held. A separate actual429 test proves an
  allowed desktop origin can read the registered Retry-After and a foreign origin receives no
  grant or exposed-header list. The six reported test-compiler warnings are in unchanged files.
- Adverse341 restores the old production filter only. Both new tests fail: a full-capacity
  preflight receives429 instead of200; the allowed-origin429 response lacks exposed Retry-After.
  Source restoration is byte-for-byte in finally. Restored342 reuses the matching340 test output
  from Gradle's cache and passes all39 cases and affected PMD.
- Full build343 passes with test Error Prone enabled.
- Canonical index, skill embedding, links, module graph and runtime configuration checks344 pass.
  Both skill copies were inspected; neither embeds the changed admission/security paragraphs.

Logs: `tmp/c1-cors-339.txt`, `tmp/c1-cors-340.txt`, `tmp/c1-cors-adverse-341.txt`,
`tmp/c1-cors-restored-342.txt`, `tmp/c1-cors-build-343.txt`, `tmp/c1-cors-docs-344.txt`.
Immutable reports: `tmp/c1-cors-results-339/`, `tmp/c1-cors-results-340/`,
`tmp/c1-cors-results-341/`, `tmp/c1-cors-results-342/`.

Root independently reread filter order, the unchanged trust predicates and both negative-control
failures. Next are the ordered governance and test debts. Full C1 integrated/live/hosted proof
and final independent review remain required.
