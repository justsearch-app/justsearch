/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.Objects;
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
    if (incomplete.contains(id) || deleting.contains(id)) {
      throw new Refused("CONVERSATION_DELETE_INCOMPLETE",
          "Deletion is in progress or incomplete. Retry deleting this conversation.");
    }
    active.merge(id, 1, Integer::sum);
    return () -> finish(id);
  }

  private synchronized void finish(String id) {
    if (active.get(id) == 1) active.remove(id);
    else active.computeIfPresent(id, (key, count) -> count - 1);
  }

  public synchronized Lease delete(String id) {
    Objects.requireNonNull(id, "id");
    if (active.containsKey(id) || !deleting.add(id)) {
      throw new Refused("CONVERSATION_BUSY",
          "Stop any running Ask or agent run, including approval waits, before deleting this conversation.");
    }
    return () -> endDelete(id);
  }

  private synchronized void endDelete(String id) { deleting.remove(id); }
  public synchronized void markIncomplete(String id) { Objects.requireNonNull(id, "id"); incomplete.add(id); }
  public synchronized void completed(String id) { incomplete.remove(id); }
}
