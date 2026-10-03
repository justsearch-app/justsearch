package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link PolicySnapshot} JSON round-trip + structural expectations.
 *
 * <p>Stage 1 scope: verify Jackson can serialise + deserialise without loss. A golden-fixture
 * byte-for-byte contract test (368 RC1 pattern) is deferred until a frontend consumer exists —
 * {@code /api/debug/session-policies} is diagnostic-only for Stage 1.
 */
@DisplayName("PolicySnapshot Jackson serialization")
class PolicySnapshotSerializationTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private static final ResolvedConfig CFG = TestResolvedConfigHelper.withDefaults();
  private static final HardwareProfile HW = HardwareProfile.gpuFull(12_000_000_000L);

  @Test
  @DisplayName("serialise → deserialise → equals (round-trip lossless)")
  void roundTrip() throws Exception {
    PolicySnapshot snapshot = sampleSnapshot();
    String json = MAPPER.writeValueAsString(snapshot);
    PolicySnapshot parsed = MAPPER.readValue(json, PolicySnapshot.class);
    assertEquals(snapshot, parsed);
  }

  @Test
  @DisplayName("JSON structure has top-level 'runtime' and 'models' keys")
  void topLevelStructure() throws Exception {
    PolicySnapshot snapshot = sampleSnapshot();
    String json = MAPPER.writeValueAsString(snapshot);
    assertTrue(json.contains("\"runtime\""), "missing 'runtime' key");
    assertTrue(json.contains("\"models\""), "missing 'models' key");
  }

  @Test
  @DisplayName("models map contains all 6 encoder roles")
  void modelsMapIsComplete() throws Exception {
    PolicySnapshot snapshot = sampleSnapshot();
    String json = MAPPER.writeValueAsString(snapshot);
    for (EncoderRole role : EncoderRole.values()) {
      assertTrue(
          json.contains("\"" + role.name() + "\""),
          "models map missing role: " + role);
    }
  }

  @Test
  @DisplayName("models map ordering is deterministic (enum.name() alphabetical via TreeMap)")
  void modelsOrderingIsDeterministic() throws Exception {
    PolicySnapshot snapshot = sampleSnapshot();
    String json = MAPPER.writeValueAsString(snapshot);

    // Assert BGE_M3 appears before CITATION (alphabetical), etc.
    int[] positions = Arrays.stream(EncoderRole.values())
        .mapToInt(r -> json.indexOf("\"" + r.name() + "\""))
        .toArray();
    for (int i = 1; i < positions.length; i++) {
      assertTrue(
          positions[i] > positions[i - 1],
          "Role " + EncoderRole.values()[i] + " appears before "
              + EncoderRole.values()[i - 1] + " in JSON — ordering broken");
    }
  }

  @Test
  @DisplayName("JSON matches committed golden fixture byte-for-byte (368 RC1 contract pattern)")
  void matchesGoldenFixture() throws Exception {
    PolicySnapshot snapshot = fixtureSnapshot();
    String actual =
        JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()
            .writeValueAsString(snapshot)
            .replace("\r\n", "\n");

    // Jackson serialises java.nio.file.Path as a platform-specific file:// URI
    // (Linux: "file:///path/x", Windows: "file:///F:/path/x"). Normalise it out so the fixture
    // pins the policy shape + all values, without being coupled to the developer's working
    // directory. Fields preserved: the variant's precision, EP, degraded flag — only the
    // modelFile string is masked.
    String normalisedActual = normaliseModelFile(actual);

    String expected;
    try (InputStream in =
        PolicySnapshotSerializationTest.class
            .getClassLoader()
            .getResourceAsStream("policy-snapshot.json")) {
      if (in == null) {
        throw new AssertionError(
            "Missing fixture: policy-snapshot.json. Regenerate by copying the 'actual' value "
                + "from the assertion output into "
                + "modules/ort-common/src/test/resources/policy-snapshot.json.");
      }
      expected =
          new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing();
    }

    assertEquals(
        expected,
        normalisedActual.stripTrailing(),
        "Golden fixture drifted. If the change is intentional, regenerate the fixture by "
            + "copying the 'actual' (post-normalisation) value from this assertion into "
            + "modules/ort-common/src/test/resources/policy-snapshot.json.");
  }

  /** Replace the platform-specific Path-URI with a fixed placeholder so the fixture is stable. */
  private static String normaliseModelFile(String json) {
    return json.replaceAll(
        "\"modelFile\"\\s*:\\s*\"[^\"]*\"", "\"modelFile\" : \"<fixture-placeholder>\"");
  }

  /**
   * A fixed canonical snapshot with a filename-only path that serialises identically on Linux
   * and Windows (no path separators → {@code Path.toString()} is stable). The fixture is
   * write-only — we never round-trip it — so Jackson's absolute-resolve behaviour on
   * deserialisation doesn't affect the byte comparison.
   */
  private static PolicySnapshot fixtureSnapshot() {
    // S3 (merge bf0c9ba5a): FP16 CPU fallback uses BASIC_OPT even for a CUDA variant.
    Path modelFile = Path.of("model_fp16.onnx");
    VariantSelection variant =
        VariantSelection.optimal(modelFile, ModelPrecision.FP16, ExecutionProvider.CUDA);
    var models = new TreeMap<EncoderRole, ModelSessionPolicy>();
    for (EncoderRole role : EncoderRole.values()) {
      models.put(role, ModelSessionPolicyResolver.resolve(role, CFG, HW, variant));
    }
    return new PolicySnapshot(RuntimePolicyResolver.resolve(CFG, HW), models);
  }

  private static PolicySnapshot sampleSnapshot() {
    // Use an absolute path so the round-trip survives Jackson's Path deserializer
    // (which resolves relatives against the working directory).
    VariantSelection variant =
        VariantSelection.optimal(
            Path.of("/models/embedding/model_fp16.onnx").toAbsolutePath(),
            ModelPrecision.FP16,
            ExecutionProvider.CUDA);
    var models = new TreeMap<EncoderRole, ModelSessionPolicy>();
    for (EncoderRole role : EncoderRole.values()) {
      models.put(role, ModelSessionPolicyResolver.resolve(role, CFG, HW, variant));
    }
    return new PolicySnapshot(RuntimePolicyResolver.resolve(CFG, HW), models);
  }
}
