---
evolution-rule: additive-optional
---
`OperatingIndexingView` gains `reading_rule_reasons = 11` (the reading rules holding file reading
now: `schedule`, `on_battery`), `held_files_waiting = 12` (ready file jobs are held from reading)
and `release = 13`, the new message `IndexingReleaseView { bool active = 1; }`: whether a "read
waiting files now" release lets file reading through those rules. The release lives in Engine
memory only; no stored state. Enum-valued tokens travel as strings without `buf.validate in:`.
No existing field is removed, renamed or retyped, and no field number is reused. Protobuf readers
ignore the additive fields; older strict JSON readers may reject them, and production's existing
status-drift handling applies.
