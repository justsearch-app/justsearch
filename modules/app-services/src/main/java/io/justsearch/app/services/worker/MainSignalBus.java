/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.mmf.MmfWorkerSignalHeaderV1;
import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory-Mapped File coordination from the Main Process side.
 *
 * <p>Layout (64 bytes):
 * <ul>
 *   <li>[0-7]: Last User Activity (Epoch Millis) - Main writes; Worker reads</li>
 *   <li>[8-15]: Main Heartbeat (Epoch Millis) - Main writes; Worker reads</li>
 *   <li>[16]: Shutdown Signal (1=Stop) - Main writes; Worker reads</li>
 *   <li>[17-19]: Reserved</li>
 *   <li>[20-23]: Worker gRPC Port - Worker writes; Main reads</li>
 *   <li>[24]: GPU Active (1=Main using GPU) - Main writes; Worker reads</li>
 *   <li>[25-63]: Reserved for future use</li>
 * </ul>
 *
 * <p>The Main Process MUST zero out bytes [20-23] before spawning the worker
 * to prevent stale port discovery.
 */
public final class MainSignalBus implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(MainSignalBus.class);

    private static final ValueLayout.OfLong LE_LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_SHORT =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Path signalPath;
    private RandomAccessFile raf;
    private FileChannel channel;
    private Arena arena;
    private MemorySegment segment;

    public MainSignalBus(Path signalPath) {
        this.signalPath = signalPath;
    }

    /**
     * Opens the signal bus MMF.
     * Creates the file if it doesn't exist.
     */
    public synchronized void open() throws IOException {
        if (segment != null) {
            // Idempotent since lane F item A5 review #14: the bootstrap opens the bus itself so it
            // can republish the GPU-scheduling gauge into a freshly mapped file and start the
            // energy poll before port discovery. WorkerSpawner.start() also called open() as step 1
            // (item A11 deleted that second caller), and a second mapping would leak the first arena
            // and the first RandomAccessFile — so idempotence stays, it is just no longer contested.
            return;
        }
        Files.createDirectories(signalPath.getParent());

        this.raf = new RandomAccessFile(signalPath.toFile(), "rw");
        this.channel = raf.getChannel();

        // Ensure file is at least MMF_SIZE bytes
        if (raf.length() < MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES) {
            raf.setLength(MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES);
        }

        this.arena = Arena.ofShared();
        this.segment =
            channel.map(FileChannel.MapMode.READ_WRITE, 0, MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES,
                arena);

        // Initialize header for new files, detect legacy for existing files
        initializeOrDetectHeader();

        log.info("Opened signal bus at {}", signalPath);
    }

    /**
     * Initializes the MMF header for new/legacy files, or validates existing header.
     * Legacy files (all-zero header) are upgraded to v1 header format.
     */
    private void initializeOrDetectHeader() {
        short magic = segment.get(LE_SHORT, MmfWorkerSignalHeaderV1.OFFSET_MAGIC);
        byte version = segment.get(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_VERSION);

        if (MmfWorkerSignalHeaderV1.isValidV1(magic, version)) {
            log.debug("MMF header valid: v{}", version);
        } else if (MmfWorkerSignalHeaderV1.isLegacyHeader(magic, version)) {
            // New file or legacy file - write header
            segment.set(LE_SHORT, MmfWorkerSignalHeaderV1.OFFSET_MAGIC,
                MmfWorkerSignalHeaderV1.MAGIC_BYTES);
            segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_VERSION,
                MmfWorkerSignalHeaderV1.FORMAT_VERSION);
            segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_FLAGS, (byte) 0);
            log.debug("Initialized MMF v1 header");
        } else {
            log.warn("Unexpected MMF header: magic=0x{}, version={} - writing v1 header",
                String.format("%04X", magic & 0xFFFF), version & 0xFF);
            segment.set(LE_SHORT, MmfWorkerSignalHeaderV1.OFFSET_MAGIC,
                MmfWorkerSignalHeaderV1.MAGIC_BYTES);
            segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_VERSION,
                MmfWorkerSignalHeaderV1.FORMAT_VERSION);
            segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalHeaderV1.OFFSET_FLAGS, (byte) 0);
        }
    }

    /**
     * Zeros out the port bytes [20-23] to clear stale port data.
     * MUST be called before spawning the worker process.
     */
    public synchronized void zeroPort() {
        ensureOpen();
        segment.set(LE_INT, MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT, 0);
        segment.force();
        log.debug("Zeroed port bytes in signal bus");
    }

    /**
     * Writes the current timestamp to the heartbeat slot [8-15].
     * Call this every ~1000ms to keep the worker alive (suicide pact).
     */
    public synchronized void writeHeartbeat() {
        ensureOpen();
        segment.set(LE_LONG, MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS,
            System.currentTimeMillis());
    }

    /**
     * Writes the shutdown signal to byte [16].
     * Call this when the main process is shutting down.
     */
    public synchronized void writeShutdown() {
        ensureOpen();
        segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL,
            (byte) 1);
        segment.force();
        log.info("Wrote shutdown signal to signal bus");
    }

    /**
     * Clears the shutdown signal (byte [16] = 0).
     * Call this before spawning a new worker.
     */
    public synchronized void clearShutdown() {
        ensureOpen();
        segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL,
            (byte) 0);
        segment.force();
    }

    /**
     * Writes the GPU active status to byte [24].
     * Call this when the inference mode changes.
     *
     * <p>When GPU is active (Online Mode), the Worker should skip GPU-accelerated
     * embeddings to avoid VRAM conflicts.
     *
     * @param active true if Main process is using GPU (Online Mode), false otherwise
     */
    public synchronized void writeGpuActive(boolean active) {
        ensureOpen();
        segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE,
            (byte) (active ? 1 : 0));
        segment.force();
        log.debug("Wrote GPU active status to signal bus: {}", active);
    }

    /**
     * Reads the GPU active status from byte [24].
     *
     * @return true if Main process is using GPU
     */
    public synchronized boolean isGpuActive() {
        ensureOpen();
        return segment.get(ValueLayout.JAVA_BYTE,
            MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE) == 1;
    }

    /**
     * Writes the OS energy-intent to byte [17] (tempdoc 630). Call when the polled energy state
     * changes. When set, the Worker yields the GPU-heavy bulk backfill to save power (mirrors the
     * GPU-active yield). Conservative by construction: pass {@code false} when the energy intent is
     * unknown, so an unreadable signal never throttles indexing.
     *
     * @param reduced true if the OS is requesting reduced background work (energy saver engaged)
     */
    public synchronized void writeEnergyReduced(boolean reduced) {
        ensureOpen();
        segment.set(ValueLayout.JAVA_BYTE, MmfWorkerSignalLayoutV1.OFFSET_ENERGY_REDUCED,
            (byte) (reduced ? 1 : 0));
        segment.force();
        log.debug("Wrote energy-reduced status to signal bus: {}", reduced);
    }

    /**
     * Reads the worker gRPC port from bytes [20-23].
     *
     * @return the port number, or 0 if not yet written
     */
    public synchronized int readPort() {
        ensureOpen();
        return segment.get(LE_INT, MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT);
    }

    /**
     * Polls for a non-zero port with timeout.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @param pollIntervalMs interval between polls in milliseconds
     * @return the discovered port
     * @throws IllegalStateException if timeout is exceeded
     */
    public int awaitPort(long timeoutMs, long pollIntervalMs) throws InterruptedException {
        return awaitPort(timeoutMs, pollIntervalMs, null);
    }

    /**
     * Polls for a non-zero port with timeout and optional early crash detection.
     *
     * <p>When a non-null {@code workerProcess} is provided, the poll loop checks
     * {@link Process#isAlive()} each iteration. If the process has exited, the method
     * fails immediately instead of waiting for the full timeout — reducing crash
     * detection from O(timeout) to O(pollInterval).
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @param pollIntervalMs interval between polls in milliseconds
     * @param workerProcess the worker process to monitor, or null to skip liveness checks
     * @return the discovered port
     * @throws IllegalStateException if timeout is exceeded or the process exits before writing a port
     */
    public int awaitPort(long timeoutMs, long pollIntervalMs, Process workerProcess)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int port = readPort();
            if (port > 0) {
                log.info("Discovered worker port: {}", port);
                return port;
            }
            if (workerProcess != null && !workerProcess.isAlive()) {
                int exitCode = workerProcess.exitValue();
                log.error("Worker process exited with code {} before writing port", exitCode);
                throw new IllegalStateException(
                        "Worker process crashed (exit code "
                                + exitCode
                                + ") before writing port to signal file");
            }
            Thread.sleep(pollIntervalMs);
        }
        boolean fileExists = Files.exists(signalPath);
        log.warn(
                "Port discovery timeout after {}ms (pollInterval={}ms, signalPath={}, fileExists={})",
                timeoutMs,
                pollIntervalMs,
                signalPath,
                fileExists);
        throw new IllegalStateException(
                "Timeout waiting for worker port after "
                        + timeoutMs
                        + "ms (signal file: "
                        + signalPath
                        + ", exists: "
                        + fileExists
                        + ")");
    }

    /**
     * Returns the path to the signal file.
     */
    public Path signalPath() {
        return signalPath;
    }

    private void ensureOpen() {
        if (segment == null) {
            throw new IllegalStateException("Signal bus not opened");
        }
    }

    @Override
    public synchronized void close() {
        if (arena != null) {
            try {
                segment.force();
            } catch (Exception e) {
                // best effort
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
            try {
                channel.close();
            } catch (IOException e) {
                log.warn("Failed to close channel", e);
            }
            channel = null;
        }
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException e) {
                log.warn("Failed to close RAF", e);
            }
            raf = null;
        }
        log.debug("Closed signal bus");
    }
}
