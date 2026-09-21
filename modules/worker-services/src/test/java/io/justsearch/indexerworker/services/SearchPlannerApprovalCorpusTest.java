package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.services.input.BgeM3Encoding;
import io.justsearch.indexerworker.services.input.CorpusCapabilities;
import io.justsearch.indexerworker.services.input.EmbeddingCompatBoundary;
import io.justsearch.indexerworker.services.input.EncodingResults;
import io.justsearch.indexerworker.services.input.QppMetrics;
import io.justsearch.indexerworker.services.input.SearchInputs;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import io.justsearch.indexerworker.services.plan.ChunkMergeDirective;
import io.justsearch.indexerworker.services.plan.LegSet;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.indexerworker.services.plan.SearchPlanner;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Planner approval-test corpus (tempdoc 525 supporting move C).
 *
 * <p>Locks in {@link SearchPlanner}'s decision shape for a corpus of synthetic
 * {@link SearchInputs} fixtures. Mirrors the {@code captureOrVerify} pattern
 * from {@code HealthEventSchemaTest} / {@code SubstrateSchemaGenTest}.
 *
 * <p>Capture: writes a canonical JSON projection of the {@link SearchDecision}
 * to {@code SSOT/schemas/search-decisions/&lt;scenario&gt;.v1.json} on first
 * run; subsequent runs verify the planner output matches the baseline. PR diffs
 * against these baselines are the planner-behaviour review surface.
 *
 * <p>The capture format is the decision's {@code summary()} map enriched with
 * variant-specific structural fields ({@code retrievalLimit}, {@code runtimeSyntax},
 * leg-specific config). This is intentionally a focused projection — the wire
 * {@link io.justsearch.ipc.SearchIntrospection} aggregate test lives elsewhere.
 *
 * <p>Scenarios: each of the 4 {@link SearchDecision} variants × representative
 * {@link LegSet} shapes for {@code MultiLegDecision}.
 */
@DisplayName("SearchPlanner approval-test corpus (tempdoc 525 move C)")
final class SearchPlannerApprovalCorpusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path schemasDir;
  private static SearchPlanner planner;

  @BeforeAll
  static void setup() {
    schemasDir = resolveSchemasDir().resolve("search-decisions");
    planner = newPlanner(true, true);
  }

  // ============================================================
  // Scenarios — one per decision variant + representative LegSet shapes.
  // ============================================================

  @Test
  @DisplayName("empty-query: blank query, sparse pipeline → EmptyQueryDecision")
  void emptyQuery() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "",
            PipelineConfig.newBuilder().setSparseEnabled(true).build(),
            allNotRequestedEncoding(),
            true);
    captureOrVerify("empty-query.v1.json", inputs);
  }

  @Test
  @DisplayName("blocked-vector: vector-only request with compat-blocked → BlockedDecision")
  void blockedVector() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "anything",
            PipelineConfig.newBuilder().setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Failed(SearchReasonCode.LEGACY_INDEX_NO_FINGERPRINT),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            false);
    captureOrVerify("blocked-vector.v1.json", inputs);
  }

  @Test
  @DisplayName("sparse-shortcut: sparse-only request, non-blank query → SparseShortcut")
  void sparseShortcut() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "hello world",
            PipelineConfig.newBuilder().setSparseEnabled(true).build(),
            allNotRequestedEncoding(),
            true);
    captureOrVerify("sparse-shortcut.v1.json", inputs);
  }

  @Test
  @DisplayName("multi-leg bm25-only (degraded hybrid): vector failed → MultiLegDecision(Bm25Only)")
  void multiLegBm25OnlyDegraded() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "machine learning",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Failed(SearchReasonCode.NO_EMBEDDING_SERVICE),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true);
    captureOrVerify("multi-leg-bm25-only-degraded.v1.json", inputs);
  }

  @Test
  @DisplayName("multi-leg bm25+dense: hybrid with successful encode → MultiLegDecision(Bm25Dense)")
  void multiLegBm25Dense() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "neural retrieval",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true);
    captureOrVerify("multi-leg-bm25-dense.v1.json", inputs);
  }

  @Test
  @DisplayName("multi-leg splade-only: splade pipeline → MultiLegDecision(SpladeOnly)")
  void multiLegSpladeOnly() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "sparse encoding",
            PipelineConfig.newBuilder().setSpladeEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.NotRequested(),
                new SpladeEncoding.Success(Map.of("encoding", 0.7f, "sparse", 0.5f)),
                new BgeM3Encoding.NotRequested()),
            true);
    captureOrVerify("multi-leg-splade-only.v1.json", inputs);
  }

  @Test
  @DisplayName("multi-leg three-way: bm25+dense+splade all success → MultiLegDecision(ThreeWay)")
  void multiLegThreeWay() throws IOException {
    SearchInputs inputs =
        baseInputs(
            "hybrid retrieval evaluation",
            PipelineConfig.newBuilder()
                .setSparseEnabled(true)
                .setDenseEnabled(true)
                .setSpladeEnabled(true)
                .build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.5f, 0.5f, 0.5f, 0.5f), "bgem3"),
                new SpladeEncoding.Success(Map.of("hybrid", 0.6f, "retrieval", 0.4f)),
                new BgeM3Encoding.NotRequested()),
            true);
    captureOrVerify("multi-leg-three-way.v1.json", inputs);
  }

  @Test
  @DisplayName("common analyzed terms skip only the redundant dense leg")
  void commonTermsSkipRedundantDenseLeg() {
    SearchInputs inputs =
        baseInputs(
            "common term",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true,
            new QppMetrics(0f, 0f, 1f, 200L, 0.50f));

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.Bm25Only.class, decision.legs());
    assertEquals(
        Optional.of(SearchReasonCode.SKIPPED_NO_DISCRIMINATIVE_TERM),
        decision.denseSkipReason());
    assertTrue(decision.hybridFallback().isEmpty(), "a planned skip is not an encoding failure");
    ChunkMergeDirective.EligibleApply merge =
        assertInstanceOf(ChunkMergeDirective.EligibleApply.class, decision.chunkMerge());
    assertNull(merge.inputs().chunkQueryVector(), "skipped dense must not leak into chunk retrieval");
  }

  @Test
  @DisplayName("one discriminative analyzed term retains dense")
  void discriminativeTermRetainsDenseLeg() {
    SearchInputs inputs =
        baseInputs(
            "common rare",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true,
            new QppMetrics(2f, 1f, 0.6f, 200L, 0.01f));

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.Bm25Dense.class, decision.legs());
    assertTrue(decision.denseSkipReason().isEmpty());
    ChunkMergeDirective.EligibleApply merge =
        assertInstanceOf(ChunkMergeDirective.EligibleApply.class, decision.chunkMerge());
    assertEquals(
        List.of(0.1f, 0.2f, 0.3f, 0.4f),
        merge.inputs().chunkQueryVector(),
        "a retained dense leg must carry its vector into chunk retrieval");
  }

  @Test
  @DisplayName("tiny corpora never use document frequency to skip dense")
  void tinyCorpusRetainsDenseLeg() {
    SearchInputs inputs =
        baseInputs(
            "common term",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true,
            new QppMetrics(0f, 0f, 1f, 99L, 1.0f));

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.Bm25Dense.class, decision.legs());
  }

  @Test
  @DisplayName("short hybrid queries skip dense with a typed reason")
  void shortHybridQuerySkipsDenseLeg() {
    SearchInputs inputs =
        baseInputs(
            "abc",
            PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true);

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.Bm25Only.class, decision.legs());
    assertEquals(Optional.of(SearchReasonCode.SKIPPED_SHORT_QUERY), decision.denseSkipReason());
  }

  @Test
  @DisplayName("dense-only queries remain recall-first even when short")
  void denseOnlyShortQueryStillRunsDense() {
    SearchInputs inputs =
        baseInputs(
            "abc",
            PipelineConfig.newBuilder().setDenseEnabled(true).build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.NotRequested(),
                new BgeM3Encoding.NotRequested()),
            true,
            new QppMetrics(0f, 0f, 1f, 200L, 1.0f));

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.DenseOnly.class, decision.legs());
    assertTrue(decision.denseSkipReason().isEmpty());
  }

  @Test
  @DisplayName("common terms remove dense but retain both sparse alternatives")
  void commonTermsMapThreeWayToBm25Splade() {
    SearchInputs inputs =
        baseInputs(
            "common term",
            PipelineConfig.newBuilder()
                .setSparseEnabled(true)
                .setDenseEnabled(true)
                .setSpladeEnabled(true)
                .build(),
            new EncodingResults(
                new VectorEncoding.Success(List.of(0.1f, 0.2f, 0.3f, 0.4f), "bgem3"),
                new SpladeEncoding.Success(Map.of("common", 0.5f)),
                new BgeM3Encoding.NotRequested()),
            true,
            new QppMetrics(0f, 0f, 1f, 200L, 0.50f));

    SearchDecision.MultiLegDecision decision =
        assertInstanceOf(SearchDecision.MultiLegDecision.class, planner.plan(inputs));
    assertInstanceOf(LegSet.Bm25Splade.class, decision.legs());
    assertEquals(
        Optional.of(SearchReasonCode.SKIPPED_NO_DISCRIMINATIVE_TERM),
        decision.denseSkipReason());
  }

  @Test
  @DisplayName("sparse-shortcut chunk-merge-disabled: chunkAwareEnabled=false → Skip(SKIPPED_DISABLED)")
  void sparseShortcutChunkMergeDisabled() throws IOException {
    SearchPlanner disabledChunkPlanner = newPlanner(false, true);
    SearchInputs inputs =
        baseInputs(
            "test query",
            PipelineConfig.newBuilder().setSparseEnabled(true).build(),
            allNotRequestedEncoding(),
            true);
    SearchDecision decision = disabledChunkPlanner.plan(inputs);
    captureDecision("sparse-shortcut-chunk-merge-disabled.v1.json", decision);
  }

  @Test
  @DisplayName("sparse-shortcut no-chunks: corpus.hasChunkDocs=false → Skip(SKIPPED_NO_CHUNK_DOCS)")
  void sparseShortcutNoChunkDocs() throws IOException {
    SearchInputs inputs =
        new SearchInputs(
            SearchRequest.newBuilder()
                .setQuery("test query")
                .setLimit(10)
                .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                .build(),
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build(),
            null,
            new CorpusCapabilities(false, false, 200L, 0.0),
            allNotRequestedEncoding(),
            QppMetrics.ZERO,
            EmbeddingCompatBoundary.of(null, true),
            Map.of(),
            null);
    SearchDecision decision = planner.plan(inputs);
    captureDecision("sparse-shortcut-no-chunk-docs.v1.json", decision);
  }

  // ============================================================
  // Capture / verify pipeline.
  // ============================================================

  private static void captureOrVerify(String fileName, SearchInputs inputs) throws IOException {
    SearchDecision decision = planner.plan(inputs);
    captureDecision(fileName, decision);
  }

  private static void captureDecision(String fileName, SearchDecision decision) throws IOException {
    JsonNode current = MAPPER.valueToTree(canonicalize(decision));
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current).replace("\r\n", "\n");
    Path path = schemasDir.resolve(fileName);
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + "\n");
      fail("Decision captured at " + path + ". Re-run to verify (expected on first run).");
    }
    String baselineJson = Files.readString(path);
    JsonNode baseline = MAPPER.readTree(baselineJson);
    assertEquals(
        baseline,
        current,
        "Planner decision for "
            + fileName
            + " diverged from baseline at "
            + path
            + ". If intended, delete the baseline and re-run to recapture.");
  }

  /**
   * Canonical map projection of the decision. Captures the {@code summary()} keys
   * plus variant-specific structural fields the summary doesn't expose
   * ({@code retrievalLimit}, {@code runtimeSyntax}, leg config). Lucene Query
   * objects are not serialised — they're behaviour, not contract.
   */
  private static Map<String, Object> canonicalize(SearchDecision decision) {
    Map<String, Object> out = new LinkedHashMap<>(decision.summary());
    switch (decision) {
      case SearchDecision.SparseShortcut sparse -> {
        out.put("runtime_syntax", sparse.runtimeSyntax().name());
        out.put("retrieval_limit", sparse.retrievalLimit());
        out.put("facets_requested", sparse.facets().isPresent());
        out.put("chunk_merge_inputs_present", chunkInputsPresent(sparse.chunkMerge()));
      }
      case SearchDecision.MultiLegDecision multi -> {
        // Tempdoc 821 §P: the multi-leg lexical leg now parses with this value, so it is a
        // behaviour-determining structural field of the decision — same standing as the sparse
        // shortcut's, and a planner regression that flips it must show up as a baseline diff.
        out.put("runtime_syntax", multi.runtimeSyntax().name());
        out.put("legs_detail", canonicalizeLegSet(multi.legs()));
        out.put("facets_requested", multi.facets().isPresent());
        out.put("hybrid_fallback", multi.hybridFallback().map(f -> f.reason().name()).orElse(null));
        out.put("splade_skip", multi.spladeSkip().map(f -> f.reason().name()).orElse(null));
        out.put("chunk_merge_inputs_present", chunkInputsPresent(multi.chunkMerge()));
      }
      case SearchDecision.BlockedDecision blocked -> {
        out.put(
            "encoding_failure_reason", blocked.encodingFailure().reason().name());
      }
      case SearchDecision.EmptyQueryDecision empty -> out.put("limit", empty.limit());
    }
    return out;
  }

  private static Map<String, Object> canonicalizeLegSet(LegSet legs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", legs.kind());
    out.put("effective_mode_label", legs.effectiveModeLabel());
    switch (legs) {
      case LegSet.Bm25Only b -> out.put("retrieval_limit", b.retrievalLimit());
      case LegSet.DenseOnly d -> {
        out.put("retrieval_limit", d.retrievalLimit());
        out.put("vector_dim", d.vector().vector().size());
      }
      case LegSet.SpladeOnly p -> {
        out.put("retrieval_limit", p.retrievalLimit());
        out.put("splade_term_count", p.splade().weights().size());
      }
      case LegSet.Bm25Dense bd -> {
        out.put("retrieval_limit", bd.retrievalLimit());
        out.put("vector_dim", bd.vector().vector().size());
        out.put("hybrid_weight", bd.hybridWeight());
      }
      case LegSet.Bm25Splade bs -> {
        out.put("retrieval_limit", bs.retrievalLimit());
        out.put("splade_term_count", bs.splade().weights().size());
      }
      case LegSet.DenseSplade ds -> {
        out.put("retrieval_limit", ds.retrievalLimit());
        out.put("vector_dim", ds.vector().vector().size());
        out.put("splade_term_count", ds.splade().weights().size());
      }
      case LegSet.ThreeWay t -> {
        out.put("retrieval_limit", t.retrievalLimit());
        out.put("vector_dim", t.vector().vector().size());
        out.put("splade_term_count", t.splade().weights().size());
        out.put("hybrid_weight", t.hybridWeight());
      }
    }
    return out;
  }

  private static boolean chunkInputsPresent(ChunkMergeDirective directive) {
    return directive instanceof ChunkMergeDirective.EligibleApply;
  }

  // ============================================================
  // Fixture builders.
  // ============================================================

  private static SearchInputs baseInputs(
      String query,
      PipelineConfig pipeline,
      EncodingResults encoding,
      boolean allowQueryEmbeddings) {
    return baseInputs(query, pipeline, encoding, allowQueryEmbeddings, QppMetrics.ZERO);
  }

  private static SearchInputs baseInputs(
      String query,
      PipelineConfig pipeline,
      EncodingResults encoding,
      boolean allowQueryEmbeddings,
      QppMetrics qpp) {
    return new SearchInputs(
        SearchRequest.newBuilder().setQuery(query).setLimit(10).setPipeline(pipeline).build(),
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build(),
        null,
        new CorpusCapabilities(true, false, 200L, 0.5),
        encoding,
        qpp,
        EmbeddingCompatBoundary.of(null, allowQueryEmbeddings),
        Map.of(),
        null);
  }

  private static EncodingResults allNotRequestedEncoding() {
    return new EncodingResults(
        new VectorEncoding.NotRequested(),
        new SpladeEncoding.NotRequested(),
        new BgeM3Encoding.NotRequested());
  }

  // ============================================================
  // Test-fixture infrastructure.
  // ============================================================

  /** Stubs a {@link ResolvedConfig} with the minimal {@code search()} sub-record. */
  private static SearchPlanner newPlanner(boolean chunkAwareEnabled, boolean correctionsEnabled) {
    ResolvedConfig.Search.Corrections corrections =
        new ResolvedConfig.Search.Corrections(correctionsEnabled, 1, 2, true);
    ResolvedConfig.Search search =
        new ResolvedConfig.Search(
            false, 0.0, chunkAwareEnabled,
            /* evidencePreviewEnabled= */ false, /* lambdamartEnabled= */ false,
            /* evidenceSpanEnabled= */ false, /* entitySignal= */ "df_rarity",
            /* mcpDeliveryBudgetBytes= */ ResolvedConfig.Search.DEFAULT_MCP_DELIVERY_BUDGET_BYTES,
            /* mcpFraming= */ ResolvedConfig.Search.McpFraming.OFF,
            /* mcpEntityCarriage= */ ResolvedConfig.Search.EntityCarriage.OFF,
            corrections,
            /* queryUnderstandingEnabled= */ false,
            /* filterNormalizationEnabled= */ false);
    ResolvedConfig config = mock(ResolvedConfig.class);
    when(config.search()).thenReturn(search);
    return new SearchPlanner(() -> config);
  }

  /** Walk up from the test working directory to find the repo's {@code SSOT/schemas/} root. */
  private static Path resolveSchemasDir() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    return cursor == null ? Path.of("SSOT/schemas").toAbsolutePath() : cursor.resolve("SSOT/schemas");
  }
}
