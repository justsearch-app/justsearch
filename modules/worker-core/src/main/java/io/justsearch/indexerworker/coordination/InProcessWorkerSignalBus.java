/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link WorkerSignalBus} for an index half running inside the Engine JVM (lane F stage A item
 * A6).
 *
 * <p>Every signal the memory-mapped bus carried existed because the two halves were two processes.
 * Inside one JVM each one either collapses to a field or stops being a question at all:
 *
 * <ul>
 *   <li><b>{@code mainGpuActive} / {@code energyReduced}</b> — the only two that survive, and they
 *       read the {@link GpuSchedulingGauge} the composition root shares with the writers
 *       ({@code InferenceWiring} and {@code EnergyStatePoller}). No byte, no polling lag: the
 *       reader observes the writer's field.
 *   <li><b>heartbeat / shutdown / suicide pact</b> — the "did the Head die?" question. In one JVM
 *       the answer is always no: if the Engine dies, the index half died with it. {@link
 *       #shouldDie()} is therefore permanently {@code false}, and the sentinel thread's kill path
 *       has no producer. This is deliberate, not an omission — a process that killed itself
 *       because it could not find its own heartbeat would be the defect.
 *   <li><b>port publication</b> — {@link #writePort(int)} records the port for diagnostics and
 *       publishes it nowhere: there is no second process to discover it. Item A9 removes the
 *       caller.
 *   <li><b>reload signal</b> — re-homed onto a request FILE,
 *       {@code <dataDir>/runtime/dev-reload.request} (review S2). It was deferred to item A18 and
 *       that was wrong: the trigger was the only part of hot reload that lived in the memory-mapped
 *       region, so deferring it did not postpone the change, it turned the dev loop off. From item
 *       A6 the sentinel polled a bus whose {@code isReloadRequested()} was the interface default,
 *       {@code false} — the MCP {@code reload} tool went on reporting a successful bytecode push
 *       and services were never reconstructed. A file is the right shape for what this always was:
 *       a cross-process poke from a dev tool that is not in this JVM, and the one thing the
 *       memory-mapped bus was genuinely still being used for.
 * </ul>
 *
 * <p>Thread-safe: the gauge is, and the two fields this class owns are atomic/volatile.
 */
public final class InProcessWorkerSignalBus implements WorkerSignalBus {

  private static final Logger log = LoggerFactory.getLogger(InProcessWorkerSignalBus.class);

  /**
   * The dev hot-reload trigger's filename inside {@code <dataDir>/runtime/}.
   *
   * <p>Its existence is the signal; its contents are ignored. That is deliberate — the producer is
   * an external tool writing from another process, and "create a file" is the only operation whose
   * partial states are all indistinguishable from "not yet". A byte inside a file could be read
   * half-written; a file either has been renamed into place or has not.
   */
  public static final String RELOAD_REQUEST_FILENAME = "dev-reload.request";

  private final GpuSchedulingGauge gpuScheduling;
  private final long startupTime = System.currentTimeMillis();
  private final AtomicInteger boundPort = new AtomicInteger(0);
  private volatile BooleanSupplier pendingIngestProbe;
  private final Path reloadRequest;

  /**
   * @param gpuScheduling the process-wide gauge — the same instance the Head-side writers hold,
   *     never a fresh one, or the worker reads a gauge nobody writes
   */
  public InProcessWorkerSignalBus(GpuSchedulingGauge gpuScheduling) {
    this(gpuScheduling, null);
  }

  /**
   * @param gpuScheduling as above
   * @param runtimeDir the {@code <dataDir>/runtime/} directory to watch for the hot-reload request
   *     file, or {@code null} to disable hot reload for this bus. Null is the right answer for
   *     tests and for any composition with no data directory: a bus that polled a path it invented
   *     would be reading a file the dev tool never writes.
   */
  public InProcessWorkerSignalBus(GpuSchedulingGauge gpuScheduling, Path runtimeDir) {
    this.gpuScheduling = Objects.requireNonNull(gpuScheduling, "gpuScheduling");
    this.reloadRequest = runtimeDir == null ? null : runtimeDir.resolve(RELOAD_REQUEST_FILENAME);
  }

  @Override
  public void open() {
    // Nothing to map: the "bus" is a reference to a gauge in this JVM.
  }

  @Override
  public void writePort(int port) {
    boundPort.set(port);
  }

  /** The port the index half last bound, or 0. Diagnostic only; nothing discovers it. */
  public int boundPort() {
    return boundPort.get();
  }

  @Override
  public long readHeartbeat() {
    // The Engine is alive by construction while this method can be called at all.
    return System.currentTimeMillis();
  }

  @Override
  public boolean isShutdownRequested() {
    // Shutdown is an ordered in-process sequence owned by the composition root, not a byte.
    return false;
  }

  @Override
  public boolean shouldDie() {
    return false;
  }

  @Override
  public boolean isMainGpuActive() {
    return gpuScheduling.isMainGpuActive();
  }

  @Override
  public boolean isEnergyReduced() {
    return gpuScheduling.isEnergyReduced();
  }

  @Override
  public boolean hasPendingIngest() {
    BooleanSupplier probe = pendingIngestProbe;
    return probe != null && probe.getAsBoolean();
  }

  @Override
  public void setPendingIngestProbe(BooleanSupplier probe) {
    this.pendingIngestProbe = probe;
  }

  /**
   * Whether the dev tool has asked for a service reconstruction.
   *
   * <p>Polled once a second by the sentinel thread, so this must be cheap and must never throw: a
   * dev-only convenience has no business being able to stop the sentinel, which also carries the
   * GPU-lifecycle and queue-cleanup arms.
   */
  @Override
  public boolean isReloadRequested() {
    Path request = reloadRequest;
    if (request == null) {
      return false;
    }
    try {
      return Files.isRegularFile(request);
    } catch (RuntimeException e) {
      log.debug("dev reload: could not stat {} ({})", request, e.getMessage());
      return false;
    }
  }

  /**
   * Consumes the request by deleting the file.
   *
   * <p>Called at the START of the reload, before services are torn down, so that a compile landing
   * mid-reload leaves a fresh request behind and gets its own reload — the same property the
   * memory-mapped signal had, and the reason DevReloadManager clears before it does any work.
   *
   * <p>A failed delete is logged and swallowed, but it is not harmless and the log says so: the
   * request would still be there on the next poll, so the reload would loop. Deleting a file this
   * process owns in a directory it owns does not fail in practice.
   */
  @Override
  public void clearReloadSignal() {
    Path request = reloadRequest;
    if (request == null) {
      return;
    }
    try {
      Files.deleteIfExists(request);
    } catch (IOException | RuntimeException e) {
      log.warn(
          "dev reload: failed to clear {} — the next sentinel poll will re-trigger the reload ({})",
          request,
          e.toString());
    }
  }

  @Override
  public long startupTime() {
    return startupTime;
  }

  @Override
  public void close() {
    // Nothing owned.
  }
}
