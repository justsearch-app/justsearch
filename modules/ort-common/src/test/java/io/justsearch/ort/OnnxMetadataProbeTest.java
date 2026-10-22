/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;

/** M1a/M2a: compare the public probe contract with the actual runtime, including graph resolution. */
final class OnnxMetadataProbeTest {
  @TempDir Path temp;
  private ConfigStore previousStore;

  @BeforeEach
  void ownOptimizedCache() {
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
  void supportedGraphUsesNoProbeSessions() throws Exception {
    Path model = fixture("ordered.onnx", graph(false, false));
    OrtEnvironment env = mock(OrtEnvironment.class);
    when(env.createSession(anyString(), any(OrtSession.SessionOptions.class)))
        .thenAnswer(call -> fail("metadata-only path created a probe session"));
    var names = assertDoesNotThrow(() -> OrtSessionAssembler.probeModelNames(env, model));
    assertEquals(List.of("X"), new ArrayList<>(names.inputs()));
    assertEquals(List.of("second", "first"), new ArrayList<>(names.outputs()));
    assertEquals(Map.of("level", "model"),
        assertDoesNotThrow(() -> OrtSessionAssembler.probeCustomMetadata(env, model)));
    verifyNoInteractions(env);
  }

  @Test
  void unparseableModelFallsBackToExactlyOneSession() throws Exception {
    Path model = Files.write(temp.resolve("unparseable.onnx"), new byte[] {58, 127});
    OrtEnvironment env = mock(OrtEnvironment.class);
    OrtSession probe = mock(OrtSession.class);
    when(probe.getInputNames()).thenReturn(Set.of("old-input"));
    when(probe.getOutputNames()).thenReturn(Set.of("old-output"));
    when(env.createSession(eq(model.toString()), any(OrtSession.SessionOptions.class))).thenReturn(probe);
    assertEquals(Set.of("old-input"), OrtSessionAssembler.probeModelNames(env, model).inputs());
    verify(env, times(1)).createSession(eq(model.toString()), any(OrtSession.SessionOptions.class));
    verify(probe).close();
  }

  @Test
  void edgeFixturesMatchActualRuntime() throws Exception {
    compare(fixture("ordered.onnx", graph(false, false)));
    compare(fixture("overlap.onnx", graph(true, false)));
    compare(fixture("sparse.onnx", graph(false, true)));
    compare(fixture("symbolic.onnx", concat(node("Identity", "X", "Y"),
        value(11, "X", tensorType(dim("batch"), dim(3))),
        value(12, "Y", tensorType(dim("batch"), dim(3))))));
    compare(fixture("missing.onnx", concat(node("Identity", "X", "Y"),
        value(11, "X", tensorType(dim(2), dim(3))),
        value(12, "Y", bytes(1, number(1, 1))))));
    compare(fixture("missing-dimension.onnx", concat(node("Identity", "X", "Y"),
        value(11, "X", tensorType(dim(2))),
        value(12, "Y", tensorType(bytes(1, new byte[0]))))));
    compare(fixture("resolved-symbolic.onnx", concat(node("Identity", "X", "Y"),
        value(11, "X", tensorType(dim(2))), value(12, "Y", tensorType(dim("resolved"))))));
    compare(fixture("scalar.onnx", concat(node("Identity", "X", "Y"),
        value(11, "X", tensorType()), value(12, "Y", tensorType()))));
    compare(fixture("sequence.onnx", concat(node("SequenceConstruct", "X", "Y"),
        value(11, "X", tensorType(dim(2))),
        value(12, "Y", bytes(4, bytes(1, tensorType(dim(2))))))));
    byte[] branch = concat(text(2, "branch-graph"), bytes(5, tensor("branch", 1)), value(12, "branch", tensorType(dim(1))));
    byte[] conditional = bytes(1, concat(text(1, "cond"), text(2, "Y"), text(4, "If"),
        bytes(5, concat(text(1, "then_branch"), bytes(6, branch), number(20, 5))),
        bytes(5, concat(text(1, "else_branch"), bytes(6, branch), number(20, 5)))));
    compare(fixture("nested.onnx", concat(conditional,
        value(11, "cond", bytes(1, concat(number(1, 9), bytes(2, new byte[0])))),
        value(12, "Y", tensorType(dim(1))))));
    Files.write(temp.resolve("weights.bin"), new byte[4]);
    byte[] external = concat(number(1, 1), number(2, 1), text(8, "weight"),
        bytes(13, concat(text(1, "location"), text(2, "weights.bin"))), number(14, 1));
    compare(fixture("external.onnx", concat(bytes(5, external),
        node("Identity", "weight", "Y"), value(11, "weight", tensorType(dim(1))),
        value(12, "Y", tensorType(dim(1))))));
  }

  @TestFactory
  List<DynamicTest> everyLocallyAvailableShippedModelMatchesRuntime() throws Exception {
    return localModels().stream().map(model -> DynamicTest.dynamicTest(model.toString(), () -> {
      compare(model);
      System.out.println("ORT metadata comparison: " + model);
    })).toList();
  }

  @Test
  void everyLocallyAvailableShippedModelUsesZeroNameOrMetadataSessions() throws Exception {
    for (Path model : localModels()) {
      OrtEnvironment noSessions = mock(OrtEnvironment.class);
      when(noSessions.createSession(anyString(), any(OrtSession.SessionOptions.class)))
          .thenAnswer(call -> fail("metadata-only path created a probe session: " + model));
      assertDoesNotThrow(() -> OrtSessionAssembler.probeModelNames(noSessions, model), model.toString());
      assertDoesNotThrow(() -> OrtSessionAssembler.probeCustomMetadata(noSessions, model), model.toString());
      verifyNoInteractions(noSessions);
      System.out.println("Zero name/metadata sessions: " + model);
    }
  }

  @Test
  void modelWithUnsupportedOperatorFailsVisiblyAtFirstUse() throws Exception {
    Path model = fixture("unsupported.onnx", concat(node("NotAnOnnxOperator", "X", "Y"),
        value(11, "X", tensorType(dim(1))), value(12, "Y", tensorType(dim(1)))));
    var variant = io.justsearch.configuration.model.VariantSelection.optimal(model,
        io.justsearch.configuration.model.ModelPrecision.FP32,
        io.justsearch.configuration.model.ExecutionProvider.CUDA);
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.NER,
        TestResolvedConfigHelper.withDefaults(),
        io.justsearch.configuration.model.HardwareProfile.gpuFull(12_000_000_000L), variant);
    try (var handle = assertDoesNotThrow(() -> OrtSessionAssembler.buildManager("unloadable-ner",
        new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> false))) {
      assertThrows(SessionTemporarilyUnavailableException.class, () -> handle.acquire(
          SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.BACKGROUND, java.time.Duration.ofSeconds(5))));
    }
  }

  private List<Path> localModels() throws Exception {
    String configured = System.getenv("JUSTSEARCH_MODELS_DIR");
    Path models = configured == null ? Path.of("../../models") : Path.of(configured);
    List<Path> found = List.of();
    if (Files.isDirectory(models)) {
      try (var files = Files.walk(models)) {
        found = files.filter(p -> p.getFileName().toString().matches("model(?:_fp16|_quantized|_int8)?\\.onnx")).sorted().toList();
      }
    }
    org.junit.jupiter.api.Assumptions.assumeFalse(found.isEmpty(), "No shipped model binaries locally available");
    return found;
  }

  private void compare(Path model) throws Exception {
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (var options = new OrtSession.SessionOptions()) {
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
      options.setInterOpNumThreads(1);
      options.setIntraOpNumThreads(1);
      try (var session = env.createSession(model.toString(), options)) {
        var actual = OrtSessionAssembler.probeModelNames(env, model);
        assertEquals(new ArrayList<>(session.getInputNames()), new ArrayList<>(actual.inputs()), model.toString());
        assertEquals(new ArrayList<>(session.getOutputNames()), new ArrayList<>(actual.outputs()), model.toString());
        assertEquals(session.getMetadata().getCustomMetadata(), OrtSessionAssembler.probeCustomMetadata(env, model), model.toString());
        for (var entry : session.getOutputInfo().entrySet()) {
          var tensor = OrtSessionAssembler.probeOutputTensorInfo(env, model, entry.getKey());
          if (entry.getValue().getInfo() instanceof TensorInfo expected) {
            assertTrue(tensor.isPresent(), model + ": " + entry.getKey());
            assertArrayEquals(expected.getShape(), tensor.orElseThrow().shape(), model + ": " + entry.getKey());
            assertEquals(expected.type, tensor.orElseThrow().type(), model.toString());
          } else assertTrue(tensor.isEmpty(), model.toString());
        }
      }
    }
  }

  private Path fixture(String name, byte[] graph) throws Exception {
    return Files.write(temp.resolve(name), concat(number(1, 10), bytes(8, number(2, 17)),
        bytes(7, concat(text(2, name), graph, bytes(16, concat(text(1, "level"), text(2, "graph"))))),
        bytes(14, concat(text(1, "level"), text(2, "model")))));
  }

  private static byte[] graph(boolean overlap, boolean sparse) {
    byte[] initializer = overlap ? bytes(5, tensor("weight", 1)) : new byte[0];
    if (sparse) initializer = bytes(15, concat(bytes(1, tensor("weight", 1)),
        bytes(2, concat(number(1, 1), number(2, 7), bytes(9, new byte[8]))), number(3, 1)));
    return concat(initializer, node("Identity", "X", "second"), node("Identity", "X", "first"),
        value(11, "X", tensorType(dim(1))),
        overlap || sparse ? value(11, "weight", tensorType(dim(1))) : new byte[0],
        value(12, "second", tensorType(dim(1))), value(12, "first", tensorType(dim(1))));
  }

  private static byte[] tensor(String name, int size) {
    return concat(number(1, size), number(2, 1), text(8, name), bytes(9, new byte[size * 4]));
  }
  private static byte[] node(String op, String input, String output) {
    return bytes(1, concat(text(1, input), text(2, output), text(4, op)));
  }
  private static byte[] value(int field, String name, byte[] type) {
    return bytes(field, concat(text(1, name), bytes(2, type)));
  }
  private static byte[] tensorType(byte[]... dimensions) {
    return bytes(1, concat(number(1, 1), bytes(2, concat(dimensions))));
  }
  private static byte[] dim(long value) { return bytes(1, number(1, value)); }
  private static byte[] dim(String value) { return bytes(1, text(2, value)); }
  private static byte[] text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
  private static byte[] bytes(int field, byte[] value) { return concat(varint((field << 3) | 2), varint(value.length), value); }
  private static byte[] number(int field, long value) { return concat(varint(field << 3), varint(value)); }
  private static byte[] varint(long value) {
    var out = new ByteArrayOutputStream();
    while ((value & ~127L) != 0) { out.write((int) (value & 127) | 128); value >>>= 7; }
    out.write((int) value);
    return out.toByteArray();
  }
  private static byte[] concat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.writeBytes(part);
    return out.toByteArray();
  }
}
