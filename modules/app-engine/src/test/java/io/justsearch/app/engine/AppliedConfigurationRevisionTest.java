/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.VerifiedLlamaTestServer;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.services.GenerativeSettingsComponentOwner;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.phases.InferenceCapabilityWiring;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class AppliedConfigurationRevisionTest {
  @TempDir Path directory;
  private static final List<String> COMPONENTS =
      List.of("api", "encoders", "generative", "index");

  @Test
  void ignoresOrderingAndObservationMetadataButIncludesEveryAppliedVersion() {
    Map<String, String> applied = appliedVersions();
    AppliedIndexGeneration generation = generation("generation-a", "{\"b\":2,\"a\":1}");
    String expected = AppliedConfigurationRevision.digest(snapshot(7, COMPONENTS, applied, false), generation);

    assertEquals(expected, AppliedConfigurationRevision.digest(
        snapshot(99, COMPONENTS.reversed(), applied, true), generation));

    for (String component : COMPONENTS) {
      var changed = new LinkedHashMap<>(applied);
      changed.put(component, applied.get(component) + "-changed");
      assertNotEquals(expected, AppliedConfigurationRevision.digest(
          snapshot(7, COMPONENTS, changed, false), generation), component);
    }
  }

  @Test
  void eachGenerativeLaunchControlChangesPublishedComponentAndEngineRevision() throws Exception {
    var controls = Map.of(
        "justsearch.llm.slots", "2",
        "justsearch.llm.kv_type", "q8_0",
        "justsearch.llm.use_thinking", "true",
        "justsearch.llm.reasoning_budget", "512");
    String before = preparedGenerativeVersion(controls);
    var applied = appliedVersions();
    applied.put("generative", before);
    var generation = generation("generation-a", "{}");
    String revision = AppliedConfigurationRevision.digest(
        snapshot(1, COMPONENTS, applied, false), generation);

    for (var change : Map.of(
        "justsearch.llm.slots", "3",
        "justsearch.llm.kv_type", "f16",
        "justsearch.llm.use_thinking", "false",
        "justsearch.llm.reasoning_budget", "256").entrySet()) {
      var changed = new LinkedHashMap<>(controls);
      changed.put(change.getKey(), change.getValue());
      String after = preparedGenerativeVersion(changed);
      assertNotEquals(before, after, change.getKey());
      var nextApplied = new LinkedHashMap<>(applied);
      nextApplied.put("generative", after);
      assertNotEquals(revision, AppliedConfigurationRevision.digest(
          snapshot(1, COMPONENTS, nextApplied, false), generation), change.getKey());
    }
  }

  private String preparedGenerativeVersion(Map<String, String> controls) throws Exception {
    var builder = ResolvedConfig.builder().putDefault("justsearch.llm.enabled", "true");
    controls.forEach(builder::putDefault);
    var desired = builder.build();
    var manager = mock(InferenceLifecycleManager.class);
    var prepared = mock(InferenceLifecycleManager.PreparedConfigApply.class);
    when(manager.prepareResolvedConfig(any(), eq(desired), eq(true), eq(false))).thenReturn(prepared);
    when(prepared.targetsOnline()).thenReturn(true);
    var handle = mock(ComponentHandle.class);
    var before = component("generative", "A", false);
    var staging = component("generative", "A", true, ComponentState.RELOADING);
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(eq(before), eq(ComponentState.RELOADING), any(), any()))
        .thenReturn(true);
    var settings = new UiSettings();
    settings.setChatEnabled(true);
    var candidate = new GenerativeSettingsComponentOwner(manager, handle, directory, false)
        .prepare(settings, desired, controls.keySet());
    return candidate.observation().appliedVersion();
  }

  @ParameterizedTest
  @CsvSource({
      "justsearch.llm.slots,3",
      "justsearch.llm.kv_type,f16",
      "justsearch.llm.use_thinking,false",
      "justsearch.llm.reasoning_budget,256",
      "policy.gpu_acceleration_enabled,false"
  })
  void deferredRefreshPublishesVerifiedBAndRecoversThatIdentity(String key, String value)
      throws Exception {
    exerciseDeferredRefresh(key, value, null);
  }

  @Test
  void onlinePolicyRefreshPublishesVerifiedIdentityAndRecoversRetainedPolicy() throws Exception {
    exerciseRefresh("policy.gpu_acceleration_enabled", "false", null, false);
  }

  @ParameterizedTest
  @CsvSource({"health", "witness"})
  void rejectedDeferredStartupCannotPromoteDesiredIdentity(String failure) throws Exception {
    exerciseDeferredRefresh("justsearch.llm.kv_type", "f16", failure);
  }

  private void exerciseDeferredRefresh(String key, String value, String rejected) throws Exception {
    exerciseRefresh(key, value, rejected, true);
  }

  private void exerciseRefresh(String key, String value, String rejected, boolean deferred)
      throws Exception {
    Path executable = Files.writeString(directory.resolve("llama-server.exe"), "server");
    Path model = Files.writeString(directory.resolve("chat.gguf"), "model");
    var controls = new LinkedHashMap<>(Map.of(
        "justsearch.llm.slots", "2", "justsearch.llm.kv_type", "q8_0",
        "justsearch.llm.use_thinking", "true", "justsearch.llm.reasoning_budget", "512",
        "policy.gpu_acceleration_enabled", "true"));
    boolean policy = key.equals("policy.gpu_acceleration_enabled");
    if (policy) controls.put("justsearch.gpu.layers", "12");
    var resolvedA = launchConfiguration(controls, executable, model);
    controls.put(key, value);
    var resolvedB = launchConfiguration(controls, executable, model);
    var inference = InferenceConfig.fromResolvedConfig(resolvedA, directory);
    var config = new ConfigStore(resolvedA);
    var budget = new RetainedStateBudget();
    budget.declare(DefaultEngineComponentRegistry.ATTEMPTED_CONFIGURATIONS, 1, "test");
    try (var server = new VerifiedLlamaTestServer();
        var executors = new TestEngineExecutors();
        var registry = new DefaultEngineComponentRegistry(budget, config.publicationLock());
        var manager = new InferenceLifecycleManager(executors, inference,
            InferenceTelemetryEvents.noop(), ManagedChildRegistry.noop(), resolvedA)) {
      for (String name : List.of("api", "index", "encoders")) {
        registry.register(refreshSpec(name, key))
            .setAppliedVersion(name + "-applied");
      }
      var handle = new ReasonRetainingComponentHandle(
          registry.register(refreshSpec("generative", key)));
      String versionA = HeadAssembly.generativeAppliedVersion(inference, resolvedA);
      handle.setAppliedVersion(versionA);
      handle.setDesiredVersion(versionA);
      var settings = new UiSettings();
      settings.setChatEnabled(true);
      var settingsStore = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          directory.resolve("settings.json"));
      settingsStore.replacePrepared(settingsStore.prepareExact(settings, new SettingsWitness(0, null)));
      InferenceCapabilityWiring.attachInferenceModeListener(manager, handle,
          new RuntimeSpecStore(settingsStore), null, config.publicationLock());
      manager.switchToOnlineMode();
      assertLaunchFlags(server, resolvedA);
      var activeGeneration = generation("generation-a", "{}");
      String revisionA = AppliedConfigurationRevision.digest(registry.snapshot(), activeGeneration);
      if (deferred) {
        server.failServing();
        assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      } else {
        server.onHealth(() -> {
          assertFalse(server.verified());
          assertEquals(versionA, handle.snapshot().appliedVersion());
        });
      }

      var composer = new FixedSettingsComponentComposer(registry);
      composer.register("generative",
          new GenerativeSettingsComponentOwner(manager, handle, directory, false));
      if (policy) {
        var query = mock(FixedSettingsComponentComposer.QueryRolePreparedOwner.class);
        when(query.selection()).thenReturn(new QueryRoleSelection(
            QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()));
        when(query.composition()).thenReturn(Optional.of(new ComposeEvidence(
            ComposeEvidence.Mode.IN_PLACE, "test", 0L, 0L)));
        when(query.observation()).thenReturn(registry.snapshot().components().stream()
            .filter(row -> row.spec().name().equals("encoders")).findFirst().orElseThrow());
        org.mockito.Mockito.doAnswer(invocation -> {
          ((Runnable) invocation.getArgument(0)).run();
          return null;
        }).when(query).withOwnerLocks(any());
        composer.register("encoders", (candidate, desired, keys) -> query);
      }
      composer.seal();
      var affected = ConfigApplyScopes.classify(resolvedA, resolvedB).component();
      assertEquals(Set.of(policy ? "encoders" : "generative"), affected.keySet());
      // Online policy-only settings dispatch has no explicit generative refresh hint.
      var prepared = composer.prepare(settings, resolvedB, affected,
          policy && !deferred ? SettingsCandidateContext.NONE : new SettingsCandidateContext(null, true));
      var swap = config.prepareSwap(resolvedB);
      prepared.withOwnerLocks(() -> {
        config.publicationLock().writeLock().lock();
        try {
          prepared.validate();
          config.validatePrepared(swap);
          prepared.install();
          config.installPrepared(swap);
        } finally {
          config.publicationLock().writeLock().unlock();
        }
      });
      prepared.notifyObservers();
      prepared.retire();
      String versionB = handle.snapshot().desiredVersion();
      assertNotEquals(versionA, versionB, key);
      if (deferred) {
        assertEquals(1, server.starts(), "Offline refresh must defer the physical launch");
        assertEquals(versionA, handle.snapshot().appliedVersion());
        assertEquals(revisionA, AppliedConfigurationRevision.digest(registry.snapshot(), activeGeneration));
        server.onHealth(() -> {
          assertFalse(server.verified());
          assertEquals(versionA, handle.snapshot().appliedVersion(), "health has not verified B yet");
          // A newer desired observation is not proof of what this physical attempt launched.
          // Copying desiredVersion instead of hashing the verified context must fail this test.
          if (rejected == null) handle.setDesiredVersion("unapplied-c");
        });
        if (rejected != null) {
          if (rejected.equals("health")) server.rejectNextHealth();
          else server.rejectNextWitness();
          assertThrows(ModeTransitionException.class, manager::switchToOnlineMode);
          assertEquals(versionA, handle.snapshot().appliedVersion());
          assertEquals(versionB, handle.snapshot().desiredVersion());
          assertEquals(revisionA, AppliedConfigurationRevision.digest(registry.snapshot(), activeGeneration));
          return;
        }
        List<EngineComponentSnapshot.Component> ready = new ArrayList<>();
        try (var subscription = registry.subscribe(snapshot -> {
          var row = snapshot.components().stream()
              .filter(component -> component.spec().name().equals("generative"))
              .findFirst().orElseThrow();
          if (row.state() == ComponentState.READY) {
            assertTrue(server.verified());
            ready.add(row);
          }
        })) {
          manager.switchToOnlineMode();
        }
        assertSame(resolvedB, server.lastResolved());
        assertEquals(versionB, handle.snapshot().appliedVersion(), key);
        assertEquals("unapplied-c", handle.snapshot().desiredVersion());
        assertEquals(ComponentState.READY, handle.snapshot().state());
        assertFalse(ready.isEmpty());
        ready.forEach(row -> assertEquals(versionB, row.appliedVersion(), "READY and B publish together"));
      } else {
        assertEquals(2, server.starts(), "Online refresh must launch B before publishing it");
        assertEquals(versionB, handle.snapshot().appliedVersion());
        assertEquals(ComponentState.READY, handle.snapshot().state());
      }
      assertSame(resolvedB, server.lastResolved());
      assertLaunchFlags(server, resolvedB);
      String revisionB = AppliedConfigurationRevision.digest(registry.snapshot(), activeGeneration);
      assertNotEquals(revisionA, revisionB, key);
      server.onHealth(() -> assertEquals(versionB, handle.snapshot().appliedVersion()));
      // A subsequent process snapshot must not substitute its policy for the retained launch B.
      config.swap(resolvedA);
      server.failServing();
      var request = new RegistryRecoveryRequest(handle);
      var recovered = manager.recoverComponent(request, HeadAssembly::generativeAppliedVersion);
      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, recovered.outcome());
      assertSame(resolvedB, server.lastResolved());
      assertLaunchFlags(server, resolvedB);
      assertEquals(3, server.starts(), "one recovery must launch the same B once");
      assertEquals(versionB, handle.snapshot().appliedVersion());
      assertEquals(revisionB, AppliedConfigurationRevision.digest(registry.snapshot(), activeGeneration));
    }
  }

  private static ComponentSpec refreshSpec(String name, String key) {
    var spec = component(name, name + "-applied", false).spec();
    boolean consumes = name.equals("generative")
        || name.equals("encoders") && key.equals("policy.gpu_acceleration_enabled");
    return new ComponentSpec(name, spec.essential(), consumes ? Set.of(key) : spec.dependencyKeys(),
        spec.composeCapability(), spec.startDeadline(), spec.recoveryBudget());
  }

  private static void assertLaunchFlags(VerifiedLlamaTestServer server, ResolvedConfig resolved) {
    var command = server.lastCommand();
    int layers = resolved.ai().gpuAccelerationAllowed() ? resolved.ai().gpuLayers() : 0;
    assertEquals(Integer.toString(layers), command.get(command.indexOf("-ngl") + 1));
    assertEquals(Integer.toString(resolved.ai().llmSlots()), command.get(command.indexOf("-np") + 1));
    assertEquals(resolved.ai().llmKvType(), command.get(command.indexOf("-ctk") + 1));
    assertEquals(resolved.ai().llmKvType(), command.get(command.indexOf("-ctv") + 1));
  }

  private ResolvedConfig launchConfiguration(Map<String, String> controls, Path executable, Path model) {
    var builder = ResolvedConfig.builder()
        .putDefault("justsearch.llm.enabled", "true")
        .putDefault("justsearch.server.exe", executable.toString())
        .put("justsearch.llm.model_path", 400, "yaml", null, model.toString())
        .putDefault("justsearch.context.size", "4096")
        .putDefault("justsearch.gpu.layers", controls.getOrDefault("justsearch.gpu.layers", "0"))
        .putDefault("justsearch.data.dir", directory.toString())
        .putDefault("justsearch.mmproj.model", "none");
    controls.forEach(builder::putDefault);
    return builder.build();
  }

  private static final class RegistryRecoveryRequest implements ComponentRecoveryAction.Request {
    private final ComponentHandle handle;
    private final EngineComponentSnapshot.Component expected;
    private EngineComponentSnapshot.Component admitted;
    private RegistryRecoveryRequest(ComponentHandle handle) {
      this.handle = handle;
      this.expected = handle.snapshot();
    }
    @Override public EngineComponentSnapshot.Component expected() { return expected; }
    @Override public EngineComponentSnapshot.Component current() { return handle.snapshot(); }
    @Override public Optional<EngineComponentSnapshot.Component> admitted() {
      return Optional.ofNullable(admitted);
    }
    @Override public boolean begin() {
      admitted = handle.tryBeginRecovery(expected, "inference.starting", "recovery").orElse(null);
      return admitted != null;
    }
    @Override public Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component current, ComponentState state, String reason, String evidence) {
      return handle.tryTransitionIfUnchanged(current, state, reason, evidence);
    }
    @Override public boolean cancelled() { return false; }
  }

  @Test
  void generationIdentityAndCanonicalInputValuesParticipateInTheDigest() {
    EngineComponentSnapshot snapshot = snapshot(1, COMPONENTS, appliedVersions(), false);
    String first = AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"b\":2,\"a\":1}}"));

    assertEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"a\":1,\"b\":2}}")));
    assertNotEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-b", "{\"nested\":{\"a\":1,\"b\":2}}")));
    assertNotEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"a\":1,\"b\":3}}")));
  }

  @Test
  void refusesMissingDuplicateOrUnestablishedComponentObservations() {
    AppliedIndexGeneration generation = generation("generation-a", "{}");
    var missing = new ArrayList<>(COMPONENTS);
    missing.remove("generative");
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot(1, missing, appliedVersions(), false), generation));

    var duplicate = new ArrayList<>(COMPONENTS);
    duplicate.add("api");
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot(1, duplicate, appliedVersions(), false), generation));

    for (String component : COMPONENTS) {
      var incomplete = new LinkedHashMap<>(appliedVersions());
      incomplete.put(component, null);
      assertUnavailable(() -> AppliedConfigurationRevision.digest(
          snapshotWithAbsent(component, incomplete), generation));
    }
  }

  @Test
  void refusesMalformedOrNonObjectCommittedInputs() {
    EngineComponentSnapshot snapshot = snapshot(1, COMPONENTS, appliedVersions(), false);
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{")));
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "[]")));
  }

  private static EngineComponentSnapshot snapshot(
      long revision, List<String> order, Map<String, String> applied, boolean noisy) {
    List<EngineComponentSnapshot.Component> rows = order.stream()
        .map(name -> component(name, applied.get(name), noisy))
        .toList();
    return new EngineComponentSnapshot(revision, rows);
  }

  private static EngineComponentSnapshot snapshotWithAbsent(
      String absentName, Map<String, String> applied) {
    return new EngineComponentSnapshot(1, COMPONENTS.stream()
        .map(name -> name.equals(absentName)
            ? component(name, null, false, ComponentState.ABSENT)
            : component(name, applied.get(name), false))
        .toList());
  }

  private static EngineComponentSnapshot.Component component(
      String name, String appliedVersion, boolean noisy) {
    return component(name, appliedVersion, noisy,
        noisy ? ComponentState.FAILED : ComponentState.READY);
  }

  private static EngineComponentSnapshot.Component component(
      String name, String appliedVersion, boolean noisy, ComponentState state) {
    var spec = new ComponentSpec(name, name.equals("api") || name.equals("index"), Set.of(name + ".key"),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ofSeconds(1), 2);
    return new EngineComponentSnapshot.Component(
        spec,
        state,
        noisy ? name + ".reason" : null,
        noisy ? Instant.parse("2030-01-01T00:00:00Z") : Instant.EPOCH,
        noisy ? 987_654_321L : 1L,
        appliedVersion,
        noisy ? name + "-desired" : null,
        noisy ? new ComposeEvidence(ComposeEvidence.Mode.IN_PLACE, "diagnostic", 10L, 20L) : null,
        noisy ? 2 : 0,
        noisy ? "opaque evidence" : null);
  }

  private static Map<String, String> appliedVersions() {
    var versions = new LinkedHashMap<String, String>();
    for (String component : COMPONENTS) versions.put(component, component + "-applied");
    return versions;
  }

  private static AppliedIndexGeneration generation(String id, String inputs) {
    return new AppliedIndexGeneration(id, new IndexTargetSnapshot(sha256(inputs), inputs));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void assertUnavailable(org.junit.jupiter.api.function.Executable executable) {
    KnowledgeClientException failure = assertThrows(KnowledgeClientException.class, executable);
    assertEquals(KnowledgeClientException.Status.UNAVAILABLE, failure.status());
  }
}
