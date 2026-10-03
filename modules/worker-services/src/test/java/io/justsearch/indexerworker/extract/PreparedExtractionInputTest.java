/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedExtractionInputTest {
  @TempDir Path tempDir;

  @Test
  void streamTextAvoidsSnapshotsAndPdfReusesOneThroughEveryEntryPoint() throws Exception {
    List<Path> files = List.of(
        Files.writeString(tempDir.resolve("plain.txt"), "searchable ordinary text ".repeat(512)
            .substring(0, 10 * 1024)),
        Files.writeString(tempDir.resolve("page.html"), "<html><body>searchable html</body></html>"),
        Files.write(tempDir.resolve("document.pdf"), pdf()));
    for (Path file : files) {
      int expectedSnapshots = file.getFileName().toString().endsWith(".pdf") ? 1 : 0;
      var factory = new ObservedFactory();
      try (var flat = new ContentExtractor(100_000, factory)) {
        assertFalse(flat.extract(file).content().isBlank());
        assertEquals(1, factory.preparations);
        assertEquals(expectedSnapshots, factory.snapshots.size());
        for (Path snapshot : factory.snapshots) assertFalse(Files.exists(snapshot));
      }
      factory = new ObservedFactory();
      try (var structured = new StructuredContentExtractor(100_000,
          TikaExtractionPolicy.defaults(), factory)) {
        assertFalse(structured.extract(file).content().isBlank());
        assertEquals(1, factory.preparations);
        assertEquals(expectedSnapshots, factory.snapshots.size());
        for (Path snapshot : factory.snapshots) assertFalse(Files.exists(snapshot));
      }
      factory = new ObservedFactory();
      try (var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
        assertFalse(extractor.extractArtifact(file).result().content().isBlank());
        assertEquals(1, factory.preparations);
        assertEquals(expectedSnapshots, factory.snapshots.size(),
            "PDF parsing and visual evidence must share one private file");
        for (Path snapshot : factory.snapshots) assertFalse(Files.exists(snapshot));
      }
    }
  }


  @Test
  void fileLazilyMaterializesCapturedBytesOnceAndDeletesThemOnClose() throws Exception {
    byte[] original = bytes("captured private text ".repeat(512));
    Path source = Files.write(tempDir.resolve("Original.TXT"), original);
    var factory = new ObservedFactory();
    try (factory) {
      try (var input = factory.prepare(source, TikaExtractionPolicy.defaults())) {
        assertTrue(factory.snapshots.isEmpty());
        assertFalse(input.isDiskBacked());
        assertEquals("Original.TXT", input.name());
        assertEquals(original.length, input.size());
        Files.delete(source);
        Path snapshot = input.file();
        assertEquals(snapshot, input.file());
        assertEquals(1, factory.snapshots.size());
        assertFalse(source.equals(snapshot));
        assertArrayEquals(original, Files.readAllBytes(snapshot));
        for (int replay = 0; replay < 2; replay++) {
          try (var stream = input.openStream()) {
            assertArrayEquals(original, stream.readAllBytes());
          }
        }
      }
      assertFalse(Files.exists(factory.snapshots.getFirst()));
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void allowListedDetectionMatchesFileDetectionWithOriginalNameAndCapturedLength() throws Exception {
    var samples = java.util.Map.of(
        "plain.txt", "searchable ordinary text",
        "notes.md", "# Searchable heading\n\nordinary text",
        "table.csv", "name,value\nsearchable,1\n",
        "page.html", "<html><body>searchable html</body></html>",
        "document.xml", "<?xml version=\"1.0\"?><document>searchable xml</document>",
        "document.json", "{\"text\":\"searchable json\"}");
    var tika = new org.apache.tika.Tika(TextNameMagicConflictDetector.wrapDefault());
    for (var sample : samples.entrySet()) {
      Path source = Files.writeString(tempDir.resolve(sample.getKey()), sample.getValue());
      String expected = tika.detect(source);
      assertTrue(PreparedExtractionInput.STREAM_ONLY_MIME_TYPES.contains(expected), expected);
      var factory = new ObservedFactory();
      try (factory; var input = factory.prepare(source, TikaExtractionPolicy.defaults())) {
        Files.delete(source);
        assertEquals(sample.getKey(), input.metadata().get(
            org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY));
        assertEquals(Long.toString(input.size()), input.metadata().get(
            org.apache.tika.metadata.Metadata.CONTENT_LENGTH));
        assertEquals(expected, input.detect(tika));
        try (var stream = input.openParserStream(tika)) {
          assertArrayEquals(bytes(sample.getValue()), stream.readAllBytes());
        }
        assertTrue(factory.snapshots.isEmpty(), sample.getKey());
      }
    }
  }

  @Test
  void nonContainerMemoryThresholdSpillsOnlyLargerInputsAndReleasesHeapBytes() throws Exception {
    int threshold = PreparedExtractionInput.MAX_IN_MEMORY_BYTES;
    assertEquals(1024 * 1024, threshold);
    var chunksField = PreparedExtractionInput.class.getDeclaredField("chunks");
    chunksField.setAccessible(true);
    var factory = new ObservedFactory();
    try (factory) {
      for (int length : new int[] {threshold - 1, threshold, threshold + 1}) {
        byte[] original = bytes("x".repeat(length));
        Path source = Files.write(tempDir.resolve("plain-" + length + ".txt"), original);
        try (var input = factory.prepare(source, TikaExtractionPolicy.defaults())) {
          assertEquals(length, input.size());
          var chunks = (List<?>) chunksField.get(input);
          if (length > threshold) {
            assertEquals(1, factory.snapshots.size(), "Large non-containers must spill");
            assertEquals(factory.snapshots.getFirst(), input.file());
            assertEquals(length, Files.size(input.file()));
            assertTrue(chunks.isEmpty(), "Spilling must release the captured heap chunks");
          } else {
            assertTrue(factory.snapshots.isEmpty(), "Inputs at or below 1 MiB stay in memory");
            assertFalse(chunks.isEmpty());
          }
          Files.delete(source);
          // Both storage paths must replay the captured bytes without reopening the source.
          try (var stream = input.openStream()) {
            assertArrayEquals(original, stream.readAllBytes());
          }
        }
      }
      assertFalse(Files.exists(factory.snapshots.getFirst()));
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void largeNonContainerSpillsIntoTheSamePrivateDirectoryAsZipCandidates() throws Exception {
    Path archive = Files.write(tempDir.resolve("archive.zip"), zip("ordinary archive text"));
    Path large = Files.writeString(tempDir.resolve("large.txt"),
        "x".repeat(PreparedExtractionInput.MAX_IN_MEMORY_BYTES + 1));
    var factory = new ObservedFactory();
    try (factory) {
      for (Path source : List.of(archive, large)) {
        try (var input = factory.prepare(source, TikaExtractionPolicy.defaults())) {
          assertEquals(1 + (source.equals(large) ? 1 : 0), factory.snapshots.size());
          assertTrue(Files.exists(input.file()));
        }
      }
      assertEquals(factory.snapshots.get(0).getParent(), factory.snapshots.get(1).getParent());
      for (Path snapshot : factory.snapshots) assertFalse(Files.exists(snapshot));
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void fullPolicyPathAndFlatFallbackPrepareOnlyOnce() throws Exception {
    Path file = Files.write(tempDir.resolve("document.zip"), zip("original searchable text"));
    var factory = new ObservedFactory();
    try (var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
      var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
      structuredField.setAccessible(true);
      Object structured = structuredField.get(extractor);
      var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
      org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("force flat fallback"))
          .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
      parserField.setAccessible(true);
      parserField.set(structured, parser);
      assertTrue(extractor.extract(file).content().contains("original searchable text"));
      assertEquals(1, factory.preparations);
      assertEquals(1, factory.snapshots.size());
      assertFalse(Files.exists(factory.snapshots.getFirst()));
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void sourceReplacementCannotChangeContainerOrNonContainerParsedBytes() throws Exception {
    for (boolean container : new boolean[] {false, true}) {
      Path source = Files.write(tempDir.resolve(container ? "source.zip" : "source.txt"),
          container ? zip("original searchable text") : bytes("original searchable text"));
      var factory = new ObservedFactory() {
        @Override
        PreparedExtractionInput prepare(Path file, TikaExtractionPolicy policy)
            throws IOException, ContentExtractor.ExtractionException {
          var input = super.prepare(file, policy);
          Files.write(file, zip("Z".repeat(128 * 1024)));
          return input;
        }
      };
      try (var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
        String result = extractor.extract(source).content();
        assertTrue(result.contains("original searchable text"));
        assertFalse(result.contains("ZZZZ"));
        assertEquals(1, factory.preparations);
        assertEquals(container ? 1 : 0, factory.snapshots.size());
      }
    }
  }

  @Test
  void byteBoundAppliesToOpenedStreamEvenWhenSizeUnderstatesIt() throws Exception {
    Path source = Files.write(tempDir.resolve("growing.txt"), new byte[16]);
    var factory = new ObservedFactory() {
      @Override
      InputStream openSource(Path file) {
        return new ByteArrayInputStream(new byte[65]);
      }
    };
    try (factory) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> factory.prepare(source, sizePolicy(64)));
      assertEquals("INPUT_TOO_LARGE", failure.reasonCode());
      assertTrue(factory.snapshots.isEmpty());
    }
  }

  @Test
  void exactByteBoundaryIsAcceptedAndReplaysWithoutReopeningSource() throws Exception {
    byte[] original = bytes("x".repeat(8192));
    Path source = Files.write(tempDir.resolve("boundary.txt"), original);
    try (var factory = new PreparedExtractionInput.Factory();
        var input = factory.prepare(source, sizePolicy(original.length))) {
      Files.delete(source);
      assertEquals(original.length, input.size());
      for (int attempt = 0; attempt < 2; attempt++) {
        try (var stream = input.openStream()) {
          assertArrayEquals(original, stream.readAllBytes());
        }
      }
    }
  }

  @Test
  void disguisedZipAndPreambleAcrossBufferBoundaryRetainExpansionBudget() throws Exception {
    for (int preamble : new int[] {0, 8190}) {
      var content = new ByteArrayOutputStream();
      content.write(bytes("x".repeat(preamble)));
      content.write(zip("Z".repeat(128 * 1024)));
      Path source = Files.write(tempDir.resolve("disguised-" + preamble + ".txt"), content.toByteArray());
      var factory = new ObservedFactory();
      try (factory) {
        var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
            () -> factory.prepare(source, TikaExtractionPolicy.defaults()));
        assertEquals("ARCHIVE_EXPANSION_LIMIT", failure.reasonCode());
        assertEquals(1, factory.snapshots.size());
        assertFalse(Files.exists(factory.snapshots.getFirst()));
      }
    }
  }

  @Test
  void containersReusePrivateDirectoryAndDeleteEachSnapshot() throws Exception {
    Path source = Files.write(tempDir.resolve("source.zip"), zip("ordinary archive text"));
    var factory = new ObservedFactory();
    try (factory) {
      for (int i = 0; i < 2; i++) {
        try (var input = factory.prepare(source, TikaExtractionPolicy.defaults())) {
          assertTrue(Files.exists(input.file()));
        }
        assertFalse(Files.exists(factory.snapshots.get(i)));
      }
      assertEquals(factory.snapshots.get(0).getParent(), factory.snapshots.get(1).getParent());
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void optionalLocalPreparationTiming() throws Exception {
    assumeTrue(Boolean.getBoolean("justsearch.s5.preparationTiming"));
    Path source = Files.writeString(tempDir.resolve("timing.txt"), "SciFact ordinary text ".repeat(200));
    var policy = TikaExtractionPolicy.defaults();
    int iterations = 100;
    long oldStarted = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Path directory = Files.createTempDirectory(tempDir, "old-snapshot-");
      Path snapshot = directory.resolve(source.getFileName());
      try {
        Files.copy(source, snapshot);
        new ContainerExpansionBudget(policy, Files.size(snapshot)).inspect(snapshot);
      } finally {
        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(directory);
      }
    }
    long oldNanos = System.nanoTime() - oldStarted;
    var factory = new ObservedFactory();
    long newStarted = System.nanoTime();
    try (factory) {
      for (int i = 0; i < iterations; i++) {
        try (var input = factory.prepare(source, policy); var stream = input.openStream()) {
          assertEquals(Files.size(source), stream.transferTo(java.io.OutputStream.nullOutputStream()));
        }
      }
    }
    long newNanos = System.nanoTime() - newStarted;
    assertTrue(factory.snapshots.isEmpty());
    System.out.printf("S5 preparation, %d docs: old snapshot %.3f ms/doc, memory %.3f ms/doc%n",
        iterations, oldNanos / 1_000_000.0 / iterations, newNanos / 1_000_000.0 / iterations);
  }

  private PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy policy, ObservedFactory factory) {
    return new PolicyDrivenTikaExtractor(workers -> { throw new AssertionError("OCR disabled"); },
        policy, OcrRoutingConfig.disabled(), OcrMetricCatalog.noop(),
        ExtractionFallbackBudget.defaults(), factory);
  }

  private static TikaExtractionPolicy sizePolicy(long size) {
    var base = TikaExtractionPolicy.defaults();
    return new TikaExtractionPolicy("small-input", base.maxExtractedChars(), size,
        Math.min(size, base.maxOfficeInputBytes()), base.maxMetadataEntries(),
        base.maxMetadataKeyChars(), base.maxMetadataValueChars(), base.maxEmbeddedResources(),
        base.maxEmbeddedDepth(), base.maxCompressionRatio(), true,
        base.allowedMimeTypes(), base.excludedMimeTypes());
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

  private static byte[] pdf() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText("searchable ordinary PDF text");
        content.endText();
      }
      document.save(bytes);
    }
    return bytes.toByteArray();
  }

  private static byte[] zip(String text) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("content.txt"));
      zip.write(bytes(text));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static class ObservedFactory extends PreparedExtractionInput.Factory {
    int preparations;
    final List<Path> snapshots = new ArrayList<>();

    @Override
    PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
        throws IOException, ContentExtractor.ExtractionException {
      preparations++;
      return super.prepare(source, policy);
    }

    @Override
    synchronized Path createSnapshot(Path source) throws IOException {
      Path snapshot = super.createSnapshot(source);
      snapshots.add(snapshot);
      return snapshot;
    }
  }
}
