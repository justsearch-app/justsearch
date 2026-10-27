/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderBrowseResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderFilesResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderInfo;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ListAllDocumentIdsResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.indexing.SchemaFields;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine for browsing indexed content by folder structure.
 *
 * <p>Derives folder hierarchy from stored file paths in the Lucene index. Uses the same
 * Supplier/Consumer pattern as {@link FacetingEngine} for searcher lifecycle management.
 */
public final class FolderBrowseEngine {
  private static final Logger log = LoggerFactory.getLogger(FolderBrowseEngine.class);

  /** Default maximum child folders returned. */
  public static final int DEFAULT_MAX_FOLDERS = 200;

  /** Default maximum files returned per folder. */
  public static final int DEFAULT_MAX_FILES = 100;

  /** Default safety cap for documents scanned. */
  public static final int DEFAULT_MAX_DOCS_SCANNED = 50_000;

  /** Maximum number of document IDs retained for one corpus-iteration page. */
  public static final int MAX_DOCUMENT_ID_PAGE_SIZE = 50_000;

  private final SearcherBridge bridge;
  private final Function<String, FieldMapper.FieldDef> fieldDefLookup;

  public FolderBrowseEngine(
      SearcherBridge bridge,
      Function<String, FieldMapper.FieldDef> fieldDefLookup) {
    this.bridge = bridge;
    this.fieldDefLookup = fieldDefLookup;
  }

  /**
   * Enumerates immediate child folders under a parent path with aggregate metadata.
   *
   * @param parentPath parent folder path (will be normalized)
   * @param maxFolders maximum folders to return (0 = default)
   * @return folder entries sorted alphabetically by name
   */
  public FolderBrowseResult enumerateFolders(String parentPath, int maxFolders) {
    if (parentPath == null || parentPath.isBlank()) {
      throw new IllegalArgumentException("parentPath is required");
    }

    String normalized = QueryFilterBuilder.normalizePathPrefix(parentPath);
    int limit = maxFolders <= 0 ? DEFAULT_MAX_FOLDERS : maxFolders;

    long startNs = System.nanoTime();
    IndexSearcher searcher = null;
    try {
      searcher = bridge.acquire();

      Query query = buildPathPrefixQuery(normalized);
      Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

      // Aggregate: folderName -> (fileCount, totalSize, lastIndexed)
      Map<String, long[]> folderStats = new HashMap<>();
      char sep = File.separatorChar;
      boolean truncated = false;
      long scanned = 0;

      for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
        if (truncated) break;

        Scorer scorer = weight.scorer(leaf);
        if (scorer == null) continue;

        Bits liveDocs = leaf.reader().getLiveDocs();
        SortedDocValues pathDv = getSortedDocValues(leaf, SchemaFields.PATH);
        NumericDocValues sizeDv = getNumericDocValues(leaf, SchemaFields.SIZE_BYTES);
        NumericDocValues indexedDv = getNumericDocValues(leaf, SchemaFields.INDEXED_AT);

        TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        DocIdSetIterator it = (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
        int doc;
        while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(doc)) continue;
          if (twoPhase != null && !twoPhase.matches()) {
            continue;
          }
          scanned++;
          if (scanned > DEFAULT_MAX_DOCS_SCANNED) {
            truncated = true;
            break;
          }

          if (pathDv == null || !pathDv.advanceExact(doc)) continue;
          String docPath = pathDv.lookupOrd(pathDv.ordValue()).utf8ToString();

          String childName = extractChildFolder(docPath, normalized, sep);
          if (childName == null) continue; // direct child file, not in a subfolder

          long size = (sizeDv != null && sizeDv.advanceExact(doc)) ? sizeDv.longValue() : 0L;
          long indexed = (indexedDv != null && indexedDv.advanceExact(doc)) ? indexedDv.longValue() : 0L;

          folderStats.merge(childName, new long[] {1, size, indexed}, (old, nw) -> {
            old[0] += nw[0];
            old[1] += nw[1];
            old[2] = Math.max(old[2], nw[2]);
            return old;
          });
        }
      }

      // Build sorted folder list
      List<FolderInfo> folders = new ArrayList<>(folderStats.size());
      for (var entry : folderStats.entrySet()) {
        String name = entry.getKey();
        long[] stats = entry.getValue();
        String folderPath = normalized + name + sep;
        folders.add(new FolderInfo(folderPath, name, stats[0], stats[1], stats[2]));
      }
      folders.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

      boolean foldersTruncated = truncated || folders.size() > limit;
      if (folders.size() > limit) {
        folders = folders.subList(0, limit);
      }

      long tookMs = (System.nanoTime() - startNs) / 1_000_000;
      return new FolderBrowseResult(Collections.unmodifiableList(folders), tookMs, foldersTruncated);

    } catch (IOException e) {
      log.warn("Failed to enumerate folders under {}", parentPath, e);
      long tookMs = (System.nanoTime() - startNs) / 1_000_000;
      return new FolderBrowseResult(List.of(), tookMs, false);
    } finally {
      if (searcher != null) {
        bridge.release(searcher);
      }
    }
  }

  /**
   * Lists files directly within a folder (not in subfolders) with metadata.
   *
   * <p>Subject to the same {@link #DEFAULT_MAX_DOCS_SCANNED} safety cap as
   * {@link #enumerateFolders}. If the scan limit is hit, {@code totalCount}
   * reflects only the documents scanned so far (approximate).
   *
   * @param folderPath folder path (will be normalized)
   * @param maxFiles maximum files to return (0 = default)
   * @param projection fields to include (empty = default set)
   * @return file entries with projected fields
   */
  public FolderFilesResult listFolderFiles(String folderPath, int maxFiles, Set<String> projection) {
    if (folderPath == null || folderPath.isBlank()) {
      throw new IllegalArgumentException("folderPath is required");
    }

    String normalized = QueryFilterBuilder.normalizePathPrefix(folderPath);
    int limit = maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
    Set<String> proj = (projection == null || projection.isEmpty()) ? defaultFileProjection() : projection;

    long startNs = System.nanoTime();
    IndexSearcher searcher = null;
    try {
      searcher = bridge.acquire();

      Query query = buildPathPrefixQuery(normalized);
      Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

      char sep = File.separatorChar;
      List<SearchHit> files = new ArrayList<>();
      Set<String> seenPaths = new HashSet<>(); // Deduplicate by path (handles reindex race)
      long totalCount = 0;
      long scanned = 0;
      boolean scanLimitHit = false;

      // Build stored field allowlist
      Set<String> storedAllowlist = new HashSet<>();
      storedAllowlist.add(SchemaFields.DOC_ID);
      storedAllowlist.add(SchemaFields.DOC_UID);
      for (String f : proj) {
        FieldMapper.FieldDef def = fieldDefLookup.apply(f);
        if (def != null && def.stored) {
          storedAllowlist.add(f);
        }
      }

      org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();

      for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
        if (scanLimitHit) break;

        Scorer scorer = weight.scorer(leaf);
        if (scorer == null) continue;

        Bits liveDocs = leaf.reader().getLiveDocs();
        SortedDocValues pathDv = getSortedDocValues(leaf, SchemaFields.PATH);

        TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        DocIdSetIterator it = (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
        int doc;
        while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(doc)) continue;
          if (twoPhase != null && !twoPhase.matches()) {
            continue;
          }
          scanned++;
          if (scanned > DEFAULT_MAX_DOCS_SCANNED) {
            scanLimitHit = true;
            break;
          }

          if (pathDv == null || !pathDv.advanceExact(doc)) continue;
          String docPath = pathDv.lookupOrd(pathDv.ordValue()).utf8ToString();

          if (!isDirectChild(docPath, normalized, sep)) continue;

          // Deduplicate: skip if we've already seen this path (can happen during reindex)
          if (!seenPaths.add(docPath)) continue;

          totalCount++;
          if (files.size() >= limit) continue; // keep counting but stop collecting

          int globalDoc = leaf.docBase + doc;
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, globalDoc, false, storedAllowlist);

          // Project DocValues fields not already in stored fields
          projectDocValues(searcher, globalDoc, proj, fields);

          String docId = fields.getOrDefault(SchemaFields.DOC_ID, "");
          files.add(new SearchHit(docId, 0.0f, fields));
        }
      }

      long tookMs = (System.nanoTime() - startNs) / 1_000_000;
      return new FolderFilesResult(Collections.unmodifiableList(files), totalCount, tookMs);

    } catch (IOException e) {
      log.warn("Failed to list files in {}", folderPath, e);
      long tookMs = (System.nanoTime() - startNs) / 1_000_000;
      return new FolderFilesResult(List.of(), 0, tookMs);
    } finally {
      if (searcher != null) {
        bridge.release(searcher);
      }
    }
  }

  /**
   * Returns a page of all indexed document IDs (excluding chunks) for corpus iteration.
   *
   * <p>Used by the GPL offline training job to iterate the full corpus without depending on
   * folder hierarchy.
   *
   * @param offset zero-based start index (for pagination)
   * @param limit max IDs to return (0 = default 1000)
   * @return paginated list of doc IDs and total count
   */
  public ListAllDocumentIdsResult listAllDocumentIds(int offset, int limit) {
    return listAllDocumentIds(offset, limit, null);
  }

  /**
   * Returns a page from an expected immutable reader generation.
   *
   * @param expectedReaderVersion required reader version, or {@code null} for the first page
   * @throws StaleDocumentIdSnapshotException when the acquired reader no longer matches
   */
  public ListAllDocumentIdsResult listAllDocumentIds(
      int offset, int limit, Long expectedReaderVersion) {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be non-negative");
    }
    if (limit < 0 || limit > MAX_DOCUMENT_ID_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "limit must be between 0 and " + MAX_DOCUMENT_ID_PAGE_SIZE);
    }
    int effectiveLimit = limit <= 0 ? 1000 : limit;
    long startNs = System.nanoTime();
    IndexSearcher searcher = null;
    try {
      searcher = bridge.acquire();
      if (!(searcher.getIndexReader() instanceof DirectoryReader reader)) {
        throw new IllegalStateException("Document ID pagination requires a DirectoryReader");
      }
      long readerVersion = reader.getVersion();
      if (expectedReaderVersion != null && expectedReaderVersion.longValue() != readerVersion) {
        throw new StaleDocumentIdSnapshotException(expectedReaderVersion, readerVersion);
      }

      // Exclude chunk documents — GPL only processes parent documents.
      BooleanQuery.Builder qb = new BooleanQuery.Builder();
      qb.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
      qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
      Query query = qb.build();
      Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

      List<String> page = new ArrayList<>(effectiveLimit);
      long matched = 0;

      for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
        Scorer scorer = weight.scorer(leaf);
        if (scorer == null) continue;

        Bits liveDocs = leaf.reader().getLiveDocs();
        SortedDocValues docIdDv = DocValues.getSorted(leaf.reader(), SchemaFields.DOC_ID);

        TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        DocIdSetIterator it = (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
        int doc;
        while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          // Raw scorer iteration does not pass through IndexSearcher.searchLeaf, so deleted but
          // unmerged document versions must be filtered explicitly. Otherwise corpus iteration
          // returns stale parent IDs after ordinary updates and hard deletes.
          if (liveDocs != null && !liveDocs.get(doc)) continue;
          if (twoPhase != null && !twoPhase.matches()) continue;
          if (!docIdDv.advanceExact(doc)) {
            throw new IllegalStateException(
                "Live parent document is missing required doc_id DocValues");
          }
          if (matched >= offset && page.size() < effectiveLimit) {
            page.add(docIdDv.lookupOrd(docIdDv.ordValue()).utf8ToString());
          }
          matched++;
        }
      }

      long tookMs = (System.nanoTime() - startNs) / 1_000_000;
      return new ListAllDocumentIdsResult(
          Collections.unmodifiableList(page), matched, tookMs, readerVersion);

    } catch (IOException e) {
      throw new IllegalStateException("Failed to list all document IDs", e);
    } finally {
      if (searcher != null) {
        bridge.release(searcher);
      }
    }
  }

  /** Signals that an offset continuation no longer addresses the reader used for its first page. */
  public static final class StaleDocumentIdSnapshotException extends RuntimeException {
    public StaleDocumentIdSnapshotException(long expectedVersion, long actualVersion) {
      super(
          "Document ID snapshot changed (expected reader version "
              + expectedVersion
              + ", found "
              + actualVersion
              + ")");
    }
  }

  // ========== Path Parsing Helpers ==========

  /**
   * Extracts the immediate child folder name from a document path relative to a parent prefix.
   *
   * @return child folder name, or null if the doc is a direct child file (not in a subfolder)
   */
  static String extractChildFolder(String docPath, String parentPrefix, char sep) {
    if (!docPath.startsWith(parentPrefix)) return null;
    String remainder = docPath.substring(parentPrefix.length());
    int sepIdx = remainder.indexOf(sep);
    if (sepIdx <= 0) return null;
    return remainder.substring(0, sepIdx);
  }

  /**
   * Returns true if the document is a direct child of the folder (not in a subfolder).
   */
  static boolean isDirectChild(String docPath, String folderPrefix, char sep) {
    if (!docPath.startsWith(folderPrefix)) return false;
    String remainder = docPath.substring(folderPrefix.length());
    return !remainder.isEmpty() && remainder.indexOf(sep) < 0;
  }

  // ========== Internal Helpers ==========

  private static Query buildPathPrefixQuery(String normalizedPath) {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalizedPath)), BooleanClause.Occur.MUST);
    qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    return qb.build();
  }

  private static SortedDocValues getSortedDocValues(LeafReaderContext leaf, String field) {
    try {
      return DocValues.getSorted(leaf.reader(), field);
    } catch (IOException | IllegalStateException e) {
      return null;
    }
  }

  private static NumericDocValues getNumericDocValues(LeafReaderContext leaf, String field) {
    try {
      return DocValues.getNumeric(leaf.reader(), field);
    } catch (IOException | IllegalStateException e) {
      return null;
    }
  }

  private void projectDocValues(
      IndexSearcher searcher, int globalDoc, Set<String> projectionFields, Map<String, String> out)
      throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = org.apache.lucene.index.ReaderUtil.subIndex(globalDoc, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDoc - leaf.docBase;

    for (String field : projectionFields) {
      if (field == null || field.isBlank() || out.containsKey(field)) continue;
      FieldMapper.FieldDef def = fieldDefLookup.apply(field);
      if (def == null || !def.docValues) continue;

      switch (def.type) {
        case "keyword" -> {
          if (def.multiValued) {
            try {
              SortedSetDocValues sdv = DocValues.getSortedSet(leaf.reader(), def.id);
              if (sdv != null && sdv.advanceExact(docInLeaf)) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < sdv.docValueCount(); i++) {
                  values.add(sdv.lookupOrd(sdv.nextOrd()).utf8ToString());
                }
                out.put(def.id, String.join(", ", values));
              }
            } catch (IOException | IllegalStateException ignored) {
              // field has no SortedSetDocValues in this segment
            }
          } else {
            SortedDocValues dv = getSortedDocValues(leaf, def.id);
            if (dv != null && dv.advanceExact(docInLeaf)) {
              out.put(def.id, dv.lookupOrd(dv.ordValue()).utf8ToString());
            }
          }
        }
        case "long", "boolean", "double" -> {
          NumericDocValues dv = getNumericDocValues(leaf, def.id);
          if (dv != null && dv.advanceExact(docInLeaf)) {
            out.put(def.id, "double".equals(def.type)
                ? Double.toString(Double.longBitsToDouble(dv.longValue()))
                : Long.toString(dv.longValue()));
          }
        }
        default -> { /* unsupported type for projection */ }
      }
    }
  }

  private static Set<String> defaultFileProjection() {
    return Set.of(
        SchemaFields.DOC_ID,
        SchemaFields.PATH,
        SchemaFields.FILENAME,
        SchemaFields.FILE_KIND,
        SchemaFields.MIME_BASE,
        SchemaFields.SIZE_BYTES,
        SchemaFields.MODIFIED_AT,
        SchemaFields.INDEXED_AT,
        SchemaFields.VDU_STATUS,
        SchemaFields.TITLE);
  }
}
