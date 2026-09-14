---
title: Configuration & SSOT
type: explanation
status: stable
description: 'The `EnvRegistry` and "Gatekeeper" pattern.'
---

# Configuration & SSOT

JustSearch enforces a strict configuration philosophy designed to prevent "it works on my machine" bugs and hidden dependencies.

## The "Golden Rule" (Inversion of Control)
**Rule:** Low-level modules (Worker, Lucene adapter, AI backend/runtime helpers) are "dumb."
*   They **never** scan the filesystem for configuration files.
*   They **should not** read `System.getenv()` / `System.getProperty()` (or mutate sysprops) directly — use `EnvRegistry` and the SSOT helpers in `modules/configuration` (`PlatformPaths`, `RepoRootLocator`, and `SystemAccess`), or pass constructor-injected config objects.

> Note (current reality): GPU/VRAM threshold resolution is now `EnvRegistry`-backed through `modules/gpu-bridge` and runtime forwarding. Remaining direct global reads are mostly legacy/edge platform or native-runtime probes. Treat these as explicit boundary exceptions or follow-up tech debt; the desired end-state is still “`modules/configuration` helpers or injected config only.”

Instead, they must be **given** their configuration object (POJO) via their constructor. This allows for easy testing by passing mock configs.

## The SSOT (Single Source of Truth)
Configuration is defined in the `SSOT/` directory at the root of the repository.
*   **Purpose:** Defines the shape of the data, the fields we index, and the default settings.
*   **Format:** JSON (parsed by Jackson).

### Field Catalog (`SSOT/catalogs/fields.v1.json`)
This file defines every field in our search index.
*   **Validation:** `FieldMapper` uses this to validate incoming documents.
*   **Properties (typical):**
    * `type` (`text`, `keyword`, `long`, `boolean`, `vector`)
    * `stored` (whether the original value is retrievable via stored fields)
    * `docValues` (required for sorting/faceting and fast filters)
    * `roles` (e.g., `filter`, `facet`, `highlight`, `sort`)
    * analyzer hints for text fields (e.g., `icu`)
    * vector spec (dimension) for the embedding field
*   **Legacy Protection:** Code that tries to index "ad-hoc" fields not in the catalog will trigger a warning or failure.

### Implicit Bounds from SSOT

SSOT configurations provide implicit bounds on runtime data structures:

- **Analyzer cache:** Limited to entries defined in `analyzers.v1.json` (4 analyzers: `content_all`, `content_en`, `content_de`, `keyword`).
- **Field definitions:** Limited to entries in `fields.v1.json`.

This means caches keyed by SSOT IDs are effectively bounded without explicit size limits. Code reviewers should not flag these caches as "unbounded" since SSOT constraints prevent unbounded growth.

In the current UI search stack, the catalog includes canonical UX-facing metadata fields like:
* `mime_base` (normalized MIME without parameters)
* `file_kind` (stable UI type buckets)
* `content_preview` (small snippet source for the results list)
* `language` (DocValues-backed for filtering/faceting)

## The `EnvRegistry` Enum

The `EnvRegistry` enum in `modules/configuration` is the **canonical** place where all operator-facing configuration keys are declared (~217 entries). A separate `ConfigKey` enum holds ~50 YAML-only tuning knobs that are not overridable via environment variables or system properties (tempdoc 347). Every configuration value flows through a single ordinal chain in `ResolvedConfigBuilder`:

| Ordinal | Source | Description |
|---------|--------|-------------|
| 500 | JVM `-D` system property | Highest priority. Used by launch scripts and tests. |
| 400 | Environment variable | Used by Docker/OS. |
| 350 | CI profile | CI profile overrides. |
| 300 | `settings.json` | User preferences persisted via UI. |
| 200 | `application.yaml` | Static YAML config file. |
| 150 | Auto-detected hardware | `GpuAutoDetection.probe()` in `ort-common`. Filesystem probe for CUDA DLLs; contributes GPU config keys. |
| 100 | `EnvRegistry` default | Implementation fallback. |

Each `EnvRegistry` entry declares two operator-facing identifiers:
- **`sysProp()`** — the JVM system property name (e.g., `justsearch.index.vector.hnsw.m`), also used as the ordinal chain lookup key
- **`envVar()`** — the environment variable name (e.g., `JUSTSEARCH_INDEX_VECTOR_HNSW_M`)

Architectural tests in `ResolvedConfigBuilderTest` enforce uniqueness across identifiers and ensure every resolved key has an `EnvRegistry` entry.

**Example Usage:**

```java
// Type-safe, centralized access
int apiPort = EnvRegistry.API_PORT.getInt(33221);
int llamaPort = EnvRegistry.SERVER_PORT.getInt(8080);
int ctx = EnvRegistry.CONTEXT_SIZE.getInt(4096);
// v1 posture: CPU-first by default. GPU offload is opt-in via settings/env (gpuLayers > 0).
int gpuLayers = EnvRegistry.GPU_LAYERS.getInt(0);
Path llmModel = Path.of(EnvRegistry.LLM_MODEL_PATH.getString("Qwen_Qwen3.5-9B-Q4_K_M.gguf"));
```

## Settings preparation and storage

`UiSettingsStore` writes a schema-v4 envelope containing settings, `acceptedRevision`, and
`lastCommittedOperationKey`. Legacy raw settings and v1/v2/v3 envelopes remain readable;
versions before v3 cannot carry revision fields. A witness is either revision zero with no
key or a positive revision with a canonical UUIDv7 key. Future versions are refused.

The GPU override is one nullable field: null means automatic selection, zero explicitly
selects CPU, and a positive value selects GPU offload. Reading an older envelope migrates
zero to null because the previous resolver treated it as automatic; positive values and
revision witnesses survive. Settings reset restores automatic selection. The v2 response
preserves null versus zero; null or omission in a partial update leaves the existing value.
When GPU selection is known, rebuilding configuration recomputes the derived context window
at ordinal150. Explicit settings and operator context overrides retain their precedence.

Preparation copies the mutable settings and serializes the candidate before touching the
file. `replacePrepared` forces temporary bytes and requires atomic sibling replacement;
it never falls back to an ordinary move. This is not a guarantee against physical power
loss. Recovery notification is a separate call after replacement. `inspect` reads the
actual witness without substituting defaults for corrupt, inaccessible, or quarantined
state; a preserved corrupt sibling prevents an absent file from masquerading as a fresh
store after restart. The transitional raw `save` refuses recorded revisions and ambiguous
recovery state. The application owner must establish recovery authority before explicitly
replacing a quarantined document.

`ConfigStoreRebuilder.prepare` builds an immutable resolved snapshot without publishing it
and propagates preparation failures. `ConfigStore.swap` replaces the snapshot and returns
a change event; `notifyListeners` is separate so an owner can release its publication lock
before arbitrary callbacks run. The convenience `update` and `rebuild` APIs retain their
swap-then-notify behavior.

## Platform Paths
To ensure seamless operation across operating systems, we use `PlatformPaths` to resolve data directories.
*   **Windows:** `%LOCALAPPDATA%/JustSearch` (e.g., `C:\Users\Name\AppData\Local\JustSearch`).
*   **macOS:** `~/Library/Application Support/JustSearch`.
*   **Linux:** `~/.justsearch`.

This centralization prevents "split-brain" issues where the UI and Worker might look at different index directories.

Current hardening (important):

- `PlatformPaths` expands `${user.home}` placeholders (narrowly) and **fails fast** if any other unexpanded `${...}` placeholder reaches filesystem IO (prevents accidental literal `${user.home}` directory creation).
- In desktop/bundled runs, the shell typically sets `-Djustsearch.data.dir=...` explicitly, so the platform default is mainly relevant for dev and fallback modes.

### Data directory contract (and logging)
* **Canonical override:** `-Djustsearch.data.dir=<path>` (sysprop) or `JUSTSEARCH_DATA_DIR=<path>` (env).
* **Bootstrap ordering:** `modules/app-launcher` uses a tiny bootstrap main (`LauncherBootstrap`) to set the data-dir property **before** SLF4J/logback initialization, so log files land in the real data directory instead of the `build/...` fallback baked into the logback config.

## `JustSearchConfigurationLoader`
Configuration loading and SSOT discovery are centralized in `modules/configuration`. `JustSearchConfigurationLoader` is the primary “gatekeeper” for loading SSOT artifacts; other modules should not re-implement SSOT scanning/parsing.

It is responsible for:
1.  **Resolve SSOT root (explicit or auto):**
    - Explicit overrides: `JUSTSEARCH_SSOT_PATH` / `justsearch.ssot.path`, or `JUSTSEARCH_REPO_ROOT` / `justsearch.repo.root` (repo root; SSOT expected at `<repoRoot>/SSOT`).
    - Auto-discovery: scan the disk (upwards from CWD) to find the `SSOT/` directory.
2.  **Parse:** Read JSON files into Java POJOs (e.g., `FieldCatalogDef`).
3.  **Fallback:** If `SSOT/` is missing (Production JAR), load from Classpath resources.

Related helper (repo-root SSOT discovery):

- `RepoRootLocator` (in `modules/configuration`) centralizes repo-root discovery and is preferred for new code that needs repo-root/SSOT discovery without re-implementing traversal logic.

### Initialization Flow

There is **one** resolution, in **one** JVM (the Engine, [ADR-0049](../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md)).

1.  `HeadlessApp` loads the SSOT artifacts and builds the resolved config **once**, then publishes it with `ConfigStore.setGlobal(...)` (`modules/ui/.../HeadlessApp.java:653`). `LauncherEnvironment` does the same for launcher-hosted entry points.
2.  `io.justsearch.app.engine.EngineRoot` composes the index half (`KnowledgeServer`, Lucene runtimes, job queue) in the same JVM. It reads the same `ConfigStore.global()` object — nothing is serialised, forwarded, or re-resolved.

The Head→Worker config-snapshot tier is **deleted** (lane F stage A item A19). It sat at ordinal
450, was written exactly once at boot, and existed only so a second JVM could inherit the first's
resolved values; so did the blanket `JUSTSEARCH_*` env forwarding, the declared `-D` forwarding set,
and the post-handshake divergence detector whose whole job was to notice when those three had failed
to agree. Two processes could disagree about their configuration; one cannot.

**The surviving ordinals** (`ResolvedConfigBuilder`, higher wins):

| Ordinal | Source |
|---|---|
| 500 | `-D` JVM argument — operator override, always wins |
| 400 | environment variable |
| 350 | CI profile overrides |
| 300 | `settings.json` — user preference set via the GUI |
| 200 | YAML `application.yaml` |
| 150 | auto-detected values (GPU capabilities, platform paths) |
| 100 | programmatic default |

There is no 450. Every resolved value carries a `ConfigResolution` trace naming the sources
considered and the winner, which is what `/api/debug/effective-config` reports.

## Settings → Effective Runtime (AI)
There are two layers that matter for “what the UI shows” vs “what is running”:

1. **User settings** (`/api/settings/v2`): persisted UI preferences + AI knobs (e.g., model paths, `gpuLayers`, context window, `maxTokens`, `pauseIndexingDuringAi`).
2. **Effective runtime config** (used by `InferenceLifecycleManager`): derived from `EnvRegistry` and system properties.

In the current app:
* **The settings→system-property promotions are gone** (tempdoc 883 decision 4 and its §C.5c residue). They predated the ordinal chain and were a precedence lie: a GUI value written as a system property resolves at ordinal 500, so `/api/debug/effective-config` reported it as `jvm_arg` and then had to read a second `*.source=ui_settings` marker sysprop to un-tell that. Every settings-borne key now reaches the resolver exactly once, at ordinal 300, via `ConfigStoreRebuilder.contributeUiSettings` — including the last two, `justsearch.index.base_path` and `justsearch.llm.model_path`, whose `/api/debug/effective-config` rows are sourced from the resolver's own provenance and read no marker.
* **One native-loader setting still becomes a system property:** `HeadlessApp` applies `UiSettings.llamaLibPath` to raw `llama.lib.path` only when blank, because the external JNI loader reads that property directly. It is outside the JustSearch resolver vocabulary. Hardware and disk discovery properties, including ORT native-path discovery, are separate from settings promotion.
* **Installed ONNX paths use the accepted settings owner.** Each acquisition stage commits its changed eligible paths together, publishes ConfigStore once, and leaves unchanged or pending paths alone. All five model configuration readers consume that snapshot. Reranker/citation status also reads the resolved paths while retaining independent observed session state. The five installer-written ONNX model-path system properties are retired; operator properties retain their normal precedence. ORT native discovery keeps its direct reader fallback and no longer republishes settings/configuration.
* **Runtime executable selection uses the same precedence chain.** Boot CUDA discovery is remembered at ordinal150; installer selection and explicit activation persist at300 through the accepted settings owner. Activation refuses an environment/JVM winner at400/500. CPU deactivation persists the baseline executable so remembered CUDA discovery cannot undo it. The server-executable ownership marker and all application-written server executable JVM properties are retired. The owner publishes ConfigStore before inference applies; failed inference restores its previous runtime configuration, and a separate accepted settings compensation compares the exact activation witness so it cannot erase a newer mutation.
* **`justsearch.llm.model_path.source` now has no writer at all.** The boot promotion went with 883 §C.5c and the installer/pack-import promotions with it, so a chat-model path reaches the resolver once, at ordinal 300. The constant survives only for tempdoc 842's unshipped profile-persistence writer; an absent marker correctly means "operator".
* **`justsearch.context.size` no longer has a promotion or a `.source` marker.** The window is derived and contributed at ordinal 150 (`auto_detected` / `hardware_probe`); a user override rides `settings.json` at 300; an operator `-D` / env var still wins at 500 / 400 - by the chain, not by a sysprop write. See `05-ai-architecture.md`, section "The context window".
* **`justsearch.gpu.layers`, `justsearch.server.exe` and `justsearch.ui.exclude_patterns` no longer have promotions or `.source` markers either** (883 decision 4 slice 2). All three ride `settings.json` at 300. Two consequences worth knowing: the VRAM-tier GPU auto-populate contributes `justsearch.gpu.layers=99` to the ordinal-150 probe map ONLY — mirroring it to a sysprop would put a derived number above the user's own setting — and `justsearch.ui.exclude_patterns` gained a `ResolvedConfig.Ui#excludePatterns` accessor, because it was contributed at 300 but never resolved, which is why its readers had to use the promoted sysprop.
* **Profile activation:** the named model/projector pair, selected executable, context and GPU layers are submitted in one inference apply. Runtime profile switches do not mutate the bootstrap `justsearch.chat.profile` operator property or persist a resolved model path. The running profile remains available through the realized identity observation.
* **Runtime propagation:** `SettingsController.handleUpdateSettingsV2()` rebuilds the `ConfigStore` from the saved settings, which is the whole propagation path — the `maybeApply*SysProp` promotions it used to run first are deleted. Note that **inference restart is required** for GPU layer changes to take effect on the running `llama-server` process.
* **Attribution:** `/api/debug/effective-config` reports the resolver's ordinal chain. Installer paths resolve as settings at300; explicit operator properties remain at500. The external `llama.lib.path` loader property has no settings-copy marker.
* `InferenceConfig.fromEnvironment(...)` reads from `EnvRegistry` (`LLM_MODEL_PATH`, `GPU_LAYERS`, `CONTEXT_SIZE`, etc).
* `InferenceLifecycleManager` also reads `llama-server`'s `GET /props` to show the **effective** `model_alias` and `n_ctx` when available (surface via `/api/inference/status`).
* `POST /api/inference/reload` re-applies persisted settings to the inference runtime (`RESTART_IF_ONLINE`): it updates the stored `InferenceConfig` always, but restarts `llama-server` only when currently Online; if the runtime has adopted an external `llama-server` instance (no process handle), restart is rejected (use `POST /api/inference/detach` to switch to a managed server on a new port).
* External server adoption is verified via `GET /props` by default (to avoid adopting unrelated HTTP services); for dev-only workflows you can set `-Djustsearch.inference.external.allow_health_only_adoption=true` to fall back to health-only adoption.
* Policy enforcement happens at spawn time: even if the stored config requests `gpuLayers > 0`, `InferenceLifecycleManager` forces `-ngl 0` when `policy.gpu_acceleration_enabled=false`.
* External server adoption can be blocked entirely with `-Djustsearch.policy.disallowExternalInferenceServers=true`. When set, the adoption probe is skipped even if a compatible server is detected on the configured port. Takes precedence over `allow_health_only_adoption`.
* Request-time budgets (e.g., `maxTokens` for summarize/Q&A/chat) are read from persisted settings and passed as `max_tokens` per request (so changes take effect without a full restart).

### UI settings v2 (UX-facing fields)
The canonical contract for user preferences is `GET/POST /api/settings/v2` with `ui` and `llm` sections.

New UX-facing fields introduced for market-readiness:

- `ui.mode`: `"simple"` | `"advanced"`\n+  Progressive disclosure mode. Default: `"simple"`.
- `ui.hasSeenTrustLoopNudge`: `boolean`\n+  One-time “trust loop” teaching moment for citations (dismissal is persisted).
- `ui.excludePatterns`: `string[]`\n+  Glob patterns used to exclude files from indexing/search.\n+  v1 posture: exclusion cleanup is deterministic via an explicit UI action ("Apply excludes"), not background auto-cleanup.

#### Exclude patterns — behavior details

**Bare pattern normalization** (`.gitignore`-like convenience): user input is expanded before matching so that average users don't need to know glob syntax. Rules applied in `ExcludeGlobs.fromPatterns()` and `ExcludeMatcher.fromPatterns()`:

| User types | Expanded to | Semantics |
|------------|-------------|-----------|
| `dist` | `**/dist/**` + `**/dist` | Directory contents + literal file, at any depth |
| `dist/` | `**/dist/**` | Directory contents at any depth |
| `*.log` | `**/*.log` | Glob without slash → any depth |
| `**/node_modules/**` | unchanged | Already qualified |
| `dist/**` | unchanged | Has slash → anchored to root |

**Two enforcement layers:**
1. **Cleanup** (`POST /api/indexing/excludes/apply`): walks watched roots, deletes already-indexed docs matching patterns. Directory patterns use `deleteDocsByPathPrefix` optimization; file patterns use `deleteDocById`. Supports `?dryRun=true` for per-pattern match preview without deletion.
2. **Live prevention**: file watcher event handler (`KnowledgeClient`) filters excluded paths before submitting to the Worker. Filtered at the event handler, not at the watcher source (library limitation).

**Worker-side hardcoded skip lists** provide a baseline independent of user-configured patterns — see `docs/explanation/03-knowledge-server.md` § "File skip lists."

**Dual implementation:** `ExcludeGlobs` (in `modules/ui`, used by cleanup action) and `ExcludeMatcher` (in `modules/app-services`, used by watcher event filtering) have identical `expandBarePattern()` and `globToRegex()` logic. They live in different Gradle modules. Any normalization fix must be applied to both.

## Frontend build-time config (ui-web)
The Lit UI supports dev-time overrides for API discovery:
* `VITE_JUSTSEARCH_API_PORT` (preferred; used by `resolveApiEndpoint()` in `modules/ui-web`)
* `VITE_API_PORT` (legacy alias supported by scripts/proxy)
* Runtime override: `?api_port=<port>` (wins over env)

## Index + migration-related runtime config (current)

These are the key runtime knobs that affect schema compatibility and migration behavior:

- **Index root override**:
  - `JUSTSEARCH_INDEX_BASE_PATH` / `-Djustsearch.index.base_path=<path>`
  - Default is derived from `<dataDir>/index/<collection>` (collection defaults to `default`)
- **Schema mismatch policy** (distinct from corruption auto-recovery):
  - YAML: `index.schema_mismatch.policy`
  - Env/sysprop: `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` / `index.schema_mismatch.policy`
  - Values: `FAIL_CLOSED` | `REBUILD_BACKUP_FIRST` | `BLUE_GREEN_MIGRATE`
- **Parity guard allow-mismatch (operator escape hatch)**:
  - `-Djustsearch.index.parity.allow_mismatch=true` — not set by default anywhere (tempdoc 915 removed
    the Head's unconditional set-sites); an operator sets this explicitly to open a known-divergent
    index read-only for diagnosis.
- **Cutover guardrail (optional)**:
  - `JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS` /
    `-Dindex.migration.cutover.max_failed_jobs=<n>`
  - Default `-1` (do not block auto-cutover based on failed jobs count)
