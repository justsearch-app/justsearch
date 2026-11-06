/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CandidateProtectionProofTest {
  @TempDir Path directory;

  @Test void stalledIdentityResolutionCannotHoldTheCutoverPastItsDeadline() throws Exception {
    Path source = Files.writeString(directory.resolve("slow.txt"), "ordinary");
    var release = new java.util.concurrent.CountDownLatch(1);
    var entered = new java.util.concurrent.CountDownLatch(1);
    Path stalled = mock(Path.class, call -> {
      if (call.getMethod().getName().equals("toRealPath")) {
        entered.countDown();
        boolean finished = false;
        while (!finished) {
          try { release.await(); finished = true; }
          catch (InterruptedException ignored) { /* Model an uninterruptible filesystem call. */ }
        }
        return source.toRealPath();
      }
      return call.getMethod().invoke(source, call.getArguments());
    });
    var classify = Arrays.stream(IngestionSkipPolicy.class.getMethods())
        .filter(method -> method.getName().equals("classifyForUpgrade")).findFirst();
    assertTrue(classify.isPresent(), "identity proof needs a deadline");
    try {
      long start = System.nanoTime();
      var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
          classify.get().invoke(IngestionSkipPolicy.active(), stalled, source, null,
              start + TimeUnit.MILLISECONDS.toNanos(200), (java.util.function.BooleanSupplier) () -> false));
      assertInstanceOf(java.io.IOException.class, failure.getCause());
      assertEquals(0, entered.getCount(), "the deadline must bound a lookup that actually started");
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
    } finally { release.countDown(); }
  }

  @Test void proofDeduplicatesSourcesAndRejectsExpiredCancelledAndUncertainEnumeration() throws Exception {
    Path source = Files.writeString(directory.resolve("notes.txt"), "ordinary");
    var lookups = new java.util.concurrent.atomic.AtomicInteger();
    Path classified = mock(Path.class, call -> {
      if (call.getMethod().getName().equals("toRealPath")) {
        lookups.incrementAndGet();
        return source.toRealPath();
      }
      return call.getMethod().invoke(source, call.getArguments());
    });
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    when(runtime.isAcceptingWrites()).thenReturn(true);
    var service = new WorkerIngestService(mock(JobQueue.class), null, null,
        IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
    var policy = spy(IngestionSkipPolicy.defaultPolicy());
    var previous = IngestionSkipPolicy.active();
    IngestionSkipPolicy.installResolved(policy);
    var fields = runtime.documentFieldOps();
    doAnswer(call -> {
      Consumer<List<DocumentFieldOps.IndexedSource>> batch = call.getArgument(1);
      var row = new DocumentFieldOps.IndexedSource(source.toString(), source.toString(), "uid", "public");
      for (int i = 0; i < 20; i++) batch.accept(Collections.nCopies(256, row));
      return 0L;
    }).when(fields).scanIndexedSources(anyInt(), any());
    try {
      try (var paths = mockStatic(Path.class, CALLS_REAL_METHODS)) {
        paths.when(() -> { Path unused = Path.of(source.toString()); }).thenReturn(classified);
        service.requireProtectedCandidateClean();
        assertEquals(2, lookups.get(), "one source/root identity lookup, not one per chunk");
        // Reflection keeps the fail-before probe compilable on the repaired candidate's baseline.
        var classify = Arrays.stream(IngestionSkipPolicy.class.getMethods())
            .filter(method -> method.getName().equals("classifyForUpgrade")).findFirst();
        assertTrue(classify.isPresent(), "the publication proof must have a bounded identity lookup");
        long calls = mockingDetails(policy).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("classifyForUpgrade")).count();
        assertEquals(1, calls, "all chunks of one source share its classification");
        var bounded = Arrays.stream(WorkerIngestService.class.getMethods())
            .filter(method -> method.getName().equals("requireProtectedCandidateClean") && method.getParameterCount() == 2)
            .findFirst();
        assertTrue(bounded.isPresent(), "cutover must supply its actual deadline and cancellation");
        for (boolean cancelled : List.of(false, true)) {
          long deadline = cancelled ? System.nanoTime() + TimeUnit.SECONDS.toNanos(1) : System.nanoTime() - 1;
          var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
              bounded.get().invoke(service, deadline, (java.util.function.BooleanSupplier) () -> cancelled));
          assertInstanceOf(java.io.IOException.class, failure.getCause());
        }
        Files.delete(source);
        service.requireProtectedCandidateClean(); // Ordinary offline sources preserve baseline recovery.
        doAnswer(call -> {
          Consumer<List<DocumentFieldOps.IndexedSource>> batch = call.getArgument(1);
          String suspect = directory.resolve("alias/agent-history/run.md").toString();
          batch.accept(List.of(new DocumentFieldOps.IndexedSource(suspect, suspect, "legacy", null)));
          return 0L;
        }).when(fields).scanIndexedSources(anyInt(), any());
        assertThrows(java.io.IOException.class, service::requireProtectedCandidateClean,
            "unresolved source identity cannot certify publication");
      }
    } finally { IngestionSkipPolicy.installResolved(previous); }
  }

  @Test void aStalledFilesystemCannotKeepThePublicationProofRunningBeyondItsBudget() throws Exception {
    Path source = Files.writeString(directory.resolve("stalled.txt"), "ordinary");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Path stalled = mock(Path.class, call -> {
      if (call.getMethod().getName().equals("toRealPath")) {
        entered.countDown();
        boolean complete = false;
        while (!complete) {
          try { release.await(); complete = true; }
          catch (InterruptedException ignored) { /* Model an uninterruptible filesystem call. */ }
        }
        return source.toRealPath();
      }
      return call.getMethod().invoke(source, call.getArguments());
    });
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    when(runtime.isAcceptingWrites()).thenReturn(true);
    var service = new WorkerIngestService(mock(JobQueue.class), null, null,
        IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
    var fields = runtime.documentFieldOps();
    doAnswer(call -> {
      Consumer<List<DocumentFieldOps.IndexedSource>> batch = call.getArgument(1);
      batch.accept(List.of(new DocumentFieldOps.IndexedSource(source.toString(), source.toString(), "uid", "public")));
      return 0L;
    }).when(fields).scanIndexedSources(anyInt(), any());
    var previous = IngestionSkipPolicy.active();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    IngestionSkipPolicy.installResolved(IngestionSkipPolicy.defaultPolicy());
    try {
      var proof = executor.submit(() -> {
        try (var paths = mockStatic(Path.class, CALLS_REAL_METHODS)) {
          paths.when(() -> { Path unused = Path.of(source.toString()); }).thenReturn(stalled);
          var bounded = Arrays.stream(WorkerIngestService.class.getMethods())
              .filter(method -> method.getName().equals("requireProtectedCandidateClean") && method.getParameterCount() == 2)
              .findFirst();
          // Exercise the baseline's real proof too: its missing budget must time out at the
          // caller while stuck in the filesystem, rather than fail merely on an absent API.
          if (bounded.isEmpty()) service.requireProtectedCandidateClean();
          else {
            try {
              bounded.get().invoke(service, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200),
                  (java.util.function.BooleanSupplier) () -> false);
            } catch (java.lang.reflect.InvocationTargetException failed) {
              if (failed.getCause() instanceof Exception cause) throw cause;
              throw new AssertionError(failed.getCause());
            }
          }
          return null;
        }
      });
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> proof.get(2, TimeUnit.SECONDS), "the proof must refuse the cutover within its budget");
      assertInstanceOf(java.io.IOException.class, failure.getCause());
      assertEquals(0, entered.getCount(), "a filesystem lookup actually started");
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      IngestionSkipPolicy.installResolved(previous);
    }
  }
}
