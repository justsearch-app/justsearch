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
