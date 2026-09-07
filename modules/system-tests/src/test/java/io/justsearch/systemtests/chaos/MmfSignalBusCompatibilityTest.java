package io.justsearch.systemtests.chaos;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.services.worker.MainSignalBus;
import io.justsearch.indexerworker.coordination.MmfWorkerSignalBus;
import io.justsearch.ipc.mmf.MmfWorkerSignalHeaderV1;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MMF signal bus: Head/Worker layout compatibility")
final class MmfSignalBusCompatibilityTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("MainSignalBus and MmfWorkerSignalBus can read/write shared fields")
  void mainAndWorkerShareLayout() throws Exception {
    Path signalPath = tempDir.resolve("worker_signal.lock");

    // Main writes initial signals.
    try (MainSignalBus main = new MainSignalBus(signalPath)) {
      main.open();
      main.clearShutdown();
      main.zeroPort();
      main.writeHeartbeat();
      main.writeGpuActive(true);
    }

    // Worker reads initial signals and writes port back.
    try (MmfWorkerSignalBus worker = new MmfWorkerSignalBus(signalPath)) {
      worker.open();
      assertTrue(worker.readHeartbeat() > 0, "worker should observe heartbeat timestamp");
      assertFalse(worker.isShutdownRequested(), "shutdown should be clear");
      assertTrue(worker.isMainGpuActive(), "worker should observe gpu_active=true");

      worker.writePort(12345);
    }

    // Main re-opens and observes port, then requests shutdown.
    try (MainSignalBus main = new MainSignalBus(signalPath)) {
      main.open();
      assertEquals(12345, main.readPort(), "main should observe worker-written port");
      main.writeShutdown();
    }

    // Worker re-opens and observes shutdown requested.
    try (MmfWorkerSignalBus worker = new MmfWorkerSignalBus(signalPath)) {
      worker.open();
      assertTrue(worker.isShutdownRequested(), "worker should observe shutdown signal");
    }
  }

  @Test
  @DisplayName("the Worker's in-process GPU-scheduling gauge is fed from both MMF bytes (item A5)")
  void gaugeIsFedFromTheMmfBytes() throws Exception {
    Path signalPath = tempDir.resolve("worker_signal_gauge.lock");

    try (MainSignalBus main = new MainSignalBus(signalPath)) {
      main.open();
      main.writeGpuActive(false);
      main.writeEnergyReduced(true);
    }

    try (MmfWorkerSignalBus worker = new MmfWorkerSignalBus(signalPath)) {
      worker.open();
      // Lane F item A5: one rule, two reasons. Until item A10 the bytes are still the transport,
      // so each read must reflect the current bytes. Item A6 stopped handing out the live gauge
      // (two reader threads raced through it), so the assertions read the accessors.
      assertFalse(worker.isMainGpuActive());
      assertTrue(worker.isEnergyReduced());
      assertTrue(worker.shouldYieldGpuBackfill(), "energy alone is a reason to yield");

      try (MainSignalBus main = new MainSignalBus(signalPath)) {
        main.open();
        main.writeEnergyReduced(false);
        main.writeGpuActive(true);
      }

      assertTrue(worker.isMainGpuActive(), "a later write must be picked up");
      assertFalse(worker.isEnergyReduced());
      assertTrue(worker.shouldYieldGpuBackfill(), "now the GPU reason holds");

      try (MainSignalBus main = new MainSignalBus(signalPath)) {
        main.open();
        main.writeGpuActive(false);
      }

      assertFalse(worker.shouldYieldGpuBackfill(), "both reasons cleared ⇒ no yield");
    }
  }

  @Test
  @DisplayName("Main writes MMF header, Worker validates it")
  void headerIsWrittenAndValidated() throws Exception {
    Path signalPath = tempDir.resolve("worker_signal_header.lock");

    // Main writes header on open
    try (MainSignalBus main = new MainSignalBus(signalPath)) {
      main.open();
    }

    // Verify header bytes in file
    byte[] raw = Files.readAllBytes(signalPath);
    ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    short magic = buf.getShort(MmfWorkerSignalHeaderV1.OFFSET_MAGIC);
    byte version = buf.get(MmfWorkerSignalHeaderV1.OFFSET_VERSION);

    assertEquals(MmfWorkerSignalHeaderV1.MAGIC_BYTES, magic, "Magic bytes should be 'JS' (0x534A)");
    assertEquals(MmfWorkerSignalHeaderV1.FORMAT_VERSION, version, "Version should be 1");

    // Worker validates header on open (no exception = success)
    try (MmfWorkerSignalBus worker = new MmfWorkerSignalBus(signalPath)) {
      worker.open();
    }
  }

  @Test
  @DisplayName("Legacy file without header is accepted by both Main and Worker")
  void legacyFileWithoutHeaderIsAccepted() throws Exception {
    Path signalPath = tempDir.resolve("legacy_signal.lock");

    // Create file with all zeros (legacy format)
    Files.write(signalPath, new byte[64]);

    // Main should open without error and write header
    try (MainSignalBus main = new MainSignalBus(signalPath)) {
      main.open();
    }

    // After main opens, header should be initialized
    byte[] raw = Files.readAllBytes(signalPath);
    ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    short magic = buf.getShort(MmfWorkerSignalHeaderV1.OFFSET_MAGIC);

    assertEquals(MmfWorkerSignalHeaderV1.MAGIC_BYTES, magic,
        "Main should initialize header on legacy file");

    // Worker should also open without error
    try (MmfWorkerSignalBus worker = new MmfWorkerSignalBus(signalPath)) {
      worker.open();
    }
  }
}
