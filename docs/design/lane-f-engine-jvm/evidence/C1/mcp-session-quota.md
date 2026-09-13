# MCP session quota correction (2026-09-12)

September13 R10 boundary: the later verification obligation below is now satisfied.
Full863 executes RequestEngineContextTest6 and EngineAdmissionTransportTest9 with no
failures/errors/skips. Successful full869 atc56e1a838 reuses those unchanged UI inputs;
the unrelated Engine lifecycle fixture failure from863 is corrected. Hosted
CI34730328727 atd9c80a646 executes both suites on Linux, including the real HTTP
header-rotation refusal and unknown-session bucketing, with no skips or retry entries.
See [C2 hosted evidence](../C2/hosted-ci.md) and
[the full-run record](../C2/latest-full-run-summary.json) for exact revision, execution
status and accessible XML. This supersedes the pending-boundary statement below;
the original focused proof remains historical.

This implements design section0 item955-1 during C2. The base is871a021bb;
the correction commit contains this record. The older C1 hosted/live proof is
not evidence for this newly changed identity path.

RequestEngineContext ignores X-JustSearch-Client-Id for MCP. ApiSecurityFilters
resolves identity through McpProtocolHandler's existing server-issued session map
before Engine admission; LocalApiServer wires that protocol owner. Known sessions
use their server identity. Missing or unknown sessions share mcp-anonymous; their
raw session string remains correlation metadata only. Browser hints are unchanged.
No second session registry or client-supplied identity authority is introduced.

The HTTP regression initializes a real MCP session, holds two calls at its
per-context ceiling, then rotates client headers. Before the fix run599 received
HTTP200 instead of429; after the fix it returns ADMISSION_CONTEXT_LIMIT with the
existing retry-safe JSON-RPC refusal. Existing aggregate-limit tests also pass.
Unit cases cover unknown/missing sessions, forged client/kind/source hints and
browser identity. The old no-clientInfo test retains its requestedBy=null contract;
its unregistered s1 header now correlates the request without becoming its identity.

Verification on Windows/JDK25:

```powershell
./gradlew.bat :modules:ui:test --tests '*RequestEngineContextTest' --tests '*EngineAdmissionTransportTest' --tests '*McpProtocolHandlerTest' --tests '*McpOriginValidationTest' :modules:ui:pmdMain :modules:ui:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
```

Run601 passes66 tests in5 suites, no failures/errors/skips; PMD main/test pass
(main reused unchanged600 input). Run600's sole failure was the superseded
unregistered-session attribution assertion, corrected without changing its
missing-requester intent. Retained at this worktree:

- tmp/c1-mcp-quota-negative-599.txt and .xml: executable counterfactual.
- tmp/c1-mcp-quota-verification-600.txt and tmp/c1-mcp-quota-600-xml: prior failure.
- tmp/c1-mcp-quota-verification-601.txt, tmp/c1-mcp-quota-601-xml and
  tmp/c1-mcp-quota-601-counts.json: positive evidence.

The correction has focused local proof; the next coherent C2 integrated and hosted
boundary must include it. The aggregate ceiling bounds multiple established
sessions; this is quota attribution, not authentication of a native process.
Retention follows the lane handoff: acceptance plus30 days, export before worktree
release, worktree review2026-10-12.

Independent read-only review verified production resolver-before-admission ordering,
server-issued membership, unknown-session bucketing and preserved refusal/browser
paths; no concrete defect found. The reviewer did not run tests; root read601 XML.
The fixture-only install(app) overload intentionally has no session resolver.
