/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** One Worker capture of a candidate target and its exact index-model files. */
public record CandidateIndexSelection(
    IndexTargetSnapshot target, Map<String, ModelFile> models) {
  public CandidateIndexSelection {
    Objects.requireNonNull(target, "target");
    models = Map.copyOf(Objects.requireNonNull(models, "models"));
    if (models.size() > 128 || models.keySet().stream().anyMatch(role ->
        !role.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
      throw new IllegalArgumentException("Candidate index model roles are invalid");
    }
  }

  /** File identity revalidated by the accepted activation before effect admission. */
  public record ModelFile(Path path, String sha256, long sizeBytes) {
    public ModelFile {
      Objects.requireNonNull(path, "path");
      if (!path.isAbsolute() || !path.normalize().equals(path)
          || sha256 == null || !sha256.matches("[0-9a-f]{64}") || sizeBytes < 0) {
        throw new IllegalArgumentException("Candidate index model file identity is invalid");
      }
    }
  }
}
