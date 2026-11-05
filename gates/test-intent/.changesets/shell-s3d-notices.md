---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: 674d3b06-d63e-44ad-9cba-03a9fcab1380
---

# Notices only when you are not looking (S3d, DE5)

DE5 replaces ST14's routing. `state/noticePolicy.ts` is the one routing decision. With the window
focused nothing leaves the app. In the background (`utils/windowFocus.ts` now also reads hidden and
minimised) each live agent request, and each long work finishing or failing, raises one desktop
notification. The taskbar badge counts Needs you. Settings › General › Notifications is Off, On or
On with sound.

The notification plugin's desktop path is fire-and-forget, so a click never reaches the app. Delivery
moved to `modules/shell/src-tauri/src/notices.rs`: `notify-rust` on Linux and
`tauri-winrt-notification` on Windows, both already in the lock file through the plugin, now direct
dependencies. The shell also shows each event id once across windows. No capability changed. The npm
`@tauri-apps/plugin-notification` lost its only importer, so it left `package.json` and
`THIRD_PARTY_NOTICES` (regenerated); the Rust plugin and its capability entries stay, because
unshipped platforms still deliver through it.

The notification used to send the row's detail, which is the request's argument summary. It now
sends the first line and the deadline.

Persisted format: one optional field, `notifications` (`off`, `on` or `sound`), in the user-state
document, cross-profile; absent means On, the document version is unchanged and no migration is
needed. `sessionStorage['justsearch.taskbarBadge']` holds the count shown, for this session only.

The existing checks that count `sendDesktopNotification` calls pass unchanged. One behaviour widens:
requests arriving in the same refresh each notify, where only the first used to.

`shell-s3d-repaired.json` holds the 12 repaired checks, each failing on 6ac7c57e77 by assertion. Its
before-state patch (`compatibility/shell-s3d-notices-before.patch`) adds only two inert entry points
(`setNotifications`, `setTaskbarBadge`) so the checks load there; it contains none of the repaired
behaviour. `shell-s3d-preserved.json` runs the other 65 checks, including every other check in the
edited `windowFocus.test.ts`, on the candidate with no fail-before claim: they are new behaviour with
no before-state to fail, or preservation that passes on the baseline. Evidence runs used a temporary
Git command adapter (the before-state is a shared clone in the session's temporary folder; nothing
is registered in the source repository) and a Node command adapter that adds `--maxWorkers=4` to
Vitest. The evidence producer is unchanged.

The duration repair defines long finished work as at least 60 seconds (`LONG_WORK_MIN_DURATION_MS`),
measured from the first observed active phase for each indexing/transition episode, install or
operation. Short or unknown-duration completions still reach the pill and Earlier; failures keep
notifying at any length. Replay remains silent. Existing positive completion checks now observe
60 seconds, with their assertions unchanged. `shell-s3d-duration-repaired.json` records the six
new duration regressions failing by assertion on candidate 32149108492c1f93c6d9227774ef49a975b44390
and passing after repair. `shell-s3d-duration-preserved.json` reruns every check in the two edited
test files. The original twelve repaired checks are rerun in `shell-s3d-repaired.json` against the
original base, retaining their assertion fail-before evidence. Temporary adapters replace only
Git worktree creation/removal with shared temporary clones and cap Vitest at four workers; before
runs copy node_modules, so each checkout has its own dependency and build directories.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "Live events leave the app once each in the background: every agent request arriving, and long work finishing or failing, with who and what and a request's deadline, never its arguments or a failure's message; Off sends nothing while the pill, the announcement and the badge stay; On with sound asks for the platform sound.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a desktop notification fires once per live event (an agent request arriving; long work finishing, the same events the pill's 'just finished' uses, or failing), never for replay and never twice across two open windows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a notification says who and what in plain words, as the panel's first line does, with a request's deadline as its body; never internal names, file contents or argument values beyond that first line",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the setting is Off (no notifications, no sound; the badge stays because it is passive), On (the default, including for existing users) or On with sound (the platform's default sound), stored with the other user settings",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request in the background notifies once, saying who, what and the deadline, never its arguments",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request each request arriving together notifies, so each click lands on its own review",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request Off sends nothing while the pill, the announcement and the badge stay",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request On with sound asks for the platform sound",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work finishing notifies once, by its row",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work failing notifies once, leaving the failure message in the panel",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work a witnessed transition settling notifies; a first load in the settled state does not",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work an AI install completing or failing notifies; finding one already online does not"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "The badge reports the needs-you count it starts with and follows it live, sent only on a change, down to zero.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the badge is the taskbar or dock count of needs-you items, cleared at zero, updated live, with no error where the platform has no badge API; Windows, which has no badge count, shows the count as an overlay icon, and only a change reaches the shell",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 the taskbar badge reports the count it starts with, so a count left before a reload can clear",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 the taskbar badge follows Needs you live, is sent only on a change, and clears at zero"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/utils/windowFocus.test.ts"
      ],
      "class": "New",
      "behaviour": "A window hidden to the tray or minimised is in the background even if it kept its focus flag.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "'in the background' means the app window is hidden, minimised or not focused; with the window focused nothing leaves the app, and the pill, its announcement and the panel are the notice",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-repaired.json",
      "newChecks": [
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused DE5 inside Tauri, a window hidden to the tray is in the background even if it kept its focus flag",
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused DE5 inside Tauri, a minimised window is in the background even if it kept its focus flag"
      ],
      "note": "This file's mock gains isVisible and isMinimized and its setup resets them; no existing case's input or expected value changes. Every other check in the file is re-run in shell-s3d-preserved.json."
    },
    {
      "items": [
        "modules/ui-web/src/utils/windowFocus.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing window-focus checks, re-run because their file was edited: the browser path, the native signal, and failing safe toward assuming focused.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "'in the background' means the app window is hidden, minimised or not focused; with the window focused nothing leaves the app, and the pill, its announcement and the panel are the notice",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-preserved.json",
      "newChecks": [
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused outside Tauri, defers to document.hasFocus()",
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused outside Tauri, reflects document.hasFocus() === false",
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused inside Tauri, uses the native getCurrentWindow().isFocused() signal, not document.hasFocus()",
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused inside Tauri with a focused window, returns true",
        "modules/ui-web/src/utils/windowFocus.test.ts#isWindowFocused inside Tauri, fails safe (assumes focused) if the native API throws"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "With the window focused nothing leaves the app and the pill is the notice; a request rediscovered after a reload and receipts discovered late stay silent, the request still counting as needing you; retained Earlier records add nothing to the badge; complete with gaps is a decision, not a completion; a teardown during the focus check sends nothing and a remount does not repeat it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C6",
          "quote": "Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "'in the background' means the app window is hidden, minimised or not focused; with the window focused nothing leaves the app, and the pill, its announcement and the panel are the notice",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request with the window focused stays in the app: the pill and its announcement are the notice",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request C6 a request rediscovered after a reload notifies nothing and still counts as needing you",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request C10 a teardown while the focus check is in flight sends nothing, and a remount does not repeat it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work C6 a complete receipt discovered late is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work C6 a failed receipt discovered late is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work complete with gaps is a decision, not a completion",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work with the window focused the pill is enough",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 the taskbar badge C6 retained Earlier records do not count"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts",
        "modules/ui-web/src/utils/notify.test.ts"
      ],
      "class": "New",
      "behaviour": "A clicked notification opens the review of an agent request, or the panel on a failed operation or an event listed by its words; an item that has left opens the panel; a must-stop dialog stays on top. The listener stops with its owner, even mid-subscription, and a remount listens once. The frontend and the shell agree on the commands, the event name and the overlay size. Nothing happens in a plain browser, with no permission prompt.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "clicking a notification brings the app forward and opens the status panel on that item, an agent request on its review; the shell delivers notifications because the notification plugin cannot report a desktop click; an item that has left opens the panel, and a must-stop dialog stays on top",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 the shell contract the commands the frontend invokes are registered, and the clicked event has one name",
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 the shell contract both sides agree on the overlay size the shell validates",
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 installNoticeActivationBridge reports the clicked event id from the shell",
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 installNoticeActivationBridge stopping removes the listener",
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 installNoticeActivationBridge C10 stopping before the subscription lands still removes it",
        "modules/ui-web/src/shell-v0/router/tauriBridge.notices.test.ts#DE5 installNoticeActivationBridge listens to nothing in a plain browser",
        "modules/ui-web/src/utils/notify.test.ts#sendDesktopNotification hands the event and its id to the shell, which shows it once and reports the click",
        "modules/ui-web/src/utils/notify.test.ts#sendDesktopNotification does nothing in a plain browser, and asks no permission",
        "modules/ui-web/src/utils/notify.test.ts#sendDesktopNotification a shell that cannot show it raises no error"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "A clicked notification opens the review of an agent request, or the panel on a failed operation or an event listed by its words; an item that has left opens the panel; a must-stop dialog stays on top. The listener stops with its owner, even mid-subscription, and a remount listens once. The frontend and the shell agree on the commands, the event name and the overlay size. Nothing happens in a plain browser, with no permission prompt.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "clicking a notification brings the app forward and opens the status panel on that item, an agent request on its review; the shell delivers notifications because the notification plugin cannot report a desktop click; an item that has left opens the panel, and a must-stop dialog stays on top",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification opens the review of the agent request",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification opens the panel on a failed operation, with focus on its row",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification finds an event listed only by its words in Earlier",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification a request answered since opens the panel on what is true now",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification never opens over a must-stop dialog",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification C10 a remount listens once: the old listener is stopped"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts"
      ],
      "class": "New",
      "behaviour": "The one routing decision: focused against background for each event kind, Off, On and On with sound, replay silence, the words of each notice, and a badge count that is Needs you only and reaches zero.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C6",
          "quote": "Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "'in the background' means the app window is hidden, minimised or not focused; with the window focused nothing leaves the app, and the pill, its announcement and the panel are the notice",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a notification says who and what in plain words, as the panel's first line does, with a request's deadline as its body; never internal names, file contents or argument values beyond that first line",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the setting is Off (no notifications, no sound; the badge stays because it is passive), On (the default, including for existing users) or On with sound (the platform's default sound), stored with the other user settings",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice agent-request: a focused window keeps it in the app, in every setting",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-finished: a focused window keeps it in the app, in every setting",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-failed: a focused window keeps it in the app, in every setting",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice agent-request: in the background Off is silent, On has no sound, On with sound has it",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-finished: in the background Off is silent, On has no sound, On with sound has it",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-failed: in the background Off is silent, On has no sound, On with sound has it",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice C6 a rediscovered event stays silent in the background, in every setting",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 words an agent request says who, what and the deadline, and is found by its row",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 words an agent request without a deadline has no body",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 words finished and failed work is found by its row, or by its words when it has none",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 needsYouCount counts Needs you only, and reaches zero",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 needsYouCount C6 retained Earlier records never add to the badge"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts"
      ],
      "class": "New",
      "behaviour": "The Notifications choice lives under General, is found by settings search and offers Off, On and On with sound; it is On when never chosen, survives a reload, drops a value outside the three and preserves co-resident values.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C11",
          "quote": "Any userState document or settings change preserves co-resident values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the setting is Off (no notifications, no sound; the badge stays because it is passive), On (the default, including for existing users) or On with sound (the platform's default sound), stored with the other user settings",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice is On for a person who never chose, including an existing document without the field",
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice off survives a reload",
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice on survives a reload",
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice sound survives a reload",
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice a value outside the three choices reads as On and never reaches the document",
        "modules/ui-web/src/shell-v0/state/notificationsState.test.ts#DE5 the Notifications choice C11 preserves co-resident values, in both directions",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts#DE5 Settings › General › Notifications lives in the General group and settings search finds it",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts#DE5 Settings › General › Notifications offers Off, On and On with sound with the stored value selected, named for assistive technology",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts#DE5 Settings › General › Notifications says when it applies, in the words of ref-13",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts#DE5 Settings › General › Notifications choosing writes the store and shows the choice",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts#DE5 Settings › General › Notifications a retained surface shows the value the document holds now"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/utils/taskbarBadge.test.ts"
      ],
      "class": "New",
      "behaviour": "The badge sends the count with the overlay Windows shows, reads 9+ above nine, clears at zero with nothing drawn, makes no native call when nothing changed or on a start with nothing waiting, clears a count a reload left behind, and raises no error without a canvas, a shell or a badge.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the badge is the taskbar or dock count of needs-you items, cleared at zero, updated live, with no error where the platform has no badge API; Windows, which has no badge count, shows the count as an overlay icon, and only a change reaches the shell",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-preserved.json",
      "newChecks": [
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge sends the count with the overlay Windows shows instead of a badge",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge more than nine reads 9+",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge zero clears it, with nothing drawn",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge a start with nothing waiting makes no native call",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge an unchanged count is not sent again",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge a reload clears the count the page before it left on the taskbar",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge without a canvas the count is still sent",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge does nothing in a plain browser",
        "modules/ui-web/src/utils/taskbarBadge.test.ts#DE5 setTaskbarBadge a platform without a badge raises no error"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The shell shows each event id once however many windows report it, a claim expires so later work can notify, zero clears the badge, and the overlay clears at zero and rejects a wrong size.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a desktop notification fires once per live event (an agent request arriving; long work finishing, the same events the pill's 'just finished' uses, or failing), never for replay and never twice across two open windows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the badge is the taskbar or dock count of needs-you items, cleared at zero, updated live, with no error where the platform has no badge API; Windows, which has no badge count, shows the count as an overlay icon, and only a change reaches the shell",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/notices.rs#notices::tests::an_event_reported_by_two_windows_shows_once",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::different_events_each_show",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::a_claim_expires_so_later_work_can_notify_and_the_ledger_stays_small",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::zero_clears_the_badge",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::the_overlay_clears_at_zero_and_rejects_a_wrong_size"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "Finished work may notify only after at least 60 seconds of observed active work, measured separately for each indexing or transition episode, install, and operation. A shorter completion stays in the pill and Earlier; an unknown duration stays inside. Failures notify at any duration, subject to the existing focus, setting and replay rules.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Finished work may notify only after at least 60 seconds of observed active work, measured separately for each indexing or transition episode, install, and operation. A shorter completion stays in the pill and Earlier; an unknown duration stays inside. Failures notify at any duration, subject to the existing focus, setting and replay rules.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside 59 seconds stays in the pill and Earlier; a later 60 second run notifies once",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside indexing durations reset between runs, so repeated short runs stay silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside a short operation completes without notifying",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside a short install completes silently, but a short failure notifies",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 work duration 59 seconds stays in the app; 60 seconds notifies",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 work duration an unknown duration stays in the app"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts"
      ],
      "class": "New",
      "behaviour": "Failures remain eligible at zero, short, long and unknown durations; replay remains silent even for long finished work.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Finished work may notify only after at least 60 seconds of observed active work, measured separately for each indexing or transition episode, install, and operation. A shorter completion stays in the pill and Earlier; an unknown duration stays inside. Failures notify at any duration, subject to the existing focus, setting and replay rules.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 work duration a failure notifies at any length, including an unknown duration",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 work duration C6 replay stays silent even after long work"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Existing positive completion examples now provide or observe 60 seconds of active work. The component tests offset Date.now while leaving real timers running. All existing assertions remain unchanged; the routing fixture adds the duration now required for a long completion. Every check in both edited files is rerun in shell-s3d-duration-preserved.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Finished work may notify only after at least 60 seconds of observed active work, measured separately for each indexing or transition episode, install, and operation. A shorter completion stays in the pill and Earlier; an unknown duration stays inside. Failures notify at any duration, subject to the existing focus, setting and replay rules.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
