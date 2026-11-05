---
schema: test-intent.v1
task: t-20261105-bandfocus
author-role: builder
author-session: band-focus-flake-builder
---

# Test-intent entries

Synchronize the final focus observation with the asynchronous action's completion. Closing the
band starts the invocation; it does not mean the opener can take focus yet. A controlled Chromium
probe held the invocation response until the original focus poll failed: the opener was disabled
and the action pending. Releasing that response enabled and focused the same opener on main.
The production focus handling is unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bandFocus-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "After confirming the failed-file action, wait for the surviving native opener to match :enabled before running the existing focus poll. ActionButton closes its band when invocation starts, keeps the opener disabled while RowActions awaits the invocation, and restores focus when pending clears. Band disappearance therefore precedes the condition under test. All keyboard input, refresh/error injections, identity assertions, cancellation checks, band-removal assertions, and the exact final focus assertion and its timeout remain unchanged. No fixed delay, retry, skip, or production change is added."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1ac85-dbe7-7762-b6d0-fd5236dca204",
  "verdict": "accept",
  "digest": "sha256:a075bcf9fe353477a1b5058412a1d61c5f7f64d951bb2c1a73d6343464a2c758",
  "note": "Adaptation (instrumentation): the added wait observes the real enabled state with a 15 s bound; slow responses of 1.8 s and 5 s and a failing removal after 5 s pass, 20 runs of the file pass, disabling focus restoration still fails the unchanged final assertion. Earlier acceptance 01a1ac80-33b5-7f02-be38-3f497cbab253 covered the version with the inherited 3 s bound. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
