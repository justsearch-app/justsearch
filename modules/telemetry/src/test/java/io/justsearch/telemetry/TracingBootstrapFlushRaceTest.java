/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the SDK's completed-but-not-retired flush request through public SDK APIs. */
@Timeout(10)
class TracingBootstrapFlushRaceTest {
  @TempDir Path dir;

  @Test
  void componentCloseWaitsForFreshCoverageAfterCompletedRequest() throws Exception {
    assertFreshCoverage(false);
  }

  @Test
  void finalTeardownWaitsForFreshCoverageAfterCompletedRequest() throws Exception {
    assertFreshCoverage(true);
  }

  private void assertFreshCoverage(boolean finalTeardown) throws Exception {
    var previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
    var exportEntered = new CountDownLatch(1);
    var releaseExport = new CountDownLatch(1);
    var callbackEntered = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    Thread caller = null;
    try {
      var health = new TelemetryHealthState();
      var bootstrap = TracingBootstrap.forIndexing(dir, health, "detailed");
      assertSame(bootstrap, TracingBootstrap.forIndexing(dir, null, "sample"));
      // The global API deliberately hides the SDK provider. Read only our own reference;
      // scheduling and all SDK interactions below use public SDK methods.
      var providerField = TracingBootstrap.class.getDeclaredField("tracerProvider");
      providerField.setAccessible(true);
      var provider = (SdkTracerProvider) providerField.get(bootstrap);
      var tracer = GlobalOpenTelemetry.getTracer("flush-race-controlled");
      // Immutable event attributes delay serialization, so callback registration cannot race
      // request completion. Neither the SDK worker nor its private state is accessed.
      var seed = tracer.spanBuilder("before-close").startSpan();
      seed.addEvent("contract.violation", new ExportGate(exportEntered, releaseExport));
      seed.end();
      var completedRequest = provider.forceFlush();
      assertTrue(exportEntered.await(1, TimeUnit.SECONDS), "seed export must reach its gate");
      completedRequest.whenComplete(() -> {
        callbackEntered.countDown();
        awaitRelease(releaseCallback);
      });
      releaseExport.countDown();
      assertTrue(callbackEntered.await(1, TimeUnit.SECONDS), "worker must hold request retirement");
      // This completion is successful, but BSP still has its request installed until our
      // callback returns. End the marker only after that request's export has finished.
      String marker = finalTeardown ? "before-final-teardown" : "after-index-close";
      var span = tracer.spanBuilder(marker).startSpan();
      assertTrue(span.getSpanContext().isSampled());
      span.end();
      var close = new FutureTask<Void>(() -> {
        if (finalTeardown) TracingBootstrap.shutdownIndexing();
        else bootstrap.close();
        return null;
      });
      caller = new Thread(close, "stale-flush-close");
      long started = System.nanoTime();
      caller.start();
      // CRC invokes callbacks under its monitor. Wait until this ordinary caller has obtained
      // the stale request and is blocked reading it, then let the worker retire the request.
      assertTrue(awaitBlockedInResult(caller), "close must encounter the completed request");
      releaseCallback.countDown();
      close.get(1, TimeUnit.SECONDS);
      assertFalse(caller.isInterrupted());
      assertTrue(Files.readString(dir.resolve("telemetry/traces.ndjson"))
          .contains("\"name\":\"" + marker + "\""), "marker must be written when close returns");
      assertFalse(health.snapshot().hasFailures(), "exclude write failure and disk suppression");
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
    } finally {
      releaseExport.countDown();
      releaseCallback.countDown();
      if (caller != null) {
        caller.join(2000);
        assertFalse(caller.isAlive(), "close caller must terminate");
      }
      TracingBootstrap.shutdownIndexing();
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(previous);
    }
  }

  private static boolean awaitBlockedInResult(Thread caller) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    do {
      if (caller.getState() == Thread.State.BLOCKED) {
        for (var frame : caller.getStackTrace()) {
          if (frame.getClassName().equals(CompletableResultCode.class.getName())) return true;
        }
      }
      Thread.yield();
    } while (caller.isAlive() && System.nanoTime() < deadline);
    return false;
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      assertTrue(release.await(3, TimeUnit.SECONDS), "gate must be explicitly released");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("gate interrupted", e);
    }
  }

  private record ExportGate(CountDownLatch entered, CountDownLatch release) implements Attributes {
    private static final Attributes VALUES = Attributes.of(AttributeKey.stringKey("contract.tier"), "test");

    @Override
    public <T> T get(AttributeKey<T> key) { return VALUES.get(key); }

    @Override
    public void forEach(BiConsumer<? super AttributeKey<?>, ? super Object> consumer) {
      VALUES.forEach(consumer);
    }

    @Override
    public int size() { return VALUES.size(); }

    @Override
    public boolean isEmpty() { return VALUES.isEmpty(); }

    @Override
    public Map<AttributeKey<?>, Object> asMap() {
      entered.countDown();
      awaitRelease(release);
      return VALUES.asMap();
    }

    @Override
    public AttributesBuilder toBuilder() { return VALUES.toBuilder(); }
  }
}
