/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** The kinds of bulk GPU work an indexing pause or slowdown applies to. */
public enum BulkWorkKind {
  INDEXING,
  /** Video-document understanding. */
  VDU
}
