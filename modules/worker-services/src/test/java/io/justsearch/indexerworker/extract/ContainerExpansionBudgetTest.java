/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerExpansionBudgetTest {
  @TempDir Path tempDir;

  @Test
  void malformedZipIsAParserFailureRatherThanAnIoFailure() throws Exception {
    Path file = tempDir.resolve("truncated.zip");
    Files.write(file, new byte[] {'P', 'K', 3, 4, 20, 0});
    var budget = new ContainerExpansionBudget(TikaExtractionPolicy.defaults(), Files.size(file));

    var failure = assertThrows(ContentExtractor.ExtractionException.class,
        () -> budget.inspect(file));

    assertEquals(ContentExtractor.ExtractionException.class, failure.getClass());
    assertTrue(failure.getCause() instanceof java.util.zip.ZipException);
  }

  @Test
  void missingContainerRemainsAnIoFailure() {
    var budget = new ContainerExpansionBudget(TikaExtractionPolicy.defaults(), 1);

    assertThrows(java.nio.file.NoSuchFileException.class,
        () -> budget.inspect(tempDir.resolve("missing.zip")));
  }

  @Test
  void manyTinyMembersRefuseAtFirstExcessBeforeAnyInflation() throws Exception {
    Map<String, byte[]> members = new LinkedHashMap<>();
    for (int i = 0; i < 4096; i++) members.put("member-" + i + ".txt", new byte[] {1});
    Path file = archive("many.zip", members);
    TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
    assertTrue(Files.size(file) < policy.maxInputBytes());
    var work = new ObservedWork();
    var budget = budget(policy, file, work);

    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget.inspect(file));

    assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
    assertEquals(policy.maxEmbeddedResources() + 1, work.visitedMembers,
        "Directory inspection must stop at the first excess member");
    assertEquals(0, work.openedMembers, "No member may inflate before admission completes");
  }

  @Test
  void oversizedNameRefusesBeforeEarlierAdmittedMemberInflates() throws Exception {
    var policy = policy(8, 8, 16);
    var members = new LinkedHashMap<String, byte[]>();
    members.put("ok.txt", new byte[] {1});
    members.put("x".repeat(17), new byte[] {1});
    Path file = archive("long-name.zip", members);
    var work = new ObservedWork();

    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget(policy, file, work).inspect(file));

    assertEquals("ARCHIVE_METADATA_LIMIT", failure.reasonCode());
    assertEquals(2, work.visitedMembers);
    assertEquals(0, work.openedMembers);
  }

  @Test
  void aggregateMetadataRefusesBeforeInflation() throws Exception {
    // One metadata slot allows 2 * (one key character + 32 value characters) bytes.
    var policy = policy(8, 1, 32);
    var members = new LinkedHashMap<String, byte[]>();
    members.put("a".repeat(20), new byte[] {1});
    members.put("b".repeat(20), new byte[] {1});
    members.put("c".repeat(20), new byte[] {1});
    Path file = archive("metadata.zip", members);
    var work = new ObservedWork();

    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget(policy, file, work).inspect(file));

    assertEquals("ARCHIVE_METADATA_LIMIT", failure.reasonCode());
    assertEquals(2, work.visitedMembers, "Do not scan beyond the first metadata overflow");
    assertEquals(0, work.openedMembers);
  }

  @Test
  void exactMemberBoundaryStillInflatesBothViews() throws Exception {
    var policy = policy(2, 8, 32);
    Path file = archive("boundary.zip", Map.of("one.txt", new byte[] {1}, "two.txt", new byte[] {2}));
    var work = new ObservedWork();

    budget(policy, file, work).inspect(file);

    assertEquals(4, work.visitedMembers, "Metadata admission and inflation each traverse the directory");
    assertEquals(2, work.openedMembers);
  }

  @Test
  void nestedInspectionsShareTheDocumentMemberBudget() throws Exception {
    var policy = policy(3, 8, 32);
    Path first = archive("first.zip", Map.of("a", new byte[] {1}, "b", new byte[] {2}));
    Path second = archive("second.zip", Map.of("c", new byte[] {3}, "d", new byte[] {4}));
    var work = new ObservedWork();
    var budget = budget(policy, first, work);
    budget.inspect(first);
    work.visitedMembers = 0;
    work.openedMembers = 0;

    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget.inspect(second));

    assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
    assertEquals(2, work.visitedMembers);
    assertEquals(0, work.openedMembers);
  }

  @Test
  void packagePartsHaveABoundEvenWhenEmbeddedResourcesAreDisabled() throws Exception {
    var policy = policy(0, 128, 4096);
    var members = new LinkedHashMap<String, byte[]>();
    members.put("[Content_Types].xml", new byte[] {1});
    for (int i = 1; i < 1000; i++) members.put("p" + i + "/", new byte[0]);
    Path accepted = archive("package.zip", members);
    var acceptedWork = new ObservedWork();
    assertDoesNotThrow(() -> budget(policy, accepted, acceptedWork).inspect(accepted));
    assertEquals(1000, acceptedWork.openedMembers);

    members.put("excess.xml", new byte[] {3});
    Path rejected = archive("package-excess.zip", members);
    var rejectedWork = new ObservedWork();
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget(policy, rejected, rejectedWork).inspect(rejected));
    assertEquals("ARCHIVE_MEMBER_LIMIT", failure.reasonCode());
    assertEquals(1001, rejectedWork.visitedMembers);
    assertEquals(0, rejectedWork.openedMembers);
  }

  @Test
  void packageExcessRefusesBeforeAnyMemberStreamOpens() throws Exception {
    var members = packageMembers(1001);
    Path file = archive("excess-package.zip", members);
    var work = new ObservedWork();
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget(TikaExtractionPolicy.defaults(), file, work).inspect(file));
    assertEquals("ARCHIVE_MEMBER_LIMIT", failure.reasonCode());
    assertEquals(0, work.openedMembers);
  }

  @Test
  void nestedAndSiblingPackagesShareOneThousandParts() throws Exception {
    // inspect is the same cumulative budget used by embedded callbacks. The outer package
    // and two children each admit 300 entries; a final sibling consumes the remainder.
    var budget = new ContainerExpansionBudget(TikaExtractionPolicy.defaults(), 1_000_000);
    for (int i = 0; i < 3; i++) {
      Path file = archive("nested-" + i + ".zip", packageMembers(300));
      assertDoesNotThrow(() -> budget.inspect(file));
    }
    Path last = archive("last.zip", packageMembers(100));
    assertDoesNotThrow(() -> budget.inspect(last));
    Path excess = archive("sibling.zip", packageMembers(1));
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget.inspect(excess));
    assertEquals("ARCHIVE_MEMBER_LIMIT", failure.reasonCode());
  }

  @Test
  void explicitRatioScalesOnlyPackageMembers() throws Exception {
    byte[] content = "a".repeat(2400).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path file = archive("scaled.zip", Map.of("[Content_Types].xml", new byte[0],
        "part", content, "padding", randomBytes(4096)));
    var base = TikaExtractionPolicy.defaults();
    var scaled = new TikaExtractionPolicy("scaled", base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), base.maxEmbeddedResources(), base.maxEmbeddedDepth(), 80,
        true, base.allowedMimeTypes(), base.excludedMimeTypes());
    try (var zip = new ZipFile(file.toFile())) {
      double ratio = (double) content.length / zip.getEntry("part").getCompressedSize();
      assertTrue(ratio > 80 && ratio < 160, "Fixture ratio: " + ratio);
    }
    assertDoesNotThrow(() -> new ContainerExpansionBudget(scaled, Files.size(file)).inspect(file));
  }

  @Test
  void spoofedPackagesAndGenericMembersKeepExpansionAndByteCeilings() throws Exception {
    for (boolean spoof : new boolean[] {false, true}) {
      var members = new LinkedHashMap<String, byte[]>();
      if (spoof) members.put("[Content_Types].xml", new byte[0]);
      members.put("payload", "a".repeat(128 * 1024).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      members.put("padding", randomBytes(8192));
      Path file = archive("bomb-" + spoof + ".zip", members);
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> new ContainerExpansionBudget(TikaExtractionPolicy.defaults(), Files.size(file)).inspect(file));
      assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
    }
  }

  private static Map<String, byte[]> packageMembers(int count) {
    var members = new LinkedHashMap<String, byte[]>();
    members.put("[Content_Types].xml", new byte[0]);
    for (int i = 1; i < count; i++) members.put("p" + i, new byte[] {1});
    return members;
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new java.util.Random(5).nextBytes(bytes);
    return bytes;
  }

  private ContainerExpansionBudget budget(TikaExtractionPolicy policy, Path file, ObservedWork work)
      throws IOException {
    return new ContainerExpansionBudget(policy, Files.size(file), path ->
        new ZipFile(path.toFile()) {
          @Override
          public Enumeration<? extends ZipEntry> entries() {
            var entries = super.entries();
            return new Enumeration<ZipEntry>() {
              @Override public boolean hasMoreElements() { return entries.hasMoreElements(); }
              @Override public ZipEntry nextElement() {
                work.visitedMembers++;
                return entries.nextElement();
              }
            };
          }

          @Override
          public InputStream getInputStream(ZipEntry entry) throws IOException {
            work.openedMembers++;
            return super.getInputStream(entry);
          }
        });
  }

  private Path archive(String name, Map<String, byte[]> members) throws IOException {
    Path file = tempDir.resolve(name);
    try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
      for (var member : members.entrySet()) {
        var entry = new ZipEntry(member.getKey());
        entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
        output.putNextEntry(entry);
        output.write(member.getValue());
        output.closeEntry();
      }
    }
    return file;
  }

  private static TikaExtractionPolicy policy(int resources, int metadataEntries, int valueChars) {
    var base = TikaExtractionPolicy.defaults();
    return new TikaExtractionPolicy("preflight-test", base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), metadataEntries, 1, valueChars, resources,
        base.maxEmbeddedDepth(), base.maxCompressionRatio(), true,
        base.allowedMimeTypes(), base.excludedMimeTypes());
  }

  private static final class ObservedWork {
    int visitedMembers;
    int openedMembers;
  }
}
