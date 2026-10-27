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

The first compatibility/highlight repair's checks live in two new files; the S67 extension below adds one separate file. HostApiSelection.test.ts and
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts"
      ],
      "class": "New",
      "behaviour": "New S67 cold document intake regression checks through the public address parser, catalog resolver and navigation handler. Ask, canonical Search, FreeChat and Extract retain exact Unicode/newline input, mode and file scope; earlier preparation remains reachable via Review this draft and nothing dispatches. Capacity and UTF-8 text-limit refusal retain incoming address inputs and name the reason.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8 extension S67 in the bounded builder brief: a valid fresh full-document hand-off wins the composer with exact text, mode and files without sending; the earlier unsent draft remains reachable for review. Genuine capacity or validation refusal must retain incoming text in the composer or address and identify the actual reason. Preserve existing recovery formats, all limits, ownership distinctions and the requirement for human review.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.ask-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.search-v3-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.free-chat-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.extract-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 core.ask-surface genuine capacity refusal retains the incoming address and both existing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 core.search-v3-surface genuine capacity refusal retains the incoming address and both existing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 text limit refusal retains the incoming address and the earlier draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 file limit refusal retains incoming text in the address with the real reason: file count",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 file limit refusal retains incoming text in the address with the real reason: path bytes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve text-only recovery loading and review, its reuse during incoming intake, and distinct ownership even when raw and typed text bytes match. No recovery limits or format versions change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8 extension S67 in the bounded builder brief: a valid fresh full-document hand-off wins the composer with exact text, mode and files without sending; the earlier unsent draft remains reachable for review. Genuine capacity or validation refusal must retain incoming text in the composer or address and identify the actual reason. Preserve existing recovery formats, all limits, ownership distinctions and the requirement for human review.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 old text-only recovery remains reviewable after an incoming task",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 preservation same raw bytes at a different place do not merge draft ownership",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 preservation old text-only recovery loads without an incoming task and requires review"
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


## S67 cold-arrival extension (agent-adopted, delegated-choice)

The supplied third builder brief extends W3/C8 with S67 and canonical fresh-load arrivals.
The new checks live only in `SearchV3View.coldArrival.test.ts`; all existing test files,
including the earlier two regression files, remain byte-identical to b831201474.

On b831201474, raw restoration marks the composer as needing review with no preparation
(`SearchV3View.ts:1644`). During incoming intake, `openRecovery` also retains the matching
saved preparation (`:1284`). `persistDraft` combines the incoming snapshot, raw snapshot
and saved preparation (`:1127–1133`). `isSv3Recovery` rejects the three drafts at
`sv3-recovery.ts:55` (the limit is two), despite only one earlier unsent text.
`navigationHandler.ts:211` clears the hand-off state before acceptance and
`URLProjector.ts:287` replaces the address; `takeSearchV3Handoff` also consumed it
before persistence at `SearchV3View.ts:3983`. The canonical URL reproduces the
same component failure. Real-browser pairing and final command results follow below.

The repair retains the matching saved proposal as the outgoing recovery copy only
when cold-restored raw text and place match. This is retention, never authority to
apply old settings or send. Different text or place still needs a separate slot.
Text-only legacy copies are normalized to an explicit null preparation when written
in the existing version-2 payload. No validator, slot limit, byte limit or format
version is changed. The existing transient hand-off is consumed after safe intake;
a refused hand-off stays available to preserve the address, marked refused so remount
cannot replay it. Accepted intake explicitly refreshes URL projection.


S67 file-by-file changes:

- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts`: retain one matching cold raw/saved proposal rather than two copies when intake reserves its outgoing work; normalize legacy preparation absence to null on existing payload writes. Leave different text/place separate. Consume the transient task only after a successful acknowledgement and refresh the accepted address. Mark refused tasks against remount replay. Name draft-count, UTF-8 text, chosen-file count/path and Structured-schema validation limits in the refusal instead of reporting one generic limit.
- `modules/ui-web/src/shell-v0/utils/compose.ts`: allow the existing transient hand-off register to be inspected before acceptance and mark refusal there. No second register or durable format.
- `modules/ui-web/src/shell-v0/router/navigationHandler.ts`: keep the original input state in a pushed address while fresh intake is pending/refused; state distribution still gets the existing empty hand-off snapshot.
- `modules/ui-web/src/shell-v0/router/URLProjector.ts`: don't overwrite a pending/refused incoming address on initial activation or subsequent store refresh. Accepted intake explicitly replaces it with the current durable navigation projection.
- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts`: 12 new checks; four cold arrivals, two genuine-capacity URL refusals, text/file-count/file-path byte refusals, and three preservation checks (both old text-only recovery paths and distinct ownership). The canonical arrival uses only query/docIds, with no mode override. No new test changes any existing test file.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json`: unchanged official evidence producer; all nine checks classify as assertion failures on b831201474 and pass on the working tree.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json`: official producer, all three checks pass; the direct paired baseline run also passes all three.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-browser.json`: durable paired native Engine/Chromium observations for four before/after cases, harness source, exact text/URLs, recovery payloads, zero-work counts, source hashes and cleanup.
- This changeset adds the S67 contracts, ids, sources and evidence. The original two evidence files and original 37 regression checks remain unchanged.

S67 component evidence, using the exact final new test file on both revisions:

| Scenario | b831201474 | Working tree |
| --- | --- | --- |
| Cold Ask, canonical Search, FreeChat, Extract: exact text/mode/files; previous draft reachable by review; no automatic dispatch | 4 assertion failures: old text retained instead of incoming | 4 pass |
| Genuine capacity refusal via Ask/canonical: incoming URL survives; prior raw and typed work kept; reason names two-draft capacity | 2 assertion failures: incoming query absent from address | 2 pass |
| UTF-8 text, file-count and path-byte refusal: incoming URL survives; actual limit named | 3 assertion failures: incoming query absent from address | 3 pass |
| Old text-only recovery with and without incoming task; explicit review required | 2 pass | 2 pass |
| Same raw text at a different place remains a distinct owner and refuses a third snapshot | 1 pass | 1 pass |

The diagnostic baseline probe captured the actual rejected JSON: incoming text/chosen scope,
old raw text with null preparation, and old identical text with decoy scope (all place=null).
It is the drafts.length > 2 branch at `sv3-recovery.ts:55`; no text/file/schema bound is exceeded.
Only the private baseline clone's temporary test probe was instrumented; production code and
all final checked-in test source are uninstrumented. Direct baseline results: 3 pass / 9 fail;
working-tree focused results: 12 pass. The unchanged hand-off tests also passed (62 cases).

Final S67 command results observed before browser execution:

- `.agents/preflight`: absent.
- Plain own `modules/ui-web` `npm ci`: source and baseline shared clone each exit 0, 283 packages each. The producer's two temporary baseline shared clones also ran their own plain npm ci, 283 packages each. No shared or linked node_modules.
- `npm run typecheck`: exit 0.
- `npm run lint`: exit 0, no warnings.
- `npm run test:unit:run -- --configLoader runner --maxWorkers=4`: exit 0, 644 files / 8,226 tests passed, no skips.
- `npm run test:unit:run -- src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private result>`: baseline exit 1 with 9 intended assertion failures and 3 passes; working tree exit 0, all 12 pass. The last full-suite run also includes the final 12 checks.
- `node scripts/ci/run-ui-web-gates.mjs`: exit 0, 28/28 passed.
- Official `run-evidence.mjs --out gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json --before b831201474 --check <each of nine ids>`: exit 0. Preservation producer `--out gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json --check <each of three ids>`: exit 0. Shared-clone adapter/private object directory as documented earlier; the producer and runner code are unchanged.
- `node scripts/governance/gates/test-intent/cli.mjs check --base f17b988e2f`: exit 1, exactly one problem, this package's missing independent acceptance. No evidence-invalid or stale digest problems. Non-failing finding: reconsider rate 2/19 audited PRs (10.5%) exceeds one in ten over the last 28 days; independent acceptance remains the orchestrator's responsibility.
- `git diff --check`: exit 0. All existing test files and `sv3-recovery.ts` byte-identical to b831201474; limits and accepted recovery formats unchanged.

Real native Engine/headless Chromium pairing completed. Durable compact observations,
exact URLs/texts, recovery snapshots, zero-work counts, harness source, build results,
private-service cleanup and candidate production/test SHA-256 hashes are in
[`uiux-compat-cold-arrival-browser.json`](../evidence/uiux-compat-cold-arrival-browser.json).
Only draft recovery payloads are archived; API/session authentication tokens are excluded.
Screenshots, full console/traffic captures and logs were also kept in the session's
private temporary folder, which the launcher removes at session end.

| Real browser scenario | b831201474 | Working tree |
| --- | --- | --- |
| S67 compatibility Ask full document load after one unsent draft | FAIL: old text, chosen new file, false limit, stripped URL | PASS: exact incoming text/files, old preparation reviewable, incoming still retained after old review, no false refusal |
| S67 canonical Search query/docIds full document load after one unsent draft | Same failure | Same pass |
| Genuine capacity refusal after two distinct preparations, incoming Ask | FAIL: incoming query stripped; generic reason | PASS: current B kept, incoming C stays in address, actual two-draft capacity named |
| Genuine capacity refusal after two distinct preparations, incoming canonical Search | Same failure | Same pass |

All eight browser cases made zero automatic chat/model work requests and had zero
page errors. Each case used a newly launched short `/tmp/jsv-*` Chromium profile/TMPDIR;
all eight profiles were removed. Chat was unconfigured in fresh private Engine HOME/data,
with AI explicitly disabled; no chat model was needed or loaded. The private fixture corpus root
was admitted through the actual Engine API. The UI was each corresponding checkout's
source through its own Vite loopback service; the Engine was each tree's installed binary.
No other session's Engine, frontend, app, display, desktop or user data was used.

Commands and observed browser/build results:

- In the baseline shared clone, `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:ui:installDist`: exit 0, 88 tasks (57 executed, 31 from cache); 1,158 seconds queued, 17 seconds building.
- In the source checkout, the same exact Gradle command: exit 0, 88 tasks (57 executed, 31 from cache); 2,112 seconds queued, 16 seconds building. Neither node_modules directory was a symlink; no shared Engine JVM lock, init script, --stop or --no-daemon was used.
- `node cold-case.mjs` using the supplied verifier's case/helper with a canonical loop and old-draft review assertions: baseline exit 1, two intended assertion failures; candidate exit 0, two passes.
- `node cold-refusal.mjs`: baseline exit 1, two intended address/refusal-reason assertion failures; candidate exit 0, two passes. It prepares A and B through real navigation, then fully loads incoming C.
- Own `browser-pair.py` campaign: exit 0 after checking both runtime cleanups. It ran one private Engine at a time, directly through `modules/ui/build/install/ui/bin/ui`, with private HOME/data/index/corpus, ephemeral loopback ports, no shared JVM lock, and matching frontend. Both manifests reported worker ready. Both private Engine profiles and both Vite processes were cleaned up by recorded process identity.
- Chromium sandbox probe: launch failed with “No usable sandbox”; it tested no product scenario and its short profile was removed. The permitted --no-sandbox fallback was then used only for the own-loopback browser cases; every result records sandbox=false.

All task work remains uncommitted. No pushes, publishing, GitHub issues or independent
acceptance were performed. Shared resources touched: the required Gradle slot queue/cache,
npm's ordinary cache, the existing Chromium executable and machine CPU. CUDA was disabled
for all checks; inherited JUSTSEARCH_* and JVM option variables were stripped, and
GRADLE_OPTS retained. No custom logs were written into /tmp/js-linux-port.

The only required check not satisfied by this builder is independent test-intent acceptance,
which is reserved for the separate verifier/acceptor. The final gate still reports that one
expected problem and the non-failing 2/19 reconsider-rate finding. No other work is left open.
