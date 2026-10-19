/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** Where the resolved {@link Profile} came from. A user setting always wins. */
public enum ProfileSource {
  MEASURED,
  USER_OVERRIDE
}
