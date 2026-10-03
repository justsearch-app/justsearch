/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/** Process exit evidence derived from the physical native-session owners. */
public enum NativeQuiescence {
  /** Every published handle retired, and no native constructor or close remains in progress. */
  QUIESCED,
  /** A native owner is still active, refused retirement, or cannot be proven quiescent. */
  UNQUIESCED
}
