/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.Exemplars;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.InstrumentKind;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.ObservableCounterMetric;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.ExemplarFilter;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalTelemetry implements Telemetry {
	private static final Logger log = LoggerFactory.getLogger(LocalTelemetry.class);

	private final SdkMeterProvider meterProvider;
	private final EngineExecutorRegistry.Registration exportRegistration;
	private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledFuture<?> heartbeatFuture;
	private final Path metricsFile;
	private final DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
	private final List<Object> gaugeHandles = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final TelemetryHealthState healthState;
  private final RrdMetricStore rrdStore;
  private final CatalogRegistry registry;

  @SuppressWarnings("PMD.UnusedFormalParameter") // serviceVersion remains metadata-only
  public LocalTelemetry(
      EngineExecutorRegistry processExecutors,
      Path dataDir,
      long flushMs,
      String serviceName,
      String serviceVersion) {
    this(processExecutors, dataDir, flushMs, serviceName, serviceVersion, "metrics.ndjson", List.<MetricCatalog>of());
  }

  @SuppressWarnings("PMD.UnusedFormalParameter") // serviceVersion remains metadata-only
  public LocalTelemetry(
      EngineExecutorRegistry processExecutors,
      Path dataDir,
      long flushMs,
      String serviceName,
      String serviceVersion,
      String metricsFileName) {
    this(processExecutors, dataDir, flushMs, serviceName, serviceVersion, metricsFileName, List.<MetricCatalog>of());
  }

  /**
   * Catalog-aware constructor. Each {@link MetricCatalog} contributes a list of
   * {@link MetricDefinition} values used to register per-metric Views (tag schemas, bucket bounds,
   * cardinality limits) before the {@code SdkMeterProvider} is built. Per-metric exemplar
   * policies declared by the catalogs are honored at NDJSON export time.
   *
   * <p>The {@link #registry()} accessor returns a {@link MetricRegistry} that catalog
   * constructors use to build their typed instrument fields after this constructor returns.
   *
   * <p>Tempdoc 417 F2 fix: production catalogs use a single registry-arg constructor (final
   * fields), so they cannot be instantiated before {@code LocalTelemetry} exists. Use
   * {@link MetricCatalog#of(String, List)} to wrap a catalog's static {@code DEFINITIONS} list
   * for this constructor; then construct the typed catalog instance against
   * {@link #registry()}.
   */
  @SuppressWarnings("PMD.UnusedFormalParameter") // serviceVersion remains metadata-only
  public LocalTelemetry(
      EngineExecutorRegistry processExecutors,
      Path dataDir,
      long flushMs,
      String serviceName,
      String serviceVersion,
      String metricsFileName,
      List<MetricCatalog> catalogs) {
		Objects.requireNonNull(processExecutors, "processExecutors");
		Objects.requireNonNull(serviceName, "serviceName");
		Objects.requireNonNull(dataDir, "dataDir");
    Objects.requireNonNull(metricsFileName, "metricsFileName");
    Objects.requireNonNull(catalogs, "catalogs");
		this.metricsFile = dataDir.resolve("telemetry").resolve(metricsFileName);
		this.healthState = new TelemetryHealthState();
		try {
			Files.createDirectories(this.metricsFile.getParent());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create telemetry directory", e);
		}

    // Tempdoc 417 alignment-follow-up Step 3a: append BaselineMetricCatalog to every
    // LocalTelemetry's catalog list so {@code jvm.uptime_ms} routes through the catalog
    // substrate instead of a direct {@code meterProvider.gaugeBuilder} call. Closes the
    // documented "one metric outside the catalog discipline" exception.
    List<MetricCatalog> effectiveCatalogs = new java.util.ArrayList<>(catalogs);
    effectiveCatalogs.add(BaselineMetricCatalog.catalogFor());
    catalogs = List.copyOf(effectiveCatalogs);

    // RRD time-series store for curated metrics. Initialization is deferred to
    // first flush (~5s) to avoid blocking startup with RRD file I/O.
    // Phase 3b: catalog-aware constructor — every MetricDefinition declaring archivedTo(...)
    // joins the curated set automatically, augmenting the legacy hand-maintained list.
    this.rrdStore = new RrdMetricStore(dataDir, catalogs);

    // Validate catalog declarations and collect definitions before SDK build.
    Map<String, MetricDefinition> definitionsByName = new HashMap<>();
    Map<String, Exemplars> exemplarPoliciesByName = new HashMap<>();
    Map<String, List<String>> tagKeyOrderByMetric = new HashMap<>();
    for (MetricCatalog catalog : catalogs) {
      String prefix = catalog.namespace() + ".";
      for (MetricDefinition def : catalog.definitions()) {
        if (!def.name().startsWith(prefix)) {
          throw new IllegalArgumentException(
              "Metric '" + def.name() + "' does not match catalog namespace '"
                  + catalog.namespace() + "'");
        }
        if (definitionsByName.put(def.name(), def) != null) {
          throw new IllegalArgumentException(
              "Duplicate metric definition for name '" + def.name() + "'");
        }
        exemplarPoliciesByName.put(def.name(), def.exemplarPolicy());
        // F1 fix: pass per-metric tag-key order (LinkedHashSet -> ordered List) to the exporter
        // so NDJSON tag-key emission order matches MetricDefinition.allowedTagKeys() declaration
        // order. Falls back to ALLOWED_TAG_KEYS for un-registered metrics.
        tagKeyOrderByMetric.put(def.name(), List.copyOf(def.allowedTagKeys()));
      }
    }

		Resource resource = Resource.getDefault();
    // Exemplar default: TRACE_BASED whenever catalogs are present (catalog-driven, per spec).
    // For legacy zero-catalog callers, preserve the historical opt-in via env/sysprop so existing
    // tests that don't pass catalogs see the same behavior as before.
    boolean envExemplarsOptIn =
        Boolean.parseBoolean(
                System.getenv().getOrDefault("JUSTSEARCH_TELEMETRY_METRICS_EXEMPLARS", "false"))
            || Boolean.parseBoolean(
                System.getProperty("justsearch.telemetry.metrics.exemplars", "false"));
    boolean exemplarsExportEnabled = !catalogs.isEmpty() || envExemplarsOptIn;
		EngineExecutorRegistry.Limits backgroundLimits = processExecutors.limits(Kind.BACKGROUND);
		int timerQueue = backgroundLimits.maxQueue();
		if (timerQueue < 1) {
			throw new IllegalArgumentException("Telemetry requires a positive BACKGROUND timer queue");
		}
		EngineExecutorRegistry.Registration exportRegistration = null;
		EngineExecutorRegistry.Registration heartbeatRegistration = null;
		SdkMeterProvider builtMeterProvider = null;
		MetricReader reader = null;
		ScheduledFuture<?> heartbeatFuture;
		try {
			exportRegistration = processExecutors.register(new EngineExecutorSpec(
				"telemetry." + serviceName + ".export", Kind.BACKGROUND, Mode.SCHEDULED, 1, timerQueue, 1));
			ScheduledExecutorService exportScheduler = exportRegistration.openScheduled(runnable -> {
				Thread t = new Thread(runnable, "telemetry-export");
				t.setDaemon(true);
				return t;
			});
			reader = PeriodicMetricReader.builder(
					new NdjsonMetricExporter(
							this.metricsFile,
							exemplarsExportEnabled,
							healthState,
							rrdStore,
							Map.copyOf(exemplarPoliciesByName),
							Map.copyOf(tagKeyOrderByMetric)))
				.setInterval(java.time.Duration.ofMillis(Math.max(1000, flushMs)))
				.setExecutor(exportScheduler)
				.build();

		// Tempdoc 417 Phase 2f cleanup: hardcoded Views are gone. The `pipeline.stage_ms`,
		// `api.request_ms`, and `api.stream.ttft_ms` histograms now come from typed catalog
		// declarations (IndexingPipelineMetricCatalog, HeadApiMetricCatalog). The dead Views
		// for `llm.latency_ms`, `plugins.stage.load_ms`, and `index.runtime.refresh_lag_ms` are
		// deleted — they were never emitted in production.

		SdkMeterProviderBuilder providerBuilder = SdkMeterProvider.builder()
			.setResource(resource)
        .registerMetricReader(reader)
        .setExemplarFilter(ExemplarFilter.traceBased());

    // Register one View per catalog-declared metric: tag schema (setAttributeFilter) and
    // bucket bounds (setAggregation) wired from the definition. This replaces the per-instrument
    // setExplicitBucketBoundariesAdvice path because a View carries both concerns in one place
    // and Views take precedence over advice.
    for (MetricDefinition def : definitionsByName.values()) {
      InstrumentType iType = mapInstrumentKind(def.kind());
      if (iType == null) continue;
      var selector = InstrumentSelector.builder().setType(iType).setName(def.name()).build();
      var viewBuilder = View.builder();
      if (!def.allowedTagKeys().isEmpty()) {
        viewBuilder.setAttributeFilter(def.allowedTagKeys());
      }
      if (def.kind() == InstrumentKind.HISTOGRAM && def.bucketBoundaries() != null) {
        List<Double> bounds = new java.util.ArrayList<>(def.bucketBoundaries().size());
        for (Long b : def.bucketBoundaries()) bounds.add(b.doubleValue());
        viewBuilder.setAggregation(Aggregation.explicitBucketHistogram(bounds));
      }
      if (def.cardinalityLimit() != null) {
        viewBuilder.setCardinalityLimit(def.cardinalityLimit());
      }
      providerBuilder = providerBuilder.registerView(selector, viewBuilder.build());
    }

			builtMeterProvider = providerBuilder.build();
			heartbeatRegistration = processExecutors.register(new EngineExecutorSpec(
				"telemetry." + serviceName + ".heartbeat", Kind.BACKGROUND, Mode.SCHEDULED, 1, timerQueue, 1));
			ScheduledExecutorService heartbeatScheduler = heartbeatRegistration.openScheduled(runnable -> {
				Thread t = new Thread(runnable, "telemetry-heartbeat");
				t.setDaemon(true);
				return t;
			});
    long period = Math.max(60_000, flushMs * 4); // rare heartbeat backup
    heartbeatFuture =
        heartbeatScheduler.scheduleAtFixedRate(
            this::writeMetricsHeartbeat, period, period, TimeUnit.MILLISECONDS);
		} catch (RuntimeException | Error failure) {
			if (builtMeterProvider != null) {
				closeOnConstructionFailure(failure, builtMeterProvider);
			} else if (reader != null) {
				closeOnConstructionFailure(failure, reader);
			}
			closeOnConstructionFailure(failure, heartbeatRegistration);
			closeOnConstructionFailure(failure, exportRegistration);
			closeOnConstructionFailure(failure, rrdStore);
			throw failure;
		}
		this.exportRegistration = exportRegistration;
		this.heartbeatRegistration = heartbeatRegistration;
		this.heartbeatFuture = heartbeatFuture;
		this.meterProvider = builtMeterProvider;
    this.registry = new CatalogRegistry(this.meterProvider, definitionsByName, gaugeHandles, healthState);

		// Always-on baseline gauge: routes through the catalog substrate per tempdoc 417 alignment
		// follow-up. {@link BaselineMetricCatalog} is appended to {@code catalogs} above so the
		// metric name {@code jvm.uptime_ms} (no per-process prefix) is registered as a View before
		// the {@code SdkMeterProvider} is built; constructing the catalog instance here wires the
		// async-gauge supplier.
		try {
			new BaselineMetricCatalog(this.registry);
		} catch (Exception e) {
			log.warn("Failed to register baseline uptime gauge", e);
		}
	}

	private static void closeOnConstructionFailure(Throwable failure, AutoCloseable resource) {
		if (resource == null) return;
		try {
			resource.close();
		} catch (Throwable cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

  private void writeMetricsHeartbeat() {
		try {
			String t = Instant.now().atOffset(ZoneOffset.UTC).format(iso);
			String line = "{\"t\":\"" + t + "\",\"name\":\"heartbeat\",\"type\":\"counter\"}\n";
			Files.writeString(metricsFile, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception e) {
      log.warn("Failed to write metrics heartbeat", e);
		}
	}

  // Tempdoc 417 Phase 3c: meter(String scope) override removed alongside Telemetry.meter().
  // Tempdoc 417 Phase 3d: histogram() override removed alongside Telemetry.histogram().
  // Tempdoc 417 Phase 3e: counter/timer/gauge overrides removed. Use {@link #registry()} →
  // typed {@code MetricCatalog} instead.

  /**
   * Returns the registry for catalog-driven metric construction. Catalog constructors call
   * {@link MetricRegistry#buildCounter}, {@link MetricRegistry#buildHistogram},
   * {@link MetricRegistry#buildGauge}, or {@link MetricRegistry#buildObservableCounter} to obtain
   * typed instrument instances backed by OTel primitives wired by this {@code LocalTelemetry}.
   */
  public MetricRegistry registry() {
    return registry;
  }

  private static InstrumentType mapInstrumentKind(InstrumentKind kind) {
    return switch (kind) {
      case COUNTER -> InstrumentType.COUNTER;
      case HISTOGRAM -> InstrumentType.HISTOGRAM;
      case GAUGE -> InstrumentType.OBSERVABLE_GAUGE;
      case OBSERVABLE_COUNTER -> InstrumentType.OBSERVABLE_COUNTER;
    };
  }

  /** Catalog-bound implementation of {@link MetricRegistry}. */
  private static final class CatalogRegistry implements MetricRegistry {
    private final SdkMeterProvider provider;
    private final Map<String, MetricDefinition> definitions;
    private final List<Object> gaugeHandles;
    private final TelemetryHealthState healthState;
    /**
     * F7 fix: cache one {@link Meter} per namespace prefix instead of rebuilding per metric.
     * OTel dedupes meters internally so the legacy per-metric build path was correctness-safe
     * but did unnecessary work on the registry-build path.
     */
    private final Map<String, Meter> meterByScope = new HashMap<>();

    CatalogRegistry(
        SdkMeterProvider provider,
        Map<String, MetricDefinition> definitions,
        List<Object> gaugeHandles,
        TelemetryHealthState healthState) {
      this.provider = provider;
      this.definitions = definitions;
      this.gaugeHandles = gaugeHandles;
      this.healthState = healthState;
    }

    private MetricDefinition resolve(String name, InstrumentKind expected) {
      MetricDefinition def = definitions.get(name);
      if (def == null) {
        throw new IllegalArgumentException(
            "No metric definition registered for name '" + name + "'");
      }
      if (def.kind() != expected) {
        throw new IllegalArgumentException(
            "Metric '" + name + "' has kind " + def.kind() + ", not " + expected);
      }
      return def;
    }

    private Meter meter(MetricDefinition def) {
      // Scope the OTel Meter by metric namespace (everything before the last '.'). F7 fix:
      // cached, so multiple metrics in the same namespace share one Meter.
      int dot = def.name().lastIndexOf('.');
      String scope = dot > 0 ? def.name().substring(0, dot) : def.name();
      return meterByScope.computeIfAbsent(scope, s -> provider.meterBuilder(s).build());
    }

    @Override
    public <T extends TagSchema> CounterMetric<T> buildCounter(String name) {
      MetricDefinition def = resolve(name, InstrumentKind.COUNTER);
      var counter = meter(def).counterBuilder(def.name()).build();
      return new CounterMetric<>(def, counter);
    }

    @Override
    public <T extends TagSchema> HistogramMetric<T> buildHistogram(String name) {
      MetricDefinition def = resolve(name, InstrumentKind.HISTOGRAM);
      var hist = meter(def).histogramBuilder(def.name()).ofLongs().build();
      return new HistogramMetric<>(def, hist);
    }

    @Override
    public <T extends TagSchema> GaugeMetric<T> buildGauge(
        String name, T tags, Supplier<Double> supplier) {
      MetricDefinition def = resolve(name, InstrumentKind.GAUGE);
      Attributes attrs = tags.toAttributes();
      Object handle =
          meter(def)
              .gaugeBuilder(def.name())
              .buildWithCallback(
                  measurement -> {
                    double v;
                    try {
                      Double got = supplier.get();
                      v = got == null ? 0.0d : got.doubleValue();
                      if (!Double.isFinite(v)) v = 0.0d;
                    } catch (Exception e) {
                      healthState.recordGaugeCallbackFailure();
                      v = 0.0d;
                    }
                    measurement.record(v, attrs);
                  });
      gaugeHandles.add(handle);
      // F6 fix: handle stored as Object on GaugeMetric; close-time check is instanceof.
      return new GaugeMetric<>(def, tags, handle);
    }

    @Override
    public <T extends TagSchema> ObservableCounterMetric<T> buildObservableCounter(
        String name, T tags, LongSupplier supplier) {
      MetricDefinition def = resolve(name, InstrumentKind.OBSERVABLE_COUNTER);
      Attributes attrs = tags.toAttributes();
      Object handle =
          meter(def)
              .counterBuilder(def.name())
              .buildWithCallback(
                  measurement -> {
                    try {
                      measurement.record(supplier.getAsLong(), attrs);
                    } catch (Exception e) {
                      healthState.recordGaugeCallbackFailure();
                    }
                  });
      gaugeHandles.add(handle);
      // F6 fix: handle stored as Object on ObservableCounterMetric; close-time check is instanceof.
      return new ObservableCounterMetric<>(def, tags, handle);
    }
  }

	@Override
	public void close() {
		// Final flush BEFORE unregistering anything — gives ObservableCounter callbacks one last
		// invocation under forceFlush so their final cumulative values reach the exporter.
		// Pre-fix order (gauges closed first) silently dropped the final value of every
		// ObservableCounter on graceful shutdown (e.g. worker.documents.indexed.total).
		// See observations.md L84 / tempdoc 403 Round 4.
		try {
			CompletableResultCode rc = this.meterProvider.forceFlush();
			rc.join(5, TimeUnit.SECONDS); // 5s headroom for slow disks; one-shot close-time wait.
		} catch (Exception e) {
			healthState.recordFlushFailure();
			log.warn("Final metrics flush failed (best-effort): {}", e.getMessage());
		}
		for (Object h : gaugeHandles) {
			if (h instanceof AutoCloseable c) {
				try {
					c.close();
				} catch (Exception ignored) {
					// best-effort unregister gauges
				}
			}
		}
    if (heartbeatFuture != null) {
      heartbeatFuture.cancel(true);
    }
    // LocalTelemetry owns both registrations and their concrete scheduler instances. The
    // injected process registry remains process-owned and is deliberately never closed here.
    try {
      this.heartbeatRegistration.close();
    } catch (Exception ignored) {
      // best-effort scheduler shutdown
    }
    this.meterProvider.close();
    try {
      this.exportRegistration.close();
    } catch (Exception ignored) {
      // best-effort scheduler shutdown
    }
    this.rrdStore.close();
	}

  /**
   * Returns the RRD time-series store for querying curated metrics.
   *
   * @return the RRD metric store, or null if initialization failed
   */
  public RrdMetricStore getRrdStore() {
    return rrdStore;
  }

	/**
	 * Returns a snapshot of the telemetry subsystem's health state.
	 *
	 * <p>This method is thread-safe and can be called from any thread.
	 *
	 * @return an immutable snapshot of health counters and timestamps
	 */
	public TelemetryHealthSnapshot getHealthSnapshot() {
		return healthState.snapshot();
	}

	/**
	 * Returns the mutable health state object for sharing with other telemetry components.
	 *
	 * <p>This method is intended for internal use to wire span export health into the shared health
	 * state. The returned object is thread-safe.
	 *
	 * @return the shared health state instance
	 */
	public TelemetryHealthState getHealthState() {
		return healthState;
	}

	/**
	 * Synchronously flushes all pending metrics to disk.
	 *
	 * <p>Useful for checkpointing before a crash report, and for driving tests that need to
	 * inspect exported metrics without waiting for the next periodic flush cycle.
	 *
	 * <p>Failures are logged and recorded in the health state but not thrown; callers can
	 * inspect {@link #getHealthSnapshot()} to detect flush failures.
	 */
	public void flush() {
		try {
			meterProvider.forceFlush().join(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			healthState.recordFlushFailure();
			log.warn("Explicit metrics flush failed", e);
		}
	}
}
