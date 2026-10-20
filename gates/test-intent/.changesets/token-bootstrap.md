---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15693-314a-7240-ad93-9b6f8c3969e5
---

# Test-intent entries

Author classification of all 25 flagged items against merge-base
`c00a0cb24b4c6000e090d5d09054c1e7e1d24e2f` with `origin/main`.
The user adopted T1–T19 on 2026-10-20: “i agree, proceed accordingly”.
Each cited scenario below quotes its agreed text, independently of implementation output.
This is an author record, with no acceptance or audit record.

Execution procedure and assertion inventory: [evidence notes](../evidence/token-bootstrap-notes.md).
The security before-runs use the relevant fix's parent: `8f49bdc3a` before
`9a893ca7a`; launcher expectations use `c00a0cb24` before `104f263de`.
Using the current parent would test already-repaired code. Preservation runs use
unaltered original tests at the merge-base, plus adapted candidate tests and an
explicit assertion/diff audit. This does not claim Windows or desktop end-to-end execution.
PR #825 is also audit-selected under the documented every-fifth-PR rule; that review,
like acceptance, must come from another session. No author approval is supplied.

```json
{
  "entries": [
    {
      "items": [
        "gates/test-intent/test-support-paths.v1.json"
      ],
      "class": "Structural rule kept",
      "rule": "All frontend src files imported only by tests must remain in the test-intent scope; the static import scan must resolve every listed path.",
      "whySurvives": "Only src/test/fetchSse.ts is added. It is the shared replacement fixture required to exercise the fetch stream under T9; no existing path or selection is removed. The register is stricter, keeping this added helper governed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T9",
          "quote": "The desktop app works end to end on Linux and Windows (search, preview, chat history and live-run reattach, activity, logs, settings, plugins, capability handshake, all streams), with no token-denied requests.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a (parent of token-boundary repair 9a893ca7a), run the candidate tests against the real LocalApiServer, enumerating its registered routes. Non-public GET/HEAD and the bootstrap are admitted rather than returning 401, and the credential endpoint exists. Candidate: every non-exempt registered method/path is refused without credentials; OPTIONS and the exact six public GET/HEAD reads remain available, and no walked response contains the test credential. The authenticated removed endpoint is 404. This is a security defect pinned by T1/T2, not an output-derived new contract."
      },
      "reproduction": "On 8f49bdc3a (parent of token-boundary repair 9a893ca7a), run the candidate tests against the real LocalApiServer, enumerating its registered routes. Non-public GET/HEAD and the bootstrap are admitted rather than returning 401, and the credential endpoint exists. Candidate: every non-exempt registered method/path is refused without credentials; OPTIONS and the exact six public GET/HEAD reads remain available, and no walked response contains the test credential. The authenticated removed endpoint is 404. This is a security defect pinned by T1/T2, not an output-derived new contract.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T3",
          "quote": "The six public reads stay open and never carry the token, in REST, SSE, well-known, MCP and historical-instance responses, including records written by an older version; runtime-client SDK tests pass.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java"
      ]
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ProductionRouteTokenWalkTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a (parent of 9a893ca7a), install and start the production Engine through IsolatedBackendFixture and enumerate its real /api/meta/routes. Assert 401 for every non-public method/path, including streams, and check the exact six public GET reads without a token and bootstrap 401/404. The before-run admits protected reads; the candidate exercises the installed process. Public-set completeness and the registered ResourceApiModule stream assertions prevent an empty enumeration or a minimal stand-in router from passing."
      },
      "reproduction": "On 8f49bdc3a (parent of 9a893ca7a), install and start the production Engine through IsolatedBackendFixture and enumerate its real /api/meta/routes. Assert 401 for every non-public method/path, including streams, and check the exact six public GET reads without a token and bootstrap 401/404. The before-run admits protected reads; the candidate exercises the installed process. Public-set completeness and the registered ResourceApiModule stream assertions prevent an empty enumeration or a minimal stand-in router from passing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T3",
          "quote": "The six public reads stay open and never carry the token, in REST, SSE, well-known, MCP and historical-instance responses, including records written by an older version; runtime-client SDK tests pass.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-installed-engine.json",
      "newChecks": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ProductionRouteTokenWalkTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T5",
          "quote": "`runtime/manifest.json` and its temporary file are owner-only after every publish, including an upgrade over a group-readable manifest and a leftover readable temporary file (POSIX mode 0600; Windows owner and SYSTEM only).",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a, create a 0664 runtime manifest and 0664 leftover temporary file, then publish three times; the POSIX permissions assertion fails because the baseline keeps a readable manifest. Publish a token-bearing head and worker update and read both per-instance manifest and NDJSON log: baseline contains the credential; the candidate contains neither the secret nor sessionToken, still includes instance identity, retains the credential only in current runtime manifest and removes the temporary file. Linux evidence covers the POSIX assumption; it is not Windows ACL execution evidence."
      },
      "reproduction": "On 8f49bdc3a, create a 0664 runtime manifest and 0664 leftover temporary file, then publish three times; the POSIX permissions assertion fails because the baseline keeps a readable manifest. Publish a token-bearing head and worker update and read both per-instance manifest and NDJSON log: baseline contains the credential; the candidate contains neither the secret nor sessionToken, still includes instance identity, retains the credential only in current runtime manifest and removes the temporary file. Linux evidence covers the POSIX assumption; it is not Windows ACL execution evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T5",
          "quote": "`runtime/manifest.json` and its temporary file are owner-only after every publish, including an upgrade over a group-readable manifest and a leftover readable temporary file (POSIX mode 0600; Windows owner and SYSTEM only).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T6",
          "quote": "Newly written instance history (`instances/<id>/manifest.json`, `manifest.log.ndjson`) holds no token. Older history is left as it is (item 4 redacts it).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T18",
          "quote": "The headless Engine no longer prints the token to stdout or stderr.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a invoke the existing HeadlessApp port-signal method, capture stdout and check that JUSTSEARCH_PORT remains while neither the credential nor JUSTSEARCH_SESSION_TOKEN is printed. The test supports the previous method signature by reflection without changing the production method. Baseline fails the secret-absence assertion; candidate passes."
      },
      "reproduction": "On 8f49bdc3a invoke the existing HeadlessApp port-signal method, capture stdout and check that JUSTSEARCH_PORT remains while neither the credential nor JUSTSEARCH_SESSION_TOKEN is printed. The test supports the previous method signature by reflection without changing the production method. Baseline fails the secret-absence assertion; candidate passes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T18",
          "quote": "The headless Engine no longer prints the token to stdout or stderr.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessLauncherProductionModeTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On c00a0cb24 (parent of launcher fix 104f263de), inspect the single Java invocation in each shipped .cmd/.ps1 launcher. Both must start HeadlessApp with the quoted production property and the selected mode property before the main class. The missing-production assertion fails before 104f263de; on this branch both cases pass. This is a source check of Windows launcher invocations, not a claim that either launcher executed on Windows."
      },
      "reproduction": "On c00a0cb24 (parent of launcher fix 104f263de), inspect the single Java invocation in each shipped .cmd/.ps1 launcher. Both must start HeadlessApp with the quoted production property and the selected mode property before the main class. The missing-production assertion fails before 104f263de; on this branch both cases pass. This is a source check of Windows launcher invocations, not a claim that either launcher executed on Windows.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T16",
          "quote": "Production without a token still refuses to start, and no port fallback installs an unsecured server.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-5ec1a1 request",
          "quote": "Base: this checkout's HEAD (104f263de, the headless launchers already start in production mode; leave that change alone)."
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-launchers.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessLauncherProductionModeTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a, install the production security filter over the minimal test router. New private GET/HEAD/PATCH/TRACE requests without credentials must return 401 and removed-bootstrap access in production must return 401 without a credential in the body; these assertions fail before. Existing mutation missing/wrong/valid-token, public-status, OPTIONS and missing-token startup assertions keep their outcomes. The dev private GET=200 is an added preservation control alongside existing dev POST=200. Fixture-only changes remove the fabricated token handler, add a private read, and propagate HttpResponseException status as production does. Dev bootstrap 404 is covered by the obsolete-behaviour warrant below; it is not claimed to fail on the transplanted old fixture."
      },
      "reproduction": "On 8f49bdc3a, install the production security filter over the minimal test router. New private GET/HEAD/PATCH/TRACE requests without credentials must return 401 and removed-bootstrap access in production must return 401 without a credential in the body; these assertions fail before. Existing mutation missing/wrong/valid-token, public-status, OPTIONS and missing-token startup assertions keep their outcomes. The dev private GET=200 is an added preservation control alongside existing dev POST=200. Fixture-only changes remove the fabricated token handler, add a private read, and propagate HttpResponseException status as production does. Dev bootstrap 404 is covered by the obsolete-behaviour warrant below; it is not claimed to fail on the transplanted old fixture.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T3",
          "quote": "The six public reads stay open and never carry the token, in REST, SSE, well-known, MCP and historical-instance responses, including records written by an older version; runtime-client SDK tests pass.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T4",
          "quote": "Dev mode stays token-free for GET and POST.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: ADR-0046 at the merge-base explicitly says GET /api/mcp/token is token-exempt and correct and guards its Origin. The later owner-adopted T1 explicitly replaces that decision by removing the route; T17 preserves Origin obligations on surviving /mcp. The branch amends ADR-0046 accordingly; that amendment is not used as an independent source. Searched docs/decisions at both merge-base and candidate for token bootstrap, /api/mcp/token, session token and MCP Origin: no other conflicting record found. The recorded conflict is resolved by the owner-adopted scenario, not an author decision.",
      "retiredExpectations": [
        "LocalApiUiTokenPolicyTest production bootstrap: old 200 and body contains TEST_TOKEN; new 401 and body excludes TEST_TOKEN (Defect pin above). Dev bootstrap: old 200 and token empty-string body; new 404, because the route is removed in both modes. Public status stays 200; dev ordinary GET/POST stay 200.",
        "McpOriginValidationTest bootstrap foreign Origin: old 403, MCP_ORIGIN_FORBIDDEN, no JSON-RPC/body token; native/loopback/Tauri: old 200 (native body contains test-token). These route-specific guards are retired with the route. The replacement fixture-absence sanity case authenticates each absent/loopback/Tauri/foreign Origin and expects 404 plus no secret. It passes when transplanted to the old minimal fixture because its fabricated handler is also removed, so this is explicitly not fail-before proof of production route removal. ProductionRouteTokenBoundaryTest and ProductionRouteTokenWalkTest supply that independent fail-before."
      ],
      "supportingEvidence": [
        "gates/test-intent/evidence/token-bootstrap-engine.json",
        "gates/test-intent/evidence/token-bootstrap-installed-engine.json",
        "gates/test-intent/evidence/token-bootstrap-java-preservation.json"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredSurvivalHttpAdmissionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T17",
          "quote": "Host allowlist, the Origin check on the surviving MCP endpoint (`/mcp`) and CORS keep their behavior on real HTTP responses, including OPTIONS preflight from desktop origins and denial of foreign, null and lookalike origins.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "whatMoved": "Authenticated fixture requests now carry their existing owner credential before reaching the oracle. DeclaredSurvivalHttpAdmissionTest changes only GET helper header (46/46 assertions identical), retaining request foreground ownership, operation stream and admission assertions. OpenAiCompatControllerTest adds the token to both existing /v1/models Origin cases; expected status 200, exact JSON body and CORS headers are identical; only diagnostic text changes from \"token-exempt production GET\" to \"authenticated production GET\". McpOriginValidationTest runs the installed production filters in production mode with a known token and adds it to GET/POST/DELETE; the 64 assertions of surviving Origin/method/lifecycle tests, origin input sets, denied error shapes, hook ordering and public status read are unchanged. Only the retired bootstrap expectations belong to the separate Obsolete entry. The static audit retains full diffs so authentication preconditions and fault injection can be reviewed, not just assertion strings.",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
        "gates/test-intent/evidence/token-bootstrap-java-preservation.json",
        "gates/test-intent/evidence/token-bootstrap-original-preservation.json"
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json"
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding",
        "instrumentation"
      ],
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T9",
          "quote": "The desktop app works end to end on Linux and Windows (search, preview, chat history and live-run reattach, activity, logs, settings, plugins, capability handshake, all streams), with no token-denied requests.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T15",
          "quote": "Installer CI, sandbox probes, jseval and conformance tools get the token by the supported path and still check missing, wrong and valid tokens.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "whatMoved": "The base GET helper delegates with empty headers by default and gains an overload that copies supplied headers; URL, GET, timeout, response body and JSON parsing stay the same. The production settings test passes its existing tokenHeader() for settings and AI-status GETs so the same write/reload persistence and activation-failure oracles can execute under T2. All 19 old assertion calls are identical; an extra HTTP=200 assertion in the readiness loop makes explicit the successful-response precondition formerly assumed before parsing activation. No activation state, missing-token POST expectation, failure deadline or persistence expected value changes.",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
        "gates/test-intent/evidence/token-bootstrap-java-preservation.json",
        "gates/test-intent/evidence/token-bootstrap-original-preservation.json"
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json"
    },
    {
      "items": [
        "modules/ui-web/src/activity/idleSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/ResourceView.history-reconnect.test.ts",
        "modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryStore.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionStreamOwnership.test.ts",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthLitView.test.ts",
        "modules/ui-web/src/test/fetchSse.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T9",
          "quote": "The desktop app works end to end on Linux and Windows (search, preview, chat history and live-run reattach, activity, logs, settings, plugins, capability handshake, all streams), with no token-denied requests.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T10",
          "quote": "Engine restart or crash with the UI open: reads and streams recover with the new token; the old token is rejected; a token published late at cold start is picked up without a permanent failure.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-5ec1a1 request",
          "quote": "Fix: these tests must exercise the stream path the product now uses. Adapt their stubs to the fetch-based stream (one shared test helper if several need it), keeping every behavioral assertion; change an assertion only where the transport change forces it, and list each such change with the old and new assertion. Do not skip or delete tests."
        }
      ],
      "whatMoved": "EventSource stubs and immediate fetch-count timing are adapted to actual authenticated fetch SSE. Shared src/test/fetchSse.ts supplies HTTP Response/ReadableStream bytes, lifecycle/abort observations and asynchronous flushes; it does not replace FetchEventSource, the parser, EnvelopeStream, reducers or rendering. All 229 old web assertion calls survive: 212 are text-identical and 17 map fake transport observations to real fetch observations with unchanged expected results (full pairs in the audit). ResourceView listener sets still equal frame/open/error then empty; stream URLs remain identical. Advisory counts, URL, identity and close expectations keep their values, observing HTTP cancellation instead of EventSource.CLOSED. Idle/presence, rows and history reconciliation, advisory ack/order/connectivity, health snapshots/deltas/reset/badges, AI poll 3/6/9 counts and cancellation, and same-run prompt/lookup/abort oracles remain. AgentSession adds a fetch-start readiness assertion while the authorization lookup remains unresolved. Indexing bridge withholds fetch instead of EventSource to exercise the same no-transport fallback and adds zero pooled connections beside zero tasks. Full diff inventories retain all setup, tags, conditions and input frames for review; test-only helper ownership is governed by the named register.",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
        "gates/test-intent/evidence/token-bootstrap-web-preservation.json",
        "gates/test-intent/evidence/token-bootstrap-original-preservation.json"
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json"
    },
    {
      "items": [
        "modules/ui-web/src/api/http.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.test.ts",
        "modules/ui-web/src/api/registry/AuthenticatedCatalogs.test.ts",
        "modules/ui-web/src/shell-v0/streaming/AuthenticatedStream.test.ts"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T2",
          "quote": "In production every request except OPTIONS and GET/HEAD on the six public Runtime Contract reads (`/api/health`, `/api/runtime/live`, `/api/runtime/ready`, `/api/status`, `/api/runtime/manifest`, `/.well-known/justsearch/manifest.json`) needs the token, including streams; a test walks every registered route of the real production server so future routes are covered.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T9",
          "quote": "The desktop app works end to end on Linux and Windows (search, preview, chat history and live-run reattach, activity, logs, settings, plugins, capability handshake, all streams), with no token-denied requests.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T10",
          "quote": "Engine restart or crash with the UI open: reads and streams recover with the new token; the old token is rejected; a token published late at cold start is picked up without a permanent failure.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T12",
          "quote": "No credential reaches an unproven Engine: a replaced manifest or a reused port during discovery gets no token and no tool arguments; discovery probes stay unauthenticated public reads.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T4",
          "quote": "Dev mode stays token-free for GET and POST.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T9",
          "quote": "The desktop app works end to end on Linux and Windows (search, preview, chat history and live-run reattach, activity, logs, settings, plugins, capability handshake, all streams), with no token-denied requests.",
          "label": "agent-drafted, owner-adopted"
        },
        "reproduction": "On 8f49bdc3a, production GETs omit the owner token and default streams use EventSource, which cannot send the required header. They cannot operate against T2 authenticated non-public routes. Named candidate checks transplanted onto that parent fail assertions; the candidate passes."
      },
      "reproduction": "Run token-bootstrap-web.json against 8f49bdc3a, parent of 9a893ca7a. http.test.ts sends old-token on GET, refreshes after 401 and returns the exact recovered object with new-token; authorizedFetch.test.ts now expects tok-abc and session-token resolution on GET, replacing the old undefined-header/no-invoke expectation. AuthenticatedCatalogs covers seven default catalog fetches plus canonical Engine schema reads using owner-boot-token, and retains an external-document control with no credential/no token lookup (that control already passes before; it is not claimed as a repair). AuthenticatedStream exercises chunk-split CRLF SSE after 401 refresh with no token in URL, EOF checkpoint resume with the next boot token, cold-start null token retry, connected/sequence state and stop-abort. Transport stubs on the parent produce no fetch, and header assertions fail; no test derives its expected value from production output. http.test.ts captures fulfilled/rejected outcome before the same exact data assertion because this Vitest version renders failed .resolves as Error; an unexpected rejection now fails toEqual as AssertionError, preserving the entire recovery oracle.",
      "evidence": "gates/test-intent/evidence/token-bootstrap-web.json",
      "newChecks": [
        "modules/ui-web/src/api/http.test.ts#authenticates GET and retries once with the newly resolved token after a restart",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.test.ts#attaches the resolved session token to GET requests",
        "modules/ui-web/src/api/registry/AuthenticatedCatalogs.test.ts",
        "modules/ui-web/src/shell-v0/streaming/AuthenticatedStream.test.ts"
      ],
      "preservedControls": "http browser/dev no-token GET stays header-free (T4); existing request serialization, abort, admission retry and endpoint discovery assertions remain. authorizedFetch non-GET/wrong-token/unsafe-retry behavior remains. External schema URLs never gain Engine credentials (T12 trust boundary)."
    }
  ]
}
```

## Acceptance records
