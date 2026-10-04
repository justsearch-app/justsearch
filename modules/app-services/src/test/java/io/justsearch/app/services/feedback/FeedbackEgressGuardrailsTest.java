package io.justsearch.app.services.feedback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Tempdoc 778 — ZERO network egress for the implicit-feedback capture path (Hard Invariant #2,
 * loopback-only). The 580 §17 disposition/feature-snapshot capture + persistence + label projection
 * are LOCAL-ONLY by construction: nothing captured ever leaves the machine (§B.3 non-goal: no
 * telemetry upload). This makes that structural, not honor-system — a future commit that gives any
 * feedback class an outbound HTTP client / raw socket fails CI with a precise pointer.
 *
 * <p>The disposition HTTP *endpoint* lives in {@code ui.api} (KnowledgeSearchController, which
 * legitimately reaches the index component through a port) — its no-egress half is covered by
 * {@code UiApiGuardrailsTest}'s {@code Feedback*} rule; this test covers the capture/persistence
 * package where the guarantee must hold absolutely.
 */
@AnalyzeClasses(
    packages = "io.justsearch.app.services.feedback",
    importOptions = ImportOption.DoNotIncludeTests.class)
class FeedbackEgressGuardrailsTest {

  @ArchTest
  static final ArchRule feedbackCapturePathMakesNoNetworkEgress =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services.feedback..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage("java.net.http..")
          .orShould()
          .accessClassesThat()
          .haveFullyQualifiedName("java.net.Socket")
          .orShould()
          .accessClassesThat()
          .haveFullyQualifiedName("java.net.URLConnection")
          .orShould()
          .accessClassesThat()
          .haveFullyQualifiedName("java.net.HttpURLConnection")
          .as(
              "the feedback capture/persistence path must make no network egress"
                  + " (loopback-only; local files only) — tempdoc 778");
}
