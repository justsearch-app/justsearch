/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.OcrTags.OcrEngineTags;
import io.justsearch.indexerworker.extract.OcrTags.OcrFailureTags;
import io.justsearch.indexerworker.extract.OcrTags.OcrSkipTags;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/** Worker-owned catalog for OCR metrics emitted by the live Tika OCR extraction route. */
public final class OcrMetricCatalog implements MetricCatalog {
  public static final String NAMESPACE = "ocr";

  public static final String SUCCEEDED_TOTAL = "ocr.succeeded_total";
  public static final String TIME_MS = "ocr.time_ms";
  public static final String FAILED_TOTAL = "ocr.failed_total";
  public static final String SKIPPED_TOTAL = "ocr.skipped_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(SUCCEEDED_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OcrTags.ENGINE_KEYS)
              .cardinalityLimit(8)
              .build(),
          MetricDefinition.histogram(TIME_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(OcrTags.ENGINE_KEYS)
              .buckets(List.of(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L))
              .cardinalityLimit(8)
              .build(),
          MetricDefinition.counter(FAILED_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OcrTags.ENGINE_AND_ERROR_KEYS)
              .cardinalityLimit(64)
              .build(),
          MetricDefinition.counter(SKIPPED_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OcrTags.REASON_KEYS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "OcrMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<OcrEngineTags> succeededTotal;
  public final HistogramMetric<OcrEngineTags> timeMs;
  public final CounterMetric<OcrFailureTags> failedTotal;
  public final CounterMetric<OcrSkipTags> skippedTotal;

  private final java.util.function.Consumer<Event> events;

  public OcrMetricCatalog(MetricRegistry registry) {
    this(registry, ignored -> {});
  }

  private OcrMetricCatalog(MetricRegistry registry, java.util.function.Consumer<Event> events) {
    this.events = events;
    Objects.requireNonNull(registry, "registry");
    this.succeededTotal = registry.buildCounter(SUCCEEDED_TOTAL);
    this.timeMs = registry.buildHistogram(TIME_MS);
    this.failedTotal = registry.buildCounter(FAILED_TOTAL);
    this.skippedTotal = registry.buildCounter(SKIPPED_TOTAL);
  }

  static OcrMetricCatalog recording(java.util.function.Consumer<Event> events) {
    return new OcrMetricCatalog(new NoopMetricRegistry(DEFINITIONS), events);
  }

  void succeeded() { publish(new Event(SUCCEEDED_TOTAL, 1, null)); }
  void time(long millis) { publish(new Event(TIME_MS, millis, null)); }
  void failed(String error) { publish(new Event(FAILED_TOTAL, 1, error)); }
  void skipped(OcrSkipReason reason) { publish(new Event(SKIPPED_TOTAL, 1, reason.name())); }

  void publish(Event event) {
    switch (event.name()) {
      case SUCCEEDED_TOTAL -> succeededTotal.add(event.value(), OcrEngineTags.of(OcrRoutingConfig.ENGINE));
      case TIME_MS -> timeMs.record(event.value(), OcrEngineTags.of(OcrRoutingConfig.ENGINE));
      case FAILED_TOTAL -> failedTotal.add(event.value(), OcrFailureTags.of(OcrRoutingConfig.ENGINE, event.detail()));
      case SKIPPED_TOTAL -> skippedTotal.add(event.value(), OcrSkipTags.of(OcrSkipReason.valueOf(event.detail())));
      default -> throw new IllegalArgumentException("Unknown OCR metric");
    }
    events.accept(event);
  }

  /** Bounded, request-local instrument events returned by the sandbox. */
  public record Event(String name, long value, String detail) {
    public Event {
      if (!List.of(SUCCEEDED_TOTAL, TIME_MS, FAILED_TOTAL, SKIPPED_TOTAL).contains(name)
          || value < 0 || (detail != null && detail.length() > 96)) {
        throw new IllegalArgumentException("Invalid OCR metric event");
      }
      if (SKIPPED_TOTAL.equals(name)) OcrSkipReason.valueOf(detail);
      if (FAILED_TOTAL.equals(name) && (detail == null || detail.isBlank())) {
        throw new IllegalArgumentException("Missing OCR error tag");
      }
    }
  }

  private static final OcrMetricCatalog NOOP =
      new OcrMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  public static OcrMetricCatalog noop() {
    return NOOP;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return DEFINITIONS;
  }
}
