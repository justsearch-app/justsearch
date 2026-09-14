---
evolution-rule: enum-value-added
---
Lane F C2-4/R6 adds OPERATION_OUTCOME_UNDONE = 3 to the retained
operation-history proto. The Java producer already emits UNDONE after successful
undo; this corrects the descriptor omission without adding a new runtime state.
Existing enum numbers and all field numbers are unchanged. Wire VERSION advances
from2.0.0 to2.1.0, the required minor evolution for an additive enum value.

OperationHistoryWireContractConformanceTest now checks every Java outcome against
the generated proto descriptor. Its negative control fails on the missing UNDONE
value while the existing record-field conformance assertion continues to pass.
