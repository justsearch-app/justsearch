/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import io.justsearch.agent.api.registry.PreciseWire;
import java.util.Objects;

/**
 * Head-side wire record for a single indexing-job row. Backs the
 * {@code core.indexing-jobs} TABULAR Resource (slice 445).
 *
 * <p>Privacy contract: {@code pathHash} is the SHA-256 hex of the absolute
 * normalized path. Raw paths NEVER appear on this wire — callers resolve
 * {@code pathHash} via the {@code core.resolve-path-hash} Operation when
 * a user gesture demands the path string. Pinned by ADR-0028 +
 * {@code LibraryResolveHashOnlyCallerPin}.
 *
 * <p>{@code state} is one of the {@code STATE_*} constants below.
 *
 * <p>Worker analogue: {@code IndexingJobChangeFeed.JobRow} (worker-core).
 * The {@code RemoteIndexingJobsBridge} translates worker proto frames →
 * this head-side record one-for-one for the V1 lean scope.
 *
 * <p>{@link PreciseWire} (tempdoc 911 / 885 UL.9): every component is present and non-null on the
 * wire — the compact constructor rejects null {@code pathHash}/{@code state}/{@code collection} and
 * normalizes {@code errorMessage}/{@code scanId} to {@code ""}, and the rest are primitives. So the
 * generated JSON Schema states {@code required} + non-null rather than the permissive
 * all-optional default, and the FE's generated Zod rejects a row missing {@code state} instead of
 * silently reading {@code undefined} (the field the RETRY_EXHAUSTED rendering depends on).
 */
public record IndexingJobView(
    String pathHash,
    String state,
    int attempts,
    long lastUpdatedMs,
    String errorMessage,
    long retryAfterMs,
    String collection,
    String scanId)
    implements PreciseWire {

  /** Awaiting processing (includes rows in retry backoff — {@code retryAfterMs} distinguishes). */
  public static final String STATE_PENDING = "PENDING";

  /** Claimed by the indexing loop. */
  public static final String STATE_PROCESSING = "PROCESSING";

  /** Indexed. */
  public static final String STATE_DONE = "DONE";

  /** Terminal: this file cannot be ingested (a parse failure, or the untyped attempts cap). */
  public static final String STATE_FAILED = "FAILED";

  /**
   * Terminal (tempdoc 885 item 21b): a transient failure — an I/O error, a parser timeout, a
   * sandbox crash — kept recurring for the whole seven-day retry window, so the queue stopped
   * retrying. Distinct from {@link #STATE_FAILED}: that says the file is unreadable, this says we
   * never managed to read it. Reset by anything that re-enqueues the path (a rescan, or a watcher
   * event on an mtime/size change), which restarts the window.
   *
   * <p>The head-side vocabulary lives here, on the canonical record named by
   * {@code governance/operation-surfaces.v1.json}, so a projection that must classify a state
   * cannot invent its own spelling.
   */
  public static final String STATE_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

  public IndexingJobView {
    Objects.requireNonNull(pathHash, "pathHash");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(collection, "collection");
    errorMessage = errorMessage == null ? "" : errorMessage;
    // The internal directory scan that enqueued this job. Public operation progress uses
    // the operation key, independently of this scan identity. Empty for single-file ingest,
    // the watcher, or a row
    // written before the worker's {@code scan_id} column existed.
    scanId = scanId == null ? "" : scanId;
  }
}
