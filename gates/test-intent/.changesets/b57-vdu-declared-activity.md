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
      "behaviour": "Brain timers, status reads, unattended readiness POSTs and feeds preserve the captured shared activity version across bare, BUTTON and MCP attribution. Real registered user actions increment it. A newly mounted undeclared polling POST cannot invent input. Every production route registration declares its activity property. Capacity refusals and background attachment do not invent accepted activity. The two independent verifier classes retain every supplied assertion. Build cleanup asserts occupied admission prerequisites in IndependentObserverHttpTest and successful background attachment, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Whether a request is user activity must be a declared property of the operation the user performed (chat, search, suggestions while typing, explicit actions), not inferred from HTTP method, default attribution or a list of excluded paths, so a new polling endpoint cannot become activity by accident.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "every UI timer and feed the frontend issues without input does not count; every user operation does; a structural check that each API route declares its activity property", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-be59f4664-841fea4f2c.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentObserverHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentUiFeedsTest.java"
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Fixture routes now explicitly declare the same user operations, observation and protocol semantics as production registration. The future-action fixture explicitly declares an action. Every request input, activity/pass checkpoint, interruption/pending/resume assertion and tag is retained. The shared activity source and real search/chat controllers remain the observation boundary. Build cleanup asserts active bootstrap mocks and the constructed controller adapter prerequisite in VduRequestActivityTest, retaining original inputs, resource lifetimes and every existing assertion."
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
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "requests nobody typed do not count as activity", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu3-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:e58894a1a78a89d1f665e493da98157b2e0b779476bc75aa1716e36db13e178c",
  "note": "FAIL LIVE-PREVIEW / OWN-DECLARED-HTTP: explicit GET operation preview is OBSERVATION. Native before be59f4664 interrupts with 0 units and PENDING; candidate 74a3b7027 wrongly completes 1 unit. GPU-on/off, real chat and actual idle UI/input checks pass. Required gate failures, exclusions and unverified cases: F:/agent-sandbox/design-b6d536/codex/verify-vdu3/report.md. No product/test edits or commits."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu4-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:46021d9903bb73c95a44fcf09bba727ae1028f5ffbf8d80af4660cd1c6ffab2e",
  "note": "Independent round 4: reject declared-activity acceptance at the current digest. Historical ingestion-event ledger GET is observation despite no unattended UI/scripts/tools caller. Own assertion fails on b45615514 and 8f45dc4b9; actual candidate request after accepted capture returns 200 while VDU completes 1 unit/SUCCESS rather than interrupting. Preview/chat/search/MCP/UI activity interruption and recovery pass; browser idle and actual automatic lock pass. Loaded-idle veto is pre-existing and remains a requirement gap. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu4/report.md. CPU native check pending shared-stack release when recorded."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu5-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:e92f9d5ff7b9c75db2aebe7eccd38b883c2560fc69fe2c6e3f2b8c179d17ffa3",
  "note": "Independent round 5: FAIL. Deliberate GET /api/chat/sessions and GET /api/indexing-jobs/failed/by-prefix remain observation with no unattended caller. Independent production-filter assertions fail and actual requests immediately after accepted capture do not interrupt: each operation completes one unit. Startup accepts an obsolete justification after removing its route. Five consumer justifications name sources that do not perform their claimed requests. Ledger and preview repair, real accepted chat and extra deliberate requests interrupt and recover; GPU-on completion, raster search and protocols pass. Automated gates, safety exclusions and remaining native checks/limits are recorded as they complete at F:/agent-sandbox/design-b6d536/codex/verify-vdu5/report.md. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu6-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:6fadb49cf76cf3a6bd668bcc95030dd4a3e98f14d7aff2580083475a238f608e",
  "note": "Independent round 6: FAIL CALLER-MECHANICS, PATH-ERROR-CONTRACT and IDLE-LEGACY-TITLE. The exact caller-check helpers accept three invalid unattended-source justifications. The running path-label endpoint returns 500 for malformed JSON where the replaced invocation returns 400. Real Chromium mount hydration posts a retained conversation title through a USER_ACTION route and interrupts VDU; this is inherited and also fails the independent assertion on 63c28232f. GPU-on and valid administrative GPU-off CPU runs complete one unit with durable two-page results and raster search. Roster, failed-jobs, ledger, preview, accepted chat, trusted typing and click interrupt with later document completion. Untouched Library, status/Brain observations, corpus growth and automatic locking permit full-idle-window automatic completion. Scrolling and wider unvisited surface combinations are unverified. Full automated comparisons, gate failures, safety exclusions and cleanup evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu6/report.md. Both owned stacks stopped; no foreign stack takeover or stop. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu7-20261012",
  "verdict": "reject",
  "digest": "sha256:6fadb49cf76cf3a6bd668bcc95030dd4a3e98f14d7aff2580083475a238f608e",
  "note": "Candidate 3d6c71cfd vs 469d8f10f. Rejected: (1) CALLER-MECHANICS: the observation caller check accepts 12 of 25 independent must-reject adversaries (reassigned target, barrel alias, shadowed const, shorthand and quoted POST method, local fetchFn and cache.fetch, click-fed subscription, two name collisions, click-mounted connectedCallback, route only in a query string); (2) LIVE-LEGACY-TITLE-DISPLAY: after an untouched reload, the person's first input (opening History) posts the adoption while the list reloads; the list answer predates the write and the local copy is already erased, so the row shows the first message instead of the retained title until a later reload (reproduced twice live; own unit L10 fails on the candidate). Passed: full JVM suites, web suite, typecheck, path-label 400/retry preservation, roster/failed-jobs/ledger/preview/chat/typing/rename interrupt with later completion, untouched reload not interrupting, idle Library automatic completion with all-observation traffic, GPU-on replay and raster search, admin GPU-off completing on CPU. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu7/. Owned stacks stopped; no product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu8-20261012",
  "verdict": "reject",
  "digest": "sha256:e264c98009ec711cbb72b3e2e3324a071cc506ff7de88c794eac0903b915d857",
  "note": "Independent round 8, candidate 857c3d9cd vs b2684e466: FAIL. (1) LIVE-STREAM-FIRST-ANSWER: after a person's first ask in a new Search v3 conversation, the untouched UI itself sends the auto-title POST /api/chat/dispatch and DELETE /api/chat/conversations/_title_... (both USER_ACTION) while the answer streams; an accepted pass captured after the ask is interrupted with 0 units (reproduced twice; a follow-up ask whose transcript follows the answer completes 2 units). The auto-title code predates the candidate, but the round requires that nothing a person does not do reports activity. (2) LEGACY-RENAME-DURING-ADOPTION: live, with tab B's adoption write delayed 3 s, the person's rename shows, then the row reverts to the legacy title at about 3.1 s and stays through 12 s while the server holds the rename; own unit L13 fails on the candidate and passes on b2684e466. (3) Own unit L17: tab B renames while its adoption is in transit and tab A acts before B confirms; the server ends with the legacy title (rename lost); fails on the candidate, passes on b2684e466. Passed: focused and full JVM suites (architecture failures identical on both arms), web suite, typecheck, builder's new web tests, own presence harness, route roles; live presence for key, click, wheel, typing, key after a failed report (presence within 25 ms, interruption at the next document boundary, about 4 s, 31 s mid-document), rename and title POST, roster, folder failures, ingestion ledger, preview, HTTP and UI chat input interrupt with later completion; presence route rejects other methods; untouched legacy reload sends no presence or title; first input shows the retained title; idle Library automatic completion with only observation traffic; automatic pass interrupted by UI typing; GPU-on replay and raster search; admin GPU-off completing on the CPU (CPU flags and buffers, raster search); MCP and OpenAI-compatible probes. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu8/. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu9-20261012",
  "verdict": "accept",
  "digest": "sha256:36456bbe13eb48ab28b1c581db4d50ccfe69bb2030d0063fd4616cc84d2915b9",
  "note": "Independent round 9, candidate f1a19f754 vs 80f03cb54: PASS. New and own checks fail before by assertion (builder's new Java tests do not compile before) and pass on the candidate; full JVM, web, typecheck, architecture, lane F, regen, API contract and wire results match the before arm. Live on the real UI and dev stack: session roster, folder failures, ingestion ledger, preview, HTTP and UI chat, key, click, wheel, typing and a key 1 s after acceptance each interrupt with 0 units and the document completes later (input to presence 3-581 ms, to interruption 3.3-4.5 s); untouched legacy reload and streaming answers do not interrupt; the new-conversation stream case completes its unit and the title arrives after the VDU hold (auto-title 503 BUSY, then 200); renames during adoption win in every tab and on the server; idle Library start after 329 s with only observation traffic, typing interrupts the automatic pass; GPU-on replay and admin GPU-off CPU pass complete with searchable raster text; MCP and OpenAI-compatible probes pass. Notes: reload within 120 s delays a pending title until a later list load; wall-clock comparison residual; evidence-invalid governance entries need the builder's evidence re-run; automatic start does not happen while the chat model stays loaded (pre-existing). Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu9/. No product or test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:891529f6fb8fc52ee64ff501767e00ce6e25beee60b461c705f73376efbb3b92",
  "note": "Re-reviewed declared request activity and the corrected delegation labels; candidate and declared before checks reproduce the recorded activity outcomes."
}
```
