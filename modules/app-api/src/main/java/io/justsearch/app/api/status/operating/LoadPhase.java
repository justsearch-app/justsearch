/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** The step a loading chat server is in. The server reports no percentage, so none is shown. */
public enum LoadPhase {
  SPAWNING,
  LOADING_MODEL,
  WARMING
}
