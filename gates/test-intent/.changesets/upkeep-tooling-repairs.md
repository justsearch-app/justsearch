---
schema: test-intent.v1
task: upkeep-tooling-repairs
author-role: builder
author-session: upkeep-tooling-repairs-builder
---

# Test-intent entries

The added CI step runs four script self-tests with Node's test runner. Product test
selections are unchanged. Script self-tests are outside the gate's product-test scope
(tempdoc 966 D1). No acceptance is recorded.

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml"
      ],
      "change": "modified",
      "kind": "watched-build-config",
      "class": "Structural rule kept",
      "rule": "Every product test selection in ci.yml is unchanged.",
      "whySurvives": "The change only adds one step that runs four script self-tests under scripts/ (out of the gate's product-test scope), so no product check is removed, narrowed or reselected."
    }
  ]
}
```

## Acceptance records
