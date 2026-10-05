---
description: "TRIGGER when: editing SSOT/catalogs/ files, modifying fields.v1.json, adding schema fields, or editing adapters-lucene/src/main/resources/SSOT/catalogs/. Loads the dual-copy sync requirement and field addition checklist."
user-invocable: true
---

# SSOT Catalog Modification

## Critical: Dual-Copy Sync

Two copies of SSOT catalogs exist and MUST be kept in sync:

| Copy | Path | Used by |
|------|------|---------|
| Root (canonical) | `SSOT/catalogs/fields.v1.json` | Development, tests |
| Classpath (packaged) | `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` | Production (packaged deployments) |

**If you only update the root copy, the field is silently dropped in packaged deployments.**

## Adding a New Field

1. Add to **both** `fields.v1.json` copies (root AND classpath)
2. Add to `SchemaFields.java` constants
3. Add to `INDEXABLE_FIELDS` set if the field should be indexed
4. Update `IndexingDocumentOps.buildDocument()` with extraction/population logic
5. **Re-index existing data** — existing indices do NOT have the new field:
   - Eval mode: `jseval run --reset` or `--start-backend --clean`
   - Dev mode: `jseval run --start-backend --clean`
6. **Regenerate the repro manifest:** `./gradlew.bat :modules:ssot-tools:regenSsotManifest`
   regenerates `SSOT/manifests/repro/repro.v1.json` so the canonical-JSON SHA-256s of
   each catalog stay in sync. The repro hash is read at Worker startup and recorded as
   build-trace metadata; if you skip this step, the worktree carries a stale hash for
   the catalog you just edited (no test fails, but `WorkerConfig.manifestHash` lies).
7. Verify pinned-hash tests: `./gradlew.bat :modules:ssot-tools:test`
8. Verify commit metadata: `./gradlew.bat :modules:adapters-lucene:test`

## Field Roles

Fields in `fields.v1.json` have roles that determine indexing behavior:

| Role | Effect |
|------|--------|
| `content` | Full-text searchable |
| `filter` | Keyword exact-match / facet / range query |
| `stored` | Returned in search results |
| `sort` | Sortable (DocValues) |

## Naming Conventions

- `entity_*_raw` — multi-valued keyword entity fields (filter/facet and NER-membership evidence)
- Analyzed `entity_*_text` duplicates are retired; do not reintroduce them for BM25 scoring.
- `meta_*` — structured metadata fields (lowercased at index+query time)
- `chunk_*` — chunk-level fields (on child documents only)
- `chunk_content` is indexed but not stored; its only valid RMW policy is `rederive-parent-slice`.
