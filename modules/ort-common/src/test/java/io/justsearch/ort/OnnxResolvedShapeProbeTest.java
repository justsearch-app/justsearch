/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** M2/M2a: a static declaration is not an authority for ORT's resolved output shape. */
final class OnnxResolvedShapeProbeTest {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"identity", "relu", "transpose"})
  void conflictingDeclarationsMatchRuntimeMetadataAndInference(String operation) throws Exception {
    Path model = model(operation);
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (var options = new OrtSession.SessionOptions()) {
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
      options.setIntraOpNumThreads(1);
      options.setInterOpNumThreads(1);
      try (var session = env.createSession(model.toString(), options);
          var input = OnnxTensor.createTensor(env, new float[][] {{1, 2, 3}, {4, 5, 6}});
          var result = session.run(Map.of("input", input))) {
        var runtime = (TensorInfo) session.getOutputInfo().get("output").getInfo();
        var probed = OrtSessionAssembler.probeOutputTensorInfo(env, model, "output").orElseThrow();
        assertArrayEquals(operation.equals("transpose") ? new long[] {3, 2} : new long[] {2, 3},
            ((OnnxTensor) result.get(0)).getInfo().getShape());
        assertArrayEquals(runtime.getShape(), probed.shape(), "must return resolved metadata, not the declaration");
        assertEquals(runtime.type, probed.type());
      }
    }
  }

  @Test
  void conflictingDimensionKeepsWarningAndStrictRejection() throws Exception {
    Path model = model("identity");
    var manifest = new ModelManifest(model.getFileName().toString(), null, null, null, null);
    var requirements = new CapabilityRequirements(Set.of(CapabilityRequirements.Fact.DIMENSION));
    var capabilities = ModelCapabilityResolver.resolve("conflicting-shape", temp, manifest,
        requirements, false, model);
    assertEquals(0, capabilities.embeddingDimension());
    assertTrue(capabilities.warnings().stream().anyMatch(w -> w.contains("embedding dimension undeclared")));
    assertThrows(IllegalStateException.class, () -> ModelCapabilityResolver.resolve(
        "conflicting-shape", temp, manifest, requirements, true, model));
  }

  @TestFactory
  List<DynamicTest> shippedCapabilitiesStillNeedNoProbeSessions() throws Exception {
    String configured = System.getenv("JUSTSEARCH_MODELS_DIR");
    Path root = configured == null ? Path.of("../../models") : Path.of(configured);
    List<Path> models = List.of();
    if (Files.isDirectory(root)) {
      try (var files = Files.walk(root)) {
        models = files.filter(p -> p.getFileName().toString().matches("model(?:_fp16|_quantized|_int8)?\\.onnx"))
            .sorted().toList();
      }
    }
    org.junit.jupiter.api.Assumptions.assumeFalse(models.isEmpty(), "No shipped model binaries locally available");
    return models.stream().map(model -> DynamicTest.dynamicTest(model.toString(), () -> {
      Path directory = model.getParent();
      String name = directory.getFileName().toString();
      var requirements = name.startsWith("gte-") ? CapabilityRequirements.EMBEDDING
          : name.equals("ner") ? CapabilityRequirements.NER : CapabilityRequirements.SPLADE;
      OrtEnvironment noSessions = mock(OrtEnvironment.class);
      when(noSessions.createSession(anyString(), any(OrtSession.SessionOptions.class)))
          .thenAnswer(call -> fail("shipped capability resolution created a probe session: " + model));
      try (var environment = mockStatic(OrtEnvironment.class)) {
        environment.when(OrtEnvironment::getEnvironment).thenReturn(noSessions);
        var capabilities = ModelCapabilityResolver.resolve(name, directory,
            ModelManifest.loadOrDefault(directory), requirements, true, model);
        if (requirements.requires(CapabilityRequirements.Fact.DIMENSION)) {
          assertTrue(capabilities.embeddingDimension() > 0);
        }
        verifyNoInteractions(noSessions);
      }
    })).toList();
  }

  private Path model(String operation) throws Exception {
    // Identity/Relu infer [2,3] but declare [2,4]; Transpose infers [3,2] but declares [2,3].
    String encoded = switch (operation) {
      case "identity" -> "CAk6aRIIdmVyaWZpZXIKGQoFaW5wdXQSBm91dHB1dCIISWRlbnRpdHlaFwoFaW5wdXQSDgoMCAESCAoCCAIKAggDYhgKBm91dHB1dBIOCgwIARIICgIIAgoCCASCAQ4KBXNjb3BlEgVncmFwaEICEBFyDgoFc2NvcGUSBW1vZGVs";
      case "relu" -> "CAk6ZRIIdmVyaWZpZXIKFQoFaW5wdXQSBm91dHB1dCIEUmVsdVoXCgVpbnB1dBIOCgwIARIICgIIAgoCCANiGAoGb3V0cHV0Eg4KDAgBEggKAggCCgIIBIIBDgoFc2NvcGUSBWdyYXBoQgIQEXIOCgVzY29wZRIFbW9kZWw=";
      case "transpose" -> "CAk6ahIIdmVyaWZpZXIKGgoFaW5wdXQSBm91dHB1dCIJVHJhbnNwb3NlWhcKBWlucHV0Eg4KDAgBEggKAggCCgIIA2IYCgZvdXRwdXQSDgoMCAESCAoCCAIKAggDggEOCgVzY29wZRIFZ3JhcGhCAhARcg4KBXNjb3BlEgVtb2RlbA==";
      default -> throw new IllegalArgumentException(operation);
    };
    return Files.write(temp.resolve(operation + ".onnx"), Base64.getDecoder().decode(encoded));
  }
}
