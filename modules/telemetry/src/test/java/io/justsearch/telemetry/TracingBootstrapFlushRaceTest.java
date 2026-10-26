/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Diagnostic reproducer: the marker acknowledgment assertion fails on SDK 1.60.1 when the race
 * occurs on the unrepaired bootstrap. Uses the real SDK, public bootstrap API, and an ordinary
 * application thread.
 */
@Timeout(30)
class TracingBootstrapFlushRaceTest {
  @TempDir Path dir;

  @Test
  void sequentialClosesCanReturnBeforeMarkerIsWritten() throws Exception {
    var previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
    int missing = 0;
    int attempts = 20_000;
    int firstMissing = -1;
    String oldMax = System.getProperty("justsearch.telemetry.traces.max_mb");
    System.setProperty("justsearch.telemetry.traces.max_mb", "1024");
    try {
      var health = new TelemetryHealthState();
      var bootstrap = TracingBootstrap.forIndexing(dir, health, "detailed");
      var replacement = TracingBootstrap.forIndexing(dir, null, "sample");
      assertSame(bootstrap, replacement);
      var tracer = GlobalOpenTelemetry.getTracer("flush-race-natural");
      Path tracesFile = dir.resolve("telemetry/traces.ndjson");
      for (int i = 0; i < attempts; i++) {
        tracer.spanBuilder("before-" + i).startSpan().end();
        bootstrap.close();
        String marker = "after-index-close-" + i;
        var span = tracer.spanBuilder(marker).startSpan();
        assertTrue(span.getSpanContext().isSampled());
        long offset;
        try (var file = FileChannel.open(tracesFile)) {
          offset = file.size();
        }
        span.end();
        long start = System.nanoTime();
        replacement.close();
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
        assertFalse(Thread.currentThread().isInterrupted());
        // Read the bytes appended since before this marker ended, once, before any cleanup.
        // A larger rotation threshold keeps these offsets stable throughout this bounded run.
        try (var file = FileChannel.open(tracesFile)) {
          var tail = ByteBuffer.allocate(Math.toIntExact(file.size() - offset));
          while (tail.hasRemaining() && file.read(tail, offset + tail.position()) > 0) {}
          tail.flip();
          if (!StandardCharsets.UTF_8.decode(tail).toString()
              .contains("\"name\":\"" + marker + "\"")) {
            missing++;
            if (firstMissing < 0) firstMissing = i;
          }
        }
      }
      System.out.printf("Sequential closes: missing=%d/%d, firstMissing=%d%n",
          missing, attempts, firstMissing);
      assertFalse(health.snapshot().hasFailures(), "exclude failed writes and disk suppression");
      assertEquals(0, missing, "markers must be written before component close returns");
    } finally {
      TracingBootstrap.shutdownIndexing();
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(previous);
      if (oldMax == null) System.clearProperty("justsearch.telemetry.traces.max_mb");
      else System.setProperty("justsearch.telemetry.traces.max_mb", oldMax);
    }
  }
}
