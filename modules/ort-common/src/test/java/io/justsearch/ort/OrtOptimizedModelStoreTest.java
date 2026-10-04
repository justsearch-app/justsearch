/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Design 958 sections 3.1-3.5, with a deterministic optimizer seam and isolated store roots. */
final class OrtOptimizedModelStoreTest {
  @TempDir Path temp;
  private final AtomicLong time = new AtomicLong(10);

  private OrtOptimizedModelStore store(String version, long bytes) {
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", temp.resolve("cache").toString(),
        "justsearch.ort.optimized_cache_max_mb", "16"));
    Path resolved = cfg.ai().optimizedCache().directory();
    return new OrtOptimizedModelStore(resolved, version, bytes, time::get);
  }

  private Path model(String directory, int identity) throws IOException {
    Path dir = Files.createDirectories(temp.resolve(directory));
    // Minimal protobuf envelope: ModelProto.graph -> GraphProto.name. No native ORT needed.
    return Files.write(dir.resolve("model.onnx"), new byte[] {58, 3, 18, 1, (byte) identity});
  }

  private Path load(OrtOptimizedModelStore store, Path model, String ep, OptLevel level,
      AtomicInteger writes) throws IOException, OrtException {
    return store.loadOrCreate(model, ep, level, plan -> {
      if (plan.optimizedOutput() != null) {
        writes.incrementAndGet();
        assertNotEquals(model, plan.input());
        assertArrayEquals(Files.readAllBytes(model), Files.readAllBytes(plan.input()));
        assertEquals(level, plan.optimizationLevel());
        Files.write(plan.optimizedOutput(), new byte[128]);
      } else {
        assertTrue(plan.cached());
        assertEquals(level, plan.optimizationLevel());
        assertTrue(Files.isRegularFile(plan.input()));
      }
      return plan.input();
    });
  }

  @Test
  void copiesAndHardLinksShareOneEntryAndWriteNothingBesideModels() throws Exception {
    Path original = model("original", 65);
    Path copyDir = Files.createDirectories(temp.resolve("copy"));
    Path copy = Files.copy(original, copyDir.resolve("model.onnx"));
    Path linkDir = Files.createDirectories(temp.resolve("hardlink"));
    Path link = Files.createLink(linkDir.resolve("model.onnx"), original);
    var store = store("1.0", 4096);
    var writes = new AtomicInteger();
    load(store, original, "cpu", OptLevel.EXTENDED_OPT, writes);
    Path cached = load(store, copy, "cpu", OptLevel.EXTENDED_OPT, writes);
    assertEquals(cached, load(store, link, "cpu", OptLevel.EXTENDED_OPT, writes));
    assertEquals(1, writes.get());
    assertEquals(1, committedEntries().size());
    for (Path directory : List.of(original.getParent(), copyDir, linkDir)) {
      try (var files = Files.list(directory)) {
        assertEquals(List.of(directory.resolve("model.onnx")), files.toList());
      }
    }
  }

  @Test
  void everyIdentityDimensionSelectsADifferentEntryIncludingPreservedMtimeByteChange()
      throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    Path first = store.entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    FileTime modified = Files.getLastModifiedTime(model);
    model("source", 66);
    Files.setLastModifiedTime(model, modified);
    Path changed = store.entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    Path version = store("2.0", 4096).entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    Path ep = store.entryPath(model, "cuda", OptLevel.EXTENDED_OPT);
    Path level = store.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    assertEquals(5, java.util.Set.of(first, changed, version, ep, level).size());
    AtomicInteger writes = new AtomicInteger();
    load(store, model, "cpu", OptLevel.BASIC_OPT, writes);
    load(store, model, "cuda", OptLevel.EXTENDED_OPT, writes);
    load(store, model, "cpu", OptLevel.EXTENDED_OPT, writes);
    assertEquals(3, writes.get());
  }

  @Test
  void concurrentCreatorsPublishOneCompleteEntryAndRemoveAllTemporaryFiles() throws Exception {
    Path model = model("source", 65);
    var barrier = new CyclicBarrier(4);
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        futures.add(pool.submit(() -> {
          // Independent store instances simulate creators sharing no instance lock.
          store("1.0", 4096).loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
            assertNotNull(plan.optimizedOutput());
            Files.write(plan.optimizedOutput(), new byte[128]);
            try {
              barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new IOException(e);
            }
            return null;
          });
          return null;
        }));
      }
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
    }
    assertEquals(1, committedEntries().size());
    assertNoTemporaryFiles();
    assertEquals(128, Files.size(committedEntries().getFirst().resolveSibling("model.onnx")));
  }

  @Test
  void reconciliationCannotDeleteAnotherThreadsSnapshotWhenProcessLookupFails() throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-slow-source", 65);
      OptLevel level = OptLevel.EXTENDED_OPT;
      byte[] sourceBytes = Files.readAllBytes(model);
      ProcessHandle owner = ProcessHandle.current();
      var ready = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var snapshot = new AtomicReference<Path>();
      var writes = new AtomicInteger();
      try (var pool = Executors.newSingleThreadExecutor()) {
        var slow = pool.submit(() -> {
          store("1.0", 4096).loadOrCreate(model, ep, level, plan -> {
            assertFalse(plan.cached());
            assertEquals(level, plan.optimizationLevel());
            assertNotNull(plan.optimizedOutput());
            writes.incrementAndGet();
            snapshot.set(plan.input());
            ready.countDown();
            await(release); // ORT has not opened the source snapshot yet.
            assertArrayEquals(sourceBytes, Files.readAllBytes(plan.input()));
            Files.write(plan.optimizedOutput(), new byte[128]);
            return null;
          });
          return null;
        });
        try {
          await(ready);
          try (var processes = mockStatic(ProcessHandle.class)) {
            processes.when(ProcessHandle::current).thenReturn(owner);
            // Deterministically reproduce a native liveness probe that misclassifies this JVM.
            // Only this thread's probes are mocked; the slow creator is still actively waiting.
            processes.when(() -> ProcessHandle.of(owner.pid())).thenReturn(Optional.empty());
            load(store("1.0", 4096), model, ep, level, writes);
            // The second same-key creator has published and run reconciliation/eviction.
            assertTrue(Files.isRegularFile(snapshot.get()), "Live snapshot removed by reconciliation");
            assertArrayEquals(sourceBytes, Files.readAllBytes(snapshot.get()));
          }
        } finally {
          release.countDown();
        }
        slow.get(15, TimeUnit.SECONDS);
      }
      assertEquals(2, writes.get());
      assertTrue(store("1.0", 4096).contains(model, ep, level));
    }
    assertEquals(2, committedEntries().size());
    assertNoTemporaryFiles();
    assertNoQuarantines();
  }

  @Test
  void deepOverrideRootsLoadTheSourceInMemoryAndWriteNothing() throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      for (boolean existing : List.of(false, true)) {
        Path root = temp.resolve("deep-" + ep + "-" + existing);
        while (root.toAbsolutePath().toString().length() < 200) root = root.resolve("x".repeat(32));
        if (existing) Files.createDirectories(root);
        var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
            "justsearch.ort.optimized_cache_dir", root.toString()));
        var store = new OrtOptimizedModelStore(cfg.ai().optimizedCache().directory(), "1.0", 4096,
            time::get, 240);
        Path model = model("source-" + ep + "-" + existing, 65);
        byte[] source = Files.readAllBytes(model);
        OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
        assertTrue(store.entryPath(model, ep, level).resolve("model.onnx").toString().length() > 240);
        String session = store.loadOrCreate(model, ep, level, plan -> {
          assertEquals(model, plan.input());
          assertFalse(plan.cached());
          assertNull(plan.optimizedOutput());
          assertEquals(level, plan.optimizationLevel());
          assertArrayEquals(source, Files.readAllBytes(plan.input()));
          return "source-session";
        });
        assertEquals("source-session", session);
        assertFalse(store.contains(model, ep, level));
        if (existing) {
          try (var files = Files.list(root)) {
            assertEquals(0, files.count());
          }
        } else {
          assertFalse(Files.exists(root));
        }
        try (var files = Files.list(model.getParent())) {
          assertEquals(List.of(model), files.toList());
        }
      }
    }
  }

  @Test
  void shortStagingPublishesACompleteLeaseBeforeOptimizationAndRemovesItAfterCommit() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    store.loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
      Path stage = plan.input().getParent();
      assertTrue(stage.getFileName().toString().matches("s-[0-9a-f]{16}"));
      var lease = new ObjectMapper().readTree(Files.readString(stage.resolve("lease.json")));
      assertEquals(ProcessHandle.current().pid(), lease.path("pid").asLong());
      assertTrue(lease.path("started").canConvertToLong());
      assertTrue(lease.path("started").asLong() >= 0);
      assertArrayEquals(Files.readAllBytes(model), Files.readAllBytes(plan.input()));
      Files.write(plan.optimizedOutput(), new byte[128]);
      return null;
    });
    Path entry = store.entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    assertTrue(entry.getFileName().toString().matches("[0-9a-f]{64}"));
    assertFalse(Files.exists(entry.resolve("lease.json")));
    assertTrue(store.contains(model, "cpu", OptLevel.EXTENDED_OPT));
    assertNoTemporaryFiles();
  }

  @Test
  void nativePathBudgetIncludesTheCommittedGraphAndAllowsTheExactBoundary() throws Exception {
    Path model = model("source", 65);
    Path root = temp.resolve("budget-cache");
    var identity = new OrtOptimizedModelStore(root, "1.0", 4096, time::get);
    Path entry = identity.entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    int stageLength = entry.getParent().resolve("s-" + "0".repeat(16))
        .resolve("model.tmp.onnx").toString().length();
    int graphLength = entry.resolve("model.onnx").toString().length();
    assertTrue(graphLength > stageLength);
    var rejected = new OrtOptimizedModelStore(root, "1.0", 4096, time::get, stageLength);
    rejected.loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
      assertEquals(model, plan.input());
      assertNull(plan.optimizedOutput());
      return null;
    });
    assertFalse(Files.exists(root));
    var accepted = new OrtOptimizedModelStore(root, "1.0", 4096, time::get, graphLength);
    accepted.loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
      assertNotNull(plan.optimizedOutput());
      assertTrue(plan.input().toString().length() <= graphLength);
      assertTrue(plan.optimizedOutput().toString().length() <= graphLength);
      Files.write(plan.optimizedOutput(), new byte[128]);
      return null;
    });
    accepted.loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
      assertTrue(plan.cached());
      assertEquals(graphLength, plan.input().toString().length());
      return null;
    });
  }

  @Test
  void abandonedLeasePreparationIsCleanedWhileALivePartialPreparationIsPreserved() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    Path provider = store.entryPath(model, "cpu", OptLevel.BASIC_OPT).getParent();
    ProcessHandle owner = ProcessHandle.current();
    long started = owner.info().startInstant().orElseThrow().toEpochMilli();
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    Path abandoned = Files.createDirectories(provider.resolve("p-" + owner.pid() + "-" + (started + 1) + "-" + token));
    Path live = Files.createDirectory(provider.resolve("p-" + owner.pid() + "-" + started + "-" + token));
    Files.writeString(abandoned.resolve("lease.json"), "{");
    Files.writeString(live.resolve("lease.json"), "{");
    try {
      createGraph(store, model, "cpu", OptLevel.BASIC_OPT);
      assertFalse(Files.exists(abandoned));
      assertEquals("{", Files.readString(live.resolve("lease.json")));
      assertTrue(store.contains(model, "cpu", OptLevel.BASIC_OPT));
    } finally {
      Files.deleteIfExists(live.resolve("lease.json"));
      Files.deleteIfExists(live);
    }
    assertNoTemporaryFiles();
  }

  @Test
  void legacyLongNamedStagesAndQuarantinesRemainSafelyReconciled() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    Path entry = store.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Files.createDirectories(entry.getParent());
    ProcessHandle owner = ProcessHandle.current();
    long started = owner.info().startInstant().orElseThrow().toEpochMilli();
    String prefix = entry.getFileName() + ".tmp-" + owner.pid() + "-";
    Path abandoned = Files.createDirectory(entry.resolveSibling(prefix + (started + 1) + "-" + UUID.randomUUID()));
    Path live = Files.createDirectory(entry.resolveSibling(prefix + started + "-" + UUID.randomUUID()));
    Path quarantine = Files.createDirectory(entry.resolveSibling(entry.getFileName() + ".quarantine-" + UUID.randomUUID()));
    Files.write(abandoned.resolve("model.tmp.onnx"), new byte[4096]);
    Files.writeString(live.resolve("model.tmp.onnx"), "live");
    Files.writeString(quarantine.resolve("model.onnx"), "quarantined");
    try {
      createGraph(store, model, "cpu", OptLevel.BASIC_OPT);
      assertFalse(Files.exists(abandoned));
      assertFalse(Files.exists(quarantine));
      assertEquals("live", Files.readString(live.resolve("model.tmp.onnx")));
      assertTrue(store.contains(model, "cpu", OptLevel.BASIC_OPT));
    } finally {
      Files.deleteIfExists(live.resolve("model.tmp.onnx"));
      Files.deleteIfExists(live);
    }
    assertNoTemporaryFiles();
    assertNoQuarantines();
  }

  @Test
  void sizeCapEvictsLeastRecentlyUsedAcrossProvidersAfterSuccessfulWrite() throws Exception {
    var store = store("1.0", 400);
    Path oldest = model("oldest", 65);
    Path recent = model("recent", 66);
    Path newest = model("newest", 67);
    var writes = new AtomicInteger();
    load(store, oldest, "cpu", OptLevel.BASIC_OPT, writes);
    time.set(20);
    load(store, recent, "cuda", OptLevel.EXTENDED_OPT, writes);
    time.set(30);
    load(store, oldest, "cpu", OptLevel.BASIC_OPT, writes); // oldest is now most recently used
    time.set(40);
    load(store, newest, "cpu", OptLevel.EXTENDED_OPT, writes);
    assertTrue(store.contains(oldest, "cpu", OptLevel.BASIC_OPT));
    assertFalse(store.contains(recent, "cuda", OptLevel.EXTENDED_OPT));
    assertTrue(store.contains(newest, "cpu", OptLevel.EXTENDED_OPT));
    assertEquals(2, committedEntries().size());
    long bytes = 0;
    for (Path entry : committedEntries()) {
      bytes += Files.size(entry) + Files.size(entry.resolveSibling("model.onnx"));
    }
    assertTrue(bytes <= 400);
    assertEquals(3, writes.get());
  }

  @Test
  void otherOrtVersionsAreRemovedOnlyAtFirstUse() throws Exception {
    Path old = temp.resolve("cache/0.9/cpu-EXTENDED_OPT/" + "a".repeat(64) + "/model.onnx");
    Files.createDirectories(old.getParent());
    Files.writeString(old, "old bytes");
    var store = store("1.0", 4096);
    Path model = model("source", 65);
    load(store, model, "cpu", OptLevel.EXTENDED_OPT, new AtomicInteger());
    assertFalse(Files.exists(temp.resolve("cache/0.9")));
    Files.createDirectories(old.getParent());
    Files.writeString(old, "created after first use");
    assertTrue(store("1.0", 4096).contains(model, "cpu", OptLevel.EXTENDED_OPT));
    assertTrue(Files.exists(old));
  }

  @Test
  void legacyCleanupDeletesExactlyFourNamesAndPreservesAllOtherSiblings() throws Exception {
    Path model = model("source", 65);
    for (String suffix : List.of(".optimized", ".opt-meta", ".cuda.optimized", ".cuda.opt-meta")) {
      Files.writeString(model.resolveSibling(model.getFileName() + suffix), "legacy");
    }
    for (String suffix : List.of(".optimized.keep", ".sha256", ".onnx_data", ".opt-meta.bak")) {
      Files.writeString(model.resolveSibling(model.getFileName() + suffix), "preserve");
    }
    OrtOptimizedModelStore.cleanupLegacy(model);
    try (var files = Files.list(model.getParent())) {
      assertEquals(5, files.count());
    }
    assertTrue(Files.isRegularFile(model));
    assertEquals("preserve", Files.readString(model.resolveSibling("model.onnx.sha256")));
  }

  @Test
  void legacyCleanupRefusesSymbolicLinksAndTheirTargets() throws Exception {
    Path model = model("source", 65);
    Path target = Files.writeString(temp.resolve("untouched"), "keep");
    Path link = model.resolveSibling("model.onnx.optimized");
    symbolicLink(link, target);
    Files.writeString(model.resolveSibling("model.onnx.opt-meta"), "ordinary legacy");
    assertThrows(IOException.class, () -> OrtOptimizedModelStore.cleanupLegacy(model));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("keep", Files.readString(target));
    assertFalse(Files.exists(model.resolveSibling("model.onnx.opt-meta")));
  }

  @Test
  void linkedAncestorRefusesLegacyDeletion() throws Exception {
    Path source = model("source", 65);
    Path legacy = Files.writeString(source.resolveSibling("model.onnx.optimized"), "keep");
    Path link = temp.resolve("linked-source");
    symbolicLink(link, source.getParent());
    assertThrows(IOException.class,
        () -> OrtOptimizedModelStore.cleanupLegacy(link.resolve("model.onnx")));
    assertThrows(IOException.class,
        () -> OrtOptimizedModelStore.cleanupLegacy(link.resolve("../source/model.onnx")));
    assertEquals("keep", Files.readString(legacy));
  }

  @Test
  void windowsJunctionAncestorRefusesLegacyDeletion() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    Path source = model("junction-target", 65);
    Path legacy = Files.writeString(source.resolveSibling("model.onnx.optimized"), "keep");
    Path junction = temp.resolve("linked-source");
    windowsJunction(junction, source.getParent());
    try {
      assertThrows(IOException.class,
          () -> OrtOptimizedModelStore.cleanupLegacy(junction.resolve("model.onnx")));
      assertTrue(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertEquals("keep", Files.readString(legacy));
    } finally {
      // Remove the junction itself so TempDir cleanup cannot traverse the reparse point.
      Files.deleteIfExists(junction);
    }
  }

  @Test
  void oldVersionCleanupRefusesLinkedTreesWithoutDeletingTheirTargets() throws Exception {
    assertOldVersionLinkPreserved(false);
  }

  @Test
  void oldVersionCleanupRefusesWindowsJunctionsWithOtherwiseDeletableLayouts() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    assertOldVersionLinkPreserved(true);
  }

  private void assertOldVersionLinkPreserved(boolean junction) throws Exception {
    Path outside = temp.resolve("outside/0.9");
    Path control = temp.resolve("cache/0.8");
    List<Path> targets = new ArrayList<>();
    String metadata = "{\"sourceSize\":5,\"created\":1,\"lastUsed\":1}";
    for (Path version : List.of(outside, control)) {
      for (String provider : List.of("cpu-BASIC_OPT", "cpu-EXTENDED_OPT", "cuda-EXTENDED_OPT")) {
        Path entry = Files.createDirectories(version.resolve(provider).resolve("a".repeat(64)));
        Path graph = Files.writeString(entry.resolve("model.onnx"), "keep");
        Files.writeString(entry.resolve("entry.json"), metadata);
        if (version.equals(outside)) targets.add(graph);
      }
    }
    Files.createDirectory(temp.resolve("cache/.ort-optimized-store-v1"));
    Path link = temp.resolve("cache/0.9");
    if (junction) windowsJunction(link, outside);
    else symbolicLink(link, outside);
    try {
      load(store("1.0", 4096), model("source", 66), "cpu", OptLevel.EXTENDED_OPT,
          new AtomicInteger());
      assertFalse(Files.exists(control, java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
      // Still a link: a symbolic link, or the junction symbolicLink() falls back to on
      // unprivileged Windows hosts.
      assertTrue(Files.isSymbolicLink(link)
          || !link.toRealPath().equals(link.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)));
      for (Path graph : targets) {
        assertEquals("keep", Files.readString(graph));
        assertEquals(metadata, Files.readString(graph.resolveSibling("entry.json")));
      }
    } finally {
      Files.deleteIfExists(link);
    }
  }

  @Test
  void uncommittedOrMalformedEntriesAreNeverLoaded() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    Path entry = store.entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    Files.createDirectories(entry);
    Files.writeString(entry.resolve("model.onnx"), "partial");
    assertFalse(store.contains(model, "cpu", OptLevel.EXTENDED_OPT));
    Files.writeString(entry.resolve("entry.json"), "invalid JSON");
    assertFalse(store.contains(model, "cpu", OptLevel.EXTENDED_OPT));
    load(store, model, "cpu", OptLevel.EXTENDED_OPT, new AtomicInteger());
    assertTrue(store.contains(model, "cpu", OptLevel.EXTENDED_OPT));
    assertNoTemporaryFiles();
  }

  @Test
  void linkedRootOptimizesInMemoryAndDoesNotWriteThroughLink() throws Exception {
    Path target = Files.createDirectories(temp.resolve("external-root"));
    Path keep = Files.writeString(target.resolve("keep"), "untouched");
    symbolicLink(temp.resolve("cache"), target);
    Path model = model("source", 65);
    store("1.0", 4096).loadOrCreate(model, "cpu", OptLevel.EXTENDED_OPT, plan -> {
      assertNull(plan.optimizedOutput());
      assertEquals(model, plan.input());
      return null;
    });
    assertEquals("untouched", Files.readString(keep));
    try (var files = Files.list(target)) {
      assertEquals(List.of(keep), files.toList());
    }
  }

  @Test
  void evictionPreflightsEntireEntryAndRefusesNestedLinks() throws Exception {
    var store = store("1.0", 200);
    Path first = model("first", 65);
    Path second = model("second", 66);
    load(store, first, "cpu", OptLevel.BASIC_OPT, new AtomicInteger());
    Path entry = store.entryPath(first, "cpu", OptLevel.BASIC_OPT);
    Path keep = Files.writeString(temp.resolve("untouched"), "keep");
    symbolicLink(entry.resolve("nested-link"), keep);
    time.set(20);
    load(store, second, "cpu", OptLevel.BASIC_OPT, new AtomicInteger());
    assertTrue(Files.isRegularFile(entry.resolve("model.onnx")));
    assertTrue(Files.isRegularFile(entry.resolve("entry.json")));
    assertEquals("keep", Files.readString(keep));
    assertFalse(store.contains(second, "cpu", OptLevel.BASIC_OPT));
  }

  @Test
  void optimizerFailureCleansPartialOutputWithoutCommitting() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    assertThrows(OrtException.class, () -> store.loadOrCreate(model, "cuda", OptLevel.EXTENDED_OPT,
        plan -> {
          Files.writeString(plan.optimizedOutput(), "partial");
          throw new OrtException("optimizer failed");
        }));
    assertTrue(committedEntries().isEmpty());
    assertNoTemporaryFiles();
  }

  @Test
  void externalInitializersAndOnnxDataSiblingsOptimizeInMemory() throws Exception {
    Path model = model("source", 65);
    // Model.graph -> Graph.initializer -> Tensor.data_location = EXTERNAL (custom filename).
    Files.write(model, new byte[] {58, 4, 42, 2, 112, 1});
    var store = store("1.0", 4096);
    store.loadOrCreate(model, "cpu", OptLevel.BASIC_OPT, plan -> {
      assertNull(plan.optimizedOutput());
      assertEquals(model, plan.input());
      assertEquals(OptLevel.BASIC_OPT, plan.optimizationLevel());
      return null;
    });
    model("source", 65);
    Files.writeString(model.resolveSibling("weights.onnx_data"), "external");
    store.loadOrCreate(model, "cuda", OptLevel.EXTENDED_OPT, plan -> {
      assertNull(plan.optimizedOutput());
      assertEquals(OptLevel.EXTENDED_OPT, plan.optimizationLevel());
      return null;
    });
    assertTrue(committedEntries().isEmpty());
  }

  @Test
  void metadataRecordsSourceSizeCreationAndLastUse() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    load(store, model, "cpu", OptLevel.BASIC_OPT, new AtomicInteger());
    time.set(50);
    load(store, model, "cpu", OptLevel.BASIC_OPT, new AtomicInteger());
    var metadata = new ObjectMapper().readTree(Files.readString(committedEntries().getFirst()));
    assertEquals(Files.size(model), metadata.path("sourceSize").asLong());
    assertEquals(10, metadata.path("created").asLong());
    assertEquals(50, metadata.path("lastUsed").asLong());
  }

  @Test
  void platformRootIsIndependentOfDataDirectoryAndOverrideUsesResolvedConfig() throws Exception {
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of("justsearch.data.dir", temp.toString()));
    var store = OrtOptimizedModelStore.fromConfig(cfg.ai().optimizedCache(), "1.0");
    Path entry = store.entryPath(model("source", 65), "cpu", OptLevel.BASIC_OPT);
    assertTrue(entry.startsWith(PlatformPaths.getPlatformDefault().resolve("cache/ort-optimized")));
    assertEquals(16_384, cfg.ai().optimizedCache().maxMb());
    var overridden = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", temp.resolve("override").toString(),
        "justsearch.ort.optimized_cache_max_mb", "0"));
    OrtOptimizedModelStore.fromConfig(overridden.ai().optimizedCache(), "1.0")
        .loadOrCreate(model("second", 66), "cpu", OptLevel.EXTENDED_OPT, plan -> {
          assertNull(plan.optimizedOutput());
          return null;
        });
    assertTrue(Files.isDirectory(temp.resolve("override")));
    assertFalse(Files.exists(temp.resolve("override/1.0")));
    assertThrows(IllegalArgumentException.class, () -> new ResolvedConfig.Ai.OptimizedCache(null, -1));
  }

  @Test
  void abandonedStagingIsReconciledBeforeAStoreInstancePublishes() throws Exception {
    Path model = model("source", 65);
    var oldStore = store("1.0", 256);
    Path entry = oldStore.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Path abandoned = staging(entry, false);
    Files.write(abandoned.resolve("model.tmp.onnx"), new byte[4096]);
    // No normally completing creator/finally block has ever owned this directory.
    var restarted = store("1.0", 256);
    createGraph(restarted, model, "cpu", OptLevel.BASIC_OPT);
    assertFalse(Files.exists(abandoned));
    assertTrue(restarted.contains(model, "cpu", OptLevel.BASIC_OPT));
    assertNoTemporaryFiles();
    try (var files = Files.walk(temp.resolve("cache"))) {
      long bytes = files.filter(Files::isRegularFile).mapToLong(p -> {
        try {
          return Files.size(p);
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }).sum();
      assertTrue(bytes <= 256);
    }
  }

  @Test
  void liveCreatorsStagingIsPreservedAndChargedAgainstTheCommittedBudget() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 250);
    Path entry = store.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Path live = staging(entry, true);
    Path output = Files.write(live.resolve("model.tmp.onnx"), new byte[128]);
    // Old age is not proof that a long optimization is abandoned.
    Files.setLastModifiedTime(live, FileTime.fromMillis(1));
    createGraph(store, model, "cpu", OptLevel.BASIC_OPT);
    assertTrue(Files.isRegularFile(output));
    assertEquals(128, Files.size(output));
    assertFalse(store.contains(model, "cpu", OptLevel.BASIC_OPT));
  }

  @Test
  void oldVersionPruningPreservesLiveStagingWhileRemovingOnlyFinishedEntries() throws Exception {
    Path model = model("source", 65);
    var old = store("0.9", 4096);
    Path oldEntry = old.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Path live = staging(oldEntry, true);
    Path output = Files.write(live.resolve("model.tmp.onnx"), new byte[128]);
    Path finished = Files.createDirectories(oldEntry.resolveSibling("b".repeat(64)));
    Files.writeString(finished.resolve("model.onnx"), "finished old-version bytes");
    createGraph(store("1.0", 4096), model, "cpu", OptLevel.BASIC_OPT);
    assertTrue(Files.isRegularFile(output));
    assertEquals(128, Files.size(output));
    assertFalse(Files.exists(finished));
    assertTrue(Files.isDirectory(temp.resolve("cache/0.9")));
  }

  @Test
  void sourceReplacementAndRestorationCannotPublishAnotherModelsGraphUnderTheSelectedKey()
      throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-source", 65);
      byte[] original = Files.readAllBytes(model);
      var store = store("1.0", 4096);
      OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
      Path entry = store.entryPath(model, ep, level);
      store.loadOrCreate(model, ep, level, plan -> {
        // Replace the source AFTER key selection, then restore A before creation returns.
        Files.write(model, new byte[] {58, 5, 18, 3, 66, 66, 66});
        byte[] optimizedFrom = Files.readAllBytes(plan.input());
        Files.write(plan.optimizedOutput(), optimizedFrom);
        Files.write(model, original);
        return null;
      });
      assertArrayEquals(original, Files.readAllBytes(entry.resolve("model.onnx")));
      var metadata = new ObjectMapper().readTree(Files.readString(entry.resolve("entry.json")));
      assertEquals(original.length, metadata.path("sourceSize").asLong());
      Path copy = Files.copy(model,
          Files.createDirectories(temp.resolve(ep + "-copy")).resolve("model.onnx"));
      store.loadOrCreate(copy, ep, level, plan -> {
        assertTrue(plan.cached());
        assertArrayEquals(original, Files.readAllBytes(plan.input()));
        return null;
      });
    }
    assertNoTemporaryFiles();
  }

  @Test
  void ambiguousOverrideRootsPreserveUnrelatedFilesAndDirectoriesIncludingWithZeroCap()
      throws Exception {
    for (long cap : new long[] {0, 4096}) {
      Path root = Files.createDirectories(temp.resolve("override-" + cap));
      Path document = Files.writeString(root.resolve("README.txt"), "user document");
      Path unrelated = Files.createDirectories(root.resolve("0.9/models"));
      Path userModel = Files.writeString(unrelated.resolve("model.onnx"), "user weights");
      Path ordinary = Files.createDirectories(root.resolve("user-files"));
      Path keep = Files.writeString(ordinary.resolve("keep.txt"), "keep");
      var store = new OrtOptimizedModelStore(root, "1.0", cap, time::get);
      Path source = model("source-" + cap, 65);
      store.loadOrCreate(source, "cpu", OptLevel.BASIC_OPT, plan -> {
        assertNull(plan.optimizedOutput());
        assertEquals(source, plan.input());
        return null;
      });
      assertEquals("user document", Files.readString(document));
      assertEquals("user weights", Files.readString(userModel));
      assertEquals("keep", Files.readString(keep));
      assertFalse(Files.exists(root.resolve(".ort-optimized-store-v1")));
      assertFalse(Files.exists(root.resolve("1.0")));
    }
  }

  @Test
  void markedRootPrunesOnlyRecognizedOldVersionsAndPreservesNewUnrelatedContents()
      throws Exception {
    Path model = model("source", 65);
    createGraph(store("1.0", 4096), model, "cpu", OptLevel.BASIC_OPT);
    Path root = temp.resolve("cache");
    Path unrelated = Files.createDirectories(root.resolve("notes"));
    Path keep = Files.writeString(unrelated.resolve("keep.txt"), "keep");
    Path file = Files.writeString(root.resolve("README.txt"), "keep root file");
    Path mixed = Files.createDirectories(root.resolve("0.9/cpu-BASIC_OPT/" + "a".repeat(64)));
    Files.writeString(mixed.resolve("model.onnx"), "old graph");
    Path unknown = Files.writeString(mixed.resolve("user.txt"), "user bytes inside version");
    createGraph(store("2.0", 4096), model, "cpu", OptLevel.BASIC_OPT);
    assertFalse(Files.exists(root.resolve("1.0")));
    assertEquals("keep", Files.readString(keep));
    assertEquals("keep root file", Files.readString(file));
    assertEquals("user bytes inside version", Files.readString(unknown));
    assertEquals("old graph", Files.readString(mixed.resolve("model.onnx")));
  }

  @Test
  void suspectGraphsAreRegeneratedForBothProvidersWithTheOriginalOptimizationLevel() throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-source", 65);
      var store = store("1.0", 4096);
      OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
      createGraph(store, model, ep, level);
      Path entry = store.entryPath(model, ep, level);
      Files.write(entry.resolve("model.onnx"), new byte[] {0});
      var attempts = new AtomicInteger();
      store.loadOrCreate(model, ep, level, plan -> {
        attempts.incrementAndGet();
        assertEquals(level, plan.optimizationLevel());
        if (plan.cached()) throw new OrtException("corrupt cached graph");
        assertNotNull(plan.optimizedOutput());
        assertArrayEquals(Files.readAllBytes(model), Files.readAllBytes(plan.input()));
        Files.write(plan.optimizedOutput(), new byte[128]);
        return null;
      });
      assertEquals(2, attempts.get());
      assertEquals(128, Files.size(entry.resolve("model.onnx")));
      store.loadOrCreate(model, ep, level, plan -> {
        assertTrue(plan.cached());
        return null;
      });
    }
    assertNoTemporaryFiles();
  }

  @Test
  void failedCachedLoadRetriesSourceOnceAndKeepsBothFailuresIfSourceAlsoFails() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    createGraph(store, model, "cuda", OptLevel.EXTENDED_OPT);
    var cachedFailure = new OrtException("cached load failed");
    var sourceFailure = new OrtException("source load failed");
    var attempts = new AtomicInteger();
    var failure = assertThrows(OrtException.class,
        () -> store.loadOrCreate(model, "cuda", OptLevel.EXTENDED_OPT, plan -> {
          attempts.incrementAndGet();
          if (plan.cached()) throw cachedFailure;
          throw sourceFailure;
        }));
    assertSame(sourceFailure, failure);
    assertArrayEquals(new Throwable[] {cachedFailure}, failure.getSuppressed());
    assertEquals(2, attempts.get());
    // The failed generation is now quarantined before the source retry, rather than retained
    // under the content key. Both failures still surface, and a later healthy source can repair.
    assertFalse(store.contains(model, "cuda", OptLevel.EXTENDED_OPT));
    assertNoTemporaryFiles();
    assertNoQuarantines();
    createGraph(store, model, "cuda", OptLevel.EXTENDED_OPT);
    assertTrue(store.contains(model, "cuda", OptLevel.EXTENDED_OPT));
  }

  @Test
  void concurrentRepairsDetachCorruptGenerationBeforeBothSourceCreations() throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-repair", 65);
      OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
      var initial = store("1.0", 4096);
      createGraph(initial, model, ep, level);
      Path entry = initial.entryPath(model, ep, level);
      Files.write(entry.resolve("model.onnx"), new byte[] {0});
      var readers = new CountDownLatch(2);
      var creators = new CountDownLatch(2);
      try (var pool = Executors.newFixedThreadPool(2)) {
        List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          futures.add(pool.submit(() -> {
            store("1.0", 4096).loadOrCreate(model, ep, level, plan -> {
              if (plan.cached()) {
                readers.countDown();
                await(readers);
                throw new OrtException("both readers rejected the corrupt generation");
              }
              // Neither creator has published yet. The failed generation must already be
              // detached; compare-then-delete publication would leave it here during creation.
              assertFalse(Files.exists(entry));
              assertEquals(level, plan.optimizationLevel());
              Files.write(plan.optimizedOutput(), new byte[128]);
              creators.countDown();
              await(creators);
              return null;
            });
            return null;
          }));
        }
        for (var future : futures) future.get(15, TimeUnit.SECONDS);
      }
      assertEquals(128, Files.size(entry.resolve("model.onnx")));
      assertTrue(initial.contains(model, ep, level));
    }
    assertNoTemporaryFiles();
    assertNoQuarantines();
  }

  @Test
  void delayedRepairAtomicallyQuarantinesHealthyReplacementEvenIfItTerminates() throws Exception {
    replacementRaceWithTermination(true);
  }

  @Test
  void delayedIncompleteEntryReplacementAtomicallyQuarantinesHealthyPublication() throws Exception {
    replacementRaceWithTermination(false);
  }

  private void replacementRaceWithTermination(boolean corruptCommitted) throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-replacement", 65);
      byte[] sourceBytes = Files.readAllBytes(model);
      OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
      var other = store("1.0", 4096);
      createGraph(other, model, ep, level);
      Path entry = other.entryPath(model, ep, level);
      if (corruptCommitted) Files.write(entry.resolve("model.onnx"), new byte[] {0});
      else Files.delete(entry.resolve("entry.json"));
      List<Path> quarantined = new ArrayList<>();
      var terminated = new AssertionError("creator terminates immediately after atomic rename");
      var delayed = new OrtOptimizedModelStore(temp.resolve("cache"), "1.0", 4096, time::get,
          (from, to) -> {
            // A has checked the failed/incomplete entry. While A pauses at the atomic-move
            // boundary, independent creator B quarantines it and publishes a healthy graph.
            repairGraph(other, model, ep, level);
            byte[] graph = Files.readAllBytes(from.resolve("model.onnx"));
            byte[] markerBytes = Files.readAllBytes(from.resolve("entry.json"));
            assertEquals(128, graph.length);
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            assertArrayEquals(graph, Files.readAllBytes(to.resolve("model.onnx")));
            assertArrayEquals(markerBytes, Files.readAllBytes(to.resolve("entry.json")));
            quarantined.add(to);
            // This is deliberately before createFresh/finally, modeling termination without
            // normal cleanup. No healthy directory is recursively deleted at the content key.
            throw terminated;
          });
      assertSame(terminated, assertThrows(AssertionError.class,
          () -> delayed.loadOrCreate(model, ep, level, plan -> {
            if (plan.cached()) throw new OrtException("A rejected the old graph");
            fail("A must terminate before source creation");
            return null;
          })));
      assertEquals(1, quarantined.size());
      assertFalse(Files.exists(entry));
      assertEquals(128, Files.size(quarantined.getFirst().resolve("model.onnx")));
      assertArrayEquals(sourceBytes, Files.readAllBytes(model));
      // Accepted residual: a late rename can quarantine B's healthy derived graph. Its bytes
      // remain complete after A terminates; the healthy source pays one extra optimization.
      createGraph(store("1.0", 4096), model, ep, level);
      assertTrue(other.contains(model, ep, level));
      assertFalse(Files.exists(quarantined.getFirst()));
    }
    assertNoTemporaryFiles();
    assertNoQuarantines();
  }

  @Test
  void incompleteEntryAppearingDuringCreationUsesAtomicQuarantineAtPublication() throws Exception {
    for (String ep : List.of("cpu", "cuda")) {
      Path model = model(ep + "-publish", 65);
      OptLevel level = ep.equals("cpu") ? OptLevel.BASIC_OPT : OptLevel.EXTENDED_OPT;
      var other = store("1.0", 4096);
      Path entry = other.entryPath(model, ep, level);
      var moves = new AtomicInteger();
      var delayed = new OrtOptimizedModelStore(temp.resolve("cache"), "1.0", 4096, time::get,
          (from, to) -> {
            // B completes the initially incomplete target after A's classification, before
            // A's move. The permitted extra optimization never entails recursive key deletion.
            repairGraph(other, model, ep, level);
            byte[] graph = Files.readAllBytes(from.resolve("model.onnx"));
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            assertArrayEquals(graph, Files.readAllBytes(to.resolve("model.onnx")));
            moves.incrementAndGet();
          });
      delayed.loadOrCreate(model, ep, level, plan -> {
        assertFalse(plan.cached());
        Files.write(plan.optimizedOutput(), new byte[128]);
        Files.createDirectory(entry);
        Files.writeString(entry.resolve("model.onnx"), "incomplete generation");
        return null;
      });
      assertEquals(1, moves.get());
      assertTrue(other.contains(model, ep, level));
      assertEquals(128, Files.size(entry.resolve("model.onnx")));
    }
    assertNoTemporaryFiles();
    assertNoQuarantines();
  }

  @Test
  void firstUseReconcilesAbandonedQuarantineBeforeCreationAndKeepsTheCap() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 256);
    Path entry = store.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Path quarantine = entry.resolveSibling("q-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    Files.createDirectories(quarantine);
    Files.write(quarantine.resolve("model.onnx"), new byte[4096]);
    Files.writeString(quarantine.resolve("entry.json"), "invalid marker");
    store.loadOrCreate(model, "cpu", OptLevel.BASIC_OPT, plan -> {
      assertFalse(Files.exists(quarantine));
      assertNotNull(plan.optimizedOutput());
      Files.write(plan.optimizedOutput(), new byte[128]);
      return null;
    });
    assertTrue(store.contains(model, "cpu", OptLevel.BASIC_OPT));
    assertTrue(Files.size(entry.resolve("model.onnx")) + Files.size(entry.resolve("entry.json")) <= 256);
    assertNoQuarantines();
    assertNoTemporaryFiles();
  }

  @Test
  void quarantineCleanupRefusesLinkedContentsAndPreservesTheirTargets() throws Exception {
    Path model = model("source", 65);
    var store = store("1.0", 4096);
    createGraph(store, model, "cpu", OptLevel.BASIC_OPT);
    Path entry = store.entryPath(model, "cpu", OptLevel.BASIC_OPT);
    Path quarantine = entry.resolveSibling("q-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    Files.move(entry, quarantine, StandardCopyOption.ATOMIC_MOVE);
    Path target = Files.writeString(temp.resolve("user-file"), "keep");
    symbolicLink(quarantine.resolve("nested-link"), target);
    createGraph(store, model, "cpu", OptLevel.BASIC_OPT);
    assertTrue(Files.isRegularFile(quarantine.resolve("model.onnx")));
    assertTrue(Files.isSymbolicLink(quarantine.resolve("nested-link")));
    assertEquals("keep", Files.readString(target));
    assertTrue(store.contains(model, "cpu", OptLevel.BASIC_OPT));
  }

  @Test
  void yamlCacheOverridesReachTheResolvedStoreAndCap() throws Exception {
    Path root = temp.resolve("yaml-cache");
    var yaml = new ObjectMapper().createObjectNode();
    yaml.putObject("ort").put("optimized_cache_dir", root.toString())
        .put("optimized_cache_max_mb", 0);
    var cfg = ResolvedConfig.builder().contributeYaml(yaml).build();
    assertEquals(root, cfg.ai().optimizedCache().directory());
    assertEquals(0, cfg.ai().optimizedCache().maxMb());
    OrtOptimizedModelStore.fromConfig(cfg.ai().optimizedCache(), "1.0")
        .loadOrCreate(model("source", 65), "cpu", OptLevel.BASIC_OPT, plan -> {
          assertNull(plan.optimizedOutput());
          return null;
        });
    assertTrue(Files.isDirectory(root));
  }

  private List<Path> committedEntries() throws IOException {
    if (!Files.exists(temp.resolve("cache"))) return List.of();
    try (var walk = Files.walk(temp.resolve("cache"))) {
      return walk.filter(p -> p.getFileName().toString().equals("entry.json")).toList();
    }
  }

  // Arrange recovery scenarios without asserting the independent source-snapshot contract.
  private static void createGraph(OrtOptimizedModelStore store, Path model, String ep, OptLevel level)
      throws IOException, OrtException {
    store.loadOrCreate(model, ep, level, plan -> {
      assertNotNull(plan.optimizedOutput());
      assertEquals(level, plan.optimizationLevel());
      Files.write(plan.optimizedOutput(), new byte[128]);
      return null;
    });
  }

  private static void repairGraph(OrtOptimizedModelStore store, Path model, String ep, OptLevel level)
      throws IOException {
    try {
      store.loadOrCreate(model, ep, level, plan -> {
        if (plan.cached()) throw new OrtException("rejected cached generation");
        assertNotNull(plan.optimizedOutput());
        Files.write(plan.optimizedOutput(), new byte[128]);
        return null;
      });
    } catch (OrtException e) {
      throw new IOException("Independent creator failed source regeneration", e);
    }
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Creator barrier timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private void assertNoQuarantines() throws IOException {
    try (var walk = Files.walk(temp.resolve("cache"))) {
      assertFalse(walk.anyMatch(p -> p.getFileName().toString().contains(".quarantine-")
          || p.getFileName().toString().matches("q-[0-9a-f]{16}")));
    }
  }

  private static Path staging(Path entry, boolean live) throws IOException {
    ProcessHandle owner = ProcessHandle.current();
    long started = owner.info().startInstant().orElseThrow().toEpochMilli();
    if (!live) started++;
    Files.createDirectories(entry.getParent());
    Path stage = Files.createDirectory(entry.resolveSibling(
        "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
    Files.writeString(stage.resolve("lease.json"), new ObjectMapper().createObjectNode()
        .put("pid", owner.pid()).put("started", started).toString());
    return stage;
  }

  private void assertNoTemporaryFiles() throws IOException {
    try (var walk = Files.walk(temp.resolve("cache"))) {
      assertFalse(walk.anyMatch(p -> p.getFileName().toString().contains(".tmp")
          || p.getFileName().toString().matches("s-[0-9a-f]{16}")
          || p.getFileName().toString().matches("p-[1-9][0-9]*-[0-9]+-[0-9a-f]{16}")));
    }
  }

  private static void windowsJunction(Path link, Path target) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
        link.toString(), target.toString()).redirectErrorStream(true).start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }

  private static void symbolicLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      // Unprivileged Windows cannot create symbolic links, but a directory junction needs no
      // privilege and is the link the store most often meets there: exercise the refusal with
      // one instead of skipping every link test on ordinary Windows hosts.
      if (System.getProperty("os.name").startsWith("Windows") && Files.isDirectory(target)) {
        try {
          windowsJunction(link, target);
          return;
        } catch (IOException junctionFailure) {
          e.addSuppressed(junctionFailure);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          e.addSuppressed(interrupted);
        }
      }
      Assumptions.abort("Symbolic links unavailable on this host: " + e.getMessage());
    }
  }
}
