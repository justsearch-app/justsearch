/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.StructuredContentExtractor;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A file's language label reflects its main text, not an opening block in another language. */
class ContentLanguageBodySampleTest {
  /** Site navigation and notices in English, longer than the old 4,096-character preview. */
  static final String ENGLISH_MENUS = String.join("\n",
      "Skip to main content",
      "Legislation.gov.uk home page",
      "Browse legislation, new legislation and changes to legislation",
      "Search legislation by title, year and number, or by the subject of the provisions",
      "Changes to legislation: there are currently no known outstanding effects for this provision.",
      "Revised legislation carried on this site may not be fully up to date. Changes and effects are"
          + " recorded by our editorial team in lists which can be found in the changes to legislation area.",
      "Where those effects have yet to be applied to the text of the legislation by the editorial"
          + " team they are also listed alongside the legislation in the affected provisions.",
      "Use this menu to access essential accompanying documents and information for this legislation.",
      "You can choose to see the latest available version or the original version as it was made.",
      "").repeat(6);
  static final Map<String, String> BODY = Map.of(
      "en", "The public library opens every morning and welcomes readers from the surrounding villages. The collection includes books about history, science and the natural world. Visitors may borrow up to ten books for three weeks.\n",
      "de", "Die öffentliche Bibliothek öffnet jeden Morgen und begrüßt Leser aus den umliegenden Dörfern. Die Sammlung enthält Bücher über Geschichte, Wissenschaft und die natürliche Welt. Besucher dürfen bis zu zehn Bücher für drei Wochen ausleihen.\n",
      "fr", "La bibliothèque publique ouvre chaque matin et accueille les lecteurs des villages voisins. La collection comprend des livres sur l'histoire, les sciences et le monde naturel. Les visiteurs peuvent emprunter dix livres pendant trois semaines.\n",
      "nl", "De openbare bibliotheek opent elke ochtend en verwelkomt lezers uit de omliggende dorpen. De verzameling bevat boeken over geschiedenis, wetenschap en de natuurlijke wereld. Bezoekers mogen tien boeken voor drie weken lenen.\n",
      "es", "La biblioteca pública abre todas las mañanas y recibe a lectores de los pueblos cercanos. La colección incluye libros sobre historia, ciencia y el mundo natural. Los visitantes pueden llevarse diez libros durante tres semanas.\n",
      "it", "La biblioteca pubblica apre ogni mattina e accoglie lettori dai paesi vicini. La collezione comprende libri sulla storia, sulla scienza e sul mondo naturale. I visitatori possono prendere in prestito dieci libri per tre settimane.\n",
      "pt", "A biblioteca pública abre todas as manhãs e recebe leitores das aldeias vizinhas. A coleção inclui livros sobre história, ciência e o mundo natural. Os visitantes podem levar dez livros durante três semanas.\n",
      "cy", "Mae'r llyfrgell gyhoeddus yn agor bob bore ac yn croesawu darllenwyr o'r pentrefi cyfagos. Mae'r casgliad yn cynnwys llyfrau am hanes, gwyddoniaeth a'r byd naturiol. Gall ymwelwyr fenthyg deg llyfr am dair wythnos.\n",
      "zh", "公共图书馆每天早上开放，欢迎来自周边村庄的读者。藏书包括历史、科学和自然世界方面的书籍。读者每次最多可以借阅十本书，借期三周。\n",
      "ru", "Публичная библиотека открывается каждое утро и принимает читателей из окрестных деревень. В собрании есть книги по истории, науке и о природе. Посетители могут взять до десяти книг на три недели.\n");
  static final Map<String, String> SCRIPT_BODY = Map.of(
      "zh", BODY.get("zh"),
      "ru", BODY.get("ru"),
      "ja", "公共の図書館は毎朝ひらいて、まわりのむらからくるひとをむかえます。ほんはれきしやかがくについてのものがあります。\n",
      "ko", "공공 도서관은 매일 아침 문을 열고 주변 마을에서 오는 독자를 맞이합니다. 소장품에는 역사와 과학에 관한 책이 있습니다.\n",
      "ar", "تفتح المكتبة العامة كل صباح وترحب بالقراء من القرى المجاورة. تضم المجموعة كتبا عن التاريخ والعلوم والعالم الطبيعي.\n",
      "hi", "सार्वजनिक पुस्तकालय हर सुबह खुलता है और आसपास के गांवों के पाठकों का स्वागत करता है। संग्रह में इतिहास और विज्ञान की पुस्तकें हैं।\n",
      "el", "Η δημόσια βιβλιοθήκη ανοίγει κάθε πρωί και καλωσορίζει αναγνώστες από τα γύρω χωριά. Η συλλογή περιλαμβάνει βιβλία για την ιστορία και τις επιστήμες.\n");

  @TempDir Path directory;

  static String label(String fileName, String mime, String content) {
    return IndexingDocumentOps.deriveParentMetadata(
        Path.of(fileName), new ExtractionResult(content, null, mime), null, null).language();
  }

  static String text(String content) {
    return label("document.txt", "text/plain", content);
  }

  /** At least {@code chars} characters of whole body lines in one language. */
  static String body(String language, int chars) {
    String line = BODY.containsKey(language) ? BODY.get(language) : SCRIPT_BODY.get(language);
    return line.repeat(chars / line.length() + 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"de", "fr", "nl"})
  void r1SavedWebPageWithEnglishMenusGetsTheBodyLanguage(String language) throws Exception {
    StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        + "<title>Legislation</title></head><body><header><nav><ul>");
    for (String line : ENGLISH_MENUS.split("\n")) {
      html.append("<li><a href=\"/menu\">").append(line).append("</a></li>");
    }
    html.append("</ul></nav></header><main>");
    for (String paragraph : body(language, 16_000).split("\n")) {
      html.append("<p>").append(paragraph).append("</p>");
    }
    html.append("</main><footer><p>All content is available under the Open Government Licence "
        + "except where otherwise stated. This site uses cookies to make it work better.</p>"
        + "</footer></body></html>");
    Path page = Files.writeString(directory.resolve("page-" + language + ".html"), html,
        StandardCharsets.UTF_8);
    try (var extractor = new StructuredContentExtractor()) {
      var extraction = extractor.extract(page);
      String content = extraction.content();
      assertTrue(content.indexOf(BODY.get(language).substring(0, 40)) > content.indexOf("Skip to main content") + 4096);
      assertEquals(language, IndexingDocumentOps.deriveParentMetadata(
          page, extraction, null, null).language());
    }
  }

  @ParameterizedTest
  @CsvSource({"en,de,20000", "en,de,200000", "en,de,2000000", "de,en,60000", "fr,nl,60000",
      "cy,en,60000"})
  void r3AFirstQuarterInAnotherLanguageDoesNotDecideTheLabel(
      String opening, String language, int length) {
    String preface = body(opening, length / 4 - BODY.get(opening).length());
    String document = preface + body(language, length - preface.length());
    assertTrue(preface.length() < document.length() / 4);
    assertEquals(language, text(document));
  }

  @Test
  void r3TheBodyLanguageWinsWhenItIsConcentratedTowardTheEnd() {
    String document = body("en", 15_000) + body("de", 60_000) + "Seite 1 von 9\n";
    assertEquals("de", text(document));
  }

  @ParameterizedTest
  @CsvSource({"en,de", "de,en", "fr,nl", "en,cy", "es,pt"})
  void r3AnEvenSplitGetsOneOfTheTwoOrNone(String first, String second) {
    for (int length : new int[] {10_000, 40_000, 400_000}) {
      String language = text(body(first, length / 2) + body(second, length / 2));
      assertTrue(language == null || language.equals(first) || language.equals(second),
          first + "/" + second + " at " + length + ": " + language);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh", "ru"})
  void r6NonLatinBodyWithEnglishMenusGetsTheBodyLanguage(String language) {
    assertEquals(language, text(ENGLISH_MENUS + body(language, 30_000)));
  }

  @ParameterizedTest
  @CsvSource({"start,20000", "middle,20000", "end,20000", "start,200000", "middle,200000",
      "end,200000"})
  void r6EnglishBodyWithAShortNonLatinBlockStaysEnglish(String where, int length) {
    String block = BODY.get("zh").repeat(4);
    String english = body("en", length);
    int at = switch (where) {
      case "start" -> 0;
      case "middle" -> english.indexOf('\n', english.length() / 3) + 1;
      default -> english.length();
    };
    String document = english.substring(0, at) + block + english.substring(at);
    long letters = document.chars().filter(Character::isLetter).count();
    assertTrue(block.chars().filter(Character::isLetter).count() < letters / 10);
    assertEquals("en", text(document));
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "de", "fr", "es", "it", "nl", "pt", "cy"})
  void n1LongMonolingualLatinProseKeepsItsLabel(String language) {
    for (int length : new int[] {3_000, 12_000, 50_000, 500_000}) {
      assertEquals(language, text(body(language, length)), language + " at " + length);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh", "ru", "ja", "ko", "ar", "hi", "el"})
  void n2LongMonolingualNonLatinTextKeepsItsScriptLabel(String language) {
    for (int length : new int[] {3_000, 12_000, 50_000, 500_000}) {
      assertEquals(language, text(body(language, length)), language + " at " + length);
    }
  }

  @Test
  void n1LongFilesWithoutProseEvidenceStayUnlabelled() throws Exception {
    assertNull(text(" \n\t".repeat(10_000)));
    assertNull(text("1234567890 0987654321 1234567890\n".repeat(2_000)));
    assertNull(label("Main.java", "text/x-java-source",
        ENGLISH_MENUS + body("en", 40_000)));
    assertNull(label("rows.csv", "text/csv", body("de", 40_000)));
    for (String probe : Set.of("people.csv", "config.yaml", "config.json", "script.sh", "main.go")) {
      assertNull(text(ContentLanguageTest.probe(probe).repeat(200)), probe);
    }
  }

  @Test
  void markdownWithAnEnglishFrontmatterAndHeadingGetsTheBodyLanguage() {
    String markdown = "---\ntitle: Annual report\ndescription: " + ENGLISH_MENUS.replace("\n", " ")
        + "\n---\n# Annual report of the public library\n\n" + ENGLISH_MENUS.replace("\n", "\n\n")
        + "\n## Bericht\n\n" + body("de", 30_000).replace("\n", "\n\n");
    assertEquals("de", label("report.md", "text/markdown", markdown));
  }

  @Test
  void denseSupplementaryCharactersAtWindowBoundariesKeepTheLabel() {
    String line = BODY.get("de").strip() + " 😀 ";
    for (int length : new int[] {9_000, 30_001, 100_002}) {
      assertEquals("de", text(line.repeat(length / line.length() + 1)), "single line of " + length);
    }
  }
}
