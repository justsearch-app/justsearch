/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One stop as its owner asks for it: the reason and its optional detail, how to stop, and which
 * component asked. All four are written into the {@code stop-intent} record.
 *
 * @param requestedBy the asking component as a ledger token, for example {@code extraction-pool},
 *     {@code chat-controller} or {@code engine-shutdown}
 */
public record StopRequest(
    StopReason reason, Optional<StopDetail> detail, StopMode mode, String requestedBy) {

  /** The {@code requestedBy} of a stop whose caller does not name itself. */
  public static final String OWNER = "owner";

  private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

  public StopRequest {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(requestedBy, "requestedBy");
    if (detail.filter(StopDetail.UNKNOWN::equals).isPresent()) {
      throw new IllegalArgumentException("UNKNOWN is a reader-only stop detail");
    }
    if (!TOKEN.matcher(requestedBy).matches()) {
      throw new IllegalArgumentException("requestedBy must be a ledger token: " + requestedBy);
    }
  }

  /** A stop with no detail, asked for by the {@link #OWNER}. */
  public static StopRequest of(StopReason reason, StopMode mode) {
    return new StopRequest(reason, Optional.empty(), mode, OWNER);
  }

  /** A stop with no detail, asked for by {@code requestedBy}. */
  public static StopRequest of(StopReason reason, StopMode mode, String requestedBy) {
    return new StopRequest(reason, Optional.empty(), mode, requestedBy);
  }

  /** The same stop with {@code detail}. */
  public StopRequest withDetail(StopDetail value) {
    return new StopRequest(reason, Optional.of(value), mode, requestedBy);
  }
}
