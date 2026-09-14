# C2-9b.3b.3 stable ingestion coordinator — 2026-09-14

Parent-owned cut3 on23f9221be. This record is work in progress, not C2 completion.
The owning mechanism is [the vertical plan](C2-8d-vertical-plan.md#stable-coordinator-implementation-refinements-2026-09-14).

## Implemented scope

EngineRoot owns one coordinator across physical index attachments and gives its application
port to Head's existing30-second operations maintenance. Its indexer-local lifecycle uses
the existing queue notification and final-close hooks. No scheduler, persistent authority,
terminal writer or independent child admission is introduced. The private runner owns
parent/child writes; the coordinator retains the parent work until durable terminal storage.

The coordinator serializes captured-cohort recovery and sequential frozen-root advancement.
Cancellation immediately fences pending claims; actual producers and issued index owners
must drain. Replacement discards fresh consent and rereads exact accepted parent/child
bindings. Failed/cancelled terminal-child catch-up repairs parent checkpoints without
admission; decreasing confirmed counts fail unavailable unchanged. Invalid binding fences
all identifiable siblings and never acknowledges unprovable evidence. Receipt-only closure
can finish an already-running third attempt without spending a fourth.

Physical close waits actual producer exits, then the index loop's issued owners. It consumes
already-completed enumeration outcomes and flushes exact child receipt/acknowledgement and
parent metadata. A producer cancelled solely for physical replacement remains replayable;
its pending runner stage and parent work survive. Failed close retains the attachment.

## Evidence and corrections

1725 focused26 cases passed after an extra EOF newline and an unused try-resource assertion
were corrected.1726 seven and1727 thirteen coordinator cases passed. Independent review
identified five defects in the draft: delayed cancellation fencing, contradictory child
lookup escaping the pump, fixed-pass lost wakeups, cached replacement authority, and failed/
cancelled parent outcomes bypassing checkpoint repair. Root corrected each with real SQLite
fixtures.1728 added a later-sibling regression that failed: replay could start root2 before
root1's corrupt terminal binding was handled. Exact next-root eligibility corrects it.

1729 ran29 cases with one failed test fixture: the final-close fixture returned SKIPPED_POLICY
but expected one indexed unit. The queue contract counts indexed content, so the fixture now
returns an issued indexed transition with a committed hash; the count assertion is preserved.
1730 ran28 Engine cases successfully and reused22 unchanged API/maintenance cases:50 cases,
nine suites, zero skips/failures/errors. PMD and format pass. These are focused checks only.

1731 temporarily bypassed five safeguards in one selected-method run: immediate cancellation,
sequential child eligibility, replacement binding reread, finite transition-driven pumping,
and final parent flush. All five regressions failed at the intended assertions (respectively
an improperly claimable member, missing sibling/parent terminals, incorrect COMPLETE after
corruption, only4 of9 roots, and timeout). Root reread the preserved XML and restored source
byte-for-byte.1732 operation/execution/guard-resolution gates pass with the coordinator
registered as a consumer of the existing operation record.

Second independent review found that a producer completing during stop could leave its
enumeration outcome open.1733 reproduced it: synchronous completion passed, completion during
stop timed out. The correction consumes an already-done exit at final close without starting
or retrying enumeration; replacement-only cancellation stays pending.1734 executes all29
Engine coordinator/settlement cases successfully, with PMD and format passing. Integrated1735
completed the full Engine/API/operations/services suites:4,031 cases, three existing services
skips, zero failures/errors. Its app-launcher guard adds38 cases with one failure (4,069 total
cases/637 suites). The unused-method findings were bindProducer and the superseded two-argument
maintenance overload. The run stopped before the whole-program audit; it is not a full green run.

Exact commands and tested source descriptions are in numeric -counts.json files, suite XML
in matching -xml directories, and Gradle output in numeric .txt files, under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/.1731's original source bytes are
1731-coordinator-original.bin. Retain through final lane reconciliation plus30 days, at least
2026-10-14. Earlier failing evidence remains preserved; later focused runs do not erase it.

## Remaining acceptance

Final stage review, integrated proof of the latest corrective source and hosted dead-code
correction remain required. Actual bounded EngineKnowledgeClient
producer binding and IngestTool/ReindexHandler prepared invocation wiring are next d.3b;
current production lifecycle wiring alone does not expose a recorded effect producer.
Live API/model, installed and final-head proof remain in the stage checklist. C2 stays open,
draft PR727 stays unmerged, and stage F remains the merge point.

The prior23f9221be hosted CI34870994136 failed app-ui/platform-contracts dead-code checks
for the unconnected settlement class/methods;11 other jobs and CLA34870989825 passed.
fa66bb1cb CI34876582200 passes12 of13 jobs, including platform-contracts; only app-ui
fails. Exact downloaded XML1742-hosted-xml identifies bindProducer and the obsolete maintenance
overload, each reported on three attempts. Logs/metadata1742-hosted-* are retained.


## Post-checkpoint review follow-up

fa66bb1cb is committed and pushed (2026-09-14,19:44 local); hosted CI34876582200 has12 passing jobs and the app-ui unused-method failure,
CLA34876577956 passes. The draft PR727 managed record was updated and exactly read back.
Second independent review accepted the earlier corrections but found two remaining defects:
never-started cancellation was misclassified as unavailable, and cancellation could precede
producer-token publication. Root accepted both, owns the diff and drafted regressions in
1739-cancellation-tests.txt while integrated1735 tests the immutable checkpoint. No further
worker correction cycle is required. [Selected correction](C2-8d-vertical-plan.md#cancellation-before-producer-publication-2026-09-14).


Root reproduced all three startup defects in1743: never-started cancellation returned FAILED,
a latch cancellation before token publication still invoked the producer, and synchronous
bounded rejection escaped without child settlement. The correction passes1744:45 cases/seven
suites, zero skips/failures/errors, all four PMD tasks and format.1745 adds an established-
progress-loss test and passes33 Engine cases; the private never-started guard bypass1746 makes
that test return CANCELLED instead of required FAILED. Root reread the assertion and restored
source byte-for-byte from1746-coordinator-original.bin.1747 verifies restored source plus the
whole-program audit:47 cases/eight suites, zero skips/failures/errors. The single
whole-program case executed;33 unchanged Engine cases were restored from cache and13
maintenance cases were up-to-date. PMD and format pass. This is represented coverage,
not47 new test executions.

The refused empty receipt applies only to the existing same-attempt createIfMissing fact;
parent binding refusal cannot manufacture a receipt after producer activity or restart.
A published token plus cancellation recheck prevents the missed-token window. Synchronous
producer rejection becomes an actual-exited failed future and follows ordinary child seal,
runner terminal and acknowledgement ordering. The producer contract requires a throw to
precede ownership of any active task; the real bounded adapter must return an exit stage
once it owns a task. Completion notification runtime failures are logged for existing
maintenance retry, and their dependent future is retained through producer stop. This removes
the prior ignored-future advisory without suppression. The obsolete maintenance overload is
removed; all fixtures now call the same three-argument production helper.
