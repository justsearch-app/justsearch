/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec F §3.5 (S11-U1): the Engine registers for suspend and resume notifications, and the upcall
 * Windows calls only enqueues and never throws. The registration is undone at the end, so the test
 * JVM keeps none.
 *
 * <p>The class is reached by name so this test compiles against a build without it, where the
 * failure is an assertion.
 */
@Timeout(60)
final class WindowsPowerNotificationsTest {
  private static final int ERROR_SUCCESS = 0;
  private static final int PBT_APMSUSPEND = 4;
  private static final int PBT_APMRESUMEAUTOMATIC = 0x12;
  private static final int PBT_APMRESUMESUSPEND = 7;
  private static final FunctionDescriptor CALLBACK = FunctionDescriptor.of(ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

  @Test @Tag("windows") @EnabledOnOs(OS.WINDOWS)
  void registersWithWindowsAndUnregisters() throws Throwable {
    Object registration = register();
    try {
      assertEquals(ERROR_SUCCESS, call(registration, "registrationResult"),
          "PowerRegisterSuspendResumeNotification: " + call(registration, "failure"));
      assertNull(call(registration, "failure"));
    } finally {
      assertEquals(ERROR_SUCCESS, call(registration, "unregister"),
          "PowerUnregisterSuspendResumeNotification");
    }
  }

  @Test @Tag("windows") @EnabledOnOs(OS.WINDOWS)
  void theUpcallStubEnqueuesTheSuspendAndNeverThrows() throws Throwable {
    Object registration = register();
    try {
      BlockingQueue<Object> delivered = new LinkedBlockingQueue<>();
      Consumer<Object> throwing = event -> {
        throw new IllegalStateException("a listener that fails");
      };
      subscribe(registration, throwing);
      subscribe(registration, delivered::add);
      MethodHandle stub = Linker.nativeLinker().downcallHandle(
          (MemorySegment) callStatic("callbackStub"), CALLBACK);
      MemorySegment context = (MemorySegment) call(registration, "context");

      // What Windows does on sleep: the stub, on a native frame, with this registration's context.
      long before = System.nanoTime();
      assertEquals(0, (int) stub.invokeExact(context, PBT_APMSUSPEND, MemorySegment.NULL));
      Object suspend = delivered.poll(5, TimeUnit.SECONDS);
      assertNotNull(suspend, "the upcall enqueued nothing");
      assertEquals("SUSPEND", String.valueOf(component(suspend, "kind")));
      assertEquals(1L, component(suspend, "epoch"));
      assertTrue((long) component(suspend, "atNanos") >= before);

      // A second suspend before a resume, an unknown type, an unknown context: dropped, not thrown.
      assertEquals(0, (int) stub.invokeExact(context, PBT_APMSUSPEND, MemorySegment.NULL));
      assertEquals(0, (int) stub.invokeExact(context, 0x7fff_0000, MemorySegment.NULL));
      assertEquals(0, (int) stub.invokeExact(MemorySegment.ofAddress(Long.MAX_VALUE),
          PBT_APMSUSPEND, MemorySegment.NULL));

      assertEquals(0, (int) stub.invokeExact(context, PBT_APMRESUMEAUTOMATIC, MemorySegment.NULL));
      assertEquals(0, (int) stub.invokeExact(context, PBT_APMRESUMESUSPEND, MemorySegment.NULL));
      Object resume = delivered.poll(5, TimeUnit.SECONDS);
      assertNotNull(resume);
      assertEquals("RESUME", String.valueOf(component(resume, "kind")));
      assertEquals(1L, component(resume, "epoch"), "the resume closes the suspend's epoch");
      assertNull(delivered.poll(300, TimeUnit.MILLISECONDS),
          "the duplicate suspend, the unknown type and the second resume deliver nothing");
      assertEquals(2L, call(registration, "listenerFailures"),
          "the failing listener was called for both events and kept its subscription");

      long suspendedAt = (long) component(suspend, "atNanos");
      long resumedAt = (long) component(resume, "atNanos");
      Method suspended = registration.getClass().getMethod("suspendedNanosBetween", long.class, long.class);
      assertEquals(resumedAt - suspendedAt, suspended.invoke(registration, suspendedAt - 1_000L, resumedAt + 1_000L));
      assertEquals(0L, suspended.invoke(registration, resumedAt + 1L, resumedAt + 1_000L));
    } finally {
      call(registration, "unregister");
    }
  }

  @Test
  void beforeBootNothingIsInstalledAndNoTimeReadsAsSuspended() throws Throwable {
    Class<?> events = assertDoesNotThrow(
        () -> Class.forName("io.justsearch.app.api.runtime.power.SystemPowerEvents"),
        "the Engine has no suspend/resume producer");
    Object before = events.getMethod("current").invoke(null);
    assertEquals("NoPowerEvents", before.getClass().getSimpleName(), "nothing installed yet");
    assertEquals(0L, events.getMethod("suspendedNanosBetween", long.class, long.class)
        .invoke(before, 0L, Long.MAX_VALUE));
  }

  private static Object register() throws Throwable {
    Class<?> type = assertDoesNotThrow(
        () -> Class.forName("io.justsearch.app.api.runtime.power.WindowsPowerNotifications"),
        "the Engine has no suspend/resume producer");
    Method register = type.getDeclaredMethod("register");
    register.setAccessible(true);
    return register.invoke(null);
  }

  private static void subscribe(Object registration, Consumer<Object> listener) throws Throwable {
    registration.getClass().getMethod("subscribe", Consumer.class).invoke(registration, listener);
  }

  private static Object callStatic(String name) throws Throwable {
    Method method = Class.forName("io.justsearch.app.api.runtime.power.WindowsPowerNotifications")
        .getDeclaredMethod(name);
    method.setAccessible(true);
    return unwrap(() -> method.invoke(null));
  }

  private static Object call(Object target, String name) throws Throwable {
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    return unwrap(() -> method.invoke(target));
  }

  private static Object component(Object record, String name) throws Throwable {
    return record.getClass().getMethod(name).invoke(record);
  }

  private interface Reflective {
    Object call() throws ReflectiveOperationException;
  }

  private static Object unwrap(Reflective call) throws Throwable {
    try {
      return call.call();
    } catch (InvocationTargetException failed) {
      throw failed.getCause();
    }
  }
}
