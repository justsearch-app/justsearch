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
  GAME_MODE,
  /** The Windows session is ending (log off, restart or shut down), refining {@code QUIT}. */
  SESSION_END,
  /** Another owner took the stack over (a dev takeover), refining {@code QUIT}. */
  TAKEOVER,
  /** The owner was interrupted (for example Ctrl+C in a dev runner), refining {@code QUIT}. */
  INTERRUPTED,
  /**
   * Reader-only: a detail token this build does not know, written by a newer writer. Readers get it
   * from {@link #fromWireOrUnknown} and keep the original token as {@code detailRaw}. No writer
   * records it.
   */
  UNKNOWN;

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

  /**
   * The reader's parse: a token outside the closed set reads as {@link #UNKNOWN}, so a record from a
   * newer writer is still read. The caller keeps the original token for display. Writers use the
   * strict {@link #fromWire}.
   */
  public static StopDetail fromWireOrUnknown(String token) {
    java.util.Objects.requireNonNull(token, "token");
    return fromToken(token).orElse(UNKNOWN);
  }

  @JsonCreator
  static StopDetail fromWire(String token) {
    return fromToken(token)
        .orElseThrow(() -> new IllegalArgumentException("unknown stop detail token: " + token));
  }
}
