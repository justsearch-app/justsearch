/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.helpers.AttributesImpl;

class HtmlCaptionVisibleOrderTest {
  @TempDir Path temp;

  @Test
  void s4ConsecutiveCaptionsPrecedeTheirTablesInTheExtractionChild() throws Exception {
    Path file = html("<p>introduction</p>" + table("First caption", "firstcell")
        + table("Second caption", "secondcell") + "<p>conclusion</p>");
    try (var child = child(TikaExtractionPolicy.defaults())) {
      var artifact = child.extract(file);
      assertEquals("introduction\n\nFirst caption\n\nfirstcell\n\nSecond caption\n\nsecondcell\n\nconclusion",
          artifact.result().content());
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
    }
  }

  @ParameterizedTest(name = "S9 limit {0} in {1}")
  @CsvSource({"12,caption", "23,cell"})
  void s9PartialTableFinalizationKeepsCaptionBeforeCell(int cap, String stoppedIn) throws Exception {
    Path file = html("<table><caption>captionword</caption><tr><td>" + "cellword ".repeat(30)
        + "</td></tr></table>");
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(cap,
        TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    try (var child = child(policy)) {
      var artifact = child.extract(file);
      String text = artifact.result().content();
      assertTrue(text.startsWith("captionword"), stoppedIn + ": " + text);
      if (stoppedIn.equals("cell")) {
        assertTrue(text.contains("cell"), text);
        assertTrue(text.indexOf("captionword") < text.indexOf("cell"), text);
      }
      assertTrue(text.length() <= cap, text);
      assertTrue(artifact.truncated(), artifact.toString());
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      artifact.validate(policy, "caption-limit");
    }
  }

  @ParameterizedTest(name = "S9 SAX limit {0}")
  @ValueSource(ints = {5, 15, 25})
  void s9CaptionAndPartialCellOrderWithExactCharacterCallbacks(int cap) throws Exception {
    var handler = new StructuredContentHandler(cap);
    var attrs = new AttributesImpl();
    handler.startElement("", "table", "table", attrs);
    handler.startElement("", "caption", "caption", attrs);
    characters(handler, "captionword");
    handler.endElement("", "caption", "caption");
    handler.startElement("", "tr", "tr", attrs);
    handler.startElement("", "td", "td", attrs);
    characters(handler, "cellword repeated words to reach limit");
    String text = handler.getDocument().toAnnotatedText(cap).text();
    assertTrue(text.startsWith("captionword".substring(0, Math.min(cap, 11))), text);
    if (cap > 11) assertTrue(text.contains("cell"), text);
    assertTrue(handler.isLimitReached());
    assertEquals(text, handler.getDocument().toAnnotatedText(cap).text(), "finalization is idempotent");
  }

  @ParameterizedTest(name = "S8 literal markers in {0}")
  @ValueSource(strings = {"txt", "md", "html"})
  void s8NonWordLiteralNoteReferencesRemain(String extension) throws Exception {
    String literal = "before [footnoteRef:1][endnoteRef:2] after";
    Path file = Files.writeString(temp.resolve("literal." + extension),
        extension.equals("html") ? "<html><body><p>" + literal + "</p></body></html>" : literal);
    try (var child = child(TikaExtractionPolicy.defaults()); var flat = new ContentExtractor()) {
      assertEquals(literal, child.extract(file).result().content());
      assertEquals(literal, flat.extract(file).content().strip());
    }
  }

  @ParameterizedTest(name = "S6 real heading {0}")
  @ValueSource(strings = {"image7.emf", "thumbnail.emf", "docProps/thumbnail.emf"})
  void s6RealBodyHeadingsWithPictureLikeNamesRemain(String name) throws Exception {
    Path file = html("<h1>" + name + "</h1><p>ordinary body</p>");
    try (var child = child(TikaExtractionPolicy.defaults())) {
      assertEquals("# " + name + "\n\nordinary body", child.extract(file).result().content());
    }
  }

  @Test
  void p3CellParagraphsAndListsKeepBoundariesAndLayout() throws Exception {
    Path file = html("<h1>Report</h1><p>Introduction</p><table><tr><td><p>repercussive</p><p>All</p>"
        + "</td><td><ul><li>Jason Adams</li><li>Avneet Nahal</li></ul></td></tr></table>"
        + "<ul><li>firstitem</li><li>seconditem</li></ul><p>ending</p>");
    try (var child = child(TikaExtractionPolicy.defaults())) {
      assertEquals("# Report\n\nIntroduction\n\nrepercussive All\nJason Adams Avneet Nahal"
          + "\n\n- firstitem\n- seconditem\n\nending", child.extract(file).result().content());
    }
  }

  @ParameterizedTest(name = "P4 cap {0}")
  @ValueSource(ints = {1, 7, 31, 64})
  void p4ContentLimitAndTruncationRemainReported(int cap) throws Exception {
    Path file = html("<h1>Report</h1><p>" + "ordinary readable content ".repeat(30) + "</p>");
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(cap,
        TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    try (var child = child(policy)) {
      var artifact = child.extract(file);
      assertTrue(artifact.result().content().length() <= cap, artifact.toString());
      assertTrue(artifact.truncated(), artifact.toString());
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      artifact.validate(policy, "preserved-limit");
    }
  }

  private Path html(String body) throws Exception {
    return Files.writeString(temp.resolve("caption.html"), "<html><body>" + body + "</body></html>");
  }
  private static String table(String caption, String cell) {
    return "<table><caption>" + caption + "</caption><tr><td>" + cell + "</td></tr></table>";
  }
  private static void characters(StructuredContentHandler handler, String text) throws Exception {
    handler.characters(text.toCharArray(), 0, text.length());
  }
  private static PersistentExtractionSandbox child(TikaExtractionPolicy policy) {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(45), 1, 128, null);
  }
}
