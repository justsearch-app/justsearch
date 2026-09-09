/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Pins the parser boundary retained by ADR-0048 and ADR-0049. */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class ExtractionParserConfinementTest {

  private static final String EXTRACT_PACKAGE = "io.justsearch.indexerworker.extract";
  private static final String VDU_PDF_RENDERER =
      "io.justsearch.app.services.vdu.PdfImageRenderer";
  private static final Set<String> AOT_TIKA_CLASSES =
      Set.of(
          "org.apache.tika.Tika",
          "org.apache.tika.parser.AutoDetectParser",
          "org.apache.tika.metadata.Metadata");

  private static final DescribedPredicate<JavaClass> OUTSIDE_PARSER_OWNERS =
      new DescribedPredicate<>("outside the extraction owner and named VDU PDF renderer") {
        @Override
        public boolean test(JavaClass type) {
          return !type.getPackageName().equals(EXTRACT_PACKAGE)
              && !type.getPackageName().startsWith(EXTRACT_PACKAGE + ".")
              && !type.getName().equals(VDU_PDF_RENDERER);
        }
      };

  @ArchTest
  static final ArchRule parserLibrariesStayInsideTheExtractionBoundary =
      noClasses()
          .that(OUTSIDE_PARSER_OWNERS)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.apache.tika..", "org.apache.pdfbox..", "org.apache.poi..")
          .because(
              "untrusted parsing stays in io.justsearch.indexerworker.extract; "
                  + "PdfImageRenderer is the dated 2026-09-09 C1 Q3 exception because VDU "
                  + "still renders bounded PDF pages in the Engine until lane F stage 17.5");

  @Test
  void aotTrainingPinsExactlyThreeNonInitializingTikaLoads() throws IOException {
    String source =
        Files.readString(
            repoRoot().resolve("modules/ui/src/main/java/io/justsearch/ui/AotTraining.java"));
    Pattern tikaLiteral = Pattern.compile("\\\"(org\\.apache\\.tika[^\\\"]+)\\\"");
    List<String> actual = new ArrayList<>();
    var matcher = tikaLiteral.matcher(source);
    while (matcher.find()) {
      actual.add(matcher.group(1));
    }

    assertEquals(3, actual.size(), "AOT training may name exactly three Tika classes");
    assertEquals(AOT_TIKA_CLASSES, Set.copyOf(actual));
    assertTrue(
        source.contains("Class.forName(className, false, AotTraining.class.getClassLoader())"),
        "AOT cache training must load classes without running parser static initialization");
  }

  @Test
  void routingTableIsTheOnlySecondLevelProcessFamilySet() throws IOException {
    Pattern setLiteral = Pattern.compile("Set\\.of\\((.*?)\\)", Pattern.DOTALL);
    Set<String> families = Set.of("pdf", "office", "archive", "image", "binary");
    List<Path> owners = new ArrayList<>();
    Path modules = repoRoot().resolve("modules");
    try (Stream<Path> files = Files.walk(modules)) {
      for (Path source :
          files.filter(p -> p.toString().endsWith(".java"))
              .filter(p -> p.toString().contains("src" + java.io.File.separator + "main"))
              .toList()) {
        String text = Files.readString(source);
        var matcher = setLiteral.matcher(text);
        while (matcher.find()) {
          String literal = matcher.group(1);
          if (families.stream().allMatch(kind -> literal.contains("\"" + kind + "\""))) {
            owners.add(source);
          }
        }
      }
    }

    assertEquals(
        List.of(
            modules.resolve(
                "worker-services/src/main/java/io/justsearch/indexerworker/extract/"
                    + "RoutingExtractionSandbox.java")),
        owners,
        "RoutingExtractionSandbox.PROCESS_KINDS must remain the only process-family authority");
  }

  @Test
  void deferredServiceConstructionDoesNotAllocateAnExtractorOwner() throws IOException {
    String source =
        Files.readString(
            repoRoot()
                .resolve(
                    "modules/worker-services/src/main/java/io/justsearch/indexerworker/server/"
                        + "DefaultWorkerAppServices.java"));
    int ingestBranch = source.indexOf("if (ingestRunning != null)");
    int extractorConstruction =
        source.indexOf("buildContentExtractor(", ingestBranch);
    int deferredBranch = source.indexOf("this.indexingLoop = null", ingestBranch);

    assertTrue(ingestBranch >= 0 && extractorConstruction > ingestBranch);
    assertTrue(
        extractorConstruction < deferredBranch,
        "the extractor owner must only be created by the ingest-capable branch");
  }

  private static Path repoRoot() {
    for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
      if (Files.isRegularFile(path.resolve("settings.gradle.kts"))) {
        return path;
      }
    }
    throw new IllegalStateException("Repository root not found");
  }
}
