/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility methods extracted from {@link LuceneLifecycleManager} for better organization and
 * testability.
 *
 * <p>All methods in this class are pure functions with zero state dependencies.
 */
public final class LuceneRuntimeUtils {
  private static final Logger log = LoggerFactory.getLogger(LuceneRuntimeUtils.class);

  /** Suffix for soft-delete timestamp field. */
  public static final String SOFT_DELETE_TS_SUFFIX = "_ts";

  /** Suffix for soft-delete version/ordinal field. */
  public static final String SOFT_DELETE_VERSION_SUFFIX = "_ordinal";

  private LuceneRuntimeUtils() {
    // Utility class - no instantiation
  }

  // ==========================================================================
  // Type Conversion Utilities
  // ==========================================================================

  /**
   * Converts a value to String, handling common types gracefully.
   *
   * @param v the value to convert (may be null)
   * @return String representation, or null if input is null
   */
  public static String asString(Object v) {
    if (v == null) return null;
    if (v instanceof String s) return s;
    if (v instanceof Number n) return String.valueOf(n);
    if (v instanceof Boolean b) return b ? "true" : "false";
    return String.valueOf(v);
  }

  /**
   * Converts a value to boolean, with lenient parsing for strings.
   *
   * @param value the value to convert
   * @return true if value represents a truthy value, false otherwise
   */
  public static boolean asBoolean(Object value) {
    if (value instanceof Boolean b) return b;
    if (value instanceof Number n) return n.longValue() != 0L;
    if (value instanceof String s) {
      String normalized = s.trim().toLowerCase(java.util.Locale.ROOT);
      if (normalized.isEmpty()) return false;
      return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes");
    }
    return false;
  }

  /**
   * Converts a value to Long, with safe parsing for strings.
   *
   * @param value the value to convert
   * @return Long representation, or null if conversion fails
   */
  public static Long asLong(Object value) {
    if (value instanceof Long l) return l;
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /**
   * Parses a string to Float, returning null on failure.
   *
   * @param raw the string to parse (may be null, empty, or "_" placeholder)
   * @return parsed Float, or null if parsing fails
   */
  public static Float parseFloatOrNull(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty() || "_".equals(s)) return null;
    try {
      return Float.parseFloat(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parses a string to Long, returning null on failure.
   *
   * @param raw the string to parse (may be null, empty, or "_" placeholder)
   * @return parsed Long, or null if parsing fails
   */
  public static Long parseLongOrNull(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty() || "_".equals(s)) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ==========================================================================
  // Exception Classification
  // ==========================================================================

  /**
   * Classifies an IOException into a specific reason for better error handling.
   *
   * @param e the IOException to classify
   * @return the classified reason
   */
  public static IndexRuntimeIOException.Reason classifyIOException(IOException e) {
    if (e instanceof CorruptIndexException) {
      return IndexRuntimeIOException.Reason.CORRUPT_INDEX;
    }
    // Treat missing Lucene segment files as corruption - these indicate partial/damaged index
    if (e instanceof java.nio.file.NoSuchFileException nsfe) {
      String path = nsfe.getFile();
      if (path != null && looksLikeLuceneSegmentFile(path)) {
        log.debug("Classifying NoSuchFileException for segment file as CORRUPT_INDEX: {}", path);
        return IndexRuntimeIOException.Reason.CORRUPT_INDEX;
      }
    }
    if (e instanceof LockObtainFailedException) {
      return IndexRuntimeIOException.Reason.LOCKED;
    }
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
    if (msg.contains("no space") || msg.contains("disk full")) {
      return IndexRuntimeIOException.Reason.DISK_FULL;
    }
    if (msg.contains("already closed")) {
      return IndexRuntimeIOException.Reason.CONFIGURATION;
    }
    return IndexRuntimeIOException.Reason.DISK_IO;
  }

  // ==========================================================================
  // Path/File Detection
  // ==========================================================================

  /**
   * Checks if a directory looks like a Lucene index directory by examining its contents.
   *
   * @param dir the directory to check
   * @return true if the directory appears to contain Lucene index files
   */
  public static boolean looksLikeLuceneIndexDirectory(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return false;
    }
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(
          p -> {
            String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            // segments_N is the canonical Lucene marker; include a few common fallbacks
            // to avoid false negatives on partially-written indexes.
            return n.startsWith("segments")
                || n.equals("write.lock")
                || n.endsWith(".si")
                || n.endsWith(".cfs")
                || n.endsWith(".cfe");
          });
    } catch (Exception ignored) {
      return false;
    }
  }

  /**
   * Checks if a file path looks like a Lucene segment file. Lucene segment files follow pattern:
   * _XX.ext or _XX_N.ext Common extensions: .vemq, .vec, .vem, .fdt, .fdx, .fdm, .fnm, .si, .liv,
   * etc.
   *
   * @param path the file path to check
   * @return true if the path appears to be a Lucene segment file
   */
  public static boolean looksLikeLuceneSegmentFile(String path) {
    if (path == null) return false;
    // Extract filename from path
    String name = path;
    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    if (lastSlash >= 0 && lastSlash < path.length() - 1) {
      name = path.substring(lastSlash + 1);
    }
    // Lucene segment files start with underscore
    if (!name.startsWith("_")) {
      return false;
    }
    // Check common Lucene segment file extensions
    return name.endsWith(".vemq")
        || name.endsWith(".vec")
        || name.endsWith(".vem")
        || name.endsWith(".vemf")
        || name.endsWith(".vex")
        || name.endsWith(".fdt")
        || name.endsWith(".fdx")
        || name.endsWith(".fdm")
        || name.endsWith(".fnm")
        || name.endsWith(".si")
        || name.endsWith(".liv")
        || name.endsWith(".nvd")
        || name.endsWith(".nvm")
        || name.endsWith(".tim")
        || name.endsWith(".tip")
        || name.endsWith(".doc")
        || name.endsWith(".pos")
        || name.endsWith(".pay")
        || name.endsWith(".tvd")
        || name.endsWith(".tvx")
        || name.endsWith(".dii")
        || name.endsWith(".dim")
        || name.contains("_Lucene"); // e.g., _2h_Lucene104_0.pos
  }

  // ==========================================================================
  // Query Building Helpers
  // ==========================================================================

  /**
   * Builds a TermInSetQuery for filtering by document IDs.
   *
   * @param field the field name to match against
   * @param docIds the set of document IDs to include
   * @return a Query that matches documents with the given IDs, or MatchNoDocsQuery if empty
   */
  public static Query termInSetFilter(String field, Set<String> docIds) {
    if (docIds == null || docIds.isEmpty()) {
      return new MatchNoDocsQuery();
    }
    ArrayList<BytesRef> terms = new ArrayList<>(docIds.size());
    for (String docId : docIds) {
      if (docId == null || docId.isBlank()) continue;
      terms.add(new BytesRef(docId));
    }
    if (terms.isEmpty()) {
      return new MatchNoDocsQuery();
    }
    return new TermInSetQuery(field, terms);
  }

  /**
   * Builds a retention query for soft-deleted documents based on age and version limits.
   *
   * @param softDeleteField the soft-delete field name
   * @param retentionDaysCfg retention period in days (null defaults to 7)
   * @param maxVersions maximum versions to retain (null means unlimited)
   * @return a Query that matches documents that should be retained
   */
  public static Query buildSoftDeleteRetentionQuery(
      String softDeleteField, Integer retentionDaysCfg, Integer maxVersions) {
    int retentionDays = retentionDaysCfg == null ? 7 : Math.max(retentionDaysCfg, 0);
    List<Query> clauses = new ArrayList<>();
    if (retentionDays > 0) {
      long cutoffMillis =
          System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(retentionDays);
      clauses.add(
          SortedNumericDocValuesField.newSlowRangeQuery(
              softDeleteTimestampField(softDeleteField), cutoffMillis, Long.MAX_VALUE));
    }
    if (maxVersions != null && maxVersions > 0) {
      long maxOrdinal = Math.max(0L, maxVersions.longValue() - 1L);
      clauses.add(
          SortedNumericDocValuesField.newSlowRangeQuery(
              softDeleteVersionField(softDeleteField), 0L, maxOrdinal));
    }
    if (clauses.isEmpty()) {
      return new MatchNoDocsQuery("soft delete retention disabled");
    }
    if (clauses.size() == 1) {
      return clauses.get(0);
    }
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (Query q : clauses) builder.add(q, BooleanClause.Occur.SHOULD);
    builder.setMinimumNumberShouldMatch(1);
    return builder.build();
  }

  // ==========================================================================
  // Soft Delete Field Name Helpers
  // ==========================================================================

  /**
   * Computes the timestamp field name for soft deletion tracking.
   *
   * @param softDeleteField the base soft-delete field name
   * @return the timestamp field name
   */
  public static String softDeleteTimestampField(String softDeleteField) {
    return softDeleteField + SOFT_DELETE_TS_SUFFIX;
  }

  /**
   * Computes the version/ordinal field name for soft deletion tracking.
   *
   * @param softDeleteField the base soft-delete field name
   * @return the version field name
   */
  public static String softDeleteVersionField(String softDeleteField) {
    return softDeleteField + SOFT_DELETE_VERSION_SUFFIX;
  }

  // ==========================================================================
  // Sort Building Helpers
  // ==========================================================================

  /**
   * Builds a Lucene Sort for the given RuntimeSearchSort.
   *
   * <p>Always includes a stable tie-breaker (idField) unless the sort is already purely by doc_id.
   *
   * @param sort the search sort to build
   * @param idField the document ID field to use as tie-breaker
   * @return the Lucene Sort instance
   * @throws NullPointerException if sort or idField is null
   */
  /**
   * The relevance sort a CHUNK-level search must use: score, then the chunk's identity expressed
   * in fields that are stable ACROSS index builds (lane F PR 0b).
   *
   * <p>{@code doc_id} alone is not such a key on a chunk row. {@code ChunkDocumentWriter} mints it
   * as {@code ChunkIds.newChunkDocId()} = {@code "chunk:" + UUID.randomUUID()}
   * ({@code ChunkIds.java:51-53}), deliberately not derived from the parent or the chunk index — so
   * it is unique within one index and uncorrelated between two ingests of the same corpus.
   * Breaking a score tie on it therefore produces an order that is stable per index and RANDOM per
   * build, which is precisely the property the tie-break exists to remove.
   *
   * <p>The deterministic pair is {@code parent_doc_id} — the parent's normalized absolute path
   * ({@code IndexingDocumentOps.java:172}) — then {@code chunk_index}. Together they identify a
   * chunk by position in a named document, which two ingests of the same corpus agree on.
   * {@code doc_id} stays as the final comparator so the order is total even for a row that carries
   * neither (a whole-document row reaching a chunk search through a filter that let it past, and
   * every row in an index written before chunking): both docvalues columns then read as "missing"
   * uniformly, which is a tie, and the deterministic path-shaped {@code doc_id} decides.
   *
   * <p>Both fields carry docvalues on chunk rows by catalog declaration —
   * {@code parent_doc_id} is a single-valued {@code keyword} with {@code docValues: true}
   * (a {@code SortedDocValuesField}) and {@code chunk_index} a {@code long} with
   * {@code docValues: true} (a {@code NumericDocValuesField}); see {@code SSOT/catalogs/fields.v1
   * .json} and {@code FieldMapper#addFields}. No schema change was needed to sort on them.
   *
   * @param idField the document ID field, used as the final tie-breaker
   * @return the Lucene Sort instance
   * @throws NullPointerException if idField is null
   */
  public static Sort buildChunkTieBreakSort(String idField) {
    if (idField == null) {
      throw new NullPointerException("idField must not be null");
    }
    return new Sort(
        SortField.FIELD_SCORE,
        new SortField(SchemaFields.PARENT_DOC_ID, SortField.Type.STRING, false),
        // Missing (a non-chunk row) reads as Lucene's numeric default 0 for every such row, so it
        // is a uniform tie that falls through to idField rather than a silent reordering.
        new SortField(SchemaFields.CHUNK_INDEX, SortField.Type.LONG, false),
        new SortField(idField, SortField.Type.STRING, false));
  }

  public static Sort buildRuntimeSort(RuntimeSearchSort sort, String idField) {
    if (sort == null) {
      throw new NullPointerException("sort must not be null");
    }
    if (idField == null) {
      throw new NullPointerException("idField must not be null");
    }
    return switch (sort) {
      case RELEVANCE ->
          new Sort(SortField.FIELD_SCORE, new SortField(idField, SortField.Type.STRING, false));
      case MODIFIED_DESC -> {
        SortField f = new SortField(SchemaFields.MODIFIED_AT, SortField.Type.LONG, true);
        f.setMissingValue(Long.MIN_VALUE);
        yield new Sort(f, new SortField(idField, SortField.Type.STRING, false));
      }
      case MODIFIED_ASC -> {
        SortField f = new SortField(SchemaFields.MODIFIED_AT, SortField.Type.LONG, false);
        f.setMissingValue(Long.MAX_VALUE);
        yield new Sort(f, new SortField(idField, SortField.Type.STRING, false));
      }
      case SIZE_DESC -> {
        SortField f = new SortField(SchemaFields.SIZE_BYTES, SortField.Type.LONG, true);
        f.setMissingValue(Long.MIN_VALUE);
        yield new Sort(f, new SortField(idField, SortField.Type.STRING, false));
      }
      case SIZE_ASC -> {
        SortField f = new SortField(SchemaFields.SIZE_BYTES, SortField.Type.LONG, false);
        f.setMissingValue(Long.MAX_VALUE);
        yield new Sort(f, new SortField(idField, SortField.Type.STRING, false));
      }
      case PATH_ASC -> new Sort(new SortField(idField, SortField.Type.STRING, false));
      case PATH_DESC -> new Sort(new SortField(idField, SortField.Type.STRING, true));
    };
  }
}
