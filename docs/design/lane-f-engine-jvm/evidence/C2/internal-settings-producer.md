# C2-6 internal settings producer — 2026-09-14

SettingsService now accepts a caller's frozen whole-document candidate and the full
witness captured with its base. It reuses OperationAttemptRunner and the fixed
settings owner; it cannot replace files or write terminal rows itself. The row's
public identity contains a canonical digest rather than private settings content.
SYSTEM_INTERNAL transport and catalog-derived trust are required; an INTERNAL
client label alone grants no authority. The candidate is privately copied before
both digest and acceptance. Typed refusal retains row identity; persistence
uncertainty propagates with the row unresolved.

Independent source review found and corrected the original client-label authority
and mutable-candidate alias defects. Correction review is clear. Executed focused
tests in1413 passed, including acceptance failure, stale full witness, committed
receipt, agent/workflow rejection and mutation during acceptance. The deliberate
1414 mutations restored both defects: agent/workflow rejection failed and the
committed theme became the mutated caller value. The canonical identity still
matched the original digest, establishing the mismatched-effect failure directly.
Both files altered by that experiment were restored byte-for-byte using the
recorded SHA256 checks. No suppression or test weakening was used.

Raw evidence under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/`:
c2-activation1413.txt and captured XML/counts; c2-activation1414-mutant.txt and
captured XML/counts; activation1413-mutant-restore-hashes.json. The earlier1410
identity assertion failure compared JSON member order: the digest was identical;
the corrected assertion uses the existing canonical descriptor comparison and
retains the committed-value check. Retain evidence through lane acceptance plus
30days and export before worktree release.

This prerequisite does not close C2-6. Activation, deactivation and compensation
consume it in the immediately following coordinated change, then remaining
installer/import/public settings writers and installed proof must be completed.
