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
--before origin/main (dea8a9f97b), one --check per file, with two overlays: the fixture file, and
state/installFlow.ts, the new module the page checks reset the window's Cancel install through
(origin/main's page and bar never import it, so it only lets the files load there). In the new
BrainInstall.flow.test.ts 33 of 37 cases fail on origin/main, every one by assertion on its claimed
behaviour (old consent words, no parts list, "AI install failed" instead of the stopped view, no Try
again or Cancel install, "Pause download", "Installing… 24%", "Resume Download", "resuming continues",
no line while a part is verified, a cancelled first install in this session not offered Install AI, and
the G6' defect itself: the base page offers Repair after a first install that stopped with nothing
placed, and over supporting files alone). Four cases hold on the base and guard the candidate: Cancel
starts nothing and Accept starts the install, unknown sizes block Accept, a completed run shows no flow,
and a cancelled repair run keeps the repair classification (RS3; the base counts any file as an
installation). In BrainInstall.focus-lockdown.test.ts 7 of 10 cases fail on the base by assertion;
Escape/Cancel focus restoration and the two consent ladder cases hold there. Every modified file fails
on the base by assertion.

Choices this builder made inside the decisions, named for the acceptor: sizes below 1 MB in the
consent and progress show whole KB (the decisions name MB and GB only; the loopback fixtures are KB
sized); the paused body keeps "kept on disk" and drops only the promise of continuing; the "Downloaded"
note on a step whose files are on disk before AI is installed (S7b-6 "the parts already placed show as
installed under their steps"); positive final-file evidence also counts the Engine's installedFully, a
running ONNX model, and an Engine verdict that already implies an installation (online, offline,
starting, indexing, background), because each is only reached from placed files. Exact words: "Checking
the downloaded files", "Setting up the downloaded parts", Cancel's toast "Install cancelled. Completed
parts stay on this computer." and the paused body "An earlier download is paused." are asserted exactly
from W7 (delegated choice, cited in the entries that assert them); "Preparing the download" (the line
while a run prepares) has no exact source and no check asserts it; the spoken progress "Installing AI,
N%" is fm-12's heading words with the share, as G3'' allows (cited in the focus check's entry). No expected value is
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

A third builder session (builder-fm-s7b-20261116c) repaired six review findings under the orchestrator's
repair decisions RS1-RS6 and W7 (delegated choices, cited in the entries) and this prose after the
acceptor's reject. Each new check failed on the previous candidate (823fb31438) by assertion for the
reason under test and passes now: RS1 Try again sent only acceptTerms where the accepted consent had
asked to switch Best match first on; RS2 "Installed — repair available" over a complete GPU runtime
package with no model file; RS3 a repair run over a damaged installation found after a restart hid the
steps, and after it failed (and Cancel install) the page said "AI isn't installed yet" and a cancelled
one lost the repair alert; RS4 "AI isn't installed yet" after Cancel install while model presence was
unreadable; RS5 "226 of 927 KB · about 2 min left" while a part was verified; RS6 focus lost to the page
when the new run completed before a poll saw it running, and left on Try again after another failure or
a refused start. The RS1 case "a choice changed since the consent wins" held on the previous candidate
(it never sent the intent) and guards the repair.

Named for the acceptor: (e) an existing installation is a final model file in model presence that the
current or just-ended run did not place (the Engine lists the parts its plan found on disk as installed
with no bytes to download; the run's other parts are its own), or installedFully, a running model, an
installed verdict; so a first install that stops is not an installation in the same session and its
steps stay hidden while it runs, while a run over files already on disk keeps the steps and the repair
classification; (f) a package's "complete" counts as a model file only for the shipped registry's model
packages (each has variants, and the Engine calls them complete only with a variant present; the
unchanged BrainSurface.firstRun F2 reports them so), never for another package such as cuda-runtime;
(g) the completion intent lives in the page: recorded at Accept with the saved choice then, sent by Try
again while that saved choice reads the same, dropped when the person changes the choice on this page
(its switch or a decline), on Cancel install, on Repair and when a run completes; the Engine does not
publish it, so after a reload Try again cannot carry it; (h) with a byte count the paused body stays "N
already downloaded is kept on disk." (unchanged BrainSurface.pausedInstall and unrequestedAi checks pin
it), so W7's "What it downloaded is kept on disk." is not shown; without a count the body is "An earlier
download is paused." alone; (i) a refused Try again moves focus to the stopped view's heading.

A fourth builder session (builder-fm-s7b-20261116d) repaired two findings of a second code review under
the orchestrator's repair decisions RS7 and RS8 (delegated choices, cited in the flow check's entry). Each
counterexample is a new case in BrainInstall.flow.test.ts that failed on the previous candidate
(46a2e06915, a shared clone with the new check file copied in) by assertion and passes now. RS7: after
Accept for Best match first over an installed AI, the store published the earlier run's completed status
again, then the new run failed; Try again sent only acceptTerms (expected enableRerankerOnCompletion as
well). RS8: after a restart Understand meaning's model file is on disk and its tokenizer missing, nothing
else; Repair is offered (held before), but while the repair runs the steps were hidden (0 steps), a
cancelled repair lost the repair alert, and a failed one said "AI isn't installed yet" after Cancel
install. Three more new cases hold on 46a2e06915 and guard the repairs: the intent follows Try again to
its new run past that run's stale failed status (RS7), its own run completing clears it (RS7), and a
model file the first install itself placed is still not an installation once that run failed (RS8, G6').

Named for the acceptor: (j) the completion intent is bound to its run by the run's start: at Accept (and
at Try again) the page notes the start of the run it shows then, binds the intent to the first status
with another start, and clears it only when a status of that run reads completed; the start operation's
answer carries no start, so binding waits for the Engine's status; (k) the Engine publishes no per-file
plan or placement data in its install status, so RS8 uses the page's own observation: when the page
first sees a run, it keeps the final model files the last model presence showed (per part and variant
file), and a final file counts as an existing installation while it is still shown; supporting files the
run plans never remove it; (l) the case RS8 names: with no observation before the run (a reload during a
repair or after it ended), only the Engine's plan speaks, as before (parts it found on disk are installed
with no bytes, every other part of the run is its own), so after a reload during a repair that plans work
for every part with a model file, the page shows that run as a first install and, once it failed or was
cancelled, not as an installation until the Engine reports one; the page adds no new claim there. The
fourth session re-ran the page evidence with --before origin/main and the same two overlays; every check
passes on the candidate and fails on the base by assertion. On origin/main (dea8a9f97b) 39 of the flow
check's 45 cases fail; the six that hold are the four named above for the first session, RS8 "before the
run, Repair is offered" and RS8's cancelled repair (the base counts any file as an installation).

A fifth builder session (builder-fm-s7b-20261116e) answered the acceptor's reject (the page heading
carried the share that fm-12 shows once, in the top-bar status) under the orchestrator's decision G3''
(delegated choice, cited verbatim in the affected entries): during any install run the heading is
"Installing AI", with no share and no "· Paused"; the paused state is said by G4's body words; the
polite announcement may still carry the share ("Installing AI, N%", once per tenth). The checks that
pinned the heading now assert the plain heading and read the share where it stands, the top bar's
status entry (the real status pill over the same AI state; its words "Installing AI models · N%" and
"AI download paused · N%" are unchanged by this slice): flow, focus-lockdown, installOperations and
repairRun. BrainSurface.redesign's remount case, which found "50%" anywhere in the page text, reads the
page's progress bar instead. On the previous candidate (9e4a0cadba, a shared clone with the new check
files copied in) the updated checks fail by assertion where the heading carried the share: 5 flow
cases, 3 focus-lockdown cases, 2 installOperations cases and 8 repairRun cases; the redesign case holds
there. The page evidence was re-run with --before origin/main and the same two overlays; every check
passes on the candidate and fails on the base by assertion (flow 39 of 45, focus-lockdown 7 of 10,
installOperations 4 of 4, repairRun 19 of 20, redesign 2 of 12 cases on dea8a9f97b). The "1 KB"
sizes are now cited to the orchestrator's accepted deviation "KB under 1 MB" in the entries that assert
them. Outside the gate's items, e2e/csp-chromium.mjs (run by src/boot/csp-styles-lockdown.test.ts)
waited for "45%" in the heading; it now waits for the run's bar at 45 and measures it as before.

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
      "behaviour": "AI Brain's install flow rendered from the Engine's recorded statuses: the consent's five parts with page names, sizes, licences and Terms, Cancel starting nothing and Accept starting the install, \"Up to\" with staged bytes (S7b-1); unknown sizes and a missing manifest block Accept with a reason, a missing licence says so (S7b-10); progress under the plain heading \"Installing AI\" with the share only in the top-bar status entry (G3''), amounts, time left only when estimated, Done only after model presence observed after the part's last file, Waiting or a stage's refusal reason (S7b-2); Pause/Resume words and Cancel's toast without a promise of continuing (S7b-3); the stopped view with Done/Stopped/Waiting, Try again, Cancel install and Detail for support, no red, no connection claim (S7b-4); Try again's run covering only what is missing (S7b-5); Cancel install bound to the failed run (S7b-6); not installed after a first install that stopped with nothing placed, partial-only, supporting-only, unknown presence (S7b-7, G6'); restart reconstruction (S7b-8). Bytes an earlier download left: the action is \"Install AI\" with the most it can download, named so in its accessible name, never \"Resume Download\" or a promise to continue (G4', gap 2); a first install that runs, a paused run and a stopped download show only the install view, without steps, graphics card or Advanced settings, and the steps return after completion and after Cancel install (gap 3). Review repairs: Try again after a failed Best match first download asks the Engine to switch it on when done, as the consent accepted, and not when that choice was saved differently since (RS1); after a restart a complete GPU runtime package with no model file is not an installation (RS2); a damaged installation found on disk after a restart offers Repair, its repair run keeps the steps, and a failed (after Cancel install) or cancelled repair run keeps the repair classification (RS3); after Cancel install with model presence unreadable the page says it is checking, not \"AI isn't installed yet\", until presence resolves (RS4); a part the Engine reports verifying, in the download phase, reads \"Checking the downloaded files\" with no time left (RS5, W7). Second review repairs: a completed status left from the earlier run does not clear the accepted consent's completion intent, which follows Try again to its new run and is cleared only when its own run completes (RS7); a model file on disk before a repair run, whose tokenizer that run downloads, keeps the steps during the run and the repair classification after it was cancelled or failed (and Cancel install), while a model file the first install itself placed is still not an installation (RS8).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS1 (repair decisions, 2026-11-16) Try again sends the completion intent of the accepted consent (which features the user chose to turn on when done), minus any choice the user changed since; never clears a requested feature.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS2 (repair decisions, 2026-11-16) Existing-installation evidence is positive presence of a final model file only; supporting-file-only packages (cuda-runtime and the like) never establish \"Installed — repair available\" (G6').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS3 (repair decisions, 2026-11-16) Existing-installation status comes from the same disk evidence wherever it is used (classification, steps shown during a repair run, after failure or cancel of a repair run), never from page history; a failed or cancelled repair keeps the repair classification while the final files are unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS4 (repair decisions, 2026-11-16) After \"Cancel install\", unknown disk evidence stays \"unknown\" (neutral words, no \"AI isn't installed yet\") until presence resolves.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS5 (repair decisions, 2026-11-16) A package reported verifying (or configuring/activating) shows what is happening and no ETA, whatever the overall phase says (G3').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "W7 (repair decisions, 2026-11-16) Words adopted, the source for exact assertions: \"Checking the downloaded files\" (verification), \"Setting up the downloaded parts\" (configuration/activation), the Cancel toast \"Install cancelled. Completed parts stay on this computer.\", and after restart with a paused download \"An earlier download is paused.\" followed by \"What it downloaded is kept on disk.\" only when the Engine reports downloaded bytes greater than 0.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS7 (repair decisions, 2026-11-16, second review) Completion intent belongs to the run it was accepted for (that run's identity); a status left over from an earlier run never clears it; it is cleared when its own run completes, or when the user changes that choice (RS1).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS8 (repair decisions, 2026-11-16, second review) Existing-installation evidence is per final model file: a final file counts when it was present before the current run placed anything (from the Engine's per-file plan or placement data if published; else presence observed before this run started); supporting files planned or downloaded by a repair never remove a model's evidence. If the Engine publishes no per-file data and no pre-run observation exists (reload during a repair), keep the classification from the last disk evidence and say nothing new; name that case in your report.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3'' (orchestrator, 2026-11-16, verbatim; replaces G3/G3' on this point): \"the page heading during any install run (first install, repair, additions) is \\\"Installing AI\\\", with no percentage and no \\\"· Paused\\\"; the percentage stands once, in the top-bar status; the paused state is said only by the body words of G4'. Screen-reader progress announcements may still carry the percentage politely (cite this decision).\" The checks read the top-bar status entry's existing words (\"Installing AI models · N%\", \"AI download paused · N%\"), which this slice does not change, and the spoken \"Installing AI, N%\" is the heading's words with the share, once per tenth.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
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
      "behaviour": "In headless Chromium with the real AI state store: Escape and Cancel return focus to Install AI and Accept is reachable by Tab; Pause keeps focus as it becomes Resume and the paused words are announced politely; progress is announced once per tenth (\"Installing AI, N%\"), while the heading stays \"Installing AI\" and the share shows in the top bar's status entry (G3''); focus moves to the heading on failure, to Pause after Try again, to the heading on completion and after Cancel install; light and dark at 600 px nothing overflows or leaves the size and corner ladders, the stopped view has no error alert, no teal bar and Try again as its only filled action; the consent stays on the ladders at 600 px. When the install view takes the page focus is not lost: after Accept and download, and from a focused step when a run starts, focus moves to Pause (gap 3). After Try again, focus is never lost: a run that completes or fails again before any poll sees it running, and a refused start, put focus on the heading (RS6).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-11 \"Install AI · 2.6 GB\" … dialog \"Download AI models?\" \"2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.\" Rows: Understand meaning 1.19 GB Apache-2.0 Terms; Related words (helps Understand meaning) 956 MB Apache-2.0 Terms; Best match first 341 MB Apache-2.0 Terms; Names of people and places 132 MB AFL-3.0 Terms; Checking answers against sources 23 MB Apache-2.0 Terms. Buttons \"Cancel\", \"Accept and download\". Note: \"Licence dialog. Parts carry the same names as on the page and in the progress list; no \\\"AI Home\\\", no model jargon as the name.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "RS6 (repair decisions, 2026-11-16) Pending focus after Try again resolves on every outcome: a new run, completion, another failure, or a refused request; focus goes to the view's heading or the new primary control, never lost.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3'' (orchestrator, 2026-11-16, verbatim; replaces G3/G3' on this point): \"the page heading during any install run (first install, repair, additions) is \\\"Installing AI\\\", with no percentage and no \\\"· Paused\\\"; the percentage stands once, in the top-bar status; the paused state is said only by the body words of G4'. Screen-reader progress announcements may still carry the percentage politely (cite this decision).\" The checks read the top-bar status entry's existing words (\"Installing AI models · N%\", \"AI download paused · N%\"), which this slice does not change, and the spoken \"Installing AI, N%\" is the heading's words with the share, once per tenth.",
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
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "W7 (repair decisions, 2026-11-16) Words adopted, the source for exact assertions: \"Checking the downloaded files\" (verification), \"Setting up the downloaded parts\" (configuration/activation), the Cancel toast \"Install cancelled. Completed parts stay on this computer.\", and after restart with a paused download \"An earlier download is paused.\" followed by \"What it downloaded is kept on disk.\" only when the Engine reports downloaded bytes greater than 0.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "The paused body keeps the kept amount and drops \"resuming continues from there\" (aiVerdict.ts:532); without a byte count it says only \"An earlier download is paused.\" (W7: nothing is claimed as kept without reported bytes), instead of \"resuming continues from where it stopped\"."
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
          "choice": "G3'' (orchestrator, 2026-11-16, verbatim; replaces G3/G3' on this point): \"the page heading during any install run (first install, repair, additions) is \\\"Installing AI\\\", with no percentage and no \\\"· Paused\\\"; the percentage stands once, in the top-bar status; the paused state is said only by the body words of G4'. Screen-reader progress announcements may still carry the percentage politely (cite this decision).\" The checks read the top-bar status entry's existing words (\"Installing AI models · N%\", \"AI download paused · N%\"), which this slice does not change, and the spoken \"Installing AI, N%\" is the heading's words with the share, once per tenth.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Heading \"Installing AI\" (G3'': no share, no \"· Paused\") and the paused line instead of \"Installing… 25%\" / \"Paused 25%\"; the share is read from the top bar's status entry, mounted over the same store (\"Installing AI models · 25%\", \"AI download paused · 25%\"); buttons \"Pause\" / \"Resume\"; the parts list replaces the staged-progress block in the hidden-while-unobserved checks."
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3'' (orchestrator, 2026-11-16, verbatim; replaces G3/G3' on this point): \"the page heading during any install run (first install, repair, additions) is \\\"Installing AI\\\", with no percentage and no \\\"· Paused\\\"; the percentage stands once, in the top-bar status; the paused state is said only by the body words of G4'. Screen-reader progress announcements may still carry the percentage politely (cite this decision).\" The checks read the top-bar status entry's existing words (\"Installing AI models · N%\", \"AI download paused · N%\"), which this slice does not change, and the spoken \"Installing AI, N%\" is the heading's words with the share, once per tenth.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Consent rows carry the page's part names and the consent's sizes (\"1 KB\", \"3 KB\"; \"Terms\"). Progress kept across a tab switch and remount is read from the page's progress bar (its value 50), since the heading no longer carries the share (G3''; was: \"50%\" anywhere in the page text, which main's heading \"Installing… 50%\" supplied)."
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator, 2026-11-16, accepted builder deviations (verbatim): \"7b builder deviations accepted (mine): KB under 1 MB; repair owed also on Engine-reported existing installation; \\\"Downloaded\\\" for on-disk step before install; consent first focus on Terms as before.\" Under it, sizes below 1 MB in the consent and the progress show whole KB (\"1 KB\", \"3 KB\", \"56 of 177 KB\"); MB and GB follow G2'.",
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
          "location": "task t-20261113-fmimpl agreement, picture notes fm-11, fm-12, fm-12b",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3/G3' Progress (heading as G3'' replaced it): page heading \"Installing AI\"; \"Search by exact words keeps working while this runs.\"; \"X of Y · about N min left\" (ETA only when remainingSeconds > 0; during verification/configuration/activation the line says what is happening, no ETA); one row per part: \"Done\" only when the Engine's model presence shows every final file of that part placed in this run's observation after the run's last file for that part (not per-file \"installed\", not byte equality, not presence older than the run); otherwise \"a of b\" or \"Waiting\"; a stage's blockedReason is shown in words instead of \"Waiting\"; buttons \"Pause\" and \"Cancel\" together.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "W7 (repair decisions, 2026-11-16) Words adopted, the source for exact assertions: \"Checking the downloaded files\" (verification), \"Setting up the downloaded parts\" (configuration/activation), the Cancel toast \"Install cancelled. Completed parts stay on this computer.\", and after restart with a paused download \"An earlier download is paused.\" followed by \"What it downloaded is kept on disk.\" only when the Engine reports downloaded bytes greater than 0.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "G3'' (orchestrator, 2026-11-16, verbatim; replaces G3/G3' on this point): \"the page heading during any install run (first install, repair, additions) is \\\"Installing AI\\\", with no percentage and no \\\"· Paused\\\"; the percentage stands once, in the top-bar status; the paused state is said only by the body words of G4'. Screen-reader progress announcements may still carry the percentage politely (cite this decision).\" The checks read the top-bar status entry's existing words (\"Installing AI models · N%\", \"AI download paused · N%\"), which this slice does not change, and the spoken \"Installing AI, N%\" is the heading's words with the share, once per tenth.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: resume, pause, consent, repair, first run): none",
      "whatChanged": "Heading \"Installing AI\" during the run and while planning (G3'': no share) and the paused line; which observation the run follows is read from the share in the top bar's status entry, a real status pill over the same store state (now with the connection, verdict and activity the bar reads); \"Pause\" / \"Resume\"; Cancel's toast says what is kept without continuing; the refused stage's reason on its part's row; no search-ready line."
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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
          "quote": "fm-12 top-bar status \"Installing AI · 50%\"; page lead \"Installing AI\"; \"Search by exact words keeps working while this runs.\" \"1.3 of 2.6 GB · about 24 min left\"; per part \"Done\" / \"120 of 956 MB\" / \"Waiting\"; buttons \"Pause\", \"Cancel\". Note: \"Installing. The bar shows the share, the line under it the amounts and time left; the percentage stands once, in the status. One list with the same names as the dialog. Pause and Cancel together. No repair message while an install is running.\"",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-zzuwe78t",
  "verdict": "reject",
  "digest": "sha256:ca57b309fe4966d6d8b90db088d4fa8d392ad29fa2d3039f603abb8374aaceab",
  "note": "Exact words without a source: 'Setting up the downloaded parts' (flow); toast 'Install cancelled. Completed parts stay on this computer.' (flow, repairRun); aiVerdict 'An earlier download is paused. What it downloaded is kept on disk.'. Prose stale: 26 of 29 flow cases fail on base, not 21 of 25; 'cancelled in this session' fails on base; unsourced words are asserted exactly, not by meaning."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-hlwdq2m_",
  "verdict": "reject",
  "digest": "sha256:40b0247702ed5dca82c709e069ee94bec1fb53366fa4723977d5d4545df39a89",
  "note": "Page heading 'Installing AI · N%' has no source: fm-12 shows the page lead 'Installing AI' and the share only in the top-bar status pill; its note, quoted in these entries, says 'the percentage stands once, in the status'. Affected: flow, focus-lockdown, installOperations, repairRun. The candidate shows the share twice."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-86rjupuo",
  "verdict": "accept",
  "digest": "sha256:3111b73e1d99239e9edbe6793e3d1deb09532d8d9fe077fb8fab0fd1be2c117a"
}
```
