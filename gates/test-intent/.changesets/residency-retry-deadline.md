---
schema: test-intent.v1
task: residency-retry-flake
author-role: builder
author-session: residency-retry-builder-20261111
---

# Retry deadline wake-up

The domain previously requested one render when its timer woke. If that happened
before the Date.now deadline, the render kept Retry disabled with no future
wake-up. It now re-arms for the remaining interval until a render can enable
Retry. Only the human click dispatches. No stored data or authority changes.

The deterministic window check fails by assertion on baseline
5cc78ebcb40ed0bbc24dbdd31cf09a73e5660d50 and passes with the product repair.
The ordinary on-time case passes on both. run-evidence.mjs ran from an isolated
shared candidate clone with these two changed files overlaid. Its temporary
before worktree belongs to that clone; --copy-node-modules gives each tree
private dependencies. The source checkout was neither committed nor reverted.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The ordinary Retry case uses a scoped fake timer and controlled Date.now instead of a real-time vi.waitFor containing settle timers. Mounting still uses real time. The same real Search window, composer Send event, 503 RELOAD_BACKOFF refusal with retryAfterMs=80, disabled/enabled button observations, extra 100 ms without dispatch, no load-anyway calls, and one explicit Retry dispatch remain. An added assertion also checks that Retry is disabled at 79 ms. The helper removes its element and restores timers and Date.now in finally. No test timeout, retry count, or other case changes.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "after a RELOAD_BACKOFF refusal with an 80 ms interval, Retry starts disabled, becomes enabled after the interval, the timer itself never submits, and clicking Retry submits once.",
          "location": "task residency-retry-flake request"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-words",
          "quote": "after a RELOAD_BACKOFF refusal with an 80 ms interval, Retry starts disabled, becomes enabled after the interval, the timer itself never submits, and clicking Retry submits once.",
          "location": "task residency-retry-flake request"
        },
        "reproduction": "Control elapsed timers separately from Date.now. Deliver the 80 ms wake-up at wall-clock +79 ms, then move the wall clock to +80 ms and advance the remaining millisecond. With the original single render timer, the real Search window Retry button remains disabled (expected true to be false); the ordinary on-time case passes. A native Node probe also observed 2 of 200 real 80 ms timers arriving 1 ms before their Date.now deadline."
      },
      "reproduction": "Control elapsed timers separately from Date.now. Deliver the 80 ms wake-up at wall-clock +79 ms, then move the wall clock to +80 ms and advance the remaining millisecond. With the original single render timer, the real Search window Retry button remains disabled (expected true to be false); the ordinary on-time case passes. A native Node probe also observed 2 of 200 real 80 ms timers arriving 1 ms before their Date.now deadline.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "after a RELOAD_BACKOFF refusal with an 80 ms interval, Retry starts disabled, becomes enabled after the interval, the timer itself never submits, and clicking Retry submits once.",
          "location": "task residency-retry-flake request"
        }
      ],
      "evidence": "gates/test-intent/evidence/residency-retry-deadline.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#Retry still becomes available when its timer wakes before the wall-clock deadline"
      ]
    }
  ]
}
```

Independent acceptance is intentionally pending.

## Builder validation

- Before state: a shared clone in the assigned temporary directory, detached at
  5cc78ebcb40ed0bbc24dbdd31cf09a73e5660d50, with a private copy of ui-web
  dependencies. No stashing, reverting, fetching or source-repository commits.
- Native clock probe: 200 successive `setTimeout(resolve, 80)` calls compared
  against `Date.now() + 80`; two woke 1 ms early (167 at 0 ms, 31 at +1 ms).
- Natural baseline run: the complete residency file 30 times in a row, 127 cases
  each, 3,810 passes, no skips or failures. Slowest Retry 264.618 ms; slowest
  case anywhere in the file 302.610 ms. The natural CI timeout was not observed.
- Fixed run: the complete residency file 30 times in a row, 128 cases each,
  3,840 passes, no skips or failures. Slowest ordinary Retry 79.013 ms;
  early-wake regression 88.770 ms. Slowest case anywhere in the file 663.034 ms
  (automatic indexing continuation across repeated polls and windows).
- Both repeated runs invoked `npx --no-install vitest run
  src/shell-v0/views/search-v3/SearchV3View.residency.test.ts --maxWorkers=4`
  from ui-web, adding only the JSON reporter and its private output filename.
  Each run series owned two Python CPU spin workers. The test process and load
  workers shared a four-CPU affinity (0-3 before, 4-7 after). Both sets of load
  workers were terminated and waited for by their owning harness.
- `.github/workflows/ci.yml` Frontend unit tests command on the baseline:
  `npm run test:unit:run --prefix modules/ui-web -- --maxWorkers=4`, adding only
  JSON output options. Default ordering and pool; 758 files / 9,690 tests passed,
  zero skips or failures, in 241.068 seconds. Two private CPU load workers shared
  CPUs 0-3 with the tests and were stopped. No retry or selection override.
- `npx --no-install vitest run src/shell-v0/views/search-v3 --maxWorkers=4`:
  2,100 tests passed, none skipped or failed.
- `npm run typecheck` and `npm run lint`: both exit 0.
- `.agents/preflight` does not exist. Gitleaks cannot run in this sandbox.
- Sandbox Node is 24.22.0; CI pins 24.14.0. All checks use the installed
  dependencies. No shared dev stack, owner application, GPU or dependency tree
  belonging to another checkout was used. CI-command runs have private cloned
  dependency trees, and the repetition runs use their own checkout's dependencies.
- Raw logs and JSON reports are under the assigned temporary folder's
  `residency-evidence/`; the assertion fail-before and candidate pass are retained
  in the adjacent script-produced D4 JSON evidence.
- The test-intent gate reports only missing independent acceptance. Its separate
  advisory reports reviewer reconsideration in 13 of 51 audited PRs (25.5%);
  the orchestrator must report that existing advisory in its delivery.

- The same frontend CI command on the fixed candidate, in its private shared
  clone with the exact source/test overlays, also passed: 758 files / 9,691 tests,
  no skips or failures, in 238.152 seconds. Ordinary Retry took 56.642 ms.
  This run likewise used two private CPU load workers on CPUs 0-3, now stopped
  and waited for. All four validation load groups have completed cleanup.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "iqbehwu5-residency-verifier",
  "verdict": "accept",
  "digest": "sha256:1b69472e81ab7c78dcad11cc9a4397660b793b4d9b4b67fd9df552e194d38efa"
}
```
