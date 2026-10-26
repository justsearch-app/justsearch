/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact work owned by the three status HTTP fixtures; never schedules an extra sample. */
final class StatusFixtureWork {
  final StatusLifecycleHandler handler;
  final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
  final Queue<Thread> threads = new ConcurrentLinkedQueue<>();
  final Queue<CountDownLatch> gates = new ConcurrentLinkedQueue<>();
  private static final List<String> RAW = List.of("worker", "disk", "inference", "power", "resume",
      "gpu", "gpu-memory", "at-rest", "conversation", "models", "agent", "telemetry",
      "device-plan", "bulk-gate", "indexing-hold", "vdu", "vdu-processing", "vision");
  private final Map<String, Queue<CompletableFuture<Void>>> handles = new ConcurrentHashMap<>();
  private final Queue<CompletableFuture<Void>> samplers = new ConcurrentLinkedQueue<>();
  private final Set<Thread> dispatches = ConcurrentHashMap.newKeySet();
  private final Set<Thread> sourceThreads = ConcurrentHashMap.newKeySet();
  private final Semaphore dispatchStarted = new Semaphore(0);
  private final AtomicInteger admittedFresh = new AtomicInteger();
  private final CountDownLatch bootSampler = new CountDownLatch(1);
  private final CountDownLatch gpuFirstSample = new CountDownLatch(1);
  private volatile boolean gpuFirstRequested;
  private volatile Thread gpuFirstThread;
  volatile boolean quiescent;
  private boolean stopped;
  private volatile boolean bootRequested;

  @SuppressWarnings("unchecked")
  StatusFixtureWork(StatusLifecycleHandler original) {
    handler = spy(original);
    try {
      var field = StatusLifecycleHandler.class.getDeclaredField("observations");
      field.setAccessible(true);
      var realLanes = (ConcurrentHashMap<String, StatusObservation<?>>) field.get(handler);
      var lanes = (ConcurrentHashMap<String, StatusObservation<?>>) mock(ConcurrentHashMap.class,
          org.mockito.AdditionalAnswers.delegatesTo(realLanes));
      doAnswer(call -> {
        String name = call.getArgument(0);
        java.util.function.Function<String, StatusObservation<?>> factory = call.getArgument(1);
        return realLanes.computeIfAbsent(name, key -> captureLane(key, factory.apply(key)));
      }).when(lanes).computeIfAbsent(anyString(), any());
      // Keep the real map empty until its real caller creates a lane. In particular, boot's
      // observations.isEmpty()/requestSample branch must retain the original default fallback.
      field.set(handler, lanes);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    doAnswer(call -> {
      var done = new CompletableFuture<Void>();
      synchronized (this) {
        if (stopped) {
          var failure = new AssertionError("sampler entered after fixture ingress stopped");
          errors.add(failure);
          throw failure;
        }
        quiescent = false;
        if (Thread.currentThread().getName().equals("status-observation")) threads.add(Thread.currentThread());
        samplers.add(done);
        bootSampler.countDown();
      }
      try {
        return call.callRealMethod();
      } catch (Throwable failure) {
        errors.add(failure);
        throw failure;
      } finally { done.complete(null); }
    }).when(handler).sampleAndBuildStatusSnapshot();
    doAnswer(call -> {
      if (observations().isEmpty()) bootRequested = true;
      return call.callRealMethod();
    }).when(handler).buildStatusSnapshot();
    doAnswer(call -> {
      if (call.getArgument(0) != null) { gpuFirstRequested = true; quiescent = false; }
      return call.callRealMethod();
    }).when(handler).setGpuMemoryReader(any());
  }

  @SuppressWarnings("unchecked")
  private Map<String, StatusObservation<?>> observations() throws ReflectiveOperationException {
    var field = StatusLifecycleHandler.class.getDeclaredField("observations");
    field.setAccessible(true);
    return (Map<String, StatusObservation<?>>) field.get(handler);
  }

  private StatusObservation<?> captureLane(String name, StatusObservation<?> real) {
    // The real factory supplied this instance with its original surfacePublished callback.
    var lane = spy(real);
    var returned = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
    handles.put(name, returned);
    doAnswer(call -> {
      Thread caller = Thread.currentThread();
      if (caller.getName().equals("status-fresh-dispatch") && dispatches.add(caller)) {
        caller.setUncaughtExceptionHandler((ignored, failure) -> errors.add(failure));
        dispatchStarted.release();
      }
      try {
        @SuppressWarnings("unchecked")
        var future = (CompletableFuture<Void>) call.callRealMethod();
        returned.add(future); // No dependent is attached to an observation future.
        return future;
      } catch (Throwable failure) {
        errors.add(failure);
        throw failure;
      }
    }).when(lane).refreshProbe(anyString(), any(), any(), anyLong(), nullable(Runnable.class), anyBoolean());
    return lane;
  }

  void handleStatus(Context ctx) throws Exception {
    synchronized (this) {
      if (stopped) { ctx.status(503); return; }
      if ("true".equalsIgnoreCase(ctx.queryParam("fresh"))) {
        quiescent = false;
        admittedFresh.incrementAndGet();
      }
      // Cached boot still sees an empty map and uses the real requestSample fallback.
      handler.handleStatus(ctx);
    }
  }

  Thread startSampler() {
    var thread = Thread.ofPlatform().daemon().unstarted(handler::sampleAndBuildStatusSnapshot);
    thread.setUncaughtExceptionHandler((ignored, failure) -> errors.add(failure));
    threads.add(thread);
    thread.start();
    return thread;
  }

  synchronized void stopIngress() { stopped = true; }

  void sourceEntered() {
    Thread source = Thread.currentThread();
    if (source.getName().startsWith("status-")) {
      sourceThreads.add(source);
      source.setUncaughtExceptionHandler((ignored, failure) -> errors.add(failure));
    }
  }

  void gpuSampleEntered() {
    sourceEntered();
    if (Thread.currentThread().getName().equals("gpu-memory-first-sample")) {
      gpuFirstThread = Thread.currentThread();
      gpuFirstThread.setUncaughtExceptionHandler((ignored, failure) -> errors.add(failure));
      threads.add(Thread.currentThread());
      gpuFirstSample.countDown();
    }
  }

  void awaitGpuFirstSample() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    assertTrue(gpuFirstSample.await(remaining(deadline), TimeUnit.NANOSECONDS),
        "the actual owned GPU first sample must enter");
    join(gpuFirstThread, deadline);
    assertNoErrors();
  }

  void drain(Set<String> held) throws Exception {
    quiescent = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    if (bootRequested) assertTrue(bootSampler.await(remaining(deadline), TimeUnit.NANOSECONDS),
        "the original boot sampler must enter before closure");
    if (gpuFirstRequested) assertTrue(gpuFirstSample.await(remaining(deadline), TimeUnit.NANOSECONDS),
        "the owned GPU first sample must enter before closure");
    while (dispatches.size() < admittedFresh.get())
      assertTrue(dispatchStarted.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS),
          "every admitted fresh route must enter its actual dispatch");
    assertEquals(admittedFresh.get(), dispatches.size(), "every admitted fresh route must dispatch");
    for (Thread thread : List.copyOf(threads)) join(thread, deadline);
    for (Thread dispatch : Set.copyOf(dispatches)) join(dispatch, deadline);
    for (var sampler : List.copyOf(samplers)) sampler.get(remaining(deadline), TimeUnit.NANOSECONDS);
    // Ingress has ended. Each completed stage has finished submitting the following stage.
    for (String name : RAW) await(name, held, deadline);
    await("index-publication", held, deadline);
    await("registry", held, deadline);
    for (String name : handles.keySet())
      if (name.equals("operating") || name.startsWith("readiness-")) await(name, held, deadline);
    await("health-taps", held, deadline);
    // A future can complete in finally before an uncaught source assertion reaches its handler.
    // Join only threads positively identified by these fixtures' actual source invocations.
    for (Thread source : Set.copyOf(sourceThreads))
      if (!held.contains(source.getName().substring("status-".length()))) join(source, deadline);
    quiescent = held.isEmpty();
    assertNoErrors();
  }

  private void await(String name, Set<String> held, long deadline) throws Exception {
    if (held.contains(name)) return;
    for (var future : List.copyOf(handles.getOrDefault(name, new ConcurrentLinkedQueue<>())))
      future.get(remaining(deadline), TimeUnit.NANOSECONDS);
  }

  private static long remaining(long deadline) throws TimeoutException {
    long left = deadline - System.nanoTime();
    if (left <= 0L) throw new TimeoutException("owned status work did not quiesce");
    return left;
  }

  private static void join(Thread thread, long deadline) throws Exception {
    if (thread == Thread.currentThread()) return;
    thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining(deadline))));
    assertFalse(thread.isAlive(), "owned status thread must finish: " + thread.getName());
  }

  void assertNoErrors() {
    if (errors.isEmpty()) return;
    var failure = new AssertionError("unexpected fixture-thread failure");
    errors.forEach(failure::addSuppressed);
    throw failure;
  }
}
