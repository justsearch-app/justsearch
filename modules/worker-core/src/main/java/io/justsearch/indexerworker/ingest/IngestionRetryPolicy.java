/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** Retry behavior attached to a typed ingestion outcome. */
public enum IngestionRetryPolicy {
  NONE,
  RETRY_WITH_BACKOFF,
  DEFER_WITHOUT_ATTEMPT,
  /**
   * #758: the source exists but cannot be read right now (locked, access
   * denied), or the folder holding it is unavailable. Retried on {@link IngestionRetryLadder}'s
   * counted, growing schedule, but never exhausted and never terminal: nothing about the file says
   * it is gone or unindexable, so its indexed content stays searchable with no time limit and the
   * queue keeps checking back once a day until the source can be read again.
   */
  RETRY_UNTIL_AVAILABLE
}
