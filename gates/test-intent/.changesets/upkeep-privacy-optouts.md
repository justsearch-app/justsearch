---
schema: test-intent.v1
task: upkeep-privacy-optouts
author-role: builder
author-session: upkeep-privacy-optouts-builder
---

# Test-intent entries

The entries below cite the owner's privacy invariant in NON-GOALS.md:8 and the scope
of task t-20261019-9d870e. Only the added privacy expectation in the existing frontend
test file changes; its recovery expectations remain intact.

Both D4 evidence files below were written by run-evidence.mjs against c00a0cb24. The named
frontend C2 check passed on the candidate and failed an assertion on the before-state. The
Java C1 case with both properties absent executed and passed on the candidate; the before-state
failed an assertion because OPT_OUT_TRACKING remained unset. The documented whole-class Java
selector includes the parameterized case (its XML name is a display name). Both repaired checks
have valid fail-before evidence.

Runner logs: tmp/privacy-evidence/followup6-ui-evidence.log and
followup7-java-class-evidence.log. The frontend used the runner's default dependency link;
Java ran behind the shared Gradle lock. The runner removed both temporary before-worktrees.
Independent acceptance is left to the acceptor; no builder acceptance record is written.

Existing C1 observations (not substitutes for D4):
- tmp/privacy-evidence/before-tokenizer-output.log:1-5 and before-tokenizer-connect.strace:8-11:
  absent defaults, successful tokenization, assertion failure, four EC2 metadata connects.
- tmp/privacy-evidence/after-tokenizer-output.log:1-3 and after-tokenizer-connect.strace:
  true defaults, identical token IDs, no IPv4/IPv6 connects. See before/after-tokenizer-summary.json
  and before-after-comparison.json for machine-readable results.
- tmp/privacy-evidence/after-gradle-test-summary.json: historical four-case entry test passed;
  the superseding D4 evidence retains only the case where both properties are absent.

Existing C2 observations (not substitutes for D4):
- tmp/privacy-evidence/before-font-assertion.log: assertion failure on the forbidden hostname.
- tmp/privacy-evidence/after-font-assertion.log: the same assertion passed.
- tmp/privacy-evidence/after-vitest-entry.log: the named new check and retained recovery check passed.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecoveryEntry.test.js"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "behaviour": "C2: the raw app entry HTML contains neither fonts.googleapis.com nor fonts.gstatic.com (case insensitive), preventing its Google Fonts stylesheet and preconnect links from returning. The existing desktop recovery/binding assertions are retained unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "**No telemetry, analytics, or phone-home.** The product does not report usage anywhere.",
          "location": "NON-GOALS.md:8"
        },
        {
          "kind": "owner-words",
          "quote": "nothing on the machine or network is exposed by accident",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-privacy-optouts-ui.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecoveryEntry.test.js#the app entry has no automatic Google Fonts contacts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "behaviour": "C1 entry guard: initializing actual HeadlessApp in a fresh child JVM sets absent OPT_OUT_TRACKING and ai.djl.offline properties to true. The retained parameterized case starts with both properties absent and asserts that both become true. The child must finish within 30 seconds and exit successfully; any still-running child is forcibly stopped and must exit within five seconds. The real tokenizer/strace probe separately verifies loading and network behavior.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "**No telemetry, analytics, or phone-home.** The product does not report usage anywhere.",
          "location": "NON-GOALS.md:8"
        },
        {
          "kind": "owner-words",
          "quote": "nothing on the machine or network is exposed by accident",
          "location": "task t-20261019-9d870e request"
        }
      ],
      "evidence": "gates/test-intent/evidence/upkeep-privacy-optouts-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTokenizerPrivacyTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-07s99x7b",
  "verdict": "accept",
  "digest": "sha256:06c4f6a75132d13599ec8ab9b98437f8e1e76596b7a4bf264b74e4691a9caafc",
  "note": "Sources support unset->true only; Java+UI checks fail on c00a0cb24 by assertion and pass on d6990260a, re-run independently"
}
```
