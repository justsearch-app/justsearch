/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** Where a load-time estimate came from. */
public enum LoadEstimateSource {
  /** Earlier loads on this machine. */
  HISTORY,
  /** The profile's default. */
  PROFILE_DEFAULT
}
