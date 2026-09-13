# R4: acceptance is independent of audit projection

September13, 0693495bd plus this item. Every dispatched declaration has a record kind
and accepts an attempt. AuditPolicy.NONE still suppresses history, while advisory
failure publication remains intact. The duplicate unrecorded execution/completion
branch is retired; the shared durable completion observer owns publication. The R1
description of two observers is historical after this correction.

OperationExecutorImplTest.mediumRiskUnauditedMutationStillAcceptsBeforeEffect checks
the RUNNING row inside the effect and COMPLETE afterward, with no history entry.
admissionRefusalRetainsItsReasonInDurableAttempt covers all four admission reasons,
asserts no effect, the original exception, one FAILED row and matching history code.
Negative798 executes11 cases and fails the five new cases for the intended reasons.
Final800 passes88 cases in5 suites with zero failures/errors/skips. PMD and UI
integration-test compilation pass. The final rerun removes the new unused-variable
compiler warning; it makes no behavioral change from passing799.

```text
gradlew.bat :modules:app-services:test --tests *OperationExecutorImplTest
  :modules:ui:compileIntegrationTestJava :modules:app-services:pmdMain
  :modules:app-services:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
```

Raw evidence is tmp/c2-review-r4-negative-798.txt and tmp/c2-review-r4-800.txt,
with matching -counts.json and -xml/ siblings. Retain through lane acceptance plus
30 days and export before releasing this worktree. The committed
[verification summary](review-r4-verification.json) identifies the tested revision.
Full batch verification and independent review remain pending.
