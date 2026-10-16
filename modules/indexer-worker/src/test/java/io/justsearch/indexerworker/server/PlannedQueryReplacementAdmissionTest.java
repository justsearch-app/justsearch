/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.DeviceMemoryLine;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.RerankerAssembly;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P7: the prepared plan must remain admissible beside the chat reservation it was built for. */
final class PlannedQueryReplacementAdmissionTest {
  private static final long MIB = 1024L * 1024L;
  private static final long RERANKER_WEIGHTS = 600L * MIB;
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
      EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3);
  @TempDir Path directory;

  @AfterEach
  void restorePlanning() {
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @ParameterizedTest(name = "chatModelSwitchIsAdmittedWithTheOldChatLoaded({0})")
  @ValueSource(longs = {12_282, 16_376, 24_564})
  void chatModelSwitchIsAdmittedWithTheOldChatLoaded(long deviceMiB) throws Exception {
    var fixture = fixture(deviceMiB);
    ResolvedConfig source = prepare(fixture, ChatModelProfile.STANDARD);
    ResolvedConfig candidate = prepare(fixture, ChatModelProfile.COMPACT);
    assertTrue(ConfigApplyScopes.classify(source, candidate).component().containsKey("encoders"),
        "the chat switch really re-prepares encoders because its arena contributions change");
    assertEquals(ChatModelProfile.COMPACT,
        ConfigStoreRebuilder.planOf(candidate).orElseThrow().plan().orElseThrow().chat()
            .model().orElseThrow());
    assertAdmitted(fixture, source, candidate);
  }

  @ParameterizedTest(name = "samePlanRepreparationIsAdmittedWithChatLoaded({0})")
  @ValueSource(longs = {16_376, 24_564})
  void samePlanRepreparationIsAdmittedWithChatLoaded(long deviceMiB) throws Exception {
    var fixture = fixture(deviceMiB);
    ResolvedConfig source = prepare(fixture, ChatModelProfile.STANDARD);
    ResolvedConfig candidate = prepare(fixture, ChatModelProfile.STANDARD);
    assertEquals(source.ai().reranker().gpuMemMb(), candidate.ai().reranker().gpuMemMb());
    assertAdmitted(fixture, source, candidate);
  }

  @Test
  void aRealRetainedWeightShortfallStillRefusesReplacement() throws Exception {
    var fixture = fixture(12_282);
    ResolvedConfig source = prepare(fixture, ChatModelProfile.STANDARD);
    ResolvedConfig candidate = prepare(fixture, ChatModelProfile.COMPACT);
    long required = estimate(candidate, fixture);
    long sourceCredit = InferenceCompositionRoot.sourceQueryReleasableBytes(
        sourceOwner(source, fixture));
    assertEquals(RERANKER_WEIGHTS, sourceCredit);
    assertTrue(required > sourceCredit, "candidate headroom still has a real cost");
    var decision = new DeviceMemoryLine(fixture.deviceMiB() * MIB, 0L)
        .decision(required, sourceCredit);
    assertEquals(ComposeEvidence.Mode.REFUSED, decision.mode());
    assertEquals("candidate_exceeds_releasable_device_memory", decision.reason());
  }

  @Test
  void externalTensorWeightsKeepTheConservativeArenaEstimate() throws Exception {
    var fixture = fixture(16_376);
    Files.writeString(fixture.model().resolveSibling("model_fp16.onnx_data"), "external weights");
    var candidate = prepare(fixture, ChatModelProfile.STANDARD);
    long cap = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, candidate,
        fixture.hardware(), VariantSelection.optimal(fixture.model(), ModelPrecision.FP16,
            ExecutionProvider.CUDA)).gpu().arenaCapBytes();
    assertTrue(cap > RERANKER_WEIGHTS);
    assertEquals(cap + (cap + 9) / 10, estimate(candidate, fixture),
        "an external tensor graph cannot prove retained weights from graph-file size alone");
  }

  private void assertAdmitted(Fixture fixture, ResolvedConfig source, ResolvedConfig candidate) {
    var sourcePlan = ConfigStoreRebuilder.planOf(source).orElseThrow().plan().orElseThrow();
    long free = (long) ((fixture.deviceMiB() - 1_024 - sourcePlan.chat().footprintMiB()
        - 1_930 - 400) * MIB);
    assertTrue(free > 0, "precondition: the old chat and planned encoder weights are resident");
    long required = estimate(candidate, fixture);
    long sourceCredit = InferenceCompositionRoot.sourceQueryReleasableBytes(
        sourceOwner(source, fixture));
    assertEquals(RERANKER_WEIGHTS, sourceCredit,
        "replacement credit comes from the realized GPU weights, not its unused arena");
    var decision = new DeviceMemoryLine(fixture.deviceMiB() * MIB, free)
        .decision(required, sourceCredit);
    assertNotEquals(ComposeEvidence.Mode.REFUSED, decision.mode(),
        "the plan's activation allowance must not be required again for a private replacement: "
            + "candidateMiB=" + required / MIB + ", freeMiB=" + free / MIB
            + ", retainedMiB=" + sourceCredit / MIB);
  }

  private static long estimate(ResolvedConfig candidate, Fixture fixture) {
    return InferenceCompositionRoot.estimateQueryFootprintBytes(
        EncoderConfigurationProjection.from(candidate), fixture.selection(), fixture.hardware());
  }

  private static QueryRoleSet sourceOwner(ResolvedConfig config, Fixture fixture) {
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, config,
        fixture.hardware(), VariantSelection.optimal(fixture.model(), ModelPrecision.FP16,
            ExecutionProvider.CUDA));
    assertTrue(policy.gpu().arenaCapBytes() > RERANKER_WEIGHTS,
        "precondition: the source's arena includes a positive activation allowance");
    SessionHandle handle = mock(SessionHandle.class);
    when(handle.isGpuAvailable()).thenReturn(true);
    when(handle.retainedGpuWeightsBytes()).thenReturn(OptionalLong.of(RERANKER_WEIGHTS));
    return new QueryRoleSet(new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.of(new RerankerAssembly(handle, null, null)), Optional.empty(), Optional.empty(),
        Optional.empty(), new PolicySnapshot(null, Map.of(EncoderRole.RERANKER, policy)),
        List.of(handle)));
  }

  private Fixture fixture(long deviceMiB) throws Exception {
    Path models = Files.createDirectories(directory.resolve("models"));
    for (var profile : List.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT)) {
      Path chat = models.resolve(profile.modelFile());
      Files.createDirectories(chat.getParent());
      Files.writeString(chat, "shipped profile fixture");
      Path projector = models.resolve(profile.mmprojFile());
      Files.createDirectories(projector.getParent());
      Files.writeString(projector, "shipped projector fixture");
    }
    Path reranker = Files.createDirectories(models.resolve("reranker"));
    Path model = reranker.resolve("model_fp16.onnx").toAbsolutePath().normalize();
    try (var file = new RandomAccessFile(model.toFile(), "rw")) {
      file.setLength(RERANKER_WEIGHTS);
      // Self-contained protobuf metadata with a skipped payload; no native session is opened.
      file.writeByte(10);
      for (long length = RERANKER_WEIGHTS - 6; length > 0; length >>>= 7) {
        file.writeByte((int) (length & 127) | (length > 127 ? 128 : 0));
      }
    }
    Path tokenizer = Files.writeString(reranker.resolve("tokenizer.json"), "{}");
    var selection = new QueryRoleSelection(QueryRoleSelection.Role.selected("model_fp16.onnx",
        GenerationModelSelection.captureIdentity(model),
        GenerationModelSelection.captureIdentity(tokenizer), ModelPrecision.FP16,
        ExecutionProvider.CUDA), QueryRoleSelection.Role.disabled());
    return new Fixture(deviceMiB, models, model, selection,
        HardwareProfile.gpuFull(deviceMiB * MIB));
  }

  private static ResolvedConfig prepare(Fixture fixture, ChatModelProfile chat) {
    var device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(),
        fixture.deviceMiB() * MIB);
    var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080, List.of(device),
        Optional.of(device));
    ConfigStoreRebuilder.rememberAutoDetected(Map.of(
        EnvRegistry.GPU_ENABLED.configKey(), "true", EnvRegistry.GPU_LAYERS.configKey(), "99",
        EnvRegistry.MODELS_DIR.configKey(), fixture.models().toString(),
        EnvRegistry.RERANK_ENABLED.configKey(), "true",
        EnvRegistry.RERANK_GPU_ENABLED.configKey(), "true"));
    ConfigStoreRebuilder.rememberDevicePlanner(config -> GpuProfileResolver.resolve(config,
        facts, WEIGHTS, Set.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT)));
    var settings = new UiSettings();
    settings.setRerankerModelPath(fixture.model().getParent().toString());
    String priorProfile = System.getProperty(EnvRegistry.CHAT_PROFILE.sysProp());
    System.setProperty(EnvRegistry.CHAT_PROFILE.sysProp(), chat.id());
    try {
      return ConfigStoreRebuilder.prepare(settings);
    } finally {
      if (priorProfile == null) System.clearProperty(EnvRegistry.CHAT_PROFILE.sysProp());
      else System.setProperty(EnvRegistry.CHAT_PROFILE.sysProp(), priorProfile);
    }
  }

  private record Fixture(long deviceMiB, Path models, Path model, QueryRoleSelection selection,
      HardwareProfile hardware) {}
}
