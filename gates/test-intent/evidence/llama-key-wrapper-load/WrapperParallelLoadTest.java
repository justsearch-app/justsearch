/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Temporary check driver: eight independent fixtures in parallel, at most twenty-five rounds. */
final class WrapperParallelLoadTest {
  @org.junit.jupiter.api.Timeout(value = 10, unit = TimeUnit.MINUTES)
  @Test void wrapperChecksUnderParallelLoad() throws Exception {
    var methods = Arrays.stream(ManagedLlamaAuthenticationTest.class.getDeclaredMethods())
        .filter(m -> m.getName().equals("aWrapperPassesTheKeyToTheServingProcessAndStopsWithIt")
            || m.getName().equals("aCooperativeWrapperDescendantExitsAndRestartAuthenticates")
            || m.getName().equals("aServingWrapperDescendantBlocksRestartUntilItEnds"))
        .sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList();
    assertFalse(methods.isEmpty());
    Path root = Files.createTempDirectory(Path.of(System.getenv("TMPDIR")), "wrapper-load-");
    List<Throwable> failed = new ArrayList<>();
    int completed = 0;
    try (var workers = Executors.newFixedThreadPool(8)) {
      for (int round = 0; round < Integer.parseInt(System.getenv().getOrDefault("WRAPPER_LOAD_ROUNDS", "25")) && failed.isEmpty(); round++) {
        List<Callable<Throwable>> batch = new ArrayList<>();
        for (int lane = 0; lane < 8; lane++) {
          var method = methods.get((round * 8 + lane) % methods.size());
          batch.add(() -> {
            var check = new ManagedLlamaAuthenticationTest();
            check.tmp = Files.createTempDirectory(root, "case-");
            Throwable failure = null;
            long began = System.nanoTime();
            try { check.captureLaunches(); method.invoke(check); }
            catch (java.lang.reflect.InvocationTargetException e) { failure = e.getCause(); }
            catch (Throwable e) { failure = e; }
            finally {
              try { check.cleanup(); }
              catch (Throwable cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
              }
            }
            System.out.println("WRAPPER_LOAD " + method.getName() + " "
                + (failure == null ? "PASS" : "FAIL " + failure) + " ms="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
            return failure;
          });
        }
        for (Future<Throwable> f : workers.invokeAll(batch, 120, TimeUnit.SECONDS)) {
          completed++;
          if (f.isCancelled()) failed.add(new AssertionError("load case exceeded 120 seconds"));
          else { var failure = f.get(); if (failure != null) failed.add(failure); }
        }
      }
    }
    System.out.println("WRAPPER_LOAD_SUMMARY completed=" + completed + " failed=" + failed.size());
    if (!failed.isEmpty()) {
      var failure = new AssertionError("parallel wrapper check failed", failed.getFirst());
      failed.stream().skip(1).forEach(failure::addSuppressed);
      throw failure;
    }
  }
}
