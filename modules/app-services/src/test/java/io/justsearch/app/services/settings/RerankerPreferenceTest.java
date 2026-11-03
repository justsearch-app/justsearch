/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class RerankerPreferenceTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void patchRetainsFilesAndIdentityUntilTheUserChangesThePath(@TempDir Path dir) {
    var settings = new UiSettings();
    settings.setRerankerModelPath(dir.toString());
    var exact = role(dir);
    settings.setRerankerRestoreSelection(exact);
    settings.setChatEnabled(true);
    SettingsPatch.merge(settings, SettingsPatch.normalize(
        json.readValue("{\"rerankerEnabled\":false}", SettingsV2.class)), true);
    assertEquals(Boolean.FALSE, settings.getRerankerEnabled());
    assertEquals(dir.toString(), settings.getRerankerModelPath());
    assertEquals(exact, settings.getRerankerRestoreSelection());
    assertEquals(Boolean.TRUE, settings.getChatEnabled());
    assertFalse(ConfigStoreRebuilder.prepare(settings).ai().reranker().enabled());
    assertEquals(Boolean.FALSE, SettingsV2Projection.toSettingsV2(settings,
        UiSettingsStore.PersistenceMode.READ_WRITE).rerankerEnabled());
    SettingsPatch.merge(settings, json.readValue("{\"rerankerModelPath\":\"different\"}",
        SettingsV2.class), true);
    assertNull(settings.getRerankerRestoreSelection());
    assertEquals(Boolean.FALSE, settings.getRerankerEnabled());
  }

  @Test
  void currentEnvelopeReopensOffAndExactSelectionAfterRestart(@TempDir Path dir) throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var candidate = new UiSettings();
    candidate.setRerankerEnabled(false);
    candidate.setRerankerModelPath(dir.toString());
    candidate.setRerankerRestoreSelection(role(dir));
    var witness = new SettingsWitness(1, OperationKeys.generate(Clock.systemUTC()));
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    store.replacePrepared(store.prepare(candidate, witness, disabled));
    var after = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        store.settingsPath()).inspect();
    assertEquals(witness, after.witness());
    assertEquals(Boolean.FALSE, after.settings().getRerankerEnabled());
    assertEquals(candidate.getRerankerRestoreSelection(), after.settings().getRerankerRestoreSelection());
    assertEquals(disabled, after.queryRoles());
    assertEquals(7, json.readTree(Files.readString(store.settingsPath())).get("schemaVersion").intValue());
  }

  @Test
  void schemaSixMigrationKeepsDisabledWitnessAndAcquisitionPreferences(@TempDir Path dir)
      throws Exception {
    var file = dir.resolve("settings.json");
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    Files.writeString(file, "{\"schemaVersion\":6,\"acceptedRevision\":0,"
        + "\"lastCommittedOperationKey\":null,\"queryRoles\":" + json.writeValueAsString(disabled)
        + ",\"settings\":{\"declinedAiPackages\":[\"reranker\"],\"chatEnabled\":false}}");
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
    var snapshot = store.inspect();
    assertNull(snapshot.settings().getRerankerEnabled());
    assertEquals(disabled, snapshot.queryRoles());
    assertEquals(java.util.List.of("reranker"), snapshot.settings().getDeclinedAiPackages());
    assertEquals(Boolean.FALSE, snapshot.settings().getChatEnabled());
    assertTrue(store.lastRecovery().isEmpty());
  }

  private static QueryRoleSelection.Role role(Path dir) {
    return QueryRoleSelection.Role.selected("custom-fp16.onnx",
        new ModelFile(dir.resolve("custom-fp16.onnx"), "a".repeat(64), 11),
        new ModelFile(dir.resolve("tokenizer.json"), "b".repeat(64), 12),
        ModelPrecision.FP16, ExecutionProvider.CUDA);
  }
}
