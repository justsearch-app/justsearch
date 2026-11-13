package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Real files -> production child -> production Lucene storage/search. Not an Engine check. */
class TableCellBoundaryFilesContractTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  static PersistentExtractionSandbox child(TikaExtractionPolicy policy) {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(45), 1, 128, null);
  }

  @ParameterizedTest(name = "S1/S5 {0} {1}")
  @CsvSource({"docx,single", "docx,body", "docx,header", "html,single", "html,body",
      "html,header", "odt,single", "odt,body", "odt,header", "doc,single", "doc,body", "doc,header"})
  void s1s5ProductionChildStoredAndSearchableParagraphs(String format, String layout) throws Exception {
    String cells = "<td><p>repercussive</p><p>All</p></td>"
        + "<td><p>Jason Adams</p><p>Avneet Nahal</p></td>";
    String table = switch (layout) {
      case "body" -> "<tr><th><p>Category</p></th><th><p>People</p></th></tr><tr>" + cells + "</tr>";
      case "header" -> "<tr>" + cells.replace("td>", "th>")
          + "</tr><tr><td><p>entry</p></td><td><p>value</p></td></tr>";
      default -> "<tr>" + cells + "</tr>";
    };
    Path file = fixture(format, "<table>" + table + "</table>");
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      var artifact = sandbox.extract(file);
      System.out.println("S1/S5 " + format + " " + layout + " parser=" + artifact.parserId()
          + " content=[" + artifact.result().content() + "]");
      assertEquals("tika-policy-structured", artifact.parserId());
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertStoredAndSearchable(artifact.result().content());
    }
  }

  private void assertStoredAndSearchable(String content) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "fixture", SchemaFields.DOC_UID, "fixture-uid",
          SchemaFields.PATH, temp.resolve("fixture").toString(),
          SchemaFields.CONTENT, content)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      String stored = runtime.documentFieldOps().getDocumentContent("fixture");
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      checks.add(() -> assertEquals(content, stored));
      for (String word : List.of("repercussive", "all", "jason", "adams", "avneet", "nahal")) {
        checks.add(() -> assertEquals(1L, runtime.readPathOps().search(
            new TermQuery(new Term(SchemaFields.CONTENT, word)), 10, Set.of(SchemaFields.DOC_ID),
            null, null).totalHits(), "keyword " + word + " absent from stored [" + stored + "]"));
      }
      checks.add(() -> assertFalse(stored.contains("repercussiveAll"), stored));
      checks.add(() -> assertFalse(stored.contains("AdamsAvneet"), stored));
      assertAll(checks);
    }
  }

  @ParameterizedTest(name = "S2/S5 {0}")
  @ValueSource(strings = {"docx", "html", "odt", "doc"})
  void s2s5ProductionChildLineBreak(String format) throws Exception {
    Path file = fixture(format, "<table><tr><td><p>repercussive<br/>All</p></td>"
        + "<td><p>Jason Adams<br/>Avneet Nahal</p></td></tr></table>");
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      var artifact = sandbox.extract(file);
      assertEquals("tika-policy-structured", artifact.parserId());
      assertStoredAndSearchable(artifact.result().content());
    }
  }

  @ParameterizedTest(name = "S3/S5 {0}")
  @ValueSource(strings = {"docx", "html", "odt", "doc"})
  void s3s5ProductionChildNestedTable(String format) throws Exception {
    Path file = fixture(format, "<table><tr><td><p>outer</p><table><tr>"
        + "<td><p>inner</p></td><td><p>second</p></td></tr></table>"
        + "<p>tail</p></td></tr></table>");
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      String actual = sandbox.extract(file).result().content();
      assertTrue(actual.strip().matches("outer\\s+inner\\s+second\\s+tail"), actual);
    }
  }

  @ParameterizedTest(name = "S4/S5 {0} cell={1}")
  @CsvSource({"html,false", "html,true", "odt,false", "odt,true"})
  void s4s5ProductionChildLists(String format, boolean cell) throws Exception {
    String list = "<ul><li><p>repercussive</p><p>All<br/>ending</p></li></ul>";
    Path file = fixture(format, cell ? "<table><tr><td>" + list + "</td></tr></table>" : list);
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      var artifact = sandbox.extract(file);
      String actual = artifact.result().content();
      assertTrue(actual.contains("repercussive"), actual);
      assertTrue(actual.matches("(?s).*repercussive\\s+All\\s+ending.*"), actual);
      if (cell) {
        assertFalse(actual.contains("- repercussive"), "duplicate standalone list: " + actual);
        assertEquals(actual.indexOf("repercussive"), actual.lastIndexOf("repercussive"), actual);
      }
    }
  }

  @ParameterizedTest(name = "P1/P2 {0}")
  @ValueSource(strings = {"docx", "html", "odt", "doc"})
  void p1p2ProductionChildSimpleTableAndFollowingParagraph(String format) throws Exception {
    Path file = fixture(format, "<p>introduction</p><table><tr><th><p> Name </p></th>"
        + "<th><p> Value </p></th></tr><tr><td><p> Revenue </p></td>"
        + "<td><p> reper<b>cus</b>sive </p></td></tr></table><p>aftertable</p>");
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      String actual = sandbox.extract(file).result().content();
      assertTrue(actual.contains("Name = Revenue\nRevenue, Value = repercussive"), actual);
      assertTrue(actual.startsWith("introduction"), actual);
      assertTrue(actual.endsWith("aftertable"), actual);
    }
  }

  @Test
  void p2ProductionChildPdfPhysicalLineHyphenJoining() throws Exception {
    Path pdf = temp.resolve("physical-lines.pdf");
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      try (var text = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
        text.beginText();
        text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        text.newLineAtOffset(72, 720);
        text.showText("This sentence contains reper-");
        text.newLineAtOffset(0, -14);
        text.showText("cussive effects and enough readable digital text to avoid OCR.");
        text.endText();
      }
      document.save(pdf.toFile());
    }
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      var artifact = sandbox.extract(pdf);
      assertEquals("tika-policy-structured", artifact.parserId());
      assertTrue(artifact.result().content().contains("repercussive"), artifact.toString());
      assertTrue(artifact.result().content().contains("effects"), artifact.toString());
    }
  }

  @Test
  void p2FlatFallbackStillExtractsText() throws Exception {
    Path file = fixture("docx", "<table><tr><td><p>repercussive</p><p>All</p></td></tr></table>");
    try (var extractor = new StructuredContentExtractor()) {
      var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
      org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("independent forced fallback"))
          .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      field.set(extractor, parser);
      var result = extractor.extractWithStatus(file);
      assertEquals(0, result.summary().tableCount(), "really used flat fallback");
      assertTrue(result.result().content().matches("(?s).*repercussive\\s+All.*"), result.toString());
    }
  }

  @Test
  // Six policies need six extraction children (~5 s each): above the default 30 s budget.
  @org.junit.jupiter.api.Timeout(value = 120, unit = TimeUnit.SECONDS)
  void p3ProductionChildCapsAndTruncationWithSeparators() throws Exception {
    Path file = fixture("docx", "<table><tr><td>" + "<p>ab😀cd</p>".repeat(50)
        + "</td></tr></table>");
    for (int cap : List.of(1, 7, 8, 31, 48, 64)) {
      var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
          cap, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
      try (var sandbox = child(policy)) {
        var artifact = sandbox.extract(file);
        assertTrue(artifact.result().content().length() <= cap, artifact.toString());
        TableCellBoundaryContractTest.assertWholeSurrogates(artifact.result().content());
        assertTrue(artifact.truncated(), artifact.toString());
        assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
        artifact.validate(policy, "cell-boundary-limit");
      }
    }
  }

  @Test
  void p4ExtractionPolicyIdentityUnchanged() {
    assertEquals("tika-default-v12", TikaExtractionPolicy.defaults().policyId());
    assertEquals("tika-config-v12-100000-1000000", TikaExtractionPolicy.fromWorkerLimits(
        new ResolvedConfig.Worker(100_000, 1_000_000)).policyId());
  }

  @Test
  void extraReusedChildHasNoCrossFileSeparatorState() throws Exception {
    Path table = fixture("docx", "<table><tr><td><p>alpha</p><p>beta</p></td></tr></table>");
    Path plain = Files.writeString(temp.resolve("plain.txt"), "ordinary content");
    try (var sandbox = child(TikaExtractionPolicy.defaults())) {
      String first = sandbox.extract(table).result().content();
      long pid = sandbox.firstChildPid();
      assertEquals("ordinary content", sandbox.extract(plain).result().content());
      assertEquals(first, sandbox.extract(table).result().content());
      assertEquals(pid, sandbox.firstChildPid());
      assertEquals(1, sandbox.spawnCount());
    }
  }

  private Path fixture(String format, String body) throws Exception {
    Path output = temp.resolve("fixture." + format);
    if (format.equals("html")) return Files.writeString(output, "<html><body>" + body + "</body></html>");
    var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new InputSource(new StringReader("<root>" + body + "</root>")));
    if (format.equals("odt")) {
      String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
          + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
          + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.3\">"
          + "<office:body><office:text>" + render(xml.getDocumentElement(), false)
          + "</office:text></office:body></office:document-content>";
      zip(output, Map.of("mimetype", "application/vnd.oasis.opendocument.text", "content.xml", content,
          "META-INF/manifest.xml", "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">"
              + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
              + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>"));
      return output;
    }
    Path docx = temp.resolve("fixture.docx");
    zip(docx, Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>" + render(xml.getDocumentElement(), true) + "<w:sectPr/></w:body></w:document>"));
    if (!format.equals("doc")) return docx;
    assumeTrue(executableOnPath("libreoffice"),
        "Legacy DOC fixtures require LibreOffice, but libreoffice is not executable on PATH");
    var builder = new ProcessBuilder("libreoffice", "--headless",
        "-env:UserInstallation=" + temp.resolve("lo-profile").toUri(), "--convert-to", "doc:MS Word 97",
        "--outdir", temp.toString(), docx.toString()).redirectErrorStream(true);
    builder.environment().remove("DISPLAY");
    builder.environment().remove("WAYLAND_DISPLAY");
    Path log = temp.resolve("conversion.log");
    var process = builder.redirectOutput(log.toFile()).start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor();
      fail("legacy DOC fixture conversion timed out: " + Files.readString(log));
    }
    System.out.println("Legacy DOC fixture generation: " + Files.readString(log));
    assertEquals(0, process.exitValue(), Files.readString(log));
    assertTrue(Files.isRegularFile(output), "LibreOffice did not generate a binary DOC: " + Files.readString(log));
    return output;
  }

  private static boolean executableOnPath(String executable) {
    String path = System.getenv("PATH");
    String name = io.justsearch.configuration.PlatformPaths.isWindows() ? executable + ".exe" : executable;
    return path != null && Arrays.stream(path.split(java.io.File.pathSeparator))
        .map(directory -> Path.of(directory, name))
        .anyMatch(file -> Files.isRegularFile(file) && Files.isExecutable(file));
  }

  private static String render(Node node, boolean word) {
    if (node.getNodeType() == Node.TEXT_NODE) {
      String value = node.getNodeValue().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
      return word ? "<w:r><w:t xml:space=\"preserve\">" + value + "</w:t></w:r>" : value;
    }
    StringBuilder inside = new StringBuilder();
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) inside.append(render(child, word));
    String tag = switch (node.getNodeName()) {
      case "table" -> word ? "w:tbl" : "table:table";
      case "tr" -> word ? "w:tr" : "table:table-row";
      case "td", "th" -> word ? "w:tc" : "table:table-cell";
      case "p" -> word ? "w:p" : "text:p";
      case "ul", "ol" -> word ? null : "text:list";
      case "li" -> word ? null : "text:list-item";
      case "br" -> word ? "w:br" : "text:line-break";
      default -> null;
    };
    if (tag == null) return inside.toString();
    if (node.getNodeName().equals("br")) return word ? "<w:r><w:br/></w:r>" : "<text:line-break/>";
    return "<" + tag + ">" + inside + "</" + tag + ">";
  }

  private static void zip(Path output, Map<String, String> entries) throws Exception {
    try (var out = new ZipOutputStream(Files.newOutputStream(output))) {
      var names = new ArrayList<>(entries.keySet());
      names.sort(Comparator.comparing(name -> name.equals("mimetype") ? "" : name));
      for (String name : names) {
        byte[] bytes = entries.get(name).getBytes(StandardCharsets.UTF_8);
        var entry = new ZipEntry(name);
        if (name.equals("mimetype")) {
          var crc = new CRC32();
          crc.update(bytes);
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(bytes.length);
          entry.setCompressedSize(bytes.length);
          entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
      }
    }
  }
}
