---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b57-vdu-declared-activity
---

Operation declarations separate user input from unattended observation. Independent acceptance is pending; this builder does not replace either prior verifier rejection.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentObserverHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentUiFeedsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Brain timers, status reads, unattended readiness POSTs and feeds preserve the captured shared activity version across bare, BUTTON and MCP attribution. Real registered user actions increment it. A newly mounted undeclared polling POST cannot invent input. Every production route registration declares its activity property. Capacity refusals and background attachment do not invent accepted activity. The two independent verifier classes retain every supplied assertion.",
      "sources": [
        {"kind":"owner-words","quote":"Whether a request is user activity must be a declared property of the operation the user performed (chat, search, suggestions while typing, explicit actions), not inferred from HTTP method, default attribution or a list of excluded paths, so a new polling endpoint cannot become activity by accident.","location":"task t-20261005-b6d536 request"},
        {"kind":"owner-words","quote":"every UI timer and feed the frontend issues without input does not count; every user operation does; a structural check that each API route declares its activity property","location":"task t-20261005-b6d536 request"}
      ],
      "evidence": "gates/test-intent/evidence/b57-declared-request-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentObserverHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentUiFeedsTest.java"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/utils/userActivity.test.ts"],
      "class": "New",
      "behaviour": "Actual app input is reported promptly, continuous pointer input remains active beyond the idle window, and the final genuine input is retained while a request is in flight. A start click has no delayed duplicate. Synthetic events and idle timers report no activity, and disconnected UI removes listeners and pending reports.",
      "sources": [
        {"kind":"owner-words","quote":"user activity during a VDU pass (search, chat, typing in the app, other user actions) interrupts it promptly","location":"task t-20261005-b6d536 request"},
        {"kind":"owner-words","quote":"requests nobody typed do not count as activity","location":"task t-20261005-b6d536 request"}
      ],
      "evidence": "gates/test-intent/evidence/b57-ui-input.json",
      "newChecks": ["modules/ui-web/src/shell-v0/utils/userActivity.test.ts"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Fixture routes now explicitly declare the same user operations, observation and protocol semantics as production registration. The future-action fixture explicitly declares an action. Every request input, activity/pass checkpoint, interruption/pending/resume assertion and tag is retained. The shared activity source and real search/chat controllers remain the observation boundary."
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpToolLifecycleTest.java"],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The fake tool definition supplies the mandatory explicit activity property. All lifecycle descriptions, catalog closure, projections and assertions remain unchanged."
    },
    {
      "items": ["modules/ui-web/src/shell-v0/utils/autoLock.test.ts"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The idle timer uses the explicitly automatic lock operation rather than the user's lock operation. The same store handler, idle thresholds, unlocked/locked cases, disabled case, method and lock effect remain the oracle.",
      "sources": [{"kind":"owner-words","quote":"requests nobody typed do not count as activity","location":"task t-20261005-b6d536 request"}]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review.
