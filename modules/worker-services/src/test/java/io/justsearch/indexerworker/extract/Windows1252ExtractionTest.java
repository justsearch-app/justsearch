/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Windows1252ExtractionTest {
  private static final Charset WESTERN = Charset.forName("windows-1252");
  private static final String TEXT = "European writing costs €: ‚ „ “ ” ‘ ’ – — … ™ œ Š Ž café déjà vu. ";
  @TempDir Path tempDir;

  private static void assertExactText(String expected, String actual) {
    assertEquals(-1, java.util.Arrays.mismatch(expected.toCharArray(), actual.toCharArray()),
        "first differing character (-1 means exact text)");
  }

  static Stream<Arguments> textCases() {
    return Stream.of("txt", "md", "csv", "json", "java")
        .flatMap(ext -> Stream.of(false, true).flatMap(large -> Stream.of("\n", "\r\n")
            .map(eol -> Arguments.of(ext, large, eol))));
  }

  @ParameterizedTest(name = "EN1 {0} large={1} eol={2}")
  @MethodSource("textCases")
  void en1Windows1252TextAcrossProductionPaths(String extension, boolean large, String eol) throws Exception {
    String title = "European € „title“ ™";
    String prefix = "md".equals(extension) ? "---\ntitle: " + title + "\nauthor: André\n---\n" : "";
    String body = TEXT.repeat(large ? 17_000 : 3).stripTrailing() + "\nClosing € „line“ with accents café.";
    String source = switch (extension) {
      case "json" -> "{\n\"text\":\"" + body.replace("\n", "\\n") + "\"\n}";
      case "java" -> "// " + body;
      default -> prefix + body;
    };
    source = source.replace("\n", eol);
    Path file = Files.write(tempDir.resolve("western." + extension), source.getBytes(WESTERN));
    assertEquals(large, Files.size(file) > PreparedExtractionInput.MAX_IN_MEMORY_BYTES);
    // Tika syntax-highlights source code (including NBSP spaces and a filename heading).
    // The same-name UTF-8 control supplies its existing format while the source characters stay exact.
    Path utf8 = Files.writeString(Files.createDirectories(tempDir.resolve("utf8")).resolve(file.getFileName()), source);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        var result = extractor.extract(file);
        String expected = "java".equals(extension) ? extractor.extract(utf8).content().strip()
            : source.replace("\r\n", "\n").strip();
        assertExactText(expected, result.content().replace("\r\n", "\n").strip());
        if ("md".equals(extension)) {
          assertEquals(title, result.title());
          assertEquals("André", result.frontmatterMetadata().get("author"));
        }
      }
    }
  }

  static Stream<Arguments> htmlCases() {
    return Stream.of(
        Arguments.of("meta", "<meta charset=windows-1252>", "windows-1252", false, TEXT + "¤"),
        Arguments.of("http-equiv", "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\">", "windows-1252", false, TEXT + "¤"),
        Arguments.of("utf16le-bom-wins", "<meta charset=windows-1252>", "UTF-16LE", true, TEXT + "😀"),
        Arguments.of("utf16be-bom-wins", "<meta charset=windows-1252>", "UTF-16BE", true, TEXT + "😀"),
        Arguments.of("latin2", "<meta charset=ISO-8859-2>", "ISO-8859-2", false, "Příliš žluťoučký kůň úpěl ďábelské ódy."),
        Arguments.of("bom-wins", "<meta charset=windows-1252>", "UTF-8", true, TEXT + "😀"),
        Arguments.of("utf8", "<meta charset=UTF-8>", "UTF-8", false, TEXT + "😀"),
        Arguments.of("latin9", "<meta charset=ISO-8859-15>", "ISO-8859-15", false, "Le café français coûte 25 € et les élèves sont déjà arrivés."),
        Arguments.of("unsupported", "<meta charset=not-a-real-charset>", "windows-1252", false, TEXT),
        Arguments.of("undeclared", "", "windows-1252", false, TEXT));
  }

  @ParameterizedTest(name = "EN2 {0}")
  @MethodSource("htmlCases")
  void en2HtmlDeclarationsAndBom(String name, String meta, String encoding, boolean bom, String text) throws Exception {
    String html = (bom ? "\uFEFF" : "") + "<html><head>" + meta
        + "<title>" + text + "</title></head><body><p>" + text + "</p></body></html>";
    Path file = Files.write(tempDir.resolve(name + ".html"), html.getBytes(Charset.forName(encoding)));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        var result = extractor.extract(file);
        assertEquals(extractor == structured ? text.strip() + "\n\n" + text.strip() : text.strip(),
            result.content().strip());
        assertEquals(text.strip(), result.title());
      }
    }
  }

  @Test
  void en1StructuredFailureUsesConfiguredFlatFallback() throws Exception {
    Path file = Files.write(tempDir.resolve("fallback.txt"), TEXT.getBytes(WESTERN));
    try (var extractor = new StructuredContentExtractor()) {
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      AutoDetectParser failing = mock(AutoDetectParser.class);
      doThrow(new org.xml.sax.SAXException("force structured failure"))
          .when(failing).parse(any(), any(), any(), any());
      field.set(extractor, failing);
      assertEquals(TEXT.strip(), extractor.extract(file).content().strip());
      verify(failing).parse(any(), any(), any(), any());
    }
  }

  @Test
  @Timeout(60)
  void en1SandboxRepeatedRequestsAndChildReplacement() throws Exception {
    Path file = Files.write(tempDir.resolve("sandbox.txt"), TEXT.getBytes(WESTERN));
    try (var sandbox = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(),
        OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 2, null)) {
      long firstPid = -1;
      for (int i = 0; i < 3; i++) {
        var artifact = sandbox.extract(file);
        assertEquals(TEXT.strip(), artifact.result().content().strip());
        assertEquals(TikaExtractionPolicy.defaults().policyId(), artifact.policyId());
        if (i == 0) firstPid = sandbox.firstChildPid();
        if (i == 1) assertEquals(firstPid, sandbox.firstChildPid());
        if (i == 2) assertNotEquals(firstPid, sandbox.firstChildPid());
      }
    }
  }

  static Stream<Arguments> embeddedCases() {
    return Stream.of(Arguments.of("plain.eml"), Arguments.of("html.eml"));
  }

  @ParameterizedTest(name = "EN3 {0}")
  @MethodSource("embeddedCases")
  void en3EmbeddedTextAndHtml(String kind) throws Exception {
    assertEmbeddedText(kind);
  }

  @Test
  void enN2EpubUsesItsXmlDeclarationWithoutTheTextDetector() throws Exception {
    assertEmbeddedText("epub");
  }

  private void assertEmbeddedText(String kind) throws Exception {
    byte[] bytes;
    if ("epub".equals(kind)) {
      var original = FormatCapabilityFixtureFactory.generate(FormatId.EPUB).bytes();
      var output = new ByteArrayOutputStream();
      try (var input = new ZipInputStream(new ByteArrayInputStream(original)); var zip = new ZipOutputStream(output)) {
        ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) {
          byte[] content = input.readAllBytes();
          if (entry.getName().endsWith("chapter.xhtml")) {
            content = ("<?xml version=\"1.0\" encoding=\"windows-1252\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"windows-1252\"/>"
                + "</head><body><p>" + TEXT + "</p></body></html>").getBytes(WESTERN);
          }
          zip.putNextEntry(new ZipEntry(entry.getName()));
          zip.write(content);
          zip.closeEntry();
        }
      }
      bytes = output.toByteArray();
    } else {
      boolean html = kind.startsWith("html");
      bytes = ("From: writer@example.invalid\r\nTo: reader@example.invalid\r\n"
          + "Subject: Encoding fixture\r\nMIME-Version: 1.0\r\nContent-Type: text/"
          + (html ? "html" : "plain") + "; charset=windows-1252\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
          + (html ? "<html><head><meta charset=windows-1252></head><body><p>" + TEXT + "¤</p></body></html>" : TEXT)).getBytes(WESTERN);
    }
    Path file = Files.write(tempDir.resolve("embedded." + kind), bytes);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        assertTrue(extractor.extract(file).content().contains(kind.startsWith("html") ? TEXT + "¤" : TEXT.strip()), kind);
      }
    }
  }

  static Stream<Arguments> encodingControls() {
    return Stream.of(
        Arguments.of("UTF-8", "Writing € café 😀 𠀀", false),
        Arguments.of("UTF-8", "Writing € café 😀 𠀀", true),
        Arguments.of("UTF-16LE", "Writing € café 😀 𠀀", true),
        Arguments.of("UTF-16BE", "Writing € café 😀 𠀀", true),
        Arguments.of("ISO-8859-1", "Les élèves français ont déjà préparé le café à côté de l'école.", false),
        Arguments.of("ISO-8859-15", "Les élèves français ont déjà préparé le café à côté de l'école pour 25 €. Suomessa hinta on 25 € ja työ on hyvää.", false),
        Arguments.of("ISO-8859-2", "Příliš žluťoučký kůň úpěl ďábelské ódy. Zażółć gęślą jaźń. České příběhy pro všechny děti.", false),
        Arguments.of("Shift_JIS", "日本語の文章です。東京の図書館で新しい本を読みます。", false),
        Arguments.of("GB18030", "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。", false),
        Arguments.of("EUC-KR", "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다.", false),
        Arguments.of("KOI8-R", "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке.", false))
        .flatMap(args -> Stream.of(1, 100).map(count -> Arguments.of(args.get()[0], ((String) args.get()[1]).repeat(count), args.get()[2])));
  }

  @ParameterizedTest(name = "EN-N1 {0} bom={2}")
  @MethodSource("encodingControls")
  void enN1OtherEncodingsKeepTheirText(String encoding, String text, boolean bom) throws Exception {
    Path file = Files.write(tempDir.resolve("control.txt"), ((bom ? "\uFEFF" : "") + text).getBytes(Charset.forName(encoding)));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      assertEquals(text, structured.extract(file).content().strip());
      assertEquals(text, flat.extract(file).content().strip());
    }
  }

  @Test
  void enN1LiteralMetaExamplesStayUtf8OutsideHtml() throws Exception {
    String text = "UTF-8 café € 😀 example <meta charset=windows-1252>";
    for (String extension : List.of("txt", "md", "java", "csv", "json")) {
      String source = switch (extension) {
        case "md" -> "```html\n" + text + "\n```";
        case "java" -> "// " + text;
        case "json" -> "{\"example\":\"" + text + "\"}";
        default -> text;
      };
      Path file = Files.writeString(tempDir.resolve("literal." + extension), source);
      try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
        for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
          String content = extractor.extract(file).content().strip();
          if ("java".equals(extension)) {
            assertTrue(content.replace('\u00a0', ' ').contains(source), extension);
          } else {
            assertEquals(source, content, extension);
          }
        }
      }
    }
  }

  @Test
  void enN2OfficeAndContainerTextStayUnchanged() throws Exception {
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (FormatId id : FormatId.values()) {
        Path file = FormatCapabilityFixtureFactory.write(tempDir, id);
        var expected = io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.forFormat(id);
        assertEquals(expected.exactAnnotatedText(), structured.extract(file).content(), id.name());
        String flatText = flat.extract(file).content();
        for (String marker : expected.requiredMarkers()) {
          // Flat mail extraction intentionally omits the subject (the structured path retains it).
          if (!marker.endsWith("_SUBJECT_MARKER")) assertTrue(flatText.contains(marker), id + ": " + marker);
        }
      }
    }
  }

  @Test
  void enN2PdfAndDocxTextStayUnchanged() throws Exception {
    String text = "Unchanged digital PDF text with accents: café.";
    Path pdf = tempDir.resolve("control.pdf");
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(40, 700);
        stream.showText(text);
        stream.endText();
      }
      document.save(pdf.toFile());
    }
    Path docx = tempDir.resolve("control.docx");
    try (var input = getClass().getResourceAsStream("/fixtures/office/office-marker.docx")) {
      assertNotNull(input);
      Files.copy(input, docx);
    }
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        assertEquals(text, extractor.extract(pdf).content().strip());
        assertTrue(extractor.extract(docx).content().contains("JUSTSEARCH_OFFICE_DOCX_MARKER"));
      }
    }
  }

  @Test
  void enN2XmlDeclarationKeepsItsEncoding() throws Exception {
    String text = "Příliš žluťoučký kůň úpěl ďábelské ódy.";
    Path file = Files.write(tempDir.resolve("declared.xml"),
        ("<?xml version=\"1.0\" encoding=\"ISO-8859-2\"?><document>" + text + "</document>")
            .getBytes(Charset.forName("ISO-8859-2")));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      assertEquals(text, structured.extract(file).content().strip());
      assertEquals(text, flat.extract(file).content().strip());
    }
  }
}
