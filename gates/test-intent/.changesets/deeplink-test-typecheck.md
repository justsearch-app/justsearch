---
schema: test-intent.v1
task: deeplink-test-typecheck
author-role: orchestrator
author-session: ebe5e60b-7280-4e63-946c-6ac9d428c78a
---

# Test-intent entries

The deep-link test added in #986 indexes `mock.calls[0]` without a non-null assertion, which the ui-web typecheck rejects under `noUncheckedIndexedAccess`. Only the types change.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/router/sources/TauriDeepLinkSource.desktop.test.ts"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "Four reads of `mock.calls[0]` gain a TypeScript non-null assertion (`calls[0]!`) so the file typechecks under noUncheckedIndexedAccess. Inputs, mocks, observed calls and every expected value are unchanged; the emitted JavaScript is identical."
    }
  ]
}
```

## Acceptance records
