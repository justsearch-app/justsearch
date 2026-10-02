/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Bounds every ZIP member, including XML consumed directly by Office/ODF/EPUB parsers. */
final class ContainerExpansionBudget {
  private final TikaExtractionPolicy policy;
  private final long documentLimit;
  private final ZipOpener zipOpener;
  private long expandedBytes;
  private int archiveMembers;
  private int packageParts;

  @FunctionalInterface
  interface ZipOpener {
    ZipFile open(Path file) throws IOException;
  }

  private record MemberLimit(long bytes, int occurrences) {}

  ContainerExpansionBudget(TikaExtractionPolicy policy, long inputBytes) {
    this(policy, inputBytes, file -> new ZipFile(file.toFile()));
  }

  ContainerExpansionBudget(TikaExtractionPolicy policy, long inputBytes, ZipOpener zipOpener) {
    this.policy = policy;
    documentLimit = limit(inputBytes);
    this.zipOpener = zipOpener;
  }

  private long limit(long compressedBytes) {
    return (long) Math.min(policy.maxInputBytes(),
        Math.max(1, compressedBytes) * policy.maxCompressionRatio());
  }

  void inspect(Path file) throws IOException, ContentExtractor.ExtractionException {
    try {
      inspectZip(file);
    } catch (ZipException | EOFException malformed) {
      throw new ContentExtractor.ExtractionException("Malformed ZIP container", malformed);
    }
  }

  private void inspectZip(Path file) throws IOException, ContentExtractor.BudgetExceededException {
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
      opened = zipOpener.open(file);
    } catch (ZipException failure) {
      if (zipSignature) throw failure;
      return;
    }
    try (ZipFile zip = opened) {
      long packageLimit = limit(Files.size(file));
      long packageBytes = 0;
      long before = expandedBytes;
      Map<String, MemberLimit> entryLimits = new HashMap<>();
      boolean packageDocument = isPackageDocument(zip);
      long metadataBytes = 0;
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        // Reserve count and metadata before retaining a name or opening any member stream.
        // Package parsers consume parts without embedded callbacks: bound those separately
        // using the policy's metadata-entry budget, including when embedded parsing is disabled.
        if (packageDocument) {
          if (packageParts >= policy.maxMetadataEntries()) {
            throw new ContentExtractor.BudgetExceededException(
                "Package member count exceeds policy", "ARCHIVE_MEMBER_LIMIT");
          }
          packageParts++;
        } else {
          if (archiveMembers >= policy.maxEmbeddedResources()) {
            throw new ContentExtractor.BudgetExceededException(
                "Archive member count exceeds policy", "EMBEDDED_RESOURCE_LIMIT");
          }
          archiveMembers++;
        }
        metadataBytes = checkMetadata(entry, metadataBytes);
        long entryLimit = limit(entry.getCompressedSize());
        entryLimits.merge(entry.getName(), new MemberLimit(entryLimit, 1),
            (first, second) -> new MemberLimit(Math.min(first.bytes(), second.bytes()),
                first.occurrences() + second.occurrences()));
      }
      // No inflation starts until the entire directory fits both budgets.
      entries = zip.entries();
      byte[] buffer = new byte[8192];
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        long entryLimit = entryLimits.get(entry.getName()).bytes();
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

  private static boolean isPackageDocument(ZipFile zip) {
    return zip.getEntry("[Content_Types].xml") != null
        || (zip.getEntry("mimetype") != null
            && (zip.getEntry("content.xml") != null
                || zip.getEntry("META-INF/container.xml") != null));
  }

  private long checkMetadata(ZipEntry entry, long retainedBytes)
      throws ContentExtractor.BudgetExceededException {
    String name = entry.getName();
    String comment = entry.getComment();
    byte[] extra = entry.getExtra();
    long bytes = 2L * name.length() + (comment == null ? 0 : 2L * comment.length())
        + (extra == null ? 0 : extra.length);
    long bytesPerMetadataEntry =
        2L * ((long) policy.maxMetadataKeyChars() + policy.maxMetadataValueChars());
    long metadataLimit = Math.min(policy.maxInputBytes(),
        bytesPerMetadataEntry > Long.MAX_VALUE / policy.maxMetadataEntries()
            ? Long.MAX_VALUE : bytesPerMetadataEntry * policy.maxMetadataEntries());
    if (name.length() > policy.maxMetadataValueChars()
        || (comment != null && comment.length() > policy.maxMetadataValueChars())
        || bytes > metadataLimit - retainedBytes) {
      throw new ContentExtractor.BudgetExceededException(
          "Archive metadata exceeds policy", "ARCHIVE_METADATA_LIMIT");
    }
    return retainedBytes + bytes;
  }

  private void inspectLocal(Path file, Map<String, MemberLimit> entryLimits, long packageLimit)
      throws IOException, ContentExtractor.BudgetExceededException {
    long packageBytes = 0;
    long metadataBytes = 0;
    byte[] buffer = new byte[8192];
    try (var input = new ZipInputStream(Files.newInputStream(file))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        MemberLimit admitted = entryLimits.get(entry.getName());
        if (admitted == null) {
          throw new ContentExtractor.BudgetExceededException(
              "ZIP local member has no inspected directory entry", "ARCHIVE_EXPANSION_LIMIT");
        }
        metadataBytes = checkMetadata(entry, metadataBytes);
        // A repeated local name must consume another admitted occurrence, not reuse one slot.
        if (admitted.occurrences() == 1) entryLimits.remove(entry.getName());
        else entryLimits.put(entry.getName(),
            new MemberLimit(admitted.bytes(), admitted.occurrences() - 1));
        long entryLimit = admitted.bytes();
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
