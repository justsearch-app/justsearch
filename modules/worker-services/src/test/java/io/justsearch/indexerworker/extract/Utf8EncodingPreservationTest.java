/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Utf8EncodingPreservationTest {
  @TempDir Path tempDir;

  static Stream<Arguments> scripts() {
    return Stream.of(
        Arguments.of("Arabic", "هذا نص باللغة العربية. اليوم نقرأ كتبا جديدة في المكتبة العامة في المدينة."),
        Arguments.of("Russian", "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке."),
        Arguments.of("Greek", "Αυτό είναι ένα ελληνικό κείμενο. Σήμερα διαβάζουμε νέα βιβλία στη δημοτική βιβλιοθήκη."),
        Arguments.of("Ukrainian", "Це український текст. Сьогодні ми читаємо нові книжки у міській бібліотеці."),
        Arguments.of("Thai", "นี่คือข้อความภาษาไทย วันนี้เราอ่านหนังสือใหม่ในห้องสมุดของเมือง"),
        Arguments.of("Hindi", "यह हिंदी में लिखा गया पाठ है। आज हम नगर के पुस्तकालय में नई किताबें पढ़ते हैं।"),
        Arguments.of("Chinese", "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。"),
        Arguments.of("Japanese", "日本語の文章です。東京の図書館で新しい本を読みます。"),
        Arguments.of("Korean", "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다."));
  }

  @ParameterizedTest(name = "otherUtf8ScriptsKeepCharsetAndText(P1 {0})")
  @MethodSource("scripts")
  void otherUtf8ScriptsKeepCharsetAndText(String name, String prose) throws Exception {
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (int count : List.of(0, 1, 3, 20, 200)) {
        String text = count == 0 ? prose.substring(0, Math.min(20, prose.length())).strip() : prose.repeat(count);
        assertEquals(StandardCharsets.UTF_8, detect(text.getBytes(StandardCharsets.UTF_8)), name);
        for (String extension : List.of("txt", "md", "csv", "json", "py", "html")) {
          String source = switch (extension) {
            case "json" -> "{\"text\":\"" + text + "\"}";
            case "py" -> "# " + text;
            case "html" -> "<html><body><p>" + text + "</p></body></html>";
            default -> text;
          };
          Path file = Files.writeString(tempDir.resolve(name + "." + extension), source);
          for (var extractor : List.<ContentExtractorProvider>of(structured, flat)) {
            assertEquals("html".equals(extension) ? text : source, extractor.extract(file).content().strip(),
                name + " " + extension + " " + count);
          }
        }
      }
    }
  }

  @Test
  void previouslyReadableShortHebrewKeepsText() throws Exception {
    String text = "שלום עולם בעברית היום.";
    assertEquals(40, text.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(StandardCharsets.UTF_8, detect(text.getBytes(StandardCharsets.UTF_8)));
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (String extension : List.of("txt", "md", "csv", "json", "py", "html", "late-meta.html")) {
        String source = switch (extension) {
          case "json" -> "{\"text\":\"" + text + "\"}";
          case "py" -> "# " + text;
          case "html", "late-meta.html" -> "<html><head>"
              + ("late-meta.html".equals(extension)
                  ? "<!--" + "a".repeat(8200) + "--><meta charset=windows-1252>" : "")
              + "</head><body><p>" + text + "</p></body></html>";
          default -> text;
        };
        Path file = Files.writeString(tempDir.resolve("readable." + extension), source);
        String expected = extension.endsWith("html") ? text : source;
        assertEquals(expected, structured.extract(file).content().strip(), extension);
        assertEquals(expected, flat.extract(file).content().strip(), extension);
      }
    }
  }

  static Stream<Arguments> legacyProse() {
    return Stream.of(
        Arguments.of("windows-1252", "Die Schüler lesen Bücher in der städtischen Bibliothek. Grüße aus Köln, schöne Straßen und große Häuser."),
        Arguments.of("windows-1252", "Les élèves français ont déjà préparé le café à côté de l'école. Nous lisons de nouveaux livres à la bibliothèque."),
        Arguments.of("KOI8-R", "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке. Хорошие рассказы помогают людям понимать мир."),
        Arguments.of("windows-1251", "Это русский текст. Сегодня мы читаем новые книги в городской библиотеке. Хорошие рассказы помогают людям понимать мир."),
        Arguments.of("ISO-8859-8", "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית. ילדים ומבוגרים לומדים יחד ומגלים סיפורים חדשים."),
        Arguments.of("windows-1255", "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית. ילדים ומבוגרים לומדים יחד ומגלים סיפורים חדשים."),
        Arguments.of("Shift_JIS", "日本語の文章です。東京の図書館で新しい本を読みます。今日は天気がよいので友達と公園に行きます。"),
        Arguments.of("GB18030", "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。孩子们和朋友一起学习，发现许多有趣的故事。"),
        Arguments.of("EUC-KR", "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다. 아이들은 친구들과 함께 공부하고 재미있는 이야기를 발견합니다."),
        Arguments.of("EUC-JP", "日本語の文章です。東京の図書館で新しい本を読みます。今日は天気がよいので友達と公園に行きます。"),
        Arguments.of("Big5", "這是一篇中文文章，今天我們在臺北的圖書館閱讀新的書籍。孩子們和朋友一起學習，發現許多有趣的故事。"));
  }

  @ParameterizedTest(name = "legacyProseKeepsTodaysCharsetAndExactText(P2 {0}: {1})")
  @MethodSource("legacyProse")
  void legacyProseKeepsTodaysCharsetAndExactText(String encoding, String prose) throws Exception {
    Charset charset = Charset.forName(encoding);
    int repeats = Math.max(2, 2048 / prose.getBytes(charset).length);
    try (var structured = new StructuredContentExtractor(); var flat = new ContentExtractor()) {
      for (int count : List.of(1, repeats)) {
        String text = prose.repeat(count);
        byte[] bytes = text.getBytes(charset);
        Charset previous = new DefaultEncodingDetector().detect(new ByteArrayInputStream(bytes), new Metadata());
        if (StandardCharsets.ISO_8859_1.equals(previous)) previous = Charset.forName("windows-1252");
        assertEquals(previous, detect(bytes));
        Path file = Files.write(tempDir.resolve("legacy.txt"), bytes);
        assertEquals(text, structured.extract(file).content().strip());
        assertEquals(text, flat.extract(file).content().strip());
      }
    }
  }

  private static Charset detect(byte[] bytes) throws Exception {
    return new OwnedTextEncodingDetector(List.of((org.apache.tika.detect.EncodingDetector) Class.forName(
        "org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector").getConstructor().newInstance(), new DefaultEncodingDetector()))
        .detect(new ByteArrayInputStream(bytes), new Metadata());
  }
}
