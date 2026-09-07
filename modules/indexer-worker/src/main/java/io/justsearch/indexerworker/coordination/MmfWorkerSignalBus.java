/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import io.justsearch.indexerworker.loop.WorkerLivenessDecision;
import io.justsearch.ipc.mmf.MmfWorkerSignalHeaderV1;
import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory-Mapped File (MMF) coordination bus for worker-main process communication.
 *
 * <p>Layout (64 bytes total):
 * <ul>
 *   <li>[0-7]: Last User Activity (Epoch Millis, long) - Main writes; Worker reads</li>
 *   <li>[8-15]: Main Heartbeat (Epoch Millis, long) - Main writes; Worker reads</li>
 *   <li>[16]: Shutdown Signal (byte, 1=Stop) - Main writes; Worker reads</li>
 *   <li>[17-19]: Reserved</li>
 *   <li>[20-23]: Worker gRPC Port (int) - Worker writes on startup</li>
 *   <li>[24]: GPU Active (byte, 1=Main using GPU) - Main writes; Worker reads</li>
 *   <li>[25-63]: Reserved for future use</li>
 * </ul>
 */
public final class MmfWorkerSignalBus implements WorkerSignalBus {
  private static final Logger log = LoggerFactory.getLogger(MmfWorkerSignalBus.class);

  private static final ValueLayout.OfLong LE_LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  private static final ValueLayout.OfInt LE_INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  private static final ValueLayout.OfShort LE_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  /** Heartbeat staleness threshold in milliseconds. */
  private static final long HEARTBEAT_STALE_MS = 5000L;

  /** Grace period after startup during which heartbeat is not checked. */
  private static final long STARTUP_GRACE_MS = 15_000L;

  private final Path signalFile;
  private final long startupTime;
  /**
   * Main (Head) process PID, or {@code <= 0} when unknown (standalone runs / tests). Used to probe
   * Head liveness so the heartbeat suicide-pact is not tripped by a benign OS-resume stale beat
   * (tempdoc 630). When unknown, {@link #shouldDie()} falls back to heartbeat-only (pre-630) behavior.
   */
  private final long headPid;
  /**
   * Intra-process pending-ingest probe (tempdoc 798). Not backed by the mapped segment — the
   * indexing loop and the backfill scheduler that reads it live in the same JVM; the Head has no
   * stake in it. Volatile because the loop thread registers it and background threads read it.
   */
  private volatile BooleanSupplier pendingIngestProbe;

  private RandomAccessFile raf;
  private FileChannel channel;
  private Arena arena;
  private MemorySegment segment;

  /**
   * Creates a MmfWorkerSignalBus using the specified signal file, with no Head-liveness signal
   * (heartbeat-only suicide-pact — pre-630 behavior). Used by standalone runs and tests.
   *
   * @param signalFile Path to the worker_signal.lock file
   */
  public MmfWorkerSignalBus(Path signalFile) {
    this(signalFile, 0L);
  }

  /**
   * Creates a MmfWorkerSignalBus that corroborates a stale heartbeat against Head's actual liveness
   * before self-terminating (tempdoc 630). A suspended Head keeps its PID, so a resume-induced stale
   * heartbeat with {@code headPid} still alive does NOT trigger the suicide-pact.
   *
   * @param signalFile Path to the worker_signal.lock file
   * @param headPid the Main (Head) process PID, or {@code <= 0} if unknown
   */
  public MmfWorkerSignalBus(Path signalFile, long headPid) {
    this.signalFile = signalFile;
    this.headPid = headPid;
    this.startupTime = System.currentTimeMillis();
  }

  @Override
  public void open() throws IOException {
    Files.createDirectories(signalFile.getParent());

    this.raf = new RandomAccessFile(signalFile.toFile(), "rw");
    this.channel = raf.getChannel();

    // Ensure file is at least MMF_SIZE bytes
    if (raf.length() < MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES) {
      raf.setLength(MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES);
    }

    this.arena = Arena.ofShared();
    this.segment =
        channel.map(
            FileChannel.MapMode.READ_WRITE, 0, MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES, arena);

    // Validate header for compatibility
    validateHeader();

    log.info("MmfWorkerSignalBus opened: {}", signalFile);
  }

  /**
   * Validates the MMF header for compatibility.
   * Logs info for legacy (pre-header) files, which are treated as v1.
   */
  private void validateHeader() {
    short magic = segment.get(LE_SHORT, MmfWorkerSignalHeaderV1.OFFSET_MAGIC);
    byte version = segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_VERSION);

    if (MmfWorkerSignalHeaderV1.isValidV1(magic, version)) {
      log.debug("MMF header valid: v{}", version);
    } else if (MmfWorkerSignalHeaderV1.isLegacyHeader(magic, version)) {
      log.info("Legacy MMF detected (no header), operating in v1 mode");
    } else {
      log.warn(
          "Unexpected MMF header: magic=0x{}, version={} - attempting v1 operation",
          String.format("%04X", magic & 0xFFFF),
          version & 0xFF);
    }
  }

  @Override
  public void writePort(int port) {
    ensureOpen();
    segment.set(LE_INT, MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT, port);
    segment.force();
    log.info("MmfWorkerSignalBus: wrote port {} to MMF", port);
  }

  @Override
  public long readHeartbeat() {
    ensureOpen();
    return segment.get(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS);
  }

  @Override
  public boolean isShutdownRequested() {
    ensureOpen();
    return segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL) == 1;
  }

  @Override
  public boolean shouldDie() {
    ensureOpen();

    boolean shutdownRequested = isShutdownRequested();
    long now = System.currentTimeMillis();
    long uptime = now - startupTime;
    long heartbeat = readHeartbeat();
    WorkerLivenessDecision.HeadLiveness head = headLiveness();

    boolean die =
        WorkerLivenessDecision.shouldDie(
            shutdownRequested,
            uptime,
            STARTUP_GRACE_MS,
            heartbeat,
            now,
            HEARTBEAT_STALE_MS,
            head);

    if (die) {
      if (shutdownRequested) {
        log.info("MmfWorkerSignalBus: shutdown signal received");
      } else {
        // Past grace + stale heartbeat + Head not alive (DEAD, or UNKNOWN with no PID signal).
        log.warn(
            "MmfWorkerSignalBus: heartbeat stale by {}ms and Head {}, triggering shutdown",
            now - heartbeat,
            head);
      }
    }
    return die;
  }

  /**
   * Probes the Main (Head) process's liveness via its PID (tempdoc 630). A suspended-then-resumed
   * Head is still {@link WorkerLivenessDecision.HeadLiveness#ALIVE} (its PID persists across a
   * suspend), so a benign resume-induced stale heartbeat does not trip the suicide-pact. Returns
   * {@link WorkerLivenessDecision.HeadLiveness#UNKNOWN} when no PID was supplied (standalone / tests),
   * preserving the pre-630 heartbeat-only behavior.
   */
  private WorkerLivenessDecision.HeadLiveness headLiveness() {
    if (headPid <= 0) {
      return WorkerLivenessDecision.HeadLiveness.UNKNOWN;
    }
    boolean alive = ProcessHandle.of(headPid).map(ProcessHandle::isAlive).orElse(false);
    return alive
        ? WorkerLivenessDecision.HeadLiveness.ALIVE
        : WorkerLivenessDecision.HeadLiveness.DEAD;
  }

  /**
   * The in-process GPU-scheduling gauge this bus feeds (lane F item A5).
   *
   * <p>Direction of travel: while the Worker is a separate process the Head writes two
   * memory-mapped bytes and this class copies them into the gauge on every read, so the composition
   * rule has one owner. Item A6 supersedes this class on the live path — the Engine root passes
   * {@code InProcessWorkerSignalBus} over its single gauge, with no file in between — and item A10
   * deletes the bytes and this class with them.
   *
   * <p><b>Not handed out.</b> The gauge is this class's write-through cache, not its answer: see
   * {@link #readGpuScheduling()} for why returning the live mutable object let two reader threads
   * observe each other's refresh.
   */
  private final io.justsearch.core.scheduling.GpuSchedulingGauge gpuScheduling =
      new io.justsearch.core.scheduling.GpuSchedulingGauge();

  /**
   * The two bytes, read once and answered from locals.
   *
   * <p><b>Why this does not return the gauge.</b> The first cut refreshed the shared mutable
   * {@code gpuScheduling} on every read and handed it back. Two worker threads calling it
   * concurrently would then race through one object: thread A refreshes (byte = 1), thread B
   * refreshes (byte = 0), thread A reads — and gets B's value. The gauge is a write-through cache of
   * a file two processes share, so the read must be a snapshot. Item A6 makes the whole question
   * moot for the in-process bus ({@code InProcessWorkerSignalBus} reads the one real gauge, which
   * has no file behind it), and A10 deletes this class.
   */
  private GpuSchedulingSnapshot readGpuScheduling() {
    ensureOpen();
    boolean mainGpuActive =
        segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE) == 1;
    boolean energyReduced =
        segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_ENERGY_REDUCED) == 1;
    // Keep the shared gauge current for anything reading it as the cache it is; the answer below
    // comes from the locals, not from the object.
    gpuScheduling.setMainGpuActive(mainGpuActive);
    gpuScheduling.setEnergyReduced(energyReduced);
    return new GpuSchedulingSnapshot(mainGpuActive, energyReduced);
  }

  /** One consistent read of the two scheduling signals. */
  private record GpuSchedulingSnapshot(boolean mainGpuActive, boolean energyReduced) {}

  @Override
  public boolean isMainGpuActive() {
    return readGpuScheduling().mainGpuActive();
  }

  @Override
  public boolean isEnergyReduced() {
    return readGpuScheduling().energyReduced();
  }

  @Override
  public boolean shouldYieldGpuBackfill() {
    GpuSchedulingSnapshot snapshot = readGpuScheduling();
    return io.justsearch.core.scheduling.GpuSchedulingGauge.shouldYield(
        snapshot.mainGpuActive(), snapshot.energyReduced());
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

  @Override
  public long startupTime() {
    return startupTime;
  }

  @Override
  public boolean isReloadRequested() {
    ensureOpen();
    return segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL) == 1;
  }

  @Override
  public void clearReloadSignal() {
    ensureOpen();
    segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL, (byte) 0);
    segment.force();
  }

  private void ensureOpen() {
    if (segment == null) {
      throw new IllegalStateException("MmfWorkerSignalBus is not open");
    }
  }

  @Override
  public void close() throws IOException {
    if (arena != null) {
      try {
        segment.force();
      } catch (Exception e) {
        log.debug("Failed to force segment on close", e);
      }
      try {
        arena.close();
      } catch (Exception e) {
        log.warn("Failed to close arena; file may remain locked on Windows.", e);
      }
      arena = null;
      segment = null;
    }
    if (channel != null) {
      channel.close();
      channel = null;
    }
    if (raf != null) {
      raf.close();
      raf = null;
    }
    log.info("MmfWorkerSignalBus closed");
  }
}
