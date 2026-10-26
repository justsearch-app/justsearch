/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.QueryDevice;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warms the serving set's query devices before readiness (Spec D §4.2, S21). Deferred SPLADE and
 * reranker CPU backups may prepare on first CPU demand; queries never create GPU sessions.
 *
 * <p>A pass runs one FOREGROUND encode, allowed to create sessions, through each configured query
 * encoder that is not warm, each under {@code InferenceRequest.within(FOREGROUND, budget,
 * cancel)}. A session is warm when a query would run on it now; that state lives on the native
 * session handle and is lost whenever a session is created or released, so every publication,
 * recomposition and GPU release makes the encoders not warm again. The runner reruns on a session
 * generation change, and checks every second for encoders that lost their warmth by other paths (a
 * recomposed set has new handles). After a failed pass it retries after the budget, or sooner on a
 * generation change.
 *
 * <p>Completion is reported to {@link QueryPrewarmListener}s (§4.3): the boot sequence waits on it
 * before it reports the models ready.
 *
 * <p>After a pass has reported ready, GPU encoders other than SPLADE and reranker warm their CPU
 * session, which a query the device turns away (busy or stuck) runs on instead of falling back to
 * keyword search. SPLADE and reranker prepare their CPU backups only on real CPU demand. This
 * warm-up does not delay readiness, and its failure leaves those queries to the keyword fallback.
 */
final class QueryEncoderPrewarm implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(QueryEncoderPrewarm.class);

  /** How often the runner looks for query encoders that are no longer warm. */
  static final long CHECK_INTERVAL_MS = 1_000L;

  /** How long shutdown waits for a pass in progress. */
  static final long CLOSE_WAIT_MS = 5_000L;

  /** The completion signal of a pass (Spec D §4.3). */
  interface QueryPrewarmListener {
    /** Every configured query encoder is warm; {@code device} is GPU only when all run on GPU. */
    void onReady(Set<EncoderRole> warmed, QueryDevice device);

    /** The pass could not warm {@code role}; the runner retries. */
    void onFailed(EncoderRole role, String reason);
  }

  /** One encode through a query encoder; true when it produced output. */
  @FunctionalInterface
  interface Encode {
    boolean run(String text, InferenceRequest request) throws Exception;
  }

  /** Warms an encoder's CPU session for queries the device turns away; true when it is warm. */
  @FunctionalInterface
  interface CpuWarm {
    boolean run(InferenceRequest request);
  }

  /**
   * One configured query encoder of the serving set.
   *
   * @param owner the encoder object, identifying it for generation-listener registration
   * @param cpuWarm warms the encoder's CPU session when it serves queries on the GPU
   */
  record Target(
      EncoderRole role,
      Object owner,
      BooleanSupplier warm,
      Supplier<QueryDevice> device,
      Consumer<Runnable> generationListener,
      Encode encode,
      CpuWarm cpuWarm, BooleanSupplier retryDue) {
    Target {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(warm, "warm");
      Objects.requireNonNull(device, "device");
      Objects.requireNonNull(generationListener, "generationListener");
      Objects.requireNonNull(encode, "encode");
      Objects.requireNonNull(cpuWarm, "cpuWarm");
      Objects.requireNonNull(retryDue, "retryDue");
    }

    Target(EncoderRole role, Object owner, BooleanSupplier warm, Supplier<QueryDevice> device,
        Consumer<Runnable> generationListener, Encode encode, CpuWarm cpuWarm) {
      this(role, owner, warm, device, generationListener, encode, cpuWarm, () -> false);
    }

    /** A target with no CPU session to warm. */
    Target(
        EncoderRole role,
        Object owner,
        BooleanSupplier warm,
        Supplier<QueryDevice> device,
        Consumer<Runnable> generationListener,
        Encode encode) {
      this(role, owner, warm, device, generationListener, encode, request -> false);
    }
  }

  /** The serving set's query encoders, held for as long as a pass uses them. */
  record Selection(List<Target> targets, Runnable release) implements AutoCloseable {
    static final Selection EMPTY = new Selection(List.of(), () -> {});

    Selection {
      targets = List.copyOf(targets);
      Objects.requireNonNull(release, "release");
    }

    @Override
    public void close() {
      release.run();
    }
  }

  private final Supplier<Selection> selection;
  private final Duration budget;
  private final ScheduledExecutorService executor;
  private final List<QueryPrewarmListener> listeners = new CopyOnWriteArrayList<>();
  private final Set<Object> listening = Collections.synchronizedSet(
      Collections.newSetFromMap(new WeakHashMap<>()));
  private final AtomicBoolean passQueued = new AtomicBoolean();
  private final AtomicLong passes = new AtomicLong();
  private volatile boolean backingOff;
  private volatile long retryAtNanos;
  private volatile boolean closed;
  private volatile ScheduledFuture<?> check;

  QueryEncoderPrewarm(
      Supplier<Selection> selection, Duration budget, ScheduledExecutorService executor) {
    this.selection = Objects.requireNonNull(selection, "selection");
    this.budget = Objects.requireNonNull(budget, "budget");
    if (budget.isZero() || budget.isNegative()) {
      throw new IllegalArgumentException("budget must be positive");
    }
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  void addListener(QueryPrewarmListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  void removeListener(QueryPrewarmListener listener) {
    listeners.remove(listener);
  }

  /** Starts the periodic warmth check and queues the first pass. */
  void start() {
    try {
      check = executor.scheduleWithFixedDelay(
          this::checkWarmth, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException refused) {
      // Passes still run on generation changes; only the periodic check is missing.
      log.warn("Query prewarm periodic check refused: {}", refused.getMessage());
    }
    requestPass();
  }

  /** Whether every configured query encoder of the serving set is warm now. */
  boolean allWarm() {
    try (Selection held = select()) {
      for (Target target : held.targets()) {
        if (!target.warm().getAsBoolean()) return false;
      }
      return true;
    }
  }

  /** Queues one pass, unless one is already queued. */
  void requestPass() {
    if (closed || !passQueued.compareAndSet(false, true)) return;
    try {
      executor.execute(this::runPass);
    } catch (RejectedExecutionException closing) {
      passQueued.set(false);
    }
  }

  private void onGenerationChanged() {
    backingOff = false;
    requestPass();
  }

  private void checkWarmth() {
    try {
      if (closed) return;
      if (backingOff && System.nanoTime() - retryAtNanos < 0) return;
      try (Selection held = select()) {
        if (held.targets().stream().anyMatch(t -> !t.warm().getAsBoolean() || t.retryDue().getAsBoolean())) {
          requestPass();
        }
      }
    } catch (RuntimeException e) {
      log.debug("Query prewarm check failed: {}", e.toString());
    }
  }

  private void runPass() {
    passQueued.set(false);
    if (closed) return;
    Set<EncoderRole> warmed = EnumSet.noneOf(EncoderRole.class);
    Map<EncoderRole, String> failed = new EnumMap<>(EncoderRole.class);
    boolean allGpu = true;
    try (Selection held = select()) {
      for (Target target : held.targets()) {
        if (closed) return;
        try {
          listen(target);
          if (!target.warm().getAsBoolean() || target.retryDue().getAsBoolean()) {
            String failure = encode(target);
            if (failure == null && !target.warm().getAsBoolean()) {
              failure = "the encode ran but its query session is not warm";
            }
            if (failure != null) {
              failed.put(target.role(), failure);
              continue;
            }
          }
          warmed.add(target.role());
          if (target.device().get() != QueryDevice.GPU) allGpu = false;
        } catch (RuntimeException e) {
          failed.put(target.role(), e.toString());
        }
      }
    }
    if (closed) return;
    if (failed.isEmpty()) {
      backingOff = false;
      QueryDevice device = !warmed.isEmpty() && allGpu ? QueryDevice.GPU : QueryDevice.CPU;
      if (!warmed.isEmpty()) log.info("Query encoders warm: {} on {}", warmed, device);
      for (QueryPrewarmListener listener : listeners) notify(() -> listener.onReady(warmed, device));
      warmCpuForQueries();
    } else {
      retryAtNanos = System.nanoTime() + budget.toNanos();
      backingOff = true;
      failed.forEach((role, reason) -> {
        log.warn("Query prewarm of {} failed (retrying): {}", role, reason);
        for (QueryPrewarmListener listener : listeners) {
          notify(() -> listener.onFailed(role, reason));
        }
      });
    }
  }

  /** Warms the CPU session of every encoder that serves queries on the GPU now. */
  private void warmCpuForQueries() {
    try (Selection held = select()) {
      for (Target target : held.targets()) {
        if (closed) return;
        try {
          if (target.device().get() != QueryDevice.GPU
              || target.role() == EncoderRole.SPLADE
              || target.role() == EncoderRole.RERANKER) continue;
          InferenceRequest request = InferenceRequest.within(
              InferenceRequest.Urgency.FOREGROUND, budget, () -> closed);
          if (!target.cpuWarm().run(request)) {
            log.info("{}: no warm CPU session; a query the device turns away falls back to keyword",
                target.role());
          }
        } catch (RuntimeException e) {
          log.warn("CPU warm-up of {} failed: {}", target.role(), e.toString());
        }
      }
    }
  }

  /** Returns null when the encode produced output, otherwise why it did not. */
  private String encode(Target target) {
    InferenceRequest request = InferenceRequest.within(
        InferenceRequest.Urgency.FOREGROUND, budget, () -> closed);
    // A distinct text per pass: a cached query embedding would not run the session.
    String text = "query prewarm " + passes.incrementAndGet();
    try {
      return target.encode().run(text, request) ? null : "the encode produced no output";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } catch (Exception e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  private void listen(Target target) {
    if (listening.add(target.owner())) {
      target.generationListener().accept(this::onGenerationChanged);
    }
  }

  private Selection select() {
    try {
      Selection selected = selection.get();
      return selected != null ? selected : Selection.EMPTY;
    } catch (RuntimeException retiring) {
      log.debug("No query encoders to prewarm: {}", retiring.toString());
      return Selection.EMPTY;
    }
  }

  private static void notify(Runnable notification) {
    try {
      notification.run();
    } catch (RuntimeException e) {
      log.warn("Query prewarm listener failed", e);
    }
  }

  /**
   * Stops the runner and waits for a pass in progress at most {@link #CLOSE_WAIT_MS}; the pass sees
   * cancellation at its next acquisition check. A native run that does not return keeps its lease,
   * which the session owner's bounded retirement then reports, and the executor registration's own
   * bounded close reaps the thread.
   */
  @Override
  public void close() {
    closed = true;
    ScheduledFuture<?> scheduled = check;
    if (scheduled != null) scheduled.cancel(false);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        log.warn("Query prewarm pass still running after {}ms at shutdown", CLOSE_WAIT_MS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
