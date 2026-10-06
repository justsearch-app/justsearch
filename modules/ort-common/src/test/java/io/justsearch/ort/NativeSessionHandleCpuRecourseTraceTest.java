/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.handle;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.holdBackground;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.hybridLeg;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.warmCpu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The trace says a turned-away query leg ran on the CPU: the leg's span carries a {@code
 * cpu_fallback.triggered} event naming the encoder and why the device turned it away, and the
 * lease it ran on is a CPU lease (from which the encoders set {@code encoder.gpu=false}).
 *
 * <p>The leg's span is a recording span made current around the acquisition, as the search's own
 * span is; the event and its attributes are the ones the NDJSON exporter allows.
 */
final class NativeSessionHandleCpuRecourseTraceTest {

  @Test
  void aTurnedAwayLegRecordsThatItRanOnTheCpu() throws Exception {
    NativeSessionHandle handle = handle("cpu-recourse-trace", 86);
    warmCpu(handle);
    RecordingSpan leg = new RecordingSpan();
    try (SessionHandle.Lease bulk = holdBackground(handle);
        Scope ignored = leg.makeCurrent();
        SessionHandle.Lease lease = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
      assertTrue(lease.isCpu(), "the turned-away leg runs on the CPU session");
    }

    Attributes event =
        leg.events.stream()
            .filter(e -> e.getKey().equals("cpu_fallback.triggered"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CPU event on the leg's span: " + leg.events));
    assertEquals("device_contended", event.get(AttributeKey.stringKey("fallback.cause")));
    assertEquals("cpu-recourse-trace", event.get(AttributeKey.stringKey("fallback.encoder")));
  }

  /** A span that records the events added to it. */
  private static final class RecordingSpan implements Span {
    final List<Map.Entry<String, Attributes>> events = new CopyOnWriteArrayList<>();

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
      events.add(Map.entry(name, attributes));
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
      return addEvent(name, attributes);
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
      return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
      return this;
    }

    @Override
    public Span updateName(String name) {
      return this;
    }

    @Override
    public void end() {}

    @Override
    public void end(long timestamp, TimeUnit unit) {}

    @Override
    public SpanContext getSpanContext() {
      return SpanContext.getInvalid();
    }

    @Override
    public boolean isRecording() {
      return true;
    }
  }
}
