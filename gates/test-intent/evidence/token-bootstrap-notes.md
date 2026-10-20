# Token-bootstrap author evidence

Task `t-20261019-5ec1a1`; author session `01a15693-314a-7240-ad93-9b6f8c3969e5`.
This audit repair starts at `b79707136`. The earlier acceptance and rejection records
remain byte-for-byte unchanged under the changeset's acceptance heading. Their digest
predates this repair; this author appends no review record.

## Base merge: 2026-10-20

Merged `origin/worktree-headless-prod` (`fe3753d37e585f35c61ae5f1488a909739d4b47c`)
as merge commit `63bcd183c720e6f72954fb91d7bcc235d640ffc8`, with the requested
message and trailers. Local `origin/main` merge-base is now
`f003a5c6476776ed2e56d9e0882f8bbaf55fc731`. This section supersedes the old
base/fixture execution references below; historical security fail-before receipts
retain their original revisions. Author session remains
`01a15693-314a-7240-ad93-9b6f8c3969e5`. No new acceptance or audit record is authored.

All three conflict hunks were resolved as follows:

1. ResourceView.render.test.ts, import/fetch-isolation hunk: retain the branch's
   fetchSseHarness/flushStreamWork path and give its non-SSE fallback main's
   `TypeError('Test backend unavailable')`. Global fetch is restored after each case.
   There is no native EventSource stub; the product's authenticated fetch SSE runs.
2. HealthLitView.test.ts, import/fetch-isolation hunk: retain the same fetch-stream
   harness and asynchronous frame delivery, adding main's rejecting non-SSE fallback.
   Global fetch restoration and stream teardown remain after each case.
3. packaging/mcpb/manifest.json, version/description hunk: version 0.3.1 from this
   branch; description and long_description (including all egress disclosures) from
   the incoming base. The remainder matches the incoming base.

The headless runtime guide merged automatically. It retains incoming desktop/headless
data-root guidance and this branch's owner-only manifest/header authentication,
six public reads, protected streams, absent bootstrap/stdout token, explicit-port
environment variable, and authenticated plan-preview example. No conflict hunk occurred
in that file.

Main added no cases or behavioral assertions in either conflicted test file; its change
was incidental-fetch isolation. All eight ResourceView and ten HealthLitView cases,
and their 35 assertion calls, survive. The merge changes no assertion. Relative to the
new base the existing transport mappings remain: `NoopEventSource.urls` becomes
`streams.connections.map((connection) => connection.url)` for both ResourceView URL
assertions; every expected URL is unchanged. HealthLitView's 22 assertions are identical.
The new-base static audit inventories all eight adapted web fixtures: 229 original
assertions retained, 17 transport observations remapped, two additional readiness/
no-connection instrumentation assertions, none lost. Java assertion inventories also
retain every surviving old assertion (bootstrap removal is documented separately).

Fresh D4 receipts, written by the repository producer without manual JSON edits:

- `token-bootstrap-merged-web.json`: candidate 63bcd183c, eight adapted files,
  100 cases passed, none failed/skipped; producer exit 0.
- `token-bootstrap-merged-base-preservation.json`: unmodified original tests from
  a detached shared clone at fe3753d37, the same eight paths, 100 passed, none
  failed/skipped; producer exit 0. No candidate test or production overlay.
- `token-bootstrap-adaptation-audit.json`: regenerated with fe3753d37 as baseline,
  embeds both receipts plus the unchanged engine repair receipt and full source diffs.
  This inventory is static evidence, not a D4 producer.

Both D4 web runs used TMPDIR under `tmp/builder/token-base-merge`, the existing
execution adapter's installed Vitest CLI, and `--maxWorkers=4`. The base dependencies
were copied (not linked) from installed node_modules; the shared clone is removed
when done. The producer commands are recorded verbatim in each receipt. Prior security
fail-before evidence remains applicable: the checked test sources did not change in
this merge, and both the new base and current parent already include the security fix.

Affected check results observed after resolving the merge:

- Complete union of ui-web test paths touched on either side since 8f49bdc3a:
  39 files, 909 cases passed, zero failures or skips; `npx vitest run <paths below>
  --maxWorkers=4` exited 0. Paths with .spec names were also checked; none omitted.
- `npm run lint` exited 0; `npm run typecheck` exited 0.
- `js-gradle-slot ./gradlew :modules:ui:test --tests io.justsearch.ui.api.McpOriginValidationTest
  --tests io.justsearch.ui.HeadlessTokenOutputTest
  --tests io.justsearch.ui.api.ProductionRouteTokenBoundaryTest
  :modules:system-tests:integrationTest
  --tests io.justsearch.systemtests.ProductionRouteTokenWalkTest -PskipWebBuild=true
  --console=plain --init-script <temporary java-policy.init.gradle>` exited 0:
  38 Origin cases (19 dev plus 19 production), two output cases, two unit route-boundary
  cases, one installed production route walk; 43 passed, none skipped. The init script
  uses the existing adapter's Java policy verbatim, placing java.io.tmpdir, JUSTSEARCH_HOME
  and JUSTSEARCH_DATA_DIR under the supplied task sandbox. JAVA_TOOL_OPTIONS was unset;
  JAVA_OPTS places the Gradle client temporary directory there too. No Java source changed,
  so no formatting edit was needed.
- The first Gradle invocation exited 1 because its installed-walk filter mistakenly used
  `io.justsearch.system.ProductionRouteTokenWalkTest`: no tests matched. The corrected
  package is `io.justsearch.systemtests`; the corrected run above passed.

Only the merge itself was committed. This changeset and subsequent evidence/notes edits
remain uncommitted. Existing token-bootstrap acceptance and audit records were compared
byte-for-byte with the pre-merge suffix and are unchanged. The gate is checked both with
its default local base (`--pr 825`) and the actual PR base (`--base origin/worktree-headless-prod
--pr 825`). Only independent acceptance on the new digest and its distinct PR #825 audit
remain. The non-blocking reconsider warning still reports four disagreements among four
audited PRs (100%); this author supplies no verdict.

Affected Vitest paths (relative to modules/ui-web):

```
src/__test-setup__/dynamicImportLifetime.test.ts
src/activity/idleSurfaces.test.ts
src/api/contract/contractEventsBridge.test.ts
src/api/http.test.ts
src/api/registry/AuthenticatedCatalogs.test.ts
src/shell-v0/aggregate-substrate/components/JfResource.test.ts
src/shell-v0/api/authorizedFetch.test.ts
src/shell-v0/chrome/Shell.test.ts
src/shell-v0/components/ResourceView.history-reconnect.test.ts
src/shell-v0/components/ResourceView.render.test.ts
src/shell-v0/components/RetrospectivePanel.test.ts
src/shell-v0/components/SourcesPane.test.ts
src/shell-v0/components/advisory/AdvisoryStore.test.ts
src/shell-v0/controllers/AgentSessionController.test.ts
src/shell-v0/controllers/AgentSessionStreamOwnership.test.ts
src/shell-v0/state/searchState.scope.test.ts
src/shell-v0/state/searchState.test.ts
src/shell-v0/streaming/AuthenticatedStream.test.ts
src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts
src/shell-v0/utils/aiInstallPoll.test.ts
src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts
src/shell-v0/views/BrainSurface.pausedInstall.test.ts
src/shell-v0/views/BrainSurface.repairRemedy.test.ts
src/shell-v0/views/BrainSurface.skipReason.test.ts
src/shell-v0/views/BrainSurface.sparkline.test.ts
src/shell-v0/views/BrainSurface.telemetry-mode.test.ts
src/shell-v0/views/HealthLitView.test.ts
src/shell-v0/views/SettingsSurface.chatWidth.test.ts
src/shell-v0/views/SettingsSurface.contextWindow.test.ts
src/shell-v0/views/SettingsSurface.highContrast.test.ts
src/shell-v0/views/SettingsSurface.v15.test.ts
src/shell-v0/views/UnifiedChatView.extractResult.test.ts
src/shell-v0/views/UnifiedChatView.test.ts
src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts
src/shell-v0/views/search-v3/Sv3Palette.test.ts
src/shell-v0/views/search-v3/Sv3Pane.test.ts
src/shell-v0/views/search-v3/sv3-record.test.ts
src/shell-v0/views/search-v3/sv3-run.test.ts
src/shell-v0/views/unifiedThreadProjection.test.ts
```

## Audit repair

- McpOriginValidationTest retains all 19 existing candidate cases in dev mode, with
  `ApiSecurityFilters(false, null)` and no credential header. JUnit's installed class
  parameterization executes the identical bodies again with production=true and the
  valid token. The original Origin inputs, expected codes/bodies, after-handler
  assertions and CORS checks are unchanged. Dev coverage is preserved; production
  execution is a separate New entry sourced primarily to T17.
- HeadlessTokenOutputTest captures both System.out and System.err, restores both in
  finally, and has separate stdout and stderr cases. Both reject the credential value
  and token-line marker. The stdout case retains the API port-line assertion and is a
  Defect pin; stderr is a New preservation control, not a claimed stderr repair.
- Both former owner-words sources were orchestrator brief text. They are now
  delegated-choice sources, retaining the exact choice quotes. Their delegation is
  "resume with remaining work autonomously", within implementation choices inside
  the agreed security scenarios. T2/T16 remain the launcher's primary sources.
- The LocalApiUiTokenPolicyTest entry no longer claims missing-token startup checks.
  Its actual controls are mutation token checks, public/dev requests and OPTIONS.

## Linux reproduction

The repository's `scripts/governance/gates/test-intent/run-evidence.mjs` writes the
D4 evidence, including its self-digest and check source digests. The JSON is not edited
by hand. One `js-gradle-slot` invocation encloses the entire producer: candidate runs,
result collection, the before checkout and before runs. The adapter verifies that its
parent holds the slot before invoking Gradle inside it. This also prevents another
call from replacing the JUnit reports before collection.

`JAVA_TOOL_OPTIONS` is unset throughout. The adapter generates a temporary Gradle init
script under TMPDIR, setting each Test JVM's `java.io.tmpdir`, `JUSTSEARCH_HOME` and
`JUSTSEARCH_DATA_DIR` under TOKEN_TEST_TEMP_ROOT. Every Engine started in that JVM
inherits the sandbox homes. JAVA_OPTS sets the Gradle client temp directory. The
policy does not change test selection, assertions, timeouts or retry behavior.

From the repository root, create the temporary directories, then run:

```sh
export TMPDIR="$PWD/tmp/builder/test-intent-repair"
export TOKEN_TEST_TEMP_ROOT="$TMPDIR/java"
export JAVA_OPTS="-Djava.io.tmpdir=$TMPDIR"
unset JAVA_TOOL_OPTIONS
mkdir -p "$TOKEN_TEST_TEMP_ROOT"
js-gradle-slot node --require ./gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs \
  scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/token-bootstrap-engine.json \
  --before 8f49bdc3a --before-root "$TMPDIR" \
  --overlay modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java#stdoutDoesNotEmitSessionCredential \
  --check modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java#stderrDoesNotEmitSessionCredential \
  --check modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/DeclaredSurvivalHttpAdmissionTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java \
  --check modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java
```

The adapter uses detached shared clones under the supplied before-root and removes
only clones it created. The compatibility overlay is test infrastructure only: it adds
the header-bearing getJson overload needed by the transplanted persistence test. No
production code is overlaid. The application before-state remains 8f49bdc3a, parent
of the security repair 9a893ca7a.

## Observed refreshed results

Candidate Gradle unit and integration commands both exited 0: 73 unit cases and
4 integration cases passed, none skipped. McpOriginValidationTest executed 38 cases,
19 per mode, with every existing assertion retained.

On 8f49bdc3a, 73 unit cases executed with 9 assertion failures; all 4 integration cases
passed. The stdout credential case failed, while the stderr credential case passed.
The 18 surviving original MCP Origin cases passed in each mode. The absence sanity
case failed once per mode because the old token-path Origin guard returns 403 to a
foreign Origin, instead of the new 404. Those two failures concern the retired T1
bootstrap guard, not a new T17 Origin defect. Real production route removal is pinned
by ProductionRouteTokenBoundaryTest and the previously executed installed route walk.

The producer itself exits **1**, because its uniform fail-before requirement treats the
passing preservation controls as non-repairs. That real exit is intentional and is not
reported as a script pass. The evidence records stdout as `assertion` before and stderr
as `passed` before. Changeset Defect pin entries select only repaired checks; New
preservation entries do not require an artificial fail-before. No compile, environment
or uncaught-exception failure is used as proof.

`js-gradle-slot ./gradlew :modules:ui:spotlessApply -PskipWebBuild=true` exited 0.
The affected candidate and before Gradle calls are all inside the producer's one slot;
JAVA_TOOL_OPTIONS is unset and no slot exit 75 was retried.

## Other evidence and fixture inventory

The unchanged earlier receipts retain their original revisions and execution policies:
`token-bootstrap-web.json` (50 candidate cases passed; 13 expected before failures),
`token-bootstrap-web-preservation.json` (100 passed), the raw Vitest reports, launcher
proof (2 passed with 2 before failures), installed Engine walk (1 passed with a before
assertion failure), and original merge-base preservation (142 passed). The old
`token-bootstrap-java-preservation.json` is historical, superseded for current Java
coverage by the refreshed engine receipt; no current entry relies on its old Origin
fixture as preservation evidence. Earlier Java receipts used JAVA_TOOL_OPTIONS; that
policy is not used by this repair.

`node gates/test-intent/evidence/token-bootstrap-adaptation-audit.mjs` regenerates the
static assertion inventory. It embeds the current engine receipt, unchanged original
preservation receipt and web preservation receipt, plus complete per-file setup diffs.
It is not a D4 producer. All 64 surviving merge-base MCP assertion calls remain
identical; the class parameterization explicitly retains the dev precondition. The
17 existing web transport observation substitutions and one Java diagnostic-message
substitution retain their expected outcomes. Three earlier instrumentation assertions
remain documented. Static matching complements inspection of all inputs/preconditions;
it does not establish them by itself.

## Review boundary

The prior records are retained on their old digest. Only new independent acceptance
and the distinct PR #825 audit remain; this author supplies neither. The gate reports all 25 items covered and only acceptance-stale (exit 1).
The non-blocking reconsider-rate warning remains: reviewer disagreements occurred
on 4 of 4 audited PRs over the last 28 days (100%). Gate unit checks passed
(all 32 internal checks), and git diff --check exited 0. Linux evidence
does not establish Windows execution or desktop end-to-end behavior.
