/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CandidateIndexTargetCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Boot projection proof for an accepted installer generation after the pointer cut. */
final class RecordedInstallerGenerationBootProjectionTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
  private static final UUID NONCE = UUID.fromString("00000000-0000-4000-8000-000000000121");
  private static final String SOURCE = "installer_model_activation";
  private static final String ARGUMENTS = "{\"source\":\"installer_model_activation\"}";

  @TempDir Path temp;

  @Test
  void pointerBeforeCommitLeavesAAndDoesNotInstallCandidateSettings() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("before-pointer"))) {
      UiSettings returned = fixture.reconcile();

      assertSameSettings(fixture.priorSettings, returned);
      assertEquals(fixture.sourceGeneration,
          new IndexGenerationManager(fixture.indexBase).inspectCurrentLayoutForBoot()
              .orElseThrow().activeGenerationId());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
      assertTrue(Files.exists(fixture.settingsPath));
      assertSameSettings(fixture.priorSettings, fixture.settings.inspect().settings());
    }
  }

  @Test
  void committedBRollsForwardExactCandidateAndSecondBootIsIdempotent() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("committed-pointer"))) {
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();

      UiSettings first = fixture.reconcile();
      assertSameSettings(fixture.candidateSettings, first);
      assertEquals(new SettingsWitness(1, fixture.operationKey), fixture.settings.inspect().witness());
      assertSameSettings(fixture.candidateSettings, fixture.settings.inspect().settings());

      UiSettings second = fixture.reconcile();
      assertSameSettings(fixture.candidateSettings, second);
      assertEquals(new SettingsWitness(1, fixture.operationKey), fixture.settings.inspect().witness());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "authority"})
  void committedV4PointerRollsForwardFrozenSourceSet(String sourceId) throws Exception {
    List<String> sources = sourceId.isEmpty() ? List.of() : List.of(sourceId);
    try (Fixture fixture = new Fixture(temp.resolve("committed-v4-" +
        (sourceId.isEmpty() ? "empty" : "one")), false, false, sources)) {
      assertEquals(RecordedInstallerGenerationPlan.SCHEMA_V4, fixture.plan.replaySchema());
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      assertSameSettings(fixture.candidateSettings, fixture.reconcile());
      assertEquals(new SettingsWitness(1, fixture.operationKey), fixture.settings.inspect().witness());
      assertSameSettings(fixture.candidateSettings, fixture.reconcile());
    }
  }

  @Test
  void committedBWithChangedSelectedChatBytesStaysFencedWithoutRollback() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("chat-drift-after-pointer"), true)) {
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      Files.writeString(fixture.chatPath, "CHAT-model", StandardCharsets.UTF_8);

      IOException failure = assertThrows(IOException.class, fixture::reconcile);
      assertTrue(failure.getMessage().contains("assets changed"));
      assertEquals("g-" + fixture.operationKey,
          new IndexGenerationManager(fixture.indexBase).inspectCurrentLayoutForBoot()
              .orElseThrow().activeGenerationId(), "committed B must not roll back");
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
      assertSameSettings(fixture.priorSettings, fixture.settings.inspect().settings());
      assertEquals(OperationState.RUNNING,
          fixture.operations.find(fixture.operationKey).orElseThrow().state(),
          "fenced recovery must not mark the committed operation complete or failed");
    }
  }

  @Test
  void legacyV2WithoutChatKeepsAThenRollsForwardCommittedB() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("legacy-v2-no-chat"), false, true)) {
      assertEquals(RecordedInstallerGenerationPlan.LEGACY_SCHEMA_V2,
          fixture.plan.replaySchema());
      assertSameSettings(fixture.priorSettings, fixture.reconcile());
      assertEquals(fixture.sourceGeneration,
          new IndexGenerationManager(fixture.indexBase).inspectCurrentLayoutForBoot()
              .orElseThrow().activeGenerationId());
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      assertSameSettings(fixture.candidateSettings, fixture.reconcile());
      assertEquals(new SettingsWitness(1, fixture.operationKey), fixture.settings.inspect().witness());
    }
  }

  @Test
  void legacyV2UnprovenChatAfterPointerStaysFencedWithoutFalseTerminal() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("legacy-v2-chat"), true, true)) {
      assertEquals(RecordedInstallerGenerationPlan.LEGACY_SCHEMA_V2,
          fixture.plan.replaySchema());
      assertThrows(IllegalArgumentException.class,
          () -> io.justsearch.app.services.registry.executor.RecordedInstallerAssetVerifier
              .verify(fixture.plan));
      assertEquals(fixture.sourceGeneration,
          new IndexGenerationManager(fixture.indexBase).inspectCurrentLayoutForBoot()
              .orElseThrow().activeGenerationId());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());

      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      IOException failure = assertThrows(IOException.class, fixture::reconcile);
      assertTrue(failure.getMessage().contains("assets changed"));
      assertEquals("g-" + fixture.operationKey,
          new IndexGenerationManager(fixture.indexBase).inspectCurrentLayoutForBoot()
              .orElseThrow().activeGenerationId());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
      assertEquals(OperationState.RUNNING,
          fixture.operations.find(fixture.operationKey).orElseThrow().state());
    }
  }

  @Test
  void unrelatedPointerSourceFailsClosedWithoutChangingSettings() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("unrelated-pointer"))) {
      var manager = new IndexGenerationManager(fixture.indexBase);
      manager.promoteBuildingGenerationToActive();
      manager.retirePreviousGeneration("g-" + fixture.operationKey, fixture.sourceGeneration);
      manager.startRecordedMigration(fixture.otherKey, SOURCE, fixture.target.fingerprint(),
          "g-" + fixture.operationKey);
      manager.promoteBuildingGenerationToActive();

      IOException failure = assertThrows(IOException.class, fixture::reconcile);
      assertTrue(failure.getMessage().contains("unrelated source"));
      assertEquals("g-" + fixture.otherKey,
          manager.inspectCurrentLayoutForBoot().orElseThrow().activeGenerationId());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
      assertTrue(Files.exists(fixture.settingsPath));
      assertSameSettings(fixture.priorSettings, fixture.settings.inspect().settings());
    }
  }

  @Test
  void unrelatedSettingsWitnessFailsClosedAfterB() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("unrelated-witness"))) {
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      UiSettings unrelated = new UiSettings();
      unrelated.setIndexBasePath(fixture.indexBase.toString());
      unrelated.setTheme("light");
      fixture.settings.replacePrepared(fixture.settings.prepareExact(unrelated,
          new SettingsWitness(1, fixture.otherKey)));

      IOException failure = assertThrows(IOException.class, fixture::reconcile);
      assertTrue(failure.getMessage().contains("witness is unrelated"));
      assertEquals(new SettingsWitness(1, fixture.otherKey), fixture.settings.inspect().witness());
      assertSameSettings(unrelated, fixture.settings.inspect().settings());
    }
  }

  @Test
  void successorWitnessWithWrongSettingsFailsClosedAsAmbiguousMove() throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("ambiguous-settings"))) {
      new IndexGenerationManager(fixture.indexBase).promoteBuildingGenerationToActive();
      UiSettings wrong = new UiSettings();
      wrong.setIndexBasePath(fixture.indexBase.toString());
      wrong.setTheme("system");
      fixture.settings.replacePrepared(fixture.settings.prepareExact(wrong,
          new SettingsWitness(1, fixture.operationKey)));

      IOException failure = assertThrows(IOException.class, fixture::reconcile);
      assertTrue(failure.getMessage().contains("successor settings differ"));
      assertEquals(new SettingsWitness(1, fixture.operationKey), fixture.settings.inspect().witness());
      assertSameSettings(wrong, fixture.settings.inspect().settings());
    }
  }

  private static void assertSameSettings(UiSettings expected, UiSettings actual) {
    assertEquals(expected.getTheme(), actual.getTheme());
    assertEquals(expected.getIndexBasePath(), actual.getIndexBasePath());
    assertEquals(expected.getEmbedOnnxModelPath(), actual.getEmbedOnnxModelPath());
  }

  private static final class Fixture implements AutoCloseable {
    private final Path indexBase;
    private final Path settingsPath;
    private final SqliteOperationStore operations;
    private final UiSettingsStore settings;
    private final UiSettings priorSettings;
    private final UiSettings candidateSettings;
    private final ResolvedConfig preliminaryConfig;
    private final IndexTargetSnapshot target;
    private final String sourceGeneration;
    private final String operationKey;
    private final String otherKey;
    private final Path chatPath;
    private final RecordedInstallerGenerationPlan plan;

    Fixture(Path root) throws Exception {
      this(root, false, false);
    }

    Fixture(Path root, boolean selectChat) throws Exception {
      this(root, selectChat, false);
    }

    Fixture(Path root, boolean selectChat, boolean legacy) throws Exception {
      this(root, selectChat, legacy, null);
    }

    Fixture(Path root, boolean selectChat, boolean legacy, List<String> sourceIds) throws Exception {
      indexBase = root.resolve("index").toAbsolutePath().normalize();
      settingsPath = root.resolve("ui-settings.json").toAbsolutePath().normalize();
      Files.createDirectories(root);
      operationKey = OperationKeys.generate(Clock.systemUTC());
      otherKey = OperationKeys.generate(Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
      priorSettings = settings("light", indexBase);
      candidateSettings = settings("dark", indexBase);
      chatPath = root.resolve("chat.gguf").toAbsolutePath().normalize();
      if (selectChat) candidateSettings.setLlmModelPath(chatPath.toString());
      preliminaryConfig = ConfigStoreRebuilder.prepare(priorSettings);
      ResolvedConfig candidateConfig = ConfigStoreRebuilder.prepare(candidateSettings);
      target = CandidateIndexTargetCapture.capture(candidateConfig);

      var generations = new IndexGenerationManager(indexBase);
      sourceGeneration = generations.initializeOrLoad().activeGenerationId();
      generations.startRecordedMigration(operationKey, SOURCE, target.fingerprint(),
          sourceGeneration, sourceIds);

      Path modelPath = root.resolve("fresh-model.onnx");
      Path assetPath = root.resolve("fresh-model.tokenizer");
      Files.writeString(modelPath, "fresh model bytes", StandardCharsets.UTF_8);
      Files.writeString(assetPath, "fresh asset bytes", StandardCharsets.UTF_8);
      var provenance = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
          RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY,
          "test-registry", sha256("test-manifest"));
      var model = new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "fp32",
          modelPath, sha256(modelPath), Files.size(modelPath), provenance);
      var asset = new RecordedInstallerGenerationPlan.AssetIdentity("tokenizer", assetPath,
          sha256(assetPath), Files.size(assetPath), provenance);
      List<RecordedInstallerGenerationPlan.AssetIdentity> assets = new java.util.ArrayList<>();
      assets.add(asset);
      RecordedInstallerGenerationPlan.ChatSelection chatSelection =
          RecordedInstallerGenerationPlan.ChatSelection.none();
      if (selectChat) {
        Files.writeString(chatPath, "chat-model", StandardCharsets.UTF_8);
        Path companionPath = root.resolve("mmproj.gguf").toAbsolutePath().normalize();
        Files.writeString(companionPath, "chat-asset", StandardCharsets.UTF_8);
        assets.add(new RecordedInstallerGenerationPlan.AssetIdentity("chat/chat.gguf",
            chatPath, sha256(chatPath), Files.size(chatPath), provenance));
        assets.add(new RecordedInstallerGenerationPlan.AssetIdentity("chat/mmproj.gguf",
            companionPath, sha256(companionPath), Files.size(companionPath), provenance));
        chatSelection = RecordedInstallerGenerationPlan.ChatSelection.selected(
            "chat/chat.gguf", List.of("chat/mmproj.gguf"));
      }
      var scope = new RecordedRootPlan(sourceGeneration, List.of(
          new RecordedRootPlan.Root(root.resolve("documents"), "documents", true, false,
              List.of(), List.of())));
      plan = new RecordedInstallerGenerationPlan(
          RecordedInstallerGenerationPlan.OPERATION_ID,
          RecordedInstallerGenerationPlan.Profile.INSTALLER_GENERATION,
          RecordedInstallerGenerationPlan.SOURCE,
          operationKey, sourceGeneration, scope, target, new SettingsWitness(0, null),
          RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
              tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(candidateSettings)),
          List.of(model), assets, legacy ? null : chatSelection, provenance, sourceIds);

      operations = new SqliteOperationStore(root.resolve("operations.db"));
      settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      settings.replacePrepared(settings.prepareExact(priorSettings, new SettingsWitness(0, null)));
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK, Set.of(OperationKind.REINDEX));
      EngineContextData accepted = new EngineContextData(operationKey);
      var request = new OperationAttemptRunner.Request(operationKey,
          OperationDescriptor.invocation(OperationKind.REINDEX,
              RecordedInstallerGenerationPlan.OPERATION_ID, ARGUMENTS, false), accepted.context,
          accepted.provenance);
      OperationPreparation preparation = new OperationPreparation(ARGUMENTS,
          plan.replaySchema(), plan.toReplayPayload(),
          OperationPreparation.Content.METADATA);
      runner.savePreparation(request, RecordedParentFixture.prepare(request, NONCE, preparation));
      var acceptedAttempt = runner.acceptPrepared(request, NONCE);
      assertEquals(OperationState.ACCEPTED, acceptedAttempt.accepted().state());
      assertTrue(operations.start(acceptedAttempt.accepted().id()));
      assertTrue(operations.armInstallerGenerationSettingsRevision(
          acceptedAttempt.accepted().id(), 0));
    }

    UiSettings reconcile() throws IOException {
      return EngineRoot.reconcileInstallerGenerationBoot(operations, settings, priorSettings,
          preliminaryConfig);
    }

    @Override public void close() throws Exception {
      operations.close();
    }

    private static UiSettings settings(String theme, Path indexBase) {
      UiSettings settings = new UiSettings();
      settings.setTheme(theme);
      settings.setIndexBasePath(indexBase.toString());
      return settings;
    }
  }

  private static final class EngineContextData {
    private final io.justsearch.core.context.EngineContext context;
    private final io.justsearch.agent.api.registry.InvocationProvenance provenance;

    EngineContextData(String key) {
      context = EngineProvenance.context(io.justsearch.core.context.EngineContext.ClientKind.INTERNAL,
          "installer-boot-test", Optional.empty(), Optional.of(new OperationAuthorizationBasis
              .PreparedContinuation(key, NONCE).encode()), TransportTag.SYSTEM_INTERNAL,
          io.justsearch.core.context.EngineContext.Survival.DURABLE,
          io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
      provenance = EngineProvenance.invocation(context, ExecutorTag.UI, OCCURRED_AT,
          Optional.empty());
    }
  }

  private static String sha256(Path path) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(path)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
