# C1-11 standalone admission composition — 2026-09-09

The standalone launcher still selected a Head constructor that omitted admission. That constructor
passed null into inference assembly, so even a non-query standalone boot failed. Head now requires
an explicit EngineAdmissionService before opening resources. The standalone composition root loads
exactly one service provider, validates that the same object owns OperationLeaseService, and passes
it through both neutral contracts. EngineRoot continues to inject its existing shared owner.
The no-argument provider uses the canonical EngineResourcePolicy loader; it is not a fallback or
another policy representation. Missing or ambiguous providers fail before Head construction.

Tests use explicit admission fixtures. LauncherExecutorProviderTest discovers the actual runtime
provider, admits and closes work, verifies operation-lease identity, and rejects missing/ambiguous
providers. Local Windows/JDK 25 run26 passed 51 launcher tests and 93 selected app-services tests
(including always-on guard tests), above WIP checkpoint 2c0c686e4's parent corrections. Raw evidence:
`tmp/c1-batch4-composition-tests-26.txt`; XML `tmp/c1-batch4-composition-green-26/`.
The integrated tree, not the isolated item commit, was tested. Required final stage proofs remain.


Integrated stress147 caught restoreProperties bypassing the configuration funnel. The lifecycle
refactor had moved direct System setters/clearers out of close into a new helper. Root now uses
existing SystemAccess.setSysProp for allthree restored boot properties, including null-to-clear,
and retires only LauncherEnvironment#close from the shrinking system-access allowlist. The
constructor's inherited allowlist entry remains unchanged. No new exception or helper is added.
Run162 passes the full launcher tests (including LauncherEnvironmentCloseTest/ResolveTest),
SystemAccessFunnelTest and PMD; its only failure is the unrelated API thin-composer ceiling,
being corrected in C1-7. Logs tmp/c1-batch4-full-stress-147.txt and
c1-batch4-integrated-restored-162.txt; XML preserved in matching full-stress-results147 and
integrated-results162 directories. Tested basec57525718 plus this correction; C1 remains open.
