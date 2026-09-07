/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;
import io.justsearch.ipc.CircuitBreakerOpenException;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;

import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SuggestResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.core.search.SearchPort;
import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.app.api.IndexingService;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Head's client for the Knowledge Server, independent of how the call is carried.
 *
 * <p>Provides methods across multiple service groups:
 * <ul>
 *   <li>Search, suggest, and document fetch (via SearchService)</li>
 *   <li>RAG context retrieval and citation matching</li>
 *   <li>Batch file submission and index status (via IngestService)</li>
 *   <li>Document deletion by ID or path prefix</li>
 *   <li>Health checks and worker status (via HealthService)</li>
 *   <li>Watched root management and persistence</li>
 *   <li>File watcher bootstrap and periodic sync control</li>
 *   <li>Schema migration orchestration</li>
 *   <li>AI/VDU operations (embedding queue, VDU processing)</li>
 *   <li>Index maintenance (GC, flush)</li>
 * </ul>
 *
 * <p><b>Lane F stage A item A6 — why this class is abstract.</b> Until A6 this file was
 * {@code KnowledgeClient}, a single class that was both the Head-side facade (watched-root
 * state and its persistence, the exclude matcher, the walk executor, the ops layer, the
 * request/response mapping to {@code app-api} records) and the gRPC transport (a
 * {@code ManagedChannel}, four stubs, a circuit breaker, a retry service config, port
 * re-discovery over the MMF bus). None of the first list is about a network. A6 splits the two:
 * everything that is a property of the <em>work</em> stays here, and the three call seams below
 * are what a transport supplies.
 *
 * <p>Two implementations exist during the stage-A transition:
 * <ul>
 *   <li>{@link KnowledgeClient} — the gRPC one, no longer constructed on the live path from
 *       A6 and deleted at A10;
 *   <li>{@code io.justsearch.app.engine.EngineKnowledgeClient} — the in-process one, which calls
 *       the converted worker services directly.
 * </ul>
 *
 * <p><b>The four operation contracts design §6 requires to survive the channel</b> are the four
 * things this class still carries, and each has a named owner here rather than in a transport:
 * the deadline categories ({@link RpcDeadlineCategory}, applied per call through the executor
 * seam), the {@code FetchDocuments} result-size bound ({@link BoundedDocumentFetch}), streaming
 * flow control ({@link #scanRoot} and {@link #subscribeIndexingJobs}, whose bounds live in the
 * implementations), and cancellation ({@link CancelToken}). Retries, the circuit breaker, port
 * re-discovery, channel management and {@code IpcTelemetry} are properties of the channel and
 * die with it.
 */
public abstract class KnowledgeClient implements Closeable, SearchPort, IndexingService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);

    /**
     * Deadline categories for gRPC operations.
     *
     * <p>Centralizes the scattered deadline configurations:
     * <ul>
     *   <li>23 methods use base deadlineMs (STANDARD)</li>
     *   <li>5 methods use deadlineMs * 2 (CONTENT_FETCH, VDU_OPERATION)</li>
     *   <li>1 method uses 30s (INDEX_GC)</li>
     *   <li>2 methods use 300s (LONG_RUNNING)</li>
     * </ul>
     *
     * <p>Each category has a multiplier applied to the base deadline.
     */
    public enum RpcDeadlineCategory {
        /** Standard operations: search, health, basic status */
        STANDARD(1.0),
        /** Content-heavy operations: fetch documents, RAG context */
        CONTENT_FETCH(2.0),
        /** VDU updates and recovery */
        VDU_OPERATION(2.0),
        /** 360: Cross-encoder reranking — 20 docs × 2048 seq on CPU takes ~42s */
        RERANK(12.0),         // 60s at 5s base = 12x
        /** Index garbage collection */
        INDEX_GC(6.0),        // 30s at 5s base = 6x
        /** Sync/prune operations (large indexes) */
        LONG_RUNNING(60.0);   // 300s at 5s base = 60x

        private final double multiplier;

        RpcDeadlineCategory(double multiplier) {
            this.multiplier = multiplier;
        }

        public long apply(long baseDeadlineMs) {
            return (long) (baseDeadlineMs * multiplier);
        }
    }

    private final long deadlineMs;
    private final IpcTelemetry telemetry;
    private final IngestRpcExecutor ingestRpcExecutor;
    private final MigrationOps migrationOps;
    private final VduOps vduOps;
    private final SyncOps syncOps;
    private final SearchRpcOps searchRpcOps;
    private final RootLifecycleOps rootLifecycleOps;
    private final ExecutorService walkExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Persistent root tracking - survives restarts via JSON file
    /**
     * Maps path -> lastIndexed timestamp.
     *
     * <p>IMPORTANT: values must be non-null (ConcurrentHashMap rejects null). We use
     * {@link WatchedRootsStore#NEVER_INDEXED} as a sentinel for "tracked but never indexed".
     */
    private final Map<Path, Instant> watchedRoots = new java.util.concurrent.ConcurrentHashMap<>();
    private final WatchedRootsStore rootsStore;
    private final WatchedRootsState watchedRootsState;

    // Last-known-good ONNX model status from Worker's health check response (D-4, tempdoc 215).
    // Updated on each successful getHealthCheck() call; returns empty list until first success.
    private final AtomicReference<List<OnnxModelStatus>> onnxModelsCache =
        new AtomicReference<>(List.of());

    // Last-known WorkerOperationalView, cached as a side-effect of getWorkerOperationalView().
    // Used by KnowledgeHttpApiAdapter to include index capabilities in search responses
    // without making a per-search gRPC call (250 Phase 3).
    private final AtomicReference<io.justsearch.app.api.status.WorkerOperationalView>
        cachedOperationalView = new AtomicReference<>(null);

    // Exclude patterns as a raw JSON string array, read from the RESOLVED config (tempdoc 883
    // decision 4 slice 2 — previously the sysprop the settings promotion mirrored them into).
    // Shared via this::getExcludeMatcher supplier with RootLifecycleOps.
    private final Object excludeLock = new Object();
    private volatile String excludeRawCache = null;
    private volatile ExcludeMatcher excludeCache = ExcludeMatcher.empty();

    /** Default batch size used when not specified. */
    static final int DEFAULT_BATCH_SIZE = 5000;

    /** Maximum batch size allowed (must match Worker's MAX_BATCH_SIZE). */
    static final int MAX_BATCH_SIZE = 10_000;

    /**
     * Builds the transport-independent half of the client.
     *
     * <p>The {@code batchSize} clamp against the Worker's {@code MAX_BATCH_SIZE} is a
     * <b>port</b> property (a per-call bounded-work bound, stage A §2), not a channel property,
     * which is why it is validated here rather than in a transport.
     *
     * @param deadlineMs the base deadline every {@link RpcDeadlineCategory} multiplies
     * @param batchSize maximum files per batch submission (must be &lt;= Worker MAX_BATCH_SIZE)
     * @param telemetry the IPC telemetry for metrics recording; {@code null} means no-op. Carried
     *     here only so the status-poll metric keeps its exact shape across the A6 transport
     *     change; item A10 audits every {@code IpcTelemetry} metric name before deleting it
     *     (stage A §5), rather than dropping them silently at the transport swap.
     */
    protected KnowledgeClient(long deadlineMs, int batchSize, IpcTelemetry telemetry) {
        this.deadlineMs = deadlineMs;
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            log.warn("Invalid batchSize {}, using default {}. Valid range: 1-{}",
                     batchSize, DEFAULT_BATCH_SIZE, MAX_BATCH_SIZE);
        }
        this.telemetry = telemetry != null ? telemetry : IpcTelemetry.noop();
        this.searchRpcOps = new SearchRpcOps(this::executeSearchRpc);
        this.ingestRpcExecutor = this::executeIngestRpc;
        this.migrationOps = new MigrationOps(ingestRpcExecutor);
        this.vduOps = new VduOps(ingestRpcExecutor, this::getStatus);

        // Initialize roots persistence file + state before syncOps so the reconcile-verification
        // recorder callback (tempdoc 626 §Axis-C) can reference the assigned watchedRootsState field.
        Path dataDir = PlatformPaths.resolveDataDir().toAbsolutePath().normalize();
        Path rootsFile = dataDir.resolve("watched_roots.json");
        this.rootsStore = new WatchedRootsStore(rootsFile, log);
        this.watchedRootsState = new WatchedRootsState(watchedRoots, rootsStore);

        // Tempdoc 626 §Axis-C — a force=false reconcile's delete-detection outcome updates the
        // per-root verification state; an orphan-prune records a one-shot drift-corrected signal. Both
        // callbacks run later on the periodic-sync thread.
        this.syncOps = new SyncOps(ingestRpcExecutor, watchedRoots,
            this.watchedRootsState::setDeleteDetectionUnverified,
            (root, count) ->
                this.watchedRootsState.recordDriftCorrected(root, count, System.currentTimeMillis()));
        this.walkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "walk-bg");
            t.setDaemon(true);
            return t;
        });
        // Tempdoc 418 Phase B — RootLifecycleOps dispatches Worker-side ScanRoot RPCs
        // for the watched-root walk and registers Worker-side watchers via WatchRoot/UnwatchRoot.
        // Backpressure stays Head-side (between progress events); batching, admission, and
        // enqueue happen Worker-side via WorkerScanOps.
        // Tempdoc 821 §3-C2 — forward the root's collection into the scan RPC (the wire and the
        // Worker have carried it all along; only this lambda dropped it).
        // Tempdoc 821 §3-C3 — same defect on the mode: this lambda hard-coded
        // SCAN_MODE_INITIAL, so a force-reindex arrived at the Worker indistinguishable from an
        // ordinary rewalk. The caller decides the mode now; this lambda only carries it.
        RootLifecycleOps.ScanRootFn scanRootFn =
            (rootPath, collection, mode, excludeGlobs, progressConsumer) ->
                scanRoot(rootPath, collection, mode, excludeGlobs, progressConsumer);
        RootLifecycleOps.WorkerWatchFn workerWatchFn =
            new RootLifecycleOps.WorkerWatchFn() {
                @Override
                public void watch(String rootPath, String collection) {
                    watchRoot(rootPath, collection);
                }

                @Override
                public void unwatch(String rootPath) {
                    unwatchRoot(rootPath);
                }
            };
        // Tempdoc 418 B-H.3 — Worker now owns backpressure (queue-depth aware throttle inside
        // WorkerScanOps) and cancellation (via ServerCallStreamObserver.isCancelled()), so Head
        // no longer needs the queue-depth supplier or its in-callback await loop.
        this.rootLifecycleOps = new RootLifecycleOps(watchedRoots, watchedRootsState,
            this::getExcludeMatcher, scanRootFn, workerWatchFn,
            this::executeDeleteByPath, this::deleteById,
            syncOps, walkExecutor);
        rootsStore.migrateLegacyRootsFileIfNeeded();
        watchedRootsState.loadPersistedRoots();
    }

    public void reindexPersistedRoots() {
        rootLifecycleOps.reindexPersistedRoots();
    }


    private DeleteByPathResponse executeDeleteByPath(Path normalizedPath) {
        DeleteByPathRequest request = DeleteByPathRequest.newBuilder()
            .setPath(normalizedPath.toString())
            .build();
        return executeIngestRpc(
            "deleteByPath",
            RpcDeadlineCategory.STANDARD,
            stub -> stub.deleteByPath(request));
    }

    // ========== The transport seam (lane F stage A item A6) ==========

    /**
     * Runs one unary {@code SearchService} call.
     *
     * <p>The implementation owns exactly two things: how the call is carried, and how
     * {@code category} is turned into a real bound on the call. It must never silently drop the
     * bound — design §6, "none may vanish with the channel".
     *
     * @param operation the port operation name, used for logging and for the foreground gauge
     * @param category the deadline category this call belongs to
     * @param rpc the call itself, expressed against the call surface
     */
    protected abstract <T> T executeSearchRpc(
            String operation,
            RpcDeadlineCategory category,
            java.util.function.Function<SearchServiceCalls, T> rpc);

    /** Runs one unary {@code IngestService} call. See {@link #executeSearchRpc}. */
    protected abstract <T> T executeIngestRpc(
            String operation,
            RpcDeadlineCategory category,
            java.util.function.Function<IngestServiceCalls, T> rpc);

    /**
     * Runs the {@code HealthService} call with an explicit deadline in milliseconds.
     *
     * <p>Health is the one caller that supplies a deadline directly rather than through a
     * category: {@code getHealthCheck(long)} is used by the health monitor with its own budget.
     */
    protected abstract <T> T executeHealthRpc(
            String operation,
            long callDeadlineMs,
            java.util.function.Function<HealthServiceCalls, T> rpc);

    /**
     * Runs the server-streaming {@code ScanRoot} call, forwarding every progress event to
     * {@code progressConsumer} and returning the terminal event.
     *
     * <p>Cancellation and flow control are the implementation's, because they are the two
     * properties that differ between a stream over a socket and a stream inside one JVM. Both are
     * port properties that must survive (stage A §2); item A8 is where the in-process bound and
     * its policy are stated.
     *
     * @param cancelToken may be {@code null} (no cancellation wired)
     */
    protected abstract io.justsearch.ipc.ScanRootProgress executeScanRoot(
            io.justsearch.ipc.ScanRootRequest request,
            CancelToken cancelToken,
            java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> progressConsumer);

    /**
     * Opens the long-lived {@code SubscribeIndexingJobs} stream and delivers every frame to
     * {@code onFrame} until the returned handle is closed.
     *
     * <p>Used by {@link RemoteIndexingJobsBridge}, which owns the head-side fan-out to the
     * {@code core.indexing-jobs} SSE Resource. As with {@link #executeScanRoot}, the bound on the
     * flow belongs to the implementation (item A7).
     *
     * @param onFrame invoked for each frame, in stream order, on the delivery thread
     * @param onError invoked once if the stream fails
     * @param onCompleted invoked once if the producer closes the stream normally
     * @return a handle whose {@code close()} stops production
     */
    public abstract IndexingJobsStream subscribeIndexingJobs(
            java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> onFrame,
            java.util.function.Consumer<Throwable> onError,
            Runnable onCompleted);

    /** Releases whatever the transport holds. Called from {@link #close()}. */
    protected abstract void closeTransport();

    /**
     * Re-establishes the connection to the index half, validating the owning process id.
     *
     * <p>A no-op by default, and that default is the honest answer for an in-process client: there
     * is no connection to lose, no port to re-discover and no process whose identity could have
     * changed under it. The worker-restart callers (AI install, pack import,
     * {@code core.restart-worker}, post-resume revalidation) keep calling it so the wire path is
     * unchanged; stage A §10 records that "restart-as-reload" becomes a {@code restart required}
     * answer, and D1 retires the operation.
     *
     * @param expectedPid the pid the caller expects to be serving, or a non-positive value to skip
     *     the check
     */
    public void reconnect(long expectedPid) {
        // Nothing to reconnect: see the javadoc.
    }

    /** {@link #reconnect(long)} without a pid check. */
    public void reconnect() {
        reconnect(-1);
    }

    /**
     * Returns a tripped circuit breaker to CLOSED.
     *
     * <p>A no-op by default: the circuit breaker is a property of the channel (stage A §2) and an
     * in-process call has nothing to trip. Deleted with the breakers at item A10.
     */
    public void resetCircuitBreaker() {
        // Nothing to reset: see the javadoc.
    }

    /** Handle on a live {@code SubscribeIndexingJobs} flow. */
    public interface IndexingJobsStream extends AutoCloseable {
        /** Stops production and releases the flow. Idempotent. */
        @Override
        void close();
    }

    /**
     * Returns the deadline for an RPC operation based on its category.
     *
     * @param category the deadline category
     * @return the deadline in milliseconds
     */
    protected final long deadline(RpcDeadlineCategory category) {
        return category.apply(deadlineMs);
    }

    private <T> T executeHealthRpc(
            String operation,
            RpcDeadlineCategory category,
            java.util.function.Function<HealthServiceCalls, T> rpc) {
        return executeHealthRpc(operation, deadline(category), rpc);
    }

    // ========== Search Service (delegates to SearchRpcOps) ==========

    public SearchResponse search(String query, int limit) {
        return searchRpcOps.search(query, limit);
    }

    public SearchResponse search(String query, int limit, io.justsearch.ipc.PipelineConfig pipeline) {
        return searchRpcOps.search(query, limit, pipeline);
    }

    public SearchResponse search(SearchRequest request) {
        return searchRpcOps.search(request);
    }

    public SearchResponse searchVector(List<Float> queryVector, int limit) {
        return searchRpcOps.searchVector(queryVector, limit);
    }

    public SuggestResponse suggest(String query, int limit) {
        return searchRpcOps.suggest(query, limit);
    }

    public FetchDocumentsResponse fetchDocuments(List<String> docIds) {
        return searchRpcOps.fetchDocuments(docIds);
    }

    public FetchDocumentSliceResponse fetchDocumentSlice(String docId, int offsetChars, int maxChars) {
        return searchRpcOps.fetchDocumentSlice(docId, offsetChars, maxChars);
    }

    public RetrieveContextResponse retrieveContext(String question, Set<String> docIds, int topK) {
        return searchRpcOps.retrieveContext(question, docIds, topK);
    }

    public RetrieveContextResponse retrieveContext(String question, Set<String> docIds, int topK, int maxContextTokens) {
        return searchRpcOps.retrieveContext(question, docIds, topK, maxContextTokens);
    }

    public RetrieveContextResponse retrieveContext(io.justsearch.app.api.RetrieveContextParams params) {
        return searchRpcOps.retrieveContext(params);
    }

    public MatchCitationsResponse matchCitations(
        String answerText,
        List<String> chunkDocIds,
        List<Integer> chunkIndices,
        List<String> passageTexts,
        double threshold) {
        return searchRpcOps.matchCitations(
            answerText, chunkDocIds, chunkIndices, passageTexts, threshold);
    }

    /**
     * 360: Cross-encoder reranking via Worker's GPU-capable model.
     *
     * @param query the search query
     * @param documentTexts pre-built document texts (title + snippet)
     * @param deadlineMs budget for inference (0 = server default)
     * @return rerank response with sorted indices and scores
     */
    public RerankResponse rerank(String query, List<String> documentTexts, long deadlineMs) {
        return searchRpcOps.rerank(query, documentTexts, deadlineMs);
    }

    public ListFoldersResponse listFolders(String parentPath, int maxFolders) {
        return searchRpcOps.listFolders(parentPath, maxFolders);
    }

    public ListFolderFilesResponse listFolderFiles(
        String folderPath, int limit, List<String> projection) {
        return searchRpcOps.listFolderFiles(folderPath, limit, projection);
    }

    /**
     * Lists all parent document IDs in the Worker's index (paginated).
     *
     * <p>Used by the GPL coordinator to iterate the full corpus without depending on folder
     * hierarchy. Excludes chunk documents.
     *
     * @param offset zero-based page start
     * @param limit max IDs to return (0 → default 1000 on the Worker)
     * @return response with doc IDs, total count, and timing
     */
    public io.justsearch.ipc.ListAllDocumentIdsResponse listAllDocumentIds(int offset, int limit) {
        return searchRpcOps.listAllDocumentIds(offset, limit);
    }

    /** Continues document-ID pagination against the immutable snapshot from the first page. */
    public io.justsearch.ipc.ListAllDocumentIdsResponse listAllDocumentIds(
            int offset, int limit, String snapshotToken) {
        return searchRpcOps.listAllDocumentIds(offset, limit, snapshotToken);
    }

    // ========== Ingest Service ==========

    /**
     * Submits a batch of files for indexing.
     *
     * @param paths list of file paths to index
     * @return batch response with accepted count
     */
    public BatchResponse submitBatch(List<Path> paths) {
        return submitBatch(paths, false);
    }

    /**
     * Submits a batch of file paths for indexing with optional force flag.
     *
     * @param paths list of file paths to index
     * @param force if true, bypass "file unchanged" check and force re-extraction
     * @return batch response with accepted count
     */
    public BatchResponse submitBatch(List<Path> paths, boolean force) {
        return submitBatch(paths, force, null);
    }

    /**
     * Submits a batch of file paths for indexing with optional force flag and collection tag.
     *
     * @param paths list of file paths to index
     * @param force if true, bypass "file unchanged" check and force re-extraction
     * @param collection optional collection tag for the indexed documents (null for default)
     * @return batch response with accepted count
     */
    public BatchResponse submitBatch(List<Path> paths, boolean force, String collection) {
        BatchRequest.Builder builder = BatchRequest.newBuilder();
        for (Path path : paths) {
            builder.addFilePaths(path.toAbsolutePath().toString());
        }
        builder.setForceReindex(force);
        if (collection != null && !collection.isBlank()) {
            builder.setTargetCollection(collection);
        }
        BatchRequest request = builder.build();
        return executeIngestRpc(
            "submitBatch",
            RpcDeadlineCategory.STANDARD,
            stub -> stub.submitBatch(request));
    }

    /**
     * Deletes a single document by exact ID (normalized path).
     * Used by file watcher to handle DELETE events.
     *
     * @param docId the document ID (will be normalized)
     * @return response indicating success/failure
     */
    public DeleteByIdResponse deleteById(String docId) {
        DeleteByIdRequest request = DeleteByIdRequest.newBuilder()
                .setDocId(docId)
                .build();
        return executeIngestRpc(
            "deleteById",
            RpcDeadlineCategory.STANDARD,
            stub -> stub.deleteById(request));
    }

    /**
     * Updates document paths in the Lucene index after file MOVE/RENAME operations. For each
     * mapping, rewrites the parent document's DOC_ID/PATH/FILENAME and all chunk documents'
     * PARENT_DOC_ID/PATH fields.
     *
     * @param pathMappings map of old absolute path to new absolute path
     * @return number of parent documents successfully updated
     */
    public int updateDocumentPaths(Map<Path, Path> pathMappings) {
        UpdatePathsRequest.Builder reqBuilder = UpdatePathsRequest.newBuilder();
        for (var entry : pathMappings.entrySet()) {
            String oldPath = normalizePath(entry.getKey().toAbsolutePath().toString());
            String newPath = normalizePath(entry.getValue().toAbsolutePath().toString());
            reqBuilder.addMappings(
                PathMapping.newBuilder()
                    .setOldPath(oldPath)
                    .setNewPath(newPath)
                    .build());
        }
        UpdatePathsResponse resp = executeIngestRpc(
            "updateDocumentPaths",
            RpcDeadlineCategory.STANDARD,
            stub -> stub.updateDocumentPaths(reqBuilder.build()));
        if (!resp.getFailedPathsList().isEmpty()) {
            log.warn("updateDocumentPaths: {} failed paths: {}",
                resp.getFailedPathsList().size(), resp.getFailedPathsList());
        }
        return resp.getUpdatedCount();
    }

    /** Normalizes a path for index storage (lowercase on Windows, platform separators). */
    private static String normalizePath(String path) {
        if (path == null) return null;
        String normalized = path.replace('/', File.separatorChar);
        if (PlatformPaths.isWindows()) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    /**
     * Gets the current indexing status.
     *
     * <p>Records timing and response size metrics via IpcTelemetry.
     *
     * @return status response with queue depth and health
     */
    public StatusResponse getStatus() {
        try (var sample = telemetry.startStatusPoll()) { // NOPMD - telemetry timing
            StatusRequest request = StatusRequest.newBuilder().build();
            StatusResponse response = executeIngestRpc(
                "getStatus",
                RpcDeadlineCategory.STANDARD,
                stub -> stub.indexStatus(request));
            telemetry.recordStatusResponseSize(response.getSerializedSize());
            return response;
        }
    }

    /**
     * Returns the raw Lucene commit user data map from the Worker's latest index commit.
     *
     * <p>Fetches the current {@link StatusResponse} and extracts the {@code commit_user_data}
     * map, which contains all key-value pairs written during the last Lucene commit
     * (e.g. fingerprints, schema hashes, model SHAs). Returns an empty map when the Worker
     * is unavailable or no commit has been made yet.
     */
    public Map<String, String> getCommitMetadata() {
        StatusResponse status = getStatus();
        return status.getCommitUserDataMap();
    }

    /**
     * Returns a typed Worker operational status view for the /api/status endpoint.
     *
     * <p>This avoids leaking proto DTOs into the UI module while keeping the response
     * backwards-compatible via the record's Jackson serialization.
     */
    public io.justsearch.app.api.status.WorkerOperationalView getWorkerOperationalView() {
        StatusResponse status = getStatus();
        io.justsearch.app.api.status.WorkerOperationalView view;
        try {
            view = WorkerStatusMapper.toUiStatusMap(status, getHealthCheck());
        } catch (Exception e) {
            log.debug("Failed to fetch worker health readiness details for UI status", e);
            view = WorkerStatusMapper.toUiStatusMap(status);
        }
        cachedOperationalView.set(view);
        return view;
    }

    /**
     * Returns the last-known WorkerOperationalView without making a gRPC call.
     *
     * <p>Updated as a side-effect of {@link #getWorkerOperationalView()}. Returns null if the
     * operational view has never been fetched (e.g., before the first status poll).
     */
    public io.justsearch.app.api.status.WorkerOperationalView cachedOperationalView() {
        return cachedOperationalView.get();
    }

    /**
     * Returns a UI-friendly snapshot of Worker status as a plain Map.
     *
     * @deprecated Use {@link #getWorkerOperationalView()} and serialize via Jackson.
     */
    // 341: getStatusMapForUi() removed — use getWorkerOperationalView() with Jackson serialization.

    /**
     * Returns a JSON-friendly snapshot of Worker status + health check for debug surfaces.
     *
     * <p>Returns a typed record to avoid leaking proto DTOs across module boundaries.
     */
    public io.justsearch.app.api.status.WorkerDebugView getDebugWorkerState() {
        StatusResponse status = getStatus();
        io.justsearch.app.api.status.HealthNodeView healthNode;
        Map<String, String> effectiveConfig;
        try {
            var health = getHealthCheck();
            healthNode = WorkerStatusMapper.buildHealthNode(health);
            // tempdoc 623 U7: surface the worker effective_config (carrying ort.version) into the
            // debug-only WorkerDebugView — retained un-hashed in the eval manifest, no status-contract change.
            effectiveConfig = health.getEffectiveConfigMap();
        } catch (Exception e) {
            healthNode = new io.justsearch.app.api.status.HealthNodeView(false, "", 0, "", false, false);
            effectiveConfig = Map.of();
        }
        return WorkerStatusMapper.toDebugWorkerState(status, healthNode, effectiveConfig);
    }

    // ========== Health Service ==========

    /**
     * Checks if the Knowledge Server is healthy.
     *
     * <p>Like every health RPC this goes through {@link #executeHealthRpc}, which calls
     * {@link #reconnect()} — a no-op unless the signal bus reports a DIFFERENT port, in which case
     * the channel genuinely must be rebuilt. The existing connection is reused otherwise.
     *
     * @return true if serving
     */
    public boolean isHealthy() {
        try {
            HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
            HealthCheckResponse response = executeHealthRpc(
                "isHealthy",
                RpcDeadlineCategory.STANDARD,
                stub -> stub.check(request));
            return response.getServing();
        } catch (CircuitBreakerOpenException e) {
            log.debug("Health check rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.debug("Health check failed", e);
            return false;
        }
    }

    /**
     * Returns the full health check response (including worker_state) for diagnostics.
     *
     * <p>Prefer this over {@link #isHealthy()} when you need details like {@code worker_state} or {@code pid}.
     *
     * <p>Same connection handling as {@link #isHealthy()}: the shared health-RPC path re-reads the
     * signal bus and rebuilds the channel only when the reported port has actually changed.
     */
    public HealthCheckResponse getHealthCheck() {
        return getHealthCheck(deadline(RpcDeadlineCategory.STANDARD));
    }

    /**
     * Health check with an explicit per-call gRPC deadline, for callers that own a total budget and
     * must be able to spend it over several attempts.
     *
     * <p>Boot-time PID validation is the motivating caller: its whole window equals the STANDARD
     * deadline, so a single slow cold call (the worker-side check touches SQLite and Lucene, which
     * are expensive on first contact) consumed the entire budget and its retry loop never iterated.
     * Passing a per-attempt deadline keeps the retry loop a retry loop.
     *
     * @param callDeadlineMs the gRPC deadline for this one call, in milliseconds
     */
    public HealthCheckResponse getHealthCheck(long callDeadlineMs) {
        HealthCheckResponse response = executeHealthRpc(
            "getHealthCheck",
            callDeadlineMs,
            stub -> stub.check(HealthCheckRequest.newBuilder().build()));
        // Update last-known-good ONNX model cache from Worker's startup-time discovery (D-4).
        // executeHealthRpc never returns null — it throws on failure, so cache is only updated
        // on success. On failure, last-known-good is preserved.
        var models = response.getOnnxModelsList();
        if (!models.isEmpty()) {
            onnxModelsCache.set(models.stream()
                .map(m -> new OnnxModelStatus(
                    m.getModelName(), m.getFound(), m.getPath(), m.getAutoDiscovered(),
                    m.getSessionActive()))
                .toList());
        }
        return response;
    }

    /**
     * Returns the last-known-good ONNX model discovery status from the Worker.
     *
     * <p>Updated on each successful {@link #getHealthCheck()} call. Returns an empty list until the
     * first successful health check response containing ONNX model data.
     */
    public List<OnnxModelStatus> getLastKnownOnnxModels() {
        return onnxModelsCache.get();
    }

    /**
     * Gets the server version.
     *
     * @return version string or null if unavailable
     */
    public String getVersion() {
        try {
                HealthCheckResponse response = executeHealthRpc(
                "getVersion",
                RpcDeadlineCategory.STANDARD,
                stub -> stub.check(HealthCheckRequest.newBuilder().build()));
            return response.getVersion();
        } catch (CircuitBreakerOpenException e) {
            log.debug("getVersion rejected by circuit breaker");
            return null;
        } catch (Exception e) {
            log.debug("Failed to get version", e);
            return null;
        }
    }

    // ========== SearchPort Implementation ==========

    @Override
    public Result search(Query intent) {
        // Map Core Query to IPC SearchRequest
        String queryText = extractQueryText(intent);
        int limit = intent.limit();
        String cursorToken = intent.cursor() == null ? null : intent.cursor().token();

        // Cursor support is TEXT-only in the Worker gRPC surface. When a cursor is provided, force TEXT pipeline.
        io.justsearch.ipc.PipelineConfig pipeline = (cursorToken != null && !cursorToken.isBlank())
            ? PipelineConfigs.TEXT
            : PipelineConfigs.HYBRID;

        // Execute via gRPC
        SearchRequest.Builder req =
            SearchRequest.newBuilder().setQuery(queryText).setLimit(limit).setPipeline(pipeline);
        if (cursorToken != null && !cursorToken.isBlank()) {
            req.setCursor(cursorToken);
        }
        SearchResponse response = searchRpcOps.search(req.build());

        // Map IPC SearchResponse to Core Result
        return toCoreResult(response);
    }

    private String extractQueryText(Query intent) {
        if (intent.clauses() == null) {
            return "";
        }
        return intent.clauses().stream()
            .filter(c -> "text".equalsIgnoreCase(c.type()) && c.value() != null)
            .map(c -> c.value().toString())
            .findFirst()
            .orElse("");
    }

    private Result toCoreResult(SearchResponse response) {
        List<Result.Hit> hits = response.getResultsList().stream()
            .map(r -> new Result.Hit(r.getId(), r.getScore(), Map.of()))
            .toList();

        io.justsearch.core.dto.Cursor cursor = null;
        String nextCursor = response.getNextCursor();
        if (!nextCursor.isBlank()) {
            cursor = io.justsearch.core.dto.Cursor.legacy(nextCursor);
        }

        return new Result(
            hits,
            Map.of(),
            cursor,
            Map.of("total_hits", response.getTotalHits(), "took_ms", response.getTookMs()));
    }

    // ========== Exclude Matcher Cache ==========

    /**
     * The resolved exclude-patterns JSON, or {@code ""}. {@code globalOrNull} because sync batches
     * can be driven before the store is published; no excludes is the safe answer there.
     *
     * <p>Package-private so the tempdoc 883 slice-2 rewiring (settings.json at ordinal 300 instead
     * of the promoted sysprop) has a direct test; {@code getExcludeMatcher} is unreachable without
     * a live gRPC client.
     */
    static String resolvedExcludePatterns() {
        var store = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
        if (store == null) return "";
        String raw = store.get().ui().excludePatterns();
        return raw == null ? "" : raw;
    }

    private ExcludeMatcher getExcludeMatcher() {
        boolean windows = PlatformPaths.isWindows();
        // String-equality cache on the raw JSON, unchanged: a resolved value is the same kind of
        // immutable string the sysprop was, and ConfigStoreRebuilder swaps it wholesale.
        String raw = resolvedExcludePatterns();
        if (raw.isBlank()) {
            excludeRawCache = "";
            excludeCache = ExcludeMatcher.empty();
            return excludeCache;
        }
        if (raw.equals(excludeRawCache)) {
            return excludeCache;
        }
        synchronized (excludeLock) {
            String raw2 = resolvedExcludePatterns();
            if (raw2.equals(excludeRawCache)) {
                return excludeCache;
            }
            ExcludeMatcher next = ExcludeMatcher.fromRawJson(raw2, windows);
            excludeRawCache = raw2;
            excludeCache = next;
            return next;
        }
    }

    // ========== IndexingService Implementation (delegates to RootLifecycleOps) ==========

    @Override
    public List<Path> getWatchedPaths() {
        return rootLifecycleOps.getWatchedPaths();
    }

    @Override
    public List<IndexingService.WatchedRoot> getWatchedRoots() {
        return rootLifecycleOps.getWatchedRoots();
    }

    @Override
    public void addWatchedPath(Path path) {
        rootLifecycleOps.addWatchedPath(path);
    }

    @Override
    public void addWatchedRoot(String collection, Path path) {
        rootLifecycleOps.addWatchedRoot(collection, path);
    }

    @Override
    public int deleteDocsByPathPrefix(Path pathPrefix) {
        return rootLifecycleOps.deleteDocsByPathPrefix(pathPrefix);
    }

    @Override
    public boolean deleteDocById(String docId) {
        return rootLifecycleOps.deleteDocById(docId);
    }

    /**
     * Tempdoc 811 (C-2a) — collection-keyed removal route. Gating (which collections are deletable)
     * belongs to the caller via {@code IngestCollectionPolicy.isDeletable}; this is the transport.
     */
    @Override
    public int deleteDocsByCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return -1;
        }
        io.justsearch.ipc.DeleteByCollectionRequest request =
            io.justsearch.ipc.DeleteByCollectionRequest.newBuilder()
                .setCollection(collection)
                .build();
        io.justsearch.ipc.DeleteByCollectionResponse response =
            executeIngestRpc(
                "deleteByCollection",
                RpcDeadlineCategory.STANDARD,
                stub -> stub.deleteByCollection(request));
        if (response == null) {
            return -1;
        }
        if (!response.getError().isEmpty()) {
            log.warn("deleteByCollection RPC returned error for {}: {}", collection, response.getError());
        }
        return response.getDeletedDocs();
    }

    @Override
    public int removeWatchedPath(Path path) {
        return rootLifecycleOps.removeWatchedPath(path);
    }

    @Override
    public void flush() {
        rootLifecycleOps.flush();
    }

    @Override
    public void reindexWatchedRoots(boolean force) {
        rootLifecycleOps.reindexWatchedRoots(force);
    }

    @Override
    public boolean reconcileRoot(String pathHash, boolean force) {
        // Tempdoc 626 §Recency (Move C) — resolve the privacy-safe pathHash to the real root Head-side
        // (raw paths never cross the wire — ADR-0028), then run a per-root force reconcile. A force=true
        // syncDirectory re-prunes orphans + re-walks the root, re-converging it AND (via SyncOps' §Recency
        // recording) refreshing the per-root verification state — clearing deleteDetectionUnverified and
        // stamping lastVerifiedAt. Mirrors ResolvePathHashHandler's head-side watched-roots fallback.
        if (pathHash == null || pathHash.isBlank()) {
            return false;
        }
        for (IndexingService.WatchedRoot root : rootLifecycleOps.getWatchedRoots()) {
            if (root.path() == null) {
                continue;
            }
            if (sha256Hex(root.path().toString()).equalsIgnoreCase(pathHash)) {
                syncDirectory(root.path().toString(), force);
                return true;
            }
        }
        return false;
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean startMigration(String reason) {
        return migrationOps.startMigration(reason);
    }

    @Override
    public boolean requestCutover(boolean forceSwitching) {
        return migrationOps.requestCutover(forceSwitching);
    }

    @Override
    public boolean rollbackMigration() {
        return migrationOps.rollbackMigration();
    }

    @Override
    public boolean pauseMigration(String reason) {
        return migrationOps.pauseMigration(reason);
    }

    @Override
    public boolean resumeMigration() {
        return migrationOps.resumeMigration();
    }

    @Override
    public IndexingService.IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
        return migrationOps.runIndexGc(keepLatest, pruneMarkedOnly);
    }

    @Override
    public IndexingService.SettleIndexOutcome settleIndex(
            boolean expungeDeletesOnly, int maxSegments) {
        return migrationOps.settleIndex(expungeDeletesOnly, maxSegments);
    }

    /**
     * Projects the Worker's gRPC quiescence message onto the app-api contract record.
     *
     * <p>The mapping lives here, at the single gRPC boundary, so the generated proto type never
     * reaches {@code ui.api} — see {@code UiApiGuardrailsTest} and {@link
     * io.justsearch.app.api.WorkerQuiescenceSnapshot}.
     */
    private static io.justsearch.app.api.WorkerQuiescenceSnapshot toSnapshot(
            io.justsearch.ipc.UpgradeQuiescenceResponse response) {
        if (response == null) {
            return null;
        }
        return new io.justsearch.app.api.WorkerQuiescenceSnapshot(
                response.getPreparationId(),
                response.getReady(),
                response.getLoopQuiesced(),
                response.getQueueCheckpointed(),
                response.getMigrationState(),
                response.getBlockersList());
    }

    public io.justsearch.app.api.WorkerQuiescenceSnapshot prepareUpgrade(String preparationId) {
        var request =
                io.justsearch.ipc.UpgradeQuiescenceRequest.newBuilder()
                        .setPreparationId(preparationId)
                        .build();
        return toSnapshot(
                executeIngestRpc(
                        "prepareUpgrade",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.prepareUpgrade(request)));
    }

    public io.justsearch.app.api.WorkerQuiescenceSnapshot upgradeStatus(String preparationId) {
        var request =
                io.justsearch.ipc.UpgradeQuiescenceRequest.newBuilder()
                        .setPreparationId(preparationId)
                        .build();
        return toSnapshot(
                executeIngestRpc(
                        "upgradeStatus",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.upgradeStatus(request)));
    }

    public io.justsearch.app.api.WorkerQuiescenceSnapshot cancelUpgrade(String preparationId) {
        var request =
                io.justsearch.ipc.UpgradeQuiescenceRequest.newBuilder()
                        .setPreparationId(preparationId)
                        .build();
        return toSnapshot(
                executeIngestRpc(
                        "cancelUpgrade",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.cancelUpgrade(request)));
    }

    @Override
    public List<IndexingService.FailedJobInfo> listFailedJobs(int limit) {
        ListFailedJobsRequest req = ListFailedJobsRequest.newBuilder()
                .setLimit(limit).build();
        ListFailedJobsResponse resp = executeIngestRpc(
                "listFailedJobs", RpcDeadlineCategory.STANDARD,
                stub -> stub.listFailedJobs(req));
        return resp.getJobsList().stream()
                .map(j -> new IndexingService.FailedJobInfo(
                        j.getPath(), j.getErrorMessage(), j.getAttempts(),
                        j.getLastUpdatedMs(), j.getCollection(),
                        j.getState().isEmpty() ? "FAILED" : j.getState(),
                        j.getScanId()))
                .toList();
    }

    @Override
    public List<IndexingService.FailedJobInfo> listFailedJobsByPathPrefix(
            Path pathPrefix, int limit) {
        if (pathPrefix == null) {
            return List.of();
        }
        io.justsearch.ipc.ListFailedJobsByPathPrefixRequest req =
                io.justsearch.ipc.ListFailedJobsByPathPrefixRequest.newBuilder()
                        .setPathPrefix(pathPrefix.toString())
                        .setLimit(limit)
                        .build();
        ListFailedJobsResponse resp = executeIngestRpc(
                "listFailedJobsByPathPrefix", RpcDeadlineCategory.STANDARD,
                stub -> stub.listFailedJobsByPathPrefix(req));
        return resp.getJobsList().stream()
                .map(j -> new IndexingService.FailedJobInfo(
                        j.getPath(), j.getErrorMessage(), j.getAttempts(),
                        j.getLastUpdatedMs(), j.getCollection(),
                        j.getState().isEmpty() ? "FAILED" : j.getState(),
                        j.getScanId()))
                .toList();
    }

    @Override
    public IndexingService.JobCounts countJobsByPathPrefix(Path pathPrefix) {
        if (pathPrefix == null) {
            return IndexingService.JobCounts.zero();
        }
        io.justsearch.ipc.CountJobsByPathPrefixRequest req =
                io.justsearch.ipc.CountJobsByPathPrefixRequest.newBuilder()
                        .setPathPrefix(pathPrefix.toString())
                        .build();
        io.justsearch.ipc.CountJobsByPathPrefixResponse resp = executeIngestRpc(
                "countJobsByPathPrefix", RpcDeadlineCategory.STANDARD,
                stub -> stub.countJobsByPathPrefix(req));
        io.justsearch.ipc.IndexingJobCounts c = resp.getCounts();
        long inFlight = c.getPendingCount() + c.getProcessingCount();
        io.justsearch.ipc.RootCoverageCounts cov = resp.getCoverage();
        return new IndexingService.JobCounts(
                inFlight,
                c.getFailedCount(),
                new IndexingService.RootCoverage(
                        cov.getParentDocsTotalEmbedding(),
                        cov.getParentDocsSettledEmbedding(),
                        cov.getParentDocsTotalSplade(),
                        cov.getParentDocsSettledSplade(),
                        cov.getParentDocsTotalNer(),
                        cov.getParentDocsSettledNer(),
                        cov.getChunkDocsTotal(),
                        cov.getChunkDocsSettled()));
    }

    @Override
    public int clearFailedJobs() {
        ClearFailedJobsRequest req = ClearFailedJobsRequest.newBuilder().build();
        ClearFailedJobsResponse resp = executeIngestRpc(
                "clearFailedJobs", RpcDeadlineCategory.STANDARD,
                stub -> stub.clearFailedJobs(req));
        return resp.getDeletedCount();
    }

    @Override
    public void clearAllRoots() {
        rootLifecycleOps.clearAllRoots();
    }

    @Override
    public boolean resetIndex() {
        ResetIndexRequest req = ResetIndexRequest.newBuilder().build();
        ResetIndexResponse resp = executeIngestRpc(
                "resetIndex", RpcDeadlineCategory.LONG_RUNNING,
                stub -> stub.resetIndex(req));
        return resp.getSuccess();
    }

    /**
     * Tempdoc 406 — admin-triggered runtime swap. Drains current ingest runtime,
     * opens a fresh one on the same path. Returns the swap duration in
     * milliseconds. Not retried automatically (admin-triggered, not idempotent at
     * the RPC level).
     *
     * @param reason low-cardinality tag forwarded to telemetry; defaults to
     *     "admin_triggered" if blank
     */
    @Override
    public long reloadRuntime(String reason) {
        io.justsearch.ipc.ReloadRuntimeRequest req =
                io.justsearch.ipc.ReloadRuntimeRequest.newBuilder()
                        .setReason(reason == null ? "" : reason)
                        .build();
        io.justsearch.ipc.ReloadRuntimeResponse resp = executeIngestRpc(
                "reloadRuntime", RpcDeadlineCategory.LONG_RUNNING,
                stub -> stub.reloadRuntime(req));
        return resp.getSwapDurationMs();
    }

    /**
     * Fetches the Worker-side session-policies snapshot and parses the JSON payloads into a
     * typed {@link Map} response (tempdoc 397 §14.28 U4). Backs
     * {@code /api/debug/session-policies} in Head — returns Worker's authoritative
     * PolicySnapshot (built at boot via InferenceCompositionRoot.compose), not a Head-side
     * re-resolve.
     *
     * <p>Encapsulated in this class so {@code ui.api.SessionPoliciesController} doesn't depend
     * on {@code io.justsearch.ipc} proto types (UiApiGuardrailsTest). Returned shape mirrors
     * today's REST response: {@code {configStatus, runtime, models}}.
     */
    public Map<String, Object> getSessionPolicies() {
        io.justsearch.ipc.SessionPoliciesRequest req =
                io.justsearch.ipc.SessionPoliciesRequest.newBuilder().build();
        io.justsearch.ipc.SessionPoliciesResponse grpcResp;
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        try {
            grpcResp = executeIngestRpc(
                    "getSessionPolicies", RpcDeadlineCategory.STANDARD,
                    stub -> stub.getSessionPolicies(req));
        } catch (RuntimeException e) {
            // Phase 2.1a debug spike (tempdoc 400 LR1-c). Pre-Phase-2.1 this
            // catch was silent, masking the root cause of worker-unreachable
            // in eval mode. Kept as a log.warn after the spike so operators
            // can diagnose recurrences. Does not leak request data; logs the
            // exception type + message only.
            log.warn(
                    "getSessionPolicies RPC failed: {}: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            response.put("configStatus", "worker-unreachable");
            response.put("runtime", new java.util.LinkedHashMap<>());
            response.put("models", new java.util.TreeMap<>());
            return response;
        }
        response.put("configStatus", grpcResp.getConfigStatus());
        try {
            tools.jackson.databind.ObjectMapper mapper =
                    new tools.jackson.databind.json.JsonMapper();
            Object runtime =
                    grpcResp.getRuntimePolicyJson().isEmpty()
                            ? new java.util.LinkedHashMap<>()
                            : mapper.readValue(grpcResp.getRuntimePolicyJson(), Object.class);
            response.put("runtime", runtime);
            Map<String, Object> models = new java.util.TreeMap<>();
            for (var entry : grpcResp.getModelPoliciesJsonMap().entrySet()) {
                models.put(entry.getKey(), mapper.readValue(entry.getValue(), Object.class));
            }
            response.put("models", models);
        } catch (RuntimeException e) {
            response.put("configStatus", "surface-unavailable");
            response.put("runtime", new java.util.LinkedHashMap<>());
            response.put("models", new java.util.TreeMap<>());
        }
        return response;
    }

    /**
     * Tempdoc 422: returns per-encoder {@link io.justsearch.app.api.status.OrtCudaView} typed
     * keyed by {@link io.justsearch.ort.EncoderRole}. Source of truth for the
     * {@code /api/inference/encoders} explainer's runtime accelerator state. Mirrors
     * {@link #getSessionPolicies()}'s shape: typed Head-side return, no proto types leaked
     * across modules.
     *
     * <p>On RPC failure, returns an empty map and logs (consistent with
     * {@code getSessionPolicies()} returning {@code "worker-unreachable"}).
     */
    public Map<io.justsearch.ort.EncoderRole, io.justsearch.app.api.status.OrtCudaView>
            getEncoderOrtCudaViews() {
        StatusResponse status;
        try {
            status = getStatus();
        } catch (RuntimeException e) {
            log.warn(
                    "getEncoderOrtCudaViews status RPC failed: {}: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return Map.of();
        }
        var gpu = status.getGpu();
        Map<io.justsearch.ort.EncoderRole, io.justsearch.app.api.status.OrtCudaView> views =
                new java.util.EnumMap<>(io.justsearch.ort.EncoderRole.class);
        views.put(io.justsearch.ort.EncoderRole.EMBEDDING,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getEmbedOrtCuda()));
        views.put(io.justsearch.ort.EncoderRole.BGE_M3,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getBgeM3OrtCuda()));
        views.put(io.justsearch.ort.EncoderRole.SPLADE,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getSpladeOrtCuda()));
        views.put(io.justsearch.ort.EncoderRole.NER,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getNerOrtCuda()));
        views.put(io.justsearch.ort.EncoderRole.RERANKER,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getRerankerOrtCuda()));
        views.put(io.justsearch.ort.EncoderRole.CITATION,
                WorkerStatusMapper.mapOrtCudaProbe(gpu.getCitationOrtCuda()));
        return views;
    }

    /**
     * Fetches the most recent privacy-safe ingestion ledger events from the Worker.
     * Returns rows containing only path-hash identifiers; raw paths never cross the boundary.
     * Backs {@code GET /api/diagnostics/ingestion/recent} (tempdoc 410 §12).
     */
    @Override
    public List<Map<String, Object>> recentIngestionEvents(int limit) {
        io.justsearch.ipc.RecentIngestionEventsRequest req =
                io.justsearch.ipc.RecentIngestionEventsRequest.newBuilder().setLimit(limit).build();
        io.justsearch.ipc.RecentIngestionEventsResponse resp =
                executeIngestRpc(
                        "recentIngestionEvents",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.recentIngestionEvents(req));
        List<Map<String, Object>> events = new java.util.ArrayList<>(resp.getEventsCount());
        for (io.justsearch.ipc.IngestionEvent event : resp.getEventsList()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", event.getId());
            row.put("pathHash", event.getPathHash());
            row.put("collection", emptyToNull(event.getCollection()));
            row.put("outcomeClass", event.getOutcomeClass());
            row.put("reasonCode", event.getReasonCode());
            row.put("retryPolicy", event.getRetryPolicy());
            row.put("diagnosticSummary", emptyToNull(event.getDiagnosticSummary()));
            row.put("observedAtMs", event.getObservedAtMs());
            row.put("sourceSizeBytes", event.getSourceSizeBytes());
            row.put("sourceModifiedAtMs", event.getSourceModifiedAtMs());
            row.put("sourceKind", event.getSourceKind());
            row.put("artifactStatus", event.getArtifactStatus());
            row.put("policyId", event.getPolicyId());
            row.put("parserId", event.getParserId());
            events.add(row);
        }
        return events;
    }

    /**
     * Fetches grouped ingestion outcome counts since the given epoch ms (0 = all retained).
     * Backs {@code GET /api/diagnostics/ingestion/summary} (tempdoc 410 §12).
     */
    @Override
    public List<Map<String, Object>> ingestionOutcomeSummary(long sinceMs) {
        io.justsearch.ipc.IngestionOutcomeSummaryRequest req =
                io.justsearch.ipc.IngestionOutcomeSummaryRequest.newBuilder()
                        .setSinceMs(sinceMs)
                        .build();
        io.justsearch.ipc.IngestionOutcomeSummaryResponse resp =
                executeIngestRpc(
                        "ingestionOutcomeSummary",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.ingestionOutcomeSummary(req));
        List<Map<String, Object>> rollups = new java.util.ArrayList<>(resp.getRollupsCount());
        for (io.justsearch.ipc.IngestionOutcomeRollup rollup : resp.getRollupsList()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("outcomeClass", rollup.getOutcomeClass());
            row.put("reasonCode", rollup.getReasonCode());
            row.put("retryPolicy", rollup.getRetryPolicy());
            row.put("count", rollup.getCount());
            row.put("lastObservedAtMs", rollup.getLastObservedAtMs());
            rollups.add(row);
        }
        return rollups;
    }

    /**
     * ADR-0028 / tempdoc 419 T5.3 — scoped reverse-lookup. Calls {@code LookupPathByHash}
     * and returns a typed map: {@code found, path, lastSeenAtMs, removedAtMs}. Backs the
     * single-purpose endpoint {@code POST /api/library/resolve-hash}; diagnostic export
     * endpoints MUST NOT call this method (enforced by ArchUnit pin
     * {@code LibraryResolveHashOnlyCallerPin}).
     */
    @Override
    public Map<String, Object> resolvePathHash(String pathHash) {
        Objects.requireNonNull(pathHash, "pathHash");
        io.justsearch.ipc.LookupPathByHashRequest req =
                io.justsearch.ipc.LookupPathByHashRequest.newBuilder()
                        .setPathHash(pathHash)
                        .build();
        io.justsearch.ipc.LookupPathByHashResponse resp =
                executeIngestRpc(
                        "lookupPathByHash",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.lookupPathByHash(req));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("found", resp.getFound());
        if (resp.getFound()) {
            row.put("path", resp.getPath());
            row.put("lastSeenAtMs", resp.getLastSeenAtMs());
            row.put("removedAtMs", resp.getRemovedAtMs());
        }
        return row;
    }

    /**
     * Slice 445: cancel an in-flight job by its {@code pathHash}. Forwards to the worker's
     * {@code CancelIndexingJob} RPC; the worker resolves the hash via its
     * {@code PathResolutionStore} and marks the row terminal.
     */
    @Override
    public Map<String, Object> cancelIndexingJob(String pathHash) {
        Objects.requireNonNull(pathHash, "pathHash");
        io.justsearch.ipc.CancelIndexingJobRequest req =
                io.justsearch.ipc.CancelIndexingJobRequest.newBuilder()
                        .setPathHash(pathHash)
                        .build();
        io.justsearch.ipc.CancelIndexingJobResponse resp =
                executeIngestRpc(
                        "cancelIndexingJob",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.cancelIndexingJob(req));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("cancelled", resp.getCancelled());
        row.put("previousState", resp.getPreviousState());
        return row;
    }

    /**
     * Slice 445: retry a FAILED job by its {@code pathHash}. Forwards to the worker's
     * {@code RetryIndexingJob} RPC; the worker resolves the hash and re-enqueues the row.
     */
    @Override
    public Map<String, Object> retryIndexingJob(String pathHash) {
        Objects.requireNonNull(pathHash, "pathHash");
        io.justsearch.ipc.RetryIndexingJobRequest req =
                io.justsearch.ipc.RetryIndexingJobRequest.newBuilder()
                        .setPathHash(pathHash)
                        .build();
        io.justsearch.ipc.RetryIndexingJobResponse resp =
                executeIngestRpc(
                        "retryIndexingJob",
                        RpcDeadlineCategory.STANDARD,
                        stub -> stub.retryIndexingJob(req));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("retried", resp.getRetried());
        row.put("previousState", resp.getPreviousState());
        return row;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Tempdoc 418 Phase B — server-streaming ScanRoot. Worker walks the root and admits each
     * discovered file via {@code WorkerIngestionAuthority}; this client forwards every
     * {@link io.justsearch.ipc.ScanRootProgress} to {@code progressConsumer} and returns the
     * terminal progress event (the one with {@code complete=true}).
     *
     * <p>Cancellation is gRPC-native: the client may stop iterating to terminate the walk
     * (the server-side {@code Files.walkFileTree} responds to the cancellation by returning
     * TERMINATE on the next visitor call).
     *
     * @param rootPath absolute root path; Worker validates it is a directory and emits a typed
     *     terminal event ({@code ROOT_NOT_DIRECTORY}) if not.
     * @param collection optional collection tag; null/blank routes to the default collection.
     * @param mode {@link io.justsearch.ipc.ScanMode} (INITIAL | RESCAN | FORCE_REINDEX).
     * @param excludeGlobs caller-supplied globs layered on top of
     *     {@code WorkerIngestionAuthority.shouldSkip}.
     * @param progressConsumer invoked for every progress event the server emits.
     * @return the terminal progress event (last value seen).
     */
    public io.justsearch.ipc.ScanRootProgress scanRoot(
            String rootPath,
            String collection,
            io.justsearch.ipc.ScanMode mode,
            List<String> excludeGlobs,
            java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> progressConsumer) {
        return scanRoot(rootPath, collection, mode, excludeGlobs, null, progressConsumer);
    }

    /**
     * Tempdoc 419 / T3 — overload that accepts a {@link CancelToken}. Calling
     * {@link CancelToken#cancel()} from any thread reaches the producer's cancellation signal:
     * over the wire that is a gRPC {@code CANCELLED} status, and in process it is the
     * {@code CallContext.CancelSignal} the scan loop polls. Either way the Worker's scan loop
     * (tempdoc 418 B-H.3) terminates within the next batch. Closes the validation finding
     * (2026-04-26) where HTTP-client abort had no effect on the in-flight scan.
     *
     * <p>Passing {@code null} for {@code cancelToken} is equivalent to the legacy 5-arg
     * overload — no cancel is wired.
     */
    public io.justsearch.ipc.ScanRootProgress scanRoot(
            String rootPath,
            String collection,
            io.justsearch.ipc.ScanMode mode,
            List<String> excludeGlobs,
            CancelToken cancelToken,
            java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> progressConsumer) {
        Objects.requireNonNull(rootPath, "rootPath");
        Objects.requireNonNull(progressConsumer, "progressConsumer");
        io.justsearch.ipc.ScanRootRequest.Builder builder =
                io.justsearch.ipc.ScanRootRequest.newBuilder()
                        .setRootPath(rootPath)
                        .setMode(mode == null ? io.justsearch.ipc.ScanMode.SCAN_MODE_INITIAL : mode);
        if (collection != null && !collection.isBlank()) {
            builder.setCollection(collection);
        }
        if (excludeGlobs != null) {
            for (String glob : excludeGlobs) {
                if (glob != null && !glob.isBlank()) {
                    builder.addExcludeGlobs(glob);
                }
            }
        }
        io.justsearch.ipc.ScanRootRequest request = builder.build();
        return executeScanRoot(request, cancelToken, progressConsumer);
    }

    /**
     * The terminal event synthesised when the caller cancels a scan mid-flight. Shared by both
     * transports so a cancelled scan reports the same reason code whether the cancellation
     * travelled over a channel or flipped an in-process flag.
     */
    protected static io.justsearch.ipc.ScanRootProgress scanCancelledEvent() {
        return io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode("CLIENT_CANCELLED")
                .build();
    }

    /**
     * The terminal event synthesised when the producer closed the scan without emitting
     * anything, so callers always see a clean signal.
     */
    protected static io.justsearch.ipc.ScanRootProgress scanEmptyStreamEvent() {
        return io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode("EMPTY_STREAM")
                .build();
    }

    /**
     * Tempdoc 418 Phase B — registers the Worker watcher subscription for a root. Phase A's
     * registry is bookkeeping-only; Phase B's watcher migration upgrades it to real change-event
     * delivery via the Methvin watcher.
     */
    public io.justsearch.ipc.WatchRootResponse watchRoot(String rootPath, String collection) {
        Objects.requireNonNull(rootPath, "rootPath");
        io.justsearch.ipc.WatchRootRequest.Builder builder =
                io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(rootPath);
        if (collection != null && !collection.isBlank()) {
            builder.setCollection(collection);
        }
        io.justsearch.ipc.WatchRootRequest request = builder.build();
        return executeIngestRpc(
                "watchRoot", RpcDeadlineCategory.STANDARD, stub -> stub.watchRoot(request));
    }

    /** Tempdoc 418 Phase B — removes a Worker watcher subscription. Idempotent. */
    public io.justsearch.ipc.UnwatchRootResponse unwatchRoot(String rootPath) {
        Objects.requireNonNull(rootPath, "rootPath");
        io.justsearch.ipc.UnwatchRootRequest request =
                io.justsearch.ipc.UnwatchRootRequest.newBuilder().setRootPath(rootPath).build();
        return executeIngestRpc(
                "unwatchRoot", RpcDeadlineCategory.STANDARD, stub -> stub.unwatchRoot(request));
    }

    public SyncDirectoryResponse syncDirectory(String rootPath, boolean force) {
        return syncOps.syncDirectory(rootPath, force);
    }

    public void startPeriodicSync() {
        syncOps.startPeriodicSync();
    }

    void stopPeriodicSync() {
        syncOps.stopPeriodicSync();
    }

    @Override
    public void reindex() {
        reindexWatchedRoots();
    }

    // ========== Pending Status Counts (Phase 2) ==========

    public int countPendingEmbeddings() {
        return vduOps.countPendingEmbeddings();
    }

    public int countPendingVdu() {
        return vduOps.countPendingVdu();
    }

    // ========== VDU Result Update (Phase 3) ==========

    public boolean updateVduResult(
            String docId,
            String extractedContent,
            io.justsearch.ipc.VduUpdateOutcome outcome,
            String enrichment,
            int pageCount) {
        return vduOps.updateVduResult(docId, extractedContent, outcome, enrichment, pageCount);
    }

    public List<String> queryPendingVduDocIds() {
        return vduOps.queryPendingVduDocIds();
    }

    public List<String> queryPendingVduDocIds(int limit) {
        return vduOps.queryPendingVduDocIds(limit);
    }

    public int markVduProcessing(String docId, int maxRetries) {
        return vduOps.markVduProcessing(docId, maxRetries);
    }

    public int recoverVduProcessing() {
        return vduOps.recoverVduProcessing();
    }

    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) {
            // Stop periodic sync first
            stopPeriodicSync();

            walkExecutor.shutdownNow();
            closeTransport();
            log.info("{} closed", getClass().getSimpleName());
        }
    }

}
