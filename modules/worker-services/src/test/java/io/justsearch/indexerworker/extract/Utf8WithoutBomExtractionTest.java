/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8WithoutBomExtractionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  // Original historical fixture was absent; this literal 222-byte prose also reproduces GB18030.
  private static final String REFERENCE_HEBREW =
      "זהו מסמך בעברית ללא סימן סדר בתים. היום אנחנו קוראים ספרים חדשים בספרייה בעיר. ילדים ומבוגרים מחפשים מילים ומגלים סיפורים.";
  private static final String SHORT_HEBREW = "מסמך בעברית ללא סימן סדר";
  private static final String HEBREW =
      "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית.";
  private static final String TITLE = "ספרים בעברית";
  @TempDir Path tempDir;

  static Stream<Arguments> hebrewFiles() {
    return Stream.of("txt", "md", "csv", "json", "py", "html", "late-meta.html")
        .flatMap(extension -> Stream.of(-1, 0, 1, 3, 20, 200)
            .map(count -> Arguments.of(extension, count)));
  }

  @ParameterizedTest(name = "hebrewAcrossFormatsAndReaders(H1 {0}, length multiplier {1})")
  @MethodSource("hebrewFiles")
  void hebrewAcrossFormatsAndReaders(String extension, int count) throws Exception {
    String body = count == -1 ? REFERENCE_HEBREW : count == 0 ? SHORT_HEBREW : HEBREW.repeat(count);
    String source = source(extension, body);
    Path file = Files.writeString(tempDir.resolve("hebrew." + extension), source);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor();
        var policy = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
            TikaExtractionPolicy.defaults())) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        var result = extractor.extract(file);
        assertText(extension, body, source, result);
      }
      var artifact = policy.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.truncated());
      assertText(extension, body, source, artifact.result());
      artifact.validate(TikaExtractionPolicy.defaults(), "hebrew-formats");
    }
  }

  @Test
  void structuredFailureUsesFlatUtf8Fallback() throws Exception {
    Path file = Files.writeString(tempDir.resolve("fallback.txt"), HEBREW);
    try (var extractor = new StructuredContentExtractor()) {
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      AutoDetectParser failing = mock(AutoDetectParser.class);
      doThrow(new org.xml.sax.SAXException("force structured failure"))
          .when(failing).parse(any(), any(), any(), any());
      field.set(extractor, failing);
      assertEquals(HEBREW, extractor.extract(file).content().strip());
      verify(failing).parse(any(), any(), any(), any());
    }
  }

  @ParameterizedTest(name = "childReuseAndReplacementKeepUtf8TextAndPolicyIdentity({arguments})")
  @ValueSource(booleans = {false, true})
  @Timeout(90)
  void childReuseAndReplacementKeepUtf8TextAndPolicyIdentity(boolean configured) throws Exception {
    Path file = Files.writeString(tempDir.resolve("sandbox.txt"), HEBREW);
    var policy = configured ? TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(100_000, 1_000_000))
        : TikaExtractionPolicy.defaults();
    String expectedPolicy = configured ? "tika-config-v5-100000-1000000" : "tika-default-v5";
    try (var sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 2, null)) {
      long firstPid = -1;
      for (int i = 0; i < 3; i++) {
        var artifact = sandbox.extract(file);
        assertEquals(expectedPolicy, artifact.policyId());
        assertEquals(HEBREW, artifact.result().content().strip());
        assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
        artifact.validate(policy, "hebrew-child");
        if (i == 0) firstPid = sandbox.firstChildPid();
        if (i == 1) assertEquals(firstPid, sandbox.firstChildPid());
        if (i == 2) assertNotEquals(firstPid, sandbox.firstChildPid());
      }
    }
  }

  @Test
  @Timeout(90)
  void childExtractsAllHebrewFormatsAndLengths() throws Exception {
    try (var sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(),
        OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 128, null)) {
      for (String extension : List.of("txt", "md", "csv", "json", "py", "html", "late-meta.html")) {
        for (int count : List.of(-1, 0, 1, 3, 20, 200)) {
          String body = count == -1 ? REFERENCE_HEBREW : count == 0 ? SHORT_HEBREW : HEBREW.repeat(count);
          String source = source(extension, body);
          Path file = Files.writeString(tempDir.resolve("child." + extension), source);
          var artifact = sandbox.extract(file);
          assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
          assertText(extension, body, source, artifact.result());
          assertEquals("tika-default-v5", artifact.policyId());
        }
      }
      assertEquals(1, sandbox.spawnCount(), "all formats use the same real child");
    }
  }

  @Test
  void htmlTitleAndBodyKeepHebrew() throws Exception {
    Path file = Files.writeString(tempDir.resolve("title.html"), "<html><head><title>" + TITLE
        + "</title></head><body><p>" + HEBREW + "</p></body></html>");
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      assertEquals(TITLE + "\n\n" + HEBREW, structured.extract(file).content().strip());
      assertEquals(HEBREW, flat.extract(file).content().strip());
      assertEquals(TITLE, structured.extract(file).title());
      assertEquals(TITLE, flat.extract(file).title());
    }
  }

  @Test
  void csvSniffingAndReadingKeepUtf8CellsAndCharset() throws Exception {
    Path file = Files.writeString(tempDir.resolve("table.csv"),
        "שם,סיפור\n" + ("מרים," + HEBREW + "\n").repeat(50));
    var metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "table.csv");
    var cells = new ArrayList<String>();
    var handler = new DefaultHandler() {
      private StringBuilder cell;
      @Override
      public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if ("td".equals(localName)) cell = new StringBuilder();
      }
      @Override
      public void characters(char[] characters, int offset, int length) {
        if (cell != null) cell.append(characters, offset, length);
      }
      @Override
      public void endElement(String uri, String localName, String qName) {
        if ("td".equals(localName)) {
          cells.add(cell.toString());
          cell = null;
        }
      }
    };
    try (var input = org.apache.tika.io.TikaInputStream.get(file)) {
      OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault())
          .parse(input, handler, metadata, new ParseContext());
    }
    assertEquals("UTF-8", metadata.get(TikaCoreProperties.DETECTED_ENCODING));
    var expected = new ArrayList<>(List.of("שם", "סיפור"));
    for (int row = 0; row < 50; row++) expected.addAll(List.of("מרים", HEBREW));
    assertEquals(expected, cells);
    assertTrue(metadata.get(Metadata.CONTENT_TYPE).startsWith("text/csv"));
  }

  @ParameterizedTest(name = "inlineMailBodiesAndArchiveAttachmentKeepHebrew({arguments})")
  @ValueSource(strings = {"plain", "html", "zip"})
  void inlineMailBodiesAndArchiveAttachmentKeepHebrew(String kind) throws Exception {
    Path file;
    if ("zip".equals(kind)) {
      file = tempDir.resolve("attachment.zip");
      try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
        zip.putNextEntry(new ZipEntry("hebrew.txt"));
        zip.write(HEBREW.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } else {
      String body = "html".equals(kind) ? "<html><body><p>" + HEBREW + "</p></body></html>" : HEBREW;
      file = Files.writeString(tempDir.resolve("body.eml"),
          "From: writer@example.invalid\r\nTo: reader@example.invalid\r\n"
              + "Subject: Hebrew fixture\r\nMIME-Version: 1.0\r\nContent-Type: text/" + kind
              + "\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + body);
    }
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor();
        var policy = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
            TikaExtractionPolicy.defaults())) {
      assertTrue(structured.extract(file).content().contains(HEBREW));
      assertTrue(flat.extract(file).content().contains(HEBREW));
      var artifact = policy.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertTrue(artifact.result().content().contains(HEBREW));
    }
  }

  @Test
  void hebrewNativeWordFindsIndexedDocument() throws Exception {
    Path file = Files.writeString(tempDir.resolve("search.txt"), HEBREW);
    try (var extractor = new ContentExtractor();
        var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
            .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var document = TestDocumentBuilder.buildDocument(file, extractor.extract(file));
      runtime.indexingCoordinator().indexSingle(document);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var response = new WorkerSearchService(runtime).search(SearchRequest.newBuilder()
          .setQuery("ספרים").setLimit(10).setMode(SearchMode.SEARCH_MODE_TEXT).build(), CallContext.none());
      assertTrue(response.getResultsList().stream().anyMatch(hit -> file.toString().equals(hit.getId())),
          "a native Hebrew word must find the indexed document");
    }
  }

  private static String source(String extension, String body) {
    return switch (extension) {
      case "md" -> "---\ntitle: " + TITLE + "\nauthor: מרים\n---\n" + body;
      case "json" -> "{\"text\":\"" + body + "\"}";
      case "py" -> "# " + body;
      case "html", "late-meta.html" -> "<html><head>"
          + ("late-meta.html".equals(extension) ? "<!--" + "a".repeat(8200) + "--><meta charset=windows-1252>" : "")
          + "</head><body><p>" + body + "</p></body></html>";
      default -> body;
    };
  }

  private static void assertText(String extension, String body, String source,
      ContentExtractor.ExtractionResult result) {
    String expected = extension.endsWith("html") ? body : source;
    assertEquals(expected, result.content().strip());
    if ("md".equals(extension)) {
      assertEquals(TITLE, result.title());
      assertEquals("מרים", result.frontmatterMetadata().get("author"));
    }
  }
}
