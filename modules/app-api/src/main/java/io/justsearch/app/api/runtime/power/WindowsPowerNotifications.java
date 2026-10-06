/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Windows suspend and resume notifications for a process with no window (Spec F §3.5):
 * {@code powrprof!PowerRegisterSuspendResumeNotification(DEVICE_NOTIFY_CALLBACK, &params, &handle)}
 * with {@code params = DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS{callback, context}}.
 *
 * <ul>
 *   <li><b>The callback only enqueues.</b> Windows calls it on a system thread with the signature
 *       {@code ULONG (PVOID context, ULONG type, PVOID setting)}; it records the type and the time,
 *       returns 0 and catches {@code Throwable}, because an exception escaping an FFM upcall ends
 *       the JVM. Listeners run on this class's own dispatch thread.
 *   <li><b>The stub and the parameters live in {@code Arena.global()}:</b> Windows keeps calling the
 *       stub for as long as the registration lives, which is the life of the process.
 *   <li><b>The context is an id, not a pointer:</b> the callback looks the registration up by it, so
 *       a call with an unknown context is a no-op.
 *   <li>{@code PBT_APMSUSPEND} (4) opens a suspend; {@code PBT_APMRESUMEAUTOMATIC} (0x12) or {@code
 *       PBT_APMRESUMESUSPEND} (7) closes it. Windows sends both resumes when a user is present; the
 *       second finds nothing open and is dropped. Other types are ignored.
 * </ul>
 */
public final class WindowsPowerNotifications implements SystemPowerEvents {
  static final int ERROR_SUCCESS = 0;
  static final int DEVICE_NOTIFY_CALLBACK = 2;
  static final int PBT_APMSUSPEND = 4;
  static final int PBT_APMRESUMESUSPEND = 7;
  static final int PBT_APMRESUMEAUTOMATIC = 0x12;
  /** Closed suspends kept for {@link #suspendedNanosBetween}; older ones are forgotten. */
  private static final int MAX_INTERVALS = 256;

  private static final FunctionDescriptor CALLBACK = FunctionDescriptor.of(ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
  private static final ConcurrentHashMap<Long, WindowsPowerNotifications> BY_CONTEXT =
      new ConcurrentHashMap<>();
  private static final AtomicLong NEXT_CONTEXT = new AtomicLong(1L);
  private static MemorySegment callbackStub;

  private record Raw(int type, long atNanos) {}

  private final long context;
  private final MemorySegment handleCell = Arena.global().allocate(ValueLayout.ADDRESS);
  private final LinkedBlockingQueue<Raw> raw = new LinkedBlockingQueue<>();
  private final CopyOnWriteArrayList<Consumer<PowerEvent>> listeners = new CopyOnWriteArrayList<>();
  private final Deque<long[]> intervals = new ArrayDeque<>();
  private final AtomicLong listenerFailures = new AtomicLong();
  private final Thread dispatcher;
  private volatile int registrationResult = -1;
  private volatile String failure = "not_registered";
  private volatile Integer unregisterResult;
  private long epoch;
  private long openSince = Long.MIN_VALUE;

  private WindowsPowerNotifications(long context) {
    this.context = context;
    this.dispatcher = Thread.ofPlatform().daemon(true).name("power-events").unstarted(this::dispatchLoop);
  }

  /**
   * Registers for notifications. The result says whether Windows accepted ({@link
   * #registrationResult()} is {@code ERROR_SUCCESS}); never throws.
   */
  static WindowsPowerNotifications register() {
    var registration = new WindowsPowerNotifications(NEXT_CONTEXT.getAndIncrement());
    // Published before the call: Windows may deliver a notification before it returns.
    BY_CONTEXT.put(registration.context, registration);
    registration.dispatcher.start();
    try {
      Linker linker = Linker.nativeLinker();
      SymbolLookup powrprof = SymbolLookup.libraryLookup("powrprof", Arena.global());
      MethodHandle registerHandle = linker.downcallHandle(
          powrprof.find("PowerRegisterSuspendResumeNotification").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MemorySegment params = Arena.global().allocate(2L * ValueLayout.ADDRESS.byteSize(),
          ValueLayout.ADDRESS.byteAlignment());
      params.set(ValueLayout.ADDRESS, 0L, callbackStub());
      params.set(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize(),
          MemorySegment.ofAddress(registration.context));
      int result = (int) registerHandle.invokeExact(DEVICE_NOTIFY_CALLBACK, params,
          registration.handleCell);
      registration.registrationResult = result;
      registration.failure = result == ERROR_SUCCESS ? null
          : "PowerRegisterSuspendResumeNotification:" + result;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      registration.failure = unavailable.getClass().getSimpleName();
    }
    if (registration.registrationResult != ERROR_SUCCESS) registration.retire();
    return registration;
  }

  /**
   * The upcall Windows calls; see the class note. Public only because the upcall stub is bound to
   * it by name, which no bytecode reference shows; a call with a context no registration owns does
   * nothing.
   */
  public static int onNotification(MemorySegment context, int type, MemorySegment setting) {
    try {
      WindowsPowerNotifications target = BY_CONTEXT.get(context.address());
      if (target != null) target.raw.offer(new Raw(type, System.nanoTime()));
    } catch (Throwable ignored) {
      // Nothing may escape an upcall: the JVM would end. A dropped notification costs one suspend.
    }
    return 0;
  }

  /** The upcall stub, made once in the global arena. */
  static synchronized MemorySegment callbackStub() throws ReflectiveOperationException {
    if (callbackStub == null) {
      MethodHandle target = MethodHandles.lookup().findStatic(WindowsPowerNotifications.class,
          "onNotification",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class));
      callbackStub = Linker.nativeLinker().upcallStub(target, CALLBACK, Arena.global());
    }
    return callbackStub;
  }

  /** The context Windows passes back to the callback for this registration. */
  public MemorySegment context() {
    return MemorySegment.ofAddress(context);
  }

  /** What {@code PowerRegisterSuspendResumeNotification} returned; -1 when it could not run. */
  int registrationResult() {
    return registrationResult;
  }

  /** Why registration failed; null when it succeeded. */
  String failure() {
    return failure;
  }

  /** Listener exceptions caught by the dispatch thread. */
  public long listenerFailures() {
    return listenerFailures.get();
  }

  /**
   * {@code PowerUnregisterSuspendResumeNotification}; the process never calls it, because the
   * registration lives as long as the process. Returns its result (the first call's, if repeated).
   */
  public synchronized int unregister() throws Throwable {
    if (unregisterResult != null) return unregisterResult;
    if (registrationResult != ERROR_SUCCESS) {
      unregisterResult = registrationResult;
      return unregisterResult;
    }
    MethodHandle unregisterHandle = Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("powrprof", Arena.global())
            .find("PowerUnregisterSuspendResumeNotification").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    unregisterResult = (int) unregisterHandle.invokeExact(handleCell.get(ValueLayout.ADDRESS, 0L));
    retire();
    return unregisterResult;
  }

  private void retire() {
    BY_CONTEXT.remove(context);
    dispatcher.interrupt();
  }

  @Override
  public Subscription subscribe(Consumer<PowerEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @Override
  public synchronized long suspendedNanosBetween(long t0, long t1) {
    if (t1 <= t0) return 0L;
    long total = 0L;
    for (long[] interval : intervals) total += overlap(interval[0], interval[1], t0, t1);
    if (openSince != Long.MIN_VALUE) total += overlap(openSince, t1, t0, t1);
    return total;
  }

  private static long overlap(long from, long to, long t0, long t1) {
    long start = Math.max(from, t0);
    long end = Math.min(to, t1);
    return end > start ? end - start : 0L;
  }

  private void dispatchLoop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        PowerEvent event = transition(raw.take());
        if (event == null) continue;
        for (Consumer<PowerEvent> listener : listeners) {
          try {
            listener.accept(event);
          } catch (RuntimeException | Error failed) {
            if (failed instanceof VirtualMachineError fatal) throw fatal;
            listenerFailures.incrementAndGet();
          }
        }
      }
    } catch (InterruptedException retired) {
      Thread.currentThread().interrupt();
    }
  }

  private synchronized PowerEvent transition(Raw notification) {
    if (notification.type() == PBT_APMSUSPEND) {
      if (openSince != Long.MIN_VALUE) return null;
      epoch++;
      openSince = notification.atNanos();
      return new PowerEvent(PowerEvent.Kind.SUSPEND, notification.atNanos(), epoch);
    }
    if (notification.type() == PBT_APMRESUMEAUTOMATIC || notification.type() == PBT_APMRESUMESUSPEND) {
      if (openSince == Long.MIN_VALUE) return null;
      intervals.addLast(new long[] {openSince, notification.atNanos()});
      while (intervals.size() > MAX_INTERVALS) intervals.removeFirst();
      openSince = Long.MIN_VALUE;
      return new PowerEvent(PowerEvent.Kind.RESUME, notification.atNanos(), epoch);
    }
    return null;
  }

  @Override
  public String toString() {
    return "WindowsPowerNotifications[" + (failure == null ? "registered" : failure) + "]";
  }
}
