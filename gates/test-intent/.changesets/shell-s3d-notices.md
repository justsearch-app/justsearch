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
moved to `modules/shell/src-tauri/src/notices.rs`: the freedesktop service over `zbus` on Linux and
`tauri-winrt-notification` on Windows, both already in the lock file through the plugin, now direct
dependencies, with `gdkwayland-sys` (already in the lock file through Tao) for the activation token. The shell also shows each event id once across windows. No capability changed. The npm
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
behaviour. `shell-s3d-preserved.json` runs the other 64 checks, including every other check in the
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

The decisions repair closes five stale or lost decisions found on 29028ddad8. The focus read is
repeated when a focus change overtakes it (`utils/windowFocusChange.ts`, a separate module so the
eight test files that mock `windowFocus.js` keep working); a detach cancels pending decisions through
the controller's lifecycle count. Work records the observation gap count when last seen active, and
an older count at its end makes the notice not live. A write to the user-state document first takes in
what another window saved, which covers every field; `setPluginSetting`, `recordRecentCommandId` and
`registerKeybinding` now build on the document they receive. On Linux the shell speaks the
notification protocol itself, because notify-rust neither listens for `ActivationToken` nor exposes
the connection it may be addressed to, and hands the token to GDK before presenting the window.
`shell-s3d-decisions-repaired.json` holds the checks failing by assertion on 29028ddad8.
`shell-s3d-decisions-preserved.json` holds the new checks with no fail-before claim: behaviour that
already held, new mechanisms with no before-state, and the Rust token path. All four earlier evidence
files are rerun with their original bases, because their test files changed. The same temporary
adapters apply, and `--before-root` keeps before-states in the session's temporary folder.

The events repair closes five items found on 67cd75a153. A clicked notification now remembers its
target: `primitives/modality.ts` reports when the last modal has closed, the pill opens the target
then, and an agent request's own security dialog gives way (`AuthorizationHost.yieldDialogTo`). The
Shell no longer closes the panel when navigation lands on the area already shown, because closing
Settings does exactly that. Each end has its own id (`endEventId`), built from engine time so windows
agree, and the shell's ledger is bounded by count instead of expiring. An end not observed live is no
longer announced (`silent` on the toast, and the AI engine announcer holds that state). A save from
another window during an edit restarts the edit, using the stored text already kept as the revision;
localStorage has no compare-and-set, so a save between that check and the write is a named limit.
`shell-s3d-events-repaired.json` holds the checks failing by assertion on 67cd75a153, and
`shell-s3d-events-preserved.json` the new checks with no fail-before claim. All six earlier evidence
files are rerun with their original bases, because their test files changed. The same temporary
adapters apply.

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
      "behaviour": "The shell shows each event id once however many windows report it, zero clears the badge, and the overlay clears at zero and rejects a wrong size.",
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
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Finished work may notify only after at least 60 seconds of observed active work, measured separately for each indexing or transition episode, install, and operation. A shorter completion stays in the pill and Earlier; an unknown duration stays inside. Failures notify at any duration, subject to the existing focus, setting and replay rules.",
          "label": "agent choice"
        }
      ],
      "removedChecks": "In StatusPill.notices.test.ts, 'DE5 long work finishing notifies once, by its row', 'DE5 long work a witnessed transition settling notifies; a first load in the settled state does not', and 'DE5 long work an AI install completing or failing notifies; finding one already online does not': the positive completion expectations without 60 seconds of observed active work are retired. In noticePolicy.test.ts, the work-finished cases of both 'DE5 routeDesktopNotice' parameterized checks and the completion example in 'DE5 words finished and failed work is found by its row, or by its words when it has none' no longer use an unknown-duration completion as their input. Adding 60 seconds changes these preconditions, even where assertions stay the same. The following New entry records the replacement examples; the earlier New duration entries record the short/unknown-duration regressions and failure/replay checks.",
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md; notification, notice, long work, duration, 60 seconds): none requires completions with short or unknown observed duration to notify."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing positive completion examples now require 60 seconds of observed active work for operations, transitions and installs. The work-finished routing cases and the words example supply that duration explicitly. Component checks offset Date.now while real timers run. Their notification, focus, setting, replay and row assertions remain, with current event ids. This is a change of duration preconditions, not an Adaptation. The cited preservation evidence records execution and passing assertions, with no assertion fail-before claim against the duration baseline 32149108492c1f93c6d9227774ef49a975b44390.",
      "sources": [
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
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work finishing notifies once, by its row",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work a witnessed transition settling notifies; a first load in the settled state does not",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work an AI install completing or failing notifies; finding one already online does not",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-finished: a focused window keeps it in the app, in every setting",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 routeDesktopNotice work-finished: in the background Off is silent, On has no sound, On with sound has it",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 words finished and failed work is found by its row, or by its words when it has none"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "The decision to notify uses the window's focus at send time: a focus read that a focus change overtook is read again, so a background result older than focus returning sends nothing and a focused result older than focus leaving still notifies.",
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
          "choice": "A focus read older than the latest focus change is discarded and read again until a result is current, in both directions, so a request is neither sent to a person who is looking nor lost when they look away during the check.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request a background result older than focus returning to the window sends nothing",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request a focused result older than the window losing focus is read again, so the request still notifies",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request focus changing again during the second read keeps reading until a result is current"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "Detaching the pill cancels its pending notification decisions, even when it reattaches before the focus check answers.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request C10 detaching and reattaching while the focus check waits cancels the old decision"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts"
      ],
      "class": "New",
      "behaviour": "Work whose end was seen only across a gap in observation raises no desktop notification, finished or failed, for transitions, indexing, installs and operations; the pill and Earlier still show it.",
      "sources": [
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
          "choice": "A desktop notification needs the end of the work to be observed live: the work was seen active, with no gap in observation, up to the observation that shows it ended. A gap is lost engine contact, a failed read of that operation, or the pill being detached. This holds for failures as well as completions; the Problems section still reports a failure.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation a transition finished while disconnected is silent, and Earlier still lists it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation indexing finished while disconnected is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an install finished while disconnected is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an install that failed while disconnected is silent, its end was not observed either",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an operation complete while disconnected is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an operation failed while disconnected is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an operation ending behind a failed read is silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an operation ending across a detach is silent",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 work duration C6 work whose end was not observed stays silent, finished or failed"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "A gap does not silence work that was seen active again and then ended live, and a poll lagging while contact holds is not a gap.",
      "sources": [
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
          "choice": "A lagging poll with engine contact held is not a gap in observation: timers are throttled in the minimised window a notification is for, so counting it would silence real completions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation a poll that lagged while contact held is not a gap, so the end still notifies",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation work seen active again after the gap ends live, so it notifies",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an install seen active again after the gap ends live, so it notifies",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 work ending across a gap in observation an operation seen running again after the gap ends live, so it notifies"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts"
      ],
      "class": "New",
      "behaviour": "A write from one window keeps what another window saved, in every field of the user-state document: the edit applies to the latest stored document, and the writing window takes in the other window's values.",
      "sources": [
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
          "choice": "The fix is at the document layer: each write first re-reads the stored document and takes in another window's save, then applies the edit, so every field is covered. Producers build on the document they receive. The stored format does not change. The writing window sees the other window's values from that write on; windows do not otherwise update each other live.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window keeps Off chosen in another window",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window keeps the other window's value in every kind of field: cross-profile, per plugin and per profile",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window keeps both windows' entries when both add to the same list",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window keeps both windows' keybindings when both add one",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window leaves the window it is made in up to date with what it kept",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window that changes nothing still leaves the window up to date",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window a later write from the first window keeps the value too",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 profiles across windows a window stays on its profile, and its edit lands there, while storage keeps the other window's choice",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 profiles across windows a profile created in another window survives a write here",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 profiles across windows a window whose profile another window deleted moves to the stored one instead of bringing it back"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts"
      ],
      "class": "New",
      "behaviour": "The re-read leaves alone what must not change: a window's own later choice wins its field, a window stays on its profile, a lone window keeps a session-only edit, a newer build's document is not adopted, and the stored format is unchanged.",
      "sources": [
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
          "choice": "A window keeps showing its own profile when another window switches, so an edit lands on the profile on screen, while storage keeps the other window's choice until this window switches. A window whose profile was deleted elsewhere moves to the stored one.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 a write from one window keeps writing what it chose over the same field from earlier",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 profiles across windows switching profile in the first window then takes over the stored choice",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 profiles across windows deleting the profile another window chose does not leave storage pointing at it",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 what the re-read leaves alone one window alone keeps a session-only edit whose save failed (F10)",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 what the re-read leaves alone a stored document from a newer build is not adopted (F9)",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 what the re-read leaves alone the stored format is unchanged: the same version and fields as before"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/utils/windowFocusChange.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "Every change in the window's focus is reported, from the DOM and inside the shell from the native signal, and stopping ends the watch, even before the native subscription lands, so a remount watches once.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE5",
          "quote": "Notices only when you're not looking: in-app notice only for needs-you items when you're elsewhere in the app; nothing for what is on screen; OS notification only when the app is in the background, for agent requests waiting and long work finished or failed; taskbar badge counts needs-you; Settings › General › Notifications: Off / On / On with sound.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-preserved.json",
      "newChecks": [
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange reports focus, until stopped",
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange reports blur, until stopped",
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange reports visibilitychange, until stopped",
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange inside the shell also reports the native signal, and stopping removes it",
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange C10 stopping before the native subscription lands still removes it",
        "modules/ui-web/src/utils/windowFocusChange.test.ts#DE5 onWindowFocusChange outside the shell asks for no native signal",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 an agent request C10 a remount watches focus once: the old watch is stopped"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "On Linux a click carries the activation token the notification server sent before it for that notification; a server that sends none gives a click without one; closing, another action and another notification's signals are not this click; the sound hints are unchanged.",
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
          "choice": "A clicked notification raises the window where the desktop allows: the shell hands the server's activation token to the toolkit before presenting the window, and without a token it requests user attention. A compositor that still refuses is a named limit, not a failure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3d-decisions-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/notices.rs#notices::tests::a_click_carries_the_activation_token_sent_before_it",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::a_click_from_a_server_without_tokens_has_none_so_the_window_asks_for_attention",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::signals_about_another_notification_are_not_this_click",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::closing_or_another_action_is_not_a_click",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::the_notification_asks_for_the_platform_sound_only_with_sound_on"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "workFinishedNotice and workFailedNotice take whether the end was observed live as their second argument, where it used to be fixed to true. Existing checks pass true and every assertion is unchanged. Every check in the file is rerun in shell-s3d-preserved.json and shell-s3d-duration-preserved.json, or shell-s3d-duration-repaired.json for its fail-before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "A desktop notification needs the end of the work to be observed live: the work was seen active, with no gap in observation, up to the observation that shows it ended.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts"
      ],
      "class": "New",
      "behaviour": "A notification clicked while a dialog is open leaves the dialog in place and remembers the target, which opens when the last blocking dialog closes, including through the real Settings window and its return to the area under it. An agent request's own security dialog gives way to the request clicked. A target that expired or was answered meanwhile opens no review and the panel says so.",
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
          "choice": "A clicked notification brings the app forward. Its target is remembered and opens when the blocking dialog closes, or at once where nothing blocks. With one agent request's review open, whether in the panel or in its security dialog, the review moves to the request clicked and the first keeps waiting; a dialog the person started stays. A target that expired or was answered meanwhile opens nothing, and the panel says so.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts#DE5 a notification clicked with Settings open leaves Settings where it is, and opens the review once Settings closes",
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts#DE5 a notification clicked with Settings open a request answered before Settings closes opens no review and says so in the panel",
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts#DE5 a notification clicked with Settings open the return to the area under Settings landing after the panel opened leaves it open",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification a request answered since says so in the panel, and opens no review",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open waits for the dialog, then opens the review",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open waits for the last of two dialogs",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open keeps waiting when another dialog follows the closed one in the same turn",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open the latest click is the one remembered",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open a request answered meanwhile opens no review and says so in the panel",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open a request that expired meanwhile opens no review and says so in the panel",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open with a review open, a request that has gone leaves the review where it is and says so",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open with a request's security dialog open, the review moves to the request clicked, and the first keeps waiting",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open with a request's security dialog open, a typed request clicked takes the dialog"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts"
      ],
      "class": "New",
      "behaviour": "What the click must not disturb: another area still closes the panel, a detach forgets the click, a row that is there says nothing about having gone, a review open in the panel moves to the request clicked, a dialog the person started stays, and clicking the request whose dialog is open keeps it.",
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
          "scenario": "C10",
          "quote": "Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "A clicked notification brings the app forward. Its target is remembered and opens when the blocking dialog closes, or at once where nothing blocks. With one agent request's review open, whether in the panel or in its security dialog, the review moves to the request clicked and the first keeps waiting; a dialog the person started stays. A target that expired or was answered meanwhile opens nothing, and the panel says so.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts#DE5 a notification clicked with Settings open moving to another area still closes the panel",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification a row that is there says nothing about it having gone",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open C10 a detach forgets the click",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open with a review open, the review moves to the request clicked",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open a dialog the person started is not an agent request's: it stays, and the target waits for it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 clicking a notification while a dialog is open clicking the request whose dialog is open keeps that dialog"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "Two ends in the same words are two events, each notifying: AI install failures a second apart, installs completing, and search settling with or without a change in the engine's indexing state. A second window seeing the same end later gives the same id, and a click finds the row by its words.",
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
          "choice": "Each real event has its own id, the same in every window that sees it: an agent request or operation by its own key, an install by the engine's start time, and search settling by the time the engine's indexing state last changed plus a count of settlings at that time. The shell's dedupe only collapses one event seen from two windows.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event two AI install failures a second apart each notify",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event two installs completing each notify",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event two settlings at different engine times each notify",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event a settling that changed nothing in the engine's indexing state is still its own event",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event a second window seeing the same end later gives the same id, so the shell shows it once",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 each end is its own event clicking one lands on its row by its words"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "An end's id carries its time and gives back its words, and the shell shows two failures of the same kind as two events.",
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
          "choice": "Each real event has its own id, the same in every window that sees it: an agent request or operation by its own key, an install by the engine's start time, and search settling by the time the engine's indexing state last changed plus a count of settlings at that time. The shell's dedupe only collapses one event seen from two windows.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 the id of an end listed by its words each event has its own: the same words at another time are another id",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 the id of an end listed by its words the same event has the same one, and it finds the row by its words",
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 the id of an end listed by its words an id without a time is its own words",
        "modules/shell/src-tauri/src/notices.rs#notices::tests::two_failures_of_the_same_kind_are_two_events"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The same event id never shows twice while the ledger remembers it, however long ago it was claimed. The before-state patch (compatibility/shell-s3d-ledger-before.patch) adds only the check's constant and a clock with each claim one window after the last, standing in for time a check cannot wait out.",
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
          "choice": "The same event id never delivers twice while the ledger remembers it. The ledger is bounded by count, not by time: it keeps the latest 500 ids and forgets the oldest.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/notices.rs#notices::tests::an_event_never_shows_twice_while_the_ledger_remembers_it"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The ledger stays bounded, by count: it keeps the latest ids and forgets the oldest.",
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
          "choice": "The same event id never delivers twice while the ledger remembers it. The ledger is bounded by count, not by time: it keeps the latest 500 ids and forgets the oldest.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/notices.rs#notices::tests::the_ledger_stays_small_by_forgetting_the_oldest_event"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "An end not observed live is not announced, for transitions, indexing, installs and operations, in the toast announcer and the AI engine announcer; Earlier still lists it.",
      "sources": [
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
          "choice": "Live-region announcements follow the same rule as desktop notifications: an end not observed live is not announced, and Earlier still lists it. The words of the end, and the AI engine's state that end left, stay unsaid until that state moves on. The current state read back after a reconnect (Search available, Indexing queue empty) is not an end and stays.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications a transition finished while disconnected says nothing, and Earlier lists it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications indexing finished while disconnected says nothing, and Earlier lists it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications an install that failed while disconnected says nothing, in either announcer, and Earlier lists it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications an install completed while disconnected says nothing, and Earlier lists it",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications an operation finished while disconnected says nothing, and Earlier lists it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "The silence ends with the unobserved end: the engine state speaks again once it moves on, and an end observed live is still announced.",
      "sources": [
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
          "choice": "Live-region announcements follow the same rule as desktop notifications: an end not observed live is not announced, and Earlier still lists it. The words of the end, and the AI engine's state that end left, stay unsaid until that state moves on. The current state read back after a reconnect (Search available, Indexing queue empty) is not an end and stays.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications the engine state speaks again once it moves on from the unsaid end",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#C6 live regions follow the same rule as notifications an end observed live is still announced"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts"
      ],
      "class": "New",
      "behaviour": "A save from another window while an edit is being made survives it: a plugin value, values in the same plugin, a deleted profile, and with an edit that must persist first.",
      "sources": [
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
          "choice": "The stored text the document already keeps is the revision for an optimistic check: a save from another window while an edit is being made restarts the edit on that save, a bounded number of times. localStorage offers no compare-and-set, so a save landing between that check and the write is a named limit; the stored format and the backend do not change.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves a plugin value another window saves while this window is writing survives",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves both windows' values in the same plugin survive",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves a profile another window deletes while this window is writing stays deleted",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves a save that must land first still builds on the other window's save"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts"
      ],
      "class": "New",
      "behaviour": "The restart stays bounded: an edit the other window's save made pointless writes nothing over it, and a window that never stops saving cannot hold an edit forever.",
      "sources": [
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
          "choice": "The stored text the document already keeps is the revision for an optimistic check: a save from another window while an edit is being made restarts the edit on that save, a bounded number of times. localStorage offers no compare-and-set, so a save landing between that check and the write is a named limit; the stored format and the backend do not change.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-events-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves an edit the other window's save made pointless stays one, and writes nothing over it",
        "modules/ui-web/src/shell-v0/state/UserStateDocument.multiWindow.test.ts#C11 overlapping saves a window that never stops saving cannot hold an edit forever: the edit lands"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Each real event has its own id, the same in every window that sees it: an agent request or operation by its own key, an install by the engine's start time, and search settling by the time the engine's indexing state last changed plus a count of settlings at that time. The shell's dedupe only collapses one event seen from two windows.",
          "label": "agent choice"
        }
      ],
      "removedChecks": "In noticePolicy.test.ts, 'DE5 words finished and failed work is found by its row, or by its words when it has none': the assertion that a completion without a row uses the bare words 'Search is up to date' as its id is retired. In StatusPill.notices.test.ts, 'DE5 long work a witnessed transition settling notifies; a first load in the settled state does not', 'DE5 short work stays inside 59 seconds stays in the pill and Earlier; a later 60 second run notifies once', and 'DE5 short work stays inside indexing durations reset between runs, so repeated short runs stay silent': the exact bare-words id expectations are retired, and their replacement inputs supply the engine's indexing-state time. The following New entry records the time-bearing id expectations. Passing explicit ids to the policy functions accompanies this change; it does not preserve the old words-based id oracle.",
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md; notification, notice, event id, dedup, indexing): none requires different events with the same words to share an id or a bare-words id for a completion without a row."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "A completion without a row carries its event id rather than using its words alone: the policy words check expects the supplied time-bearing id, and the three component checks expect Search is up to date@2026-11-06T10:00:00Z#0 after supplying the engine time. Their other notification and duration assertions remain. Every replacement check passed in the cited evidence, which has no fail-before run against the event baseline 67cd75a1533d174c9facd7770991bacbe3996244; these replacements are not marked repaired. Separate New entries cover distinct events and row lookup with their own evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Each real event has its own id, the same in every window that sees it: an agent request or operation by its own key, an install by the engine's start time, and search settling by the time the engine's indexing state last changed plus a count of settlings at that time. The shell's dedupe only collapses one event seen from two windows.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3d-duration-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/noticePolicy.test.ts#DE5 words finished and failed work is found by its row, or by its words when it has none",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 long work a witnessed transition settling notifies; a first load in the settled state does not",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside 59 seconds stays in the pill and Earlier; a later 60 second run notifies once",
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#DE5 short work stays inside indexing durations reset between runs, so repeated short runs stay silent"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/notices.rs#cfg(test)"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The same event id never delivers twice while the ledger remembers it. The ledger is bounded by count, not by time: it keeps the latest 500 ids and forgets the oldest.",
          "label": "agent choice"
        }
      ],
      "removedChecks": "In notices.rs, 'notices::tests::a_claim_expires_so_later_work_can_notify_and_the_ledger_stays_small': the expectation that finished:search can be claimed again at CLAIM_WINDOW (60 seconds), with an expired ledger entry pruned so its size is one, is retired. The replacement New entries are 'notices::tests::an_event_never_shows_twice_while_the_ledger_remembers_it' (shell-s3d-events-repaired.json, assertion fail-before on 67cd75a1533d174c9facd7770991bacbe3996244) and 'notices::tests::the_ledger_stays_small_by_forgetting_the_oldest_event' (shell-s3d-events-preserved.json, not repaired). The explicit clock inputs also leave 'notices::tests::an_event_reported_by_two_windows_shows_once' (including the second claim at now + 5 ms) and 'notices::tests::different_events_each_show'; their current successive-claim expectations are covered by the existing New shell-ledger entry and shell-s3d-preserved.json, not by a claim that the old temporal preconditions survive. NoticeLedger::claim dropping its time parameter accompanies a replacement of the time-expiry oracle with a count-bounded ledger.",
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md; notification, notice, ledger, dedup, expiry, event id, 60 seconds): none requires a notification claim to expire after 60 seconds or a ledger bounded by time rather than the latest 500 event ids."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1aeea-7908-73c2-bad0-394b9f52416e",
  "verdict": "accept",
  "digest": "sha256:bf69474e4573c7c7fa238b077fef7d57c855a305d10a2bac04acc2d68240c484",
  "note": "Round 4: replaced expectations recorded as Obsolete with sources and conflict searches; replacements New with passing evidence; repaired checks fail before by assertion. Earlier rounds accepted or rejected this changeset as product repairs proceeded (01a1adf4-a4eb-72a1-9814-5a066c10294d accept, 01a1ae3c-a32b-7d42-9fd9-a7afa4e79ce8 reject, 01a1ae99-bda4-74a3-98f2-1387202e1373 reject). Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
