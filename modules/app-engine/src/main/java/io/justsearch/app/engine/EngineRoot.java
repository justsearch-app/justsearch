/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WorkerHost;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Engine's composition root (lane F design 3.2), and the only place that composes both halves.
 *
 * <p>Design 3.2 places the composition root <em>outside</em> the three rings: it "binds every
 * implementation and owns the two sequences that span rings, startup and shutdown". This module is
 * the only one permitted to depend on both halves of the Engine — the application half
 * ({@code app-services}) and the index half ({@code worker-services} / {@code worker-core} /
 * {@code indexer-worker}) — which is what ArchUnit rule 6b pins from the other side: nothing
 * outside {@code io.justsearch.app.engine..}, {@code io.justsearch.indexerworker..} and
 * {@code io.justsearch.adapters..} may reach into
 * {@code io.justsearch.indexerworker.{server,services,loop}..}.
 *
 * <p><b>What item A6 made this class do.</b> Before A6 the index half was a second JVM:
 * {@code KnowledgeServerBootstrap} created a memory-mapped signal file, spawned
 * {@code IndexerWorker.main}, waited for it to publish a port, and opened a gRPC channel to it.
 * This class replaces those four steps with three method calls — build the same
 * {@link WorkerConfig} the worker built for itself, construct a {@link KnowledgeServer}, start it —
 * and hands back an {@link EngineKnowledgeClient} whose calls are calls.
 *
 * <p><b>One config, no snapshot.</b> {@link WorkerConfig#load()} reads
 * {@code ConfigStore.global()}, which the Head has already resolved. In the split world the worker
 * received a serialised copy at config ordinal 450 and then checked it for divergence from the
 * Head's; in one JVM there is one {@code ResolvedConfig} and divergence is not a thing that can
 * happen. The snapshot tier itself is retired at item A19; nothing here depends on it either way.
 *
 * <p><b>What is deliberately still here at A6.</b> {@link KnowledgeServer#start()} still binds the
 * gRPC server and still calls {@code signalBus.writePort} — both harmless (the in-process bus
 * publishes the port nowhere) and both deleted at item A9. Keeping them for one item is what lets
 * the branch stay compiling and green between A6 and A9 rather than opening a red window.
 *
 * <p>Adding a port is a catalogue entry, an interface, and a binding in this class (design 3.3).
 * Nothing else in the repo may construct an implementation of a port.
 */
public final class EngineRoot implements WorkerHost {

  private static final Logger log = LoggerFactory.getLogger(EngineRoot.class);

  private final Function<GpuSchedulingGauge, KnowledgeServer> serverFactory;
  private final long deadlineMs;
  private final int batchSize;

  private volatile KnowledgeServer server;
  private volatile EngineKnowledgeClient client;

  /**
   * @param deadlineMs the base call deadline the {@code RpcDeadlineCategory} multipliers apply to
   *     — the same {@code KnowledgeServerConfig.deadlineMs()} the wire client used
   * @param batchSize the per-batch submission clamp
   */
  public EngineRoot(long deadlineMs, int batchSize) {
    this(
        gauge -> new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(gauge)),
        deadlineMs,
        batchSize);
  }

  /** Test seam: supply the index half rather than building it from the global config. */
  EngineRoot(
      Function<GpuSchedulingGauge, KnowledgeServer> serverFactory, long deadlineMs, int batchSize) {
    this.serverFactory = Objects.requireNonNull(serverFactory, "serverFactory");
    this.deadlineMs = deadlineMs;
    this.batchSize = batchSize;
  }

  @Override
  public KnowledgeClient start(GpuSchedulingGauge gpuScheduling, IpcTelemetry telemetry)
      throws IOException {
    Objects.requireNonNull(gpuScheduling, "gpuScheduling");
    if (client != null) {
      return client;
    }
    KnowledgeServer started = serverFactory.apply(gpuScheduling);
    started.start();
    this.server = started;

    // THE gauge the indexing loop paces off, read from the field that owns it rather than from
    // IndexingPacing: the pacing field starts as IndexingPacing.unthrottled(), whose gauge is a
    // fresh orphan, and is only replaced with the real policy partway through start().
    ForegroundLoadGate gate = new ForegroundLoadGate(started.foregroundLoad());
    EngineKnowledgeClient built =
        new EngineKnowledgeClient(started::appServices, gate, deadlineMs, batchSize, telemetry);
    this.client = built;
    log.info("Engine composed the index half in-process (no worker process, no channel)");
    return built;
  }

  @Override
  public long ownerPid() {
    return ProcessHandle.current().pid();
  }

  @Override
  public void close() {
    EngineKnowledgeClient c = client;
    client = null;
    if (c != null) {
      c.close();
    }
    KnowledgeServer s = server;
    server = null;
    if (s != null) {
      try {
        s.close();
      } catch (IOException e) {
        log.warn("Error closing the in-process index half", e);
      }
    }
  }
}
