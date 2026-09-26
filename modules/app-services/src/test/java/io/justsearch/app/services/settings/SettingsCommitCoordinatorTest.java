/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Direct owner and real SQLite runner coverage for the settings commit protocol. */
final class SettingsCommitCoordinatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  private static final Set<OperationKind> SETTINGS_KINDS =
      Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE);

  @TempDir Path temp;

  @Test
  void normalCommitPublishesFileWitnessConfigAndPreparedResponse() throws Exception {
    Path settingsPath = temp.resolve("normal-settings.json");
    try (var operations = operations("normal")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      var owner = new SettingsCommitCoordinator(settings, config, () -> {}, candidate ->
          OperationResult.success("prepared", Map.of("theme", candidate.getTheme())));
      var runner = runner(operations, owner);
      UiSettings candidate = candidate("dark", List.of("*.tmp"));
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));

      var result = runner.start(attempt, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate);
        return OperationExecution.finished(OperationResult.success("handler"));
      });

      assertEquals(OperationState.COMPLETE, result.record().state());
      assertTrue(Files.exists(settingsPath));
      var snapshot = settings.inspect();
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), snapshot.witness());
      assertEquals("dark", snapshot.settings().getTheme());
      assertEquals("[\"*.tmp\"]", config.get().ui().excludePatterns());
      assertEquals("prepared", result.response().message());
      assertEquals(attempt.accepted().key(), result.response().structuredData().get("operationKey"));
      assertEquals(1L, result.response().structuredData().get("acceptedRevision"));
      assertEquals("dark", result.response().structuredData().get("theme"));
      assertEquals(0L, operations.find(attempt.accepted().key()).orElseThrow().expectedSettingsRevision());
    }
  }

  @Test
  void generationBoundChangeRefusesWithReindexPointerBeforeFileOrConfigPublication()
      throws Exception {
    Path settingsPath = temp.resolve("generation-bound-settings.json");
    try (var operations = operations("generation-bound")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var initial = ConfigStoreRebuilder.prepare(new UiSettings());
      var config = new ConfigStore(initial);
      var owner = coordinator(settings, config);
      var runner = runner(operations, owner);
      UiSettings candidate = new UiSettings();
      candidate.setEmbedOnnxModelPath(temp.resolve("different-encoder.onnx").toString());
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertFalse(result.response().success());
      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("GENERATION_BOUND_REQUIRES_REINDEX",
          result.response().errorCode().orElseThrow());
      assertEquals("core.bulk-reindex", result.response().errorDetails().get("operation"));
      assertTrue(((List<?>) result.response().errorDetails().get("keys"))
          .contains("justsearch.embed.onnx.model_path"));
      assertFalse(Files.exists(settingsPath));
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      assertSame(initial, config.get());
    }
  }

  @Test
  void maskedInstallerDesiredPathStillCommitsThroughOrdinarySettingsOwner() throws Exception {
    String key = "justsearch.embed.onnx.model_path";
    String prior = System.getProperty(key);
    System.setProperty(key, temp.resolve("operator-model").toString());
    try {
      Path settingsPath = temp.resolve("masked-installer-settings.json");
      try (var operations = operations("masked-installer")) {
        var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
        var config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
        var runner = runner(operations, coordinator(settings, config));
        UiSettings candidate = settings.load();
        String desired = temp.resolve("installed-model").toString();
        candidate.setEmbedOnnxModelPath(desired);
        var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));

        var result = runner.start(attempt, handle -> OperationExecution.finished(
            runner.applySettings(handle, currentWitness(settings), candidate)));

        assertEquals(OperationState.COMPLETE, result.record().state());
        assertEquals(desired, settings.inspect().settings().getEmbedOnnxModelPath());
        assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
      }
    } finally {
      if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
    }
  }

  @Test
  void installerGenerationRecoveryWaitsForCompositeOwnerOnBothSidesOfSettingsMove()
      throws Exception {
    for (boolean settingsMoved : List.of(false, true)) {
      Path settingsPath = temp.resolve("activation-recovery-" + settingsMoved + ".json");
      try (var operations = operations("activation-recovery-" + settingsMoved)) {
        var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
        var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
        OperationRecord accepted = row(operations, OperationKind.REINDEX);
        OperationRecord armed = new OperationRecord(accepted.id(), accepted.key(),
            new OperationDescriptor(OperationKind.REINDEX,
                RecordedInstallerGenerationPlan.OPERATION_ID, "{}"), accepted.context(),
            accepted.executor(), accepted.initiator(), accepted.correlationId(),
            OperationState.RUNNING, accepted.phase(), accepted.checkpointCursor(),
            accepted.unitsCompleted(), accepted.unitsFailed(), accepted.attempts(),
            accepted.acceptedAt(), accepted.startedAt(), accepted.updatedAt(),
            accepted.completedAt(), accepted.failureReason(), accepted.receipt(),
            accepted.historyMode(), accepted.provenanceOccurredAt(), 0L);
        if (settingsMoved) writeWitness(settings, 1, armed.key());

        owner.inspectRecovery(List.of(new SettingsCommitOwner.RecoveryInput(armed, Optional.empty())));

        assertInstanceOf(OperationAttemptRunner.Reconciliation.Wait.class, owner.reconcile(armed),
            "settings alone cannot complete or fail a pointer-committed activation");
        assertThrows(SettingsCommitOwner.Refused.class,
            () -> owner.reserve(armed.id() + 1, OperationKeys.generate(CLOCK), settings.inspect().witness()));
        if (!settingsMoved) {
          var resumed = owner.reserve(armed.id(), armed.key(), new SettingsWitness(0, null));
          assertNotNull(resumed, "the same armed installer row must reuse its boot reservation");
          assertThrows(SettingsCommitOwner.Refused.class,
              () -> owner.reserve(armed.id(), armed.key(), new SettingsWitness(1, armed.key())),
              "a successor witness cannot be re-prepared as the source");
        } else {
          assertThrows(SettingsCommitOwner.Refused.class,
              () -> owner.reserve(armed.id(), armed.key(), new SettingsWitness(0, null)),
              "a committed successor witness must be reconciled by the composite owner");
        }
      }
    }
  }

  @Test
  void installerProjectionKeepsSettingsAtAUntilItsPointerCallback() throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        temp.resolve("installer-projection.json"));
    var initial = ConfigStoreRebuilder.prepare(new UiSettings());
    var config = new ConfigStore(initial);
    var owner = coordinator(settings, config);
    owner.inspectRecovery(List.of());
    String key = OperationKeys.generate(CLOCK);
    UiSettings candidate = new UiSettings();
    candidate.setEmbedOnnxModelPath(temp.resolve("candidate-embedding.onnx").toString());
    var control = new ProjectionControl();
    var reservation = owner.reserve(71, key, currentWitness(settings));
    var projection = owner.prepareInstallerGenerationProjection(reservation, candidate, control);

    assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
    assertSame(initial, config.get());
    config.publicationLock().writeLock().lock();
    try {
      projection.admitBeforePointer();
      assertTrue(control.admitted);
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      projection.afterPointerCommitted();
    } finally { config.publicationLock().writeLock().unlock(); }
    assertTrue(control.committed);
    assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
    assertEquals(candidate.getEmbedOnnxModelPath(), settings.inspect().settings().getEmbedOnnxModelPath());
    projection.afterRuntimePublished();
    owner.releaseAfterTerminal(71);
  }

  @Test
  void installerProjectionRefusesFullWitnessConflictBeforePointer() throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        temp.resolve("installer-projection-conflict.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    var owner = coordinator(settings, config);
    owner.inspectRecovery(List.of());
    String key = OperationKeys.generate(CLOCK);
    var reservation = owner.reserve(72, key, currentWitness(settings));
    var projection = owner.prepareInstallerGenerationProjection(reservation, new UiSettings(),
        new ProjectionControl());
    writeWitness(settings, 1, OperationKeys.generate(CLOCK));

    config.publicationLock().writeLock().lock();
    try { assertThrows(SettingsCommitOwner.Refused.class, projection::admitBeforePointer); }
    finally { config.publicationLock().writeLock().unlock(); }
    projection.abortBeforePointer();
    assertEquals(1L, settings.inspect().witness().acceptedRevision());
    owner.releaseAfterTerminal(72);
  }

  @Test
  void installerPostmoveFailureWithMatchingWitnessButDifferentBytesRequiresRecovery() throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        temp.resolve("installer-postmove-drift.json"));
    var initial = ConfigStoreRebuilder.prepare(new UiSettings());
    var config = new ConfigStore(initial);
    var owner = new SettingsCommitCoordinator(settings, config, () -> {},
        ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"), prepared -> {
          settings.replacePrepared(settings.prepareExact(new UiSettings(), prepared.witness()));
          throw new IOException("move reported failure after installing different bytes");
        });
    owner.inspectRecovery(List.of());
    String key = OperationKeys.generate(CLOCK);
    UiSettings candidate = new UiSettings();
    candidate.setEmbedOnnxModelPath(temp.resolve("accepted-model").toString());
    var reservation = owner.reserve(73, key, currentWitness(settings));
    AtomicBoolean uncertain = new AtomicBoolean();
    var control = new SettingsCommitOwner.AttemptControl() {
      @Override public boolean admitCommit(SettingsCommitOwner.Receipt receipt) { return true; }
      @Override public void committed(SettingsCommitOwner.Receipt receipt) {
        throw new AssertionError("different settings bytes cannot commit");
      }
      @Override public void uncertain() { uncertain.set(true); }
    };
    var projection = owner.prepareInstallerGenerationProjection(reservation, candidate, control);

    config.publicationLock().writeLock().lock();
    try {
      projection.admitBeforePointer();
      assertThrows(IOException.class, projection::afterPointerCommitted);
    } finally { config.publicationLock().writeLock().unlock(); }
    assertTrue(uncertain.get());
    assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
    assertEquals("", settings.inspect().settings().getEmbedOnnxModelPath());
    assertSame(initial, config.get(), "failed exact projection cannot publish a candidate runtime");
  }

  @Test
  void installerTerminalGuardRequiresBothSuccessorWitnessAndExactCandidate() throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        temp.resolve("installer-terminal-guard.json"));
    var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
    String key = OperationKeys.generate(CLOCK);
    UiSettings candidate = new UiSettings();
    candidate.setEmbedOnnxModelPath(temp.resolve("candidate-model").toString());
    String json = tools.jackson.databind.json.JsonMapper.builder().build()
        .writeValueAsString(candidate);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest("{}".getBytes(StandardCharsets.UTF_8)));
    String assetHash = "0".repeat(64);
    var provenance = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY, "registry", assetHash);
    Path asset = temp.resolve("candidate-model.onnx");
    var plan = new RecordedInstallerGenerationPlan(key, "g-source",
        new RecordedRootPlan("g-source", List.of()), new IndexTargetSnapshot(digest, "{}"),
        new SettingsWitness(0, null),
        RecordedInstallerGenerationPlan.CandidateSettings.fromJson(json),
        List.of(new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "model.onnx",
            asset, assetHash, 1, provenance)),
        List.of(new RecordedInstallerGenerationPlan.AssetIdentity("embedding/model.onnx",
            asset, assetHash, 1, provenance)),
        RecordedInstallerGenerationPlan.ChatSelection.none(), provenance);

    assertFalse(owner.installerGenerationProjected(plan));
    settings.replacePrepared(settings.prepareExact(candidate, new SettingsWitness(1, key)));
    assertTrue(owner.installerGenerationProjected(plan));
    settings.replacePrepared(settings.prepareExact(new UiSettings(), new SettingsWitness(1, key)));
    assertFalse(owner.installerGenerationProjected(plan),
        "matching revision and operation key cannot certify different settings bytes");
  }

  private static final class ProjectionControl implements SettingsCommitOwner.AttemptControl {
    private boolean admitted;
    private boolean committed;
    @Override public boolean admitCommit(SettingsCommitOwner.Receipt receipt) {
      admitted = true;
      return true;
    }
    @Override public void committed(SettingsCommitOwner.Receipt receipt) { committed = true; }
    @Override public void uncertain() { throw new AssertionError("Projection unexpectedly became uncertain"); }
  }

  @Test
  void componentChangeRefusesBeforePublicationUntilItsRuntimeOwnerCanPrepare() throws Exception {
    Path settingsPath = temp.resolve("component-settings.json");
    try (var operations = operations("component")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var initial = ConfigStoreRebuilder.prepare(new UiSettings());
      var config = new ConfigStore(initial);
      var owner = coordinator(settings, config);
      var runner = runner(operations, owner);
      UiSettings candidate = new UiSettings();
      candidate.setContextLength(initial.ai().contextSize() + 1024);
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("COMPONENT_PREPARATION_REQUIRED", result.response().errorCode().orElseThrow());
      assertFalse(Files.exists(settingsPath));
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      assertSame(initial, config.get());
    }
  }

  @Test
  void secondComponentRefusalAbortsFirstAndLeavesAcceptedSettingsAtA() throws Exception {
    Path settingsPath = temp.resolve("two-component-settings.json");
    try (var operations = operations("two-component")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      settings.replacePrepared(settings.prepareExact(new UiSettings(), new SettingsWitness(0, null)));
      byte[] originalBytes = Files.readAllBytes(settingsPath);
      var initial = ConfigStoreRebuilder.prepare(settings.load());
      var config = new ConfigStore(initial);
      var registry = org.mockito.Mockito.mock(io.justsearch.core.component.EngineComponentRegistry.class);
      var lease = org.mockito.Mockito.mock(io.justsearch.core.component.EngineComponentRegistry.ApplyLease.class);
      org.mockito.Mockito.when(registry.tryApply()).thenReturn(
          new io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt.Acquired(lease));
      var first = org.mockito.Mockito.mock(FixedSettingsComponentComposer.PreparedOwner.class);
      org.mockito.Mockito.when(first.observation()).thenReturn(
          org.mockito.Mockito.mock(io.justsearch.core.component.EngineComponentSnapshot.Component.class));
      var components = new FixedSettingsComponentComposer(registry);
      components.register("generative", (candidate, desired, keys) -> first);
      components.register("index", (candidate, desired, keys) -> {
        throw new SettingsCommitOwner.Refused(OperationResult.failure(
            "Index candidate refused", "COMPONENT_PREPARATION_REQUIRED",
            Map.of("component", "index"), false));
      });
      components.seal();
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> OperationResult.success("prepared"), () -> false, components);
      var runner = runner(operations, owner);
      UiSettings candidate = settings.load();
      candidate.setContextLength(initial.ai().contextSize() + 1024);
      candidate.setIndexBasePath(temp.resolve("different-index").toString());
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("COMPONENT_PREPARATION_REQUIRED", result.response().errorCode().orElseThrow());
      assertEquals("index", result.response().errorDetails().get("component"));
      org.mockito.Mockito.verify(first).abort();
      org.mockito.Mockito.verify(lease).close();
      assertArrayEquals(originalBytes, Files.readAllBytes(settingsPath));
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      assertSame(initial, config.get());
    }
  }

  @Test
  void unrelatedSettingsChangeDoesNotPrepareAnyComponent() throws Exception {
    Path settingsPath = temp.resolve("unrelated-component-settings.json");
    try (var operations = operations("unrelated-component")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      var components = org.mockito.Mockito.mock(SettingsComponentComposer.class);
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> OperationResult.success("prepared"), () -> false, components);
      var runner = runner(operations, owner);
      UiSettings candidate = settings.load();
      candidate.setExcludePatterns(List.of("*.tmp"));
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(List.of("*.tmp"), settings.inspect().settings().getExcludePatterns());
      assertEquals("[\"*.tmp\"]", config.get().ui().excludePatterns());
      org.mockito.Mockito.verifyNoInteractions(components);
    }
  }

  @Test
  void chatEnabledChangeCannotEscapeThroughPostcommitReconciler() throws Exception {
    Path settingsPath = temp.resolve("chat-component-settings.json");
    try (var operations = operations("chat-component")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var initial = ConfigStoreRebuilder.prepare(new UiSettings());
      var config = new ConfigStore(initial);
      var runner = runner(operations, coordinator(settings, config));
      UiSettings candidate = new UiSettings();
      candidate.setChatEnabled(true);
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("COMPONENT_PREPARATION_REQUIRED", result.response().errorCode().orElseThrow());
      assertFalse(Files.exists(settingsPath));
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      assertSame(initial, config.get());
    }
  }

  @Test
  void transientProfileForcesOnePreparedGenerativeOwnerEvenWithoutASettingsDelta() throws Exception {
    try (var operations = operations("transient-profile")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("transient-profile-settings.json"));
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      var seen = new AtomicReference<SettingsCandidateContext>();
      var installed = new AtomicBoolean();
      SettingsComponentComposer components = new SettingsComponentComposer() {
        @Override public Prepared prepare(UiSettings candidate,
            io.justsearch.configuration.resolved.ResolvedConfig desired,
            Map<String, Set<String>> affected) {
          throw new AssertionError("Profile context must not be dropped");
        }

        @Override public Prepared prepare(UiSettings candidate,
            io.justsearch.configuration.resolved.ResolvedConfig desired,
            Map<String, Set<String>> affected, SettingsCandidateContext context) {
          assertEquals(Set.of("generative"), affected.keySet());
          assertEquals(Set.of("chatProfile"), affected.get("generative"));
          seen.set(context);
          return new Prepared() {
            @Override public void validate() {}
            @Override public void install() { installed.set(true); }
            @Override public void notifyObservers() {}
            @Override public void retire() {}
            @Override public void abort() {}
          };
        }
      };
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> OperationResult.success("prepared"), () -> false, components);
      var runner = runner(operations, owner);
      var context = new SettingsCandidateContext(ChatModelProfile.COMPACT);
      var candidateRequest = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
              SettingsCandidatePreparation.OPERATION_REF, "{}", false), context(), null);
      var prepared = runner.withPreparation(candidateRequest, scope ->
          runner.savePreparation(scope.request(), new io.justsearch.app.api.operations.OperationStore.Preparation(
              java.util.UUID.randomUUID(), SettingsCandidatePreparation.encode(context))).orElseThrow());
      var attempt = runner.acceptPrepared(candidateRequest, prepared.nonce());

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), new UiSettings(), context)));

      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(context, seen.get());
      assertTrue(installed.get());
      assertEquals(1, settings.inspect().witness().acceptedRevision());
    }
  }

  @Test
  void acceptedProfilePreparationCannotBeChangedByExecutingBody() throws Exception {
    try (var operations = operations("profile-binding")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("profile-binding.json"));
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      var owner = coordinator(settings, config);
      var runner = runner(operations, owner);
      var acceptedContext = new SettingsCandidateContext(ChatModelProfile.COMPACT);
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
              SettingsCandidatePreparation.OPERATION_REF, "{}", false), context(), null);
      var preparation = runner.withPreparation(request, scope ->
          runner.savePreparation(scope.request(), new io.justsearch.app.api.operations.OperationStore.Preparation(
              java.util.UUID.randomUUID(), SettingsCandidatePreparation.encode(acceptedContext))).orElseThrow());
      var accepted = runner.acceptPrepared(request, preparation.nonce());

      assertThrows(IllegalArgumentException.class, () -> runner.start(accepted,
          handle -> OperationExecution.finished(runner.applySettings(handle,
              currentWitness(settings), new UiSettings(),
              new SettingsCandidateContext(ChatModelProfile.STANDARD)))));

      assertEquals(OperationState.FAILED, operations.find(accepted.accepted().key()).orElseThrow().state());
      assertFalse(Files.exists(settings.settingsPath()));
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
      assertNull(operations.find(accepted.accepted().key()).orElseThrow().expectedSettingsRevision());
    }
  }

  @Test
  void impossiblePostcommitInstallerFailureRetainsCommittedWitnessForOrderedRecovery()
      throws Exception {
    Path settingsPath = temp.resolve("committed-install-failure.json");
    AtomicInteger restarts = new AtomicInteger();
    try (var operations = operations("committed-install-failure")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      SettingsComponentComposer components = (candidate, desired, affected) ->
          new SettingsComponentComposer.Prepared() {
            @Override public void validate() {}
            @Override public void install() { throw new IllegalStateException("broken prepared install"); }
            @Override public void notifyObservers() {}
            @Override public void retire() {}
            @Override public void abort() {}
          };
      var owner = new SettingsCommitCoordinator(settings, config, restarts::incrementAndGet,
          candidate -> OperationResult.success("prepared"), () -> false, components);
      var runner = runner(operations, owner);
      UiSettings candidate = new UiSettings();
      candidate.setChatEnabled(true);
      var attempt = runner.accept(request(OperationKind.RECONFIGURE));

      assertThrows(IllegalStateException.class, () -> runner.start(attempt, handle ->
          OperationExecution.finished(runner.applySettings(handle, currentWitness(settings), candidate))));

      assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
      assertEquals(OperationState.RUNNING,
          operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(1, restarts.get());
    }
  }

  @Test
  void restartRequiredPortCompletesBeforeSchedulingOneRequestedRestart() throws Exception {
    Path settingsPath = temp.resolve("restart-port-settings.json");
    AtomicInteger restarts = new AtomicInteger();
    AtomicReference<String> committedKey = new AtomicReference<>();
    try (var operations = operations("restart-port")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
      int servingPort = config.get().ports().apiPort();
      var servingPortResolution = config.get().resolution("justsearch.api.port");
      var owner = new SettingsCommitCoordinator(settings, config, () -> {
        assertEquals(new SettingsWitness(1, committedKey.get()), settings.inspect().witness());
        assertEquals(OperationState.COMPLETE,
            operations.find(committedKey.get()).orElseThrow().state());
        assertEquals(servingPort, config.get().ports().apiPort());
        restarts.incrementAndGet();
      }, candidate -> OperationResult.success("prepared"));
      var runner = runner(operations, owner);
      UiSettings candidate = new UiSettings();
      candidate.setApiPort(0);
      var request = request(OperationKind.RECONFIGURE);
      var attempt = runner.accept(request);
      committedKey.set(attempt.accepted().key());

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate)));

      assertTrue(result.response().success());
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(Boolean.TRUE, result.response().structuredData().get("restartScheduled"));
      assertEquals(1, restarts.get());
      assertEquals(Integer.valueOf(0), settings.inspect().settings().configuredApiPort());
      assertEquals(servingPort, config.get().ports().apiPort());
      assertEquals(servingPortResolution, config.get().resolution("justsearch.api.port"));
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
      var retry = runner.start(runner.accept(request), ignored -> {
        throw new AssertionError("recorded reconfigure must not execute again");
      });
      assertEquals(OperationState.COMPLETE, retry.record().state());
      assertEquals(1, restarts.get(), "a receipt lookup cannot schedule a second restart");
    }
  }

  @Test
  void delayedRetryReturnsItsCommittedRevisionWithoutOverwritingLaterSettings() throws Exception {
    try (var operations = operations("delayed-retry")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("delayed-retry.json"));
      var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
      var runner = runner(operations, owner);
      var firstRequest = request(OperationKind.SETTINGS_APPLY);
      var first = runner.accept(firstRequest);
      runner.start(first, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()))));
      var second = runner.accept(request(OperationKind.SETTINGS_APPLY));
      runner.start(second, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate("light", List.of()))));

      SettingsWitness committedWitness = currentWitness(settings);
      var replacement = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var replacementKey = OperationKeys.generate(CLOCK);
      assertNotEquals(committedWitness.lastCommittedOperationKey(), replacementKey);
      var pairRefusal = runner.start(replacement, handle -> OperationExecution.finished(
          runner.applySettings(handle,
              new SettingsWitness(committedWitness.acceptedRevision(), replacementKey),
              candidate("replacement", List.of()))));
      assertFalse(pairRefusal.response().success());
      assertEquals(OperationState.FAILED, pairRefusal.record().state());
      assertEquals("VERSION_CONFLICT", pairRefusal.response().errorCode().orElseThrow());
      assertNull(operations.find(replacement.accepted().key()).orElseThrow().expectedSettingsRevision());
      assertEquals(committedWitness, settings.inspect().witness());

      var retry = runner.start(runner.accept(firstRequest), handle -> {
        throw new AssertionError("A recorded outcome must not execute its body again");
      });
      assertEquals(OperationState.COMPLETE, retry.record().state());
      assertEquals(first.accepted().key(), retry.response().structuredData().get("operationKey"));
      assertEquals(1L, retry.response().structuredData().get("acceptedRevision"));
      assertEquals(new SettingsWitness(2, second.accepted().key()), settings.inspect().witness());
      assertEquals("light", settings.inspect().settings().getTheme());
      var stale = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var refusal = runner.start(stale, handle -> OperationExecution.finished(
          runner.applySettings(handle, new SettingsWitness(0, null), candidate("dark", List.of()))));
      assertEquals("VERSION_CONFLICT", refusal.response().errorCode().orElseThrow());
      assertEquals(OperationState.FAILED, refusal.record().state());
      assertNull(refusal.record().expectedSettingsRevision());
      assertEquals(2L, settings.inspect().witness().acceptedRevision());
    }
  }

  @Test
  void externalWitnessChangeDuringPreparationRefusesBeforeReplacingSettings() throws Exception {
    try (var operations = operations("precommit-witness-change")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("precommit-witness-change.json"));
      var initial = ConfigStoreRebuilder.prepare(new UiSettings());
      var config = new ConfigStore(initial);
      String externalKey = OperationKeys.generate(CLOCK);
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          ConfigStoreRebuilder::prepare, candidate -> {
            try {
              settings.replacePrepared(settings.prepare(candidate("external", List.of()),
                  new SettingsWitness(1, externalKey)));
            } catch (IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
            return OperationResult.success("prepared");
          }, settings::replacePrepared);
      var runner = runner(operations, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));

      var result = runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, new SettingsWitness(0, null), candidate("dark", List.of()))));

      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("VERSION_CONFLICT", result.response().errorCode().orElseThrow());
      assertEquals(new SettingsWitness(1, externalKey), settings.inspect().witness());
      assertEquals("external", settings.inspect().settings().getTheme());
      assertEquals(initial, config.get());
    }
  }

  @Test
  void preparationFailureDoesNotWriteOrSwapAndTerminalFailureReleasesGuard() throws Exception {
    try (var operations = operations("preparation")) {
      Path settingsPath = temp.resolve("preparation-settings.json");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var initial = ConfigStoreRebuilder.prepare(new UiSettings());
      var config = new ConfigStore(initial);
      AtomicBoolean fail = new AtomicBoolean(true);
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("builder failure");
            return ConfigStoreRebuilder.prepare(candidate);
          }, candidate -> OperationResult.success("prepared"), settings::replacePrepared);
      var runner = runner(operations, owner);
      var first = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(IllegalStateException.class, () -> runner.start(first, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.FAILED, operations.find(first.accepted().key()).orElseThrow().state());
      assertFalse(Files.exists(settingsPath));
      assertSame(initial, config.get());

      var second = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var committed = runner.start(second, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("done"));
      });
      assertEquals(OperationState.COMPLETE, committed.record().state());
      assertEquals(1L, settings.inspect().witness().acceptedRevision());
    }
  }

  @Test
  void occupiedAndStaleReservationsRefuseBeforeSqlArm() throws Exception {
    try (var operations = operations("reservations")) {
      var settings = new UiSettingsStore(
          UiSettingsStore.PersistenceMode.READ_WRITE, temp.resolve("reservation-settings.json"));
      var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
      owner.inspectRecovery(List.of());
      var first = row(operations, OperationKind.SETTINGS_APPLY);
      var second = row(operations, OperationKind.SETTINGS_APPLY);
      owner.reserve(first.id(), first.key(), new SettingsWitness(0, null));
      var occupied = assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.reserve(second.id(), second.key(), new SettingsWitness(0, null)));
      assertEquals("RECONFIGURE_IN_PROGRESS", occupied.response().errorCode().orElseThrow());
      assertNull(operations.find(first.key()).orElseThrow().expectedSettingsRevision());
      assertNull(operations.find(second.key()).orElseThrow().expectedSettingsRevision());
      owner.releaseAfterTerminal(first.id());

      var staleWitness = currentWitness(settings);
      UiSettingsStore.PreparedSettings prepared = settings.prepare(candidate("old", List.of()),
          new SettingsWitness(1, OperationKeys.generate(CLOCK)));
      settings.replacePrepared(prepared);
      var stale = assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.reserve(second.id(), second.key(), staleWitness));
      assertEquals("VERSION_CONFLICT", stale.response().errorCode().orElseThrow());
      assertNull(operations.find(second.key()).orElseThrow().expectedSettingsRevision());
    }
  }

  @Test
  void concurrentContenderIsRefusedWhilePreparationHoldsPhysicalMutex() throws Exception {
    try (var operations = operations("concurrent-reserve")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("concurrent-reserve.json"));
      CountDownLatch preparing = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {}, candidate -> {
            preparing.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("preparation probe timeout");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(failure);
            }
            return ConfigStoreRebuilder.prepare(candidate);
          }, candidate -> OperationResult.success("prepared"), settings::replacePrepared);
      var runner = runner(operations, owner);
      var first = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var second = runner.accept(request(OperationKind.SETTINGS_APPLY));
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      AtomicReference<OperationAttemptRunner.Result> secondResult = new AtomicReference<>();
      CountDownLatch refused = new CountDownLatch(1);
      Thread writer = new Thread(() -> {
        try {
          runner.start(first, handle -> OperationExecution.finished(
              runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()))));
        } catch (Throwable failure) { firstFailure.set(failure); }
      }, "settings-transaction-writer");
      Thread contender = new Thread(() -> {
        try {
          secondResult.set(runner.start(second, handle -> OperationExecution.finished(
              runner.applySettings(handle, currentWitness(settings), candidate("light", List.of())))));
        } catch (Throwable failure) { secondFailure.set(failure); }
        finally { refused.countDown(); }
      }, "settings-transaction-contender");
      boolean prompt;
      writer.start();
      try {
        assertTrue(preparing.await(5, TimeUnit.SECONDS));
        contender.start();
        prompt = refused.await(1, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        writer.join(5000);
        contender.join(5000);
      }
      assertFalse(writer.isAlive());
      assertFalse(contender.isAlive());
      assertTrue(prompt, "occupied owner must refuse before the first preparation is released");
      assertNull(firstFailure.get());
      assertNull(secondFailure.get());
      assertEquals("RECONFIGURE_IN_PROGRESS", secondResult.get().response().errorCode().orElseThrow());
      assertNull(secondResult.get().record().expectedSettingsRevision());
      assertEquals(new SettingsWitness(1, first.accepted().key()), settings.inspect().witness());
    }
  }

  @Test
  void fatalBeforeFileRequestsRestartAndReopensAsPrecommitFailure() throws Exception {
    Path db = temp.resolve("fatal-before-file.db");
    Path path = temp.resolve("fatal-before-file.json");
    String key;
    AtomicInteger restarts = new AtomicInteger();
    try (var operations = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
          ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"), prepared -> {
            throw new AssertionError("fatal before replacing file");
          });
      var runner = runner(operations, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      key = attempt.accepted().key();
      assertThrows(AssertionError.class, () -> runner.start(attempt, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of())))));
      assertEquals(1, restarts.get());
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      assertEquals(0L, operations.find(key).orElseThrow().expectedSettingsRevision());
      assertFalse(Files.exists(path));
      assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.reserve(999, OperationKeys.generate(CLOCK), new SettingsWitness(0, null)));
    }
    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      runner(reopened, coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()))));
      assertEquals(OperationState.FAILED, reopened.find(key).orElseThrow().state());
      assertEquals("interrupted_before_settings_commit", reopened.find(key).orElseThrow().failureReason());
      assertEquals(1, restarts.get());
    }
  }

  @Test
  void reentrantPreparationAndCrossThreadNotificationCallsAreRefused() throws Exception {
    var probeRow = unarmedProbeRow("notification-lock-probe");
    var settings = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, temp.resolve("reentrant-settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    AtomicReference<SettingsCommitCoordinator> ownerRef = new AtomicReference<>();
    AtomicReference<SettingsCommitOwner.Reservation> reservationRef = new AtomicReference<>();
    AtomicReference<Throwable> prepareProbe = new AtomicReference<>();
    AtomicReference<Throwable> notificationProbe = new AtomicReference<>();
    CountDownLatch notificationDone = new CountDownLatch(1);
    AtomicBoolean notificationCallbackSawDone = new AtomicBoolean();
    var owner = new SettingsCommitCoordinator(settings, config, () -> {}, candidate -> {
      try {
        ownerRef.get().apply(reservationRef.get(), candidate("nested", List.of()), new RecordingControl());
      } catch (Throwable failure) {
        prepareProbe.set(failure);
      }
      return ConfigStoreRebuilder.prepare(candidate);
    }, candidate -> OperationResult.success("prepared"), settings::replacePrepared);
    ownerRef.set(owner);
    config.addListener(event -> {
      Thread probe = new Thread(() -> {
        try {
          ownerRef.get().reconcile(probeRow);
          ownerRef.get().reserve(100, OperationKeys.generate(CLOCK), currentWitness(settings));
        } catch (Throwable failure) {
          notificationProbe.set(failure);
        } finally {
          notificationDone.countDown();
        }
      }, "settings-notification-probe");
      probe.start();
      try {
        notificationCallbackSawDone.set(notificationDone.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
    });
    owner.inspectRecovery(List.of());
    var reservation = owner.reserve(1, OperationKeys.generate(CLOCK), new SettingsWitness(0, null));
    reservationRef.set(reservation);
    var control = new RecordingControl();
    owner.apply(reservation, candidate("dark", List.of()), control);
    assertNotNull(control.receipt);
    assertInstanceOf(IllegalStateException.class, prepareProbe.get());
    assertTrue(notificationDone.await(5, TimeUnit.SECONDS));
    assertTrue(notificationCallbackSawDone.get(), "notification ran after physical mutex release");
    var notificationRefusal = assertInstanceOf(SettingsCommitOwner.Refused.class, notificationProbe.get());
    assertEquals("RECONFIGURE_IN_PROGRESS", notificationRefusal.response().errorCode().orElseThrow());
    owner.releaseAfterTerminal(1);
  }

  @Test
  void recoveryIssueSubscriberCanCallOwnerAfterMutexIsReleased() throws Exception {
    var probeRow = unarmedProbeRow("recovery-lock-probe");
    Path settingsPath = temp.resolve("unreadable-settings.json");
    Files.createDirectories(settingsPath.getParent());
    Files.writeString(settingsPath, "not-json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
    var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
    owner.inspectRecovery(List.of());
    CountDownLatch callback = new CountDownLatch(1);
    CountDownLatch callbackThreadDone = new CountDownLatch(1);
    AtomicReference<Throwable> probe = new AtomicReference<>();
    AtomicBoolean callbackSawThreadDone = new AtomicBoolean();
    owner.recoveryIssue().thenAccept(issue -> {
      Thread probeThread = new Thread(() -> {
        try {
          owner.reconcile(probeRow);
          owner.reserve(7, OperationKeys.generate(CLOCK), new SettingsWitness(0, null));
        } catch (Throwable failure) {
          probe.set(failure);
        } finally {
          callbackThreadDone.countDown();
        }
      }, "settings-recovery-probe");
      probeThread.start();
      try {
        callbackSawThreadDone.set(callbackThreadDone.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      } finally {
        callback.countDown();
      }
    });
    assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.reserve(6, OperationKeys.generate(CLOCK), new SettingsWitness(0, null)));
    assertTrue(callback.await(5, TimeUnit.SECONDS));
    assertTrue(callbackSawThreadDone.get(), "recovery subscriber ran after physical mutex release");
    assertInstanceOf(SettingsCommitOwner.Refused.class, probe.get());
    assertEquals(SettingsCommitOwner.RecoveryReason.UNREADABLE_WITNESS,
        owner.recoveryIssue().toCompletableFuture().join().reason());
  }

  @Test
  void replacementBeforeMoveFailsAndAfterMoveCommitsByExactWitness() throws Exception {
    Path beforePath = temp.resolve("before-move.json");
    try (var operations = operations("before-move")) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, beforePath);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {},
          ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"),
          prepared -> { throw new IOException("before move"); });
      var runner = runner(operations, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(IllegalStateException.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.FAILED, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertFalse(Files.exists(beforePath));
    }

    try (var operations = operations("after-move")) {
      Path path = temp.resolve("after-move.json");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {},
          ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"),
          prepared -> { settings.replacePrepared(prepared); throw new IOException("after move"); });
      var runner = runner(operations, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var result = runner.start(attempt, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("done"));
      });
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
    }
  }

  @Test
  void thirdAndCorruptWitnessesRetainRunningRowRequestRestartAndBlockHealth() throws Exception {
    for (boolean corrupt : List.of(false, true)) {
      String name = corrupt ? "corrupt-witness" : "third-witness";
      Path path = temp.resolve(name + ".json");
      AtomicInteger restarts = new AtomicInteger();
      try (var operations = operations(name)) {
        var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
        var owner = new SettingsCommitCoordinator(settings,
            new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
            ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"), prepared -> {
              settings.replacePrepared(prepared);
              if (corrupt) Files.writeString(path, "broken");
              else settings.replacePrepared(settings.prepare(prepared.settings(),
                  new SettingsWitness(9, OperationKeys.generate(CLOCK))));
              throw new IOException("ambiguous move");
            });
        var runner = runner(operations, owner);
        var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
        assertThrows(IllegalStateException.class, () -> runner.start(attempt, handle -> {
          runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
          return OperationExecution.finished(OperationResult.success("unreachable"));
        }));
        assertEquals(OperationState.RUNNING, operations.find(attempt.accepted().key()).orElseThrow().state());
        assertEquals(1, restarts.get());
        var issue = owner.recoveryIssue().toCompletableFuture().join();
        assertEquals(corrupt ? SettingsCommitOwner.RecoveryReason.UNREADABLE_WITNESS
            : SettingsCommitOwner.RecoveryReason.CONTRADICTORY_WITNESS, issue.reason());
        assertThrows(SettingsCommitOwner.Refused.class,
            () -> owner.reserve(55, OperationKeys.generate(CLOCK), new SettingsWitness(0, null)));
      }
    }
  }

  @Test
  void failedSqlArmDoesNotPrepareAndReleasesOnlyAfterDurableFailure() throws Exception {
    Path db = temp.resolve("arm-failure.db");
    AtomicInteger preparations = new AtomicInteger();
    AtomicInteger restarts = new AtomicInteger();
    try (var operations = new SqliteOperationStore(db);
        var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("arm-failure.json"));
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
          candidate -> {
            preparations.incrementAndGet();
            return ConfigStoreRebuilder.prepare(candidate);
          }, candidate -> OperationResult.success("prepared"), settings::replacePrepared);
      var runner = runner(operations, owner);
      statement.execute("CREATE TRIGGER reject_settings_arm BEFORE UPDATE OF accepted_settings_revision ON operations "
          + "BEGIN SELECT RAISE(FAIL, 'arm refused'); END");
      var first = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
          () -> runner.start(first, handle -> OperationExecution.finished(
              runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of())))));
      assertEquals(OperationState.FAILED, operations.find(first.accepted().key()).orElseThrow().state());
      assertNull(operations.find(first.accepted().key()).orElseThrow().expectedSettingsRevision());
      assertEquals(0, preparations.get());
      assertEquals(0, restarts.get());
      assertFalse(Files.exists(settings.settingsPath()));
      statement.execute("DROP TRIGGER reject_settings_arm");
      var second = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var committed = runner.start(second, handle -> OperationExecution.finished(
          runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()))));
      assertEquals(OperationState.COMPLETE, committed.record().state());
      assertEquals(1, preparations.get());
    }
  }

  @Test
  void terminalSqlFailureRetainsCommittedFenceForRestart() throws Exception {
    Path db = temp.resolve("terminal-failure.db");
    AtomicInteger restarts = new AtomicInteger();
    try (var operations = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          temp.resolve("terminal-failure.json"));
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
          candidate -> OperationResult.success("prepared"));
      var runner = runner(operations, owner);
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER reject_settings_complete BEFORE UPDATE OF state ON operations "
            + "WHEN NEW.state = 'COMPLETE' BEGIN SELECT RAISE(FAIL, 'terminal fence'); END");
      }
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(RuntimeException.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("done"));
      }));
      assertEquals(OperationState.RUNNING, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(1, restarts.get());
      assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.reserve(88, OperationKeys.generate(CLOCK), currentWitness(settings)));
    }
  }

  @Test
  void fatalAfterFileIsRetainedAndAReopenedOwnerReconcilesExactWitness() throws Exception {
    Path db = temp.resolve("fatal-reopen.db");
    Path settingsPath = temp.resolve("fatal-reopen.json");
    String key;
    AtomicInteger firstRestarts = new AtomicInteger();
    try (var operations = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), firstRestarts::incrementAndGet,
          ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"), prepared -> {
            settings.replacePrepared(prepared);
            throw new AssertionError("after durable replace");
          });
      var runner = runner(operations, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      key = attempt.accepted().key();
      assertThrows(AssertionError.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, currentWitness(settings), candidate("dark", List.of()));
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      assertEquals(1, firstRestarts.get());
    }
    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var restarts = new AtomicInteger();
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
          candidate -> OperationResult.success("prepared"));
      new OperationAttemptRunnerImpl(reopened, CLOCK, SETTINGS_KINDS, owner);
      assertEquals(OperationState.COMPLETE, reopened.find(key).orElseThrow().state());
      assertEquals(0, restarts.get());
    }
  }

  @Test
  void committedTransientProfileWaitsForBootCompositionBeforeTerminalCompletion() throws Exception {
    Path db = temp.resolve("profile-recovery.db");
    Path file = temp.resolve("profile-recovery.json");
    var context = new SettingsCandidateContext(ChatModelProfile.COMPACT);
    String key;
    try (var operations = new SqliteOperationStore(db)) {
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
              SettingsCandidatePreparation.OPERATION_REF, "{}", false), context(), null);
      var preparation = new io.justsearch.app.api.operations.OperationStore.Preparation(
          java.util.UUID.randomUUID(), SettingsCandidatePreparation.encode(context));
      operations.savePreparation(request.key(), request.descriptor(), preparation);
      var row = operations.acceptPrepared(request.key(), request.descriptor(), request.context(),
          request.provenance(), preparation.nonce()).record();
      key = row.key();
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      UiSettings candidate = new UiSettings();
      candidate.setServerExecutablePath(temp.resolve("llama-server.exe").toString());
      settings.replacePrepared(settings.prepare(candidate, new SettingsWitness(1, key)));
      // Process termination here loses the in-memory prepared owner, after the durable move.
    }

    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
      var installed = new AtomicInteger();
      SettingsComponentComposer components = new SettingsComponentComposer() {
        @Override public Prepared prepare(UiSettings candidate,
            io.justsearch.configuration.resolved.ResolvedConfig desired,
            Map<String, Set<String>> affected) {
          throw new AssertionError("Recovered transient context was dropped");
        }

        @Override public Prepared prepare(UiSettings candidate,
            io.justsearch.configuration.resolved.ResolvedConfig desired,
            Map<String, Set<String>> affected, SettingsCandidateContext recovered) {
          assertEquals(context, recovered);
          assertEquals(Set.of("chatProfile"), affected.get("generative"));
          assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
          return new Prepared() {
            @Override public void validate() {}
            @Override public void install() { installed.incrementAndGet(); }
            @Override public void notifyObservers() {}
            @Override public void retire() {}
            @Override public void abort() {}
          };
        }
      };
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> OperationResult.success("prepared"), () -> false, components);
      var runner = runner(reopened, owner);
      assertEquals(OperationState.RUNNING, reopened.find(key).orElseThrow().state());
      assertEquals(0, installed.get());
      assertTrue(runner.reconcileSettingsAfterComposition());
      assertEquals(1, installed.get());
      assertEquals(OperationState.COMPLETE, reopened.find(key).orElseThrow().state());
      assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
      assertEquals("", settings.load().getLlmModelPath());
    }
  }

  @Test
  void committedTransientProfileWithoutPreparationStaysRunningAndBlocksServing() throws Exception {
    Path db = temp.resolve("profile-missing-preparation.db");
    Path file = temp.resolve("profile-missing-preparation.json");
    String key;
    try (var operations = new SqliteOperationStore(db)) {
      var descriptor = OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
          SettingsCandidatePreparation.OPERATION_REF, "{}", false);
      var row = operations.accept(OperationKeys.generate(CLOCK), descriptor, context(), null).record();
      key = row.key();
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(1, key)));
    }
    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(settings.load())));
      var runner = runner(reopened, owner);
      assertEquals(OperationState.RUNNING, reopened.find(key).orElseThrow().state());
      assertEquals(SettingsCommitOwner.RecoveryReason.INVALID_PREPARATION,
          owner.recoveryIssue().toCompletableFuture().join().reason());
      assertFalse(runner.reconcileSettingsAfterComposition());
      assertEquals(OperationState.RUNNING, reopened.find(key).orElseThrow().state());
    }
  }

  @Test
  void missingRecoveredProfileModelLeavesCommittedRowRunningWithoutBootRestart() throws Exception {
    Path db = temp.resolve("profile-unavailable.db");
    Path file = temp.resolve("profile-unavailable.json");
    String key;
    try (var operations = new SqliteOperationStore(db)) {
      var context = new SettingsCandidateContext(ChatModelProfile.COMPACT);
      var descriptor = OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
          SettingsCandidatePreparation.OPERATION_REF, "{}", false);
      key = OperationKeys.generate(CLOCK);
      var preparation = new io.justsearch.app.api.operations.OperationStore.Preparation(
          java.util.UUID.randomUUID(), SettingsCandidatePreparation.encode(context));
      operations.savePreparation(key, descriptor, preparation);
      var row = operations.acceptPrepared(key, descriptor, context(), null, preparation.nonce()).record();
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(1, key)));
    }
    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> OperationResult.success("prepared"), () -> false,
          new SettingsComponentComposer() {
            @Override public Prepared prepare(UiSettings candidate,
                io.justsearch.configuration.resolved.ResolvedConfig desired,
                Map<String, Set<String>> affected) {
              throw new AssertionError("Recovered candidate context was dropped");
            }
            @Override public Prepared prepare(UiSettings candidate,
                io.justsearch.configuration.resolved.ResolvedConfig desired,
                Map<String, Set<String>> affected, SettingsCandidateContext recovered) {
              assertEquals(new SettingsCandidateContext(ChatModelProfile.COMPACT), recovered);
              throw new IllegalStateException("profile model is missing");
            }
          });
      var runner = runner(reopened, owner);
      assertFalse(runner.reconcileSettingsAfterComposition());
      assertEquals(OperationState.RUNNING, reopened.find(key).orElseThrow().state());
      assertEquals(SettingsCommitOwner.RecoveryReason.COMPOSITION_FAILED,
          owner.recoveryIssue().toCompletableFuture().join().reason());
      assertFalse(runner.reconcileSettingsAfterComposition(), "unresolved boot recovery is not retried in place");
      assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "malformed-nonce", "malformed-envelope"})
  void exactCommittedFileWinsEvenWhenAcceptedPreparationCannotBeDecoded(String variant) throws Exception {
    Path db = temp.resolve("committed-invalid-preparation-" + variant + ".db");
    Path file = temp.resolve("committed-invalid-preparation-" + variant + ".json");
    String key;
    try (var operations = new SqliteOperationStore(db)) {
      var row = row(operations, OperationKind.SETTINGS_APPLY);
      key = row.key();
      operations.start(row.id());
      operations.armSettingsRevision(row.id(), 4);
      writeWitness(new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file), 5, key);
      if (!variant.equals("missing")) {
        String nonce = variant.equals("malformed-nonce")
            ? "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" : java.util.UUID.randomUUID().toString();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
            var update = connection.prepareStatement("UPDATE operations SET preparation_nonce=?, "
                + "preparation_sealed=0, preparation_payload='not-an-envelope' WHERE id=?")) {
          update.setString(1, nonce);
          update.setLong(2, row.id());
          assertEquals(1, update.executeUpdate());
        }
      }
    }
    try (var reopened = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var owner = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
      new OperationAttemptRunnerImpl(reopened, CLOCK, SETTINGS_KINDS, owner);
      assertEquals(OperationState.COMPLETE, reopened.find(key).orElseThrow().state());
      assertEquals(new SettingsWitness(5, key), settings.inspect().witness());
      assertFalse(owner.recoveryIssue().toCompletableFuture().isDone());
    }
  }

  @Test
  void bootUnchangedWitnessFailsAndExactNewWitnessCompletes() throws Exception {
    bootDecision("boot-unchanged", 4, 4, false, OperationState.FAILED,
        "interrupted_before_settings_commit");
    bootDecision("boot-exact", 4, 5, true, OperationState.COMPLETE, null);
  }

  @Test
  void interruptedReconfigureBeforeFileMoveUsesEngineApplyFailureReason() throws Exception {
    Path file = temp.resolve("reconfigure-precommit.json");
    try (var operations = operations("reconfigure-precommit")) {
      var row = row(operations, OperationKind.RECONFIGURE);
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      runner(operations, coordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()))));
      assertEquals(OperationState.FAILED, operations.find(row.key()).orElseThrow().state());
      assertEquals("ENGINE_RESTARTED_DURING_APPLY",
          operations.find(row.key()).orElseThrow().failureReason());
      assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
    }
  }

  @Test
  void bootContradictoryQuarantinedAndInMemoryRowsWaitAndBlockHealth() throws Exception {
    assertBootWait("boot-contradictory", UiSettingsStore.PersistenceMode.READ_WRITE,
        (settings, key) -> writeWitness(settings, 6, OperationKeys.generate(CLOCK)),
        SettingsCommitOwner.RecoveryReason.CONTRADICTORY_WITNESS);
    assertBootWait("boot-quarantined", UiSettingsStore.PersistenceMode.READ_WRITE,
        (settings, key) -> {
          Files.writeString(settings.settingsPath(), "broken");
          settings.load();
        }, SettingsCommitOwner.RecoveryReason.UNREADABLE_WITNESS);
    assertBootWait("boot-memory", UiSettingsStore.PersistenceMode.IN_MEMORY,
        (settings, key) -> {}, SettingsCommitOwner.RecoveryReason.PERSISTENCE_DISABLED);
  }

  @Test
  void multipleArmedRowsBlockTheWholeSettingsSet() throws Exception {
    try (var operations = operations("boot-multiple")) {
      Path path = temp.resolve("boot-multiple.json");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      var first = row(operations, OperationKind.SETTINGS_APPLY);
      var second = row(operations, OperationKind.RECONFIGURE);
      operations.start(first.id());
      operations.start(second.id());
      operations.armSettingsRevision(first.id(), 0);
      operations.armSettingsRevision(second.id(), 0);
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {},
          candidate -> OperationResult.success("prepared"));
      var boundedReads = org.mockito.Mockito.spy(operations);
      org.mockito.Mockito.doThrow(new AssertionError("Multiple armed rows must not load private payloads"))
          .when(boundedReads).acceptedPreparation(org.mockito.Mockito.anyLong());
      new OperationAttemptRunnerImpl(boundedReads, CLOCK, SETTINGS_KINDS, owner);
      org.mockito.Mockito.verify(boundedReads, org.mockito.Mockito.never())
          .acceptedPreparation(org.mockito.Mockito.anyLong());
      assertEquals(SettingsCommitOwner.RecoveryReason.MULTIPLE_ARMED_ROWS,
          owner.recoveryIssue().toCompletableFuture().join().reason());
      assertEquals(OperationState.RUNNING, operations.find(first.key()).orElseThrow().state());
      assertEquals(OperationState.RUNNING, operations.find(second.key()).orElseThrow().state());
      var blocked = assertThrows(SettingsCommitOwner.Refused.class,
          () -> owner.reserve(999, OperationKeys.generate(CLOCK), new SettingsWitness(0, null)));
      assertEquals("SETTINGS_RECOVERY_REQUIRED", blocked.response().errorCode().orElseThrow());
    }
  }

  @Test
  void foreignAndRetiredReservationsCannotBeApplied() throws Exception {
    var path = temp.resolve("foreign.json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
    var first = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
    var second = coordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())));
    first.inspectRecovery(List.of());
    second.inspectRecovery(List.of());
    String key = OperationKeys.generate(CLOCK);
    var reservation = first.reserve(1, key, new SettingsWitness(0, null));
    var control = new RecordingControl();
    assertThrows(IllegalArgumentException.class,
        () -> second.apply(reservation, candidate("dark", List.of()), control));
    first.apply(reservation, candidate("dark", List.of()), control);
    first.releaseAfterTerminal(1);
    assertThrows(IllegalArgumentException.class,
        () -> first.apply(reservation, candidate("light", List.of()), new RecordingControl()));
  }

  @Test
  void cancellationBeforeCommitAdmissionLeavesFileWitnessAndServingConfigUntouched() {
    var path = temp.resolve("cancel-before-commit.json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    var serving = config.get();
    var owner = coordinator(settings, config);
    owner.inspectRecovery(List.of());
    var reservation = owner.reserve(1, OperationKeys.generate(CLOCK), new SettingsWitness(0, null));
    var control = new RecordingControl(false);

    assertThrows(java.util.concurrent.CancellationException.class,
        () -> owner.apply(reservation, candidate("dark", List.of()), control));

    assertFalse(Files.exists(path));
    assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
    assertSame(serving, config.get());
    assertNull(control.receipt);
    owner.releaseAfterTerminal(1);
  }

  @Test
  void processClosingRefusesBeforeReplacingSettings() {
    var path = temp.resolve("closing-before-commit.json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    var owner = new SettingsCommitCoordinator(settings, config, () -> {},
        candidate -> OperationResult.success("prepared"), () -> true);
    owner.inspectRecovery(List.of());
    var reservation = owner.reserve(1, OperationKeys.generate(CLOCK), new SettingsWitness(0, null));

    var refused = assertThrows(SettingsCommitOwner.Refused.class,
        () -> owner.apply(reservation, candidate("dark", List.of()), new RecordingControl()));
    assertEquals("ENGINE_CLOSING", refused.response().errorCode().orElseThrow());
    assertFalse(Files.exists(path));
    assertEquals(new SettingsWitness(0, null), settings.inspect().witness());
    owner.releaseAfterTerminal(1);
  }

  private void bootDecision(String name, long expected, long observed, boolean exact,
      OperationState state, String failureReason) throws Exception {
    try (var operations = operations(name)) {
      Path path = temp.resolve(name + ".json");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
      var row = row(operations, OperationKind.SETTINGS_APPLY);
      operations.start(row.id());
      operations.armSettingsRevision(row.id(), expected);
      writeWitness(settings, observed, exact ? row.key() : OperationKeys.generate(CLOCK));
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {},
          candidate -> OperationResult.success("prepared"));
      new OperationAttemptRunnerImpl(operations, CLOCK, SETTINGS_KINDS, owner);
      var current = operations.find(row.key()).orElseThrow();
      assertEquals(state, current.state());
      if (failureReason == null) assertNull(current.failureReason());
      else assertEquals(failureReason, current.failureReason());
    }
  }

  @FunctionalInterface
  private interface BootSetup { void apply(UiSettingsStore settings, String key) throws Exception; }

  private void assertBootWait(String name, UiSettingsStore.PersistenceMode mode,
      BootSetup setup, SettingsCommitOwner.RecoveryReason reason) throws Exception {
    try (var operations = operations(name)) {
      Path path = temp.resolve(name + ".json");
      var settings = new UiSettingsStore(mode, path);
      var row = row(operations, OperationKind.SETTINGS_APPLY);
      operations.start(row.id());
      operations.armSettingsRevision(row.id(), 4);
      setup.apply(settings, row.key());
      AtomicInteger restarts = new AtomicInteger();
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), restarts::incrementAndGet,
          candidate -> OperationResult.success("prepared"));
      new OperationAttemptRunnerImpl(operations, CLOCK, SETTINGS_KINDS, owner);
      assertEquals(reason, owner.recoveryIssue().toCompletableFuture().join().reason());
      assertEquals(OperationState.RUNNING, operations.find(row.key()).orElseThrow().state());
      assertEquals(0, restarts.get());
    }
  }

  private static void writeWitness(UiSettingsStore settings, long revision, String key) throws Exception {
    var prepared = settings.prepare(candidate("dark", List.of()),
        new SettingsWitness(revision, revision == 0 ? null : key));
    settings.replacePrepared(prepared);
  }

  private SettingsCommitCoordinator coordinator(UiSettingsStore settings, ConfigStore config) {
    return new SettingsCommitCoordinator(settings, config, () -> {},
        candidate -> OperationResult.success("prepared"));
  }

  private static SettingsWitness currentWitness(UiSettingsStore settings) {
    return settings.inspect().witness();
  }

  private OperationRecord unarmedProbeRow(String name) throws Exception {
    try (var operations = operations(name)) {
      return row(operations, OperationKind.SETTINGS_APPLY);
    }
  }

  private SqliteOperationStore operations(String name) throws Exception {
    return new SqliteOperationStore(temp.resolve("operations-" + name + ".db"));
  }

  private static OperationAttemptRunnerImpl runner(SqliteOperationStore operations,
      SettingsCommitCoordinator owner) {
    return new OperationAttemptRunnerImpl(operations, CLOCK, SETTINGS_KINDS, owner);
  }

  private static OperationAttemptRunner.Request request(OperationKind kind) {
    return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
        new OperationDescriptor(kind, "core.settings", "{}"), context(), null);
  }

  private static OperationRecord row(SqliteOperationStore operations, OperationKind kind) {
    return operations.accept(OperationKeys.generate(CLOCK),
        new OperationDescriptor(kind, "core.settings", "{}"), context(), null).record();
  }

  private static EngineContext context() {
    return new EngineContext(EngineContext.ClientKind.INTERNAL, "settings-test", Optional.empty(),
        Optional.empty(), "system", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.BACKGROUND);
  }

  private static UiSettings candidate(String theme, List<String> excludes) {
    var candidate = new UiSettings();
    candidate.setTheme(theme);
    candidate.setExcludePatterns(excludes);
    return candidate;
  }

  private static final class RecordingControl implements SettingsCommitOwner.AttemptControl {
    private SettingsCommitOwner.Receipt receipt;

    private final boolean admit;

    private RecordingControl() { this(true); }

    private RecordingControl(boolean admit) { this.admit = admit; }

    @Override public boolean admitCommit(SettingsCommitOwner.Receipt receipt) { return admit; }
    @Override public void committed(SettingsCommitOwner.Receipt receipt) { this.receipt = receipt; }
    @Override public void uncertain() {}
  }
}
