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
  in either order: proven if either one proves, unless their identities conflict.

  | Subject | Record and fields | Proves |
  | --- | --- | --- |
  | Engine incarnation | `engine-exit` REQUESTED, UNEXPECTED with a code, or UNKNOWN without a code | true |
  | Engine incarnation | `engine-exit` marked reconciled | false |
  | Engine incarnation | `engine-owner-died` osCheck GONE, with its PID or without one | true |
  | Engine incarnation | `engine-owner-died` osCheck GONE naming another PID | false |
  | Engine incarnation | `engine-owner-died` ALIVE_MATCH (any class), IDENTITY_UNKNOWN, REUSED, or no osCheck | false |
  | Engine incarnation | `engine-intent`, `ledger-gap` | false |
  | Ledger child | `child-exit` REQUESTED or UNEXPECTED | true |
  | Ledger child | `child-exit` marked reconciled, or naming another PID | false |
  | Ledger child | `owner-died` osCheck GONE (any class) | true |
  | Ledger child | `owner-died` ALIVE_MATCH, IDENTITY_UNKNOWN, REUSED, or no osCheck (any class) | false |
  | Ledger child | `child-survived`, `stop-intent`, `stop-escalate`, `child-note`, `gpu-release` | false |
  | Ledger child | a second `child-start` with another identity: the childId is ambiguous, and no record naming only the childId proves | false |
  | Job witness | its own `job-process-exit`, with or without a code | true |
  | Job witness | its own exit that lost track of the birth, or one tick off its start | false |
  | Job witness | its own exit marked reconciled, without osCheck | false |
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
  new line does not parse) and read again in full. The ledger writers only append and rotate.

What changed:

- `scripts/dev/lib/stop-exit-census.cjs`: `proofOfRecord` and `provenExitOf` decide exit proof
  per the table above, from all of an identity's records. `ledgerView` gives each Engine
  incarnation and ledger child `exit` (proven only), `proof` and `unproven`, and each witness a
  `proof` (`provenWitnesses`); a childId or incarnation with two different start identities is
  ambiguous. `endedIdentitiesOf(view)` lists the identities with a proof, with their `birthTick`
  (the microsecond for an Engine start a dev runner or the CLI wrote, else exact). `exitResult`
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
  or moving file (ENOENT, EPERM, EBUSY, EACCES), a short read, or a grown file whose first new
  line does not parse. After 8 attempts, 5 ms apart, it throws `LEDGER_UNSETTLED`, which
  `readRunLedger` records as the family's `error`. The verdict replaces the arming read's ledger gaps with the
  final read's. The ledger view reads each helper window's instants once and finds windows,
  owner records, owning children, console parents and report rows through PID and image indexes
  instead of rescanning every record per helper, and `readRunLedger` looks up each Engine
  owner's manifest once instead of once per child-spawn record; on the run's own 17 MB ledger the view went from 34.9 s to 0.09 s with output identical
  to b599d4d7e apart from `exitAccountingComplete`. A retried stop renames the earlier
  `exit-targets.json` and `process-exits.json` to the next free `.N.json`, merges every kept
  census's OS exits (and the targets it observed them for) into its verdict, and writes the
  merged OS exits back to `process-exits.json`. `exitAccountingComplete` is true with no gap
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
  report and the stop's output list the kept reports as `earlierReports` (oldest first), and the
  report carries the census's `earlierCensus`. The census keeps its earlier files the same way
  (40 attempts, 50 ms apart). stop-report.json is always the latest stop's, so its readers
  (including the forget check) are unchanged.

New script check `scripts/dev/test-dev-runner-stop-census.mjs` (26 cases). Cases 1 to 6 fail by
assertion on b599d4d7e, case 7 on ccd687b2d, cases 8 to 12 on f3a8282f7 (8 and 9 pass on
b599d4d7e, as preservation), cases 6, 13 and 14 on 5b53911da, cases 15, 16 and 18 on bd198cc98
(16 passes on b599d4d7e, as preservation), cases 17, 19, 20 and 22 to 26 on e3f82e1a9 (19 and 20
pass on b599d4d7e, as preservation; 21 passes on e3f82e1a9 and fails on b599d4d7e, as
preservation of the coarser matching), and all pass after:

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
   `ledgerView`, `endedIdentitiesOf` and the census verdict. Proven exactly as the table says;
   when unproven, accounting is not complete, a gap names the identity (except for a conflict)
   and its row is UNKNOWN. On e3f82e1a9 it fails for ALIVE_MATCH, IDENTITY_UNKNOWN and no-osCheck
   reconciliations classed OWNER_DIED, for an exit followed by a non-proving record, for the
   witness's owner receipt, and for exits marked reconciled.
18. Real `stopRun` with two manifest helpers whose PIDs are present but whose births cannot be
   read. The ledger has their births with sub-millisecond parts and their exits. The one whose
   manifest start matches its ledger birth at the millisecond is never looked up and is resolved
   by the ledger. The one recorded 1 ms after its ledger birth is looked up and stays unresolved.
   On bd198cc98 and b599d4d7e both were looked up 3 times.
19. Real `stopRun` over 13 identities whose records do not prove an exit (child: `owner-died`
   ALIVE_MATCH, IDENTITY_UNKNOWN, REUSED or without osCheck, all classed OWNER_DIED, then a pair;
   `child-survived`; `child-exit` naming another PID; Engine incarnation 1, with incarnation 2
   ended: `engine-owner-died` ALIVE_MATCH, IDENTITY_UNKNOWN or no osCheck; witness: its own exit
   marked reconciled without osCheck, reconciled IDENTITY_UNKNOWN, its exit one tick off). Each is
   looked up, unresolved, the stop is `STOP_INCOMPLETE` and the report's accounting is not
   complete. On e3f82e1a9 the child and Engine owner-class cases were complete.
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
   holds it, reads no record twice, and its next read equals `readLedger`. On e3f82e1a9 the
   missing file threw ENOENT at the stat and the open.
23. A family rotated at every stat: the reader throws `LEDGER_UNSETTLED`; `readRunLedger` records
   the family's error and the verdict has a `ledger-unreadable` gap and is not complete. On
   e3f82e1a9 the read returned.
24. The census verdict's read meets the current children file renamed and not yet replaced, at its
   open: the exited child is still accounted REQUESTED and the census is complete. On e3f82e1a9 it
   had a `ledger-unavailable` gap and no row for the child.
25. Real Windows sharing: a PowerShell process holds stop-report.json open without delete sharing.
   Released 650 ms after the first refused rename: the earlier report is kept byte-for-byte as
   stop-report.1.json. Held through the stop: stop-report.json is untouched and the new report is
   stop-report.1.json, with `reportWrittenAs` and `earlierReportNotArchived`. On e3f82e1a9 the
   earlier report was overwritten.
26. An earlier census whose targets file is torn (`{"targets":[TORN`) is kept byte-for-byte,
   named in `earlierCensus` and marked `readable: false` in `earlierCensusFiles`; an earlier
   census held open is left untouched and the new one is written under the next number
   (`censusWrittenAs`). On e3f82e1a9 `earlierCensus` left out `exit-targets.1.json`.

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

Execution logs: F:/agent-sandbox/design-b6d536/b102-stop-census/ (this round's in verifier-r4/).
