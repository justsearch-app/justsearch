# Audit 4 — Blockers to N Engines against one shared inference host (read-only, lane-F-A HEAD 4229f1091)

Produced 2026-09-10 by an opus subagent under a read-only brief. Paths relative to the worktree root. Every `file:line` is at `4229f1091`.

**Headline.** The "one dev stack per machine" limit is not a port limit, not a data-directory limit, and not an Engine-process limit. What blocks it: (a) a deliberately machine-shared lease file, (b) GPU/VRAM with no CPU fallback, (c) two write-side races in the shared models directory.

## 1. The single-stack constraint today

**Lease.** `dev-runner.cjs:53-62` resolves `stateRoot` to `mainRepoRoot/tmp/dev-runner` ("so all worktrees coordinate on one lease"). `active.json` written at `cmdStart` (`:2382-2407`). Default 30 s, clamped `[30, 7200]` (`:88-96`; `test-dev-runner-lease-duration.mjs:43-103`). Renewer every ~10 s (`:2484-2557`). Verdicts from `computeOwnershipVerdict` (`scripts/dev/lib/ownership-verdict.cjs:112-296`): `NO_OWNER`, `USE`, `RECLAIM_DEAD`, `WAIT_CRITICAL_OP`, `REQUIRES_CONFIRMATION`, `TAKEOVER_ABANDONED`, `IDLE_HOLD`, `CONTENTION`. Presence grading (`:82-93`): abandoned 5 min, idle 15 min; unknown = active. `takeover: deny|warn|force` (`:186-260`). `acquire_when_free` (`server.mjs:2526+`).

**Second-start refusal** `acquireAdmission()` (`dev-runner.cjs:1703-1815`): exclusive-create `active.lock.json` (`:1706-1735`) → lease `expiresAt` + `runnerPid` (`:1737-1751`) → `op-leases.json` (`:1684-1694`) → owner activity (`:1755`). **Not keyed on port-in-use.**

**`quick_health` foreign runs** `probeForeignRuns` (`process-record.cjs:343-465`; `server.mjs:2389`): reads `tmp/dev-runner/foreign/*.json`, cross-checks pid and `ports.api`; probes `FOREIGN_BACKEND_PORTS = [33221]` (`:237`); `null` = did not probe, `[]` = none.

**Exclusive by physics:** GPU/VRAM — `resolveCuda12ServerExe` (`:521-528`) resolves only cuda12, no CPU baseline (`test-dev-runner-runtime-resolution.mjs` names a "9B-on-CPU cross-worktree DOS"); one physical GPU's serialized execution (`NativeSessionHandle.java:116` per session); host RAM under the chat model (`handoff.md:544-546`); a given index directory — Lucene `write.lock` (`RuntimeSession.java:1012`), `AppInstanceLock` `<dataDir>/app.lock` (`AppInstanceLock.java:56-61`, `HeadlessApp.java:978`), `IndexRootLock` (`IndexRootLock.java:33-47`) — all per-dir.

**Exclusive by convention only:** API port `apiPort: 0` (`dev-runner.cjs:164`, `:2086`; `ResolvedConfigBuilder.java:921-926`; discovery via manifest `:2247-2251`); Vite `uiPort: 5173` `--strictPort` (`:163`, `:2148`); JDWP scans 5005–5024 (`:1279-1285`); llama port default 0 (`ResolvedConfigBuilder.java:924`); jseval 33221 hard-coded (`backend.py:22`); data dir default `<repoRoot>/modules/ui-web/.dev-data` per worktree (`:302-313`, `:747`, `:2087-2088`; platform default `PlatformPaths.java:67-92`); the single-stack invariant is a shared-state-root property — `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` (`:54-58`) bypasses it.

**Soft gap:** `cmdStop`'s port-owner fallback (`:3140-3161`) `taskkill`s whichever PID owns the recorded port without verifying it belongs to this run.

## 2. Model and native asset resolution per worktree

`prepare-worktree.cjs` seeds config (`:76-77`) and runs `npm ci` + `installDist` (`:96-114`); no model linking. At start: `ensureSharedCuda12Staged()` (`:496-512`) copies into `mainRepoRoot/modules/ui/native-bin/llama-server/`; `resolveCuda12ServerExe()` (`:521-528`); `JUSTSEARCH_MODELS_DIR = mainRepoRoot/models` (`:540-546`), plain env var. Java resolution `OnnxModelDiscovery.java:73-120` over `ResolvedPathResolver.resolveModelRoots` (`:47-80`): override → `justsearch.models.dir` → `<dataDir>/models` → `<repoRoot>/models` → `<baseDir>/models` (`:90-106`) → dev fallback (`:110-116`).

| Asset | Scope | Verdict |
|---|---|---|
| shared cuda12 exe | main checkout | SAFE (read-only) |
| model files | main checkout | SAFE |
| ORT CUDA-EP JAR extraction `%TEMP%/onnxruntime-java<random>/` | per-process | SAFE |
| **`OrtCudaHelper.copyCudaDllsToOrtTempDir`** (`:464-522`) picks the most-recently-modified `onnxruntime-java*` dir (`:474-489`) | machine-shared `%TEMP%` | **COLLIDES** — `:453-457` "assumes a single ORT-using JVM per machine" |
| **ONNX optimized-graph cache** (`OnnxSessionCache.java:41-43`, `:156`) sibling of the shared model file + `.opt-meta` (`:263-273`, `:332-343`) | machine-shared | **COLLIDES on cold cache** (`:217-224`, `:275-282`, `:65-108`, `:153-184`); warm is read-only |
| AOT cache `build/aot-cache/` (`build.gradle.kts:1031-1139`; `dev-runner.cjs:1987-1990`) | per-worktree | SAFE |
| Lucene index, `app.lock`, `.index.lock`, `.clean-shutdown`, crash reports | per-worktree dataDir | SAFE |

No `CreateMutex`, named pipe, or MMF singleton beyond file locks.

## 3. Memory footprint facts

**JVM flags** (`test-dev-runner-head-java-opts.mjs:45-56`, `lib.rs:784-810`, `dev-runner.cjs:739-756`): `-XX:+UseSerialGC -XX:MetaspaceSize=128m -XX:MaxDirectMemorySize=256m -XX:+UseCompactObjectHeaders -XX:-UsePerfData -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError`. Packaged-only `-Xmx2g` (`lib.rs:784`; not tuned `:760-763`). Dev: no default `-Xmx` (`dev-runner.cjs:712-718, 748`). `memory-budget.md:3-7, 11-18`: no host ORT cap, no `MaxMetaspaceSize`.

**ORT caps** (`ResolvedConfigBuilder`): BGE-M3 3072 (`:1179`), embed 6144 (`:1201`), SPLADE 4096 (`:1273`), NER 2048 (`:1293`), reranker 2048 (`:1305`), citation none. Sum 17 408 MB on 12 GB; per-session budgets not pre-allocations (`:1194`).

**Chat model — three numbers:** measured VRAM 6 206 MiB at `-c 32768`, 10 321 MiB at 262144 (register D-010); on-disk ~6.34 GB (`model-registry.v2.json:250-263`); ~11 GB system RAM (`design.md:172`, `handoff.md:545`) unmeasured; `HardwareProfile.java:23` "~7.5 GB". Unreconciled.

**Encoder-only VRAM:** 7.7 GB of 12 GB (`691-corpus-build-throughput.md:249-254`, 391 docs, old caps, LLM offline `:501-502`; embed cap raised 3072→6144 since).

**Second Engine marginal cost:** no merged-Engine RSS measurement exists (`memory-budget.md:30-32`; `stages/E.md` E0.3 `:50-54`; no `evidence/E/`). Pre-merge proxies: split Head p50 330→361 MB idle, 386→439 MB under load (`evidence/pr0/README.md:27-29`); split Worker 4.04 GB idle / 4.25 GB loaded (`:30`). Estimate: hundreds of MB to low single-digit GB, bounded by `-Xmx2g` + 256 MB + uncapped metaspace + Lucene mmap; does not pay the ~4 GB ORT delta, 7.7 GB VRAM, or 6.2–10.3 GB chat model.

## 4. Composition profiles

design.md `:1611-1616` (verbatim): profiles stores `real|ephemeral` × inference `full|encoders|none`; `full`, `bench`, `verification`; "the dev-runner runs more than one instance only once the inference host exists."

`stages/D2.md:41-47` §0.2: no composition profile exists; `EngineRoot` embedded vs process split. `:77-79` §0.6: `IndexSchema.ephemeral()` has no production caller; no verification boot-time number. `:156-162` D2-2: `EngineProfile(stores, inference)`; `:128`, `:364-365`: under 5 s first cut.

**Prior art omitted by design.md and D2.md:** `JUSTSEARCH_LITE_MODE` (`EnvRegistry.java:558-570`: skips ILM, "equivalent to `JUSTSEARCH_AI_DISABLED=true`", "single-digit seconds"); `JUSTSEARCH_AI_DISABLED` (`:346`; `InferenceDecision.java:35,63`); `JUSTSEARCH_AI_EMBED_ENABLED` (`:343`; `EmbeddingConfig.java:22, 58-59`); `IsolatedBackendFixture` (`modules/system-tests/src/integrationTest/.../IsolatedBackendFixture.java:20-63, 303-306`) spawns `HeadlessApp` with lite mode, AI disabled, temp data dir, port 0.

`AI_OFFLINE` is an `ApiErrorCode` (`:97`), not a boot flag. `chatProfile`: `ChatModelProfile.java:36-38` STANDARD / COMPACT / PADDLE_OCR_VL; dev default `compact` (`dev-runner.cjs:1917`); no `--profile` flag.

## 5. jseval and verification tiers

`--start-backend` (`run.py:47`) → `start_backend` (`backend.py:116-261`) → `_boot_and_wait` (`:264-339`) spawns `gradlew :modules:ui:runHeadlessEval` (`:281-304`). `_DEFAULT_PORT = 33221` (`:22`), no CLI override. Data dir `<repo_root>/tmp/headless-eval-data` (`:183-194`, `_paths.py:131`); models from main checkout (`:217-226`, `_paths.py:99-116`); `--clean` wipes whole dir (`:201-209`). `llm=True` fails closed (`:169-170`, `:70-89`). `run_register.py:1-37` records the 2026-08-14 invisible-backend incident; registers at spawn (`backend.py:306-319`) into `tmp/dev-runner/foreign/jseval-<pid>.json`; `gpuBound: "unverified"`.

Two jseval runs cannot coexist as shipped: same port, same data dir, `AppInstanceLock`, GPU. `scripts/jseval/lane-f/` instruments hard-code 33221 and `cleanup --active --force` (`fixture-pair.sh:27, 119-134`; `head-flag-run.sh:16, 77`; `encoder-latency-probe.sh:12`; `fixture-cycle.sh:30`); `head-rss-sampler.ps1:13-18` merges every `HeadlessApp` java process.

## 6. Boot timing

`KnowledgeServer.startDeferredModelInitialization` (`:1116-1124`) async on `deferred-model-init`, called at `:1072`; `initDeferredModels` (`:1374`); `modelReadyLatch` 120 s (`:2711`); in-code estimate "~15-20s" (`:2707-2710`). **No ONNX encoder warm-up inference at boot** (only `AiBackend.warmup()` no-op, llama timeouts, Lucene `SearcherManager` warmup, a reranker warm-up rerank at `KnowledgeServer.java:1576`).

Measured (`evidence/pr0/README.md:31-32`, 2026-09-07, `--skip-build`, 91 docs): startup warm HTTP 2.56 s, worker ready 7.48 s; restart HTTP 2.62 s, worker ready 7.06 s; unchanged by flags (`:60`); no AOT cache measured (`:64-67`). `IsolatedBackendFixture.java:67-71`: manifest→health min 2.45 / p50 2.74 / max 7.02 s (48 samples); budget 240 s (`:86`), tail censored (`:78-85`).

**The ~40 s figure has no measurement:** sources are `server.mjs:1207, 1213, 1237` (tool description strings) and design.md quoting them (`:706`, `:414`, `:817`, `:1322`, `:1615`). "Worker ready 7.48 s" is `LIFECYCLE_STATE_READY` in `/api/health` (`head-flag-run.sh:37-52`), not encoder-ready. **The encoder-warm milestone is not measured anywhere.**

## Contradictions
1. design.md 1616 "more than one instance only once the inference host exists" — overstated.
2. design.md 706 "~40 s" — unmeasured.
3. design.md 1615 verification profile as a lane-F deliverable — largely exists via lite mode.
4. design.md 1508 host cap — vacuous today (single unfair semaphore per session).
5. `OrtCudaHelper.java:453-457` contradicts the N-Engine premise; design does not name it or the sibling cache.
6. Three unreconciled chat-model numbers.

## Could not establish
1. The encoder-warm milestone.
2. Merged-Engine RSS.
3. VRAM with encoders and llama-server co-resident.
4. Fixed cost of loading ORT natives with zero sessions.
5. Whether the cold-cache race corrupts or merely wastes.
6. The `%TEMP%` heuristic's failure rate.
7. CI health excursion tail.
8. AOT-cache boot effect.
9. Whether anything besides the shared lease refuses a second start under a state-root override.
10. Whether `worker: LIFECYCLE_STATE_READY` depends on encoder readiness.
