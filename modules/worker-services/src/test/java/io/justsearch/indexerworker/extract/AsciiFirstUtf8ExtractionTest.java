/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AsciiFirstUtf8ExtractionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static PersistentExtractionSandbox sandbox;
  @TempDir Path tempDir;

  @BeforeAll
  static void startSandbox() {
    sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 128, null);
  }

  @AfterAll
  static void closeSandbox() {
    if (sandbox != null) sandbox.close();
  }

  static Stream<Arguments> formatsAndPrefixes() {
    return Stream.of("txt", "md", "csv", "json", "py", "html")
        .flatMap(format -> Stream.of(16_384, 16_385, 65_536, 1_048_576)
            .flatMap(prefix -> Stream.of("ספרים בעברית", "Русские книги", "中文书籍", "😀", "Grüße déjà café")
                .map(text -> Arguments.of(format, prefix, text))));
  }

  @ParameterizedTest(name = "asciiFirstTextAcrossFormatsAndRoutes({0}, prefix {1}, {2})")
  @MethodSource("formatsAndPrefixes")
  @Timeout(180)
  void asciiFirstTextAcrossFormatsAndRoutes(String format, int prefix, String text) throws Exception {
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor();
        var policy = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
            TikaExtractionPolicy.defaults())) {
      String head = switch (format) {
        case "html" -> "<html><body><p>";
        case "json" -> "{\"text\":\"";
        case "py" -> "# ";
        case "csv" -> "key,value\nrow,";
        default -> "";
      };
      String padding = "a".repeat(prefix - head.length());
      // Expectations are the literal representation of each format, never another extraction.
      String source = switch (format) {
        case "html" -> "<html><body><p>" + padding + text + "</p></body></html>";
        case "json" -> "{\"text\":\"" + padding + text + "\"}";
        case "py" -> "# " + padding + text;
        case "csv" -> "key,value\nrow," + padding + text + "\n";
        default -> padding + text;
      };
      String expectedStructured = switch (format) {
        case "html" -> padding + text;
        default -> source;
      };
      String expectedFlat = expectedStructured;
      if ("csv".equals(format)) {
        // Keep complete, bounded rows inside the sniffing window, with the first non-ASCII
        // byte still at exactly the requested offset (including the header and separators).
        int rows = (prefix - 14) / 1005;
        String rowPadding = "a".repeat(1000);
        String finalPadding = "a".repeat((prefix - 14) % 1005);
        source = "key,value\n" + ("row," + rowPadding + "\n").repeat(rows)
            + "row," + finalPadding + text + "\n";
        expectedStructured = ("key = row\nrow, value = " + rowPadding + "\n").repeat(rows)
            + "key = row\nrow, value = " + finalPadding + text;
        expectedFlat = "key\tvalue\n" + ("\trow\t" + rowPadding + "\n").repeat(rows)
            + "\trow\t" + finalPadding + text;
      }
      assertEquals(prefix, source.indexOf(text));
      Path file = Files.writeString(tempDir.resolve("ascii-first." + format), source);
      String detected;
      String attributed;
      // Parsing the prepared root metadata exercises the exact object that carries the fact.
      try (var factory = new PreparedExtractionInput.Factory();
          var captured = factory.prepare(file, TikaExtractionPolicy.defaults());
          var input = captured.openStream()) {
        var metadata = captured.metadata();
        OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault())
            .parse(input, new BodyContentHandler(-1), metadata, new ParseContext());
        detected = metadata.get(TikaCoreProperties.DETECTED_ENCODING);
        attributed = metadata.get(TikaCoreProperties.ENCODING_DETECTOR);
      }
      String structuredText = structured.extract(file).content().strip();
      String flatText = flat.extract(file).content().strip();
      var artifact = policy.extractArtifact(file);
      var child = sandbox.extract(file);
      String tableOrText = expectedStructured;
      String flatExpected = expectedFlat;
      assertAll(
          () -> assertEquals("UTF-8", detected, "parser charset"),
          () -> assertEquals(OwnedTextEncodingDetector.class.getName() + "#detectUtf8", attributed),
          () -> assertExactText(tableOrText, structuredText, format + " structured"),
          () -> assertExactText(flatExpected, flatText, format + " flat"),
          () -> assertExactText(tableOrText, artifact.result().content().strip(), format + " policy"),
          () -> assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status()),
          () -> assertExactText(tableOrText, child.result().content().strip(), format + " child"),
          () -> assertEquals(ExtractionStatus.SUCCESS_FULL, child.status()),
          () -> assertEquals("tika-default-v6", child.policyId()),
          () -> assertEquals(1L, sandbox.spawnCount(), "all cases must reuse one child"),
          () -> assertEquals(0L, sandbox.restartCount()));
    }
  }

  private static void assertExactText(String expected, String actual, String route) {
    // Do not duplicate megabyte padding in assertion reports for every affected route.
    assertTrue(expected.equals(actual), () -> route + " UTF-8 text differs; output tail: "
        + actual.substring(Math.max(0, actual.length() - 80)));
  }

  @ParameterizedTest(name = "unfinishedXmlPrefixUsesSnapshotUtf8({0})")
  @ValueSource(strings = {"html", "txt"})
  void unfinishedXmlPrefixUsesSnapshotUtf8(String extension) throws Exception {
    String prefix = "<?xml encoding=\"UTF-8\" LOG HEADER\n<html><body>";
    String padding = "a".repeat(16_384);
    String source = prefix + padding + "ספרים</body></html>";
    Path file = Files.writeString(tempDir.resolve("unfinished." + extension), source);
    assertUtf8DeclarationText(file, source, padding + "ספרים", extension);
  }

  @ParameterizedTest(name = "malformedXmlDeclarationUsesSnapshotUtf8({index})")
  @ValueSource(strings = {
      "<?xml encoding='UTF-8'?>",
      "<?xml version='1.0' encoding='UTF-8' standalone='maybe'?>",
      "<?xml version='1.0' encoding='UTF-8' extra='yes'?>",
      "<?xml encoding='UTF-8' version='1.0'?>",
      "<?xml version='1.0' encoding='UTF-8'"
  })
  void malformedXmlDeclarationUsesSnapshotUtf8(String prefix) throws Exception {
    String source = prefix + "\n" + "a".repeat(16_384) + "ספרים";
    Path file = Files.writeString(tempDir.resolve("malformed.txt"), source);
    assertUtf8DeclarationText(file, source, source, "txt");
  }

  static Stream<Arguments> declaredUtf8Charsets() {
    return Stream.of("html", "txt").flatMap(extension -> Stream.of("UTF-8", "utf8")
        .map(encoding -> Arguments.of(extension, encoding)));
  }

  @ParameterizedTest(name = "completeUtf8XmlDeclarationUsesSnapshotUtf8({0}, {1})")
  @MethodSource("declaredUtf8Charsets")
  void completeUtf8XmlDeclarationUsesSnapshotUtf8(String extension, String encoding) throws Exception {
    String declaration = "<?xml version = '1.0' encoding = \"" + encoding + "\" standalone='yes' ?>";
    String padding = "a".repeat(16_384);
    String source = declaration + "<html><body>" + padding + "ספרים</body></html>";
    Path file = Files.writeString(tempDir.resolve("declared." + extension), source);
    assertUtf8DeclarationText(file, source, padding + "ספרים", extension);
  }

  private static void assertUtf8DeclarationText(Path file, String source, String expected, String extension)
      throws Exception {
    try (var factory = new PreparedExtractionInput.Factory();
        var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = prepared.openStream();
        var flat = new ContentExtractor(); var structured = new StructuredContentExtractor()) {
      var metadata = prepared.metadata();
      // The HTML signature also wins for .txt here. Force the plain parser as well so both
      // real parser routes are covered with the reviewer's exact bytes.
      var parser = "txt".equals(extension)
          ? OwnedTikaParsers.create((stream, meta) -> org.apache.tika.mime.MediaType.TEXT_PLAIN)
          : OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault());
      var handler = new BodyContentHandler(-1);
      parser.parse(input, handler, metadata, new ParseContext());
      assertEquals("UTF-8", metadata.get(TikaCoreProperties.DETECTED_ENCODING), "parser charset");
      assertExactText("txt".equals(extension) ? source : expected, handler.toString().strip(), extension + " parser");
      assertExactText(expected, flat.extract(file).content().strip(), extension + " flat");
      assertExactText(expected, structured.extract(file).content().strip(), extension + " structured");
    }
  }

  @Test
  void asciiFirstStructuredFailureUsesFlatFallback() throws Exception {
    String text = "a".repeat(65_536) + "ספרים בעברית";
    Path file = Files.writeString(tempDir.resolve("fallback.txt"), text);
    try (var extractor = new StructuredContentExtractor()) {
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      AutoDetectParser failing = mock(AutoDetectParser.class);
      doThrow(new org.xml.sax.SAXException("force structured failure"))
          .when(failing).parse(any(), any(), any(), any());
      field.set(extractor, failing);
      assertEquals(text, extractor.extract(file).content().strip());
      verify(failing).parse(any(), any(), any(), any());
    }
  }

  @Test
  void wholeSnapshotSelectsUtf8EvenWhenCharacterCapStopsBeforeNonAscii() throws Exception {
    Path file = Files.writeString(tempDir.resolve("capped.txt"), "a".repeat(65_536) + "ספרים");
    try (var flat = new ContentExtractor(16)) {
      var result = flat.extract(file);
      assertEquals("a".repeat(16), result.content());
      assertTrue(result.mimeType().contains("charset=UTF-8"));
    }
  }

  @Test
  void asciiFirstNativeWordFindsIndexedDocument() throws Exception {
    Path file = Files.writeString(tempDir.resolve("search.txt"), "a".repeat(16_384) + "\nספרים בעברית");
    try (var extractor = new ContentExtractor();
        var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
            .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(TestDocumentBuilder.buildDocument(file, extractor.extract(file)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var response = new WorkerSearchService(runtime).search(SearchRequest.newBuilder()
          .setQuery("ספרים").setLimit(10).setMode(SearchMode.SEARCH_MODE_TEXT).build(), CallContext.none());
      assertTrue(response.getResultsList().stream().anyMatch(hit -> file.toString().equals(hit.getId())));
    }
  }
}
