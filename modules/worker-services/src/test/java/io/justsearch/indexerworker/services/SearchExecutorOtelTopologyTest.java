package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 517 verification — OTel span topology preservation under the
 * Capture / Plan / Execute / Respond decomposition.
 *
 * <p>The redesign moved span creation from the monolithic {@code SearchOrchestrator}
 * into {@link io.justsearch.indexerworker.services.execute.SearchExecutor}. The
 * topology invariants from tempdoc §"Invariants preserved" item #12 + §A.3 must
 * hold post-refactor: {@code search/retrieval} parented to the request context,
 * {@code search/chunk_merge} a <i>sibling</i> of retrieval (not a child) under
 * the request context.
 *
 * <p>This test uses an SDK {@link CapturingSpanExporter} to assert the emitted
 * span shape directly, matching the pattern in
 * {@code TracingWorkflowSpanAttributeProcessorTest}.
 */
@DisplayName("SearchExecutor OTel span topology (tempdoc 517 invariant #12)")
final class SearchExecutorOtelTopologyTest {

  @Test
  @DisplayName("Sparse-only query emits search/retrieval span under the request context")
  void sparseOnlyRetrievalSpanParentedToRequestContext() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      // Create a synthetic request-level parent span so the retrieval span's
      // explicit-parent-context discipline is observable in the export.
      var tracer = GlobalOpenTelemetry.get().getTracer("test");
      Span requestSpan = tracer.spanBuilder("test.request").startSpan();
      try (Scope ignored = Context.current().with(requestSpan).makeCurrent()) {
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                    .build());
        assertNotNull(response, "search() should return a non-null response");
      } finally {
        requestSpan.end();
      }
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      SpanData retrieval = findSpan(exporter.spans, "search/retrieval");
      assertNotNull(retrieval, "search/retrieval span should be emitted");
      SpanData requestSpanData = findSpan(exporter.spans, "test.request");
      assertNotNull(requestSpanData, "synthetic test.request parent span should be emitted");
      assertEquals(
          requestSpanData.getSpanId(),
          retrieval.getParentSpanId(),
          "retrieval span must be parented to the request context (tempdoc invariant #12)");
      assertEquals(
          "TEXT",
          retrieval.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("search.mode")));
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("worker span carries record-derived attributes (553 4a-worker projection, tracing on)")
  void workerSpanCarriesRecordDerivedAttributes() throws Exception {
    // The live dev stack runs with tracing OFF, so the worker-span projection (a no-op without a
    // recording span) is only observable with a real recording tracer — exactly this in-memory SDK
    // setup. SearchResponseBuilder.build projects the worker trace slice onto Span.current(); within
    // this requestSpan scope (after the child execute spans close) that IS the request span, so the
    // exported request span must carry the record-derived justsearch.search.worker.* attributes.
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      var tracer = GlobalOpenTelemetry.get().getTracer("test");
      Span requestSpan = tracer.spanBuilder("test.request").startSpan();
      try (Scope ignored = Context.current().with(requestSpan).makeCurrent()) {
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                    .build());
        assertNotNull(response, "search() should return a non-null response");
      } finally {
        requestSpan.end();
      }
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      SpanData req = findSpan(exporter.spans, "test.request");
      assertNotNull(req, "synthetic test.request span should be emitted");
      assertEquals(
          "TEXT",
          req.getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey(
                  "justsearch.search.worker.effective_mode")),
          "worker span must carry the record-derived effective_mode (4a-worker projection)");
      assertEquals(
          "executed",
          req.getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey(
                  "justsearch.search.worker.stage.sparse-retrieval.status")),
          "worker span must carry the record-derived sparse-retrieval stage status");
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("search/retrieval span carries the OpenInference RETRIEVER projection (553 Phase A)")
  void retrievalSpanCarriesOpenInferenceDocuments() throws Exception {
    // The dev stack runs tracing OFF, so the per-leg OpenInference projection (a no-op without a
    // recording span) is only observable with a real recording tracer — this in-memory SDK setup is
    // the correct verification tier. A full real search must produce a search/retrieval span tagged
    // openinference.span.kind=RETRIEVER carrying the matched document (id + score) projected from the
    // leg result by OpenInferenceSpanProjection.
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-oi", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Hello")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertNotNull(response);
      assertTrue(response.getTotalHits() > 0, "the indexed doc should match");
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      SpanData retrieval = findSpan(exporter.spans, "search/retrieval");
      assertNotNull(retrieval, "search/retrieval span should be emitted");
      assertEquals(
          "RETRIEVER",
          retrieval
              .getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey("openinference.span.kind")),
          "retrieval span must be tagged as an OpenInference RETRIEVER");
      assertEquals(
          "doc-oi",
          retrieval
              .getAttributes()
              .get(
                  io.opentelemetry.api.common.AttributeKey.stringKey(
                      "retrieval.documents.0.document.id")),
          "retrieval span must carry the matched document id, projected from the leg result");
      assertNotNull(
          retrieval
              .getAttributes()
              .get(
                  io.opentelemetry.api.common.AttributeKey.doubleKey(
                      "retrieval.documents.0.document.score")),
          "retrieval span must carry the matched document score");
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("BM25-only via degraded hybrid still produces a single retrieval span")
  void multiLegBm25OnlyEmitsRetrievalSpan() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-2", "Lorem ipsum")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      // Hybrid request with no embedding service → dense leg fails → degraded BM25-only path.
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Lorem")
                  .setLimit(10)
                  .setPipeline(
                      PipelineConfig.newBuilder()
                          .setSparseEnabled(true)
                          .setDenseEnabled(true)
                          .build())
                  .build());
      assertNotNull(response);
      assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "hybrid fallback should fire for missing dense");
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      SpanData retrieval = findSpan(exporter.spans, "search/retrieval");
      assertNotNull(retrieval, "search/retrieval span should be emitted");
      // Tempdoc invariant #10: effectiveMode reflects what actually executed (not what was
      // requested). Degraded HYBRID → only BM25 → effective mode "TEXT".
      assertEquals(
          "TEXT",
          retrieval.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("search.mode")));
      assertHasAttribute(retrieval, "search.took_ms");
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("Empty-query decision emits no retrieval span")
  void emptyQueryDecisionEmitsNoRetrievalSpan() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-3", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertNotNull(response);
      assertEquals(0, response.getTotalHits());
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      // Empty query short-circuits in the planner before any executor IO — no retrieval span.
      assertNull(
          findSpan(exporter.spans, "search/retrieval"),
          "EmptyQueryDecision must not emit a retrieval span");
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // ============================================================
  // Helpers (duplicated from SearchOrchestratorComposablePathTest — see
  // tempdoc 517 §B.4 implementation decision: copy-paste pattern for test
  // isolation, no shared base class).
  // ============================================================

  private static SpanData findSpan(List<SpanData> spans, String name) {
    for (SpanData span : spans) {
      if (name.equals(span.getName())) return span;
    }
    return null;
  }

  private static void assertHasAttribute(SpanData span, String key) {
    Object value = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey(key));
    if (value == null) {
      value = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(key));
    }
    assertFalse(value == null, () -> "span " + span.getName() + " must have attribute " + key);
  }

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    try {
      return service.search(request, CallContext.none());
    } catch (WorkerServiceException e) {
      throw new RuntimeException("search() errored", e);
    }
  }

  private static RunningRuntime newLifecycleWithOneDoc(String docId, String content)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-otel-topology-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, docId,
                    SchemaFields.DOC_UID, docId + "#0",
                    SchemaFields.PATH, docId,
                    SchemaFields.CONTENT, content)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  /**
   * Minimal SDK exporter capturing all exported spans into an in-memory list.
   * Mirrors the {@code CapturingSpanExporter} pattern in {@code
   * TracingWorkflowSpanAttributeProcessorTest.java:58–77}.
   */
  private static final class CapturingSpanExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> exported) {
      this.spans.addAll(exported);
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
  }
}
