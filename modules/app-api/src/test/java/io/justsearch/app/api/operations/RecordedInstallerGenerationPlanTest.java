/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RecordedInstallerGenerationPlanTest {
  private static final Path ROOT = Path.of("installer-generation-plan").toAbsolutePath().normalize();
  private static final String HASH = "a".repeat(64);

  @Test
  void roundTripsTheCompleteFrozenCandidateAndDetachesCollections() {
    var plan = plan();
    String payload = plan.toReplayPayload();
    var restored = RecordedInstallerGenerationPlan.fromReplayPayload(payload);

    assertEquals(plan, restored);
    assertEquals(plan.planHash(), restored.planHash());
    assertEquals("core.activate-installed-models", restored.operationId());
    assertEquals("installer_model_activation", restored.source());
    assertThrows(UnsupportedOperationException.class, () -> restored.models().clear());
  }

  @Test
  void fixesOperationProfileSourceAndGenerationBindings() {
    assertThrows(IllegalArgumentException.class, () -> new RecordedInstallerGenerationPlan(
        "core.bulk-reindex", RecordedInstallerGenerationPlan.Profile.INSTALLER_GENERATION,
        "installer_model_activation", key(), "g1", scope(), target(), witness(), candidate(),
        List.of(model("embedding")), List.of(asset("embedding")), provenance()));
    assertThrows(IllegalArgumentException.class, () -> new RecordedInstallerGenerationPlan(
        key(), "other-generation", scope(), target(), witness(), candidate(),
        List.of(model("embedding")), List.of(asset("embedding")), provenance()));
  }

  @Test
  void strictReplayRejectsUnknownMissingDuplicateAndSchemaFields() {
    String valid = plan().toReplayPayload();
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.fromReplayPayload(
        valid.replace("\"source\":\"installer_model_activation\"", "\"unexpected\":true,\"source\":\"installer_model_activation\"")));
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.fromReplayPayload(
        valid.replace("\"source\":\"installer_model_activation\"", "\"source\":\"installer_model_activation\",\"source\":\"installer_model_activation\"")));
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.fromReplayPayload(
        valid.replace("\"source\":\"installer_model_activation\",", "")));
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.fromReplayPayload(
        "recorded-installer-generation-v1", valid));
  }

  @Test
  void rejectsTamperedSettingsHashesRuntimeCredentialsAndNonCanonicalJson() {
    var valid = plan().toReplayPayload();
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.fromReplayPayload(
        valid.replace(candidate().sha256(), "b".repeat(64))));
    assertThrows(IllegalArgumentException.class, () -> RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
        "{\"runtimeConfig\":{\"port\":1}}"));
    assertThrows(IllegalArgumentException.class, () -> new RecordedInstallerGenerationPlan.CandidateSettings(
        sha256("{\"z\":1,\"a\":2}"), "{ \"z\" : 1, \"a\" : 2 }"));
  }

  @Test
  void rejectsBadIdentityHashSizeAndPathNormalization() {
    assertThrows(IllegalArgumentException.class, () -> modelWith(HASH, 1, Path.of("relative-model.onnx")));
    assertThrows(IllegalArgumentException.class, () -> modelWith("A".repeat(64), 1, ROOT.resolve("x/../model.onnx")));
    assertThrows(IllegalArgumentException.class, () -> modelWith(HASH, -1, ROOT.resolve("model.onnx")));
    assertThrows(IllegalArgumentException.class, () -> new RecordedInstallerGenerationPlan.AssetIdentity(
        "asset", ROOT.resolve("asset.bin"), "A".repeat(64), 1, provenance()));
  }

  @Test
  void candidateSettingsAreBoundedAndHashedByUtf8Bytes() {
    String oversized = "{\"value\":\"" + "é".repeat(RecordedInstallerGenerationPlan.MAX_CANDIDATE_SETTINGS_BYTES) + "\"}";
    assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length > RecordedInstallerGenerationPlan.MAX_CANDIDATE_SETTINGS_BYTES);
    assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerGenerationPlan.CandidateSettings.fromJson(oversized));
  }

  @Test
  void executorBindingTurnsOnlyTheExactUnboundV2CandidateIntoAContinuation() {
    String key = key();
    var unbound = new RecordedInstallerGenerationPlan("g1", scope(), target(), witness(), candidate(),
        List.of(model("embedding")), List.of(asset("embedding")), provenance());
    assertNull(unbound.operationKey());
    var preparation = new OperationPreparation("{}", RecordedInstallerGenerationPlan.SCHEMA,
        unbound.toReplayPayload(), OperationPreparation.Content.METADATA);

    assertFalse(RecordedInstallerGenerationPlan.continuationPreparation(operation(), preparation));
    var bound = RecordedInstallerGenerationPlan.bindOperationKey(operation(), preparation, key);
    assertEquals(key, RecordedInstallerGenerationPlan.fromReplayPayload(
        bound.replayPayloadJson()).operationKey());
    assertTrue(RecordedInstallerGenerationPlan.continuationPreparation(operation(), bound, key));
    assertFalse(RecordedInstallerGenerationPlan.continuationPreparation(operation(), bound, key()));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerGenerationPlan.bindOperationKey(operation(), bound, key()));
  }

  private static Operation operation() {
    var id = new OperationRef(RecordedInstallerGenerationPlan.OPERATION_ID);
    return new Operation(id,
        Presentation.of(new I18nKey("test.activate"), new I18nKey("test.activate.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(), Set.of(), false)
            .withRecordKind(OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
        Provenance.core("1.0"), Set.of(ExecutorTag.UI));
  }

  private static RecordedInstallerGenerationPlan plan() {
    return new RecordedInstallerGenerationPlan(key(), "g1", scope(), target(), witness(), candidate(),
        List.of(model("embedding")), List.of(asset("embedding")), provenance());
  }

  private static RecordedRootPlan scope() {
    return new RecordedRootPlan("g1", List.of(new RecordedRootPlan.Root(ROOT, "documents", true,
        false, List.of(), List.of())));
  }

  private static IndexTargetSnapshot target() {
    String inputs = "{\"dimension\":768}";
    return new IndexTargetSnapshot(sha256(inputs), inputs);
  }

  private static SettingsWitness witness() { return new SettingsWitness(3, key()); }

  private static RecordedInstallerGenerationPlan.CandidateSettings candidate() {
    return RecordedInstallerGenerationPlan.CandidateSettings.fromJson("{\"indexPaths\":[],\"models\":{}}");
  }

  private static RecordedInstallerGenerationPlan.ModelIdentity model(String id) {
    return new RecordedInstallerGenerationPlan.ModelIdentity(id, "fp32", ROOT.resolve(id + ".onnx"), HASH,
        1, provenance());
  }

  private static RecordedInstallerGenerationPlan.ModelIdentity modelWith(String hash, long size, Path path) {
    return new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "fp32", path, hash, size, provenance());
  }

  private static RecordedInstallerGenerationPlan.AssetIdentity asset(String id) {
    return new RecordedInstallerGenerationPlan.AssetIdentity(id, ROOT.resolve(id + ".bin"), HASH, 1, provenance());
  }

  private static RecordedInstallerGenerationPlan.AcquisitionProvenance provenance() {
    return new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY, "manifest-1", HASH);
  }

  private static String key() {
    return OperationKeys.generate(java.time.Clock.systemUTC());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
