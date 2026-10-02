/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveExtractionLimitsTest {
  @TempDir Path tempDir;

  @Test
  void strictRatioRejectsNonOutputOdtAndOoxmlPartsWithoutEmbeddedCallbacks() throws Exception {
    for (FormatId format : new FormatId[] {FormatId.ODT, FormatId.XLSX}) {
      String member = format == FormatId.ODT ? "content.xml" : "xl/worksheets/sheet1.xml";
      Path file = commentedDocument(format, member, 8192);
      TikaExtractionPolicy strict = ratioPolicy(2);
      assertTrue(Files.size(file) < strict.maxInputBytes());
      try (var ordinary = extractor(TikaExtractionPolicy.defaults())) {
        ExtractionArtifact artifact = ordinary.extractArtifact(file);
        assertTrue(!artifact.result().content().isBlank());
        assertEquals(0, artifact.embeddedResourceCount(), "Package parts bypass embedded callbacks");
        assertTrue(!artifact.result().content().contains("ZZZZ"), "Comments do not consume text budget");
      }
      try (var limited = extractor(strict)) {
        var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
            () -> limited.extractArtifact(file));
        assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
      }
      var structured = new StructuredContentExtractor(strict.maxExtractedChars(), strict);
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> structured.extractWithStatus(file));
      assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
    }
  }

  @Test
  void embeddedOoxmlUsesTheSameInternalExpansionPolicy() throws Exception {
    Path spreadsheet = commentedDocument(FormatId.XLSX, "xl/worksheets/sheet1.xml", 8192);
    Path outer = archive("embedded-office.zip", Map.of("report.xlsx", Files.readAllBytes(spreadsheet)));
    try (var limited = extractor(ratioPolicy(2))) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> limited.extractArtifact(outer));
      assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
    }
  }

  @Test
  void standaloneFlatParsingRejectsNonOutputOdtExpansion() throws Exception {
    Path file = commentedDocument(FormatId.ODT, "content.xml", 256 * 1024);
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> new ContentExtractor().extract(file));
    assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
  }

  @Test
  void preparedInputCannotBeReplacedBetweenInspectionAndParsing() throws Exception {
    Path source = archive("source.zip", Map.of("ok.txt", bytes("original text")));
    try (var input = PreparedExtractionInput.prepare(source, TikaExtractionPolicy.defaults())) {
      Files.write(source, zip(Map.of("large.txt", bytes("Z".repeat(128 * 1024)))));
      var context = new org.apache.tika.parser.ParseContext();
      var budget = new EmbeddedResourceBudget(TikaExtractionPolicy.defaults(),
          Files.size(input.file()), context, input.expansion());
      var result = new ContentExtractor().extract(input.file(), context, budget);
      assertTrue(result.content().contains("original text"));
    }
  }

  @Test
  void realArchiveReportsObservedResourcesAndNestedDepth() throws Exception {
    byte[] inner = zip(Map.of("inside.txt", bytes("embedded searchable content")));
    Path file = archive("accepted.zip", Map.of("inner.zip", inner, "outer.txt", bytes("outer text")));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      ExtractionArtifact artifact = extractor.extractArtifact(file);
      assertTrue(artifact.result().content().contains("embedded searchable content"));
      assertEquals(3, artifact.embeddedResourceCount());
      assertEquals(2, artifact.maxEmbeddedDepth());
      artifact.validate(TikaExtractionPolicy.defaults(), "source");
    }
  }

  @Test
  void rejectsThreeHundredMembersWithoutFlatFallbackBypass() throws Exception {
    Map<String, byte[]> members = new LinkedHashMap<>();
    for (int i = 0; i < 300; i++) members.put("member" + i + ".txt", bytes("small member " + i));
    Path file = archive("members.zip", members);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> extractor.extractArtifact(file));
      assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
    }
    var flatFailure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> new ContentExtractor().extract(file));
    assertEquals("EMBEDDED_RESOURCE_LIMIT", flatFailure.reasonCode());
  }

  @Test
  void rejectsDeepRealArchive() throws Exception {
    byte[] nested = bytes("deep searchable text");
    String name = "leaf.txt";
    for (int i = 0; i < 10; i++) {
      nested = zip(Map.of(name, nested));
      name = "nested.zip";
    }
    Path file = tempDir.resolve("deep.zip");
    Files.write(file, nested);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> extractor.extractArtifact(file));
      assertEquals("EMBEDDED_DEPTH_LIMIT", failure.reasonCode());
    }
  }

  @Test
  void rejectsExpansionBelowInputAndTextCaps() throws Exception {
    Path file = archive("expanded.zip", Map.of("expanded.txt", bytes("A".repeat(128 * 1024))));
    assertTrue(Files.size(file) * 100 < 128 * 1024);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> extractor.extractArtifact(file));
      assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
    }
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> new ContentExtractor().extract(file));
    assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
  }

  @Test
  void flatFallbackRetainsConfiguredPolicy() throws Exception {
    Path file = archive("flat.zip", Map.of("one.txt", bytes("one"), "two.txt", bytes("two")));
    TikaExtractionPolicy base = TikaExtractionPolicy.defaults();
    var policy = new TikaExtractionPolicy("one-member", base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), 1, base.maxEmbeddedDepth(), base.maxCompressionRatio(), true,
        base.allowedMimeTypes(), base.excludedMimeTypes());
    var structured = new StructuredContentExtractor(policy.maxExtractedChars(), policy);
    // Force the structured attempt to fail before parsing, so the production fallback uses a
    // real archive and must preserve the caller's one-resource policy.
    var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
    org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("structured failure"))
        .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    var field = StructuredContentExtractor.class.getDeclaredField("parser");
    field.setAccessible(true);
    field.set(structured, parser);
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
        () -> structured.extractWithStatus(file));
    assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
  }

  private PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy policy) {
    return new PolicyDrivenTikaExtractor(workers -> {
      throw new AssertionError("OCR is disabled");
    }, policy);
  }

  private TikaExtractionPolicy ratioPolicy(double ratio) {
    TikaExtractionPolicy base = TikaExtractionPolicy.defaults();
    return new TikaExtractionPolicy("strict-ratio", base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), base.maxEmbeddedResources(), base.maxEmbeddedDepth(), ratio,
        true, base.allowedMimeTypes(), base.excludedMimeTypes());
  }

  private Path commentedDocument(FormatId format, String member, int commentBytes) throws Exception {
    Map<String, byte[]> members = new LinkedHashMap<>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(
        FormatCapabilityFixtureFactory.generate(format).bytes()))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        byte[] content = input.readAllBytes();
        if (entry.getName().equals(member)) {
          String xml = new String(content, StandardCharsets.UTF_8);
          int declaration = xml.startsWith("<?xml") ? xml.indexOf("?>") + 2 : 0;
          content = bytes(xml.substring(0, declaration) + "<!--" + "Z".repeat(commentBytes)
              + "-->" + xml.substring(declaration));
        }
        members.put(entry.getName(), content);
      }
    }
    assertTrue(members.containsKey(member), "Fixture must contain the parser-internal member");
    return archive(format.name().toLowerCase(java.util.Locale.ROOT) + "-" + commentBytes
        + (format == FormatId.ODT ? ".odt" : ".xlsx"), members);
  }

  private Path archive(String name, Map<String, byte[]> members) throws Exception {
    return Files.write(tempDir.resolve(name), zip(members));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zip(Map<String, byte[]> members) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var member : members.entrySet()) {
        zip.putNextEntry(new ZipEntry(member.getKey()));
        zip.write(member.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
