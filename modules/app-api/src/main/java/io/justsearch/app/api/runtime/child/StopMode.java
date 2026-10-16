/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/** How a stop is carried out. */
public enum StopMode {
  /** Run the child's cooperative step (for example close stdin) and wait out its grace first. */
  COOPERATIVE,
  /** Terminate at once. */
  NOW
}
