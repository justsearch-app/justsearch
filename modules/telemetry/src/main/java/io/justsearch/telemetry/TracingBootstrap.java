/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
	private final SdkTracerProvider tracerProvider;

	/**
	 * Creates a TracingBootstrap configured for indexing pipeline profiling.
	 *
	 * @param dataDir the data directory for trace file output
	 * @param healthState health state for export monitoring (may be null)
	 * @param level tracing level: "sample" (1% ratio) or "detailed" (100%)
	 * @return a new TracingBootstrap with the appropriate sampler
	 */
	public static TracingBootstrap forIndexing(
			Path dataDir, TelemetryHealthState healthState, String level) {
		Sampler sampler = "sample".equals(level)
			? Sampler.traceIdRatioBased(0.01)
			: Sampler.alwaysOn();
		return new TracingBootstrap(dataDir, healthState, sampler);
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
		var tracesFile = dataDir.resolve("telemetry").resolve("traces.ndjson");
		var exporter = new NdjsonSpanExporter(tracesFile, healthState);
		var builder = SdkTracerProvider.builder()
			.setSampler(sampler)
			.setResource(buildResource(env))
			.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
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

	@Override
	public void close() {
		tracerProvider.close();
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
		try {
			tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Explicit span flush failed", e);
		}
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
