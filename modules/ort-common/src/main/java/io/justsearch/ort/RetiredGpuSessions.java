/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * GPU sessions that a release retired to their run and that are still open, per device and role
 * (Spec E revision 4, invariant 5). The memory plan holds one session per role on the GPU: while a
 * role's retired session is open, its memory is still allocated, so no handle of that role on that
 * device creates a new GPU session. The handles of the role are told when the last one closes, so
 * that readiness and prewarm look again.
 *
 * <p>Process-wide, like {@link DeviceEncoderGates}: the handle that retired a session and the handle
 * a recomposition builds for the same role see the same record.
 */
final class RetiredGpuSessions {

  private record Key(int cudaDevice, String role) {}

  private static final Object LOCK = new Object();
  private static final Map<Key, Integer> OPEN = new HashMap<>();
  // Weak, so a closed handle that is no longer referenced goes away (as in DeviceHealth).
  private static final Map<Key, Set<NativeSessionHandle>> HANDLES = new HashMap<>();

  private RetiredGpuSessions() {}

  /** {@code handle} creates GPU sessions for {@code role} on {@code cudaDevice}. */
  static void watch(NativeSessionHandle handle, int cudaDevice, String role) {
    synchronized (LOCK) {
      HANDLES.computeIfAbsent(new Key(cudaDevice, role),
          key -> Collections.newSetFromMap(new WeakHashMap<>())).add(handle);
    }
  }

  /** A GPU session of {@code role} was retired to its run and is still open. */
  static void opened(int cudaDevice, String role) {
    synchronized (LOCK) {
      OPEN.merge(new Key(cudaDevice, role), 1, Integer::sum);
    }
  }

  /**
   * A retired GPU session of {@code role} was closed, or let go of with its device. When it was the
   * last one, the role's handles are told.
   */
  static void closed(int cudaDevice, String role) {
    List<NativeSessionHandle> notify;
    synchronized (LOCK) {
      Key key = new Key(cudaDevice, role);
      Integer open = OPEN.get(key);
      if (open == null) throw new IllegalStateException("no retired GPU session open for " + key);
      if (open > 1) {
        OPEN.put(key, open - 1);
        return;
      }
      OPEN.remove(key);
      Set<NativeSessionHandle> handles = HANDLES.get(key);
      notify = handles == null ? List.of() : new ArrayList<>(handles);
    }
    for (NativeSessionHandle handle : notify) handle.retiredGpuSessionsClosed();
  }

  /** Whether a retired GPU session of {@code role} is still open on {@code cudaDevice}. */
  static boolean isOpen(int cudaDevice, String role) {
    synchronized (LOCK) {
      return OPEN.containsKey(new Key(cudaDevice, role));
    }
  }
}
