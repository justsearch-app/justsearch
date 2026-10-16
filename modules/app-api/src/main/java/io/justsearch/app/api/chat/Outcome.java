/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.util.Objects;

/** How an asynchronous desired-state request to the chat controller ended. */
public sealed interface Outcome permits Outcome.Applied, Outcome.Superseded, Outcome.Refused,
    Outcome.Failed {

  /** The request took effect; {@code status} is the snapshot that published it. */
  record Applied(ChatStatus status) implements Outcome {
    public Applied {
      Objects.requireNonNull(status, "status");
    }
  }

  /** A later request replaced this one before it took effect. */
  record Superseded() implements Outcome {}

  /** The controller declined the request under the current conditions. */
  record Refused(ChatRefusal refusal) implements Outcome {
    public Refused {
      Objects.requireNonNull(refusal, "refusal");
    }
  }

  /** The request was attempted and failed. */
  record Failed(FailedCode code) implements Outcome {
    public Failed {
      Objects.requireNonNull(code, "code");
    }
  }
}
