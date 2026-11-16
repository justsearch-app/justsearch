---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: builder-fm-s7b-20261116
---

# Slice 7b test intent: AI Brain's install flow

AI Brain's install flow follows the agreed pictures fm-11 (consent), fm-12 (installing) and fm-12b (a
stopped download), with the orchestrator's delegated decisions G1-G6 as revised after the challenger
(G2'-G6'), and one classification fix (G6'): a first install that stopped before any final model file
was placed is "not installed", never "Installed — repair available". The sources are the pictures'
words as owner-adopted design (quoted in each entry, because tmp/design is not in git), the owner's
decision "Local test only (Recommended)" (no internet download in any check), and the decisions as
delegated choices under the implementation delegation.

The page checks render the statuses the Engine really publishes. The new Java test
InstallFlowStatusLoopbackTest drives the real AiInstallService, curl transport, resume records and
placement against a loopback server (no network): multi-file parts, Pause while a file finishes, a
dropped connection whose resume also fails, cancel, a failed run, restarts and a new run after a
failure. It records the status, model presence and plan preview JSON into
brain-install/__fixtures__/engine-install-statuses.json (when JUSTSEARCH_INSTALL_FIXTURE_OUT names the
file) and on every other run compares what the Engine publishes with that file (states, phases, pause,
error codes, part and stage states, presence, preview components), so the fixtures cannot drift from
the Engine silently. The Engine is unchanged (G1), so this check passes on origin/main too; its
evidence, gates/test-intent/evidence/fm-brain-install-engine.json, records that.

The page evidence, gates/test-intent/evidence/fm-brain-install.json, was written by run-evidence with
--before origin/main (dea8a9f97b), one --check per file, with the fixture file overlaid. In the new
BrainInstall.flow.test.ts 21 of 25 cases fail on origin/main, every one by assertion on its claimed
behaviour (old consent words, no parts list, "AI install failed" instead of the stopped view, no Try
again or Cancel install, "Pause download", "Installing… 24%", and the G6' defect itself: after a restart
that followed a cancelled first install with nothing placed, the base page offers Repair). Four cases
hold on the base and guard the candidate: Cancel starts nothing and Accept starts the install, unknown
sizes block Accept, a cancelled first install in the same session is not installed, a completed run
shows no flow. In BrainInstall.focus-lockdown.test.ts five of eight cases fail on the base by
assertion; Escape/Cancel focus restoration and the consent's ladder cases hold there. Every modified
file fails on the base by assertion on the changed expectation only.

Choices this builder made inside the decisions, named for the acceptor: sizes below 1 MB in the
consent and progress show whole KB (the decisions name MB and GB only; the loopback fixtures are KB
sized); the paused body keeps "kept on disk" and drops only the promise of continuing; the "Downloaded"
note on a step whose files are on disk before AI is installed (S7b-6 "the parts already placed show as
installed under their steps"); positive final-file evidence also counts the Engine's installedFully, a
running ONNX model, and an Engine verdict that already implies an installation (online, offline,
starting, indexing, background), because each is only reached from placed files; a run that just
failed or was cancelled counts as an installation needing repair only when this page saw an installed
AI before or the runtime runs a model (only an activated install has one). Words without an exact source (the phase lines "Preparing the download" / "Setting up the
downloaded parts", the toast "Install cancelled. Completed parts stay on this computer.", the
announcements) are asserted where a decision states them and otherwise by meaning. No expected value is
computed by production code: the part sizes are the shipped registry's byte counts and the pictured
words. This builder wrote no acceptance record.

A second builder session (builder-fm-s7b-20261116b) closed three gaps the orchestrator named against the
pictures and decisions. Gap 1 (fm-12b, G5'): the top bar's status entry follows the stopped view. While a
failed run with a part missing is shown it reads "1 Download stopped" in amber (Needs you's tone and
count, its words the row's own), and the status panel row says "Download stopped" with the stopped view's
sentence; "Cancel install" closes that run for the whole window (the page and the bar share it), after
which the bar returns to normal and an identical failed poll stays closed, while a later run's failure
shows again. The bar decides from the install status (a run part not reported placed) and also follows
the page when its model-presence evidence shows a part missing. New check BrainInstall.topBar.test.ts.
Gap 2 (G4'): over bytes an earlier download left, the action is "Install AI · N" (N the most it can
download, staged bytes included, as the consent's "Up to"), never "Resume Download"; a paused run keeps
"Resume" and "Cancel". Gap 3: during a first install and while a download is stopped, the install view is
the page: no steps, graphics card, Advanced settings or reading progress; they return when it is gone,
and focus that was on the page (Accept's restored focus, a step) moves to Pause or the heading.

Named for the acceptor: (a) the install view stands alone for a first install and a stopped download; a
repair or additions run over an existing installation keeps its steps, because the unchanged repair-run
checks (BrainSurface.repairRun.test.ts) pin per-step states during those runs and the pictures show a
first install; (b) the action keeps the shared formatter ("Install AI · 2.60 GB"), as slice 7a's checks
pin "Install AI · 2.51 GB"; (c) the failure toast "AI install failed" and its desktop notice are unchanged
(G5': toast severity unchanged), so for its few seconds that notice can stand beside "1 Download stopped";
(d) after Cancel install the closed run asks nothing in the bar, and work waiting only for AI is calm, as
for AI nobody asked for. The page evidence was re-run with --before origin/main and two overlays: the
fixture file, and state/installFlow.ts, the new module the page checks reset the window's Cancel install
through; origin/main's page and bar never import it, so it only lets the files load there. Four
modified older checks follow the gaps: BrainSurface.pausedInstall.test.ts (gap 2),
BrainSurface.liveRepairs.test.ts and BrainOverview.install.test.ts (gap 3) and the type-ladder census
in src/boot/type-ladder-lockdown.test.ts (150 to 142: the fixture's Brain is a first install that runs).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "AI Brain's install flow rendered from the Engine's recorded statuses: the consent's five parts with page names, sizes, licences and Terms, Cancel starting nothing and Accept starting the install, \"Up to\" with staged bytes (S7b-1); unknown sizes and a missing manifest block Accept with a reason, a missing licence says so (S7b-10); progress heading, amounts, time left only when estimated, Done only after model presence observed after the part's last file, Waiting or a stage's refusal reason (S7b-2); Pause/Resume words and Cancel's toast without a promise of continuing (S7b-3); the stopped view with Done/Stopped/Waiting, Try again, Cancel install and Detail for support, no red, no connection claim (S7b-4); Try again's run covering only what is missing (S7b-5); Cancel install bound to the failed run (S7b-6); not installed after a first install that stopped with nothing placed, partial-only, supporting-only, unknown presence (S7b-7, G6'); restart reconstruction (S7b-8). Bytes an earlier download left: the action is \"Install AI\" with the most it can download, named so in its accessible name, never \"Resume Download\" or a promise to continue (G4', gap 2); a first install that runs, a paused run and a stopped download show only the install view, without steps, graphics card or Advanced settings, and the steps return after completion and after Cancel install (gap 3).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12b badge \"Download stopped\"; \"The download stopped\" \"The connection was lost. What is already here is kept, and search by exact words keeps working.\" \"1.3 of 2.6 GB\"; per part \"Done\" / \"Stopped at 120 of 956 MB\" / \"Waiting\"; buttons \"Try again\", \"Cancel install\". Note: \"One sentence says what happened and what is kept. Nothing is teal because nothing is running; the one filled action is Try again; the status asks for you in amber. Not checked against the Engine: whether it keeps partial downloads and whether it retries by itself first.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Local test only (Recommended)",
          "location": "task t-20261113-fmimpl user turn of 2026-11-16 (download question)",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G1 Scope: page-only plus one classification fix (G6). Engine work deferred and named: immediate Pause, a structured failure category (to say \"the connection was lost\"), keeping partials when a resumed transfer fails, a \"dismiss failed run\" transition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G5/G5' Stopped (fm-12b), shown for a failed run with parts missing: amber, \"The download stopped.\" then \"Completed parts stay on this computer, and search by exact words keeps working.\" (no \"The connection was lost\"); per part \"Done\" / \"Stopped\" (the last reported \"a of b\" only as history: \"Stopped (had a of b)\") / \"Waiting\"; \"Try again\" (starts a new run that reuses what it can; no words promising it continues) and \"Cancel install\" (a page-local dismissal bound to that failed run's identity, start time + state: an identical failed poll stays dismissed, a later run's failure shows again; after dismissal the page shows what disk evidence says, installed parts listed). \"Detail for support\" keeps the raw diagnosis behind a disclosure. Toast severity unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G6/G6' After a cancelled or failed first install with no file placed, the page is \"not installed\", never \"Installed — repair available\": the repair heading needs positive final-file presence (model presence of a final file, not staging or \"incomplete\"); partial-only, supporting-file-only and unavailable observations are separate cases (not installed / not installed / unknown); the manual-remedy branch follows the same rule. Precedence: a failed first install with nothing placed shows the stopped view while that run is shown; after dismissal or restart, \"not installed\".",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Checks use status shapes the Engine really sends: a Java test drives the real install service and transport against a loopback server (no network) for multi-file parts, pause while a file finishes, a dropped connection, a failed resume, cancel and a failed run, and records the status JSON it publishes as fixtures the page tests render.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 2 (G4'): \"while paused the buttons are \\\"Resume\\\" and \\\"Cancel\\\"; today the paused state still has \\\"Resume Download\\\". Change it (and its accessible name) and any words implying continuing that the Engine does not back.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 3: \"fm-12 and fm-12b show only the install view (header, sentence, overall line, one row per part, buttons): the page's steps are not shown during an install or while the stopped view is shown; they return when the install view is gone (installed, cancelled, dismissed). Focus must not be lost when the view switches.\"",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-install.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.focus-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "In headless Chromium with the real AI state store: Escape and Cancel return focus to Install AI and Accept is reachable by Tab; Pause keeps focus as it becomes Resume and the paused words are announced politely; progress is announced once per tenth; focus moves to the heading on failure, to Pause after Try again, to the heading on completion and after Cancel install; light and dark at 600 px nothing overflows or leaves the size and corner ladders, the stopped view has no error alert, no teal bar and Try again as its only filled action; the consent stays on the ladders at 600 px. When the install view takes the page focus is not lost: after Accept and download, and from a focused step when a run starts, focus moves to Pause (gap 3).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12b badge \"Download stopped\"; \"The download stopped\" \"The connection was lost. What is already here is kept, and search by exact words keeps working.\" \"1.3 of 2.6 GB\"; per part \"Done\" / \"Stopped at 120 of 956 MB\" / \"Waiting\"; buttons \"Try again\", \"Cancel install\". Note: \"One sentence says what happened and what is kept. Nothing is teal because nothing is running; the one filled action is Try again; the status asks for you in amber. Not checked against the Engine: whether it keeps partial downloads and whether it retries by itself first.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Local test only (Recommended)",
          "location": "task t-20261113-fmimpl user turn of 2026-11-16 (download question)",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G5/G5' Stopped (fm-12b), shown for a failed run with parts missing: amber, \"The download stopped.\" then \"Completed parts stay on this computer, and search by exact words keeps working.\" (no \"The connection was lost\"); per part \"Done\" / \"Stopped\" (the last reported \"a of b\" only as history: \"Stopped (had a of b)\") / \"Waiting\"; \"Try again\" (starts a new run that reuses what it can; no words promising it continues) and \"Cancel install\" (a page-local dismissal bound to that failed run's identity, start time + state: an identical failed poll stays dismissed, a later run's failure shows again; after dismissal the page shows what disk evidence says, installed parts listed). \"Detail for support\" keeps the raw diagnosis behind a disclosure. Toast severity unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S7b-9 Keyboard, screen reader announcements of progress at a polite rate, focus kept on Pause/Resume, light and dark, 600 px; Escape/Cancel focus restoration and focus after retry, dismissal, failure and completion (challenger additions adopted in the revised decisions).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Checks use status shapes the Engine really sends: a Java test drives the real install service and transport against a loopback server (no network) for multi-file parts, pause while a file finishes, a dropped connection, a failed resume, cancel and a failed run, and records the status JSON it publishes as fixtures the page tests render.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 3: \"fm-12 and fm-12b show only the install view (header, sentence, overall line, one row per part, buttons): the page's steps are not shown during an install or while the stopped view is shown; they return when the install view is gone (installed, cancelled, dismissed). Focus must not be lost when the view switches.\"",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-install.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.focus-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.topBar.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "The real status pill beside the AI Brain page, both reading one AI state built from the Engine's recorded statuses: a failed run with a part missing, and a failed first install with nothing placed, show in the top bar as \"1 Download stopped\" in amber (warning tone, count 1), the status panel row says \"Download stopped\" with \"Completed parts stay on this computer, and search by exact words keeps working.\" and not \"AI install failed\"; after Cancel install the entry returns to normal (no Download stopped, no Needs you, no count), the identical failed poll stays closed, a later run's failure shows \"Download stopped\" again; without the AI Brain page the bar decides from the install status alone.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12b badge \"Download stopped\"; \"The download stopped\" \"The connection was lost. What is already here is kept, and search by exact words keeps working.\" \"1.3 of 2.6 GB\"; per part \"Done\" / \"Stopped at 120 of 956 MB\" / \"Waiting\"; buttons \"Try again\", \"Cancel install\". Note: \"One sentence says what happened and what is kept. Nothing is teal because nothing is running; the one filled action is Try again; the status asks for you in amber. Not checked against the Engine: whether it keeps partial downloads and whether it retries by itself first.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G5/G5' Stopped (fm-12b), shown for a failed run with parts missing: amber, \"The download stopped.\" then \"Completed parts stay on this computer, and search by exact words keeps working.\" (no \"The connection was lost\"); per part \"Done\" / \"Stopped\" (the last reported \"a of b\" only as history: \"Stopped (had a of b)\") / \"Waiting\"; \"Try again\" (starts a new run that reuses what it can; no words promising it continues) and \"Cancel install\" (a page-local dismissal bound to that failed run's identity, start time + state: an identical failed poll stays dismissed, a later run's failure shows again; after dismissal the page shows what disk evidence says, installed parts listed). \"Detail for support\" keeps the raw diagnosis behind a disclosure. Toast severity unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 1 (G5'): \"fm-12b shows in the top bar an amber status badge \\\"Download stopped\\\" (picture text: \\\"1 Download stopped\\\") while a failed run with parts missing is shown; today the bar says \\\"AI install failed\\\" in red. Make the top bar's status entry follow the stopped view (amber, \\\"Download stopped\\\"), and return to normal after \\\"Cancel install\\\" / a later run, consistent with G5'.\"",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-install.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.topBar.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/InstallFlowStatusLoopbackTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "The real install service, curl transport, resume records and placement against a loopback server publish the statuses the page renders: multi-file parts with the per-file installed between files, Pause reported at once while a file finishes, no next file while paused, completion; cancel with nothing placed and with a part placed, then after a restart; a dropped connection whose resume also fails (retries, the part fails, later parts continue, the run fails), then a new run whose total covers only the missing part; every file failing verification with nothing placed. Each run compares the recorded shapes with the page fixtures.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Local test only (Recommended)",
          "location": "task t-20261113-fmimpl user turn of 2026-11-16 (download question)",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G1 Scope: page-only plus one classification fix (G6). Engine work deferred and named: immediate Pause, a structured failure category (to say \"the connection was lost\"), keeping partials when a resumed transfer fails, a \"dismiss failed run\" transition.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Checks use status shapes the Engine really sends: a Java test drives the real install service and transport against a loopback server (no network) for multi-file parts, pause while a file finishes, a dropped connection, a failed resume, cancel and a failed run, and records the status JSON it publishes as fixtures the page tests render.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G5/G5' Stopped (fm-12b), shown for a failed run with parts missing: amber, \"The download stopped.\" then \"Completed parts stay on this computer, and search by exact words keeps working.\" (no \"The connection was lost\"); per part \"Done\" / \"Stopped\" (the last reported \"a of b\" only as history: \"Stopped (had a of b)\") / \"Waiting\"; \"Try again\" (starts a new run that reuses what it can; no words promising it continues) and \"Cancel install\" (a page-local dismissal bound to that failed run's identity, start time + state: an identical failed poll stays dismissed, a later run's failure shows again; after dismissal the page shows what disk evidence says, installed parts listed). \"Detail for support\" keeps the raw diagnosis behind a disclosure. Toast severity unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G6/G6' After a cancelled or failed first install with no file placed, the page is \"not installed\", never \"Installed — repair available\": the repair heading needs positive final-file presence (model presence of a final file, not staging or \"incomplete\"); partial-only, supporting-file-only and unavailable observations are separate cases (not installed / not installed / unknown); the manual-remedy branch follows the same rule. Precedence: a failed first install with nothing placed shows the stopped view while that run is shown; after dismissal or restart, \"not installed\".",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-install-engine.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/InstallFlowStatusLoopbackTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json"
      ],
      "change": "added",
      "kind": "test-data",
      "class": "New",
      "behaviour": "The install status, model presence and plan preview JSON the Engine published in InstallFlowStatusLoopbackTest (written by it with JUSTSEARCH_INSTALL_FIXTURE_OUT; every run of it compares its shapes with this file), read by the page tests.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Local test only (Recommended)",
          "location": "task t-20261113-fmimpl user turn of 2026-11-16 (download question)",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Checks use status shapes the Engine really sends: a Java test drives the real install service and transport against a loopback server (no network) for multi-file parts, pause while a file finishes, a dropped connection, a failed resume, cancel and a failed run, and records the status JSON it publishes as fixtures the page tests render.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-install-engine.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/InstallFlowStatusLoopbackTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "The paused body keeps the kept amount and drops \"resuming continues from there\" / \"resuming continues from where it stopped\" (aiVerdict.ts:532)."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/installComponents.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/installComponents.ts#composeTransferLine",
            "modules/ui-web/src/shell-v0/state/installComponents.ts#searchReadyNotice"
          ]
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "The transfer-line (rate and ~time) and search-ready composers are removed with their only caller, the old progress block; fm-12 shows no rate and no search-ready line. Their cases go; the stage-row cases stay."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/installComponents.ts#searchReadyNotice"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Time left as \"about 2 min left\" without a rate; the search-ready case goes with its composer; a refused stage's reason stands on its part's row (the input lists that part); the in-flight part is named by its page name; the toggle is \"Pause\" / \"Resume\"."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.firstRun.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G6/G6' After a cancelled or failed first install with no file placed, the page is \"not installed\", never \"Installed — repair available\": the repair heading needs positive final-file presence (model presence of a final file, not staging or \"incomplete\"); partial-only, supporting-file-only and unavailable observations are separate cases (not installed / not installed / unknown); the manual-remedy branch follows the same rule. Precedence: a failed first install with nothing placed shows the stopped view while that run is shown; after dismissal or restart, \"not installed\".",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "F3: staged bytes or a run record alone are a first install that stopped: \"AI isn't installed yet\", no Repair, Install AI offered; a new F3 case keeps Repair for an interrupted install with a final file placed."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installConsent.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "With staged bytes the consent states \"Up to\" the whole download (staged bytes included) and no resumed amount; without them no \"Up to\"."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Heading \"Installing AI · 25%\" and the paused line instead of \"Installing… 25%\" / \"Paused 25%\"; buttons \"Pause\" / \"Resume\"; the parts list replaces the staged-progress block in the hidden-while-unobserved checks."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Amounts in the unit of the whole, one decimal for a total: \"2.8 of 5.6 GB\" instead of \"2.79 GB\" and \"5.59 GB\"."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G2' Consent: part names follow the page steps (Understand meaning; Related words, with \"helps Understand meaning\"; Best match first; Names of people and places; Checking answers against sources); licence and Terms per part; header \"Download AI models?\" and \"N will be downloaded to this computer. Each part comes from its own author, under the licence shown.\"; buttons \"Cancel\" / \"Accept and download\"; the total says \"Up to N will be downloaded\" when staged partial bytes exist (reuse not guaranteed), else \"N will be downloaded\"; the consent row list follows the actual plan, the five-part list is only the first-run case. The existing promise \"will be resumed, not downloaded again\" goes. Sizes: a consent/progress-only formatting context (MB whole, GB two decimals for parts, one for the total); the shared formatter and its other callers unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress: header \"Installing AI · N%\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/installComponents.ts#searchReadyNotice"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Heading \"Installing AI · N%\" (\"Installing AI\" while planning) and the paused line; \"Pause\" / \"Resume\"; Cancel's toast says what is kept without continuing; the refused stage's reason on its part's row; no search-ready line."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.install.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 3: \"fm-12 and fm-12b show only the install view (header, sentence, overall line, one row per part, buttons): the page's steps are not shown during an install or while the stopped view is shown; they return when the install view is gone (installed, cancelled, dismissed). Focus must not be lost when the view switches.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "fm-12: \"Pause\" and \"Cancel\" together. fm-12 (gap 3): during a first install Best match first is a part row and no step or switch is drawn (was: the Best match first step is drawn during the run)."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.preservation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "fm-12: the button is \"Pause\"."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G4' Pause: since the Engine reports paused at once while the current file may still finish, the words after Pause are \"Paused. The file being downloaded may still finish.\" with Resume and Cancel; no claim of acknowledgement. Toast and post-restart promises of continuing go (aiVerdict.ts:532, BrainSurface.ts:2589, :3791).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 2 (G4'): \"while paused the buttons are \\\"Resume\\\" and \\\"Cancel\\\"; today the paused state still has \\\"Resume Download\\\". Change it (and its accessible name) and any words implying continuing that the Engine does not back.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Over bytes an earlier download left the action is \"Install AI\", never \"Resume Download\" or any promise to resume (was: \"offers to resume rather than to start over\", expecting \"Resume Download\")."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12b badge \"Download stopped\"; \"The download stopped\" \"The connection was lost. What is already here is kept, and search by exact words keeps working.\" \"1.3 of 2.6 GB\"; per part \"Done\" / \"Stopped at 120 of 956 MB\" / \"Waiting\"; buttons \"Try again\", \"Cancel install\". Note: \"One sentence says what happened and what is kept. Nothing is teal because nothing is running; the one filled action is Try again; the status asks for you in amber. Not checked against the Engine: whether it keeps partial downloads and whether it retries by itself first.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 3: \"fm-12 and fm-12b show only the install view (header, sentence, overall line, one row per part, buttons): the page's steps are not shown during an install or while the stopped view is shown; they return when the install view is gone (installed, cancelled, dismissed). Focus must not be lost when the view switches.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "D4 for a failed run: the stopped view is the page, so no step is drawn; Cancel install closes it, then the steps are checked as before (no \"Downloading\", \"Not downloaded\"). The window's Cancel install is reset after each case."
    },
    {
      "items": [
        "modules/ui-web/src/boot/type-ladder-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-12 \"Installing AI · 50%\" \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, gap 3: \"fm-12 and fm-12b show only the install view (header, sentence, overall line, one row per part, buttons): the page's steps are not shown during an install or while the stopped view is shown; they return when the install view is gone (installed, cancelled, dismissed). Focus must not be lost when the view switches.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "The type ladder census is 142 views, not 150: the fixture's AI Brain is a first install that runs, and its page is then the install view with no steps or Advanced settings to open (Brain 1 view per theme instead of 5). The ladder rules themselves are unchanged; the install view is audited on them in BrainInstall.focus-lockdown."
    }
  ]
}
```

## Acceptance records
