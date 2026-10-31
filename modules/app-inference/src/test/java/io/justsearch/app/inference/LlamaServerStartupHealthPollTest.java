/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * The startup health wait of {@link LlamaServerOps}: it polls {@code /health} soon after a launch
 * and backs off to a dedicated 100 ms cap (25, 50, then 100 ms), anew for every
 * physical launch attempt. A server that is healthy within milliseconds is seen within
 * milliseconds; a long load is polled serially with a 100 ms pause once capped.
 *
 * <p>The pauses are read through the launcher's {@code startupHealthPauses} seam where the tree has
 * it; on a tree without it nothing is recorded. A separate probe-count check uses a loading
 * window at the server, which needs no seam.
 */
@Timeout(60)
final class LlamaServerStartupHealthPollTest {
  /** The pauses a wait that never sees health takes: it doubles from 25 ms to the 100 ms cap. */
  private static final List<Long> LADDER = List.of(25L, 50L, 100L);

  @TempDir Path tmp;
  private LlamaServerOps ops;
  private HttpServer server;
  private final Map<Integer, AtomicInteger> loadingProbes = new ConcurrentHashMap<>();
  private int lastStartupPort;

  @AfterEach
  void stopEverything() throws Exception {
    try {
      if (ops != null) {
        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
        ops.shutdown();
      }
    } finally {
      if (server != null) server.stop(0);
      HealthyFakeLlamaServer.endAll(tmp.resolve("fake-llama"));
    }
  }

  // ==================== the first polls come quickly (new behaviour) ====================

  /**
   * A server still loading at the first poll and healthy right after is seen at the second poll,
   * one short pause later, not a flat 500 ms later.
   */
  @Test
  void aServerReadyJustAfterTheFirstPollIsSeenAfterOneShortPause() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    List<Long> pauses = recordPauses(ops);
    LlamaServerOps.StartResult start = legacyExternal(ops, health.port());
    health.answerLoading(1);

    ops.waitForServerHealth(start);

    List<Long> arrivals = health.waitArrivals();
    assertEquals(2, arrivals.size(), "one loading answer, then healthy: " + arrivals);
    assertEquals(List.of(25L), pauses, "one 25 ms pause");
  }

  // ==================== the backoff, its cap and serial polling (VH5) ====================

  /**
   * A long load: nine loading answers, then healthy. The pauses double from 25 ms and stop at the
   * 100 ms cap; the polls never overlap.
   */
  @Test
  void aLongLoadIsPolledSeriallyOnAGrowingPauseCappedAt100Ms() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    List<Long> pauses = recordPauses(ops);
    LlamaServerOps.StartResult start = legacyExternal(ops, health.port());
    health.answerLoading(9);

    ops.waitForServerHealth(start);

    assertEquals(List.of(25L, 50L, 100L, 100L, 100L, 100L, 100L, 100L, 100L), pauses,
        "a doubling pause from 25 ms that stops at the dedicated 100 ms startup cap");
    List<Long> arrivals = health.waitArrivals();
    assertEquals(10, arrivals.size(), "one poll per pause, plus the first: " + arrivals);
    assertEquals(1, health.mostInFlight(), "the polls are serial");
  }

  // ==================== each physical attempt starts short (VH4) ====================

  /**
   * A window that does not fit at the top rung exits after a while, and the launcher steps down one
   * rung and launches again. The second launch's wait starts at 25 ms again: it does not carry on
   * from where the first launch's wait had backed off to.
   */
  @Test
  void aSteppedDownRelaunchStartsAgainAtTheShortestPause() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--max-ctx", "16384", "--startup-until-signal");
    ops = ops(new AtomicInteger());
    List<Long> pauses = releaseManagedAttemptsAfterLoading(ops);
    LlamaServerOps.StartResult start = ops.startLlamaServer(managedRequest(executable));

    ops.waitForServerHealth(start);

    assertEquals(List.of(32768, 16384), windows(), "exactly one step-down");
    assertTrue(ops.activeManagedCandidateAlive(start), "the server at 16384 is serving");
    assertRestartedPauses(pauses, 2);
  }

  /** Unsupported reasoning flags cost one retry; that physical launch starts its pause anew. */
  @Test
  void aReasoningFlagRetryStartsAgainAtTheShortestPause() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--reject-reasoning", "--startup-until-signal");
    ops = ops(new AtomicInteger());
    List<Long> pauses = releaseManagedAttemptsAfterLoading(ops);
    var start = ops.startLlamaServer(managedRequest(executable));

    ops.waitForServerHealth(start);

    var launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(2, launches.size(), "one reasoning-flag retry");
    assertTrue(launches.getFirst().contains("--reasoning-budget"));
    assertTrue(!launches.getLast().contains("--reasoning-budget"));
    assertRestartedPauses(pauses, 2);
  }

  /** Both initial and one-shot waits get one fresh-port bind retry, with a fresh pause ladder. */
  @ParameterizedTest(name = "{displayName} recovery={0}")
  @ValueSource(booleans = {false, true})
  void aLostBindRetryStartsAgainAtTheShortestPause(boolean recovery) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--log-line", "main: couldn't bind HTTP server socket, hostname: 127.0.0.1",
        "--startup-until-signal");
    ops = ops(new AtomicInteger());
    List<Long> pauses = releaseManagedAttemptsAfterLoading(ops);
    var start = ops.startLlamaServer(managedRequest(executable));

    ModeTransitionException failure = assertThrows(ModeTransitionException.class, () -> {
      if (recovery) ops.waitForServerHealthOnce(start);
      else ops.waitForServerHealth(start);
    });

    assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
    var launches = HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama"));
    assertEquals(2, launches.size(), "one lost-bind retry");
    assertEquals(List.of(32768, 32768), windows(), "no context step-down");
    assertTrue(!flag(launches.getFirst(), "--port").equals(flag(launches.getLast(), "--port")),
        "retry uses a fresh port");
    assertRestartedPauses(pauses, 2);
  }

  /** A later start on the same launcher cannot inherit a slow wait's capped pause. */
  @Test
  void aLaterStartAfterASlowWaitStartsAgainAtTheShortestPause() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    List<Long> pauses = recordPauses(ops, recorded -> {
      if (recorded.size() >= 6) health.releaseLoading();
    });
    var first = legacyExternal(ops, health.port());
    health.answerLoading(Integer.MAX_VALUE);
    ops.waitForServerHealth(first);
    assertEquals(List.of(25L, 50L, 100L, 100L, 100L, 100L), pauses);
    assertEquals(7, health.waitArrivals().size());
    ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);

    var later = legacyExternal(ops, health.port());
    pauses = recordPauses(ops, recorded -> {
      if (recorded.size() >= 3) health.releaseLoading();
    });
    health.answerLoading(Integer.MAX_VALUE);
    ops.waitForServerHealth(later);

    assertEquals(LADDER, pauses, "later start restarts at 25 ms");
    assertEquals(4, health.waitArrivals().size());
  }

  /** One-shot recovery shares the short polling wait without changing its fallback policy. */
  @Test
  void aOneShotRecoveryStartsAgainAtTheShortestPause() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    List<Long> pauses = recordPauses(ops, recorded -> {
      if (recorded.size() >= 3) health.releaseLoading();
    });
    var start = legacyExternal(ops, health.port());
    health.answerLoading(Integer.MAX_VALUE);

    ops.waitForServerHealthOnce(start);

    assertEquals(LADDER, pauses);
    assertEquals(4, health.waitArrivals().size());
  }

  /** A real loading window distinguishes the startup cap from the former flat 500 ms pause. */
  @Test
  void aLoadingWindowReceivesAtLeastEightHealthProbes() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    var start = legacyExternal(ops, health.port());
    health.answerLoading(Integer.MAX_VALUE);
    health.loadingWindowNanos = TimeUnit.MILLISECONDS.toNanos(1_500);

    ops.waitForServerHealth(start);

    // The window starts at the first request and counts only requests inside it, even if a
    // scheduler stall delays the final healthy response. See startup-health-backoff.md.
    System.out.println("1500 ms loading window: " + health.probesInLoadingWindow + " /health probes");
    assertTrue(health.probesInLoadingWindow.get() >= 8,
        "at least eight health probes within 1500 ms; got " + health.probesInLoadingWindow);
  }

  /** The first successful probe does not pause at all. */
  @Test
  void aServerHealthyOnTheFirstProbeNeedsNoPause() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    List<Long> pauses = recordPauses(ops);
    var start = legacyExternal(ops, health.port());
    health.answerLoading(0);

    ops.waitForServerHealth(start);

    assertEquals(1, health.waitArrivals().size());
    assertEquals(List.of(), pauses);
  }

  // ==================== interrupt during a pause (VH3c, preservation) ====================

  /** An interrupt while the wait pauses between polls ends it as HEALTH_CHECK_INTERRUPTED. */
  @Test
  @Timeout(60)
  void anInterruptDuringAPauseEndsTheWaitAsInterrupted() throws Exception {
    ScriptedHealth health = scriptedServer();
    ops = ops(new AtomicInteger());
    LlamaServerOps.StartResult start = legacyExternal(ops, health.port());
    health.answerLoading(Integer.MAX_VALUE);
    CompletableFuture<Void> waiting = new CompletableFuture<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread waiter = Thread.ofPlatform().daemon().start(() -> {
      try {
        ops.waitForServerHealth(start);
        waiting.complete(null);
      } catch (ModeTransitionException | RuntimeException failure) {
        interrupted.set(Thread.currentThread().isInterrupted());
        waiting.completeExceptionally(failure);
      }
    });

    // Interrupt only while the waiter sleeps between polls, never while a probe is in flight.
    while (!waiting.isDone()) {
      if (pausing(waiter)) waiter.interrupt();
      Thread.sleep(5);
    }

    ExecutionException ended = assertThrows(ExecutionException.class, waiting::get);
    ModeTransitionException failure =
        assertInstanceOf(ModeTransitionException.class, ended.getCause());
    assertEquals(ModeTransitionException.Reason.HEALTH_CHECK_INTERRUPTED, failure.reason());
    assertTrue(interrupted.get(), "the waiter retains its interrupt flag");
    assertTrue(!health.waitArrivals().isEmpty(), "it was polling: " + health.waitArrivals());
  }

  // ==================== healthy, then gone (VH3a, preservation) ====================

  /**
   * A server that answers health and exits moments later, at different moments relative to the
   * health success and the arming of runtime monitoring: the exit is reported exactly once, either
   * as the start's failure or as one crash of the serving server, never both and never neither.
   */
  @ParameterizedTest(name = "{displayName} exit after {0} ms")
  @ValueSource(ints = {0, 5, 25, 100, 400})
  @Timeout(60)
  void aServerThatExitsJustAfterHealthIsReportedExactlyOnce(int exitAfterMs) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(tmp.resolve("fake-llama"),
        "--exit-after-health", String.valueOf(exitAfterMs));
    AtomicInteger crashes = new AtomicInteger();
    ops = ops(crashes);
    LlamaServerOps.StartResult start = ops.startLlamaServer(managedRequest(executable));

    int startFailures = 0;
    try {
      ops.waitForServerHealth(start);
    } catch (ModeTransitionException startFailure) {
      startFailures = 1;
    }
    long pid = Long.parseLong(Files.readAllLines(tmp.resolve("fake-llama").resolve("identities.txt"))
        .getFirst().split(" ")[0]);
    ProcessHandle.of(pid).ifPresent(server -> server.onExit().join());
    Thread.sleep(1_000); // room for the exit watcher to report, and to report twice were it to

    assertEquals(1, windows().size(), "one launch");
    assertEquals(1, startFailures + crashes.get(),
        "reported once: startFailures=" + startFailures + " crashes=" + crashes.get());
  }

  // ==================== helpers ====================

  private List<Long> releaseManagedAttemptsAfterLoading(LlamaServerOps target) throws Exception {
    return recordPauses(target, recorded -> {
      var attempt = splitIntoAttempts(recorded).getLast();
      int probes = loadingProbes.getOrDefault(lastStartupPort, new AtomicInteger()).get();
      if (attempt.size() >= LADDER.size() && probes >= 3) {
        try {
          HealthyFakeLlamaServer.signalStartup(tmp.resolve("fake-llama"), lastStartupPort);
        } catch (java.io.IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
    });
  }

  private static String flag(List<String> argv, String flag) {
    return argv.get(argv.indexOf(flag) + 1);
  }

  private void assertRestartedPauses(List<Long> pauses, int count) {
    var attempts = splitIntoAttempts(pauses);
    assertEquals(count, attempts.size(), "one fresh pause ladder per wait: " + pauses);
    for (var attempt : attempts) {
      assertTrue(attempt.size() >= LADDER.size(), "wait reached its cap: " + attempt);
      assertEquals(LADDER, attempt.subList(0, LADDER.size()));
      assertTrue(attempt.subList(LADDER.size(), attempt.size()).stream()
          .allMatch(pause -> pause == 100L), "all later pauses stay capped: " + attempt);
    }
    assertEquals(count, loadingProbes.size(), "each attempt answered loading");
    assertTrue(loadingProbes.values().stream().allMatch(probes -> probes.get() >= 3),
        "each attempt answered at least three loading probes: " + loadingProbes);
  }

  private static boolean pausing(Thread thread) {
    if (thread.getState() != Thread.State.TIMED_WAITING
        && thread.getState() != Thread.State.WAITING) {
      return false;
    }
    StackTraceElement[] frames = thread.getStackTrace();
    boolean inWait = false;
    boolean inSleep = false;
    for (StackTraceElement frame : frames) {
      if (frame.getMethodName().equals("awaitServerHealth")) {
        inWait = true;
        break;
      }
      if (frame.getClassName().equals(Thread.class.getName())
          && frame.getMethodName().startsWith("sleep")) {
        inSleep = true;
      }
      if (frame.getClassName().startsWith("java.net.http")
          || frame.getClassName().startsWith("jdk.internal.net.http")) {
        return false;
      }
    }
    return inWait && inSleep;
  }

  /** Splits recorded pauses into one run per wait: every wait's first pause is 25 ms. */
  private static List<List<Long>> splitIntoAttempts(List<Long> pauses) {
    List<List<Long>> runs = new ArrayList<>();
    for (long pause : pauses) {
      if (runs.isEmpty() || pause == LADDER.getFirst()) runs.add(new ArrayList<>());
      runs.getLast().add(pause);
    }
    return runs;
  }

  /**
   * Records the pauses of {@code target}'s startup health waits through its seam. On a tree
   * without the seam the list stays empty.
   */
  private static List<Long> recordPauses(LlamaServerOps target) throws Exception {
    return recordPauses(target, ignored -> {});
  }

  private static List<Long> recordPauses(
      LlamaServerOps target, Consumer<List<Long>> afterPause) throws Exception {
    List<Long> pauses = new CopyOnWriteArrayList<>();
    Field seam;
    try {
      seam = LlamaServerOps.class.getDeclaredField("startupHealthPauses");
    } catch (NoSuchFieldException absent) {
      return pauses;
    }
    seam.setAccessible(true);
    seam.set(target, (LongConsumer) pause -> {
      pauses.add(pause);
      afterPause.accept(pauses);
    });
    return pauses;
  }

  private List<Integer> windows() throws Exception {
    return HealthyFakeLlamaServer.launches(tmp.resolve("fake-llama")).stream()
        .map(argv -> Integer.parseInt(argv.get(argv.indexOf("-c") + 1))).toList();
  }

  private LlamaServerOps ops(AtomicInteger crashes) throws Exception {
    GpuCapabilitiesService gpu = Mockito.mock(GpuCapabilitiesService.class);
    HttpClient client = Mockito.spy(HttpClient.newHttpClient());
    Mockito.doAnswer(call -> {
      HttpRequest request = call.getArgument(0);
      if (request.uri().getPath().equals("/health")
          && StackWalker.getInstance().walk(frames -> frames.anyMatch(
              frame -> frame.getMethodName().equals("awaitServerHealth")))) {
        lastStartupPort = request.uri().getPort();
        HttpResponse<?> response = (HttpResponse<?>) call.callRealMethod();
        if (response.statusCode() == 503) {
          loadingProbes.computeIfAbsent(lastStartupPort, ignored -> new AtomicInteger())
              .incrementAndGet();
        }
        return response;
      }
      return call.callRealMethod();
    }).when(client).send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any());
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        client, new ObjectMapper(), gpu, () -> Mode.OFFLINE,
        new NoopPropsObserver(), guard -> crashes.incrementAndGet(), (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
  }

  /** A managed launch of {@code executable} with a GPU-derived window (ladder 32768, 16384, ...). */
  private LlamaServerOps.StartRequest managedRequest(Path executable) throws Exception {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    String cache = System.getenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR");
    if (cache != null && !cache.isBlank()) {
      builder.putDefault("justsearch.ort.optimized_cache_dir", cache);
    }
    InferenceConfig config = new InferenceConfig(executable, model, null, freePort(), 4096, 99, false);
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(config, builder.build()),
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
  }

  /** The user's own server at {@code port}, which the legacy policy adopts. */
  private LlamaServerOps.StartResult legacyExternal(LlamaServerOps target, int port)
      throws Exception {
    Path executable = tmp.resolve("external-llama.exe");
    Path model = tmp.resolve("external.gguf");
    if (!Files.exists(executable)) Files.createFile(executable);
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putDefault("justsearch.inference.external_endpoint", "http://127.0.0.1:" + port);
    builder.putDefault("justsearch.context.size", "4096");
    builder.putDefault("justsearch.llm.slots", "1");
    builder.putDefault("justsearch.llm.use_thinking", "false");
    builder.putDefault("justsearch.llm.reasoning_budget", "0");
    builder.putDefault("justsearch.data.dir", tmp.resolve("data").toString());
    InferenceConfig inference = new InferenceConfig(executable, model, null, port, 4096, 4, false);
    return target.startLlamaServer(new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(inference, builder.build()),
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL));
  }

  /**
   * An in-process llama-shaped server. It answers health 200 until {@link
   * ScriptedHealth#answerLoading} arms it; from then on it times every health request's arrival,
   * answers the first {@code n} of them 503 "Loading model" and the rest 200.
   */
  private ScriptedHealth scriptedServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    ScriptedHealth health = new ScriptedHealth(server.getAddress().getPort());
    server.createContext("/health", exchange -> {
      int inFlight = health.inFlight.incrementAndGet();
      health.mostInFlight.accumulateAndGet(inFlight, Math::max);
      try {
        boolean loading = false;
        if (health.armed.get()) {
          long now = System.nanoTime();
          health.arrivals.add(now);
          loading = health.loadingLeft.getAndDecrement() > 0;
          if (health.loadingWindowNanos > 0) {
            loading = now - health.arrivals.getFirst() < health.loadingWindowNanos;
            if (loading) health.probesInLoadingWindow.incrementAndGet();
          }
        }
        byte[] body = (loading
            ? "{\"error\":{\"code\":503,\"message\":\"Loading model\"}}"
            : "{\"status\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(loading ? 503 : 200, body.length);
        exchange.getResponseBody().write(body);
      } finally {
        exchange.close();
        health.inFlight.decrementAndGet();
      }
    });
    server.createContext("/props", exchange -> {
      byte[] body = "{\"model_path\":\"external.gguf\",\"n_ctx\":4096}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "startup-health-poll");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
    return health;
  }

  private static final class ScriptedHealth {
    private final int port;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger loadingLeft = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    private final List<Long> arrivals = new CopyOnWriteArrayList<>();
    private volatile long loadingWindowNanos;
    private final AtomicInteger probesInLoadingWindow = new AtomicInteger();

    ScriptedHealth(int port) {
      this.port = port;
    }

    int port() {
      return port;
    }

    /** From now on: time each health request, and answer the first {@code n} with 503. */
    void answerLoading(int n) {
      arrivals.clear();
      loadingLeft.set(n);
      mostInFlight.set(0);
      armed.set(true);
    }

    void releaseLoading() {
      loadingLeft.set(0);
    }

    /** Arrival times (monotonic nanos) of the health requests since {@link #answerLoading}. */
    List<Long> waitArrivals() {
      return List.copyOf(arrivals);
    }

    int mostInFlight() {
      return mostInFlight.get();
    }
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {}

    @Override
    public void onContextTokensObserved(int contextTokens) {}

    @Override
    public String observedModelId() {
      return null;
    }

    @Override
    public Integer observedContextTokens() {
      return null;
    }
  }
}
