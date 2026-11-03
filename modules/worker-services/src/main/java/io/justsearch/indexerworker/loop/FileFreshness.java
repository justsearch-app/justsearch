/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.loop.FileFreshnessSnapshot.SourceValidationResult;

/** Pure comparison of transient source witnesses; persisted millisecond metadata is separate. */
final class FileFreshness {

  private FileFreshness() {}

  static SourceValidationResult classify(FileFreshnessSnapshot prev, FileFreshnessSnapshot current) {
    if (!prev.normalizedPath().equals(current.normalizedPath())) {
      return SourceValidationResult.DELETED;
    }
    if (prev.regularFile() != current.regularFile()) {
      return SourceValidationResult.SOURCE_KIND_CHANGED;
    }
    if (prev.sizeBytes() != current.sizeBytes()) {
      return SourceValidationResult.SIZE_CHANGED;
    }
    if (!prev.modifiedTime().equals(current.modifiedTime())) {
      return SourceValidationResult.MODIFIED_TIME_CHANGED;
    }
    if (!java.util.Objects.equals(prev.fileKey(), current.fileKey())) {
      return SourceValidationResult.FILE_KEY_CHANGED;
    }
    if (!java.util.Objects.equals(prev.changedTime(), current.changedTime())) {
      return SourceValidationResult.CONTENT_CHANGED;
    }
    return SourceValidationResult.FRESH;
  }
}
