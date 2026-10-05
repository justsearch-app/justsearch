/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** Which part of the product a degradation affects. */
public enum DegradationScope {
  SEARCH,
  RAG,
  CHAT,
  INDEXING,
  RUNTIME
}
