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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-j6bl87as",
  "verdict": "accept",
  "digest": "sha256:4e24bfd79180d980d53145027cb487d9bb05bca651266a5d646250ded243169d",
  "note": "ci.yml diff is +8/-0: one new step running four script self-tests; every product test selection unchanged; Structural rule kept holds"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-67ng90bp",
  "verdict": "accept",
  "digest": "sha256:c21f1d5716f6cb5a9b2fc51f7350498511b6cdf8b75633213fbc00fabed1f604",
  "note": "after merge of main a533f0cf0, ci.yml diff is still +8/-0: one new public-claims step running four script self-tests under scripts/; no product test selection changed; Structural rule kept holds"
}
```
