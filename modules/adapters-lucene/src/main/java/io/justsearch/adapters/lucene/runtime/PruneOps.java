/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 * files are confirmed absent from disk.
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

  /** How a prune ended. */
  public enum Completion {
    /** Every document under the prefix was judged. */
    COMPLETE,
    /** The abort checker stopped the run (cancellation or interruption). */
    ABORTED,
    /**
     * Not every document could be judged: some documents' presence could not be established (they
     * were kept, and the rest were still judged), or the root stopped vouching for absence (the
     * run stopped there; nothing after that point was removed). Deletions already confirmed stay
     * applied.
     */
    INCOMPLETE
  }

  /**
   * Outcome of one prune: the number of documents removed, how the run ended, and how many
   * documents were kept because their presence could not be told.
   */
  public record PruneResult(int pruned, Completion completion, int keptUnknown) {
    public PruneResult {
      Objects.requireNonNull(completion, "completion");
    }

    public PruneResult(int pruned, Completion completion) {
      this(pruned, completion, 0);
    }

    public boolean complete() {
      return completion == Completion.COMPLETE;
    }
  }

  /**
   * Decides, for one prune, when a document's backing file may be treated as deleted.
   *
   * <p>A document is removed only when its path is reported {@link PathPresence#ABSENT} AND the
   * root re-checked after that observation is still the same, present root ({@link #rootCurrent}).
   * The root is checked once per batch, not per document.
   */
  public interface PresenceGuard {
    /** True while the root that vouches for absence is present, readable and unchanged. */
    boolean rootCurrent();

    /** Three-way presence of one stored path. */
    default PathPresence presence(Path file) {
      return PathPresence.probe(file);
    }

    /** The baseline guard: the root must be a readable directory each time it is asked. */
    static PresenceGuard forRoot(Path root) {
      Objects.requireNonNull(root, "root");
      return () -> PathPresence.isReadableDirectory(root);
    }
  }

  /**
   * Prunes under {@code pathPrefix}, with the prefix folder itself as the root that vouches for
   * absence ({@link PresenceGuard#forRoot}).
   *
   * @return documents pruned, or -1 if aborted. An incomplete run (root or entry unreadable) returns
   *     what it pruned before stopping; callers that must tell it apart use the {@link PruneResult}
   *     form.
   */
  public int pruneByPathPrefix(String pathPrefix, BooleanSupplier abortChecker, int throttleBatchSize) {
    return pruneByPathPrefix(pathPrefix, abortChecker, throttleBatchSize, path -> {});
  }

  /**
   * As {@link #pruneByPathPrefix(String, BooleanSupplier, int)}, additionally reporting each pruned
   * document's file path to {@code confirmedDeletionSink}.
   */
  public int pruneByPathPrefix(
      String pathPrefix,
      BooleanSupplier abortChecker,
      int throttleBatchSize,
      Consumer<String> confirmedDeletionSink) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("pruneByPathPrefix requires non-blank prefix");
    }
    PresenceGuard guard;
    try {
      guard = PresenceGuard.forRoot(Path.of(pathPrefix));
    } catch (InvalidPathException invalid) {
      guard = () -> false;
    }
    PruneResult result =
        pruneByPathPrefix(pathPrefix, abortChecker, throttleBatchSize, guard, confirmedDeletionSink);
    return result.completion() == Completion.ABORTED ? -1 : result.pruned();
  }

  /**
   * Prunes documents under {@code pathPrefix} whose backing files are confirmed absent.
   *
   * <p>"Confirmed absent" (task t-20261013-ef3c41): the path is reported not found, the nearest
   * folder above it that exists can be listed ({@link PathPresence#absentBeneathListableFolder}),
   * and the root was present, the same root, before and after that observation. Absent candidates
   * are collected per batch and only removed after {@link PresenceGuard#rootCurrent()} passes
   * immediately before they are applied, so a drive unplugged or a share dropped mid-batch removes
   * nothing. A document whose presence cannot be established ({@link PathPresence#UNKNOWN}) is
   * kept and the rest are still judged (round 4: one unreadable subfolder must not stop the
   * genuine deletions beside it); the run then ends {@link Completion#INCOMPLETE} with the count of
   * kept documents. The first such document of a batch re-checks the root at once: a root that no
   * longer vouches ends the run there, discarding that batch's candidates. A batch in which every
   * document was found asks nothing of the root, since nothing is removed on its strength; the run
   * asks once more at its end, so a root that changed meanwhile still reports it incomplete.
   *
   * <p>The sink exists so a Worker-side collaborator can act on each confirmed deletion without
   * adapters-lucene taking a dependency on it (tempdoc 931 §C.6 wires the document-identity
   * deletion mark through it). A throwing sink aborts the prune, so callers pass a swallowing one.
   *
   * @param pathPrefix the path prefix to scan (e.g. {@code C:\Users\docs\})
   * @param abortChecker optional checker that returns true to abort the operation
   * @param throttleBatchSize number of documents per batch (throttle sleep and root re-check)
   * @param guard the presence authority for this prune
   * @param confirmedDeletionSink receives each pruned document's file path
   */
  public PruneResult pruneByPathPrefix(
      String pathPrefix,
      BooleanSupplier abortChecker,
      int throttleBatchSize,
      PresenceGuard guard,
      Consumer<String> confirmedDeletionSink) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("pruneByPathPrefix requires non-blank prefix");
    }
    Objects.requireNonNull(guard, "guard");
    Objects.requireNonNull(confirmedDeletionSink, "confirmedDeletionSink");
    if (throttleBatchSize <= 0) {
      throttleBatchSize = 100;
    }

    String idField = session.fieldMapper.idField();
    String normalized = normalizePathPrefix(pathPrefix);
    int checked = 0;
    Batch batch = new Batch(guard, confirmedDeletionSink);
    Path boundary;
    try {
      boundary = Path.of(pathPrefix);
    } catch (InvalidPathException invalid) {
      boundary = null;
    }
    Map<Path, PathPresence> folderVerdicts = new java.util.HashMap<>();
    int judged = 0;

    // Snapshot once so acquire and release use the same instance.
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not available (runtime closed?)");
    }
    if (!guard.rootCurrent()) {
      log.warn("pruneByPathPrefix: root unavailable or changed, nothing pruned for prefix {}",
          normalized);
      return new PruneResult(0, Completion.INCOMPLETE);
    }
    IndexSearcher searcher = null;
    Completion completion = Completion.COMPLETE;
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
      final int pageSize = 10_000;
      org.apache.lucene.search.ScoreDoc after = null;
      Sort sort = new Sort(new SortField(idField, SortField.Type.STRING, false));
      scan:
      while (true) {
        org.apache.lucene.search.TopDocs topDocs =
            after == null
                ? searcher.search(query, pageSize, sort, true)
                : searcher.searchAfter(after, query, pageSize, sort, true);
        if (topDocs.scoreDocs.length == 0) {
          break;
        }
        for (var scoreDoc : topDocs.scoreDocs) {
          // Abort check - respect cancellation. Candidates of the open batch were never vouched
          // for by a root re-check, so they are dropped; the next run judges them again.
          if (abortChecker != null && abortChecker.getAsBoolean()) {
            batch.candidates.clear();
            completion = Completion.ABORTED;
            log.info(
                "pruneByPathPrefix: aborted by checker after {} checked, {} pruned",
                checked,
                batch.pruned);
            break scan;
          }

          // Throttle - yield CPU/IO and re-check the root every N documents.
          checked++;
          if (checked % throttleBatchSize == 0) {
            Thread.sleep(1);
            if (!batch.flush()) {
              completion = Completion.INCOMPLETE;
              break scan;
            }
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
          // Explicit paths take precedence. For a pathless chunk, resolve the exact owner's
          // stored path before falling back to its legacy filesystem ID if no path is available.
          if (filePath == null || filePath.isBlank()) {
            filePath = docId;
            if ("true".equals(docFields.get(SchemaFields.IS_CHUNK))
                && parentDocId != null && !parentDocId.isBlank()) {
              filePath = parentDocId;
              var ownerDocs = searcher.search(new TermQuery(new Term(idField, parentDocId)), 1);
              if (ownerDocs.scoreDocs.length > 0) {
                String ownerPath = SearchResultFormatter.extractFromStoredFields(
                    storedFields, ownerDocs.scoreDocs[0].doc, false, Set.of(SchemaFields.PATH))
                    .get(SchemaFields.PATH);
                if (ownerPath != null && !ownerPath.isBlank()) {
                  filePath = ownerPath;
                }
              }
            }
          }
          if (!normalizePathPrefix(filePath).startsWith(normalized)) {
            continue;
          }

          Path file;
          try {
            file = Path.of(filePath);
          } catch (InvalidPathException unparseable) {
            // A stored path this system cannot parse can be neither confirmed absent nor found:
            // the document is kept, and the rest of the prefix is still judged.
            log.debug("pruneByPathPrefix: kept a document whose stored path is not valid here: {}",
                docId);
            continue;
          }
          judged++;
          PathPresence presence = guard.presence(file);
          if (presence == PathPresence.ABSENT) {
            // "Not found" beneath a folder that cannot be listed proves nothing.
            presence = PathPresence.absentBeneathListableFolder(file, boundary, folderVerdicts);
          }
          if (presence == PathPresence.UNKNOWN) {
            // Cannot tell: the document is kept and the rest are still judged. If it is the root
            // itself that stopped answering, nothing more is removed, including this batch's
            // unconfirmed candidates; a later run resumes once it answers again.
            batch.keptUnknown++;
            if (!batch.rootCheckedSinceUncertain) {
              batch.rootCheckedSinceUncertain = true;
              if (!guard.rootCurrent()) {
                log.warn(
                    "pruneByPathPrefix: the root stopped answering; stopping incomplete after {}"
                        + " checked, {} pruned for prefix {}",
                    checked,
                    batch.pruned,
                    normalized);
                batch.candidates.clear();
                completion = Completion.INCOMPLETE;
                break scan;
              }
            }
            continue;
          }
          if (presence == PathPresence.ABSENT) {
            // Ownership fields never authorize deletion of another document. Parents and chunks
            // are independently selected and checked, including divergent explicit child paths.
            batch.candidates.add(new Candidate(docId, filePath));
          }
        }
        after = topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
      }
      if (completion == Completion.COMPLETE && !batch.flush()) {
        completion = Completion.INCOMPLETE;
      }
      // A root that stopped being the same root during the run makes the run incomplete even when
      // no deletion was pending to notice it; one check per run, not per batch.
      if (completion == Completion.COMPLETE && judged > 0 && !batch.lastFlushAskedRoot
          && !guard.rootCurrent()) {
        log.warn("pruneByPathPrefix: the root is no longer the same root; reporting incomplete for"
            + " prefix {}", normalized);
        completion = Completion.INCOMPLETE;
      }
      if (completion == Completion.COMPLETE && batch.keptUnknown > 0) {
        completion = Completion.INCOMPLETE;
      }

      // Commit whatever was confirmed and removed, unless the caller cancelled the run.
      if (batch.pruned > 0 && completion != Completion.ABORTED) {
        commitOps.commitAndTrack(CommitReason.PRUNE);
      }
      log.info(
          "pruneByPathPrefix: {} - {} checked, {} pruned, {} kept (presence unknown) for prefix {}",
          completion,
          checked,
          batch.pruned,
          batch.keptUnknown,
          normalized);
      return new PruneResult(batch.pruned, completion, batch.keptUnknown);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("pruneByPathPrefix: interrupted after {} checked, {} pruned", checked, batch.pruned);
      return new PruneResult(batch.pruned, Completion.ABORTED, batch.keptUnknown);
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

  private record Candidate(String docId, String filePath) {}

  /** Absent candidates observed since the last root check. */
  private final class Batch {
    private final PresenceGuard guard;
    private final Consumer<String> sink;
    private final List<Candidate> candidates = new ArrayList<>();
    private int pruned;
    private int keptUnknown;
    /** Whether the root was re-checked after an undecidable document in the current batch. */
    private boolean rootCheckedSinceUncertain;
    /** Whether the most recent flush asked the root (it had candidates to apply). */
    private boolean lastFlushAskedRoot;

    Batch(PresenceGuard guard, Consumer<String> sink) {
      this.guard = guard;
      this.sink = sink;
    }

    /**
     * Removes the batch's candidates if the root still vouches for their absence, asking it right
     * before they are applied. A batch without candidates asks nothing: nothing is removed on its
     * strength.
     *
     * @return false when the root check failed; the candidates are discarded, nothing is removed
     */
    boolean flush() {
      rootCheckedSinceUncertain = false;
      lastFlushAskedRoot = !candidates.isEmpty();
      if (candidates.isEmpty()) {
        return true;
      }
      if (!guard.rootCurrent()) {
        log.warn(
            "pruneByPathPrefix: root unavailable or changed mid-prune; {} unconfirmed candidates"
                + " kept",
            candidates.size());
        candidates.clear();
        return false;
      }
      for (Candidate candidate : candidates) {
        indexingCoordinator.deleteById(candidate.docId());
        pruned++;
        sink.accept(candidate.filePath());
        log.debug("pruneByPathPrefix: deleted orphan document: {}", candidate.docId());
      }
      candidates.clear();
      return true;
    }
  }
}
