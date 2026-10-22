/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 629 (#E faithful import) — {@link RunEventStore#appendRawEvents} replays a run's event ledger
 * VERBATIM (preserving each record's original timestamp) without firing projector listeners, so an
 * encrypted-backup restore is no longer meta-only. The live {@link RunEventStore#appendEvent} path
 * re-stamps {@code Instant.now()} and fires listeners — wrong for replay.
 */
class RunEventStoreTest {

  @TempDir Path tempDir;

  @Test
  void writesVersionedEventsAndRefusesFutureRecordsWithoutChangingBytes() throws Exception {
    var store = new RunEventStore(tempDir.resolve("runs"));
    store.writeRunMeta("s1", Map.of("sessionId", "s1"));
    store.appendEvent("s1", "agent_run", "started", Map.of());
    String current = Files.readString(store.eventsPath("s1"));
    assertTrue(current.contains("\"schemaVersion\":1"));

    String future =
        "{\"schemaVersion\":99,\"timestamp\":\"2020-01-01T00:00:00Z\","
            + "\"shapeId\":\"agent_run\",\"eventType\":\"future\",\"payload\":{}}\n";
    Files.writeString(store.eventsPath("s1"), future);
    assertThrows(UnsupportedStoreVersionException.class, () -> store.readEvents("s1"));
    assertEquals(future, Files.readString(store.eventsPath("s1")));
  }

  @Test
  void appendFailureIsObservableInsteadOfLookingLikeASuccessfulEmptyWrite() throws Exception {
    var store = new RunEventStore(tempDir.resolve("runs"));
    store.writeRunMeta("s1", Map.of("sessionId", "s1"));
    Files.createDirectories(store.eventsPath("s1"));
    assertThrows(
        CorruptDurableStoreException.class,
        () -> store.appendEvent("s1", "agent_run", "started", Map.of()));
  }

  @Test
  void appendRawEventsPreservesTimestampsAndTypes() {
    var store = new RunEventStore(tempDir.resolve("runs"));
    store.writeRunMeta("s1", Map.of("sessionId", "s1"));
    store.appendRawEvents(
        "s1",
        List.of(
            record("2020-01-01T00:00:00Z", "agent_run", "session_started", Map.of("k", "v1")),
            record("2020-01-02T00:00:00Z", "agent_run", "tool_call", Map.of("k", "v2"))));

    List<Map<String, Object>> read = store.readEvents("s1");
    assertEquals(2, read.size());
    assertEquals("2020-01-01T00:00:00Z", read.get(0).get("timestamp"), "original timestamp preserved");
    assertEquals("session_started", read.get(0).get("eventType"));
    assertEquals("2020-01-02T00:00:00Z", read.get(1).get("timestamp"));
    assertEquals("tool_call", read.get(1).get("eventType"));
  }

  @Test
  void appendRawEventsDoesNotFireListeners() {
    var store = new RunEventStore(tempDir.resolve("runs"));
    store.writeRunMeta("s1", Map.of("sessionId", "s1"));
    var fired = new AtomicInteger(0);
    store.addEventListener((sid, ev) -> fired.incrementAndGet());
    store.appendRawEvents("s1", List.of(record("2020-01-01T00:00:00Z", "agent_run", "x", Map.of())));
    assertEquals(0, fired.get(), "replay must NOT fire projectors (the live appendEvent path does)");
  }

  @Test
  void backupRoundTripThroughJsonIsFaithful() {
    // Mirror the FULL backup data path: live appendEvent (stamps now()) -> readEvents (the export
    // source) -> JSON serialize+deserialize (the encrypted container) -> appendRawEvents (the import
    // sink) -> readEvents. The restored ledger must match (count + per-record timestamp + eventType),
    // not the meta-only empty shell the old sink produced.
    var src = new RunEventStore(tempDir.resolve("src"));
    src.writeRunMeta("s1", Map.of("sessionId", "s1"));
    src.appendEvent("s1", "agent_run", "session_started", Map.of("a", 1));
    src.appendEvent("s1", "agent_run", "tool_call", Map.of("a", 2));
    List<Map<String, Object>> exported = src.readEvents("s1");
    assertEquals(2, exported.size());

    ObjectMapper mapper = new ObjectMapper();
    String containerJson = mapper.writeValueAsString(exported);
    List<Map<String, Object>> viaJson =
        mapper.readValue(containerJson, new TypeReference<List<Map<String, Object>>>() {});

    var dst = new RunEventStore(tempDir.resolve("dst"));
    dst.writeRunMeta("s1", Map.of("sessionId", "s1"));
    dst.appendRawEvents("s1", viaJson);
    List<Map<String, Object>> restored = dst.readEvents("s1");
    assertEquals(exported.size(), restored.size(), "every exported event is restored");
    for (int i = 0; i < exported.size(); i++) {
      assertEquals(exported.get(i).get("timestamp"), restored.get(i).get("timestamp"));
      assertEquals(exported.get(i).get("eventType"), restored.get(i).get("eventType"));
    }
  }

  @Test
  void appendRawEventsEmptyOrNullIsNoop() {
    var store = new RunEventStore(tempDir.resolve("runs"));
    store.appendRawEvents("s1", List.of());
    store.appendRawEvents("s1", null);
    assertTrue(store.readEvents("s1").isEmpty());
  }

  private static Map<String, Object> record(
      String ts, String shapeId, String eventType, Map<String, Object> payload) {
    var m = new LinkedHashMap<String, Object>();
    m.put("timestamp", ts);
    m.put("shapeId", shapeId);
    m.put("eventType", eventType);
    m.put("payload", payload);
    return m;
  }
}
