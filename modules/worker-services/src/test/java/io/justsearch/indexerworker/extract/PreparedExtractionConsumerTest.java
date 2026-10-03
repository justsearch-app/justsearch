/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedExtractionConsumerTest {
  @TempDir Path tempDir;

  @Test
  void pdfEvidenceKeepsSpilledSnapshotsFileBackedWithoutReopeningSource() throws Exception {
    for (boolean spilled : new boolean[] {false, true}) {
      byte[] original = spilled ? largePdf() : pdf();
      Path source = Files.write(tempDir.resolve(spilled ? "large.pdf" : "small.pdf"), original);
      try (var factory = new PreparedExtractionInput.Factory();
          var observed = org.mockito.Mockito.spy(factory.prepare(source, TikaExtractionPolicy.defaults()))) {
        assertEquals(spilled, observed.isDiskBacked());
        if (spilled) {
          assertTrue(original.length > PreparedExtractionInput.MAX_IN_MEMORY_BYTES);
        } else {
          assertTrue(original.length <= PreparedExtractionInput.MAX_IN_MEMORY_BYTES);
        }
        org.mockito.Mockito.doThrow(new AssertionError("PDF evidence must use its snapshot file"))
            .when(observed).openStream();
        Files.delete(source);
        var summary = PdfVisualAnalyzer.enrich(observed, StructuredDocumentSummary.empty());
        assertEquals(1, summary.pageCount(), "Evidence must come from the captured PDF");
        org.mockito.Mockito.verify(observed, org.mockito.Mockito.never()).openStream();
        org.mockito.Mockito.verify(observed).file();
      }
    }
  }

  @Test
  void fullPolicyPathAndFlatFallbackPrepareOnlyOnce() throws Exception {
    Path file = Files.write(tempDir.resolve("document.zip"), zip("original searchable text"));
    var factory = new ObservedFactory();
    try (var independentFactories = org.mockito.Mockito.mockConstruction(
            PreparedExtractionInput.Factory.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
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
      assertEquals(1, independentFactories.constructed().size(), "Exercise the flat fallback factory");
      assertNoIndependentPreparations(independentFactories);
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void preparationGuardRejectsTheIndependentPublicFallbackMutation() throws Exception {
    Path source = Files.write(tempDir.resolve("mutation.zip"), zip("original searchable text"));
    try (var factory = new PreparedExtractionInput.Factory();
        var input = factory.prepare(source, TikaExtractionPolicy.defaults());
        var independentFactories = org.mockito.Mockito.mockConstruction(
            PreparedExtractionInput.Factory.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS))) {
      // Reproduce the reviewer's mutation: the public Path entry point prepares again through
      // a separately constructed default factory. It still extracts the expected text.
      try (var fallback = new ContentExtractor(100_000)) {
        assertTrue(fallback.extract(input.file()).content().contains("original searchable text"));
      }
      assertEquals(1, independentFactories.constructed().size());
      assertThrows(AssertionError.class, () -> assertNoIndependentPreparations(independentFactories));
    }
  }


  @Test
  void delayedOoxmlIdentificationEnforcesPolicyAdmissionBeforeParsing() throws Exception {
    for (boolean exclude : new boolean[] {false, true}) {
      Path source = delayedOfficeDocument(exclude ? "excluded.bin" : "oversized.bin");
      var base = TikaExtractionPolicy.defaults();
      var policy = new TikaExtractionPolicy("office-admission", base.maxExtractedChars(),
          base.maxInputBytes(), 1024, base.maxMetadataEntries(), base.maxMetadataKeyChars(),
          base.maxMetadataValueChars(), base.maxEmbeddedResources(), base.maxEmbeddedDepth(),
          base.maxCompressionRatio(), true, base.allowedMimeTypes(),
          exclude ? java.util.Set.of(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
              : base.excludedMimeTypes());
      var factory = sourceDeletingFactory();
      try (var extractor = extractor(policy, factory)) {
        var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
        structuredField.setAccessible(true);
        var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
        parserField.setAccessible(true);
        var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
        parserField.set(structuredField.get(extractor), parser);
        if (exclude) {
          var failure = assertThrows(ContentExtractor.ExtractionException.class,
              () -> extractor.extract(source));
          assertEquals("MIME type excluded by extraction policy", failure.getMessage());
        } else {
          var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
              () -> extractor.extract(source));
          assertEquals("OFFICE_INPUT_TOO_LARGE", failure.reasonCode());
        }
        org.mockito.Mockito.verifyNoInteractions(parser);
        assertEquals(1, factory.preparations);
        assertEquals(1, factory.snapshots.size());
        assertFalse(Files.exists(factory.snapshots.getFirst()));
      }
      assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
    }
  }

  @Test
  void declaredOfficeTypeSupplementsButNeverReplacesTheDetectedMimeForPolicyAdmission()
      throws Exception {
    // A malformed OPC package detects as application/zip while declaring a Word main part.
    // Excluding ZIP must still reject it before parsing: the declaration adds Office limits and
    // exclusions, it does not relabel the input that the parser will see.
    Path source = delayedOfficeDocument("zip-excluded.bin");
    var base = TikaExtractionPolicy.defaults();
    var policy = new TikaExtractionPolicy("zip-exclusion", base.maxExtractedChars(),
        base.maxInputBytes(), base.maxOfficeInputBytes(), base.maxMetadataEntries(),
        base.maxMetadataKeyChars(), base.maxMetadataValueChars(), base.maxEmbeddedResources(),
        base.maxEmbeddedDepth(), base.maxCompressionRatio(), true, base.allowedMimeTypes(),
        java.util.Set.of("application/zip"));
    var factory = sourceDeletingFactory();
    try (var extractor = extractor(policy, factory)) {
      var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
      structuredField.setAccessible(true);
      var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
      parserField.setAccessible(true);
      var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
      parserField.set(structuredField.get(extractor), parser);
      var failure = assertThrows(ContentExtractor.ExtractionException.class,
          () -> extractor.extract(source));
      assertEquals("MIME type excluded by extraction policy", failure.getMessage());
      org.mockito.Mockito.verifyNoInteractions(parser);
    }
  }

  @Test
  void delayedOoxmlIdentificationEnforcesBothStandaloneOfficeLimitsBeforeParsing() throws Exception {
    Path original = delayedOfficeDocument("standalone.bin");
    assertTrue(Files.size(original) > TikaExtractionPolicy.DEFAULT_MAX_OFFICE_INPUT_BYTES);
    for (boolean structured : new boolean[] {false, true}) {
      Path source = Files.copy(original, tempDir.resolve(structured ? "structured.bin" : "flat.bin"));
      var factory = sourceDeletingFactory();
      if (structured) {
        try (var extractor = new StructuredContentExtractor(100_000,
            TikaExtractionPolicy.defaults(), factory)) {
          var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
          parserField.setAccessible(true);
          var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
          parserField.set(extractor, parser);
          var failure = assertThrows(ContentExtractor.ExtractionException.class,
              () -> extractor.extract(source));
          assertTrue(failure.getMessage().startsWith("Office file too large:"));
          org.mockito.Mockito.verifyNoInteractions(parser);
        }
      } else {
        try (var extractor = new ContentExtractor(100_000, factory)) {
          var tikaField = ContentExtractor.class.getDeclaredField("tika");
          tikaField.setAccessible(true);
          var tika = (org.apache.tika.Tika) tikaField.get(extractor);
          var parser = org.mockito.Mockito.mock(org.apache.tika.parser.Parser.class);
          tikaField.set(extractor, new org.apache.tika.Tika(tika.getDetector(), parser));
          var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
              () -> extractor.extract(source));
          assertEquals("OFFICE_INPUT_TOO_LARGE", failure.reasonCode());
          org.mockito.Mockito.verifyNoInteractions(parser);
        }
      }
      assertEquals(1, factory.preparations);
      assertEquals(1, factory.snapshots.size());
      assertFalse(Files.exists(factory.snapshots.getFirst()));
      assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
    }
  }

  @Test
  void tiffDimensionGuardsUseOnePrivateFileWithoutCachingSpilledBytesInHeap() throws Exception {
    var guard = PolicyDrivenTikaExtractor.class.getDeclaredMethod("imageWithinConfiguredGuards",
        Path.class, String.class, PreparedExtractionInput.class);
    guard.setAccessible(true);
    for (boolean spilled : new boolean[] {false, true}) {
      Path source = writeTiffWithLateDirectory(spilled ? "large.tif" : "small.tif", spilled);
      for (boolean pixelGuard : new boolean[] {false, true}) {
        Path candidate = Files.copy(source, tempDir.resolve("guard-" + spilled + "-" + pixelGuard + ".tif"));
        var factory = new ObservedFactory();
        var config = new OcrRoutingConfig(false, List.of("eng"), 10_000, 1,
            pixelGuard ? null : 10, pixelGuard ? 100 : null, null, null);
        try (factory;
            var extractor = new PolicyDrivenTikaExtractor(
                workers -> { throw new AssertionError("Dimension rejection must precede OCR"); },
                TikaExtractionPolicy.defaults(), config);
            var input = org.mockito.Mockito.spy(factory.prepare(candidate, TikaExtractionPolicy.defaults()))) {
          assertEquals(spilled, input.isDiskBacked());
          assertEquals(spilled ? 1 : 0, factory.snapshots.size());
          org.mockito.Mockito.doThrow(new AssertionError("Dimensions must use file random access"))
              .when(input).openStream();
          Files.delete(candidate);
          assertEquals(false, guard.invoke(extractor, candidate, "image/tiff", input));
          assertEquals(false, guard.invoke(extractor, candidate, "image/tiff", input));
          org.mockito.Mockito.verify(input, org.mockito.Mockito.never()).openStream();
          assertEquals(1, factory.snapshots.size());
          assertEquals(input.file(), input.file());
        }
        assertFalse(Files.exists(factory.snapshots.getFirst()));
        assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
      }
    }
  }

  @Test
  void fullImageExtractionSharesOneSnapshotAndRecordsDimensionRejection() throws Exception {
    Path source = tempDir.resolve("guarded.png");
    assertTrue(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(
        64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", source.toFile()));
    var factory = sourceDeletingFactory();
    var config = new OcrRoutingConfig(false, List.of("eng"), 10_000, 1, 10, null, null, null);
    try (var extractor = new PolicyDrivenTikaExtractor(
        workers -> { throw new AssertionError("Dimension rejection must precede OCR"); },
        TikaExtractionPolicy.defaults(), config, OcrMetricCatalog.noop(),
        ExtractionFallbackBudget.defaults(), factory)) {
      var artifact = extractor.extractArtifact(source);
      assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"size\""));
      assertEquals(1, factory.preparations);
      assertEquals(1, factory.snapshots.size());
      assertFalse(Files.exists(factory.snapshots.getFirst()));
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void malformedOfficePackagesApplyAdmissionToWordExcelPowerPointAndMacroWord() throws Exception {
    String[][] formats = {
        {"word/document.xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
        {"xl/workbook.xml",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
        {"ppt/presentation.xml",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
        {"word/document.xml", "application/vnd.ms-word.document.macroEnabled.main+xml",
            "application/vnd.ms-word.document.macroenabled.12"}};
    for (int i = 0; i < formats.length; i++) {
      String[] format = formats[i];
      Path source = malformedOfficePackage("format-" + i + ".bin", format[0], format[1]);
      var factory = sourceDeletingFactory();
      var base = TikaExtractionPolicy.defaults();
      var policy = new TikaExtractionPolicy("office-family", base.maxExtractedChars(),
          base.maxInputBytes(), 1, base.maxMetadataEntries(), base.maxMetadataKeyChars(),
          base.maxMetadataValueChars(), base.maxEmbeddedResources(), base.maxEmbeddedDepth(),
          base.maxCompressionRatio(), true, base.allowedMimeTypes(), base.excludedMimeTypes());
      try (var extractor = extractor(policy, factory)) {
        var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
            () -> extractor.extract(source));
        assertEquals("OFFICE_INPUT_TOO_LARGE", failure.reasonCode(), format[2]);
        assertEquals(1, factory.snapshots.size());
      }
      Path excludedSource = malformedOfficePackage("excluded-format-" + i + ".bin", format[0], format[1]);
      policy = new TikaExtractionPolicy("excluded-office-family", base.maxExtractedChars(),
          base.maxInputBytes(), base.maxOfficeInputBytes(), base.maxMetadataEntries(),
          base.maxMetadataKeyChars(), base.maxMetadataValueChars(), base.maxEmbeddedResources(),
          base.maxEmbeddedDepth(), base.maxCompressionRatio(), true, base.allowedMimeTypes(),
          java.util.Set.of(format[2]));
      factory = sourceDeletingFactory();
      try (var extractor = extractor(policy, factory)) {
        var failure = assertThrows(ContentExtractor.ExtractionException.class,
            () -> extractor.extract(excludedSource));
        assertEquals("MIME type excluded by extraction policy", failure.getMessage(), format[2]);
        assertEquals(1, factory.snapshots.size());
      }
    }
  }

  @Test
  void officeFallbackRequiresAnExistingMainPartAndRejectsConflictingDeclarations() throws Exception {
    Path absent = malformedOfficePackage("absent.bin", "absent.xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
    Path conflicting = tempDir.resolve("conflicting.bin");
    try (var zip = new ZipOutputStream(Files.newOutputStream(conflicting))) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(bytes("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
          + "</Types>"));
      zip.closeEntry();
      for (String name : List.of("word/document.xml", "xl/workbook.xml", "untyped.bin")) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes("untyped part"));
        zip.closeEntry();
      }
    }
    var tika = new org.apache.tika.Tika(TextNameMagicConflictDetector.wrapDefault());
    try (var factory = new PreparedExtractionInput.Factory()) {
      try (var input = factory.prepare(absent, TikaExtractionPolicy.defaults())) {
        assertEquals("application/zip", input.detect(tika));
        assertEquals(null, input.declaredOfficeType());
      }
      try (var input = factory.prepare(conflicting, TikaExtractionPolicy.defaults())) {
        // Conflicting declarations are rejected by the supplemental Office admission check.
        var failure = assertThrows(IOException.class, input::declaredOfficeType);
        assertEquals("Could not inspect Office content types", failure.getMessage());
      }
    }
  }

  @Test
  void declaredOfficeTypeFacesExclusionsButNotTheAllowList() throws Exception {
    // An allow-list naming only ZIP admits a ZIP-detected package even though its declared Word
    // type is not on the allow-list: the declaration supplements exclusions, not the allow-list.
    Path source = delayedOfficeDocument("allow-listed-zip.bin");
    var base = TikaExtractionPolicy.defaults();
    var policy = new TikaExtractionPolicy("zip-allow-list", base.maxExtractedChars(),
        base.maxInputBytes(), base.maxOfficeInputBytes(), base.maxMetadataEntries(),
        base.maxMetadataKeyChars(), base.maxMetadataValueChars(), base.maxEmbeddedResources(),
        base.maxEmbeddedDepth(), base.maxCompressionRatio(), true,
        java.util.Set.of("application/zip"), base.excludedMimeTypes());
    var tika = new org.apache.tika.Tika(TextNameMagicConflictDetector.wrapDefault());
    try (var factory = new PreparedExtractionInput.Factory();
        var input = factory.prepare(source, policy)) {
      assertEquals("application/zip", input.detect(tika));
      assertTrue(policy.permitsMimeType(input.detect(tika)));
      String declared = input.declaredOfficeType();
      assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          declared);
      assertFalse(policy.excludesMimeType(declared));
    }
    // Through production admission: the extractor must not refuse it as an excluded MIME type.
    try (var extractor = extractor(policy, sourceDeletingFactory())) {
      var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
      structuredField.setAccessible(true);
      var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
      parserField.setAccessible(true);
      parserField.set(structuredField.get(extractor),
          org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class));
      try {
        extractor.extract(source);
      } catch (ContentExtractor.ExtractionException e) {
        org.junit.jupiter.api.Assertions.assertNotEquals(
            "MIME type excluded by extraction policy", e.getMessage());
      }
    }
  }

  @Test
  void malformedContentTypesInAGenericZipIsNoOfficeDeclaration() throws Exception {
    Path generic = tempDir.resolve("generic.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(generic))) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(bytes("<not-closed"));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("data.txt"));
      zip.write(bytes("plain member"));
      zip.closeEntry();
    }
    try (var factory = new PreparedExtractionInput.Factory();
        var input = factory.prepare(generic, TikaExtractionPolicy.defaults())) {
      assertEquals(null, input.declaredOfficeType());
    }
  }

  private Path malformedOfficePackage(String name, String mainPart, String mainType) throws IOException {
    Path source = tempDir.resolve(name);
    try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
      zip.putNextEntry(new ZipEntry("untyped.bin"));
      zip.write(bytes("unrelated part without a content type"));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(bytes("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Override PartName=\"/" + mainPart + "\" ContentType=\"" + mainType + "\"/>"
          + "</Types>"));
      zip.closeEntry();
      if (!mainPart.equals("absent.xml")) {
        zip.putNextEntry(new ZipEntry(mainPart));
        zip.write(bytes("<main/>"));
        zip.closeEntry();
      }
    }
    return source;
  }

  private ObservedFactory sourceDeletingFactory() {
    return new ObservedFactory() {
      @Override
      PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
          throws IOException, ContentExtractor.ExtractionException {
        var input = org.mockito.Mockito.spy(super.prepare(source, policy));
        Files.delete(source);
        if (input.isDiskBacked()) {
          org.mockito.Mockito.doThrow(new AssertionError("Spilled binary must use its snapshot file"))
              .when(input).openStream();
        }
        return input;
      }
    };
  }

  @Test
  void validDelayedOoxmlPackagesRetainOriginalS5OfficeRejections() throws Exception {
    Path original = delayedOfficeDocument("valid-delayed.bin", true);
    var factory = sourceDeletingFactory();
    Path policySource = Files.copy(original, tempDir.resolve("valid-policy.bin"));
    try (var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> extractor.extract(policySource));
      assertEquals("OFFICE_INPUT_TOO_LARGE", failure.reasonCode());
      assertEquals(1, factory.snapshots.size());
    }
    factory = sourceDeletingFactory();
    Path flatSource = Files.copy(original, tempDir.resolve("valid-flat.bin"));
    try (var extractor = new ContentExtractor(100_000, factory)) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> extractor.extract(flatSource));
      assertEquals("OFFICE_INPUT_TOO_LARGE", failure.reasonCode());
      assertEquals(1, factory.snapshots.size());
    }
    factory = sourceDeletingFactory();
    Path structuredSource = Files.copy(original, tempDir.resolve("valid-structured.bin"));
    try (var extractor = new StructuredContentExtractor(100_000,
        TikaExtractionPolicy.defaults(), factory)) {
      var failure = assertThrows(ContentExtractor.ExtractionException.class,
          () -> extractor.extract(structuredSource));
      assertTrue(failure.getMessage().startsWith("Office file too large:"));
      assertEquals(1, factory.snapshots.size());
    }
  }

  private Path delayedOfficeDocument(String name) throws IOException {
    return delayedOfficeDocument(name, false);
  }

  private Path delayedOfficeDocument(String name, boolean valid) throws IOException {
    Path source = tempDir.resolve(name);
    try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
      // Incompressible content before identifying OOXML parts exceeds both Tika's ordinary
      // stream ZIP-detection mark limit (16 MiB) and the standalone Office cap (30 MiB).
      zip.putNextEntry(new ZipEntry("padding.bin"));
      byte[] block = new byte[8192];
      var random = new java.util.Random(731);
      for (int i = 0; i < 31 * 1024 * 1024 / block.length; i++) {
        random.nextBytes(block);
        zip.write(block);
      }
      zip.closeEntry();
      try (var fixture = new java.util.zip.ZipInputStream(java.util.Objects.requireNonNull(
          getClass().getResourceAsStream("/fixtures/office/office-marker.docx")))) {
        ZipEntry entry;
        while ((entry = fixture.getNextEntry()) != null) {
          zip.putNextEntry(new ZipEntry(entry.getName()));
          if (valid && entry.getName().equals("[Content_Types].xml")) {
            String types = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            zip.write(bytes(types.replace("<Default Extension=\"xml\"",
                "<Default Extension=\"bin\" ContentType=\"application/octet-stream\"/><Default Extension=\"xml\"")));
          } else {
            fixture.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
    return source;
  }

  private Path writeTiffWithLateDirectory(String name, boolean spilled) throws IOException {
    int pixels = 640 * 480;
    int directoryOffset = spilled ? PreparedExtractionInput.MAX_IN_MEMORY_BYTES + 8 : pixels + 8;
    var bytes = java.nio.ByteBuffer.allocate(directoryOffset + 2 + 9 * 12 + 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    bytes.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(directoryOffset);
    bytes.position(directoryOffset);
    bytes.putShort((short) 9);
    // Single uncompressed grayscale strip at offset 8. The first IFD follows the pixel data
    // and, for the spilled case, a gap above 1 MiB. Memory caching would read through that gap.
    int[][] tags = {{256, 4, 640}, {257, 4, 480}, {258, 3, 8}, {259, 3, 1},
        {262, 3, 1}, {273, 4, 8}, {277, 3, 1}, {278, 4, 480}, {279, 4, pixels}};
    for (int[] tag : tags) {
      bytes.putShort((short) tag[0]).putShort((short) tag[1]).putInt(1).putInt(tag[2]);
    }
    bytes.putInt(0);
    return Files.write(tempDir.resolve(name), bytes.array());
  }

  private PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy policy, ObservedFactory factory) {
    return new PolicyDrivenTikaExtractor(workers -> { throw new AssertionError("OCR disabled"); },
        policy, OcrRoutingConfig.disabled(), OcrMetricCatalog.noop(),
        ExtractionFallbackBudget.defaults(), factory);
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

  private static void assertNoIndependentPreparations(
      org.mockito.MockedConstruction<PreparedExtractionInput.Factory> factories)
      throws IOException, ContentExtractor.ExtractionException {
    for (var factory : factories.constructed()) {
      org.mockito.Mockito.verify(factory, org.mockito.Mockito.never()).prepare(
          org.mockito.ArgumentMatchers.any(Path.class),
          org.mockito.ArgumentMatchers.any(TikaExtractionPolicy.class));
    }
  }

  private static byte[] largePdf() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      var padding = new org.apache.pdfbox.pdmodel.common.PDStream(document);
      try (var output = padding.createOutputStream()) {
        // No compression filter: retain a valid PDF with a content stream above the spill cap.
        output.write(bytes(" ".repeat(PreparedExtractionInput.MAX_IN_MEMORY_BYTES + 1)));
      }
      page.setContents(padding);
      document.save(bytes);
    }
    return bytes.toByteArray();
  }

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
