/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 565 §15.C — the SHAPE-AGNOSTIC, append-only run event log.
 *
 * <p>The durable half of a run is a timestamped event stream: a per-run {@code events.ndjson} of
 * {@code {timestamp, eventType, payload}} records, with listeners fired (outside the write lock) on
 * every persisted event so a downstream projector (the ledger / the unified thread) derives from this
 * ONE record and can never get ahead of it. This was previously fused into {@link AgentRunStore}, which
 * also carries the AGENT-specific {@code meta.json} (profiles / handoff / resume). §15.C extracts the
 * generic event-log so BOTH the agent run (via {@code AgentRunStore}, which maps {@code AgentEvent} →
 * {@code (eventType, payload)} and composes this) and the workflow run (via {@code WorkflowShapeRunner},
 * which stamps + appends its SSE events) persist + project through one authority — the run-render leaf
 * unification at the persistence tier. This class references no shape vocabulary: the caller owns the
 * {@code eventType} string and the {@code payload} map.
 */
public final class RunEventStore {
  private static final Logger LOG = LoggerFactory.getLogger(RunEventStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private final Path rootDir; // nullable for noop
  private final StoreCipher cipher; // tempdoc 629 (LAYER) — seals events.ndjson + meta.json when enabled

  /**
   * Listeners fired (outside the write lock) on every persisted event; copy-on-write, fail-soft (a
   * throwing listener must not corrupt the run). The listener receives {@code (sessionId, record)}
   * where {@code record} is the persisted {@code {timestamp, eventType, payload}} map.
   */
  private final List<BiConsumer<String, Map<String, Object>>> eventListeners =
      new CopyOnWriteArrayList<>();

  public RunEventStore(Path rootDir) {
    this(rootDir, StoreCipher.disabled());
  }

  /** Tempdoc 629 (LAYER) — {@code events.ndjson} (per-line) + {@code meta.json} (whole-file) are sealed
   * by {@code cipher} when at-rest encryption is enabled. While locked, reads return empty (the agent-run
   * ledger is empty-until-unlock — a documented limitation) and writes refuse without overwriting. */
  public RunEventStore(Path rootDir, StoreCipher cipher) {
    this.rootDir = rootDir;
    this.cipher = Objects.requireNonNull(cipher, "cipher");
  }

  public static RunEventStore noop() {
    return new RunEventStore(null, StoreCipher.disabled());
  }

  public boolean isEnabled() {
    return rootDir != null;
  }

  /** Register a listener fired (outside the write lock) on every persisted event. */
  public void addEventListener(BiConsumer<String, Map<String, Object>> listener) {
    eventListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /**
   * Append one event to the run's {@code events.ndjson} and fan out to listeners (the record is on
   * disk before any listener sees it). Returns the persisted record, or null if disabled/failed.
   *
   * <p>Tempdoc 565 §15.C fix — the record carries the run's {@code shapeId} so SHAPE-SPECIFIC
   * listeners (e.g. the agent-run ledger projector) can filter: the store is now multi-shape (agent +
   * workflow share it), and an agent-only projector must NOT react to a workflow event.
   */
  public Map<String, Object> appendEvent(
      String sessionId, String shapeId, String eventType, Map<String, Object> payload) {
    Map<String, Object> record = writeEventRecord(sessionId, shapeId, eventType, payload);
    if (record != null) {
      for (BiConsumer<String, Map<String, Object>> listener : eventListeners) {
        try {
          listener.accept(sessionId, record);
        } catch (Exception e) {
          LOG.warn("RunEventStore event listener failed for session {}", sessionId, e);
        }
      }
    }
    return record;
  }

  private synchronized Map<String, Object> writeEventRecord(
      String sessionId, String shapeId, String eventType, Map<String, Object> payload) {
    if (!isEnabled()) {
      return null;
    }
    try {
      Files.createDirectories(runDir(sessionId));
      var record = new LinkedHashMap<String, Object>();
      record.put("schemaVersion", CURRENT_SCHEMA_VERSION);
      record.put("timestamp", Instant.now().toString());
      record.put("shapeId", shapeId);
      record.put("eventType", eventType);
      record.put("payload", payload);
      String line = cipher.seal(MAPPER.writeValueAsString(record)) + System.lineSeparator();
      Files.writeString(
          eventsPath(sessionId),
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      return record;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "run-events", "Failed to append event for session " + sessionId, e);
    }
  }

  /**
   * Tempdoc 629 (#E faithful import) — append pre-formed event records VERBATIM (preserving each
   * record's original {@code timestamp}), with NO listener fan-out. The import/replay twin of
   * {@link #appendEvent}: the live path re-stamps {@code Instant.now()} and fires projectors (correct
   * for a running run), which would corrupt timestamps and double-fire on a backup restore. The records
   * come straight from {@link #readEvents} (already {@code {timestamp, shapeId, eventType, payload}} and
   * upcast), so the round-trip is faithful. Sealed exactly like the live path; while locked, {@code seal}
   * throws and the write fails soft without overwriting (mirrors {@link #writeEventRecord}).
   */
  public synchronized void appendRawEvents(String sessionId, List<?> records) {
    if (!isEnabled() || records == null || records.isEmpty()) {
      return;
    }
    try {
      Files.createDirectories(runDir(sessionId));
      StringBuilder sb = new StringBuilder();
      for (Object record : records) {
        if (!(record instanceof Map<?, ?> rec)) {
          continue; // skip non-record entries (the records come from JSON via readEvents)
        }
        sb.append(cipher.seal(MAPPER.writeValueAsString(rec))).append(System.lineSeparator());
      }
      if (sb.length() == 0) {
        return;
      }
      Files.writeString(
          eventsPath(sessionId),
          sb.toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "run-events", "Failed to append imported events for session " + sessionId, e);
    }
  }

  /**
   * Read all persisted events for a run (oldest first), upcasting legacy payloads on read.
   *
   * <p><strong>KNOWN DEFECT, not a design limitation (tempdoc 806 C5).</strong> While the chat
   * cipher is sealed and locked this returns an empty list, so "the ledger is unreadable right now"
   * and "this run has no events" are the same answer. That is the exact shape tempdoc 806 fixed for
   * {@code FileMemoryStore} after round 12 filed it as a HIGH: an unreadable state answered as an
   * empty one, which consumers then render as a positive factual claim. Here the observable
   * consequences are {@code GET /api/chat/sessions/{id}/events} answering <em>404 "No events found
   * for session X"</em> (a confident negative derived from a read that could not run) and {@code GET
   * /api/chat/sessions/{id}/transcript} downloading a transcript with {@code events: []} — a "save
   * your record" affordance that silently produces an empty record. Every restart locks the key, so
   * this is the ordinary state, not an edge case.
   *
   * <p>The fix conforms to the precedent W1 shipped for memory rather than inventing one: the read
   * path exposes its locked state and the API answers <strong>423 Locked</strong> with the typed
   * {@code STORE_LOCKED} errorCode (the tempdoc-629 global handler in {@code LocalApiServer} already
   * maps {@code KeyLockedException} that way). It is deliberately NOT applied in this change:
   * {@code AgentRunQueryService.threadEvents} also reads this method to build the unified
   * conversation thread, so failing loud here changes what a locked chat surface renders, and that
   * needs live verification the ride-along scope did not cover. Do not re-label this a "documented
   * limitation" — it is an unfixed instance of a named defect class.
   */
  public synchronized List<Map<String, Object>> readEvents(String sessionId) {
    if (!isEnabled()) {
      return List.of();
    }
    Path events = eventsPath(sessionId);
    if (!Files.exists(events)) {
      return List.of();
    }
    if (cipher.enabled() && cipher.locked()) {
      // Tempdoc 806 C5 — sealed + locked: unreadable, reported as empty. See the method javadoc:
      // this is a known unfixed instance of the 806 unreadable-is-not-empty defect class.
      return List.of();
    }
    try {
      List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
      List<Map<String, Object>> out = new ArrayList<>();
      for (String line : lines) {
        if (line == null || line.isBlank()) {
          continue;
        }
        Map<String, Object> event = MAPPER.readValue(cipher.open(line), MAP_REF);
        int version =
            event.get("schemaVersion") instanceof Number number ? number.intValue() : 0;
        if (version > CURRENT_SCHEMA_VERSION) {
          throw new UnsupportedStoreVersionException(
              "run-events", version, CURRENT_SCHEMA_VERSION);
        }
        out.add(EventPayloadUpcaster.upcast(event));
      }
      return out;
    } catch (UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "run-events", "Event log is unreadable for session " + sessionId, e);
    }
  }

  /**
   * Tempdoc 565 §15.C — write the generic run-index {@code meta.json} (sessionId / conversationId /
   * shapeId / startedAt / state / background …). The unified thread finds a run by scanning these for
   * a matching {@code conversationId}, so a workflow run that writes one is projected alongside agent
   * runs. The AGENT run layers richer fields (profiles / handoff / resume) on the same file via
   * {@code AgentRunStore}; this is the shape-agnostic base both share.
   */
  public synchronized void writeRunMeta(String sessionId, Map<String, Object> meta) {
    if (!isEnabled()) {
      return;
    }
    try {
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
      AtomicFileWrites.replaceUtf8(metaPath(sessionId), cipher.seal(json));
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "run-events", "Failed to write run metadata for session " + sessionId, e);
    }
  }

  public Path runDir(String sessionId) {
    return rootDir.resolve(sessionId);
  }

  public Path eventsPath(String sessionId) {
    return runDir(sessionId).resolve("events.ndjson");
  }

  public Path metaPath(String sessionId) {
    return runDir(sessionId).resolve("meta.json");
  }

  /**
   * Per-event-payload upcasters for {@code events.ndjson} entries (tempdoc 429 §F.21 C2): rev-9 changed
   * {@code "safetyLevel": "READ_ONLY"|"WRITE"|"DESTRUCTIVE"} to {@code "risk": "low"|"medium"|"high"}.
   * Old session events on disk still carry the legacy field; this materializes them in the new shape on
   * read. Pure; never mutates stored data.
   */
  private static final class EventPayloadUpcaster {
    private static Map<String, Object> upcast(Map<String, Object> event) {
      Object payload = event.get("payload");
      if (!(payload instanceof Map<?, ?> raw)) {
        return event;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) raw;
      if (!typed.containsKey("safetyLevel") || typed.containsKey("risk")) {
        return event;
      }
      Object legacy = typed.get("safetyLevel");
      String risk =
          switch (legacy instanceof String s ? s : "") {
            case "READ_ONLY" -> "low";
            case "WRITE" -> "medium";
            case "DESTRUCTIVE" -> "high";
            default -> null;
          };
      if (risk == null) {
        return event;
      }
      var migratedPayload = new LinkedHashMap<>(typed);
      migratedPayload.remove("safetyLevel");
      migratedPayload.put("risk", risk);
      var migratedEvent = new LinkedHashMap<>(event);
      migratedEvent.put("payload", migratedPayload);
      return migratedEvent;
    }
  }
}
