/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** The GPU co-residence profile, chosen by dedicated graphics memory size. */
public enum Profile {
  /** 16 GB or more. */
  P16_PLUS,
  /** 12 GB class. */
  P12,
  /** 8 GB class. */
  P8,
  NO_GPU,
  /** Not resolved yet. */
  UNRESOLVED
}
