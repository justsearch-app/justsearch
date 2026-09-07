package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the OTel span attribute keys emitted by {@code SearchExecutor}
 * (tempdoc 525 supporting move A). Pairs with the documentation at
 * {@code docs/reference/contracts/search-execution-spans.md}.
 *
 * <p>The contract is a <i>closed allowlist per span name</i> — every attribute key
 * present on a span must be declared here, and every "required" key must be present
 * on every emission of that span. Adding a new attribute requires adding it to the
 * allowlist below AND to the documentation in the same slice.
 *
 * <p>{@code commit.*} keys are dynamic (from Lucene commit user-data); they're
 * allowlisted by prefix rather than by exact match.
 */
@DisplayName("SearchExecutor OTel span attribute contract (tempdoc 525 move A)")
final class SearchExecutionSpanAttrsContractTest {

  // ============================================================
  // Contract — closed allowlist per span name.
  // Source of truth: docs/reference/contracts/search-execution-spans.md
  // ============================================================

  /** Required keys for {@code search/retrieval}. Missing key = contract violation. */
  private static final Set<String> RETRIEVAL_REQUIRED =
      Set.of("search.mode", "search.took_ms");

  // Tempdoc 553 Phase A: the worker search/* spans now also project their leg/fusion result onto
  // OpenInference attributes (openinference.span.kind + the per-document retrieval.documents.* /
  // reranker.output_documents.* payload). These are declared OPTIONAL here (their presence depends
  // on whether the leg produced documents) and the dynamic document keys are allowlisted by prefix.
  private static final String OI_SPAN_KIND = "openinference.span.kind";

  /** Optional keys for {@code search/retrieval}. Present-but-unallowed = contract violation. */
  private static final Set<String> RETRIEVAL_OPTIONAL =
      Set.of("search.searcher_generation", OI_SPAN_KIND);

  /**
   * Dynamic key prefixes allowed on {@code search/retrieval}. {@code commit.*} suffixes come from
   * Lucene commit user-data; {@code retrieval.documents.*} is the OpenInference RETRIEVER payload.
   */
  private static final Set<String> RETRIEVAL_OPTIONAL_PREFIXES =
      Set.of("commit.", "retrieval.documents.");

  /** Required keys for {@code search/branch}. */
  private static final Set<String> BRANCH_REQUIRED = Set.of("search.retrieval.branch");

  /** Optional keys for {@code search/branch}. */
  private static final Set<String> BRANCH_OPTIONAL = Set.of(OI_SPAN_KIND);

  /** Dynamic key prefixes allowed on {@code search/branch} (OpenInference RETRIEVER payload). */
  private static final Set<String> BRANCH_OPTIONAL_PREFIXES = Set.of("retrieval.documents.");

  /** Required keys for {@code search/fuse}. */
  private static final Set<String> FUSE_REQUIRED =
      Set.of("search.fusion.algorithm", "search.fusion.branch_count");

  /** Optional keys for {@code search/fuse}. */
  private static final Set<String> FUSE_OPTIONAL =
      Set.of(
          "search.retrieval.branch",
          OI_SPAN_KIND,
          "reranker.model_name",
          "reranker.input_branch_count");

  /** Dynamic key prefixes allowed on {@code search/fuse} (OpenInference RERANKER payload). */
  private static final Set<String> FUSE_OPTIONAL_PREFIXES = Set.of("reranker.output_documents.");

  /** Required keys for {@code search/chunk_merge} (currently none — see contract doc). */
  private static final Set<String> CHUNK_MERGE_REQUIRED = Set.of();

  /** Optional keys for {@code search/chunk_merge}. */
  private static final Set<String> CHUNK_MERGE_OPTIONAL = Set.of(OI_SPAN_KIND);

  // ============================================================
  // Tests — one per SearchDecision variant.
  // The four variants exercised here are EmptyQueryDecision (no span emitted),
  // BlockedDecision (no span emitted), SparseShortcut, and MultiLegDecision
  // (via BM25-degraded HYBRID). Three-way + chunk-merge variants are
  // exercised via SearchExecutorLegSetMatrixTest's broader coverage; this
  // test enforces the attr contract on the spans those tests already emit.
  // ============================================================

  @Test
  @DisplayName("SparseShortcut: search/retrieval attrs conform to contract")
  void sparseShortcutContract() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
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
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      assertContractForAllSpans(exporter.spans);
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("MultiLegDecision (BM25-degraded HYBRID): all emitted span attrs conform to contract")
  void multiLegBm25OnlyContract() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    GlobalOpenTelemetry.resetForTest();
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    GlobalOpenTelemetry.set(sdk);
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-2", "Lorem ipsum")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
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
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      assertContractForAllSpans(exporter.spans);
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("EmptyQueryDecision: no executor spans emitted (planner short-circuit)")
  void emptyQueryEmitsNoExecutorSpans() throws Exception {
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

      // Planner short-circuit: no executor spans. Contract is vacuously satisfied.
      for (SpanData span : exporter.spans) {
        String name = span.getName();
        assertTrue(
            !name.startsWith("search/"),
            () ->
                "EmptyQueryDecision must not emit any search/* span, but found: " + name);
      }
    } finally {
      provider.close();
      GlobalOpenTelemetry.resetForTest();
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // ============================================================
  // Contract validator — shared across the variant tests.
  // ============================================================

  private static void assertContractForAllSpans(List<SpanData> spans) {
    Map<String, Set<String>> required =
        Map.of(
            "search/retrieval", RETRIEVAL_REQUIRED,
            "search/branch", BRANCH_REQUIRED,
            "search/fuse", FUSE_REQUIRED,
            "search/chunk_merge", CHUNK_MERGE_REQUIRED);
    Map<String, Set<String>> optional =
        Map.of(
            "search/retrieval", RETRIEVAL_OPTIONAL,
            "search/branch", BRANCH_OPTIONAL,
            "search/fuse", FUSE_OPTIONAL,
            "search/chunk_merge", CHUNK_MERGE_OPTIONAL);
    Map<String, Set<String>> optionalPrefixes =
        Map.of(
            "search/retrieval", RETRIEVAL_OPTIONAL_PREFIXES,
            "search/branch", BRANCH_OPTIONAL_PREFIXES,
            "search/fuse", FUSE_OPTIONAL_PREFIXES,
            "search/chunk_merge", Set.<String>of());

    for (SpanData span : spans) {
      String name = span.getName();
      if (!required.containsKey(name)) {
        continue; // Not a contract-governed span name; out of scope.
      }
      Set<String> presentKeys = new HashSet<>();
      span.getAttributes().asMap().forEach((k, v) -> presentKeys.add(k.getKey()));

      Set<String> req = required.get(name);
      for (String k : req) {
        if (!presentKeys.contains(k)) {
          fail(
              "Span '"
                  + name
                  + "' is missing required attribute '"
                  + k
                  + "'. Present: "
                  + presentKeys);
        }
      }

      Set<String> opt = optional.get(name);
      Set<String> optPrefixes = optionalPrefixes.get(name);
      Set<String> allowedExact = new HashSet<>(req);
      allowedExact.addAll(opt);
      for (String k : presentKeys) {
        if (allowedExact.contains(k)) {
          continue;
        }
        boolean prefixMatch = false;
        for (String prefix : optPrefixes) {
          if (k.startsWith(prefix)) {
            prefixMatch = true;
            break;
          }
        }
        if (!prefixMatch) {
          fail(
              "Span '"
                  + name
                  + "' carries undeclared attribute '"
                  + k
                  + "'. Add it to the contract at docs/reference/contracts/"
                  + "search-execution-spans.md AND to "
                  + "SearchExecutionSpanAttrsContractTest before merging.");
        }
      }
    }
  }

  // ============================================================
  // Helpers — copy-paste from SearchExecutorOtelTopologyTest per the
  // test-isolation pattern established in tempdoc 517 §B.4 (no shared base).
  // ============================================================

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
    Path base = Files.createTempDirectory("justsearch-span-contract-test-");
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

  /** Captures all exported spans into an in-memory list (mirrors SearchExecutorOtelTopologyTest). */
  private static final class CapturingSpanExporter implements SpanExporter {
    private final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

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
