---
schema: test-intent.v1
task: upkeep-privacy-optouts
author-role: builder
author-session: upkeep-privacy-optouts-builder
---

# Test-intent entries

The outcome and C1/C2 below come from the owner-adopted task request, as directed by the
orchestrator's 2026-10-20 follow-up 4. Only the added privacy expectation in the existing
frontend test file changes; its recovery expectations remain intact.

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
          "kind": "owner-adopted-scenario",
          "task": "upkeep-privacy-optouts",
          "scenario": "C2",
          "quote": "JustSearch's own code stops two network contacts the user never asked for, so the product matches its promise that nothing leaves the machine except user-initiated downloads.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task upkeep-privacy-optouts request, builder brief, Checks C2",
          "quote": "C2 a check that index.html contains no fonts.googleapis.com / fonts.gstatic.com reference after (and does before); add it to an existing ui-web test or guard if one fits, rather than a new script."
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
          "kind": "owner-adopted-scenario",
          "task": "upkeep-privacy-optouts",
          "scenario": "C1",
          "quote": "JustSearch's own code stops two network contacts the user never asked for, so the product matches its promise that nothing leaves the machine except user-initiated downloads.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task upkeep-privacy-optouts request, builder brief, Checks C1",
          "quote": "or a JUnit test that asserts the opt-out is set by the shared entry code before tokenizer construction, plus the strace evidence."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Then set the switch(es) in the product so they are in force before any tokenizer is constructed in every JVM that loads one (find every entry point: the Engine main, the worker if it is a separate JVM, test harnesses are not required). Prefer one small shared place in code over launcher flags, so headless, desktop and dev starts all get it.",
          "delegationLocation": "task upkeep-privacy-optouts request, builder brief, item 1",
          "delegationScope": "Select confirmed DJL opt-out switches and their shared Java initialization before tokenizer construction.",
          "choice": "Install true defaults in HeadlessApp class initialization with putIfAbsent, preserving explicit JVM property choices. Check independent defaults and explicit-value preservation in fresh bounded JVMs so earlier tests cannot supply initialization accidentally.",
          "label": "agent choice"
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
