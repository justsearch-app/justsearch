# C2-3 agent continuation cleanup

2026-09-13, based on73d865c12. Full1038 executes the integrated build and
represents10292 cases, with one failure, zero errors and35 inherited skips.
UnreferencedCodeTest identifies three obsolete non-public overloads in AgentSession
and AgentToolDispatcher. The failure is retained, not accepted or suppressed.

Remove those overloads. The announcement-failure regression now enters the actual
prepareAndApprove path; the retry-policy fixture explicitly supplies the absent
plan used by its unwired dispatcher. Production already uses the retained-plan
method. No architecture rule or assertion is weakened.

Final1039 executes both full agent and launcher test tasks:772 cases in74 suites,
zero failures/errors/skips. Agent PMD passes and UI integration compilation reuses
unchanged successful inputs. Full1038 is the negative architecture witness. A new
successful integrated build is still required after the remaining review fixes.
The latest successful full-run summary therefore remains1009, not1038.

The read-only reviews at73d865c12 also found background workflow metadata still
hardcoded false and late pending frames able to open an approval after conclusion.
Both are the next required per-item corrections; neither is waived. The frontend
investigation additionally identifies identity-free buffered frames from a replaced
stream, which the root-owned lifecycle correction must cover. Native task cap4
prevented a new reviewer-role task; existing workers supplied bounded read-only
checks, without claiming a configured Sol reviewer run.

[Verification](agent-cleanup-verification.json) includes the exact commands, both
run summaries, execution/reuse status and source hashes. Raw logs/counts/XML are
under F:/justsearch-public/.claude/worktrees/lane-F-A/tmp with prefixes
c2-3-full1038 and c2-3-agent-cleanup1039. Keep through stage acceptance plus30 days
and export before worktree release. C2-3 and the later lane stages remain open.
