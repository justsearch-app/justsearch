/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LossyDecodingExtractionTest {
  private static final String LOSS = "TEXT_LOST_WHILE_DECODING";
  private static final String TEXT_PARSER = "org.apache.tika.parser.csv.SurrogateSafeTextAndCSVParser";
  private static final String HTML_PARSER = "org.apache.tika.parser.html.JSoupParser";
  private static final String INLINE_IMAGE = "<img src=\"data:image/png;base64,"
      + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=\">";
  @TempDir Path tempDir;

  @Test
  void invalidUtf8SequenceMidFileIsPartial() throws Exception {
    lossy("sequence.txt", utf8("Valid words ", 0xc3, 0x28), "UTF-8", TEXT_PARSER);
  }
  @Test
  void invalidUtf8ByteIsPartial() throws Exception {
    lossy("byte.txt", utf8("Valid words ", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void truncatedUtf8SequenceMidFileIsPartial() throws Exception {
    lossy("middle.txt", concat(utf8("Valid words ", 0xe2, 0x82), " ending".getBytes(StandardCharsets.UTF_8)), "UTF-8", TEXT_PARSER);
  }
  @Test
  void incompleteUtf8AtRealEofIsPartial() throws Exception {
    lossy("eof.txt", utf8("Valid words ", 0xe2, 0x82), "UTF-8", TEXT_PARSER);
  }

  @Test void malformedUtf8AcrossAuditBufferInMemory() throws Exception {
    assertAuditBoundary(64 * 1024 - 1, false, "UTF-8", true, 0xc3, 0x28);
  }

  @Test void malformedUtf8AcrossAuditBufferSpilled() throws Exception {
    assertAuditBoundary(17 * 64 * 1024 - 1, true, "UTF-8", true, 0xc3, 0x28);
  }

  @Test void incompleteUtf8AfterFullAuditBufferInMemory() throws Exception {
    assertAuditBoundary(64 * 1024, false, "UTF-8", true, 0xe2, 0x82);
  }

  @Test void incompleteUtf8AfterFullAuditBufferSpilled() throws Exception {
    assertAuditBoundary(17 * 64 * 1024, true, "UTF-8", true, 0xe2, 0x82);
  }

  @Test void longAsciiRunBeforeInvalidUtf8ByteInMemory() throws Exception {
    assertAuditBoundary(3 * 64 * 1024, false, "UTF-8", true, 0xff);
  }

  @Test void longAsciiRunBeforeInvalidUtf8ByteSpilled() throws Exception {
    assertAuditBoundary(19 * 64 * 1024, true, "UTF-8", true, 0xff);
  }

  @Test void cleanAsciiAuditIsNotLossy() throws Exception {
    assertAuditBoundary(0, false, "US-ASCII", false);
    for (int asciiBytes : new int[] {0, 7, 8, 9, 15, 16, 17}) {
      assertAuditBoundary(asciiBytes, false, "US-ASCII", false, 0x41);
    }
    assertAuditBoundary(19 * 64 * 1024, true, "US-ASCII", false, 0x41);
  }

  @Test void longAsciiRunBeforeInvalidAsciiByteInMemory() throws Exception {
    assertAuditBoundary(3 * 64 * 1024, false, "US-ASCII", true, 0x80);
  }

  @Test void longAsciiRunBeforeInvalidAsciiByteSpilled() throws Exception {
    assertAuditBoundary(19 * 64 * 1024, true, "US-ASCII", true, 0x80);
  }

  @Test void validUtf8AcrossAuditBufferInMemory() throws Exception {
    assertAuditBoundary(64 * 1024 - 1, false, "UTF-8", false, 0xf0, 0x9f, 0x98, 0x80, 0x61);
  }

  @Test void validUtf8AcrossAuditBufferSpilled() throws Exception {
    assertAuditBoundary(17 * 64 * 1024 - 1, true, "UTF-8", false, 0xf0, 0x9f, 0x98, 0x80, 0x61);
  }

  @Test void utf8InvalidLeadBytesAreLossy() throws Exception {
    for (int lead = 0x80; lead <= 0xff; lead++) {
      if (lead <= 0xc1 || lead >= 0xf5) {
        assertAuditBoundary(8, false, "UTF-8", true, lead, 0x80, 0x80, 0x80);
      }
    }
  }

  @Test void utf8OverlongFormsAreLossy() throws Exception {
    assertAuditBoundary(8, false, "UTF-8", true, 0xc0, 0x80);
    assertAuditBoundary(8, false, "UTF-8", true, 0xc1, 0xbf);
    assertAuditBoundary(8, false, "UTF-8", true, 0xe0, 0x9f, 0xbf);
    assertAuditBoundary(8, false, "UTF-8", true, 0xf0, 0x8f, 0xbf, 0xbf);
  }

  @Test void utf8EncodedSurrogatesAreLossy() throws Exception {
    assertAuditBoundary(8, false, "UTF-8", true, 0xed, 0xa0, 0x80);
    assertAuditBoundary(8, false, "UTF-8", true, 0xed, 0xbf, 0xbf);
  }

  @Test void utf8CodePointsAboveUnicodeMaximumAreLossy() throws Exception {
    assertAuditBoundary(8, false, "UTF-8", true, 0xf4, 0x90, 0x80, 0x80);
    assertAuditBoundary(8, false, "UTF-8", true, 0xf4, 0xbf, 0xbf, 0xbf);
  }

  @Test void utf8AuditMatchesJdkStrictDecoderOnGeneratedBytes() throws Exception {
    var random = new Random(0x55544638L);
    try (var factory = new PreparedExtractionInput.Factory()) {
      for (int sample = 0; sample < 4096; sample++) {
        byte[] valid = randomUtf8(random);
        byte[] bytes = switch (sample % 10) {
          case 0, 1, 2 -> valid;
          case 3 -> concat(valid, new byte[] {(byte) (random.nextBoolean()
              ? 0x80 + random.nextInt(0x42) : 0xf5 + random.nextInt(11))});
          case 4 -> concat(valid, new byte[] {(byte) 0xe2, (byte) 0x82, 0x20});
          case 5 -> concat(valid, new byte[] {(byte) 0xe0, (byte) (0x80 + random.nextInt(32)), (byte) 0x80});
          case 6 -> concat(valid, new byte[] {(byte) 0xed, (byte) (0xa0 + random.nextInt(32)), (byte) 0x80});
          case 7 -> concat(valid, new byte[] {(byte) 0xf4, (byte) (0x90 + random.nextInt(48)), (byte) 0x80, (byte) 0x80});
          case 8 -> concat(valid, new byte[] {(byte) 0xf0, (byte) 0x90, (byte) 0x80});
          default -> {
            byte[] arbitrary = new byte[1 + random.nextInt(32)];
            random.nextBytes(arbitrary);
            yield concat(valid, arbitrary);
          }
        };
        assertAuditMatchesJdk(factory, bytes, false, "generated sample " + sample);
      }
      byte[][] sequences = {
          {(byte) 0xc2, (byte) 0x80}, {(byte) 0xe0, (byte) 0xa0, (byte) 0x80},
          {(byte) 0xed, (byte) 0x9f, (byte) 0xbf}, {(byte) 0xf0, (byte) 0x90, (byte) 0x80, (byte) 0x80},
          {(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf}, {(byte) 0xff},
          {(byte) 0xe2, 0x20, (byte) 0x80}, {(byte) 0xe0, (byte) 0x9f, (byte) 0xbf},
          {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
          {(byte) 0xf0, (byte) 0x90, (byte) 0x80, 0x20}, {(byte) 0xc2, 0x20},
          {(byte) 0xf0, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf}, {(byte) 0xc0, (byte) 0x80},
          {(byte) 0xf0, (byte) 0x90, (byte) 0x80}, {(byte) 0xc2}
      };
      for (boolean spilled : new boolean[] {false, true}) {
        int boundary = (spilled ? 17 : 1) * 64 * 1024;
        for (int split = 0; split <= 3; split++) {
          for (int sequence = 0; sequence < sequences.length; sequence++) {
            byte[] prefix = new byte[boundary - split];
            Arrays.fill(prefix, (byte) 'a');
            // Exercise both continuation carry-over and incomplete sequences at real EOF.
            byte[] bytes = concat(prefix, sequences[sequence]);
            if (sequence < 5) bytes = concat(bytes, randomUtf8(random));
            assertAuditMatchesJdk(factory, bytes, spilled,
                "boundary sample " + spilled + "/" + split + "/" + sequence);
          }
        }
      }
    }
  }

  private static byte[] randomUtf8(Random random) {
    int[] edges = {0, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff};
    var text = new StringBuilder("Valid words café ");
    int length = random.nextInt(32);
    for (int i = 0; i < length; i++) {
      int codePoint = random.nextBoolean() ? edges[random.nextInt(edges.length)]
          : random.nextInt(0x110000);
      if (codePoint < 0xd800 || codePoint > 0xdfff) text.appendCodePoint(codePoint);
    }
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void assertAuditMatchesJdk(PreparedExtractionInput.Factory factory, byte[] bytes,
      boolean spilled, String sample) throws Exception {
    boolean expected;
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      expected = false;
    } catch (CharacterCodingException malformed) {
      expected = true;
    }
    Path file = write("differential.txt", bytes);
    try (var input = factory.prepare(file, TikaExtractionPolicy.defaults())) {
      assertEquals(spilled, input.isDiskBacked(), sample + " storage");
      Files.delete(file);
      Metadata metadata = input.metadata();
      metadata.set(Metadata.CONTENT_ENCODING, "UTF-8");
      var context = new ParseContext();
      var record = mock(ParseRecord.class);
      when(record.getParsers()).thenReturn(new String[] {TEXT_PARSER});
      context.set(ParseRecord.class, record);
      assertEquals(expected, DecodingLossAudit.isLossy(input, metadata, context), sample);
    }
  }

  @Test void genericUtf16BomSelectsByteOrderForIncompleteSurrogate() throws Exception {
    assertAuditBoundary(0, false, "UTF-16", true, 0xff, 0xfe, 0x00, 0xd8);
  }

  @Test void iso2022JpShiftStateSurvivesAuditReads() throws Exception {
    // End the JIS X 0208 escape at a full buffer; the next read starts with an invalid byte.
    assertAuditBoundary(64 * 1024 - 3, false, "ISO-2022-JP", true, 0x1b, 0x24, 0x42, 0x20);
    assertAuditBoundary(17 * 64 * 1024 - 3, true, "ISO-2022-JP", true, 0x1b, 0x24, 0x42, 0x20);
  }

  private void assertAuditBoundary(int asciiBytes, boolean spilled, String charset,
      boolean lossy, int... suffix) throws Exception {
    byte[] bytes = new byte[asciiBytes + suffix.length];
    Arrays.fill(bytes, (byte) 'a');
    for (int i = 0; i < suffix.length; i++) bytes[asciiBytes + i] = (byte) suffix[i];
    Path file = write("audit.txt", bytes);
    try (var factory = new PreparedExtractionInput.Factory();
        var input = factory.prepare(file, TikaExtractionPolicy.defaults())) {
      assertEquals(spilled, input.isDiskBacked(), "prepared snapshot storage");
      Files.delete(file); // Replay must use the snapshot, without reopening the source.
      Metadata metadata = input.metadata();
      metadata.set(Metadata.CONTENT_ENCODING, charset);
      var context = new ParseContext();
      var record = mock(ParseRecord.class);
      when(record.getParsers()).thenReturn(new String[] {TEXT_PARSER});
      context.set(ParseRecord.class, record);
      assertEquals(lossy, DecodingLossAudit.isLossy(input, metadata, context));
    }
  }

  @Test
  void undefinedWindows1252ByteIsPartial() throws Exception {
    lossy("western.txt", concat("European café costs € — ™ ".repeat(40).getBytes(Charset.forName("windows-1252")), new byte[] {(byte) 0x81}), "windows-1252", TEXT_PARSER);
  }
  @Test
  void loneUtf16LowSurrogateIsPartial() throws Exception {
    lossy("utf16.txt", concat("\uFEFFValid words ".getBytes(StandardCharsets.UTF_16LE), new byte[] {0, (byte) 0xdc}), "UTF-16LE", TEXT_PARSER);
  }
  @Test
  void lossAfterDetectorWindowIsPartial() throws Exception {
    lossy("late.txt", utf8("Valid words ".repeat(10_000), 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void undeclaredUtf8LossAfterDetectorWindowIsPartial() throws Exception {
    lossy("undeclared.txt", concat("Valid words café € 😀 ".repeat(7_000).getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff}), "UTF-8", TEXT_PARSER);
  }
  @Test
  void declaredHtmlLossIsPartial() throws Exception {
    lossy("declared.html", html("<meta charset=UTF-8>", false), "UTF-8", HTML_PARSER);
  }
  @Test
  void htmlBomWinsConflictingDeclaration() throws Exception {
    lossy("conflict.html", concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, html("<meta charset=windows-1252>", false)), "UTF-8", HTML_PARSER);
  }
  @Test
  void lateHtmlDeclarationUsesReportedCharset() throws Exception {
    clean("late.html", html(" ".repeat(15_000) + "<meta charset=UTF-8>", false), "windows-1252", HTML_PARSER);
  }
  @Test void lateHtmlDeclarationReportsLossInActualWindows1252() throws Exception {
    byte[] bytes = html(" ".repeat(15_000) + "<meta charset=UTF-8>", false);
    for (int i = 0; i < bytes.length; i++) if (bytes[i] == (byte) 0xff) bytes[i] = (byte) 0x81;
    lossy("late-loss.html", bytes, "windows-1252", HTML_PARSER);
  }
  @Test
  void xhtmlThroughHtmlParserReportsLoss() throws Exception {
    lossy("body.xhtml", html("<meta charset=UTF-8>", false), "UTF-8", HTML_PARSER);
  }
  @Test
  void lossBeforeContentLimitIsPartialAndTruncated() throws Exception {
    assertTruncated(utf8("Valid words ", 0xff), "txt", false,
        "Valid words \uFFFD" + " Valid words".repeat(5) + " Valid");
  }
  @Test
  void lossAfterContentLimitIsPartialAndTruncated() throws Exception {
    assertTruncated(utf8("Valid words ".repeat(20_000), 0xff), "txt", false,
        "Valid words ".repeat(6) + "Valid wo");
  }
  @Test
  void markupHeavyHtmlLossAfterLimitIsPartialAndTruncated() throws Exception {
    assertTruncated(html("<meta charset=UTF-8>", true), "html", true,
        "Valid words\n\n".repeat(6) + "Va");
  }

  @Test void structuredFailureFlatFallbackStillReportsLoss() throws Exception {
    Path file = write("fallback.txt", utf8("Valid words ", 0xff));
    assertEncoding(file, "UTF-8", TEXT_PARSER);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      forceFlatFallback(extractor);
      assertLoss(extractor.extractArtifact(file), false);
    }
  }

  @Test void htmlParserMetaCannotDisableLossAudit() throws Exception {
    Path file = write("spoofed-parser.html", html(
        "<meta charset=UTF-8><meta name=\"X-TIKA:Parsed-By\" content=other>", false));
    var metadata = assertEncoding(file, "UTF-8", HTML_PARSER);
    assertTrue(Arrays.asList(metadata.getValues("X-TIKA:Parsed-By")).contains("other"));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertLoss(artifact, false);
      assertEquals(LOSS, artifact.partialReasonCode());
      assertEquals("Valid words\n\n\uFFFD", artifact.result().content());
    }
  }

  @Test void inlineImageCannotDisableHtmlLossAudit() throws Exception {
    assertInlineImageHtmlLoss(false, false);
  }

  @Test void plainTextParserOwnsOuterDocument() throws Exception {
    clean("outer.txt", utf8("Valid words café"), "UTF-8", TEXT_PARSER);
  }

  @Test void inlineImageHtmlParserOwnsOuterDocument() throws Exception {
    Path file = write("outer.html", ("<html><head><meta charset=UTF-8></head><body>"
        + INLINE_IMAGE + "<p>Valid words café</p></body></html>").getBytes(StandardCharsets.UTF_8));
    assertEncoding(file, "UTF-8", HTML_PARSER, "org.apache.tika.parser.image.ImageParser");
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertEquals("Valid words café", artifact.result().content());
      assertFalse(artifact.warnings().contains(LOSS));
    }
  }

  @Test void inlineImageCannotDisableCombinedHtmlLossAudit() throws Exception {
    assertInlineImageHtmlLoss(true, false);
  }

  @Test @Timeout(90)
  void sandboxInlineImageCannotDisableHtmlLossAudit() throws Exception {
    assertInlineImageHtmlLoss(false, true);
  }

  @Test @Timeout(90)
  void sandboxInlineImageCannotDisableCombinedHtmlLossAudit() throws Exception {
    assertInlineImageHtmlLoss(true, true);
  }

  private void assertInlineImageHtmlLoss(boolean truncated, boolean sandbox) throws Exception {
    // Put the image before the text limit so its parser runs even for truncated output.
    Path file = write("inline-image.html", concat(
        ("<html><head><meta charset=UTF-8></head><body>" + INLINE_IMAGE
            + "<p><b>Valid words</b></p>".repeat(truncated ? 2000 : 1)).getBytes(StandardCharsets.UTF_8),
        concat(new byte[] {(byte) 0xff}, "</body></html>".getBytes(StandardCharsets.UTF_8))));
    assertEncoding(file, "UTF-8", HTML_PARSER, "org.apache.tika.parser.image.ImageParser");
    var policy = truncated ? limited() : TikaExtractionPolicy.defaults();
    ExtractionArtifact artifact;
    if (sandbox) {
      try (var child = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
          PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
          OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 10, null)) {
        artifact = child.extract(file);
      }
    } else {
      try (var local = extractor(policy)) {
        artifact = local.extractArtifact(file);
      }
    }
    assertEquals(truncated ? "TEXT_LOST_WHILE_DECODING_AND_TRUNCATED" : LOSS,
        artifact.partialReasonCode());
    assertLoss(artifact, truncated);
    assertEquals(truncated ? "Valid words\n\n".repeat(6) + "Va" : "Valid words\n\n\uFFFD",
        artifact.result().content());
  }

  @Test void htmlEncodingMetaCannotHideLoss() throws Exception {
    Path file = write("spoofed-encoding.html", html(
        "<meta charset=UTF-8><meta name=\"Content-Encoding\" content=windows-1252>"
            + "<meta http-equiv=\"Content-Encoding\" content=windows-1252>"
            + "<meta property=\"Content-Encoding\" content=windows-1252>", false));
    var metadata = assertEncoding(file, "UTF-8", HTML_PARSER);
    assertTrue(Arrays.asList(metadata.getValues(Metadata.CONTENT_ENCODING)).contains("windows-1252"));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      assertLoss(extractor.extractArtifact(file), false);
    }
  }

  @Test void htmlEncodingMetaCannotInventLoss() throws Exception {
    Path file = write("clean-spoofed-encoding.html", ("<html><head><meta charset=windows-1252>"
        + "<meta name=\"Content-Encoding\" content=UTF-8>"
        + "<meta http-equiv=\"Content-Encoding\" content=UTF-8>"
        + "<meta property=\"Content-Encoding\" content=UTF-8>"
        + "</head><body>Valid words café</body></html>").getBytes(Charset.forName("windows-1252")));
    var metadata = assertEncoding(file, "windows-1252", HTML_PARSER);
    assertTrue(Arrays.asList(metadata.getValues(Metadata.CONTENT_ENCODING)).contains("UTF-8"));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.warnings().contains(LOSS));
      assertEquals("Valid words café", artifact.result().content());
    }
  }

  @Test void htmlParserMetaCannotEnableAuditForMail() throws Exception {
    byte[] header = ("From: writer@example.invalid\r\nTo: reader@example.invalid\r\nSubject: fixture\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    Path file = write("spoofed-parser.eml", concat(header, html(
        "<meta charset=UTF-8><meta name=\"X-TIKA:Parsed-By\" content=\"" + HTML_PARSER + "\">", false)));
    // The mail parser owns the document; the HTML body has separate metadata and charset.
    assertEncoding(file, null, "org.apache.tika.parser.mail.RFC822Parser");
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.warnings().contains(LOSS));
      assertTrue(artifact.result().content().contains("Valid words"));
    }
  }

  @Test void flatFallbackPreservesLossAndTruncation() throws Exception {
    Path file = write("fallback-lossy.txt", concat(utf8("Valid words ", 0xff),
        " Valid words".repeat(100).getBytes(StandardCharsets.UTF_8)));
    assertEncoding(file, "UTF-8", TEXT_PARSER);
    try (var extractor = extractor(limited())) {
      forceFlatFallback(extractor);
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      assertEquals("TEXT_LOST_WHILE_DECODING_AND_TRUNCATED", artifact.partialReasonCode());
      assertTrue(artifact.truncated());
      assertTrue(artifact.warnings().contains(LOSS));
      assertEquals("Valid words \uFFFD" + " Valid words".repeat(5) + " Valid ", artifact.result().content());
    }
  }

  @Test void flatFallbackPreservesCleanTruncation() throws Exception {
    Path file = write("fallback-clean.txt", utf8("Valid words ".repeat(100)));
    assertEncoding(file, "UTF-8", TEXT_PARSER);
    try (var extractor = extractor(limited())) {
      forceFlatFallback(extractor);
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      assertEquals("SUCCESS_PARTIAL", artifact.partialReasonCode());
      assertTrue(artifact.truncated());
      assertFalse(artifact.warnings().contains(LOSS));
      assertEquals("Valid words ".repeat(6) + "Valid wo", artifact.result().content());
    }
  }

  @Test void flatFallbackDiscardsFailedAttemptState() throws Exception {
    Path file = write("fallback-short-clean.txt", utf8("Valid words"));
    assertEncoding(file, "UTF-8", TEXT_PARSER);
    try (var extractor = extractor(limited())) {
      forceFlatFallback(extractor);
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.truncated());
      assertFalse(artifact.warnings().contains(LOSS));
      assertEquals("Valid words\n", artifact.result().content());
    }
  }

  private static void forceFlatFallback(PolicyDrivenTikaExtractor extractor) throws Exception {
    var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
    structuredField.setAccessible(true);
    var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
    parserField.setAccessible(true);
    AutoDetectParser failing = mock(AutoDetectParser.class);
    doAnswer(invocation -> {
      var record = spy(new ParseRecord());
      doReturn(new String[] {"org.apache.tika.parser.mail.RFC822Parser"}).when(record).getParsers();
      record.setWriteLimitReached(true);
      invocation.<ParseContext>getArgument(3).set(ParseRecord.class, record);
      throw new org.xml.sax.SAXException("force flat fallback after recording a parser and write limit");
    }).when(failing).parse(any(), any(), any(), any());
    parserField.set(structuredField.get(extractor), failing);
  }

  @Test
  @Timeout(90)
  void sandboxRoutesReuseAndReplacementKeepLossIsolated() throws Exception {
    Path loss = write("loss.txt", utf8("Valid words ", 0xff));
    Path clean = write("clean.txt", utf8("Valid words"));
    Path html = write("loss.html", html(
        "<meta charset=UTF-8><meta name=\"X-TIKA:Parsed-By\" content=other>", false));
    var policy = TikaExtractionPolicy.defaults();
    try (var local = extractor(policy);
        var child = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
            PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
            OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 4, null)) {
      long firstPid = -1;
      for (Path file : List.of(loss, clean, html, clean, loss, clean)) {
        var expected = local.extractArtifact(file);
        var actual = child.extract(file);
        assertEquals(expected.result().content(), actual.result().content());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.warnings(), actual.warnings());
        if (!file.equals(clean)) assertLoss(actual, false);
        else assertEquals(ExtractionStatus.SUCCESS_FULL, actual.status());
        if (firstPid == -1) firstPid = child.firstChildPid();
      }
      assertNotEquals(firstPid, child.firstChildPid());
    }
  }

  @Test
  @Timeout(90)
  void sandboxPreservesCombinedLossAndTruncationForTextAndHtml() throws Exception {
    var policy = limited();
    Path before = write("before.txt", concat(utf8("Valid words ", 0xff), "Valid words ".repeat(1000).getBytes(StandardCharsets.UTF_8)));
    Path after = write("after.txt", utf8("Valid words ".repeat(1000), 0xff));
    Path html = write("limited.html", html("<meta charset=UTF-8>", true));
    try (var local = extractor(policy);
        var child = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
            PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class), policy,
            OcrRoutingConfig.disabled(), Duration.ofSeconds(25), 1, 10, null)) {
      for (Path file : List.of(before, after, html)) {
        assertEncoding(file, "UTF-8", file.equals(html) ? HTML_PARSER : TEXT_PARSER);
        var expected = local.extractArtifact(file);
        var actual = child.extract(file);
        assertEquals(expected.result().content(), actual.result().content());
        assertLoss(actual, true);
      }
    }
  }

  @Test
  void lossyMarkdownIsPartial() throws Exception {
    lossy("loss.md", utf8("# Valid words ", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void lossyJsonIsPartial() throws Exception {
    lossy("loss.json", concat(utf8("{\"words\":\"Valid words ", 0xff), "\"}".getBytes(StandardCharsets.UTF_8)), "UTF-8", TEXT_PARSER);
  }
  @Test
  void lossySourceCodeIsPartial() throws Exception {
    lossy("loss.java", utf8("// Valid words ", 0xff), "UTF-8", "org.apache.tika.parser.code.SourceCodeParser");
  }
  @Test
  void lossyCsvIsPartial() throws Exception {
    lossy("loss.csv", utf8("name,value\nValid,words\n", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void lossyTsvIsPartial() throws Exception {
    lossy("loss.tsv", utf8("name\tvalue\nValid\twords\n", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void replacementOnlyArtifactIsPartial() throws Exception {
    lossy("replacement-only.txt", utf8("", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void oneAlphanumericArtifactIsPartial() throws Exception {
    lossy("one.txt", utf8("a", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void twoAlphanumericArtifactIsPartial() throws Exception {
    lossy("two.txt", utf8("ab", 0xff), "UTF-8", TEXT_PARSER);
  }
  @Test
  void cleanWindows1252IsUnchanged() throws Exception {
    clean("western.txt", "European writing costs €: ‚ „ “ ” ‘ ’ – — … ™ œ Š Ž café déjà vu.".getBytes(Charset.forName("windows-1252")), "windows-1252", TEXT_PARSER);
  }
  @Test
  void cleanShiftJisIsUnchanged() throws Exception {
    clean("japanese.txt", "日本語の文章です。東京の図書館で新しい本を読みます。".getBytes(Charset.forName("Shift_JIS")), "Shift_JIS", TEXT_PARSER);
  }
  @Test
  void cleanGb18030IsUnchanged() throws Exception {
    clean("chinese.txt", "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。".getBytes(Charset.forName("GB18030")), "GB18030", TEXT_PARSER);
  }
  @Test
  void cleanEucKrIsUnchanged() throws Exception {
    clean("korean.txt", "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다.".getBytes(Charset.forName("EUC-KR")), "EUC-KR", TEXT_PARSER);
  }
  @Test
  void cleanKoi8RIsUnchanged() throws Exception {
    clean("russian.txt", "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке.".getBytes(Charset.forName("KOI8-R")), "KOI8-R", TEXT_PARSER);
  }
  @Test
  void undeclaredHtmlIsUnchanged() throws Exception {
    clean("clean.html", "<html><body>European writing costs € — café</body></html>".getBytes(Charset.forName("windows-1252")), "windows-1252", HTML_PARSER);
  }

  @Test
  void intentionalReplacementCharacterIsClean() throws Exception {
    clean("replacement.txt", utf8("Valid words \uFFFD"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void utf8WithBomIsClean() throws Exception {
    clean("utf8.txt", utf8("Valid words café € 😀"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void utf8WithoutBomIsClean() throws Exception {
    clean("utf8.txt", "Valid words café € 😀".getBytes(StandardCharsets.UTF_8), "UTF-8", TEXT_PARSER);
  }
  @ParameterizedTest
  @ValueSource(ints = {16_384, 65_536, 1_048_576})
  void utf8WithoutBomAfterLongAsciiStartIsClean(int prefixLength) throws Exception {
    clean("ascii-first.txt", ("a".repeat(prefixLength) + "café € 😀")
        .getBytes(StandardCharsets.UTF_8), "UTF-8", TEXT_PARSER);
  }

  @ParameterizedTest
  @ValueSource(ints = {0xff, 0x81})
  void malformedUtf8AfterLongAsciiStartKeepsLegacyAudit(int invalid) throws Exception {
    byte[] bytes = concat(("a".repeat(16_384) + "é").getBytes(StandardCharsets.UTF_8),
        new byte[] {(byte) invalid});
    // The invalid snapshot keeps main's windows-1252 fallback: 0xff is decodable,
    // while 0x81 is undefined in that actual charset and must be reported as loss.
    if (invalid == 0x81) lossy("legacy.txt", bytes, "windows-1252", TEXT_PARSER);
    else clean("legacy.txt", bytes, "windows-1252", TEXT_PARSER);
  }

  @Test
  void asciiFirstFlatFallbackRetainsSnapshotFactAndIsClean() throws Exception {
    String text = "a".repeat(65_536) + "café € 😀";
    Path file = write("fallback-ascii-first.txt", text.getBytes(StandardCharsets.UTF_8));
    assertEncoding(file, "UTF-8", TEXT_PARSER);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      forceFlatFallback(extractor);
      var artifact = extractor.extractArtifact(file);
      assertEquals(text, artifact.result().content().strip());
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.warnings().contains(LOSS));
    }
  }

  @Test
  void asciiIsClean() throws Exception {
    clean("ascii.txt", "Valid words with ASCII text".getBytes(StandardCharsets.US_ASCII), "windows-1252", TEXT_PARSER);
  }
  @Test
  void utf16LeBomIsClean() throws Exception {
    clean("le.txt", "\uFEFFValid words café".getBytes(StandardCharsets.UTF_16LE), "UTF-16LE", TEXT_PARSER);
  }
  @Test
  void utf16BeBomIsClean() throws Exception {
    clean("be.txt", "\uFEFFValid words café".getBytes(StandardCharsets.UTF_16BE), "UTF-16BE", TEXT_PARSER);
  }
  @Test
  void utf16LeWithoutBomIsClean() throws Exception {
    clean("le.txt", "Valid words with enough ASCII letters".getBytes(StandardCharsets.UTF_16LE), "UTF-16LE", TEXT_PARSER);
  }
  @Test
  void utf16BeWithoutBomIsClean() throws Exception {
    clean("be.txt", "Valid words with enough ASCII letters".getBytes(StandardCharsets.UTF_16BE), "UTF-16BE", TEXT_PARSER);
  }
  @Test
  void markdownUsesTextParser() throws Exception {
    clean("body.md", utf8("# Valid words café"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void jsonUsesTextParser() throws Exception {
    clean("body.json", utf8("{\"words\":\"Valid words café\"}"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void sourceUsesSourceCodeParser() throws Exception {
    clean("body.java", utf8("// Valid words café"), "UTF-8", "org.apache.tika.parser.code.SourceCodeParser");
  }
  @Test
  void csvUsesTextParser() throws Exception {
    clean("body.csv", utf8("name,value\nValid,words\n"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void tsvUsesTextParser() throws Exception {
    clean("body.tsv", utf8("name\tvalue\nValid\twords\n"), "UTF-8", TEXT_PARSER);
  }
  @Test
  void cleanHtmlIsUnchanged() throws Exception {
    clean("body.html", "<html><head><meta charset=UTF-8></head><body>Valid words café</body></html>".getBytes(StandardCharsets.UTF_8), "UTF-8", HTML_PARSER);
  }
  @Test void cleanTruncationRemainsPartialWithoutLoss() throws Exception {
    Path file = write("long.txt", utf8("Valid words ".repeat(100)));
    try (var extractor = extractor(limited())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      assertTrue(artifact.truncated());
      assertFalse(artifact.warnings().contains(LOSS));
    }
  }
  @Test void blankRemainsEmpty() throws Exception {
    Path file = write("blank.txt", utf8("  \n  "));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      assertEquals(ExtractionStatus.SUCCESS_EMPTY, extractor.extractArtifact(file).status());
    }
  }
  @Test void inputBudgetStillRefusesBeforeParsing() throws Exception {
    Path file = write("large.txt", utf8("Valid words ".repeat(100)));
    var policy = TikaExtractionPolicy.fromWorkerLimits(new io.justsearch.configuration.resolved.ResolvedConfig.Worker(80, 20));
    try (var extractor = extractor(policy)) {
      assertThrows(ContentExtractor.BudgetExceededException.class, () -> extractor.extractArtifact(file));
    }
  }

  @Test void validDecodedControlsDoNotImplyLoss() throws Exception {
    clean("controls.txt", utf8("Valid words\u0000\u0001"), "UTF-8", TEXT_PARSER);
  }
  @Test void misdeclaredMailPartKeepsExistingOutcome() throws Exception {
    byte[] header = ("From: writer@example.invalid\r\nTo: reader@example.invalid\r\nSubject: fixture\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\nValid words ").getBytes(StandardCharsets.US_ASCII);
    unchangedContainer("message.eml", concat(header, new byte[] {(byte) 0xff}));
  }

  @Test void containerWithOuterCharsetAndLossyTextPartIsNotAudited() throws Exception {
    // MboxParser reports windows-1252 for the outer document. Keep an undefined byte
    // there too: auditing the outer bytes merely because a later text parser ran must fail.
    byte[] header = ("From writer@example.invalid Tue Oct 27 12:00:00 2026\n"
        + "From: writer@example.invalid\nTo: reader@example.invalid\nSubject: fixture ")
        .getBytes(StandardCharsets.US_ASCII);
    // Unlike inline text/plain, CSV passes through embedded dispatch and records its parser.
    byte[] body = ("\nMIME-Version: 1.0\nContent-Type: text/csv; charset=UTF-8\n"
        + "Content-Transfer-Encoding: base64\n\n"
        + java.util.Base64.getEncoder().encodeToString(utf8("Valid words ", 0xff)) + "\n")
        .getBytes(StandardCharsets.US_ASCII);
    Path file = write("message.mbox", concat(concat(header, new byte[] {(byte) 0x81}), body));
    assertEncoding(file, "windows-1252", "org.apache.tika.parser.mbox.MboxParser",
        "org.apache.tika.parser.mail.RFC822Parser", TEXT_PARSER);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertEquals("SUCCESS_PARTIAL", artifact.partialReasonCode());
      assertFalse(artifact.warnings().contains(LOSS));
      assertFalse(artifact.truncated());
      assertTrue(artifact.result().content().contains("Valid words \uFFFD"));
    }
  }

  @Test void pdfNamedTextIsAuditedByTextParser() throws Exception {
    Path file = write("routed.txt", asciiLeadingPdfWithLateInvalidByte());
    assertEncoding(file, "windows-1252", TEXT_PARSER);
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertLoss(artifact, false);
      assertEquals(LOSS, artifact.partialReasonCode());
      assertTrue(artifact.result().content().contains("Valid PDF words"));
    }
  }

  @Test void samePdfNamedPdfIsNotAudited() throws Exception {
    Path file = write("routed.pdf", asciiLeadingPdfWithLateInvalidByte());
    assertEncoding(file, null, "org.apache.tika.parser.pdf.PDFParser");
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertEquals("SUCCESS_PARTIAL", artifact.partialReasonCode());
      assertFalse(artifact.warnings().contains(LOSS));
      assertFalse(artifact.truncated());
      assertEquals("Valid PDF words", artifact.result().content());
    }
  }
  @Test void invalidXmlStillFailsThroughXmlParser() throws Exception {
    Path file = write("invalid.xml", concat(utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><document>Valid words ", 0xff), "</document>".getBytes(StandardCharsets.UTF_8)));
    try (var extractor = extractor(TikaExtractionPolicy.defaults())) {
      assertThrows(ContentExtractor.ExtractionException.class, () -> extractor.extractArtifact(file));
    }
  }
  @Test void pdfKeepsExistingOutcome() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/pdf/pdf-text-layer.pdf")) {
      assertNotNull(stream);
      unchangedContainer("digital.pdf", stream.readAllBytes());
    }
  }
  @Test void officeKeepsExistingOutcome() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/office/office-marker.docx")) {
      assertNotNull(stream);
      unchangedContainer("office.docx", stream.readAllBytes());
    }
  }
  private void unchangedContainer(String name, byte[] bytes) throws Exception {
    Path file = write(name, bytes);
    try (var extractor = extractor(TikaExtractionPolicy.defaults()); var structured = new StructuredContentExtractor()) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(structured.extract(file).content(), artifact.result().content());
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.warnings().contains(LOSS));
    }
  }

  private void assertTruncated(byte[] bytes, String extension, boolean markup, String expectedText) throws Exception {
    if (!markup) bytes = concat(bytes, " Valid words".repeat(100).getBytes(StandardCharsets.UTF_8));
    Path file = write("limited." + extension, bytes);
    assertEncoding(file, "UTF-8", markup ? HTML_PARSER : TEXT_PARSER);
    try (var extractor = extractor(limited())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(expectedText, artifact.result().content());
      assertLoss(artifact, true);
    }
  }
  private void lossy(String name, byte[] bytes, String charset, String parser) throws Exception {
    Path file = write(name, bytes);
    assertEncoding(file, charset, parser);
    try (var extractor = extractor(TikaExtractionPolicy.defaults()); var structured = new StructuredContentExtractor()) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(structured.extract(file).content(), artifact.result().content());
      assertLoss(artifact, false);
    }
  }
  private void clean(String name, byte[] bytes, String charset, String parser) throws Exception {
    Path file = write(name, bytes);
    assertEncoding(file, charset, parser);
    try (var extractor = extractor(TikaExtractionPolicy.defaults()); var structured = new StructuredContentExtractor()) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(structured.extract(file).content(), artifact.result().content());
      assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
      assertFalse(artifact.warnings().contains(LOSS));
    }
  }
  private static void assertLoss(ExtractionArtifact artifact, boolean truncated) {
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
    assertEquals(truncated, artifact.truncated());
    assertTrue(artifact.warnings().contains(LOSS), "artifact must carry decoding loss across the sandbox");
  }
  private static Metadata assertEncoding(Path file, String charset, String... parserNames) throws Exception {
    var detector = TextNameMagicConflictDetector.wrapDefault();
    var parser = OwnedTikaParsers.create(detector);
    var tika = new Tika(detector, parser, new org.apache.tika.language.translate.DefaultTranslator());
    try (var factory = new PreparedExtractionInput.Factory(); var input = factory.prepare(file, TikaExtractionPolicy.defaults());
        var stream = input.openParserStream(tika)) {
      Metadata metadata = input.metadata();
      var context = new ParseContext();
      parser.parse(stream, new org.xml.sax.helpers.DefaultHandler(), metadata, context);
      assertEquals(charset, metadata.get(Metadata.CONTENT_ENCODING), "charset actually used");
      String[] actualParsers = context.get(ParseRecord.class).getParsers();
      assertEquals(parserNames[0], Arrays.stream(actualParsers)
          .filter(name -> !name.equals("org.apache.tika.parser.AutoDetectParser")
              && !name.equals("org.apache.tika.parser.DefaultParser")
              && !name.equals("org.apache.tika.parser.CompositeParser"))
          .findFirst().orElseThrow(), "first non-dispatcher must own the outer document");
      for (String parserName : parserNames) {
        assertTrue(Arrays.asList(context.get(ParseRecord.class).getParsers()).contains(parserName),
            () -> "actual parsers: " + Arrays.toString(context.get(ParseRecord.class).getParsers()));
      }
      return metadata;
    }
  }
  private Path write(String name, byte[] bytes) throws Exception { return Files.write(tempDir.resolve(name), bytes); }
  private static PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy policy) { return new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy); }
  private static TikaExtractionPolicy limited() { return TikaExtractionPolicy.fromWorkerLimits(new io.justsearch.configuration.resolved.ResolvedConfig.Worker(80, 10_000_000)); }
  private static byte[] html(String meta, boolean large) {
    return concat(("<html><head>" + meta + "</head><body>" + "<p><b>Valid words</b></p>".repeat(large ? 2000 : 1)).getBytes(StandardCharsets.UTF_8),
        concat(new byte[] {(byte) 0xff}, "</body></html>".getBytes(StandardCharsets.UTF_8)));
  }
  private static byte[] utf8(String text, int... invalid) {
    byte[] prefix = ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8);
    byte[] result = Arrays.copyOf(prefix, prefix.length + invalid.length);
    for (int i = 0; i < invalid.length; i++) result[prefix.length + i] = (byte) invalid[i];
    return result;
  }
  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static byte[] asciiLeadingPdfWithLateInvalidByte() {
    // A valid, uncompressed PDF: ASCII comments exceed the routing/charset probe windows.
    // ISO-8859-1 serializes the one intentional 0x81 byte without changing xref offsets.
    StringBuilder pdf = new StringBuilder("%PDF-1.4\n%" + "Valid words ".repeat(20_000) + "\n");
    String content = "BT /F1 12 Tf 72 700 Td (Valid PDF words) Tj ET\n";
    String[] objects = {
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream"
    };
    int[] offsets = new int[objects.length];
    for (int i = 0; i < objects.length; i++) {
      offsets[i] = pdf.length();
      pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
    }
    pdf.append("% Invalid text byte: \u0081\n");
    int xref = pdf.length();
    pdf.append("xref\n0 6\n0000000000 65535 f \n");
    for (int offset : offsets) {
      pdf.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
    }
    pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
        .append(xref).append("\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
  }
}
