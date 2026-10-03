/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

/** Visibility requested by the source owner for an accepted projection mutation. */
public enum ProjectionDurability {
  NRT,
  DURABLE
}
