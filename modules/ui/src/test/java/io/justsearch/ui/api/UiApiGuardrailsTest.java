package io.justsearch.ui.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.ui.api", importOptions = ImportOption.DoNotIncludeTests.class)
class UiApiGuardrailsTest {
  // `uiApiMustNotReadEnvOrSystemProperties` was retired in tempdoc 883 decision 5. It was the only
  // one of the six per-module copies that also covered clearProperty; the single repo-wide
  // replacement, io.justsearch.deadcode.SystemAccessFunnelTest (modules/dead-code-audit), covers
  // clearProperty everywhere, plus Boolean.getBoolean / Integer.getInteger, which none of the six
  // covered anywhere.

  // Exception classes in ipc.* (e.g. CircuitBreakerOpenException) are legitimate to catch in
  // error handlers. Tempdoc 400 §22 Issue A (LR6-a refactor) moved the
  // @BuildContract / @AdvisoryContract annotations out of
  // io.justsearch.ipc.contracts into a new dep-free core-contracts module at
  // io.justsearch.contracts; they are no longer in the ipc.. tree, so the
  // previous exemption is no longer required.
  @ArchTest
  static final ArchRule uiApiMustNotSpreadProtoDtosBeyondKnownControllers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.ui.api..")
          .should()
          .dependOnClassesThat(
              Predicates.resideInAnyPackage("io.justsearch.ipc..")
                  .and(DescribedPredicate.not(Predicates.simpleNameEndingWith("Exception"))))
          .as(
              "ui.api must not depend on ipc proto message types"
                  + " (use app-api contracts instead; ipc exception classes are permitted)");

  // Tempdoc 778 — zero network egress for the feedback-capture surface. Loopback-only (Hard
  // Invariant #2): the implicit-feedback flag surface never uploads anything. The {@code Feedback*}
  // controller must hold NO outbound network client — no HttpClient (java.net.http), no raw Socket /
  // URLConnection. (The disposition endpoint itself is KnowledgeSearchController, which legitimately
  // reaches the index component through a port, so the rule is scoped to the Feedback* surface; the
  // capture/persistence package's absolute no-egress guarantee is enforced in app-services'
  // FeedbackEgressGuardrailsTest.)
  @ArchTest
  static final ArchRule feedbackSurfaceMustNotMakeNetworkEgress =
      noClasses()
          .that()
          .haveSimpleNameStartingWith("Feedback")
          .should()
          .dependOnClassesThat(
              Predicates.resideInAnyPackage("java.net.http..")
                  .or(
                      DescribedPredicate.describe(
                          "an outbound socket/URL-connection type",
                          jc ->
                              jc.getFullName().equals("java.net.Socket")
                                  || jc.getFullName().equals("java.net.URLConnection")
                                  || jc.getFullName().equals("java.net.HttpURLConnection"))))
          .as(
              "the feedback-capture surface must make no network egress"
                  + " (loopback-only) — tempdoc 778");
}
