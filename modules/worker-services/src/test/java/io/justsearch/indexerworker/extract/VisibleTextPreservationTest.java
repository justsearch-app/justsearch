/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisibleTextPreservationTest {
  @TempDir Path temp;

  @Test
  void p2PlainTextIsUnchangedIncludingLiteralNoteMarkers() throws Exception {
    String text = "Ordinary body [footnoteRef:1] and [endnoteRef:2] remain literal.\nSecond line.";
    Path file = Files.writeString(temp.resolve("body.txt"), text);
    try (var child = child(); var flat = new ContentExtractor()) {
      assertEquals(text, child.extract(file).result().content());
      assertEquals(text, flat.extract(file).content().strip());
    }
  }

  @Test
  void p2DigitalPdfKeepsVisibleText() throws Exception {
    Path file = temp.resolve("body.pdf");
    String text = "Readable digital PDF body remains searchable with every visible word.";
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(FontName.HELVETICA), 12);
        stream.newLineAtOffset(72, 720);
        stream.showText(text);
        stream.endText();
      }
      document.save(file.toFile());
    }
    try (var child = child(); var flat = new ContentExtractor()) {
      assertEquals(text, child.extract(file).result().content());
      assertEquals(text, flat.extract(file).content().strip());
    }
  }

  @Test
  void p2SpreadsheetKeepsHeadingAndCellLayout() throws Exception {
    var fixture = FormatCapabilityFixtureFactory.generate(FormatId.XLSX);
    Path file = Files.write(temp.resolve(fixture.fileName()), fixture.bytes());
    try (var child = child()) {
      String text = child.extract(file).result().content();
      assertEquals("# Capability\n\nFixture = Format breadth\nFormat breadth, Evidence = JUSTSEARCH_XLSX_SMOKE_MARKER", text);
    }
  }

  @Test
  void p2MailBodyAndTextAttachmentRemain() throws Exception {
    var fixture = FormatCapabilityFixtureFactory.generate(FormatId.EML);
    Path file = Files.write(temp.resolve(fixture.fileName()), fixture.bytes());
    try (var child = child(); var flat = new ContentExtractor()) {
      String structured = child.extract(file).result().content();
      String fallback = flat.extract(file).content();
      for (String raw : new String[] {structured, fallback}) {
        String text = raw.replace("\r\n", "\n");
        assertTrue(text.contains("JUSTSEARCH_EML_BODY_MARKER\nOwned message body."), text);
        assertTrue(text.contains("JUSTSEARCH_EML_ATTACHMENT_MARKER\nOwned attachment content from the deterministic EML recipe."), text);
      }
    }
  }

  private static PersistentExtractionSandbox child() {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(),
        OcrRoutingConfig.disabled(), Duration.ofSeconds(45), 1, 128, null);
  }
}
