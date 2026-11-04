/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HostilePackageAdmissionTest {
  @TempDir Path temporary;

  @Test
  void localOnlyDuplicateDirectoryMisleadingAndOverlappingMembersCannotBypassInspection() throws Exception {
    byte[] original = zip(Map.of("first", new byte[] {1}, "other", new byte[] {2}));
    int central = central(original);
    int firstLength = 46 + u16(original, central + 28) + u16(original, central + 30)
        + u16(original, central + 32);
    // Delete only the first directory record, retaining its local header and payload.
    byte[] hidden = new byte[original.length - firstLength];
    System.arraycopy(original, 0, hidden, 0, central);
    System.arraycopy(original, central + firstLength, hidden, central,
        original.length - central - firstLength);
    int end = hidden.length - 22;
    view(hidden).putShort(end + 8, (short) 1).putShort(end + 10, (short) 1)
        .putInt(end + 12, u32(original, original.length - 22 + 12) - firstLength);
    refused("local-only.zip", hidden);

    byte[] single = zip(Map.of("first", new byte[] {1}));
    int localLength = central(single);
    byte[] duplicate = new byte[single.length + localLength];
    System.arraycopy(single, 0, duplicate, 0, localLength);
    System.arraycopy(single, 0, duplicate, localLength, single.length);
    view(duplicate).putInt(duplicate.length - 22 + 16, 2 * localLength);
    refused("duplicate-local.zip", duplicate);

    refused("directory-payload.zip", zip(Map.of("directory/", repetitive())));

    byte[] misleading = zip(Map.of("payload", repetitive()));
    view(misleading).putInt(central(misleading) + 24, 1); // declared uncompressed size
    refused("misleading.zip", misleading);

    // The second directory record points at the first local entry, hiding the bomb from
    // directory-driven inflation. The independent local walk must still catch it.
    var members = new LinkedHashMap<String, byte[]>();
    members.put("first", new byte[] {1});
    members.put("other", repetitive());
    byte[] overlap = zip(members);
    int first = central(overlap);
    int second = first + 46 + u16(overlap, first + 28) + u16(overlap, first + 30)
        + u16(overlap, first + 32);
    view(overlap).putInt(second + 42, 0).putInt(second + 20, 3).putInt(second + 24, 1);
    refused("overlap.zip", overlap);
  }

  @Test
  void packageAndDocumentTotalsAndEffectiveByteCeilingsRemainIndependentOfMemberRatio() throws Exception {
    var base = TikaExtractionPolicy.defaults();
    // Each member is < 200x, while the package as a whole exceeds 100x.
    var members = new LinkedHashMap<String, byte[]>();
    members.put("[Content_Types].xml", new byte[0]);
    byte[] member = new byte[48_180];
    byte[] prefix = new byte[180];
    new Random(5).nextBytes(prefix);
    System.arraycopy(prefix, 0, member, 0, prefix.length);
    for (int i = 0; i < 20; i++) members.put("p" + i, member);
    byte[] content = zip(members);
    Path file = Files.write(temporary.resolve("package-total.zip"), content);
    try (var zip = new ZipFile(file.toFile())) {
      assertTrue(zip.stream().allMatch(e -> e.getSize() <= 200 * e.getCompressedSize()));
      assertTrue(zip.stream().mapToLong(ZipEntry::getSize).sum() > 100 * content.length);
    }
    refused("package-total.zip", content);
    var expansion = new ContainerExpansionBudget(base, 1);
    Path small = Files.write(temporary.resolve("document-total.zip"), zip(Map.of("small", new byte[101])));
    assertEquals("ARCHIVE_EXPANSION_LIMIT", assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> expansion.inspect(small)).reasonCode());

    byte[] padding = new byte[8192];
    new Random(5).nextBytes(padding);
    Path capped = Files.write(temporary.resolve("capped.zip"), zip(Map.of("[Content_Types].xml", new byte[0],
        "padding", padding)));
    var policy = new TikaExtractionPolicy("small-byte-ceiling", base.maxExtractedChars(), 4096,
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), base.maxEmbeddedResources(), base.maxEmbeddedDepth(),
        base.maxCompressionRatio(), true, base.allowedMimeTypes(), base.excludedMimeTypes());
    assertEquals("ARCHIVE_EXPANSION_LIMIT", assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> new ContainerExpansionBudget(policy, Files.size(capped)).inspect(capped)).reasonCode());
  }

  @Test
  void genericMemberBetweenOneAndTwoHundredRatioStillRefusesWithPadding() throws Exception {
    byte[] payload = new byte[2400];
    byte[] padding = new byte[4096];
    new Random(5).nextBytes(padding);
    byte[] content = zip(Map.of("payload", payload, "padding", padding));
    Path file = Files.write(temporary.resolve("generic-midratio.zip"), content);
    try (var zip = new ZipFile(file.toFile())) {
      var member = zip.getEntry("payload");
      double ratio = (double) member.getSize() / member.getCompressedSize();
      assertTrue(ratio > 100 && ratio < 200, "Fixture ratio: " + ratio);
      assertTrue(zip.stream().mapToLong(ZipEntry::getSize).sum() < 100 * content.length);
    }
    refused("generic-midratio.zip", content);
  }

  private void refused(String name, byte[] content) throws Exception {
    Path file = Files.write(temporary.resolve(name), content);
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> new ContainerExpansionBudget(TikaExtractionPolicy.defaults(), content.length).inspect(file));
    assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
  }

  private static byte[] repetitive() { return new byte[128 * 1024]; }
  private static int central(byte[] content) { return u32(content, content.length - 22 + 16); }
  private static ByteBuffer view(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
  private static int u16(byte[] bytes, int offset) { return Short.toUnsignedInt(view(bytes).getShort(offset)); }
  private static int u32(byte[] bytes, int offset) { return view(bytes).getInt(offset); }

  private static byte[] zip(Map<String, byte[]> members) throws Exception {
    var content = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(content)) {
      for (var member : members.entrySet()) {
        var entry = new ZipEntry(member.getKey());
        entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(member.getValue());
        zip.closeEntry();
      }
    }
    return content.toByteArray();
  }
}
