/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QueryRoleSelectionResolverTest {
  @Test
  void environmentModelPathOverridesCommittedDisablementOnlyForThisBoot(
      @TempDir Path directory) throws Exception {
    Path model = Files.writeString(directory.resolve("model.onnx"), "override model");
    Path tokenizer = Files.writeString(directory.resolve("tokenizer.json"), "override tokenizer");
    String key = EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey();
    var resolved = new ResolvedConfigBuilder()
        .putSettings(key, directory.resolve("unused-desired").toString())
        .put(key, ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var",
            EnvRegistry.CITATION_SCORER_MODEL_PATH.envVar(), directory.toString())
        .build();
    var committed = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());

    var result = QueryRoleSelectionResolver.forBoot(committed, resolved, null);
    var effective = result.selection();

    assertTrue(result.unavailableRoles().isEmpty());
    assertEquals(QueryRoleSelection.State.DISABLED, effective.reranker().state());
    assertEquals(QueryRoleSelection.State.SELECTED, effective.citation().state());
    assertEquals(model, effective.citation().model().path());
    assertEquals(tokenizer, effective.citation().tokenizer().path());
    assertNotNull(effective.citation().precision());
    assertEquals(QueryRoleSelection.State.DISABLED, committed.citation().state());
  }

  @Test
  void missingOperatorModelLeavesOnlyThatRoleUnavailable(@TempDir Path directory) {
    String key = EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey();
    var resolved = new ResolvedConfigBuilder()
        .put(key, ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var",
            EnvRegistry.CITATION_SCORER_MODEL_PATH.envVar(),
            directory.resolve("missing").toString())
        .build();
    var committed = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());

    var result = QueryRoleSelectionResolver.forBoot(committed, resolved, null);

    assertEquals(QueryRoleSelection.State.DISABLED, result.selection().reranker().state());
    assertEquals(QueryRoleSelection.State.DISABLED, result.selection().citation().state());
    assertEquals(java.util.Set.of(EncoderRole.CITATION), result.unavailableRoles().keySet());
    assertEquals(QueryRoleSelection.State.DISABLED, committed.citation().state());
  }

  @Test
  void nonOverriddenGenerationRoleSurvivesAbsentDesiredPath(@TempDir Path directory)
      throws Exception {
    Path model = Files.writeString(directory.resolve("model.onnx"), "active generation model");
    Files.writeString(directory.resolve("tokenizer.json"), "active tokenizer");
    String sha = GenerationModelSelection.captureIdentity(model).sha256();
    var generation = GenerationModelSelection.accepted(
        java.util.Map.of("reranker", new ModelArtifact(model.toString(), sha)), "splade", 768);

    var selected = QueryRoleSelectionResolver.select("reranker", false, null, null,
        generation, false, false);

    assertEquals(QueryRoleSelection.State.SELECTED, selected.state());
    assertEquals(model, selected.model().path());
  }
}
