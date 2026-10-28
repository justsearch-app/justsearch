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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AsciiFirstUtf8ExtractionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path tempDir;

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
            TikaExtractionPolicy.defaults());
        var sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
            PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class),
            TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 128, null)) {
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
          () -> assertEquals("tika-default-v6", child.policyId()));
    }
  }

  private static void assertExactText(String expected, String actual, String route) {
    // Do not duplicate megabyte padding in assertion reports for every affected route.
    assertTrue(expected.equals(actual), () -> route + " UTF-8 text differs; output tail: "
        + actual.substring(Math.max(0, actual.length() - 80)));
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
