# D1 fingerprint apply-scope audit

Read-only source audit at `6c95d7989e1e8e154afa61b69ece3b6b0205c5ab` in the
Windows `lane-f-pr1-verify` worktree. This note identifies the configuration
declarations that can change the **current** `IndexFingerprint.Inputs`; it does
not claim the D1-3 register, D1-12 model binding, or their tests are implemented.
No build or test was run for this audit. Integrated run `2298` was still owned by
the root session, so its result is not evidence here.

## Current input assembly

`SsotCommitMetadataSource.fingerprintInputs` is the sole assembly used for both
the digest and `index_fingerprint_inputs`
(`SsotCommitMetadataSource.java:103-108,180-223`). `Inputs` contains the physical
field projection, analyzer fingerprint, vector format, normalized HNSW values,
chunk/preview constants, Lucene/ICU versions, and embedding/SPLADE/NER model
fingerprints (`IndexFingerprint.java:118-119,191-224,267-320`). The Worker
installs the three process-wide model providers and effective vector-dimension
provider before the first boot fingerprint (`KnowledgeServer.java:684-708`).

Apply scope is the minimum lifecycle required to apply a declared value. Use the
agreed precedence `restart-required > generation-bound > component > hot`.
Accordingly, a restart selector remains a real fingerprint dependency but is
excluded from the exact `generation-bound` row equality.

### Direct semantic generation inputs

These eight declarations directly select a value represented in current
`Inputs`. They are the mandatory `generation-bound` rows after higher-precedence
restart selectors are excluded.

| Canonical key | Declaration and environment alias | Current input/read edge |
| --- | --- | --- |
| `justsearch.embed.backend` | `EnvRegistry.EMBED_BACKEND`; `JUSTSEARCH_EMBED_BACKEND` (`EnvRegistry.java:185-186`) | `EmbeddingFingerprint` returns no configured model unless the normalized backend is `auto` or `onnx` (`EmbeddingFingerprint.java:99-105`), changing `embedding_model_sha256`. |
| `justsearch.embed.onnx.model_path` | `EnvRegistry.EMBED_ONNX_MODEL_PATH`; `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` (`EnvRegistry.java:188-194`) | `EmbeddingConfig` reads the resolved value and discovery chooses that directory (`EmbeddingConfig.java:71-86`); the manifest-selected model file is hashed (`EmbeddingFingerprint.java:125-131`). |
| `justsearch.splade.model_path` | `EnvRegistry.SPLADE_MODEL_PATH`; `JUSTSEARCH_SPLADE_MODEL_PATH` (`EnvRegistry.java:594-598`) | `SpladeFingerprint` takes the resolved path through model discovery and hashes the manifest-selected model file (`SpladeFingerprint.java:72-98`), changing `splade_model_sha256`. |
| `justsearch.ner.model_path` | `EnvRegistry.NER_MODEL_PATH`; `JUSTSEARCH_NER_MODEL_PATH` (`EnvRegistry.java:483-487`) | `NerFingerprint` takes the resolved path through model discovery and hashes the manifest-selected model file (`NerFingerprint.java:74-103`), changing `ner_model_sha256`. |
| `justsearch.sparse_model` | `EnvRegistry.SPARSE_MODEL`; `JUSTSEARCH_SPARSE_MODEL` (`EnvRegistry.java:612-615`) | The Worker publishes dimension `1024` for `bge-m3`, otherwise no override (`KnowledgeServer.java:2198-2207`); that value replaces the catalog vector dimension in `fields` (`SsotCommitMetadataSource.java:197-203`). |
| `index.vector.hnsw.m` | `EnvRegistry.INDEX_VECTOR_HNSW_M`; `JUSTSEARCH_INDEX_VECTOR_HNSW_M` (`EnvRegistry.java:996-999`) | `resolved.index().effectiveVectorHnswM()` supplies the normalized `hnsw.m` input (`SsotCommitMetadataSource.java:203-210`). |
| `index.vector.hnsw.ef_construction` | `EnvRegistry.INDEX_VECTOR_HNSW_EF_CONSTRUCTION`; `JUSTSEARCH_INDEX_VECTOR_HNSW_EF_CONSTRUCTION` (`EnvRegistry.java:1000-1002`) | `resolved.index().effectiveVectorHnswEfConstruction()` supplies normalized `hnsw.ef_construction` (`SsotCommitMetadataSource.java:203-210`). |
| `index.vector.quantization.enabled` | `EnvRegistry.INDEX_VECTOR_QUANTIZATION_ENABLED`; `JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED` (`EnvRegistry.java:1005-1007`) | The applied boolean maps to `int8_sq` or `float32` (`SsotCommitMetadataSource.java:169-177`) and supplies `vector_format` (`:199-204`). |

`EnvRegistry.configKey()` is exactly its system-property string
(`EnvRegistry.java:1461-1469`); the environment names above are source aliases,
not additional register keys.

### Higher-precedence selectors that still feed current inputs

Model auto-discovery orders explicit `modelsDir`, `<dataDir>/models`,
`<repoRoot>/models`, then `<baseDir>/models`; the base directory orders `home`,
`dataDir`, `repoRoot`, then `user.dir`
(`ResolvedPathResolver.java:18-32,44-79`). Embedding, SPLADE, and NER fingerprints
all use that discovery path (`EmbeddingConfig.java:71-86`;
`SpladeFingerprint.java:72-86`; `NerFingerprint.java:95-104`). Catalog and analyzer
fingerprints independently locate the repository through `REPO_ROOT`, then
`SSOT_PATH`, then the working directory (`RepoRootLocator.java:29-62`;
`SsotCommitMetadataSource.java:57-72,196-203`;
`SsotAnalyzerRegistry.java:48-64,204-223`).

| Canonical key | Declaration and environment alias | Required treatment |
| --- | --- | --- |
| `justsearch.home` | `EnvRegistry.HOME`; `JUSTSEARCH_HOME` (`EnvRegistry.java:288-289`) | Current model-discovery input; `restart-required` today because discovery and model fingerprints are process-cached. Keep it in fingerprint dependency evidence, but outside generation-row equality. |
| `justsearch.data.dir` | `EnvRegistry.DATA_DIR`; `JUSTSEARCH_DATA_DIR` (`EnvRegistry.java:44-45`) | Current model-discovery input and already explicitly restart-required by D1-3 (`D1.md:263-265`). |
| `justsearch.models.dir` | `EnvRegistry.MODELS_DIR`; `JUSTSEARCH_MODELS_DIR` (`EnvRegistry.java:300-301`) | Current model-discovery input; `restart-required` today for the same process-cached ownership. |
| `justsearch.repo.root` | `EnvRegistry.REPO_ROOT`; `JUSTSEARCH_REPO_ROOT` (`EnvRegistry.java:283-284`) | Selects model roots and the SSOT files used for field/analyzer inputs; `restart-required` today. |
| `justsearch.ssot.path` | `EnvRegistry.SSOT_PATH`; `JUSTSEARCH_SSOT_PATH` (`EnvRegistry.java:50-51`) | Can select the SSOT repository used for physical-field/analyzer inputs; `restart-required` today. |

The fallback `user.dir`, actual SSOT/catalog bytes, actual model bytes, chunking
and preview constants, and Lucene/ICU versions are real non-configuration inputs.
They require no config-register row. `justsearch.config` is only a source selector:
the resolved semantic values above are hashed, so the config-file path itself is
not a generation input.

## Current compatibility gaps

These are not members of the exact current feed set and must not be used as
false proof that current `Inputs` already identifies all persisted output.

1. **The configured field catalog can differ from the catalog fingerprinted.**
   `KnowledgeServer` builds writable and read-only runtimes from
   `JustSearchConfigurationLoader.loadFieldCatalog()`
   (`KnowledgeServer.java:2151-2175,2223-2233`), whose first choice is
   `EnvRegistry.FIELD_CATALOG` (`JustSearchConfigurationLoader.java:100-133`).
   `SsotCommitMetadataSource` instead always projects
   `<repoRoot>/SSOT/catalogs/fields.v1.json` (`:62-63,196-203`). Therefore
   `justsearch.fieldCatalog` / `EnvRegistry.FIELD_CATALOG` /
   `JUSTSEARCH_FIELD_CATALOG` (`EnvRegistry.java:53-54`) can change physical
   fields without changing `Inputs`. D1 must either pass the actual applied
   `FieldCatalogDef` into fingerprint assembly or retire/forbid this override;
   labeling the key alone cannot repair parity.
2. **Enabled-role selection is absent.** `justsearch.ai.embed.enabled`,
   `justsearch.splade.enabled`, and `justsearch.ner.enabled` decide whether the
   corresponding assemblies are requested (`InferenceCompositionRoot.java:278-282,
   339-343,430-434`), while the fingerprint providers hash discoverable models
   without encoding enabled state (`EmbeddingFingerprint.java:99-130`;
   `SpladeFingerprint.java:72-98`; `NerFingerprint.java:74-103`). Changing role
   selection can therefore change persisted output without changing generation
   identity. This belongs with D1-12's per-generation `ModelIdentity`, not in the
   test for what current `Inputs` already contains.
3. **BGE-M3 identity is incomplete by accepted design.** `SPARSE_MODEL` changes
   the vector dimension, but current `MODEL_INPUT_KEYS` contains only embedding,
   SPLADE, and NER (`IndexFingerprint.java:118-119`).
   `justsearch.bgem3.enabled` and `justsearch.bgem3.model_path` have no model
   digest input, and SPLADE may still be fingerprinted even when BGE-M3 is the
   selected sparse encoder. D1 records this as a D1-12 model-binding gap
   (`D1.md:540-576,735-738`); do not describe it as current D1-3 proof.
4. Encoder behavior controls that alter persisted representations, including
   SPLADE max sequence length/activation and NER max sequence length/confidence,
   are not fields of current `Inputs`. Their final generation classification
   must follow D1-12's representation contract; the eight-key current-input set
   above must not be broadened by assertion before the fingerprint shape changes.

`index.boosts` is a separate boundary. `ConfigKey.INDEX_BOOSTS`
(`ConfigKey.java:63`) feeds `boosts_fp` commit metadata
(`SsotCommitMetadataSource.java:124-132,364-372`), but it is not an
`IndexFingerprint.Inputs` member. Treat it as an `index` component dependency
with its actual captured boosts map, not as generation-bound.

## Register/test decisions

- Amend D1-3's literal equality so the semantic generation-row set is the eight
  direct keys above, while a second assertion accounts for the five
  higher-precedence restart selectors as fingerprint dependencies.
- Keep dependency membership distinct from `applyScope`; shared/root selectors
  remain declared dependencies even when their one register row says
  `restart-required`.
- Add negative witnesses for one omitted direct key and one incorrectly promoted
  restart selector. A declaration-count assertion alone cannot establish the
  lifecycle meaning.
- Do not claim generation compatibility until the field-catalog mismatch and the
  D1-12 role/model-identity gaps are closed with tests using different applied
  artifacts in one process. Current process-wide fingerprint caches
  (`EmbeddingFingerprint.java:80-96`; `SpladeFingerprint.java:66-99`;
  `NerFingerprint.java:68-92`) cannot provide that proof.
