/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.indexerworker.services.WorkerServiceException;
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
