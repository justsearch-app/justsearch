/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Diagnostic: eight parallel fixtures; 200 candidate cases, at most 2,000 old cases. */
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
    List<Throwable> target = new ArrayList<>();
    boolean oldCheck = methods.size() == 1;
    int rounds = oldCheck ? 250 : 25;
    int completed = 0;
    try (var workers = Executors.newFixedThreadPool(8)) {
      for (int round = 0; round < rounds && (oldCheck ? target.isEmpty() : failed.isEmpty()); round++) {
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
          else {
            var failure = f.get();
            if (failure != null) {
              failed.add(failure);
              if (oldCheck && failure instanceof AssertionError && failure.getMessage() != null
                  && failure.getMessage().contains("stopping the owned wrapper also ends its serving descendant")) {
                target.add(failure);
              }
            }
          }
        }
      }
    }
    System.out.println("WRAPPER_LOAD_SUMMARY completed=" + completed + " failed=" + failed.size()
        + " target=" + target.size());
    if (oldCheck && target.isEmpty()) {
      var missing = new IllegalStateException("Old wrapper-stop race not reproduced within the bound");
      failed.forEach(missing::addSuppressed);
      throw missing;
    }
    if (!failed.isEmpty()) {
      var failure = new AssertionError(oldCheck ? "old wrapper-stop race reproduced" : "parallel wrapper check failed",
          oldCheck ? target.getFirst() : failed.getFirst());
      failed.stream().skip(1).forEach(failure::addSuppressed);
      throw failure;
    }
  }
}
