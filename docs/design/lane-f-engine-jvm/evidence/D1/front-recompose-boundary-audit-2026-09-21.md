# D1-3 front recompose boundary audit

Source audit at `b4d01c1cc`. This note resolves the ownership facts behind three
candidate apply-scope rows. It presents implementation choices and does not select
a register scope or alter the D1 design.

The governing constraints are material: D1-2 says the `api` component never enters
`RELOADING` (`stages/D1.md:237-238`), while D1-5 defines the `generative` compose as
`InferenceLifecycleManager.applyConfig` (`stages/D1.md:336-347`). The latter owns an
`InferenceConfig`, mode transition and managed llama-server stop/start/health cycle
(`InferenceLifecycleManager.java:679-723,764-848`). It does not reconstruct front,
conversation, or agent objects.

## Actual replacement boundaries

| Key | Retained readers | Present owner that can replace it | Consequence for the candidate scope |
| --- | --- | --- | --- |
| `justsearch.api.port` | `LocalApiServer` resolves the configured port, binds Javalin, records fallback policy, and publishes the component applied version and READY evidence during construction (`LocalApiServer.java:299-365`). | No live replacement seam. `stop()` closes the API's executors, samplers, modules, conversation controllers and Javalin itself (`LocalApiServer.java:970-1049`). The only fresh bind is a new `LocalApiServer`; routes retain the original assembly/controller references (`LocalApiServer.java:635-679`). | The current `component:api` candidate describes the physical owner but not an executable recompose. It also conflicts with D1-2's “RELOADING never” unless the component contract is amended. |
| `justsearch.rag.top_k` | `ConversationApiAssembly` reads one `ResolvedConfig.Rag` and constructs `RAGContext` with the scalar (`ConversationApiAssembly.java:170,224-248`). `RAGContext` retains it in final `defaultTopK`, read when a request omits `topK` (`RAGContext.java:179-187,217-225,837-855`). | No replacement seam. `ContextInjectorRegistry` is an immutable copied map (`ContextInjectorRegistry.java:16-39`), and the containing `ConversationApiAssembly.Result` is final on `LocalApiServer` and captured by route/controller wiring (`LocalApiServer.java:118,285-291,650-679`). Generative `applyConfig` does not reach it. | `component:api` currently implies rebuilding much more than the one reader and cannot be executed while API never reloads. `component:generative` would name the wrong owner. |
| `justsearch.citation.match_threshold` | The same boot snapshot constructs `StreamingCitationMatcher`, which retains a final normalized threshold (`ConversationApiAssembly.java:160-176`; `StreamingCitationMatcher.java:55-77,127-141`). Separately, `AgentLoopWiring` reads the global snapshot and constructs an `AgentCitationResolver`, which retains another normalized scalar (`AgentLoopWiring.java:139-152`; `AgentCitationResolver.java:35-53,111-116`). | Partial existing seam only: `AgentStepRunner.citationResolver` is volatile and `AgentLoopService.setCitationDocumentService` replaces it (`AgentStepRunner.java:83-86,126-129`; `AgentLoopService.java:1013-1035`). The streaming matcher sits in immutable `StreamConsumerRegistry` (`StreamConsumerRegistry.java:16-39`) and has no setter/supplier. No present owner can replace both readers together. | `component:generative` is not an honest description of the current compose: restarting or applying the native child touches neither frozen citation reader. `component:api` would still miss the Head/agent reader. |

`API_PORT` is already the API component's only declared dependency
(`LocalApiServer.java:1059-1066`) and its spec currently says `IN_PLACE`. That flag is
capability metadata, not an implementation: the builder registers a handle before
constructing the one server (`LocalApiServer.java:1304-1319`), and there is no method
that applies a later desired port. This is a concrete mismatch between the current
component declaration and D1-2's no-reload rule.

`RAG_TOP_K` and `CITATION_MATCH_THRESHOLD` are both normalized in the same typed
`ResolvedConfig.Rag` (`ResolvedConfig.java:763-795`;
`ResolvedConfigBuilder.java:1645-1655`). Their shared configuration record does not
make the managed llama child their owner. `RAG_TOP_K` affects request retrieval, and
the citation threshold affects Worker-backed matching after answer generation; neither
is consumed by `InferenceConfig` or the native launch path.

## Bounded options

### `justsearch.api.port`

1. **Honest current-lifetime option: `restart-required`.** A requested process
   restart reconstructs `LocalApiServer`, rebinds once, republishes the runtime
   manifest/host binding, and preserves D1-2's “api never RELOADING.” This follows
   the actual reader lifetime rather than treating the component label as proof of
   apply behavior.
2. **Real `component:api` option: add an API rebind lifecycle.** This is not a small
   setter. The owner would have to quiesce/admit requests, bind a replacement
   loopback listener, republish discovery and runtime-manifest identity, redirect
   both hosts/clients, retire the old listener and its owned resources, and define
   rollback when the desired bind fails. It requires changing D1-2's state rule and
   proving the local API trust boundary on both listeners. Rebuilding all of
   `LocalApiServer` solely to change a socket is the present mechanism; no narrower
   listener owner exists.

There is no meaningful `hot` reader option for a bound socket: reading a new integer
does not move the listener or its discovery identity.

### `justsearch.rag.top_k`

1. **Small hot-reader extension.** Replace the frozen scalar with an injected
   apply-owned supplier/atomic typed RAG projection and read one value at the start
   of `RAGContext.inject` before `extractTopK`. Existing explicit request-body
   precedence remains unchanged. The D1 operation publishes the new projection only
   after validation; existing in-flight requests retain the value they captured.
   This changes one behavioral reader and does not rebuild routes, registries,
   controllers, Head assembly, or the native child.
2. **Honest current-lifetime option: `restart-required`.** If no live projection is
   added, only process reconstruction replaces the immutable context injector.
3. **API recompose option.** Making `ContextInjectorRegistry` replaceable would be
   broader than this key and would need a coherent replacement of the conversation
   engine's collaborating registries. Calling that `component:api` is honest only
   after that lifecycle exists; it is not supplied by the socket component today.

The first option is the smallest behavioral extension. It earns `hot` only if the
apply operation updates the exact injected projection and a request proves the new
default; a per-request raw environment read would bypass the accepted snapshot and
is not equivalent.

### `justsearch.citation.match_threshold`

1. **Small shared hot-reader extension.** Give both `StreamingCitationMatcher` and
   the agent citation path the same apply-owned typed threshold projection. Each
   matching pass captures one normalized value before dispatch to `DocumentService`.
   The existing volatile agent resolver seam can either be reused during apply or
   simplified to consume that projection; the streaming matcher needs the analogous
   supplier/replace seam. The apply completes only after both paths observe the same
   projection, preserving the documented single cutoff.
2. **Honest current-lifetime option: `restart-required`.** Without the shared live
   projection, process reconstruction is the only existing action that replaces both
   retained readers coherently.
3. **Broaden a component compose.** Assigning the key to `generative` would require
   redefining D1-5's compose transaction to include front conversation and agent
   wiring unrelated to the native child. Assigning it to `api` would still need a
   cross-owner update of the Head agent. Either choice is a new multi-owner compose
   contract, not a dependency-list correction, and risks making native-child restart
   rebuild unrelated Head services.

The shared hot projection is narrower than rebuilding either component, but its
regression must update and exercise both RAG streaming and agent terminal-answer
matching. Proving only the existing `AgentLoopService` setter would leave the known
two-reader divergence.

## Decision inputs for the register owner

- A component scope promises an actual component compose. A dependency digest or a
  `ComponentSpec.ComposeCapability` value does not establish that behavior.
- `component:generative` should remain limited to values applied by
  `InferenceLifecycleManager.applyConfig` unless D1 explicitly broadens that
  transaction. Citation and RAG wiring currently lie outside it.
- `component:api` currently means the bound API owner, whose design forbids reload.
  Using it for conversation objects conflates listener lifecycle with front-service
  configuration unless a narrower sub-owner is added and made part of API compose.
- `hot` is supportable for the two request-time values with small captured-reader
  extensions. It is not supportable for the port. If those extensions are declined,
  `restart-required` truthfully describes all three current lifetimes.

No register row, production source, test, or design decision is changed by this
audit.
