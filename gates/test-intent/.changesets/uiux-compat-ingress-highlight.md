---
schema: test-intent.v1
task: uiux-compat-ingress-highlight
author-role: builder
author-session: codex-uiux-compat-ingress-builder
---

# Test-intent entries

These contracts are agent-adopted (delegated-choice). Sources are W3/C8/A7 and A3/C9
in the supplied WP1.5 agreement and the corrected bounded builder brief. The correction
settles 0-based inclusive bounds: finite integers with 0 <= start <= end. No old assertion,
baseline or runner configuration is weakened. No author acceptance is provided.

Causes on f17b988e2f: `router/navigationHandler.ts:193` parks only canonical Search tasks;
Ask's catalog schema instead hydrates the old `ask` store, which Search no longer reads.
Shape-mounted Search rejects incoming hand-offs at `SearchV3View.ts:3982`.
Retired aliases work because the resolver redirects to canonical Search; canonical
addresses already use its hand-off preparation and validation. Separately,
`plugin-api/capabilities/ui.ts:58` copies identity but omits both highlight fields.
The earlier repair also rejected line 0 with `start > 0`.

All added checks live in two new files. HostApiSelection.test.ts and
SearchV3View.handoffs.test.ts are byte-identical to f17b988e2f, preserving earlier
evidence and acceptance digests. On the baseline with only these two new files overlaid,
14 repair checks fail at the intended assertions and all 23 preservation checks pass;
the candidate passes all 37. The correction alone was also reproduced against the earlier
uncommitted repair: five line-zero checks failed (three trust tiers and two rendered widths).

DOM probes at 900 and 1568 mock the observed box and assert overlay/inline mode, one reader,
its path/range and actual painted text, including the first line and negative-range refusal.
These are DOM tests, not real browser geometry evidence. File-error HTTP/SSE fixtures
are controlled responses, not native Engine file-read evidence.

The unchanged official evidence producer is invoked for repair and preservation ids.
To satisfy the builder's shared-clone/private-dependencies rule, a temporary Git adapter
translates only worktree creation/removal into shared-clone/detached-checkout/removal,
with plain npm ci in each clone. A private temporary object directory with read-only
source alternates holds the producer's throwaway-index working-tree objects. No source
worktree registration, shared node_modules or source object writes are needed.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts"
      ],
      "class": "New",
      "behaviour": "New public-address and public-plugin regression checks for agent-adopted W3/C8/A7 and A3/C9: exact prepared inputs, no dispatch until Send, chosen request scope, visible file refusal, valid 0-based inclusive range propagation and painted lines in inline/overlay presentations. Existing test files are byte-identical to f17b988e2f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8/A7: supported compatibility links retain exact text and chosen files, select Ask/model-only/Structured and wait for Send; invalid or unavailable scope is visibly refused without broadening. Preserve retired aliases and canonical Search links. Agent-adopted A3/C9: public plugin inspector opens one reader on the right file with the supplied passage. The corrected builder brief specifies finite integer bounds with 0 <= start <= end, otherwise no highlight and no error; retain the existing 0-based inclusive line convention.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-ingress-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation CORE keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation TRUSTED_PLUGIN keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation UNTRUSTED_PLUGIN keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range 6 to 8 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range 6 to 8 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range 0 to 1 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range 0 to 1 only",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.ask-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.free-chat-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.extract-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 a warm compatibility address replaces preparation and still waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A2 an invalid file scope through core.ask-surface keeps text and refuses Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.ask-surface with missing file refuses without widening scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.ask-surface with unreadable file refuses without widening scope"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts"
      ],
      "class": "New",
      "behaviour": "New preservation checks for retired aliases and canonical Search address text/files/request scope; canonical invalid, missing and unreadable file refusal without widening; absent, partial, reversed, negative, fractional, nonfinite or nonnumeric plugin highlight bounds are ignored without throwing or retaining a previous highlight. Negative-start ranges paint no lines at both widths. Existing test files are byte-identical to f17b988e2f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8/A7: supported compatibility links retain exact text and chosen files, select Ask/model-only/Structured and wait for Send; invalid or unavailable scope is visibly refused without broadening. Preserve retired aliases and canonical Search links. Agent-adopted A3/C9: public plugin inspector opens one reader on the right file with the supplied passage. The corrected builder brief specifies finite integer bounds with 0 <= start <= end, otherwise no highlight and no error; retain the existing 0-based inclusive line convention.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-ingress-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range undefined to undefined without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to undefined without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range undefined to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range -1 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 8 to 6 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 1.5 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8.5 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range NaN to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to NaN without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range Infinity to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to Infinity without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range null to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range -1 to 1 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range -1 to 1 only",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.unified-chat-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.search-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.agent-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.search-v3-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A2 an invalid file scope through core.search-v3-surface keeps text and refuses Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.search-v3-surface with missing file refuses without widening scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.search-v3-surface with unreadable file refuses without widening scope"
      ]
    }
  ]
}
```

## Builder validation (not independent acceptance)

File-by-file result:

- `modules/ui-web/src/shell-v0/router/navigationHandler.ts`: retained the earlier compatibility-target lowering into canonical Search ingress before schema hydration, preserving task text, shape and file validation.
- `modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.ts`: retained highlight propagation and corrected validation to integer bounds with 0 <= start <= end. Number.isInteger rejects NaN and infinities.
- `modules/ui-web/src/shell-v0/plugin-api/plugin-types.ts`: documented both optional highlight fields with the reader's 0-based inclusive convention and corrected valid bounds.
- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts`: moved all new compatibility-address checks into their own file, with isolated setup/cleanup.
- `modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts`: moved all new inspector checks into their own file; tests valid first-line spans and negative-start refusal at both rendered widths, in addition to trust tiers and malformed ranges.
- `gates/test-intent/evidence/uiux-compat-ingress-repair.json`: regenerated unchanged-producer evidence for 14 repaired check ids, including all assertion failures on f17b988e2f.
- `gates/test-intent/evidence/uiux-compat-ingress-preservation.json`: regenerated unchanged-producer evidence for 23 preservation check ids.
- This changeset: updated paths, the corrected contract, check ids and validation results. No acceptance record added.

`HostApiSelection.test.ts` and `SearchV3View.handoffs.test.ts` are restored byte-for-byte to f17b988e2f. No existing test files remain changed relative to that baseline.

Before/after scenario results (same final new test files overlaid on the baseline):

| Scenario / test | f17b988e2f | Candidate |
| --- | --- | --- |
| C8 W3 A7 compatibility address Ask, FreeChat and Extract: text/files/mode, human Send | 3 assertion failures | 3 pass |
| C8 A7 warm compatibility arrival replaces preparation and waits | 1 assertion failure | 1 pass |
| C8 A2 invalid Ask file scope retains text and refuses Send | 1 assertion failure | 1 pass |
| C8 A7 missing/unreadable Ask file: request scope and visible refusal | 2 assertion failures | 2 pass |
| C9 A3 valid inclusive inspector passage, including line 0, across three trust tiers | 3 assertion failures | 3 pass |
| C9 A3 inspector paints lines 6–8 and 0–1 at widths 900 and 1568 | 4 assertion failures | 4 pass |
| C2 A7 three retired aliases and canonical Search retain text/files/request scope | 4 pass | 4 pass |
| C8 A2/A7 canonical Search invalid/missing/unreadable file refusal | 3 pass | 3 pass |
| C9 A3 absent/partial/reversed/negative/fractional/nonfinite/nonnumeric ranges ignored | 14 pass | 14 pass |
| C9 A3 negative-start inspector ranges paint no lines at both widths | 2 pass | 2 pass |

Commands and observed results:

- `.agents/preflight`: absent; not run.
- Plain `npm ci` in source UI and root: exit 0, 283 and 386 packages installed. UI reports one moderate audit finding; root reports zero vulnerabilities. Dependencies and lockfiles unchanged. Both shared before-clones used their own plain UI npm ci, 283 packages each; no shared dependency trees.
- In `modules/ui-web`, `npm run typecheck`: exit 0.
- In `modules/ui-web`, `npm run lint`: exit 0, zero warnings.
- In `modules/ui-web`, `npm run test:unit:run -- --configLoader runner --maxWorkers=4`: exit 0; 643 files, 8,214 tests passed, no skipped tests; 96.33 seconds.
- Focused `npm run test:unit:run -- src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private result>`: baseline exit 1, 23 passed / 14 assertion failures; candidate exit 0, 37 passed. The unchanged official producer also exits 0 for repair (with --before f17b988e2f) and preservation ids. All 14 repair ids classify as assertion failures before and pass after; all 23 preservation cases pass in both direct focused runs.
- `node scripts/ci/run-ui-web-gates.mjs`: exit 0, 28/28 passed.
- `node scripts/governance/gates/test-intent/cli.mjs check --base origin/main`: exit 1, exactly ten expected problems: the package's missing independent acceptance plus nine main-moved uncovered items listed below. No evidence-invalid, entry-invalid or stale acceptance-digest problems. The untouched baseline shared clone reports the same nine uncovered items with the same base b742806877c52da9d7197c0654954011ef5a4121. Its remote-tracking main was aligned to the source's origin/main because a local clone otherwise tracks the source's local main branch.
- `git diff --check`: exit 0. Existing test files compared with `git diff --exit-code f17b988e2f -- <both original test files>`: exit 0.

Main-moved uncovered items (this package touches none of these):

- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushBarrierTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushRaceTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/app/engine/OutstandingHttpFailureCleanupIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiConfigIsolationIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java`

All processes ran with CUDA_VISIBLE_DEVICES=-1, inherited JUSTSEARCH_* and JVM option variables removed, and GRADLE_OPTS retained. No Engine, browser, GPU, owner app or existing service was used. Checks used private temporary clones, their own dependencies/build folders, DOM/HTTP/SSE fixtures, the suite's own loopback fixtures and npm's ordinary cache. No commits, merge, publishing, GitHub issue actions or independent acceptance. Real browser/native Engine boundary confirmation remains with the independent verifier.
