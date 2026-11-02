package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint.Analysis;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Chunking;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.FieldShape;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Hnsw;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Inputs;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — what {@code index_fingerprint} must and must not react to.
 *
 * <p>The key it replaces was wrong in both directions, so both directions are pinned here: a change
 * that alters the bytes on disk has to move it (or a stale index goes undetected), and a change that
 * does not has to leave it alone (or every annotation edit costs users a full reindex, which is what
 * tempdoc 804 documented).
 */
final class IndexFingerprintTest {

  @AfterEach
  void resetProviders() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  private static FieldShape textField() {
    return new FieldShape(
        "content", "text", true, false, false, "icu_default", List.of("content"), null, null);
  }

  private static FieldShape vectorField(int dimension, String similarity) {
    return new FieldShape(
        "vector", "vector", false, false, false, null, List.of("vector"), dimension, similarity);
  }

  private static Inputs baseline() {
    return new Inputs(
        "1.0.0",
        List.of(textField(), vectorField(768, "euclidean")),
        "analyzer-fp-1",
        "float32",
        new Hnsw(16, 200),
        new Chunking(500, 50, 100, 2000, "v1"),
        4096,
        new Analysis("10.2.1", "76.1"),
        ModelFingerprint.present("a".repeat(64)),
        ModelFingerprint.notConfigured(),
        ModelFingerprint.notConfigured());
  }

  /** Rebuilds {@link #baseline()} with one component replaced. */
  private static Inputs with(UnaryOperator<Inputs> mutation) {
    return mutation.apply(baseline());
  }

  private static Inputs fields(Inputs in, List<FieldShape> fields) {
    return new Inputs(
        in.catalogSchemaVersion(),
        fields,
        in.analyzerFingerprint(),
        in.vectorFormat(),
        in.hnsw(),
        in.chunking(),
        in.contentPreviewMaxChars(),
        in.analysis(),
        in.embeddingModel(),
        in.spladeModel(),
        in.nerModel());
  }

  private static String fp(Inputs in) {
    return IndexFingerprint.compute(in).orElseThrow();
  }

  private static void moves(String what, Inputs mutated) {
    assertNotEquals(fp(baseline()), fp(mutated), what + " must move the fingerprint");
  }

  @Test
  void companionOutageKeepsKnownGraphAndGraphOutageKeepsKnownCompanions() {
    var graphOnly = ModelFingerprint.present("a".repeat(64)).withAssets(Optional.empty());
    assertEquals("a".repeat(64), graphOnly.sha());
    assertEquals(IndexFingerprint.ModelState.INDETERMINATE, graphOnly.state());
    assertTrue(IndexFingerprint.compute(withModels(graphOnly, null, null)).isEmpty());
    assertEquals(List.of("embedding_model_assets_sha256"),
        withModels(graphOnly, null, null).indeterminateInputs());
    assertTrue(new String(IndexFingerprint.canonicalJson(withModels(graphOnly, null, null)),
        StandardCharsets.UTF_8).contains("\"embedding_model_sha256\":\"" + "a".repeat(64) + "\""));

    var companionsOnly = ModelFingerprint.indeterminate().withAssets(Optional.of("b".repeat(64)));
    assertEquals("b".repeat(64), companionsOnly.assetsSha());
    assertTrue(IndexFingerprint.compute(withModels(companionsOnly, null, null)).isEmpty());
    assertEquals(List.of("embedding_model_sha256"),
        withModels(companionsOnly, null, null).indeterminateInputs());
  }

  @Test
  void identicalInputsGiveIdenticalFingerprints() {
    assertEquals(fp(baseline()), fp(baseline()));
  }

  @Test
  void fieldOrderIsNotAnInput() {
    assertEquals(
        fp(baseline()),
        fp(with(in -> fields(in, List.of(vectorField(768, "euclidean"), textField())))),
        "the catalog field order is authoring convenience, not index shape");
  }

  @Test
  void everyPhysicalInputMovesTheFingerprint() {
    moves(
        "catalog_schema_version",
        with(
            in ->
                new Inputs(
                    "2.0.0",
                    in.fields(),
                    in.analyzerFingerprint(),
                    in.vectorFormat(),
                    in.hnsw(),
                    in.chunking(),
                    in.contentPreviewMaxChars(),
                    in.analysis(),
                    in.embeddingModel(),
                    in.spladeModel(),
                    in.nerModel())));
    moves(
        "vector dimension",
        with(in -> fields(in, List.of(textField(), vectorField(1024, "euclidean")))));
    moves(
        "vector similarity (phase 3 flips this and must invalidate EUCLIDEAN indexes)",
        with(in -> fields(in, List.of(textField(), vectorField(768, "dot_product")))));
    moves(
        "analyzer fingerprint",
        with(
            in ->
                new Inputs(
                    in.catalogSchemaVersion(),
                    in.fields(),
                    "analyzer-fp-2",
                    in.vectorFormat(),
                    in.hnsw(),
                    in.chunking(),
                    in.contentPreviewMaxChars(),
                    in.analysis(),
                    in.embeddingModel(),
                    in.spladeModel(),
                    in.nerModel())));
    moves(
        "vector storage format",
        with(
            in ->
                new Inputs(
                    in.catalogSchemaVersion(),
                    in.fields(),
                    in.analyzerFingerprint(),
                    "int8_sq",
                    in.hnsw(),
                    in.chunking(),
                    in.contentPreviewMaxChars(),
                    in.analysis(),
                    in.embeddingModel(),
                    in.spladeModel(),
                    in.nerModel())));
    moves("HNSW m", withHnsw(new Hnsw(32, 200)));
    moves("HNSW ef_construction", withHnsw(new Hnsw(16, 400)));
    moves("chunk target tokens", withChunking(new Chunking(400, 50, 100, 2000, "v1")));
    moves("chunk overlap tokens", withChunking(new Chunking(500, 60, 100, 2000, "v1")));
    moves("chunk minimum tokens", withChunking(new Chunking(500, 50, 120, 2000, "v1")));
    moves(
        "the chunk threshold, which decides whether chunk documents exist at all",
        withChunking(new Chunking(500, 50, 100, 3000, "v1")));
    moves("chunker algorithm version", withChunking(new Chunking(500, 50, 100, 2000, "v2")));
    moves("content_preview bound", withPreview(8192));
    moves("Lucene version", withAnalysis(new Analysis("10.3.0", "76.1")));
    moves("ICU version", withAnalysis(new Analysis("10.2.1", "77.1")));
    moves("embedding model digest", withModels(ModelFingerprint.present("b".repeat(64)), null, null));
    moves("SPLADE model digest", withModels(null, ModelFingerprint.present("c".repeat(64)), null));
    moves("NER model digest", withModels(null, null, ModelFingerprint.present("d".repeat(64))));
  }

  private static Inputs withHnsw(Hnsw hnsw) {
    Inputs in = baseline();
    return new Inputs(
        in.catalogSchemaVersion(),
        in.fields(),
        in.analyzerFingerprint(),
        in.vectorFormat(),
        hnsw,
        in.chunking(),
        in.contentPreviewMaxChars(),
        in.analysis(),
        in.embeddingModel(),
        in.spladeModel(),
        in.nerModel());
  }

  private static Inputs withChunking(Chunking chunking) {
    Inputs in = baseline();
    return new Inputs(
        in.catalogSchemaVersion(),
        in.fields(),
        in.analyzerFingerprint(),
        in.vectorFormat(),
        in.hnsw(),
        chunking,
        in.contentPreviewMaxChars(),
        in.analysis(),
        in.embeddingModel(),
        in.spladeModel(),
        in.nerModel());
  }

  private static Inputs withPreview(int maxChars) {
    Inputs in = baseline();
    return new Inputs(
        in.catalogSchemaVersion(),
        in.fields(),
        in.analyzerFingerprint(),
        in.vectorFormat(),
        in.hnsw(),
        in.chunking(),
        maxChars,
        in.analysis(),
        in.embeddingModel(),
        in.spladeModel(),
        in.nerModel());
  }

  private static Inputs withAnalysis(Analysis analysis) {
    Inputs in = baseline();
    return new Inputs(
        in.catalogSchemaVersion(),
        in.fields(),
        in.analyzerFingerprint(),
        in.vectorFormat(),
        in.hnsw(),
        in.chunking(),
        in.contentPreviewMaxChars(),
        analysis,
        in.embeddingModel(),
        in.spladeModel(),
        in.nerModel());
  }

  private static Inputs withModels(
      ModelFingerprint embedding, ModelFingerprint splade, ModelFingerprint ner) {
    Inputs in = baseline();
    return new Inputs(
        in.catalogSchemaVersion(),
        in.fields(),
        in.analyzerFingerprint(),
        in.vectorFormat(),
        in.hnsw(),
        in.chunking(),
        in.contentPreviewMaxChars(),
        in.analysis(),
        embedding == null ? in.embeddingModel() : embedding,
        splade == null ? in.spladeModel() : splade,
        ner == null ? in.nerModel() : ner);
  }

  /**
   * "A model was never configured here" and "a model is configured but I could not digest it" are
   * different answers, and only the first is an answer. Conflating them is how a transiently
   * unreadable model file would cost a user a full reindex.
   */
  @Test
  void anIndeterminateModelYieldsNoFingerprintAtAll() {
    assertTrue(
        IndexFingerprint.compute(withModels(ModelFingerprint.indeterminate(), null, null)).isEmpty(),
        "embedding");
    assertTrue(
        IndexFingerprint.compute(withModels(null, ModelFingerprint.indeterminate(), null)).isEmpty(),
        "splade");
    assertTrue(
        IndexFingerprint.compute(withModels(null, null, ModelFingerprint.indeterminate())).isEmpty(),
        "ner");
  }

  /** A skipped comparison has to be able to say which question went unanswered. */
  @Test
  void indeterminateInputsAreNamed() {
    assertEquals(
        List.of("embedding_model_sha256", "ner_model_sha256"),
        withModels(ModelFingerprint.indeterminate(), null, ModelFingerprint.indeterminate())
            .indeterminateInputs());
    assertEquals(List.of(), baseline().indeterminateInputs());
  }

  @Test
  void notConfiguredIsADeterminateAnswerDistinctFromAnyDigest() {
    assertNotEquals(
        fp(baseline()),
        fp(withModels(null, ModelFingerprint.present("c".repeat(64)), null)),
        "installing a SPLADE model changes what is written into the sparse fields");
  }

  @Test
  void modelFingerprintOfMapsTheThreeStatesCorrectly() {
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED,
        ModelFingerprint.of(false, Optional.of("x")).state(),
        "no model file means not configured, whatever a stale digest says");
    assertEquals(
        IndexFingerprint.ModelState.INDETERMINATE,
        ModelFingerprint.of(true, Optional.empty()).state(),
        "a model file we cannot digest is indeterminate, not absent");
    assertEquals(
        IndexFingerprint.ModelState.PRESENT, ModelFingerprint.of(true, Optional.of("x")).state());
  }

  /**
   * The canonical JSON is what gets hashed, so it is also what an operator reads when a mismatch
   * costs them a rebuild. Pin that it carries the named inputs and none of the excluded ones.
   */
  @Test
  void canonicalJsonCarriesTheNamedInputsAndNoQueryTimeConfig() {
    String json = new String(IndexFingerprint.canonicalJson(baseline()), StandardCharsets.UTF_8);
    for (String expected :
        List.of(
            "rendering_version",
            "catalog_schema_version",
            "analyzer_fp",
            "vector_format",
            "hnsw",
            "ef_construction",
            "chunking",
            "threshold_chars",
            "algorithm_version",
            "preview",
            "max_chars",
            "analysis",
            "lucene_version",
            "icu_version",
            "embedding_model_sha256",
            "splade_model_sha256",
            "ner_model_sha256",
            "similarity",
            "doc_values",
            "multi_valued",
            "roles")) {
      assertTrue(json.contains(expected), "canonical JSON should carry " + expected + ": " + json);
    }
    for (String forbidden : List.of("boosts", "k1", "similarity_fp", "ef_search", "rmwPolicy")) {
      assertFalse(
          json.contains(forbidden),
          "canonical JSON must not carry query-time or runtime-policy input " + forbidden);
    }
  }

  @Test
  void aProviderThatThrowsIsIndeterminateNotAbsent() {
    IndexFingerprint.installModelFingerprintProviders(
        () -> {
          throw new IllegalStateException("model store unavailable");
        },
        ModelFingerprint::notConfigured,
        ModelFingerprint::notConfigured);
    assertEquals(
        IndexFingerprint.ModelState.INDETERMINATE,
        IndexFingerprint.embeddingModel().state(),
        "a provider that fails has told us nothing, not that the model is gone");
    assertEquals(
        List.of("embedding_model_sha256"),
        IndexFingerprint.indeterminateModelInputs(),
        "the guard must be able to name the input it could not resolve");
  }

  /**
   * Tempdoc 931 §C.5. The digest stays all-or-nothing, but the inputs it hashes can still be
   * compared one by one when a model digest is missing — and the report has to name the input in
   * terms an operator can act on. {@code fields} is an array, so an ordinal would be meaningless;
   * it is keyed by field id instead.
   */
  @Test
  void differingInputsNamesTheMovedInputByFieldIdNotByOrdinal() {
    String stored = json(baseline());
    String expected = json(with(in -> fields(in, List.of(textField(), vectorField(1024, "euclidean")))));

    var differences = IndexFingerprint.differingInputs(stored, expected, List.of());
    assertEquals(1, differences.size(), differences.toString());
    assertEquals("fields[vector].vector.dimension", differences.getFirst().path());
    assertEquals("768", differences.getFirst().stored());
    assertEquals("1024", differences.getFirst().expected());
  }

  /**
   * The ignored keys are supplied, never inferred: {@code INDETERMINATE} and {@code NOT_CONFIGURED}
   * both render as JSON {@code null}, so the stored rendering alone cannot say which it was. Naming
   * them removes the unanswerable question from both sides and leaves every answerable one.
   */
  @Test
  void namedModelKeysAreDroppedFromBothSidesAndNothingElseIs() {
    String stored = json(baseline());
    String withNer = json(withModels(null, null, ModelFingerprint.present("d".repeat(64))));

    assertEquals(
        List.of("ner_model_sha256"),
        IndexFingerprint.differingInputs(stored, withNer, List.of()).stream()
            .map(IndexFingerprint.InputDifference::path)
            .toList(),
        "without the ignore list the model digest is just another input");
    assertEquals(
        List.of(),
        IndexFingerprint.differingInputs(stored, withNer, List.of("ner_model_sha256")),
        "naming it as unresolved removes it from the comparison entirely");

    String withNerAndDimension =
        json(
            new Inputs(
                baseline().catalogSchemaVersion(),
                List.of(textField(), vectorField(1024, "euclidean")),
                baseline().analyzerFingerprint(),
                baseline().vectorFormat(),
                baseline().hnsw(),
                baseline().chunking(),
                baseline().contentPreviewMaxChars(),
                baseline().analysis(),
                baseline().embeddingModel(),
                baseline().spladeModel(),
                ModelFingerprint.present("d".repeat(64))));
    assertEquals(
        List.of("fields[vector].vector.dimension"),
        IndexFingerprint.differingInputs(stored, withNerAndDimension, List.of("ner_model_sha256"))
            .stream()
            .map(IndexFingerprint.InputDifference::path)
            .toList(),
        "dropping the unresolvable question must not drop the resolvable one beside it");
  }

  /**
   * A rendering that cannot be parsed leaves the question unanswered, which is the same thing an
   * uncomputable digest does — and the opposite of answering "different". Reporting a difference
   * here would spend a full rebuild on unreadable bytes.
   */
  @Test
  void anUnparseableRenderingIsNotADifference() {
    assertEquals(List.of(), IndexFingerprint.differingInputs("not json", json(baseline()), List.of()));
    assertEquals(List.of(), IndexFingerprint.differingInputs(json(baseline()), "[]", List.of()));
  }

  private static String json(Inputs in) {
    return new String(IndexFingerprint.canonicalJson(in), StandardCharsets.UTF_8);
  }

  @Test
  void theDefaultProvidersAreNotConfigured() {
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED, IndexFingerprint.embeddingModel().state());
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED, IndexFingerprint.spladeModel().state());
    assertEquals(IndexFingerprint.ModelState.NOT_CONFIGURED, IndexFingerprint.nerModel().state());
    assertEquals(List.of(), IndexFingerprint.indeterminateModelInputs());
  }
}
