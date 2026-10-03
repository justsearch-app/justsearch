package io.justsearch.app.api.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.justsearch.contract.wire.EngineComponentState;
import io.justsearch.core.component.ComponentState;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 564 (proto-parity X-cut) — record↔proto conformance guard for the {@code /api/status}
 * wire surface, completing the X-cut that {@code OperationHistoryWireContractConformanceTest}
 * started for operation-history and that {@code KnowledgeWireContractConformanceTest} applies to
 * the knowledge surface. {@code status} is the largest and hardest surface (≈30 nested {@code
 * *View} / {@code *Group} records, mixed snake/camel JSON keys per ADR-09a SE-4), and until now it
 * was hand-mirrored into {@code contracts/wire/status.proto} with nothing mechanical keeping the
 * tree in sync — the same drift class that let tempdoc 549's {@code SearchTrace} ship on the
 * records but never reach {@code knowledge.proto} (551 §B.3).
 *
 * <p>Unlike the knowledge test (which hand-enumerates each nested type), this guard is a <em>single
 * recursive walker</em>: starting at {@link StatusResponse}, every record-typed component is
 * followed into its corresponding nested proto message automatically (via the proto field's {@code
 * getMessageType()}). A new {@code *View} added anywhere in the reachable tree is covered the moment
 * it is referenced — no enumeration to keep in sync, so the guard cannot itself drift. The walk is
 * self-scoping: only records reachable from {@code StatusResponse} are checked (debug-only records
 * such as {@code WorkerDebugView} / {@code EffectiveConfigEntry} are not part of this surface).
 *
 * <p>For every record component, the effective JSON key (the record component's {@link JsonProperty}
 * override if present — checked on the component, its accessor, and the canonical-constructor
 * parameter — otherwise the component name) must have a corresponding field in the proto descriptor
 * (matched by proto {@code json_name}). Adding a field to a status record without adding it to the
 * proto now fails here. The reverse direction (proto carries a field the record does not) is
 * intentionally permitted — the contract may declare deprecated aliases or forward-compat fields.
 */
@DisplayName("status wire contract: every StatusResponse record field is described by status.proto")
final class StatusWireContractConformanceTest {

  @Test
  @DisplayName("StatusResponse + every nested record ⊆ status.proto (recursive)")
  void everyRecordFieldHasAProtoField() {
    List<String> failures = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    walk(
        StatusResponse.class,
        io.justsearch.contract.wire.StatusResponse.getDescriptor(),
        "StatusResponse",
        failures,
        visited);
    assertTrue(
        failures.isEmpty(),
        () ->
            "Record↔proto drift in the /api/status wire surface — add the missing field(s) to "
                + "contracts/wire/status.proto (the gated wire contract):\n  "
                + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("component states are a bijection and preserve unknown protobuf values")
  void componentStateWireMappingIsExplicitAndForwardCompatible() throws Exception {
    Set<String> coreNames =
        java.util.Arrays.stream(ComponentState.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    Set<String> wireNames =
        java.util.Arrays.stream(EngineComponentState.values())
            .filter(
                state ->
                    state != EngineComponentState.ENGINE_COMPONENT_STATE_UNSPECIFIED
                        && state != EngineComponentState.UNRECOGNIZED)
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    assertEquals(coreNames, wireNames);
    var mapper = new ObjectMapper();
    for (ComponentState state : ComponentState.values()) {
      assertEquals(state.name(), EngineComponentState.valueOf(state.name()).name());
      assertEquals('"' + state.name() + '"', mapper.writeValueAsString(state));
    }
    assertThrows(IllegalArgumentException.class, () -> ComponentState.valueOf("FUTURE_STATE"));
    assertNull(EngineComponentState.forNumber(999));
    var unknown =
        io.justsearch.contract.wire.EngineComponent.newBuilder().setStateValue(999).build();
    assertEquals(999, unknown.getStateValue());
    assertEquals(EngineComponentState.UNRECOGNIZED, unknown.getState());
  }

  @Test
  @DisplayName("readiness engineComponents map uses the typed component message")
  void readinessEngineComponentsMapHasTypedWireValue() {
    FieldDescriptor field =
        io.justsearch.contract.wire.ReadinessEnvelopeView.getDescriptor()
            .findFieldByName("engine_components");
    assertTrue(field.isMapField());
    assertEquals(
        io.justsearch.contract.wire.EngineComponentView.getDescriptor(),
        field.getMessageType().findFieldByName("value").getMessageType());
  }

  @Test
  @DisplayName("schema 2 moves components without reusing the legacy field")
  void statusComponentsPreserveLegacyFieldReservation() {
    Descriptor status = io.justsearch.contract.wire.StatusResponse.getDescriptor();
    assertNull(status.findFieldByNumber(4));
    assertTrue(
        status.toProto().getReservedRangeList().stream()
            .anyMatch(range -> range.getStart() <= 4 && range.getEnd() > 4));
    assertTrue(status.toProto().getReservedNameList().contains("components"));

    FieldDescriptor components = status.findFieldByNumber(35);
    assertEquals("components", components.getJsonName());
    assertEquals(
        io.justsearch.contract.wire.EngineComponents.getDescriptor(), components.getMessageType());
  }

  /**
   * Recursively validate that {@code recordClass} is a structural subset of {@code proto}, following
   * record-typed components into their nested proto messages. Failures are accumulated into {@code
   * failures} (one line each) rather than fast-failing, so a single run reports the whole drift set.
   */
  private static void walk(
      Class<?> recordClass,
      Descriptor proto,
      String path,
      List<String> failures,
      Set<String> visited) {
    if (!recordClass.isRecord()) {
      failures.add(path + ": " + recordClass.getName() + " is not a record");
      return;
    }
    // Cycle / re-visit guard keyed on the (record, proto-message) pair: the same record validated
    // against two different proto messages is two genuine checks, but the same pair twice is not.
    if (!visited.add(recordClass.getName() + "@" + proto.getFullName())) {
      return;
    }
    Map<String, FieldDescriptor> protoByJsonName =
        proto.getFields().stream()
            .collect(Collectors.toMap(FieldDescriptor::getJsonName, Function.identity(), (a, b) -> a));

    RecordComponent[] components = recordClass.getRecordComponents();
    for (int i = 0; i < components.length; i++) {
      RecordComponent rc = components[i];
      if (isJsonIgnored(recordClass, rc, i)) {
        // @JsonIgnore components never serialize to JSON, so they are not wire fields — the proto
        // correctly omits them (e.g. WorkerOperationalView.aiReady / embeddingReady).
        continue;
      }
      String jsonKey = jsonKeyOf(recordClass, rc, i);
      FieldDescriptor pf = protoByJsonName.get(jsonKey);
      if (pf == null) {
        failures.add(
            path
                + "."
                + jsonKey
                + " has no matching json_name in "
                + proto.getFullName()
                + " (present: "
                + sortedJsonNames(proto)
                + ")");
        continue;
      }
      Class<?> nested = nestedRecordType(rc.getGenericType());
      if (nested != null) {
        if (pf.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
          walk(nested, pf.getMessageType(), path + "." + jsonKey, failures, visited);
        } else {
          failures.add(
              path
                  + "."
                  + jsonKey
                  + ": Java type is record "
                  + nested.getSimpleName()
                  + " but proto field "
                  + pf.getFullName()
                  + " is scalar ("
                  + pf.getJavaType()
                  + ")");
        }
      }
    }
  }

  /**
   * The effective JSON key for a record component: the {@link JsonProperty} override (checked on the
   * component itself, then its accessor method, then the canonical-constructor parameter by index —
   * {@code @JsonProperty} is authored on the constructor parameter in these records) if present and
   * non-empty, otherwise the component name (which already carries snake_case for some fields, e.g.
   * {@code Lifecycle.reason_code}).
   */
  private static String jsonKeyOf(Class<?> recordClass, RecordComponent rc, int index) {
    JsonProperty jp = rc.getAnnotation(JsonProperty.class);
    if (jp == null) {
      try {
        jp = recordClass.getMethod(rc.getName()).getAnnotation(JsonProperty.class);
      } catch (NoSuchMethodException ignored) {
        // accessor always exists for a record component; ignore defensively
      }
    }
    if (jp == null) {
      Constructor<?> ctor = canonicalConstructor(recordClass);
      Annotation[] paramAnnotations = ctor.getParameterAnnotations()[index];
      for (Annotation a : paramAnnotations) {
        if (a instanceof JsonProperty p) {
          jp = p;
          break;
        }
      }
    }
    return (jp != null && !jp.value().isEmpty()) ? jp.value() : rc.getName();
  }

  /**
   * Whether the component is {@link JsonIgnore}-annotated (on the component, its accessor, or the
   * canonical-constructor parameter) — such components do not serialize to JSON, so they are not
   * wire fields and the proto rightly omits them.
   */
  private static boolean isJsonIgnored(Class<?> recordClass, RecordComponent rc, int index) {
    if (rc.isAnnotationPresent(JsonIgnore.class)) {
      return true;
    }
    try {
      if (recordClass.getMethod(rc.getName()).isAnnotationPresent(JsonIgnore.class)) {
        return true;
      }
    } catch (NoSuchMethodException ignored) {
      // accessor always exists for a record component; ignore defensively
    }
    for (Annotation a : canonicalConstructor(recordClass).getParameterAnnotations()[index]) {
      if (a instanceof JsonIgnore) {
        return true;
      }
    }
    return false;
  }

  /** The canonical constructor: parameter types equal the record components' types, in order. */
  private static Constructor<?> canonicalConstructor(Class<?> recordClass) {
    Class<?>[] paramTypes =
        java.util.Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
    try {
      return recordClass.getDeclaredConstructor(paramTypes);
    } catch (NoSuchMethodException e) {
      throw new AssertionError("No canonical constructor for record " + recordClass.getName(), e);
    }
  }

  /**
   * If the component's type is a record (directly, or as the element of a {@code List<R>} /
   * {@code java.util.Optional<R>}), return that record class; otherwise {@code null} (scalar, enum,
   * {@code String}, {@code List<String>}, {@code Map<..>} — all validated by name alone, no
   * recursion). Map value-records are not present in the status tree, so they are not unwrapped.
   */
  private static Class<?> nestedRecordType(Type type) {
    if (type instanceof Class<?> c) {
      return c.isRecord() ? c : null;
    }
    if (type instanceof ParameterizedType pt) {
      Class<?> raw = (Class<?>) pt.getRawType();
      if (List.class.isAssignableFrom(raw) || java.util.Optional.class.equals(raw)) {
        Type arg = pt.getActualTypeArguments()[0];
        if (arg instanceof Class<?> ac && ac.isRecord()) {
          return ac;
        }
      }
    }
    return null;
  }

  private static List<String> sortedJsonNames(Descriptor proto) {
    return proto.getFields().stream()
        .map(FieldDescriptor::getJsonName)
        .sorted()
        .collect(Collectors.toList());
  }
}
