/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B T22 (S19): the activation self-test's candidate runs as a {@code LLAMA_SELF_TEST} child of
 * the Engine's control, is stopped with {@code SELF_TEST}, and its GPU release poll has finished
 * before the self-test returns, so before the main server starts. The candidate is a stand-in server
 * ({@link HealthyFakeLlamaServer}) on {@code -ngl 1}; the adapter memory is a fake probe that shows
 * the release only some samples after the stop.
 */
final class SelfTestGpuBarrierTest {
  private static final long HELD = 10L << 30;
  private static final long RELEASED = 2L << 30;

  @TempDir Path tmp;
  private Path fakeDir;
  private DefaultChildControl control;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (control != null) control.close();
    } finally {
      if (fakeDir != null) HealthyFakeLlamaServer.endAll(fakeDir);
    }
  }

  @Test
  void theCandidateIsStoppedForTheSelfTestAndReleasedBeforeTheMainStart() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Path model = Files.createFile(tmp.resolve("model.gguf"));
    Path dataDir = Files.createDirectories(tmp.resolve("data"));
    AtomicInteger reads = new AtomicInteger();
    VramProbe probe = new VramProbe() {
      @Override
      public GpuMemorySource source() {
        return GpuMemorySource.NVML;
      }

      @Override
      public OptionalLong adapterUsedBytes(Optional<GpuDevice> device) {
        // Read 1 is taken before the terminate; the release shows from the fourth read on.
        return OptionalLong.of(reads.incrementAndGet() <= 3 ? HELD : RELEASED);
      }
    };
    control = DefaultChildControl.openForEngine(
        dataDir, "self-test-barrier", ManagedChildRegistry.noop(), probe);
    int candidatePort = freePort();

    SelfTestRun run = new ChildSelfTestLauncher(control).run(new SelfTestSpec(
        List.of(executable.toString(), "--port", String.valueOf(candidatePort),
            "-m", model.toString(), "-ngl", "1"),
        null, List.of(), tmp.resolve("logs").resolve("self-test.log"), candidatePort,
        Duration.ofSeconds(20), new HealthProbe()));

    assertTrue(run.exercised(), "the candidate became healthy: " + run.failure());
    List<LedgerRecord> atReturn = ledger(dataDir);
    LedgerRecord spawn = only(atReturn, LedgerRecordType.CHILD_SPAWN);
    String candidate = spawn.text("childId").orElseThrow();
    assertEquals("LLAMA_SELF_TEST", spawn.text("kind").orElseThrow());
    LedgerRecord intent = only(atReturn, LedgerRecordType.STOP_INTENT);
    assertEquals("SELF_TEST", intent.text("reason").orElseThrow());
    LedgerRecord exit = only(atReturn, LedgerRecordType.CHILD_EXIT);
    assertEquals("REQUESTED", exit.text("exitClass").orElseThrow());
    LedgerRecord release = only(atReturn, LedgerRecordType.GPU_RELEASE);
    assertEquals(candidate, release.text("childId").orElseThrow());
    assertEquals("recovered", release.text("result").orElseThrow(),
        "the self-test returned only after its GPU release poll finished");
    assertTrue(intent.seq() < exit.seq() && exit.seq() < release.seq(), "stop, exit, release");

    // The main server's start (gpu) follows the release.
    int mainPort = freePort();
    ChildHandle main = control.start(new ChildSpec(ChildKind.LLAMA_SERVER,
        List.of(executable.toString(), "--port", String.valueOf(mainPort), "-m", model.toString(),
            "-ngl", "1"),
        null, Map.of(), new ChildSpec.Stdio.AppendToLog(tmp.resolve("logs").resolve("main.log")),
        true, OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE,
        "llama-server"));
    control.stop(main, StopReason.QUIT, StopMode.NOW).toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
    List<LedgerRecord> after = ledger(dataDir);
    LedgerRecord mainSpawn = after.stream()
        .filter(record -> record.type() == LedgerRecordType.CHILD_SPAWN)
        .filter(record -> "LLAMA_SERVER".equals(record.text("kind").orElse(null)))
        .findFirst().orElseThrow();
    assertTrue(release.seq() < mainSpawn.seq(),
        "the candidate's GPU release is recorded before the main server is spawned");
  }

  private static List<LedgerRecord> ledger(Path dataDir) throws Exception {
    return ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records();
  }

  private static LedgerRecord only(List<LedgerRecord> records, LedgerRecordType type) {
    List<LedgerRecord> found = records.stream().filter(record -> record.type() == type).toList();
    assertEquals(1, found.size(), type + " records: " + found);
    return found.getFirst();
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /** The caller's view of the candidate: its health answers, and nothing more is exercised. */
  private static final class HealthProbe implements SelfTestSpec.Probe {
    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public boolean healthy(int port) {
      try {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(1)).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
      } catch (java.io.IOException unreachable) {
        return false;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    @Override
    public void exercise(int port) {}
  }
}
