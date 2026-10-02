/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** Stable reason-code constants for typed ingestion outcomes. */
public final class IngestionReasonCodes {
  public static final String SUCCESS = "SUCCESS";

  /**
   * Indexed successfully, but the parser output was truncated to fit the policy's max-extracted
   * chars cap. Written to {@code SchemaFields.EXTRACTION_REASON_CODE} so search-side callers can
   * distinguish "got everything" from "got the prefix Tika handed us before we cut it off."
   */
  public static final String SUCCESS_PARTIAL = "SUCCESS_PARTIAL";

  /**
   * Indexed successfully, but the parser produced no usable content at all (tempdoc 671,
   * Long-term design part 2) — distinct from {@link #SUCCESS} (which implies real content) and
   * from {@link #SUCCESS_PARTIAL} (which means truncated, not empty). Written to
   * {@code SchemaFields.EXTRACTION_REASON_CODE} so search-side callers can distinguish "no
   * content because there genuinely was none" from a silent, misleading "full success."
   */
  public static final String SUCCESS_EMPTY = "SUCCESS_EMPTY";
  /**
   * Extraction dropout (tempdoc 790): the tier that ran produced no usable text, and a further
   * fallback tier (VDU/VLM per ADR-0018) is queued for this document. Distinct from
   * {@link #SUCCESS_EMPTY}, which says "nothing was extracted" without saying whether anything
   * else will try.
   */
  public static final String EXTRACTION_DROPOUT_PENDING_FALLBACK = "EXTRACTION_DROPOUT_PENDING_FALLBACK";

  /**
   * Extraction dropout (tempdoc 790) that every eligible tier failed to recover — the terminal
   * state of the fallback chain. Written together with
   * {@code SchemaFields.EXTRACTION_METHOD_NONE} so search-side callers can surface an honest hole
   * instead of an invisible one.
   */
  public static final String EXTRACTION_DROPOUT_UNRECOVERED = "EXTRACTION_DROPOUT_UNRECOVERED";

  public static final String SKIPPED_TEMP_OR_SYSTEM = "SKIPPED_TEMP_OR_SYSTEM";
  public static final String MISSING_INGESTION_BOUNDARY = "MISSING_INGESTION_BOUNDARY";
  public static final String UNCHANGED = "UNCHANGED";
  public static final String NON_REGULAR_SOURCE = "NON_REGULAR_SOURCE";
  public static final String MISSING_AT_PROCESSING = "MISSING_AT_PROCESSING";
  public static final String DELETED_OR_MISSING = "DELETED_OR_MISSING";
  public static final String STALE_AFTER_EXTRACTION = "STALE_AFTER_EXTRACTION";
  public static final String DELETED_AFTER_SNAPSHOT = "DELETED_AFTER_SNAPSHOT";
  public static final String SIZE_CHANGED_AFTER_SNAPSHOT = "SIZE_CHANGED_AFTER_SNAPSHOT";
  public static final String MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT =
      "MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT";
  public static final String FILE_KEY_CHANGED_AFTER_SNAPSHOT = "FILE_KEY_CHANGED_AFTER_SNAPSHOT";
  public static final String SOURCE_KIND_CHANGED_AFTER_SNAPSHOT =
      "SOURCE_KIND_CHANGED_AFTER_SNAPSHOT";
  public static final String UNREADABLE = "UNREADABLE";
  public static final String IO_ERROR = "IO_ERROR";
  public static final String INPUT_TOO_LARGE = "INPUT_TOO_LARGE";
  public static final String OFFICE_INPUT_TOO_LARGE = "OFFICE_INPUT_TOO_LARGE";
  public static final String PARSER_FAILED = "PARSER_FAILED";
  public static final String PARSER_TIMEOUT = "PARSER_TIMEOUT";
  public static final String SANDBOX_FAILED = "SANDBOX_FAILED";
  public static final String WRITE_FAILED = "WRITE_FAILED";
  public static final String WRITE_UNAVAILABLE_DRAINING = "WRITE_UNAVAILABLE_DRAINING";
  /**
   * File detected as a cloud-only placeholder (e.g., OneDrive Files-on-Demand) that is not
   * present locally. Reading would trigger network hydration; deferred until the user opts in.
   * Tempdoc 410 §3 / Phase 2.1.
   */
  public static final String CLOUD_PLACEHOLDER = "CLOUD_PLACEHOLDER";

  private IngestionReasonCodes() {}
}
