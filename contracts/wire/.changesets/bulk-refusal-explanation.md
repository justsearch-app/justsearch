---
category: wire
evolution-rule: additive-optional
---

OperationHistoryEntry gains optional string field 12 `message`, matching the existing Java
read-time explanation field. The explanation includes its support code. Existing fields,
field numbers, enum values, stored receipts and state remain unchanged.

Wire 3.0.2 is a patch: old producers can omit the field and old consumers can ignore it.
