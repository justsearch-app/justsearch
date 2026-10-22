/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.Objects;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Engine-wide exclusion between a conversation's complete writer lifetimes and deletion.
 * Identifiers are opaque here; each durable store validates them before filesystem access.
 */
public final class ConversationWork {
  private final Map<String, Integer> active = new HashMap<>();
  private final Set<String> deleting = new HashSet<>();
  private final Set<String> incomplete = new HashSet<>();
  // Process-lifetime markers distinguish a stale first write from an unseen conversation id.
  private final Set<String> deleted = new LinkedHashSet<>();
  private long deletionCount;

  @FunctionalInterface
  public interface Lease extends AutoCloseable {
    @Override void close();
  }

  public static final class Refused extends IllegalStateException {
    private final String code;
    public Refused(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
  }

  public synchronized Lease start(String id) {
    if (id == null) return () -> {};
    if (deleted.contains(id)) {
      throw new Refused("CONVERSATION_DELETED",
          "This conversation was deleted. Start a new conversation instead.");
    }
    checkDeletion(id);
    active.merge(id, 1, Integer::sum);
    return () -> finish(id);
  }

  /** Only the explicit backup import may intentionally restore a deleted identity. */
  public synchronized Lease restore(String id) {
    checkDeletion(id);
    deleted.remove(id);
    return start(id);
  }

  private void checkDeletion(String id) {
    if (incomplete.contains(id) || deleting.contains(id)) {
      throw new Refused("CONVERSATION_DELETE_INCOMPLETE",
          "Deletion is in progress or incomplete. Retry deleting this conversation.");
    }
  }

  private synchronized void finish(String id) {
    if (active.get(id) == 1) active.remove(id);
    else active.computeIfPresent(id, (key, count) -> count - 1);
  }

  public synchronized Lease delete(String id) {
    Objects.requireNonNull(id, "id");
    if (active.containsKey(id) || !deleting.add(id)) {
      throw new Refused("CONVERSATION_BUSY",
          "Wait for compaction or queued background runs to finish, or stop any running Ask or agent run, including approval waits, before deleting this conversation.");
    }
    return () -> endDelete(id);
  }

  private synchronized void endDelete(String id) { deleting.remove(id); }
  public synchronized void markIncomplete(String id) { Objects.requireNonNull(id, "id"); incomplete.add(id); }
  public synchronized void completed(String id) {
    Objects.requireNonNull(id, "id");
    incomplete.remove(id);
    deleted.add(id);
    deletionCount++;
  }

  /** Cumulative successful deletions in this Engine run, including markers no longer on the wire. */
  public synchronized long deletionCount() {
    return deletionCount;
  }

  /** Newest 100 exact deletions on the wire; older process markers still refuse stale writes. */
  public synchronized List<String> deletedIds() {
    return deleted.stream().skip(Math.max(0, deleted.size() - 100)).toList();
  }
}
