---
schema: test-intent.v1
task: t-20261020-c2e24a
author-role: builder
author-session: upgrade-030-builder
---

# Authentic release fixture infrastructure and before-state characterizations

The user adopted the quoted scenarios on **2026-10-20** with **"i agree, proceed accordingly"**.
Their intended outcomes are unchanged; the two requested characterizations document the known-bad
before-state for later PRs to invert. Implementation choices use the separately quoted delegation,
not orchestrator prose relabelled as owner words.

New fixture checks were also run without the archives in a shared before clone to establish
assertion failure for the missing authentic fixture. Full scale and release-process captures have
separate small text evidence. Independent acceptance is intentionally left to the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030FixtureTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ScaleGenerator.java",
        "modules/indexer-worker/src/test/resources/upgrade-030/README.md",
        "modules/indexer-worker/src/test/resources/upgrade-030/fields.v1.json",
        "modules/indexer-worker/src/test/resources/upgrade-030/migrating.zip",
        "modules/indexer-worker/src/test/resources/upgrade-030/small.zip",
        "modules/indexer-worker/src/test/resources/upgrade-030/switching.zip"
      ],
      "class": "New",
      "behaviour": "Provide authentic release captures and portable isolated copies retaining release index identity, v13 queue fields, settings v2, real MIGRATING/SWITCHING state and acknowledged buffered operations. Expand that schema/codec on demand for U7 scale. Preserve indexed modification times for unchanged source bytes so unpacking does not cause extraction under U9.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U1",
          "quote": "A 0.3.0 data folder upgraded in place (desktop on Windows and Linux, headless): no rebuild or migration starts, the old index opens writable and stays searchable, and a file added after the upgrade is searchable within one indexing cycle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U7",
          "quote": "Jobs 0.3.0 left PENDING or PROCESSING (including a root with more than 200k indexed files and about 700k queued jobs) are not marked done unread: every never-indexed file is indexed or queued with its root after the upgrade, and an indexed file with a queued update shows the new content in search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U8",
          "quote": "A queued explicitly added file outside any watched root is kept and indexed, without granting broader ingestion authority.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U12",
          "quote": "0.3.0's failed and retry-exhausted rows, including one whose path is under no root, do not block the upgrade, and no cutover depends on them.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U18",
          "quote": "Upgrade repair: 0.3.0 rows waiting on long write-failure retries and retry-exhausted rows become claimable on the first start and get indexed; parser-timeout and I/O-failure rows keep their retry times. A crash during the repair leaves it idempotent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U9",
          "quote": "Recovering 0.3.0's queued work does not re-extract unchanged files: extraction calls for unchanged files stay near zero while new and modified files are processed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implement the authentic release fixture harness and its requested current-main characterizations; no product changes.",
          "choice": "Use a text-only Linux release capture with real release schema/segments, paused adverse queue preparation, complete path relocation using the release catalog/metadata, and synthetic on-demand scale expansion. Pin the explicitly requested known-bad current-main outcomes pending later inversion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-030-fixtures.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030FixtureTest.java#authenticFoldersRetainReleaseStateAndRelocateTogether"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java"
      ],
      "class": "New",
      "behaviour": "Owner-requested before-state characterization: current main starts a migration for the release index and retires the captured ready PENDING, PROCESSING and explicit-file jobs with MISSING_INGESTION_BOUNDARY. These are defect observations to invert in later fixes; intended U1/U7/U8 semantics remain unchanged. Hold model/config identity constant so a legitimate model change cannot satisfy the rebuild assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U1",
          "quote": "A 0.3.0 data folder upgraded in place (desktop on Windows and Linux, headless): no rebuild or migration starts, the old index opens writable and stays searchable, and a file added after the upgrade is searchable within one indexing cycle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U7",
          "quote": "Jobs 0.3.0 left PENDING or PROCESSING (including a root with more than 200k indexed files and about 700k queued jobs) are not marked done unread: every never-indexed file is indexed or queued with its root after the upgrade, and an indexed file with a queued update shows the new content in search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U8",
          "quote": "A queued explicitly added file outside any watched root is kept and indexed, without granting broader ingestion authority.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implement the authentic release fixture harness and its requested current-main characterizations; no product changes.",
          "choice": "Use a text-only Linux release capture with real release schema/segments, paused adverse queue preparation, complete path relocation using the release catalog/metadata, and synthetic on-demand scale expansion. Pin the explicitly requested known-bad current-main outcomes pending later inversion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-030-fixtures.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#issue739CurrentMainStartsMigrationForAuthenticReleaseIndex",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#issue739CurrentMainRetiresReadyLegacyJobsWithoutReadingThem"
      ]
    }
  ]
}
```

## Acceptance records
