/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

/** Observable lifecycle state of an Engine-owned component. */
public enum ComponentState {
  ABSENT,
  STARTING,
  READY,
  RELOADING,
  FAILED,
  UNAVAILABLE
}
