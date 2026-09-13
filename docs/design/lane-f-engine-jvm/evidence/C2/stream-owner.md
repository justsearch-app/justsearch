# C2-3 run stream and approval lifetime

2026-09-13, based on4cc619fb4. A read-only review found a pending frame could arrive
after done/error and start a new approval lookup. Further inspection found pending
frames carry no run id and an old buffered stream could therefore be attributed to
a replacement run; old catch/finally paths also changed the replacement's state.

Use the existing AbortController object as the stream owner. Allfive stream entry
points bind callbacks, catch and cleanup to that owner. Replacement aborts the old
observer, invalidates its approval lookup, clears unidentified AUTO calls and waits
for the new run's identity. A same-run reattach preserves its existing gate/lookup.
Live pending frames require isStreaming; replay still renders them without action.
Cancellation finishes the local lifecycle before awaiting DELETE, so its late
response cannot end a new run. Destruction detaches the stream and drains approvals.
This avoids a new run-id map or a second stream generation registry; the existing
handle and live state already own the required lifetime.

Negative1044 catches allseven original cases (done/error plus five replacement
entry paths); two companion cases pass. Initial1045 passes the new ownership tests
but32 old direct-handler fixtures lack live state. They now explicitly establish
the live state that their simulated stream handlers require, preserving their
assertions. The first fixture-edit script refuses an incorrect expected count;
1046 therefore repeats the unchanged failures. Corrected1047 passes153 tests.

The expanded suite adds old transport failures, same-run reattach continuity,
queued AUTO replacement, delayed DELETE and destruction. Initial1048 has one test
fixture error: its DELETE resolver is assigned only after asynchronous token setup,
so resolving it immediately fails and teardown times out. Constructing the deferred
response before the fetch mock fixes the fixture. Final1049 passes all162 focused
cases. Full1050 passes6508 tests in484 files, zero failures/skips, plus typecheck and
lint. Its JSON report is retained. Negative1051 removes only the cleanup owner guard;
allfive selected replacement cases fail at the intended post-cleanup live-state
assertion (13 unselected). Restore exact source bytes; final1052 passes162 cases.
The restored source is byte-identical to1050, so its full-suite proof remains valid.

The independent follow-up read of the root-owned diff finds no further concrete
issues. This used the existing worker under the native four-task cap, not a newly
configured Sol reviewer. Its review is source inspection, not another test run.

Canonical documentation and allfive checks pass1053 after regeneration; the UI
coverage gate passes54 paths/four core surfaces. Worktree-bound jseval reports no
mapped affected step for this controller file; this is not browser proof. Java
integration inputs remain unchanged from1043. Fresh integrated Java/build resources,
live/model and hosted C2-3 proof remain required, after the nested workflow fix.

[Verification](stream-owner-verification.json) records source/artifact hashes and
full-suite counts. Commands run from modules/ui-web: focused tests name
src/shell-v0/controllers/AgentSessionController.test.ts and
src/shell-v0/controllers/AgentSessionStreamOwnership.test.ts;1051 selects
-t 'ignores frames and cleanup'. Raw files are retained under
F:/justsearch-public/.claude/worktrees/lane-F-A/tmp, prefixes1044 through1053 listed
in the verification record. Keep through stage acceptance plus30 days and export
before worktree release. C2 and later lane stages remain open.
