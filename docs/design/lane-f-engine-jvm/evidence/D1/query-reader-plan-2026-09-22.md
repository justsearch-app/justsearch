# D1 query feature readers, 2026-09-22

This is the bounded next implementation plan, not completion evidence. Based on
read-only review at d87a0e60c plus the control-reader changes. Both flags default
false; the user-visible enabled/disabled semantics remain unchanged.

## Authority and operation boundary

Add queryUnderstandingEnabled and filterNormalizationEnabled to ResolvedConfig.Search,
using the existing justsearch.qu.enabled and justsearch.filter_norm.enabled keys.
Services receive BooleanSupplier dependencies. Compatibility constructors retain
their existing raw reads; production suppliers read the captured ConfigStore.

QueryUnderstandingService owns one availability sample for each extraction. KSE
calls a combined extractIfAvailable path and removes its preceding availability
read. A disabled operation yields no future/AI work, preserving current behavior.

FilterNormalizationService owns one availability sample per normalization. A
private body receives that captured decision. Public normalize still performs
deterministic normalization when LLM use is disabled; normalizeIfAvailable preserves
KSE's whole-operation feature gate. RetrieveContextController keeps its deterministic
path. Do not remove the KSE outer gate without replacing its semantics.

## Production composition

HeadAssembly captures one store beside its resolved snapshot, passes it through
ServicePhase.Input and AgentToolFactory to KnowledgeHttpApiAdapter/KSE. The HTTP
path borrows the same Headless store through LocalApiServer.Builder, then passes
it to both eager/late search and retrieve controller composition. Neither service
samples ConfigStore.global inside operations. Existing constructors remain usable.

Owning files: ResolvedConfig/ResolvedConfigBuilder, QueryUnderstandingService,
FilterNormalizationService, KnowledgeSearchEngine, KnowledgeHttpApiAdapter,
AgentToolFactory, ServicePhase, HeadAssembly, LocalApiServer, CoreApiAssembly,
KnowledgeSearchController, RetrieveContextController and HeadlessApp. Inspect exact
constructor reach before editing; return new ownership ambiguity to the root.

## Required proof

- Default/explicit typed flags and the hand-built Search fixture retain parity.
- Alternating true/false suppliers: two eligible operations, exactly two reads,
  one AI call; no within-operation second sample.
- KSE disabled filter path stays gated; retrieve deterministic output still works.
- Actual agent/adapter and retrieve composition use store A after global replacement
  by contradictory B, then observe an update to A without reconstruction.
- HTTP composition passes the same borrowed authority to both owners. Supplier
  reflection alone is insufficient; exercise actual requests.
- Focused module tests and static checks, then integrated/live proof at the coherent
  boundary. These readers do not complete apply-register or dispatch acceptance.

## Implementation and focused2395 evidence

Typed booleans and per-operation service sampling are implemented. HeadAssembly
retains one ConfigStore for its resolved startup snapshot and both eager ServicePhase
and late AgentToolHandlers composition. The late path was included because an eager
build without an index client returns no adapter. KSE uses the combined service
methods; compatibility constructors preserve previous raw-read behavior.

2395 command:
`./gradlew.bat :modules:configuration:test :modules:app-services:test --tests '*QueryUnderstandingServiceTest' --tests '*FilterNormalizationServiceTest' --tests '*AgentToolFactoryCompositionTest' --tests '*HeadAssemblyTest' --tests '*CompositionRootGuardrailsTest' :modules:worker-services:test --tests '*SearchPlannerApprovalCorpusTest' --continue --console=plain`

PASS34s:377 cases, zero failures/errors, three skips across49 suites/three freshly
executed tasks. Skips are the existing deferred composition checks4d/4e/4f. Captured
tmp/2395-query-readers-focused logs/XML/counts and source inventory identify the
c948cf85f + WIP boundary. UI files were not compiled or tested in this generation.

Actual eager and late factory-produced adapters use enabled store A while global B
is disabled; both query-understanding and filter-normalization response projections
appear, with exactly two AI completions. Updating A to disabled suppresses both on
subsequent requests through the same adapter, with no additional AI completion and
four total Worker searches. Service tests independently prove alternating-supplier
sampling and deterministic-vs-gated filter behavior. HTTP/controller proof, static
checks, independent review and live model checks remain pending.

2397 UI/static boundary executes53 cases:13 HTTP/controller/composer cases pass,
two system-access/dead-code-audit cases pass; app-launcher reports one failure in38
cases because the package-private legacy assemble overload is now test-only.
PMD separately reports11 redundant type qualifiers caused by the new import in
AgentToolFactoryCompositionTest. Root removes the orphan internal overload, updates
its four test callers to pass the existing nullable store argument explicitly, and
uses the imported type at the old mock sites. Public compatibility entry points stay.
No rule or assertion is weakened. All2397 logs/XML/counts are captured.

2398 corrected boundary passes52 freshly executed cases/21 suites in57s, all static
checks green. The13 HTTP cases prove actual Worker boost filters from valid QU
output, live store disable without rebuilding controllers, unresolved filter LLM
mapping, and retained deterministic matching with LLM disabled. Composer source
checks normalize CRLF and supplement actual controller request proof. The borrowed
store is retained once in CoreApiAssembly.Result for real late consumers, with no
duplicate LocalApiServer field or test-only accessor. Independent production review
found no material defect.

## Integrated and installed proof (2026-09-22)

2400 passed `./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true
:modules:ui:installDist --continue --console=plain` in10m50s at51b01365d plus the
source-frozen reader changes:11500 cases, zero failures/errors,31 recorded skips,
1797 suites across34 tasks (11 tasks reused). Full log, XML, counts, skip list and
23-file source inventory are retained under tmp/2400-query-readers-integrated*.
No failed-task/compiler-error entry occurs in the full log.

Installed run2402 (76b6a26f-2b7a-419a-86be-c83d63f838a0, API49303) enables both
flags through the runner process environment. Health schema2 reports all four
components READY. Runtime-client live smoke passes contract0.4.0/readiness/health.
Standard real-model tier2 query passes exact/intersection/context checks with zero
errors (retrieval2020ms, LLM2966ms,64 completion tokens); this one-query smoke does
not establish broad search quality. Evidence: tmp/2402-model-query/tier2-eval.json,
tmp/2402-query-health.json and tmp/2402-runtime-client.txt.

One-shot actual HTTP search returns200 and queryUnderstanding.appliedBoosts
meta_author=[Mortimer Flux], latency841ms. Explicit CBS Sports filtering returns200
and hybrid normalization to cbs sports, latency465ms. The tiny corpus has no
matching source; this proves the enabled path, not source-mapping quality. Raw
responses are tmp/2402-query-understanding-http.json and
tmp/2402-filter-normalization-http.json. The dev MCP administrative api_call does
not allow the search route; direct one-shot HTTP observes the existing public
query contract, while the model evaluation uses jseval. Stop reports portsClosed
true in tmp/2402-query-stop.json. Apply-register/dispatch and remaining D1 work are
still open; this completes only the bounded reader slice.
