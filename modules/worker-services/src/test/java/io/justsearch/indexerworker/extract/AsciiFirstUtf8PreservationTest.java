/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AsciiFirstUtf8PreservationTest {
  @TempDir Path tempDir;

  @ParameterizedTest(name = "pureAsciiKeepsTextAndCharset(length {0})")
  @ValueSource(ints = {1, 16_384, 16_385, 1_048_577})
  void pureAsciiKeepsTextAndCharset(int length) throws Exception {
    String text = "a".repeat(length);
    assertTextAndCharset(text.getBytes(StandardCharsets.US_ASCII), text, "windows-1252");
  }

  static Stream<Arguments> legacyTails() {
    return Stream.of(Arguments.of("windows-1252", "Grüße déjà café", "Grüße déjà café"),
        Arguments.of("KOI8-R", "Россия", "òÏÓÓÉÑ"),
        Arguments.of("windows-1251", "Россия", "Ðîññèÿ"),
        Arguments.of("ISO-8859-8", "ספרים", "ñôøéí"));
  }

  @ParameterizedTest(name = "legacySuffixKeepsLiteralTextAndCharset({0})")
  @MethodSource("legacyTails")
  void legacySuffixKeepsLiteralTextAndCharset(String encoding, String tail, String expected) throws Exception {
    String prefix = "a".repeat(16_384);
    assertTextAndCharset((prefix + tail).getBytes(Charset.forName(encoding)), prefix + expected, "windows-1252");
  }

  static Stream<Arguments> malformedTails() {
    return Stream.of(Arguments.of(new byte[] {(byte) 0xFF}, "ÿ"),
        Arguments.of(new byte[] {(byte) 0xC0, (byte) 0xAF}, "À¯"),
        Arguments.of(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "í €"),
        Arguments.of(new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, "ô�€€"),
        Arguments.of(new byte[] {(byte) 0xC1}, "Á"),
        Arguments.of(new byte[] {(byte) 0xF5}, "õ"),
        Arguments.of(new byte[] {(byte) 0xF8}, "ø"),
        Arguments.of(new byte[] {(byte) 0xE0, (byte) 0x80}, "à€"),
        Arguments.of(new byte[] {(byte) 0xF0, (byte) 0x80}, "ð€"),
        Arguments.of(new byte[] {(byte) 0x80}, "€"),
        Arguments.of(new byte[] {(byte) 0xE2}, "â"),
        Arguments.of(new byte[] {(byte) 0xE2, (byte) 0x82}, "â‚"),
        Arguments.of(new byte[] {(byte) 0xF0, (byte) 0x90, (byte) 0x80}, "ð�€"),
        Arguments.of(new byte[] {(byte) 0xE2, 'a', (byte) 0xAC}, "âa¬"));
  }

  @ParameterizedTest(name = "invalidSnapshotSuffixKeepsDefault({index})")
  @MethodSource("malformedTails")
  void invalidSnapshotSuffixKeepsDefault(byte[] invalid, String tail) throws Exception {
    String prefix = "a".repeat(16_384);
    byte[] valid = (prefix + "é").getBytes(StandardCharsets.UTF_8);
    byte[] bytes = Arrays.copyOf(valid, valid.length + invalid.length);
    System.arraycopy(invalid, 0, bytes, valid.length, invalid.length);
    assertTextAndCharset(bytes, prefix + "Ã©" + tail, "windows-1252");
    // Capture must inspect beyond a parser's character cap, including incomplete EOF sequences.
    Path file = Files.write(tempDir.resolve("capped.txt"), bytes);
    try (var extractor = new ContentExtractor(16)) {
      var result = extractor.extract(file);
      assertEquals("a".repeat(16), result.content());
      assertTrue(result.mimeType().contains("charset=windows-1252"));
    }
  }

  @Test
  void validWindowStillWinsOverInvalidSnapshotSuffix() throws Exception {
    String prefix = "é" + "a".repeat(20_000);
    byte[] bytes = Arrays.copyOf(prefix.getBytes(StandardCharsets.UTF_8), 20_004);
    bytes[20_002] = (byte) 0xFF;
    bytes[20_003] = (byte) 0xE2;
    assertTextAndCharset(bytes, prefix + "��", "UTF-8");
  }

  @Test
  void firstCompleteSequenceAndEarlyCaptureBoundaryKeepUtf8() throws Exception {
    for (int offset : List.of(8191, 16_382)) {
      String text = "a".repeat(offset) + "é";
      assertTextAndCharset(text.getBytes(StandardCharsets.UTF_8), text, "UTF-8");
    }
  }

  @Test
  void copiedMetadataAndSequentialDocumentsNeverInheritSnapshotFact() throws Exception {
    Path file = Files.writeString(tempDir.resolve("root.txt"), "a".repeat(16_384) + "ספרים");
    var detector = detector();
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults())) {
      Metadata root = prepared.metadata();
      assertEquals(List.of("Content-Length", "resourceName"), Arrays.stream(root.names()).sorted().toList());
      Metadata copied = new Metadata();
      for (String key : root.names()) for (String value : root.getValues(key)) copied.add(key, value);
      byte[] bytes = ("a".repeat(16_384) + "é").getBytes(StandardCharsets.UTF_8);
      assertEquals(StandardCharsets.UTF_8, detector.detect(new ByteArrayInputStream(bytes), copied));
      try (var input = prepared.openStream()) {
        detector.detect(new java.io.BufferedInputStream(input), root);
      }
      assertEquals(StandardCharsets.UTF_8,
          detector.detect(new ByteArrayInputStream(bytes), new Metadata()));
      assertEquals(Charset.forName("windows-1252"), detector.detect(
          new ByteArrayInputStream("ASCII".getBytes(StandardCharsets.US_ASCII)), new Metadata()));
    }
  }

  @Test
  void concurrentDocumentsKeepIndependentFacts() throws Exception {
    var detector = detector();
    try (var owners = new TestEngineExecutors();
        var registration = owners.register(EngineExecutorSpec.virtual("utf8-preservation",
            EngineExecutorSpec.Kind.BACKGROUND, 1));
        var executor = registration.openVirtual()) {
      var tasks = new ArrayList<Callable<Charset>>();
      for (int i = 0; i < 16; i++) {
        boolean valid = i % 2 == 0;
        Path file = Files.write(tempDir.resolve("concurrent-" + i + ".txt"),
            ("a".repeat(16_384) + "é").getBytes(valid ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1));
        tasks.add(() -> {
          try (var factory = new PreparedExtractionInput.Factory();
              var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
              var input = new java.io.BufferedInputStream(prepared.openStream())) {
            Charset root = detector.detect(input, prepared.metadata());
            assertEquals(valid ? StandardCharsets.UTF_8 : Charset.forName("windows-1252"), root);
            return detector.detect(new ByteArrayInputStream(
                ("a".repeat(16_384) + "é").getBytes(StandardCharsets.UTF_8)), new Metadata());
          }
        });
      }
      var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) {
        // Ordinary metadata must derive its decision from its own stream.
        assertEquals(StandardCharsets.UTF_8, detector.detect(new ByteArrayInputStream(
            ("a".repeat(16_384) + "é").getBytes(StandardCharsets.UTF_8)), new Metadata()));
        assertEquals(StandardCharsets.UTF_8, results.get(i).get());
      }
    }
  }

  @ParameterizedTest(name = "embeddedBodiesKeepOriginalText({0})")
  @ValueSource(strings = {"zip", "attachment", "plain", "html"})
  void embeddedBodiesKeepOriginalText(String kind) throws Exception {
    String text = "a".repeat(16_384) + "é";
    Path file;
    if ("zip".equals(kind)) {
      file = tempDir.resolve("embedded.zip");
      try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var entry = new ZipEntry("child.txt");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        var crc = new java.util.zip.CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
      }
    } else {
      String body = "html".equals(kind) ? "<html><body><p>" + text + "</p></body></html>" : text;
      String headers = "From: writer@example.invalid\r\nTo: reader@example.invalid\r\n"
          + "Subject: preservation\r\nMIME-Version: 1.0\r\n";
      if ("attachment".equals(kind)) {
        headers += "Content-Type: multipart/mixed; boundary=part\r\n\r\n--part\r\n"
            + "Content-Type: text/plain\r\nContent-Disposition: attachment; filename=child.txt\r\n"
            + "Content-Transfer-Encoding: 8bit\r\n\r\n";
        body += "\r\n--part--\r\n";
      } else {
        headers += "Content-Type: text/" + kind + "\r\nContent-Transfer-Encoding: 8bit\r\n\r\n";
      }
      file = Files.writeString(tempDir.resolve("embedded.eml"), headers + body);
    }
    try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(flat, structured)) {
        String content = extractor.extract(file).content();
        assertTrue(content.contains(text),
            () -> kind + " output tail: " + content.substring(Math.max(0, content.length() - 80)));
      }
    }
  }

  @Test
  void nestedAndSiblingResourcesUseTheirOwnEncodingBytesOnRepeatedExtraction() throws Exception {
    String prefix = "a".repeat(16_384);
    String utf8 = prefix + "ספרים";
    String legacy = prefix + "Grüße déjà café";
    var nestedBytes = new java.io.ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(nestedBytes)) {
      stored(zip, "nested.txt", utf8.getBytes(StandardCharsets.UTF_8));
    }
    Path file = tempDir.resolve("siblings.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      stored(zip, "utf8.txt", utf8.getBytes(StandardCharsets.UTF_8));
      stored(zip, "legacy.txt", legacy.getBytes(Charset.forName("windows-1252")));
      stored(zip, "nested.zip", nestedBytes.toByteArray());
    }
    try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(flat, structured)) {
        for (int attempt = 0; attempt < 2; attempt++) {
          String content = extractor.extract(file).content();
          assertTrue(content.contains(utf8));
          assertTrue(content.contains(legacy));
          assertTrue(content.indexOf("ספרים") != content.lastIndexOf("ספרים"));
        }
      }
    }
  }

  private static void stored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    var entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(bytes.length);
    var crc = new java.util.zip.CRC32();
    crc.update(bytes);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(bytes);
    zip.closeEntry();
  }

  @ParameterizedTest(name = "rootCarrierDoesNotDisplaceDeclarationsOrBoms({0})")
  @ValueSource(strings = {"html-meta", "html-alias", "html-metadata", "xml", "xml-alias", "xhtml", "xhtml-alias", "utf8-bom", "utf16le", "utf16be"})
  void rootCarrierDoesNotDisplaceDeclarationsOrBoms(String kind) throws Exception {
    String prefix = "a".repeat(16_384);
    String extension = "txt";
    String source = prefix + "é";
    String expected = source;
    Charset encoding = StandardCharsets.UTF_8;
    switch (kind) {
      case "html-meta", "html-alias", "html-metadata" -> {
        extension = "html";
        String declaration = "html-alias".equals(kind) ? "latin1" : "windows-1252";
        source = "<html><head><meta charset=" + declaration + "></head><body><p>" + prefix + "é</p></body></html>";
        expected = prefix + "Ã©";
      }
      case "xml", "xml-alias", "xhtml", "xhtml-alias" -> {
        extension = kind.startsWith("xml") ? "xml" : "xhtml";
        String declaration = kind.endsWith("alias") ? "latin1" : "ISO-8859-1";
        source = "<?xml version=\"1.0\" encoding=\"" + declaration + "\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>" + prefix + "é</p></body></html>";
        expected = prefix + "Ã©";
      }
      case "utf8-bom" -> source = "\uFEFF" + source;
      case "utf16le" -> encoding = StandardCharsets.UTF_16LE;
      case "utf16be" -> encoding = StandardCharsets.UTF_16BE;
      default -> throw new AssertionError(kind);
    }
    Path file = Files.write(tempDir.resolve("precedence." + extension), source.getBytes(encoding));
    try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      assertEquals(expected, flat.extract(file).content().strip());
      assertEquals(expected, structured.extract(file).content().strip());
    }
    if ("html-metadata".equals(kind)) {
      try (var factory = new PreparedExtractionInput.Factory();
          var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
          var input = new java.io.BufferedInputStream(prepared.openStream())) {
        var metadata = prepared.metadata();
        metadata.set(Metadata.CONTENT_TYPE, "text/html; charset=ISO-8859-1");
        assertEquals(Charset.forName("windows-1252"), detector().detect(input, metadata));
      }
    }
  }

  @Test
  void completedUtf8WindowKeepsTodaysXmlDeclarationOutcome() throws Exception {
    Path file = Files.writeString(tempDir.resolve("completed.xhtml"),
        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>é</p></body></html>");
    try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      assertEquals("é", flat.extract(file).content().strip());
      assertEquals("é", structured.extract(file).content().strip());
    }
  }

  static Stream<Arguments> declaredLegacyCharsets() {
    return Stream.of("html", "txt").flatMap(extension -> Stream.of("ISO-8859-1", "windows-1252")
        .map(encoding -> Arguments.of(extension, encoding)));
  }

  @ParameterizedTest(name = "completeLegacyXmlDeclarationKeepsTodaysOutcome({0}, {1})")
  @MethodSource("declaredLegacyCharsets")
  void completeLegacyXmlDeclarationKeepsTodaysOutcome(String extension, String encoding) throws Exception {
    String declaration = "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>";
    String padding = "a".repeat(16_384);
    String source = declaration + "<html><body>" + padding + "ספרים</body></html>";
    String expected = padding + "×¡×¤×¨×™×�";
    Path file = Files.writeString(tempDir.resolve("declared." + extension), source);
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = new java.io.BufferedInputStream(prepared.openStream());
        var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      var metadata = prepared.metadata();
      metadata.set(Metadata.CONTENT_TYPE, "html".equals(extension) ? "text/html" : "text/plain");
      assertEquals(Charset.forName("windows-1252"), detector().detect(input, metadata));
      if ("txt".equals(extension)) {
        var handler = new org.apache.tika.sax.BodyContentHandler(-1);
        OwnedTikaParsers.create((stream, meta) -> org.apache.tika.mime.MediaType.TEXT_PLAIN)
            .parse(input, handler, metadata, new org.apache.tika.parser.ParseContext());
        assertEquals(declaration + "<html><body>" + padding + "×¡×¤×¨×™×�</body></html>",
            handler.toString().strip());
      }
      String flatText = flat.extract(file).content().strip();
      String structuredText = structured.extract(file).content().strip();
      assertTrue(expected.equals(flatText), () -> extension + " flat tail: "
          + flatText.substring(Math.max(0, flatText.length() - 80)));
      assertTrue(expected.equals(structuredText), () -> extension + " structured tail: "
          + structuredText.substring(Math.max(0, structuredText.length() - 80)));
    }
  }

  @ParameterizedTest(name = "declarationClosingBeyondWindowKeepsTodaysOutcome({0}, {1})")
  @MethodSource("unfinishedWindowDeclarations")
  void declarationClosingBeyondWindowKeepsTodaysOutcome(String extension, String encoding) throws Exception {
    String declaration = "<?xml version=\"1.0\" encoding=\"" + encoding + "\"" + " ".repeat(20_000) + "?>";
    String padding = "a".repeat(16_384);
    String source = declaration + "<html><body>" + padding + "ספרים</body></html>";
    Path file = Files.writeString(tempDir.resolve("long-declaration." + extension), source);
    try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      assertEquals(padding + "×¡×¤×¨×™×�", flat.extract(file).content().strip());
      assertEquals(padding + "×¡×¤×¨×™×�", structured.extract(file).content().strip());
    }
  }

  @ParameterizedTest(name = "unfinishedWindowDeclarationKeepsDetectorFallback({0}, {1})")
  @MethodSource("unfinishedWindowDeclarations")
  void unfinishedWindowDeclarationKeepsDetectorFallback(String extension, String encoding) throws Exception {
    String source = "<?xml version=\"1.0\" encoding=\"" + encoding + "\"" + " ".repeat(20_000)
        + "?><html><body>" + "a".repeat(16_384) + "ספרים</body></html>";
    Path file = Files.writeString(tempDir.resolve("long-declaration." + extension), source);
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = new java.io.BufferedInputStream(prepared.openStream())) {
      Metadata metadata = prepared.metadata();
      metadata.set(Metadata.CONTENT_TYPE, "html".equals(extension) ? "text/html" : "text/plain");
      assertEquals(Charset.forName("windows-1252"), detector().detect(input, metadata));
    }
  }

  static Stream<Arguments> unfinishedWindowDeclarations() {
    return Stream.of("html", "txt").flatMap(extension -> Stream.of("ISO-8859-1", "UTF-8", "utf8")
        .map(encoding -> Arguments.of(extension, encoding)));
  }

  @ParameterizedTest(name = "parserPassesRootMetadataUncopied({0})")
  @ValueSource(strings = {"txt", "md", "csv", "json", "py", "html"})
  void parserPassesRootMetadataUncopied(String extension) throws Exception {
    String source = "html".equals(extension) ? "<html><body>ASCII</body></html>" : "ASCII";
    Path file = Files.writeString(tempDir.resolve("identity." + extension), source);
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = prepared.openStream()) {
      var metadata = prepared.metadata();
      var context = new org.apache.tika.parser.ParseContext();
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var detector = detector();
      context.set(EncodingDetector.class, (stream, received) -> {
        assertSame(metadata, received, "the root-only carrier must reach the leaf detector unchanged");
        calls.incrementAndGet();
        return detector.detect(stream, received);
      });
      OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault()).parse(input,
          new org.apache.tika.sax.BodyContentHandler(-1), metadata, context);
      assertTrue(calls.get() > 0);
    }
  }

  @Test
  void captureReadFailureDeletesSpilledSnapshot() throws Exception {
    Path file = Files.writeString(tempDir.resolve("failure.txt"), "x");
    var snapshots = new ArrayList<Path>();
    try (var factory = new PreparedExtractionInput.Factory() {
      @Override
      InputStream openSource(Path source) {
        return new InputStream() {
          private int reads;
          @Override
          public int read() throws IOException { throw new IOException("capture failed"); }
          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            if (reads++ > 130) throw new IOException("capture failed");
            Arrays.fill(bytes, offset, offset + length, (byte) 'a');
            return length;
          }
        };
      }
      @Override
      synchronized Path createSnapshot(Path source) throws IOException {
        Path path = super.createSnapshot(source);
        snapshots.add(path);
        return path;
      }
    }) {
      assertEquals("capture failed", assertThrows(IOException.class,
          () -> factory.prepare(file, TikaExtractionPolicy.defaults())).getCause().getMessage());
      assertEquals(1, snapshots.size());
      assertFalse(Files.exists(snapshots.getFirst()));
    }
    assertFalse(Files.exists(snapshots.getFirst().getParent()));
  }

  private void assertTextAndCharset(byte[] bytes, String expected, String charset) throws Exception {
    Path file = Files.write(tempDir.resolve("preserved.txt"), bytes);
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = new java.io.BufferedInputStream(prepared.openStream());
        var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      var metadata = prepared.metadata();
      assertEquals(Charset.forName(charset), detector().detect(input, metadata));
      assertEquals(expected, flat.extract(file).content().strip());
      assertEquals(expected, structured.extract(file).content().strip());
    }
  }

  private static OwnedTextEncodingDetector detector() throws ReflectiveOperationException {
    return new OwnedTextEncodingDetector(List.of((EncodingDetector) Class.forName(
        "org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector").getConstructor().newInstance(),
        new DefaultEncodingDetector()));
  }
}
