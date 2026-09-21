# D1 control-reader verification, 2026-09-22

Base: `d87a0e60c6ac2a4362e6d966c4f541a80330a441`, pushed to PR727.
D1 acceptance remains open. This record covers the next bounded reader batch.

## Implemented behavior awaiting complete verification

- Retire `workers.indexer.enabled`, its YAML mapping/carrier and smoke diagnostic.
  The required embedded index never read the value. Preserve worker service version.
  Declaration union is now 277: 236 EnvRegistry + 53 ConfigKey - 12 aliases.
- Make `llm.enabled=false` prevent generative manager creation. HeadAssembly uses
  its captured configuration for the decision and passes the retained decision to
  BootstrapInferenceFactory. Headless launch identity uses the same predicate.
  AI-disabled and the process lite-mode gate retain precedence. This is a startup
  connection, not a claim of hot creation/removal of an absent manager.
- Resolve an unset citation-scoring wire threshold at CitationMatchOps using its
  captured scorer configuration. Positive explicit requests win; invalid/missing
  configured defaults use 0.5. Cross-encoder and embedding fallback share the value.

## 2387 focused pass and independent refutation

Command:
`./gradlew.bat :modules:configuration:test :modules:app-services:test --tests '*InferenceDecisionTest' --tests '*HeadAssemblyTest' --tests '*HeadAssemblyComponentRegistryTest' :modules:app-launcher:test --tests '*SmokeDriverTest' --tests '*UnreferencedCodeTest' :modules:worker-services:test --tests '*WorkerSearchServiceMatchCitationsTest' :modules:ui:compileJava spotlessCheck pmdAll --continue --console=plain`

PASS in 1m28s: 380 executed cases, zero failures/errors/skips, 61 suites across
four test tasks. Static checks and UI compilation pass. Logs, XML/counts and
14-source inventory are under `tmp/2387-control-readers-focused*`.

The negative LLM branch is proved through actual HeadAssembly manager absence and
registry ABSENT state, plus captured settings versus contradictory globals. The
scorer regression invokes the real WorkerSearchService request path with an existing
deterministic producer seam; its embedding fallback distinguishes cosine 0.8 from
configured threshold 0.9 and explicit request 0.7.

Independent review refuted the positive snapshot claim despite the passing tests:
InferenceConfig re-read ConfigStore.global(), including a second read in server
executable discovery. BootstrapInferenceFactory also retained an unused probe/cache
that could supply a different configuration. Root removed that cache/probe and
added an explicit `fromResolvedConfig` path. Both the actual factory and Headless
use it; compatibility `fromEnvironment` delegates through one captured store read.
The server discovery fixture keeps its original assertions with the new parameter.

The new positive factory test replaces global A with observably different B, then
checks the actual manager's executable/model/port/context/GPU values and declared
launch hash against A. It does not launch fake executables or load fake model files.
Existing profile-source and legacy-profile compatibility rules remain; this fix
removes global snapshot mixing, not all remaining D1 configuration obligations.

## 2388/2389 captured snapshot verification

`./gradlew.bat :modules:app-inference:test --tests '*InferenceConfigFromEnvironmentTest' --tests '*InferenceConfigServerExeTest' :modules:app-services:test --tests '*InferenceDecisionTest' --tests '*BootstrapInferenceFactoryCapturedConfigTest' --tests '*HeadAssemblyTest' --tests '*HeadAssemblyComponentRegistryTest' :modules:ui:compileJava spotlessCheck pmdAll --continue --console=plain`

2388 executed 62 cases with zero failures/errors/skips across ten suites. The build
failed solely on PMD's redundant `java.util.Objects` qualifier. After using the
existing import, 2389 passed all static checks and reused those 62 tests unchanged.
Both generations retain logs, XML, counts and 18-source inventories. The independent
reviewer's final reread found no residual material defect in this bounded slice.
Actual disabled boot and positive standard model proof remain due.

## Governance and hosted status

2387 config-surface passes with downward pins 102 YAML mappings / 236 EnvRegistry /
53 ConfigKey; no growth waiver. Matrix, llms index and embedded-skill checks pass.
CI35659220060 at d87a0e60c completed with app-ui and Public claims failed; all other
jobs, including system integration and Windows-native tests, passed. Logs are under
`tmp/ci-35659220060`. App-ui assertions passed but background root persistence raced
TempDir cleanup: its non-waiting test executor did not implement production owner
close semantics. The fixture now uses `awaitingTermination()`; production already
waits. 2390 focused watched-root tests and spotlessCheck pass; evidence is captured.

Hosted Public claims omitted its SARIF artifact. Root reproduced locally after
producing the required knip report: the exact error is status-response generated
unused exports growing from 6 to 8, not the reviewer's tentative isRecovering
attribution. Preserved `tmp/2390-dead-code-before.sarif` and knip JSON identify the
actual violation; generator/consumer ownership is under investigation. No baseline
was relaxed and no helper was deleted on the disproved attribution. Local results
do not replace either hosted failure.

The settled schema2 plan already requires the generated component vocabulary at
frontend consumers. StatusDeck now types its actual api/index state reads with
generated ComponentState (preserving null/undefined), and the wire faithfulness
suite directly checks the exported validator's exact six-state vocabulary plus
retired/unknown rejection. No generator policy or schema changed. 2391 typecheck
and42 focused frontend cases pass; schema regeneration check passes. The dead-code
gate now passes with three informational downward-rebalance findings and unchanged
baseline. Before/after SARIF makes the formerly red rule independently inspectable.
The full frontend and integrated Java/static/stress/installDist checks are running.

Full frontend2391 passes6598 cases/490 files (exit0); expected fixture network and
Happy DOM teardown diagnostics remain visible in its retained output. The existing
always-run governance artifact upload now also retains SARIF and Knip input, so
future hosted failures preserve the exact finding rather than requiring an inferred
attribution. Python YAML parsing verifies the upload remains unconditional; hosted
artifact delivery itself awaits the next authorized checkpoint run.

## Integrated2391 and configuration funnel correction

`./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist --continue --console=plain`
finished in11m13s with11490 cases, one failure, zero errors and31 skips across1793
suites/34 tasks. Eleven unchanged tasks reused prior results. Logs/XML/counts and
19 production-source inventory were captured before correction. The sole failure
was SystemAccessFunnelTest: moving configuration assembly introduced direct user.dir
reads in InferenceConfig#fromResolvedConfig and HeadAssembly's constructor. Root
initially missed this earlier log entry while reading only the tail; checking the
full failure index corrected the status before any success claim.

Both process-global reads now use the existing SystemAccess accessor. The three
obsolete InferenceConfig#fromEnvironment and removed InferenceDecision allowlist
entries were retired; no allowlist growth. Config-surface2392 passes with five
informational findings. Corrected focused inference/assembly/funnel tests, static
checks and installDist run as2392; source remains frozen until capture.

2392 completed PASS in40s:71 freshly executed cases, zero failures/errors/skips,
14 suites/three test tasks; static checks and installDist pass. Captured generation
and19-source inventory retain the exact source boundary. This closes the sole2391
funnel failure with a focused rerun; other2391 suites are not relabelled as rerun.

## Installed2392/2393 proof

2392 disabled run7f0249b6-7136-4c11-a2e5-e1ba4da412b1 used process-local
JUSTSEARCH_LLM_ENABLED=false inherited by the official owned dev runner. Fresh
installDist boots schema2: api/index/encoders READY and generative ABSENT, health200.
An executable assertion checks schema/index/generative states from retained health
JSON. The owned stack stopped with portsClosed:true; its client closed/terminated.

2393 normal run0a9651d5-afa8-4f63-9eda-097a169afd6d uses the same distribution and
standard profile, without the process-local disable. All four components READY;
online intent converged at acceptedRevision4. Runtime-client contract0.4.0 smoke
passes readiness/health200. The real Qwen3.5-9B query via jseval passes exact answer
match1.0 with1918ms retrieval/3263ms LLM,74 completion tokens. Live UI home measure
reports94 landmarks, zero axe/console issues and no overflow; root inspected the
screenshot with the connected/online status visible. Evidence: tmp/2393-model-query,
tmp/2393-runtime-client.txt, tmp/2393-normal-health.json and tmp/2393-ui-home.

No remaining D1/D2/E/F acceptance is waived by these bounded reader proofs.
