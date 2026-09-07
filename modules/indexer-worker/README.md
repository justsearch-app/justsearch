JustSearch Indexer Worker & AI Bridge: Architectural Safety Manual

This document defines the critical constraints, execution flows, and operational context for the indexer-worker module. It is designed to prevent architectural regressions by AI agents.

1. Critical Invariants (The Safety Rails)
-----------------------------------------

Concurrency & Ownership:
- Lucene Write Access: The Lucene IndexWriter is NOT thread-safe for concurrent commits from multiple components. It is owned exclusively by the IndexingLoop. No other service (e.g., WorkerIngestService) should attempt to write to the index directly.
- Job Queue Locking: The JobQueue uses a ReentrantLock to serialize access to the SQLite database. While SQLite supports some concurrency, this application-level lock prevents "database locked" errors during heavy contention between ingestion (writes) and polling (reads).
- Native Memory Lifecycle: The EmbeddingService manages an ONNX Runtime embedding backend. ONNX sessions hold off-heap memory that must be explicitly closed via the try-with-resources pattern or the shutdown hook to prevent memory leaks.

Hardware Isolation:
- CPU-Only Mandate: The indexer-worker MUST operate in CPU-only mode (gpuLayers = 0). The Main Process owns the GPU for interactive chat. The worker must never initialize a Llama context with GPU layers enabled, as this will cause VRAM fragmentation or Out-Of-Memory crashes in the main application.
- Port Binding: The KnowledgeServer must bind to port 0 (ephemeral). The actual port is communicated to the main process via the WorkerSignalBus (memory-mapped file). Hardcoding a port will break the multi-process architecture.

Signal Bus & Coordination (Memory Mapped File):
- File: `worker_signal.lock` (in App Data Dir)
- Layout (64 bytes):
  - [0-7]: Last User Activity (Epoch Millis, long). Main writes; Worker reads.
  - [8-15]: Main Heartbeat (Epoch Millis, long). Main writes; Worker reads.
  - [16]: Shutdown Signal (Byte, 1=Stop). Main writes; Worker reads.
  - [20-23]: Worker gRPC Port (Int). Worker writes on startup.
- Initialization Rule: The Main Process MUST overwrite bytes [20-23] with 0 immediately upon opening the MMF, *before* spawning the Worker process, to prevent stale port data.

2. Operational Context (Threads & Resources)
--------------------------------------------

Process Context:
- Isolated Worker Process: This code runs in a separate JVM process ("KnowledgeServer"), distinct from the main UI/Application process.
- Lifecycle: It is spawned by the main process and monitored via a "Sentinel Thread".
- Suicide Pact: If the main process stops updating the heartbeat in the Signal Bus (timeout > 5000ms) and the worker has been up for > 15s, the worker MUST terminate immediately to prevent "Zombie Worker" processes holding file locks.

Execution Environment:
- Main Loop: The IndexingLoop runs on a low-priority daemon thread ("indexing-loop").
- Foreground duty cycle: after each batch (and each extracted file) the loop calls `IndexingPacing.pace()`. While search-family gRPC calls are in flight, it yields enough wall time to hold indexing at `justsearch.indexing.foreground_duty_pct` (default 20%) — reported as the PAUSED state for the duration of the yield only. This keeps the UI responsive without ever stopping indexing.
- Tokenization Pool: The EmbeddingActor uses a dedicated "llama-embed-tokenize" thread pool. This separates CPU-bound tokenization/chunking from the inference thread.

Blocking Policy:
- gRPC Services: Ingest and Search services run on Netty event loops. They must NEVER perform blocking I/O (reading files, running inference) directly.
- Ingest: Offloads persistence to the JobQueue (fast SQLite write).
- Search: Delegates to LuceneIndexRuntime (fast read) or EmbeddingService (computationally expensive). Note: Current Search implementation may block; future refactoring should ensure async handling for vector generation.

3. Hot Paths & Execution Flows
------------------------------

Flow 1: File Ingestion (Batch)
Trigger: Main process sends BatchRequest via gRPC.
1. WorkerIngestService.submitBatch() receives list of paths.
2. Paths are sanitized (traversal checks, existence checks).
3. JobQueue.enqueue() acquires lock, writes PENDING rows to SQLite.
4. Returns accepted count to client immediately (Non-blocking).

Flow 2: The Indexing Pipeline
Trigger: IndexingLoop wakes up (IDLE -> RUNNING).
1. JobQueue.pollPending() -> Returns batch of file paths.
2. ContentExtractor.extract() -> Uses Tika to parse text from PDF/Docx/HTML.
   - Constraint: Tika runs in this Worker process to isolate heavy allocations from the UI process.
3. EmbeddingService.embed() ->
    a. Checks model availability (lazy init).
    b. LlamaService (Bridge) -> EmbeddingActor (Tokenize Pool) -> Tokenizes text.
    c. Splits into overlapping chunks (Sliding Window).
    d. EmbeddingActor (Inference Thread) -> Computes vectors on CPU.
    e. Returns ChunkedEmbedding (Primary Mean-Pooled Vector + Chunk Vectors).
4. LuceneIndexRuntime.index() -> Writes Document.
   - Codec: Uses `PragmaticCodec` (Int8 HNSW) to reduce vector RAM footprint by ~75%.
   - Fields: `content` (Text, BM25), `vector` (KNN Float), `doc_uid` (Keyword).
5. JobQueue.markDone() -> Updates SQLite state.
6. Commit: Occurs every 10 seconds or when buffer is full.

Flow 3: Vector Search
Trigger: User queries "contract 2024".
1. WorkerSearchService.search() receives query.
2. EmbeddingService.embed(query) -> Generates query vector (CPU).
3. LuceneIndexRuntime.search() -> Performs KNN vector search + Boolean filtering.
   - Uses Reciprocal Rank Fusion (RRF) to merge Text (BM25) and Vector results.
4. Returns SearchResponse with scored hits.

4. Developer Task Map
---------------------

- Add support for new file extension:
  Edit: extract/ContentExtractor.java
  Action: Register new parser or adjust Tika configuration.

- Change Embedding Model / Dimensions:
  Edit: SSOT/catalogs/fields.v1.json
  Action: Update the `vector.dimension` field in the catalog. The dimension is loaded
  via `JustSearchConfigurationLoader.loadFieldCatalog().vectorDimension()` and injected
  into services. Do NOT hardcode dimensions in code. See modules/configuration/README.md.

- Tune the foreground duty cycle (background throttling):
  Edit: loop/pacing/IndexingPacing.java (policy) — but prefer configuration:
  Action: Set justsearch.indexing.foreground_duty_pct / justsearch.indexing.foreground_cooldown_ms.

- Modify SQLite Schema / Queue Logic:
  Edit: queue/JobQueue.java
  Action: Update initSchema() and SQL queries.

- Add new field to Index:
  Edit: loop/IndexingLoop.java (buildDocument method)
  Action: Populate new field in Lucene Document.
  Note: Must also update SSOT schema definition in main repo.

- Debug inference/native-runtime crashes:
  Edit: modules/app-inference and modules/gpu-bridge owners first.
  Action: Check llama-server lifecycle logs, runtime mode transitions, and GPU/VRAM detection. The old in-process aibridge `NativeLlamaBinding` path is removed.

5. Verification & Diagnostics
-----------------------------

Self-Checks:
- Health Check: WorkerHealthService provides deep health status.
  Command: gRPC call to Health/Check.
  Success Criteria: Returns SERVING status, queue depth, and "isHealthy: true".
- Startup: Check logs for "KnowledgeServer started on port X".

Logs & Metrics:
- Log File: justsearch.data.dir/logs/indexer-worker.log (or worker-{date}.log).
- Console Output: DISABLED by default (via logback.xml) to prevent pipe buffer deadlocks in headless production mode.
- Success Pattern: "Indexed successfully: ... in Xms"
- Failure Pattern: "Circuit breaker tripped", "Failed to load embedding model".
- Metrics: OperationalMetrics class tracks tps (tokens per second), queue depth, and commit counts.

Chaos Testing:
- To verify durability: Kill the worker process while "PROCESSING" jobs. Restart.
- Expected: JobQueue.recoverStuckJobs() should move them back to PENDING.
