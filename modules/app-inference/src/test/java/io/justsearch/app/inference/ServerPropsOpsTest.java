package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerPropsOpsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AtomicReference<String> lastModelId;
  private AtomicReference<Integer> lastContextTokens;
  private AtomicReference<Boolean> externalActive;
  /** The {@code -c} the ops under test should believe was launched (tempdoc 883). */
  private AtomicReference<Integer> requestedContextTokens;
  private ServerPropsOps ops;

  @BeforeEach
  void setUp() {
    lastModelId = new AtomicReference<>(null);
    lastContextTokens = new AtomicReference<>(null);
    externalActive = new AtomicReference<>(false);
    requestedContextTokens = new AtomicReference<>(4096);
    ops =
        new ServerPropsOps(observer(), () -> requestedContextTokens.get());
  }

  /** A config whose contextSize (4096) deliberately DISAGREES with the launched rung under test. */
  private static InferenceConfig configuredAt4096() {
    return new InferenceConfig(
        Path.of("bin", "llama-server.exe"),
        Path.of("models", "configured.gguf"),
        null,
        8080,
        4096,
        0,
        false);
  }

  private PropsObserver observer() {
    return new PropsObserver() {
      @Override
      public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {
        lastModelId.set(modelId);
      }

      @Override
      public void onContextTokensObserved(int contextTokens) {
        lastContextTokens.set(contextTokens);
      }

      @Override
      public String observedModelId() {
        return lastModelId.get();
      }

      @Override
      public Integer observedContextTokens() {
        return lastContextTokens.get();
      }
    };
  }

  private void update(ServerPropsOps target, JsonNode root) {
    ResolvedConfig resolved = mock(ResolvedConfig.class);
    ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
    when(resolved.ai()).thenReturn(ai);
    when(ai.gpuAccelerationAllowed()).thenReturn(true);
    LlamaServerOps.StartDisposition disposition =
        externalActive.get()
            ? LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL
            : LlamaServerOps.StartDisposition.LAUNCHED_MANAGED;
    target.updateFromPropsBestEffort(
        root,
        new LlamaServerOps.StartResult(
            new LlamaServerConfigContext(configuredAt4096(), resolved),
            LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL,
            disposition,
            disposition == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL ? null : "declared"));
  }

  // ==================== resetExternalAdoptionState ====================

  @Test
  void resetExternalAdoptionState_setsVerifiedAndTimestamp() {
    long before = System.currentTimeMillis();
    ops.resetExternalAdoptionState(true, null);
    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);

    assertTrue(diag.verified());
    assertNull(diag.verificationError());
    assertTrue(diag.adoptedAtMs() >= before);
  }

  @Test
  void resetExternalAdoptionState_setsErrorWhenNotVerified() {
    ops.resetExternalAdoptionState(false, "props_missing_expected_fields");
    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);

    assertFalse(diag.verified());
    assertEquals("props_missing_expected_fields", diag.verificationError());
  }

  // ==================== buildExternalDiagnostics ====================

  @Test
  void buildExternalDiagnostics_returnsSnapshotAfterReset() {
    ops.resetExternalAdoptionState(true, null);
    var diag = ops.buildExternalDiagnostics(true, 12345L, "timeout", 3);

    assertTrue(diag.usingExternalLlamaServer());
    assertTrue(diag.verified());
    assertNull(diag.modelId());
    assertNull(diag.contextTokens());
    assertFalse(diag.modelMismatch());
    assertFalse(diag.contextTooSmall());
    assertTrue(diag.adoptedAtMs() > 0);
    assertEquals(12345L, diag.lastHealthOkAtMs());
    assertEquals("timeout", diag.lastHealthError());
    assertEquals(3, diag.consecutiveHealthFailures());
  }

  // ==================== context window mismatch (tempdoc 883) ====================

  @Test
  void contextMismatch_isFalseWhenTheServerHonouredTheLaunchedRung() {
    assertFalse(ServerPropsOps.isContextWindowMismatch(16384, 16384));
    assertFalse(
        ServerPropsOps.isContextWindowMismatch(16384, 32768),
        "a server with MORE context than we asked for is not a mismatch");
  }

  @Test
  void contextMismatch_isTrueWhenTheServerGaveLessThanWasLaunched() {
    assertTrue(ServerPropsOps.isContextWindowMismatch(32768, 8192));
  }

  @Test
  void contextMismatch_isFalseWhenThisProcessLaunchedNothing() {
    // Adopted external server: we chose no window, so we have no claim to contradict.
    assertFalse(ServerPropsOps.isContextWindowMismatch(0, 512));
  }

  @Test
  void contextMismatch_readbackConsultsTheLaunchedRungNotTheConfiguredValue() throws Exception {
    // The comparand is the point, not the comparison. This setUp's InferenceConfig says 4096; a
    // launch that stepped down to 2048 and a server reporting 2048 agree with each other, and the
    // readback must ask the launch - not the (now stale) config - to know that. A warning that is
    // wrong on every successful step-down teaches operators to ignore the one time it is right.
    requestedContextTokens.set(2048);
    java.util.concurrent.atomic.AtomicInteger consulted =
        new java.util.concurrent.atomic.AtomicInteger();
    ServerPropsOps stepped =
        new ServerPropsOps(
            observer(),
            () -> {
              consulted.incrementAndGet();
              return requestedContextTokens.get();
            });

    update(stepped, MAPPER.readTree("{\"n_ctx\":2048}"));

    assertTrue(consulted.get() > 0, "the launched rung is the comparand");
    assertEquals(2048, lastContextTokens.get());
  }

  // ============ adopted-server context floor (tempdoc 883 review item 3) ============

  @Test
  void adoptedContextTooSmall_isFalseForAWorkableByoServer() {
    // An adopted BYO llama-server at 8192 is perfectly workable. Before tempdoc 883 this was
    // compared against OUR configured contextSize, which is now a derived 32768 rung on a GPU
    // box — so every adopted server below our own preference would have been flagged too small.
    assertFalse(ServerPropsOps.isAdoptedContextTooSmall(8192));
    assertFalse(ServerPropsOps.isAdoptedContextTooSmall(4096));
    assertFalse(ServerPropsOps.isAdoptedContextTooSmall(32768));
  }

  @Test
  void adoptedContextTooSmall_isTrueBelowTheLaddersBottomRung() {
    // Below the smallest window this app will run its OWN engine at, "too small" is honest.
    assertTrue(ServerPropsOps.isAdoptedContextTooSmall(2048));
    assertTrue(ServerPropsOps.isAdoptedContextTooSmall(512));
  }

  @Test
  void adoptedContextTooSmall_isFalseWhenTheWindowIsUnknown() {
    assertFalse(ServerPropsOps.isAdoptedContextTooSmall(null));
  }

  @Test
  void adoptedContextFloor_isTheLaddersBottomRung() {
    // Pins the floor to the policy rather than to a literal that can drift away from it.
    assertEquals(4096, ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS);
    assertFalse(
        ServerPropsOps.isAdoptedContextTooSmall(ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS));
    assertTrue(
        ServerPropsOps.isAdoptedContextTooSmall(ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS - 1));
  }

  // ==================== updateFromPropsBestEffort ====================

  @Test
  void updateFromPropsBestEffort_extractsModelIdFromAlias() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_alias\":\"my-model\",\"n_ctx\":4096}");
    update(ops, root);

    assertEquals("my-model", lastModelId.get());
  }

  @Test
  void updateFromPropsBestEffort_extractsModelIdFromPathFilename() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_path\":\"/tmp/some-model.gguf\",\"n_ctx\":4096}");
    update(ops, root);

    assertEquals("some-model.gguf", lastModelId.get());
  }

  @Test
  void updateFromPropsBestEffort_extractsContextTokens() throws Exception {
    JsonNode root = MAPPER.readTree("{\"n_ctx\":2048}");
    update(ops, root);

    assertEquals(2048, lastContextTokens.get());
  }

  @Test
  void updateFromPropsBestEffort_populatesExternalDiagnosticsForMismatch() throws Exception {
    externalActive.set(true);
    JsonNode root =
        MAPPER.readTree(
            "{\"model_alias\":\"external\","
                + "\"model_path\":\"/models/external.gguf\","
                + "\"n_ctx\":2048}");
    update(ops, root);

    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);
    assertTrue(diag.verified());
    assertTrue(diag.modelMismatch());
    assertTrue(diag.contextTooSmall());
    assertEquals("external", diag.modelId());
    assertEquals(2048, diag.contextTokens());
  }

  // ==================== looksLikeLlamaServerProps ====================

  @Test
  void looksLikeLlamaServerProps_trueForValidProps() throws Exception {
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_alias\":\"test\"}")));
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_path\":\"/tmp/model.gguf\"}")));
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(MAPPER.readTree("{\"n_ctx\":4096}")));
  }

  // ==================== Vision Capability ====================

  @Test
  void updateFromPropsBestEffort_extractsVisionCapability() throws Exception {
    JsonNode root =
        MAPPER.readTree("{\"model_alias\":\"test\",\"modalities\":{\"vision\":true},\"n_ctx\":4096}");
    update(ops, root);

    assertTrue(ops.hasVisionCapability());
  }

  @Test
  void visionCapability_falseWhenModalitiesMissing() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_alias\":\"test\",\"n_ctx\":4096}");
    update(ops, root);

    assertFalse(ops.hasVisionCapability());
  }

  @Test
  void visionCapability_resetOnExternalAdoptionReset() throws Exception {
    JsonNode root =
        MAPPER.readTree("{\"model_alias\":\"test\",\"modalities\":{\"vision\":true},\"n_ctx\":4096}");
    update(ops, root);
    assertTrue(ops.hasVisionCapability());

    ops.resetExternalAdoptionState(true, null);
    assertFalse(ops.hasVisionCapability());
  }

  @Test
  void chatTemplateCaps_readFromB8571PropsVerbatim() throws Exception {
    // Tempdoc 835 Q4 — captured verbatim from the bundled b8571. The load-bearing observation is
    // the ABSENCE of supports_enable_thinking: per-request thinking support is not advertised, so
    // this signal can only say "reasoning-aware build" and launch-argument acceptance stays the
    // authoritative verdict.
    JsonNode root =
        MAPPER.readTree(
            "{\"model_alias\":\"test\",\"n_ctx\":4096,\"build_info\":\"b8571-e397d3885\","
                + "\"chat_template_caps\":{\"supports_object_arguments\":true,"
                + "\"supports_parallel_tool_calls\":true,\"supports_preserve_reasoning\":true,"
                + "\"supports_string_content\":true,\"supports_system_role\":true,"
                + "\"supports_tool_calls\":true,\"supports_tools\":true,"
                + "\"supports_typed_content\":false}}");

    assertTrue(ServerPropsOps.hasChatTemplateCaps(root));
    assertTrue(ServerPropsOps.supportsPreserveReasoning(root));
    assertTrue(root.path("chat_template_caps").path("supports_enable_thinking").isMissingNode());

    update(ops, root);
    assertEquals("b8571", ops.actualServerBuild());
  }

  @Test
  void chatTemplateCaps_absentOnOlderBuildsIsNotAFailure() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_alias\":\"test\",\"n_ctx\":4096}");

    assertFalse(ServerPropsOps.hasChatTemplateCaps(root));
    assertFalse(ServerPropsOps.supportsPreserveReasoning(root));
    assertFalse(ServerPropsOps.hasChatTemplateCaps(null));
    assertFalse(ServerPropsOps.supportsPreserveReasoning(null));

    update(ops, root);
  }

  @Test
  void looksLikeLlamaServerProps_falseForEmptyOrMissing() throws Exception {
    assertFalse(ServerPropsOps.looksLikeLlamaServerProps(null));
    assertFalse(
        ServerPropsOps.looksLikeLlamaServerProps(MAPPER.readTree("{}")));
    assertFalse(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_alias\":\"\"}")));
  }
}
