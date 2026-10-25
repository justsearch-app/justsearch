/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;

/**
 * Maps {@link io.justsearch.indexing.api.IndexDocument} instances to Lucene Documents using a field catalog.
 *
 * <p>This class supports two modes of operation:
 * <ol>
 *   <li><b>Explicit injection (recommended):</b> Pass a {@link FieldCatalogDef} to the constructor.
 *       This is the IoC-compliant approach used by production code and tests.</li>
 *   <li><b>Auto-discovery (legacy):</b> The default constructor uses {@link JustSearchConfigurationLoader}
 *       to find the SSOT catalog. This is provided for backward compatibility.</li>
 * </ol>
 */
public final class FieldMapper {
  private static final Logger log = LoggerFactory.getLogger(FieldMapper.class);
  private static final ObjectMapper M = new ObjectMapper();

  // Track unknown fields to warn only once per field name (avoid log spam)
  private final java.util.Set<String> warnedUnknownFields = java.util.concurrent.ConcurrentHashMap.newKeySet();

  static final class FieldDef {
    final String id;
    final String type;
    final boolean stored;
    final boolean docValues;
    final List<String> roles;
    final Integer vectorDim; // nullable
    final String analyzerKey; // nullable
    final boolean multiValued;
    final String rmwPolicy; // nullable (tempdoc 711)

    FieldDef(String id, String type, boolean stored, boolean docValues, List<String> roles, Integer vectorDim, String analyzerKey, boolean multiValued, String rmwPolicy) {
      this.id = id;
      this.type = type;
      this.stored = stored;
      this.docValues = docValues;
      this.roles = roles;
      this.vectorDim = vectorDim;
      this.analyzerKey = analyzerKey;
      this.multiValued = multiValued;
      this.rmwPolicy = rmwPolicy;
    }

    /** Creates a FieldDef from the configuration POJO. */
    static FieldDef fromPojo(FieldCatalogDef.FieldDef pojo) {
      return new FieldDef(
          pojo.id(),
          pojo.type(),
          pojo.stored(),
          pojo.docValues(),
          pojo.roles(),
          pojo.vectorDimension(),
          pojo.analyzer(),
          pojo.multiValued(),
          pojo.rmwPolicy()
      );
    }
  }

  private final Map<String, FieldDef> byId;
  private final FieldDef primaryKeyField;
  private final FieldDef docUidField;
  private final Map<String, List<String>> statusWitnessFields;

  /**
   * Creates a FieldMapper from an explicit {@link FieldCatalogDef}.
   *
   * <p>This is the recommended constructor for production code and tests.
   *
   * @param catalog the field catalog definition (must not be null)
   */
  public FieldMapper(FieldCatalogDef catalog) {
    Objects.requireNonNull(catalog, "catalog");
    this.byId = convertFromPojo(catalog);
    validateMultiValuedConstraints(byId);
    this.primaryKeyField = resolvePrimaryKey(byId);
    this.docUidField = resolveDocUid(byId);
    this.statusWitnessFields = deriveStatusWitnessFields(byId);
  }

  /**
   * Creates a FieldMapper from a specific path.
   *
   * @param catalogPath path to the JSON catalog file
   */
  FieldMapper(Path catalogPath) {
    this(loadCatalogFromPath(catalogPath, true));
  }

  /**
   * Creates a FieldMapper from a parsed JSON tree.
   *
   * @param catalog the parsed JSON root node
   */
  FieldMapper(JsonNode catalog) {
    this(loadCatalogTree(catalog));
  }

  private FieldMapper(Map<String, FieldDef> catalog) {
    this.byId = Objects.requireNonNull(catalog, "catalog");
    validateMultiValuedConstraints(byId);
    this.primaryKeyField = resolvePrimaryKey(byId);
    this.docUidField = resolveDocUid(byId);
    this.statusWitnessFields = deriveStatusWitnessFields(byId);
  }

  /**
   * Builds the Lucene document, reporting any dense-vector field dropped because its value could
   * not be normalized (tempdoc 931 §C.3).
   *
   * <p>A non-finite embedding used to abort the whole write: Lucene's {@code KnnFloatVectorField}
   * rejects it with {@link IllegalArgumentException} and no caller caught it per document, so one
   * bad vector in a batch lost every other document in it. The document is worth
   * far more than the one field — it stays lexically searchable — so the field is dropped, the
   * paired {@code *_status} in the same write is corrected to {@code FAILED} so backfill accounting
   * stays truthful (and the write-time status/artifact contract is not handed a lie), and the drop
   * is reported to {@code report} for the caller's per-batch WARN and counter.
   *
   * @param report accumulator for dropped vector fields, or null when the caller does not count
   */
  Document toDocument(Map<String, Object> fields, DroppedVectorReport report) {
    Document doc = new Document();
    int added = 0;
    if (fields != null) {
      Map<String, String> droppedStatusTargets = detectUnusableVectors(fields, report);
      for (Map.Entry<String, Object> e : fields.entrySet()) {
        FieldDef def = byId.get(e.getKey());
        if (def == null) {
          // Warn once per unknown field name to help diagnose silent data loss
          if (warnedUnknownFields.add(e.getKey())) {
            log.warn("Unknown field '{}' ignored - not defined in SSOT field catalog. "
                + "Add to SSOT/catalogs/fields.v1.json if this field should be indexed.", e.getKey());
          }
          continue;
        }
        if (droppedStatusTargets.containsKey(def.id)) continue;
        Object value =
            droppedStatusTargets.containsValue(def.id)
                ? SchemaFields.EMBEDDING_STATUS_FAILED
                : e.getValue();
        added += addFields(doc, def, value);
      }
    }
    if (added == 0) {
      // Ensure at least one field to avoid empty document edge cases
      doc.add(new StoredField("_ingest_ts", System.currentTimeMillis()));
    }
    return doc;
  }

  /** Representative inputs exercise every configured mapping in the disposable health index. */
  Document ingestionCanaryDocument() {
    Map<String, Object> fields = new HashMap<>();
    for (FieldDef def : byId.values()) {
      Object value = switch (def.type) {
        case "text" -> "JustSearch ingestion canary";
        case "keyword" -> def.multiValued ? List.of("canary", "probe") : "canary";
        case "long" -> 1L;
        case "boolean" -> true;
        case "splade" -> Map.of("canary", 1.0f);
        case "vector" -> {
          // Dimension zero disables vectors in test catalogs; no valid Lucene vector exists.
          if (def.vectorDim != null && def.vectorDim <= 0) yield null;
          float[] vector = new float[def.vectorDim == null ? 1 : def.vectorDim];
          vector[0] = 1.0f;
          yield vector;
        }
        default -> null; // addFields also ignores catalog types it does not implement.
      };
      fields.put(def.id, value);
    }
    return toDocument(fields, null);
  }

  /**
   * Vector fields in this write whose value cannot be indexed, mapped to the status field that
   * witnesses them (or the empty string when the catalog pairs none). Detection runs before the
   * build loop so the paired status can be corrected regardless of map iteration order.
   */
  private Map<String, String> detectUnusableVectors(
      Map<String, Object> fields, DroppedVectorReport report) {
    Map<String, String> dropped = java.util.Collections.emptyMap();
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      FieldDef def = byId.get(e.getKey());
      if (def == null || !"vector".equals(def.type)) continue;
      String reason = vectorRejectionReason(def, e.getValue());
      if (reason == null) continue;
      if (dropped.isEmpty()) dropped = new HashMap<>();
      String statusTarget = rmwPolicyStatusTarget(def.rmwPolicy);
      dropped.put(def.id, statusTarget == null ? "" : statusTarget);
      if (report != null) {
        report.record(asString(fields.get(primaryKeyField.id)), def.id, reason);
      }
    }
    return dropped;
  }

  /**
   * Why {@code value} cannot be written to {@code def}, or null when it can. Only the rejection
   * Lucene itself would raise is reported — a NaN or infinite component, which {@code
   * KnnFloatVectorField} refuses for every similarity. A null value or a dimension mismatch are
   * separate, already-handled conditions ({@code addFields} skips the former and throws on the
   * latter, which {@code IndexingCoordinator.validate} catches at the front door). A zero vector is
   * legal under the catalog's current (Euclidean) similarity and is written as-is; a similarity
   * that normalizes (dot product) must add its zero-magnitude rejection here.
   */
  private static String vectorRejectionReason(FieldDef def, Object value) {
    float[] vec = asFloatArray(value);
    if (vec == null) return null;
    if (def.vectorDim != null && vec.length != def.vectorDim) return null;
    for (int i = 0; i < vec.length; i++) {
      if (!Float.isFinite(vec[i])) {
        return "non-finite value " + vec[i] + " at index " + i + " in vector field " + def.id;
      }
    }
    return null;
  }

  /**
   * Per-write accumulator for dropped dense-vector fields: a count for the metric, a bounded sample
   * of document ids for one WARN, and the last rejection reason. Not thread-safe by design — each
   * write lane creates its own and reads it on the same thread.
   */
  static final class DroppedVectorReport {
    private static final int MAX_SAMPLE = 3;

    private int count;
    private final List<String> sampleDocIds = new ArrayList<>(MAX_SAMPLE);
    private String lastReason;

    void record(String docId, String fieldId, String reason) {
      count++;
      lastReason = reason;
      if (sampleDocIds.size() < MAX_SAMPLE) {
        sampleDocIds.add((docId == null ? "<no id>" : docId) + "/" + fieldId);
      }
    }

    int count() {
      return count;
    }

    List<String> sampleDocIds() {
      return List.copyOf(sampleDocIds);
    }

    String lastReason() {
      return lastReason;
    }
  }

  Integer ssotVectorDimensionOrNull() {
    FieldDef def = byId.get("vector");
    return def == null ? null : def.vectorDim;
  }

  FieldDef fieldDef(String id) { return byId.get(id); }

  Map<String, FieldDef> fieldDefs() { return java.util.Collections.unmodifiableMap(byId); }

  String idField() { return primaryKeyField.id; }

  /** RMW policy that re-reads the field from the index and carries it forward (tempdoc 711). */
  static final String RMW_PRESERVE_REREAD = "preserve-reread";
  /** RMW policy prefix that resets a named docValues-backed status field so a backfill re-derives. */
  static final String RMW_RESET_STATUS_PREFIX = "reset-status:";
  /**
   * RMW policy prefix (tempdoc 717): preserve-reread with a reset-status fallback. Re-reads the
   * vector; if the re-read returns null (a documented-normal outcome — the value is genuinely
   * absent in the snapshot), resets the named docValues-backed status field so a backfill
   * re-derives, instead of silently carrying nothing forward and leaving the status lying (the
   * F-032 "status lies" hole that plain {@code preserve-reread} left open).
   */
  static final String RMW_PRESERVE_REREAD_OR_RESET_PREFIX = "preserve-reread-or-reset:";
  /** RMW policy for indexed chunk text reconstructed exactly from its stored parent offsets. */
  static final String RMW_REDERIVE_PARENT_SLICE = "rederive-parent-slice";

  /**
   * Fail-fast catalog validation (tempdoc 711, generalized to every type by tempdoc 714): every
   * non-stored, non-docValues field — regardless of type — must declare a parseable
   * {@code rmwPolicy} so the read-modify-write choke point knows how to preserve it; stored or
   * docValues-backed fields, which survive RMW already, must NOT declare one.
   * {@code preserve-reread} is only legal on {@code vector} fields (the engine's re-read lane is a
   * float-vector read-back; on any other type it would silently no-op instead of preserving). A
   * {@code reset-status:<target>} target must exist in the catalog and be docValues-backed. Called
   * alongside {@link #validatePrimaryKeySupport()}.
   */
  void validateRmwPolicies() {
    for (FieldDef def : byId.values()) {
      boolean fragile = !def.stored && !def.docValues;
      if (fragile) {
        String policy = def.rmwPolicy;
        if (policy == null || policy.isBlank()) {
          throw new IllegalStateException(
              "Field " + def.id + " (type " + def.type + ") is non-stored and non-docValues "
                  + "but declares no rmwPolicy — RMW would silently destroy it "
                  + "(tempdocs 711/714)");
        }
        if (policy.equals(RMW_PRESERVE_REREAD)) {
          if (!"vector".equals(def.type)) {
            throw new IllegalStateException(
                "Field " + def.id + " (type " + def.type + ") declares rmwPolicy 'preserve-reread' "
                    + "but only vector fields support index re-read — the engine would silently "
                    + "no-op instead of preserving (tempdoc 714)");
          }
          continue;
        }
        if (policy.startsWith(RMW_PRESERVE_REREAD_OR_RESET_PREFIX)) {
          // Tempdoc 717: preserve-reread with a reset-status fallback. The re-read lane is a
          // float-vector read-back (vector-only), and a null re-read must downgrade the paired
          // status so a backfill re-derives rather than leaving it lying (the F-032 hole).
          if (!"vector".equals(def.type)) {
            throw new IllegalStateException(
                "Field " + def.id + " (type " + def.type + ") declares rmwPolicy "
                    + "'preserve-reread-or-reset' but only vector fields support index re-read "
                    + "(tempdoc 717)");
          }
          validateResetTarget(
              def, policy.substring(RMW_PRESERVE_REREAD_OR_RESET_PREFIX.length()));
          continue;
        }
        if (policy.startsWith(RMW_RESET_STATUS_PREFIX)) {
          validateResetTarget(def, policy.substring(RMW_RESET_STATUS_PREFIX.length()));
          continue;
        }
        if (policy.equals(RMW_REDERIVE_PARENT_SLICE)) {
          if (!SchemaFields.CHUNK_CONTENT.equals(def.id) || !"text".equals(def.type)) {
            throw new IllegalStateException(
                "rmwPolicy 'rederive-parent-slice' is only supported on the chunk_content text field");
          }
          // Tempdoc 931 §C.1: the re-slice is only safe against the parent revision the chunk was
          // cut from, so the catalog must also carry the stored revision hash the guard compares.
          FieldDef revision = byId.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
          if (revision == null || !revision.stored) {
            throw new IllegalStateException(
                "rmwPolicy 'rederive-parent-slice' requires a stored "
                    + SchemaFields.CHUNK_PARENT_CONTENT_SHA256
                    + " field — without it the RMW re-slice cannot tell the parent revision the"
                    + " chunk was cut from apart from a later rewrite (tempdoc 931)");
          }
          continue;
        }
        throw new IllegalStateException(
            "Field " + def.id + " has unknown rmwPolicy '" + policy + "'");
      }
      if (def.rmwPolicy != null && !def.rmwPolicy.isBlank()) {
        throw new IllegalStateException(
            "Field " + def.id + " declares rmwPolicy '" + def.rmwPolicy + "' but is stored or "
                + "docValues-backed (RMW preserves it already) — remove the policy");
      }
    }
  }

  /**
   * Validates a reset-status target field name (shared by the {@code reset-status:} and {@code
   * preserve-reread-or-reset:} lanes): it must exist in the catalog and be docValues-backed so
   * {@code WritePathOps} can read and restore the status across an RMW.
   */
  private void validateResetTarget(FieldDef def, String target) {
    FieldDef targetDef = byId.get(target);
    if (targetDef == null) {
      throw new IllegalStateException(
          "Field " + def.id + " rmwPolicy reset target '" + target
              + "' does not exist in the catalog");
    }
    if (!targetDef.docValues) {
      throw new IllegalStateException(
          "Field " + def.id + " rmwPolicy reset target '" + target
              + "' must be docValues-backed so the status can be restored across RMW");
    }
  }

  /** Catalog fields that declare an {@code rmwPolicy} (drives the RMW preservation engine). */
  List<FieldDef> rmwPolicyFields() {
    List<FieldDef> out = new ArrayList<>();
    for (FieldDef def : byId.values()) {
      if (def.rmwPolicy != null && !def.rmwPolicy.isBlank()) out.add(def);
    }
    return out;
  }

  /**
   * The status field a {@code reset-status:} / {@code preserve-reread-or-reset:} policy names, or
   * null for a policy that names none ({@code preserve-reread}) or no policy at all. Single parse
   * point for the policy suffix, shared by the RMW engine's inversion below.
   */
  static String rmwPolicyStatusTarget(String policy) {
    if (policy == null || policy.isBlank()) return null;
    if (policy.startsWith(RMW_PRESERVE_REREAD_OR_RESET_PREFIX)) {
      return policy.substring(RMW_PRESERVE_REREAD_OR_RESET_PREFIX.length());
    }
    if (policy.startsWith(RMW_RESET_STATUS_PREFIX)) {
      return policy.substring(RMW_RESET_STATUS_PREFIX.length());
    }
    return null;
  }

  /**
   * Status field -&gt; the artifact fields that witness a {@code COMPLETED} value on it, derived by
   * inverting the catalog's existing {@code rmwPolicy} declarations (tempdoc 798). The pairing is
   * already declared there — {@code vector} names {@code embedding_status}, {@code chunk_vector}
   * names {@code chunk_embedding_status}, {@code splade} names {@code splade_status} — so the
   * write-time contract needs no second catalog key that could drift from this one.
   *
   * <p>Keys are sorted so a violation is reported deterministically.
   */
  Map<String, List<String>> statusWitnessFields() {
    return statusWitnessFields;
  }

  private static Map<String, List<String>> deriveStatusWitnessFields(Map<String, FieldDef> byId) {
    java.util.TreeMap<String, List<String>> out = new java.util.TreeMap<>();
    for (FieldDef def : byId.values()) {
      String status = rmwPolicyStatusTarget(def.rmwPolicy);
      if (status == null) continue;
      out.computeIfAbsent(status, k -> new ArrayList<>()).add(def.id);
    }
    out.replaceAll(
        (k, v) -> {
          java.util.Collections.sort(v);
          return List.copyOf(v);
        });
    return java.util.Collections.unmodifiableMap(out);
  }

  void validatePrimaryKeySupport() {
    if (!primaryKeyField.docValues) {
      throw new IllegalStateException("Primary key field " + primaryKeyField.id + " must be DocValues-backed");
    }
    if (docUidField == null) {
      throw new IllegalStateException("Field catalog missing doc_uid tiebreaker");
    }
    if (!docUidField.docValues) {
      throw new IllegalStateException("Field doc_uid must be DocValues-backed");
    }
    for (FieldDef def : byId.values()) {
      if (def.roles.contains("id") && !def.docValues) {
        throw new IllegalStateException("Field " + def.id + " carries role id but lacks DocValues");
      }
    }
  }

  /**
   * Whether {@code value} would actually materialize at least one Lucene field for {@code fieldId} —
   * the same predicate {@link #addFields} applies, expressed once here so callers ask "did this
   * write bring data?" instead of the weaker "is the key non-null?".
   *
   * <p>Motivating case (tempdoc 798): a {@code splade} weight map that is empty, or whose weights
   * are all &lt;= 0, is non-null yet produces zero {@code FeatureField} postings — a null check would
   * accept it, and the resulting data-less {@code COMPLETED} is exactly the livelock the write-time
   * {@link StatusArtifactContract} exists to stop. Keeping the predicate next to {@code addFields}
   * is what stops the two from drifting apart.
   */
  boolean wouldMaterialize(String fieldId, Object value) {
    FieldDef def = byId.get(fieldId);
    if (def == null) return value != null;
    if ("splade".equals(def.type)) {
      if (!(value instanceof Map<?, ?> sparseVec)) return false;
      for (Object weight : sparseVec.values()) {
        if (weight instanceof Number n && n.floatValue() > 0.0f) return true;
      }
      return false;
    }
    if ("vector".equals(def.type)) {
      float[] vec = asFloatArray(value);
      return vec != null && (def.vectorDim == null || vec.length == def.vectorDim);
    }
    return value != null;
  }

  private int addFields(Document doc, FieldDef def, Object value) {
    int count = 0;
    switch (def.type) {
      case "text" -> {
        String s = asString(value);
        if (s != null) {
          doc.add(new TextField(def.id, s, def.stored ? Field.Store.YES : Field.Store.NO));
          count++;
        }
      }
      case "keyword" -> {
        if (def.multiValued && def.docValues) {
          List<String> values = asList(value);
          for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            if (def.roles != null && def.roles.contains("filter")) {
              doc.add(new StringField(def.id, v, def.stored ? Field.Store.YES : Field.Store.NO));
            } else if (def.stored) {
              doc.add(new StoredField(def.id, v));
            }
            doc.add(new SortedSetDocValuesField(def.id, new BytesRef(v)));
            count++;
          }
        } else {
          String s = asString(value);
          if (s != null && def.docValues) {
            // Create StringField (inverted index) if primary key OR has filter role
            // This enables O(log n) TermQuery lookups for status fields
            if (def == primaryKeyField || (def.roles != null && def.roles.contains("filter"))) {
              doc.add(new StringField(def.id, s, def.stored ? Field.Store.YES : Field.Store.NO));
            } else if (def.stored) {
              doc.add(new StoredField(def.id, s));
            }
            // DocValues for sorting/faceting
            doc.add(new SortedDocValuesField(def.id, new BytesRef(s)));
            count++;
          } else if (s != null && def.stored) {
            // Stored-only keyword: no postings, no doc-values column — payload the reader fetches
            // by doc id (chunk_parent_content_sha256, tempdoc 931 §C.1). Without this branch the
            // catalog could declare such a field and the mapper would silently write nothing.
            doc.add(new StoredField(def.id, s));
            count++;
          }
        }
      }
      case "long" -> {
        Long v = asLong(value);
        if (v != null && def.docValues) {
          doc.add(new NumericDocValuesField(def.id, v));
          if (def.roles != null && def.roles.contains("filter")) {
            doc.add(new LongPoint(def.id, v));
          }
          if (def.stored) doc.add(new StoredField(def.id, v));
          count++;
        }
      }
      case "boolean" -> {
        Boolean b = asBoolean(value);
        if (b != null && def.docValues) {
          doc.add(new NumericDocValuesField(def.id, b ? 1L : 0L));
          if (def.stored) doc.add(new StoredField(def.id, b ? 1 : 0));
          count++;
        }
      }
      case "vector" -> {
        float[] vec = asFloatArray(value);
        if (vec != null) {
          if (def.vectorDim != null && vec.length != def.vectorDim) {
            throw new IllegalArgumentException("vector dimension mismatch for " + def.id + ": expected " + def.vectorDim + ", got " + vec.length);
          }
          doc.add(new KnnFloatVectorField(def.id, vec));
          count++;
        }
      }
      case "splade" -> {
        @SuppressWarnings("unchecked")
        Map<String, Float> sparseVec = (Map<String, Float>) value;
        if (sparseVec != null) {
          for (var entry : sparseVec.entrySet()) {
            float weight = Math.min(entry.getValue(), 64.0f);
            if (weight > 0.0f) {
              doc.add(new FeatureField(def.id, entry.getKey(), weight));
              count++;
            }
          }
        }
      }
      default -> {
        // ignore unknown types
      }
    }
    return count;
  }

  /**
   * Converts from the configuration POJO to the internal map structure.
   */
  private static Map<String, FieldDef> convertFromPojo(FieldCatalogDef catalog) {
    Map<String, FieldDef> map = new HashMap<>();
    for (FieldCatalogDef.FieldDef field : catalog.fields()) {
      map.put(field.id(), FieldDef.fromPojo(field));
    }
    return map;
  }

  private static Map<String, FieldDef> loadCatalogFromPath(Path path, boolean explicit) {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) {
      String msg = "Field catalog not found: " + path;
      if (explicit) {
        throw new IllegalStateException(msg);
      }
      log.warn(msg);
      throw new IllegalStateException(msg);
    }
    try (InputStream in = Files.newInputStream(path)) {
      return loadCatalogTree(M.readTree(in));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load field catalog: " + path, e);
    }
  }

  private static Map<String, FieldDef> loadCatalogTree(JsonNode root) {
    Objects.requireNonNull(root, "root");
    Map<String, FieldDef> map = new HashMap<>();
    for (JsonNode n : root.withArray("fields")) {
      String id = n.path("id").asText();
      String type = n.path("type").asText();
      boolean stored = n.path("stored").asBoolean(false);
      boolean docValues = n.path("docValues").asBoolean(false);
      List<String> roles = new ArrayList<>();
      for (JsonNode roleNode : n.withArray("roles")) roles.add(roleNode.asText());
      Integer dim = n.has("vector") ? n.path("vector").path("dimension").asInt() : null;
      if (dim != null && dim == 0) dim = null;
      String analyzer = n.has("analyzer") ? n.path("analyzer").asText(null) : null;
      if (analyzer != null && analyzer.isBlank()) analyzer = null;
      boolean multiValued = n.path("multiValued").asBoolean(false);
      String rmwPolicy = n.has("rmwPolicy") ? n.path("rmwPolicy").asText(null) : null;
      if (rmwPolicy != null && rmwPolicy.isBlank()) rmwPolicy = null;
      map.put(id, new FieldDef(id, type, stored, docValues, roles, dim, analyzer, multiValued, rmwPolicy));
    }
    return map;
  }

  /**
   * Converts a value to a list of strings for multi-valued field indexing.
   * Null inputs return empty list. List inputs have null entries removed.
   * Non-list inputs are wrapped as a single-element list via {@link #asString}.
   * Nested lists are stringified (not flattened) — callers should pass flat lists.
   */
  private static List<String> asList(Object v) {
    if (v == null) return List.of();
    if (v instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        if (item != null) result.add(String.valueOf(item));
      }
      return result;
    }
    String s = asString(v);
    return s != null ? List.of(s) : List.of();
  }

  private static String asString(Object v) {
    if (v == null) return null;
    if (v instanceof String s) return s;
    return String.valueOf(v);
  }

  private static Long asLong(Object v) {
    if (v instanceof Number n) return n.longValue();
    try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignored) { return null; }
  }

  private static Boolean asBoolean(Object v) {
    if (v instanceof Boolean b) return b;
    if (v instanceof Number n) return n.longValue() != 0L;
    if (v != null) return Boolean.parseBoolean(String.valueOf(v).toLowerCase(Locale.ROOT));
    return null;
  }

  @SuppressWarnings("unchecked")
  private static float[] asFloatArray(Object v) {
    if (v == null) return null;
    if (v instanceof float[] fa) return fa;
    if (v instanceof double[] da) {
      float[] out = new float[da.length];
      for (int i = 0; i < da.length; i++) out[i] = (float) da[i];
      return out;
    }
    if (v instanceof List<?> list) {
      float[] out = new float[list.size()];
      for (int i = 0; i < list.size(); i++) out[i] = ((Number) list.get(i)).floatValue();
      return out;
    }
    return null;
  }

  private static void validateMultiValuedConstraints(Map<String, FieldDef> fields) {
    for (FieldDef def : fields.values()) {
      if (def.multiValued && !def.docValues) {
        throw new IllegalStateException(
            "Multi-valued field '" + def.id + "' requires docValues=true");
      }
      if (def.multiValued && def.roles != null && def.roles.contains("id")) {
        throw new IllegalStateException(
            "Multi-valued field '" + def.id + "' cannot have 'id' role");
      }
    }
  }

  private static FieldDef resolvePrimaryKey(Map<String, FieldDef> fields) {
    return fields.values().stream()
        .filter(def -> def.roles != null && def.roles.contains("id"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Field catalog must define a primary key field with role 'id'"));
  }

  private static FieldDef resolveDocUid(Map<String, FieldDef> fields) {
    return fields.get(SchemaFields.DOC_UID);
  }
}
