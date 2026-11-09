---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5-continuation
---

# First launch and Getting started (S5)

Search derives first use from authoritative watched roots and live searchable counts. The shared
Library add flow is available in Search, and plugin tours appear in Getting started. The new card
also renders in Search's content scroller after results, so Help reopening and pending plugin
walkthroughs remain available with a docked composer. The shell's old hidden walkthrough element
retains the single event dispatcher; the embedded section renders its saved progress.

Persisted format: the existing cross-profile `justsearch.userState.v2.walkthroughState` record,
under `core.getting-started`, with `{activeStepIndex: 0, completedStepIds, dismissed}`. The marks are
`try-search` and `reopened-from-help`. No new key or legacy welcome migration is introduced.
A pending, non-dismissed plugin tour shows a plugin-only card after core completion.

## Test inventory

All added checks are durable. Temporary shared clones and executable adapters are scaffolding,
kept outside the repository. Every check in each new test file was rerun for the final file bytes.

| Added file (under `modules/ui-web/src/shell-v0/`) | Scenarios |
| --- | --- |
| `state/firstUse.test.ts` | SB1–SB11 projection and legacy matrix |
| `components/GettingStarted.test.ts` | SB3–SB6, SB12 interactions |
| `state/firstUse.persistence.test.ts` | SB10, SB11, SB14 shared saves |
| `state/firstUse.pill.test.ts` | SB9 precedence |
| `state/firstUse.observations.test.ts` | SB2, SB4, SB6 freshness/wire validation |
| `state/firstUse.reset.test.ts` | SB10 actual reset storage gate |
| `components/GettingStarted.plugins.test.ts` | SB12 retained completion triggers |
| `chrome/FirstUse.app-lockdown.test.ts` | SB2, SB3, SB5, SB6, SB10, SB13, SB14 real Chromium |
| `substrates/manifest/walkthroughContributions.test.ts` | SB12 retained manifest lifecycle |

## Old → new mapping

- Core welcome manifest registration/provenance and uninstall checks → the sample contribution's
  corresponding registration/provenance and uninstall checks. The obsolete installer and its
  idempotence guard are deleted. The replacement duplicate-manifest check asserts the unchanged
  manifest API refusal, rather than copying the retired wrapper's no-op contract.
- `test_walkthrough_seed_dismisses_welcome` → `test_walkthrough_seed_hides_getting_started` in the
  harness. SB14 requires established captures to suppress the new card. The old welcome seed is
  removed; the new first-use variants seed no walkthrough progress, pinned by an added check.
- The existing semantic-pause fixture freshness check additionally requires a positive LIVE
  searchable count. The nine-step CI journey initially failed because its fixture said one indexed
  file but zero searchable files; S5 correctly rendered the empty state. The fixture now supplies
  one for both counts, preserving the existing readable-keyword-index scenario and all its UI
  assertions. No existing assertion was removed or weakened.

## Evidence and checks

`run-evidence.mjs` produced the four attached records without editing the producer. Temporary
command adapters append `--maxWorkers=4` to Vitest and replace its registered before-worktree with
an owned shared clone under the assigned temporary directory. Dependencies are copied into each
clone, with separate build/cache folders. No stash, source checkout reset, commit, push, fetch or
rebase was used.

Repaired standard checks fail on e3a479db7d with assertions and pass on the candidate.
The 15 repaired Chromium cases do likewise. Navigation preservation, the three plugin trigger
cases, the sample manifest lifecycle and the 29 Library add cases pass before and after.
The added fixture checks also failed against the baseline's old seed/status and passed after.

## Acceptance results (continuation)

The orchestrator authorized the bounded backend change: suggested folders are read-only and
fetched automatically for the first-use view. `GET /api/indexing/suggested-roots` is now
`OBSERVATION`, beside `GET /api/indexing/roots`, with a consumer justification. Only this route's
activity expectations changed in the three existing Java activity tests; every other route's
expectation stays as before. The existing independent declaration helper also pins this route's
`OBSERVATION` role with one assertion.

The retained `src/activity/idleSurfaces.test.ts` was not edited. Before the route change it reported
**3 passed, 1 failed**, with an assertion containing
`GET /api/indexing/suggested-roots: USER_ACTION`; after the change it reported **4 passed**.

The two unchanged 50 ms visible-Search readiness cases were run individually, serially, five
times each on shared-clone baseline `e3a479db7d` and five times each on the candidate. All **20
runs passed**, each printing `1 passed, 1 warning` and exiting 0. Per-round baseline/candidate
results, in current-Search then legacy-Search order:

| Round | Baseline | Candidate |
| --- | --- | --- |
| 1 | pass / pass | pass / pass |
| 2 | pass / pass | pass / pass |
| 3 | pass / pass | pass / pass |
| 4 | pass / pass | pass / pass |
| 5 | pass / pass | pass / pass |

No other owned checks ran during this comparison. It does not identify a defect caused by S5;
no readiness test, predicate or timeout was edited. The temporary Python 3.13 venv uses
Playwright 1.61.0 and existing installed Chromium. The initial venv setup selected Playwright
1.65.0, whose requested browser was absent; those launch errors are infrastructure failures,
excluded from the readiness comparison. No browser was installed.

The all-green outcome is **blocked** by two newly observed failures outside the bounded assignment.
`js-gradle-slot ./gradlew :modules:ui:test :modules:ui:pmdMain :modules:ui:pmdTest
:modules:ui:spotlessCheck -PskipWebBuild=true --console=plain` exited 1 after 13m 21s:

```text
NvidiaSmiAvailabilityHttpTest > anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory() FAILED
    org.opentest4j.AssertionFailedError at NvidiaSmiAvailabilityHttpTest.java:83
2487 tests completed, 1 failed, 2 skipped
BUILD FAILED
```

The assertion message was: `the installed tool is available: {"cudaAvailable":true,"totalVramBytes":12884901888,"vramDescription":"12.0 GB","vramDetectionSource":"nvml","nvidiaSmiAvailable":false,"nvmlAvailable":true,"nvmlTotalVramBytes":12884901888,"nvmlDriverVersion":"591.59","cudaVersion":"12"} ==> expected: <true> but was: <false>`.
This test registers an inference status handler directly and tests installed NVIDIA-tool
availability; it does not use the indexing route. The test and GPU code are unchanged. The
failure has not been reproduced on the baseline or diagnosed, and no repair outside the
assignment was attempted. The full module report was copied before filtered evidence runs
could replace it; its failure output is retained here because the temporary folder is removed.

Final continuation checks:

- `npm run typecheck` and `npm run lint` from `modules/ui-web`: both exit 0.
- Full `npx vitest run --maxWorkers=4`, once in this continuation: exit 0,
  **9,386 passed in 741 files**, 179.09s.
- `js-gradle-slot ./gradlew :modules:ui:pmdMain :modules:ui:pmdTest
  :modules:ui:spotlessCheck -PskipWebBuild=true --console=plain`: exit 0,
  `BUILD SUCCESSFUL in 12s`. PMD and Spotless tasks were up to date against the final source.
- Fresh `shell-s5-route-activity.json` uses the unmodified `run-evidence.mjs` inside
  `js-gradle-slot`; its inner Gradle run inherits the held slot. All **58 cases** pass on the
  final test bytes: DeclaredRequestActivityTest 4, IndependentDeclaredActivityTest 28,
  VerifierRoundFourActivityTest 26. Evidence generation exits 0.
- `python -m pytest scripts/jsharness/tests -q` in the isolated Python 3.13 venv:
  exit 1, **472 passed, 1 failed, 1 skipped**, one existing pytest deprecation warning,
  49.49s. Both 50 ms visible-Search readiness cases pass in this full run. The failure is:

```text
FAILED scripts/jsharness/tests/test_ui_serve.py::test_start_vite_server_never_adopts_foreign_listener[False-0.7]
jsharness.ui_shot.ServeStartError: Vite exited with code 1 on :43536; no stderr captured
scripts/jsharness/jsharness/ui_shot.py:487: ServeStartError
```

The failing harness test and its `ui_shot.py` implementation are unchanged. It substitutes a
private Python strict-port server and checks refusal to adopt a foreign listener. This failure
has not been baseline-reproduced or diagnosed; no test, timeout, server code or environment
assumption was changed to bypass it. The full output was read. Per the builder contract, these
new failures are returned to the orchestrator rather than repaired outside the assignment.

Independent acceptance remains pending. The test-intent advisory observed in this continuation
is **12 of 48 audited PRs (25%)** with reviewer disagreement or tie-break; the orchestrator must
include it as a delivery finding. No `.agents/preflight` exists in this checkout.

Earlier checks on the unchanged first-launch UI files remain applicable:

- UI-web gates: 31/31 pass. Knip report generated (the wrapped knip process exited 1) and
  dead-code gate: pass (the gate reported seven findings). Component-vocabulary freshness: pass.
- Targeted standard tests: 71 passed, including 29 retained Library add cases. The new browser
  file: 16 passed. Existing evidence covers those test-file bytes, including assertion failures
  on the shared baseline clone and preservation passes there.
- Measured axe: all 112 registered surfaces passed, zero new or serious/critical violations.
  The two fresh captures have zero axe violations, zero real console errors and no horizontal
  overflow. Their proportion rows pass the existing evaluator. Captures and the proportion
  report are under ignored `tmp/s5-first-use/`.
- The exact nine CI fixture journeys passed after the semantic fixture's live-count correction.
  No journey assertion was weakened.

Shared resources: used the shared Gradle slots/cache and read the installed Chromium cache and
the worktree's installed Node dependencies;
checks used private browser contexts, ports, caches, copied baseline dependencies and build folders.
No shared dev stack or owner application was used. The full Java module suite exercised the
installed NVIDIA-tool availability check above; no inference workload was started. Only owned processes were closed. Python
3.13 and Playwright 1.61.0 were installed under the assigned temporary directory; no browser install,
cache clearing, commit, push, fetch or rebase occurred. The suggested-roots classification is corrected; the serial readiness comparison passes on
both baseline and candidate.

The malformed-wire rejection test uses an explicit assertion on the caught rejection because
Vitest formats a resolved `.rejects.toThrow()` as a plain Error, which the evidence classifier
cannot recognise as an assertion failure. The rejection contract is unchanged.


```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "New",
      "behaviour": "SB1\u2013SB11: unknown/zero/positive facts, queue wording, empty watched roots, ad-hoc content, optional AI, every legacy welcome shape, Hide/reload/Help, profile and reset projection.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Steps come from facts: step 1 is done when a folder is watched or documents are searchable; step 3 when AI is active; step 2 when a search returned while documents were searchable (a search with nothing searchable does not count). Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "People who finished or dismissed the old welcome do not see Getting started, unless they have no folders (C5). Partial old progress does not count as any new step being done. Old records are left in place unmodified; absent, partial, finished, dismissed and malformed records are all handled against both empty and populated engines."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Existing content without watched folders (for example ad-hoc ingest): searchable documents make Search usable, with no demand for another folder."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "A watched folder yielding nothing (empty, excluded, unsupported, or failed): the card stays truthful, step 1 is not presented as success for search, and it points to Library."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.test.ts"
      ],
      "class": "New",
      "behaviour": "SB3\u2013SB6/SB12: shared add operation, desktop cancel, retry and focus after leaving, suggestion refusals/deduplication/absence, count-qualified search completion, remount during indexing and a pending plugin-only card.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Add a folder uses the existing add flow: native picker on desktop, manual entry in the browser. Cancelling changes nothing and returns focus to the button. An invalid path, refusal or failure says so and can be retried."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Suggested folders from the suggested-roots API show name and path, no file counts. Choosing one adds only that folder, once, through the same operation, even if clicked repeatedly."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Steps come from facts: step 1 is done when a folder is watched or documents are searchable; step 3 when AI is active; step 2 when a search returned while documents were searchable (a search with nothing searchable does not count). Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "When the core steps are finished and a plugin walkthrough is pending and not dismissed, the card shows with only that plugin's section; today's behaviour shows a pending contributed walkthrough without being asked, and SB12 keeps its behaviour."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.persistence.test.ts"
      ],
      "class": "New",
      "behaviour": "SB10/SB11/SB14: actual shared UserStateDocument instances adopt Hide/Help saves, preserve unrelated values, and viewing malformed legacy storage does not rewrite it.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "People who finished or dismissed the old welcome do not see Getting started, unless they have no folders (C5). Partial old progress does not count as any new step being done. Old records are left in place unmodified; absent, partial, finished, dismissed and malformed records are all handled against both empty and populated engines."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Restored navigation and explicit URLs win: Getting started never replaces a saved destination or adds a Back/Forward entry. Co-resident saved values survive."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.persistence.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "class": "New",
      "behaviour": "SB9: setup replaces only calm; unreachable, limited, needs, problems, working and finished statuses keep precedence.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The pill shows \"Getting started \u00b7 n of 3\" only where it would otherwise be calm; every higher status (unreachable, limited, needs you, problems, working) still wins."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts"
      ],
      "class": "New",
      "behaviour": "SB2/SB4/SB6: unavailable/unknown/stale and aged observations never become empty or progress claims; malformed suggested-root wire items are rejected.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Suggested folders from the suggested-roots API show name and path, no file counts. Choosing one adds only that folder, once, through the same operation, even if clicked repeatedly."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "api/domains/indexing.ts:70-88 getSuggestedRoots has no UI caller today and does not validate items: validate the wire shape."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reset.test.ts"
      ],
      "class": "New",
      "behaviour": "SB10: the real pre-boot reset storage gate removes Hide and Help state before a fresh launch.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reset.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.plugins.test.ts"
      ],
      "class": "New",
      "behaviour": "SB12 preservation: command, setting and extension-install events still complete plugin walkthrough steps and retain their saved progress on reload.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.plugins.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/manifest/walkthroughContributions.test.ts"
      ],
      "class": "New",
      "behaviour": "SB12 preservation: contributed manifest registration retains provenance/source/steps, duplicate manifests are refused without a duplicate tour, and uninstall removes the contribution.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Write these tests first and show each failing before your change for the reason under test:",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Retire the core welcome contribution while keeping plugin walkthrough installation and removal behaviour.",
          "choice": "Use a sample contributed manifest for lifecycle coverage after retiring the core welcome installer. Its duplicate-install rejection is the unchanged manifest API contract; the deleted core wrapper's no-op guard is retired with that wrapper.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/manifest/walkthroughContributions.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB2/SB3/SB5/SB6/SB10/SB13: real Shell/Search in private Chromium checks facts, focus, cross-window storage, no-AI search and Help after results, reload during indexing, pending-add navigation, three viewport sizes and three palettes at 200% text.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Add a folder uses the existing add flow: native picker on desktop, manual entry in the browser. Cancelling changes nothing and returns focus to the button. An invalid path, refusal or failure says so and can be retried."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Steps come from facts: step 1 is done when a folder is watched or documents are searchable; step 3 when AI is active; step 2 when a search returned while documents were searchable (a search with nothing searchable does not count). Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Dark, light, forced colours; 1280x800, 900x800, 600x400 and enlarged text: nothing overflows sideways, actions stay reachable, keyboard order and focus after add or failure are right, and status changes are announced politely."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Write these tests first and show each failing before your change for the reason under test:",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Verify the requested first-use interactions, storage boundaries, navigation, accessibility and responsive cases in real Chromium.",
          "choice": "Drive the real Shell and Search using owned Vite/Chromium processes and controlled API/platform responses, without a shared dev stack. All browser checks are durable.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-browser-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#unknown \u2192 zero \u2192 positive changes the hero only with confirmed facts and preserves the ordinary composer",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#browser cancel and add failure restore keyboard focus; duplicate submission stays single",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#Hide, a real second window, reload, Help reopen and profile switch share the same progress",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#keyword search needs no AI and Help reopening stays visible after a returned search",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#reloading during indexing rediscovers the folder and live count before offering search",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#leaving and returning during an add keeps failure actionable and focuses the new path field",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, forced, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, forced, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, forced, enlarged text keeps setup actions reachable without horizontal overflow"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB14 preservation: an explicit Library URL and a subsequent journal restore retain their destination, journal and history when first-use facts arrive.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Restored navigation and explicit URLs win: Getting started never replaces a saved destination or adds a Back/Forward entry. Co-resident saved values survive."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-browser-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#explicit addresses and the saved journal win without an onboarding history entry"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/manifest/canonicalManifest.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/substrates/manifest/canonicalManifest.ts"
          ]
        }
      ],
      "conflictSearch": "Searched repository walkthrough/manifest references and the supplied SB12/design. The core welcome installer is explicitly retired; the registry and public plugin contribution API are retained."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged. The literal production declaration is also required to be OBSERVATION.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts",
        "modules/ui-web/src/shell-v0/components/GettingStarted.reading-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB6: live INDEXING with an absent or zero queue reports still reading, in the shared projection and the production card in headless Chromium.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "observed queue greater than zero gives \"m waiting to be read\"; work under way with the queue absent, or zero while a scan is still running, gives \"still reading\"; paused or idle with no work gives neither; stale or unknown gives nothing."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-reading-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts#reports still reading during INDEXING with queue undefined",
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts#reports still reading during INDEXING with queue 0",
        "modules/ui-web/src/shell-v0/components/GettingStarted.reading-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts"
      ],
      "class": "New",
      "behaviour": "SB6 preservation: a positive observed queue reports waiting files, idle/paused without work adds neither suffix, stale/unknown/unavailable and expired observations show no progress. Pausing does not erase an observed positive queue.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "observed queue greater than zero gives \"m waiting to be read\"; work under way with the queue absent, or zero while a scan is still running, gives \"still reading\"; paused or idle with no work gives neither; stale or unknown gives nothing."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-reading-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance is pending.
