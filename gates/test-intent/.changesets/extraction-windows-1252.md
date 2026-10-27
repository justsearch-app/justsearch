---
schema: test-intent.v1
task: extraction-windows-1252
author-role: builder
author-session: uq-enc-builder
---

# Windows-1252 extraction test intent

Scope: Windows-1252 text and HTML decode without loss. Existing expectations change only for the required extraction policy identity bump.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Windows1252ExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "EN1–EN3 and EN-N1–EN-N2: exact Windows-1252 text and HTML through structured, flat, fallback, sandbox reuse/replacement and embedded routes; preserve other encodings, literal meta examples, XML and generated document/container text.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-windows-1252 request",
          "delegationScope": "Windows-1252 text and HTML decode without loss",
          "choice": "EN1–EN3 and EN-N1–EN-N2: exact Windows-1252 text and HTML through structured, flat, fallback, sandbox reuse/replacement and embedded routes; preserve other encodings, literal meta examples, XML and generated document/container text.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Windows1252ExtractionTest.java"
      ],
      "evidence": "gates/test-intent/evidence/extraction-windows-1252-repair.json",
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "EN1/EN-N3: admission, extraction and indexing preserve Windows-1252 text, title and frontmatter through reopen, below and above the snapshot threshold; the unchanged catalog retains its pinned index fingerprint.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-windows-1252 request",
          "delegationScope": "Windows-1252 text and HTML decode without loss",
          "choice": "EN1/EN-N3: admission, extraction and indexing preserve Windows-1252 text, title and frontmatter through reopen, below and above the snapshot threshold; the unchanged catalog retains its pinned index fingerprint.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java"
      ],
      "evidence": "gates/test-intent/evidence/extraction-windows-1252-repair.json",
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "EN-N3/F3/F4: restart with a durable queued job preserves unchanged legacy policy provenance and content; forced reindex publishes current-policy content that survives reopen.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-windows-1252 request",
          "delegationScope": "Windows-1252 text and HTML decode without loss",
          "choice": "EN-N3/F3/F4: restart with a durable queued job preserves unchanged legacy policy provenance and content; forced reindex publishes current-policy content that survives reopen.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java"
      ],
      "evidence": "gates/test-intent/evidence/extraction-windows-1252-preservation.json",
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "F3: bump default and configured extraction policy identities to v2 for changed decoding; update only their pinned policy expectations, leaving format text and fixture hashes unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-windows-1252 request",
          "delegationScope": "Windows-1252 text and HTML decode without loss",
          "choice": "F3: bump default and configured extraction policy identities to v2 for changed decoding; update only their pinned policy expectations, leaving format text and fixture hashes unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-windows-1252-repair.json",
      "repaired": true,
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-windows-1252 request",
          "delegationScope": "Windows-1252 text and HTML decode without loss",
          "choice": "Bump both extraction policy ids (default and configured) because the extraction behaviour changes",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions/ and docs/reference/ for tika-default-v1, tika-configured-v1 and extraction policy v1 requirements: no record requiring the v1 extraction policy id found."
    }
  ]
}
```
