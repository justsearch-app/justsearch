---
evolution-rule: additive-optional
---
ADR-0050 adds `OperatingStateView operating = 36 [json_name = "operating"]` to
`StatusResponse`, with the new messages `OperatingStateView`, `OperatingChatView`,
`ChatLoadView`, `OperatingIndexingView`, `OperatingGpuView`, `OperatingDegradationView`
and `OperatingFreshnessView`. The Java records live in
`modules/app-api/.../status/operating/`; enum-valued fields travel as strings carrying the
Java enum's wire value, without `buf.validate in:`, so a new value stays additive.

The first producer only projects today's runtime state (chat from the generative component
and the runtime ENGINE axis, indexing from the queue and the energy-saver poll, GPU memory from the one GpuMemoryReader,
diagnostic only: adapter total and free, our processes' dedicated and shared use, and
`externalUsedBytes`, the adapter use minus ours).
No existing field is removed, renamed or retyped, and no field number is reused.
