/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        assertEquals(model, plan.input());
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
    Path old = temp.resolve("cache/old/cpu-EXTENDED_OPT/hash/model.onnx");
    Files.createDirectories(old.getParent());
    Files.writeString(old, "old bytes");
    var store = store("1.0", 4096);
    Path model = model("source", 65);
    load(store, model, "cpu", OptLevel.EXTENDED_OPT, new AtomicInteger());
    assertFalse(Files.exists(temp.resolve("cache/old")));
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
  void windowsJunctionAncestorRefusesLegacyAndVersionDeletion() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    Path source = model("junction-target", 65);
    Path legacy = Files.writeString(source.resolveSibling("model.onnx.optimized"), "keep");
    Files.createDirectories(temp.resolve("cache"));
    Path junction = temp.resolve("cache/old");
    Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
        junction.toString(), source.getParent().toString()).redirectErrorStream(true).start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    try {
      assertThrows(IOException.class,
          () -> OrtOptimizedModelStore.cleanupLegacy(junction.resolve("model.onnx")));
      load(store("1.0", 4096), model("source", 66), "cpu", OptLevel.EXTENDED_OPT,
          new AtomicInteger());
      assertTrue(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertEquals("keep", Files.readString(legacy));
    } finally {
      // Remove the junction itself so TempDir cleanup cannot traverse the reparse point.
      Files.deleteIfExists(junction);
    }
  }

  @Test
  void oldVersionCleanupRefusesLinkedTreesWithoutDeletingTheirTargets() throws Exception {
    Path outside = model("outside", 65).getParent();
    Files.createDirectories(temp.resolve("cache"));
    symbolicLink(temp.resolve("cache/old"), outside);
    Path model = model("source", 66);
    load(store("1.0", 4096), model, "cpu", OptLevel.EXTENDED_OPT, new AtomicInteger());
    assertTrue(Files.isRegularFile(outside.resolve("model.onnx")));
    assertTrue(Files.isSymbolicLink(temp.resolve("cache/old")));
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
    var metadata = new ObjectMapper().readTree(committedEntries().getFirst().toFile());
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

  private void assertNoTemporaryFiles() throws IOException {
    try (var walk = Files.walk(temp.resolve("cache"))) {
      assertFalse(walk.anyMatch(p -> p.getFileName().toString().contains(".tmp")));
    }
  }

  private static void symbolicLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assumptions.abort("Symbolic links unavailable on this host: " + e.getMessage());
    }
  }
}
