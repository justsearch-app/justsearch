/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.core.status.StatusChangeCounter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Completeness boundary for the canonical status projection. Every record field participates by
 * default, including unknown future producers and freshness flags. Only observation clocks and
 * the JVM heap measurements taken by the projection itself are omitted. No producer is invoked,
 * no owner lock is acquired, and no I/O is performed here. Owner publications remain the immediate
 * signal; this comparison covers a missed publication when its value reaches the status surface.
 */
final class StatusPublicationBackstop {
  private static final Set<String> OBSERVATIONS = Set.of(
      "observedAt", "observed_at", "observedAtMs", "state_since", "since", "sampledAt",
      "assembledAt", "ageMs", "stalenessMs", "maxStalenessMs", "uptimeMs", "workerRpcAtMs", "switchingAgeMs",
      "migrationSwitchingAgeMs", "migrationPausedAtMs", "startedAt", "startedAtMs",
      "finishedAtMs", "lastSeenAt", "agingReleaseAt", "lastFailedAtMs", "nextRetryAtMs",
      "lastBackupAtMs", "lastErrorAtMs", "lastQuickCheckAtMs", "queueDbLastBackupAtMs",
      "queueDbLastErrorAtMs", "queueDbLastQuickCheckAtMs");
  private static final ClassValue<RecordComponent[]> FIELDS = new ClassValue<>() {
    @Override protected RecordComponent[] computeValue(Class<?> type) {
      return type.getRecordComponents();
    }
  };
  private final AtomicReference<Object> previous = new AtomicReference<>();

  StatusResponse publish(StatusResponse response) {
    Object next = content(response);
    for (;;) {
      Object before = previous.get();
      if (next.equals(before)) return response;
      if (previous.compareAndSet(before, next)) {
        StatusChangeCounter.process().changed();
        return response;
      }
    }
  }

  static boolean observation(Class<?> type, String field) {
    for (var component : FIELDS.get(type)) {
      if (component.getName().equals(field)
          && component.getAccessor().isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) return true;
    }
    return OBSERVATIONS.contains(field)
        || (type == StatusResponse.class
            && (field.equals("memoryUsedBytes") || field.equals("memoryTotalBytes")))
        || (type == io.justsearch.app.api.status.operating.OperatingStateView.class
            && field.equals("seq"));
  }

  /** Structural equality with inclusion as the default, never a hand-maintained field projection. */
  static Object content(Object value) {
    if (value == null) return null;
    Class<?> type = value.getClass();
    if (type.isRecord()) {
      var result = new LinkedHashMap<String, Object>();
      for (var field : FIELDS.get(type)) {
        if (observation(type, field.getName())) continue;
        try { result.put(field.getName(), content(field.getAccessor().invoke(value))); }
        catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("Status field has no publication path: " + field, failure);
        }
      }
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof Map<?, ?> values) {
      var result = new LinkedHashMap<Object, Object>();
      values.forEach((key, item) -> result.put(key, content(item)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof Iterable<?> values) {
      var result = new ArrayList<>();
      values.forEach(item -> result.add(content(item)));
      return Collections.unmodifiableList(result);
    }
    if (type.isArray()) {
      var result = new ArrayList<>();
      for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
        result.add(content(java.lang.reflect.Array.get(value, i)));
      }
      return Collections.unmodifiableList(result);
    }
    if (value instanceof String || value instanceof Number || value instanceof Boolean
        || value instanceof Enum<?>) return value;
    throw new IllegalStateException("Status type has no publication path: " + type.getName());
  }
}
