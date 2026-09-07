/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.identity.PathHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Lightweight file identity and metadata snapshot for Worker-side stale-source detection. */
record FileFreshnessSnapshot(
    Path originalPath,
    String normalizedPath,
    String pathHash,
    Object fileKey,
    long sizeBytes,
    long modifiedAtMs,
    boolean regularFile,
    long observedAtMs) {

  static FileFreshnessSnapshot capture(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Path absolute = path.toAbsolutePath().normalize();
    // The one derivation of this key, shared with every site that MARKS a path by it
    // (WorkerScanOps, WorkerIngestService#submitBatch). See PathNormalizer#normalizeKey.
    String key = PathNormalizer.normalizeKey(absolute);
    BasicFileAttributes attrs =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return new FileFreshnessSnapshot(
        absolute,
        key,
        pathHash(key),
        attrs.fileKey(),
        attrs.size(),
        attrs.lastModifiedTime().toMillis(),
        attrs.isRegularFile(),
        System.currentTimeMillis());
  }

  static FileFreshnessSnapshot fromEnvelope(FileEnvelope envelope) {
    return new FileFreshnessSnapshot(
        envelope.originalPath(),
        envelope.normalizedPath(),
        envelope.pathHash(),
        envelope.fileKey(),
        envelope.sizeBytes(),
        envelope.modifiedAtMs(),
        envelope.regularFile(),
        envelope.observedAtMs());
  }

  SourceValidationResult validateNow() {
    try {
      FileFreshnessSnapshot current = capture(originalPath);
      return compare(current);
    } catch (IOException e) {
      return SourceValidationResult.DELETED;
    }
  }

  private SourceValidationResult compare(FileFreshnessSnapshot current) {
    // The pure classification law lives in FileFreshness (tempdoc 555 seam).
    return FileFreshness.classify(this, current);
  }

  enum SourceValidationResult {
    FRESH,
    DELETED,
    SIZE_CHANGED,
    CONTENT_CHANGED,
    MODIFIED_TIME_CHANGED,
    FILE_KEY_CHANGED,
    SOURCE_KIND_CHANGED
  }

  static String pathHash(String value) {
    return PathHash.sha256(value);
  }
}
