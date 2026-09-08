/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.indexerworker.services.WorkerServiceException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parity {@code KnowledgeClientException}'s javadoc promises, and until now only promised.
 *
 * <p>{@code EngineKnowledgeClient.translate} converts the index half's failure vocabulary into the
 * port's with {@code KnowledgeClientException.Status.valueOf(w.status().name())} — a string
 * round-trip between two enums that are deliberately COPIES of each other rather than one shared
 * type, because sharing would put {@code worker-services} on {@code ui}'s compile classpath and
 * ArchUnit rule 6b forbids that.
 *
 * <p>Copying is the right call and it has a specific cost: {@code valueOf} throws
 * {@link IllegalArgumentException} on an unknown name. So the day someone adds a constant to
 * {@code WorkerServiceException.Status} and not to the port's copy, the translation stops being a
 * translation and starts being a crash — inside a catch block, on the failure path, which is the
 * worst place to discover it and the least likely to be exercised. Nothing else in the build
 * notices: both enums compile, and the existing tests only use constants that already exist in
 * both.
 *
 * <p>{@code app-engine} is the only module that can see both types, which is why the pin lives
 * here. That is also why it did not exist: the class promising it is in {@code app-api}, and
 * {@code app-api} cannot import the worker's enum to check itself.
 */
@DisplayName("KnowledgeClientException.Status <-> WorkerServiceException.Status parity")
final class KnowledgeClientExceptionStatusParityTest {

  private static Set<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Test
  @DisplayName("the two enums declare exactly the same constant names")
  void constantSetsAreIdentical() {
    Set<String> worker = names(WorkerServiceException.Status.values());
    Set<String> port = names(KnowledgeClientException.Status.values());

    assertEquals(
        worker,
        port,
        "the port's Status is a hand-copy of the worker's. A name present on one side and not the "
            + "other makes EngineKnowledgeClient.translate throw IllegalArgumentException from "
            + "inside a catch block. Add the constant to BOTH, and give it an HTTP mapping in "
            + "ApiErrorHandler.mapClientStatusToHttp while you are there.");
  }

  @Test
  @DisplayName("every worker status survives the valueOf round-trip the translator performs")
  void everyWorkerStatusTranslates() {
    // The assertion above is the general property; this one is the actual call site's operation,
    // executed for every constant. If translate() ever stops using valueOf, this still holds.
    for (WorkerServiceException.Status s : WorkerServiceException.Status.values()) {
      assertDoesNotThrow(
          () -> KnowledgeClientException.Status.valueOf(s.name()),
          "EngineKnowledgeClient.translate cannot carry " + s.name() + " across the port boundary");
    }
  }

  // ==================== The two "is the worker unreachable?" classifiers ====================

  /**
   * Two independent places decide, from a {@link KnowledgeClientException}, whether the index half
   * is unreachable — and they decide different things with the answer:
   *
   * <ul>
   *   <li>{@code GplJobCoordinator.isTransientWorkerUnavailable} aborts the GPL pass so the run is
   *       retried later. A false negative writes a zero-feature triple into the training set.
   *   <li>{@code AgentToolErrors.isWorkerUnreachable} maps to {@code SERVICE_UNAVAILABLE} rather
   *       than {@code INTERNAL_ERROR}. A false negative tells the model "internal error, do not
   *       retry" for the one condition where retrying is correct.
   * </ul>
   *
   * <p>Both were retargeted at lane F item A14 / the lane F review from gRPC-shaped predicates that
   * had stopped matching anything, and both were rewritten to the same rule against the same type —
   * separately, in different modules, with no shared code and nothing comparing them. That is two
   * hand-copies of one rule, which is the {@code KnowledgeClientException}-vs-{@code
   * WorkerServiceException} shape this class already exists for, one level up. This module is again
   * the only place both are visible: {@code app-services} reaches {@code app-agent} on its {@code
   * api} surface, so an {@code implementation} edge to {@code app-services} puts both on this test's
   * classpath, and neither {@code app-services} nor {@code app-agent} can see the other's classifier
   * from where it lives.
   *
   * <p>Reflection, because both methods are deliberately non-public (one package-private, one
   * private) and widening them to be testable would enlarge a production surface to serve a test.
   * The lookup failing is itself asserted, so a rename cannot turn this into a check of nothing.
   */
  private static Method classifier(String className, String methodName) {
    try {
      Method m = Class.forName(className).getDeclaredMethod(methodName, Throwable.class);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "the classifier this test compares is gone or renamed: "
              + className
              + "#"
              + methodName
              + ". If it genuinely moved, retarget this test — do not delete it. A vanished "
              + "classifier is the drift it exists to catch, not a reason to stop looking.",
          e);
    }
  }

  private static boolean classify(Method m, Throwable t) {
    try {
      return (Boolean) m.invoke(null, t);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("invoking " + m.getName() + " threw", e);
    }
  }

  private static Method gplClassifier() {
    return classifier(
        "io.justsearch.app.services.gpl.GplJobCoordinator", "isTransientWorkerUnavailable");
  }

  private static Method agentClassifier() {
    return classifier("io.justsearch.agent.tools.AgentToolErrors", "isWorkerUnreachable");
  }

  @Test
  @DisplayName("both unreachable-classifiers agree on EVERY status, and on which ones are true")
  void bothUnreachableClassifiersAgreeOnEveryStatus() {
    Method gpl = gplClassifier();
    Method agent = agentClassifier();
    Set<KnowledgeClientException.Status> gplTrue = new LinkedHashSet<>();
    Set<KnowledgeClientException.Status> agentTrue = new LinkedHashSet<>();

    for (KnowledgeClientException.Status s : KnowledgeClientException.Status.values()) {
      KnowledgeClientException e = new KnowledgeClientException(s, "probe");
      boolean g = classify(gpl, e);
      boolean a = classify(agent, e);
      assertEquals(
          g,
          a,
          "the two hand-copies of the unreachable rule disagree about "
              + s
              + ": GplJobCoordinator.isTransientWorkerUnavailable="
              + g
              + ", AgentToolErrors.isWorkerUnreachable="
              + a
              + ". One of them is wrong; decide which and fix that one, do not relax this "
              + "assertion. If the difference is deliberate, pin it here with the reason.");
      if (g) {
        gplTrue.add(s);
      }
      if (a) {
        agentTrue.add(s);
      }
    }

    // "They agree" is satisfied perfectly by two classifiers that both answer false to everything
    // — which is EXACTLY the state item A14 found (a gRPC-typed predicate no live exception could
    // match). So the agreed-true set is pinned by name, not just by equality.
    assertEquals(
        Set.of(
            KnowledgeClientException.Status.UNAVAILABLE,
            KnowledgeClientException.Status.DEADLINE_EXCEEDED),
        gplTrue,
        "the transient-unreachable set is UNAVAILABLE + DEADLINE_EXCEEDED (the 1:1 successors of "
            + "the gRPC codes both classifiers used to match). An empty set here means a "
            + "classifier stopped matching anything, which fails nothing else in the build.");
    assertEquals(gplTrue, agentTrue, "…and the second classifier's true-set is the same one");
  }

  @Test
  @DisplayName("the ONE deliberate difference, pinned rather than papered over")
  void theAgentClassifierAlsoNameMatchesOpenSetTransportFailures() {
    // Not a divergence on the status axis: for every KnowledgeClientException the two agree (above).
    // AgentToolErrors carries ONE extra arm, by design and with its reason stated in its javadoc —
    // it also name-matches *UnavailableException / *ConnectException, which are open sets any
    // library may contribute to and therefore cannot be matched by a type it chose to depend on.
    // GplJobCoordinator has no such arm because its input is narrow: the one call it guards is
    // KnowledgeClient.search, whose failures arrive already translated.
    Throwable openSet = new DocumentService.UnavailableException("index unreachable");

    assertTrue(
        classify(agentClassifier(), openSet),
        "AgentToolErrors must keep classifying a *UnavailableException as unreachable");
    assertFalse(
        classify(gplClassifier(), openSet),
        "GplJobCoordinator deliberately does NOT carry the name-match arm — if this flips, the "
            + "asymmetry above is no longer the documented one and the javadocs need re-reading");
  }

  @Test
  @DisplayName("the vocabulary is not empty, so the checks above cannot pass over nothing")
  void theVocabularyIsPopulated() {
    // Both enums going empty (a refactor that emptied one and "fixed" the other to match) would
    // satisfy set equality perfectly. Nine is the count both carried when this pin was written.
    assertTrue(
        KnowledgeClientException.Status.values().length >= 9,
        "the port's status vocabulary shrank below the nine constants it was written with — if a "
            + "status was genuinely retired, lower this floor in the same change and say which");
  }
}
