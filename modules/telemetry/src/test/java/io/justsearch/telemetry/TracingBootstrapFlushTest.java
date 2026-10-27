/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Timeout(15)
class TracingBootstrapFlushTest {
  @TempDir Path dir;
  private OpenTelemetry previous;

  @BeforeEach
  void resetGlobal() {
    previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
  }

  @AfterEach
  void restoreGlobal() {
    TracingBootstrap.shutdownIndexing();
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(previous);
  }

  @Test
  void concurrentComponentClosesAcknowledgeEachCallersMarkers() throws Exception {
    var bootstrap = TracingBootstrap.forIndexing(dir, null, "detailed");
    var tracer = GlobalOpenTelemetry.getTracer("concurrent-flush");
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> {
        closeWithMarkers(bootstrap, tracer, barrier, "first-");
        return null;
      });
      var second = executor.submit(() -> {
        closeWithMarkers(bootstrap, tracer, barrier, "second-");
        return null;
      });
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
  }

  private void closeWithMarkers(TracingBootstrap bootstrap, Tracer tracer,
      CyclicBarrier barrier, String prefix) throws Exception {
    for (int i = 0; i < 100; i++) {
      String marker = prefix + i;
      tracer.spanBuilder(marker).startSpan().end();
      barrier.await(2, TimeUnit.SECONDS);
      bootstrap.close();
      assertTrue(Files.readString(dir.resolve("telemetry/traces.ndjson"))
          .contains("\"name\":\"" + marker + "\""), marker);
    }
  }

  @Test
  void emptyAndRepeatedComponentClosesReturnPromptlyAndKeepProviderLive() throws Exception {
    var bootstrap = TracingBootstrap.forIndexing(dir, null, "detailed");
    long started = System.nanoTime();
    for (int i = 0; i < 50; i++) bootstrap.close();
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
    GlobalOpenTelemetry.getTracer("repeated-flush").spanBuilder("still-live").startSpan().end();
    bootstrap.close();
    assertTrue(Files.readString(dir.resolve("telemetry/traces.ndjson")).contains("still-live"));
  }

  @Test
  void blockedLocalExporterExpiresWithinTheSingleDeadlineAndLogsExpiry() throws Exception {
    var exporter = new BlockingExporter();
    var logger = (Logger) LoggerFactory.getLogger(TracingBootstrap.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try (var bootstrap = new TracingBootstrap(exporter, Sampler.alwaysOn(), Map.of())) {
      GlobalOpenTelemetry.getTracer("blocked-flush").spanBuilder("blocked").startSpan().end();
      long started = System.nanoTime();
      try {
        // Component close calls this same flush method; the injected exporter blocks local A.
        bootstrap.flush();
        long elapsed = System.nanoTime() - started;
        assertTrue(exporter.entered.getCount() == 0, "export must actually block");
        assertTrue(elapsed >= Duration.ofMillis(4500).toNanos());
        assertTrue(elapsed < Duration.ofSeconds(6).toNanos(), "one five-second budget");
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage()
            .equals("Explicit span flush expired after 5 seconds")));
        assertFalse(events.list.stream().anyMatch(event -> event.getFormattedMessage()
            .equals("Explicit span flush completed")));
        System.out.printf("Blocked local flush elapsed: %.3f s%n", elapsed / 1_000_000_000.0);
      } finally {
        exporter.release.countDown();
      }
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void localAndOtlpWaitsShareTheSameDeadline() throws Exception {
    var exporter = new BlockingExporter();
    var requestEntered = new CountDownLatch(1);
    var releaseRequest = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/traces", exchange -> {
      requestEntered.countDown();
      try {
        exchange.getRequestBody().readAllBytes();
        releaseRequest.await(10, TimeUnit.SECONDS);
        exchange.sendResponseHeaders(200, -1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    Thread releaser = null;
    String endpoint = "http://localhost:" + server.getAddress().getPort() + "/v1/traces";
    try (var bootstrap = new TracingBootstrap(exporter, Sampler.alwaysOn(), Map.of(
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint,
        "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "10000"))) {
      GlobalOpenTelemetry.getTracer("shared-deadline").spanBuilder("blocked").startSpan().end();
      releaser = Thread.ofVirtual().start(() -> {
        try {
          if (exporter.entered.await(2, TimeUnit.SECONDS)) {
            Thread.sleep(3000);
            exporter.release.countDown();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      long started = System.nanoTime();
      try {
        bootstrap.flush();
        long elapsed = System.nanoTime() - started;
        assertTrue(exporter.entered.getCount() == 0);
        assertTrue(requestEntered.getCount() == 0, "both exporters must participate");
        assertTrue(elapsed >= Duration.ofMillis(4500).toNanos());
        assertTrue(elapsed < Duration.ofSeconds(6).toNanos(), "local wait must consume OTLP's budget");
        System.out.printf("Combined local/OTLP flush elapsed: %.3f s%n", elapsed / 1_000_000_000.0);
      } finally {
        exporter.release.countDown();
        releaseRequest.countDown();
      }
    } finally {
      exporter.release.countDown();
      releaseRequest.countDown();
      if (releaser != null) {
        releaser.interrupt();
        releaser.join(1000);
      }
      server.stop(0);
    }
  }

  @Test
  void alreadyInterruptedComponentClosePreservesInterrupt() {
    var bootstrap = TracingBootstrap.forIndexing(dir, null, "detailed");
    try {
      Thread.currentThread().interrupt();
      long started = System.nanoTime();
      bootstrap.close();
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptWhileAwaitingLocalExporterReturnsWithInterruptPreserved() throws Exception {
    var exporter = new BlockingExporter();
    try (var bootstrap = new TracingBootstrap(exporter, Sampler.alwaysOn(), Map.of())) {
      GlobalOpenTelemetry.getTracer("interrupt-flush").spanBuilder("blocked").startSpan().end();
      var preserved = new AtomicBoolean();
      Thread caller = Thread.ofVirtual().start(() -> {
        bootstrap.flush();
        preserved.set(Thread.currentThread().isInterrupted());
      });
      try {
        assertTrue(exporter.entered.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1000);
        assertFalse(caller.isAlive(), "interruption must stop waiting");
        assertTrue(preserved.get());
      } finally {
        exporter.release.countDown();
        caller.interrupt();
        caller.join(1000);
      }
    }
  }

  @Test
  void retainedComponentCloseAfterFinalShutdownReturnsPromptly() throws Exception {
    var bootstrap = TracingBootstrap.forIndexing(dir, null, "detailed");
    GlobalOpenTelemetry.getTracer("shutdown-flush").spanBuilder("last").startSpan().end();
    TracingBootstrap.shutdownIndexing();
    assertTrue(Files.readString(dir.resolve("telemetry/traces.ndjson"))
        .contains("\"name\":\"last\""));
    long started = System.nanoTime();
    bootstrap.close();
    bootstrap.flush();
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
  }

  @Test
  void racingProviderShutdownReleasesAnExistingFlushWaiter() throws Exception {
    var exporter = new BlockingExporter();
    var bootstrap = new TracingBootstrap(exporter, Sampler.alwaysOn(), Map.of());
    GlobalOpenTelemetry.getTracer("racing-shutdown").spanBuilder("blocked").startSpan().end();
    Thread caller = Thread.ofVirtual().start(bootstrap::flush);
    Thread closer = null;
    try {
      assertTrue(exporter.entered.await(2, TimeUnit.SECONDS));
      // Direct construction closes the provider, using the same shutdown boundary as final teardown.
      closer = Thread.ofVirtual().start(bootstrap::close);
      caller.join(1000);
      assertFalse(caller.isAlive(), "provider shutdown must release a blocked flush caller");
      long started = System.nanoTime();
      bootstrap.flush();
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
    } finally {
      exporter.release.countDown();
      caller.join(1000);
      if (closer != null) {
        closer.join(2000);
        assertFalse(closer.isAlive());
      }
      bootstrap.close();
    }
    long started = System.nanoTime();
    bootstrap.flush();
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
  }

  private static final class BlockingExporter implements SpanExporter {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      entered.countDown();
      try {
        if (!release.await(12, TimeUnit.SECONDS)) return CompletableResultCode.ofFailure();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return CompletableResultCode.ofFailure();
      }
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

    @Override
    public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  }
}
