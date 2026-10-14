---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b102-stop-census
---

# Dev-runner stop and exit census after a long run

All changed checks are script self-tests under `scripts/`, which
`docs/reference/testing/test-intent.md` excludes from the product-test gate, and
`run-evidence.mjs` has runners for Gradle, vitest and cargo only. No Java, frontend-module,
Rust, build-selection or governance-tooling file changes. The empty gate entry list reflects
that scope; the intent and the execution record below still need the orchestrator's
independent verifier. The builder supplies no acceptance record.

```json
{ "entries": [] }
```

Intent source: **delegated-choice**, label **agent choice**. Delegation quote:
"do whatever you think is best. your working autonomously". Delegation location:
task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94).
Scope: task t-20261005-b6d536, process ownership and exit accounting.

Choices, decided by the orchestrator under that delegation after the quiet-machine measurement of
b599d4d7e (run 8f60c4e7, item 6 defects of the dev-runner stop and census):

- A recorded identity whose exit is in the Engine's exit ledger (same PID and creation time) is
  gone and is not probed again. A live process whose creation time cannot be read never matches a
  recorded identity with a known exit; it still blocks only when nothing proves the recorded
  identity ended. (Run 8f60c4e7: console host 24536 exited at 14:56:55 per the ledger; Windows
  gave the PID to svchost.exe at 15:11:32, whose birth the stop could not read, and the stop
  reported it as a surviving Engine job member.)
- The census reads each ledger file once per stop and indexes it by identity; it does not
  re-parse per pass. (Each pass took about 17.7 s on the 17 MB ledger and ran at least twice, so
  the stop exceeded its 60 s limit.)
- A retried stop never overwrites an earlier census; it keeps it and adds to it. With the Engine
  already gone, the retry still completes the census from the ledger, and reports it complete
  when every recorded identity has an exit.
- A retried stop never overwrites the earlier stop-report.json either: it is kept as
  stop-report.1.json (then .2, and so on), and the new stop-report.json records `earlierCensus`
  and `earlierReports`, naming the kept files. A self-exit report keeps an earlier report the
  same way.

Follow-up decisions after the independent verifier rejected f3a8282f7:

- Any unreadable, corrupt or torn ledger line in a file the census depends on makes accounting
  incomplete, with a gap naming the file and the line count; the census never claims complete
  over records it could not read. A torn last line in a file still being written during the stop
  counts the same, unless a later re-read in the same stop finds it complete. (f3a8282f7 reported
  complete with no gap and left out a child whose start line was corrupt or torn; b599d4d7e
  reported incomplete.)
- The ledger is resolved first: an identity with a ledger exit is never passed to an OS lookup or
  probe, in any phase. (f3a8282f7 passed a ledger-ended Engine to the identity lookup three times
  in one stop.)

Follow-up decisions after the independent verifier rejected 5b53911da:

- A gap from an earlier read of the ledger is dropped when a later read in the same stop resolves
  it, as with a torn line read whole later. (5b53911da kept the arming read's "engine-start lacks
  exact PID/birth/executable identity" gap after the Engine start line had completed, so a stop
  with the Engine's REQUESTED exit stayed incomplete.)
- Each ledger file is cached per stop by file id, size and modification time. An unchanged file is
  not opened again in the same stop; a grown file reads only its new tail. (5b53911da read each
  file 7 times per stop: one payload read and six 256-byte head reads.)
- The stop is not slower than b599d4d7e on the verifier's 17 MB ledger of about 20,000 Engine
  children, and stays well under 60 s. (5b53911da took 39.4 s there against b599d4d7e's 19.1 s,
  because it built the ledger view at every lookup phase and each view scanned every child for
  each children record.)

Follow-up decisions after the independent verifier rejected bd198cc98:

- A ledger file is known by the handle it is read through (its identity compared after the open),
  never by a stat taken before the open. On any mismatch both files are read again in full. A
  ledger read never drops records that b599d4d7e's whole read would have seen. (On bd198cc98 a
  rotation between the stat and the open cached the replacement's bytes under the moved file's
  identity: an archived child with no exit was lost, and the stop reported complete with no gap.)
- Only records that prove an exit count as ended: an exit record, or a reconciliation that states
  the process is gone. Anything else goes through the normal identity-checked path. (On
  bd198cc98 an `engine-owner-died` with osCheck ALIVE_MATCH was taken as an exit: the live
  identity got no lookup, and the stop said STOPPED while it ran.) The record kinds and outcomes
  the ledger can hold for a process with an identity:
  - proves the exit: `engine-exit` (the runner on the exit it observed, or the CLI once the Engine
    is gone or its PID reused); `child-exit` (the Engine on its held handle's exit, or the
    predecessor stop after the process exited; a start failure has no `child-start`, so no
    identity); a witness's own `job-process-exit` (on its handle's wait); and a reconciliation
    (`engine-owner-died`, `owner-died`, reconciled `job-process-exit`) with osCheck GONE;
  - does not prove it: any reconciliation with osCheck ALIVE_MATCH (found alive) or
    IDENTITY_UNKNOWN (could not tell), a reconciliation without osCheck, and `child-survived`,
    `stop-intent`, `stop-escalate`, `engine-intent`, `engine-self-intent`, `helper-intent`,
    `child-note`, `gpu-release` and `ledger-gap`, which are not exit records. Helper receipts
    (`helper-exit`) and windows are not used to resolve identities.
- Identities are matched at the precision each record was written with, never looser than the
  less precise side: the ledger's microsecond, or the millisecond of a manifest helper's recorded
  start. (On b599d4d7e and bd198cc98 a manifest helper's exact ledger exit was not matched, so the
  stop probed its unreadable reused PID three times and reported STOP_INCOMPLETE.)

Follow-up decisions after the independent verifier rejected e3f82e1a9 (each round had found a new
edge of the same rules, so this round fixes the rules as classes, each with its own enumerating
check):

- Exit proof is one function over all the records of an identity (`provenExitOf`). It returns an
  exit only from a record that proves it: an exit its writer observed (`engine-exit`,
  `child-exit`, a witness's own `job-process-exit`), or a reconciliation that checked the process
  and found it gone (osCheck GONE), naming the identity. Proof is monotonic: no later record,
  owner receipt, merge or role change takes it back. An owner class, a reason or a reconciled flag
  proves nothing alone. Accounting completeness and the lookup skip use only this function; an
  identity with exit records but no proof is classed UNKNOWN with an `exit_unproven` gap naming
  it. (On e3f82e1a9 an `owner-died` or `engine-owner-died` with osCheck ALIVE_MATCH,
  IDENTITY_UNKNOWN or none, classed OWNER_DIED, made accounting complete; an exit followed by a
  later non-proving reconciliation was un-proven; a witness's owner receipt replaced its native
  exit; and an exit marked reconciled without osCheck proved it.)
- The proof table (record, as the only record of the identity; `true` = ended). Any two records,
  in either order: proven if either one proves. Each child-start is its own identity (PID and
  birth), proven on its own; a childId is never the unit of proof. When a childId has two or more
  different starts, a record naming only the childId proves none of them, and a record naming a
  start's PID and birth proves that start alone.

  | Subject | Record and fields | Proves |
  | --- | --- | --- |
  | Engine incarnation | `engine-exit` REQUESTED, UNEXPECTED with a code, or UNKNOWN without a code | true |
  | Engine incarnation | `engine-exit` marked reconciled | false |
  | Engine incarnation | `engine-owner-died` osCheck GONE, with its PID or without one | true |
  | Engine incarnation | `engine-owner-died` osCheck GONE naming another PID | false |
  | Engine incarnation | `engine-owner-died` ALIVE_MATCH (any class), IDENTITY_UNKNOWN, REUSED, or no osCheck | false |
  | Engine incarnation | `engine-intent`, `ledger-gap` | false |
  | Engine incarnation | an exit or GONE reconciliation of another incarnation, or one naming another birth than the start (at the start's measured tick) | false |
  | Engine incarnation with two start identities | an exit or GONE reconciliation not naming the start's PID and birth | false |
  | Engine incarnation | two exits with different codes | true, and a `ledger_codes_disagree` gap |
  | Ledger child | `child-exit` REQUESTED, UNEXPECTED with a code, or UNKNOWN without a code | true |
  | Ledger child | `child-exit` marked reconciled, naming another PID, or naming its PID with another birth | false |
  | Ledger child | `owner-died` osCheck GONE (any class) naming the owner its spawn named (PID, birth and instance) | true |
  | Ledger child | `owner-died` osCheck GONE naming another owner PID, birth or instance, or no `previousOwner` when its spawn named one | false |
  | Ledger child | `child-exit` or `owner-died` naming another incarnation than its spawn | false |
  | Ledger child | two `child-exit` with different codes | true, and a `ledger_codes_disagree` gap |
  | Ledger child | `owner-died` ALIVE_MATCH, IDENTITY_UNKNOWN, REUSED, or no osCheck (any class) | false |
  | Ledger child | `child-survived`, `stop-intent`, `stop-escalate`, `child-note`, `gpu-release` | false |
  | Ledger child | a second `child-start` with another identity under its childId: then no record naming only the childId proves either start | false |
  | Ledger child, its childId shared with another start | `child-exit`, or `owner-died` osCheck GONE, naming its PID and birth | true |
  | Job witness | its own `job-process-exit`, with or without a code | true |
  | Job witness | its own exit that lost track of the birth, or one tick off its start | false |
  | Job witness | its own exit marked reconciled, without osCheck | false |
  | Job witness | its own exit, or a reconciled osCheck GONE, naming another owner instance than its start | false |
  | Job witness | reconciled `job-process-exit` osCheck GONE, written by the dev runner or the next Engine | true |
  | Job witness | reconciled osCheck GONE written by the witness's own Engine | false |
  | Job witness | reconciled IDENTITY_UNKNOWN, ALIVE_MATCH, or no osCheck | false |
  | Job witness | `helper-start` and `helper-exit` alone | false |
  | any | `job-process-start`, `helper-intent`, `helper-window-start`/`-end`, `engine-start`, `engine-self-intent`, spawn records | false |

- Precision: when both sides are exact (a native read, `birthTick` 1, against a ledger birth an
  Engine wrote), they match only when equal. They are compared at a coarser tick only when one
  side really is coarser, at that side's tick, for that pair only: a CIM read or a dev runner's
  Engine start (the microsecond), a manifest helper (the millisecond). The Engine job stop
  (`stop-job-run.ps1`) compares each ledger-ended identity at its own tick. (On e3f82e1a9 an
  exact native identity one tick after an exact ledger child birth matched it and was skipped.)
- Ledger file states: during a stop, a file that is missing, renamed, replaced, grown, truncated or
  recreated between a read's stat, open, fstat and read means a writer or a rotation is under way.
  The reader enumerates the family again and retries, at most 8 times, 5 ms apart. If it does not
  settle, the family is reported unreadable (`ledger-unreadable` gap naming it), never complete.
  (On e3f82e1a9 a file renamed and not yet replaced at the open threw ENOENT, and the verdict lost
  an archived child's exit to a `ledger-unavailable` gap.)
- Retention: a report or census that cannot be archived is never overwritten. The stop retries the
  rename on a sharing error (EBUSY, EPERM, EACCES) for 2 s; if it still fails, the new report is
  written under the next free number and the result says so (`earlierReportNotArchived`,
  `reportWrittenAs`; for the census `earlierCensusNotArchived`, `censusWrittenAs`). Every kept
  earlier-census file is listed, readable or not: `earlierCensus` stays the list of names, and
  `earlierCensusFiles` adds `{ name, readable }`. (On e3f82e1a9 a held stop-report.json was
  overwritten after the failed rename, and an unreadable earlier census was left out of
  `earlierCensus`.)
- Known limit: a file rewritten in place under the same file id, longer than before, whose old
  length happens to end on a line boundary of the new content, reads as grown. The reader cannot
  tell it from an append. A rewrite whose old length falls inside a line is detected (its first
  new line does not parse): since round 6 that line is a `ledger-damaged` gap read from the tail,
  not a reason to read the file again in full. The ledger writers only append and rotate.

Follow-up decisions after the independent verifier rejected e2939b0ed:

- Precision is what each side actually measured, declared truthfully where it is recorded. A
  census target carries the tick of its recorded birth: exact for a birth the Engine read natively
  (its own `engine-start`, every `child-start`, a witness's `job-process-start`), the microsecond
  for a CIM read (a dev runner's or the CLI's `engine-start`, the stop's own scope), the
  millisecond for a manifest helper. The census's held handle is native, so a native ledger birth
  is compared with it exactly. (On e2939b0ed, and on b599d4d7e, every ledger target was declared
  microsecond: the real observer accepted a running child whose native birth was one tick off its
  exact ledger birth, and the census was complete.)
- Each start is its own identity (PID and birth) and needs its own proof: the ledger view has one
  child per distinct start under a childId, each proven by `provenExitOf` (ambiguous when the
  childId has another start), each armed and judged on its own; a census target's ledger exit is
  only its own start's. (On e2939b0ed two starts under one childId collapsed into the later one:
  its exit, naming it, hid the earlier start, still running, and the census was complete with no
  gap. b599d4d7e reported incomplete.)
- A witness's exit proves it only when it names the same owner (PID, birth and instance) as the
  witness's start; proofs are judged after every start is read, so record order never matters.
  (On e2939b0ed the witness's own exit naming another owner instance skipped the lookup while the
  process ran; b599d4d7e looked it up.)
- Earlier census and report evidence is read and merged whether or not it could be archived. A
  file that stays held (not renamed) is read where it is, and its targets and OS exits are merged,
  so an earlier OS-vs-ledger disagreement is carried into every retry and never dropped. A file
  that cannot be read makes accounting incomplete with an `earlier-census-unreadable` gap naming
  it. Every earlier file is listed, archived or held: `earlierCensusFiles` and
  `earlierReportFiles` give each `{ name, readable, archived }`. (On e2939b0ed a held
  exit-targets.json and process-exits.json were left out of the lists and the merge, so the
  retry reported complete without the first census's `ledger_os_disagree`; a held
  stop-report.json was left out of `earlierReports`.)

Follow-up decisions after the independent verifier rejected 7c67eb5d3 (decided by the
orchestrator):

- Identity: "a record proves or matches a start only when it agrees with every identity field both
  carry: PID, birth at measured precision (native FILETIME on both sides is exact, whatever a
  declaration says), childId, owner instance and incarnation. A missing field on the record is
  never a wildcard for a field the start carries, except birth precision where the record genuinely
  measured less. If anything is ambiguous, it's not proof." (On 7c67eb5d3 a child's `owner-died`
  GONE naming another owner instance proved its exit while it ran; a second Engine start in a
  shared incarnation took the first start's exit; a target's stale declared `birthTick` 10 let a
  held handle one tick off a native birth match.)
- Evidence: "gaps and disagreements found by any earlier stop of the run are carried forward and
  keep accounting incomplete until a person forgets the run. No later record clears them. A
  ledger that holds two different exit codes for one identity is itself a disagreement. An earlier
  report or census that can't be read is a gap." The findings carried are every gap whose reason
  names a disagreement (`ledger_os_disagree`, `ledger_codes_disagree`, `owner_witness_disagree`,
  ...), `evidence_lost`, `ledger-damaged` other than a torn last line alone, and
  `earlier-census-unreadable` / `earlier-report-unreadable`. A gap that only says an exit was not
  seen yet (`unrecorded_exit`, an identity-bound OS exit unavailable, `exit_unproven`,
  `ledger-unreadable`, a torn last line) is looked for again by the retry and not carried. (On
  7c67eb5d3 an unreadable earlier report, or a disagreement only an earlier report held, left no
  gap, and a later receipt naming the OS code cleared the first census's `ledger_os_disagree`.)
- Performance: "read the tail only. The damaged line gives a gap from the tail; the earlier content
  stays cached." (On 7c67eb5d3 one corrupt appended line made the reader read the whole file
  again.)

What changed:

- `scripts/dev/lib/stop-exit-census.cjs`: `proofOfRecord` and `provenExitOf` decide exit proof
  per the table above, from all of an identity's records. `ledgerView` gives each Engine
  incarnation and ledger child `exit` (proven only), `proof` and `unproven`, and each witness a
  `proof` (`provenWitnesses`, from an exit naming the start's owner); an incarnation with two
  different start identities is ambiguous, and a childId with two or more different starts gives
  one child per start, proven only by a record naming its PID and birth.
  `endedIdentitiesOf(view)` lists the identities with a proof, with their `birthTick` (the
  microsecond for an Engine start a dev runner or the CLI wrote, else exact). Census targets carry
  the same `birthTick` (exact for the Engine's own records, children and witnesses), so the
  PowerShell observer compares a native ledger birth with the held handle's exactly. `exitResult`
  adds `exit_unproven` for exit records without a proof and `ledger-unreadable` for a family that
  did not settle. `readRunLedger` reports each family's `damage`: the
  files with corrupt, unknown-schema, unknown-type or torn lines, with their counts
  (`exit-ledger.cjs` `readLedger` and the incremental reader now return per-file counts as
  `files`). The verdict adds a `ledger-damaged` gap per damaged file (`family`, `file`, `lines`
  and the counts), so accounting is incomplete; a torn line at the verdict is read once more
  after 250 ms first. One incremental reader serves the stop and its census (the stop passes its
  own as `ledgerReader`) (`exit-ledger.cjs` `createIncrementalReader`: a file is known by its
  file id, size and modification time from a stat; an unchanged file is not opened again, a
  first read is one read of the whole file, a grown file reads and parses only the bytes
  appended since, any other change parses it again from its start, and `version()` changes
  when any read finds a difference). A file that has to be read is opened, and its identity is
  taken from the open handle (`fstat`). If that differs from the stat's, a rotation has replaced
  it, so neither identity's cache is kept and the read starts again from the listing. A read whose
  listing or file identities changed by its end starts again too, as does one that met a missing
  or moving file (ENOENT, EPERM, EBUSY, EACCES) or a short read (a grown file whose new bytes
  hold a damaged line keeps its cache and counts the line as damaged). After 8 attempts, 5 ms apart, it throws `LEDGER_UNSETTLED`, which
  `readRunLedger` records as the family's `error`. The verdict replaces the arming read's ledger gaps with the
  final read's. The ledger view reads each helper window's instants once and finds windows,
  owner records, owning children, console parents and report rows through PID and image indexes
  instead of rescanning every record per helper, and `readRunLedger` looks up each Engine
  owner's manifest once instead of once per child-spawn record; on the run's own 17 MB ledger the view went from 34.9 s to 0.09 s with output identical
  to b599d4d7e apart from `exitAccountingComplete`. A retried stop renames the earlier
  `exit-targets.json` and `process-exits.json` to the next free `.N.json`, merges every kept
  census's OS exits (and the targets it observed them for) into its verdict, and writes the
  merged OS exits back to `process-exits.json`. An earlier census that stayed held is read in
  place and merged too; one that cannot be read is an `earlier-census-unreadable` gap, and
  `earlierCensusFiles` lists each file with `readable` and `archived`. `exitAccountingComplete` is true with no gap
  when a target was armed or the run's Engine incarnations are in the ledger.
- `scripts/dev/lib/stop-job-run.ps1` and `job-run.cjs` (`stopJobRunSync` option
  `endedIdentities`, passed through a temporary `-EndedPath` file): identities the ledger shows
  ended are skipped by the job stop's probes and reported as `endedPerLedger`, each compared at
  its own `birthTick`.
- `scripts/dev/dev-runner.cjs` `stopRun`: the stop reads the ledger before its first lookup and
  again before each lookup phase and the Engine job stop. An identity whose exit the ledger records
  is left out of every identity lookup and of the port-owner match. Every lookup verdict for it is
  `gone`, the post-kill wait for it is skipped, the Engine job stop skips it, and the stop report
  lists it once in `resolvedByLedger`. The stop judges the ledger again only when the reader's
  `version()` changed since its last judgement. A recorded identity matches a ledger exit at the
  coarser of the ledger's microsecond and the identity's own `birthTick` (the millisecond for a
  manifest helper). `writeSelfExitStopReport` keeps an earlier report the same
  way as a stop does. Before writing its report, a stop
  renames an existing stop-report.json to the next free stop-report.N.json (retrying a sharing
  error for 2 s; if it stays, the report is written under the next free number instead); the
  report and the stop's output list the kept reports as `earlierReports` (oldest first, then a
  held stop-report.json) and `earlierReportFiles` (`{ name, archived, readable }`), and the
  report carries the census's `earlierCensus` and `earlierCensusFiles`. The census keeps its earlier files the same way
  (40 attempts, 50 ms apart). stop-report.json is always the latest stop's, so its readers
  (including the forget check) are unchanged.
- Round 6, `stop-exit-census.cjs`: `recordAgrees` matches a record to a start on every identity
  field both carry (PID, birth at the start's measured tick, childId, owner PID, birth and
  instance, incarnation), and an `owner-died` must name the owner its child's spawn named.
  `birthTickOf` works a target's tick out from its source and the ledger view, never from a
  declared `birthTick`; the census arming view also serves the OS exits handed to `beforeVerdict`.
  `ledgerExitFor` gives an Engine target only its own start's exit. `provenExitOf` reports
  `conflictingCodes`, and `exitResult` adds a `ledger_codes_disagree` gap for them. `isFinding`
  and `carryFindings` carry an earlier stop's findings (with `carriedFrom`) and keep accounting
  incomplete; the census writes its findings into its `process-exits` file and reads them back
  from every kept earlier census. `dev-runner.cjs` `keepEarlierReports` returns `earlierFindings`
  (each readable report's gaps, and `earlier-report-unreadable` for one that cannot be read), and
  `stopRun` carries them into the report. `exit-ledger.cjs` `createIncrementalReader` parses the
  appended tail only; a line there that does not parse is damage, not a reason to reread.

New script check `scripts/dev/test-dev-runner-stop-census.mjs` (41 cases). Cases 1 to 6 fail by
assertion on b599d4d7e, case 7 on ccd687b2d, cases 8 to 12 on f3a8282f7 (8 and 9 pass on
b599d4d7e, as preservation), cases 6, 13 and 14 on 5b53911da, cases 15, 16 and 18 on bd198cc98
(16 passes on b599d4d7e, as preservation), cases 17, 19, 20 and 22 to 26 on e3f82e1a9 (19 and 20
pass on b599d4d7e, as preservation; 21 passes on e3f82e1a9 and fails on b599d4d7e, as
preservation of the coarser matching), cases 17, 19, 25, 26 and 27 to 31 on e2939b0ed (29
passes on b599d4d7e, as preservation), cases 32 to 34 and 36 to 41 on 7c67eb5d3 (32 and 33 pass
on b599d4d7e, as preservation; 35 passes on 7c67eb5d3 and b599d4d7e, as preservation of the
dev-runner Engine's microsecond birth through this round's change), and all pass after:

1. Real `stop-job-run.ps1`: a job member whose PID now names a running process whose birth
   cannot be read (found at run time, query rights only) is a survivor without the ledger and is
   gone with its ledger exit.
2. Real `stopRun` with the real census, identity lookup and job stop, the Engine gone: on
   b599d4d7e it reports `STOP_INCOMPLETE` with "Engine job: incomplete (pid N still running)",
   the incident's error; after, `STOPPED`, `endedPerLedger` names the member, census complete.
3. A tracked identity with a ledger exit is resolved without a lookup (as is the ledger-ended
   Engine); one the lookup cannot read and without a ledger exit still blocks.
4. A retried census keeps the earlier census verbatim (`process-exits.1.json`,
   `exit-targets.1.json`), accounts the Engine with the earlier OS exit and is complete.
5. A stop that finds the Engine gone is complete from the ledger; an identity without its exit
   record still leaves it incomplete.
6. A stop over a 17 MiB synthetic ledger shaped like run 8f60c4e7's (2229 job processes, helper
   receipts, helper windows, rotated files): b599d4d7e took 167,572 ms and read 71,308,464
   ledger bytes; after, 4,614 ms and 17,832,236 bytes (ledger 17,827,116 bytes). It asserts at
   most 20 s and exactly one read call on each ledger file and no whole-file read. On 5b53911da:
   "children.v1.1.jsonl is read once per stop" fails with 7 read calls.
7. Three stops of one run (incomplete, then two that complete): the first report is kept
   byte-for-byte as stop-report.1.json, the second as stop-report.2.json; each later report names
   them in `earlierReports`, and the retry's report names the kept census files in
   `earlierCensus`. On ccd687b2d: "the earlier stop report is kept" fails.
8. and 9. Real `stopRun`, empty scope census: an Engine start and exit, then a job process's
   start with no exit, whose line is corrupt (8) or torn before its newline (9). Accounting is
   not complete. On f3a8282f7 it was complete with no gaps.
10. The census verdict for those two ledgers has a `ledger-damaged` gap naming children.v1.jsonl
   with 1 line (corrupt count 1, or torn). A line torn at arming and whole by the verdict leaves
   no such gap.
11. Real `stopRun` with a valid Engine identity and its ledger exit: the injected lookup is never
   given the Engine's PID, the port-owner match never gets it, and the report resolves it by
   the ledger. On f3a8282f7 the lookup got it 3 times.
12. `writeSelfExitStopReport` over an existing stop-report.json keeps it as stop-report.1.json
   and names it in `earlierReports`.
13. The census arms while the Engine start line has only its first 80 bytes (no Engine target);
   the line is then completed and the Engine exit appended. The verdict has the Engine's
   REQUESTED exit, no gaps, and is complete. On 5b53911da the gap list still held the arming
   read's Engine-identity gap.
14. Real `stopRun` over a 17,000,732-byte ledger of 19,195 Engine children (spawn, start and exit
   each, one owner): exactly one read call per ledger file, complete, at most 10 s. On 5b53911da
   it took 31,041 ms with 7 read calls per file; after, 1,448 ms.
15. Rotation between the reader's stat and its open of children.v1.jsonl, through the census
   with that reader: the reader then holds what `readLedger` holds, every record from before the
   rotation included, and accounting is incomplete (the child has a start and no exit). On
   bd198cc98 the reader held only the replacement's note. The verifier's `rotation-race.cjs` passes
   on b599d4d7e and after, and fails on bd198cc98.
16. Real `stopRun`: an Engine incarnation with an `engine-owner-died` ALIVE_MATCH record, tracked
   and alive with its birth. It is looked up, the stop is `STOP_INCOMPLETE` and it is unresolved.
   On bd198cc98 it was never looked up.
17. The proof table: each row's record alone, and every ordered pair of rows, written as a real
   ledger for an Engine incarnation, a ledger child and a job witness, through `readRunLedger`,
   `ledgerView`, `endedIdentitiesOf` and the census verdict. Proven exactly as the table says
   (a pair with a second start under the childId is proven only by a record naming the child's
   PID and birth); when unproven, accounting is not complete, a gap names the identity and its
   row is UNKNOWN. On e3f82e1a9 it fails for ALIVE_MATCH, IDENTITY_UNKNOWN and no-osCheck
   reconciliations classed OWNER_DIED, for an exit followed by a non-proving record, for the
   witness's owner receipt, and for exits marked reconciled. On e2939b0ed it fails for the
   witness's exit naming another owner instance and for every pair with a second start (no gap
   named the subject).
18. Real `stopRun` with two manifest helpers whose PIDs are present but whose births cannot be
   read. The ledger has their births with sub-millisecond parts and their exits. The one whose
   manifest start matches its ledger birth at the millisecond is never looked up and is resolved
   by the ledger. The one recorded 1 ms after its ledger birth is looked up and stays unresolved.
   On bd198cc98 and b599d4d7e both were looked up 3 times.
19. Real `stopRun` over 13 identities whose records do not prove an exit (child: `owner-died`
   ALIVE_MATCH, IDENTITY_UNKNOWN, REUSED or without osCheck, all classed OWNER_DIED, then a pair;
   `child-survived`; `child-exit` naming another PID; Engine incarnation 1, with incarnation 2
   ended: `engine-owner-died` ALIVE_MATCH, IDENTITY_UNKNOWN or no osCheck; witness: its own exit
   marked reconciled without osCheck, reconciled IDENTITY_UNKNOWN, its exit one tick off, its
   exit naming another owner instance; child: a second start under its childId that exits by its
   own identity). Each is looked up, unresolved, the stop is `STOP_INCOMPLETE` and the report's
   accounting is not complete. On e3f82e1a9 the child and Engine owner-class cases were
   complete; on e2939b0ed the witness owner-instance case was `STOPPED` without a lookup and the
   second-start case was complete.
20. Real `stopRun`: an exact native identity one tick after an exact ledger child birth with an
   exit is looked up and blocks the stop; real `stop-job-run.ps1`: a job member at birth X+1 with
   a ledger-ended identity at X, exact, stays a survivor. On e3f82e1a9 it was never looked up.
21. Real `stopRun`: an exact identity equal to the ledger child's, a CIM read of another child's
   microsecond, and an exact read of the Engine within the microsecond its dev-runner start
   recorded are all skipped and the stop is `STOPPED`; the job stop treats a member within the
   microsecond of a microsecond ended identity as gone.
22. The incremental reader with the current file missing, renamed and replaced, grown, truncated,
   deleted and recreated, or rewritten in place, at its stat, open, fstat and first read, cold and
   with the file cached (48 combinations): it never throws, keeps generation 0 where a file still
   holds it, reads no record twice, and its next read equals `readLedger`, except a rewrite in
   place under the same id, which now shows as damage (round 6). On e3f82e1a9 the missing file
   threw ENOENT at the stat and the open.
23. A family rotated at every stat: the reader throws `LEDGER_UNSETTLED`; `readRunLedger` records
   the family's error and the verdict has a `ledger-unreadable` gap and is not complete. On
   e3f82e1a9 the read returned.
24. The census verdict's read meets the current children file renamed and not yet replaced, at its
   open: the exited child is still accounted REQUESTED and the census is complete. On e3f82e1a9 it
   had a `ledger-unavailable` gap and no row for the child.
25. Real Windows sharing: a PowerShell process holds stop-report.json open without delete sharing.
   Released 650 ms after the first refused rename: the earlier report is kept byte-for-byte as
   stop-report.1.json. Held through the stop: stop-report.json is untouched and the new report is
   stop-report.1.json, with `reportWrittenAs` and `earlierReportNotArchived`, and the held report
   is listed in `earlierReports` and `earlierReportFiles` (`archived: false, readable: true`). A
   second stop while it is still held writes stop-report.2.json and lists stop-report.1.json
   (archived) and stop-report.json (held). On e3f82e1a9 the earlier report was overwritten; on
   e2939b0ed the held report was not listed.
26. An earlier census whose targets file is torn (`{"targets":[TORN`) is kept byte-for-byte,
   named in `earlierCensus` and marked `readable: false` in `earlierCensusFiles`; an earlier
   census held open is left untouched, listed with `archived: false, readable: true`, and the new
   one is written under the next number (`censusWrittenAs`). The torn file makes accounting
   incomplete with an `earlier-census-unreadable` gap naming it. On e3f82e1a9 `earlierCensus` left
   out `exit-targets.1.json`; on e2939b0ed the torn file left no gap and the held file was not
   listed.
27. Native precision: a ledger child target is `birthTick` 1 and a dev runner's Engine start 10
   (an Engine start the Engine wrote, 1); a held handle one tick off the child's exact birth, in
   the same microsecond, is a mismatch and the census is incomplete. With the real PowerShell
   observer and a running child of the test: its exact ledger birth is observed and complete; a
   ledger birth one tick off it, same microsecond, is an identity mismatch and incomplete. On
   e2939b0ed every target was 10 and both were complete.
28. Starts under one childId, judged through `ledgerView`, `endedIdentitiesOf`, `exitTargets` and
   the verdict: two starts with the later ending by its identity (either record order), an exit
   naming only the childId, three starts with the middle ending by its identity (with and
   without a childId-only exit), a reconciliation finding the first gone by its identity, all
   three ending by their identities, and the same start recorded twice. Exactly the starts with
   their own proof are ended; every other start is armed, named by a gap, and accounting is
   incomplete. On e2939b0ed the earlier starts were lost.
29. Preserved from b599d4d7e: the real census of two running children of the test under one
   childId, the later ended with an exit naming it, is not complete. On e2939b0ed it was complete.
30. The same real census names the running start in a gap and not the ended one; with three
   starts, the middle ended by its identity and one childId-only exit, the first and third are
   named; with all three ended by their identities it is complete. On e2939b0ed and b599d4d7e no
   gap named the running start.
31. Real Windows sharing over a real disagreement: a running child of the test is the run's
   Engine and exits 7 while its ledger exit records 0; the first census reports
   `ledger_os_disagree`. Both census files are held open (FileShare.Read) through two retries:
   each is written under the next number, keeps the disagreement, lists both held files
   (`archived: false, readable: true`), and the held bytes are unchanged. Released, the next retry
   archives them as `.3`, still reports the disagreement, and lists every file archived. A census
   file held with FileShare.None (unreadable) is an `earlier-census-unreadable` gap. On e2939b0ed
   the first retry was complete with no gap.
32. Real `stopRun`: a child whose only record after its start is an `owner-died` GONE naming
   another owner instance than its spawn, the child still running with its birth. It is looked
   up, the stop is `STOP_INCOMPLETE` and accounting is not complete. On 7c67eb5d3 it was never
   looked up.
33. Two Engine starts under one incarnation, the exit naming only the first, the OS seeing both
   exit 0: not complete. Preserved from b599d4d7e.
34. The same ledger: only the second start is armed, a gap names it and its row is not an
   expected termination. On 7c67eb5d3 it took the first start's exit and was complete; b599d4d7e
   armed neither.
35. The census's OS exits handed to `beforeVerdict` match a dev runner's microsecond Engine birth
   to the exact held handle (the stop writes its Engine exit from them). It failed with this
   round's tick change until the arming view was passed (the real-CLI stop-ownership file found
   it).
36. A child and an Engine start the Engine wrote, each target declared `birthTick` 10 after
   arming, the held handle one tick off in the same microsecond: not complete, a gap names it.
   On 7c67eb5d3 both were complete.
37. A corrupt line appended to a cached children file: exactly one read of the 6 new bytes, one
   damaged line, earlier records kept. On 7c67eb5d3 the file was read again in full.
38. Real `stopRun` after an earlier stop-report.json: none, complete; corrupt, an
   `earlier-report-unreadable` gap naming stop-report.1.json; one holding `ledger_os_disagree`,
   carried from stop-report.1.json, and once only through a further retry; one holding only
   `unrecorded_exit`, complete. On 7c67eb5d3 the corrupt and disagreeing reports left it complete.
39. A running child of the test as the Engine exits 7, its ledger exit says 0: the first real
   census finds `ledger_os_disagree`. A later `engine-exit` code 7 for the same identity is
   appended; the retry is not complete and its only gaps are the carried `ledger_os_disagree`
   (from process-exits.1.json) and `ledger_codes_disagree`. On 7c67eb5d3 the retry was complete.
40. Generated: 3,000 record sets from seed 0xB6D536 (Engine or child; one or two starts,
   duplicate, same PID one tick off, or another; up to three exits or reconciliations naming or
   omitting PID and birth, right, wrong or one tick off, another incarnation or childId, owner
   same, another instance, PID or birth, without instance or none; osCheck variants, reconciled,
   codes 0 and 3, every order; OS rows with own or neighbouring birth, codes 0 and 3, or none, and
   declared ticks 1, 10, 10000 or none), each judged by `endedIdentitiesOf` and the verdict and by
   an oracle in the test that is the identity rule alone. A mismatch prints the seed, case and
   input; `STOP_CENSUS_GEN_CASE=<n>` runs one case. On 7c67eb5d3, 186 cases disagree.
41. Generated: 250 histories through real `stopRun` with a stand-in observer, up to three earlier
   stop reports or census files, each unreadable or holding up to three gaps from a table of
   findings and not-yet-seen gaps. Complete exactly when no earlier file is unreadable and none
   holds a finding, and every unreadable file and finding is named. On 7c67eb5d3, 140 disagree.

Timing on the verifier's own harness (`full-stop-perf.cjs` over its `lifecycle-perf.cjs` fixture:
17 MB children ledger, 20,029 children, 60,089 records), b599d4d7e and this change interleaved
three times, CPU load sampled before each run (26 to 46 % for b599d4d7e, 0 to 37 % for this
change, about 11 GB free): b599d4d7e 13,239 / 13,745 / 14,578 ms, 120,178 record parses, 4 read
calls per file, not complete; this change 1,250 / 1,276 / 1,276 ms, 60,089 parses, 1 read call per
file, complete. The verifier's `assert-file-reads.cjs` and `assert-perf.cjs` pass on it, and its
19 `independent.cjs` scenarios pass, `torn-engine-completes` included. b599d4d7e is slower
because it parses the ledger twice and builds the view twice, each view scanning every child per
children record; 5b53911da was slower still because it built that view at every lookup phase.
The `world-state.mjs` CLI does not load the changed files; timed three times interleaved it took
12,500 / 8,563 / 8,183 ms on b599d4d7e and 9,312 / 9,081 / 8,101 ms with this change, so its
earlier 12.3 s against the 10 s budget was machine load.

After the bd198cc98 fixes, the same harness was run again, interleaved three times, with CPU load
of 0 to 32 %. b599d4d7e took 15,643 / 15,699 / 13,050 ms; this change took 1,445 / 1,201 /
1,251 ms with 1 read call per file and was complete. `assert-file-reads.cjs` and
`assert-perf.cjs` pass. Its `independent.cjs` (19) and `independent-extra.cjs` (22) scenarios all
pass.

After the e3f82e1a9 fixes, the verifier's harnesses were run again from copies with fresh
folders. On the same 17 MB harness, interleaved three times: b599d4d7e 14,235 / 13,941 /
12,416 ms, 120,178 parses, not complete; this change 1,943 / 1,694 / 1,708 ms, 60,089 parses,
1 read call per file, complete; `assert-perf.cjs` and `assert-file-reads.cjs` pass. Its
`lifecycle-perf.cjs` census took 12,483 ms on b599d4d7e and 1,132 ms with this change. Round 3's
`independent.cjs` (19) and `independent-extra.cjs` (22), round 4's `independent.cjs` (19),
`independent-extra.cjs` (22) and the two `independent-edges.cjs` retention scenarios,
`audit-edges.cjs` (19) and `audit-more.cjs` (23), `rotation-edges.cjs` (stat-before-open with 1, 4
and 5 rotations, held-before-read, final-stat, no-file-ids), `rotation-rename-gap.cjs`,
`rotation-read-retry.cjs` (cold and warm), `rotation-race.cjs` and both `torn-engine.cjs` pass.

After the e2939b0ed fixes, the verifier's round-5 harnesses were run again from copies with fresh
folders, against this change and b599d4d7e. Round 5's `proof-probes.cjs` (5) and
`r5-retention.cjs` (3) pass; on b599d4d7e the two native-neighbour probes and all three retention
cases fail by assertion, and the two-starts probe passes there too, as preservation. On the 17 MB
harness, interleaved three times: b599d4d7e 12,374 / 12,681 / 12,397 ms, 120,178 parses, not
complete; this change 1,868 / 1,907 / 1,799 ms, 60,089 parses, 1 read call per file, complete;
`assert-perf.cjs` and `assert-file-reads.cjs` pass. Its `lifecycle-perf.cjs` census took
12,389 ms on b599d4d7e and 1,195 ms with this change. The r2, r3 and r4 `independent.cjs` (19
each), r3 and r4 `independent-extra.cjs` (22 each), the two `independent-edges.cjs` retention
scenarios, `audit-edges.cjs` (19), `audit-more.cjs` (23), the four `enoent-states.cjs` states,
`rotation-race.cjs`, `rotation-edges.cjs`, `rotation-read-retry.cjs` (cold and warm),
`rotation-rename-gap.cjs`, `torn-engine.cjs` and `writer-and-named-limit.cjs` pass; on b599d4d7e
the same cases fail by assertion as the verifier recorded (`rotation-race.cjs`,
`rotation-edges.cjs` and `writer-and-named-limit.cjs` pass there too). Cases 6 and 14 took 1,202
and 2,004 ms in the full run of the check.

After the 7c67eb5d3 fixes, every verifier harness of rounds 2 to 6 was run again from copies with
fresh folders, against this change and b599d4d7e. Round 6's `proof-extra.cjs` (5) and
`r6-retention.cjs` (6), round 5's `proof-probes.cjs` (5) and `r5-retention.cjs` (3), the r2, r3
and r4 `independent.cjs` (19 each), r3 and r4 `independent-extra.cjs` (22 each), the two
`independent-edges.cjs` retention scenarios, `audit-edges.cjs` (19), `audit-more.cjs` (23),
`rotation-race.cjs`, `rotation-edges.cjs`, `rotation-read-retry.cjs` (cold and warm),
`rotation-rename-gap.cjs`, `torn-engine.cjs`, the four `enoent-states.cjs` states,
`writer-and-named-limit.cjs` and `incremental-tail-probe.cjs` (plain and corrupt: one 6-byte read
at the old end, one damaged line) all pass. On b599d4d7e they fail by assertion as before
(independent 5/19, independent-extra 6/22, audit-edges 11/19, audit-more 15/23, proof-probes and
proof-extra 3/5, both retention files none), with `rotation-race.cjs`, `rotation-edges.cjs` and
`writer-and-named-limit.cjs` passing there too. On the 17 MB harness, interleaved three times:
b599d4d7e 13,535 / 12,982 / 13,505 ms, 120,178 parses, not complete; this change 1,917 / 1,805 /
1,798 ms, 60,089 parses, complete; `assert-perf.cjs` and `assert-file-reads.cjs` pass. Its
`lifecycle-perf.cjs` census took 13,975 ms on b599d4d7e and 1,353 ms with this change. The 41
`test-dev-runner-*.mjs` files and `e-pair-identity.test.mjs` pass (200 tests). In the full run of
the check, cases 6 and 14 took 1,151 and 1,924 ms, and the generated cases 40 and 41 took 11,031
and 9,954 ms (they run last in the file).

Execution logs: F:/agent-sandbox/design-b6d536/b102-stop-census/ (this round's in verifier-r6/).
