/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.FileFreshnessSnapshot.SourceValidationResult;

/**
 * Pure admission/stale-classification policy — the law-bearing core extracted from
 * {@link WorkerIngestionAuthority} (tempdoc 555 §4, functional-core/imperative-shell). The authority
 * itself is IO-entangled (Files.exists/isReadable/capture short-circuit in order); this class holds
 * the pure, exhaustive mapping from a post-snapshot freshness validation to its ingestion reason
 * code. The LAW: the mapping is TOTAL (every SourceValidationResult has a distinct, correct reason
 * code); a wrong arm emits a misleading diagnostic for a stale-after-snapshot file.
 */
final class AdmissionPolicy {

  private AdmissionPolicy() {}

  static String staleReasonCode(SourceValidationResult validation) {
    return switch (validation) {
      case SIZE_CHANGED -> IngestionReasonCodes.SIZE_CHANGED_AFTER_SNAPSHOT;
      case CONTENT_CHANGED -> IngestionReasonCodes.STALE_AFTER_EXTRACTION;
      case MODIFIED_TIME_CHANGED -> IngestionReasonCodes.MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT;
      case FILE_KEY_CHANGED -> IngestionReasonCodes.FILE_KEY_CHANGED_AFTER_SNAPSHOT;
      case SOURCE_KIND_CHANGED -> IngestionReasonCodes.SOURCE_KIND_CHANGED_AFTER_SNAPSHOT;
      case DELETED -> IngestionReasonCodes.DELETED_AFTER_SNAPSHOT;
      case UNREADABLE -> IngestionReasonCodes.SOURCE_UNREADABLE;
      case FRESH -> IngestionReasonCodes.STALE_AFTER_EXTRACTION;
    };
  }
}
