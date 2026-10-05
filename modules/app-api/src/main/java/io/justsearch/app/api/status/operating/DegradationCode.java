/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The standing degradations the status surface lists. Lower snake case on the wire. Per-request
 * fallbacks are not here; they stay on the request's own trace.
 */
public enum DegradationCode {
  /** A user setting (context, layers, GPU off) cannot be honoured on this machine. */
  USER_SETTING_UNMET,
  /** The exit ledger cannot be written, so exits are not being accounted. */
  EXIT_LEDGER_UNWRITABLE,
  /** A held child is still alive after both terminate attempts. */
  CHILD_SURVIVED;

  /** The wire token, for example {@code exit_ledger_unwritable}. */
  @JsonValue
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }
}
