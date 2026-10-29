/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/** A capped parent prefilter cannot establish the complete eligible set. Never a partial result. */
public final class ParentFilterOverflowException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ParentFilterOverflowException(int limit) {
    super("Retrieval filter scope exceeds " + limit + " eligible parents; narrow the filters or supply docIds");
  }
}
