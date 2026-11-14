/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.rag.ChunkOffsetMath;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.xml.sax.ContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

/** Small, owned packages through the production parser, fallback and extraction child. */
class WordVisibleTextFilesTest extends LuceneExecutorTestBase {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  private static final String PAGE = "Visible first page contains searchable orchard words";
  @TempDir Path temp;
  private static PersistentExtractionSandbox extractionChild;

  @BeforeAll
  static void startExtractionChild() {
    extractionChild = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(),
        OcrRoutingConfig.disabled(), Duration.ofSeconds(45), 1, 128, null);
  }

  @AfterAll
  static void stopExtractionChild() {
    extractionChild.close();
  }

  @ParameterizedTest(name = "S1/S5 preview {0} via {1}")
  @CsvSource({"wmf,child", "emf,child", "wmf,fallback", "emf,fallback", "wmf,flat", "emf,flat"})
  void previewTextIsNotDuplicated(String format, String route) throws Exception {
    Path file = word(paragraph(PAGE), format, true, false, false);
    String text = extract(file, route).result().content();
    assertEquals(PAGE, text.strip(), "Preview must contribute no text: " + text);
    assertEquals(1, text.split("orchard", -1).length - 1, text);
  }

  @ParameterizedTest(name = "S2/S7 inline {0} via {1}")
  @CsvSource({"wmf,child", "emf,child", "wmf,fallback", "emf,fallback"})
  void inlinePictureNamesDoNotBecomeTextOrHeadings(String format, String route) throws Exception {
    Path file = word(heading("First real heading") + paragraph("beforepicture") + picture()
        + paragraph("afterpicture " + "readable body words ".repeat(100))
        + heading("Second real heading") + paragraph("finalparagraph"), format, false, true, false);
    String text = extract(file, route).result().content();
    assertAll(
        () -> assertFalse(text.contains("image7."), text),
        () -> assertTrue(text.contains("beforepicture"), text),
        () -> assertTrue(text.contains("afterpicture"), text),
        () -> assertTrue(text.contains("First real heading"), text),
        () -> assertTrue(text.contains("Second real heading"), text),
        () -> assertTrue(text.contains("finalparagraph"), text));
    if (route.equals("child")) {
      var headings = new ChunkOffsetMath.MetadataIndex(text);
      assertEquals("First real heading", headings.precedingHeading(text.indexOf("afterpicture")).text());
      assertEquals("Second real heading", headings.precedingHeading(text.indexOf("finalparagraph")).text());
    }
  }

  @ParameterizedTest(name = "P1 vector text {0}")
  @ValueSource(strings = {"wmf", "emf"})
  void realInlineVectorPictureTextRemains(String format) throws Exception {
    Path file = word(paragraph("beforepicture") + picture() + paragraph("afterpicture"),
        format, false, true, false);
    String text = extract(file, "child").result().content();
    assertTrue(text.contains("Visible vector illustration"), text);
    assertTrue(text.contains("beforepicture"), text);
    assertTrue(text.contains("afterpicture"), text);
  }

  @ParameterizedTest(name = "S3/S8 notes via {0}")
  @ValueSource(strings = {"child", "fallback", "flat", "split"})
  void noteTextRemainsWithoutInternalReferences(String route) throws Exception {
    String refs = "<w:r><w:footnoteReference w:id=\"1\"/><w:endnoteReference w:id=\"2\"/></w:r>";
    String body = "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>"
        + run("headingbefore ") + refs + run(" headingafter") + "</w:p>"
        + "<w:p>" + run("bodybefore ") + refs + run(" bodyafter") + "</w:p>"
        + "<w:tbl><w:tr><w:tc><w:p>" + run("cellbefore ") + refs + run(" cellafter")
        + "</w:p></w:tc></w:tr></w:tbl>"
        + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
        + run("listbefore ") + refs + run(" listafter") + "</w:p>";
    String text = extract(word(body, "wmf", false, false, true), route).result().content();
    assertAll(
        () -> assertFalse(text.contains("[footnoteRef:"), text),
        () -> assertFalse(text.contains("[endnoteRef:"), text),
        () -> assertTrue(text.contains("Visible footnote explanation"), text),
        () -> assertTrue(text.contains("Visible endnote explanation"), text),
        () -> assertTrue(text.matches("(?s).*headingbefore\\s+headingafter.*"), text),
        () -> assertTrue(text.matches("(?s).*bodybefore\\s+bodyafter.*"), text),
        () -> assertTrue(text.matches("(?s).*cellbefore\\s+cellafter.*"), text),
        () -> assertTrue(text.matches("(?s).*listbefore\\s+listafter.*"), text));
  }

  @ParameterizedTest(name = "S5 nested Word {0} via {1}")
  @CsvSource({"zip,child", "eml,child", "zip,fallback", "eml,fallback"})
  void nestedWordUsesTheSameVisibleTextRules(String container, String route) throws Exception {
    String body = paragraph(PAGE) + picture() + "<w:p>" + run("bodybefore ")
        + "<w:r><w:footnoteReference w:id=\"1\"/></w:r>" + run(" bodyafter") + "</w:p>";
    byte[] docx = Files.readAllBytes(word(body, "wmf", true, true, true));
    Path file = container.equals("zip")
        ? Files.write(temp.resolve("outer.zip"), zip(Map.of("report.docx", docx)))
        : mail("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
    String text = extract(file, route).result().content();
    assertAll(
        () -> assertEquals(1, text.split("orchard", -1).length - 1, text),
        () -> assertFalse(text.contains("image7.wmf"), text),
        () -> assertFalse(text.contains("[footnoteRef:"), text),
        () -> assertTrue(text.contains("Visible footnote explanation"), text),
        () -> assertTrue(text.contains("Visible vector illustration"), text),
        () -> assertTrue(text.contains("report.docx"), text));
  }

  @ParameterizedTest(name = "S2p/S6 {0} name {1}")
  @CsvSource({"zip,image7.emf", "zip,thumbnail.emf", "zip,docProps/thumbnail.emf",
      "eml,image7.emf", "eml,thumbnail.emf", "eml,docProps/thumbnail.emf"})
  void imageAttachmentAndMemberNamesRemainSearchable(String container, String name) throws Exception {
    byte[] image = vector("emf", "Visible attachment illustration");
    Path file = container.equals("zip") ? Files.write(temp.resolve("names.zip"), zip(Map.of(name, image)))
        : mail(name, "image/emf", image);
    String text = extract(file, "child").result().content();
    // Mail image attachment names are not stored today (unchanged); archive member names are.
    if (container.equals("zip")) assertTrue(text.contains(name), text);
    assertTrue(text.contains("Visible attachment illustration"), text);
  }

  @ParameterizedTest(name = "S11 only {0}")
  @ValueSource(strings = {"preview", "picture-name", "empty"})
  void pictureOnlyDocumentHasEmptyStatusAndIsFoundByFilename(String kind) throws Exception {
    Path file = word(kind.equals("picture-name") ? picture() : "", "wmf",
        kind.equals("preview"), kind.equals("picture-name"), false, "");
    var artifact = extract(file, "child");
    assertAll(
        () -> assertEquals("", artifact.result().content()),
        () -> assertEquals(ExtractionStatus.SUCCESS_EMPTY, artifact.status()));
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "pictureonly", SchemaFields.DOC_UID, "pictureonly",
          SchemaFields.PATH, file.toString(), SchemaFields.FILENAME, "pictureonly.docx",
          SchemaFields.CONTENT, artifact.result().content())));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1L, runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.FILENAME,
          "pictureonly.docx")), 10, Set.of(SchemaFields.DOC_ID), null, null).totalHits());
    }
  }

  @Test
  void p2p3WordBodyHeadingsCellsAndListsKeepTheirText() throws Exception {
    Path file = word(heading("Report") + paragraph("Introduction")
        + "<w:tbl><w:tr><w:tc>" + paragraph("repercussive") + paragraph("All")
        + "</w:tc><w:tc>" + paragraph("Jason Adams") + paragraph("Avneet Nahal")
        + "</w:tc></w:tr></w:tbl>" + paragraph("ending"), "wmf", false, false, false);
    String text = extract(file, "child").result().content();
    assertEquals("# Report\n\nIntroduction\n\nrepercussive All\nJason Adams Avneet Nahal\n\nending", text);
  }

  @ParameterizedTest(name = "S1 other Office preview {0}")
  @ValueSource(strings = {"XLSX", "PPTX_WITH_NOTES"})
  void previewOfOtherOfficePackagesIsNotBodyText(String format) throws Exception {
    var fixture = FormatCapabilityFixtureFactory.generate(FormatId.valueOf(format));
    var parts = new LinkedHashMap<String, byte[]>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(fixture.bytes()))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) parts.put(entry.getName(), input.readAllBytes());
    }
    String contentTypes = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8);
    contentTypes = contentTypes.replace("</Types>", "<Default Extension=\"wmf\" ContentType=\"image/wmf\"/></Types>");
    parts.put("[Content_Types].xml", bytes(contentTypes));
    String rootRels = new String(parts.get("_rels/.rels"), StandardCharsets.UTF_8);
    rootRels = rootRels.replace("</Relationships>", relationship("preview",
        "http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail",
        "docProps/thumbnail.wmf") + "</Relationships>");
    parts.put("_rels/.rels", bytes(rootRels));
    parts.put("docProps/thumbnail.wmf", vector("wmf", "Preview must never become searchable"));
    Path file = Files.write(temp.resolve(fixture.fileName()), zip(parts));
    String text = extract(file, "child").result().content();
    assertFalse(text.contains("Preview must never become searchable"), text);
    assertTrue(text.contains(format.equals("XLSX") ? "JUSTSEARCH_XLSX_SMOKE_MARKER"
        : "JUSTSEARCH_PPTX_SLIDE_MARKER"), text);
  }

  private ExtractionArtifact extract(Path file, String route) throws Exception {
    var policy = TikaExtractionPolicy.defaults();
    if (route.equals("child")) {
      return extractionChild.extract(file);
    }
    if (route.equals("flat")) {
      try (var flat = new ContentExtractor()) {
        return ExtractionArtifact.full(flat.extract(file), policy, "test-flat", false);
      }
    }
    try (var structured = new StructuredContentExtractor()) {
      var parser = mock(AutoDetectParser.class);
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      if (route.equals("split")) {
        AutoDetectParser original = (AutoDetectParser) field.get(structured);
        doAnswer(invocation -> {
          InputStream input = invocation.getArgument(0);
          ContentHandler target = invocation.getArgument(1);
          Metadata metadata = invocation.getArgument(2);
          ParseContext context = invocation.getArgument(3);
          original.parse(input, new ContentHandlerDecorator(target) {
            @Override public void characters(char[] chars, int start, int length) throws SAXException {
              for (int i = start; i < start + length; i++) super.characters(chars, i, 1);
            }
          }, metadata, context);
          return null;
        }).when(parser).parse(any(), any(), any(), any());
      } else {
        doThrow(new SAXException("deliberate structured failure to exercise the real flat fallback"))
            .when(parser).parse(any(), any(), any(), any());
      }
      field.set(structured, parser);
      var result = structured.extractWithStatus(file);
      if (!route.equals("split")) assertEquals(0, result.summary().tableCount(), "forced flat fallback");
      else assertEquals(1, result.summary().tableCount(), "real Word parse with one-character callbacks");
      return ExtractionArtifact.full(result.result(), policy, "test-fallback", result.truncated());
    }
  }

  private Path word(String body, String format, boolean preview, boolean inline, boolean notes) throws Exception {
    return word(body, format, preview, inline, notes, "Visible vector illustration");
  }

  private Path word(String body, String format, boolean preview, boolean inline, boolean notes,
      String inlineText) throws Exception {
    var parts = new LinkedHashMap<String, byte[]>();
    String types = "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Default Extension=\"" + format + "\" ContentType=\"image/" + format + "\"/>"
        + override("document", "document.main") + override("styles", "styles")
        + override("footnotes", "footnotes") + override("endnotes", "endnotes")
        + override("numbering", "numbering");
    parts.put("[Content_Types].xml", bytes("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" + types + "</Types>"));
    String rootRels = relationship("office", R + "officeDocument", "word/document.xml");
    if (preview) {
      rootRels += relationship("preview", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail",
          "docProps/thumbnail." + format);
      parts.put("docProps/thumbnail." + format, vector(format, PAGE));
    }
    parts.put("_rels/.rels", bytes(relationships(rootRels)));
    String rels = relationship("styles", R + "styles", "styles.xml")
        + relationship("numbering", R + "numbering", "numbering.xml");
    if (inline) {
      rels += relationship("picture", R + "image", "media/image7." + format);
      parts.put("word/media/image7." + format, vector(format, inlineText));
    }
    if (notes) {
      for (String kind : new String[] {"footnote", "endnote"}) {
        rels += relationship(kind, R + kind + "s", kind + "s.xml");
        parts.put("word/" + kind + "s.xml", bytes("<w:" + kind + "s xmlns:w=\"" + W + "\"><w:" + kind
            + " w:id=\"" + (kind.equals("footnote") ? 1 : 2) + "\">"
            + paragraph("Visible " + kind + " explanation") + "</w:" + kind + "></w:" + kind + "s>"));
      }
    }
    parts.put("word/_rels/document.xml.rels", bytes(relationships(rels)));
    parts.put("word/styles.xml", bytes("<w:styles xmlns:w=\"" + W + "\"><w:style w:type=\"paragraph\" w:styleId=\"Heading1\">"
        + "<w:name w:val=\"heading 1\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr></w:style></w:styles>"));
    parts.put("word/numbering.xml", bytes("<w:numbering xmlns:w=\"" + W + "\"><w:abstractNum w:abstractNumId=\"0\">"
        + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/></w:lvl>"
        + "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"));
    parts.put("word/document.xml", bytes("<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R.substring(0, R.length() - 1)
        + "\" xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>" + body + "<w:sectPr/></w:body></w:document>"));
    return Files.write(temp.resolve("pictureonly.docx"), zip(parts));
  }

  private static String override(String part, String type) {
    return "<Override PartName=\"/word/" + part + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml."
        + type + "+xml\"/>";
  }

  private static String run(String text) { return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>"; }
  private static String paragraph(String text) { return "<w:p>" + run(text) + "</w:p>"; }
  private static String heading(String text) {
    return "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>" + run(text) + "</w:p>";
  }
  private static String picture() {
    return "<w:p><w:r><w:pict><v:shape id=\"picture\" style=\"width:100pt;height:30pt\">"
        + "<v:imagedata r:id=\"picture\"/></v:shape></w:pict></w:r></w:p>";
  }
  private static String relationship(String id, String type, String target) {
    return "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + target + "\"/>";
  }
  private static String relationships(String inner) {
    return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + inner + "</Relationships>";
  }

  /** Minimal real metafiles: header, text record and EOF. No external corpus required. */
  private static byte[] vector(String format, String text) {
    if (format.equals("wmf")) {
      byte[] chars = bytes(text);
      int textRecord = 12 + ((chars.length + 1) & ~1);
      ByteBuffer data = ByteBuffer.allocate(18 + textRecord + 6).order(ByteOrder.LITTLE_ENDIAN);
      data.putShort((short) 1).putShort((short) 9).putShort((short) 0x300)
          .putInt(data.capacity() / 2).putShort((short) 0).putInt(textRecord / 2).putShort((short) 0);
      data.putInt(textRecord / 2).putShort((short) 0x521).putShort((short) chars.length).put(chars);
      if ((chars.length & 1) != 0) data.put((byte) 0);
      data.putShort((short) 0).putShort((short) 0).putInt(3).putShort((short) 0);
      return data.array();
    }
    byte[] chars = text.getBytes(StandardCharsets.UTF_16LE);
    int record = 76 + ((chars.length + 3) & ~3);
    ByteBuffer data = ByteBuffer.allocate(108 + record + 20).order(ByteOrder.LITTLE_ENDIAN);
    data.putInt(1).putInt(108);
    data.position(40);
    data.putInt(0x464d4520).putInt(0x10000).putInt(data.capacity()).putInt(3).putShort((short) 1);
    data.position(72);
    data.putInt(1024).putInt(768).putInt(270).putInt(203);
    data.position(100);
    data.putInt(270000).putInt(203000);
    data.putInt(84).putInt(record);
    data.position(108 + 24);
    data.putInt(1).putFloat(1).putFloat(1).putInt(0).putInt(0).putInt(text.length()).putInt(76)
        .putInt(0).putInt(0).putInt(0).putInt(100).putInt(100).putInt(0).put(chars);
    data.position(108 + record);
    data.putInt(14).putInt(20).putInt(0).putInt(0).putInt(20);
    return data.array();
  }

  private Path mail(String name, String mime, byte[] attachment) throws Exception {
    return Files.writeString(temp.resolve("message.eml"), "From: author@example.test\r\nTo: reader@example.test\r\n"
        + "Subject: Visible mail subject\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=boundary\r\n\r\n"
        + "--boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nVisible mail body\r\n"
        + "--boundary\r\nContent-Type: " + mime + "; name=\"" + name + "\"\r\n"
        + "Content-Disposition: attachment; filename=\"" + name + "\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getMimeEncoder().encodeToString(attachment) + "\r\n--boundary--\r\n");
  }

  private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
  private static byte[] zip(Map<String, byte[]> parts) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
