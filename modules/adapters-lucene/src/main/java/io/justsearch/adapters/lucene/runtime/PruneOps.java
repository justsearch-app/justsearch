/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles orphan-document pruning by scanning indexed documents and removing those whose backing
 * files no longer exist on disk.
 *
 * <p>Extracted from {@link LuceneLifecycleManager} to keep the facade focused on lifecycle and
 * delegation. The facade stub calls {@code ensureStarted()} and {@code guardWritable()} before
 * delegating to this class.
 */
public final class PruneOps {
  private static final Logger log = LoggerFactory.getLogger(PruneOps.class);

  private final RuntimeSession session;
  private final IndexingCoordinator indexingCoordinator;
  private final CommitOps commitOps;

  PruneOps(RuntimeSession session, IndexingCoordinator indexingCoordinator, CommitOps commitOps) {
    this.session = session;
    this.indexingCoordinator = indexingCoordinator;
    this.commitOps = commitOps;
  }

  /**
   * Prunes orphaned documents whose paths start with the given prefix and whose backing files no
   * longer exist on disk.
   *
   * @param pathPrefix the path prefix to scan (e.g. "C:\Users\docs\")
   * @param abortChecker optional checker that returns true to abort the operation
   * @param throttleBatchSize number of documents to check between throttle sleeps
   * @return number of orphan candidates pruned (owned children are also deleted), or -1 if aborted
   */
  public int pruneByPathPrefix(String pathPrefix, BooleanSupplier abortChecker, int throttleBatchSize) {
    return pruneByPathPrefix(pathPrefix, abortChecker, throttleBatchSize, path -> {});
  }

  /**
   * As {@link #pruneByPathPrefix(String, BooleanSupplier, int)}, additionally reporting each pruned
   * document's file path to {@code confirmedDeletionSink}.
   *
   * <p>The sink exists so a Worker-side collaborator can act on the ONE place that establishes
   * "this file is verifiably gone" without adapters-lucene taking a dependency on it (tempdoc 931
   * §C.6 wires the document-identity deletion mark through it). Sink failures are the sink's
   * problem; a throwing sink aborts the prune, so callers pass a swallowing one.
   */
  public int pruneByPathPrefix(
      String pathPrefix,
      BooleanSupplier abortChecker,
      int throttleBatchSize,
      java.util.function.Consumer<String> confirmedDeletionSink) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("pruneByPathPrefix requires non-blank prefix");
    }
    if (throttleBatchSize <= 0) {
      throttleBatchSize = 100;
    }

    String idField = session.fieldMapper.idField();
    String normalized = normalizePathPrefix(pathPrefix);
    int pruned = 0;
    int checked = 0;

    // Snapshot once so acquire and release use the same instance.
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not available (runtime closed?)");
    }
    IndexSearcher searcher = null;
    try {
      searcher = mgr.acquire();
      Query query = new BooleanQuery.Builder()
          .add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.SHOULD)
          // Legacy documents may have only a filesystem doc_id. Validate the stored path below
          // before applying this fallback so an ID cannot pull another backing path into scope.
          .add(new PrefixQuery(new Term(idField, normalized)), BooleanClause.Occur.SHOULD)
          .add(
              new BooleanQuery.Builder()
                  .add(new PrefixQuery(new Term(SchemaFields.PARENT_DOC_ID, normalized)),
                      BooleanClause.Occur.FILTER)
                  .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                      BooleanClause.Occur.FILTER)
                  .build(),
              BooleanClause.Occur.SHOULD)
          .setMinimumNumberShouldMatch(1)
          .build();

      // Stored-field visitor: we need doc_id for deletion and path for file existence check.
      // Avoid decoding large stored fields (content).
      org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
      Set<String> storedAllowlist =
          Set.of(idField, SchemaFields.PATH, SchemaFields.PARENT_DOC_ID, SchemaFields.IS_CHUNK);

      // Page through results to avoid allocating a potentially huge ScoreDoc[] (Integer.MAX_VALUE).
      final int batchSize = 10_000;
      org.apache.lucene.search.ScoreDoc after = null;
      Sort sort = new Sort(new SortField(idField, SortField.Type.STRING, false));
      while (true) {
        org.apache.lucene.search.TopDocs topDocs =
            after == null
                ? searcher.search(query, batchSize, sort, true)
                : searcher.searchAfter(after, query, batchSize, sort, true);
        if (topDocs.scoreDocs.length == 0) {
          break;
        }
        for (var scoreDoc : topDocs.scoreDocs) {
          // Abort check - respect user activity
          if (abortChecker != null && abortChecker.getAsBoolean()) {
            log.info(
                "pruneByPathPrefix: aborted by checker after {} checked, {} pruned",
                checked,
                pruned);
            return -1;
          }

          // Throttle - yield CPU/IO every N documents
          checked++;
          if (checked % throttleBatchSize == 0) {
            Thread.sleep(1);
          }

          // Read doc_id (for deletion) and path (for file existence check).
          // The 'path' field contains the parent file path for both parent docs and chunks,
          // avoiding the need to parse "#chunk_" from doc_id.
          Map<String, String> docFields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, scoreDoc.doc, false, storedAllowlist);
          String docId = docFields.get(idField);
          String filePath = docFields.get(SchemaFields.PATH);
          String parentDocId = docFields.get(SchemaFields.PARENT_DOC_ID);
          if (docId == null || docId.isBlank()) {
            continue;
          }
          // A pathless chunk's owner supplies its backing path even after the parent is removed.
          // Explicit paths take precedence; other legacy documents fall back to doc_id.
          if (filePath == null || filePath.isBlank()) {
            filePath =
                "true".equals(docFields.get(SchemaFields.IS_CHUNK))
                        && parentDocId != null && !parentDocId.isBlank()
                    ? parentDocId
                    : docId;
          }
          if (!normalizePathPrefix(filePath).startsWith(normalized)) {
            continue;
          }

          // Check if file still exists
          if (!Files.exists(Path.of(filePath))) {
            if (parentDocId != null && !parentDocId.isBlank() && !parentDocId.equals(docId)) {
              indexingCoordinator.deleteByIdAndChunks(parentDocId);
              indexingCoordinator.deleteById(docId);
            } else {
              indexingCoordinator.deleteByIdAndChunks(docId);
            }
            pruned++;
            confirmedDeletionSink.accept(filePath);
            log.debug("pruneByPathPrefix: deleted orphan document: {}", docId);
          }
        }
        after = topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
      }

      // Commit if we pruned anything
      if (pruned > 0) {
        commitOps.commitAndTrack(CommitReason.PRUNE);
      }

      log.info(
          "pruneByPathPrefix: complete - {} checked, {} pruned for prefix {}",
          checked,
          pruned,
          normalized);
      return pruned;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("pruneByPathPrefix: interrupted after {} checked, {} pruned", checked, pruned);
      return -1;
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Prune failed", e);
    } finally {
      if (searcher != null) {
        try {
          mgr.release(searcher);
        } catch (IOException ignored) {
          // best-effort release
        }
      }
    }
  }
}
