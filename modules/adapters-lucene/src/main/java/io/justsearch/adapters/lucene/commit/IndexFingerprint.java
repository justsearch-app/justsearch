/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single rebuild-requiring identity of an index: a SHA-256 over a canonical JSON rendering of
 * the <em>effective physical index shape</em> — everything that decides what bytes end up in the
 * Lucene directory, and nothing else.
 *
 * <p>It replaces four of the five keys that used to be parity-checked -- {@code schema_ver},
 * {@code analyzer_fp}, {@code index_schema_fp} and {@code similarity_fp}. The fifth,
 * {@code boosts_fp}, survives unchanged as the benign one; {@code schema_fp} (the search-intent
 * schema hash) was never a parity key and stays plain observability. Those four were untruthful in
 * both directions: {@code schema_ver} tracked the search-intent grammar version and could never
 * fire, while {@code index_schema_fp} hashed the whole catalog <em>file</em>, so an annotation-only
 * edit (an {@code rmwPolicy} added to a field) flipped it against an index that was physically
 * still perfectly compatible (tempdoc 804, tempdoc 915 §C).
 *
 * <h2>What is in, and why</h2>
 *
 * <ul>
 *   <li>{@code catalog_schema_version} — the catalog's own {@code version}; a deliberate,
 *       author-driven break lever.
 *   <li>{@code fields} — the <em>physical projection</em> of each field: {@code id}, {@code type},
 *       {@code stored}, {@code docValues}, {@code multiValued}, {@code analyzer}, {@code roles}
 *       (roles decide the Lucene field construction — {@code filter} picks a keyword/doc-values
 *       shape) and, for vector fields, {@code dimension} + {@code similarity}. Sorted by id;
 *       roles sorted within a field.
 *   <li>{@code analyzer_fp} — the analyzer definitions, because index-time analysis decides the
 *       postings.
 *   <li>{@code vector_format} — {@code float32} vs {@code int8_sq}: a different
 *       {@code KnnVectorsFormat} on disk.
 *   <li>{@code hnsw} — {@code m} and {@code ef_construction}, the two parameters that shape the
 *       graph that is written.
 *   <li>{@code chunking} — target/overlap/minimum tokens, the character threshold above which a
 *       document is chunked at all, and the splitter algorithm version: together these decide
 *       whether chunk documents exist and where they begin and end.
 *   <li>{@code preview.max_chars} — bounds {@code content_preview}, a stored field.
 *   <li>{@code analysis.lucene_version} / {@code analysis.icu_version} — the libraries that do the
 *       analysis. An upgrade changes the postings with every descriptor unchanged.
 *   <li>{@code embedding_model_sha256} / {@code splade_model_sha256} / {@code ner_model_sha256} —
 *       the models whose output is stored in the vector, sparse and entity fields.
 * </ul>
 *
 * <h2>What is deliberately out</h2>
 *
 * <ul>
 *   <li>{@code rmwPolicy} field annotations — read-modify-write preservation policy. {@code
 *       FieldMapper} rejects an {@code rmwPolicy} on any stored or doc-values field, so by
 *       construction it can only describe fields that are not read back from disk: it changes
 *       runtime backfill behaviour, never bytes. This exclusion is the specific fix for 804's
 *       over-trigger.
 *   <li>Query-time scoring: BM25 {@code k1}/{@code b} ({@code similarity_fp}), field boosts
 *       ({@code boosts_fp}), and HNSW {@code ef_search}. These change ranking, not storage, so a
 *       change to them must never cost the user a reindex.
 *   <li>The search-intent grammar, prompt packs and templates. They never touched the index.
 * </ul>
 *
 * <h2>Indeterminate inputs are not mismatches</h2>
 *
 * <p>A model fingerprint is tri-state. {@code NOT_CONFIGURED} (no model resolvable for this
 * deployment at all) is a determinate answer and hashes as JSON {@code null}. {@code INDETERMINATE}
 * (a model file is configured but its digest could not be read) is <em>not</em> an answer, and
 * {@link #compute} returns {@link Optional#empty()} rather than inventing one. Callers stamp
 * nothing and compare nothing in that case: a transiently unreadable model file must not be
 * indistinguishable from a swapped one, because the consequence of the latter is a full rebuild
 * (`green-masked-destructive`).
 *
 * <p>The digest stays all-or-nothing, but "no digest" no longer has to mean "no comparison". The
 * canonical inputs are ALSO stamped, under {@link #COMMIT_META_INPUTS_KEY}, so a runtime that
 * cannot compute a digest can still compare the inputs the indeterminate model does not touch —
 * a vector dimension or a chunking change is a rebuild-requiring difference whether or not the NER
 * model file happened to be readable this boot (tempdoc 931 §C.5). {@link #differingInputs} is that
 * comparison; the ignored keys are named by the caller, never inferred from the JSON, because an
 * indeterminate model and an unconfigured one both render as {@code null}.
 */
public final class IndexFingerprint {

  /** Key under which the fingerprint is stamped into Lucene commit user data. */
  public static final String COMMIT_META_KEY = "index_fingerprint";

  /**
   * Key under which the canonical inputs JSON — the exact bytes {@link #compute} hashes — is
   * stamped alongside the digest. Deliberately NOT a parity key of its own: it is the SAME
   * statement as {@code index_fingerprint}, so comparing both would report one shape change twice
   * and (worse) let a rendering difference that the digest already covers surface as a second,
   * independent-looking mismatch. It is read only on the path where the digest is unavailable.
   */
  public static final String COMMIT_META_INPUTS_KEY = "index_fingerprint_inputs";

  /**
   * The model-digest input names, in report order — the one place they are written. The canonical
   * rendering, the "which input went unresolved" report and the parity fallback's ignore list all
   * read them from here: three hand-typed copies of the same three strings is how one of them ends
   * up spelled differently from the key that is actually hashed.
   */
  public static final List<String> MODEL_INPUT_KEYS =
      List.of("embedding_model_sha256", "splade_model_sha256", "ner_model_sha256");

  /**
   * Version of the {@link #compute} rendering itself. Bump it when the canonical JSON shape
   * changes in a way that must invalidate existing indexes even though every input is unchanged.
   */
  public static final String RENDERING_VERSION = "1";

  private static final ObjectMapper M =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private IndexFingerprint() {}

  /** Whether a model's identity is known, knowably absent, or simply unavailable right now. */
  public enum ModelState {
    /** A model is configured and its digest was read. */
    PRESENT,
    /** No model of this kind is configured for this deployment — a determinate answer. */
    NOT_CONFIGURED,
    /** A model is configured but its digest could not be resolved — not an answer. */
    INDETERMINATE
  }

  /** Tri-state identity of one model that contributes content to the index. */
  public record ModelFingerprint(ModelState state, String sha) {
    public ModelFingerprint {
      Objects.requireNonNull(state, "state");
    }

    public static ModelFingerprint present(String sha) {
      Objects.requireNonNull(sha, "sha");
      return new ModelFingerprint(ModelState.PRESENT, sha);
    }

    public static ModelFingerprint notConfigured() {
      return new ModelFingerprint(ModelState.NOT_CONFIGURED, null);
    }

    public static ModelFingerprint indeterminate() {
      return new ModelFingerprint(ModelState.INDETERMINATE, null);
    }

    /**
     * Maps an {@code Optional} digest to the tri-state, given whether a model file was found at
     * all. A resolvable path with no digest is {@link ModelState#INDETERMINATE}; no path is
     * {@link ModelState#NOT_CONFIGURED}.
     */
    public static ModelFingerprint of(boolean configured, Optional<String> sha) {
      if (!configured) {
        return notConfigured();
      }
      return sha.map(ModelFingerprint::present).orElseGet(ModelFingerprint::indeterminate);
    }
  }

  /** One field's physical shape. {@code analyzer} and {@code vector} are nullable. */
  public record FieldShape(
      String id,
      String type,
      boolean stored,
      boolean docValues,
      boolean multiValued,
      String analyzer,
      List<String> roles,
      Integer vectorDimension,
      String vectorSimilarity) {
    public FieldShape {
      Objects.requireNonNull(id, "id");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  /**
   * Chunk-splitting parameters. {@code thresholdChars} decides whether a document is chunked at
   * all, so it governs whether chunk documents exist, not merely where they end.
   */
  public record Chunking(
      int targetTokens,
      int overlapTokens,
      int minTokens,
      int thresholdChars,
      String algorithmVersion) {}

  /**
   * HNSW graph-construction parameters, already resolved to the values the codec will use. Not
   * nullable on purpose: hashing the raw config would make writing a default out explicitly look
   * like a schema change and cost a full reindex for a no-op edit.
   */
  public record Hnsw(int m, int efConstruction) {}

  /** Versions of the libraries that perform index-time analysis. */
  public record Analysis(String luceneVersion, String icuVersion) {}

  /** The complete, ordered input set. */
  public record Inputs(
      String catalogSchemaVersion,
      List<FieldShape> fields,
      String analyzerFingerprint,
      String vectorFormat,
      Hnsw hnsw,
      Chunking chunking,
      int contentPreviewMaxChars,
      Analysis analysis,
      ModelFingerprint embeddingModel,
      ModelFingerprint spladeModel,
      ModelFingerprint nerModel) {
    public Inputs {
      Objects.requireNonNull(fields, "fields");
      Objects.requireNonNull(analyzerFingerprint, "analyzerFingerprint");
      Objects.requireNonNull(vectorFormat, "vectorFormat");
      Objects.requireNonNull(hnsw, "hnsw");
      Objects.requireNonNull(chunking, "chunking");
      Objects.requireNonNull(analysis, "analysis");
      Objects.requireNonNull(embeddingModel, "embeddingModel");
      Objects.requireNonNull(spladeModel, "spladeModel");
      Objects.requireNonNull(nerModel, "nerModel");
    }

    /**
     * Names the model inputs that could not be resolved, so a skipped parity check can report which
     * question went unanswered instead of just going quiet.
     */
    public List<String> indeterminateInputs() {
      return namedIndeterminate(orderedModels());
    }

    /** The three model fingerprints positionally aligned with {@link #MODEL_INPUT_KEYS}. */
    List<ModelFingerprint> orderedModels() {
      return List.of(embeddingModel, spladeModel, nerModel);
    }
  }

  /**
   * Renders {@code inputs} as canonical JSON and returns its SHA-256, or {@link Optional#empty()}
   * if any model fingerprint is {@link ModelState#INDETERMINATE}.
   */
  public static Optional<String> compute(Inputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    if (!inputs.indeterminateInputs().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(sha256Hex(canonicalJson(inputs)));
  }

  /**
   * The canonical JSON that {@link #compute} hashes. Exposed so a failing parity check can show
   * an operator <em>which</em> input moved instead of two opaque digests.
   */
  public static byte[] canonicalJson(Inputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    TreeMap<String, Object> root = new TreeMap<>();
    root.put("rendering_version", RENDERING_VERSION);
    root.put("catalog_schema_version", inputs.catalogSchemaVersion());
    root.put("analyzer_fp", inputs.analyzerFingerprint());
    root.put("vector_format", inputs.vectorFormat());

    TreeMap<String, Object> hnsw = new TreeMap<>();
    hnsw.put("m", inputs.hnsw().m());
    hnsw.put("ef_construction", inputs.hnsw().efConstruction());
    root.put("hnsw", hnsw);

    TreeMap<String, Object> chunking = new TreeMap<>();
    chunking.put("target_tokens", inputs.chunking().targetTokens());
    chunking.put("overlap_tokens", inputs.chunking().overlapTokens());
    chunking.put("min_tokens", inputs.chunking().minTokens());
    chunking.put("threshold_chars", inputs.chunking().thresholdChars());
    chunking.put("algorithm_version", inputs.chunking().algorithmVersion());
    root.put("chunking", chunking);

    TreeMap<String, Object> preview = new TreeMap<>();
    preview.put("max_chars", inputs.contentPreviewMaxChars());
    root.put("preview", preview);

    TreeMap<String, Object> analysis = new TreeMap<>();
    analysis.put("lucene_version", inputs.analysis().luceneVersion());
    analysis.put("icu_version", inputs.analysis().icuVersion());
    root.put("analysis", analysis);

    List<ModelFingerprint> models = inputs.orderedModels();
    for (int i = 0; i < MODEL_INPUT_KEYS.size(); i++) {
      root.put(MODEL_INPUT_KEYS.get(i), modelValue(models.get(i)));
    }

    List<FieldShape> sortedFields = new ArrayList<>(inputs.fields());
    sortedFields.sort((a, b) -> a.id().compareTo(b.id()));
    List<Object> fieldNodes = new ArrayList<>(sortedFields.size());
    for (FieldShape f : sortedFields) {
      TreeMap<String, Object> node = new TreeMap<>();
      node.put("id", f.id());
      node.put("type", f.type());
      node.put("stored", f.stored());
      node.put("doc_values", f.docValues());
      node.put("multi_valued", f.multiValued());
      node.put("analyzer", f.analyzer());
      List<String> roles = new ArrayList<>(f.roles());
      java.util.Collections.sort(roles);
      node.put("roles", roles);
      if (f.vectorDimension() != null || f.vectorSimilarity() != null) {
        TreeMap<String, Object> vector = new TreeMap<>();
        vector.put("dimension", f.vectorDimension());
        vector.put("similarity", f.vectorSimilarity());
        node.put("vector", vector);
      }
      fieldNodes.add(node);
    }
    root.put("fields", fieldNodes);

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator g =
            M.tokenStreamFactory()
                .createGenerator(bos)
                .configure(StreamWriteFeature.AUTO_CLOSE_TARGET, true)) {
      M.writeValue(g, root);
      g.flush();
      return bos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed rendering canonical index-fingerprint JSON", e);
    }
  }

  private static String modelValue(ModelFingerprint fp) {
    return fp.state() == ModelState.PRESENT ? fp.sha() : null;
  }

  /** One input that differs between a stored and an expected canonical rendering. */
  public record InputDifference(String path, String stored, String expected) {
    @Override
    public String toString() {
      return path + " (stored=" + stored + ", expected=" + expected + ")";
    }
  }

  /**
   * The inputs that differ between two canonical renderings, ignoring {@code ignoredTopLevelKeys}.
   *
   * <p>This is what makes an uncomputable digest survivable. When a configured model's digest is
   * unreadable, {@link #compute} refuses to answer — but the answer it refuses to give is about
   * that model, not about the vector dimension, the chunk threshold or the analyzer. Dropping the
   * unresolved model keys from BOTH sides and comparing the rest asks exactly the question the
   * runtime can still answer truthfully.
   *
   * <p>The ignored keys are supplied, not inferred: an {@code INDETERMINATE} model and a
   * {@code NOT_CONFIGURED} one both render as JSON {@code null} (see {@link #modelValue}), so the
   * stored JSON alone cannot say which it was. The caller knows which inputs this runtime could not
   * resolve, and that is the only honest source.
   *
   * <p>Paths are the operator-facing part: {@code fields} is an array, but it is an array keyed by
   * {@code id}, so a difference is reported as {@code fields[vector].vector.dimension} rather than
   * an ordinal nobody can map back to a field.
   *
   * @param storedJson the canonical rendering recorded in commit metadata
   * @param expectedJson the canonical rendering this runtime would write
   * @param ignoredTopLevelKeys top-level input names to drop from both sides before comparing
   * @return the differing input paths, ordered, empty when the determinate inputs agree
   */
  public static List<InputDifference> differingInputs(
      String storedJson, String expectedJson, Collection<String> ignoredTopLevelKeys) {
    Objects.requireNonNull(storedJson, "storedJson");
    Objects.requireNonNull(expectedJson, "expectedJson");
    JsonNode stored = parseOrNull(storedJson);
    JsonNode expected = parseOrNull(expectedJson);
    if (stored == null || expected == null || !stored.isObject() || !expected.isObject()) {
      // Unparseable is not "different". A commit whose recorded inputs cannot be read leaves the
      // question unanswered, exactly like an uncomputable digest does, and the caller declines.
      return List.of();
    }
    ObjectNode a = (ObjectNode) stored.deepCopy();
    ObjectNode b = (ObjectNode) expected.deepCopy();
    if (ignoredTopLevelKeys != null) {
      for (String key : ignoredTopLevelKeys) {
        a.remove(key);
        b.remove(key);
      }
    }
    List<InputDifference> out = new ArrayList<>();
    compareNodes("", a, b, out);
    return List.copyOf(out);
  }

  private static JsonNode parseOrNull(String json) {
    try {
      return M.readTree(json);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void compareNodes(
      String path, JsonNode stored, JsonNode expected, List<InputDifference> out) {
    if (stored.isObject() && expected.isObject()) {
      for (String name : sortedUnion(stored.propertyNames(), expected.propertyNames())) {
        compareNodes(
            path.isEmpty() ? name : path + "." + name,
            stored.path(name),
            expected.path(name),
            out);
      }
      return;
    }
    if (stored.isArray() && expected.isArray() && keyedById(stored) && keyedById(expected)) {
      Map<String, JsonNode> storedById = byId(stored);
      Map<String, JsonNode> expectedById = byId(expected);
      for (String id : sortedUnion(storedById.keySet(), expectedById.keySet())) {
        compareNodes(
            path + "[" + id + "]",
            storedById.getOrDefault(id, MissingNode.getInstance()),
            expectedById.getOrDefault(id, MissingNode.getInstance()),
            out);
      }
      return;
    }
    if (!stored.equals(expected)) {
      out.add(new InputDifference(path, render(stored), render(expected)));
    }
  }

  private static boolean keyedById(JsonNode array) {
    for (JsonNode element : array) {
      if (!element.isObject() || !element.has("id")) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, JsonNode> byId(JsonNode array) {
    Map<String, JsonNode> out = new TreeMap<>();
    for (JsonNode element : array) {
      out.put(element.path("id").asString(""), element);
    }
    return out;
  }

  private static List<String> sortedUnion(Collection<String> a, Collection<String> b) {
    java.util.TreeSet<String> union = new java.util.TreeSet<>(a);
    union.addAll(b);
    return List.copyOf(union);
  }

  private static String render(JsonNode node) {
    if (node == null || node.isMissingNode()) {
      return "<absent>";
    }
    return node.isValueNode() ? node.asString("null") : node.toString();
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Process-wide model-fingerprint providers.
  //
  // The fingerprint has to come out identical at three sites that build it independently: the
  // commit path, the parity guard's "expected" snapshot, and the green-cutover verification. Two of
  // its inputs — the embedding and SPLADE model digests — are only knowable in the Worker's model
  // modules, which adapters-lucene cannot depend on. The Worker installs suppliers here once at
  // boot; everything else (Head, tests, tools) sees the NOT_CONFIGURED default, consistently.
  // Same shape as ConfigStore.globalOrNull() and EmbeddingFingerprint's own process-wide cache.
  // ---------------------------------------------------------------------------------------------

  private static final AtomicReference<Supplier<ModelFingerprint>> EMBEDDING_PROVIDER =
      new AtomicReference<>(ModelFingerprint::notConfigured);
  private static final AtomicReference<Supplier<ModelFingerprint>> SPLADE_PROVIDER =
      new AtomicReference<>(ModelFingerprint::notConfigured);
  private static final AtomicReference<Supplier<ModelFingerprint>> NER_PROVIDER =
      new AtomicReference<>(ModelFingerprint::notConfigured);

  private static final AtomicReference<Supplier<Integer>> VECTOR_DIMENSION_PROVIDER =
      new AtomicReference<>(() -> null);

  /**
   * Installs the effective vector dimension — the one the {@code FieldMapper} actually builds
   * fields with, which is not always the catalog's declared value (the Worker overrides it to 1024
   * when BGE-M3 is the active sparse model). Returning {@code null} means "use what the catalog
   * declares".
   *
   * <p>Separate from reading {@code index.vector.dimension} out of config, deliberately: the
   * override is env-driven, and a fingerprint that consulted a different source than the field
   * construction would be exactly the kind of near-miss this key exists to eliminate.
   */
  public static void installEffectiveVectorDimension(Supplier<Integer> provider) {
    VECTOR_DIMENSION_PROVIDER.set(Objects.requireNonNull(provider, "provider"));
  }

  /** The installed effective vector dimension, or {@code null} to use the catalog's declaration. */
  public static Integer effectiveVectorDimension() {
    try {
      return VECTOR_DIMENSION_PROVIDER.get().get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Installs the process-wide model-fingerprint providers. Call once, early, from the Worker. */
  public static void installModelFingerprintProviders(
      Supplier<ModelFingerprint> embedding,
      Supplier<ModelFingerprint> splade,
      Supplier<ModelFingerprint> ner) {
    EMBEDDING_PROVIDER.set(Objects.requireNonNull(embedding, "embedding"));
    SPLADE_PROVIDER.set(Objects.requireNonNull(splade, "splade"));
    NER_PROVIDER.set(Objects.requireNonNull(ner, "ner"));
  }

  /** Restores the NOT_CONFIGURED defaults. For tests that installed a provider. */
  public static void resetModelFingerprintProviders() {
    EMBEDDING_PROVIDER.set(ModelFingerprint::notConfigured);
    SPLADE_PROVIDER.set(ModelFingerprint::notConfigured);
    NER_PROVIDER.set(ModelFingerprint::notConfigured);
    VECTOR_DIMENSION_PROVIDER.set(() -> null);
  }

  /** The embedding model's tri-state identity, per the installed provider. */
  public static ModelFingerprint embeddingModel() {
    return safeGet(EMBEDDING_PROVIDER.get());
  }

  /** The SPLADE model's tri-state identity, per the installed provider. */
  public static ModelFingerprint spladeModel() {
    return safeGet(SPLADE_PROVIDER.get());
  }

  /** The NER model's tri-state identity, per the installed provider. */
  public static ModelFingerprint nerModel() {
    return safeGet(NER_PROVIDER.get());
  }

  /**
   * Names the model inputs the installed providers currently cannot resolve. The parity guard uses
   * it to say WHICH question went unanswered when it declines to compare, so an operator sees a
   * cause instead of silence.
   */
  public static List<String> indeterminateModelInputs() {
    return namedIndeterminate(List.of(embeddingModel(), spladeModel(), nerModel()));
  }

  /** Names unresolved model inputs captured for one runtime, independent of global providers. */
  public static List<String> indeterminateModelInputs(
      ModelFingerprint embedding, ModelFingerprint splade, ModelFingerprint ner) {
    return namedIndeterminate(List.of(embedding, splade, ner));
  }

  /** The {@link #MODEL_INPUT_KEYS} whose positionally-aligned fingerprint is INDETERMINATE. */
  private static List<String> namedIndeterminate(List<ModelFingerprint> orderedModels) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < MODEL_INPUT_KEYS.size(); i++) {
      if (orderedModels.get(i).state() == ModelState.INDETERMINATE) {
        out.add(MODEL_INPUT_KEYS.get(i));
      }
    }
    return List.copyOf(out);
  }

  /**
   * The {@link #MODEL_INPUT_KEYS} that a stored canonical rendering records as JSON {@code null}.
   *
   * <p>Only meaningful when the commit recorded NO digest, and then it is the mirror image of
   * {@link #indeterminateModelInputs()}: that names what THIS runtime cannot resolve, this names
   * what the COMMIT could not resolve. A commit without a digest was written while at least one
   * model was {@code INDETERMINATE}, but {@link #modelValue} renders {@code INDETERMINATE} and
   * {@code NOT_CONFIGURED} identically as {@code null}, so the rendering cannot say which. Every
   * null-valued model key is therefore ambiguous and must be dropped rather than reported as a
   * difference against a digest this runtime can now read.
   *
   * <p><strong>Honest limit:</strong> because they are dropped, a model ADDED since such a commit
   * (stored {@code null}, expected a real digest) is not caught by the fallback. That is a real
   * miss, and it is still strictly better than the alternative it replaces — declining to compare
   * anything at all, which missed the vector dimension too. It closes on the next commit, which
   * records a digest.
   */
  public static List<String> nullModelInputs(String storedJson) {
    JsonNode stored = storedJson == null ? null : parseOrNull(storedJson);
    if (stored == null || !stored.isObject()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String key : MODEL_INPUT_KEYS) {
      JsonNode value = stored.path(key);
      if (value.isMissingNode() || value.isNull()) {
        out.add(key);
      }
    }
    return List.copyOf(out);
  }

  private static ModelFingerprint safeGet(Supplier<ModelFingerprint> supplier) {
    try {
      ModelFingerprint fp = supplier.get();
      return fp == null ? ModelFingerprint.indeterminate() : fp;
    } catch (RuntimeException e) {
      // A provider that throws has not told us the model is absent — it has told us nothing.
      return ModelFingerprint.indeterminate();
    }
  }
}
