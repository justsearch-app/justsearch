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
  start's PID and birth proves that start alone. Round 8: a record naming more than its key (a
  PID, a birth, or a child's incarnation) proves a start only when it carries and agrees on every field the start carries
  (PID, birth, childId, owner instance, incarnation); a key-only record proves the one start of its
  key only when its owner or writer is the start's owner instance and no record of the key
  contradicts the start (names another PID, birth or owner), which then also undoes any key-only
  proof in a pair.

  | Subject | Record and fields | Proves |
  | --- | --- | --- |
  | Engine incarnation | `engine-exit` REQUESTED, UNEXPECTED with a code, or UNKNOWN without a code, written by a writer bound to the start (round 11) | true |
  | Engine incarnation | `engine-exit` marked reconciled | false |
  | Engine incarnation | `engine-owner-died` osCheck GONE, with its PID or without one, written by a writer bound to the start (round 10) | true |
  | Engine incarnation | `engine-owner-died` osCheck GONE naming another PID | false |
  | Engine incarnation | `engine-owner-died` ALIVE_MATCH (any class), IDENTITY_UNKNOWN, REUSED, or no osCheck | false |
  | Engine incarnation | `engine-intent`, `ledger-gap` | false |
  | Engine incarnation | `engine-exit` or `engine-owner-died` naming only the incarnation, written by an Engine that is neither the start's process nor the writer that recorded the start, or by a writer with its PID started at another millisecond (round 9) | false |
  | Engine incarnation | `engine-exit` or `engine-owner-died` naming only the incarnation, written by a supervisor (shell, dev runner, dev CLI) that did not record the start, or by the start writer's PID with another start instant or role, unless it is the stopping process's own writer bound to the identity that stop checked (round 10) | false |
  | Engine incarnation | `engine-exit` or `engine-owner-died` GONE naming the start's whole PID and birth, written by a writer not bound to the start (another dev runner, a dev CLI, a shell, another Engine, the start writer's PID with another start instant or role) (round 11) | false |
  | Engine incarnation | an exit or GONE reconciliation of another incarnation, or one naming another birth than the start (at the start's measured tick) | false |
  | Engine incarnation with two start identities | an exit or GONE reconciliation not naming the start's PID and birth | false |
  | Engine incarnation | two exits with different codes | true, and a `ledger_codes_disagree` gap |
  | Ledger child | `child-exit` REQUESTED, UNEXPECTED with a code, or UNKNOWN without a code, naming only the childId, written by its owning Engine | true |
  | Ledger child | `child-exit` naming only the childId, written by another Engine (round 8) | false |
  | Ledger child | `child-exit` marked reconciled, naming another PID, or naming its PID with another birth | false |
  | Ledger child | `owner-died` osCheck GONE (any class) naming the owner its spawn named (PID, birth and instance) | true |
  | Ledger child | `owner-died` osCheck GONE naming another owner PID, birth or instance, or no `previousOwner` when its spawn named one | false |
  | Ledger child | `child-exit` or `owner-died` naming another incarnation than its spawn | false |
  | Ledger child | two `child-exit` with different codes | true, and a `ledger_codes_disagree` gap |
  | Ledger child | `owner-died` ALIVE_MATCH, IDENTITY_UNKNOWN, REUSED, or no osCheck (any class) | false |
  | Ledger child | `child-survived`, `stop-intent`, `stop-escalate`, `child-note`, `gpu-release` | false |
  | Ledger child | a second `child-start` with another identity under its childId: then no record naming only the childId proves either start | false |
  | Ledger child, its childId shared with another start | `child-exit` naming its PID, birth, owner and incarnation, or `owner-died` osCheck GONE naming its PID, birth, incarnation and its owner as `previousOwner` (round 8) | true |
  | Ledger child | `child-exit` naming its PID and birth only, or without its owner or its incarnation (round 8) | false |
  | Ledger child | a start whose spawn, later spawn or second record names another owner or incarnation: an `identity_metadata_conflict` (round 8) | false |
  | Job witness | its own `job-process-exit`, with or without a code | true |
  | Job witness | its own exit that lost track of the birth, or one tick off its start | false |
  | Job witness | its own exit marked reconciled, without osCheck | false |
  | Job witness | its own exit, or a reconciled osCheck GONE, naming another owner instance than its start | false |
  | Job witness | reconciled `job-process-exit` osCheck GONE, written by the dev runner or the next Engine | true |
  | Job witness | reconciled osCheck GONE written by the witness's own Engine | false |
  | Job witness | reconciled IDENTITY_UNKNOWN, ALIVE_MATCH, or no osCheck | false |
  | Job witness | `helper-start` and `helper-exit` alone | false |
  | any | `job-process-start`, `helper-intent`, `helper-window-start`/`-end`, `engine-start`, `engine-self-intent`, spawn records | false |

  Round 11, beside the ledger: a stop's first identity-checked lookup, before it acts, that finds a
  recorded identity's PID absent, or naming a process whose birth it read and which is another
  birth (at the coarser of the two ticks), ends every identity of that PID born no later than the
  lookup. The census accounts each such identity ended, exit code unknown unless the OS gave one,
  reason "ended, observed by identity-checked lookup". It resolves only gaps that say the end was
  unproven or unrecorded (`exit_unproven`, `ledger_record_missing`, `unrecorded_exit`,
  `final_owner_receipt_unavailable`, the teardown and pre-stop handle gaps); a gap naming a PID
  alone only when every unproven identity of that PID was found ended. Disagreements, conflicts,
  damage and lost evidence stay. A lookup that failed or could not read the birth ends nothing.

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

Follow-up decisions after the independent verifier rejected a2c0400b3 (decided by the
orchestrator):

- One identity value per start: "PID, birth with its measured precision and source, childId,
  owner instance and incarnation", built when the start's record is read and never overwritten.
  Later metadata that disagrees with it (a later `child-spawn` of the childId naming another owner or
  incarnation, a `child-start` naming another than its spawn, the same process recorded again under
  other metadata) is itself a finding: an `identity_metadata_conflict` gap naming the start, and no
  record proves that start. (On a2c0400b3 an exit that left out the start's owner or incarnation
  proved it, as a missing field matched anything, and the latest `child-spawn` of a childId was
  applied to every start under it, so an exit naming the later owner proved an earlier start.)
- One comparison function, `compareIdentity` (`scripts/dev/lib/ledger-identity.cjs`), with the
  whole value on both sides; no consumer compares a PID or a birth itself. Its rules: `same` (one
  process: PID, birth at the coarser of the two measured ticks, and every other field both carry),
  `proves` (a ledger record proving a start's end), `contradicts` (a field both carry disagrees),
  `exact` (one start, every field equal, births to the tick) and `native` (PID and birth alone, for
  pairing records whose other fields describe a different relation, such as a receipt's creator;
  never a proof). Its consumers: exit proof (`proofOfRecord`, `provenExitOf`, the witness proofs in
  `ledgerView`); receipt and owner selection in `ledgerView` (`createdBy`, `sameOwner`, a helper's
  owner Engine or parser); the start index of `ledgerView` (one start per process under a childId,
  the conflicts above); the ended-identity list (`endedIdentitiesOf`); lookup suppression
  (`ledgerResolution(view).isEnded`, the dev runner's `provenEnded`, for every lookup phase, the
  post-kill wait, the Engine job stop and the port-owner candidates); runner matching (the dev
  runner's `checkRecordedProcess`, a tracked record against its OS row); the Engine exit the stop
  takes from the census (`pendingEngineExit`); target building, arming and the verdict
  (`exitTargets`, `exitResult`, `ledgerExitFor`, `ledgerSummaryOf`); and GONE and witness
  reconciliation (`reconcileCrashedHelpers`). The dev runner's three places are marked
  `// <ledger-resolution>`; a structural check reads the census and those regions, comments
  stripped, and fails on any PID or birth comparison, a birth taken as a number, an identity
  keyed by a PID/birth string, or a read of the birth-precision table outside the module.
- The rule for `proves`: a record that names more than its key (a PID, a birth, or a child's
  incarnation) must carry and agree on every field the start carries (PID, birth at the start's measured tick, childId or witnessId,
  owner instance, incarnation). A missing field is never a wildcard. A key-only record (a
  `child-exit` or `owner-died` naming only the childId; an Engine record naming only the
  incarnation) proves a start only when exactly one start in the run has that key, the record's
  owner (`owner`, `previousOwner`), or else its writer (an Engine writer whose PID and `startedAt`
  are the owner's PID and birth to the millisecond), is the start's owner instance, and no other
  record of the key contradicts the start. A child start with no owner is never proven by a
  key-only record. When two starts share the key it proves neither.
- Edge 1 (`missing-child-exit-native-fields`, a key-only exit of the one start under a childId)
  was corrected on evidence: the real writers emit key-only exits (DefaultChildControl,
  LedgerReconciler, PredecessorChildStop, `engine-actuator.cjs`), the schema allows them, and S1
  (zero lookups for a ledger-ended identity) needs them. Such an exit, written by the start's
  owning Engine or naming its owner, still proves that start, so the stop never looks it up.
  Adding PID and birth to the exit writers is a follow-up after part 1, not done here.
- An Engine incarnation's key-only record has no birth; the dev runner's `engine-owner-died` names
  the PID, which must then agree with the start's. (This reading lets `engine-owner-died` GONE
  with `{ incarnation, pid }` prove the one start of its incarnation, as the proof table already
  had it.) Confirmed on the writer: the dev runner's reconcile (`engine-actuator.cjs`) is the only
  writer of `engine-owner-died` (the Engine's `LedgerRecordType` names it but writes none), and
  it writes `{ incarnation, basis, osCheck, exitClass, pid }` for the one open start of that
  incarnation it read. Driven for real (verifier-r8/confirm/engine-owner-died-real-writer.cjs),
  its GONE record, for an absent PID or one reused by another birth, proves that start; its
  ALIVE_MATCH and IDENTITY_UNKNOWN records do not.
- Writer agreement (the assumption that changed): the verifier's fixtures of rounds 2 to 7 gave the
  Engine writer any `startedAt` (a year after the owner's birth in the 17 MB perf fixture, about
  three hours before it in `rotation-rename-gap.cjs` and `enoent-states.cjs`). Round 8 holds a
  key-only exit to its writer being the owner: its PID and its `startedAt` the owner's birth to the
  millisecond. That is what a real Engine writer records: `startedAt` is
  `ProcessHandle.current().info().startInstant()` (DefaultChildControl.java:198), written through
  `LedgerTimestamps.format` (`truncatedTo(MILLIS)`). Measured on the repo's JDK (Temurin 25.0.2,
  `resolve-jdk.cjs`), 40 JVMs each printing that value while the native reader
  (`read-process-birth.ps1`, GetProcessTimes) took their FILETIME: the value was the native birth
  truncated to the millisecond in 40 of 40, 22 of them 0.5 ms or more past their millisecond, so
  Java truncates and never rounds, which is `birthMs`. Case 60 checks it on every run. A writer a
  year or three hours away from its own birth cannot occur, so those fixtures were wrong and the
  contract stands (coordinator decision). Corrected copies sit beside the originals, which are
  unchanged: `rotation-rename-gap-r8.cjs`, `enoent-states-r8.cjs`, `make-perf-fixture-r8.cjs` and
  `lifecycle-perf-r8.cjs`, each with only `startedAt` derived from the owner's birth. A misaligned
  writer still proves nothing, so the stop falls back to looking the child up and stays safe
  (case 59).
- Carry by default: every gap a stop reports is a finding, carried into every later stop of the
  run (`carriedFrom`), keeping accounting incomplete until a person forgets the run, unless its kind
  is on the short not-seen-yet list (`NOT_SEEN_YET`): `unrecorded_exit`, and a `ledger-damaged`
  gap that is a torn last line of one line. Those only say an exit was not yet seen, and a later
  record or read can account it. Everything else is carried, a kind added later included; so
  `exit_unproven`, `ledger-unreadable`, an identity-bound OS exit that was unavailable,
  `identity_metadata_conflict` and `unaccounted_process` (a contradicted containment) are now
  carried, which the 7c67eb5d3 decision above had looked for again. (On a2c0400b3 a stop that
  observed a child alive after its owner's job had closed, against recorded containment, was
  followed by a retry that dropped the contradiction and was complete.) Two edges of the same rule
  that the verifier's exploratory `sticky-edges.cjs` cases found on a2c0400b3 are fixed with it: a
  self-exit report, which takes no census, carries the earlier stops' findings too (it is the
  latest `stop-report.json`, so it never reads as if they had not been found); and an earlier
  report or census file that parses to anything but a report (`null`, a list, a number) cannot be
  read, which is a gap, like one that does not parse.

Follow-up decisions after the independent verifier rejected 3e9ebd546 (decided by the
orchestrator; four regressions, each passing on b599d4d7e: a start appended after arming under
another childId or incarnation borrowed an ended start's exit row through their shared PID and
birth; two tracked records of one process under two childIds were merged into one, so the unproven
one was never looked up; a coarse declared `birthTick` beside a native birth let an exit of a birth
one tick away end a live tracked process; and an Engine's incarnation-only exit written by another
Engine proved a live Engine start):

- One plain decision, used in production: `isProven(start, allRecords, allStarts)`
  (`stop-exit-census.cjs`) decides whether a start is proven ended, by brute force over every
  record and every start it is given, with the agreed rules: a record agreeing with the start's
  whole identity, native births compared to the tick; a key-only record only for a key no other
  start shares and no record of the key contradicts; its writer the start's owner (a child's or a
  witness's), and never a start with a conflict. The census decides every start with it (Engine
  starts, each child start, each job witness start); a start that is not proven is looked up.
  Indexes only narrow the candidates: the census passes isProven the records and starts of the
  start's key (its incarnation, childId or witnessId), which never changes its answer, and never
  decides itself. `ledgerView` returns every start with its answer (`allStarts`), and a generated
  check compares each one with isProven over all records of the ledger.
- A row is the start's own: the census's verdict counts a start as observed only by a row written
  for it (the same PID and birth and the same childId or witnessId, and incarnation for an Engine),
  never by a row of another start of the same process.
- Tracked starts are a list of distinct records, never a map keyed by less than the whole
  identity: the dev runner's `trackedIdentities()` merges two records only when they are the same
  identity (compareIdentity `exact`) measured at the same tick, the second one's fields over the
  first's, owned when either says so. Every other merge or overwrite of tracked identities is gone.
- A birth read natively is exact whatever tick a record declares beside it (`identityOf`): two
  native births are compared to the tick, never at a coarser declared `birthTick`.
- (Superseded in round 10, below: a writer's role is never evidence, and the supervisor clause is
  withdrawn.) An Engine's incarnation-only record proves its one start only when a supervisor wrote it (the
  dev runner, the dev CLI or the shell, the only writers of `engine-exit` and `engine-owner-died`
  that name only the incarnation: `engine-actuator.cjs`, `createCliEngineRecorder`), the Engine
  itself (its PID, its `startedAt` its birth to the millisecond), or the writer that recorded the
  start (the same role, PID and `startedAt`; the verifier's `audit-edges.cjs` and `audit-more.cjs`
  fixtures record an Engine start and its exit through one Engine writer, and pass on 3e9ebd546).
  Any other Engine, or a writer with the Engine's PID started at another millisecond, proves
  nothing.
- Any other record of a childId (`child-note`, `stop-intent`, `stop-escalate`, `child-survived`,
  `gpu-release`) naming another owner or incarnation than a start of the childId is a conflict on
  that start, whichever was written first.

Follow-up decisions after the independent verifier rejected ba109f4ab (decided by the
orchestrator; an unbound supervisor writer proved a live Engine's exit through its role alone, and
two whole child starts of one childId, PID and birth that differed in owner or incarnation were
collapsed into one):

- A writer's role is never evidence; the round-9 supervisor clause is withdrawn. An Engine's
  incarnation-only record proves its one start only when its exact writer identity (role, PID and
  start instant) is bound to that start: the Engine itself, or the writer that recorded the start.
  A dev CLI stop's own `engine-intent` binds nothing: one real `dev-runner stop` from a second CLI
  process against a fixture run (verifier-r10/real-cli-stop) wrote its intent and exit as
  `dev-cli` with its own PID and the instant of its first write, and no record before them, nor
  the run record or lease (they name an agent session), names that writer.
- Within one stop, the stopping process's own writer is bound to an Engine start only when that
  same process checked the Engine's identity (`checkRecordedProcess` `match`: the recorded PID and
  birth against the OS row) before writing its intent and exit (`stopWriterBinding`). The binding
  is the checked PID and birth with the writer and incarnation, held in memory and passed to the
  census directly; it is never derived from the ledger. A stop that did not check (an unreadable
  birth) has none, a checked identity other than the start's binds nothing, and a later stop never
  inherits it, so it looks the Engine up. Recording the checked PID and birth on the dev CLI's own
  intent and exit records is logged as a follow-up.
- The no-merge rule for child starts: two child-starts of one childId and one process (PID and
  birth) under another owner or incarnation are two starts. Both are kept and reported, the
  disagreement is a conflict on each (an `identity_metadata_conflict` finding per start), and
  neither is proven. Only a start recorded again with every field equal (compareIdentity `exact`)
  is one start.

Follow-up decisions after the independent verifier rejected 5b4cea41b (decided by the
orchestrator; an unbound supervisor writer proved a live Engine's exit by naming its whole PID and
birth, an identity-checked OS lookup that found a crashed Engine gone left accounting incomplete, a
later real CLI stop lost completeness, and the live CLI read the whole Engine ledger again):

- Writer binding applies to every supervisor-written Engine record that would prove an exit:
  `engine-exit`, GONE and `engine-owner-died`, naming the incarnation alone or the whole identity.
  An unbound writer's record is evidence to look the Engine up, never proof. Child records written
  by the Java Engine keep the agreed rules.
- An identity-checked OS observation that the exact PID-plus-birth identity no longer exists
  proves the process ended: the PID absent, the PID reused by another birth, or the process
  exited. It counts as complete for that identity, with exit code unknown and reason "ended,
  observed by identity-checked lookup". A missing exit code is a fact to report, not a gap. An
  unreadable lookup is still incomplete (S4 unchanged).
- A later real CLI stop is fixed by the decision above: the earlier report's completeness is
  carried, and the later stop is complete through its own lookup.
- The actuator uses the same incremental, handle-identified reader as the census: unchanged files
  are not reopened, and grown files are read only for their tail.

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
- Round 8, new `scripts/dev/lib/ledger-identity.cjs`: `identityOf` (the identity value of any
  record, target, OS row or tracked record: PID, birth, its tick and source, childId, witnessId,
  owner instance, owner, incarnation), `startIdentity` (a ledger start's value, frozen),
  `noteConflict`, `compareIdentity` with the five rules above, `writerIsOwner`, and the precision and
  ordering helpers (`TICK`, `birthMs`, `birthFileTime`, `birthOrder`, `bornNotAfter`, `bornAfter`,
  `bornAt`, `identityKey`, `namedBy`, `hasPid`). An object's value is cached against the fields it
  was read from, owner included, so an object changed in place is read again.
  `stop-exit-census.cjs` is rewritten onto it: `ledgerView` builds each start's identity from its
  own record (its spawn's owner and incarnation only where the start names none) and notes
  conflicts; `provenExitOf` takes the run's count of starts per key and whether a record of the key
  contradicts the start; `exitResult` adds an `identity_metadata_conflict` gap per conflicting start;
  `ledgerResolution(view)` gives `ended` and `isEnded(tracked)` (ended only when some start agrees
  with the tracked identity and every start that agrees is proven ended), indexed by PID;
  `NOT_SEEN_YET` and `isFinding` carry every other gap. `dev-runner.cjs`: lookup suppression,
  `checkRecordedProcess` and the Engine exit match go through `ledgerResolution` and
  `compareIdentity`. `checkRecordedProcess` now reads the OS row as what it is, a CIM read of the
  process table (the microsecond, unless the lookup names its tick); before, it declared the row
  exact, so a tracked identity recorded to the tick whose birth is not a whole microsecond was
  called `reused` against its own row and a live process was taken as gone (also on b599d4d7e).
- Round 8 test fixtures: the writers in `test-dev-runner-exit-census.mjs`,
  `test-dev-runner-exit-census-evidence.mjs`, `test-dev-runner-ix-lifecycle.mjs` and
  `test-dev-runner-witness-classification.mjs` now carry a PID and a `startedAt` equal to their
  owner Engine's birth in milliseconds, as the real `LedgerWriterId` does (`startedAt` is the
  ProcessHandle start instant; see `governance/fixtures/exit-ledger/engine-written/`). Their
  key-only exits had proven the child through writers with no PID or start time, or a start time
  other than the owner's birth; nothing binds such a writer to the child's owner, so the round-8
  rule refuses them.
- Round 8, cost: the census matched each ledger item against every gap (`denied`), and each earlier
  census record against every other (`keepEarlierCensus`, the verdict's merge), by scanning. With
  the identity rule each of those comparisons builds or checks an identity value, so a stop with
  thousands of gaps took minutes (537 s on the verifier's 17 MB fixture, where no child's exit is
  proven). They now go through indexes with the same answers: the denying gaps kept apart as they
  are added, earlier records by their key (`keysOf`, `namedIn`), and the OS exits by the key they
  echo (`osExitOf`).
- Round 8, carry: `dev-runner.cjs` `writeSelfExitStopReport` carries the earlier reports' findings
  (`carryFindings`; `exitAccountingComplete: false` and the carried gaps when there are any, left
  unset otherwise, as before); `keepEarlierReports` and the census's `keepEarlierCensus` treat a
  file that parses to no object as unreadable.
- Round 9, `stop-exit-census.cjs`: `proofOfRecord` and `provenExitOf` are replaced by `proofBy`
  (how one record proves a start, by family) and `isProven` (exported), and `proofFields` (a
  start's `exit`, `proof`, `unproven` and `conflictingCodes` from isProven's answer). `ledgerView`
  decides each Engine start, child start and witness start with isProven, given the records and
  starts of its key, and returns them as `allStarts`; witness starts are grouped per witnessId
  (the same start recorded twice is one). `CHILD_META_TYPES` records naming another owner or
  incarnation are conflicts on the starts of their childId. `exitResult` notes each row with the
  start it was written for (`rowWrittenFor`): a target, a console host, an accounted process, a
  ledger start. A ledger start (the inventory: every Engine start, child start and witness) and
  each job witness start of the run are covered only by a row written for that start (`rowFor`:
  the same PID and birth, childId and witnessId, and incarnation for an Engine); other gaps and
  observed processes are covered by a row of their PID and birth, as before. `ledger-identity.cjs`: `identityOf` takes a native
  source as exact before any declared tick; `compareIdentity` `proves` takes an Engine's
  incarnation-only record only from a supervisor (`SUPERVISOR_ROLES`, removed in round 10), the Engine itself, or a
  writer that recorded the start (`startWriters`: `ledgerView` keeps each Engine start's writers,
  one per record of the same start).
  `dev-runner.cjs`: `trackedIdentities()` (in a `<ledger-resolution>` region, exported for the
  check) holds the stop's tracked records as a list; `stopRun` uses it for every source of tracked
  records (earlier trees, the run record, `stop-tracked-processes.json`, the manifest helpers and
  every phase's additions).
- Round 10, `ledger-identity.cjs`: `compareIdentity` `proves` takes an Engine's incarnation-only
  record only from the Engine itself or a writer of the start (`startWriters`); `SUPERVISOR_ROLES`
  is gone. `writerOfThisProcess` tells a writer of the running process. `stop-exit-census.cjs`:
  `stopWriterBinding` validates a stop's binding (its writer this process's, a checked PID and
  birth, an incarnation); `ledgerView(ledger, runId, { stopBindings })` adds a bound writer to the
  writers of the Engine start the checked identity names (compareIdentity `same`, incarnation
  included); `startStopExitCensus` reads `runtime.stopBindings` when it builds each view. A
  child-start is merged into an earlier one only when `exact`; one of the same process under
  another owner or incarnation is pushed as its own start with a `restart` conflict on both.
  `engine-actuator.cjs` `createCliEngineRecorder`: `writerIdentity()` returns the identity it
  writes as once it has written (records unchanged). `dev-runner.cjs` `stopRun`: after the
  recorder's first record for an Engine whose identity this stop checked, the stop adds that
  binding (`stopBindings`), which its ledger resolution and its census use.
- Round 9 test fixtures: the writers in `test-dev-runner-owner-coverage.mjs`,
  `test-dev-runner-delayed-crash-receipt.mjs` and `test-dev-runner-shim-console.mjs` now carry
  their start instant (`startedAt`, the writer's birth to the millisecond), as the ledger reader
  requires of every writer (`exit-ledger.cjs` `readWriter`). Without it their Engine's own
  incarnation-only exit no longer proved its start (the writer could not be told to be that
  Engine), and three cases failed; with it they pass here, on 3e9ebd546 and on b599d4d7e.
- Round 11, `ledger-identity.cjs`: `compareIdentity` `proves` checks the writer binding of every
  Engine record before its shape: the Engine itself, a writer of the start, or a stop's bound
  writer (`startWriters`), whether it names the incarnation alone or the whole PID and birth.
  `stop-exit-census.cjs`: `lookupEndedIdentities` validates a stop's lookup observations (`pid`,
  `observed` `absent` or `reused` with `observedCreationFileTimeUtc` and its tick, `observedAtMs`);
  `exitResult(..., { lookupEnded })` judges each identity against them with compareIdentity
  (`endedByObservation`), resolves the gaps above into `resolvedByLookup`, and gives each such
  identity's row `ledgerReason` "ended, observed by identity-checked lookup", `endedObservedBy`,
  and an exit code only when the OS gave one; an armed target the lookup found ended, with nothing
  else known of it, gets such a row. `startStopExitCensus` `finish({ beforeVerdict, lookupEnded })`
  passes them through. `dev-runner.cjs` `stopRun`: after its first lookup and before acting,
  `noteLookupEnded` records each unproven recorded identity with a birth whose PID the lookup
  found absent, or reused by another readable birth, with the time the lookup was asked; the stop
  hands them to the census and reports them as `endedByLookup` (report and output). It passes its
  incremental reader to the dev CLI recorder. `engine-actuator.cjs`: `createEngineActuator` and
  `createCliEngineRecorder` take a `ledgerReader` (default: a new incremental reader) and read the
  Engine ledger only through it (`reconcile`, the self-intent check, `exitRecorded`).
- Round 11 test fixtures: in `test-dev-runner-stop-census.mjs`, the later receipt of the
  disagreement case (39) was written by a dev runner writer with the right PID but started at that
  moment, which is not the writer that recorded the start; it now carries that writer's start
  instant, so the receipt proves the start and the case keeps checking that a disagreement is never
  cleared by it. Without it, `ledger_codes_disagree` was missing (the receipt no longer proved).
  In `test-dev-runner-terminal-children.mjs` (failed Engine quit, llama cleanup), the lookup stub
  answered every PID with the backend's birth, so the stop's first lookup saw the running llama
  child's PID name another birth and handed that observation to the census, whose stand-in asserts
  no option names the child's PID (the stop's own kills are not handed to it). The stub now answers
  the child's PID with its own manifest start while it runs, as the real lookup does; the
  assertion is unchanged. It passes here and on 5b4cea41b; unchanged, it failed here by assertion.
- Round 10 test fixtures: the dev runner's Engine records in `test-dev-runner-exit-census.mjs`,
  `test-dev-runner-exit-census-evidence.mjs` and `test-dev-runner-job-witness.mjs` were written by
  a role alone (`{ role: 'dev-runner' }`, once with a PID but no start instant), which the ledger
  reader never yields and which proved the Engine's exit only through the withdrawn supervisor
  clause; they now carry one whole dev runner writer for the start and its exit, and pass here
  and on ba109f4ab. `test-dev-runner-process-accounting-order.mjs` (runner job close) has a dev
  CLI recorder of this process write the intent and exit after a dev runner's start, as a stop
  does: its census now gets that stop's binding (the recorder's `writerIdentity()` and the
  Engine's PID and birth) through `ledgerView`, as `stopRun` passes it. Without these, seven cases
  failed by assertion (the Engine's exit unproven).

New script check `scripts/dev/test-dev-runner-stop-census.mjs` (90 cases; 40 and 41 rewritten and
42 to 61 added in round 8, 62 to 71 in round 9, 69 rewritten and 72 to 82 added in round 10, 40 and
71 extended and 83 to 90 added in round 11, with their own before-results below). Cases 1 to 6 fail by
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
40. Generated (rewritten in round 8): 2,000 ledgers from seed 0xB6D536, Engine or child. Starts
   carry owner and incarnation fields of their own or none; spawns name them, later spawns,
   restarts and second starts of the same process name others; exits and reconciliations name or
   leave out a PID, a birth, a childId, an owner (whole, without its instance, another instance,
   PID or birth) and an incarnation, each right, wrong or one tick off, written by the owning Engine
   or another, in every order. Each is judged by the census (`endedIdentitiesOf` and the verdict of
   a census armed before any exit, the OS reporting what it saw) and by a whole `stopRun` (the real
   census behind a stand-in for the PowerShell observer) of a run tracking one identity in some
   form (whole, bare, another owner, incarnation or childId, coarse, one tick off), its OS lookup
   stubbed with CIM-like rows (alive, reused, unreadable, gone): whether the stop looks it up, its
   outcome and accounting. The oracle in the test is the round-8 identity rule alone, written
   independently of `ledger-identity.cjs`. A mismatch prints the seed, case and input and names
   the kinds misjudged (census, lookup, outcome, accounting); `STOP_CENSUS_GEN_CASE=<n>` runs one
   case and `STOP_CENSUS_GEN_DUMP=<dir>` writes the mismatches as JSON. On a2c0400b3, 559 of the
   2,000 are misjudged (census 178, lookup 170, outcome 409, accounting 144; a case can be
   misjudged several ways): 178 prove a start the rule does not (a missing field taken as a
   wildcard, owner or incarnation lost), 170 skip the lookup of a tracked identity the ledger does
   not end, and 371 call a running tracked identity reused against its CIM row (case 52). On
   b599d4d7e the check cannot run: there is no `endedIdentitiesOf`.
41. Generated (rewritten in round 8): 250 histories, 378 real `stopRun` stops with the stand-in
   observer: up to three earlier stop reports or census files, each unreadable or holding up to
   three gaps from a table of findings (`identity-bound`, `exit_unproven`, `ledger-unreadable`,
   `identity_metadata_conflict`, a kind added later, the disagreements) and not-seen-yet gaps
   (`unrecorded_exit`, a torn last line), then one to three stops, each of which may find a
   contained child alive after its owner's job closed. Complete exactly when no earlier file is
   unreadable or holds a finding and no stop so far found the containment contradicted; every
   unreadable file and finding is named, and a contradiction an earlier stop found is carried
   from it. On a2c0400b3, 148 of the 250 disagree: 30 drop a containment contradiction an earlier
   stop of the history found, the rest drop a finding that is no longer on the not-seen-yet list.
42. to 50. Round 8 identity edges, each through the real census (its PowerShell observer) and a
   whole real `stopRun` of a run tracking a running child of the test, the OS lookups the real ones:
   an exit naming the child's PID, birth and owner but not the incarnation its start carries (42),
   or PID, birth and incarnation but not its owner (43); a `child-start` naming another owner
   instance (44) or incarnation (45) than its spawn; a later `child-spawn` naming another owner,
   with an exit naming that owner (46); the same PID and birth under a second childId whose start
   no exit names (47); a childId-only exit with two starts under the childId (48), written by
   another Engine (49), or naming another owner instance (50). Each time the stop looks the child
   up, finds it running, is `STOP_INCOMPLETE`, and accounting is not complete. 42 to 47 are the
   verifier's reproductions and fail by assertion on a2c0400b3 (the child was taken as ended and
   never looked up), as does 49; 48 and 50 pass on a2c0400b3. All pass on b599d4d7e, as
   preservation.
51. A tracked record naming another owner instance than the child's start, the child's own exit in
   the ledger: the child is looked up and blocks the stop. The verifier's reproduction; fails on
   a2c0400b3 (no lookup), passes on b599d4d7e.
52. Runner matching: a tracked identity recorded to the tick, its birth not a whole microsecond,
   running: the stop finds it running and is `STOP_INCOMPLETE`. On a2c0400b3 and b599d4d7e it was
   called reused against its own CIM row and the stop was `STOPPED`.
53. Edge 1 corrected: the owning Engine's `child-exit` naming only the childId of the one start
   under it proves that start (S1): the stop never looks it up, `stop-report.json` lists it in
   `resolvedByLedger`, the stop is `STOPPED` and accounting complete. Passes on a2c0400b3; on
   b599d4d7e it was looked up.
54. Sticky containment: a running child recorded as contained, its owner's job closed more than
   the grace period before the observation; the first real stop reports `unaccounted_process`
   ("contradicts recorded containment"). The child ends and a retry runs with an empty scope: it
   carries the gap (`carriedFrom`) and is not complete. Fails by assertion on a2c0400b3 (the retry
   was complete) and on b599d4d7e (no carried gap).
55. Real `stopRun` after an earlier stop-report.json, or an earlier process-exits.json, holding
   `null`, `[]` or `7`: accounting is not complete and an `earlier-report-unreadable` (or
   `earlier-census-unreadable`) gap names the file. Fails by assertion on a2c0400b3 (complete) and
   on b599d4d7e.
56. `writeSelfExitStopReport` after an earlier report holding `ledger_os_disagree`: the report, and
   stop-report.json on disk, are not complete and carry it from stop-report.1.json; after one
   holding only `unrecorded_exit`, accounting is left unset. Fails by assertion on a2c0400b3 and
   b599d4d7e.
57. Structural: the census, and the dev runner's three `<ledger-resolution>` regions, comments
   stripped, hold no PID or birth comparison, no birth taken as a number, no PID/birth key string
   and no read of the birth-precision table. Fails on a2c0400b3 and b599d4d7e.
58. Real `stopRun` over a 17 MB ledger of 19,195 Engine children whose key-only exits prove
   nothing (their writer records a start a year after the owner's birth): every child is an
   `exit_unproven` gap, each ledger file is read once, and the stop takes under 30 s. Before the
   round-8 indexing below it failed by assertion after 561,570 ms. b599d4d7e took about 13 s on
   the verifier's fixture of the same shape.
59. Identity edge `key-only-exit-misaligned-writer`: a key-only `child-exit` of the one start
   under its childId, written by a writer with the owner's PID but a `startedAt` a year after, or
   about three hours before, the owner's birth (the misaligned fixtures). Through the real census
   and the real OS lookups, the stop looks the live child up, finds it running, and is
   incomplete. Fails by assertion on a2c0400b3 (the child was taken as ended, never looked up);
   passes on b599d4d7e, as preservation.
60. Writer agreement: eight real JVMs from the repo's JDK each print their own PID and
   `startInstant` as the Engine's writer records it; the native reader takes their FILETIME while
   they live. `birthMs` of that birth is the printed millisecond, `writerIsOwner` holds, and a
   key-only exit that writer wrote proves its one child. It needs ledger-identity.cjs, so it does
   not load on a2c0400b3 or b599d4d7e; against a copy of it whose `birthMs` rounds it fails by
   assertion on the first birth 0.5 ms or more past its millisecond.
61. Real `stopRun` after an earlier report holding one gap of every kind: `exit_unproven`, an
   identity-bound OS exit unavailable, `identity_metadata_conflict`, `unaccounted_process`,
   `ledger_codes_disagree`, `ledger_os_disagree`, `evidence_lost`, `final_owner_receipt_unavailable`,
   `ledger-unreadable`, `earlier-census-unreadable`, a damaged line that is not a torn tail, a torn
   tail of two lines, and a kind added later are each carried from stop-report.1.json;
   `unrecorded_exit` and a torn last line of one line are not. Fails by assertion on a2c0400b3
   (`exit_unproven` not carried) and on b599d4d7e (nothing carried).
62. Round 9, late childId (the verifier's reproduction): a child of the run, running, whose start
   and own exit are in the ledger; once the census has armed, a `child-spawn` and `child-start` under
   a second childId with the same PID and birth are appended. Through a whole real `stopRun` (the
   real census and OS lookups), stop-report.json and the result are not complete, and a gap names
   the process. Fails by assertion on 3e9ebd546 (complete: the late start borrowed the ended
   start's row); passes on b599d4d7e.
63. Round 9, late incarnation: an Engine start of incarnation 2 and its exit, then, after the real
   census has armed, a start of incarnation 3 with the same PID and birth: the census is not
   complete. Passes on 3e9ebd546 and b599d4d7e, as preservation.
64. and 65. Round 9, tracked list (the verifier's reproduction, in both orders): a running child
   under childId `c` with its own exit, and the same process started under `second-child`, which no
   exit names; the run tracks both records, `second-child` then `c` (64) and `c` then
   `second-child` (65). The stop looks the child up, finds it running, is `STOP_INCOMPLETE` and
   accounting is not complete. 64 fails by assertion on 3e9ebd546 (the second record overwrote the
   first; no lookup, `STOPPED`); 65 passes there. Both pass on b599d4d7e.
66. Round 9, `trackedIdentities()`: seven records of one PID and birth that differ in childId,
   owner, incarnation or declared tick stay seven, in order, each field kept; the same seven added
   again whole (owned, with an observed name) merge into them, owned. Fails on 3e9ebd546 and
   b599d4d7e (no such list).
67. Round 9, native precision (the verifier's reproduction): a running tracked process read
   natively (`creationTimeSource` native) declaring `birthTick` 10000, and a ledger child one tick
   from its birth with its exit. The stop looks it up and is `STOP_INCOMPLETE`. Fails by assertion
   on 3e9ebd546 (ended by the other birth's exit, never looked up); passes on b599d4d7e.
68. Round 9, Engine writer (the verifier's reproduction): a running process records its own Engine
   start (incarnation 2); the run's first Engine, another PID and birth, writes an `engine-exit`
   naming only incarnation 2. The tracked start is looked up and the stop is `STOP_INCOMPLETE`,
   accounting incomplete. Fails by assertion on 3e9ebd546 (`STOPPED`, complete); passes on
   b599d4d7e.
69. Engine writer table (rewritten in round 10): an `engine-exit` naming only the incarnation of an
   Engine's one start proves it when the Engine itself, the Engine writer that recorded the start,
   the dev runner that recorded it or the shell that recorded it wrote it, and the Engine itself
   after a dev runner recorded it; not when a dev runner, dev CLI or shell that did not record it,
   another dev runner, the start writer's PID a second later or under another role, a dev CLI after
   the dev runner recorded it, another Engine, a writer with the Engine's PID started a millisecond
   later, or an Engine other than the start's recorder did. Fails by assertion on ba109f4ab (the
   unbound supervisor rows prove there).
70. Round 9, related shapes the verifier found, each through the real census of a running child
   with its own exit: a later `child-note` naming another owner instance; a second childId whose
   start names a relative executable; a job witness start of the same process. None is complete.
   Fails by assertion on 3e9ebd546 (all three complete); passes on b599d4d7e.
71. Round 9, generated: 3,000 ledgers from seed 0xB6D536 ^ 9. Engine starts of up to three
   incarnations and child starts under two childIds share PIDs and births (and births one tick
   apart), some appended after the census armed; exits name the whole identity, one tick off, or
   only the key; Engine incarnation-only exits are written by the dev runner, the dev CLI, the
   shell, the Engine itself, the Engine's PID a millisecond later, the run's first Engine or the
   next one; `engine-owner-died` and `owner-died` with GONE or ALIVE_MATCH, named owners whole,
   bare, another instance or PID; job witnesses of the same processes with their own, reconciled
   or one-tick-off exits; later notes naming another owner. Tracked records name a start whole,
   bare, under another key or owner, or a process no start names, with declared ticks of 1, 10 and
   10000 beside native and CIM sources, repeated and in random order. Each case checks: every start
   written is a start of the ledger, none merged; each start's decision (`allStarts`, and the
   proof the verdict reads) is isProven's over every record and start of the ledger; the census,
   armed before the late records with no OS exit observed, is never complete over an unproven start
   and, without a witness, complete exactly when every start is proven and every start it armed for
   was proven at arming; each tracked record stays in `trackedIdentities()` whole and is looked up
   exactly when some start agreeing with it is unproven or none agrees; and a whole `stopRun`
   (stand-in observer, every tracked PID running with an unreadable birth) looks up and reports
   exactly those PIDs, is `STOP_INCOMPLETE` when any is, and is never complete over an unproven
   start. `STOP_CENSUS_REFERENCE=<file>` takes isProven from another checkout's census. Run on
   3e9ebd546 with this change's isProven as the reference, 690 of the 3,000 are misjudged
   (decision 590, census 113, stop 272; a case can be misjudged several ways): 624 starts proven
   there that isProven does not prove (552 Engine starts, mostly through another Engine's
   incarnation-only exit, and 72 child starts). On b599d4d7e the decision checks do not apply (no
   `allStarts`). Extended in round 10: Engine starts are also recorded by the shell, and Engine
   incarnation-only exits are also written by the shell, another dev runner and the dev runner's
   PID a second later, a dev CLI's sometimes after its own `engine-intent`; a child-start is
   sometimes recorded again for the same childId and process naming another owner or incarnation.
   Two checks independent of isProven are added: the starts written are compared whole (childId,
   PID, birth, owner and incarnation, each child-start's own or its latest spawn's in the order
   the census reads the ledger), so a merged start is a misjudgment; and every Engine start proven
   ended must have a record naming its PID and birth, or an incarnation-only one by a writer of
   its start or by that Engine itself (the `writer` check). Run on ba109f4ab (this file copied
   into its checkout, its own isProven the reference), 1,831 of the 3,000 are misjudged (writer
   1,368, starts 867); none here. Round 8's generated identity check (case 40) now expects an
   incarnation-only exit, all the dev runner's there, to prove only a start that dev runner
   recorded, and a child-start of the same process under another owner or incarnation to be a
   start of its own: on ba109f4ab it misjudges 33 of its 2,000 cases (census 32, lookup 19,
   accounting 23), none here.
   Extended in round 11: the whole-identity `engine-exit` and the `engine-owner-died` records (some
   naming the birth) are written by any of the dev runner, the dev CLI, the shell, another dev
   runner, the dev runner's PID a second later, the Engine itself, the run's first Engine or the
   next one, and the run's first Engine's own exit sometimes names its whole PID and birth; the
   `writer` check requires a bound writer for every proving Engine record, whole identity or not.
   The stop's lookup finds each PID absent, reused by a process of another microsecond, alive or
   unreadable (the run's first Engine absent, reused or unreadable, never running), and the stop is
   judged by the oracle's own reading of the lookup: a looked-up PID with a tracked record not
   ended is reported unresolved only when running or unreadable, the stop is `STOP_INCOMPLETE`
   when any is, and it is complete (without a witness or such a PID) exactly when every start of
   the run is proven or was found ended by the lookup (asked of its PID, born before it, absent or
   another microsecond, without a metadata conflict); never complete otherwise. Round 8's
   generated check (case 40) likewise writes each Engine exit and reconciliation by the dev runner,
   the Engine writer, the Engine itself or another dev runner, proves an Engine start only through
   a bound writer, and expects a whole stop to account each start of the run that its lookup found
   absent or reused (and the run's Engine of a child case, which it always finds absent) without a
   conflict or two codes. On 5b4cea41b (this file copied into its checkout) case 71 misjudges
   1,057 of its 3,000 cases (writer 882, stop 249, census 1) and case 40 1,067 of its 2,000 (census
   26, lookup 14, accounting 1,047); none here. With the stop's observations withheld from the census
   (`lookupEnded: []`) case 40 misjudges 1,064 of its 2,000 cases (accounting) and case 71 250 of its
   3,000 (stop); with the writer binding applied only to incarnation-only records (5b4cea41b's
   rule), case 40 misjudges 26 (census 26, lookup 14, accounting 2) and case 71 882 (writer 882,
   census 1).
72. to 75. Round 10, unbound supervisor (the verifier's `own-edges.cjs` reproductions), through a
   whole real `stopRun` of a running Engine stand-in whose incarnation-2 start one writer recorded
   and whose incarnation-only exit another wrote: another shell (72), another dev runner (73), a
   dev CLI stop after its own `engine-intent` (74), and the start writer's PID started a second
   later (75). The Engine is looked up and found running, the stop is `STOP_INCOMPLETE` and
   accounting incomplete. Each fails by assertion on ba109f4ab (no lookup, the exit taken as
   proof).
76. Round 10, bound writer: the dev runner that recorded an Engine start writes its intent and
   incarnation-only exit; the stop never looks the ended Engine up and no gap names it. Passes on
   ba109f4ab, as preservation.
77. Round 10, dev CLI fallback: an Engine that has gone, whose only exit a later dev CLI wrote
   after its intent, is looked up, found gone, and the stop is `STOPPED` with nothing unresolved.
   Fails by assertion on ba109f4ab (not looked up).
78. and 79. Round 10, whole starts (the verifier's reproductions): two child-starts of one childId,
   PID and birth of a running child, naming two incarnations (78) or two owner instances (79), and
   an exit naming the first whole. `allStarts` holds two child starts, each with a conflict and
   neither proven; the view reports both; the real census gives one `identity_metadata_conflict`
   per start and is not complete; a stop tracking the first looks the child up and is
   `STOP_INCOMPLETE`. Both fail by assertion on ba109f4ab (one start for two).
80. Round 10, stop binding table, through `ledgerView` with `stopBindings`: a dev CLI intent and
   incarnation-only exit written as this process prove the start only with a binding of this
   process's writer (same start instant), the start's PID and birth checked, under its
   incarnation; not with no binding (a later stop), an unchecked binding, a checked identity
   without a birth, another birth, another PID or another incarnation, a writer of another process
   or this process with another start instant. Fails by assertion on ba109f4ab (the role proves
   every row).
81. Round 10, unchecked stop: a whole `stopRun` of a run that asks its runner to stop, whose
   running Engine's birth cannot be read, with a dev CLI intent and exit already in the ledger
   written as this very process: the stop writes no Engine record, looks the Engine up, reports it
   unresolved, is `STOP_INCOMPLETE` and accounting incomplete. Fails by assertion on ba109f4ab
   (the exit proves it; no lookup).
82. Round 10, real dev CLI stop: a runner and an Engine (live fixture processes) and a run record
   asking the runner to stop; one real `dev-runner stop --json --run` from a second CLI process
   checks the Engine, ends both through the identity-checked terminations, and writes its intent
   and exit as `dev-cli` with its own PID. Its own stop is `STOPPED`, no gap names the Engine, and
   its row is an expected termination. A later stop in another process (the test's `stopRun`)
   looks the Engine up instead of taking the record. Fails by assertion on ba109f4ab (the later
   stop takes the exit through the dev CLI role), and fails by assertion on this change with
   `stopWriterBinding` returning null (`ledger_record_missing` for the Engine in the CLI's own
   stop), as do 80's bound row.
83. Round 11, Engine writer table, through `ledgerView`: an `engine-exit` naming the whole PID and
   birth, an `engine-owner-died` GONE naming them, one naming the PID, and an incarnation-only exit
   prove the start only when written by the dev runner that recorded it or the Engine itself; never
   by another dev runner, a dev CLI, a shell, another Engine, the start writer's PID started a second
   later or under another role. Fails by assertion on 5b4cea41b (the whole-identity rows prove from
   every writer) and on b599d4d7e (its view has no `proof`, so the bound rows prove nothing there).
84. and 85. Round 11, unbound full identity (the verifier's `unbound-full-native-engine-exit`): a
   running Engine stand-in whose incarnation-2 start a dev runner recorded, and an `engine-exit`
   (84) or `engine-owner-died` GONE (85) naming its whole PID and birth written by a dev CLI that
   did not. The real census (children family present) is not complete; a whole real `stopRun`
   looks the Engine up, finds it running, reports it and is `STOP_INCOMPLETE`, accounting
   incomplete. Both fail by assertion on 5b4cea41b (the census complete) and pass on b599d4d7e.
86. Round 11, crash recovery (the verifier's `unbound-owner-died-gone-real-lookup`): a real child
   process as the Engine, its start recorded by a dev runner; it ends; the real
   `createEngineActuator(...).reconcile()` of this process (the next supervisor, an unbound writer)
   records `engine-owner-died` GONE. A whole real `stopRun` with the real census and real lookups
   looks the Engine up, finds it gone, and is `STOPPED`, accounting complete; its row reads "ended,
   observed by identity-checked lookup" with exit code null, and `endedByLookup` names it
   `absent`. Fails by assertion on 5b4cea41b and on b599d4d7e (not complete).
87. Round 11, lookup rows: a crashed Engine (start by a dev runner, GONE by another) whose PID the
   stop's first lookup finds absent, or reused by a readable birth a minute later, is accounted
   (complete, the lookup-ended row, one `endedByLookup`); one whose birth cannot be read stays
   incomplete and `STOP_INCOMPLETE`, with nothing in `endedByLookup` (S4). Fails by assertion on
   5b4cea41b and on b599d4d7e (the absent and reused rows not complete).
88. Round 11, census, through `exitResult` with `lookupEnded`: an armed Engine start with no exit is
   accounted, its code null and the lookup-ended reason, by an observation that its PID was absent
   or named another birth; not by one naming a birth of the same microsecond (at the CIM tick), one
   made before the start's birth, an unreadable one, one without the time of the lookup, or one of
   another PID; without an observation it stays incomplete. A bound exit naming code 0 recorded
   after arming, with the OS reporting 7, keeps its `ledger_os_disagree` whatever the lookup found.
   Fails by assertion on 5b4cea41b and on b599d4d7e (no observation accounts anything).
89. Round 11, real dev CLI stops (the verifier's `real-second-cli-and-later-stop`): a runner and an
   Engine (live fixture processes); one real `dev-runner stop --json --run` from a second CLI
   process is `STOPPED` and complete; a later real CLI stop, the Engine gone, is `STOPPED` and
   complete through its own lookup (the Engine's row reads "ended, observed by identity-checked
   lookup"), lists stop-report.1.json as readable in `earlierReportFiles`, and that kept report is
   still complete. Fails by assertion on 5b4cea41b (the later stop not complete:
   `process handle unavailable before stop`) and on b599d4d7e (not complete).
90. Round 11, CLI read-once: a ledger whose Engine start the stop's incremental reader has read; a
   dev CLI recorder given that reader writes its intent and exit, and the reader reads again (the
   census's verdict). No ledger file is read whole (`readFileSync`), the start is not parsed again,
   and every Engine record is parsed once. Fails by assertion on 5b4cea41b ("no ledger file is read
   whole": the recorder's `exitRecorded` read the whole file) and on b599d4d7e (no incremental
   reader).

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

After the a2c0400b3 fixes, every verifier harness of rounds 2 to 7 was run again from copies with
fresh folders (verifier-r8/harness-logs), against this change and b599d4d7e. On this change: the
r2, r3 and r4 `independent.cjs` (19 each), r3 and r4 `independent-extra.cjs` (22 each),
`independent-edges.cjs` (21, S1-zero-lookup included), `proof-probes.cjs` and `proof-extra.cjs`
(5 each), `r5-retention.cjs` (3), `r6-retention.cjs` (6), `audit-edges.cjs` (19), `audit-more.cjs`
(23), `sticky-edges.cjs` (3, the two exploratory cases included) and `incremental-tail-probe.cjs`
(plain and corrupt: one 6-byte read at offset 791, one damaged line) pass. Round 7's
`r7-identity-edges.cjs` passes 7 of 8: `missing-child-exit-native-fields` fails, as decided (edge 1,
case 53). On b599d4d7e they fail by assertion as before (independent 5/19, independent-extra
6/22, independent-edges 5/21, proof-probes and proof-extra 3/5, both retention files none,
audit-edges 11/19, audit-more 15/23, sticky-edges 1/3, both tail probes), and
`r7-identity-edges.cjs` passes 8 of 8 there, as preservation.
With the corrected fixtures (the writer agreement decision above), interleaved three times, CPU
load 1 to 57 %: on the 17 MB harness with `make-perf-fixture-r8.cjs` (`run-perf-r8.ps1`),
b599d4d7e took 15,043 / 14,663 / 14,874 ms, 118,510 parses, not complete; this change took 2,564 /
2,592 / 2,448 ms, 59,255 parses, 1 read call per file, complete. `assert-perf.cjs`,
`assert-file-reads.cjs` and `assert-comparison-r8.cjs` pass. Its `lifecycle-perf-r8.cjs` census
took 14,509 ms on b599d4d7e (arming 5,073, verdict 9,435) and 2,135 ms with this change (1,645
and 489), complete. `rotation-rename-gap-r8.cjs` and the four `enoent-states-r8.cjs` states pass
on this change and fail by assertion on b599d4d7e (its rotation and ENOENT defects, as the
originals there). The runs over the original, misaligned fixtures follow, kept as they were
measured.
On the 17 MB harness, interleaved three times (CPU load 0 to 85 %): b599d4d7e 16,622 / 14,754 /
16,263 ms, 120,178 parses, not complete; this change 17,912 / 16,934 / 16,708 ms, 60,089 parses, 1
read call per file, not complete. `assert-perf.cjs` and `assert-file-reads.cjs` pass on it;
`assert-comparison.cjs` fails (this change up to 2.2 s slower, and not complete). That fixture's
Engine writer records `startedAt` 2026-10-14T07:00:00Z for an owner born 2025-10-14T07:00:00Z, so
under the round-8 rule no key-only `child-exit` proves its child: all 20,029 children are
`exit_unproven` gaps and observer targets (its `lifecycle-perf.cjs` census armed in 15,560 ms
against b599d4d7e's 5,127 ms, and finished in 1,249 ms against 9,925 ms). With only that
`startedAt` set to the owner's birth (`perf/writer-matched/`), interleaved three times: b599d4d7e
14,658 / 15,635 / 17,014 ms, not complete; this change 2,453 / 2,524 / 2,780 ms, complete. Before
the round-8 indexing (case 58) the unmatched fixture took 537,043 ms on this change
(`perf/run1-before-indexing/`). Of the boundary harnesses, `rotation-edges.cjs`,
`rotation-read-retry.cjs` (cold and warm), `rotation-race.cjs`, `writer-and-named-limit.cjs`, the
two permanent `enoent-states.cjs` states, `torn-engine.cjs` and the legacy r2 and r3 torn and
rotation probes pass. `rotation-rename-gap.cjs` and the two transient `enoent-states.cjs` states
fail by assertion for the same reason (their writer records 12:00:00Z for an owner born
14:56:55.399Z, and the child is `exit_unproven`), and pass with only that `startedAt` set to the
owner's birth. The legacy r2 and r3 perf harnesses took 15,748 and 11,794 ms on b599d4d7e and
14,639 and 12,864 ms with this change (`assert-perf.cjs` passes), not complete for the same reason.
The 41 `test-dev-runner-*.mjs` files and `e-pair-identity.test.mjs` pass (208 tests). In the
full run of the check, cases 6 and 14 took 3,454 and 2,579 ms, case 58 13,652 ms, and the
generated cases 40 and 41 took 101,018 and 33,990 ms, with other work loading the machine
(47,299 and 20,270 ms in the run before the indexing).

After the 3e9ebd546 fixes, every verifier harness of rounds 2 to 8 was run again from copies with
fresh folders (verifier-r9/harness-logs), against this change and b599d4d7e. On this change: the
r2, r3 and r4 `independent.cjs` (19 each), r3 and r4 `independent-extra.cjs` (22 each),
`independent-edges.cjs` (21), `proof-probes.cjs` and `proof-extra.cjs` (5 each), `r5-retention.cjs`
(3), `r6-retention.cjs` (6), `audit-edges.cjs` (19), `audit-more.cjs` (23), `sticky-edges.cjs` (3),
`incremental-tail-probe.cjs` (plain and corrupt), round 8's `r8-corrected-identity.cjs` (12) and
`independent-edges.cjs` (3), and `fresh-edges.cjs` (9) pass; `r7-identity-edges.cjs` passes 7 of 8
(`missing-child-exit-native-fields`, as decided in round 8). `fresh-edges.cjs` was 0 of 9 on
3e9ebd546. The first run on this change, before the start-writer reading above, failed
`engine-death-before-later-ALIVE_MATCH` and `-IDENTITY_UNKNOWN` in both audit files (an Engine start
and its exit written by one Engine writer); its logs are kept as `run-harnesses.out`. On b599d4d7e:
independent 5/19, independent-extra 6/22, independent-edges 5/21, proof-probes and proof-extra 3/5,
both retention files none, audit-edges 11/19, audit-more 15/23, sticky-edges 1/3, both tail probes
fail, `r7-identity-edges.cjs` 8/8, `r8-corrected-identity.cjs` 11/12, round 8's
`independent-edges.cjs` 3/3 and `fresh-edges.cjs` 9/9. The corrected `rotation-rename-gap-r8.cjs`
and the four `enoent-states-r8.cjs` states pass on this change and fail by assertion on
b599d4d7e. Of the originals, `rotation-edges.cjs`, `rotation-read-retry.cjs` (cold and warm),
`rotation-race.cjs`, `writer-and-named-limit.cjs`, the two permanent `enoent-states.cjs` states,
`torn-engine.cjs` and the legacy r2 and r3 torn and r3 rotation probes pass on this change;
`rotation-rename-gap.cjs` and the two transient `enoent-states.cjs` states fail for their
misaligned writer, as in round 8. With the corrected generator (`run-perf-r8.ps1`), interleaved
three times, CPU load 12 to 48 %: b599d4d7e 11,409 / 12,114 / 12,155 ms, 118,510 parses, not
complete; this change 2,053 / 2,083 / 2,029 ms, 59,255 parses, complete; `assert-perf.cjs`,
`assert-file-reads.cjs` and `assert-comparison-r8.cjs` pass. Its `lifecycle-perf-r8.cjs` census
took 12,056 ms on b599d4d7e (arming 4,149, verdict 7,905) and 1,705 ms with this change (1,329 and
375), complete. Over the original, misaligned fixtures: b599d4d7e 13,004 / 12,109 / 12,774 ms, this
change 13,298 / 13,196 / 13,392 ms, both not complete (every child looked for, as in round 8); the
legacy r2 and r3 perf harnesses took 12,044 and 11,706 ms on b599d4d7e and 11,340 and 14,795 ms
with this change, not complete for the same reason.
The 41 `test-dev-runner-*.mjs` files and `e-pair-identity.test.mjs` pass (230 tests). In the full
run of the check, with other work loading the machine, the 17 MB stops took 2,637 and 3,142 ms, case
58 17,830 ms, and the generated cases 40, 41 and 71 took 122,037, 37,676 and 219,213 ms (in a run
alone before: 1,748, 2,044, 11,639, 88,624, 30,952 and 182,234 ms).

After the ba109f4ab fixes, every verifier harness of rounds 2 to 9 was run again from copies with
fresh folders against this change (verifier-r10/harness-logs), with the round-9 verifier's
`own-edges.cjs` added: the r2, r3 and r4 `independent.cjs` (19 each), r3 and r4
`independent-extra.cjs` (22 each), `independent-edges.cjs` (21), `proof-probes.cjs` and
`proof-extra.cjs` (5 each), `r5-retention.cjs` (3), `r6-retention.cjs` (6), `sticky-edges.cjs` (3),
both `incremental-tail-probe.cjs` runs, `r8-corrected-identity.cjs` (12), round 8's
`independent-edges.cjs` (3), `fresh-edges-r9.cjs` (9) and `own-edges.cjs` (6)
pass; `r7-identity-edges.cjs` passes 7 of 8 (`missing-child-exit-native-fields`, as decided in round
8). `audit-edges.cjs` passes 18 of 19 and `audit-more.cjs` 22 of 23: both fail
`engine-owner-died-GONE`, as on b599d4d7e, because its `engine-owner-died` GONE is written by a dev
runner writer (the harness process, started at that moment) that did not record the Engine start,
which an Engine writer recorded; with a writer's role no longer evidence, the reconciliation
proves nothing, the stop looks the tracked identity up, cannot read its birth, and is
`STOP_INCOMPLETE`. The corrected boundary harnesses (`rotation-rename-gap-r8.cjs`, the four
`enoent-states-r8.cjs` states) pass and fail by assertion on b599d4d7e; the originals give the same
results as in round 9. With the corrected generator (`run-perf-r8.ps1`), interleaved three times,
CPU load 13 to 62 %: b599d4d7e 15,334 / 12,654 / 14,259 ms, 118,510 parses, not complete; this
change 2,105 / 2,135 / 2,342 ms, 59,255 parses, complete; `assert-perf.cjs`,
`assert-file-reads.cjs` and `assert-comparison-r8.cjs` pass. Its census alone took 14,154 ms on
b599d4d7e and 1,829 ms with this change (arming 1,396, verdict 432), complete. Over the original,
misaligned fixtures, CPU load 31 to 68 %: b599d4d7e 15,332 / 14,554 / 16,165 ms, this change
18,266 / 18,030 / 18,294 ms, both not complete (every child looked for); the legacy r2 and r3
perf harnesses took 15,950 and 14,414 ms on b599d4d7e and 17,388 and 16,811 ms with this change.
The 41 `test-dev-runner-*.mjs` files and `e-pair-identity.test.mjs` pass (241 tests), as does
`fixture-cleanup.test.mjs` (10). In the full run of the check, the 17 MB stops took 2,813 and
2,970 ms, case 58 17,419 ms, case 82 16,645 ms, and the generated cases 40, 41 and 71 took
116,089, 47,106 and 262,583 ms.

After the 5b4cea41b fixes, the round-10 verifier's reproductions (copied and repointed to this
round's folder, verifier-r11/vx) pass on this change: `r10-edges.cjs` (3 of 3:
`unbound-full-native-engine-exit`, `unbound-owner-died-gone-real-lookup`,
`real-second-cli-and-later-stop`), `r10-corrected.cjs` `unbound-owner-died-gone-real-lookup`, and
`live-recovery.cjs` (the Engine alive). Its CLI read check (verifier-r11/cli-read: two real CLI
stops under a preload that records every open, read, whole-file read and Engine record parse) finds
no whole-ledger read and each Engine record parsed once in both CLI processes here; on 5b4cea41b it
fails by assertion ("reads no whole ledger file"). Every verifier harness of rounds 2 to 9 was run
again from copies with fresh folders (verifier-r11/harness-logs) with the same results as in round
10: r2, r3 and r4 `independent.cjs` (19 each), r3 and r4 `independent-extra.cjs` (22 each),
`independent-edges.cjs` (21), `proof-probes.cjs` and `proof-extra.cjs` (5 each), `r5-retention.cjs`
(3), `r6-retention.cjs` (6), `sticky-edges.cjs` (3), both `incremental-tail-probe.cjs` runs,
`r8-corrected-identity.cjs` (12), round 8's `independent-edges.cjs` (3), `fresh-edges-r9.cjs` (9) and
`own-edges.cjs` (6) pass; `r7-identity-edges.cjs` passes 7 of 8 (`missing-child-exit-native-fields`,
as decided in round 8); `audit-edges.cjs` 18 of 19 and `audit-more.cjs` 22 of 23, both failing
`engine-owner-died-GONE` as before (the unbound reconciliation proves nothing, and the stop looks the
tracked identity up and cannot read its birth: S4). The boundary harnesses give the same exit codes
as in round 10, the corrected ones passing here and failing by assertion on b599d4d7e. With the
corrected generator (`run-perf-r8.ps1`), interleaved three times, run alone, CPU load 32 to 54 %:
b599d4d7e 13,046 / 12,932 / 13,287 ms, 118,510 parses, not complete; this change 2,260 / 2,264 /
2,513 ms, 59,255 parses, complete; `assert-perf.cjs`, `assert-file-reads.cjs` and
`assert-comparison-r8.cjs` pass. Its census alone took 14,563 ms on b599d4d7e (arming 4,961, verdict
9,601) and 1,751 ms with this change (1,335 and 416), complete. Over the original, misaligned
fixtures: b599d4d7e 14,172 / 13,725 / 14,951 ms, this change 15,781 / 14,490 / 15,988 ms, both not
complete (every child looked for); the legacy r2 and r3 perf harnesses took 14,497 and 13,902 ms on
b599d4d7e and 17,434 and 15,975 ms with this change, not complete. The 41 `test-dev-runner-*.mjs`
files and `e-pair-identity.test.mjs` pass (249 tests; `test-dev-runner-terminal-children.mjs` after
its fixture change above, run again on its own), as does `fixture-cleanup.test.mjs` (10). In the
full run of the check (90 of 90), the 17 MB stops took 2,462 and 2,712 ms, case 58 13,632 ms, cases
82 and 89 16,035 and 16,142 ms, and the generated cases 40, 41 and 71 took 98,208, 30,467 and
212,488 ms.

Execution logs: F:/agent-sandbox/design-b6d536/b102-stop-census/ (this round's in verifier-r11/).
