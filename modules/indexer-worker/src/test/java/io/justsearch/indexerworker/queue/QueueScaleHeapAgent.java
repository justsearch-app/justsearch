/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Exact reachable-object sizes for the mirror roots, without counting the SQLite connection. */
public final class QueueScaleHeapAgent {
  private static Instrumentation instrumentation;

  private QueueScaleHeapAgent() {}

  public static void premain(String arguments, Instrumentation supplied) {
    instrumentation = supplied;
  }

  static long reachableBytes(Object... roots) throws ReflectiveOperationException {
    if (instrumentation == null) {
      // Gradle loads tests in a child loader; premain ran in the system loader.
      Class<?> agent = Class.forName(QueueScaleHeapAgent.class.getName(), true, ClassLoader.getSystemClassLoader());
      var field = agent.getDeclaredField("instrumentation");
      field.setAccessible(true);
      instrumentation = (Instrumentation) field.get(null);
    }
    if (instrumentation == null) throw new IllegalStateException("Run the queueScaleEvidence task (heap agent required)");
    var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    var pending = new ArrayDeque<Object>();
    for (Object root : roots) if (root != null) pending.add(root);
    long bytes = 0;
    while (!pending.isEmpty()) {
      Object value = pending.removeLast();
      if (!seen.add(value)) continue;
      bytes += instrumentation.getObjectSize(value);
      Class<?> type = value.getClass();
      if (type.isArray()) {
        if (!type.componentType().isPrimitive()) {
          for (Object item : (Object[]) value) if (item != null) pending.add(item);
        }
      } else {
        for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
          for (var field : parent.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            Object child = field.get(value);
            if (child != null) pending.add(child);
          }
        }
      }
    }
    return bytes;
  }
}
