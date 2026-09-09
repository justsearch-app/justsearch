/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NdjsonSpanExporter implements SpanExporter {
  private static final Logger log = LoggerFactory.getLogger(NdjsonSpanExporter.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final Set<String> ALLOWED_ATTRS = Set.of(
      // pipeline_hash + budget_profile retired by tempdoc 400 LR2-d (orphan per ADR 0014).
      "pipeline_name", "stage_id", "reason_code",
      "justsearch.workflow.run_id", "justsearch.workflow.family",
      // Tempdoc 518 Appendix F W2.2: inference generation counter, attached at span-start
      // by InferenceGenerationSpanProcessor when a runner is registered.
      "justsearch.inference.generation",
      // Tempdoc 518 Appendix G S3: inference.transition span attributes emitted by
      // TransitionRunner.run around the transition body.
      "inference.from_phase",
      "inference.to_phase",
      "inference.reason",
      "inference.success",
      "inference.wire_code",
      // Tempdoc 518 Appendix G Wave A.2: per-request HTTP spans emitted by LocalApiServer's
      // global before/after hooks. Names mirror OTel semantic conventions for HTTP servers.
      "http.method",
      "http.route",
      "http.target",
      "http.status_code",
      // Coarse request attribution projected from the cached EngineContext by LocalApiServer.
      // Client, session and grant identifiers are intentionally not exported.
      "engine.originator",
      "engine.transport",
      // Agent OTel spans (gen_ai semantic conventions)
      "gen_ai.operation.name",
      "gen_ai.agent.id",
      "gen_ai.agent.name",
      "gen_ai.conversation.id",
      "gen_ai.tool.name",
      "gen_ai.tool.call.id",
      "gen_ai.usage.input_tokens",
      "gen_ai.usage.output_tokens",
      // Indexing pipeline spans (312 Phase 0)
      "batch.polled", "batch.extracted",
      "embed.batch_size", "embed.gpu", "embed.success", "embed.error",
      "doc.path", "doc.size_bytes",
      "embedding.source",
      "paths.count",
      // Search pipeline spans (250 Phase 5c)
      "search.mode",
      "search.query_length",
      "search.total_hits",
      "search.took_ms",
      // Commit-metadata identity attrs (400 LR2-d.2) — replaces the retired
      // pipeline_hash/budget_profile slot. Sourced from the Worker's cached
      // commit metadata snapshot; governs runtime index identity.
      "commit.schema_fp",
      "commit.field_catalog_hash",
      "commit.synonyms_hash",
      "commit.grammar_hash",
      "commit.similarity_fp",
      "commit.boosts_fp",
      "commit.index_fingerprint",
      // Per-ORT-call span attrs (400 LR2-a). Emitted on encoder.ort_run spans
      // produced by OnnxEmbeddingEncoder, SpladeEncoder, BertNerInference,
      // and BgeM3Encoder around each ai.onnxruntime.OrtSession#run call.
      "encoder.name",
      "encoder.gpu",
      "encoder.batch_size",
      "encoder.seq_len",
      // Lease-acquisition span attrs (400 LR2-b). Emitted on lease.acquire
      // spans produced inside NativeSessionHandle.acquire() around the
      // gpuInferenceSemaphore wait.
      "lease.mode",
      "lease.wait_queue_depth",
      // CPU fallback attrs (400 LR2-c) live in ALLOWED_EVENT_ATTRS only —
      // they're emitted via Span.addEvent (NativeSessionHandle:397, EncoderOrtRunSpans:107),
      // never as span-level attrs. Cleanup of the pre-P6 duplication noted at
      // lines ~119-122 of this file.
      // Search-branch span attrs (400 LR2-e). Emitted on search/branch spans
      // produced inside SearchOrchestrator's 3-way virtual-thread fan-out.
      "search.retrieval.branch",
      // Cross-encoder span attrs (400 §22 Issue D split LR2-e.2). Emitted
      // on search/rerank spans around the CE invocation in
      // RagContextOps.chunkRerank. Records the candidate count routed to
      // the CE so Layer-4 projections can surface reranker throughput.
      "search.ce.scored",
      // Fusion span attrs (400 §22 Issue D split LR2-e.3). Emitted on
      // search/fuse spans around HybridFusionUtils call sites. Records
      // the fusion algorithm (rrf/cc) + per-branch contribution count.
      "search.fusion.algorithm",
      "search.fusion.branch_count",
      // Searcher generation attr (400 §22 Issue D split LR2-e.4). Emitted
      // on search/retrieval spans when the Lucene IndexSearcher's
      // generation is available; lets Layer-4 detect cross-commit drift.
      "search.searcher_generation",
      // OpenInference span content (tempdoc 553 Phase A). The worker search/* spans project
      // their leg/fusion result onto OpenInference RETRIEVER/RERANKER attributes via
      // OpenInferenceSpanProjection, for Phoenix/Tempo interop. The per-document keys
      // (retrieval.documents.*, reranker.output_documents.*) are dynamic/indexed and are
      // allowlisted by prefix below; these are the fixed scalar keys.
      "openinference.span.kind",
      "reranker.model_name",
      "reranker.input_branch_count");

  // Tempdoc 553 Phase A: prefix allowlist for the dynamic, indexed OpenInference document keys
  // (e.g. retrieval.documents.0.document.id / .score / .content). Kept separate from the exact-key
  // ALLOWED_ATTRS so the document schema can evolve independently. Carries per-document content per
  // the owner-amended span-privacy contract (docs/reference/contracts/search-execution-spans.md).
  private static final Set<String> ALLOWED_ATTR_PREFIXES = Set.of(
      "retrieval.documents.", "reranker.output_documents.");

  // Tempdoc 402 P6: allowlist of span event names. Events whose name is not
  // in this set are dropped during export — keeps the NDJSON schema under
  // control and prevents leaking arbitrary addEvent() calls (like the
  // "skip" events on pipeline.stage.rerank spans) into consumer projections.
  // Consumers:
  //   - contract.violation → contract_violations.py (tempdoc 402 LR6-c)
  //   - cpu_fallback.triggered → cpu_fallback_counts.py (tempdoc 400 LR2-c)
  //
  // cpu_fallback.triggered was added alongside the P6 cleanup pass: the
  // exporter had never emitted events, so events produced by
  // NativeSessionHandle#reportCpuSessionFailure +
  // EncoderOrtRunSpans#emitCpuFallbackEvent were silently dropped since the
  // feature shipped. Extending the allowlist here closes that pre-existing
  // silent-failure with a one-line addition — same bug class P6 exists to fix.
  private static final Set<String> ALLOWED_EVENT_NAMES = Set.of(
      "contract.violation", "cpu_fallback.triggered");

  // Tempdoc 402 P6: allowlist of event-level attrs. Separate from
  // ALLOWED_ATTRS (span-level) to keep event-schema evolution independent
  // from span-schema evolution. A span attr with the same key would not
  // pass this filter, and vice versa — deliberate.
  //
  // Contract.* attrs consumed by contract_violations.py.
  // Fallback.* attrs consumed by cpu_fallback_counts.py (canonical home;
  // the pre-P6 duplicate listing in ALLOWED_ATTRS was removed per
  // observations.md cleanup).
  private static final Set<String> ALLOWED_EVENT_ATTRS = Set.of(
      "contract.tempdoc", "contract.tier", "contract.description",
      "fallback.cause", "fallback.encoder");

  private static final long DISK_CRITICAL_BYTES = 200L * 1024 * 1024;
  private static final long DISK_WARNING_BYTES = 1024L * 1024 * 1024;

  private final Path tracesFile;
  private final TelemetryHealthState healthState;
  private final long rotateMaxBytes;
  private final int retentionDays;

  NdjsonSpanExporter(Path tracesFile) {
    this(tracesFile, null);
  }

  NdjsonSpanExporter(Path tracesFile, TelemetryHealthState healthState) {
    this.tracesFile = tracesFile;
    this.healthState = healthState;
    long defaultMaxMb = 10;
    String maxMbStr = System.getProperty("justsearch.telemetry.traces.max_mb",
        System.getenv().getOrDefault("JUSTSEARCH_TELEMETRY_TRACES_MAX_MB",
            Long.toString(defaultMaxMb)));
    long maxMb;
    try { maxMb = Long.parseLong(maxMbStr); } catch (NumberFormatException e) { maxMb = defaultMaxMb; }
    this.rotateMaxBytes = Math.max(1, maxMb) * 1024 * 1024;
    int defaultDays = 7;
    String daysStr = System.getProperty("justsearch.telemetry.traces.retention.days",
        System.getenv().getOrDefault("JUSTSEARCH_TELEMETRY_TRACES_RETENTION_DAYS",
            Integer.toString(defaultDays)));
    int days;
    try { days = Integer.parseInt(daysStr); } catch (NumberFormatException e) { days = defaultDays; }
    this.retentionDays = Math.max(1, days);
    try {
      Files.createDirectories(tracesFile.getParent());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create traces directory", e);
    }
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (spans == null || spans.isEmpty()) return CompletableResultCode.ofSuccess();
    if (!hasSufficientDiskSpace()) return CompletableResultCode.ofSuccess();
    rotateIfNeeded();
    try {
      StringBuilder sb = new StringBuilder();
      for (SpanData sd : spans) {
        String start = ISO.format(Instant.ofEpochMilli(sd.getStartEpochNanos() / 1_000_000).atOffset(ZoneOffset.UTC));
        String end = ISO.format(Instant.ofEpochMilli(sd.getEndEpochNanos() / 1_000_000).atOffset(ZoneOffset.UTC));
        // Tempdoc 400 §23.8 D-1: emit duration_ms as a first-class structural
        // field sourced from the OTel SDK's nanosecond-precision timestamps
        // (start/end are ms-truncated ISO strings; re-parsing them loses
        // sub-ms precision for fast encoder calls). Keeps LR4-g's PSI
        // over encoder.ort_run span durations sharp. Consumers that do
        // not need duration ignore the field; the LR4-g projection reads
        // it with a start/end fallback for backward compatibility with
        // traces.ndjson files produced before this commit landed.
        double durationMs =
            Math.max(0L, sd.getEndEpochNanos() - sd.getStartEpochNanos()) / 1_000_000.0;
        String attrs = java.util.stream.Stream.concat(
                sd.getResource().getAttributes().asMap().entrySet().stream(),
                sd.getAttributes().asMap().entrySet().stream())
            .filter(e -> isAllowedAttr(e.getKey().getKey()))
            .map(e -> "\"" + json(e.getKey().getKey()) + "\":\"" + json(String.valueOf(e.getValue())) + "\"")
            .collect(Collectors.joining(","));
        // Tempdoc 402 P6: emit span events alongside attrs. Consumer
        // `contract_violations.py` reads span["events"] expecting a list of
        // {"name": ..., "attrs": {...}}. Events whose name is not on
        // ALLOWED_EVENT_NAMES are dropped; event attrs are filtered through
        // ALLOWED_EVENT_ATTRS. Empty events array is emitted for schema
        // stability (consumers don't need null-coalescing).
        String events = sd.getEvents().stream()
            .filter(ev -> ALLOWED_EVENT_NAMES.contains(ev.getName()))
            .map(ev -> {
              String eventAttrs = ev.getAttributes().asMap().entrySet().stream()
                  .filter(e -> ALLOWED_EVENT_ATTRS.contains(e.getKey().getKey()))
                  .map(e -> "\"" + json(e.getKey().getKey()) + "\":\""
                      + json(String.valueOf(e.getValue())) + "\"")
                  .collect(Collectors.joining(","));
              return "{\"name\":\"" + json(ev.getName()) + "\",\"attrs\":{" + eventAttrs + "}}";
            })
            .collect(Collectors.joining(","));
        sb.append('{')
          .append("\"trace_id\":\"").append(sd.getTraceId()).append("\",")
          .append("\"span_id\":\"").append(sd.getSpanId()).append("\",")
          .append("\"parent_span_id\":\"").append(sd.getParentSpanId()).append("\",")
          .append("\"name\":\"").append(json(sd.getName())).append("\",")
          .append("\"start\":\"").append(start).append("\",")
          .append("\"end\":\"").append(end).append("\",")
          .append("\"duration_ms\":").append(durationMs).append(',')
          .append("\"status\":\"").append(sd.getStatus().getStatusCode().name()).append("\",")
          .append("\"attrs\":{").append(attrs).append("},")
          .append("\"events\":[").append(events).append(']')
          .append('}').append('\n');
      }
      Files.writeString(tracesFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      if (healthState != null) {
        healthState.recordSpanExportSuccess();
      }
      pruneByRetention();
    } catch (Exception e) {
      // Best-effort local exporter: log and record failure but don't disturb app flows
      if (healthState != null) {
        healthState.recordSpanExportFailure();
      }
      log.warn("Span export failed (best-effort): {}", e.getMessage());
    }
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  /** True if a span attribute key is allowed (exact-match or one of the dynamic prefixes). */
  private static boolean isAllowedAttr(String key) {
    if (ALLOWED_ATTRS.contains(key)) {
      return true;
    }
    for (String prefix : ALLOWED_ATTR_PREFIXES) {
      if (key.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  // Tempdoc 727-followup: escape the full JSON string-escape set (RFC 8259 §7), not just
  // backslash/quote. A prior version only escaped `\` and `"`; any attribute value containing a raw
  // newline (e.g. multi-paragraph document content captured by OpenInferenceSpans.reranker on
  // search/rerank spans) split one logical NDJSON record across multiple physical lines, breaking
  // per-line JSON parsing downstream (422/10,494 lines unparseable in a captured traces.ndjson,
  // in 24 contiguous ranges — all attributable to unescaped control characters).
  private static String json(String s) {
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  private void rotateIfNeeded() {
    try {
      if (Files.exists(tracesFile)) {
        long size = Files.size(tracesFile);
        if (size >= rotateMaxBytes) {
          String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
              .format(ZonedDateTime.now(ZoneOffset.UTC));
          Path rolled = tracesFile.getParent().resolve("traces." + ts + ".ndjson");
          Files.move(tracesFile, rolled);
        }
      }
    } catch (Exception e) {
      // best-effort rotation: log but don't crash
      log.warn("Traces file rotation failed (best-effort): {}", e.getMessage());
    }
  }

  private boolean hasSufficientDiskSpace() {
    try {
      FileStore store = Files.getFileStore(tracesFile.getParent());
      long usable = store.getUsableSpace();
      if (usable < DISK_CRITICAL_BYTES) {
        if (healthState != null) healthState.recordDiskSpaceLowEvent();
        log.error(
            "Disk space critically low ({} MB) — trace export suppressed",
            usable / (1024 * 1024));
        return false;
      }
      if (usable < DISK_WARNING_BYTES) {
        log.warn(
            "Disk space low ({} MB) — trace export continuing", usable / (1024 * 1024));
      }
      return true;
    } catch (IOException e) {
      return true; // optimistic on failure
    }
  }

  private void pruneByRetention() {
    try {
      var dir = tracesFile.getParent();
      var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
      DiagnosticFileRetention.pruneBefore(dir, "traces.", cutoff);
    } catch (Exception e) {
      log.warn("Traces retention pruning failed (best-effort): {}", e.getMessage());
    }
  }
}
