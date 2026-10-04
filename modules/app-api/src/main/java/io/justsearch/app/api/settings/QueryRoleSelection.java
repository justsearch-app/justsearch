/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ExecutionProvider;
import java.nio.file.Path;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Exact query-model bytes selected by one committed settings witness. */
public record QueryRoleSelection(Role reranker, Role citation) {
  public QueryRoleSelection {
    Objects.requireNonNull(reranker, "reranker");
    Objects.requireNonNull(citation, "citation");
  }

  /** Disabled is an explicit decision; absence of the outer selection means legacy fallback. */
  public record Role(State state, String variantId, ModelFile model, ModelFile tokenizer,
      @JsonInclude(JsonInclude.Include.NON_NULL) ModelPrecision precision,
      @JsonInclude(JsonInclude.Include.NON_NULL) ExecutionProvider targetEp) {
    public Role {
      Objects.requireNonNull(state, "state");
      if (state == State.DISABLED) {
        if (variantId != null || model != null || tokenizer != null || precision != null
            || targetEp != null) {
          throw new IllegalArgumentException("Disabled query role cannot name files");
        }
      } else {
        if (variantId == null || !variantId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
          throw new IllegalArgumentException("Selected query variant is invalid");
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tokenizer, "tokenizer");
        // Null descriptors are readable only for v5 selections committed before runtime
        // metadata was recorded. New selections use selected(..., precision, targetEp).
        if ((precision == null) != (targetEp == null)) {
          throw new IllegalArgumentException("Query runtime descriptor must be complete");
        }
        Path directory = Objects.requireNonNull(model.path().getParent(), "model directory");
        if (!tokenizer.path().equals(directory.resolve("tokenizer.json"))) {
          throw new IllegalArgumentException("Query tokenizer must be beside its selected model");
        }
      }
    }

    public static Role disabled() {
      return new Role(State.DISABLED, null, null, null, null, null);
    }

    public static Role selected(String variantId, ModelFile model, ModelFile tokenizer,
        ModelPrecision precision, ExecutionProvider targetEp) {
      return new Role(State.SELECTED, variantId, model, tokenizer,
          Objects.requireNonNull(precision, "precision"),
          Objects.requireNonNull(targetEp, "targetEp"));
    }
  }

  public enum State {
    DISABLED,
    SELECTED
  }
}
