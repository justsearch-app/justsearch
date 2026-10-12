/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Slice 496 §3.B — persistence interface for PERSISTENT ConversationShapes.
 *
 * <p>Provides multi-turn conversation history: load on dispatch, append after
 * each LLM response. The ConversationEngine integrates it at the substrate-driven
 * dispatch path — PERSISTENT shapes get history seeded from the store, EPHEMERAL
 * shapes get a no-op.
 *
 * <p>Per the tempdoc 496 design-decision: persistence is engine-integrated for
 * substrate-driven shapes (changes the prior PersistenceMode.java "shape-implemented"
 * principle). The agent loop's AgentRunStore stays agent-specific — it stores
 * richer data (tool calls, traces, handoff state, budget tracking). This interface
 * handles simple message threads.
 *
 * <p>Implementation: {@code FileConversationStore} uses JSONL per session at
 * {@code tmp/conversations/<shapeId>/<sessionId>/messages.jsonl} with a
 * {@code meta.json} for session metadata.
 */
public interface ConversationStore {

  /**
   * Slice 516 FIX-T4 — prefix for internal throwaway sessions (currently the
   * one-shot LLM call used for auto-title generation). The FE producer
   * (TS: {@code ConversationListStore.generateConversationTitle}) MUST use
   * this exact spelling; the Java consumer
   * ({@code FileConversationStore.deleteSession}) references this constant
   * to bypass the {@link BranchesPreventDeletionException} check. Renaming
   * here requires updating the TS side in lockstep.
   */
  String THROWAWAY_SESSION_PREFIX = "_title_";

  /**
   * Tempdoc 610 Phase A — reserved {@code branchPointMessageId} marking an
   * <em>empty-prefix</em> branch: a branch that inherits NOTHING from its
   * parent. Required to edit/retry the FIRST message of a conversation (there
   * is no preceding message to branch from). It is distinct from a blank/absent
   * branch point, which already means "inherit the FULL parent prefix"
   * (see {@code FileConversationStore.loadHistory}). The FE producer
   * (TS: {@code UnifiedChatView} edit/retry of the first turn) MUST use this
   * exact spelling.
   */
  String EMPTY_PREFIX_SENTINEL = "__empty_prefix__";

  /**
   * Tempdoc 838 — {@code titleSource} values, pinned on the interface the same way
   * {@link #THROWAWAY_SESSION_PREFIX} is, so the producer (the FE rename / auto-title write-through),
   * the validator (the controller) and the consumer (the list projection) share one spelling.
   *
   * <p>{@code user} means a reader typed the name; {@code auto} means the model summarised the
   * conversation into one. The distinction is not decoration: it is what lets the window refuse to
   * auto-title a conversation the reader already named, ACROSS A RELOAD — before this field the
   * provenance lived only in the tab's memory, so the next ask after a reload overwrote the reader's
   * name with the model's.
   */
  String TITLE_SOURCE_USER = "user";

  /** Tempdoc 838 — the model named it. See {@link #TITLE_SOURCE_USER}. */
  String TITLE_SOURCE_AUTO = "auto";

  /**
   * Load the message history for an existing session. Returns an empty list if
   * the session doesn't exist (first request creates it).
   */
  List<Map<String, Object>> loadHistory(String sessionId);

  /**
   * Append a message to the session's history. Creates the session directory
   * if it doesn't exist (first append = session creation).
   */
  void appendMessage(String sessionId, String shapeId, Map<String, Object> message);

  /**
   * Tempdoc 734 round-14 F4 — TRUE when the store cannot be read or written right now (encrypted at
   * rest with the data key locked). The read side already answers this honestly ({@code
   * loadHistory} lets {@code KeyLockedException} reach the 423 mapping); this is the same fact asked
   * BEFORE a write, so a dispatch that would be accepted-and-dropped can refuse instead of returning
   * a 200 for a turn no store will hold. Same predicate and same wording as the memory store's
   * {@code MemoryStore#isLocked()} (tempdoc 806 W1) — one condition, one vocabulary.
   *
   * <p>Default {@code false} — an unencrypted or in-memory store is always writable.
   */
  default boolean isLocked() {
    return false;
  }

  /**
   * List sessions for a given shape, ordered by last-active descending.
   */
  List<SessionSummary> listSessions(String shapeId, int limit);

  /**
   * Slice 515 FIX-7 — direct lookup of one session's metadata. Replaces the
   * listSessions+filter workaround for single-session meta access (e.g.,
   * resolving parent pointers on history load). Returns
   * {@code Optional.empty()} if no session exists with the given id.
   */
  Optional<SessionSummary> getSessionMeta(String sessionId);

  /**
   * Delete a session and its history.
   *
   * <p>Slice 515 FIX-3 — implementations may throw {@code IllegalStateException}
   * when child branches exist, to prevent silent prefix loss. The controller
   * maps such failures to HTTP 409 with the child session ids in the response
   * body so the FE can offer a cascade-delete UX.
   */
  void deleteSession(String sessionId);

  /**
   * Slice 513 — create a new session branching from {@code parentSessionId} at
   * {@code branchPointMessageId}. The branch carries no messages of its own;
   * {@link #loadHistory} resolves the parent prefix lazily on every call.
   *
   * <p>The {@code newSessionId} is supplied by the caller (typically the REST
   * handler generates a UUID) so the operation is a no-op repeat if invoked
   * twice with the same id. The branch's {@code shapeId} mirrors the parent's
   * so listSessions surfaces it under the same family.
   */
  void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId);

  /**
   * Tempdoc 610 Phase C — set (or clear) the session's <em>effective-context
   * floor</em>: the message id from which {@link #loadEffectiveContext} begins
   * the prompt history. Pass {@code null} to clear it (restore full context).
   * This decouples what the next turn's prompt sees from what the transcript
   * <em>displays</em> ({@link #loadHistory} is unaffected) — the substrate for
   * in-place "reset context to here". No-op for stores without persistence.
   */
  void setContextFloor(String sessionId, String floorMessageId);

  /**
   * Tempdoc 610 Phase C — the message history the next LLM turn should see:
   * {@link #loadHistory} trimmed to start at the session's context floor (the
   * floor message and everything after it). With no floor set this is
   * identical to {@link #loadHistory}. The {@code ConversationEngine} seeds
   * PERSISTENT shapes from this; the display path keeps {@link #loadHistory}.
   */
  List<Map<String, Object>> loadEffectiveContext(String sessionId);

  /**
   * Tempdoc 610 Phase D — compact: set the context floor AND attach a summary
   * of the messages above it. {@link #loadEffectiveContext} then prepends the
   * summary as a synthetic context message ahead of the floor-trimmed turns, so
   * the next LLM turn sees "[summary] + recent turns" instead of the full
   * history — while the transcript still displays everything. A plain
   * {@link #setContextFloor} (rewind) clears any attached summary. No-op for
   * stores without persistence.
   */
  void compactContext(String sessionId, String floorMessageId, String summaryText);

  /**
   * Tempdoc 610 §E.3 — mark a single message excluded from (or included back into) the effective
   * context. Excluded messages stay in {@link #loadHistory} (the transcript still displays them) but
   * are filtered out of {@link #loadEffectiveContext} <em>before</em> the floor trim, so the next LLM
   * turn does not see them. A per-message generalization of the floor. No-op for stores without
   * persistence.
   */
  void excludeMessage(String sessionId, String messageId, boolean excluded);

  /**
   * Tempdoc 610 §E.3 — the message ids currently excluded from the effective context for this
   * session (never null; empty when none). Surfaced on history load so the FE can render the
   * per-message toggle state + the out-of-context treatment on reload.
   */
  List<String> excludedMessageIds(String sessionId);

  /**
   * Tempdoc 610 §J.3 — mark a single retrieved SOURCE (a RAG chunk, id is the FE/store's
   * unit-separator-joined {@code parentDocId + chunkIndex}) excluded from (or included back into) this
   * conversation's retrieval. Excluded sources are dropped by the Worker BEFORE ranking on subsequent
   * turns (the injector/retrieval seam, parallel to and independent of {@link #excludeMessage} on the
   * conversation seam). No-op for stores without persistence.
   */
  default void excludeSource(String sessionId, String sourceId, boolean excluded) {}

  /**
   * Tempdoc 610 §J.3 — the source ids currently excluded from retrieval for this session (never null;
   * empty when none). Seeded onto the engine's context each turn + surfaced on history load so the FE
   * can render the per-source toggle + dim treatment on reload.
   */
  default List<String> excludedSourceIds(String sessionId) {
    return List.of();
  }

  /**
   * Tempdoc 838 — name this conversation, durably. A {@code null} or blank {@code title} CLEARS the
   * name; {@code titleSource} is one of {@link #TITLE_SOURCE_USER} / {@link #TITLE_SOURCE_AUTO} and
   * records who chose it.
   *
   * <p>Before this seam the name was the one fact about a conversation the browser owned
   * ({@code localStorage['jf-conversation-titles']}), so it did not survive a cleared site-data, a
   * second client or a profile change — and it sat in plaintext outside the encryption boundary while
   * the conversation it named was sealed. The title is conversation CONTENT (an auto-title is the
   * model's summary of the messages; a reader's title is a sentence about them), so an implementation
   * that seals content seals this too.
   *
   * <p>Implementations MUST NOT materialise a session for an unknown id — a titled zero-message
   * conversation is a list row that names nothing. No-op for stores without persistence.
   */
  default void setTitle(String sessionId, String title, String titleSource) {}

  /**
   * Names this conversation only while it has no name, as one step: a name written meanwhile, by a
   * person or by the model, is never replaced. Two writers use it: the model's automatic title, and
   * a browser's retained legacy title being moved to the server. Both arrive late by nature, and
   * either one landing over a name a person just gave the conversation would undo that person's
   * choice.
   *
   * <p>The default reads and writes in two steps, which is enough for stores with no concurrent
   * writers; a persistent store makes it atomic against {@link #setTitle}.
   *
   * @return true when {@code title} was stored; false when the conversation already had a name, the
   *     title is blank, or the conversation does not exist
   */
  default boolean setTitleIfUntitled(String sessionId, String title, String titleSource) {
    if (title == null || title.isBlank()) return false;
    Optional<SessionSummary> meta = getSessionMeta(sessionId);
    if (meta.isEmpty()) return false;
    String existing = meta.get().title();
    if (existing != null && !existing.isBlank()) return false;
    setTitle(sessionId, title, titleSource);
    return true;
  }

  /**
   * Tempdoc 834 §4.3 — mark this conversation as having a turn OPEN (generation in flight), or
   * clear the mark when the turn closes.
   *
   * <p>Why only iterating shapes need it: {@code ConversationEngine} persists the assistant turn
   * INSIDE its iteration loop, so a {@code WITHIN_TURN_ITERATION} shape writes one assistant turn
   * per iteration. A crash at iteration 3 of 8 therefore leaves three persisted turns that read as
   * a finished answer. A ONE_SHOT ask has no such gap — it persists once, complete.
   *
   * <p>The mark must be cleared in a {@code finally}, never at selected exits: the dispatch body has
   * eight of them, and clearing at only some would render every cleanly-errored run "interrupted" —
   * the inverse dishonesty. Only a process death then leaves it set, which is exactly the condition
   * it encodes. No-op for stores without persistence.
   */
  default void setTurnOpen(String sessionId, boolean open) {}

  /**
   * Tempdoc 834 §4.3 — whether this conversation has a turn left open by a process that died
   * mid-generation. {@code false} for stores without persistence.
   */
  default boolean isTurnOpen(String sessionId) {
    return false;
  }

  /** Summary of a persisted session for list UIs. */
  record SessionSummary(
      String sessionId,
      String shapeId,
      long createdAtMs,
      long lastActiveAtMs,
      int messageCount,
      String firstUserMessage,
      String parentSessionId,
      String branchPointMessageId,
      // Tempdoc 610 Phase C — the effective-context floor message id (null when
      // no floor is set). Surfaced so the FE can render the floor divider.
      String contextFloor,
      // Tempdoc 610 Phase D — the compaction summary attached to the floor
      // (null for a plain rewind). Surfaced so the FE divider can show it.
      String contextFloorSummary,
      // Tempdoc 838 — the conversation's name (null when it has none, and blank
      // rather than null when the store is sealed + locked, exactly like
      // firstUserMessage). This is the durable authority a rename writes to.
      String title,
      // Tempdoc 838 — who named it: TITLE_SOURCE_USER or TITLE_SOURCE_AUTO
      // (null when there is no title). Structural, not content: it is a
      // provenance bit, so it stays readable while the conversation is locked.
      String titleSource) {

    /** Backward-compat constructor — sessions without branch metadata. */
    public SessionSummary(
        String sessionId,
        String shapeId,
        long createdAtMs,
        long lastActiveAtMs,
        int messageCount,
        String firstUserMessage) {
      this(sessionId, shapeId, createdAtMs, lastActiveAtMs, messageCount, firstUserMessage,
          null, null, null, null, null, null);
    }

    /** Backward-compat constructor — branch metadata but no context floor. */
    public SessionSummary(
        String sessionId,
        String shapeId,
        long createdAtMs,
        long lastActiveAtMs,
        int messageCount,
        String firstUserMessage,
        String parentSessionId,
        String branchPointMessageId) {
      this(sessionId, shapeId, createdAtMs, lastActiveAtMs, messageCount, firstUserMessage,
          parentSessionId, branchPointMessageId, null, null, null, null);
    }

    /** Backward-compat constructor — floor but no attached summary. */
    public SessionSummary(
        String sessionId,
        String shapeId,
        long createdAtMs,
        long lastActiveAtMs,
        int messageCount,
        String firstUserMessage,
        String parentSessionId,
        String branchPointMessageId,
        String contextFloor) {
      this(sessionId, shapeId, createdAtMs, lastActiveAtMs, messageCount, firstUserMessage,
          parentSessionId, branchPointMessageId, contextFloor, null, null, null);
    }
  }

  /** No-op implementation for EPHEMERAL shapes. */
  static ConversationStore noop() {
    return NoOpConversationStore.INSTANCE;
  }
}
