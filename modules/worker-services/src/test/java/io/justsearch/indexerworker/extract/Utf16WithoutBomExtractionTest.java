/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Utf16WithoutBomExtractionTest {
  private static final String RUSSIAN =
      "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке.";
  private static final String GREEK =
      "Αυτό είναι ένα ελληνικό κείμενο. Σήμερα διαβάζουμε νέα βιβλία στη δημοτική βιβλιοθήκη.";
  private static final String UKRAINIAN =
      "Це український текст. Сьогодні ми читаємо нові книжки у міській бібліотеці.";
  private static final String HEBREW =
      "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית.";
  private static final String WESTERN = "European writing costs €: ‚ „ “ ” ‘ ’ – — … ™ œ Š Ž café déjà vu.";
  @TempDir Path tempDir;

  @Test
  void bomlessUtf16LeRussianMatchesUtf8() throws Exception {
    assertMatchesUtf8(RUSSIAN, StandardCharsets.UTF_16LE);
  }

  @Test
  void bomlessUtf16BeRussianMatchesUtf8() throws Exception {
    assertMatchesUtf8(RUSSIAN, StandardCharsets.UTF_16BE);
  }

  @Test
  void bomlessUtf16LeGreekMatchesUtf8() throws Exception {
    assertMatchesUtf8(GREEK, StandardCharsets.UTF_16LE);
  }

  @Test
  void bomlessUtf16BeGreekMatchesUtf8() throws Exception {
    assertMatchesUtf8(GREEK, StandardCharsets.UTF_16BE);
  }

  @Test
  void bomlessUtf16LeUkrainianMatchesUtf8() throws Exception {
    assertMatchesUtf8(UKRAINIAN, StandardCharsets.UTF_16LE);
  }

  @Test
  void bomlessUtf16BeUkrainianMatchesUtf8() throws Exception {
    assertMatchesUtf8(UKRAINIAN, StandardCharsets.UTF_16BE);
  }

  @Test
  void bomlessUtf16LeHebrewMatchesUtf8() throws Exception {
    assertMatchesUtf8(HEBREW, StandardCharsets.UTF_16LE);
  }

  @Test
  void bomlessUtf16BeHebrewMatchesUtf8() throws Exception {
    assertMatchesUtf8(HEBREW, StandardCharsets.UTF_16BE);
  }

  @Test
  void windowEndingInsideSurrogatePairMatchesUtf8() throws Exception {
    String prefix = (RUSSIAN + " ").repeat(60).substring(0, 4095);
    String text = prefix + "😀 " + RUSSIAN;
    assertEquals(8192, (prefix + "😀").getBytes(StandardCharsets.UTF_16LE).length - 2);
    assertMatchesUtf8(text, StandardCharsets.UTF_16LE);
  }

  @Test
  void configuredLimitTruncatesCorrectUtf16TextWithPartialStatus() throws Exception {
    int limit = 48;
    String text = (RUSSIAN + " ").repeat(10);
    Path file = write("limited.txt", text.getBytes(StandardCharsets.UTF_16LE));
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        limit, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    try (var extractor = new PolicyDrivenTikaExtractor(
        TestWorkerExecutorRegistrations.ocrFactory(), policy)) {
      var artifact = extractor.extractArtifact(file);
      assertExactText(text.substring(0, limit).stripTrailing(), artifact.result().content());
      assertTrue(artifact.truncated());
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      artifact.validate(policy, "utf16-limit");
    }
  }

  @Test
  void defaultPolicyIdentityIsV3() throws Exception {
    assertEquals("tika-default-v3", TikaExtractionPolicy.defaults().policyId());
  }

  @Test
  void blankPolicyIdentityFallsBackToV3() throws Exception {
    var policy = new TikaExtractionPolicy(" ", 48, 1024, 1024, 128, 128, 4096,
        0, 0, 100.0d, true, Set.of(), Set.of());
    assertEquals("tika-default-v3", policy.policyId());
  }

  @Test
  void configuredPolicyIdentityIsV3() throws Exception {
    assertEquals("tika-config-v3-48-1024", TikaExtractionPolicy.fromWorkerLimits(
        new ResolvedConfig.Worker(48, 1024)).policyId());
  }

  @Test
  void utf8WithoutBomKeepsText() throws Exception {
    String text = "Writing € café 😀 𠀀";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("UTF-8")), text);
  }

  @Test
  void utf8WithBomKeepsText() throws Exception {
    String text = "Writing € café 😀 𠀀";
    assertExtractedText("control.txt", ("\uFEFF" + text).getBytes(Charset.forName("UTF-8")), text);
  }

  @Test
  void utf16LeWithBomKeepsText() throws Exception {
    String text = "Writing € café 😀 𠀀";
    assertExtractedText("control.txt", ("\uFEFF" + text).getBytes(Charset.forName("UTF-16LE")), text);
  }

  @Test
  void utf16BeWithBomKeepsText() throws Exception {
    String text = "Writing € café 😀 𠀀";
    assertExtractedText("control.txt", ("\uFEFF" + text).getBytes(Charset.forName("UTF-16BE")), text);
  }

  @Test
  void asciiKeepsText() throws Exception {
    String text = "Ordinary ASCII writing with a final marker.";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("US-ASCII")), text);
  }

  @Test
  void windows1252KeepsText() throws Exception {
    String text = WESTERN;
    assertExtractedText("control.txt", text.getBytes(Charset.forName("windows-1252")), text);
  }

  @Test
  void shiftJisKeepsText() throws Exception {
    String text = "日本語の文章です。東京の図書館で新しい本を読みます。";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("Shift_JIS")), text);
  }

  @Test
  void gb18030KeepsText() throws Exception {
    String text = "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("GB18030")), text);
  }

  @Test
  void eucKrKeepsText() throws Exception {
    String text = "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다.";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("EUC-KR")), text);
  }

  @Test
  void koi8RKeepsText() throws Exception {
    String text = RUSSIAN;
    assertExtractedText("control.txt", text.getBytes(Charset.forName("KOI8-R")), text);
  }

  @Test
  void interiorByteOrderMarkKeepsText() throws Exception {
    String text = "Writing café with an interior \uFEFF marker.";
    assertExtractedText("control.txt", text.getBytes(Charset.forName("UTF-8")), text);
  }

  @Test
  void htmlWithDeclarationKeepsText() throws Exception {
    String html = "<html><head><meta charset=windows-1252></head><body><p>" + WESTERN + "</p></body></html>";
    assertExtractedText("declared.html", html.getBytes(Charset.forName("windows-1252")), WESTERN);
  }

  @Test
  void htmlWithoutDeclarationKeepsText() throws Exception {
    String html = "<html><body><p>" + WESTERN + "</p></body></html>";
    assertExtractedText("undeclared.html", html.getBytes(Charset.forName("windows-1252")), WESTERN);
  }

  @Test
  void xmlWithDeclarationKeepsText() throws Exception {
    String text = "Příliš žluťoučký kůň úpěl ďábelské ódy.";
    String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-2\"?><document>" + text + "</document>";
    assertExtractedText("declared.xml", xml.getBytes(Charset.forName("ISO-8859-2")), text);
  }

  @Test
  void largeUtf16KeepsFinalMarker() throws Exception {
    String marker = "FINAL_SEARCHABLE_MARKER";
    String text = "Ordinary writing in a library. ".repeat(1800) + marker;
    byte[] bytes = text.getBytes(StandardCharsets.UTF_16LE);
    assertTrue(bytes.length > 100_000);
    assertExtractedText("large.txt", bytes, text);
  }

  @Test
  void oneTrailingNulKeepsExistingReplacement() throws Exception {
    String text = "Ordinary writing with café and a final marker.";
    assertExtractedText("trailing.txt", (text + "\0").getBytes(StandardCharsets.UTF_8), text + "\uFFFD");
  }

  @Test
  void sparseNulsKeepExistingReplacements() throws Exception {
    String text = "First\0middle\0last words with ordinary writing.";
    assertExtractedText("sparse.txt", text.getBytes(StandardCharsets.UTF_8), text.replace('\0', '\uFFFD'));
  }

  @Test
  void nulPaddingAfterUtf8KeepsExistingReplacements() throws Exception {
    String text = "Ordinary writing with café and a final marker.";
    assertExtractedText("padded-utf8.txt", (text + "\0".repeat(16)).getBytes(StandardCharsets.UTF_8),
        text + "\uFFFD".repeat(16));
  }

  @Test
  void nulPaddingAfterWindows1252KeepsExistingReplacements() throws Exception {
    String text = "European writing costs € and café.";
    assertExtractedText("padded-western.txt", (text + "\0".repeat(16)).getBytes(Charset.forName("windows-1252")),
        text + "\uFFFD".repeat(16));
  }

  @Test
  void utf32LeKeepsText() throws Exception {
    String text = "Ordinary writing ends here.";
    assertExtractedText("utf32.txt", text.getBytes(Charset.forName("UTF-32LE")), text);
  }

  @Test
  void nulPairsKeepExistingReplacements() throws Exception {
    String text = "First\0\0middle\0\0last ordinary writing.";
    assertExtractedText("pairs.txt", text.getBytes(StandardCharsets.UTF_8), text.replace('\0', '\uFFFD'));
  }

  @Test
  void nulsOnlyAfterWindowKeepExistingReplacements() throws Exception {
    String text = "Ordinary ASCII writing. ".repeat(400);
    assertTrue(text.getBytes(StandardCharsets.UTF_8).length > 8192);
    assertExtractedText("late-nuls.txt", (text + "\0".repeat(8)).getBytes(StandardCharsets.UTF_8),
        text + "\uFFFD".repeat(8));
  }

  @Test
  void shortCjkWithoutEnoughNulsKeepsExistingText() throws Exception {
    // Today's detector renders these four bytes as Windows-1252; the narrow rule must not guess.
    assertExtractedText("short-cjk.txt", "中文".getBytes(StandardCharsets.UTF_16LE), "-N‡e");
  }

  private void assertMatchesUtf8(String text, Charset encoding) throws Exception {
    Path file = write("source.txt", text.getBytes(encoding));
    // An explicit UTF-8 control avoids exercising the fallback detector on the reference.
    Path utf8 = write("utf8.txt", ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        String expected = extractor.extract(utf8).content().strip();
        assertExactText(expected, extractor.extract(file).content().strip());
      }
    }
  }

  private void assertExtractedText(String name, byte[] bytes, String expected) throws Exception {
    Path file = write(name, bytes);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
        assertExactText(expected, extractor.extract(file).content().strip());
      }
    }
  }

  private Path write(String name, byte[] bytes) throws Exception {
    return Files.write(tempDir.resolve(name), bytes);
  }

  private static void assertExactText(String expected, String actual) {
    assertEquals(-1, Arrays.mismatch(expected.toCharArray(), actual.toCharArray()),
        "first differing character (-1 means exact text)");
  }
}
