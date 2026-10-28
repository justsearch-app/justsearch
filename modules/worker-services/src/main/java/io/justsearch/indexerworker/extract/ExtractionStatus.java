/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Internal trust/provenance status for parser output before document construction. */
public enum ExtractionStatus {
  SUCCESS_FULL,
  SUCCESS_PARTIAL,
  // Tempdoc 671, Long-term design part 2: the pipeline completed cleanly (no exception, no
  // truncation) but produced no usable content. Distinct from SUCCESS_FULL — which used to be
  // set unconditionally for any non-truncated result, including empty ones — and distinct from
  // SUCCESS_PARTIAL (which means truncated or lossy, not "empty"). Mirrors the established
  // SchemaFields.VDU_STATUS_COMPLETED_EMPTY precedent for the same "ran fine, found nothing"
  // distinction in the sibling VDU subsystem.
  SUCCESS_EMPTY,
  FAILED,
  TIMED_OUT,
  BUDGET_EXCEEDED
}
