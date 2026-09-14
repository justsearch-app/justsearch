package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.lifecycle.AgentLifecycle;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 561 P-D2 — the presence axis has a REAL background producer (not an FE-only banner). A
 * background run proceeds without a watcher, persists to the ONE durable AgentRunStore record stamped
 * {@code background=true}, and the render-on-return inbox source ({@code presenceSince}) surfaces it.
 */
final class BackgroundRunServiceTest {

  @TempDir Path operationDirectory;
  private io.justsearch.app.observability.operations.SqliteOperationStore operationStore;
  private OperationAttemptRunner attempts;
  @org.junit.jupiter.api.BeforeEach
  void openOperationRunner() throws Exception {
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(operationDirectory.resolve("operations.db"));
    attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operationStore,
        Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN));
  }
  @org.junit.jupiter.api.AfterEach
  void closeOperationRunner() throws Exception {
    if (operationStore != null) operationStore.close();
  }


  /** A fake agent loop that persists + completes a run (as the real loop does) and emits its start. */
  private static final class FakeLoop implements AgentService {
    private final AgentRunStore runStore;
    private final AtomicInteger bodyCalls = new AtomicInteger();

    FakeLoop(AgentRunStore runStore) {
      this.runStore = runStore;
    }

    @Override
    public void runAgent(
        AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      runAgent(request, eventConsumer, false, engineContext);
    }

    // Mirrors AgentLoopService: a background run is stamped on its durable record (P-D2), so the
    // presence projection can surface it; an interactive run is not.
    @Override
    public void runAgent(
        AgentRequest request,
        Consumer<AgentEvent> eventConsumer,
        boolean background,
        EngineContext engineContext) {
      bodyCalls.incrementAndGet();
      String sid = UUID.randomUUID().toString();
      runStore.startRun(sid, request, request.messages(), 1000);
      if (background) {
        runStore.markBackground(sid);
      }
      // Complete the run (state DONE, updatedAt = now) — mirrors the real loop's terminal checkpoint.
      runStore.updateCheckpoint(sid, "DONE", request.messages(), 1, 0, 120, "");
      eventConsumer.accept(new AgentEvent.SessionStarted(sid));
    }

    int bodyCalls() {
      return bodyCalls.get();
    }

    @Override
    public Map<String, Object> sessionSnapshot(String sessionId) { return runStore.readSnapshot(sessionId); }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    // Mirrors AgentLoopService.presenceSince — the real store projection under test.
    @Override
    public List<AgentLifecycle> presenceSince(Instant since) {
      return runStore.presenceRunsSince(since).stream()
          .map(AgentLifecycleProjection::fromRun)
          .toList();
    }
  }

  private static final class CountingRegistry implements EngineExecutorRegistry {
    private final CountingRegistration registration = new CountingRegistration();

    @Override
    public Registration register(EngineExecutorSpec spec) {
      registration.spec = spec;
      return registration;
    }

    @Override
    public Limits limits(EngineExecutorSpec.Kind kind) {
      return new Limits(4, 64);
    }

    @Override
    public int maxConcurrentWork() {
      return 64;
    }

    @Override
    public int retryAfterSeconds() {
      return 1;
    }

    @Override
    public EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      registration.close();
    }
  }

  private static final class CountingRegistration implements EngineExecutorRegistry.Registration {
    private final AtomicInteger scheduledCount = new AtomicInteger();
    private EngineExecutorSpec spec;
    private CountingScheduler scheduler;

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      scheduler = new CountingScheduler(factory, scheduledCount);
      return scheduler;
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      if (scheduler != null) scheduler.shutdownNow();
    }
  }

  private static final class CountingScheduler extends ScheduledThreadPoolExecutor {
    private final AtomicInteger scheduledCount;

    private CountingScheduler(ThreadFactory factory, AtomicInteger scheduledCount) {
      super(1, factory);
      this.scheduledCount = scheduledCount;
      setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      scheduledCount.incrementAndGet();
      return super.schedule(command, delay, unit);
    }
  }

  @Test
  @DisplayName("a background run persists stamped background=true and presenceSince surfaces it")
  void backgroundRunIsProducedAndSurfacedOnReturn(@TempDir Path tmp) {
    var runStore = new AgentRunStore(tmp.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    var registry = new TestEngineExecutors();
    var background = new BackgroundRunService(attempts, loop, registry);
    try {
      Instant before = Instant.now().minusSeconds(3600);

      String sid =
          background.runInBackground(
              AgentRequest.singleTurn(
                  List.of(Map.of("role", "user", "content", "reindex the new files overnight"))),
              EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);

    // The producer ran a real detached run and stamped the durable record.
      assertNotNull(sid, "the run started and its sessionId was captured");
      Map<String, Object> meta = runStore.readSnapshot(sid);
      assertNotNull(meta);
      assertEquals(Boolean.TRUE, meta.get("background"), "the run is marked background");
      assertEquals("DONE", meta.get("state"));

    // The render-on-return inbox source: "what completed while you were away".
      List<AgentLifecycle> presence = loop.presenceSince(before);
      assertEquals(1, presence.size(), "the background run is in the presence projection");
      assertEquals(sid, presence.get(0).sessionId());

    // A user who looked AFTER the run sees nothing new (the since-filter is exclusive).
      assertTrue(
          loop.presenceSince(Instant.now().plusSeconds(3600)).isEmpty(),
          "no background work after the user's last-seen mark");
    } finally {
      background.shutdown();
      registry.close();
    }
  }

  @Test
  @DisplayName("a supplied key deduplicates pending and terminal schedules")
  void suppliedKeyDeduplicatesPendingAndTerminalAttempts() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    AgentRequest request = request("one scheduled run");
    String pendingKey = OperationKeys.generate(Clock.systemUTC());
    try (var registry = new TestEngineExecutors()) {
      var pendingService = new BackgroundRunService(attempts, loop, registry);
      try {
        var first = pendingService.schedule(
            pendingKey, request, java.time.Duration.ofDays(1),
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
        var duplicate = pendingService.schedule(
            pendingKey, request, java.time.Duration.ofDays(1),
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
        assertFalse(first.existing());
        assertEquals(pendingKey, first.accepted().key());
        assertTrue(duplicate.existing());
        assertEquals(first.accepted().id(), duplicate.accepted().id());
        assertEquals(0, loop.bodyCalls(), "a pending duplicate must not enter the agent body");
      } finally {
        pendingService.shutdown();
      }

      AgentRequest terminalRequest = request("one terminal scheduled run");
      String terminalKey = OperationKeys.generate(Clock.systemUTC());
      var terminalService = new BackgroundRunService(attempts, loop, registry);
      try {
        var first = terminalService.schedule(
            terminalKey, terminalRequest, java.time.Duration.ZERO,
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
        assertEquals(terminalKey, first.accepted().key());
        first.completion().toCompletableFuture().join();
        assertEquals(1, loop.bodyCalls());

        var replay = terminalService.schedule(
            terminalKey, terminalRequest, java.time.Duration.ZERO,
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
        assertTrue(replay.existing());
        replay.completion().toCompletableFuture().join();
        assertEquals(1, loop.bodyCalls(), "a terminal replay must not enter the agent body again");
      } finally {
        terminalService.shutdown();
      }
    }
  }

  @Test
  @DisplayName("a supplied key binds the request and normalized delay")
  void changedRequestOrDelayUnderSameKeyIsRejected() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    String key = OperationKeys.generate(Clock.systemUTC());
    AgentRequest original = request("original prompt");
    try (var registry = new TestEngineExecutors()) {
      var background = new BackgroundRunService(attempts, loop, registry);
      try {
        background.schedule(
            key, original, java.time.Duration.ofHours(1),
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
        var promptConflict = assertThrows(OperationStoreException.class,
            () -> background.schedule(
                key, request("changed prompt"), java.time.Duration.ofHours(1),
                EngineContextTestFixtures.AGENT_LOOP_BACKGROUND));
        assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, promptConflict.code());

        var delayConflict = assertThrows(OperationStoreException.class,
            () -> background.schedule(
                key, original, java.time.Duration.ofHours(2),
                EngineContextTestFixtures.AGENT_LOOP_BACKGROUND));
        assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, delayConflict.code());
        assertEquals(OperationState.ACCEPTED, operationStore.find(key).orElseThrow().state());
      } finally {
        background.shutdown();
      }
    }
  }

  @Test
  @DisplayName("concurrent duplicate schedules install one timer")
  void concurrentDuplicateSchedulesCreateOneFreshTimer() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    AgentRequest request = request("concurrent prompt");
    String key = OperationKeys.generate(Clock.systemUTC());
    var registry = new CountingRegistry();
    try (var callers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var background = new BackgroundRunService(attempts, loop, registry);
      try {
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<OperationAttemptRunner.PreparedAttempt>>();
        for (int i = 0; i < 16; i++) {
          futures.add(callers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return background.schedule(
                key, request, java.time.Duration.ofDays(1),
                EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
          }));
        }
        start.countDown();
        int fresh = 0;
        long acceptedId = -1;
        for (var future : futures) {
          var prepared = future.get(5, TimeUnit.SECONDS);
          if (!prepared.existing()) fresh++;
          if (acceptedId < 0) acceptedId = prepared.accepted().id();
          assertEquals(acceptedId, prepared.accepted().id());
        }
        assertEquals(1, fresh);
        assertEquals(1, registry.registration.scheduledCount.get());
        assertEquals(0, loop.bodyCalls());
      } finally {
        background.shutdown();
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @DisplayName("a closed scheduler replays a recorded terminal result")
  void closedSchedulerAnswersMatchingRecordedAttempt() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    AgentRequest request = request("recorded before close");
    String key = OperationKeys.generate(Clock.systemUTC());
    try (var registry = new TestEngineExecutors()) {
      var background = new BackgroundRunService(attempts, loop, registry);
      var first = background.schedule(
          key, request, java.time.Duration.ZERO,
          EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
      first.completion().toCompletableFuture().join();
      background.shutdown();

      var replay = background.schedule(
          key, request, java.time.Duration.ZERO,
          EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
      assertTrue(replay.existing());
      assertEquals(first.accepted().id(), replay.accepted().id());
      assertEquals(OperationState.COMPLETE, replay.completion().toCompletableFuture().join().state());
      assertEquals(1, loop.bodyCalls());
    }
  }

  private static AgentRequest request(String prompt) {
    return AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", prompt)));
  }

  @Test
  @DisplayName("an INTERACTIVE run is not in the presence projection (only background runs are)")
  void interactiveRunsAreNotPresence(@TempDir Path tmp) {
    var runStore = new AgentRunStore(tmp.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);

    // Drive runAgent directly (an interactive run) — NOT through BackgroundRunService, so unmarked.
    loop.runAgent(
        AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "find invoices"))),
        ev -> {}, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(
        loop.presenceSince(Instant.now().minusSeconds(3600)).isEmpty(),
        "interactive runs are not background — the presence inbox stays empty");
  }

  @Test
  @DisplayName("a closed scheduler preserves its rejection when failed refusal persistence is injected")
  void closedSchedulerRejectionRetainsPersistenceFailure() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    try (var registry = new TestEngineExecutors()) {
      var background = new BackgroundRunService(attempts, loop, registry);
      background.shutdown();
      refuseFailedTerminalTransition();

      var rejection = assertThrows(RejectedExecutionException.class,
          () -> background.schedule(
              AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "closed"))),
              java.time.Duration.ZERO, EngineContextTestFixtures.AGENT_LOOP_BACKGROUND));
      assertTrue(java.util.Arrays.stream(rejection.getSuppressed())
          .anyMatch(failure -> failure instanceof OperationStoreException storeFailure
              && storeFailure.code() == OperationStoreException.Code.STORAGE_FAILED),
          "failed durable refusal is preserved on the original scheduler rejection");

      var degradation = attempts.persistenceFailure().toCompletableFuture().join();
      assertEquals(OperationState.FAILED, degradation.intendedState());
      assertEquals(OperationState.ACCEPTED,
          operationStore.find(degradation.operationKey()).orElseThrow().state());
      assertEquals(0, loop.bodyCalls(), "a rejected schedule never enters the agent body");
    }
  }

  @Test
  @DisplayName("shutdown reports failed refusal persistence and leaves its accepted timer unresolved")
  void shutdownRefusalPersistenceFailureIsObservable() throws Exception {
    var runStore = new AgentRunStore(operationDirectory.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    try (var registry = new TestEngineExecutors()) {
      var background = new BackgroundRunService(attempts, loop, registry);
      var attempt = background.schedule(
          AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "long delay"))),
          java.time.Duration.ofDays(1), EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);
      String key = attempt.accepted().key();
      refuseFailedTerminalTransition();

      var shutdownFailure = assertThrows(OperationStoreException.class, background::shutdown);
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, shutdownFailure.code());
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      var completionFailure = assertThrows(CompletionException.class,
          () -> attempt.completion().toCompletableFuture().join());
      assertTrue(completionFailure.getCause() instanceof OperationStoreException,
          "completion exposes the failed durable refusal");
      assertEquals(OperationState.ACCEPTED, operationStore.find(key).orElseThrow().state());

      var degradation = attempts.persistenceFailure().toCompletableFuture().join();
      assertEquals(key, degradation.operationKey());
      assertEquals(OperationState.FAILED, degradation.intendedState());
      assertEquals(0, loop.bodyCalls(), "shutdown refusal cancels the timer before the agent body");
    }
  }

  private void refuseFailedTerminalTransition() throws Exception {
    try (var connection = java.sql.DriverManager.getConnection(
            "jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER refuse_failed_terminal BEFORE UPDATE ON operations "
          + "WHEN NEW.state = 'FAILED' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
    }
  }
}
