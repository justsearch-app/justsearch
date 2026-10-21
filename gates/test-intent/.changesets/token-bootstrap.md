---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15693-314a-7240-ad93-9b6f8c3969e5
---

# Test-intent entries

Author entries retain 25 test/support items. The current merge-base with
`origin/main` is `f6d92d768f86d778ea805012b176c3349705018c`, merged in
`734f9f25b` on 2026-10-21. This diff has 24 changed items: 19 flagged items and
five isolated new test exemptions. HeadlessLauncherProductionModeTest already exists
unchanged in main; its historical entry remains, as do the entries for exempt files
with their original source/evidence duties.
The user adopted T1–T19 on 2026-10-20: “i agree, proceed accordingly”.
Each cited scenario below quotes its agreed text, independently of implementation output.
These are author entries. Existing independent acceptance and audit records are retained
unchanged below; they refer to an older digest. No new review record is authored here.

Execution procedure and assertion inventory: [evidence notes](../evidence/token-bootstrap-notes.md).
The security before-runs use the relevant fix's parent: `8f49bdc3a` before
`9a893ca7a`; launcher expectations use `c00a0cb24` before `104f263de`.
Using the current parent would test already-repaired code. Preservation runs use
unaltered original tests at the current main merge-base, plus adapted candidate tests and an
explicit assertion/diff audit regenerated against that same base. Historical receipts
retain their recorded revisions. The latest merge is `734f9f25b`; subsequent evidence and
changeset edits are intentionally uncommitted. This does not claim Windows or desktop end-to-end execution.
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
        "reproduction": "On 8f49bdc3a invoke the existing HeadlessApp port-signal method, capturing both System.out and System.err and restoring both in finally. The stdout case keeps the JUSTSEARCH_API_PORT=54321 assertion and rejects the credential value and JUSTSEARCH_SESSION_TOKEN marker. It fails before on stdout and passes after. The same helper supports the old signature by reflection. The separately recorded stderr case is preservation, not a claim of a stderr defect: it passes before and after."
      },
      "reproduction": "On 8f49bdc3a invoke the existing HeadlessApp port-signal method, capturing both System.out and System.err and restoring both in finally. The stdout case keeps the JUSTSEARCH_API_PORT=54321 assertion and rejects the credential value and JUSTSEARCH_SESSION_TOKEN marker. It fails before on stdout and passes after. The same helper supports the old signature by reflection. The separately recorded stderr case is preservation, not a claim of a stderr defect: it passes before and after.",
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
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java#stdoutDoesNotEmitSessionCredential"
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
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed security scenarios",
          "choice": "Base: this checkout's HEAD (104f263de, the headless launchers already start in production mode; leave that change alone).",
          "label": "agent choice"
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
        "reproduction": "On 8f49bdc3a, install the production security filter over the minimal test router. New private GET/HEAD/PATCH/TRACE requests without credentials must return 401 and removed-bootstrap access in production must return 401 without a credential in the body; these assertions fail before. Existing mutation missing/wrong/valid-token, public-status and OPTIONS assertions keep their outcomes. The dev private GET=200 is an added preservation control alongside existing dev POST=200. Fixture-only changes remove the fabricated token handler, add a private read, and propagate HttpResponseException status as production does. Dev bootstrap 404 is covered by the obsolete-behaviour warrant below; it is not claimed to fail on the transplanted old fixture."
      },
      "reproduction": "On 8f49bdc3a, install the production security filter over the minimal test router. New private GET/HEAD/PATCH/TRACE requests without credentials must return 401 and removed-bootstrap access in production must return 401 without a credential in the body; these assertions fail before. Existing mutation missing/wrong/valid-token, public-status and OPTIONS assertions keep their outcomes. The dev private GET=200 is an added preservation control alongside existing dev POST=200. Fixture-only changes remove the fabricated token handler, add a private read, and propagate HttpResponseException status as production does. Dev bootstrap 404 is covered by the obsolete-behaviour warrant below; it is not claimed to fail on the transplanted old fixture.",
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
        "McpOriginValidationTest bootstrap foreign Origin: old 403, MCP_ORIGIN_FORBIDDEN, no JSON-RPC/body token; native/loopback/Tauri: old 200 (native body contains test-token). Those route-specific expectations are retired with the route. The replacement fixture-absence case keeps dev requests credential-free and authenticates production requests, expecting 404 plus no secret for each absent/loopback/Tauri/foreign Origin. On 8f49bdc3a it fails for the foreign Origin in both modes (old guard returns 403), proving the obsolete token-path guard was retired. Because the fabricated handler is removed by the test overlay, that alone does not prove production route removal: ProductionRouteTokenBoundaryTest and ProductionRouteTokenWalkTest supply the real-server fail-before."
      ],
      "supportingEvidence": [
        "gates/test-intent/evidence/token-bootstrap-engine.json",
        "gates/test-intent/evidence/token-bootstrap-installed-engine.json"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredSurvivalHttpAdmissionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java"
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
      "whatMoved": "Authenticated fixture requests now carry their existing owner credential before reaching the oracle. DeclaredSurvivalHttpAdmissionTest changes only GET helper header (46/46 assertions identical), retaining request foreground ownership, operation stream and admission assertions. OpenAiCompatControllerTest adds the token to both existing /v1/models Origin cases; expected status 200, exact JSON body and CORS headers are identical; only diagnostic text changes from \"token-exempt production GET\" to \"authenticated production GET\". The static audit retains full diffs so authentication preconditions and fault injection can be reviewed, not just assertion strings.",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
        "gates/test-intent/evidence/token-bootstrap-engine.json",
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
        "gates/test-intent/evidence/token-bootstrap-engine.json",
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
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed security scenarios",
          "choice": "Fix: these tests must exercise the stream path the product now uses. Adapt their stubs to the fetch-based stream (one shared test helper if several need it), keeping every behavioral assertion; change an assertion only where the transport change forces it, and list each such change with the old and new assertion. Do not skip or delete tests.",
          "label": "agent choice"
        }
      ],
      "whatMoved": "EventSource stubs and immediate fetch-count timing are adapted to actual authenticated fetch SSE. Shared src/test/fetchSse.ts supplies HTTP Response/ReadableStream bytes, lifecycle/abort observations and asynchronous flushes; it does not replace FetchEventSource, the parser, EnvelopeStream, reducers or rendering. All 229 old web assertion calls survive: 212 are text-identical and 17 map fake transport observations to real fetch observations with unchanged expected results (full pairs in the audit). ResourceView listener sets still equal frame/open/error then empty; stream URLs remain identical. Advisory counts, URL, identity and close expectations keep their values, observing HTTP cancellation instead of EventSource.CLOSED. Idle/presence, rows and history reconciliation, advisory ack/order/connectivity, health snapshots/deltas/reset/badges, AI poll 3/6/9 counts and cancellation, and same-run prompt/lookup/abort oracles remain. AgentSession adds a fetch-start readiness assertion while the authorization lookup remains unresolved. Indexing bridge withholds fetch instead of EventSource to exercise the same no-transport fallback and adds zero pooled connections beside zero tasks. Full diff inventories retain all setup, tags, conditions and input frames for review; test-only helper ownership is governed by the named register. Base merge 63bcd183c keeps main's incidental-request isolation in ResourceView.render and HealthLitView: non-stream fetches reject with TypeError('Test backend unavailable'), while fetchSseHarness handles actual SSE requests. Main added no cases in those two files; all eight ResourceView and ten HealthLitView cases and all 35 assertion calls survive. No behavioral assertion was changed by this merge. The complete 39-file union of ui-web tests touched by either side executed: 909 passed, zero failed/skipped. A fresh unmodified shared clone at PR base fe3753d37 ran all eight original stream-fixture files (100 cases); candidate adaptations also ran 100 cases. The new-base inventory records every old assertion and its transport-only mapping. The 2026-10-21 main merge (734f9f25b, base f6d92d768) changes no test or assertion in these fixtures. Fresh D4 runs execute all eight original files unmodified in a detached shared clone of the new main base (100 passed), and the eight adapted files in the merged candidate (100 passed). The regenerated new-base inventory retains all 229 original web assertions, including the 17 transport observation mappings; none is unmatched. Earlier merge executions described above remain historical evidence, rather than claims about the current base.",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
        "gates/test-intent/evidence/token-bootstrap-main-20261021-web.json",
        "gates/test-intent/evidence/token-bootstrap-main-20261021-base-preservation.json"
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
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T17",
          "quote": "Host allowlist, the Origin check on the surviving MCP endpoint (`/mcp`) and CORS keep their behavior on real HTTP responses, including OPTIONS preflight from desktop origins and denial of foreign, null and lookalike origins.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "whatMoved": "All 19 existing candidate cases run in dev mode with ApiSecurityFilters(false, null) and no session-token headers, preserving their inputs, assertions, hook ordering and fixture preconditions. This includes the 18 surviving merge-base cases plus the absence sanity case already warranted by T1; retired bootstrap expectations remain in the Obsolete entry. JUnit class parameterization also runs the identical bodies in production, classified separately as New. The dev run is retained rather than replaced. The 64 surviving merge-base assertion calls remain identical; the full setup diff and before/after execution receipts are in the adaptation audit.",
      "evidence": "gates/test-intent/evidence/token-bootstrap-adaptation-audit.json",
      "adaptationEvidence": [
        "gates/test-intent/evidence/token-bootstrap-engine.json",
        "gates/test-intent/evidence/token-bootstrap-original-preservation.json"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "class": "New",
      "behaviour": "Run all 19 existing MCP Origin cases alongside the preserved dev run in production mode: install ApiSecurityFilters(true, \"test-token\") and authenticate GET/POST/DELETE helper requests. The same Origin inputs, rejection codes/bodies, method ordering, after-handler balance, public-route CORS and bootstrap-absence assertions apply. No prior expectation is replaced; pure predicate cases also run in both class invocations. T17 supplies the independent Origin obligation; T2 explains the authenticated production fixture and T1 the already-removed token endpoint.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "T17",
          "quote": "Host allowlist, the Origin check on the surviving MCP endpoint (`/mcp`) and CORS keep their behavior on real HTTP responses, including OPTIONS preflight from desktop origins and denial of foreign, null and lookalike origins.",
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
          "scenario": "T1",
          "quote": "`GET /api/mcp/token` hands the token to no caller: without a token it is refused like any other protected route (401), with a valid token it answers 404; no other route hands out the token.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation control: capture stderr as well as stdout while invoking emitPortSignals, and assert stderr contains neither secret-output-token nor JUSTSEARCH_SESSION_TOKEN=. The base prints its credential only to stdout, so the stderr expectation passes on both 8f49bdc3a and the candidate. This is not labeled a repaired stderr defect.",
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
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java#stderrDoesNotEmitSessionCredential"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "92a21fb6-b2be-4233-b0b5-3b5a2910d56b",
  "verdict": "accept",
  "digest": "sha256:6ee3080b467b5dbd824f79bc9b9b053ce32f86dca22cf86eb85373bab1f3ec80",
  "note": "All 11 entries match their class and owner-adopted T1-T19 sources; reran the Java security pins and web token/stream pins (both fail on base by assertion for the reason under test and pass on fcdac3d8f), and the 0073e38aa and http.test.ts edits add only the token or capture the outcome without weakening any assertion."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "efb762b0-25cb-4e32-9a2c-62d35ea349d0",
  "verdict": "reject",
  "digest": "sha256:6ee3080b467b5dbd824f79bc9b9b053ce32f86dca22cf86eb85373bab1f3ec80",
  "note": "The McpOriginValidationTest Adaptation changes a precondition, not just instrumentation: it moves the live /mcp Origin cases from dev to production mode, which the change did not force (my rerun with the dev-mode fixture passes 19/19 on the candidate), and leaves no live test of the guard in dev mode, where ADR-0046 says it applies regardless of mode and where it is the only barrier because no token is required; keep the dev-mode cases (add the production run alongside) or cite a source for dropping them."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "870966bc-b6a6-40df-b8e1-01d50f08b2f8",
  "verdict": "accept",
  "digest": "sha256:3c4cfc3d6dbe8201f250db2adc33417919cf5974038d56c647ecff475a528573",
  "note": "All 15 entries hold against T1-T19 with owner-adopted sources; reran Java and web fail-before on the merge-base (assertion failures for the reason under test) and candidate passes, McpOriginValidationTest keeps all dev-mode cases; minor: E5/E10 delegated-choice text reads as brief wording and the launcher mode assertion has no cited source."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "6b4a638d-8764-4777-b299-fbdb6811912c",
  "verdict": "accept",
  "digest": "sha256:3c4cfc3d6dbe8201f250db2adc33417919cf5974038d56c647ecff475a528573",
  "note": "All 15 entries match their class and T1-T19 sources, and both delegated choices stay inside 'resume with remaining work autonomously' with no stronger conflict (ADR-0046 at base is replaced by the later T1/T2); my reruns show the Java and web new checks fail on c00a0cb24 by assertion for the reason under test and pass on e1a09d099, and the unmodified base McpOriginValidationTest and LocalApiUiTokenPolicyTest pass on the candidate except the T1-retired token-route cases, so the dev-mode Origin cases are kept; notes: the launcher delegated-choice text is a brief line rather than a stated choice, and the launcher and installed-route-walk receipts were made with JAVA_TOOL_OPTIONS (not rerun by me)."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "24342684-97c7-4add-95c5-bf296423268c",
  "verdict": "accept",
  "digest": "sha256:0f11c30e4abfe497db3d91a352a4c88d77cab807295ed443e97ef0fb2910723f",
  "note": "All entries hold their class against owner-adopted T1-T19 with no stronger conflict (ADR-0046 at base is superseded by T1/T2; the launcher choice matches the recorded orchestrator decision); I reran the Java (LocalApiUiTokenPolicyTest, McpOriginValidationTest) and web (http, authorizedFetch) new checks on 8f49bdc3a, where they fail by assertion for the reason under test, and they pass on 316da8a0a; the unmodified originals pass at f003a5c64, the dev-mode Origin cases are kept 19/19, and the merged ResourceView.render/HealthLitView keep all assertions; minor: two delegated-choice texts read as brief lines."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "e74f8991-bc56-4a12-a427-5f33819f440d",
  "verdict": "accept",
  "digest": "sha256:0f11c30e4abfe497db3d91a352a4c88d77cab807295ed443e97ef0fb2910723f",
  "note": "All 15 entries hold against owner-adopted T1-T19 with in-scope delegated choices (ADR-0046 at base is superseded by T1/T2/T19); my reruns show the Java and web new checks fail on base f003a5c64 by assertion for the reason under test and pass on 316da8a0a, all 18 surviving dev-mode McpOriginValidationTest cases pass unchanged on both, and adapted stream tests still fail when the real fetch SSE parser drops frames; notes: idleSurfaces now feeds frames only to open streams (transport-forced, not called out in whatMoved), and the launcher and installed-walk receipts were made with JAVA_TOOL_OPTIONS (launcher re-verified without it; walk not rerun)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "463cc439-2e8d-4bc4-a560-ac7077418318",
  "verdict": "accept",
  "digest": "sha256:78189d5f6e14b12cb9061bfaac80d9943d80e06a7b63544c6de078695cf6086b",
  "note": "All 19 flagged items re-reviewed at base 50eafae23: classes and T1/T2/T4/T9/T17 sources hold, ADR-0046's base text is superseded by owner-adopted T1/T2/T19, old assertions survive (17 web transport remaps only), and reruns show the new Java and web checks fail by assertion on the base and pass on the candidate with dev-mode Origin cases intact."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "9a13f402-2547-4f10-a332-f97d01b8add5",
  "verdict": "accept",
  "digest": "sha256:78189d5f6e14b12cb9061bfaac80d9943d80e06a7b63544c6de078695cf6086b",
  "note": "Audit at 86244f7c7 (base 50eafae23): no flagged item, entry or evidence changed since 7df82d2ef and main changed no flagged test; reran Java and web fail-before/preservation at the new base (assertion failures for the reason under test, all McpOriginValidation dev cases and original tests pass on both); non-blocking: header prose names old bases and the HeadlessLauncherProductionModeTest entry now covers a file this PR no longer changes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "a4c2b5eb-530f-4cc3-841f-9a6cb207900c",
  "verdict": "accept",
  "digest": "sha256:883010a3fde180e23e2c4d7bb98e66eaff3cf52951f8575e0e0a2215b31437b8",
  "note": "All 15 entries hold on digest 883010a3: classes match the before-state tests and the owner-adopted T1-T19, delegated choices stay in scope with base ADR-0046 superseded by T1/T19, and reruns confirmed assertion fail-before plus candidate passes for the Java token/Origin and web GET-token checks, with dev-mode Origin cases unchanged and the merged production router walked."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "3dfdf9fc-0b44-4632-8027-02109cc4faaa",
  "verdict": "accept",
  "digest": "sha256:883010a3fde180e23e2c4d7bb98e66eaff3cf52951f8575e0e0a2215b31437b8",
  "note": "All 15 entries hold on the merged candidate: classes match, sources predate the PR, delegated choices are in scope, Java and web fail-befores rerun as assertion failures, adaptations keep every old assertion and dev-mode precondition, and the walk covers the merge's new stop route."
}
```
