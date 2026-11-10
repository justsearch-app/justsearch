/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ContentLanguageTest {
  static Stream<Arguments> prose() {
    return Stream.of(
        Arguments.of("en", "The public library opens every morning and welcomes readers from the surrounding villages. The collection includes books about history, science and the natural world."),
        Arguments.of("de", "Die öffentliche Bibliothek öffnet jeden Morgen und begrüßt Leser aus den umliegenden Dörfern. Die Sammlung enthält Bücher über Geschichte, Wissenschaft und die natürliche Welt."),
        Arguments.of("fr", "La bibliothèque publique ouvre chaque matin et accueille les lecteurs des villages voisins. La collection comprend des livres sur l'histoire, les sciences et le monde naturel."),
        Arguments.of("es", "La biblioteca pública abre todas las mañanas y recibe a lectores de los pueblos cercanos. La colección incluye libros sobre historia, ciencia y el mundo natural."),
        Arguments.of("it", "La biblioteca pubblica apre ogni mattina e accoglie lettori dai paesi vicini. La collezione comprende libri sulla storia, sulla scienza e sul mondo naturale."),
        Arguments.of("nl", "De openbare bibliotheek opent elke ochtend en verwelkomt lezers uit de omliggende dorpen. De verzameling bevat boeken over geschiedenis, wetenschap en de natuurlijke wereld."),
        Arguments.of("pt", "A biblioteca pública abre todas as manhãs e recebe leitores das aldeias vizinhas. A coleção inclui livros sobre história, ciência e o mundo natural."),
        Arguments.of("cy", "Mae'r llyfrgell gyhoeddus yn agor bob bore ac yn croesawu darllenwyr o'r pentrefi cyfagos. Mae'r casgliad yn cynnwys llyfrau am hanes, gwyddoniaeth a'r byd naturiol."));
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "de"})
  void labelsLatinProseInSeparateJvmStarts(String locale, @TempDir Path directory) throws Exception {
    Path output = directory.resolve("locale-output.txt");
    Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-Duser.language=" + locale, "-Duser.country=" + (locale.equals("en") ? "US" : "DE"),
        "-cp", System.getProperty("java.class.path"), ContentLanguageTest.class.getName())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(45, TimeUnit.SECONDS), "locale child timed out");
      assertEquals(0, child.exitValue(), Files.readString(output));
    } finally {
      if (child.isAlive()) child.destroyForcibly().waitFor();
    }
  }

  public static void main(String[] args) {
    prose().forEach(example -> {
      Object[] values = example.get();
      assertEquals(values[0], LanguageUtils.resolveLanguage((String) values[1]));
    });
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" \n\t", "Hello", "Hallo Welt", "1234567890 1234567890 1234567890 1234567890 1234567890",
      "public static void main(String[] args) { System.out.println(42); }",
      "const result = values.map(value => value * 2);\nexport { result };",
      "אחד שניים שלושה ארבעה חמישה שישה שבעה שמונה תשעה עשרה"})
  void insufficientOrNonProseEvidenceHasNoLabel(String text) {
    assertNull(LanguageUtils.resolveLanguage(text));
  }

  static Stream<Arguments> scripts() {
    return Stream.of(
        Arguments.of("zh", "这是一段中文文本用于测试。"),
        Arguments.of("ru", "Это текст на русском языке."),
        Arguments.of("ja", "これはひらがなとカタカナのテキストです。"),
        Arguments.of("ko", "이것은 한국어 텍스트입니다."),
        Arguments.of("ar", "هذا نص باللغة العربية."),
        Arguments.of("hi", "यह हिंदी भाषा में लिखा गया पाठ है।"),
        Arguments.of("el", "Αυτό είναι ένα κείμενο στην ελληνική γλώσσα."));
  }

  @ParameterizedTest
  @MethodSource("scripts")
  void retainsExistingScriptLabels(String language, String text) {
    assertEquals(language, LanguageUtils.resolveLanguage(text));
  }
}
