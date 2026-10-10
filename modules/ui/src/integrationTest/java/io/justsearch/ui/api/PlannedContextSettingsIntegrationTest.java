/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.DevicePlanState;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ort.EncoderRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P4 through HTTP, durable settings, config planning and the real query-only ownership guard. */
final class PlannedContextSettingsIntegrationTest extends LocalApiIntegrationTestBase {
  private ConfigStore prior;
  private ConfigStore config;
  private io.justsearch.indexerworker.server.PlannedSettingsQueryOwner owner;
  private io.justsearch.app.inference.InferenceLifecycleManager inference;
  private io.justsearch.core.component.EngineComponentRegistry registry;
  private io.justsearch.core.component.ComponentHandle indexComponent;

  @Override protected void beforeServerStart() throws Exception {
    prior = ConfigStore.globalOrNull();
    setPropForTest("policy.gpu_acceleration_enabled", "true");
    setPropForTest("justsearch.llm.enabled", "true");
    setPropForTest("justsearch.gpu.layers", "99");
    setPropForTest("justsearch.ai.embed.enabled", "false");
    setPropForTest("justsearch.splade.enabled", "false");
    setPropForTest("justsearch.ner.enabled", "false");
    setPropForTest("justsearch.rerank.enabled", "false");
    setPropForTest("justsearch.citation.scorer.enabled", "false");
    var device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(),
        12_282L * 1024 * 1024);
    var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), Optional.of(device));
    ConfigStoreRebuilder.rememberDevicePlanner(cfg -> GpuProfileResolver.resolve(cfg, facts,
        Map.of(EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
            EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3), Set.of(ChatModelProfile.STANDARD)));
    config = new ConfigStore(ConfigStoreRebuilder.prepare(new io.justsearch.app.api.UiSettings()));
    ConfigStore.setGlobal(config);
    DevicePlanState.process().publish(ConfigStoreRebuilder.planOf(config.get()).orElseThrow());
    config.addListener(event -> DevicePlanState.process().publish(
        ConfigStoreRebuilder.planOf(event.current()).orElseThrow()));
  }

  @Override protected io.justsearch.app.inference.InferenceLifecycleManager settingsInferenceManager()
      throws Exception {
    inference = org.mockito.Mockito.mock(io.justsearch.app.inference.InferenceLifecycleManager.class);
    org.mockito.Mockito.when(inference.prepareResolvedConfig(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call -> {
          var prepared = org.mockito.Mockito.mock(
              io.justsearch.app.inference.InferenceLifecycleManager.PreparedConfigApply.class);
          org.mockito.Mockito.doAnswer(lock -> {
            ((Runnable) lock.getArgument(0)).run();
            return null;
          }).when(prepared).withLifecycleLock(org.mockito.ArgumentMatchers.any());
          return prepared;
        });
    return inference;
  }

  @Override protected void configureSettingsComponents(
      io.justsearch.app.services.settings.FixedSettingsComponentComposer composer,
      io.justsearch.core.component.EngineComponentRegistry registry, ConfigStore store,
      UiSettingsStore settings) throws Exception {
    this.registry = registry;
    indexComponent = registry.register(new io.justsearch.core.component.ComponentSpec("index", false, Set.of(),
        io.justsearch.core.component.ComponentSpec.ComposeCapability.BESIDE,
        java.time.Duration.ZERO, 0));
    owner = new io.justsearch.indexerworker.server.PlannedSettingsQueryOwner(
        composer, registry, store, settings);
  }

  @Override protected LocalApiServer.Builder configureServer(LocalApiServer.Builder builder) {
    return builder.componentRegistry(registry).indexComponent(indexComponent);
  }

  @AfterEach void restorePlanner() throws Exception {
    if (owner != null) owner.close();
    ConfigStoreRebuilder.rememberDevicePlanner(null);
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
    DevicePlanState.process().publish(GpuProfileResolver.Resolution.unresolved());
    TestResolvedConfigHelper.restoreGlobal(prior);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anOversizedPartialContextUpdatePersistsAndExplainsTheUnmetSetting(boolean chatEnabled)
      throws Exception {
    var current = getJson("/api/settings/v2");
    var toggled = postJson("/api/settings/v2", Map.of("ui", Map.of("chatEnabled", chatEnabled),
        "witness", current.json().path("witness"), "operationKey", operationKey()));
    assertEquals(200, toggled.statusCode(), toggled.body());
    var response = postJson("/api/settings/v2", Map.of("llm", Map.of("contextWindow", 131_072),
        "witness", toggled.json().path("witness"), "operationKey", operationKey()));
    assertEquals(200, response.statusCode(), "partial context write: " + response.body());
    assertEquals(131_072, response.json().path("llm").path("contextWindow").asInt());
    assertEquals(131_072, new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE)
        .load().getContextLength(), "fresh store sees the committed value");
    assertEquals(131_072, config.get().ai().contextSize());
    assertFalse(owner.indexClosed(), "query settings retain the index model lifetime");
    // The sampler publishes the post-settings observation; HTTP only reads that publication.
    var coreField = LocalApiServer.class.getDeclaredField("core");
    coreField.setAccessible(true);
    var core = (CoreApiAssembly.Result) coreField.get(server);
    core.statusLifecycleHandler().sampleAndBuildStatusSnapshot();
    var status = getJson("/api/status");
    assertEquals(200, status.statusCode(), status.body());
    var notices = status.json().path("operating").path("degradations");
    assertTrue(notices.toString().contains("user_setting_unmet"), status.body());
    assertTrue(notices.toString().contains("inference.setting_unmet"), status.body());
  }

  private static String operationKey() {
    return io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
  }
}
