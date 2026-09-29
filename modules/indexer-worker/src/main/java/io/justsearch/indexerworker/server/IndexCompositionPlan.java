/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.RuntimePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, process-local authority for one index encoder native composition attempt. */
record IndexCompositionPlan(
    EncoderConfigurationProjection projection,
    HardwareProfile hardware,
    RuntimePolicy runtimePolicy,
    Map<EncoderRole, RolePlan> roles,
    boolean bgeM3Selected) {

  IndexCompositionPlan {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(hardware, "hardware");
    Objects.requireNonNull(runtimePolicy, "runtimePolicy");
    roles = Map.copyOf(Objects.requireNonNull(roles, "roles"));
  }

  RolePlan role(EncoderRole role) {
    return roles.getOrDefault(role, RolePlan.disabled());
  }

  /** Refuses changed bytes, disappeared inputs, and newly appeared optional inputs. */
  void validateInputs() throws IOException {
    for (Map.Entry<EncoderRole, RolePlan> entry : roles.entrySet()) {
      for (InputWitness input : entry.getValue().inputs()) {
        if (!input.matches()) {
          throw new IOException(
              "Captured " + entry.getKey() + " input changed before native assembly: "
                  + input.path());
        }
      }
    }
  }

  boolean complete() {
    return roles.values().stream().noneMatch(RolePlan::captureFailed);
  }

  record RolePlan(
      boolean requested,
      VariantSelection variant,
      ModelSessionPolicy policy,
      Path metadataDirectory,
      List<InputWitness> inputs,
      String captureFailure) {

    RolePlan {
      inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
      if ((variant == null) != (policy == null)) {
        throw new IllegalArgumentException("Variant and session policy must be captured together");
      }
      if (variant != null) {
        Objects.requireNonNull(metadataDirectory, "metadataDirectory");
      }
    }

    static RolePlan disabled() {
      return new RolePlan(false, null, null, null, List.of(), null);
    }

    static RolePlan unavailable(boolean requested) {
      return new RolePlan(requested, null, null, null, List.of(), null);
    }

    static RolePlan captureFailed(boolean requested, Exception failure) {
      return new RolePlan(requested, null, null, null, List.of(), failure.toString());
    }

    boolean captureFailed() {
      return captureFailure != null;
    }
  }

  /** Present-file identity or an absence witness for one exact normalized path. */
  record InputWitness(Path path, ModelFile present) {
    InputWitness {
      path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
      if (present != null && !present.path().equals(path)) {
        throw new IllegalArgumentException("Input identity path differs from its witness path");
      }
    }

    static InputWitness capture(Path path) throws IOException {
      Path exact = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
      if (!Files.exists(exact, LinkOption.NOFOLLOW_LINKS)) {
        return new InputWitness(exact, null);
      }
      return new InputWitness(exact, GenerationModelSelection.captureIdentity(exact));
    }

    boolean matches() {
      return present == null
          ? !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
          : GenerationModelSelection.verifyIdentity(present);
    }
  }
}
