---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b72-vdu-presence
---

The web UI reports a person acting through its presence signal (POST /api/ui/activity, USER_ACTION)
on trusted key presses, pointer presses, wheel turns, touches and text entry, at once for the first
input and at most once every three seconds while input continues. Pointer movement, hover, scroll
events and synthetic events send nothing. The static caller proof (observationCallers.test.ts, added
and now removed on this branch) is retired; observation reasons stay as documentation with their
route and registration checks. The untouched-UI check now drives the real shell. A retained legacy
conversation title shows from the first input on, its local copy goes only once a list answer
reflects the saved title, and a rename made meanwhile wins.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/utils/userActivity.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "A single trusted key press, pointer press, wheel turn, touch or text entry is reported synchronously, with no delayed duplicate, and idle time reports nothing. Page-dispatched input, pointer movement, hover, boundary events and scroll events are not reported. Sixty seconds of continuous input give one report per three-second interval (reports at least 3000 ms apart, the last no earlier than the last input) and nothing afterwards. A failed report does not hold back the next input. Disconnect drops a pending end-of-interval report and the listeners. On b2684e466 every trusted event, including pointer movement, counted, and continuous input reported every 100 ms.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "The web UI sends a small user-presence signal on trusted input events a person makes (key press, pointer press, wheel or scroll, touch; not pointer movement or hover, and not synthetic events), throttled so that continuous input sends at most one request every few seconds, through a route declared USER_ACTION.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Choose the throttle so a single key press or click always interrupts promptly, and say why it is enough.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "the presence signal's throttle and the events that count as a person scrolling", "choice": "A three-second interval with a leading report (the first input after a quiet interval is sent at once) and one end-of-interval report for the last input of a burst. Scroll is reported through the wheel, key, touch and pointer press that cause it, not through scroll events, which the browser marks trusted when the page scrolls itself.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b72-vdu-presence.json",
      "newChecks": ["modules/ui-web/src/shell-v0/utils/userActivity.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/activity/presenceSignal.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "In the real mounted shell, a person's key press, click, wheel scroll and touch on a control inside a shadow root each send exactly one POST /api/ui/activity at once; page-dispatched events, hover and pointer movement send none; ten seconds of continuous typing and scrolling send at most four. On b2684e466 pointer movement sent the signal and continuous input sent one request per event.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "a person's key press, click, scroll and touch in the real UI code produce the presence signal (and synthetic events, hover and pointer movement do not)", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b72-vdu-presence.json",
      "newChecks": ["modules/ui-web/src/activity/presenceSignal.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/activity/idleSurfaces.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "The existing case is unchanged: real main-surface mounts, timers, nonempty row labels and corpus-growth status updates send only registered observations. The added case mounts the real shell on Library, removes it and mounts it again (reload), runs ten minutes of interval timers, delivers the connect burst and heartbeats on every stream it opens, delivers a status update and lets a resting pointer fire trusted move and boundary events; it requires no POST /api/ui/activity and no USER_ACTION request, then one trusted key press sends exactly one presence signal. On b2684e466 the resting pointer's trusted movement sent the signal.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "The behavioural idle-surface check stays and becomes the guard for the web UI: an untouched UI, including mount, reload, timers, streams and status delivery, sends no USER_ACTION request and no presence signal.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "add a check that mounting the main surfaces and delivering status updates with no person acting sends only OBSERVATION requests", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b66 builder contract)'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b72-vdu-presence.json",
      "newChecks": ["modules/ui-web/src/activity/idleSurfaces.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/state/legacyTitleAdoption.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "The verifier's legacy-title cases L1 to L10, copied with their assertions (line endings only converted to LF). L9: a rename made while adoption is in flight keeps the person's title on the server. L10: when the input that adopts also reloads the list and that answer predates the save, the shown title stays the retained one. L1 to L8 preserve untouched mounts and reloads sending no title write, one adoption on first input that then erases the local copy, two tabs, a locked store, a backend title winning and a person's rename. On b2684e466 L9 and L10 fail by assertion.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Fix it so the adopted title is displayed from the first input on, the local copy is removed only once the server has the title and a list answer reflects it (or a newer answer supersedes), and a rename a person makes while adoption is in flight wins over the legacy title", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "the verifier's L9 and L10 legacy-title cases (copy them in, keeping their assertions)", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b72 builder contract)'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b72-vdu-presence.json",
      "newChecks": ["modules/ui-web/src/shell-v0/state/legacyTitleAdoption.test.ts"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder does not provide its own acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu8-20261012",
  "verdict": "reject",
  "digest": "sha256:14c2ec5c87267edbb08cb3aba8dcb5f0303368ef8c7ef4b0f05c6971822f84af",
  "note": "Independent round 8, candidate 857c3d9cd vs b2684e466: FAIL. (1) LIVE-STREAM-FIRST-ANSWER: after a person's first ask in a new Search v3 conversation, the untouched UI itself sends the auto-title POST /api/chat/dispatch and DELETE /api/chat/conversations/_title_... (both USER_ACTION) while the answer streams; an accepted pass captured after the ask is interrupted with 0 units (reproduced twice; a follow-up ask whose transcript follows the answer completes 2 units). The auto-title code predates the candidate, but the round requires that nothing a person does not do reports activity. (2) LEGACY-RENAME-DURING-ADOPTION: live, with tab B's adoption write delayed 3 s, the person's rename shows, then the row reverts to the legacy title at about 3.1 s and stays through 12 s while the server holds the rename; own unit L13 fails on the candidate and passes on b2684e466. (3) Own unit L17: tab B renames while its adoption is in transit and tab A acts before B confirms; the server ends with the legacy title (rename lost); fails on the candidate, passes on b2684e466. Passed: focused and full JVM suites (architecture failures identical on both arms), web suite, typecheck, builder's new web tests, own presence harness, route roles; live presence for key, click, wheel, typing, key after a failed report (presence within 25 ms, interruption at the next document boundary, about 4 s, 31 s mid-document), rename and title POST, roster, folder failures, ingestion ledger, preview, HTTP and UI chat input interrupt with later completion; presence route rejects other methods; untouched legacy reload sends no presence or title; first input shows the retained title; idle Library automatic completion with only observation traffic; automatic pass interrupted by UI typing; GPU-on replay and raster search; admin GPU-off completing on the CPU (CPU flags and buffers, raster search); MCP and OpenAI-compatible probes. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu8/. No product/test edits, commits or pushes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu9-20261012",
  "verdict": "accept",
  "digest": "sha256:8c6572cbe47a83ecaffafc8a698be3d50fee3feca97f43e9acf13481fd188773",
  "note": "Independent round 9, candidate f1a19f754 vs 80f03cb54: PASS. New and own checks fail before by assertion (builder's new Java tests do not compile before) and pass on the candidate; full JVM, web, typecheck, architecture, lane F, regen, API contract and wire results match the before arm. Live on the real UI and dev stack: session roster, folder failures, ingestion ledger, preview, HTTP and UI chat, key, click, wheel, typing and a key 1 s after acceptance each interrupt with 0 units and the document completes later (input to presence 3-581 ms, to interruption 3.3-4.5 s); untouched legacy reload and streaming answers do not interrupt; the new-conversation stream case completes its unit and the title arrives after the VDU hold (auto-title 503 BUSY, then 200); renames during adoption win in every tab and on the server; idle Library start after 329 s with only observation traffic, typing interrupts the automatic pass; GPU-on replay and admin GPU-off CPU pass complete with searchable raster text; MCP and OpenAI-compatible probes pass. Notes: reload within 120 s delays a pending title until a later list load; wall-clock comparison residual; evidence-invalid governance entries need the builder's evidence re-run; automatic start does not happen while the chat model stays loaded (pre-existing). Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu9/. No product or test edits, commits or pushes."
}
```
