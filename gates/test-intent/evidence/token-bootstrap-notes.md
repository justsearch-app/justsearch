# Token-bootstrap author evidence

Task `t-20261019-5ec1a1`; author session `01a15693-314a-7240-ad93-9b6f8c3969e5`.
This audit repair starts at `b79707136`. The earlier acceptance and rejection records
remain byte-for-byte unchanged under the changeset's acceptance heading. Their digest
predates this repair; this author appends no review record.

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
