/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether a CUDA device is still usable by this process (Spec E §6.2). One per device ordinal, for
 * the process's lifetime: an Engine incarnation.
 *
 * <p>A run that fails with a code of {@link DeviceLostClassifier}'s closed list latches its device as
 * lost. The latch never clears within the process: a lost CUDA context does not come back, so the
 * device's encoders run on CPU until the Engine restarts and probes the GPU again. When it latches,
 * every session handle on the device drops its GPU session (without closing it natively, which may
 * hang on a lost device; the process takes it along) and moves its session generation, and the
 * process's loss listeners are told once.
 *
 * <p>A lost device is not a stuck one. {@link DeviceEncoderGate}'s breaker opens while a GPU run
 * has not returned for {@code stuckAfterMs}; it turns query legs that have a keyword fallback away
 * at the gate, the run may still return, and it closes when the run's seat is released. A lost
 * device is known from an error a run returned; its handles stop selecting the GPU before the gate,
 * so no request waits for or is refused by the gate, semantic search continues on CPU, and the latch
 * does not close.
 */
public final class DeviceHealth {

  private static final Logger log = LoggerFactory.getLogger(DeviceHealth.class);

  private static final Map<Integer, DeviceHealth> DEVICES = new ConcurrentHashMap<>();
  private static final List<LossListener> LISTENERS = new CopyOnWriteArrayList<>();
  // Orders latching against listener registration, so each listener is told of each loss once.
  private static final Object LOCK = new Object();

  /** Told once per lost device, on the thread whose run found the loss. */
  @FunctionalInterface
  public interface LossListener {
    void onDeviceLost(int cudaDevice, int code);
  }

  private final int cudaDevice;
  private volatile int lostCode = -1;
  // The handles on this device; weak, so a closed handle that is no longer referenced goes away.
  private final Set<NativeSessionHandle> handles =
      Collections.newSetFromMap(new WeakHashMap<>());

  private DeviceHealth(int cudaDevice) {
    this.cudaDevice = cudaDevice;
  }

  /** The health of CUDA device {@code cudaDevice} in this process. */
  public static DeviceHealth forDevice(int cudaDevice) {
    return DEVICES.computeIfAbsent(cudaDevice, DeviceHealth::new);
  }

  /**
   * Registers a listener for device loss in this process. It is told at once of every device already
   * lost, and later of each device when it is lost.
   *
   * @return closing it removes the listener
   */
  public static AutoCloseable addLossListener(LossListener listener) {
    Objects.requireNonNull(listener, "listener");
    List<DeviceHealth> alreadyLost = new ArrayList<>();
    synchronized (LOCK) {
      LISTENERS.add(listener);
      for (DeviceHealth device : DEVICES.values()) {
        if (device.lostCode >= 0) alreadyLost.add(device);
      }
    }
    for (DeviceHealth device : alreadyLost) notify(listener, device.cudaDevice, device.lostCode);
    return () -> LISTENERS.remove(listener);
  }

  /** True once a run on this device failed with a listed device-lost code. */
  public boolean isLost() {
    return lostCode >= 0;
  }

  /** The code that latched this device, or empty while it is usable. */
  public OptionalInt lostCode() {
    int code = lostCode;
    return code >= 0 ? OptionalInt.of(code) : OptionalInt.empty();
  }

  /**
   * Latches this device as lost with {@code code}, a code of the closed list. Only the first report
   * latches; it drops the GPU sessions of the device's handles and tells the loss listeners.
   *
   * @return whether this call latched the device
   */
  public boolean reportLost(int code) {
    if (code < 0) throw new IllegalArgumentException("code must not be negative: " + code);
    List<NativeSessionHandle> affected;
    List<LossListener> listeners;
    synchronized (LOCK) {
      if (lostCode >= 0) return false;
      lostCode = code;
      affected = new ArrayList<>(handles);
      listeners = List.copyOf(LISTENERS);
    }
    log.error(
        "CUDA device {} lost (CUDA error {}); its encoders run on CPU until the Engine restarts",
        cudaDevice,
        code);
    for (NativeSessionHandle handle : affected) handle.onDeviceLost(code);
    for (LossListener listener : listeners) notify(listener, cudaDevice, code);
    return true;
  }

  /** Registers a handle built for this device, so a loss drops its GPU session. */
  void register(NativeSessionHandle handle) {
    int code;
    synchronized (LOCK) {
      handles.add(handle);
      code = lostCode;
    }
    if (code >= 0) handle.onDeviceLost(code);
  }

  private static void notify(LossListener listener, int cudaDevice, int code) {
    try {
      listener.onDeviceLost(cudaDevice, code);
    } catch (RuntimeException e) {
      log.warn("device-lost listener failed", e);
    }
  }
}
