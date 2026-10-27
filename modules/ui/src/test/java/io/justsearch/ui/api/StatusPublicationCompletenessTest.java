/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.status.StatusResponse;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatusPublicationCompletenessTest extends StatusIsolationFixtureSupport {
  public record FutureProducer(boolean available, String newStatusField, boolean stale, long ageMs) {}

  private static Class<?> backstop() {
    try { return Class.forName("io.justsearch.ui.api.StatusPublicationBackstop"); }
    catch (ClassNotFoundException missing) {
      fail("Canonical status has no completeness publication path for unregistered fields/producers");
      return null;
    }
  }

  @Test void everyStatusBearingFieldAndFutureProducerHasAPublicationPath() throws Exception {
    var type = backstop();
    supported(StatusResponse.class, new HashSet<>());
    var normalize = type.getDeclaredMethod("content", Object.class);
    assertNotEquals(normalize.invoke(null, new FutureProducer(false, "before", false, 1)),
        normalize.invoke(null, new FutureProducer(true, "after", true, 1)),
        "a producer unknown to the owner-publication inventory must still signal");
    assertEquals(normalize.invoke(null, new FutureProducer(true, "after", true, 1)),
        normalize.invoke(null, new FutureProducer(true, "after", true, 99)),
        "pure age observations must not signal");
    try (var f = new Fixture(true)) {
      covered(f.handler.buildStatusSnapshot(), normalize, type.getDeclaredMethod("observation", Class.class, String.class));
    }
  }

  /**
   * b82: the inclusion check above derives what it expects from the production exclusion
   * predicate, so a state field misclassified as a measurement or clock would still pass it. These
   * user-visible states are listed here, independently of the classification table: each one must
   * advance the counter when it changes.
   */
  @Test void knownUserVisibleStatesAlwaysSignal() throws Exception {
    var type = backstop();
    var observation = type.getDeclaredMethod("observation", Class.class, String.class);
    var witnesses = new java.util.LinkedHashMap<Class<?>, List<String>>();
    witnesses.put(io.justsearch.app.api.status.AtRestProtectionView.class, List.of("diskEncryption", "source", "confidence"));
    witnesses.put(io.justsearch.app.api.status.GpuStatusView.class, List.of("available", "source"));
    witnesses.put(io.justsearch.app.api.status.ConversationProtectionView.class, List.of("state"));
    witnesses.put(io.justsearch.app.api.status.StatusMeta.class, List.of("workerRpcStale"));
    witnesses.put(io.justsearch.app.api.status.CoreIndexView.class, List.of("indexHealthy", "indexState"));
    witnesses.put(io.justsearch.app.api.status.InferenceRuntimeView.class, List.of("engineState", "chatEnabledSpec"));
    witnesses.put(io.justsearch.app.api.status.ReadinessComponentView.class, List.of("state", "reasonCode", "stale"));
    witnesses.put(io.justsearch.app.api.lifecycle.LifecycleSnapshotV2.Component.class, List.of("state", "reason_code"));
    witnesses.put(io.justsearch.app.api.status.operating.OperatingChatView.class, List.of("state", "reasonCode"));
    witnesses.put(io.justsearch.app.api.status.operating.OperatingIndexingView.class,
        List.of("state", "reasons", "reasonCode", "affects"));
    witnesses.put(io.justsearch.app.api.status.operating.OperatingGpuView.class, List.of("stale"));
    witnesses.put(io.justsearch.app.api.status.operating.OperatingFreshnessView.class, List.of("staleSources"));
    var silent = new java.util.ArrayList<String>();
    for (var entry : witnesses.entrySet()) {
      var declared = new HashSet<String>();
      for (var component : entry.getKey().getRecordComponents()) declared.add(component.getName());
      for (String field : entry.getValue()) {
        assertTrue(declared.contains(field), entry.getKey().getSimpleName() + " declares " + field);
        if ((Boolean) observation.invoke(null, entry.getKey(), field)) {
          silent.add(entry.getKey().getSimpleName() + "." + field);
        }
      }
    }
    assertEquals(List.of(), silent, "user-visible states that would change without a publication");
  }

  private static void supported(Type type, Set<Type> visited) {
    if (!visited.add(type)) return;
    if (type instanceof ParameterizedType parameterized) {
      assertTrue(parameterized.getRawType() == List.class
          || parameterized.getRawType() == Map.class, "unsupported status container: " + type);
      for (var argument : parameterized.getActualTypeArguments()) supported(argument, visited);
    } else if (type instanceof Class<?> concrete) {
      if (concrete.isArray()) supported(concrete.getComponentType(), visited);
      else if (concrete.isRecord()) {
        for (var field : concrete.getRecordComponents()) supported(field.getGenericType(), visited);
      } else assertTrue(concrete.isPrimitive() || concrete.isEnum() || concrete == String.class
          || Number.class.isAssignableFrom(concrete) || concrete == Boolean.class,
          "status-bearing type has no structural publication path: " + concrete);
    } else fail("uncovered status type: " + type);
  }

  private static void covered(Object value, java.lang.reflect.Method normalize,
      java.lang.reflect.Method observation) throws Exception {
    if (value == null) return;
    if (value.getClass().isRecord()) {
      var content = (Map<?, ?>) normalize.invoke(null, value);
      var expected = new HashSet<String>();
      for (var field : value.getClass().getRecordComponents()) {
        if ((Boolean) observation.invoke(null, value.getClass(), field.getName())) continue;
        expected.add(field.getName());
        covered(field.getAccessor().invoke(value), normalize, observation);
      }
      assertEquals(expected, content.keySet(), "unpublished status fields in " + value.getClass());
    } else if (value instanceof Map<?, ?> map) {
      for (var item : map.values()) covered(item, normalize, observation);
    } else if (value instanceof Iterable<?> list) {
      for (var item : list) covered(item, normalize, observation);
    }
  }

  @Test void producerWithoutOwnerPublicationStillSignalsCanonicalChange() throws Exception {
    backstop();
    try (var f = new Fixture(true)) {
      var state = new AtomicReference<>("locked");
      f.handler.setConversationProtectionSupplier(state::get);
      f.handler.sampleAndBuildStatusSnapshot();
      long before = io.justsearch.core.status.StatusChangeCounter.process().current();
      state.set("unlocked"); // deliberately bypass ALL owner publications
      assertEquals("unlocked", get(f, "?fresh=true").path("conversationProtection").path("state").asText());
      assertTrue(io.justsearch.core.status.StatusChangeCounter.process().current() > before,
          "the status surface must publish even when its producer forgets");
    }
  }
}
