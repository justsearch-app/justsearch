package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.ort.SessionHandle;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Keeps native encoder lifetime behind the in-process encoder owners (Lane F WP4). */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
final class NativeInferenceContainmentTest {
  /**
   * The backfill operation's BFC-arena classifier and the standalone benchmark are dated
   * exceptions: neither holds native leases or exposes them to application/port callers.
   */
  private static final String[] ENCODER_OWNER_PACKAGES = {
    "io.justsearch.ort",
    "io.justsearch.indexerworker.server",
    "io.justsearch.indexerworker.inference",
    "io.justsearch.indexerworker.embed",
    "io.justsearch.indexerworker.splade",
    "io.justsearch.indexerworker.ner",
    "io.justsearch.indexerworker.bgem3",
    "io.justsearch.reranker",
  };
  private static final Set<String> DATED_CLASS_EXCEPTIONS = Set.of(
      "io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps",
      "io.justsearch.benchmarks.EncoderBatchSweepBench");

  private static final DescribedPredicate<JavaClass> OUTSIDE_ENCODER_OWNERS =
      new DescribedPredicate<>("outside encoder owners and two dated classifier exceptions") {
        @Override public boolean test(JavaClass type) {
          if (DATED_CLASS_EXCEPTIONS.contains(type.getName())) return false;
          for (String owner : ENCODER_OWNER_PACKAGES) {
            if (type.getPackageName().equals(owner)
                || type.getPackageName().startsWith(owner + ".")) return false;
          }
          return true;
        }
      };

  private static final ArchRule NATIVE_OWNER_BOUNDARY = noClasses()
      .that(OUTSIDE_ENCODER_OWNERS)
      .should().dependOnClassesThat()
      .haveNameMatching("io\\.justsearch\\.(ort\\.(NativeSessionHandle|SessionHandle|"
          + "SessionAcquisitionRequest|SessionRetiredException|ModelSessionPolicy|RuntimePolicy)"
          + "(\\$.*)?|indexerworker\\.server\\.EncoderSet(\\$.*)?)")
      .as("native session and EncoderSet ownership stays inside encoder implementation packages");

  @ArchTest
  static final ArchRule nativeOwnersDoNotLeakIntoApplicationOrCore = NATIVE_OWNER_BOUNDARY;

  private record PlantedAppDependency(SessionHandle session) {}

  @Test
  void plantedApplicationDependencyFiresTheRule() {
    assertTrue(new PlantedAppDependency(null).session() == null);
    var planted = new ClassFileImporter().importClasses(PlantedAppDependency.class);
    AssertionError violation = assertThrows(AssertionError.class,
        () -> NATIVE_OWNER_BOUNDARY.check(planted));
    assertTrue(violation.getMessage().contains("SessionHandle"));
  }

  @Test
  void productionImporterSeesTheEncoderOwner() {
    var production = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("io.justsearch");
    assertTrue(production.stream().anyMatch(type -> type.getName().equals(
        "io.justsearch.indexerworker.server.EncoderSet")),
        "the containment rule must import the concrete encoder owner it protects");
  }
}
