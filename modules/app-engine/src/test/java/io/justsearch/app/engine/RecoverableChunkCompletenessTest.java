/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** D1: a configured SYSTEM_INTERNAL root survives a real JVM halt and actual parser/index replay. */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
final class RecoverableChunkCompletenessTest {
  // This checks durable completeness of a 5,557-chunk replay, not indexing throughput.
  private static final long COMPLETION_TIMEOUT_SECONDS = 300;
  private static final long PROBE_TIMEOUT_SECONDS = 360;

  @Test
  void interruptedWatchedAndRecordedIngestReplaysEveryDeclaredChunk(@TempDir Path data) throws Exception {
    int control = runScenario(Files.createDirectories(data.resolve("control")), false);
    int replay = runScenario(Files.createDirectories(data.resolve("interrupted")), true);
    assertEquals(control, replay, "replay has exactly the uninterrupted control's chunk count");
  }

  private static int runScenario(Path data, boolean interrupt) throws Exception {
    Path root = Files.createDirectories(data.resolve("documents"));
    Path file = root.resolve("large-replay.txt");
    Files.writeString(data.resolve("source.txt"), ("alpha beta gamma delta ".repeat(83) + "\n\n").repeat(5001), StandardCharsets.UTF_8);
    Files.writeString(data.resolve("watched_roots.json"), JsonMapper.builder().build().writeValueAsString(
        Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", root.toString(), "collection", "documents")))));
    Process abandoned = launch(data, interrupt ? "abandon" : "control");
    awaitOwnedExit(abandoned);
    assertEquals(interrupt ? EngineExit.FATAL_OR_UNCAUGHT : 0, abandoned.exitValue(),
        readLog(data, interrupt ? "abandon" : "control"));
    String key = Files.readString(data.resolve("operation-key"), StandardCharsets.UTF_8);
    var layout = new IndexGenerationManager(data.resolve("index")).initializeOrLoad();
    var plan = new RecordedRootPlan(layout.activeGenerationId(), List.of(new RecordedRootPlan.Root(
        file, "documents", false, true, List.of(), List.of())));
    String childKey;
    try (var operations = new SqliteOperationStore(data.resolve("operations.db"))) {
      var parent = operations.find(key).orElseThrow();
      assertEquals(interrupt ? OperationState.RUNNING : OperationState.COMPLETE, parent.state());
      assertEquals("jsa1:auto", parent.context().grantReference().orElseThrow());
      var child = operations.findIngestChild(key, plan).orElseThrow();
      childKey = child.key();
      assertEquals(interrupt ? OperationState.RUNNING : OperationState.COMPLETE, child.state());
      assertEquals(1, child.attempts());
      System.out.println("AFTER_ABANDON: parent=" + parent.state() + ", child=" + child.state()
          + ", basis=" + parent.context().grantReference().orElseThrow());
    }
    if (interrupt) {
      Process recovered = launch(data, "recover");
      awaitOwnedExit(recovered);
      assertEquals(0, recovered.exitValue(), readLog(data, "recover"));
    }
    try (var operations = new SqliteOperationStore(data.resolve("operations.db"))) {
      var parent = operations.find(key).orElseThrow();
      assertEquals(OperationState.COMPLETE, parent.state());
      assertEquals(interrupt ? 2 : 1, parent.attempts(), "one original attempt and one replay");
      assertEquals(0, parent.unitsFailed());
      var child = operations.find(childKey).orElseThrow();
      assertEquals(OperationState.COMPLETE, child.state());
      assertEquals(interrupt ? 2 : 1, child.attempts(), "the original child replays once");
    }
    int persisted;
    try (var directory = FSDirectory.open(layout.activeGenerationPath());
        var reader = DirectoryReader.open(directory)) {
      var ids = new HashSet<String>();
      var ordinals = new HashSet<String>();
      int chunks = 0;
      int total = -1;
      for (var leaf : reader.leaves()) {
        var live = leaf.reader().getLiveDocs();
        for (int i = 0; i < leaf.reader().maxDoc(); i++) {
          if (live != null && !live.get(i)) continue;
          var document = leaf.reader().storedFields().document(i);
          String parent = document.get(io.justsearch.indexing.SchemaFields.PARENT_DOC_ID);
          if (parent == null) continue;
          chunks++;
          int declared = document.getField(io.justsearch.indexing.SchemaFields.CHUNK_TOTAL).numericValue().intValue();
          if (total < 0) total = declared;
          assertEquals(total, declared, "every chunk belongs to the same complete set");
          assertTrue(ids.add(document.get(io.justsearch.indexing.SchemaFields.DOC_ID)), "duplicate chunk ID");
          assertTrue(ordinals.add(parent + ":" + document.getField(io.justsearch.indexing.SchemaFields.CHUNK_INDEX)
              .numericValue()), "duplicate chunk ordinal for the same parent");
        }
      }
      assertTrue(chunks >= 5000, "large replay must persist all chunks, observed " + chunks);
      assertEquals(total, chunks,
          "COMPLETE requires the entire declared chunk set");
      persisted = chunks;
      System.out.println("RECOVERABLE_CHUNK_SET: COMPLETE, chunks=" + chunks
          + ", uniqueIds=" + ids.size() + ", uniqueOrdinals=" + ordinals.size());
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("jobs.db"));
        var query = db.createStatement(); var rows = query.executeQuery(
            "SELECT state, count(*) AS n FROM jobs GROUP BY state")) {
      assertTrue(rows.next());
      assertEquals("DONE", rows.getString("state"));
      assertEquals(1, rows.getInt("n"), "exactly one recorded file revision");
      assertFalse(rows.next());
    }
    try (var operations = new SqliteOperationStore(data.resolve("operations.db"))) {
      assertEquals(1, operations.find(key).orElseThrow().unitsCompleted());
    }
    return persisted;
  }

  private static Process launch(Path data, String phase) throws Exception {
    Path args = data.resolve(phase + ".args");
    String classpath = System.getProperty("java.class.path").replace("\\", "/").replace("\"", "\\\"");
    Files.writeString(args, "--enable-native-access=ALL-UNNAMED\n-cp\n\"" + classpath
        + "\"\n" + Probe.class.getName() + "\n\"" + data.toString().replace("\\", "/")
        + "\"\n" + phase + "\n", StandardCharsets.UTF_8);
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "@" + args).redirectErrorStream(true).redirectOutput(data.resolve(phase + ".log").toFile()).start();
    // Capture ownership at spawn; this test never terminates by name, ancestry, or bare PID.
    Files.writeString(data.resolve(phase + ".identity"), process.pid() + " "
        + process.info().startInstant().orElseThrow(), StandardCharsets.UTF_8);
    return process;
  }

  private static void awaitOwnedExit(Process process) throws Exception {
    long pid = process.pid();
    Instant creation = process.info().startInstant().orElseThrow();
    try {
      assertTrue(process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the owned probe must exit within its bound");
    } finally {
      if (process.isAlive()) {
        if (process.pid() != pid || !process.info().startInstant().orElseThrow().equals(creation)) {
          throw new AssertionError("refusing to stop a probe with a different creation identity");
        }
        process.destroyForcibly(); // retained spawn handle, verified PID plus creation instant
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  private static String readLog(Path data, String phase) throws Exception {
    String log = Files.readString(data.resolve(phase + ".log"), StandardCharsets.UTF_8);
    log.lines().filter(line -> line.startsWith("RECOVERABLE_COMPLETE:")).forEach(System.out::println);
    return log;
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      try { run(args); }
      catch (Throwable failure) {
        failure.printStackTrace();
        Runtime.getRuntime().halt(99);
      }
    }

    // This disposable probe JVM exits to exercise the real process recovery boundary.
    @SuppressWarnings("PMD.DoNotTerminateVM")
    private static void run(String[] args) throws Exception {
      Path data = Path.of(args[0]);
      boolean abandon = args[1].equals("abandon");
      EngineTestHarness.publishConfig(data, data.resolve("index"), Map.of(
          "justsearch.models.dir", Files.createDirectories(data.resolve("empty-models")).toString(),
          "justsearch.gpu.enabled", "false", "justsearch.embed.gpu.enabled", "false",
          "justsearch.splade.gpu_enabled", "false", "justsearch.extraction.sandbox.mode", "in_process"));
      var operations = new SqliteOperationStore(data.resolve("operations.db"));
      var attempts = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
          new RecordedIngestPlanResolver());
      var authority = OperationAuthority.load(data);
      var control = DefaultChildControl.openForEngine(data, "replay-" + args[1], ManagedChildRegistry.noop(),
          VramProbe.none(), io.justsearch.app.api.runtime.containment.EngineContainment.installAtBoot());
      var factoryMethod = EngineRoot.class.getDeclaredMethod("serverFactory", ChildControl.class,
          java.util.function.Supplier.class);
      factoryMethod.setAccessible(true);
      var factory = (EngineRoot.ServerFactory) factoryMethod.invoke(null, control,
          (java.util.function.Supplier<io.justsearch.configuration.resolved.ConfigStore>)
              io.justsearch.configuration.resolved.ConfigStore::global);
      var root = new EngineRoot(operations, attempts, factory, 30_000, 100,
          Runtime.getRuntime()::halt, () -> { throw new AssertionError("unexpected relaunch"); }, authority);
      root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      String key;
      AutoCloseable initialWork = null;
      if (!args[1].equals("recover")) {
        var coordinator = root.recordedIngestion();
        var serverField = EngineRoot.class.getDeclaredField("server");
        serverField.setAccessible(true);
        var server = (KnowledgeServer) serverField.get(root);
        var runtimeField = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
        runtimeField.setAccessible(true);
        var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) runtimeField.get(server);
        var sessionField = runtime.getClass().getDeclaredField("session");
        sessionField.setAccessible(true);
        Object session = sessionField.get(runtime);
        var telemetryField = session.getClass().getDeclaredField("telemetryEvents");
        telemetryField.setAccessible(true);
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        telemetryField.set(session, new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents() {
          @Override public void onWriteBarrierContention(long waitNanos) {
            if (mutations.incrementAndGet() != 1000 || !abandon) return;
            runtime.commitOps().commitAndTrack();
            runtime.commitOps().maybeRefreshBlocking();
            try {
              var ids = runtime.documentFieldOps().queryDocIdsByFieldOrThrow(
                  io.justsearch.indexing.SchemaFields.PARENT_DOC_ID,
                  io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(data.resolve("documents/large-replay.txt")), 10000);
              if (ids.isEmpty()) throw new AssertionError("no partial chunks persisted");
              String total = runtime.documentFieldOps().getDocumentFieldOrThrow(ids.getFirst(),
                  io.justsearch.indexing.SchemaFields.CHUNK_TOTAL);
              Files.writeString(data.resolve("declared-total"), total, StandardCharsets.UTF_8);
              System.out.println("INTERRUPTED: chunks=" + ids.size() + ", total=" + total);
              if (ids.size() >= Integer.parseInt(total)) throw new AssertionError("not interrupted mid-file");
              if (!Files.exists(data.resolve("operation-key"))) throw new AssertionError("recorded unit not started");
            } catch (Exception failure) { throw new AssertionError(failure); }
            Runtime.getRuntime().halt(EngineExit.FATAL_OR_UNCAUGHT);
          }
        });
        Files.copy(data.resolve("source.txt"), data.resolve("documents/large-replay.txt"));
        var generation = new IndexGenerationManager(data.resolve("index")).initializeOrLoad().activeGenerationId();
        var plan = new RecordedRootPlan(generation, List.of(new RecordedRootPlan.Root(
            data.resolve("documents/large-replay.txt"), "documents", false, true, List.of(), List.of())));
        var context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "quit-replay",
            Optional.empty(), Optional.of(new OperationAuthorizationBasis.StructuralAuto().encode()),
            TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        key = OperationKeys.generate(Clock.systemUTC());
        var request = new OperationAttemptRunner.Request(key,
            OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
            EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(), Optional.empty()));
        var prepared = RecordedParentFixture.prepare(request, plan);
        attempts.savePreparation(request, prepared);
        var accepted = attempts.acceptPrepared(request, prepared.nonce());
        var work = root.admission().admit(context, false);
        initialWork = work;
        attempts.start(accepted, handle -> coordinator.execute(handle, work.context()));
        Files.writeString(data.resolve("operation-key"), key, StandardCharsets.UTF_8);
        if (abandon) {
          TimeUnit.SECONDS.sleep(COMPLETION_TIMEOUT_SECONDS);
          throw new AssertionError("interrupted ingest never reached its chunk write checkpoint");
        }
      }
      key = Files.readString(data.resolve("operation-key"), StandardCharsets.UTF_8);
      long started = System.nanoTime();
      long until = started + TimeUnit.SECONDS.toNanos(COMPLETION_TIMEOUT_SECONDS);
      while (System.nanoTime() < until && operations.find(key).orElseThrow().state() != OperationState.COMPLETE) {
        root.recordedIngestion().maintain();
        Thread.sleep(100);
      }
      if (operations.find(key).orElseThrow().state() != OperationState.COMPLETE) {
        throw new AssertionError("recoverable unit did not reach COMPLETE: " + operations.find(key));
      }
      System.out.println("RECOVERABLE_COMPLETE: phase=" + args[1] + ", elapsedMs="
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      // COMPLETE is durable before the attempt's physical work permit is released. Use the
      // production shutdown order rather than racing close against that final release.
      if (initialWork != null) initialWork.close();
      root.admission().beginClosing();
      attempts.beginClosing();
      root.quiesceProducers(Duration.ofSeconds(5));
      if (!root.admission().awaitDrained(Duration.ofSeconds(5))
          || !attempts.awaitDrained(Duration.ofSeconds(5))) {
        throw new AssertionError("completed replay did not release its physical work");
      }
      root.close();
      control.close();
      operations.close();
      root.executors().close();
    }
  }
}
