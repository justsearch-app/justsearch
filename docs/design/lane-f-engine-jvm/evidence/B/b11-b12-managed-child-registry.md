# B11-B12 managed-child registry and startup reconciliation

Date: 2026-09-08  
Candidate base: `43698eb48a33fb0f5cade976407a72eb3708155d`

## Result

B11 and B12 use one composition-root-owned registry with `RuntimeManifestPublisher` as the
only durable writer. The first v2 write is a synchronous pre-bind ownership seed. Startup
reconciles that seed before worker or inference child-capable asynchronous work begins, then
enriches the same manifest after the API binds.

The private v2 manifest carries managed children and shutdown handoff state. Its public
projection excludes child identity, handoff state, and the mutation token. The Worker endpoint no
longer carries the obsolete gRPC port. Runtime contract `0.3.0` is reflected in the Java authority,
OpenAPI, generated TypeScript client, route snapshots, and contract tests. The root and resource
v2 schemas have identical SHA-256
`EA585DCDBEB8B6157BFC1F0EEDBCFCA0E27D9D091FF9F52CCF8E6A1640FC316A`.

## Acceptance evidence

| Boundary | Evidence | Result |
| --- | --- | --- |
| v1 tolerance, v2 strict/public schema, future-version refusal, recoverability metadata | `RuntimeManifestSchemaCompatibilityTest`, `RuntimeManifestPublisherTest`, `SchemaControllerTest`, `RuntimeManifestControllerRedactionTest`, runtime-client tests | Passed |
| predecessor carry-forward, pre-bind seed, bind enrichment, late-start preservation | `RuntimeManifestPublisherTest`; `HeadlessAppManagedChildStartupOrderingTest` holds the production seed commit and proves reconciliation and the child-capable future cannot begin | Passed |
| persist-before-memory registration and rollback | `MutableManagedChildRegistryTest`; `PersistentExtractionSandboxTest` proves normal rollback death and injected kill/wait failure retains a handle for close retry; `ManagedLlamaAdoptionTest` proves the same root cleanup reachability for llama | Passed |
| PID/start/executable identity and config reconciliation | `ManagedChildReconcilerTest` uses real owned process handles for match, config mismatch, executable mismatch/reused PID, extraction, and failed termination | Passed |
| managed llama health/props/config adoption and launch identity | `ManagedLlamaAdoptionTest` invokes the production adoption and periodic-health seams with a real owned child and loopback HTTP; it covers death before adoption, adopted death and hang routing to managed crash recovery, config mismatch, failed matched termination, and exact replacement-record cleanup | Passed |
| shutdown disposition and registry/publisher lock ordering | `EngineShutdownSequenceTest`; `RuntimeManifestPublisherTest` holds the registry alone, proves shutdown cannot occupy the publisher while waiting, and separately checks the retained manifest after concurrent registration | Passed |
| restart/hang child preservation and terminal identity-safe cleanup | dev-runner supervisor tests; 60 Rust library tests, including a real-process positive/mismatch test of the terminal identity helper | Passed, with packaged Tauri execution limitation below |
| system-access and unreferenced-code negative controls | `SystemAccessFunnelTest`, `UnreferencedCodeTest` | Passed after the policy guard caught and drove removal of two new direct property reads |

## Commands

- `gradlew.bat build -x test --no-build-cache`: passed (325 tasks; compilation, assembly, PMD,
  Spotless, integration-test and repository checks completed).
- Focused Java tests for runtime manifest compatibility/publisher/registry, shutdown sequencing,
  reconciliation, llama launch/adoption, extraction registration, and worker sandbox wiring:
  passed.
- Affected Java `spotlessCheck` and `pmdMain` across app-api, app-engine, app-inference,
  app-services, ui, indexer-worker, and worker-services: passed.
- Review correction focused Java run: 56 tests passed; exact invoked-task XML preserved at
  `C:\Users\Elias\AppData\Local\Temp\lane-f-b11-b12-review1-focused-1788880549`. Final filtered
  adoption/contention/barrier rerun: 30 tests passed; XML preserved at
  `C:\Users\Elias\AppData\Local\Temp\lane-f-b11-b12-review1-final-focused-1788880837`.
- Final managed-adoption death/hang negative-control rerun: 6 tests passed; XML preserved at
  `C:\Users\Elias\AppData\Local\Temp\lane-f-b11-b12-review1-adoption-final-1788881136`.
- `cargo test --lib --locked`: 60 passed, including the direct terminal identity-helper test.
- `npm test` in `packages/runtime-client`: 7 passed; `npm run check:regen`: passed.
- `npm run typecheck` and `npm run test:unit:run` in `modules/ui-web`: 480 files and 6,451 tests
  passed.
- `node scripts/dev/test-dev-runner-supervisor.mjs`: 8 scenarios passed.
- Runtime-manifest closure: 3,408 files checked, no violations. Documentation checks: 114 docs
  and 155 canonical-link files passed. Dependency lock resolution made no tracked change.
- Full `gradlew.bat test --no-build-cache` produced 1,126 task-scoped
  `build/test-results/test/TEST-*.xml` files across all 34 source-test modules: 7,128 cases, one
  failure, no errors, and 22 skipped. No source-test module was missing, so this was not a
  fail-fast-shortened inventory. The sole current-run failure was B11/B12's
  `SystemAccessFunnelTest`; it was corrected and its focused rerun passed. A recursively counted,
  older `updateSchemas` XML had misleadingly added a stale `WorkerDebugView` schema failure; the
  current task-scoped app-api schema test is green, and source/baseline removed `signal_bus`
  together before this base. Original XML is preserved at
  `C:\Users\Elias\AppData\Local\Temp\lane-f-b11-b12-stable-full-1788877867`.

## Root correction after the final worker round

Root took the remaining fixes instead of extending the worker brief. Repeated
registration-rollback termination failures now keep the exact process and final
cleanup callback, refuse another llama launch, and fail terminal close. Head
resource teardown aggregates failures after trying every handle, so the ordered
sequence cannot call a surviving unregistered child a clean shutdown. An already
dead current llama retires its exact registry entry even when its exit callback
was cancelled before death.

The focused root run passed 58 tests (8 managed adoption, 1 manager shutdown,
23 extraction sandbox, 1 Head teardown aggregation, 25 publisher tests), preserved
in `tmp/b11-root-close-xml`. The original contention test did not distinguish the
old inversion. The added `shutdownWaitingForRegistryDoesNotHoldPublisher` does:
restoring publisher-before-registry locking failed it with a timeout; restoring
the correction passed. Affected PMD checks passed. The subsequent repeat-start
guard passed all eight managed-adoption tests and inference PMD. These are focused
results, not combined stage acceptance. Raw logs remain local:

| Artifact | SHA-256 |
| --- | --- |
| `tmp/b11-root-close-proof.txt` | `a6caf4b72f3d44d7ae6530285a6ab8b069573f15bc2626d65f62f1c4def89970` |
| `tmp/b11-lock-negative.txt` | `e62b5a58b6b80d57cf8318d913b0bd7b45a2914fde2c92643faf68dc84aa7124` |
| `tmp/b11-root-close-final.txt` | `b8532e7647962373dc3f94070d27cbc981856b3d6c7ab302707193cb61293237` |
| `tmp/b11-root-repeat-start.txt` | `8d2c7b7b9566ad6b103bae424bc24f2ffe1591f1931d2ef14c1bf4ed0f41dec6` |

Independent read-only review signed off the root correction on 2026-09-08.

## Limits

The Tauri `AppHandle` path was compiled and source reviewed through the Rust library tests; this
batch does not claim packaged desktop actuator execution. `check-store-recoverability` still
depends on the separately reviewed B14/B16 candidate because this base lacks its
`UpgradeShutdownViaRequestFileTest` evidence and `engine_host.rs` classification. The combined
candidate must rerun the full Java suite and that governance gate.

`cargo fmt --check` remains red on the pre-existing crate-wide formatting backlog. Its output has
no hunk for the new terminal identity test; the Rust library compiled and all 60 tests passed.
