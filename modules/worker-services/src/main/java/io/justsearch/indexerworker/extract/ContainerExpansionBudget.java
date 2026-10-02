/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Bounds every ZIP member, including XML consumed directly by Office/ODF/EPUB parsers. */
final class ContainerExpansionBudget {
  private final TikaExtractionPolicy policy;
  private final long documentLimit;
  private long expandedBytes;

  ContainerExpansionBudget(TikaExtractionPolicy policy, long inputBytes) {
    this.policy = policy;
    documentLimit = limit(inputBytes);
  }

  private long limit(long compressedBytes) {
    return (long) Math.min(policy.maxInputBytes(),
        Math.max(1, compressedBytes) * policy.maxCompressionRatio());
  }

  void inspect(Path file) throws IOException, ContentExtractor.BudgetExceededException {
    boolean zipSignature;
    try (InputStream input = Files.newInputStream(file)) {
      byte[] magic = input.readNBytes(4);
      zipSignature = magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
          && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6));
    }
    // ZIP-based parsers also accept packages with a leading preamble. Inspect their central
    // directory regardless of the first bytes; an invalid ordinary file is simply not a ZIP.
    ZipFile opened;
    try {
      opened = new ZipFile(file.toFile());
    } catch (ZipException failure) {
      if (zipSignature) throw failure;
      return;
    }
    try (ZipFile zip = opened) {
      long packageLimit = limit(Files.size(file));
      long packageBytes = 0;
      long before = expandedBytes;
      Map<String, Long> entryLimits = new HashMap<>();
      var entries = zip.entries();
      byte[] buffer = new byte[8192];
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        long entryLimit = limit(entry.getCompressedSize());
        entryLimits.merge(entry.getName(), entryLimit, Math::min);
        long entryBytes = 0;
        // Read actual inflated bytes, including comments/non-output XML and directory payloads.
        // Declared ZIP lengths are untrusted, so they cannot be the accounting authority.
        try (InputStream inflated = zip.getInputStream(entry)) {
          int read;
          while ((read = inflated.read(buffer, 0, (int) Math.min(buffer.length,
              Math.min(entryLimit - entryBytes,
                  Math.min(packageLimit - packageBytes, documentLimit - expandedBytes)) + 1))) != -1) {
            entryBytes += read;
            packageBytes += read;
            expandedBytes += read;
            if (entryBytes > entryLimit || packageBytes > packageLimit || expandedBytes > documentLimit) {
              throw new ContentExtractor.BudgetExceededException(
                  "Container expansion exceeds policy", "ARCHIVE_EXPANSION_LIMIT");
            }
          }
        }
      }
      if (zipSignature) {
        // Some native parsers consume local headers, others the central directory. Inspect both
        // views of these immutable bytes; a hidden/local-only part must not bypass the preflight.
        long centralBytes = expandedBytes;
        expandedBytes = before;
        inspectLocal(file, entryLimits, packageLimit);
        expandedBytes = Math.max(centralBytes, expandedBytes);
      }
    }
  }

  private void inspectLocal(Path file, Map<String, Long> entryLimits, long packageLimit)
      throws IOException, ContentExtractor.BudgetExceededException {
    long packageBytes = 0;
    byte[] buffer = new byte[8192];
    try (var input = new ZipInputStream(Files.newInputStream(file))) {
      java.util.zip.ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        Long entryLimit = entryLimits.get(entry.getName());
        if (entryLimit == null) {
          throw new ContentExtractor.BudgetExceededException(
              "ZIP local member has no inspected directory entry", "ARCHIVE_EXPANSION_LIMIT");
        }
        long entryBytes = 0;
        int read;
        while ((read = input.read(buffer, 0, (int) Math.min(buffer.length,
            Math.min(entryLimit - entryBytes,
                Math.min(packageLimit - packageBytes, documentLimit - expandedBytes)) + 1))) != -1) {
          entryBytes += read;
          packageBytes += read;
          expandedBytes += read;
          if (entryBytes > entryLimit || packageBytes > packageLimit || expandedBytes > documentLimit) {
            throw new ContentExtractor.BudgetExceededException(
                "Local container expansion exceeds policy", "ARCHIVE_EXPANSION_LIMIT");
          }
        }
      }
    }
  }
}
