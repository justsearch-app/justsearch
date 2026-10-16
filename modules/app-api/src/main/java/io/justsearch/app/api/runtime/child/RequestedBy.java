/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/** Who recorded the intent behind a requested exit. */
public enum RequestedBy {
  /** The owning process: the shell for the Engine, the Engine for its children. */
  OWNER,
  /** The process itself, before it exits on its own decision ({@code engine-self-intent}). */
  SELF
}
