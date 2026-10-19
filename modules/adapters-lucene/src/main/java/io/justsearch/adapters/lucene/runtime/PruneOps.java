/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
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
   * Holds off file publications while one batch of deletions is applied.
   *
   * <p>Primary ingestion writes a file's parent and all of its chunks inside the Worker's
   * file-mutation fence, and VDU chunk replacement and renames hold the same fence. A batch applied
   * inside it therefore sees each file's rows wholly or not at all, and no row of a file can be
   * published between the last check of its absence and its deletion.
   */
  @FunctionalInterface
  public interface WriterFence {
    /** Runs {@code batch} with file publications held off and returns its result. */
    boolean hold(BooleanSupplier batch);

    /** For a prune with no concurrent file publisher to coordinate with. */
    WriterFence NONE = BooleanSupplier::getAsBoolean;
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
    return pruneByPathPrefix(
        pathPrefix, abortChecker, throttleBatchSize, guard, confirmedDeletionSink, WriterFence.NONE);
  }

  /**
   * As {@link #pruneByPathPrefix(String, BooleanSupplier, int, PresenceGuard, Consumer)}, applying
   * each batch inside {@code writerFence}.
   *
   * <p>Every row of a file the prune reports deleted is removed (task: a directory sync removes
   * every row of a deleted file). The scan reads a view refreshed when the prune starts, so it
   * finds every file whose rows were written before it. The scan only nominates files: inside the
   * fence each nominated file's absence is checked again, and the rows removed are the ones a
   * freshly refreshed view holds for it at that moment, each selected by the same rules as the
   * scan. A chunk written after the scan (a re-index or a VDU replacement gives chunks new ids) is
   * therefore removed with its file, a file that came back or can no longer be judged keeps its
   * rows, and a renamed file's rows, which no longer carry the old path, are left alone. Confirmed
   * deletions are committed and the search view refreshed before the prune returns.
   */
  public PruneResult pruneByPathPrefix(
      String pathPrefix,
      BooleanSupplier abortChecker,
      int throttleBatchSize,
      PresenceGuard guard,
      Consumer<String> confirmedDeletionSink,
      WriterFence writerFence) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("pruneByPathPrefix requires non-blank prefix");
    }
    Objects.requireNonNull(guard, "guard");
    Objects.requireNonNull(confirmedDeletionSink, "confirmedDeletionSink");
    Objects.requireNonNull(writerFence, "writerFence");
    if (throttleBatchSize <= 0) {
      throttleBatchSize = 100;
    }

    String idField = session.fieldMapper.idField();
    String normalized = normalizePathPrefix(pathPrefix);
    int checked = 0;
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
    Batch batch =
        new Batch(guard, confirmedDeletionSink, writerFence, mgr, normalized, idField, boundary);
    if (!guard.rootCurrent()) {
      log.warn("pruneByPathPrefix: root unavailable or changed, nothing pruned for prefix {}",
          normalized);
      return new PruneResult(0, Completion.INCOMPLETE);
    }
    IndexSearcher searcher = null;
    Completion completion = Completion.COMPLETE;
    try {
      // The background refresh may lag the writer by its whole interval; a stale view would miss
      // files written since, or hold only some of a file's chunks.
      mgr.maybeRefreshBlocking();
      searcher = mgr.acquire();

      StoredFields storedFields = searcher.storedFields();

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

          Row row = Row.read(searcher, storedFields, scoreDoc.doc, idField);
          if (row == null) {
            continue;
          }
          String docId = row.docId();
          String filePath = row.filePath();
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
            batch.candidates.add(new Candidate(docId, filePath, file));
          }
        }
        after = topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
      }
      if (completion == Completion.COMPLETE && !batch.flush()) {
        completion = Completion.INCOMPLETE;
      }
      if (completion == Completion.COMPLETE && batch.keptUnknown > 0) {
        completion = Completion.INCOMPLETE;
      }

      // Commit whatever was confirmed and removed, unless the caller cancelled the run. The commit
      // refreshes only the admission reader: refresh the search view too, so a search started
      // after this prune returns sees none of the removed rows.
      if (batch.pruned > 0 && completion != Completion.ABORTED) {
        commitOps.commitAndTrack(CommitReason.PRUNE);
        mgr.maybeRefreshBlocking();
      }
      // A root that stopped being the same root during the run makes the run incomplete even when
      // no deletion was pending to notice it, and even after the last batch asked it: asked once
      // more, after the removals are committed and published.
      if (completion == Completion.COMPLETE && judged > 0 && !guard.rootCurrent()) {
        log.warn("pruneByPathPrefix: the root is no longer the same root; reporting incomplete for"
            + " prefix {}", normalized);
        completion = Completion.INCOMPLETE;
      }
      log.info(
          "pruneByPathPrefix: {} - {} checked, {} pruned, {} kept (presence unknown) for prefix {};"
              + " writer fence held {} ms over {} batches (longest {} ms)",
          completion,
          checked,
          batch.pruned,
          batch.keptUnknown,
          normalized,
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(batch.fenceNanos),
          batch.fencedBatches,
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(batch.longestFenceNanos));
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

  private record Candidate(String docId, String filePath, Path file) {}

  /** One indexed row, the backing path that decides it, and the stored fields the scan matches. */
  private record Row(
      String docId, String filePath, String path, String parentDocId, boolean chunk) {
    /** Whether the scan's query, for {@code prefix}, matches this row. */
    boolean inScan(String prefix) {
      return startsWith(path, prefix) || startsWith(docId, prefix)
          || (chunk && startsWith(parentDocId, prefix));
    }

    private static boolean startsWith(String value, String prefix) {
      return value != null && value.startsWith(prefix);
    }

    /**
     * Reads {@code doc}'s id and backing path, or {@code null} for a row without an id. Explicit
     * paths take precedence. For a pathless chunk, the exact owner's stored path is resolved
     * before falling back to its legacy filesystem ID if no path is available.
     */
    static Row read(IndexSearcher searcher, StoredFields storedFields, int doc, String idField)
        throws IOException {
      // Avoid decoding large stored fields (content).
      Map<String, String> fields = SearchResultFormatter.extractFromStoredFields(
          storedFields, doc, false,
          Set.of(idField, SchemaFields.PATH, SchemaFields.PARENT_DOC_ID, SchemaFields.IS_CHUNK));
      String docId = fields.get(idField);
      if (docId == null || docId.isBlank()) {
        return null;
      }
      String path = fields.get(SchemaFields.PATH);
      String parentDocId = fields.get(SchemaFields.PARENT_DOC_ID);
      boolean chunk = "true".equals(fields.get(SchemaFields.IS_CHUNK));
      String filePath = path;
      if (filePath == null || filePath.isBlank()) {
        filePath = docId;
        if (chunk && parentDocId != null && !parentDocId.isBlank()) {
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
      return new Row(docId, filePath, path, parentDocId, chunk);
    }
  }

  /** Absent candidates observed since the last root check. */
  private final class Batch {
    private final PresenceGuard guard;
    private final Consumer<String> sink;
    private final WriterFence fence;
    private final SearcherManager mgr;
    private final String prefix;
    private final String idField;
    private final Path boundary;
    private final List<Candidate> candidates = new ArrayList<>();
    private int pruned;
    private int keptUnknown;
    /** Whether the root was re-checked after an undecidable document in the current batch. */
    private boolean rootCheckedSinceUncertain;
    private long fenceNanos;
    private long longestFenceNanos;
    private int fencedBatches;

    Batch(PresenceGuard guard, Consumer<String> sink, WriterFence fence, SearcherManager mgr,
        String prefix, String idField, Path boundary) {
      this.guard = guard;
      this.sink = sink;
      this.fence = fence;
      this.mgr = mgr;
      this.prefix = prefix;
      this.idField = idField;
      this.boundary = boundary;
    }

    /**
     * Removes the files of the batch's candidates if the root still vouches for their absence,
     * asking it before the batch's rows are gathered and once more after each file is checked
     * again, right before the rows are removed. A batch without candidates asks nothing: nothing
     * is removed on its strength. The whole application runs inside the writer fence, and its
     * removals are visible to the next holder of the fence.
     *
     * @return false when the root check failed; the candidates are discarded, nothing is removed
     */
    boolean flush() throws IOException {
      rootCheckedSinceUncertain = false;
      if (candidates.isEmpty()) {
        return true;
      }
      try {
        return fence.hold(() -> {
          long start = System.nanoTime();
          try {
            return applyFenced();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          } finally {
            long held = System.nanoTime() - start;
            fenceNanos += held;
            longestFenceNanos = Math.max(longestFenceNanos, held);
            fencedBatches++;
          }
        });
      } catch (UncheckedIOException e) {
        throw e.getCause();
      } finally {
        candidates.clear();
      }
    }

    private boolean applyFenced() throws IOException {
      if (!guard.rootCurrent()) {
        log.warn(
            "pruneByPathPrefix: root unavailable or changed mid-prune; {} unconfirmed candidates"
                + " kept",
            candidates.size());
        return false;
      }
      // Each nominated file is judged again now: one that came back, or can no longer be judged,
      // keeps its rows and gets no deletion mark.
      Map<String, Path> files = new LinkedHashMap<>();
      Map<String, Integer> nominatedRows = new java.util.HashMap<>();
      for (Candidate candidate : candidates) {
        files.putIfAbsent(candidate.filePath(), candidate.file());
        nominatedRows.merge(candidate.filePath(), 1, Integer::sum);
      }
      // Rows are taken from a view refreshed inside the fence, so a file's rows are all there,
      // including chunks written since the scan; nothing of it can be published until we leave.
      Map<String, List<String>> rows = new LinkedHashMap<>();
      mgr.maybeRefreshBlocking();
      IndexSearcher current = mgr.acquire();
      try {
        StoredFields storedFields = current.storedFields();
        for (String filePath : files.keySet()) {
          rows.put(filePath, rowsOf(current, storedFields, filePath));
        }
      } finally {
        mgr.release(current);
      }
      // The last checks come after every slow step, right before the removal: each file's absence,
      // then the root that vouches for it. A file that came back, or can no longer be judged, keeps
      // its rows and gets no deletion mark. A file recreated after these checks still loses its
      // rows; the walk that follows the prune finds it on disk and queues it again.
      Map<Path, PathPresence> folderVerdicts = new java.util.HashMap<>();
      for (var file : files.entrySet()) {
        PathPresence presence = guard.presence(file.getValue());
        if (presence == PathPresence.ABSENT) {
          presence =
              PathPresence.absentBeneathListableFolder(file.getValue(), boundary, folderVerdicts);
        }
        if (presence == PathPresence.UNKNOWN) {
          keptUnknown += nominatedRows.get(file.getKey());
        } else if (presence != PathPresence.ABSENT) {
          log.debug("pruneByPathPrefix: kept a file that is present again: {}", file.getKey());
        }
        if (presence != PathPresence.ABSENT) {
          rows.remove(file.getKey());
        }
      }
      if (!guard.rootCurrent()) {
        log.warn(
            "pruneByPathPrefix: root changed while the batch was checked; {} files kept",
            rows.size());
        return false;
      }
      for (var file : rows.entrySet()) {
        for (String docId : file.getValue()) {
          indexingCoordinator.deleteById(docId);
          pruned++;
          sink.accept(file.getKey());
          log.debug("pruneByPathPrefix: deleted orphan document: {}", docId);
        }
      }
      if (rows.values().stream().anyMatch(ids -> !ids.isEmpty())) {
        // Publish the removals before leaving the fence: the next writer reads its parent from
        // this view, and must not rebuild children of a parent that is already gone.
        mgr.maybeRefreshBlocking();
      }
      return true;
    }

    /**
     * The ids of the rows in {@code searcher} whose own backing path is {@code filePath}, selected
     * by the scan's rules: matched by the scan's query, with an explicit path taking precedence
     * over ownership. A row of another file is never selected through its parent. The scan's
     * prefix queries are applied to each row's stored fields rather than run again: expanding them
     * per file would cost a pass over every term of the root.
     */
    private List<String> rowsOf(IndexSearcher searcher, StoredFields storedFields, String filePath)
        throws IOException {
      // A pathless chunk is backed by its owner's path: find the owners stored under this path.
      Query withPath = new TermQuery(new Term(SchemaFields.PATH, filePath));
      List<BytesRef> owners = new ArrayList<>();
      owners.add(new BytesRef(filePath));
      for (var hit : allHits(searcher, withPath)) {
        String ownerId = SearchResultFormatter.extractFromStoredFields(
            storedFields, hit.doc, false, Set.of(idField)).get(idField);
        if (ownerId != null && !ownerId.isBlank()) {
          owners.add(new BytesRef(ownerId));
        }
      }
      Query sameFile = new BooleanQuery.Builder()
          .add(withPath, BooleanClause.Occur.SHOULD)
          .add(new TermQuery(new Term(idField, filePath)), BooleanClause.Occur.SHOULD)
          .add(new TermInSetQuery(SchemaFields.PARENT_DOC_ID, owners), BooleanClause.Occur.SHOULD)
          .setMinimumNumberShouldMatch(1)
          .build();
      List<String> ids = new ArrayList<>();
      for (var hit : allHits(searcher, sameFile)) {
        Row row = Row.read(searcher, storedFields, hit.doc, idField);
        if (row != null && row.filePath().equals(filePath) && row.inScan(prefix)) {
          ids.add(row.docId());
        }
      }
      return ids;
    }
  }

  /** Every hit of {@code query}, however many there are. */
  private static org.apache.lucene.search.ScoreDoc[] allHits(IndexSearcher searcher, Query query)
      throws IOException {
    int count = searcher.count(query);
    return count == 0
        ? new org.apache.lucene.search.ScoreDoc[0]
        : searcher.search(query, count).scoreDocs;
  }
}
