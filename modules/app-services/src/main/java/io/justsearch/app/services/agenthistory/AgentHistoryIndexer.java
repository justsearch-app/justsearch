/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.agenthistory;

import io.justsearch.app.services.worker.KnowledgeClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 585 §D Phase 4 (D4a) — "search-your-own-agent-history", the ingestion half: when an agent
 * run finishes, synthesise a markdown transcript (the final answer + what the agent found) and index
 * it into a dedicated {@code agent-history} Lucene collection, so the user can later search their
 * agent history with the SAME hybrid retrieval the app uses for documents. Uniquely enabled by
 * 585's durable-runs × the product's search core (§D.4) — the product searching its own assistant's
 * memory.
 *
 * <p>Wiring: a terminal-run listener on {@code RunEventStore.addEventListener} (registered in
 * {@code HeadAssembly}). The {@code done}/{@code error} record carries the run's final answer +
 * grounding sources, so no full-history replay is needed. The transcript is written atomically
 * (temp + {@code ATOMIC_MOVE}) and indexed via the explicit-collection ingest API
 * ({@link KnowledgeClient#submitBatch(List, boolean, String)} with {@code "agent-history"}) —
 * which sidesteps the YAML-only watched-collection config (the transcript does not need a watched
 * root). The search-side scoping (default-exclude + an "Agent history" scope) is the D4b half.
 *
 * <p>Off the hot path: the listener fires synchronously on the terminal-event append, so the
 * write + the blocking ingest RPC run on a daemon single-thread executor — a slow/hung worker can
 * never stall the agent loop's final emit. Fully fail-soft: any error is logged, never propagated.
 */
public final class AgentHistoryIndexer {

  /** The reserved collection tag for indexed agent transcripts (shared with the D4b search scope). */
  public static final String COLLECTION = "agent-history";

  /**
   * The first characters of every transcript {@link #renderTranscript} produces, and therefore the
   * readability probe {@link #reconcileNow} uses. One constant so the writer and the check cannot
   * drift into a reconciliation that rebuilds every file forever (or none of them).
   */
  static final String TRANSCRIPT_HEADER = "# Agent run";

  /**
   * Transcripts one reconciliation pass will RE-DERIVE. It bounds the writes, not the pass: every
   * session the caller supplies is still stat'd by {@link #isReadableTranscript} before this cap can
   * apply, and the caller itself pays a listing (HeadAssembly asks {@code AgentRunStore} for every
   * session, which reads and sorts each run's meta). So one pass costs roughly a few filesystem
   * operations per persisted run, at boot AND at each unlock, plus at most this many rebuilds. That
   * is off the boot thread and small for realistic run counts, but it is a per-run cost, not a
   * constant one — the honest bound and the mitigation (a scan cap or a "last reconciled" marker)
   * are recorded in tempdoc 909 §E. The pass is idempotent, so rebuilds beyond the cap are picked up
   * by the next start or unlock.
   */
  private static final int MAX_REBUILDS_PER_PASS = 200;

  /**
   * Tempdoc 909 §E item 1 — the suffix of the durable "written but not yet indexed" marker that
   * sits beside {@code <sessionId>.md}. The Head cannot ask the index what it holds (index I/O is
   * the Worker's), so the one thing it CAN do is remember, on its own disk, that a submit still
   * owes. That is the bounded Head-side half; the index-side reconciliation the Worker would own
   * is explicitly not this.
   */
  static final String PENDING_SUFFIX = ".md.pending";

  private static final Logger LOG = LoggerFactory.getLogger(AgentHistoryIndexer.class);

  private final Path historyDir;
  private final Supplier<KnowledgeClient> clientSupplier;
  private final ExecutorService executor;

  /**
   * Wire a terminal-run transcript indexer onto a {@code RunEventStore} listener registrar (the
   * one-line composition seam, mirroring {@code AgentDispositionWiring.register}).
   */
  public static AgentHistoryIndexer register(
      java.util.function.Consumer<java.util.function.BiConsumer<String, Map<String, Object>>>
          addEventListener,
      Path historyDir,
      Supplier<KnowledgeClient> clientSupplier) {
    var indexer = new AgentHistoryIndexer(historyDir, clientSupplier);
    addEventListener.accept(indexer::onEvent);
    return indexer;
  }

  public AgentHistoryIndexer(Path historyDir, Supplier<KnowledgeClient> clientSupplier) {
    this.historyDir = historyDir;
    this.clientSupplier = clientSupplier;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "agent-history-indexer");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * The {@code RunEventStore} listener: on a terminal {@code done}/{@code error} record, schedule the
   * transcript write + ingest off the hot path. Non-terminal events are ignored.
   */
  public void onEvent(String sessionId, Map<String, Object> record) {
    if (record == null) {
      return;
    }
    String eventType = eventTypeOf(record);
    if (!"done".equals(eventType) && !"error".equals(eventType)) {
      return;
    }
    Map<String, Object> payload = payloadOf(record);
    boolean errored = "error".equals(eventType);
    executor.execute(() -> writeAndIndex(sessionId, payload, errored));
  }

  /**
   * Tempdoc 629 (#E faithful import) — re-index a RESTORED run's transcript from its (already-persisted)
   * events. Faithful backup-import does not fire listeners (replaying historical events must not
   * re-trigger live projectors as if they were happening now), so a restored run never reaches the live
   * {@link #onEvent} path and would be viewable-but-not-searchable. This replays the restored events
   * through {@link #onEvent}, which self-filters to the terminal {@code done}/{@code error} event and
   * indexes its transcript via the SAME off-the-hot-path, fail-soft route live runs use. Import is
   * skip-existing-by-session, so only NEW runs reach here — the re-index can never duplicate.
   */
  @SuppressWarnings("unchecked")
  public void reindexRestoredRun(String sessionId, List<?> events) {
    if (events == null) {
      return;
    }
    for (Object ev : events) {
      if (ev instanceof Map<?, ?>) {
        onEvent(sessionId, (Map<String, Object>) ev);
      }
    }
  }

  /**
   * Tempdoc 909 item 1 — re-derive the transcripts that are missing or unreadable, off the boot
   * thread. Runs on the same single daemon thread the live path uses, so it can never delay boot or
   * an agent run.
   *
   * @param sessionIds every persisted run id, newest-first (the caller bounds the scan)
   * @param eventLoader loads one run's persisted events; may return null/empty when the run is gone
   *     or the store is locked
   */
  public void reconcile(Supplier<List<String>> sessionIds, Function<String, List<?>> eventLoader) {
    executor.execute(() -> reconcileNow(sessionIds, eventLoader));
  }

  /**
   * The synchronous body of {@link #reconcile} — returns how many transcripts it re-derived.
   *
   * <p><b>The corruption policy this implements</b> (register row {@code agent-history-transcripts},
   * {@code REGENERATE_FROM_RUN_EVENTS_OR_PRESERVE}): a transcript is a DERIVED projection of the
   * sealed {@code agent-runs} terminal event, so a missing or unreadable one is re-derived from
   * that event and re-indexed. When the run is NOT available — deleted, or the store is locked on an
   * encrypted install — the bytes on disk are left exactly as they are and the transcript is simply
   * not re-indexed. It is never deleted: an unreadable store is indistinguishable from an empty one
   * here (the same trap {@code AgentRunReconciler} documents), so a "drop what cannot be rebuilt"
   * rule would erase every good transcript on the first locked boot.
   *
   * <p><b>And the pending-index half</b> (tempdoc 909 §E item 1): a transcript whose bytes are fine
   * but whose {@code submitBatch} never happened carries a {@link #PENDING_SUFFIX} marker; this
   * pass re-submits those once a knowledge client exists and clears the marker only on success.
   * The returned count is rebuilds only — a re-submit derives nothing.
   */
  int reconcileNow(Supplier<List<String>> sessionIds, Function<String, List<?>> eventLoader) {
    List<String> ids;
    try {
      ids = sessionIds.get();
    } catch (RuntimeException e) {
      LOG.warn("Agent-history reconciliation could not list runs", e);
      return 0;
    }
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    int rebuilt = 0;
    int preserved = 0;
    int resubmitted = 0;
    for (String sessionId : ids) {
      if (sessionId == null || sessionId.isBlank()) {
        continue;
      }
      if (isReadableTranscript(transcriptPath(sessionId))) {
        // Tempdoc 909 §E item 1: healthy bytes are not evidence of a healthy INDEX. A transcript
        // written while the knowledge client was null never reached submitBatch, and every later
        // pass used to skip it here — permanently missing from the collection. The marker is the
        // fact that distinguishes the two, so it, not the file's readability, decides.
        if (isPending(sessionId)) {
          try {
            submitAndClearPending(sessionId, transcriptPath(sessionId));
            if (!isPending(sessionId)) {
              resubmitted++;
            }
          } catch (IOException | RuntimeException e) {
            LOG.debug(
                "Agent-history transcript {} is still pending index: {}", sessionId, e.toString());
          }
        }
        continue;
      }
      if (rebuilt >= MAX_REBUILDS_PER_PASS) {
        LOG.info(
            "Agent-history reconciliation stopped at {} rebuilds; the rest resume next start",
            MAX_REBUILDS_PER_PASS);
        break;
      }
      Map<String, Object> terminal;
      try {
        terminal = terminalRecord(eventLoader.apply(sessionId));
      } catch (RuntimeException e) {
        LOG.debug("Agent-history reconciliation could not read run {}: {}", sessionId, e.toString());
        terminal = null;
      }
      if (terminal == null) {
        if (Files.exists(transcriptPath(sessionId))) {
          preserved++;
        }
        continue; // nothing to re-derive from — preserve whatever is on disk
      }
      writeAndIndex(sessionId, payloadOf(terminal), "error".equals(eventTypeOf(terminal)));
      rebuilt++;
    }
    if (rebuilt > 0) {
      LOG.info("Re-derived {} agent-history transcript(s) from their runs", rebuilt);
    }
    if (resubmitted > 0) {
      LOG.info(
          "Indexed {} agent-history transcript(s) that were written while the worker was"
              + " unavailable",
          resubmitted);
    }
    if (preserved > 0) {
      LOG.warn(
          "{} agent-history transcript(s) are unreadable and their runs are unavailable; the files"
              + " were left untouched and are not searchable",
          preserved);
    }
    return rebuilt;
  }

  private Path transcriptPath(String sessionId) {
    return historyDir.resolve(sessionId + ".md");
  }

  /**
   * Whether {@code target} is a transcript this class wrote: a non-empty file starting with the
   * header {@link #renderTranscript} always emits. A zero-length or foreign-content file is the
   * observable shape of a torn write, and is treated as absent rather than left un-searchable
   * forever.
   */
  private static boolean isReadableTranscript(Path target) {
    try {
      if (!Files.isRegularFile(target) || Files.size(target) == 0) {
        return false;
      }
      try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
        char[] head = new char[TRANSCRIPT_HEADER.length()];
        int read = reader.read(head);
        return read == head.length && TRANSCRIPT_HEADER.equals(new String(head));
      }
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** The run's terminal {@code done}/{@code error} record, or null when it has none. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> terminalRecord(List<?> events) {
    if (events == null) {
      return null;
    }
    Map<String, Object> terminal = null;
    for (Object ev : events) {
      if (ev instanceof Map<?, ?> record) {
        Map<String, Object> typed = (Map<String, Object>) record;
        String eventType = eventTypeOf(typed);
        if ("done".equals(eventType) || "error".equals(eventType)) {
          terminal = typed;
        }
      }
    }
    return terminal;
  }

  private static String eventTypeOf(Map<String, Object> record) {
    return String.valueOf(record.get("eventType"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadOf(Map<String, Object> record) {
    return record.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
  }

  private void writeAndIndex(String sessionId, Map<String, Object> payload, boolean errored) {
    try {
      Files.createDirectories(historyDir);
      Path target = transcriptPath(sessionId);
      atomicWrite(target, renderTranscript(sessionId, payload, errored));
      // Tempdoc 909 §E item 1: the marker is written BEFORE the submit, so every window in which
      // the ingest can be lost — no client (Worker down / not yet connected), a throwing RPC, a
      // crash between the two — leaves the same durable "this transcript is not indexed" fact on
      // disk. Without it the transcript is healthy, reconciliation skips it forever, and the run
      // is permanently missing from the agent-history collection.
      markPending(sessionId);
      submitAndClearPending(sessionId, target);
    } catch (Exception e) {
      // Fail-soft — a failed history index must never affect the run or the user.
      LOG.warn("Failed to index agent-history transcript for session {}", sessionId, e);
    }
  }

  /**
   * Submit the transcript to the {@code agent-history} collection and clear its pending marker.
   *
   * <p>The marker is cleared ONLY after {@code submitBatch} returns normally: no client means the
   * ingest never happened, and a throwing RPC means it may not have. Either way the marker stays
   * and the next reconciliation pass retries. Re-submitting an already-indexed transcript is
   * harmless — the ingest is keyed by path and forced, so it re-indexes the same document.
   */
  private void submitAndClearPending(String sessionId, Path target) throws IOException {
    KnowledgeClient client = clientSupplier.get();
    if (client == null) {
      return; // marker stays; a later pass with a client submits it
    }
    client.submitBatch(List.of(target), true, COLLECTION);
    Files.deleteIfExists(pendingMarkerPath(sessionId));
  }

  /** The durable "written but not yet indexed" marker, a sibling of the transcript it names. */
  private Path pendingMarkerPath(String sessionId) {
    return historyDir.resolve(sessionId + PENDING_SUFFIX);
  }

  private boolean isPending(String sessionId) {
    return Files.isRegularFile(pendingMarkerPath(sessionId));
  }

  private void markPending(String sessionId) throws IOException {
    Path marker = pendingMarkerPath(sessionId);
    if (!Files.exists(marker)) {
      Files.writeString(marker, "", StandardCharsets.UTF_8);
    }
  }

  /** Build the searchable markdown: the final answer + what the agent found (its grounding sources). */
  @SuppressWarnings("unchecked")
  static String renderTranscript(String sessionId, Map<String, Object> payload, boolean errored) {
    StringBuilder md = new StringBuilder();
    if (errored) {
      md.append(TRANSCRIPT_HEADER).append(" (error)\n\n");
      md.append(str(payload.get("error"))).append("\n");
      return md.toString();
    }
    String answer = str(payload.get("finalResponse"));
    md.append(TRANSCRIPT_HEADER).append("\n\n");
    md.append(answer).append("\n");

    Object sourcesObj = payload.get("sources");
    if (sourcesObj instanceof List<?> sources && !sources.isEmpty()) {
      md.append("\n## What the agent found\n\n");
      for (Object s : sources) {
        if (s instanceof Map<?, ?> src) {
          Map<String, Object> m = (Map<String, Object>) src;
          String title = str(m.get("title"));
          String path = str(m.get("path"));
          String excerpt = str(m.get("excerpt"));
          md.append("- **").append(title.isBlank() ? path : title).append("**");
          if (!path.isBlank()) {
            md.append(" (").append(path).append(")");
          }
          if (!excerpt.isBlank()) {
            md.append(": ").append(excerpt);
          }
          md.append("\n");
        }
      }
    }

    md.append("\n---\n");
    md.append(
        String.format(
            Locale.ROOT,
            "Iterations: %s · Tool calls: %s · Tokens: %s\n",
            str(payload.get("iterationsUsed")),
            str(payload.get("toolCallsExecuted")),
            str(payload.get("totalTokensUsed"))));
    return md.toString();
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  /** Atomic write (temp + ATOMIC_MOVE) — the FileOperationLog pattern. */
  private static void atomicWrite(Path target, String content) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
