# D1 initial owner wiring evidence

Base `574a88d47` plus uncommitted D1 implementation. This is intermediate evidence,
not D1-1/2/3 acceptance. Raw outputs are under `tmp/` in the lane worktree; retain
through lane acceptance plus 30 days and export before worktree deletion.

The process owns one registry and retains index/encoder handles across physical
index replacement. Real Headless boot supplies the registry to API and generative
owners. API READY follows successful bind; encoder READY requires requested roles
and actual service wiring. Generative currently bridges the existing capability
authority; D1-2 must replace that authority before batch acceptance. Index READY
still awaits the existing sampler's complete host-ready conjunction.

| Run | Result and limit |
| --- | --- |
| 2272 component foundation | 114 cases, 19 suites, no failures/errors/skips; earlier foundation revision only |
| 2273 applied values | 3 cases, 1 suite pass; strict typed digest |
| 2276 index applied values | 5 cases, 2 suites pass; WorkerConfig projection, not all index inputs |
| 2278 owner wiring | Build failed on record constructor self-assignment rule; app-services 9 cases/5 suites passed |
| 2279 owner wiring | Build failed: one factory fixture retained old arity; 5 observation tests lacked global fixture config; 27 other indexer cases passed |
| 2280 owner wiring | Build successful; 38 cases/8 suites, zero failures/errors/skips, indexer-worker and UI freshly executed; app-engine test sources compiled |
| 2281 affected modules | Build successful in 15m18s; all four tasks freshly executed, 5,359 cases/803 suites, zero failures/errors, 19 skips; before review corrections |
| 2287 review corrections | 72 cases/16 suites, zero failures/errors/skips; all four selected tasks freshly executed. Actual captured-config index startup, commit metadata, occupied-port fallback, and bounded adapter delivery regressions pass. Source inventory and XML retained under `tmp/2287-component-review-corrections*`. |
| 2288 extraction capture | 104 configuration cases passed; 1 of16 worker-service cases failed because a new heap assertion assumed inline argv. Encoder/API tests did not execute. |
| 2289 captured owners | 45 cases/10 suites freshly passed without skips: extraction17, indexer21, API7. Includes actual enabled encoder composition/service wiring with only native assembly mocked and the unchanged thin-composer ceiling. Source inventory/XML under `tmp/2289-captured-owner-configuration*`. |
| 2290 runtime projection | 91 cases/11 suites, zero failures/errors/skips across four fresh tasks; actual runtime projection, snapshot discovery, extraction, index startup/reconstruction and encoder composition. App-engine test sources compiled. Source inventory/XML under `tmp/2290-index-owner-projection*`. |
| 2291 projection corrections | 16 cases/4 suites, zero failures/errors/skips across two fresh tasks. Separate commit-refresh thresholds stay active in on-demand mode; disabled background/recall controls do not change applied values. Source inventory/XML under `tmp/2291-index-projection-review*`. |
| 2292–2293 static checks | Failed on edited CRLF files, redundant qualifiers/initial assignment and unused test resource declarations. Root corrected these without changing rule configuration. No test task executed in2293. |
| 2294 interrupted integrated checks | Configuration and adapters-lucene freshly passed1,024 cases/139 suites with no failures/errors/skips. UI PMD found one additional unused subscription declaration, stopping the remaining modules. The declaration is corrected; this is partial proof only and precedes the final three review corrections. |
| 2295 cleanup fixture | New telemetry cleanup regression failed because its two-second bound was shorter than the SDK's five-second worker poll; later focused tasks did not run. Installed-library bytecode preserved at `tmp/2295-batch-worker-bytecode.txt` explains the shutdown-before-thread-exit sequence. |
| 2296 owner review regressions | 22 cases/8 suites, zero failures/errors/skips; telemetry, indexer-worker and UI tasks freshly executed. All Spotless/PMD tasks pass. Covers hot reload, retained discovery, actual tracing acquisition/normalization, exporter cleanup and production four-component registration/API bind. |
| 2297 cleanup negative control | One intended failure: omitting SDK cleanup leaves a new BatchSpanProcessor worker alive past the bounded wait. Exact failure/XML retained; production source restored byte-exactly. |

Each preserved run has its command/output in `tmp/<run-name>.txt`, bounded XML
copies and `-counts.json`. 2280 ran focused
`KnowledgeServerComponentObservationTest`, `InferenceCompositionRootComposeTest`,
`InferenceSurfaceTest`, `LocalApiComponentRegistryTest`, and
`LocalApiServerThinComposerTest`, plus app-engine compileTestJava.

Post2287 root review restored the unchanged thin-composer ceiling of30 fields.
The initial API wiring had raised it to31. The requested port is constructor-only,
so it is now a local variable; the persistent component handle fits the original
limit without another wrapper. Run2289 proves the restored constraint.

The 2278 fix removed a redundant assignment while retaining null validation. The
2279 fixes updated the remaining factory fixture and removed the observation test's
unneeded global configuration dependency. No assertions or validation were weakened.
Partial composition reports the desired digest but cannot claim a fully applied
configuration. This decision is in the owning component plan.

Independent foundation review found three corrections before a checkpoint can claim
honest applied versions:

- Encoder service wiring still reconstructs several configs through global reads.
  Even repeating a factory against the same snapshot can repeat filesystem discovery.
  Use one package-private typed projection for composition and wiring; snapshot-aware
  discovery must not fall back to a disagreeing global config. The disabled-role test
  alone did not prove this property. Master GPU and policy-veto inputs also need typed
  projections from the existing resolver, not raw resolution strings.
- WorkerConfig is not a complete index-owner projection. Index base path, migration
  cutover policy and shared sparse-model inputs are consumed outside it. Conversely,
  several old IPC fields are merely copied and have no operational reader. The
  97-row prefix audit is `tmp/2277-index-config-scopes.json` (62 component:index,
  22 hot, 3 generation-bound, 10 unresolved); it is evidence, not the full register.
- API explicit-port fallback currently gives applied and desired the same digest.
  Retain the requested policy as desired and record the effective ephemeral policy
  as applied; prove this with a real occupied-port fallback. The chosen ephemeral
  port number remains runtime evidence rather than a stable config value.

Correction ownership after 2281 passed and its XML/source inventory was preserved: encoder worker owns
snapshot-aware role factories/discovery/projection (including configuration and
reranker contracts), API worker owns fallback and its real-bind test, root owns
KnowledgeServer/actual index projection and intentional-absence semantics. An
independent reader is mapping the index capture boundary. The compiled-source freeze
is released; corrected sources need fresh focused proof. `tmp/2282-app-engine-progress.txt` identified the owned test executor
creating the existing 20,000-file scan-cancellation fixture; a quiet tail did not
prove a hang, so no process was killed or test weakened.

Still required: independent review corrections, actual four-owner coverage with a
non-vacuous floor, readiness/manifest/health authority replacement, full 291-key
apply register, required negative controls, generated contracts and both adapters.
Do not infer these capabilities from the focused tests or the presence of handles.
