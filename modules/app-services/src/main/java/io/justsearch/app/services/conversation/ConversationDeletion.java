/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.conversation.ConversationWork;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.services.agenthistory.AgentHistoryIndexer;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.configuration.persistence.StoreIds;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns deletion across the authored stores, derived history and the Worker's queued projection. */
public final class ConversationDeletion {
  private static final Logger LOG = LoggerFactory.getLogger(ConversationDeletion.class);
  private final FileConversationStore conversations;
  private final AgentRunStore runs;
  private final AgentHistoryIndexer history;
  private final RunChannelRegistry channels;
  private final DataKeyManager keys;

  public ConversationDeletion(FileConversationStore conversations, AgentRunStore runs,
      AgentHistoryIndexer history, RunChannelRegistry channels, DataKeyManager keys) {
    this.conversations = conversations;
    this.runs = runs;
    this.history = history;
    this.channels = channels;
    this.keys = keys;
    conversations.useConversationWork(runs.conversationWork());
    conversations.pendingDeletions().values().forEach(history::suppress);
  }

  /** Exact two-store identity read, including empty and run-only conversations, while unlocked. */
  public boolean exists(String id) {
    StoreIds.requireValid(id);
    synchronized (keys) {
      if (conversations.isLocked() || runs.isLocked()) throw new KeyLockedException();
      return conversations.pendingDeletions().containsKey(id)
          || conversations.getSessionMeta(id).isPresent()
          || !runs.listRunIdsByConversation(id).isEmpty();
    }
  }

  public int delete(String id) {
    StoreIds.requireValid(id);
    if (conversations.isLocked() || runs.isLocked()) throw new KeyLockedException();
    // Reserve BEFORE queueing behind history. A terminal event is queued before its live writer
    // releases this same authority, so no older history write can be queued behind our deletion.
    var lease = conversations.conversationWork().delete(id);
    return history.afterWrites(() -> {
      try (lease) {
        // Key lifecycle operations use this monitor too. Lock cannot split an admitted delete
        // into a plaintext half and a sealed half, nor hide runs halfway through enumeration.
        synchronized (keys) { return deleteOwned(id); }
      }
    }, lease::close);
  }

  private int deleteOwned(String id) {
    conversations.checkDeletion(id);
    if (runs.isLocked()) throw new KeyLockedException();
    if (channels.live().stream().anyMatch(c -> id.equals(c.descriptor().conversationId()))) {
      throw new ConversationWork.Refused("CONVERSATION_BUSY",
          "Stop the running Ask or agent before deleting this conversation.");
    }
    var runIds = new LinkedHashSet<>(conversations.pendingDeletions().getOrDefault(id, List.of()));
    runIds.addAll(runs.listRunIdsByConversation(id));
    List<String> captured = List.copyOf(runIds);
    // The one new durable record is required for crash recovery: run metadata may be gone on
    // restart, but the intent still identifies the transcript/index/queue entries to finish.
    conversations.prepareDeletion(id, captured);
    history.suppress(captured);
    for (String runId : captured) history.deleteTranscript(runId);
    int removed = runs.deleteRuns(captured);
    channels.forgetConversation(id);
    conversations.deleteContents(id); // intent and listing metadata are removed last
    conversations.conversationWork().completed(id);
    history.unsuppress(captured);
    return removed;
  }

  /** Startup recovery is best effort, with a visible, blocked row retained on any failure. */
  public void recoverPending() {
    for (String id : conversations.pendingDeletions().keySet()) {
      try { delete(id); }
      catch (RuntimeException failure) {
        LOG.warn("Conversation {} deletion remains incomplete; retry deleting it", id, failure);
      }
    }
  }
}
