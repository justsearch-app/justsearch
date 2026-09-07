package io.justsearch.app.services.agenthistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.KnowledgeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 585 §D Phase 4 (D4a) — the agent-history transcript rendering + 629 restored-run re-index. */
class AgentHistoryIndexerTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a finished run renders the answer + what it found (grounding sources) + a footer")
  void doneTranscriptCarriesAnswerAndSources() {
    Map<String, Object> payload =
        Map.of(
            "finalResponse", "Your Q3 taxes are in tax-2024.pdf.",
            "iterationsUsed", 3,
            "toolCallsExecuted", 2,
            "totalTokensUsed", 1500,
            "sources",
            List.of(
                Map.of(
                    "title", "Q3 Tax Summary",
                    "path", "/docs/tax-2024.pdf",
                    "excerpt", "Total owed: $4,200")));

    String md = AgentHistoryIndexer.renderTranscript("sess-1", payload, false);

    // The answer is searchable body text.
    assertTrue(md.contains("Your Q3 taxes are in tax-2024.pdf."), "answer present");
    // "What the agent found" — the grounding source title + excerpt are indexed too.
    assertTrue(md.contains("What the agent found"), "sources section present");
    assertTrue(md.contains("Q3 Tax Summary"), "source title present");
    assertTrue(md.contains("Total owed: $4,200"), "source excerpt present");
    // The run footer.
    assertTrue(md.contains("Iterations: 3"), "iteration count present");
    assertTrue(md.contains("Tool calls: 2"), "tool-call count present");
  }

  @Test
  @DisplayName("an errored run renders the error, not an empty answer")
  void errorTranscriptCarriesTheError() {
    Map<String, Object> payload = Map.of("error", "model timed out");
    String md = AgentHistoryIndexer.renderTranscript("sess-2", payload, true);
    assertTrue(md.contains("error"), "marked as error");
    assertTrue(md.contains("model timed out"), "error message present");
    assertFalse(md.contains("What the agent found"), "no sources section for an error");
  }

  @Test
  @DisplayName("629: re-indexing a RESTORED run writes its transcript from the terminal event")
  void reindexRestoredRunWritesTranscriptFromTerminalEvent() throws Exception {
    Path historyDir = tempDir.resolve("agent-history");
    // null client → the .md is still written; submitBatch (the only client-gated step) is skipped.
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);
    var events =
        List.of(
            Map.of(
                "eventType", "session_started",
                "payload", Map.of("sessionId", "sess-restored")),
            Map.of(
                "eventType",
                "done",
                "payload",
                Map.of(
                    "finalResponse", "RESTORED answer marker ZQXRESTORE.",
                    "iterationsUsed", 1,
                    "toolCallsExecuted", 0,
                    "totalTokensUsed", 10,
                    "sources",
                    List.of(
                        Map.of("title", "Doc A", "path", "/d/a.md", "excerpt", "found-snippet")))));

    indexer.reindexRestoredRun("sess-restored", events);

    Path md = historyDir.resolve("sess-restored.md");
    for (int i = 0; i < 150 && !Files.exists(md); i++) {
      Thread.sleep(20); // the write runs on the indexer's daemon executor (off the hot path)
    }
    assertTrue(Files.exists(md), "restored run's transcript .md was written");
    String content = Files.readString(md);
    assertTrue(content.contains("RESTORED answer marker ZQXRESTORE."), "the answer is indexed (searchable)");
    assertTrue(content.contains("What the agent found"), "grounding sources are indexed");
    assertTrue(content.contains("Doc A"), "source title indexed");
  }

  // ===== Tempdoc 909 item 1 — the transcript recovery policy =====

  /** One terminal `done` event whose answer carries {@code marker}. */
  private static List<Map<String, Object>> doneEvents(String marker) {
    return List.of(
        Map.of("eventType", "started", "payload", Map.of()),
        Map.of(
            "eventType",
            "done",
            "payload",
            Map.of(
                "finalResponse", marker,
                "iterationsUsed", 1,
                "toolCallsExecuted", 0,
                "totalTokensUsed", 10)));
  }

  @Test
  @DisplayName("909: a MISSING transcript is re-derived from the run's terminal event")
  void reconcileRebuildsAMissingTranscript() throws Exception {
    Path historyDir = tempDir.resolve("h-missing");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);

    int rebuilt =
        indexer.reconcileNow(() -> List.of("sess-gone"), id -> doneEvents("REBUILT-ZQX-1"));

    assertEquals(1, rebuilt);
    assertTrue(
        Files.readString(historyDir.resolve("sess-gone.md")).contains("REBUILT-ZQX-1"),
        "the transcript is re-derived from agent-runs, not left permanently un-searchable");
  }

  @Test
  @DisplayName("909: a TORN transcript (zero-length or foreign bytes) is replaced, not kept")
  void reconcileReplacesATornTranscript() throws Exception {
    Path historyDir = Files.createDirectories(tempDir.resolve("h-torn"));
    Files.writeString(historyDir.resolve("sess-torn.md"), ""); // a torn write's observable shape
    // NUL-prefixed on purpose (written as escapes so this file stays text): a crash after the
    // rename but before the bytes land leaves a zero-filled prefix, which is the second shape
    // a torn transcript takes on disk.
    Files.writeString(historyDir.resolve("sess-garbage.md"), "\0\0not a transcript");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);

    int rebuilt =
        indexer.reconcileNow(
            () -> List.of("sess-torn", "sess-garbage"), id -> doneEvents("REBUILT-ZQX-" + id));

    assertEquals(2, rebuilt);
    assertTrue(Files.readString(historyDir.resolve("sess-torn.md")).contains("REBUILT-ZQX-sess-torn"));
    assertTrue(
        Files.readString(historyDir.resolve("sess-garbage.md")).contains("REBUILT-ZQX-sess-garbage"));
  }

  /**
   * The destructive-branch half of the policy, and the reason it is not "drop what cannot be
   * rebuilt": on an encrypted install with the store locked, the run store lists nothing and reads
   * nothing — indistinguishable from "the run was deleted". A rule that deleted the unrebuildable
   * transcript would erase the user's whole agent history on the first locked boot.
   */
  @Test
  @DisplayName("909: an unreadable transcript whose run is unavailable is PRESERVED, never deleted")
  void reconcilePreservesWhatItCannotRebuild() throws Exception {
    Path historyDir = Files.createDirectories(tempDir.resolve("h-locked"));
    Path unreadable = historyDir.resolve("sess-locked.md");
    Files.writeString(unreadable, "\0\0not a transcript");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);

    // A locked store: sessions list, but reading their events yields nothing.
    int rebuilt = indexer.reconcileNow(() -> List.of("sess-locked"), id -> List.of());

    assertEquals(0, rebuilt);
    assertTrue(Files.exists(unreadable), "the bytes must survive a pass that cannot rebuild them");
    assertEquals(
        "\0\0not a transcript",
        Files.readString(unreadable),
        "…and survive unmodified");
  }

  @Test
  @DisplayName("909: a healthy transcript is left exactly as it is (no rewrite, no re-index)")
  void reconcileLeavesAHealthyTranscriptAlone() throws Exception {
    Path historyDir = Files.createDirectories(tempDir.resolve("h-healthy"));
    Path good = historyDir.resolve("sess-good.md");
    Files.writeString(good, AgentHistoryIndexer.TRANSCRIPT_HEADER + "\n\nthe original answer\n");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);

    int rebuilt =
        indexer.reconcileNow(
            () -> List.of("sess-good"),
            id -> {
              throw new AssertionError("a healthy transcript must not load its run's events");
            });

    assertEquals(0, rebuilt);
    assertTrue(Files.readString(good).contains("the original answer"));
  }

  // ===== Tempdoc 909 §E item 1 — a transcript written while the Worker is down =====
  //
  // The defect: writeAndIndex wrote the .md, found a null client, and returned. The bytes were
  // healthy, so every later reconciliation pass skipped the session at the isReadableTranscript
  // guard — the run was permanently absent from the agent-history collection with nothing on disk
  // recording that fact. The bounded Head-side fix is a durable pending marker; the Worker-owned
  // index-side reconciliation 909 also describes is deliberately NOT built here.

  /** The marker that says "written, not yet indexed". */
  private static Path marker(Path historyDir, String sessionId) {
    return historyDir.resolve(sessionId + AgentHistoryIndexer.PENDING_SUFFIX);
  }

  @Test
  @DisplayName("909: a transcript written with NO client leaves a pending marker (nothing submitted)")
  void transcriptWrittenWithoutAClientIsMarkedPending() throws Exception {
    Path historyDir = tempDir.resolve("h-pending");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null); // the Worker is down

    indexer.reconcileNow(() -> List.of("sess-down"), id -> doneEvents("WROTE-WHILE-DOWN"));

    assertTrue(Files.exists(historyDir.resolve("sess-down.md")), "the transcript is still written");
    assertTrue(
        Files.exists(marker(historyDir, "sess-down")),
        "…and the fact that it never reached the index is recorded ON DISK, not just lost");
  }

  /**
   * The right-reason control for the test above: with a client present the SAME write submits and
   * leaves no marker. Without this, a marker that was written unconditionally would pass the test
   * above while meaning nothing.
   */
  @Test
  @DisplayName("909: the same write WITH a client submits and leaves no marker")
  void transcriptWrittenWithAClientIsNotMarkedPending() throws Exception {
    Path historyDir = tempDir.resolve("h-up");
    KnowledgeClient client = mock(KnowledgeClient.class);
    var indexer = new AgentHistoryIndexer(historyDir, () -> client);

    indexer.reconcileNow(() -> List.of("sess-up"), id -> doneEvents("WROTE-WHILE-UP"));

    verify(client, times(1))
        .submitBatch(
            List.of(historyDir.resolve("sess-up.md")), true, AgentHistoryIndexer.COLLECTION);
    assertFalse(
        Files.exists(marker(historyDir, "sess-up")),
        "a submitted transcript carries no pending marker");
  }

  /**
   * The recovery itself, across a RESTART: the marker is durable, so a brand-new indexer instance
   * over the same directory — which is what a later boot actually is — submits the transcript
   * exactly once and clears the marker. A second pass after that must submit nothing, or every
   * boot would re-ingest the entire history.
   */
  @Test
  @DisplayName("909: a later pass (new instance, same dir) submits the pending transcript once and clears it")
  void aLaterPassSubmitsThePendingTranscriptExactlyOnceAndClearsTheMarker() throws Exception {
    Path historyDir = tempDir.resolve("h-recover");

    // Boot 1: the Worker is down.
    new AgentHistoryIndexer(historyDir, () -> null)
        .reconcileNow(() -> List.of("sess-r"), id -> doneEvents("RECOVER-ZQX"));
    assertTrue(Files.exists(marker(historyDir, "sess-r")), "precondition: pending after boot 1");

    // Boot 2: a NEW indexer over the same directory, Worker up. The marker is the only carrier of
    // state between the two — there is no in-memory queue to inherit.
    KnowledgeClient client = mock(KnowledgeClient.class);
    var rebooted = new AgentHistoryIndexer(historyDir, () -> client);
    int rebuilt = rebooted.reconcileNow(() -> List.of("sess-r"), id -> doneEvents("RECOVER-ZQX"));

    assertEquals(0, rebuilt, "a re-submit derives nothing — the healthy bytes are reused as-is");
    verify(client, times(1))
        .submitBatch(List.of(historyDir.resolve("sess-r.md")), true, AgentHistoryIndexer.COLLECTION);
    assertFalse(Files.exists(marker(historyDir, "sess-r")), "the marker is cleared after the submit");
    assertTrue(
        Files.readString(historyDir.resolve("sess-r.md")).contains("RECOVER-ZQX"),
        "the ORIGINAL transcript is what got indexed, not a rewrite");

    // Boot 3: nothing left to do. `verifyNoMoreInteractions` is what makes "exactly once" true
    // across passes rather than once per pass.
    rebooted.reconcileNow(() -> List.of("sess-r"), id -> doneEvents("RECOVER-ZQX"));
    verifyNoMoreInteractions(client);
  }

  /**
   * The skip that must survive the fix: a healthy transcript with NO marker is left alone — not
   * rewritten (the 909 test above) and not re-submitted either. Otherwise the marker would have
   * bought recovery at the price of re-ingesting every transcript on every boot.
   */
  @Test
  @DisplayName("909: a healthy transcript with no marker is still skipped — no submit, no rebuild")
  void aHealthyUnmarkedTranscriptIsStillSkipped() throws Exception {
    Path historyDir = Files.createDirectories(tempDir.resolve("h-skip"));
    Path good = historyDir.resolve("sess-ok.md");
    Files.writeString(good, AgentHistoryIndexer.TRANSCRIPT_HEADER + "\n\nthe original answer\n");
    KnowledgeClient client = mock(KnowledgeClient.class);
    var indexer = new AgentHistoryIndexer(historyDir, () -> client);

    int rebuilt =
        indexer.reconcileNow(
            () -> List.of("sess-ok"),
            id -> {
              throw new AssertionError("an unmarked healthy transcript must not load its events");
            });

    assertEquals(0, rebuilt);
    verifyNoInteractions(client);
    assertTrue(Files.readString(good).contains("the original answer"), "left byte-identical");
  }

  /**
   * The marker outlives a failed retry. A throwing {@code submitBatch} — the Worker up but the RPC
   * failing — must leave the marker exactly as a null client does, or a single transient failure
   * would silently consume the recovery.
   */
  @Test
  @DisplayName("909: a FAILING submit leaves the marker for the next pass")
  void aFailingSubmitLeavesTheMarkerInPlace() throws Exception {
    Path historyDir = tempDir.resolve("h-rpcfail");
    new AgentHistoryIndexer(historyDir, () -> null)
        .reconcileNow(() -> List.of("sess-f"), id -> doneEvents("RPC-FAIL-ZQX"));

    KnowledgeClient failing = mock(KnowledgeClient.class);
    when(failing.submitBatch(anyList(), anyBoolean(), anyString()))
        .thenThrow(new IllegalStateException("worker RPC failed"));
    new AgentHistoryIndexer(historyDir, () -> failing)
        .reconcileNow(() -> List.of("sess-f"), id -> doneEvents("RPC-FAIL-ZQX"));

    assertTrue(
        Files.exists(marker(historyDir, "sess-f")),
        "a throwing submit is not a successful one — the marker must survive it");

    // …and the pass after that, with a working client, still recovers the transcript.
    KnowledgeClient ok = mock(KnowledgeClient.class);
    new AgentHistoryIndexer(historyDir, () -> ok)
        .reconcileNow(() -> List.of("sess-f"), id -> doneEvents("RPC-FAIL-ZQX"));
    verify(ok, times(1))
        .submitBatch(List.of(historyDir.resolve("sess-f.md")), true, AgentHistoryIndexer.COLLECTION);
    assertFalse(Files.exists(marker(historyDir, "sess-f")));
  }

  @Test
  @DisplayName("629: a restored run with no terminal event indexes nothing (the self-filter holds)")
  void reindexRestoredRunWithNoTerminalEventWritesNothing() throws Exception {
    Path historyDir = tempDir.resolve("agent-history-2");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);
    indexer.reindexRestoredRun(
        "sess-partial", List.of(Map.of("eventType", "session_started", "payload", Map.of())));
    Thread.sleep(100); // give the (would-be) executor time; a terminal-less run must write nothing
    assertFalse(
        Files.exists(historyDir.resolve("sess-partial.md")), "no terminal event → no transcript");
  }
}
