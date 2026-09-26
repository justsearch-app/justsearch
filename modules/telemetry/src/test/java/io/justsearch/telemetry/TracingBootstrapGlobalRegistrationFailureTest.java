/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracingBootstrapGlobalRegistrationFailureTest {
  private static final String BATCH_WORKER_PREFIX = "BatchSpanProcessor_WorkerThread";
  @TempDir Path dir;

  @Test
  void closesNewSdkWhenAnotherGlobalIsAlreadyRegistered() throws Exception {
    var previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
    OpenTelemetrySdk incumbent = OpenTelemetrySdk.builder().build();
    GlobalOpenTelemetry.set(incumbent);
    var registered = GlobalOpenTelemetry.get();
    Set<Long> workersBefore = batchWorkerIds();

    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              new TracingBootstrap(
                  dir,
                  null,
                  Sampler.alwaysOn(),
                  Map.of()));

      assertSame(registered, GlobalOpenTelemetry.get(), "failed registration must keep the incumbent");
      // Shutdown completes the exporter before the worker leaves its default five-second poll.
      assertNoNewBatchWorkers(workersBefore, Duration.ofSeconds(10));
    } finally {
      GlobalOpenTelemetry.resetForTest();
      incumbent.close();
      GlobalOpenTelemetry.set(previous);
    }
  }

  private static Set<Long> batchWorkerIds() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(thread -> thread.getName().startsWith(BATCH_WORKER_PREFIX))
        .map(Thread::threadId)
        .collect(Collectors.toSet());
  }

  private static void assertNoNewBatchWorkers(Set<Long> workersBefore, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<Thread> leaked;
    do {
      leaked = Thread.getAllStackTraces().keySet().stream()
          .filter(Thread::isAlive)
          .filter(thread -> thread.getName().startsWith(BATCH_WORKER_PREFIX))
          .filter(thread -> !workersBefore.contains(thread.threadId()))
          .toList();
      if (leaked.isEmpty()) return;
      for (Thread thread : leaked) {
        thread.join(25);
      }
    } while (System.nanoTime() < deadline);

    fail(
        "failed TracingBootstrap construction leaked BatchSpanProcessor workers: "
            + leaked.stream()
                .map(thread -> thread.getName() + "#" + thread.threadId() + ":" + thread.getState())
                .toList());
  }
}
