/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.*;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real upgrade boot and process sandbox; six 25-second parses span two 60-second root syncs. */
@Tag("stress")
@Timeout(360)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class RetirementMaintenanceProcessTest {
  @Test
  void ordinaryProcessBatchSurvivesTwoRealIntervalSyncs(@TempDir Path temporary) throws Exception {
    processBatchSurvivesTwoRealIntervalSyncs(false, temporary);
  }

  @Test
  void retirementProcessBatchSurvivesTwoRealIntervalSyncs(@TempDir Path temporary) throws Exception {
    processBatchSurvivesTwoRealIntervalSyncs(true, temporary);
  }

  private void processBatchSurvivesTwoRealIntervalSyncs(boolean retirement, Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("switching", temporary);
    Path alpha = root.resolve("roots/alpha");
    ConfigStore previous = ConfigStore.globalOrNull();
    var release = new CountDownLatch(1);
    var arrived = new CountDownLatch(1);
    var once = new AtomicBoolean();
    try (var gate = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
      gate.setSoTimeout(60_000);
      String command = childCommand(root, gate.getLocalPort());
      WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"),
          "BLUE_GREEN_MIGRATE", Map.of("justsearch.ai.disabled", "true", "justsearch.ai.embed.enabled", "false",
              "justsearch.splade.enabled", "false", "justsearch.ner.enabled", "false",
              "justsearch.extraction.sandbox.mode", "PROCESS", "justsearch.extraction.sandbox.pool", "1",
              "justsearch.extraction.sandbox.command", command,
              // Ordinary reconciliation also repairs the fixture's older indexed extraction policy.
              // Keep that real backlog and all six delayed sources in one production batch.
              "justsearch.backfill.poll_batch_size", "64"));
      var installed = ConfigStore.global();
      try (var _ = new ReleasedModelIdentities(root);
           var ex = new TestEngineExecutors();
           var server = Release030RetirementPublicSyncTest.namedBoot(root, ex, transition -> {
             if (retirement && transition.point().equals("release030-before-replay") && once.compareAndSet(false, true)) {
               arrived.countDown(); assertTrue(release.await(300, TimeUnit.SECONDS));
             }
           })) {
        try {
        activate(server);
        if (retirement) assertTrue(arrived.await(30, TimeUnit.SECONDS));
        else await(() -> state(root).previous_generation() == null, "authentic upgrade retires before ordinary trial", 90);
        var producer = (DefaultWorkerAppServices) server.appServices();
        assertTrue(producer.pauseProducerForCutover(30_000));
        var files = new ArrayList<Path>();
        for (int i = 0; i < 6; i++) files.add(Files.writeString(alpha.resolve("fu4-" + i + ".txt"), "processvioletmarker" + i));
        var request = SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).build();
        var service = server.appServices().ingestService();
        assertTrue(service.syncDirectory(request, CallContext.none()).getError().isBlank());
        assertEquals(retirement, queue(server).release030RetirementGeneration() != null);
        var jobs = queue(server);
        var revisions = files.stream().map(f -> jobs.watcherReconciliationRow(key(f)).get("unit_revision")).toList();
        if (retirement) assertTrue(revisions.stream().allMatch(r -> r.startsWith("root2:")),
            "the live upgrade trial must exercise ROOT ownership");
        var events = new CopyOnWriteArrayList<String>();
        var tickFailures = new CopyOnWriteArrayList<Throwable>();
        var ticks = new CountDownLatch(2);
        var timerOwner = ex.register(new EngineExecutorSpec("fu4-process-control", EngineExecutorSpec.Kind.BACKGROUND,
            EngineExecutorSpec.Mode.SCHEDULED, 2, 8, 2));
        var timer = timerOwner.openScheduled(r -> { var t = new Thread(r, "fu4-process-control"); t.setDaemon(true); return t; });
        producer.resumeProducerAfterCutover();
        var names = new ArrayList<String>();
        ScheduledFuture<?> periodic = null;
        try {
          for (int i = 0; i < files.size(); i++) {
            assertTrue(tickFailures.isEmpty(), "sync ownership failures: " + tickFailures);
            try (var connection = gate.accept()) {
              connection.setSoTimeout(40_000);
              String name = new DataInputStream(connection.getInputStream()).readUTF();
              names.add(name); events.add("extract " + name);
              if (i == 0) {
                for (var file : files) assertEquals("PROCESSING", queue(server).watcherReconciliationRow(key(file)).get("state"),
                    "the entire batch is claimed before the first delayed parse");
                periodic = timer.scheduleAtFixedRate(() -> {
                  if (ticks.getCount() == 0) return;
                  try {
                    var response = service.syncDirectory(request, CallContext.none());
                    events.add("admission " + response);
                    assertTrue(response.getError().isBlank());
                    for (int at = 0; at < files.size(); at++) {
                      var row = queue(server).watcherReconciliationRow(key(files.get(at)));
                      events.add("owner " + files.get(at).getFileName() + " " + row);
                      assertEquals(revisions.get(at), row.get("unit_revision"));
                      assertEquals("PROCESSING", row.get("state"));
                    }
                  } catch (Throwable failure) { tickFailures.add(failure); }
                  finally { ticks.countDown(); }
                }, 60, 60, TimeUnit.SECONDS);
              }
              // An explicit gate and timer, never a parser sleep; each child remains below its
              // 60-second extraction budget while the single production batch spans 150 seconds.
              timer.schedule(() -> {
                try { connection.getOutputStream().write(1); connection.getOutputStream().flush(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
              }, 25, TimeUnit.SECONDS).get(35, TimeUnit.SECONDS);
            }
          }
          assertTrue(ticks.await(10, TimeUnit.SECONDS));
          assertTrue(tickFailures.isEmpty(), "sync ownership failures: " + tickFailures);
          assertEquals(6, new HashSet<>(names).size(), "exactly one request per source");
          assertTrue(producer.pauseProducerForCutover(30_000), "synchronous parser and publication have exited");
          server.lifecycleManagerForTests().commitOps().commitAndTrack();
          server.lifecycleManagerForTests().commitOps().maybeRefreshBlocking();
          for (int i = 0; i < files.size(); i++) {
            var row = queue(server).watcherReconciliationRow(key(files.get(i)));
            events.add("published " + files.get(i).getFileName() + " " + row);
            assertEquals("DONE", row.get("state"));
            assertEquals(revisions.get(i), row.get("unit_revision"));
            assertTrue(search(server, "processvioletmarker" + i).contains(key(files.get(i))));
          }
        } finally {
          if (periodic != null) periodic.cancel(false);
          timerOwner.close();
          producer.resumeProducerAfterCutover();
          release.countDown();
          System.out.println("FU4_PROCESS retirement=" + retirement + " events=" + events);
        }
        } finally { release.countDown(); }
      } finally { release.countDown(); ConfigStore.restoreGlobal(installed, previous); }
    } finally { release.countDown(); }
  }

  private static String childCommand(Path root, int port) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Path args = root.resolve("fu4-child.args");
    Files.writeString(args, "-Xmx128m\n--enable-native-access=ALL-UNNAMED\n-Dfu4.gate.port=" + port
        + "\n-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\")
        + "\"\n" + DelayedChild.class.getName() + "\n");
    return "\"" + java + "\" \"@" + args + "\"";
  }

  /** Independent framing around the production parser, reached through the operator command. */
  public static final class DelayedChild {
    private DelayedChild() { }

    public static void main(String[] args) throws Exception {
      PrintStream protocol = System.out;
      System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
      ExtractionSandboxChild.initializeProcessBoundary(args);
      var mapper = new tools.jackson.databind.ObjectMapper();
      var input = new DataInputStream(System.in);
      var output = new DataOutputStream(protocol);
      while (true) {
        int length;
        try { length = input.readInt(); } catch (EOFException end) { return; }
        if (length < 1 || length > 1_048_576) throw new IOException("Invalid request frame");
        var request = mapper.readValue(input.readNBytes(length), SandboxExtractionRequest.class);
        Path file = Path.of(request.path());
        if (file.getFileName().toString().startsWith("fu4-")) {
          try (var socket = new Socket(InetAddress.getLoopbackAddress(), Integer.getInteger("fu4.gate.port"))) {
            socket.setSoTimeout(40_000);
            new DataOutputStream(socket.getOutputStream()).writeUTF(file.getFileName().toString());
            if (socket.getInputStream().read() != 1) throw new IOException("Parser gate closed");
          }
        }
        try (var parser = new PolicyDrivenTikaExtractor(ExtractionSandboxChild::openOcrPool, request.policy(), request.ocrConfig())) {
          byte[] bytes = mapper.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(request.requestId(), parser.extractArtifact(file)));
          output.writeInt(bytes.length); output.write(bytes); output.flush();
        }
      }
    }
  }
}
