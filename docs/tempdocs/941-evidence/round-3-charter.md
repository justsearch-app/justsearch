# Round 20 charter — 0.3.0 candidate 3, fresh-install (the runbook's final qualifying round)

Round numbering continues the 0.2.0 series (round 18 upgrade lane on candidate 1, round 19
first fresh-install on candidate 2; tempdoc 941 records both).

Purpose: the FINAL `fresh-install` qualifying round of the 0.3.0 line on candidate 3, which
carries the round-19 fix campaign. Two jobs: (1) the full first-run journey on a clean machine
(installer → first paint → Install AI → first index → first search/chat → restart → uninstall),
and (2) re-confirm every round-19 finding fixed, with the healthy signature stated per item
below. A clean pass qualifies the commit for the signed tag build. Verifier: Codex first (the
Step-0 `@oai/sky` probe and PNG magic-byte export are mandatory — round 19 needed both),
Claude Code fallback.

Candidate: `JustSearch_0.3.0_x64-setup.exe` — UNSIGNED dispatch of `build-installer.yml` on
`main` after the round-19 fix PR merged (`sign=false`, `sandboxTestMode=false`). Exact
SHA-256, size and CI run: `candidate-provenance.md`. Unsigned ⇒ Authenticode NotSigned
everywhere and the SmartScreen unsigned-publisher warning: capture it, mark
`install-trust-prompts` **unobservable-signed** with that reason, do NOT file it. Where the
generic coverage brief says "signed candidate", THIS charter's signing statement wins.

Mode: `fresh-install`, `--no-models`. No host models mapped; never set `JUSTSEARCH_MODELS_DIR`.
Search v3 (tempdoc 852) is NOT promoted: a visible "v3" chat toggle or second chat surface is a
finding.

## Sequence

1. Install on the clean image. Capture every installer page and every Windows trust prompt.
2. First paint, backend health, jar-uniqueness check, pre-Install-AI UI sanity.
3. Install AI. Start it from the UI button if the automation policy allows that confirmation
   boundary (round 19 used the API start and UI-observed every later state; the literal
   UI start-click is the one unexercised step). On ANY package failure run
   `probe-download.ps1` before forming a hypothesis.
4. Library: add the SciFact folder (one confirm click — 914 §O-2) and one small folder of your
   own; let enrichment drain; capture golden queries only once the embed EP is warm.
5. Coverage brief `sandbox`-tier items, the escalation ladder (Search → Documents → Structured
   → Agent), trust surfaces, `/mcp` raw POST + the MCPB stdio TYPED_CONFIRM driver, one Worker
   kill during an agent run, one genuine four-process cold restart, uninstall.
6. Run the long-running Structured probe (item 1 below) BEFORE any restart, in the same
   conversation as the Documents answer, then immediately fork a fresh-conversation control.

## What is NEW in candidate 3 vs candidate 2 (verify, don't re-file)

1. **Structured result renders in a long-running shell (round-19 F1 fix).** Trigger: on the
   shell that has been open since first paint, in the SAME conversation that already holds the
   Documents (`core.rag-ask`) answer, select "Structured — fields as JSON" from the bottom mode
   control, attach `{"type":"object","properties":{"phrase":{"type":"string"}}}`, send one
   prompt. Record the authenticated `POST /api/chat/dispatch` span count before and after.
   HEALTHY: count +1 (plus the OPTIONS preflight) AND a rendered JSON result under the
   "Model-generated structure" frame tied to that prompt, in that conversation. BROKEN: prompt
   cleared, no new frame, the previous Document Q&A card still showing. Control: a new
   conversation via "Start fresh" + "+ Schema" must also render. If the long-running case
   fails again, FIRST scroll the transcript to its bottom (the round-19 mechanism was a
   transcript that never followed its tail, so the new card sat below the fold) and read
   `.conversation` scrollTop/scrollHeight/clientHeight; then capture
   `GET /api/chat/conversations/<id>/history`: an extract assistant record there means the
   backend generated and persisted, and the defect is in the shell.
2. **Failed-files drawer shows the scan id (round-19 F2 fix).** Trigger: corrupt PDF
   (`%PDF-1.4` header followed by garbage) in a watched folder, rescan, then WAIT through at
   least one periodic sync cycle (several minutes) before reading. HEALTHY:
   `GET /api/indexing-jobs/failed/by-prefix` row carries the same non-empty `scanId` and
   `collection` as `GET /api/indexing-jobs/failed`, and the Library drawer shows a
   "Scan <id>" line equal to it. BROKEN: `scanId:""` or `collection:""` on the by-prefix row
   after the sync cycle, or no scan line while the row carries one.
3. **Clean Install AI completes (round-19 F3 fix).** HEALTHY: after all user packages install,
   `GET /api/ai/install/status` reports `installedFully:true`, no `chat-compact` row in
   `packages`, and the Brain surface shows the clean completed state, not "Installed with
   limitations". Each skipped package row (if any) carries a `skipCause` id
   (`hardware`, `intent`, `user-declined`); only `hardware` may yield the limitations banner.
   BROKEN: `installedFully:false` with every user package installed, or a message attributing
   a non-hardware skip to hardware.
4. **Grounded answer keeps its frame after a cold restart (round-19 F4 fix).** Trigger: a
   Documents answer with sources, then the genuine four-process cold restart, reopen the
   conversation from history. HEALTHY: the card still reads "Based on your documents" with the
   same sources and the shape tag "Document Q&A". BROKEN: "Model answer — this mode does not
   search your documents" on the reloaded turn. This is the `ui-api-truthfulness-under-load`
   verdict's subject this round.
5. **Round-18 fixes stay fixed**: no raw `settings.*` label at the three Settings points
   (before/after Worker kill and after cold restart); failed-file health event reads as a
   sentence (no `{path}` / `atMs=`); Add Folder during a rebuild shows the authored notice.
6. **Harness H1**: `collect-evidence.ps1` must not print `ARTIFACT-PREDATES-BOOT` for an
   install made after boot; its fingerprint now compares the install directory's creation time
   in UTC. If it fires on a clean install, that is a harness finding again.
7. **Round-18 must-watch ids** stay in the brief and get verdicts; the five `upgrade-*` ids are
   `unobservable` in a fresh-install round with that reason.

## Round-plan obligations

- Budget `expired-pending-approval-ceremony` right after the first TYPED_CONFIRM approval.
- `webview-performs-one-search` (UI search with results; trace shows the mutating call 2xx
  with the session token).
- Golden parity: the staged `golden-parity.json` is still the candidate-1 baseline (same
  corpus, same embedding weights; neither fix campaign touched search). Capture after
  enrichment is drained and the EP is warm.
- Codex: Step-0 probe per the amended skill; verify PNG magic bytes on the first capture.
- Every Documents-rung capture filename carries BOTH `unified-chat` and `rag-ask`; the
  Structured captures carry `extract`.
- All six process artifacts; `findings.md` standalone; in-round tools under the mapped share's
  `round-tools/`. Rename over-claiming filenames BEFORE writing `evidence-review.v1.json`.

## Blocker classification (runbook step 3)

Every item above is **needs-round**. There are no open needs-dig blockers on this candidate.

## Carried over / do NOT re-file

- Known Issues in the 0.3.0 CHANGELOG (in-app update from 0.2.0 not exercised — 617 §9; one
  overall download bar — 840; empty quick answer with reasoning on — 845/848; out-of-root
  documents not removable — 875; the 10-iteration agent ceiling — 868): observed-known unless
  worse than described.
- Locked-chat `POST /api/chat/dispatch` returning 200 and discarding the question (817 §1).
- Health card 2–4 min staleness in the safe direction (0.2.0 round-16 F5).
- Restart-era bare "AI installed." beside `installedFully:false` — re-check under item 3; if
  the API now says `true`, this is closed, not carried.
- SmartScreen reputation verdicts and elevation prompts: structurally unobservable
  (`EnableLUA=0`, no MOTW on the folder mount) — record unobservable-with-reason.
- Golden parity q04/q06/q08 sub-floor overlap: descriptive under the blocking
  golden-#1-in-top-3 assertion (the dev→Sandbox systematic divergence, 734 finding 5).
- The privacy recovery-key screen behind the irreversible passphrase commit: inspect the
  forward-only warning, do not commit; record as a coverage limitation, not a finding.
- Files first discovered by the periodic directory sync under a collection-tagged root land
  in `default` (open item from round 19 F2, not fixed in candidate 3): observed-known.
