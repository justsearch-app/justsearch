/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;

/**
 * The bounded {@code detail} token that refines a {@link StopReason}, for example {@code RECYCLE}
 * with {@code request_budget}. Lower case on the wire. It is never a free string: a value outside
 * this set is not a detail.
 */
public enum StopDetail {
  REQUEST_BUDGET,
  PROTOCOL,
  HANG,
  START,
  REQUEST,
  ESCALATED,
  /** Reserved: no producer schedules a bulk-window stop yet. */
  BULK_WINDOW,
  GPU_BUSY,
  GAME_MODE;

  /** The wire token, for example {@code request_budget}. */
  @JsonValue
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses a wire token. Empty for anything outside the closed set; never a pass-through. */
  public static Optional<StopDetail> fromToken(String token) {
    if (token == null) return Optional.empty();
    for (StopDetail detail : values()) {
      if (detail.token().equals(token)) return Optional.of(detail);
    }
    return Optional.empty();
  }

  @JsonCreator
  static StopDetail fromWire(String token) {
    return fromToken(token)
        .orElseThrow(() -> new IllegalArgumentException("unknown stop detail token: " + token));
  }
}
