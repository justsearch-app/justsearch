/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real CPU ORT round-trip plus option-routing checks for the owned store. */
final class OnnxSessionCacheTest {
  @TempDir Path temp;
  private ConfigStore previousStore;

  @BeforeEach
  void configureStore() {
    previousStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", temp.resolve("cache").toString(),
        "justsearch.ort.optimized_cache_max_mb", "16"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void realOrtLoadsCopiesAndHardLinksFromOneCommittedGraphWithoutSiblingWrites() throws Exception {
    Path original = Files.createDirectories(temp.resolve("original")).resolve("model.onnx");
    try (var resource = getClass().getClassLoader().getResourceAsStream(
        "capability-fixtures/unstamped.onnx.b64")) {
      assertNotNull(resource);
      Files.write(original, Base64.getDecoder().decode(
          new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip()));
    }
    Path copy = Files.copy(original,
        Files.createDirectories(temp.resolve("copy")).resolve("model.onnx"));
    Path link = Files.createLink(
        Files.createDirectories(temp.resolve("hardlink")).resolve("model.onnx"), original);
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession first = OnnxSessionCache.createCachedSession(env, original)) {
      assertFalse(first.getInputNames().isEmpty());
    }
    var store = OrtOptimizedModelStore.configured();
    Path entry = store.entryPath(original, "cpu", OptLevel.EXTENDED_OPT);
    Path optimized = entry.resolve("model.onnx");
    assertTrue(Files.isRegularFile(entry.resolve("entry.json")));
    FileTime sentinel = FileTime.fromMillis(1_000_000);
    Files.setLastModifiedTime(optimized, sentinel);
    try (OrtSession second = OnnxSessionCache.createCachedSession(env, copy);
        OrtSession third = OnnxSessionCache.createCachedSession(env, link)) {
      assertEquals(second.getInputNames(), third.getInputNames());
    }
    assertEquals(sentinel, Files.getLastModifiedTime(optimized));
    try (var entries = Files.walk(temp.resolve("cache"))) {
      assertEquals(1, entries.filter(p -> p.getFileName().toString().equals("entry.json")).count());
    }
    for (Path model : new Path[] {original, copy, link}) {
      try (var files = Files.list(model.getParent())) {
        assertEquals(List.of(model), files.toList());
      }
    }
  }

  @Test
  void realOrtRejectsCorruptCommittedGraphAndRepairsItFromHealthySource() throws Exception {
    Path model = temp.resolve("model.onnx");
    try (var resource = getClass().getClassLoader().getResourceAsStream(
        "capability-fixtures/unstamped.onnx.b64")) {
      assertNotNull(resource);
      Files.write(model, Base64.getDecoder().decode(
          new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip()));
    }
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    Path entry = OrtOptimizedModelStore.configured().entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    Path graph = entry.resolve("model.onnx");
    Path marker = entry.resolve("entry.json");
    try (OrtSession first = OnnxSessionCache.createCachedSession(env, model)) {
      assertFalse(first.getInputNames().isEmpty());
    }
    byte[] originalMarker = Files.readAllBytes(marker);
    Files.write(graph, new byte[] {0}); // nonempty invalid protobuf; leave the valid marker intact
    assertArrayEquals(originalMarker, Files.readAllBytes(marker));
    assertTrue(OrtOptimizedModelStore.configured().contains(model, "cpu", OptLevel.EXTENDED_OPT));
    try (SessionOptions raw = new SessionOptions()) {
      assertThrows(OrtException.class, () -> env.createSession(graph.toString(), raw));
    }
    try (OrtSession recovered = OnnxSessionCache.createCachedSession(env, model)) {
      assertFalse(recovered.getInputNames().isEmpty());
    }
    assertTrue(Files.size(graph) > 1);
    // The repaired bytes themselves must be loadable, not just the returned source session.
    try (SessionOptions raw = new SessionOptions();
        OrtSession repaired = env.createSession(graph.toString(), raw);
        OrtSession cached = OnnxSessionCache.createCachedSession(env, model)) {
      assertEquals(repaired.getInputNames(), cached.getInputNames());
    }
  }

  @Test
  void helperInstalledSnapshotsKeepConfiguredEntriesInTheGradleTestRoot() throws Exception {
    String directory = System.getenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR");
    assertNotNull(directory, "Every Gradle Test task must supply the isolated ORT store root");
    assertFalse(directory.isBlank());
    Path root = Path.of(directory).toAbsolutePath().normalize();
    Path model = Files.write(temp.resolve("helper-model.onnx"), new byte[] {58, 3, 18, 1, 65});
    for (var snapshot : List.of(
        TestResolvedConfigHelper.withDefaults(),
        TestResolvedConfigHelper.fromEntries(Map.of("justsearch.ort.optimized_cache_max_mb", "0")),
        TestResolvedConfigHelper.fromEntries(Map.of("justsearch.ort.optimized_cache_dir", " ")))) {
      ConfigStore.setGlobal(new ConfigStore(snapshot));
      assertConfiguredEntryRoot(model, root);
    }
    TestResolvedConfigHelper.storeWithDefaults();
    assertConfiguredEntryRoot(model, root);
    TestResolvedConfigHelper.storeFromEnvironment();
    assertConfiguredEntryRoot(model, root);

    // Tests that deliberately supply a private root still override the fixture default.
    Path explicit = temp.resolve("explicit-cache");
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", explicit.toString()))));
    assertConfiguredEntryRoot(model, explicit);
    assertFalse(Files.exists(explicit)); // Resolving an entry does not initialize the store.
  }

  private static void assertConfiguredEntryRoot(Path model, Path root) throws Exception {
    Path entry = OrtOptimizedModelStore.configured().entryPath(model, "cpu", OptLevel.EXTENDED_OPT);
    assertTrue(entry.startsWith(root));
    assertEquals(root.resolve(OnnxSessionCache.ortVersion()).resolve("cpu-EXTENDED_OPT"), entry.getParent());
  }

  @Test
  @Tag("windows")
  void windowsOverlongCacheRootCreatesARealSessionWithoutStoreWrites() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    Path model = temp.resolve("model.onnx");
    try (var resource = getClass().getClassLoader().getResourceAsStream(
        "capability-fixtures/unstamped.onnx.b64")) {
      assertNotNull(resource);
      Files.write(model, Base64.getDecoder().decode(
          new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip()));
    }
    Path root = temp.resolve("unused-deep-cache");
    while (root.toAbsolutePath().toString().length() < 200) root = root.resolve("x".repeat(32));
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", root.toString(),
        "justsearch.ort.optimized_cache_max_mb", "16"))));
    var store = OrtOptimizedModelStore.configured();
    assertTrue(store.entryPath(model, "cpu", OptLevel.EXTENDED_OPT)
        .resolve("model.onnx").toString().length() > 240);
    try (OrtSession session = OnnxSessionCache.createCachedSession(OrtEnvironment.getEnvironment(), model)) {
      assertFalse(session.getInputNames().isEmpty());
    }
    assertFalse(store.contains(model, "cpu", OptLevel.EXTENDED_OPT));
    assertFalse(Files.exists(root));
    try (var files = Files.list(temp)) {
      assertEquals(List.of(model), files.toList());
    }
  }

  @Test
  void externalCpuAndCudaModelsRetainOptimizationLevelsAndDisableSerialization() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"), new byte[] {58, 4, 42, 2, 112, 1});
    OrtEnvironment env = mock(OrtEnvironment.class);
    SessionOptions cpu = mock(SessionOptions.class);
    SessionOptions cuda = mock(SessionOptions.class);
    OrtSession result = mock(OrtSession.class);
    when(env.createSession(eq(model.toString()), any(SessionOptions.class))).thenReturn(result);
    assertSame(result, OnnxSessionCache.createCachedSession(env, model, cpu, OptLevel.BASIC_OPT));
    assertSame(result, OnnxSessionCache.createCachedGpuSession(env, model, cuda));
    verify(cpu).setOptimizationLevel(OptLevel.BASIC_OPT);
    verify(cuda).setOptimizationLevel(OptLevel.EXTENDED_OPT);
    verify(cpu).setOptimizedModelFilePath("");
    verify(cuda).setOptimizedModelFilePath("");
  }

  @Test
  void cachedGraphDisablesOptimizationAndClearsReusedOutputPathThroughApplier() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"), new byte[] {58, 0});
    OrtEnvironment env = mock(OrtEnvironment.class);
    SessionOptions options = mock(SessionOptions.class);
    OrtSession result = mock(OrtSession.class);
    doAnswer(call -> {
      String path = call.getArgument(0);
      if (!path.isEmpty()) Files.write(Path.of(path), new byte[128]);
      return null;
    }).when(options).setOptimizedModelFilePath(anyString());
    when(env.createSession(anyString(), eq(options))).thenReturn(result);
    assertSame(result, OnnxSessionCache.createCachedSession(env, model, options, OptLevel.BASIC_OPT));
    assertSame(result, OnnxSessionCache.createCachedSession(env, model, options, OptLevel.BASIC_OPT));
    verify(options).setOptimizationLevel(OptLevel.BASIC_OPT);
    verify(options).setOptimizationLevel(OptLevel.NO_OPT);
    verify(options).setOptimizedModelFilePath("");
    Path cached = OrtOptimizedModelStore.configured().entryPath(model, "cpu", OptLevel.BASIC_OPT)
        .resolve("model.onnx");
    verify(env).createSession(cached.toString(), options);
  }

  @Test
  void failedNativeCreationLeavesNoCommitMarkerOrPartialOutput() throws Exception {
    Path model = Files.write(temp.resolve("model.onnx"), new byte[] {58, 0});
    OrtEnvironment env = mock(OrtEnvironment.class);
    SessionOptions options = mock(SessionOptions.class);
    doAnswer(call -> {
      String path = call.getArgument(0);
      if (!path.isEmpty()) Files.writeString(Path.of(path), "partial native output");
      return null;
    }).when(options).setOptimizedModelFilePath(anyString());
    when(env.createSession(anyString(), eq(options))).thenThrow(new OrtException("failed"));
    assertThrows(OrtException.class, () -> OnnxSessionCache.createCachedGpuSession(env, model, options));
    try (var paths = Files.walk(temp.resolve("cache"))) {
      assertFalse(paths.anyMatch(p -> p.getFileName().toString().contains(".tmp")
          || p.getFileName().toString().matches("s-[0-9a-f]{16}")
          || p.getFileName().toString().matches("p-[1-9][0-9]*-[0-9]+-[0-9a-f]{16}")
          || p.getFileName().toString().equals("entry.json")));
    }
  }
}
