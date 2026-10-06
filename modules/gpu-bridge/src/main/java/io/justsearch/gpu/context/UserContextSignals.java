/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.context;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemAccess;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Windows {@link UserContextReader} (Spec E section 4.4, {@code UserContextSignals}), over FFM:
 *
 * <ul>
 *   <li>shell32 {@code SHQueryUserNotificationState}: exclusive full screen, busy, presentation;
 *   <li>powrprof {@code PowerRegisterForEffectivePowerModeNotifications} ({@code
 *       EFFECTIVE_POWER_MODE_V2}): Game Mode. The callback only stores the mode in a volatile field;
 *       Windows calls it once at registration with the current mode and again on each change;
 *   <li>user32 {@code GetForegroundWindow}, {@code GetWindowRect}, {@code MonitorFromWindow}, {@code
 *       GetMonitorInfoW}, {@code GetWindowThreadProcessId}, {@code GetClassNameW}: the foreground
 *       window's process, whether it covers its monitor, and whether it is the desktop or taskbar.
 * </ul>
 *
 * <p>Each part fails on its own and is then unknown in the sample; {@link #sampleNow()} never
 * throws. Off Windows every sample is {@link UserContextSample#unknown()}.
 */
public final class UserContextSignals implements UserContextReader {
  private static final Logger log = LoggerFactory.getLogger(UserContextSignals.class);

  private static final Linker LINKER = Linker.nativeLinker();
  private static final int EFFECTIVE_POWER_MODE_V2 = 2;
  private static final int EFFECTIVE_POWER_MODE_GAME_MODE = 5;
  private static final int MONITOR_DEFAULTTONULL = 0;
  private static final long RECT_BYTES = 16;
  private static final long MONITORINFO_BYTES = 40;
  private static final int CLASS_NAME_CHARS = 64;
  private static final Set<String> SHELL_CLASSES = Set.of("Progman", "WorkerW", "Shell_TrayWnd");
  /** No mode reported yet. */
  private static final int NO_MODE = -1;

  private final Arena libraries;
  private final MethodHandle queryNotificationState;
  private final MethodHandle getForegroundWindow;
  private final MethodHandle getWindowThreadProcessId;
  private final MethodHandle getWindowRect;
  private final MethodHandle monitorFromWindow;
  private final MethodHandle getMonitorInfo;
  private final MethodHandle getClassName;
  private final MethodHandle unregisterPowerMode;
  private MemorySegment powerRegistration; // Guarded by this.
  private volatile int effectivePowerMode = NO_MODE;
  private boolean closed; // Guarded by this.

  private UserContextSignals(boolean supported) {
    if (!supported) {
      libraries = null;
      queryNotificationState = null;
      getForegroundWindow = null;
      getWindowThreadProcessId = null;
      getWindowRect = null;
      monitorFromWindow = null;
      getMonitorInfo = null;
      getClassName = null;
      unregisterPowerMode = null;
      closed = true;
      return;
    }
    libraries = Arena.ofShared();
    SymbolLookup shell32;
    SymbolLookup user32;
    try {
      shell32 = systemLibrary("shell32.dll", libraries);
      user32 = systemLibrary("user32.dll", libraries);
    } catch (RuntimeException e) {
      libraries.close();
      throw e;
    }
    queryNotificationState =
        downcall(shell32, "SHQueryUserNotificationState", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    getForegroundWindow = downcall(user32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
    getWindowThreadProcessId =
        downcall(
            user32, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    getWindowRect =
        downcall(user32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    monitorFromWindow =
        downcall(user32, "MonitorFromWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    getMonitorInfo =
        downcall(user32, "GetMonitorInfoW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    getClassName =
        downcall(
            user32, "GetClassNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    MethodHandle unregister = null;
    try {
      SymbolLookup powrprof = systemLibrary("powrprof.dll", libraries);
      unregister =
          downcall(
              powrprof,
              "PowerUnregisterFromEffectivePowerModeNotifications",
              FunctionDescriptor.of(JAVA_INT, ADDRESS));
      powerRegistration = registerPowerMode(powrprof);
    } catch (RuntimeException e) {
      log.info("Windows Game Mode is not observable; game detection uses the other signals", e);
    }
    unregisterPowerMode = unregister;
  }

  /** Opens the reader; off Windows it reads nothing. */
  public static UserContextSignals open() {
    return new UserContextSignals(PlatformPaths.isWindows());
  }

  @Override
  public synchronized UserContextSample sampleNow() {
    if (closed) return UserContextSample.unknown();
    int mode = effectivePowerMode;
    Optional<Boolean> gameMode =
        powerRegistration == null || mode == NO_MODE
            ? Optional.empty()
            : Optional.of(mode == EFFECTIVE_POWER_MODE_GAME_MODE);
    try (Arena arena = Arena.ofConfined()) {
      NotificationState state = notificationState(arena);
      MemorySegment window = foregroundWindow();
      if (window == null) {
        return new UserContextSample(state, gameMode, OptionalLong.empty(), false, false);
      }
      return new UserContextSample(
          state,
          gameMode,
          ownerPid(arena, window),
          coversMonitor(arena, window),
          isShell(arena, window));
    }
  }

  private NotificationState notificationState(Arena arena) {
    try {
      MemorySegment out = arena.allocate(JAVA_INT);
      int hr = (int) queryNotificationState.invokeExact(out);
      return hr == 0 ? NotificationState.fromQuns(out.get(JAVA_INT, 0)) : NotificationState.UNKNOWN;
    } catch (Throwable t) {
      rethrowFatal(t);
      log.debug("SHQueryUserNotificationState failed", t);
      return NotificationState.UNKNOWN;
    }
  }

  private MemorySegment foregroundWindow() {
    try {
      MemorySegment window = (MemorySegment) getForegroundWindow.invokeExact();
      return window.address() == 0 ? null : window;
    } catch (Throwable t) {
      rethrowFatal(t);
      log.debug("GetForegroundWindow failed", t);
      return null;
    }
  }

  private OptionalLong ownerPid(Arena arena, MemorySegment window) {
    try {
      MemorySegment pid = arena.allocate(JAVA_INT);
      int thread = (int) getWindowThreadProcessId.invokeExact(window, pid);
      int value = pid.get(JAVA_INT, 0);
      return thread == 0 || value == 0
          ? OptionalLong.empty()
          : OptionalLong.of(Integer.toUnsignedLong(value));
    } catch (Throwable t) {
      rethrowFatal(t);
      log.debug("GetWindowThreadProcessId failed", t);
      return OptionalLong.empty();
    }
  }

  private boolean coversMonitor(Arena arena, MemorySegment window) {
    try {
      MemorySegment rect = arena.allocate(RECT_BYTES, 4);
      if ((int) getWindowRect.invokeExact(window, rect) == 0) return false;
      MemorySegment monitor =
          (MemorySegment) monitorFromWindow.invokeExact(window, MONITOR_DEFAULTTONULL);
      if (monitor.address() == 0) return false;
      MemorySegment info = arena.allocate(MONITORINFO_BYTES, 4);
      info.set(JAVA_INT, 0, (int) MONITORINFO_BYTES);
      if ((int) getMonitorInfo.invokeExact(monitor, info) == 0) return false;
      // MONITORINFO.rcMonitor starts after cbSize.
      return covers(rect, info.asSlice(4, RECT_BYTES));
    } catch (Throwable t) {
      rethrowFatal(t);
      log.debug("Foreground window geometry failed", t);
      return false;
    }
  }

  /** A window rectangle covers a monitor rectangle when it reaches every edge. */
  static boolean covers(MemorySegment window, MemorySegment monitor) {
    return window.get(JAVA_INT, 0) <= monitor.get(JAVA_INT, 0)
        && window.get(JAVA_INT, 4) <= monitor.get(JAVA_INT, 4)
        && window.get(JAVA_INT, 8) >= monitor.get(JAVA_INT, 8)
        && window.get(JAVA_INT, 12) >= monitor.get(JAVA_INT, 12);
  }

  private boolean isShell(Arena arena, MemorySegment window) {
    try {
      MemorySegment buffer = arena.allocate(JAVA_CHAR, CLASS_NAME_CHARS);
      int length = (int) getClassName.invokeExact(window, buffer, CLASS_NAME_CHARS);
      if (length <= 0) return false;
      char[] chars = new char[length];
      for (int i = 0; i < length; i++) chars[i] = buffer.getAtIndex(JAVA_CHAR, i);
      return SHELL_CLASSES.contains(new String(chars));
    } catch (Throwable t) {
      rethrowFatal(t);
      log.debug("GetClassNameW failed", t);
      return false;
    }
  }

  private MemorySegment registerPowerMode(SymbolLookup powrprof) {
    MethodHandle register =
        downcall(
            powrprof,
            "PowerRegisterForEffectivePowerModeNotifications",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    MemorySegment callback;
    try {
      // The power callback stores the mode and nothing else: it must never throw into native code.
      IntConsumer store = mode -> effectivePowerMode = mode;
      MethodHandle target =
          MethodHandles.dropArguments(
              MethodHandles.publicLookup()
                  .findVirtual(
                      IntConsumer.class, "accept", MethodType.methodType(void.class, int.class))
                  .bindTo(store),
              1,
              MemorySegment.class);
      // The stub lives as long as the process: Windows may still be inside a callback while the
      // registration is withdrawn, and a freed stub would crash the JVM. One stub per reader.
      callback =
          LINKER.upcallStub(
              target, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), Arena.global());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("power mode callback", e);
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment handleOut = arena.allocate(ADDRESS);
      int hr =
          (int)
              register.invokeExact(
                  EFFECTIVE_POWER_MODE_V2, callback, MemorySegment.NULL, handleOut);
      if (hr != 0) {
        throw new IllegalStateException(
            "PowerRegisterForEffectivePowerModeNotifications status=0x" + Integer.toHexString(hr));
      }
      return handleOut.get(ADDRESS, 0);
    } catch (Throwable t) {
      rethrowFatal(t);
      if (t instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("power mode registration", t);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (powerRegistration != null && unregisterPowerMode != null) {
      try {
        int unusedStatus = (int) unregisterPowerMode.invokeExact(powerRegistration);
      } catch (Throwable t) {
        rethrowFatal(t);
        log.debug("PowerUnregisterFromEffectivePowerModeNotifications failed", t);
      }
    }
    powerRegistration = null;
    if (libraries != null && libraries.scope().isAlive()) libraries.close();
  }

  private static SymbolLookup systemLibrary(String fileName, Arena arena) {
    String root = SystemAccess.envVar("SystemRoot");
    Path system32 = Path.of(root == null ? "C:\\Windows" : root, "System32");
    return SymbolLookup.libraryLookup(system32.resolve(fileName), arena);
  }

  private static MethodHandle downcall(
      SymbolLookup library, String symbol, FunctionDescriptor descriptor) {
    MemorySegment function =
        library
            .find(symbol)
            .orElseThrow(() -> new IllegalStateException("symbol missing: " + symbol));
    return LINKER.downcallHandle(function, descriptor);
  }

  private static void rethrowFatal(Throwable t) {
    if (t instanceof VirtualMachineError error) throw error;
  }
}
