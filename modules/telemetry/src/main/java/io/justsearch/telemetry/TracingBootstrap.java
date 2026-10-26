/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TracingBootstrap implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(TracingBootstrap.class);
	private static TracingBootstrap indexingBootstrap;
	private final SdkTracerProvider tracerProvider;
	private final BatchSpanProcessor localSpanProcessor;
	private volatile boolean shutdown;
	private boolean processLifetime;

	/**
	 * Creates or reuses the process-owned tracing bootstrap for indexing pipeline profiling.
	 *
	 * <p>The first successful installation fixes the output directory and sampler for this JVM.
	 * Physical index shutdown only flushes spans; ordered Engine teardown closes the provider.
	 *
	 * @param dataDir the data directory for trace file output
	 * @param healthState health state for export monitoring (may be null)
	 * @param level tracing level: "sample" (1% ratio) or "detailed" (100%)
	 * @return the Engine-process owner, retained across physical index replacements
	 */
	public static synchronized TracingBootstrap forIndexing(
			Path dataDir, TelemetryHealthState healthState, String level) {
		if (indexingBootstrap != null) return indexingBootstrap;
		Sampler sampler = "sample".equals(level)
			? Sampler.traceIdRatioBased(0.01)
			: Sampler.alwaysOn();
		TracingBootstrap candidate = new TracingBootstrap(dataDir, healthState, sampler);
		candidate.processLifetime = true;
		indexingBootstrap = candidate;
		return candidate;
	}

	/**
	 * Creates a TracingBootstrap configured for the Head process (REST API, agent loop, search
	 * adapter). Tempdoc 518 Appendix G W4.2 — activates the existing head-side span-authoring
	 * code (which previously fired into the no-op {@code GlobalOpenTelemetry}) so the W2.2
	 * {@code justsearch.inference.generation} attribute attaches to exported spans.
	 *
	 * @param dataDir the data directory for trace file output
	 * @param healthState health state for export monitoring (may be null)
	 * @param level tracing level: "sample" (1% ratio) or "detailed" (100%); any other value
	 *     defaults to always-on
	 * @return a new TracingBootstrap with the appropriate sampler
	 */
	public static TracingBootstrap forHead(
			Path dataDir, TelemetryHealthState healthState, String level) {
		Sampler sampler = "sample".equals(level)
			? Sampler.traceIdRatioBased(0.01)
			: Sampler.alwaysOn();
		return new TracingBootstrap(dataDir, healthState, sampler);
	}

	public TracingBootstrap(Path dataDir) {
		this(dataDir, null);
	}

	public TracingBootstrap(Path dataDir, TelemetryHealthState healthState) {
		this(dataDir, healthState, Sampler.alwaysOn(), System.getenv());
	}

	public TracingBootstrap(Path dataDir, TelemetryHealthState healthState, Sampler sampler) {
		this(dataDir, healthState, sampler, System.getenv());
	}

	TracingBootstrap(
			Path dataDir, TelemetryHealthState healthState, Sampler sampler, Map<String, String> env) {
		this(new NdjsonSpanExporter(dataDir.resolve("telemetry").resolve("traces.ndjson"), healthState),
			sampler, env);
	}

	TracingBootstrap(SpanExporter exporter, Sampler sampler, Map<String, String> env) {
		localSpanProcessor = BatchSpanProcessor.builder(exporter).build();
		var builder = SdkTracerProvider.builder()
			.setSampler(sampler)
			.setResource(buildResource(env))
			.addSpanProcessor(localProcessorWithShutdownSignal());
		var workflowSpanProcessor = buildWorkflowSpanAttributeProcessor(env);
		if (workflowSpanProcessor != null) {
			builder.addSpanProcessor(workflowSpanProcessor);
		}
		// Tempdoc 518 Appendix F W2.2: tag every span with the current inference generation
		// (no-op until app-inference registers a supplier; sentinel -1 also skipped).
		builder.addSpanProcessor(new InferenceGenerationSpanProcessor());
		var otlpExporter = buildOptionalOtlpExporter(env);
		if (otlpExporter != null) {
			builder.addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build());
		}
		this.tracerProvider = builder.build();
		OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
			.setTracerProvider(tracerProvider)
			.setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
			.build();
		try {
			GlobalOpenTelemetry.set(sdk);
		} catch (RuntimeException | Error failure) {
			try {
				sdk.close();
			} catch (RuntimeException | Error cleanupFailure) {
				if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Index components only flush their process-owned provider when retiring. Global telemetry
	 * cannot be registered again during local recovery, so ordered Engine teardown closes it.
	 */
	@Override
	public void close() {
		if (processLifetime) flush();
		else tracerProvider.close();
	}

	/** Final teardown after all Engine span producers drain; never call during local recovery. */
	public static synchronized void shutdownIndexing() {
		if (indexingBootstrap == null) return;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		try {
			indexingBootstrap.flush(deadline);
			// Provider.close() has its own ten-second wait. Share the flush budget instead.
			indexingBootstrap.tracerProvider.shutdown()
				.join(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} finally {
			indexingBootstrap = null;
		}
	}

	/** Description of the sampler installed in this bootstrap's actual provider. */
	public String samplerDescription() {
		return tracerProvider.getSampler().getDescription();
	}

	/**
	 * Synchronously flushes all pending spans to disk.
	 *
	 * <p>Useful for driving tests that need to inspect exported traces without waiting for the
	 * next batch export cycle. Failures are logged but not thrown.
	 */
	public void flush() {
		flush(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
	}

	private void flush(long deadline) {
		try {
			if (shutdown || Thread.currentThread().isInterrupted()) return;
			var first = requestLocalFlush();
			if (first == null || !awaitFlush(first, deadline)) return;
			// BatchSpanProcessor 1.60.1:270-271 completes before retiring the request;
			// :300-310 can return that stale identity or the shared success sentinel.
			// A fresh generation covers pre-close ends, except the accepted SDK accounting
			// residual at :213-216 when another producer is between offer and increment.
			while (!shutdown && !Thread.currentThread().isInterrupted()) {
				long remaining = flushTimeRemaining(deadline);
				if (remaining <= 0) return;
				var next = requestLocalFlush();
				if (next == null) return;
				if (next != first && next != CompletableResultCode.ofSuccess()) {
					if (!awaitFlush(next, deadline)) return;
					// Preserve flushing of the other processors, including batched OTLP.
					if (shutdown || Thread.currentThread().isInterrupted()
							|| flushTimeRemaining(deadline) <= 0) return;
					if (awaitFlush(tracerProvider.forceFlush(), deadline)) {
						log.debug("Explicit span flush completed");
					}
					return;
				}
				remaining = flushTimeRemaining(deadline);
				if (remaining <= 0) return;
				TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MICROSECONDS.toNanos(100)));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			log.warn("Explicit span flush failed", e);
		}
	}

	private long flushTimeRemaining(long deadline) {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) log.warn("Explicit span flush expired after 5 seconds");
		return remaining;
	}

	private boolean awaitFlush(CompletableResultCode result, long deadline) {
		while (!shutdown && !Thread.currentThread().isInterrupted()) {
			long remaining = flushTimeRemaining(deadline);
			if (remaining <= 0) return false;
			if (result.isDone()) {
				if (!result.isSuccess()) log.warn("Explicit span flush failed");
				return result.isSuccess();
			}
			result.join(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
		}
		return false;
	}

	private CompletableResultCode requestLocalFlush() {
		synchronized (localSpanProcessor) {
			return shutdown ? null : localSpanProcessor.forceFlush();
		}
	}

	private SpanProcessor localProcessorWithShutdownSignal() {
		// The SDK exposes no shutdown query. Coordinate requests with provider-driven shutdown
		// before it can retire the worker, and let callers already waiting notice promptly.
		return new SpanProcessor() {
			@Override
			public void onStart(Context parent, ReadWriteSpan span) {
				localSpanProcessor.onStart(parent, span);
			}

			@Override
			public boolean isStartRequired() { return localSpanProcessor.isStartRequired(); }

			@Override
			public void onEnd(ReadableSpan span) { localSpanProcessor.onEnd(span); }

			@Override
			public boolean isEndRequired() { return localSpanProcessor.isEndRequired(); }

			@Override
			public CompletableResultCode forceFlush() {
				var result = requestLocalFlush();
				return result == null ? CompletableResultCode.ofSuccess() : result;
			}

			@Override
			public CompletableResultCode shutdown() {
				synchronized (localSpanProcessor) {
					shutdown = true;
				}
				return localSpanProcessor.shutdown();
			}
		};
	}

	private static SpanExporter buildOptionalOtlpExporter(Map<String, String> env) {
		String endpoint = resolveTracesEndpoint(env);
		if (endpoint == null || endpoint.isBlank()) {
			return null;
		}
		try {
			var builder = OtlpHttpSpanExporter.builder()
				.setEndpoint(endpoint);
			Duration timeout = resolveTimeout(env);
			if (timeout != null) {
				builder.setTimeout(timeout);
			}
			Map<String, String> headers = resolveHeaders(env);
			headers.forEach(builder::addHeader);
			log.info("OTLP trace fan-out enabled: {}", endpoint);
			return builder.build();
		} catch (RuntimeException e) {
			log.warn("Failed to initialize OTLP trace exporter", e);
			return null;
		}
	}

	private static String resolveTracesEndpoint(Map<String, String> env) {
		String tracesEndpoint = trimToNull(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
		if (tracesEndpoint != null) {
			return tracesEndpoint;
		}
		String genericEndpoint = trimToNull(env.get("OTEL_EXPORTER_OTLP_ENDPOINT"));
		if (genericEndpoint == null) {
			return null;
		}
		if (genericEndpoint.endsWith("/")) {
			return genericEndpoint + "v1/traces";
		}
		if (genericEndpoint.endsWith("/v1/traces")) {
			return genericEndpoint;
		}
		return genericEndpoint + "/v1/traces";
	}

	private static Duration resolveTimeout(Map<String, String> env) {
		String raw = trimToNull(env.get("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT"));
		if (raw == null) {
			raw = trimToNull(env.get("OTEL_EXPORTER_OTLP_TIMEOUT"));
		}
		if (raw == null) {
			return null;
		}
		try {
			return Duration.ofMillis(Long.parseLong(raw));
		} catch (NumberFormatException e) {
			log.warn("Ignoring invalid OTLP timeout '{}'", raw);
			return null;
		}
	}

	private static Map<String, String> resolveHeaders(Map<String, String> env) {
		String raw = trimToNull(env.get("OTEL_EXPORTER_OTLP_TRACES_HEADERS"));
		if (raw == null) {
			raw = trimToNull(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
		}
		Map<String, String> headers = new LinkedHashMap<>();
		if (raw == null) {
			return headers;
		}
		for (String part : raw.split(",")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = trimToNull(part.substring(0, eq));
			String value = trimToNull(part.substring(eq + 1));
			if (key != null && value != null) {
				headers.put(key, value);
			}
		}
		return headers;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Resource buildResource(Map<String, String> env) {
		var builder = Attributes.builder();
		putIfPresent(builder, "justsearch.workflow.run_id", env.get("JUSTSEARCH_WORKFLOW_RUN_ID"));
		putIfPresent(builder, "justsearch.workflow.family", env.get("JUSTSEARCH_WORKFLOW_FAMILY"));
		return Resource.getDefault().merge(Resource.create(builder.build()));
	}

	static SpanProcessor buildWorkflowSpanAttributeProcessor(Map<String, String> env) {
		String workflowRunId = trimToNull(env.get("JUSTSEARCH_WORKFLOW_RUN_ID"));
		String workflowFamily = trimToNull(env.get("JUSTSEARCH_WORKFLOW_FAMILY"));
		if (workflowRunId == null && workflowFamily == null) {
			return null;
		}
		return new WorkflowSpanAttributeProcessor(workflowRunId, workflowFamily);
	}

	private static void putIfPresent(AttributesBuilder builder, String key, String value) {
		String trimmed = trimToNull(value);
		if (trimmed != null) {
			builder.put(AttributeKey.stringKey(key), trimmed);
		}
	}
}
