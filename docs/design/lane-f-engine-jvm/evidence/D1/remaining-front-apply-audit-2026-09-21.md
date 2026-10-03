# D1 remaining front/control apply-scope audit

Source audit at `6c95d7989`. The 24 rows below are the exact requested subset of
`tmp/2298-declared-key-union.json`: eight `justsearch.agent.*` declarations, four
`infra.health.*` declarations, and twelve named front/control declarations. This is
reader evidence for the D1-3 reconciliation, not an implemented register or apply-path
proof. Scope precedence and shared dependency obligations are defined by
[`apply-register-plan-2026-09-21.md`](apply-register-plan-2026-09-21.md).

`restart-required` means at least one current behavioral reader freezes the value in a
process-lifetime owner. `hot` means the behavioral reader can consume a replacement
without owner reconstruction, although the table separately identifies missing
ConfigStore/apply wiring. A declaration with no operational reader has no honest scope
candidate: assigning `hot` would claim an apply behavior that does not exist.

## Exact-key audit

| Canonical key and declaration | Current reader shape | Candidate scope and dependency finding | Primary reader evidence |
| --- | --- | --- | --- |
| `justsearch.agent.browse.default_max_folders` — `EnvRegistry.AGENT_BROWSE_DEFAULT_MAX_FOLDERS` (`EnvRegistry.java:156-158`) | Frozen at class initialization | `restart-required` | `BrowseTool.java:33-39` resolves the ConfigStore value into static-final `DEFAULT_MAX_FOLDERS`; executions reuse it at `:80-93`. |
| `justsearch.agent.context_compression.enabled` — `EnvRegistry.AGENT_CONTEXT_COMPRESSION_ENABLED` (`EnvRegistry.java:133-135`) | Frozen in AgentLoop construction | `restart-required` | `AgentLoopService.java:323-341` constructs one `AgentContextCompressor` from the resolved value. |
| `justsearch.agent.context_compression.keep_last_results` — `EnvRegistry.AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS` (`EnvRegistry.java:143-145`) | Frozen in AgentLoop construction | `restart-required` | Same constructor capture at `AgentLoopService.java:333-340`. |
| `justsearch.agent.context_compression.min_chars` — `EnvRegistry.AGENT_CONTEXT_COMPRESSION_MIN_CHARS` (`EnvRegistry.java:138-140`) | Frozen in AgentLoop construction | `restart-required` | Same constructor capture at `AgentLoopService.java:333-340`. |
| `justsearch.agent.max_completion_tokens` — `EnvRegistry.AGENT_MAX_COMPLETION_TOKENS` (`EnvRegistry.java:165-166`) | Live per LLM call | `hot` | `AgentContextBudgets.java:59-69` reads the current ConfigStore value for every `forCall`. |
| `justsearch.agent.max_tool_result_chars` — `EnvRegistry.AGENT_MAX_TOOL_RESULT_CHARS` (`EnvRegistry.java:161-162`) | Live per result-budget decision | `hot` | `AgentContextBudgets.java:94-103` reads the current ConfigStore value for every tool-result cap. |
| `justsearch.agent.search.default_limit` — `EnvRegistry.AGENT_SEARCH_DEFAULT_LIMIT` (`EnvRegistry.java:148-149`) | Frozen at class initialization | `restart-required` | `SearchTool.java:44-50` resolves the ConfigStore value into static-final `DEFAULT_LIMIT`; request parsing reuses it at `:212`. |
| `justsearch.agent.search.default_mode` — `EnvRegistry.AGENT_SEARCH_DEFAULT_MODE` (`EnvRegistry.java:152-153`) | Live per omitted-mode request | `hot` | `SearchTool.java:53-67` reads ConfigStore, and the request paths call it at `:96` and `:219`. |
| `infra.health.poll_interval_ms` — `ConfigKey.INFRA_HEALTH_POLL_INTERVAL_MS` (`ConfigKey.java:87`) | Replaceable aggregator config, but current production refresh trigger is disconnected from ConfigStore updates | `hot` target; missing ConfigStore-to-`InfraHealthBootstrap` apply wiring | `InfraHealthBootstrap.java:20-44` can rebuild the aggregator from the current ConfigStore. It is wired only to `ConfigManagerBootstrap`, whose `refresh()` has no production caller (`ConfigManagerBootstrap.java:20-59`). |
| `infra.health.thresholds.ann_cache_ready_percent` — `ConfigKey.INFRA_HEALTH_ANN_CACHE_READY_PCT` (`ConfigKey.java:90`) | Same replaceable/disconnected path | `hot` target; same missing apply wiring | `InfraHealthBootstrap.java:35-44` reads the current percentage; `InfraDiagnosticsService.java:42-45` atomically replaces the aggregator. |
| `infra.health.thresholds.nrt_stale_ms` — `ConfigKey.INFRA_HEALTH_NRT_STALE_MS` (`ConfigKey.java:88`) | Same replaceable/disconnected path | `hot` target; same missing apply wiring | `InfraHealthBootstrap.java:35-44`; replacement seam at `InfraDiagnosticsService.java:42-45`. |
| `infra.health.thresholds.translator_handshake_stale_ms` — `ConfigKey.INFRA_HEALTH_TRANSLATOR_STALE_MS` (`ConfigKey.java:89`) | Same replaceable/disconnected path | `hot` target; same missing apply wiring | `InfraHealthBootstrap.java:35-44`; replacement seam at `InfraDiagnosticsService.java:42-45`. |
| `justsearch.ai.disabled` — `EnvRegistry.AI_DISABLED` (`EnvRegistry.java:346`) | Frozen existence decision | `restart-required`; missing shared dependency on `generative` | `InferenceDecision.java:29-64` decides whether the inference manager is constructed. `HeadAssembly.java:68-75` omits this key from `GENERATIVE_DEPENDENCIES`, even though it selects intentional component absence. |
| `justsearch.rule.tick.ms` — `EnvRegistry.RULE_TICK_MS` (`EnvRegistry.java:1211`) | Frozen scheduler interval | `restart-required` | `RuleRunnerBuilder.java:55-80` resolves the value once and passes it to `RuleRunner`; `RuleRunner.java:53-80,115` retains and schedules from that duration. |
| `justsearch.ui.automation.enabled` — `EnvRegistry.UI_AUTOMATION_ENABLED` (`EnvRegistry.java:468`) | Mixed: runtime context is live, diagnostics overrides are boot-frozen | `restart-required` with current semantics; a hot target needs diagnostics apply/clear wiring | `RuntimeContextConfigBridge.java:12-69` updates runtime context from ConfigStore changes, but `BootstrapHelpers.java:145-166` installs diagnostic suppliers only during boot and has no inverse clear path. |
| `justsearch.ui.automation.forceDiagnostics` — `EnvRegistry.UI_AUTOMATION_FORCE_DIAGNOSTICS` (`EnvRegistry.java:471-473`) | Boot-frozen diagnostics side effect | `restart-required` | `BootstrapHelpers.java:145-166` reads the flag once while installing fixed diagnostic suppliers. `RuntimeContextConfigBridge` does not observe it. |
| `justsearch.ui.exclude_patterns` — `EnvRegistry.UI_EXCLUDE_PATTERNS` (`EnvRegistry.java:1198`) | Live per indexing/search operation | `hot` | `ExcludesServiceImpl.java:29-51` reads current ConfigStore patterns; `KnowledgeClient.java:891-899` does the same for worker-facing paths. |
| `justsearch.summary.max_tokens` — `EnvRegistry.SUMMARY_MAX_TOKENS` (`EnvRegistry.java:180`) | No operational reader | No honest candidate; retire the declaration or connect an owner before assigning scope | `ResolvedConfigBuilder.java:1373-1376` resolves it and `ResolvedConfig.java:376-377` stores it, but the production source tree has no `ResolvedConfig.summary().maxTokens()` consumer. |
| `justsearch.summary.pipeline` — `EnvRegistry.SUMMARY_PIPELINE` (`EnvRegistry.java:177`) | No operational reader | No honest candidate; retire the declaration or connect an owner before assigning scope | `ResolvedConfigBuilder.java:1373-1376` resolves it and `ResolvedConfig.java:376-377` stores it, but the production source tree has no `ResolvedConfig.summary().pipeline()` consumer. |
| `justsearch.rag.top_k` — `EnvRegistry.RAG_TOP_K` (`EnvRegistry.java:351`) | Frozen in API composition | `component:api`; missing API component dependency/projection | `ConversationApiAssembly.java:229-248` captures `ragTopK` into a constructed `RAGContext`; `RAGContext.java:179-187` retains the value. `LocalApiServer` currently declares only `API_PORT` at `LocalApiServer.java:325-332`. |
| `justsearch.gpl.reeval_size_factor` — `EnvRegistry.GPL_REEVAL_SIZE_FACTOR` (`EnvRegistry.java:908-909`) | Frozen in Head orchestration | `restart-required` | `GplRevalidationTrigger.java:18-28` captures the ConfigStore value into a final field; `GplOrchestration.java:240` constructs that process-lifetime trigger. |
| `justsearch.lambdamart.enabled` — `EnvRegistry.LAMBDAMART_ENABLED` (`EnvRegistry.java:479`) | Mixed: initial load/train is frozen; later trigger gate is live | `restart-required` with current semantics | `OrchestrationPhase.java:190-210` reads live ConfigStore in the later trigger callback at `:198-201`, but the initial model load/train decision is made once at `:204-210`. A hot false/true transition cannot undo or replay both effects coherently. |
| `justsearch.qu.enabled` — `EnvRegistry.QU_ENABLED` (`EnvRegistry.java:78-79`) | Live direct EnvRegistry read | `hot` target; missing ConfigStore-backed apply reader | `QueryUnderstandingService.java:108-113` evaluates the environment/system property on each enablement check. D1 apply must route the current resolved value to this reader rather than assuming a persisted ConfigStore change mutates the process environment. |
| `justsearch.filter_norm.enabled` — `EnvRegistry.FILTER_NORM_ENABLED` (`EnvRegistry.java:82-85`) | Live direct EnvRegistry read | `hot` target; missing ConfigStore-backed apply reader | `FilterNormalizationService.java:97-102` evaluates the environment/system property on each enablement check. It has the same ConfigStore/apply disconnect as query understanding. |

## Reconciliation findings

- The eight agent declarations split by actual lifetime: five are frozen
  (`browse.default_max_folders`, the three context-compression fields, and
  `search.default_limit`) while three are live (`max_completion_tokens`,
  `max_tool_result_chars`, `search.default_mode`). Their common prefix does not imply one
  scope.
- All four `infra.health.*` values have a viable atomic hot replacement seam, but the
  listener is attached to `ConfigManagerBootstrap`, not `ConfigStore`, and no production
  code calls `ConfigManagerBootstrap.refresh()`. The register may call them `hot` only
  together with the missing apply bridge and a regression that changes the resulting
  health payload.
- `AI_DISABLED` is restart-required by current manager-existence semantics and is also a
  missing shared dependency of the `generative` component. Restart precedence does not
  remove the dependency obligation.
- `RAG_TOP_K` is owned by API composition and is missing from the API component's declared
  dependency/value projection.
- `UI_AUTOMATION_ENABLED` already has one hot projection, but its diagnostic override is a
  second behavioral reader with boot-only side effects. Classifying it hot before adding
  symmetric diagnostic apply/clear behavior would leave partial application.
- `SUMMARY_PIPELINE` and `SUMMARY_MAX_TOKENS` are resolved storage without behavioral
  consumers. The full-union reconciliation must retire them or name and connect an owner;
  it must not manufacture a passing `hot` row for dead configuration.

No source, register, component dependency, or runtime behavior is changed by this audit.
