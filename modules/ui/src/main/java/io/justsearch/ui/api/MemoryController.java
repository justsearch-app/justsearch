/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.memory.MemoriesUnavailableException;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.app.api.ApiErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-E — {@code /api/memory}: the user-facing projection of the agent's learned-memory
 * canonical record ({@link MemoryStore}). The two first-class user controls the §P-E design requires:
 * inspect ("what it knows", {@code GET}) and forget ({@code DELETE}). {@code POST} is the producer
 * seam (the agent / an operator records a durable fact). One authority, rendered + edited; no second
 * store (the {@code operation-surface} register's memory single-authority guard fails the build on a
 * fork).
 */
public final class MemoryController {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Tempdoc 806 W1 — HTTP 423 Locked is this codebase's answer for "an AUTHORED store's data key is
   * locked": the tempdoc-629 global {@code KeyLockedException} mapping ({@code LocalApiServer}) emits it
   * and the shell already reads it ({@code conversationListStore.ts} → the locked-chat affordance).
   * Memory conforms rather than minting a second status for the same condition.
   */
  private static final int LOCKED_STATUS = 423;

  private final MemoryStore memoryStore;

  public MemoryController(MemoryStore memoryStore) {
    this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
  }

  /** {@code GET /api/memory} — the inspectable "what it knows" projection (newest first). */
  public void handleList(Context ctx) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (MemoryStore.VersionedMemory entry : memoryStore.versionedMemories()) {
        Map<String, Object> row = toWire(entry.record());
        row.put("revision", entry.revision());
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("memories", rows);
      // Tempdoc 806 W1 — "cannot read" is not "nothing learned". While the store is sealed + locked
      // `memories` is empty because the plaintext was dropped from RAM, not because nothing was ever
      // learned. Carrying the flag on the wire lets EVERY client (the shell and /mcp alike) tell the
      // two apart instead of rendering the unreadable state as a positive claim.
      payload.put("locked", memoryStore.isLocked());
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (MemoriesUnavailableException e) {
      ctx.status(503).json(Map.of(
          "errorCode", ApiErrorCode.MEMORIES_UNAVAILABLE.name(),
          "error", e.getMessage(), "unavailable", true));
    } catch (Exception e) {
      LOG.error("Failed to list memory", e);
      ctx.status(500).json(Map.of("error", "Failed to read memory"));
    }
  }

  /** {@code POST /api/memory} — the producer seam: record a learned item. */
  @SuppressWarnings("unchecked")
  public void handleRemember(Context ctx) {
    try {
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : MAPPER.readValue(ctx.body(), Map.class);
      String content = str(body.get("content"));
      if (content == null || content.isBlank()) {
        ctx.status(400).json(Map.of("error", "content is required"));
        return;
      }
      String id = str(body.get("id"));
      MemoryRecord rec =
          new MemoryRecord(
              id != null && !id.isBlank() ? id : UUID.randomUUID().toString(),
              str(body.get("kind")),
              content,
              str(body.get("sourceConversationId")),
              str(body.get("actor")),
              Instant.now());
      memoryStore.remember(rec);
      ctx.json(Map.of("ok", true, "id", rec.id()));
    } catch (KeyLockedException locked) {
      respondLocked(ctx);
    } catch (MemoriesUnavailableException e) {
      ctx.status(503).json(Map.of(
          "errorCode", ApiErrorCode.MEMORIES_UNAVAILABLE.name(),
          "error", e.getMessage(), "unavailable", true));
    } catch (Exception e) {
      LOG.error("Failed to record memory", e);
      ctx.status(500).json(Map.of("error", "Failed to record memory"));
    }
  }

  /** {@code DELETE /api/memory/{id}} — user control: forget one item. */
  public void handleForget(Context ctx) {
    try {
      String revision = ctx.header("If-Match");
      if (revision == null) {
        memoryStore.forget(ctx.pathParam("id"));
        ctx.json(Map.of("ok", true));
        return;
      }
      if (Collections.list(ctx.req().getHeaders("If-Match")).size() != 1
          || !revision.matches("\"[0-9a-f]{64}\"")) {
        ctx.status(400).json(Map.of("errorCode", ApiErrorCode.INVALID_REQUEST.name(),
            "error", "If-Match must contain one memory revision"));
        return;
      }
      MemoryStore.ForgetResult result = memoryStore.forgetIf(ctx.pathParam("id"), revision);
      if (result.current() != null) {
        ctx.status(412).json(Map.of("errorCode", ApiErrorCode.INVALID_STATE.name(),
            "error", "The current item was kept; the earlier attempt may have completed"));
        return;
      }
      ctx.json(Map.of("ok", true, "removed", result.removed()));
    } catch (KeyLockedException locked) {
      respondLocked(ctx);
    } catch (MemoriesUnavailableException e) {
      ctx.status(503).json(Map.of(
          "errorCode", ApiErrorCode.MEMORIES_UNAVAILABLE.name(),
          "error", e.getMessage(), "unavailable", true));
    } catch (Exception e) {
      LOG.error("Failed to forget memory", e);
      ctx.status(500).json(Map.of("error", "Failed to forget memory"));
    }
  }

  /**
   * Tempdoc 806 W1 — the locked answer, typed. Before this, a locked mutation was indistinguishable
   * from a disk failure ({@code 500 "Failed to record memory"}) or, worse, reported success: a locked
   * {@code forget} missed the emptied cache, never persisted, and returned {@code ok:true} while the
   * record survived on disk and reappeared on unlock. A client cannot act on either; it can act on
   * this.
   */
  private static void respondLocked(Context ctx) {
    Map<String, Object> body =
        ApiErrorHandler.toResponse(
            ApiErrorCode.STORE_LOCKED,
            "Memory is encrypted and locked - unlock it to change what the AI has learned.");
    body.put("locked", true);
    ctx.status(LOCKED_STATUS).json(body);
  }

  private static Map<String, Object> toWire(MemoryRecord r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("kind", r.kind());
    m.put("content", r.content());
    m.put("sourceConversationId", r.sourceConversationId());
    m.put("actor", r.actor());
    m.put("createdAt", r.createdAt().toString());
    return m;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }
}
