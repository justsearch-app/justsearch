---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: 01a1919b-c926-7f71-960d-673e44a8413b
---

# Test-intent entries

Preservation and declaration-generation work for frontend structure step 5. Independent acceptance remains with the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/api/registry/SurfaceCatalogPreservation.test.ts",
        "modules/ui-web/src/api/registry/__fixtures__/core-surfaces.before.json",
        "modules/ui-web/src/api/registry/__snapshots__/SurfaceCatalogPreservation.test.ts.snap",
        "modules/ui-web/src/shell-v0/views/unifiedThreadClient.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve ordered catalog fields and their absence over online, cached 304, cached network/HTTP/rejected-body failure, cold failure and recovery paths through PluginRegistry; preserve seven-kind strict validation, every SEARCH attribute, unknown-kind tolerance and incomplete signaling. Captured at bc827e04b0 before production edits; snapshots characterize existing cold/recovery/consumption gaps and do not ratify them as intended behavior. Faults in contributed mount tags fail all seven snapshots; relaxed known-event and SEARCH validation and non-string-kind admission fail the added parsing cases.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261029-fs993c request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-31",
          "delegationScope": "Step 5 of the agreed frontend structure design: single-source duplicated declarations; nothing the user sees changes",
          "choice": "Generate the six shared surface fields and thread vocabulary from Java while preserving the existing contribution selection, catalog snapshots, parser strictness and static gate predicates.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-single-source.json",
      "newChecks": [
        "modules/ui-web/src/api/registry/SurfaceCatalogPreservation.test.ts",
        "modules/ui-web/src/shell-v0/views/unifiedThreadClient.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfacesTsGenerationTest.java"
      ],
      "class": "New",
      "behaviour": "The explicit six-field projection of all Java surface definitions is deterministic, escaped and byte-compared in ordinary mode; missing/edited output and changed Java metadata fail, and only explicit update mode writes it. Membership, consumption, provenance, altitude, risk and state schema stay out of plugin contributions.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261029-fs993c request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-31",
          "delegationScope": "Step 5 of the agreed frontend structure design: single-source duplicated declarations; nothing the user sees changes",
          "choice": "Generate the six shared surface fields and thread vocabulary from Java while preserving the existing contribution selection, catalog snapshots, parser strictness and static gate predicates.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-single-source.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfacesTsGenerationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-agent-api/src/test/java/io/justsearch/agent/api/registry/RegistryEnumsTsGenerationTest.java",
        "modules/app-agent-api/build.gradle.kts",
        "modules/app-observability/build.gradle.kts"
      ],
      "class": "Structural rule kept",
      "rule": "Ordinary module tests compare generated artifacts with the live Java authorities; writing requires explicit update mode. Existing tests and comparison predicates remain selected.",
      "whySurvives": "The enum emitter retains its previous output and extends it with InteractionEventKind as a runtime tuple and derived type. Observability emits where CoreSurfaceCatalog is already available and its existing updateSchemas selection also includes the new emitter. Both comparison tasks track the external committed artifact as an input; update tasks track generated outputs, so edits or missing files invalidate cached comparisons. The required CI observability test invocation executes the emitter.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261029-fs993c request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-31",
          "delegationScope": "Step 5 of the agreed frontend structure design: single-source duplicated declarations; nothing the user sees changes",
          "choice": "Generate the six shared surface fields and thread vocabulary from Java while preserving the existing contribution selection, catalog snapshots, parser strictness and static gate predicates.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Resolve the capability owner mount tag against createCorePluginManifest().capabilities.surfaces instead of parsing duplicate ID/mount-tag literals from CorePlugin source. Security source still establishes unlockEncryption ownership, Settings still lacks it, and the remedy target assertion is unchanged. A wrong generated Security mount tag fails the ownership assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261029-fs993c request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-31",
          "delegationScope": "Step 5 of the agreed frontend structure design: single-source duplicated declarations; nothing the user sees changes",
          "choice": "Generate the six shared surface fields and thread vocabulary from Java while preserving the existing contribution selection, catalog snapshots, parser strictness and static gate predicates.",
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
  "role": "verifier",
  "session": "01a191bd-5e1d-7ce2-b24e-f2604bcd0436",
  "verdict": "accept",
  "digest": "sha256:a1fc1c0417ee65f72cfc0a2d0c0a147b4ada025955a9cc68b77ca518155af36b"
}
```
