/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.memory.MemoriesUnavailableException;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 561 P-E — the PASSIVE (answer-plane) learning producer. A {@link StreamConsumer} wired onto
 * the chat / RAG-ask shapes: at the end of a turn it scans the user's message for an explicit,
 * high-precision memory cue ("remember that …", "note that …", "I prefer …", "my … is …", "call me
 * …") and persists the durable fact into the single-authority {@link MemoryStore} the user inspects +
 * forgets via {@code /api/memory} — so the answer plane contributes to memory too, not just the agent.
 *
 * <p>Deliberately a precise heuristic, not a per-turn LLM extraction call: per 559 §15 cost-realism an
 * extra inference round-trip on every chat turn is a poor trade. The id is a content hash, so restating
 * the same preference does not create a duplicate ({@code MemoryStore.remember} is idempotent on id).
 * A richer LLM-based semantic extractor is a documented evolution (it would thread an inference client
 * + a token budget into this consumer).
 */
public final class MemoryExtractionConsumer implements StreamConsumer {

  /** Stable id used by {@code ConversationShape.streamConsumerIds}. */
  public static final String ID = "core.memory-extraction";

  private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractionConsumer.class);

  private static final int MAX_CONTENT_CHARS = 240;

  private final MemoryStore memoryStore;

  /**
   * Tempdoc 734 round-14 F4, applied to the PASSIVE plane — one WARN per lock EPISODE, not per turn.
   * Set when a drop is reported, cleared the moment the store is observed unlocked, so a re-lock is
   * announced again while a locked session cannot spam a WARN on every message. This consumer is a
   * singleton shared across concurrent turns, hence atomic.
   */
  private final AtomicBoolean lockedEpisodeReported = new AtomicBoolean(false);

  private MemoriesUnavailableException unavailableEpisodeReported;

  public MemoryExtractionConsumer(MemoryStore memoryStore) {
    this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    return StreamConsumerResult.empty();
  }

  /**
   * Tempdoc 734 round-14 F4 (chat plane) applied to the PASSIVE plane. This method used to catch every
   * {@link RuntimeException} and drop it, so while the data key was locked every write threw and
   * passive learning silently recorded nothing — the accepted-and-dropped shape F4 closed on the chat
   * path, one plane over.
   *
   * <p>The chat plane's answer (a user-facing 423 refusal) is the WRONG shape here: this is background
   * work on a turn the user did not ask to be recorded, and refusing the turn over it would break a
   * conversation that is otherwise serviceable. So the drop stays a drop — but it stops being SILENT
   * ({@link #lockedEpisodeReported}), and it stops being paid for: {@link MemoryStore#isLocked()}
   * (tempdoc 806 W1) is consulted before the write instead of discovering the lock by exception.
   *
   * <p>The WARN is only raised when a fact WOULD have been stored. The cue scan is a pure string scan —
   * no IO, no inference — so running it first costs nothing and is what makes the report witnessed:
   * "we skipped something" is a claim, and on a turn with no memory cue nothing was skipped.
   *
   * <p><b>Recovery story (no queue, deliberately).</b> There is no deferred-retry buffer and this
   * change does not add one: the record id is a content hash and {@code remember} is idempotent on it,
   * so a fact restated after unlock lands exactly once, and the consumer re-fires on every subsequent
   * turn — the natural retry seam is the conversation itself. A fact stated ONLY while locked is lost,
   * which is why the WARN exists: the loss is visible rather than inferred. Note the narrow window
   * this occupies — {@code ConversationEngine.wouldDiscardWhileLocked} already refuses any dispatch
   * that would persist a conversation record while locked, so the turns that reach here at all are the
   * ephemeral ones that persist nothing else.
   */
  @Override
  public synchronized StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    try {
      String userMessage = lastUserMessage(ctx.messages());
      Extracted fact = extract(userMessage);
      // Observed writable: the lock episode (if any) has ended — the next one gets its own WARN.
      // Read and reset BEFORE the cue check, because an episode ends when the STORE unlocks, not
      // when a cue-bearing turn happens to arrive while it is unlocked. Resetting only on the
      // latter let lock → cueless unlocked turns → re-lock keep the flag set, so the second
      // episode's first drop was silent — the exact "one WARN per episode" contract this field
      // exists to keep.
      boolean locked = memoryStore.isLocked();
      if (!locked) {
        lockedEpisodeReported.set(false);
      }
      if (fact == null) {
        return StreamConsumerResult.empty();
      }
      if (locked) {
        reportSkipped(fact);
        return StreamConsumerResult.empty();
      }
      memoryStore.remember(
          new MemoryRecord(
              "chat:" + Integer.toHexString(fact.content.toLowerCase(Locale.ROOT).hashCode()),
              fact.kind,
              fact.content,
              ctx.sessionId(),
              "chat",
              Instant.now()));
    } catch (MemoriesUnavailableException unavailable) {
      // Completion is serialized so a delayed failure from before recovery cannot replace the
      // marker for a newer episode and make a later turn report that newer episode twice.
      if (unavailableEpisodeReported != unavailable) {
        unavailableEpisodeReported = unavailable;
        LOG.warn("Passive memory extraction skipped: memories are unavailable; the learned fact was not recorded. Retry after the stored memories can be read.");
      }
    } catch (KeyLockedException locked) {
      // The store locked between the pre-check and the write, or reports `isLocked()` conservatively.
      // Caught DISTINCTLY: this is a known refusal with a named cause, not an unexplained failure.
      reportSkipped(null);
    } catch (RuntimeException ignored) {
      // Best-effort: a failed extraction must never break the user's turn.
    }
    return StreamConsumerResult.empty();
  }

  /** Announce a dropped passive memory once per lock episode, naming what was skipped and why. */
  private void reportSkipped(Extracted fact) {
    if (lockedEpisodeReported.compareAndSet(false, true)) {
      LOG.warn(
          "Passive memory extraction skipped: the memory store is locked (data-at-rest key not"
              + " unlocked), so a learned {} from this turn was not recorded. Further skips are not"
              + " logged until the store is writable again; unlock and restate the fact to store it.",
          fact == null ? "fact" : fact.kind);
    }
  }

  private static String lastUserMessage(List<Map<String, Object>> messages) {
    if (messages == null) {
      return null;
    }
    for (int i = messages.size() - 1; i >= 0; i--) {
      Map<String, Object> m = messages.get(i);
      if ("user".equals(m.get("role")) && m.get("content") instanceof String s) {
        return s;
      }
    }
    return null;
  }

  private record Extracted(String content, String kind) {}

  /**
   * High-precision cue extraction. Returns null when no explicit memory-worthy statement is present
   * (the common case) — only fires on a clear directive or a first-person preference/identity fact.
   */
  private static Extracted extract(String message) {
    if (message == null) {
      return null;
    }
    String trimmed = message.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);

    // Explicit directive: "remember that X" / "remember: X" / "please remember X" / "note that X".
    for (String cue : List.of("remember that ", "remember: ", "please remember ", "note that ", "remember ")) {
      int idx = lower.indexOf(cue);
      if (idx >= 0) {
        String rest = trimmed.substring(idx + cue.length()).trim();
        return rest.isEmpty() ? null : new Extracted(cap(rest), "fact");
      }
    }
    // First-person preference / identity — the statement is self-contained, keep it verbatim.
    if (lower.startsWith("i prefer ")
        || lower.startsWith("i like ")
        || lower.startsWith("i love ")
        || lower.startsWith("i hate ")
        || lower.startsWith("i dislike ")
        || lower.startsWith("i always ")
        || lower.startsWith("i usually ")
        || lower.startsWith("i never ")
        || lower.startsWith("call me ")
        || lower.startsWith("my name is ")) {
      return new Extracted(cap(trimmed), "preference");
    }
    return null;
  }

  private static String cap(String s) {
    return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS).trim() + "…";
  }
}
