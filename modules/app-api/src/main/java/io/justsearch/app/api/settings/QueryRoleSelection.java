/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import java.nio.file.Path;
import java.util.Objects;

/** Exact query-model bytes selected by one committed settings witness. */
public record QueryRoleSelection(Role reranker, Role citation) {
  public QueryRoleSelection {
    Objects.requireNonNull(reranker, "reranker");
    Objects.requireNonNull(citation, "citation");
  }

  /** Disabled is an explicit decision; absence of the outer selection means legacy fallback. */
  public record Role(State state, String variantId, ModelFile model, ModelFile tokenizer) {
    public Role {
      Objects.requireNonNull(state, "state");
      if (state == State.DISABLED) {
        if (variantId != null || model != null || tokenizer != null) {
          throw new IllegalArgumentException("Disabled query role cannot name files");
        }
      } else {
        if (variantId == null || !variantId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
          throw new IllegalArgumentException("Selected query variant is invalid");
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tokenizer, "tokenizer");
        Path directory = Objects.requireNonNull(model.path().getParent(), "model directory");
        if (!tokenizer.path().equals(directory.resolve("tokenizer.json"))) {
          throw new IllegalArgumentException("Query tokenizer must be beside its selected model");
        }
      }
    }

    public static Role disabled() {
      return new Role(State.DISABLED, null, null, null);
    }

    public static Role selected(String variantId, ModelFile model, ModelFile tokenizer) {
      return new Role(State.SELECTED, variantId, model, tokenizer);
    }
  }

  public enum State {
    DISABLED,
    SELECTED
  }
}
