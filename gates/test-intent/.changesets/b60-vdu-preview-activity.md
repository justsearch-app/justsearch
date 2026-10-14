---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b60-vdu-preview-activity
---

Deliberately requested operation previews, transcript exports, history inspection and other client reads advance shared user activity. Automatic renders, timers, streams and status reads retain their declared observer behavior. The caller audit is recorded in F:/agent-sandbox/design-b6d536/codex/b60-vdu-preview-activity/report.md. Independent acceptance remains pending; the existing verifier rejection is preserved.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Each audited deliberately requested GET advances activity through real HTTP production admission filters with and without internal attribution hints; the operation-preview assertion and all independent verifier assertions are retained. Automatic trace polling is additionally preserved. The same check fails by activity-not-advanced assertion on b45615514.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "anything else a client calls deliberately is USER_ACTION", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b60-vdu-preview-activity.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The exhaustive registered-route HTTP check expects the audited deliberate GET operations to advance activity. Its accepted requests, production registration and admission, observer expectations, declaration closure and capacity checks are retained. The current declaration oracle also includes the b63 caller audit; its proof is refreshed against 55e4dd392 in the b63 evidence run.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Change only classifications that the rule gets wrong; keep the startup validator and the b57 design.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b63-vdu-observation-justified.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"]
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
  "session": "verify-vdu4-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:ddd1b26cbeccab69e1bf43d7ac4fa4a6457f82d6a9703423e45926356ed6b92d",
  "note": "Independent round 4: preview repair passes assertion reproduction and native capture/interruption/recovery. Candidate fails the complete declared-activity requirement: deliberate GET /api/diagnostics/ingestion/recent remains observation with no unattended consumer; own assertion fails both arms and the real accepted pass completes 1 unit instead of interrupting. Loaded-idle automatic VDU veto is pre-existing with no supplied S7/E exemption. Full evidence, completed native positives, gate failures and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu4/report.md. CPU native check pending shared-stack release when this verdict was recorded."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu5-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:e68b19a0639c7a8526969736b9c613c55ab1c3837d4ba6594cdd252c4a45e6a8",
  "note": "Independent round 5: FAIL. Deliberate GET /api/chat/sessions and GET /api/indexing-jobs/failed/by-prefix remain observation with no unattended caller. Independent production-filter assertions fail and actual requests immediately after accepted capture do not interrupt: each operation completes one unit. Startup accepts an obsolete justification after removing its route. Five consumer justifications name sources that do not perform their claimed requests. Ledger and preview repair, real accepted chat and extra deliberate requests interrupt and recover; GPU-on completion, raster search and protocols pass. Automated gates, safety exclusions and remaining native checks/limits are recorded as they complete at F:/agent-sandbox/design-b6d536/codex/verify-vdu5/report.md. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu6-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:95178813e824374395dbcd23ba68cdbeb231b4e82b8064fd42d69aaf0ecf5fdc",
  "note": "Independent round 6: FAIL CALLER-MECHANICS, PATH-ERROR-CONTRACT and IDLE-LEGACY-TITLE. The exact caller-check helpers accept three invalid unattended-source justifications. The running path-label endpoint returns 500 for malformed JSON where the replaced invocation returns 400. Real Chromium mount hydration posts a retained conversation title through a USER_ACTION route and interrupts VDU; this is inherited and also fails the independent assertion on 63c28232f. GPU-on and valid administrative GPU-off CPU runs complete one unit with durable two-page results and raster search. Roster, failed-jobs, ledger, preview, accepted chat, trusted typing and click interrupt with later document completion. Untouched Library, status/Brain observations, corpus growth and automatic locking permit full-idle-window automatic completion. Scrolling and wider unvisited surface combinations are unverified. Full automated comparisons, gate failures, safety exclusions and cleanup evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu6/report.md. Both owned stacks stopped; no foreign stack takeover or stop. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu7-20261012",
  "verdict": "reject",
  "digest": "sha256:95178813e824374395dbcd23ba68cdbeb231b4e82b8064fd42d69aaf0ecf5fdc",
  "note": "Candidate 3d6c71cfd vs 469d8f10f. Rejected: (1) CALLER-MECHANICS: the observation caller check accepts 12 of 25 independent must-reject adversaries (reassigned target, barrel alias, shadowed const, shorthand and quoted POST method, local fetchFn and cache.fetch, click-fed subscription, two name collisions, click-mounted connectedCallback, route only in a query string); (2) LIVE-LEGACY-TITLE-DISPLAY: after an untouched reload, the person's first input (opening History) posts the adoption while the list reloads; the list answer predates the write and the local copy is already erased, so the row shows the first message instead of the retained title until a later reload (reproduced twice live; own unit L10 fails on the candidate). Passed: full JVM suites, web suite, typecheck, path-label 400/retry preservation, roster/failed-jobs/ledger/preview/chat/typing/rename interrupt with later completion, untouched reload not interrupting, idle Library automatic completion with all-observation traffic, GPU-on replay and raster search, admin GPU-off completing on CPU. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu7/. Owned stacks stopped; no product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu8-20261012",
  "verdict": "reject",
  "digest": "sha256:95178813e824374395dbcd23ba68cdbeb231b4e82b8064fd42d69aaf0ecf5fdc",
  "note": "Independent round 8, candidate 857c3d9cd vs b2684e466: FAIL. (1) LIVE-STREAM-FIRST-ANSWER: after a person's first ask in a new Search v3 conversation, the untouched UI itself sends the auto-title POST /api/chat/dispatch and DELETE /api/chat/conversations/_title_... (both USER_ACTION) while the answer streams; an accepted pass captured after the ask is interrupted with 0 units (reproduced twice; a follow-up ask whose transcript follows the answer completes 2 units). The auto-title code predates the candidate, but the round requires that nothing a person does not do reports activity. (2) LEGACY-RENAME-DURING-ADOPTION: live, with tab B's adoption write delayed 3 s, the person's rename shows, then the row reverts to the legacy title at about 3.1 s and stays through 12 s while the server holds the rename; own unit L13 fails on the candidate and passes on b2684e466. (3) Own unit L17: tab B renames while its adoption is in transit and tab A acts before B confirms; the server ends with the legacy title (rename lost); fails on the candidate, passes on b2684e466. Passed: focused and full JVM suites (architecture failures identical on both arms), web suite, typecheck, builder's new web tests, own presence harness, route roles; live presence for key, click, wheel, typing, key after a failed report (presence within 25 ms, interruption at the next document boundary, about 4 s, 31 s mid-document), rename and title POST, roster, folder failures, ingestion ledger, preview, HTTP and UI chat input interrupt with later completion; presence route rejects other methods; untouched legacy reload sends no presence or title; first input shows the retained title; idle Library automatic completion with only observation traffic; automatic pass interrupted by UI typing; GPU-on replay and raster search; admin GPU-off completing on the CPU (CPU flags and buffers, raster search); MCP and OpenAI-compatible probes. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu8/. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu9-20261012",
  "verdict": "accept",
  "digest": "sha256:370ffbfacfade0e33832f51ca6a2f1390a4b0c17688b9fc94087fa22db23fe4a",
  "note": "Independent round 9, candidate f1a19f754 vs 80f03cb54: PASS. New and own checks fail before by assertion (builder's new Java tests do not compile before) and pass on the candidate; full JVM, web, typecheck, architecture, lane F, regen, API contract and wire results match the before arm. Live on the real UI and dev stack: session roster, folder failures, ingestion ledger, preview, HTTP and UI chat, key, click, wheel, typing and a key 1 s after acceptance each interrupt with 0 units and the document completes later (input to presence 3-581 ms, to interruption 3.3-4.5 s); untouched legacy reload and streaming answers do not interrupt; the new-conversation stream case completes its unit and the title arrives after the VDU hold (auto-title 503 BUSY, then 200); renames during adoption win in every tab and on the server; idle Library start after 329 s with only observation traffic, typing interrupts the automatic pass; GPU-on replay and admin GPU-off CPU pass complete with searchable raster text; MCP and OpenAI-compatible probes pass. Notes: reload within 120 s delays a pending title until a later list load; wall-clock comparison residual; evidence-invalid governance entries need the builder's evidence re-run; automatic start does not happen while the chat model stays loaded (pre-existing). Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu9/. No product or test edits, commits or pushes."
}
```
