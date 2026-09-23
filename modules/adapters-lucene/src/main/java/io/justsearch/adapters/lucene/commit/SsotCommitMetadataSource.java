/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds commit metadata from SSOT artifacts with deterministic hashing and canonical JSON.
 */
public final class SsotCommitMetadataSource implements CommitMetadataSource {
  private static final ObjectMapper M =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  /**
   * The similarity Lucene's two-arg {@code KnnFloatVectorField} constructor applies when the
   * catalog does not declare one. Named here so the fingerprint records the similarity actually in
   * force, and so declaring {@code vector.similarity} in the catalog later moves the fingerprint.
   */
  private static final String DEFAULT_VECTOR_SIMILARITY = "euclidean";

  private final File repoRoot;
  private final SsotAnalyzerRegistry analyzerRegistry;
  private final SsotAnalyzerRegistry.AnalyzerFingerprintingService fingerprintingService;
  private final ResolvedConfig resolvedConfigSnapshot;
  private final RuntimeFingerprintInputs runtimeFingerprintInputs;
  private volatile String cachedAnalyzerFingerprint;

  public SsotCommitMetadataSource() {
    this(null, false, null);
  }

  /** Builds metadata from one captured configuration snapshot. */
  public SsotCommitMetadataSource(ResolvedConfig snapshot) {
    this(snapshot, true, null);
  }

  /**
   * Builds metadata for one runtime from captured configuration and model inputs.
   *
   * <p>The supplied inputs belong to this source for its lifetime. This lets co-resident Lucene
   * runtimes commit and check parity against their own encoder generation while legacy callers can
   * continue using the process-wide providers through the older constructors.
   */
  public SsotCommitMetadataSource(
      ResolvedConfig snapshot, RuntimeFingerprintInputs runtimeFingerprintInputs) {
    this(
        snapshot,
        true,
        Objects.requireNonNull(runtimeFingerprintInputs, "runtimeFingerprintInputs"));
  }

  private SsotCommitMetadataSource(
      ResolvedConfig snapshot,
      boolean requireSnapshot,
      RuntimeFingerprintInputs runtimeFingerprintInputs) {
    if (requireSnapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
    }
    this.resolvedConfigSnapshot = snapshot;
    this.runtimeFingerprintInputs = runtimeFingerprintInputs;
    this.repoRoot = resolveRepoRoot();
    this.analyzerRegistry = new SsotAnalyzerRegistry();
    this.fingerprintingService = new SsotAnalyzerRegistry.AnalyzerFingerprintingService();
  }

  /**
   * Immutable runtime-owned inputs that are not derivable from {@link ResolvedConfig}.
   *
   * <p>A {@code null} vector dimension means the field catalog's declaration is effective. Model
   * fingerprints remain tri-state so an unavailable digest stays distinct from an unconfigured
   * role.
   */
  public record RuntimeFingerprintInputs(
      Integer effectiveVectorDimension,
      IndexFingerprint.ModelFingerprint embeddingModel,
      IndexFingerprint.ModelFingerprint spladeModel,
      IndexFingerprint.ModelFingerprint nerModel) {
    public RuntimeFingerprintInputs {
      Objects.requireNonNull(embeddingModel, "embeddingModel");
      Objects.requireNonNull(spladeModel, "spladeModel");
      Objects.requireNonNull(nerModel, "nerModel");
    }
  }

  private static File resolveRepoRoot() {
    java.nio.file.Path root = JustSearchConfigurationLoader.repoRootStatic();
    if (root == null) {
      throw new IllegalStateException("Repository root not found (no SSOT directory)");
    }
    return root.toFile();
  }

  @Override
  public Map<String, Object> build() {
    try {
      ResolvedConfig resolved = configForCallOrNull();
      Map<String, Object> out = new LinkedHashMap<>();

      // versions/catalog.json — grammar/template observability only. The index's identity no
      // longer depends on this file: `schema_ver` used to be sourced from `intent_v1.schema_ver`
      // (the search-intent grammar version, pinned at "1.0.0" since 2026-01-04), which made it a
      // rebuild-requiring parity key that could never fire (tempdoc 915 §B, tempdoc 804).
      JsonNode versions = M.readTree(file("SSOT/versions/catalog.json"));
      String grammarVer = versions.path("intent_v1").path("grammar_ver").asText();
      int templateVer = versions.path("intent_v1").path("template_ver").get(0).asInt();

      // required hashes (canonical JSON for JSON, raw bytes concatenation for text/gbnf)
      out.put("schema_fp", sha256Json(file("SSOT/schemas/domain/search-intent.schema.json")));
      String fieldCatalogHash = sha256Json(file("SSOT/catalogs/fields.v1.json"));
      out.put("field_catalog_hash", fieldCatalogHash);
      // The one rebuild-requiring key: a hash over the effective *physical* index shape. Absent
      // when a configured model's digest is unresolvable — see IndexFingerprint's class Javadoc on
      // why an indeterminate input must not be stamped as an answer.
      //
      // The canonical INPUTS are stamped unconditionally beside it, including on the boot where the
      // digest could not be computed. That is the point: an index whose shape was recorded while a
      // model file was unreadable still recorded everything that model does not touch, so a later
      // boot in the same state can still tell a changed vector dimension from an unchanged one
      // (tempdoc 931 §C.5). It is the same statement as the digest, not a second one — see
      // IndexFingerprint.COMMIT_META_INPUTS_KEY on why it is not a parity key.
      IndexFingerprint.Inputs inputs = fingerprintInputs(resolved);
      IndexFingerprint.compute(inputs).ifPresent(fp -> out.put(IndexFingerprint.COMMIT_META_KEY, fp));
      out.put(
          IndexFingerprint.COMMIT_META_INPUTS_KEY,
          new String(
              IndexFingerprint.canonicalJson(inputs), java.nio.charset.StandardCharsets.UTF_8));
      // Per-language synonym lists were removed in tempdoc 581 §13 / ADR-0043 (native
      // multilingual, no per-language levers). synonyms_hash is retained as a commit-metadata /
      // observability identity field (consumed by telemetry spans + jseval) and is now the
      // SHA-256 of the empty synonym set. It is NOT a parity key, so this value change does not
      // affect existing on-disk indices.
      out.put("synonyms_hash", sha256Bytes(new byte[0]));

      // grammar/templates/prompts
      out.put("grammar_ver", grammarVer);
      out.put("grammar_hash", sha256Bytes(Files.readAllBytes(file("SSOT/artifacts/grammars/intent_v1.gbnf").toPath())));
      out.put("template_ver", templateVer);
      out.put("prompt_pack_hash", sha256Concat(List.of(
          file("SSOT/prompts/en/intent.v1.json"),
          file("SSOT/prompts/en/summary.v1.json"))));

      // Query-time scoring descriptors. Neither changes a byte on disk, so neither is a parity
      // key: similarity_fp is BM25 k1/b (observability only), boosts_fp is the one *benign*
      // parity key — a mismatch means the running config disagrees with the index, which is worth
      // reporting but never worth a reindex.
      out.put("similarity_fp", sha256Bytes(
          similarityDescriptor(resolved).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      // boosts fingerprint from app-config index.boosts (deterministic)
      String boostsJson = boostsCanonicalJson(resolved);
      out.put("boosts_fp", sha256Bytes(boostsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

      // feature toggle for grammar (default ON in this slice)
      out.put("grammar_on", true);

      // Vector storage format stamp. Also an index_fingerprint input (a different
      // KnnVectorsFormat is a different on-disk encoding); kept as its own key because
      // VectorFormatDetector and the status surface report it directly.
      out.put("vector_format", vectorFormat(resolved));

      return Map.copyOf(out);
    } catch (IOException e) {
      throw new IllegalStateException("Failed building commit metadata from SSOT", e);
    }
  }

  private File file(String relative) { return new File(repoRoot, relative); }

  /**
   * Reduces a library version to {@code major.minor}. The analysis libraries are fingerprint
   * inputs because an upgrade can change the postings with every field descriptor unchanged, but
   * hashing the full version would make a patch release — which by both projects' compatibility
   * policy does not change analysis output — cost every install a full reindex. The residual
   * risk is accepted and named in tempdoc 915 §C.3: a patch that did change tokenisation would
   * go undetected.
   */
  static String majorMinor(String version) {
    if (version == null || version.isBlank()) {
      return "";
    }
    String[] parts = version.trim().split("\\.");
    if (parts.length < 2) {
      return version.trim();
    }
    return parts[0] + "." + parts[1];
  }

  /** {@code float32} unless vector quantization is enabled. */
  private static String vectorFormat(ResolvedConfig resolved) {
    try {
      boolean quantized =
          resolved != null && Boolean.TRUE.equals(resolved.index().vectorQuantizationEnabled());
      return quantized ? "int8_sq" : "float32";
    } catch (RuntimeException e) {
      return "float32";
    }
  }

  /**
   * The assembled {@link IndexFingerprint.Inputs} — the one assembly from which BOTH the digest
   * ({@link IndexFingerprint#compute}) and the canonical inputs JSON are rendered: two call sites
   * that each built their own would be free to disagree, and the whole value of the inputs key is
   * that it is the exact rendering the digest hashes.
   *
   * <p>The catalog is projected to its <em>physical</em> shape here rather than hashed as a file:
   * {@code rmwPolicy} is dropped because it cannot describe a stored or doc-values field (see
   * {@code FieldMapper.validateRmwPolicies}), so it never changes what is written. That single
   * exclusion is what the old {@code index_schema_fp} lacked, and why three annotation-only catalog
   * edits each falsely demanded a reindex (tempdoc 804).
   */
  private IndexFingerprint.Inputs fingerprintInputs(ResolvedConfig resolved) throws IOException {
    RuntimeFingerprintInputs runtimeInputs = runtimeFingerprintInputs;
    if (runtimeInputs == null) {
      runtimeInputs =
          new RuntimeFingerprintInputs(
              IndexFingerprint.effectiveVectorDimension(),
              IndexFingerprint.embeddingModel(),
              IndexFingerprint.spladeModel(),
              IndexFingerprint.nerModel());
    }
    return fingerprintInputs(resolved, runtimeInputs);
  }

  /** Worker candidate capture uses explicit identities without installing process-wide providers. */
  public IndexFingerprint.Inputs fingerprintInputs(ResolvedConfig resolved,
      Integer effectiveVectorDimension, IndexFingerprint.ModelFingerprint embeddingModel,
      IndexFingerprint.ModelFingerprint spladeModel, IndexFingerprint.ModelFingerprint nerModel)
      throws IOException {
    return fingerprintInputs(resolved, new RuntimeFingerprintInputs(effectiveVectorDimension,
        embeddingModel, spladeModel, nerModel));
  }

  /**
   * Assembles the physical index fingerprint inputs for one captured runtime.
   *
   * <p>The legacy metadata path reads the process-wide Worker providers. The runtime-bound
   * constructor supplies these values directly so a generation-changing producer can prepare a
   * different candidate while the serving runtime continues to use its own identity.
   *
   * @param resolved the candidate's captured resolved configuration, or {@code null} to preserve
   *     the no-configuration defaults used by {@link #build()}
   * @param runtimeInputs the runtime's captured vector dimension and model identities
   * @return the complete inputs used by {@link IndexFingerprint#compute}
   * @throws IOException when the SSOT catalog cannot be read
   */
  private IndexFingerprint.Inputs fingerprintInputs(
      ResolvedConfig resolved, RuntimeFingerprintInputs runtimeInputs)
      throws IOException {
    JsonNode catalog = M.readTree(file("SSOT/catalogs/fields.v1.json"));

    return new IndexFingerprint.Inputs(
            catalog.path("version").asText(),
            projectFields(catalog, runtimeInputs.effectiveVectorDimension()),
            analyzerFingerprint(),
            vectorFormat(resolved),
            new IndexFingerprint.Hnsw(
                resolved == null
                    ? ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M
                    : resolved.index().effectiveVectorHnswM(),
                resolved == null
                    ? ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION
                    : resolved.index().effectiveVectorHnswEfConstruction()),
            new IndexFingerprint.Chunking(
                ChunkSplitter.DEFAULT_CHUNK_TOKENS,
                ChunkSplitter.DEFAULT_OVERLAP_TOKENS,
                ChunkSplitter.MIN_CHUNK_TOKENS,
                ChunkSplitter.CHUNK_THRESHOLD_CHARS,
                ChunkSplitter.ALGORITHM_VERSION),
            ChunkSplitter.CONTENT_PREVIEW_MAX_CHARS,
            new IndexFingerprint.Analysis(
                majorMinor(org.apache.lucene.util.Version.LATEST.toString()),
                majorMinor(com.ibm.icu.util.VersionInfo.ICU_VERSION.toString())),
            runtimeInputs.embeddingModel(),
            runtimeInputs.spladeModel(),
            runtimeInputs.nerModel());
  }

  /**
   * Projects the catalog to the per-field properties that decide what is written to disk.
   *
   * <p>Package-private so the exclusions can be tested directly against two catalogs that differ
   * only in an annotation. That test is the guard on tempdoc 804's actual complaint: hashing the
   * catalog <em>file</em> made three annotation-only edits each demand a reindex of an index that
   * was physically identical to what the runtime would have written.
   *
   * @param catalog the parsed field catalog
   * @param effectiveDimension the runtime's vector dimension, or null to use each field's declared
   *     one
   */
  static List<IndexFingerprint.FieldShape> projectFields(
      JsonNode catalog, Integer effectiveDimension) {
    List<IndexFingerprint.FieldShape> fields = new ArrayList<>();
    for (JsonNode f : catalog.path("fields")) {
      JsonNode vector = f.path("vector");
      Integer declaredDimension =
          vector.isObject() && vector.has("dimension") ? vector.path("dimension").asInt() : null;
      Integer dimension =
          declaredDimension == null
              ? null
              : (effectiveDimension != null ? effectiveDimension : declaredDimension);
      String similarity =
          vector.isObject() && vector.has("similarity")
              ? vector.path("similarity").asText()
              : (declaredDimension == null ? null : DEFAULT_VECTOR_SIMILARITY);
      List<String> roles = new ArrayList<>();
      for (JsonNode role : f.path("roles")) {
        roles.add(role.asText());
      }
      fields.add(
          new IndexFingerprint.FieldShape(
              f.path("id").asText(),
              f.path("type").asText(),
              f.path("stored").asBoolean(false),
              f.path("docValues").asBoolean(false),
              f.path("multiValued").asBoolean(false),
              f.has("analyzer") ? f.path("analyzer").asText() : null,
              roles,
              dimension,
              similarity));
    }
    return fields;
  }

  private ResolvedConfig configForCallOrNull() {
    if (resolvedConfigSnapshot != null) {
      return resolvedConfigSnapshot;
    }
    try {
      return resolvedConfigOrFallback();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String analyzerFingerprint() {
    String fp = cachedAnalyzerFingerprint;
    if (fp != null) {
      return fp;
    }
    synchronized (this) {
      fp = cachedAnalyzerFingerprint;
      if (fp == null) {
        fp = fingerprintingService.fingerprint(analyzerRegistry, analyzerRegistry.analyzerIds());
        cachedAnalyzerFingerprint = fp;
      }
    }
    return fp;
  }

  private static String sha256Json(File jsonFile) throws IOException {
    JsonNode node = M.readTree(jsonFile);
    byte[] canonical = canonicalJson(node);
    return sha256Bytes(canonical);
  }

  private static String sha256Concat(List<File> files) throws IOException {
    List<File> sorted = files.stream().sorted((a, b) -> a.getPath().compareTo(b.getPath())).collect(Collectors.toList());
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    for (File f : sorted) bos.write(Files.readAllBytes(f.toPath()));
    return sha256Bytes(bos.toByteArray());
  }

  private static String sha256Bytes(byte[] b) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(b);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte x : digest) sb.append(String.format("%02x", x));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] canonicalJson(JsonNode node) throws IOException {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator g = M.tokenStreamFactory().createGenerator(bos)
            .configure(StreamWriteFeature.AUTO_CLOSE_TARGET, true)) {
      M.writeTree(g, node);
      return bos.toByteArray();
    }
  }

  private static String similarityDescriptor(ResolvedConfig resolved) {
    try {
      ResolvedConfig.Index idx = resolved != null ? resolved.index() : null;
      String cls = org.apache.lucene.search.similarities.BM25Similarity.class.getName();
      float k1 = idx != null && idx.similarityTextK1() != null
          ? idx.similarityTextK1().floatValue() : 0.9f;
      float b = idx != null && idx.similarityTextB() != null
          ? idx.similarityTextB().floatValue() : 0.4f;
      return cls + "(k1=" + trimFloat(k1) + ",b=" + trimFloat(b) + ")";
    } catch (Exception e) {
      return org.apache.lucene.search.similarities.BM25Similarity.class.getName()
          + "(k1=0.900,b=0.400)";
    }
  }

  /** Resolves config from ConfigStore if available, otherwise builds from RuntimeConfig. */
  private static ResolvedConfig resolvedConfigOrFallback() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store != null) {
      ResolvedConfig cfg = store.get();
      if (cfg != null) return cfg;
    }
    io.justsearch.configuration.resolved.ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeBaseSources();
    return builder.build();
  }

  private static String trimFloat(float v) {
    // Produce a stable short decimal for descriptor
    return String.format(java.util.Locale.ROOT, "%.3f", v);
  }

  private static String boostsCanonicalJson(ResolvedConfig resolved) throws IOException {
    try {
      Map<String, Double> boosts = resolved == null ? Map.of() : resolved.index().boosts();
      // boosts is already TreeMap-backed (deterministic key order) from ResolvedConfig
      return M.writeValueAsString(boosts);
    } catch (Exception e) {
      // Fallback for tests
      return "{}";
    }
  }
}
