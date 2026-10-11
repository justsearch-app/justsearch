/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.status.StatusResponse;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatusPublicationCompletenessTest extends VerifierStatusIsolationTest {
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

  private static void supported(Type type, Set<Type> visited) {
    if (!visited.add(type)) return;
    if (type instanceof ParameterizedType parameterized) {
      assertTrue(parameterized.getRawType() == java.util.List.class
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
