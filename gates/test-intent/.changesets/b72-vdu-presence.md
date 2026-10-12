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
        {"kind": "owner-words", "quote": "The web UI sends a small user-presence signal on trusted input events a person makes (key press, pointer press, wheel or scroll, touch; not pointer movement or hover, and not synthetic events), throttled so that continuous input sends at most one request every few seconds, through a route declared USER_ACTION.", "location": "task t-20261005-b6d536 request (b72 builder contract)"},
        {"kind": "owner-words", "quote": "Choose the throttle so a single key press or click always interrupts promptly, and say why it is enough.", "location": "task t-20261005-b6d536 request (b72 builder contract)"},
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 request (quoted in the b72 builder contract)", "delegationScope": "the presence signal's throttle and the events that count as a person scrolling", "choice": "A three-second interval with a leading report (the first input after a quiet interval is sent at once) and one end-of-interval report for the last input of a burst. Scroll is reported through the wheel, key, touch and pointer press that cause it, not through scroll events, which the browser marks trusted when the page scrolls itself.", "label": "agent choice"}
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
        {"kind": "owner-words", "quote": "a person's key press, click, scroll and touch in the real UI code produce the presence signal (and synthetic events, hover and pointer movement do not)", "location": "task t-20261005-b6d536 request (b72 builder contract)"}
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
        {"kind": "owner-words", "quote": "The behavioural idle-surface check stays and becomes the guard for the web UI: an untouched UI, including mount, reload, timers, streams and status delivery, sends no USER_ACTION request and no presence signal.", "location": "task t-20261005-b6d536 request (b72 builder contract)"},
        {"kind": "owner-words", "quote": "add a check that mounting the main surfaces and delivering status updates with no person acting sends only OBSERVATION requests", "location": "task t-20261005-b6d536 request (b66 builder contract)"}
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
        {"kind": "owner-words", "quote": "Fix it so the adopted title is displayed from the first input on, the local copy is removed only once the server has the title and a list answer reflects it (or a newer answer supersedes), and a rename a person makes while adoption is in flight wins over the legacy title", "location": "task t-20261005-b6d536 request (b72 builder contract)"},
        {"kind": "owner-words", "quote": "the verifier's L9 and L10 legacy-title cases (copy them in, keeping their assertions)", "location": "task t-20261005-b6d536 request (b72 builder contract)"}
      ],
      "evidence": "gates/test-intent/evidence/b72-vdu-presence.json",
      "newChecks": ["modules/ui-web/src/shell-v0/state/legacyTitleAdoption.test.ts"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder does not provide its own acceptance.
