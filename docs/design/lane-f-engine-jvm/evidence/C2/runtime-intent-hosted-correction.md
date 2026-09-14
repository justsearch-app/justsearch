# Runtime-intent hosted correction — 2026-09-14

Hosted CI [34790375481](https://github.com/justsearch-app/justsearch/actions/runs/34790375481)
at36cd600b4 failed Build and Public claims. CLA succeeded. This is a product
regression, not runner starvation. The runtime-intent implementation remains
locally verified with separate hosted proof owed after these corrections.

The operation-surface scanner correctly detected RuntimeSpecStore's new use of
OperationAttemptRunner, a registered sibling lifecycle type. Declare it as a
consumer of the existing runner, guarded by RuntimeIntentProducerTest. Do not
change discovery patterns or population floors. Its diagnostic now describes
operation/action types rather than incorrectly naming only IndexingJobView.
The second defect was a redundant java.util qualifier in InferenceHandlers where
List was already imported; remove the qualifier without changing error behavior.

Independent read-only review identified both causes and verified the registered
consumer lineage. Root reproduced both failures locally before correcting them.

Verification on36cd600b4 plus the dirty activation batch, Windows/Java25:

- `node --test scripts/governance/gates/operation-surface/enforcer.test.mjs`:
  six positive/negative fixture checks pass.
- `node scripts/governance/run.mjs --gate operation-surface --gate execution-surface --gate register-guard-resolution --mode gate`:
  all three pass, zero findings; the same invocation previously failed on the
  missing runtime-intent declaration.
- `:modules:ui:pmdMain` and UI format/integration compilation pass in1411;
  PMD reproduced the exact hosted failure in1410 before the qualifier fix.
  Focused activation1411 represents106 tests with no failures/skips, reused from
  the executed1410/1409 tasks as recorded in the count manifest. This is not a
  full-suite or hosted-success claim.

Raw evidence lives under
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/`:
hosted1407-failed.txt, hosted1407-jobs.json, workflow-signal1407.md,
operation-surface1407-red.sarif, operation-surface1411-green.sarif,
operation-surface1411-tests.txt, operation-surface1411-gates.txt,
c2-activation1410.txt, c2-activation1411.txt and their captured XML/count files.
Retain through lane acceptance plus30days; export before releasing the worktree.
C2 remains open; the activation batch is still under independent review.
