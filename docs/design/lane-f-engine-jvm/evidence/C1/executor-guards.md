# C1-5/C1-16 executable executor ownership and census guards

2026-09-09 Windows / Temurin25.0.2, combined candidate over d0d09bc72; C1 remains open.
ExecutorArchitectureTest lives in app-launcher so the production bytecode of both Engine halves
is on its classpath. It excludes test fixtures explicitly as well as ordinary tests. The source
scan covers all module production Java before a new module reaches that runtime classpath.

Only DefaultEngineExecutorRegistry's implementation and ExtractionSandboxChild.openOcrPool may
construct raw executors. The child exception is method-specific, not a whole-file allowance.
Calls, static imports, method references and constructor references are covered. A separate source
check requires the five current Engine HttpClient construction chains to inject an executor; the
named ExternalLlamaServerClient system-test harness remains outside the Engine-process rule.
The source guard is a projection, not a second runtime authority or a complete Java parser.

The census counts explicit spec construction sites, expands the existing shared bundle helpers,
and enforces the design floors of58 explicit and63 logical sites. Actual restored count is64
explicit plus20 helper calls minus5 helper definitions =79 logical factory sites. Alternative
constructor sites count independently; these are not79 simultaneously registered process names.
The registry's duplicate-name refusal is covered by its existing behavioral tests.

Initial app-services placement exposed test-fixture factories (run93); root moved the rule to the
full launcher classpath and excluded fixtures. Run99 failed to compile because ArchUnit1.4.1 has
getRawParameterTypes, not getParameters; root verified the installed library API. Runs100/103 found
three redundant qualifiers after imports changed; run104 fixed them and PMD passed. Review then
found a method-reference bypass; root changed bytecode traversal to getCodeUnitAccessesFromSelf
and added source-reference matching. No validation or baseline was weakened.

- Mutation108: statically imported bare Executors.newSingleThreadExecutor outside its owner fails
  both source and bytecode guards.
- Mutation109: a raw pool in another ExtractionSandboxChild method fails both guards.
- Mutations121/122: Executors::newFixedThreadPool and ForkJoinPool::new both fail both guards.
- Mutation125: zeroing the logical census fails its non-vacuous63 floor (64 explicit,20 helpers).
- Mutation126: removing one actual OpenAI HttpClient.executor call fails the hidden-pool guard.
- Mutation127: adding a telemetry return type from core.context fails the execution-only layering
  rule, while the legitimate core.execution imports pass on the restored candidate.
- Mutation132: a new unused launcher class fails WholeProgramDeadCodeTest by its exact class name;
  restoring source passes, and the committed ArchUnit store does not grow or change.

Restored run128 passed launcher guards, whole-program dead-code and affected PMD. Run133 passed
whole-program dead-code after its mutation. All mutation files are restored in finally. Logs:
tmp/c1-batch4-executor-guard-mutant-{108,109,121,122,125,126,127}.txt,
c1-batch4-dead-code-mutant-132.txt, c1-batch4-restored-{128,133}.txt. Preserved XML is in matching
mutant directories and tmp/c1-batch4-green-{128,133}. Retain through lane completion plus30 days.
Full/stress/live/hosted proof remains separate. Rule9 async proof is recorded in async-guard.md.

Restored run135 passed launcher guards and PMD; XML retained in tmp/c1-batch4-guards-green-135.
