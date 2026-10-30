/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.BlockedBy;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.child.StopDetail;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("operating block records")
final class OperatingStateViewTest {

  private static final String T = "2026-10-05T10:00:00Z";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("enums have exactly the reconciled members and wire casing")
  void vocabulary() throws Exception {
    assertEquals(List.of("IDLE", "RUNNING", "SLOWED", "PAUSING", "PAUSED"), names(IndexingState.class));
    assertEquals(List.of("P16_PLUS", "P12", "P8", "NO_GPU", "UNRESOLVED"), names(Profile.class));
    assertEquals(List.of("MEASURED", "USER_OVERRIDE"), names(ProfileSource.class));
    assertEquals(
        List.of("settings_recovery", "user_paused", "schedule", "on_battery",
            "game_mode", "gpu_busy_external", "energy_saver", "chat_generating"),
        Arrays.stream(IndexingReason.values()).map(r -> MAPPER.writeValueAsString(r)
            .replace("\"", "")).toList(),
        "lower_snake on the wire, in precedence order");
    assertEquals("\"user_setting_unmet\"",
        MAPPER.writeValueAsString(DegradationCode.USER_SETTING_UNMET));
    assertEquals("\"indexing\"", MAPPER.writeValueAsString(OperatingBlock.INDEXING));
    assertEquals(IndexingReason.ENERGY_SAVER,
        MAPPER.readValue("\"energy_saver\"", IndexingReason.class));
  }

  @Test
  @DisplayName("chat reason must belong to the vocabulary of its state")
  void chatReasonVocabulary() {
    assertEquals(Set.of(), OperatingChatView.reasonsFor(ChatState.READY));
    assertTrue(OperatingChatView.reasonsFor(ChatState.UNLOADED).contains("RESIDENCY_PRESSURE"));
    assertTrue(OperatingChatView.reasonsFor(ChatState.FAILED).contains("CRASH_LOOP"));

    chat(ChatState.LOADING, "RELOAD", null, null);
    chat(ChatState.UNLOADED, "RESIDENCY_PRESSURE", BlockedBy.GPU_BUSY, null);
    assertThrows(IllegalArgumentException.class, () -> chat(ChatState.READY, "RELOAD", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> chat(ChatState.FAILED, "RESIDENCY_PRESSURE", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> chat(ChatState.READY, null, BlockedBy.GPU_BUSY, null));
    ChatLoadView load = new ChatLoadView(LoadPhase.LOADING_MODEL, T, 10L, null, null);
    chat(ChatState.LOADING, null, null, load);
    assertThrows(IllegalArgumentException.class, () -> chat(ChatState.READY, null, null, load));
  }

  @Test
  @DisplayName("chat counts are consistent; a load estimate names its source")
  void chatCountsAndLoad() {
    new OperatingChatView(ChatState.READY, null, null, null, T, true, true, 1, 0, null, null,
        null, null, T);
    assertThrows(IllegalArgumentException.class, () -> new OperatingChatView(ChatState.READY,
        null, null, null, T, true, true, 0, 0, null, null, null, null, T));
    assertThrows(IllegalArgumentException.class, () -> new OperatingChatView(ChatState.READY,
        null, null, null, T, true, null, -1, 0, null, null, null, null, T));
    assertThrows(IllegalArgumentException.class,
        () -> new ChatLoadView(LoadPhase.WARMING, T, 1L, 5L, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ChatLoadView(LoadPhase.WARMING, T, -1L, null, null));
  }

  @Test
  @DisplayName("indexing reasons are distinct, ordered, and required when PAUSED or SLOWED")
  void indexingRules() {
    indexing(IndexingState.PAUSED,
        List.of(IndexingReason.GAME_MODE, IndexingReason.ENERGY_SAVER));
    assertThrows(IllegalArgumentException.class, () -> indexing(IndexingState.PAUSED,
        List.of(IndexingReason.ENERGY_SAVER, IndexingReason.GAME_MODE)));
    assertThrows(IllegalArgumentException.class, () -> indexing(IndexingState.SLOWED,
        List.of(IndexingReason.CHAT_GENERATING, IndexingReason.CHAT_GENERATING)));
    assertThrows(IllegalArgumentException.class,
        () -> indexing(IndexingState.PAUSED, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> indexing(IndexingState.SLOWED, List.of()));
    indexing(IndexingState.RUNNING, List.of());
  }

  @Test
  @DisplayName("gpu: unknown has no readings; profile source pairs with a resolved profile")
  void gpuRules() {
    OperatingGpuView unknown = OperatingGpuView.unknown();
    assertEquals(Profile.UNRESOLVED, unknown.profile());
    assertFalse(unknown.stale());
    new OperatingGpuView(Profile.P12, ProfileSource.MEASURED, "RTX", 12L, 8L, 2L, 0L, 2L,
        false, GpuMemorySource.PDH, T, 10L, false);
    assertThrows(IllegalArgumentException.class, () -> new OperatingGpuView(Profile.P12, null,
        null, null, null, null, null, null, null, GpuMemorySource.NONE, null, null, false));
    assertThrows(IllegalArgumentException.class, () -> new OperatingGpuView(Profile.UNRESOLVED,
        ProfileSource.MEASURED, null, null, null, null, null, null, null, GpuMemorySource.NONE, null,
        null, false));
    assertThrows(IllegalArgumentException.class, () -> new OperatingGpuView(Profile.UNRESOLVED,
        null, null, 12L, null, null, null, null, null, GpuMemorySource.NONE, null, null, false));
    assertThrows(IllegalArgumentException.class, () -> new OperatingGpuView(Profile.P8,
        ProfileSource.MEASURED, null, 12L, 8L, 2L, null, null, null, GpuMemorySource.NVML, T, 1L,
        false));
    assertThrows(IllegalArgumentException.class, () -> new OperatingGpuView(Profile.P8,
        ProfileSource.MEASURED, null, 12L, 8L, null, null, 4L, null, GpuMemorySource.NVML, T, 1L,
        false));
  }

  @Test
  @DisplayName("the block validates its envelope and omits absent chat and indexing on the wire")
  void envelope() throws Exception {
    var freshness = new OperatingFreshnessView(30_000L, List.of());
    var view = new OperatingStateView(OperatingStateView.SCHEMA_VERSION, 1L, T, null, null,
        OperatingGpuView.unknown(), List.of(), freshness);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(view));
    assertFalse(json.has("chat"));
    assertFalse(json.has("indexing"));
    assertTrue(json.get("gpu").has("adapterTotalBytes"), "precise nulls stay present");

    assertThrows(IllegalArgumentException.class, () -> new OperatingStateView(2, 1L, T, null,
        null, OperatingGpuView.unknown(), List.of(), freshness));
    assertThrows(IllegalArgumentException.class, () -> new OperatingStateView(1, 0L, T, null,
        null, OperatingGpuView.unknown(), List.of(), freshness));
    assertThrows(IllegalArgumentException.class, () -> new OperatingFreshnessView(0L, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new OperatingDegradationView(
        DegradationCode.CHILD_SURVIVED, null, DegradationScope.values()[0], "runtime.x", T, T,
        0L));
  }

  @Test
  @DisplayName("a chat detail serializes as its lower-case token")
  void chatDetailToken() throws Exception {
    var chat = new OperatingChatView(ChatState.UNLOADED, "RESIDENCY_PRESSURE",
        StopDetail.GAME_MODE, null, T, true, null, null, null, BlockedBy.GAME_MODE, null, null,
        null, T);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(chat));
    assertEquals("game_mode", json.get("detail").asText());
    assertEquals("GAME_MODE", json.get("blockedBy").asText());
  }

  private static OperatingChatView chat(
      ChatState state, String reason, BlockedBy blockedBy, ChatLoadView load) {
    return new OperatingChatView(state, reason, null, null, T, true, null, null, null, blockedBy,
        load, null, null, T);
  }

  private static OperatingIndexingView indexing(IndexingState state, List<IndexingReason> r) {
    return new OperatingIndexingView(state, r, null, T, List.of(), 1L, null, T);
  }

  private static List<String> names(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
  }
}
