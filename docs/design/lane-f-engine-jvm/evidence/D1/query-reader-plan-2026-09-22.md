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
