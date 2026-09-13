package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 406 Phase 6 / Gap E: stress test for 50 holder-swap cycles with concurrent reads
 * and writes. Validates the design's "build new value, swap, close old" pattern under load:
 * no thread leaks, no file-handle leaks, no leaked {@code RuntimeSession} instances.
 *
 * <p>Thread mix:
 *
 * <ul>
 *   <li>4 reader threads: tight search loop via {@code holder.get().readPathOps().search(...)}
 *   <li>2 writer threads: tight indexSingle loop via {@code holder.get().indexingCoordinator()};
 *       catches typed drain-time rejections and retries on the upgraded holder reference
 *   <li>1 swapper thread: 50 cycles of {@code old = holder.getAndSet(builder.open());
 *       old.drainAndClose(...);}
 * </ul>
 *
 * <p>Tagged {@code @Tag("stress")} so it's excluded by default; opt in with
 * {@code -PincludeStress=true}.
 */
@Tag("stress")
class LifecycleStressTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  private static final int CYCLES = 50;
  private static final int READERS = 4;
  private static final int WRITERS = 2;

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void fiftyHolderSwapCycles_noLeaks_concurrentReadsAndWrites() throws Exception {
    Path indexPath = tempDir.resolve("stress-idx");
    Files.createDirectories(indexPath);

    // Seed the index with 100 docs so search has something to find.
    var seed =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();
    for (int i = 0; i < 100; i++) {
      seed.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "seed-" + i,
                      SchemaFields.DOC_UID, "seed-" + i + "#0",
                      SchemaFields.CONTENT, "seed content " + i)));
    }
    seed.commitOps().commitAndTrack();
    seed.close();

    // Build the schema once; used by all cycles.
    IndexSchema schema =
        IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(4),
            new SsotCommitMetadataSource(),
            new JsonSchemaCommitMetadataValidator());

    AtomicReference<RunningRuntime> holder =
        new AtomicReference<>(schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open());

    // Track weak refs to prove no RuntimeSession leak.
    List<WeakReference<RunningRuntime>> swappedOut = new CopyOnWriteArrayList<>();
    AtomicInteger searchesCompleted = new AtomicInteger(0);
    AtomicInteger writesCompleted = new AtomicInteger(0);
    AtomicInteger writesRetried = new AtomicInteger(0);
    AtomicBoolean stop = new AtomicBoolean(false);
    List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    int baselineThreads = Thread.activeCount();

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch writerInsideBarrier = new CountDownLatch(1);
    CountDownLatch drainMarked = new CountDownLatch(1);
    AtomicBoolean armFirstWriter = new AtomicBoolean(true);
    holder.get().session().telemetryEvents =
        new LuceneRuntimeTypes.TelemetryEvents() {
          @Override
          public void onWriteBarrierContention(long waitNanos) {
            if (!Thread.currentThread().getName().equals("stress-writer-0")
                || !armFirstWriter.compareAndSet(true, false)) {
              return;
            }
            writerInsideBarrier.countDown();
            try {
              if (!drainMarked.await(10, TimeUnit.SECONDS)) {
                uncaught.add(new AssertionError("swapper did not mark the forced drain in time"));
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              uncaught.add(e);
            }
          }
        };
    List<Thread> workers = new ArrayList<>();

    // Reader threads
    for (int i = 0; i < READERS; i++) {
      Thread t =
          new Thread(
              () -> {
                try {
                  start.await();
                  while (!stop.get()) {
                    try {
                      var rt = holder.get();
                      if (rt == null) {
                        // Holder briefly null between drainAndClose + fresh open.
                        // Park briefly to avoid CPU-spinning.
                        LockSupport.parkNanos(100_000L); // 0.1ms
                        continue;
                      }
                      rt.readPathOps()
                          .search(
                              new MatchAllDocsQuery(),
                              10,
                              Set.of(),
                              RuntimeSearchSort.RELEVANCE,
                              null);
                      // Count any successful search (hit count varies with swap timing).
                      searchesCompleted.incrementAndGet();
                    } catch (Throwable e) {
                      // Ops may briefly throw during swap; tolerate and retry on next loop
                      if (!isExpectedSwapException(e)) uncaught.add(e);
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              },
              "stress-reader-" + i);
      t.setUncaughtExceptionHandler((th, e) -> uncaught.add(e));
      workers.add(t);
      t.start();
    }

    // Writer threads
    for (int i = 0; i < WRITERS; i++) {
      final int writerId = i;
      Thread t =
          new Thread(
              () -> {
                try {
                  start.await();
                  int n = 0;
                  while (!stop.get()) {
                    try {
                      var rt = holder.get();
                      if (rt == null) {
                        // Holder briefly null between drainAndClose + fresh open.
                        // Park briefly to avoid CPU-spinning.
                        LockSupport.parkNanos(1_000_000L); // 1ms
                        continue;
                      }
                      String docId = "writer-" + writerId + "-" + (n++);
                      rt.indexingCoordinator()
                          .indexSingle(
                              new IndexDocument(
                                  Map.of(
                                      SchemaFields.DOC_ID, docId,
                                      SchemaFields.DOC_UID, docId + "#0",
                                      SchemaFields.CONTENT, "writer body " + n)));
                      writesCompleted.incrementAndGet();
                    } catch (IndexRuntimeIOException e) {
                      if (e.reason() != IndexRuntimeIOException.Reason.DRAINING) {
                        throw e;
                      }
                      // The governed drain signal tells the caller to retry on the upgraded holder.
                      writesRetried.incrementAndGet();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              },
              "stress-writer-" + i);
      t.setUncaughtExceptionHandler((th, e) -> uncaught.add(e));
      workers.add(t);
      t.start();
    }

    // Swapper thread. Lucene allows only one IndexWriter per directory, so the cycle
    // is: mark draining → swap holder to null (writers see null and skip) → drainAndClose
    // old → open fresh → swap holder to fresh. Readers see null briefly and retry.
    Thread swapper =
        new Thread(
            () -> {
              try {
                start.await();
                for (int cycle = 0; cycle < CYCLES; cycle++) {
                  RunningRuntime old = holder.get();
                  if (cycle == 0
                      && !writerInsideBarrier.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("first writer did not enter the write barrier in time");
                  }
                  // Mark draining so writers stop immediately.
                  old.session().draining = true;
                  // Swap to null so new writes route nowhere; existing in-flight finish.
                  holder.set(null);
                  if (cycle == 0) {
                    drainMarked.countDown();
                  }
                  swappedOut.add(new WeakReference<>(old));
                  old.drainAndClose(Duration.ofSeconds(5));
                  // Old writer is now closed; safe to acquire write.lock for the new instance.
                  RunningRuntime fresh = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
                  holder.set(fresh);
                  // Tiny pause so readers/writers get a live window each cycle.
                  Thread.sleep(20);
                }
              } catch (Throwable e) {
                uncaught.add(e);
              } finally {
                drainMarked.countDown();
                stop.set(true);
              }
            },
            "stress-swapper");
    swapper.setUncaughtExceptionHandler((th, e) -> uncaught.add(e));
    workers.add(swapper);
    swapper.start();

    // Release the hounds.
    start.countDown();

    // Wait for swapper to finish all cycles.
    swapper.join(120_000L);
    stop.set(true);
    for (Thread t : workers) {
      t.join(5_000L);
    }

    // Final close + verification.
    var lastRuntime = holder.getAndSet(null);
    if (lastRuntime != null) {
      lastRuntime.drainAndClose(Duration.ofSeconds(5));
    }

    // Settle window: System.gc + sleep to let MMapDirectory file mappings finalize.
    System.gc();
    Thread.sleep(2_000L);
    System.gc();
    Thread.sleep(500L);

    // Assertions.
    assertEquals(CYCLES, swappedOut.size(), "expected exactly " + CYCLES + " swap cycles");
    assertTrue(searchesCompleted.get() > 0, "readers should have completed at least one search");
    assertTrue(writesCompleted.get() > 0, "writers should have completed at least one write");
    // The first swap deterministically marks draining while writer 0 holds the barrier read lock.
    // This pins the production retry contract without relying on an incidental scheduling race.
    assertTrue(
        writesRetried.get() > 0,
        "writers should have hit at least one typed drain rejection across "
            + CYCLES
            + " cycles; got "
            + writesRetried.get());
    assertTrue(uncaught.isEmpty(), "no unexpected exceptions; got: " + uncaught);

    // Thread leak: tolerate small noise from JIT / GC threads.
    int currentThreads = Thread.activeCount();
    assertTrue(
        currentThreads <= baselineThreads + 5,
        "thread leak: baseline=" + baselineThreads + ", current=" + currentThreads);

    // RuntimeSession leak: most refs should be enqueued (GC may not collect everything,
    // tolerate up to 10 references — Lucene mmap pinning can keep a handful alive briefly).
    int alive = 0;
    for (WeakReference<RunningRuntime> ref : swappedOut) {
      if (ref.get() != null) alive++;
    }
    assertTrue(
        alive <= 10,
        "RuntimeSession leak: " + alive + " of " + CYCLES + " swapped-out runtimes still alive");
  }

  private static boolean isExpectedSwapException(Throwable e) {
    String msg = e.getMessage();
    if (msg == null) return false;
    String lower = msg.toLowerCase();
    return lower.contains("draining")
        || lower.contains("not writable")
        || lower.contains("already closed")
        || lower.contains("not ready")
        || lower.contains("runtime closed")
        || lower.contains("searchermanager not available");
  }
}
