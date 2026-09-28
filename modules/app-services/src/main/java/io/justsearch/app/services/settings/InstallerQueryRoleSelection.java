/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.settings.QueryRoleSelection;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Projects complete query identities from an accepted generation plan into settings v5. */
final class InstallerQueryRoleSelection {
  private InstallerQueryRoleSelection() {}

  static Optional<QueryRoleSelection> fromPlan(RecordedInstallerGenerationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    UiSettings candidate = tools.jackson.databind.json.JsonMapper.builder().build()
        .readValue(plan.candidateSettings().canonicalJson(), UiSettings.class);
    // The plan has no explicit DISABLED intent. A missing role with a blank UI path may still
    // be active through the generation manifest, so only two complete selected roles can replace
    // that manifest's authority.
    if (plan.models().stream().noneMatch(model -> "reranker".equals(model.packageId()))
        || plan.models().stream().noneMatch(model ->
            "citation-scorer".equals(model.packageId()))) return Optional.empty();
    Optional<QueryRoleSelection.Role> reranker = role(plan, "reranker",
        candidate.getRerankerModelPath());
    Optional<QueryRoleSelection.Role> citation = role(plan, "citation-scorer",
        candidate.getCitationScorerModelPath());
    if (reranker.isEmpty() || citation.isEmpty()) return Optional.empty();
    return Optional.of(new QueryRoleSelection(reranker.orElseThrow(), citation.orElseThrow()));
  }

  private static Optional<QueryRoleSelection.Role> role(RecordedInstallerGenerationPlan plan,
      String packageId, String desiredPath) {
    var selected = plan.models().stream()
        .filter(model -> packageId.equals(model.packageId())).findFirst();
    if (selected.isEmpty()) return Optional.empty();
    var model = selected.orElseThrow();
    Path directory = model.path().getParent();
    if (desiredPath == null || desiredPath.isBlank()
        || !Path.of(desiredPath).toAbsolutePath().normalize().equals(directory)) {
      throw new IllegalArgumentException("Query model path differs from accepted plan: "
          + packageId);
    }
    Path tokenizerPath = directory.resolve("tokenizer.json");
    var tokenizer = plan.assets().stream()
        .filter(asset -> (packageId + "/tokenizer.json").equals(asset.assetId()))
        .findFirst();
    if (tokenizer.isEmpty()) return Optional.empty();
    var asset = tokenizer.orElseThrow();
    if (!tokenizerPath.equals(asset.path())) {
      throw new IllegalArgumentException("Query tokenizer in accepted plan is outside model directory: "
          + packageId);
    }
    return Optional.of(QueryRoleSelection.Role.selected(model.variantId(),
        new ModelFile(model.path(), model.sha256(), model.sizeBytes()),
        new ModelFile(asset.path(), asset.sha256(), asset.sizeBytes())));
  }
}
