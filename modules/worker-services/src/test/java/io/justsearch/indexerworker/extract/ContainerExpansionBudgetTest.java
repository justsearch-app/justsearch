/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    var policy = policy(0, 2, 32);
    var members = new LinkedHashMap<String, byte[]>();
    members.put("[Content_Types].xml", new byte[] {1});
    members.put("part.xml", new byte[] {2});
    Path accepted = archive("package.zip", members);
    var acceptedWork = new ObservedWork();
    budget(policy, accepted, acceptedWork).inspect(accepted);
    assertEquals(2, acceptedWork.openedMembers);

    members.put("excess.xml", new byte[] {3});
    Path rejected = archive("package-excess.zip", members);
    var rejectedWork = new ObservedWork();
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> budget(policy, rejected, rejectedWork).inspect(rejected));
    assertEquals("ARCHIVE_MEMBER_LIMIT", failure.reasonCode());
    assertEquals(3, rejectedWork.visitedMembers);
    assertEquals(0, rejectedWork.openedMembers);
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
        output.putNextEntry(new ZipEntry(member.getKey()));
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
