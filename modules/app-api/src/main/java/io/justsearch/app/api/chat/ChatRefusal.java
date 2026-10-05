/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A chat demand that was not admitted. On the wire it becomes the {@code CHAT_UNAVAILABLE} error
 * with {@code retryAfterMs} when {@link #retryAfter()} is present.
 */
public final class ChatRefusal extends Exception {
  private static final long serialVersionUID = 1L;

  private final RefusalReason reason;
  private final transient Optional<Duration> retryAfter;
  private final boolean forceAllowed;

  public ChatRefusal(RefusalReason reason, Optional<Duration> retryAfter, boolean forceAllowed) {
    super("chat unavailable: " + Objects.requireNonNull(reason, "reason"));
    Objects.requireNonNull(retryAfter, "retryAfter");
    retryAfter.ifPresent(
        d -> {
          if (d.isNegative()) throw new IllegalArgumentException("retryAfter must not be negative");
        });
    this.reason = reason;
    this.retryAfter = retryAfter;
    this.forceAllowed = forceAllowed;
  }

  public RefusalReason reason() {
    return reason;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  /** True when the app may offer the user a load in spite of the refusal. */
  public boolean forceAllowed() {
    return forceAllowed;
  }
}
