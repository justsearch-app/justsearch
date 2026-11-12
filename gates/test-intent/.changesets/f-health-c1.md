---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: f600c2c9-1d54-413e-8745-3129d7e6c5e8
---

# Follow-up C1 — Health after the redesign

Sources are the owner's request for the in-between UI and the owner's delegation of design choices; each
choice names the follow-up C1 scenario it pins (FC1-FC5, settled with the orchestrator decisions of
2026-11-12: a definite failed read of a source makes the facts that depend on it unconfirmed at once,
expiry stays the fallback when reads simply stop; wording never claims work that is not happening; no
raw transport errors as banner text).

Four files are new and isolated (the gate reports them `[exempt]`); they are recorded here so the
fail-before evidence travels with the change. One existing test changed,
`ActivitySurface.scroll-lockdown.test.ts`: its Health/Logs case at 600×400 with 200% text asserted that
each member's own body is its scroller and then skipped the rest as a named limit outside that change
(the header fills the tab). FC5 settles that limit by letting the header scroll with the body in a
short window, so in that window the case now asserts that the member scrolls as a whole and that the
wheel moves it. Its 1280×720 case keeps every expectation (the body is the scroller, the member ends
inside the window, the body ends inside the window and shows something, the wheel moves the body); only
the order of two assertions changed. The new files:

- `HealthSurface.outage.test.ts` (happy-dom, the real status poller): FC1-FC3 with the Engine refusing,
  aborting or answering 500, Health open and hidden, the Engine back while hidden, a reload mid-outage,
  and reads that stop answering while the event stream beats.
- `HealthSurface.outage-lockdown.test.ts` (real Chromium, real Shell and System hub): the same outage
  with Health open, hidden, across a reload, in a second window and with forced colours.
- `HealthSurface.connection.test.ts` (happy-dom): FC4, the declared Connection card under the core
  presentation.
- `SurfaceReach-lockdown.test.ts` (real Chromium, real Shell, System hub and Library tabs): FC5 at
  600x400 with 200% text for Health, Logs and Library › Browse; and, as preservation checks that pass
  before and after, the fixed header at 1280x720.

Fail-before evidence (`--before origin/main`): `gates/test-intent/evidence/f-health-c1.json`.
Preservation evidence (candidate only): `gates/test-intent/evidence/f-health-c1-preserved.json`.

Amendments FC3a, FC3b and FC5a (after the real-Engine outage check, 2026-11-12) add two isolated files:
`HealthSurface.readout-outage.test.ts` (happy-dom, the real status poller under the core declared
presentation: the readout and 'What you can do right now' during a failed or stopped status read while the
event stream beats, and no dangling separator) and `LogSurface.small-window-lockdown.test.ts` (real
Chromium, real Shell and System hub: Logs rows at 600x400 with 200% text, and preservation at the other
sizes). Two existing tests adapt their input (`LivenessReadout.test.ts`, `CapabilityMap.test.ts`): their
answering backend now has a current status read, which the readout and the map consult.
Fail-before evidence (`--before eda737bb15`): `gates/test-intent/evidence/f-health-c1-amend.json`.
Preservation evidence (candidate only): `gates/test-intent/evidence/f-health-c1-amend-preserved.json`.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC1 FC2: when one status read fails definitively (connection refused, aborted, a 500 answer), Health's Engine, Graphics card, Folders and Index rows read unconfirmed and the Files and Queue counts are marked last known at once, three seconds after the last answer and so well inside the 20 s freshness bound; the headline says in words that the Engine's current status cannot be read, and no raw transport error ('Failed to fetch', 'aborted a request', '(500)') appears anywhere in Health. When reads answer again the rows return to quiet without a reload. Health hidden during the failed read shows the rows unconfirmed when it returns; the Engine back while Health is hidden shows Health quiet on return. A reload mid-outage shows no raw error and never 'Everything is working'. A second window says the same after its own failed read. With forced colours the outage words and the unconfirmed rows stay visible. FC3: during the outage (reads failing, or reads no longer answering while the event stream still beats) Index and Folders never say 'Catching up…', 'The backend is busy' or 'Rebuilding…'; they say their status is unconfirmed.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC1-FC3 with the orchestrator decisions of 2026-11-12: a definite failed read of a source (refused, aborted, 5xx) makes the facts that depend on it unconfirmed at once, by one currency rule used by every Health row and the overview counts; expiry stays the fallback when reads simply stop; wording never claims work that is not happening (no 'Catching up', 'backend is busy' or 'Rebuilding' when nothing catches up or rebuilds); no raw transport error as banner text, words instead; rows return without a reload when reads resume.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#Engine refused with Health open: rows and counts unconfirmed within one read, words instead of a raw error; rows return when reads resume",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#Engine aborted with Health open: rows and counts unconfirmed within one read, words instead of a raw error; rows return when reads resume",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#Engine server error with Health open: rows and counts unconfirmed within one read, words instead of a raw error; rows return when reads resume",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#Engine refused while Health is hidden: Health shows the rows unconfirmed when it returns",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#the Engine comes back while Health is hidden: Health returns quiet, without a reload",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#a reload while the Engine is down: no raw transport error, no claim of health",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#reads fail while the event stream still beats: no \"Catching up\", \"backend is busy\" or \"Rebuilding\"",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage.test.ts#reads stop answering while the event stream still beats: Index names no catching up",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts#refused with Health open: rows unconfirmed within one failed read, then quiet again without a reload",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts#aborted with Health hidden: unconfirmed on return; the Engine back while hidden: quiet on return",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts#server errors and a reload mid-outage: no raw error and no claim of health; quiet again when it answers",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts#a second window: both say the rows are unconfirmed within one failed read of their own",
        "modules/ui-web/src/shell-v0/views/HealthSurface.outage-lockdown.test.ts#forced colours: the outage is said in words that stay visible"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.connection.test.ts"
      ],
      "class": "New",
      "behaviour": "FC4: in Diagnostics' Connection card under the core declared presentation, with keyword search working (retrieval READY) and the AI chat engine off, the live readout never names 'Retrieval' and names the AI as the subject of its words ('AI models' and 'Offline' in its accessible name); with the AI online its accessible name begins 'AI models: Online'.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC4: the Connection card does not say 'Retrieval: Offline' while keyword search works; it names what it actually reports in words. The readout reports the app's status line; when those words are the AI engine's they are named for the AI models (the same name as Health's AI models row), otherwise the readout is named Status.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.connection.test.ts#keyword search works and AI chat is off: no \"Retrieval: Offline\", the readout names the AI",
        "modules/ui-web/src/shell-v0/views/HealthSurface.connection.test.ts#with the AI online the readout still names the AI as its subject"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC5: at 600x400 with 200% text, inside the real Shell and tab hosts, Health (with Diagnostics open), Logs and Library › Browse keep the end of their content reachable: wheeling over the surface brings the bottom of the body's last element into the surface's visible window, and End pressed from inside the surface does the same.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC5: at 600x400 with 200% text Health and Logs, and Library › Browse which shares the pattern, keep their content reachable by one layout rule shared by those surfaces: headers do not fill the tab panel; the body scrolls or the header scrolls with it; wheel and keyboard reach the end. 1280x720 unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Health at 600x400 with 200% text: the wheel and End reach the end of the content",
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Logs at 600x400 with 200% text: the wheel and End reach the end of the content",
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Library › Browse at 600x400 with 200% text: the wheel and End reach the end of the content"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC5: at 600x400 with 200% text the Health and Logs headers must not fill the System tab panel; 'the body scrolls or the header scrolls with it'. The chosen rule is the shared layout's: in a window at most 30rem tall the header scrolls with the body and the surface is the one scroller (the approach Activity already takes). Removed expectation: in the 600×400 window with 200% text, Health's and Logs' own body has overflow-y auto (it is now visible, and the surface scrolls), together with the named-limit skip of the rest of the case when the header fills the tab.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -e 'sticky header|fixed header|header scrolls|200% text|200 ?percent|600 ?x ?400|600×400|own scroller|surfaceLayout|surface-layout|Failed to fetch|Retrieval: Offline|last known' docs/decisions. No match; no decision governs where a surface's header scrolls. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC5 in the System hub with the Activity ledger loaded: at 600×400 with 200% text, Health and Logs each end inside the window, scroll as a whole (the member's overflow-y is auto and its content is longer than its tab), and the wheel over the member moves it.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC5: at 600x400 with 200% text Health and Logs keep their content reachable; headers do not fill the System tab panel; the body scrolls or the header scrolls with it; wheel and keyboard reach the end. Here the header scrolls with the body: the member is the one scroller.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts#at 600×400 with 200% text, the Health and Logs tabs keep their own scrolling"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC5 '1280x720 unchanged', preservation checks that pass before and after: at 1280x720 with 100% text Health, Logs and Library › Browse keep a fixed header; wheeling over the body does not scroll the surface itself, and the header and the top of the body stay where they were.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 FC5: 1280x720 unchanged — the fixed header above a body that scrolls on its own stays as it is.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Health at 1280x720: the header stays fixed while the body scrolls on its own",
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Logs at 1280x720: the header stays fixed while the body scrolls on its own",
        "modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts#Library › Browse at 1280x720: the header stays fixed while the body scrolls on its own"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts"
      ],
      "class": "New",
      "behaviour": "FC3a: with Diagnostics open under the core declared presentation, when a status read fails (refused, aborted, a 500 answer) 16 s after the last answer while the event stream still beats, or when status reads stop answering for 25 s while the stream beats, the Connection readout no longer shows the stream-derived 'Catching up…' (or any 'Online', 'Ready', 'connected' words) and no healthy or in-progress dot (tone neither live nor info); it says the Engine status is unconfirmed, in its words and its accessible name, as the Engine row does. 'What you can do right now' lists nothing as available, keyword search included. When reads answer again the readout and keyword search return without a reload.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 amendment FC3a (orchestrator, 2026-11-12, after the real-Engine outage check): during an Engine outage Diagnostics' Connection readout does not say 'Catching up…' or show a healthy state; it says the Engine cannot be reached / last known, like the rows; 'What you can do right now' lists nothing that needs the Engine as available. Both use Health's one currency rule (statusCurrency over healthObservationCurrent in state/systemHealth.ts) instead of their own: while the status read is not current the readout says 'Engine: Status unconfirmed' with a neutral dot (its indexing words say the indexing status is unconfirmed, and the store's own words about reaching the Engine, 'Backend disconnected', 'Connecting…', 'Reconnecting…', stay, with a neutral dot), and every capability the map would call available reads 'Engine status unconfirmed' ('Search status unconfirmed' for keyword search).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status read refused while the event stream beats, 16 s after the last answer: the readout says the Engine status is unconfirmed, nothing listed as available",
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status read aborted while the event stream beats, 16 s after the last answer: the readout says the Engine status is unconfirmed, nothing listed as available",
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status read server error while the event stream beats, 16 s after the last answer: the readout says the Engine status is unconfirmed, nothing listed as available",
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status reads stop answering while the event stream beats, 25 s after the last answer: the same by expiry",
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#reads answer again: the readout and keyword search return without a reload"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts"
      ],
      "class": "New",
      "behaviour": "FC3b: after a failed status read (refused, a 500 answer) no status line in Health (its sentences, rows, list items, headings and status regions) ends with a separator that has nothing after it; the indexing line above the counts reads 'Indexing status unconfirmed', not 'Indexing status unconfirmed —'.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 amendment FC3b (orchestrator, 2026-11-12): no status line ends with a dangling separator; a line with nothing after its separator drops the separator. The line is composed by joining its non-empty parts, as Health's other indexing lines already are.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status read refused: no line ends in \"—\" with nothing after it",
        "modules/ui-web/src/shell-v0/views/HealthSurface.readout-outage.test.ts#status read server error: no line ends in \"—\" with nothing after it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC5a: Logs inside the real Shell and System hub at 600x400 with 200% text, holding Engine log lines (sentences, paths, a long unbroken token): nothing scrolls sideways (surface, body, page), and every row's message lies inside the window with at least twenty characters of its own font per line (or the whole row, if narrower); time, level and logger end inside the window.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 amendment FC5a (orchestrator, 2026-11-12): at 600x400 with 200% text, and wherever the small-window layout applies, each log row's message has a usable width inside the window; rows wrap or stack their columns; no horizontal scrolling; 1280x720 unchanged. One rule for every width: the time, level and logger keep their column widths, the message takes the rest of the line and wraps onto its own full-width line when the rest is narrower than its 20rem basis; no width-specific rules.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts#600x400 with 200% text: every message has a usable width inside the window; nothing scrolls sideways"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC5a preservation checks that pass before and after: at 600x400 with normal text and at 1280x720 with 200% text (where the small-window layout also applies) every message keeps a usable width inside the window and nothing scrolls sideways; at 1280x720 with normal text each row is one line of time, level, logger and message, the columns at 0, 9rem, 13rem and 22rem plus their gaps, 9rem, 4rem and 9rem wide, and the message fills the rest of the line.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 amendment FC5a: '1280x720 unchanged', and the other sizes where the small-window layout applies stay readable.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-health-c1-amend-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts#600x400: every message keeps a usable width inside the window; nothing scrolls sideways",
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts#1280x720 with 200% text: every message keeps a usable width inside the window; nothing scrolls sideways",
        "modules/ui-web/src/shell-v0/views/LogSurface.small-window-lockdown.test.ts#1280x720: each row is one line of time, level, logger and message in their columns"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/LivenessReadout.test.ts",
        "modules/ui-web/src/shell-v0/components/CapabilityMap.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Their fixtures describe an answering backend through a hand-built AiState alone; the readout and the map now also read when the status body arrived (getStatusReceivedAtMs), Health's currency rule. Each fixture now also feeds the store a status read (__feedForTest) so the backend they describe has a current status, and LivenessReadout's partial state carries that status. Every assertion is unchanged: the tone mapping (info, live, degraded, error, idle), the cold-start keyword row unconfirmed, the AI reasons, the remedy control, the not-live snapshot rows, and the live-snapshot rows available. A readout or map that ignored its status's currency, or claimed availability with it, still fails them.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting the in-between UI work, quoted in the follow-up C1 builder brief, 2026-11-12",
          "label": "owner request for this piece"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Follow-up C1 amendment FC3a: the readout and the map use Health's one currency rule for the status read instead of their own.",
          "label": "agent choice"
        }
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
  "session": "verifier-h0udpdtf-round2",
  "verdict": "accept",
  "digest": "sha256:2e1df4e10612adaa04e958bd8cfa4129c1dee58e921c620ebf396dbdb2b927bb"
}
```
