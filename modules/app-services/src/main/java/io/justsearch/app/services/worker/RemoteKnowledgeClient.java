/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.CircuitBreakerOpenException;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.KnowledgeServerNotConnectedException;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import io.justsearch.ipc.grpc.GrpcMessageLimits;
import io.justsearch.ipc.grpc.GrpcRetryServiceConfig;
import io.justsearch.ipc.grpc.RequestIdClientInterceptor;
import io.justsearch.ipc.grpc.TraceClientInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gRPC transport for {@link KnowledgeClient}: a channel, four stubs, and the reliability
 * machinery that only a channel needs.
 *
 * <p>Reliability features, all of them properties of the <b>wire</b> and none of them of the work
 * (lane F stage A §2, design §6):
 * <ul>
 *   <li>Port re-discovery from the MMF signal bus on worker restart</li>
 *   <li>gRPC retry policy for idempotent RPCs (SearchService, HealthService,
 *       5 IngestService methods; retryableStatusCodes=[UNAVAILABLE])</li>
 *   <li>Circuit breaker (3-failure threshold, 10s cooldown)</li>
 *   <li>{@code IpcTelemetry}, message-size limits, and the trace/request-id client
 *       interceptors</li>
 * </ul>
 *
 * <p><b>Not on the live path from stage A item A6.</b> The composition root
 * ({@code io.justsearch.app.engine.EngineRoot}) now builds the Worker inside the Engine JVM and
 * binds {@code EngineKnowledgeClient} instead; nothing in {@code src/main} constructs this class
 * any more. It is kept compiling — with its tests — only until item A10 deletes the whole wire
 * client stack (the MMF bus, the two circuit breakers, {@code IpcTelemetry} and this class) as
 * one reviewable change. Until then {@code WholeProgramDeadCodeTest} reports it as unreferenced;
 * that is the accepted red 17.4 predicts, not a defect.
 */
public final class RemoteKnowledgeClient extends KnowledgeClient {
    private static final Logger log = LoggerFactory.getLogger(RemoteKnowledgeClient.class);

    private static final String HOST = "127.0.0.1";
    private static final long GRPC_RETRY_INITIAL_BACKOFF_MS = 100;
    private static final long GRPC_RETRY_MAX_BACKOFF_MS = 2000;
    private static final double GRPC_RETRY_BACKOFF_MULTIPLIER = 2.0d;

    private final MainSignalBus signalBus;
    private final Map<String, Object> grpcServiceConfig;
    private final GrpcCircuitBreaker circuitBreaker;
    private final AtomicReference<ManagedChannel> channelRef = new AtomicReference<>();
    private final AtomicInteger currentPort = new AtomicInteger(0);

    // Test seam (683): when set via connectForTesting(Channel), the search-RPC path uses the
    // injected stub as-is — no ensureConnected() channel check, no reconnect() port re-discovery.
    // Always false in production.
    private volatile boolean testChannelPinned;

    private volatile SearchServiceGrpc.SearchServiceBlockingStub searchStub;
    private volatile IngestServiceGrpc.IngestServiceBlockingStub ingestStub;
    // Slice 445: async stub for the long-lived SubscribeIndexingJobs server-streaming
    // RPC. Blocking stub returns an Iterator that doesn't fit the StreamObserver-based
    // RemoteIndexingJobsBridge consumer.
    private volatile IngestServiceGrpc.IngestServiceStub ingestAsyncStub;
    private volatile HealthServiceGrpc.HealthServiceBlockingStub healthStub;

    /**
     * Creates a new RemoteKnowledgeClient with a default circuit breaker and default batch size.
     *
     * @param signalBus the signal bus for port discovery
     * @param deadlineMs deadline for each RPC call in milliseconds
     * @param maxRetries maximum retry attempts for transient failures
     */
    public RemoteKnowledgeClient(MainSignalBus signalBus, long deadlineMs, int maxRetries) {
        this(signalBus, deadlineMs, maxRetries, DEFAULT_BATCH_SIZE, new GrpcCircuitBreaker(),
             IpcTelemetry.noop());
    }

    /**
     * Creates a new RemoteKnowledgeClient with a custom circuit breaker and telemetry.
     *
     * @param signalBus the signal bus for port discovery
     * @param deadlineMs deadline for each RPC call in milliseconds
     * @param maxRetries maximum retry attempts for transient failures
     * @param batchSize maximum files per batch submission (must be &lt;= Worker MAX_BATCH_SIZE)
     * @param circuitBreaker the circuit breaker for failure handling
     * @param telemetry the IPC telemetry for metrics recording
     */
    public RemoteKnowledgeClient(MainSignalBus signalBus, long deadlineMs, int maxRetries,
                                 int batchSize, GrpcCircuitBreaker circuitBreaker,
                                 IpcTelemetry telemetry) {
        super(deadlineMs, batchSize, telemetry);
        this.signalBus = signalBus;
        this.grpcServiceConfig = buildGrpcRetryServiceConfig(maxRetries);
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new GrpcCircuitBreaker();
    }

    /**
     * Connects to the Knowledge Server using the port from the signal bus.
     *
     * @param port the gRPC port to connect to
     */
    public void connect(int port) {
        if (port <= 0) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }

        closeChannel();

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(HOST, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .enableRetry()
                .defaultServiceConfig(grpcServiceConfig)
                .maxInboundMessageSize(GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES)
                .build();

        // 250 Phase 5c: Propagate OTel trace context and request ID to the Worker via gRPC metadata
        Channel tracedChannel =
            ClientInterceptors.intercept(channel,
                new TraceClientInterceptor(),
                new RequestIdClientInterceptor());

        channelRef.set(channel);
        currentPort.set(port);

        searchStub = SearchServiceGrpc.newBlockingStub(tracedChannel);
        ingestStub = IngestServiceGrpc.newBlockingStub(tracedChannel);
        ingestAsyncStub = IngestServiceGrpc.newStub(tracedChannel);
        healthStub = HealthServiceGrpc.newBlockingStub(tracedChannel);

        log.info("Connected to Knowledge Server at {}:{}", HOST, port);
    }

    /**
     * Test seam (683): wires a prebuilt channel (e.g. in-process) into the search path so
     * {@code search(Query)} runs without {@link ManagedChannelBuilder} and without signal-bus
     * port re-discovery. Production callers use {@link #connect(int)}.
     *
     * <p>Tempdoc 821 §3-C2 — the ingest stub is pinned too, so {@code scanRoot} (and with it the
     * watched-root scan arm's ScanRootRequest) is exercisable over an in-process server.
     */
    void connectForTesting(Channel channel) {
        searchStub = SearchServiceGrpc.newBlockingStub(channel);
        ingestStub = IngestServiceGrpc.newBlockingStub(channel);
        testChannelPinned = true;
    }

    private static Map<String, Object> buildGrpcRetryServiceConfig(int maxRetries) {
        GrpcRetryServiceConfig.RetryPolicyProfile profile =
            GrpcRetryServiceConfig.profile(
                "grpc-rkc-unavailable-v1",
                Math.max(0, maxRetries),
                GRPC_RETRY_INITIAL_BACKOFF_MS,
                GRPC_RETRY_MAX_BACKOFF_MS,
                GRPC_RETRY_BACKOFF_MULTIPLIER,
                List.of("UNAVAILABLE"));
        return GrpcRetryServiceConfig.forPolicyProfile(
            profile,
            List.of(
                SearchServiceGrpc.getServiceDescriptor().getName(),
                HealthServiceGrpc.getServiceDescriptor().getName()),
            List.of(
                new GrpcRetryServiceConfig.MethodScope(
                    IngestServiceGrpc.getServiceDescriptor().getName(),
                    List.of(
                        "IndexStatus",
                        "QueryPendingVdu",
                        "PruneMissing",
                        "RecoverVduProcessing",
                        "SyncDirectory"))));
    }

    /**
     * Reconnects using the signal bus to discover the current port.
     *
     * <p>No longer an override: review S6 deleted the facade's no-op declaration, because nothing
     * calls it. This whole class goes at item A10.
     */
    public void reconnect() {
        reconnect(-1);  // No PID validation
    }

    /**
     * Reconnects using the signal bus to discover the current port,
     * optionally validating that the worker PID matches the expected value.
     *
     * <p>PID validation prevents connecting to a stale/zombie process that wrote
     * its port between zeroPort() and the new worker starting.
     *
     * @param expectedPid if &gt; 0, validates the worker PID matches after connecting
     * @throws IllegalStateException if no valid port or PID mismatch detected
     */
    public void reconnect(long expectedPid) {
        int newPort = signalBus.readPort();
        if (newPort <= 0) {
            throw new IllegalStateException("No valid port in signal bus");
        }
        if (newPort != currentPort.get()) {
            log.info("Port changed from {} to {}, reconnecting", currentPort.get(), newPort);
            connect(newPort);
        }

        // Validate PID if requested
        if (expectedPid > 0) {
            HealthCheckResponse response = getHealthCheck();
            long actualPid = response.getPid();
            if (actualPid != expectedPid) {
                throw new IllegalStateException(
                    "PID mismatch after reconnect: expected " + expectedPid + ", got " + actualPid);
            }
            log.info("Worker PID validated after reconnect: {}", actualPid);
        }
    }

    // ========== Circuit Breaker Support ==========

    /**
     * Executes an RPC with circuit breaker protection.
     *
     * @param operation description of the operation (for logging)
     * @param rpc the RPC supplier to execute
     * @return the RPC result
     * @throws CircuitBreakerOpenException if the circuit breaker is open
     */
    private <T> T executeWithCircuitBreaker(String operation, java.util.function.Supplier<T> rpc) {
        if (!circuitBreaker.allowRequest()) {
            throw new CircuitBreakerOpenException("Circuit breaker open, request rejected: " + operation);
        }
        try {
            T result = rpc.get();
            circuitBreaker.recordSuccess();
            return result;
        } catch (io.grpc.StatusRuntimeException e) {
            log.debug(
                    "gRPC call failed: operation={}, status={}, description={}",
                    operation,
                    e.getStatus().getCode(),
                    e.getStatus().getDescription());
            if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE
                    || e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                circuitBreaker.recordFailure();
            }
            throw e;
        }
    }

    /**
     * Resets the circuit breaker to CLOSED state.
     * Intended for use after worker restart.
     */
    public void resetCircuitBreaker() {
        circuitBreaker.reset();
    }

    // ========== The transport seam ==========

    @Override
    protected <T> T executeSearchRpc(
            String operation,
            RpcDeadlineCategory category,
            Function<SearchServiceCalls, T> rpc) {
        if (!testChannelPinned) {
            ensureConnected();
            reconnect();
        }
        SearchServiceGrpc.SearchServiceBlockingStub stub =
            searchStub.withDeadlineAfter(deadline(category), TimeUnit.MILLISECONDS);
        return executeWithCircuitBreaker(operation, () -> rpc.apply(new SearchStubCalls(stub)));
    }

    @Override
    protected <T> T executeIngestRpc(
            String operation,
            RpcDeadlineCategory category,
            Function<IngestServiceCalls, T> rpc) {
        ensureConnected();
        reconnect();
        IngestServiceGrpc.IngestServiceBlockingStub stub =
            ingestStub.withDeadlineAfter(deadline(category), TimeUnit.MILLISECONDS);
        return executeWithCircuitBreaker(operation, () -> rpc.apply(new IngestStubCalls(stub)));
    }

    @Override
    protected <T> T executeHealthRpc(
            String operation,
            long callDeadlineMs,
            Function<HealthServiceCalls, T> rpc) {
        ensureConnected();
        reconnect();
        HealthServiceGrpc.HealthServiceBlockingStub stub =
            healthStub.withDeadlineAfter(callDeadlineMs, TimeUnit.MILLISECONDS);
        return executeWithCircuitBreaker(operation, () -> rpc.apply(stub::check));
    }

    @Override
    protected ScanRootProgress executeScanRoot(
            ScanRootRequest request,
            CancelToken cancelToken,
            Consumer<ScanRootProgress> progressConsumer) {
        if (!testChannelPinned) {
            ensureConnected();
            reconnect();
        }
        java.util.function.Supplier<ScanRootProgress> body =
                () -> executeWithCircuitBreaker(
                        "scanRoot", () -> drainScanIterator(request, progressConsumer));
        if (cancelToken == null) {
            return body.get();
        }
        // Run the gRPC call inside the cancellable context. gRPC binds the call to the current
        // context; cancelling the context cancels the call. The scan iterator receives a
        // StatusRuntimeException(CANCELLED) on the next .hasNext()/.next() and the wrapper below
        // converts it to a clean terminal event.
        try {
            return cancelToken.context().call(body::get);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("scanRoot under CancelToken failed", e);
        }
    }

    private ScanRootProgress drainScanIterator(
            ScanRootRequest request, Consumer<ScanRootProgress> progressConsumer) {
        java.util.Iterator<ScanRootProgress> events =
                ingestStub
                    .withDeadlineAfter(deadline(RpcDeadlineCategory.LONG_RUNNING),
                                       TimeUnit.MILLISECONDS)
                    .scanRoot(request);
        ScanRootProgress last = null;
        try {
            while (events.hasNext()) {
                ScanRootProgress event = events.next();
                last = event;
                progressConsumer.accept(event);
                if (event.getComplete()) {
                    // Server signaled completion; drain remaining (should be none).
                    break;
                }
            }
        } catch (io.grpc.StatusRuntimeException e) {
            // CANCELLED is the expected outcome when CancelToken.cancel() fires while the
            // iterator is mid-stream. Emit a synthetic terminal event so callers always see
            // a clean signal regardless of cancel timing.
            if (e.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
                ScanRootProgress cancelled = scanCancelledEvent();
                progressConsumer.accept(cancelled);
                return cancelled;
            }
            throw e;
        }
        if (last == null) {
            // Server closed the stream without emitting anything — synthesize a terminal
            // event so callers always see a clean signal.
            last = scanEmptyStreamEvent();
            progressConsumer.accept(last);
        }
        return last;
    }

    @Override
    public IndexingJobsStream subscribeIndexingJobs(
            Consumer<IndexingJobsFrame> onFrame,
            Consumer<Throwable> onError,
            Runnable onCompleted) {
        IngestServiceGrpc.IngestServiceStub asyncStub = ingestAsyncStub;
        if (asyncStub == null) {
            throw new KnowledgeServerNotConnectedException();
        }
        StreamObserver<IndexingJobsFrame> observer =
            new StreamObserver<>() {
                @Override
                public void onNext(IndexingJobsFrame frame) {
                    onFrame.accept(frame);
                }

                @Override
                public void onError(Throwable t) {
                    onError.accept(t);
                }

                @Override
                public void onCompleted() {
                    onCompleted.run();
                }
            };
        io.grpc.Context.CancellableContext ctx =
            io.grpc.Context.current().withCancellation();
        ctx.run(
            () ->
                asyncStub.subscribeIndexingJobs(
                    SubscribeIndexingJobsRequest.newBuilder().build(), observer));
        return () -> ctx.cancel(null);
    }

    @Override
    protected void closeTransport() {
        closeChannel();
    }

    private void ensureConnected() {
        if (channelRef.get() == null) {
            throw new KnowledgeServerNotConnectedException();
        }
    }

    private void closeChannel() {
        ManagedChannel channel = channelRef.getAndSet(null);
        if (channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
        searchStub = null;
        ingestStub = null;
        ingestAsyncStub = null;
        healthStub = null;
    }
}
