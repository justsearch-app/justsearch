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
frontend C2 check passed on the candidate and failed an assertion on the before-state. All
four Java C1 parameter combinations executed and passed on the candidate; the before-state
failed assertions in the three cases with absent defaults, while explicit false/false passed.
The documented whole-class Java selector includes every parameterized case (their XML names
are display names). Both repaired checks have valid fail-before evidence.

Runner logs: tmp/privacy-evidence/followup6-ui-evidence.log and
followup6-java-class-evidence.log. The frontend used the runner's default dependency link;
Java ran behind the shared Gradle lock. The runner removed both temporary before-worktrees.
Independent acceptance is left to the acceptor; no builder acceptance record is written.

Existing C1 observations (not substitutes for D4):
- tmp/privacy-evidence/before-tokenizer-output.log:1-5 and before-tokenizer-connect.strace:8-11:
  absent defaults, successful tokenization, assertion failure, four EC2 metadata connects.
- tmp/privacy-evidence/after-tokenizer-output.log:1-3 and after-tokenizer-connect.strace:
  true defaults, identical token IDs, no IPv4/IPv6 connects. See before/after-tokenizer-summary.json
  and before-after-comparison.json for machine-readable results.
- tmp/privacy-evidence/after-gradle-test-summary.json: all four parameterized entry cases passed.

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
      "behaviour": "C1 entry guard: initializing actual HeadlessApp in a fresh child JVM sets absent OPT_OUT_TRACKING and ai.djl.offline properties to true. Four parameterized combinations cover both absent, both explicitly false, and each independently absent; explicit false values survive. The child must finish within 30 seconds and exit successfully; any still-running child is forcibly stopped and must exit within five seconds. The real tokenizer/strace probe separately verifies loading and network behavior.",
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
