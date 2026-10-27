---
schema: test-intent.v1
task: t-20261026-798a70
author-role: builder
author-session: 01a17afa-33cf-7a01-b66d-35da339fb28e
---

# Isolate integration-test configuration and HTTP cleanup ownership

Only test fixtures and their checks change. The new, isolated
`LocalApiConfigIsolationIntegrationTest.java` is exempt and is not included in these entries.
Execution results below cite supplied evidence; this changeset author did not run Gradle.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/OutstandingHttpFailureCleanupIntegrationTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Repair the test fixture's ownership mistakes: cancel its own outstanding HTTP send before retrying cleanup and inspect its own closer threads. Preserve the product cleanup contract, budgets and assertions; exercise the repaired observation with an independently owned same-name thread.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Keep the shared-budget, retained-owner, independent transport-start and physical retry-drain expectations while removing two fixture defects seen in main CI run 40404506498 on ebf7918334. HttpClient.close waits for pending sends; the finally block cancels only this test's send before its existing cleanup retry. Direct liveness assertions target the fixture's two closer threads instead of unrelated JVM threads with matching names.",
        "callersAndStateOwners": "LinuxWatchedDriveReturnIntegrationTest creates the RuntimeReloadProducersHttpIntegrationTest.Fixture, stalled handler, asynchronous exchange and cleanup attempt. That fixture owns httpCloser and transportCloser. OutstandingHttpFailureCleanupIntegrationTest adds a check of this test infrastructure: it invokes the existing budget case with zero allowance while owning a separate same-name thread, releases and joins that thread in finally, and verifies its termination. Its existing pre-entry failure identity and cleanup cases are unchanged. Production cleanup and state-retention ownership are unchanged.",
        "indirectBehaviours": "The send remains outstanding during the original budget observation; cancellation occurs only in finally before finishOutstandingHttpExchange. Allowances 0, 500000 and 2500000000 ns, the 60 s method timeout, entered/stalled preconditions, original allowance plus 1 s bound, retained private configuration, independent transport start, fixture closure and final 10 s cleanup remain. The added foreign-thread case validates ownership of the test's observation rather than introducing a product expectation. Both fixture closer threads must still physically exit, and the independently owned thread must remain alive until its own release.",
        "evidence": "tmp/accept-evidence/original-ci-failures.json records the original cleanup timeout and same-name thread assertion failures across the two CI attempts. tmp/accept-evidence/verifier-1-summary.md reports S2 false failures for both foreign thread names before, passes after and 30/30 candidate budget repetitions; S3 and tmp/accept-evidence/diff-review.txt preserve the original assertions and budgets. The later matched candidate.log reports BUILD SUCCESSFUL with spotlessCheck and pmdIntegrationTest successful; cand-xml records 19/19 drive cases and 3/3 outstanding-failure cases passing without skips. before.log and before-xml record 18/19 drive cases passing, with an unrelated repeated-return handle-retention assertion failure.",
        "unresolvedRisks": "The first verifier reported S4 drive-suite and PMD failures on 86535aaff6; the later supplied matched candidate run passes after the PMD follow-up. The original HTTP-close timeout did not reproduce in the verifier's 30 baseline repetitions, so no deterministic fail-before is claimed for cancellation. The matched base drive suite was not wholly green because of its handle-retention failure. The foreign-thread regression case directly exercises fixture infrastructure, and is classified Incidental on that warrant, not as a new product oracle. No D4 run-evidence file is claimed."
      }
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Give each API fixture its own environment and complete settings configuration, discover policy in that fixture's scope, restore its predecessor after successful teardown, and surface stop failures while preserving existing API expectations.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Stop API tests from inheriting a retained foreign ConfigStore and consequently applying settings without the correct governed scope. Preserve the tests' policy and HTTP expectations by resolving launch properties before policy discovery and preparing a complete settings snapshot for this fixture. Report a server-stop failure instead of silently accepting incomplete teardown.",
        "callersAndStateOwners": "All LocalApiIntegrationTestBase subclasses use startServer and stopServer. The base snapshots the prior global before subclass hooks, publishes its own environment store, prepares settings with ConfigStoreRebuilder, publishes the resulting store and passes it to its resources and settings components. A retained engine fixture owns the predecessor and its unchanged snapshot. The base restores that predecessor through ConfigStore.restoreGlobal only when its own config exists and teardown has no recorded close failure; restoration must not overwrite a newer owner's publication.",
        "indirectBehaviours": "Policy discovery now reads this fixture's home and AI switches before the settings snapshot is built. Settings, API and resource composition share that complete snapshot and publication lock. Server-stop failure is retained as the primary close failure; later operations and settings-resource close failures are suppressed rather than replacing it. On recorded failure, the fixture's configuration remains published; successful teardown restores the predecessor. Existing HTTP calls, policy inputs, expected outcomes and test selection remain. The two subclasses that previously published stores adapt to this ownership below.",
        "evidence": "tmp/accept-evidence/original-ci-failures.json records both AI import failures with 'No governed apply scope for configuration key: search.facets.fields'. verifier-1-summary.md reports S1 both imports failing before and passing after with predecessor store and snapshot preserved; its additional newer-owner restoration check passes before and after. diff-review.txt records the fixture-only change and unchanged subclass assertions. The later candidate.log reports successful formatting, PMD and selected integration execution. cand-xml records LocalApiConfigIsolation 2/2, AiInstallPolicy 4/4, and AiPackPolicy 2 passing with 1 skipped and no failures.",
        "unresolvedRisks": "One AiPackPolicy case is skipped and is not claimed as executed. The earlier verifier's S4 failure is retained in verifier-1-summary.md; the supplied later candidate run is the passing preservation evidence. No injected server-stop failure execution is supplied, so failure propagation and retention are supported by diff inspection only. This shared base affects additional API subclasses beyond the selected supplied runs; those are not claimed as verified here. No D4 run-evidence file is claimed."
      }
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Configuration setup and predecessor restoration move to LocalApiIntegrationTestBase's fixture ownership. PlannedContextSettingsIntegrationTest keeps its launch properties, fake device facts, planner, real query-only owner, components, requests and assertions; it binds its config field and publishes/listens to DevicePlanState in configureSettingsComponents using the actual API store supplied by the base, instead of constructing a second store in beforeServerStart. Its planner and device-plan cleanup remain; duplicate global restoration is removed. ProdModeSettingsPersistenceIntegrationTest retains its production-mode properties, seeded install contract, model, absent-settings precondition, authenticated API requests and all four behavioral assertions; its redundant policy discovery, environment-store publication and AfterEach restoration are now performed by the base. No expected values, fault injection, tags, selection or conditions change. tmp/accept-evidence/diff-review.txt records preservation; the supplied cand-xml reports PlannedContext 3/3 and ProdMode 4/4 passing without skips, and candidate.log reports successful spotlessCheck and pmdIntegrationTest. These are supplied execution results, not a D4 evidence file or a new execution by this author.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Adapt the planning and production-persistence fixtures to the base fixture's owned configuration while preserving their complete existing product oracles and retiring duplicate store setup and restoration.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "cc6d91b2-5314-42fd-867b-dbc2d8a68699",
  "verdict": "accept",
  "digest": "sha256:19b3256fa66225b595ae83e164a099b3ad2026bd6833e76f75d37a09e980fe56"
}
```
