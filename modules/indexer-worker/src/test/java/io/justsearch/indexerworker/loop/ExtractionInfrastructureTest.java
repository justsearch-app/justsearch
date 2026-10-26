/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Round-nine O7/O8 and K1/K4: real snapshot storage plus a content-independent untyped failure. */
final class ExtractionInfrastructureTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "snapshotStorage({0})")
  @ValueSource(strings = {"readonly", "lost-directory", "readonly-child", "lost-directory-child"})
  @Timeout(40)
  @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void engineOwnedSnapshotFailureNeverChargesHealthySources(String mode) throws Exception {
    try (var h = new Fixture(mode.endsWith("-child"))) {
      // D8: prove the same actual provider and immutable >1 MiB inputs work before damaging scratch.
      assertTrue(Files.size(h.sources.getFirst()) > 1024 * 1024);
      assertTrue(h.timebox.extractArtifact(h.sources.getFirst()).result().content().contains("healthy"));
      Path scratch = h.snapshotFolder; assertNotNull(scratch);
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(scratch);
      try {
        if (mode.startsWith("readonly")) {
          Files.setPosixFilePermissions(scratch, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
          assertFalse(Files.isWritable(scratch));
        } else Files.delete(scratch);
        for (Path source : h.sources) assertTrue(Files.isReadable(source));
        assertThrows(Exception.class, () -> h.timebox.extractArtifact(h.sources.getFirst()));
        h.start(); h.assertOutageBeyondGrace();
        if (mode.startsWith("readonly")) Files.setPosixFilePermissions(scratch, permissions);
        else Files.createDirectory(scratch);
        h.assertRecovery();
        assertTrue(h.probes.get() >= 4, "shared snapshot failures are proved by the actual canary every pause");
      } finally {
        if (!Files.exists(scratch)) Files.createDirectory(scratch);
        Files.setPosixFilePermissions(scratch, permissions);
      }
    }
  }

  @Test @Timeout(40)
  void untypedInfrastructureFailureIsProvenByTheSameExtractionCanary() throws Exception {
    try (var h = new Fixture()) {
      h.unknown.set(true);
      h.start(); h.assertOutageBeyondGrace();
      assertEquals(4, h.probes.get(), "one canary per failed batch, immediately and shared across rows");
      assertEquals(0, h.writes.get(), "canary must never write an index document");
      h.unknown.set(false); h.assertRecovery();
      assertEquals(4, h.probes.get(), "healthy batches never probe");
      assertTrue(h.errors.stream().allMatch(e -> e instanceof ContentExtractor.ExtractionException),
          "O8 has no typed availability or filesystem provenance signal");
    }
  }

  @ParameterizedTest(name = "poisonCanary({0})")
  @ValueSource(strings = {"mixed", "one", "all"})
  @Timeout(35)
  void poisonOnlyAndMixedBatchesTerminateWithHealthyInfrastructure(String shape) throws Exception {
    try (var h = new Fixture()) {
      boolean mixed = shape.equals("mixed");
      h.poison.set(true); h.mixed = !shape.equals("all"); h.isolated = shape.equals("one");
      h.resetSnapshotAfterWarmup();
      h.start();
      await(() -> h.rows().stream().allMatch(r -> r.state().equals("FAILED") || r.state().equals("DONE")), 18_000);
      var rows = h.rows();
      System.out.println("K" + (mixed ? "1" : "4") + " poison rows=" + rows + " probes=" + h.probes);
      assertEquals(mixed || h.isolated ? 1 : 2, rows.stream().filter(r -> r.state().equals("FAILED") && r.attempts() == 1).count());
      assertEquals(mixed ? 1 : 0, rows.stream().filter(r -> r.state().equals("DONE") && r.attempts() == 0).count());
      assertEquals(mixed ? 0 : 1, h.probes.get(), "successful peer proves per-file; all-poison needs one passing canary");
      if (!mixed) assertTrue(h.probeSnapshot.get(), "passing canary used real snapshot and parser");
      if (h.isolated) {
        queueHealthyLater(h);
        await(() -> h.rows().stream().anyMatch(r -> r.state().equals("DONE")), 4_000);
        assertEquals(1, h.rows().stream().filter(r -> r.state().equals("FAILED") && r.attempts() == 1).count());
      }
    }
  }

  private static void queueHealthyLater(Fixture h) throws Exception {
    Path later = h.sources.getLast();
    h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(later).withinRoot(later.getParent())));
  }

  private record Row(String state, int attempts, long retry, String policy) {}
  @FunctionalInterface private interface Check { boolean get() throws Exception; }
  private static void await(Check condition, long ms) throws Exception {
    long end = System.nanoTime() + ms * 1_000_000;
    while (!condition.get() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(condition.get(), "condition before " + ms + " ms deadline");
  }

  private final class Fixture implements AutoCloseable {
    final Path db = temp.resolve("jobs.db");
    final SqliteJobQueue actualQueue = new SqliteJobQueue(db);
    final SqliteJobQueue queue = mock(SqliteJobQueue.class, org.mockito.AdditionalAnswers.delegatesTo(actualQueue));
    final TestEngineExecutors owners = new TestEngineExecutors();
    final PolicyDrivenTikaExtractor provider = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
    final AtomicBoolean unknown = new AtomicBoolean();
    final AtomicBoolean poison = new AtomicBoolean();
    final AtomicInteger probes = new AtomicInteger();
    final AtomicInteger writes = new AtomicInteger();
    final AtomicBoolean recovering = new AtomicBoolean();
    final AtomicLong recoveredClaimAtMs = new AtomicLong();
    final AtomicInteger recoveredClaimCount = new AtomicInteger();
    final AtomicBoolean probeSnapshot = new AtomicBoolean();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    final List<Path> sources;
    TimeboxedContentExtractor timebox;
    Path snapshotFolder;
    Path childScratchRoot;
    ExtractionSandbox actual;
    IndexingLoop loop;
    WorkerIngestService service;
    boolean mixed;
    boolean isolated;

    Fixture() throws Exception { this(false); }

    Fixture(boolean process) throws Exception {
      try {
        Path corpus = Path.of(System.getProperty("justsearch.test.outageCorpus",
            Path.of(System.getProperty("java.io.tmpdir"), "justsearch-outage-regression-corpus").toString()));
        Files.createDirectories(corpus);
        sources = List.of(corpus.resolve("snapshot-healthy-a.txt"), corpus.resolve("snapshot-healthy-b.txt"));
        // Generate once in a fixed folder; later cases, attempts and builder rounds reuse the bytes.
        for (Path source : sources) {
          if (!Files.exists(source)) Files.writeString(source, "healthy readable source content\n".repeat(36_000));
        }
        Path warmup = corpus.resolve("canary-warmup.rtf");
        if (!Files.exists(warmup)) Files.writeString(warmup, "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\fnil Arial;}}\\pard\\f0 JustSearch healthy extraction canary.\\par}");
        queue.open();
        assertTrue(provider.extractArtifact(corpus.resolve("canary-warmup.rtf")).result().content()
            .contains("healthy extraction canary"), "publish a warmed real parser before timed recovery checks");
        if (process) {
          childScratchRoot = Files.createTempDirectory(temp, "extraction-child-").toAbsolutePath().normalize();
          var readers = owners.register(new EngineExecutorSpec("round9-reader", EngineExecutorSpec.Kind.BACKGROUND,
              EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
          actual = new PersistentExtractionSandbox(readers,
              List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Djava.io.tmpdir=" + childScratchRoot, "-cp", System.getProperty("java.class.path"), ExtractionSandboxChild.class.getName()),
              TikaExtractionPolicy.defaults(), null, Duration.ofSeconds(5), 1, 100, null);
          Set<Path> before = snapshotFolders();
          actual.extract(sources.getFirst());
          Set<Path> created = snapshotFolders(); created.removeAll(before);
          assertEquals(1, created.size(), "one real child owns one private snapshot folder");
          snapshotFolder = created.iterator().next();
          assertEquals(childScratchRoot, snapshotFolder.getParent(), "snapshot must belong to this fixture's child");
        } else {
          actual = provider::extractArtifact;
          var field = PolicyDrivenTikaExtractor.class.getDeclaredField("inputFactory"); field.setAccessible(true);
          Object factory = field.get(provider);
          var dir = factory.getClass().getDeclaredField("directory"); dir.setAccessible(true);
          snapshotFolder = (Path) dir.get(factory);
        }
        ExtractionSandbox sandbox = new ExtractionSandbox() {
          @Override public ExtractionArtifact extract(Path file) throws IOException, ContentExtractor.ExtractionException {
            boolean probe = file.getFileName().toString().startsWith("justsearch-extraction-canary-");
            if (probe) probes.incrementAndGet();
            try {
              if (unknown.get()) throw new ContentExtractor.ExtractionException("untyped infrastructure fault");
              if (poison.get() && !probe && (!mixed || file.equals(sources.getFirst()))) {
                throw new IllegalStateException("content crashes parser");
              }
              var artifact = actual.extract(file);
              if (probe) {
                assertTrue(artifact.result().content().contains("healthy extraction canary"));
                var field = PolicyDrivenTikaExtractor.class.getDeclaredField("inputFactory"); field.setAccessible(true);
                Object factory = field.get(provider);
                var dir = factory.getClass().getDeclaredField("directory"); dir.setAccessible(true);
                probeSnapshot.set(dir.get(factory) != null);
              }
              return artifact;
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            catch (IOException | ContentExtractor.ExtractionException | RuntimeException failure) { errors.add(failure); throw failure; }
          }
        };
        var registration = owners.register(new EngineExecutorSpec("round9-timebox", EngineExecutorSpec.Kind.BACKGROUND,
            EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
        timebox = new TimeboxedContentExtractor(registration, sandbox, Duration.ofSeconds(5), null);
      } catch (Exception | Error failure) {
        close(failure);
        throw failure;
      }
    }

    void resetSnapshotAfterWarmup() throws Exception {
      var field = PolicyDrivenTikaExtractor.class.getDeclaredField("inputFactory"); field.setAccessible(true);
      Object factory = field.get(provider);
      var directory = factory.getClass().getDeclaredField("directory"); directory.setAccessible(true);
      synchronized (factory) {
        Files.delete((Path) directory.get(factory));
        directory.set(factory, null);
      }
      // Poison fails before provider admission. Only the built-in canary can recreate this folder.
    }

    Set<Path> snapshotFolders() throws IOException {
      try (var paths = Files.list(childScratchRoot)) {
        return new HashSet<>(paths.filter(p -> p.getFileName().toString().startsWith("justsearch-parse-input-"))
            .toList());
      }
    }

    void start() throws Exception {
      var identities = mock(DocumentIdentityStore.class);
      when(identities.resolve(anyString(), anyLong())).thenAnswer(call -> new DocumentIdentityStore.Identity(
          call.getArgument(0), "uid-" + call.getArgument(0), call.getArgument(1), call.getArgument(1)));
      var writer = mock(IndexingCoordinator.class);
      doAnswer(call -> { writes.incrementAndGet(); return null; }).when(writer).indexSingle(any());
      doAnswer(call -> {
        List<JobQueue.IndexJob> claims = actualQueue.pollPending(call.getArgument(0));
        if (recovering.get() && !claims.isEmpty() && recoveredClaimAtMs.get() == 0) {
          recoveredClaimCount.set(claims.size());
          recoveredClaimAtMs.set(System.currentTimeMillis());
        }
        return claims;
      }).when(queue).pollPending(anyInt());
      loop = new IndexingLoop(null, null, queue, writer, mock(CommitOps.class), mock(DocumentFieldOps.class),
          mock(IndexCountOps.class), () -> new ResolvedConfigBuilder().build(), mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), null, null, null, null, timebox, null,
          new IndexingLoopOptions(false, null, identities, null, null, null));
      service = new WorkerIngestService(queue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
          temp, temp.resolve("index"), null, null, null, 0L);
      queue.enqueueEntries(sources.stream().limit(isolated ? 1 : 2).map(p -> JobQueue.EnqueueEntry.stat(p).withinRoot(p.getParent())).toList());
      loop.start();
    }

    boolean paused() throws Exception {
      var field = IndexingLoop.class.getDeclaredField("loopThread"); field.setAccessible(true);
      Thread owner = (Thread) field.get(loop);
      return Arrays.stream(owner.getStackTrace()).anyMatch(f -> f.getMethodName().equals("sleepAfterError"));
    }

    void assertOutageBeyondGrace() throws Exception {
      await(() -> (errors.size() >= 8 && paused()
          && rows().stream().allMatch(r -> r.retry() >= System.currentTimeMillis() + 6_000))
          || rows().stream().anyMatch(r -> r.attempts() > 0), 20_000);
      var rows = rows();
      System.out.println("round9 outage errors=" + errors.size() + " probes=" + probes + " rows=" + rows);
      assertTrue(rows.stream().allMatch(r -> r.attempts() == 0), "shared infrastructure must never charge healthy source files");
      assertTrue(rows.stream().allMatch(r -> r.state().equals("PENDING") && r.policy().equals("DEFER_WITHOUT_ATTEMPT")));
      long deadline = rows.getFirst().retry();
      assertTrue(deadline > System.currentTimeMillis() && deadline <= System.currentTimeMillis() + 8_000);
      assertTrue(rows.stream().allMatch(r -> r.retry() == deadline));
      var status = service.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());
      assertEquals("WAITING", status.getCore().getState());
      assertEquals(deadline, status.getFailure().getNextRetryAtMs());
      assertEquals(0, status.getMigration().getProcessingJobsCount());
    }

    void assertRecovery() throws Exception {
      long deadline = rows().getFirst().retry();
      recovering.set(true);
      await(() -> recoveredClaimAtMs.get() > 0, Math.max(0, deadline - System.currentTimeMillis()) + 1_000);
      assertEquals(2, recoveredClaimCount.get(), "both deferred files are claimed together");
      assertTrue(recoveredClaimAtMs.get() >= deadline && recoveredClaimAtMs.get() <= deadline + 1_000,
          "recovery claim must agree with the persisted resume deadline, not a minute-scale wait");
      // Claim latency is independent of parsing two >1 MiB files (each has a five-second timebox).
      await(() -> rows().stream().allMatch(r -> r.state().equals("DONE")), 10_000);
      assertTrue(rows().stream().allMatch(r -> r.attempts() == 0));
      System.out.println("recovered claim=" + recoveredClaimAtMs.get() + " deadline=" + deadline
          + " count=" + recoveredClaimCount.get() + " attempts=0");
    }

    List<Row> rows() throws Exception {
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
          var rows = s.executeQuery("SELECT state,attempts,retry_after,last_retry_policy FROM jobs ORDER BY path")) {
        var result = new ArrayList<Row>();
        while (rows.next()) result.add(new Row(rows.getString(1), rows.getInt(2), rows.getLong(3), rows.getString(4)));
        return result;
      }
    }
    @Override public void close() throws Exception { close(null); }

    private void close(Throwable primary) throws Exception {
      Throwable failure = primary;
      for (AutoCloseable resource : new AutoCloseable[] {loop, timebox, actual, provider, owners, queue}) {
        if (resource == null) continue;
        try { resource.close(); }
        catch (Exception | Error cleanup) {
          if (failure == null) failure = cleanup;
          else if (failure != cleanup) failure.addSuppressed(cleanup);
        }
      }
      if (primary == null && failure instanceof Exception exception) throw exception;
      if (primary == null && failure instanceof Error error) throw error;
    }
  }
}
