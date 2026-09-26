# Closed-enumeration refusal correction

2020 installed evidence and local regression2023 prove a permanent recovery refusal
can strand an already-COMPLETE enumeration with unsettled members.2022 separately
repairs the successful-replay harness precondition (persisted corpus scope) without
changing its search/retry assertions. The refusal gap remains a product defect.

Selected ownership after bounded independent design comparison: persist the exact
permanent refusal in the existing parent checkpoint cursor, keeping that parent
open through child settlement/receipt acknowledgement. Terminal-parent-first was
rejected: it would lose the runner's open-parent acknowledgement inventory and
require reverse retention and new terminal cleanup paths. Queue-first retirement
was rejected: a crash plus later authority change could turn skipped work into
parent SUCCESS. A new queue schema/settlement-cause field is unnecessary.

The parent cursor gains ingest-refusal:1:<allowlisted-code>, a durable decision
witness rather than permission or receipt acknowledgement. This supersedes only
the progress-only parent grammar in C2-8d-vertical-plan.md. Validate preparation,
plan and binding first; then honor a recorded refusal before current authority.
Unknown marker versions/codes fail closed. Permanent binding/scope/authority/
generation refusal and attempt exhaustion qualify; transient Wait and cancellation
do not. Later count checkpoints must preserve the marker. Parent remains the sole
terminal refusal owner; a COMPLETE child with policy-skipped coverage does not
mean its parent succeeded.

Malformed decisions instead produce UNAVAILABLE and retain unresolved queue evidence;
they cannot authorize retirement or acknowledgement. A real child corruption test
pins this distinction. Valid refusal receipt bookkeeping at the child attempt limit
uses CheckpointAndWait on the existing RUNNING attempt and immediately schedules the
next coordinator pass. It spends no fourth attempt and needs no producer binding.

Boot owners use runner-owned CheckpointAndWait on RUNNING rows: existing checkpoint
persistence, no new attempt/admission/body/terminal event, leave reconciliation
eligible. Live owners use their existing handle. Serialize coordinator decisions
with its existing ownership lock. Revoke exact child permissions and request
producer cancellation before the marker; no irreversible queue retirement before
that write. Queue retirement is a narrow existing-owner operation on an exact
closed COMPLETE unsealed walk/hash, under the queue lock/transaction, with no
issued claim: skip only pending/orphaned-processing members via existing ledger
coverage, preserving completed/indexed evidence and enumeration outcome. Existing
seal/terminal/ack machinery follows. Never broaden DENY into permanent refusal.

Required checks:2023 becomes green; queue exact-coverage/idempotency/active-claim
barrier; runner no-attempt/continued-eligibility/storage-failure behavior; crash
cuts before marker, after marker, after retirement and after child terminal before
ACK; changed authority cannot reverse the witness; malformed cursor refusal; live
count preservation; rooted installed baseline, focused suites and full stress at
the coherent recovery boundary. The six installed fault cases remain required.

## Executed evidence

At base81459ecd9 plus this correction,2025 passes72 cases across coordinator,
runner and queue tests.2026 passes46 coordinator cases including live issued-claim
drain/count preservation and malformed markers, with PMD/format.2027 passes the
installed rooted processing/retry baseline after a verified Engine crash and successor
recovery. It predates the isolated attempt-limit review correction and is not proof
of the six remaining fault cases or of installed refusal recovery.

Independent review found a child-attempt-limit failure and a missing immediate
reconciliation pass. Both are corrected; the regression requires parent failure
and exact receipt acknowledgement at attach, before binding a producer or calling
maintenance.2028 passed47 coordinator cases before that stronger assertion.2029
passed57/59 but exposed a fixture reusing the old process admission controller in
the two malformed-marker cases; successor admission is now separate. No production
validation was weakened. Final2030 represents88 cases/6 suites, zero failures/errors/
skips:59 engine cases execute;29 unchanged runner/queue cases reuse2025 results.
Engine PMD/format pass; unchanged observability/queue PMD passed2026.

Commands, revision descriptions, raw logs, copied XML and counts are retained at
tmp/2023*, tmp/2025* through tmp/2030* in this worktree through lane acceptance plus
30 days; export before worktree release.2023 is the original red coordinator
regression (RUNNING instead of FAILED). Canonical storage/cursor docs are updated.
Next: six installed fault cases plus real client disconnect; full stress at that
coherent recovery boundary. C2 and D1/D2/E/F remain open.
