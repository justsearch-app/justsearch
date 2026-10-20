# Token-bootstrap author evidence

Task `t-20261019-5ec1a1`; author session `01a15693-314a-7240-ad93-9b6f8c3969e5`.
Candidate starts at `0073e38aa`. The only test edit for evidence preparation captures
`request()` fulfillment/rejection before an exact `toEqual` assertion in `http.test.ts`.
The expected recovered response, old/new credentials and exact request count remain.
No acceptance or audit was authored.

## Reproduction

The producer is the repository's `scripts/governance/gates/test-intent/run-evidence.mjs`.
Each `token-bootstrap-*.json` with schema `test-intent-evidence.v1` is its untouched output.
Its self-digest and test source digests are verified by the gate.

The accompanying `token-bootstrap-execution-adapter.cjs` is loaded with Node `--require`.
It changes execution policy, without manufacturing outcomes or altering tests:

- `./gradlew ...` runs as `js-gradle-slot ./gradlew ...`; exit 75 stops without retry.
  Java inherits `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<external TMPDIR>` so its temporary
  files follow the same location. The producer records its requested command; the actual prefixed command is printed by
  the adapter. Every Gradle command uses that prefix on candidate and before states.
- The producer's `git worktree add/remove` operations instead create detached shared
  clones under this checkout's ignored `tmp/builder/test-intent/` and remove only clones
  created by the adapter. No Git worktree registration writes into the source repository.
- Vitest runs through the same installed `node_modules/vitest/vitest.mjs` CLI with
  `--maxWorkers=4`. Dependency copies dereference `.bin` links, whose relative imports
  otherwise break when executed from `.bin`; invoking the package CLI avoids that
  environment error. Dependencies are copied, not linked to the source checkout.
- `TMPDIR=/home/elias/.cache/justsearch-token-builder/test-intent-01a15693` keeps Node
  temporary files outside the checkout and outside `/tmp`. Before checkouts stay in the
  supplied ignored builder folder. No product requests/assertions are adapted by this shim.

The initial copied-dependency run failed before executing baseline Vitest because
`.bin` had been dereferenced; its environment result was replaced by a successful
package-CLI run. A separate HTTP before-run exposed Vitest `.resolves` reporting
`Error` instead of `AssertionError`; no exception result is used as repair proof.
The explicit captured-outcome assertion then failed before as an assertion.
After noticing Java does not honor TMPDIR, only this author's recorded still-queued
Gradle processes were stopped and requeued with java.io.tmpdir set; all attached
Java evidence uses the corrected environment. No slot exit 75 was retried.

Example (from repository root; create both temporary directories first):

```sh
TMPDIR=/home/elias/.cache/justsearch-token-builder/test-intent-01a15693 \
node --require ./gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs \
  scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/token-bootstrap-engine.json \
  --before 8f49bdc3a --before-root "$PWD/tmp/builder/test-intent" \
  --check modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java
```

Other evidence files list every requested command and check id. Reproduce by passing
those ids as `--check` arguments. For repaired frontend behavior also pass
`--copy-node-modules --before 8f49bdc3a`. Launcher fail-before uses `c00a0cb24`.
`8f49bdc3a` is the parent of repair `9a893ca7a`, and `c00a0cb24` is the parent of
launcher repair `104f263de`. The current parent already contains both repairs.
The whole-branch comparison and original preservation state are the merge-base
`c00a0cb24b4c6000e090d5d09054c1e7e1d24e2f` with `origin/main`.

## Fixture preservation

`token-bootstrap-adaptation-audit.mjs` generates `token-bootstrap-adaptation-audit.json`:

```sh
node gates/test-intent/evidence/token-bootstrap-adaptation-audit.mjs
```

This is a static inventory, explicitly not D4 execution evidence. It records baseline
and candidate source digests, every old/new assertion, all observation substitutions,
added instrumentation assertions and complete per-file diffs (including setup/tags).
MCP Origin inventory is explicitly limited to surviving MCP tests; the changeset
separately classifies retired token-route expectations as Obsolete.

Every old assertion in the adapted scope has a match: 17 web observation expressions
change from EventSource fixture state to real fetch lifecycle observations while keeping
the expected URL/count/identity/listener/closed outcome. One Java assertion changes
only diagnostic text. Added instrumentation checks are fetch-start readiness, no pooled
connection when fetch is absent, and HTTP 200 before activation response parsing.

`token-bootstrap-original-preservation.json` executes the original, unmodified checks
in a detached shared clone at the merge-base, with an isolated dependency copy.
`token-bootstrap-web-preservation.json` and `token-bootstrap-java-preservation.json`
execute their adapted candidate equivalents. The base helper has no test of its own;
its actual consumer, ProdModeSettingsPersistenceIntegrationTest, executes the overload.
The fetch helper has no independent expected product behavior; its eight importing
suites execute the real default stream path. No test was skipped or removed for this task.

The new catalog suite contains an external-schema negative control that already passes
before; that is preservation, not evidence of a repaired defect. Likewise policy tests
include preserved public/dev/mutation/startup controls alongside new failing security
assertions. The changeset does not treat already-passing controls as fail-before pins.

## Observed results

Every attached run-evidence invocation exited 0. The intentional parent test commands
exited 1 and were classified as assertion failures; they were not compile or environment
failures. The artifact records preserve their real failing command output.

| Evidence | Candidate | Before |
| --- | --- | --- |
| `token-bootstrap-engine.json` | 16 passed | 16 executed, 7 assertion failures; all four repaired file selections classify `assertion` |
| `token-bootstrap-web.json` | 14 selected cases passed; full Vitest run: 50 passed | All four repaired selections classify `assertion`; full parent run: 13 failed, 37 passed, no skips |
| `token-bootstrap-launchers.json` | 2 passed | Both launcher invocations fail the missing production JVM property assertion at `c00a0cb24` |
| `token-bootstrap-installed-engine.json` | 1 passed, real installed Engine | Real Engine route walk fails with 117 authentication assertions at `8f49bdc3a` |
| `token-bootstrap-web-preservation.json` | 100 passed | Original 100 pass in the merge-base preservation run |
| `token-bootstrap-java-preservation.json` | 41 passed | Original 42 pass; two retired bootstrap Origin cases become one absence case, warranted by T1 |
| `token-bootstrap-original-preservation.json` | Original merge-base checks: 142 passed, none skipped | No candidate test transplant: original assertions execute before adaptation |

`node --test scripts/governance/gates/test-intent/units.test.mjs` exited 0:
all 32 internal checks passed. `npm run lint`, `npm run typecheck` and
`git diff --check` exited 0. The intent gate reports all 25 items covered and
only `test-intent/acceptance-missing`, with exit 1 as expected for an author-only record.

The untouched Vitest JSON receipts are `token-bootstrap-web-raw-candidate.json` and
`token-bootstrap-web-raw-before.json`. The external-schema control and browser/dev
GET control pass on both; all 13 repaired frontend cases fail before with assertions.
To retain full receipts when reproducing the web producer, set
`TOKEN_EVIDENCE_RAW="$PWD/gates/test-intent/evidence/token-bootstrap-web-raw"`.
The execution adapter copies the runner's JSON before its temporary report is removed.

Both final gate invocations (default and `--pr 825`) also emit the non-blocking
`test-intent/reconsider-rate` warning: over the last 28 days on origin/main,
two-reviewer disagreements were 4 of 4 audited PRs (100%), with no tie-break yet,
above the one-in-ten threshold. The documented finding must be passed to the
orchestrator; it does not add a blocking gate problem.

## Review boundary

Linux execution cannot establish Windows ACLs, Windows launcher execution, installer CI,
Claude Desktop MCPB or Linux/Windows desktop end-to-end behavior. This evidence prepares
intent review for the 25 flagged items; it does not replace the independent verifier's
behavior review. PR #825 is audit-selected: after acceptance, a distinct reviewer must
also record an audit under the documented rule. The author supplies neither record.
