/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.configuration.persistence.StoreIds;
import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ConversationWork;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 496 §3.B — file-backed implementation of {@link ConversationStore}.
 *
 * <p>Storage layout: {@code <rootDir>/<shapeId>/<sessionId>/messages.jsonl}
 * (append-only, one JSON object per line) + {@code meta.json} (session metadata
 * for list UIs: created timestamp, last-active, message count, first user message).
 *
 * <p>Concurrency: single-writer per sessionId. The engine dispatches one request
 * per session at a time (sequential LLM calls); concurrent requests to different
 * sessions are safe because they write to different directories. If concurrent
 * same-session requests ever happen, the JSONL append is atomic per line (each
 * write is a single {@code Files.writeString} with APPEND mode; individual lines
 * don't interleave).
 */
public final class FileConversationStore implements ConversationStore {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  private static final Logger LOG = LoggerFactory.getLogger(FileConversationStore.class);
  private static final java.util.Set<Path> SKIPPED_INVALID_FOLDERS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ConversationWork conversationWork = new ConversationWork();
  private static final String DELETE_INTENT = "deletion.json";

  private final Path rootDir;
  private final io.justsearch.agent.api.encryption.StoreCipher cipher;

  public FileConversationStore(Path rootDir) {
    this(rootDir, io.justsearch.agent.api.encryption.StoreCipher.disabled());
  }

  /**
   * Tempdoc 629 (LAYER): the {@code cipher} seals/opens {@code messages.jsonl} content with the data
   * key. {@code disabled()} = plaintext passthrough (back-compat with existing un-encrypted stores).
   * While locked, reads of encrypted content throw {@code KeyLockedException} (NOT the empty
   * fail-soft), so the surface shows "locked" not "deleted".
   */
  public FileConversationStore(Path rootDir, io.justsearch.agent.api.encryption.StoreCipher cipher) {
    this.rootDir = Objects.requireNonNull(rootDir, "rootDir");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
  }

  @Override
  public ConversationWork conversationWork() { return conversationWork; }

  /** Composition binds the run store's authority before exposing either store to clients. */
  public void useConversationWork(ConversationWork work) {
    this.conversationWork = Objects.requireNonNull(work, "work");
    pendingDeletions().keySet().forEach(work::markIncomplete);
  }

  /** Persist the exact run identities before destroying any of their join metadata. */
  void prepareDeletion(String id, List<String> runIds) {
    StoreIds.requireValid(id);
    runIds.forEach(StoreIds::requireValid);
    try {
      AtomicFileWrites.replaceStrict(resolveSessionDir(id).resolve(DELETE_INTENT),
          MAPPER.writeValueAsBytes(Map.of("schemaVersion", 1, "runIds", runIds)));
      conversationWork.markIncomplete(id);
    } catch (IOException e) { throw new UncheckedIOException("Cannot record conversation deletion", e); }
  }

  /** Intents contain identities only; their presence keeps an interrupted delete listed. */
  public Map<String, List<String>> pendingDeletions() {
    if (!Files.isDirectory(rootDir)) return Map.of();
    Map<String, List<String>> pending = new LinkedHashMap<>();
    try (var dirs = Files.list(rootDir)) {
      for (Path dir : dirs.filter(Files::isDirectory).filter(FileConversationStore::isValidSessionEntry).toList()) {
        Path intent = dir.resolve(DELETE_INTENT);
        if (!Files.exists(intent)) continue;
        var json = MAPPER.readTree(Files.readString(intent, StandardCharsets.UTF_8));
        if (!json.path("runIds").isArray()) throw new IOException("Invalid conversation deletion intent");
        List<String> ids = new ArrayList<>();
        for (var value : json.path("runIds")) {
          String id = value.asString();
          StoreIds.requireValid(id);
          ids.add(id);
        }
        pending.put(dir.getFileName().toString(), List.copyOf(ids));
      }
    } catch (IOException e) { throw new UncheckedIOException("Cannot read pending conversation deletions", e); }
    return pending;
  }

  void checkDeletion(String sessionId) {
    StoreIds.requireValid(sessionId);
    if (isLocked()) throw new io.justsearch.agent.api.encryption.KeyLockedException();
    List<String> children = findChildBranches(sessionId);
    if (!children.isEmpty()) throw new BranchesPreventDeletionException(sessionId, children);
  }

  @Override
  public List<Map<String, Object>> loadHistory(String sessionId) {
    StoreIds.requireValid(sessionId);
    return loadHistory(sessionId, new java.util.HashSet<>());
  }

  private List<Map<String, Object>> loadHistory(String sessionId, java.util.Set<String> seen) {
    // Slice 513 — branching: if meta has parentSessionId + branchPointMessageId,
    // prepend the parent's history up to and including the branch point. The
    // {@code seen} guard short-circuits any accidental parent cycle.
    if (!seen.add(sessionId)) {
      LOG.warn("Detected parent cycle resolving session {}; truncating prefix walk", sessionId);
      return loadOwnMessages(sessionId);
    }
    List<Map<String, Object>> prefix = List.of();
    Map<String, Object> meta;
    try {
      meta = readMeta(sessionId);
    } catch (io.justsearch.agent.api.encryption.KeyLockedException locked) {
      // Tempdoc 727 (fix): readMeta decrypts firstUserMessage/contextFloorSummary and throws while
      // locked. Parent/branch resolution below only needs the PLAINTEXT structural fields
      // (parentSessionId/branchPointMessageId), so fail safe to "no parent info" — the same fail-safe
      // already used for a missing/stale floor id — instead of letting the whole session's history
      // 500. loadOwnMessages degrades independently below for THIS session's own sealed lines.
      meta = null;
    }
    if (meta != null) {
      Object parent = meta.get("parentSessionId");
      Object branchPoint = meta.get("branchPointMessageId");
      // Saved links can predate portable id validation. An unaddressable parent is missing.
      if (parent instanceof String parentId && StoreIds.isValid(parentId)) {
        List<Map<String, Object>> parentHistory = loadHistory(parentId, seen);
        if (branchPoint instanceof String bp && EMPTY_PREFIX_SENTINEL.equals(bp)) {
          // Tempdoc 610 Phase A — empty-prefix branch: inherit nothing from the
          // parent (used to edit/retry the FIRST message). Must be checked
          // BEFORE the id-search, since the sentinel is non-blank and would
          // otherwise fall through to the full-parent prefix below.
          prefix = List.of();
        } else if (branchPoint instanceof String bp && !bp.isBlank()) {
          int idx = -1;
          for (int i = 0; i < parentHistory.size(); i++) {
            if (bp.equals(parentHistory.get(i).get("id"))) {
              idx = i;
              break;
            }
          }
          prefix = idx >= 0 ? parentHistory.subList(0, idx + 1) : parentHistory;
        } else {
          prefix = parentHistory;
        }
      }
    }
    List<Map<String, Object>> own = loadOwnMessages(sessionId);
    if (prefix.isEmpty()) return own;
    List<Map<String, Object>> combined = new ArrayList<>(prefix.size() + own.size());
    combined.addAll(prefix);
    combined.addAll(own);
    return combined;
  }

  private List<Map<String, Object>> loadOwnMessages(String sessionId) {
    Path messagesFile = resolveMessagesFile(sessionId);
    if (!Files.exists(messagesFile)) return List.of();
    try {
      List<Map<String, Object>> history = new ArrayList<>();
      int index = 0;
      for (String line : Files.readAllLines(messagesFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) continue;
        Map<String, Object> msg;
        try {
          @SuppressWarnings("unchecked")
          Map<String, Object> opened = MAPPER.readValue(cipher.open(line), Map.class);
          msg = opened;
        } catch (io.justsearch.agent.api.encryption.KeyLockedException locked) {
          // Tempdoc 727 (fix) — mirrors listSessions's "keep the item, hide the content" precedent
          // (:216-227): a sealed + locked message must not 500 the whole session's history (would
          // look deleted), and must not silently disappear from the count either. Unlike
          // listSessions's per-FIELD seal, messages.jsonl seals the WHOLE line (role/id/ts/content
          // together, appendMessage :151-158), so the finest degrade this format allows is a
          // position-preserving placeholder with an opaque role — chatTurn() filters it the same way
          // it already filters "system"/context messages, so the thread renders what it can instead
          // of erroring.
          Map<String, Object> placeholder = new LinkedHashMap<>();
          placeholder.put("role", "locked");
          placeholder.put("content", "");
          placeholder.put("id", "idx-" + index + "-locked");
          placeholder.put("locked", true);
          placeholder.put("hash", computeHash(placeholder));
          history.add(placeholder);
          index++;
          continue;
        }
        // Slice 513: synthesize id/hash for legacy messages that lack them. The
        // synthetic id is deterministic (idx-N) so the FE can pass it back to
        // the branch endpoint even for pre-513 sessions.
        if (!msg.containsKey("id") || !(msg.get("id") instanceof String)) {
          msg = new LinkedHashMap<>(msg);
          msg.put("id", "idx-" + index);
        }
        if (!msg.containsKey("hash") || !(msg.get("hash") instanceof String)) {
          msg = new LinkedHashMap<>(msg);
          msg.put("hash", computeHash(msg));
        }
        // A cancelled extraction was never accepted. Keep its outcome/identity for stopped-note
        // and branch readers, but never expose its partial JSON as an answer, including old records.
        if ("assistant".equals(msg.get("role")) && "core.extract".equals(msg.get("shapeId"))
            && "CANCELLED".equals(msg.get("disposition"))) {
          msg = new LinkedHashMap<>(msg);
          msg.put("content", "");
        }
        history.add(msg);
        index++;
      }
      if (!history.isEmpty()
          && "core.extract".equals(history.get(history.size() - 1).get("shapeId"))
          && isTurnOpen(sessionId)) {
        int question = history.size() - 1;
        while (question >= 0 && !"user".equals(history.get(question).get("role"))) question--;
        if (question >= 0 && "core.extract".equals(history.get(question).get("shapeId"))) {
          // Read-time recovery only: never resume work or write a success after a process death.
          Map<String, Object> last = history.get(history.size() - 1);
          var incomplete = new LinkedHashMap<String, Object>(last);
          incomplete.put("role", "assistant");
          incomplete.put("content", "Extraction has not completed. Retry or edit to run it again.");
          incomplete.put("terminalError", true);
          incomplete.put("errorCode", "INCOMPLETE_TURN");
          if (question == history.size() - 1) incomplete.put("id", last.get("id") + "-incomplete");
          history = new ArrayList<>(history.subList(0, question + 1));
          history.add(incomplete);
        }
      }
      return history;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "conversations", "cannot read messages for session " + sessionId, e);
    }
  }

  /**
   * Tempdoc 734 round-14 F4 — the SAME condition {@link io.justsearch.agent.api.encryption.StoreCipher}
   * throws on: {@code open} of a sealed line raises {@code KeyLockedException} (the history 423), and
   * {@code seal} of a new line raises it too, so a locked append cannot persist. Asked before the
   * write, this turns accepted-and-dropped into a refusal. Mirrors {@code FileMemoryStore#isLocked()}.
   */
  @Override
  public boolean isLocked() {
    return cipher.enabled() && cipher.locked();
  }

  @Override
  public void createSession(String sessionId, String shapeId) {
    StoreIds.requireValid(sessionId);
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      if (isLocked()) throw new io.justsearch.agent.api.encryption.KeyLockedException();
      synchronized (titleWrites) {
        if (readMeta(sessionId) != null) return;
        var meta = new LinkedHashMap<String, Object>();
        meta.put("shapeId", shapeId == null ? "" : shapeId);
        meta.put("createdAtMs", System.currentTimeMillis());
        writeMetaAtomic(resolveSessionDir(sessionId), meta, sessionId, true);
      }
    }
  }

  @Override
  public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      Path sessionDir = resolveSessionDir(sessionId);
      try {
        requireSession(sessionId);
        // Slice 513: ensure id + hash are present before write. The store is the
        // authoritative source — callers don't need to know about hash semantics.
        Map<String, Object> enriched = enrichMessage(message, shapeId);
        String json = MAPPER.writeValueAsString(enriched);
        Path messagesFile = sessionDir.resolve("messages.jsonl");
        Files.writeString(
            messagesFile,
            cipher.seal(json) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        updateMeta(sessionDir, shapeId, enriched);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to append message for session " + sessionId, e);
      }
    }
  }

  private static Map<String, Object> enrichMessage(Map<String, Object> message, String shapeId) {
    boolean hasId = message.get("id") instanceof String s && !s.isBlank();
    boolean hasHash = message.get("hash") instanceof String h && !h.isBlank();
    boolean hasTs = message.get("ts") instanceof String t && !t.isBlank();
    boolean hasShape = message.get("shapeId") instanceof String sh && !sh.isBlank();
    if (hasId && hasHash && hasTs && hasShape) return message;
    Map<String, Object> enriched = new LinkedHashMap<>(message);
    // Tempdoc 863 §4.A.5 (F1) — the SHAPE that dispatched this turn, per message. The session's own
    // `shapeId` cannot answer this: it is first-wins for the whole conversation (A-10.1), so a mixed
    // conversation's delegate turn and its ask turns would all report the shape that OPENED it. The
    // turn's tier is a fact about the turn, and the caller already supplies it on every append.
    //
    // This exists because the FE derived a turn's tier from whether it recorded tool calls or notes
    // (`sv3-record.ts`'s activity heuristic). That correlates until it does not: a delegate run that
    // called NO tool records no activity at all, and after 863's suppression it contributes no
    // run-plane events either, so it read as an ordinary ask turn and was offered Edit / Retry /
    // Branch — the silent tier conversion §4.A.5 A-8 exists to refuse. Same class as `recordsToThread`
    // itself and as 847's `panelSpeaks` (§6): gate a fact on itself, never on a correlate.
    if (!hasShape && shapeId != null && !shapeId.isBlank()) {
      enriched.put("shapeId", shapeId);
    }
    if (!hasId) enriched.put("id", UUID.randomUUID().toString());
    if (!hasHash) enriched.put("hash", computeHash(enriched));
    // Tempdoc 561 P-A/P-B (correction): a per-message timestamp (does not affect the hash, which is
    // role+content only) so the unified-thread projection can interleave chat turns with agent
    // events by the authoritative time.
    if (!hasTs) enriched.put("ts", java.time.Instant.now().toString());
    return enriched;
  }

  private static String computeHash(Map<String, Object> message) {
    String role = String.valueOf(message.getOrDefault("role", ""));
    String content = String.valueOf(message.getOrDefault("content", ""));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(role.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(content.getBytes(StandardCharsets.UTF_8));
      byte[] hash = digest.digest();
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed in every JDK; treat as unreachable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  @Override
  public List<SessionSummary> listSessions(String shapeId, int limit) {
    if (!Files.isDirectory(rootDir)) return List.of();
    List<SessionSummary> summaries = new ArrayList<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rootDir)) {
      for (Path sessionDir : dirs) {
        if (!isValidSessionEntry(sessionDir) || !Files.isDirectory(sessionDir)) continue;
        String sessionId = sessionDir.getFileName().toString();
        Path metaFile = sessionDir.resolve("meta.json");
        if (!Files.exists(metaFile)) {
          if (Files.exists(sessionDir.resolve(DELETE_INTENT))) {
            summaries.add(new SessionSummary(sessionId, "core.agent-run", 0, 0, 0,
                "Deletion incomplete — retry deleting this conversation"));
          }
          continue;
        }
        {
          Map<String, Object> meta = readRawMeta(metaFile);
          String metaShape = (String) meta.getOrDefault("shapeId", "");
          if (shapeId != null && !shapeId.isBlank() && !shapeId.equals(metaShape)) continue;
          Object parent = meta.get("parentSessionId");
          Object branchPoint = meta.get("branchPointMessageId");
          Object floor = meta.get("contextFloor");
          // Tempdoc 629 (LAYER): decrypt the content fields per-session. If sealed + locked, keep the
          // session in the list with its content hidden (FE renders "Untitled") — do NOT drop it (would
          // look deleted) and do NOT fail the whole list. Opening the conversation surfaces the locked notice.
          String firstUserMessage = (String) meta.getOrDefault("firstUserMessage", "");
          String floorSummary = meta.get("contextFloorSummary") instanceof String fs ? fs : null;
          // Tempdoc 838 — the title is sealed content, so it hides with the rest while locked; the
          // provenance bit beside it is structural and stays readable.
          String title = meta.get("title") instanceof String t ? t : null;
          Object source = meta.get("titleSource");
          try {
            firstUserMessage = cipher.open(firstUserMessage);
            if (floorSummary != null) floorSummary = cipher.open(floorSummary);
            if (title != null) title = cipher.open(title);
          } catch (io.justsearch.agent.api.encryption.KeyLockedException locked) {
            firstUserMessage = "";
            floorSummary = null;
            title = null;
          }
          summaries.add(new SessionSummary(
              sessionId,
              metaShape,
              ((Number) meta.getOrDefault("createdAtMs", 0L)).longValue(),
              ((Number) meta.getOrDefault("lastActiveAtMs", 0L)).longValue(),
              ((Number) meta.getOrDefault("messageCount", 0)).intValue(),
              firstUserMessage,
              parent instanceof String p ? p : null,
              branchPoint instanceof String bp ? bp : null,
              floor instanceof String f ? f : null,
              floorSummary,
              title,
              source instanceof String ts ? ts : null));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list conversations in " + rootDir, e);
    }
    summaries.sort(Comparator.comparingLong(SessionSummary::lastActiveAtMs).reversed());
    return summaries.size() <= limit ? summaries : summaries.subList(0, limit);
  }

  @Override
  public void branchFrom(
      String parentSessionId, String branchPointMessageId, String newSessionId) {
    try (var ignoredWriting = conversationWork.start(parentSessionId); var ignoredDestination = conversationWork.start(newSessionId)) {
      StoreIds.requireValid(parentSessionId);
      StoreIds.requireValid(newSessionId);
      if (parentSessionId.regionMatches(true, 0, THROWAWAY_SESSION_PREFIX, 0, THROWAWAY_SESSION_PREFIX.length())) {
        throw new IllegalArgumentException("Cannot branch from an internal throwaway session");
      }
      Objects.requireNonNull(parentSessionId, "parentSessionId");
      Objects.requireNonNull(branchPointMessageId, "branchPointMessageId");
      Objects.requireNonNull(newSessionId, "newSessionId");
      Map<String, Object> parentMeta = readMeta(parentSessionId);
      if (parentMeta == null) {
        throw new IllegalArgumentException(
            "Parent session not found: " + parentSessionId);
      }
      // Tempdoc 610 Phase A — the empty-prefix sentinel intentionally references
      // NO real parent message (it inherits nothing), so skip the existence
      // validation for it. loadHistory special-cases the sentinel to an empty
      // prefix.
      if (!EMPTY_PREFIX_SENTINEL.equals(branchPointMessageId)) {
        // Slice 515 FIX-2 — validate the branch-point id exists in the parent's
        // resolved history. Silent fallback to full-parent prefix was a footgun.
        // loadHistory walks any further ancestors via the seen-cycle guard, so
        // this also tolerates chained branches.
        boolean branchPointFound = false;
        for (Map<String, Object> msg : loadHistory(parentSessionId)) {
          if (branchPointMessageId.equals(msg.get("id"))) {
            branchPointFound = true;
            break;
          }
        }
        if (!branchPointFound) {
          throw new IllegalArgumentException(
              "Branch point message id not found in parent: " + branchPointMessageId);
        }
      }
      Path newSessionDir = resolveSessionDir(newSessionId);
      Map<String, Object> meta = new LinkedHashMap<>();
      long now = System.currentTimeMillis();
      meta.put("createdAtMs", now);
      meta.put("lastActiveAtMs", now);
      meta.put("shapeId", parentMeta.getOrDefault("shapeId", ""));
      meta.put("messageCount", 0);
      Object preview = parentMeta.get("firstUserMessage");
      if (preview instanceof String s && !s.isEmpty()) meta.put("firstUserMessage", s);
      meta.put("parentSessionId", parentSessionId);
      meta.put("branchPointMessageId", branchPointMessageId);
      writeMetaAtomic(newSessionDir, meta, newSessionId, true);
    }
  }

  @Override
  public Optional<SessionSummary> getSessionMeta(String sessionId) {
    StoreIds.requireValid(sessionId);
    Map<String, Object> meta = readMeta(sessionId);
    if (meta == null) return Optional.empty();
    String metaShape = (String) meta.getOrDefault("shapeId", "");
    Object parent = meta.get("parentSessionId");
    Object branchPoint = meta.get("branchPointMessageId");
    Object floor = meta.get("contextFloor");
    Object floorSummary = meta.get("contextFloorSummary");
    // Tempdoc 838 — readMeta already decrypted the content fields (title among them) or propagated
    // KeyLockedException; the asymmetry with listSessions is deliberate and pre-existing (the history
    // endpoint must 423, the list must not).
    Object title = meta.get("title");
    Object titleSource = meta.get("titleSource");
    return Optional.of(new SessionSummary(
        sessionId,
        metaShape,
        ((Number) meta.getOrDefault("createdAtMs", 0L)).longValue(),
        ((Number) meta.getOrDefault("lastActiveAtMs", 0L)).longValue(),
        ((Number) meta.getOrDefault("messageCount", 0)).intValue(),
        (String) meta.getOrDefault("firstUserMessage", ""),
        parent instanceof String p ? p : null,
        branchPoint instanceof String bp ? bp : null,
        floor instanceof String f ? f : null,
        floorSummary instanceof String fs ? fs : null,
        title instanceof String t ? t : null,
        titleSource instanceof String ts ? ts : null));
  }

  @Override
  public void setContextFloor(String sessionId, String floorMessageId) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      // Tempdoc 610 Phase C — persist (or clear) the effective-context floor in
      // meta.json, atomically, mirroring the branchFrom meta-write pattern.
      Path sessionDir = resolveSessionDir(sessionId);
      Map<String, Object> existing = readMeta(sessionId);
      boolean clearing = floorMessageId == null || floorMessageId.isBlank();
      if (existing == null && clearing) return;
      Map<String, Object> meta = new LinkedHashMap<>(requireSession(sessionId));
      // A plain rewind carries no summary — clear any prior compaction summary.
      meta.remove("contextFloorSummary");
      if (clearing) {
        meta.remove("contextFloor");
      } else {
        meta.put("contextFloor", floorMessageId);
      }
      writeMetaAtomic(sessionDir, meta, sessionId);
    }
  }

  /** Orders title writes, so a write made only while untitled cannot interleave with a rename. */
  private final Object titleWrites = new Object();

  @Override
  public void setTitle(String sessionId, String title, String titleSource) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      synchronized (titleWrites) {
        writeTitle(sessionId, readMeta(sessionId), title, titleSource);
      }
    }
  }

  @Override
  public boolean setTitleIfUntitled(String sessionId, String title, String titleSource) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      if (title == null || title.isBlank()) return false;
      synchronized (titleWrites) {
        Map<String, Object> existing = readMeta(sessionId);
        if (existing == null) return false;
        Object named = existing.get("title");
        if (named instanceof String s && !s.isBlank()) return false;
        writeTitle(sessionId, existing, title, titleSource);
        return true;
      }
    }
  }

  private void writeTitle(
      String sessionId, Map<String, Object> existing, String title, String titleSource) {
    // Late auto-title replies and idempotent clear-title calls leave missing sessions alone.
    if (existing == null) return;
    Map<String, Object> meta = new LinkedHashMap<>(existing);
    if (title == null || title.isBlank()) {
      meta.remove("title");
      meta.remove("titleSource");
    } else {
      String trimmed = title.trim();
      meta.put(
          "title",
          trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed);
      meta.put(
          "titleSource", TITLE_SOURCE_AUTO.equals(titleSource) ? TITLE_SOURCE_AUTO : TITLE_SOURCE_USER);
    }
    writeMetaAtomic(resolveSessionDir(sessionId), meta, sessionId);
  }

  @Override
  public void compactContext(String sessionId, String floorMessageId, String summaryText) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      // Tempdoc 610 Phase D — like setContextFloor, but attach the summary of the
      // messages above the floor. loadEffectiveContext prepends it.
      if (floorMessageId == null || floorMessageId.isBlank()) {
        return;
      }
      Path sessionDir = resolveSessionDir(sessionId);
      Map<String, Object> meta = new LinkedHashMap<>(requireSession(sessionId));
      meta.put("contextFloor", floorMessageId);
      if (summaryText != null && !summaryText.isBlank()) {
        meta.put("contextFloorSummary", summaryText);
      } else {
        meta.remove("contextFloorSummary");
      }
      writeMetaAtomic(sessionDir, meta, sessionId);
    }
  }

  @Override
  public void excludeMessage(String sessionId, String messageId, boolean excluded) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      // Tempdoc 610 §E.3 — toggle a message id in the meta.json excludedMessageIds list. Excluded
      // messages stay in the transcript (loadHistory) but are dropped from loadEffectiveContext.
      toggleStringInMeta(sessionId, "excludedMessageIds", messageId, excluded);
    }
  }

  @Override
  public List<String> excludedMessageIds(String sessionId) {
    StoreIds.requireValid(sessionId);
    return readStringList(sessionId, "excludedMessageIds");
  }

  @Override
  public void excludeSource(String sessionId, String sourceId, boolean excluded) {
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      StoreIds.requireValid(sessionId);
      // Tempdoc 610 §J.3 — toggle a retrieved-source id in the meta.json excludedSourceIds list. The
      // engine seeds these onto each turn's context; the Worker drops the matching chunks pre-search.
      toggleStringInMeta(sessionId, "excludedSourceIds", sourceId, excluded);
    }
  }

  @Override
  public List<String> excludedSourceIds(String sessionId) {
    StoreIds.requireValid(sessionId);
    return readStringList(sessionId, "excludedSourceIds");
  }

  @Override
  public void setTurnOpen(String sessionId, boolean open) {
    StoreIds.requireValid(sessionId);
    try (var ignoredWriting = conversationWork.start(sessionId)) {
      Map<String, Object> existing = readMeta(sessionId);
      if (existing == null && !open) return;
      Map<String, Object> meta = new LinkedHashMap<>(requireSession(sessionId));
      if (open) meta.put("turnOpen", true);
      else meta.remove("turnOpen");
      writeMetaAtomic(resolveSessionDir(sessionId), meta, sessionId);
    }
  }

  @Override
  public boolean isTurnOpen(String sessionId) {
    StoreIds.requireValid(sessionId);
    Map<String, Object> meta = readMeta(sessionId);
    return meta != null && Boolean.TRUE.equals(meta.get("turnOpen"));
  }

  /** Tempdoc 610 — add/remove a string id in a meta.json list field (atomic write). */
  private void toggleStringInMeta(String sessionId, String key, String value, boolean present) {
    if (value == null || value.isBlank()) return;
    Path sessionDir = resolveSessionDir(sessionId);
    Map<String, Object> existing = readMeta(sessionId);
    if (existing == null && !present) return; // nothing to remove on an unknown session
    Map<String, Object> meta = new LinkedHashMap<>(requireSession(sessionId));
    Object raw = meta.get(key);
    java.util.LinkedHashSet<Object> ids = new java.util.LinkedHashSet<>();
    if (raw instanceof List<?> l) ids.addAll(l);
    if (present) {
      ids.add(value);
    } else {
      ids.remove(value);
    }
    if (ids.isEmpty()) {
      meta.remove(key);
    } else {
      meta.put(key, new ArrayList<>(ids));
    }
    writeMetaAtomic(sessionDir, meta, sessionId);
  }

  /** Tempdoc 610 — read a meta.json string-list field (never null; empty when absent or malformed). */
  private List<String> readStringList(String sessionId, String key) {
    Map<String, Object> meta = readMeta(sessionId);
    if (meta == null) return List.of();
    Object raw = meta.get(key);
    if (!(raw instanceof List<?> l)) return List.of();
    List<String> ids = new ArrayList<>(l.size());
    for (Object o : l) {
      if (o instanceof String s) ids.add(s);
    }
    return ids;
  }

  private Map<String, Object> requireSession(String sessionId) {
    Map<String, Object> meta = readMeta(sessionId);
    if (meta == null) throw new IllegalArgumentException("No such conversation: " + sessionId);
    return meta;
  }

  private void writeMetaAtomic(Path sessionDir, Map<String, Object> meta, String sessionId) {
    writeMetaAtomic(sessionDir, meta, sessionId, false);
  }

  private void writeMetaAtomic(
      Path sessionDir, Map<String, Object> meta, String sessionId, boolean creating) {
    if (!creating) requireSession(sessionId);
    try {
      AtomicFileWrites.replace(
          sessionDir.resolve("meta.json"),
          MAPPER.writeValueAsBytes(withSealedMeta(versionedMeta(meta))));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write conversation metadata for " + sessionId, e);
    }
  }

  @Override
  public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
    StoreIds.requireValid(sessionId);
    // Tempdoc 610 Phase C/D — the prompt history: the full resolved history
    // trimmed to start at the context floor (floor message inclusive), with the
    // compaction summary (if any) prepended as a synthetic system message. No
    // floor (or a stale floor id) → fail safe to the full history.
    List<Map<String, Object>> full = loadHistory(sessionId);
    Map<String, Object> meta = readMeta(sessionId);
    if (meta == null) meta = Map.of();
    // Tempdoc 610 §E.3 — the floor is located in the FULL history, before exclusion: excluding the
    // floor message itself must not move the floor (if the trim looked for it among the kept messages
    // it would miss, fail safe to the whole history, and bring back everything the floor hid).
    Object floor = meta.get("contextFloor");
    Object summary = meta.get("contextFloorSummary");
    List<Map<String, Object>> trimmed = new ArrayList<>(full);
    if (floor instanceof String f && !f.isBlank()) {
      int idx = -1;
      for (int i = 0; i < full.size(); i++) {
        if (f.equals(full.get(i).get("id"))) {
          idx = i;
          break;
        }
      }
      if (idx >= 0) trimmed = new ArrayList<>(full.subList(idx, full.size()));
    }
    // Per-message exclusion then drops an excluded message wherever it sits at or after the floor.
    Object excludedRaw = meta.get("excludedMessageIds");
    if (excludedRaw instanceof List<?> exList && !exList.isEmpty()) {
      java.util.Set<Object> excluded = new java.util.HashSet<>(exList);
      trimmed.removeIf(m -> excluded.contains(m.get("id")));
    }
    // Locate the floor before excluding outcome carriers: a floor on a stopped answer still
    // excludes the earlier history, even though that unaccepted answer itself is not prompt input.
    trimmed.removeIf(m -> "core.extract".equals(m.get("shapeId"))
        && (Boolean.TRUE.equals(m.get("terminalError"))
            || ("assistant".equals(m.get("role")) && "CANCELLED".equals(m.get("disposition")))));
    if (summary instanceof String s && !s.isBlank()) {
      Map<String, Object> summaryMsg = new LinkedHashMap<>();
      summaryMsg.put("role", "system");
      summaryMsg.put("content", "Summary of earlier conversation:\n" + s);
      trimmed.add(0, summaryMsg);
    }
    return trimmed;
  }

  /**
   * Slice 515 FIX-3 — find all sessions whose meta points at the given
   * {@code parentSessionId}. Used by {@link #deleteSession} to block
   * deletion that would orphan a child branch's lazy prefix walk.
   */
  private List<String> findChildBranches(String parentSessionId) {
    if (!Files.isDirectory(rootDir)) return List.of();
    List<String> children = new ArrayList<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rootDir)) {
      for (Path sessionDir : dirs) {
        if (!isValidSessionEntry(sessionDir) || !Files.isDirectory(sessionDir)) continue;
        Map<String, Object> meta = readMeta(sessionDir.getFileName().toString());
        if (meta != null && meta.get("parentSessionId") instanceof String savedParentId
            && parentSessionId.equalsIgnoreCase(savedParentId)) {
          children.add(sessionDir.getFileName().toString());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to scan for child branches of " + parentSessionId, e);
    }
    return children;
  }

  // Slice 516 FIX-T3 — BranchesPreventDeletionException moved to
  // io.justsearch.agent.api.conversation; see import above.

  // Tempdoc 629 (LAYER) — meta.json carries conversation CONTENT (the first user message + the
  // context-floor summary). Seal just those fields (not the whole file) so the conversation LIST keeps
  // working while locked: structural fields (ids, counts, timestamps) stay plaintext, and listSessions
  // catches a locked content field per-session instead of failing the whole list.
  // Tempdoc 838 — `title` joins them: an auto-title is the model's summary of the messages and a
  // reader's title is a sentence about them, so either one leaks the subject of a sealed
  // conversation. `titleSource` deliberately does NOT join them — it is a provenance bit, not
  // content, and the window needs it while the store is locked.
  private static final String[] META_CONTENT_FIELDS = {
    "firstUserMessage", "contextFloorSummary", "title"
  };

  /** Tempdoc 838 — the stored-title cap, the same 200 chars the firstUserMessage preview uses. */
  private static final int MAX_TITLE_LENGTH = 200;

  /** Return a copy of {@code meta} with the content fields sealed (no-op when encryption is disabled). */
  private Map<String, Object> withSealedMeta(Map<String, Object> meta) {
    if (!cipher.enabled()) return meta;
    Map<String, Object> out = new LinkedHashMap<>(meta);
    for (String k : META_CONTENT_FIELDS) {
      if (out.get(k) instanceof String s && !s.isEmpty() && !cipher.isSealed(s)) {
        out.put(k, cipher.seal(s));
      }
    }
    return out;
  }

  /** Decrypt the content fields in-place. Throws {@link KeyLockedException} if sealed + locked. */
  private void openMetaContent(Map<String, Object> meta) {
    if (meta == null) return;
    for (String k : META_CONTENT_FIELDS) {
      if (meta.get(k) instanceof String s && !s.isEmpty()) meta.put(k, cipher.open(s));
    }
  }

  private Map<String, Object> readMeta(String sessionId) {
    Path metaFile = resolveSessionDir(sessionId).resolve("meta.json");
    // An inaccessible path is inconclusive; reading it must surface the error.
    if (Files.notExists(metaFile)) return null;
    try {
      Map<String, Object> meta = readRawMeta(metaFile);
      // Tempdoc 629 (LAYER): decrypt the content fields. KeyLockedException propagates (the conversation
      // is locked) — consistent with loadOwnMessages, so the history endpoint surfaces 423 not empty.
      openMetaContent(meta);
      return meta;
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    }
  }

  private static Map<String, Object> readRawMeta(Path metaFile) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> meta =
          MAPPER.readValue(Files.readString(metaFile, StandardCharsets.UTF_8), Map.class);
      Object rawVersion = meta.get("schemaVersion");
      if (rawVersion != null && !(rawVersion instanceof Integer)) {
        throw new CorruptDurableStoreException(
            "conversations", "schemaVersion must be an integer in " + metaFile);
      }
      Integer observedVersion = rawVersion instanceof Integer version ? version : null;
      StoreFormatVersions.requireReadable(
          "conversations", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
      return meta;
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "conversations", "cannot parse metadata " + metaFile, e);
    }
  }

  private static Map<String, Object> versionedMeta(Map<String, Object> meta) {
    Map<String, Object> versioned = new LinkedHashMap<>(meta);
    versioned.put("schemaVersion", CURRENT_SCHEMA_VERSION);
    return versioned;
  }

  @Override
  public void deleteSession(String sessionId) {
    try (var ignoredDeleting = conversationWork.delete(sessionId)) {
      checkDeletion(sessionId);
      deleteContents(sessionId);
    }
  }

  /** Caller owns deletion exclusion. Keep the durable intent until every content file is gone. */
  void deleteContents(String sessionId) {
    Path sessionDir = resolveSessionDir(sessionId);
    if (!Files.exists(sessionDir)) return;
    try {
      try (var walker = Files.walk(sessionDir)) {
        for (Path path : walker.sorted(Comparator.reverseOrder()).toList()) {
          if (path.equals(sessionDir) || path.equals(sessionDir.resolve(DELETE_INTENT))
              || path.equals(sessionDir.resolve("meta.json"))) continue;
          Files.deleteIfExists(path);
        }
      }
      Files.deleteIfExists(sessionDir.resolve("meta.json"));
      Files.deleteIfExists(sessionDir.resolve(DELETE_INTENT));
      Files.deleteIfExists(sessionDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Conversation deletion is incomplete; retry deleting it", e);
    }
  }

  private static boolean isValidSessionEntry(Path sessionDir) {
    if (StoreIds.isValid(sessionDir.getFileName().toString())) return true;
    if (SKIPPED_INVALID_FOLDERS.add(sessionDir.toAbsolutePath().normalize())) {
      LOG.warn("Skipping conversation folder with invalid id: {}", sessionDir.toUri().toASCIIString());
    }
    return false;
  }

  private Path resolveSessionDir(String sessionId) {
    return StoreIds.resolve(rootDir, sessionId);
  }

  private Path resolveMessagesFile(String sessionId) {
    return resolveSessionDir(sessionId).resolve("messages.jsonl");
  }

  private void updateMeta(Path sessionDir, String shapeId, Map<String, Object> message) {
    Path metaFile = sessionDir.resolve("meta.json");
    try {
      if (!Files.isRegularFile(metaFile)) {
        throw new IllegalArgumentException("No such conversation: " + sessionDir.getFileName());
      }
      Map<String, Object> meta = new LinkedHashMap<>(readRawMeta(metaFile));
      // Tempdoc 863 §4.A.5 A-10.1 — FIRST WINS. This was an unconditional overwrite on every append,
      // so a conversation's declared shape was really "the shape of its most recent turn". That is
      // the wrong fact to store: `listSessions(shapeId, …)` filters rows by it and
      // `UnifiedChatView` re-tags the WHOLE transcript with the one shape it resolves, so one turn in
      // a different mode silently relabelled everything before it. The edge already existed for a
      // mixed conversation whose last append came from a different substrate shape; 863 made it
      // routine by putting delegate turns on the record, so the policy is FIXED rather than merely
      // stopped from spreading: a conversation's shape is the shape that OPENED it, which is stable,
      // legible, and the same answer on every subsequent append.
      // Absent OR BLANK, not just absent (F5): `branchFrom` seeds a child's meta with
      // `parentMeta.getOrDefault("shapeId", "")`, so a branch of a session whose own meta predates
      // this field starts life with `shapeId: ""`. Treating that as "already declared" would freeze
      // the branch's shape at empty forever — first-wins is about the first REAL declaration.
      Object declared = meta.get("shapeId");
      if (!(declared instanceof String s) || s.isBlank()) {
        meta.put("shapeId", shapeId);
      }
      meta.put("lastActiveAtMs", System.currentTimeMillis());
      int count = ((Number) meta.getOrDefault("messageCount", 0)).intValue() + 1;
      meta.put("messageCount", count);
      // Capture the first user message as a preview for session lists.
      if (!meta.containsKey("firstUserMessage")
          && "user".equals(message.get("role"))) {
        String content = (String) message.getOrDefault("content", "");
        meta.put("firstUserMessage",
            content.length() > 200 ? content.substring(0, 200) : content);
      }
      AtomicFileWrites.replace(
          metaFile, MAPPER.writeValueAsBytes(withSealedMeta(versionedMeta(meta))));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to update conversation metadata in " + sessionDir, e);
    }
  }
}
