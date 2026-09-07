/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.Closeable;

/**
 * Where the index half lives, and how the Head reaches it (lane F stage A item A6).
 *
 * <p>{@link KnowledgeServerBootstrap} used to answer both questions itself, in one hard-coded way:
 * spawn a JVM, discover its port over a memory-mapped file, open a gRPC channel. This interface is
 * that decision made explicit so the composition root can make it instead — which is the only way
 * the root can own the worker lifecycle without {@code app-services} depending on {@code
 * app-engine} (the edge runs the other way, and rule 6b closes the shortcut).
 *
 * <p>One implementation exists on the live path: {@code io.justsearch.app.engine.EngineRoot},
 * which builds the index half inside this JVM. The legacy path — no host supplied, spawn a process
 * — is kept only until item A11 deletes {@code WorkerSpawner}.
 *
 * <p><b>Lifecycle.</b> {@link #start} is called once per bootstrap start attempt and must be safe
 * to call again after {@link #close}; {@code KnowledgeServerBootstrap.closeForUpgrade()} tears the
 * integration down and boot recovery restarts it against the same instance.
 */
public interface WorkerHost extends Closeable {

  /**
   * Starts the index half and returns the client for it.
   *
   * @param gpuScheduling the Engine's one GPU-scheduling gauge (item A5). The host must give the
   *     index half <em>this</em> instance rather than a fresh one, or the worker reads a gauge
   *     that neither the inference wiring nor the energy poll writes.
   * @param telemetry the IPC telemetry the bootstrap built, so the status-poll metric keeps its
   *     exact shape across the transport change (item A10 audits the names before deleting them)
   * @return a usable client; never null
   * @throws Exception if the index half could not be started
   */
  KnowledgeClient start(GpuSchedulingGauge gpuScheduling, IpcTelemetry telemetry) throws Exception;

  /**
   * The OS process id that owns the index — this JVM's own pid for an in-process host.
   *
   * <p>Kept because the bootstrap's PID validation and the health monitor both report it, and
   * because a future host could legitimately be a child process again (design 3.5 explicitly
   * refuses location transparency for the ports, so that would be a new decision, not a silent
   * fallback).
   *
   * @return the owning pid, or 0 when not started
   */
  long ownerPid();

  /** Stops the index half. Idempotent. */
  @Override
  void close();
}
