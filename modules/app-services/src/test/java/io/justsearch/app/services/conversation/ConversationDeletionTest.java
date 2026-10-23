/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.AgentRunStore;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.services.agenthistory.AgentHistoryIndexer;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.DeleteByIdResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationDeletionTest {
  @TempDir Path temp;

  @Test
  void removesBothPlanesAndHistoryButPreservesAnotherConversation() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    when(client.deleteHistoryTranscript(any(), any())).thenReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build());
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      store.createSession("conversation", "core.free-chat");
      store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "private question"));
      seedRun(runs, "run", "conversation");
      seedRun(runs, "survivor", "other");
      deletion.delete("conversation");
      assertTrue(store.listSessions(null, 20).isEmpty());
      assertTrue(store.loadHistory("conversation").isEmpty());
      assertFalse(Files.exists(temp.resolve("runs/run")));
      assertFalse(Files.exists(temp.resolve("history/run.md")));
      assertFalse(Files.exists(temp.resolve("history/run.md.pending")));
      assertFalse(Files.exists(temp.resolve("history/run.md.tmp")));
      assertTrue(Files.exists(temp.resolve("history/survivor.md")));
      assertTrue(Files.exists(temp.resolve("runs/survivor/meta.json")));
      verify(client).deleteHistoryTranscript(eq(temp.resolve("history/run.md")), any());
      verify(client, never()).deleteHistoryTranscript(eq(temp.resolve("history/survivor.md")), any());
      assertTrue(store.pendingDeletions().isEmpty());
    }
  }

  @Test
  void runOnlyConversationIsDeletedCompletely() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    when(client.deleteHistoryTranscript(any(), any())).thenReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build());
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      seedRun(runs, "run", "conversation");
      deletion.delete("conversation");
      assertTrue(runs.listConversations(20).isEmpty());
      assertFalse(Files.exists(temp.resolve("history/run.md")));
      assertTrue(store.listSessions(null, 20).isEmpty());
    }
  }

  @Test
  void failureStaysListedBlocksNewWritesAndRetryFinishes() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    when(client.deleteHistoryTranscript(any(), any()))
        .thenReturn(DeleteByIdResponse.newBuilder().setError("permission denied").build())
        .thenReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build());
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      seedRun(runs, "run", "conversation");
      assertThrows(IllegalStateException.class, () -> deletion.delete("conversation"));
      assertEquals("conversation", store.listSessions(null, 20).getFirst().sessionId());
      assertThrows(IllegalStateException.class, () -> store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "late")));
      deletion.delete("conversation");
      assertTrue(store.listSessions(null, 20).isEmpty());
      assertFalse(Files.exists(temp.resolve("runs/run")));
      assertFalse(Files.exists(temp.resolve("history/run.md")));
    }
  }

  @Test
  void interruptedDeleteRecoversOnNextStartEvenAfterRunMetadataWasLost() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    when(client.deleteHistoryTranscript(any(), any())).thenThrow(new AssertionError("engine stop"));
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      seedRun(runs, "run", "conversation");
      assertThrows(AssertionError.class, () -> deletion.delete("conversation"));
      assertFalse(store.listSessions(null, 20).isEmpty(), "an interrupted delete must keep a visible recovery row");
    }
    Files.deleteIfExists(temp.resolve("runs/run/meta.json"));
    var restarted = new FileConversationStore(temp.resolve("conversations"));
    var restartedRuns = new AgentRunStore(temp.resolve("runs"));
    doReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build()).when(client).deleteHistoryTranscript(any(), any());
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      deletion(restarted, restartedRuns, history).recoverPending();
      assertTrue(restarted.listSessions(null, 20).isEmpty());
      assertFalse(Files.exists(temp.resolve("runs/run")));
      assertFalse(Files.exists(temp.resolve("history/run.md")));
    }
  }

  @Test
  void successfulDeleteDropsRetiredReplayAndItsRecordHint() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var channels = new RunChannelRegistry();
    var id = io.justsearch.app.observability.stream.run.RunId.mint();
    var survivor = io.justsearch.app.observability.stream.run.RunId.mint();
    channels.open(id, new io.justsearch.app.observability.stream.run.RunDescriptor("core.rag-ask", "conversation", 1),
        io.justsearch.app.observability.stream.run.RunChannelPolicy.conversational());
    channels.open(survivor, new io.justsearch.app.observability.stream.run.RunDescriptor("core.rag-ask", "other", 1),
        io.justsearch.app.observability.stream.run.RunChannelPolicy.conversational());
    channels.retire(id);
    channels.retire(survivor);
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> null)) {
      new ConversationDeletion(store, runs, history, channels, DataKeyManager.disabled()).delete("conversation");
      assertTrue(channels.find(id).isEmpty(), "deleted conversation content must not remain replayable");
      assertTrue(channels.retiredDescriptor(id).isEmpty());
      assertTrue(channels.find(survivor).isPresent());
    }
  }

  @Test
  void waitsOutQueuedHistoryAndReconciliationCannotRecreateIt() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    var writing = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var completed = new java.util.concurrent.CountDownLatch(1);
    var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    when(client.submitBatch(anyList(), eq(true), eq(AgentHistoryIndexer.COLLECTION), any())).thenAnswer(invocation -> {
      writing.countDown();
      assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
      return io.justsearch.ipc.BatchResponse.newBuilder().setAcceptedCount(1).build();
    });
    when(client.deleteHistoryTranscript(any(), any())).thenReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build());
    Thread deleting = null;
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      seedRun(runs, "run", "conversation");
      history.onEvent("run", Map.of("eventType", "done", "payload", Map.of("finalAnswer", "queued private answer")));
      assertTrue(writing.await(2, java.util.concurrent.TimeUnit.SECONDS));
      deleting = Thread.ofVirtual().start(() -> {
        try { deletion.delete("conversation"); }
        catch (Throwable failure) { failed.set(failure); }
        finally { completed.countDown(); }
      });
      try {
        assertFalse(completed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS), "deletion must wait out the invisible writer");
      } finally { release.countDown(); }
      assertTrue(completed.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(failed.get());
      history.reconcile(() -> runs.listSessions(100).stream().map(r -> (String) r.get("sessionId")).toList(),
          id -> runs.runEvents().readEvents(id));
    } finally {
      release.countDown();
      if (deleting != null) deleting.join(3000);
    }
    assertFalse(Files.exists(temp.resolve("history/run.md")));
    assertFalse(Files.exists(temp.resolve("history/run.md.pending")));
  }

  @Test
  void fileDeletionFailureKeepsTheRecoveryRowAndRetryFinishes() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    Path folder = temp.resolve("conversations/conversation");
    boolean posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    var held = new java.util.concurrent.atomic.AtomicReference<java.nio.channels.FileChannel>();
    var permissions = new java.util.concurrent.atomic.AtomicReference<java.util.Set<java.nio.file.attribute.PosixFilePermission>>();
    when(client.deleteHistoryTranscript(any(), any())).thenAnswer(invocation -> {
      if (posix) {
        permissions.set(Files.getPosixFilePermissions(folder));
        Files.setPosixFilePermissions(folder, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
      } else {
        held.set(java.nio.channels.FileChannel.open(folder.resolve("messages.jsonl"),
            java.nio.file.StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));
      }
      return DeleteByIdResponse.newBuilder().setSuccess(true).build();
    });
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      store.createSession("conversation", "core.free-chat");
      store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "private"));
      seedRun(runs, "run", "conversation");
      try {
        assertThrows(java.io.UncheckedIOException.class, () -> deletion.delete("conversation"));
        assertFalse(store.listSessions(null, 20).isEmpty());
      } finally {
        if (held.get() != null) held.get().close();
        if (permissions.get() != null) Files.setPosixFilePermissions(folder, permissions.get());
      }
      doReturn(DeleteByIdResponse.newBuilder().setSuccess(true).build()).when(client).deleteHistoryTranscript(any(), any());
      deletion.delete("conversation");
      assertTrue(store.listSessions(null, 20).isEmpty());
      assertFalse(Files.exists(temp.resolve("runs/run")));
    }
  }

  @Test
  void incompleteRunOnlyDeletionRemainsPresentAfterJoinMetadataIsLost() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new AgentRunStore(temp.resolve("runs"));
    var client = mock(KnowledgeClient.class);
    when(client.deleteHistoryTranscript(any(), any())).thenThrow(new AssertionError("engine stop"));
    try (var history = new AgentHistoryIndexer(new TestEngineExecutors(), temp.resolve("history"), () -> client)) {
      var deletion = deletion(store, runs, history);
      seedRun(runs, "run", "conversation");
      assertThrows(AssertionError.class, () -> deletion.delete("conversation"));
      Files.delete(temp.resolve("runs/run/meta.json"));
      assertTrue(deletion.exists("conversation"), "the durable intent must keep leftover run events discoverable");
      assertTrue(Files.exists(temp.resolve("runs/run/events.ndjson")));
    }
  }

  private ConversationDeletion deletion(FileConversationStore store, AgentRunStore runs, AgentHistoryIndexer history) {
    return new ConversationDeletion(store, runs, history, new RunChannelRegistry(), DataKeyManager.disabled());
  }

  private void seedRun(AgentRunStore runs, String id, String conversationId) throws Exception {
    runs.runEvents().writeRunMeta(id, Map.of("sessionId", id, "conversationId", conversationId,
        "shapeId", "core.agent-run", "startedAt", "2026-10-19T00:00:00Z"));
    runs.runEvents().appendEvent(id, "core.agent-run", "done", Map.of("finalAnswer", "private answer"));
    Files.createDirectories(temp.resolve("history"));
    Files.writeString(temp.resolve("history/" + id + ".md"), "# Agent run\nprivate answer");
    Files.writeString(temp.resolve("history/" + id + ".md.pending"), "");
    Files.writeString(temp.resolve("history/" + id + ".md.tmp"), "interrupted transcript write");
  }
}
