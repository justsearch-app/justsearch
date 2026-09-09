/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C2 — a watched root's OWN initial scan carries the root's collection.
 *
 * <p>This file used to be the baseline pin for the opposite behaviour: the label reached the
 * watcher arm ({@code WorkerWatchFn.watch(rootPath, collection)}) but the scan arm had no channel
 * for it, so {@link RootLifecycleOps.ScanRootFn#scan} took only (rootPath, excludeGlobs,
 * progressConsumer) and the documents the initial scan admitted carried no {@code collection} at
 * all. The scan arm now takes the label too, and the assertions below are the positive inversion:
 * both arms must receive the SAME collection for the same root.
 *
 * <p>The rule these tests pin is uniform: a root's label reaches every arm and every restart, and
 * an unlabeled root maps to {@code IngestCollectionPolicy.DEFAULT_COLLECTION} everywhere, so one
 * root's documents never oscillate between tagged and untagged.
 *
 * <p>Tempdoc 885 §UD open item 4 closes the third arm: the label is now PERSISTED with the root, so
 * it also reaches {@code getWatchedRoots()} (which reported {@code null} → {@code "default"} on the
 * live repro) and the restart rewalk (which re-tagged a labelled root's documents as the default).
 */
@DisplayName("watched-root scan — collection carriage")
final class WatchedRootScanCollectionTest {

  @TempDir Path tempDir;

  /** What each arm received for one root. */
  private record Arms(String watched, String scanned, String scannedRoot, ScanMode scannedMode) {}

  /** RootLifecycleOps wired so both arms record the collection they were handed. */
  private final class Capture {
    private final Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    private final AtomicReference<String> watchedCollection = new AtomicReference<>();
    private final AtomicReference<String> scannedCollection = new AtomicReference<>();
    private final AtomicReference<String> scannedRoot = new AtomicReference<>();
    private final AtomicReference<ScanMode> scannedMode = new AtomicReference<>();
    private final ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    private final WatchedRootsState state;
    private final RootLifecycleOps ops;

    Capture(String storeName) {
      state =
          new WatchedRootsState(
              watchedRoots, new WatchedRootsStore(tempDir.resolve(storeName + ".json"), null));
      ops =
          new RootLifecycleOps(
              watchedRoots,
              state,
              () -> ExcludeMatcher.empty(),
              (rootPath, collection, mode, globs, progress, engineContext) -> {
                scannedRoot.set(rootPath);
                scannedCollection.set(collection);
                scannedMode.set(mode);
                return null;
              },
              new RootLifecycleOps.WorkerWatchFn() {
                @Override
                public void watch(String rootPath, String collection, io.justsearch.core.context.EngineContext engineContext) {
                  watchedCollection.set(collection);
                }

                @Override
                public void unwatch(String rootPath, io.justsearch.core.context.EngineContext engineContext) {}
              },
              (p, engineContext) -> null,
              (s, engineContext) -> null,
              mock(SyncOps.class),
              walkExecutor);
    }

    /** Drains the queued walk, then reports what each arm saw. */
    Arms drain() throws Exception {
      walkExecutor.shutdown();
      assertTrue(
          walkExecutor.awaitTermination(10, TimeUnit.SECONDS),
          "the queued walk must finish before the assertions read what it dispatched");
      return new Arms(
          watchedCollection.get(),
          scannedCollection.get(),
          scannedRoot.get(),
          scannedMode.get());
    }
  }

  private Arms addRootAndCapture(String label, Path root) throws Exception {
    Capture capture = new Capture(root.getFileName().toString());
    capture.ops.addWatchedRoot(label, root, TestEngineContexts.internal());
    return capture.drain();
  }

  @Test
  @DisplayName("both the watcher arm AND the scan arm receive the root's collection")
  void labeledRootScanCarriesItsCollection() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("labeled"));

    Arms arms = addRootAndCapture("my-notes", root);

    assertTrue(arms.scannedRoot() != null, "the walk did dispatch a scan for the labeled root");
    assertEquals(
        "my-notes", arms.watched(), "the watcher arm carries the label (unchanged behaviour)");
    assertEquals(
        "my-notes",
        arms.scanned(),
        "the scan arm must carry the SAME label — the initial scan's documents belong to the"
            + " root's collection, not the default bucket");
  }

  @Test
  @DisplayName("a blank label normalizes to the default bucket on BOTH arms, not to null")
  void blankLabelNormalizesOnBothArms() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("unlabeled"));

    Arms arms = addRootAndCapture("  ", root);

    // Precision: proves the scan arm forwards addWatchedRoot's NORMALIZED label rather than
    // happening to agree by both being null/blank.
    assertEquals("default", arms.watched(), "watcher arm sees the normalized label");
    assertEquals("default", arms.scanned(), "scan arm sees the same normalized label");
  }

  @Test
  @DisplayName("the label the caller supplied is readable from getWatchedRoots — the live repro")
  void addedLabelIsReadableFromTheWatchedRootListing() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("listed"));
    Capture capture = new Capture("listed-roots");

    capture.ops.addWatchedRoot("lane-c4-live", root, TestEngineContexts.internal());
    capture.drain();

    // 885 §UL.6: POST /api/indexing/roots with {"collection":"lane-c4-live"} returned 200 and the
    // subsequent GET reported "default". Both write-only arms had the label; the listing did not,
    // because nothing kept it. This is that GET, one layer down.
    var listed = capture.ops.getWatchedRoots(TestEngineContexts.internal());
    assertEquals(1, listed.size(), "the root was registered");
    assertEquals(
        "lane-c4-live",
        listed.get(0).collection(),
        "the listing must report the label the caller supplied, not null (rendered 'default')");
  }

  @Test
  @DisplayName("an ad-hoc ingest under a labelled root inherits the label (the behaviour change)")
  void adHocIngestUnderALabelledRootInheritsTheLabel() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("inherit"));
    Path unlabeledRoot = Files.createDirectories(tempDir.resolve("inherit-plain"));
    Capture capture = new Capture("inherit-roots");
    capture.ops.addWatchedRoot("my-notes", root, TestEngineContexts.internal());
    capture.ops.addWatchedRoot(null, unlabeledRoot, TestEngineContexts.internal());
    capture.drain();

    // getWatchedRoots() feeds IngestCollectionPolicy.RootBinding at AgentToolFactory:203 and
    // KnowledgeSearchController:815, so making the listing truthful CHANGES what an ad-hoc ingest
    // with no explicit collection resolves to for a file under a labelled root: it now inherits the
    // root's label instead of the index default. That is the correct semantics — the root's own
    // scan has always tagged those documents with the label, so before this the same file got a
    // different collection depending on which arm admitted it — but it is a behaviour change and is
    // pinned here rather than left to be discovered.
    List<IngestCollectionPolicy.RootBinding> bindings =
        capture.ops.getWatchedRoots(TestEngineContexts.internal()).stream()
            .map(r -> new IngestCollectionPolicy.RootBinding(r.path(), r.collection()))
            .toList();

    assertEquals(
        "my-notes",
        IngestCollectionPolicy.resolve(null, root.resolve("note.md"), bindings),
        "a file under a labelled root belongs to that root's collection");
    assertNull(
        IngestCollectionPolicy.resolve(null, unlabeledRoot.resolve("note.md"), bindings),
        "an unlabeled root still resolves to the index default — null, exactly as before");
    assertEquals(
        "explicit",
        IngestCollectionPolicy.resolve("explicit", root.resolve("note.md"), bindings),
        "an explicitly requested collection still wins over the root's label");
  }

  @Test
  @DisplayName("a restart rewalk re-sends the PERSISTED label, not the default")
  void reindexPersistedRootsReSendsTheStoredLabel() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("relabeled"));

    // Boot 1: the user adds a labelled root; the label is persisted with it.
    Capture first = new Capture("relabeled-roots");
    first.ops.addWatchedRoot("my-notes", root, TestEngineContexts.internal());
    first.drain();

    // Boot 2: a fresh state over the SAME store file — exactly what a restart does.
    Capture second = new Capture("relabeled-roots");
    second.state.loadPersistedRoots();
    second.ops.reindexPersistedRoots(TestEngineContexts.durableInternal());
    Arms arms = second.drain();

    assertEquals("my-notes", arms.watched(), "the watcher arm re-registers under the real label");
    assertEquals(
        "my-notes",
        arms.scanned(),
        "and the rewalk re-tags the root's documents with it — re-sending 'default' here is what"
            + " made a labelled root's documents oscillate across restarts");
  }

  @Test
  @DisplayName("a restart rewalk hands BOTH arms the same label the add path wrote")
  void reindexPersistedRootsAgreesAcrossArmsAndWithTheAddPath() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("persisted"));
    Capture capture = new Capture("persisted-roots");
    // A root persisted BEFORE the collection field existed: known, with no recorded label.
    capture.state.markNeverIndexed(root.toAbsolutePath().normalize());

    capture.ops.reindexPersistedRoots(TestEngineContexts.durableInternal());
    Arms arms = capture.drain();

    // One root must carry ONE label. A rewalk that disagrees with its own watcher — or with what
    // addWatchedRoot wrote — makes the root's documents oscillate between tagged and untagged.
    assertEquals(
        arms.watched(),
        arms.scanned(),
        "the restart rewalk's two arms must agree with each other for the same root");
    assertEquals(
        "default",
        arms.scanned(),
        "and agree with the add path's normalization, so a restart does not retag the root");
  }

  /**
   * The tests above run against a test-supplied ScanRootFn, so they pin the Java threading but not
   * the PRODUCTION lambda that maps it onto the port call. This drives the real
   * {@link KnowledgeClient#addWatchedRoot} through a {@link TestKnowledgeClient} and asserts the
   * {@link ScanRootRequest} the index half would actually receive — so reverting that lambda's
   * collection argument to {@code null} fails here.
   *
   * <p>Lane F stage A item A10 replaced the in-process gRPC server and the memory-mapped signal bus
   * this nested class used with the client double. The subject is unchanged: the assertions were
   * always about the request the production ScanRootFn assembles, never about how it travelled.
   */
  @Nested
  @DisplayName("production lambda -> ScanRootRequest")
  class ProductionScanRequestForwarding {

    private final AtomicReference<ScanRootRequest> request = new AtomicReference<>();
    private final CountDownLatch received = new CountDownLatch(1);
    private KnowledgeClient client;
    private String prevDataDir;

    @BeforeEach
    void setUp() throws Exception {
      prevDataDir = System.getProperty("justsearch.data.dir");
      Path dataDir = Files.createDirectories(tempDir.resolve("data"));
      System.setProperty("justsearch.data.dir", dataDir.toString());

      client =
          new TestKnowledgeClient(
              new io.justsearch.core.execution.TestEngineExecutors(),
              null,
              null,
              req -> {
                request.set(req);
                received.countDown();
              });
    }

    @AfterEach
    void tearDown() {
      if (client != null) {
        client.close();
        client = null;
      }
      if (prevDataDir == null) {
        System.clearProperty("justsearch.data.dir");
      } else {
        System.setProperty("justsearch.data.dir", prevDataDir);
      }
    }

    @Test
    @DisplayName("addWatchedRoot's scan reaches the index half with the root's collection set")
    void scanRequestCarriesTheRootsCollection() throws Exception {
      Path root = Files.createDirectories(tempDir.resolve("wired"));

      client.addWatchedRoot("my-notes", root, TestEngineContexts.internal());

      assertTrue(
          received.await(10, TimeUnit.SECONDS),
          "the background walk must dispatch a ScanRoot call");
      ScanRootRequest sent = request.get();
      assertEquals(
          "my-notes",
          sent.getCollection(),
          "the production ScanRootFn must forward the collection into ScanRootRequest — a literal"
              + " null here is the defect this pins");
      assertEquals(
          root.toAbsolutePath().normalize().toString(),
          sent.getRootPath(),
          "same request, so the collection cannot belong to some other root's scan");
      assertEquals(
          ScanMode.SCAN_MODE_INITIAL, sent.getMode(), "a watched root's own scan is the initial one");
    }
  }
}
