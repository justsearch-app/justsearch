/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.rag.ChunkOffsetMath;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.TikaCoreProperties.EmbeddedResourceType;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.AttributesImpl;

class EmbeddedPictureRoleTest {
  @ParameterizedTest(name = "S10 role beats name {0}")
  @ValueSource(strings = {"docProps/thumbnail.emf", "docProps/thumbnail.wmf", "unexpected-name.bin"})
  void s10ThumbnailRoleIsDeclinedBeforeReadingOrParsing(String name) throws Exception {
    var calls = new AtomicInteger();
    var context = context(calls);
    var budget = new EmbeddedResourceBudget(policy(1), 1_000, context);
    Metadata metadata = metadata(name, EmbeddedResourceType.THUMBNAIL);
    var neverRead = mock(InputStream.class);
    when(neverRead.read(any(byte[].class), anyInt(), anyInt()))
        .thenThrow(new AssertionError("A preview stream must never be read"));
    clearInvocations(neverRead);
    assertAll(
        () -> assertFalse(budget.shouldParseEmbedded(metadata), "thumbnail must be declined"),
        () -> assertDoesNotThrow(() -> budget.parseEmbedded(neverRead,
            new BodyContentHandler(), metadata, true)),
        () -> assertEquals(0, budget.resources()),
        () -> assertEquals(0, budget.maxDepth()),
        () -> assertEquals(0, calls.get()),
        () -> verifyNoInteractions(neverRead));
    assertDoesNotThrow(budget::check);
  }

  @Test
  void s10SkippedThumbnailDoesNotConsumeRealAttachmentAllowance() throws Exception {
    var calls = new AtomicInteger();
    var context = context(calls);
    var budget = new EmbeddedResourceBudget(policy(1), 1_000, context);
    budget.parseEmbedded(new ByteArrayInputStream(new byte[0]), new BodyContentHandler(),
        metadata("preview.emf", EmbeddedResourceType.THUMBNAIL), true);
    Metadata real = metadata("report.txt", EmbeddedResourceType.ATTACHMENT);
    assertTrue(budget.shouldParseEmbedded(real));
    budget.parseEmbedded(new ByteArrayInputStream("real words".getBytes(StandardCharsets.UTF_8)),
        new BodyContentHandler(), real, true);
    assertEquals(1, budget.resources());
    assertEquals(1, calls.get());
    assertThrows(RuntimeException.class, () -> budget.shouldParseEmbedded(real));
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class, budget::check);
    assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
  }

  @ParameterizedTest(name = "S2/S7 inline name {0}")
  @ValueSource(strings = {"image7.emf", "thumbnail.emf", "docProps/thumbnail.emf"})
  void s2InlinePictureHasNoSyntheticHeadingButRetainsVectorText(String name) throws Exception {
    var calls = new AtomicInteger();
    var context = context(calls);
    var budget = new EmbeddedResourceBudget(policy(1), 1_000, context);
    var handler = new StructuredContentHandler(10_000);
    element(handler, "h1", "First real heading");
    element(handler, "p", "beforepicture");
    budget.parseEmbedded(new ByteArrayInputStream(new byte[] {1}), handler,
        metadata(name, EmbeddedResourceType.INLINE), true);
    element(handler, "p", "afterpicture");
    element(handler, "h2", "Second real heading");
    element(handler, "p", "finalparagraph");
    String text = handler.getDocument().toAnnotatedText(10_000).text();
    var headings = new ChunkOffsetMath.MetadataIndex(text);
    assertAll(
        () -> assertEquals("# First real heading\n\nbeforepicture\n\nVisible vector text\n\nafterpicture"
            + "\n\n## Second real heading\n\nfinalparagraph", text),
        () -> assertEquals("First real heading", headings.precedingHeading(text.indexOf("afterpicture")).text()),
        () -> assertEquals("Second real heading", headings.precedingHeading(text.indexOf("finalparagraph")).text()));
    assertEquals(1, calls.get());
  }

  @ParameterizedTest(name = "S6 attachment name {0}")
  @ValueSource(strings = {"image7.emf", "thumbnail.emf", "docProps/thumbnail.emf"})
  void s6AttachmentRoleKeepsPictureLikeName(String name) throws Exception {
    var calls = new AtomicInteger();
    var context = context(calls);
    var budget = new EmbeddedResourceBudget(policy(1), 1_000, context);
    var handler = new StructuredContentHandler(10_000);
    budget.parseEmbedded(new ByteArrayInputStream(new byte[] {1}), handler,
        metadata(name, EmbeddedResourceType.ATTACHMENT), true);
    String text = handler.getDocument().toAnnotatedText(10_000).text();
    assertTrue(text.contains(name), text);
    assertTrue(text.contains("Visible vector text"), text);
    assertEquals(1, budget.resources());
  }

  @Test
  void p4RealResourceCountLimitStillFailsWithTypedReason() throws Exception {
    var context = context(new AtomicInteger());
    var budget = new EmbeddedResourceBudget(policy(1), 1_000, context);
    Metadata real = metadata("real.txt", EmbeddedResourceType.ATTACHMENT);
    budget.parseEmbedded(new ByteArrayInputStream(new byte[] {1}), new BodyContentHandler(), real, true);
    assertThrows(RuntimeException.class, () -> budget.shouldParseEmbedded(real));
    var failure = assertThrows(ContentExtractor.BudgetExceededException.class, budget::check);
    assertEquals("EMBEDDED_RESOURCE_LIMIT", failure.reasonCode());
  }

  private static Metadata metadata(String name, EmbeddedResourceType role) {
    var metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
    metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, role.toString());
    metadata.set(Metadata.CONTENT_TYPE, "image/emf");
    return metadata;
  }

  private static ParseContext context(AtomicInteger calls) {
    var context = new ParseContext();
    Parser parser = mock(Parser.class);
    when(parser.getSupportedTypes(context)).thenReturn(Set.of(MediaType.image("emf")));
    try {
      doAnswer(invocation -> {
        calls.incrementAndGet();
        ContentHandler handler = invocation.getArgument(1);
        handler.startDocument();
        handler.startElement("http://www.w3.org/1999/xhtml", "html", "html", new AttributesImpl());
        handler.startElement("http://www.w3.org/1999/xhtml", "body", "body", new AttributesImpl());
        handler.startElement("http://www.w3.org/1999/xhtml", "p", "p", new AttributesImpl());
        char[] text = "Visible vector text".toCharArray();
        handler.characters(text, 0, text.length);
        handler.endElement("http://www.w3.org/1999/xhtml", "p", "p");
        handler.endElement("http://www.w3.org/1999/xhtml", "body", "body");
        handler.endElement("http://www.w3.org/1999/xhtml", "html", "html");
        handler.endDocument();
        return null;
      }).when(parser).parse(any(), any(), any(), any());
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
    context.set(Parser.class, parser);
    return context;
  }

  private static void element(ContentHandler handler, String name, String text) throws Exception {
    handler.startElement("", name, name, new AttributesImpl());
    handler.characters(text.toCharArray(), 0, text.length());
    handler.endElement("", name, name);
  }

  private static TikaExtractionPolicy policy(int resources) {
    var base = TikaExtractionPolicy.defaults();
    return new TikaExtractionPolicy("picture-role-test", base.maxExtractedChars(), base.maxInputBytes(),
        base.maxOfficeInputBytes(), base.maxMetadataEntries(), base.maxMetadataKeyChars(),
        base.maxMetadataValueChars(), resources, base.maxEmbeddedDepth(), base.maxCompressionRatio(),
        base.requireXmlEntitySafeTikaDefaults(), base.allowedMimeTypes(), base.excludedMimeTypes());
  }
}
