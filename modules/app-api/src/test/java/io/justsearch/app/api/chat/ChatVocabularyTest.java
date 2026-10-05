/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.core.context.EngineContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The chat vocabulary (reconciliation section 3) and the invariants of {@link ChatStatus}. */
@DisplayName("chat vocabulary")
final class ChatVocabularyTest {

  private static final Instant SINCE = Instant.parse("2026-10-05T10:00:00Z");

  @Test
  @DisplayName("every enum has exactly the reconciled members, in order")
  void exactMembers() {
    assertEquals(List.of("OFF", "LOADING", "READY", "UNLOADED", "REFUSED", "FAILED"),
        names(ChatState.class));
    assertEquals(List.of("RESIDENCY_PRESSURE", "SELF_TEST", "IDLE_UNLOAD"),
        names(UnloadReason.class), "no VDU_ENTER: VDU is a hold, not an unload");
    assertEquals(List.of("GPU_BUSY", "GAME_MODE", "RELOAD_BACKOFF"), names(BlockedBy.class));
    assertEquals(List.of("PROFILE_CANNOT_FIT", "EXTERNAL_CONFLICT", "EXTERNAL_POLICY_BLOCKED",
        "EXECUTABLE_MISSING", "INVALID_CONFIG"), names(RefusedCode.class));
    assertEquals(List.of("RESOURCE_EXHAUSTED", "CRASH_LOOP", "STARTUP_TIMEOUT", "START_FAILED",
        "MISSING_DLL", "MODEL_LOAD_FAILED", "STOP_FAILED", "EXTERNAL_UNHEALTHY",
        "DEVICE_LOST_LIMIT"), names(FailedCode.class));
    assertEquals(List.of("COLD_START", "BOOT_ORDER", "RECONFIGURE", "MODEL_SWITCH", "VDU",
        "RECOVERY", "RELOAD", "STEP_DOWN"), names(LoadingCause.class));
    assertEquals(List.of("GPU_BUSY", "GAME_MODE", "RELOAD_BACKOFF", "LOADING", "BUSY", "OFF",
        "FAILED", "REFUSED", "ENGINE_STOPPING", "DEVICE_RECOVERING", "SELF_TEST"),
        names(RefusalReason.class));
    assertEquals(List.of("HUMAN_CHAT", "AGENT_CHAT", "ASSIST", "BACKGROUND", "AUX"),
        names(DemandKind.class));
  }

  @Test
  @DisplayName("wire casing is the constant name")
  void wireCasing() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertEquals("\"UNLOADED\"", mapper.writeValueAsString(ChatState.UNLOADED));
    assertEquals("\"RELOAD_BACKOFF\"", mapper.writeValueAsString(RefusalReason.RELOAD_BACKOFF));
  }

  @Test
  @DisplayName("ChatStatus: each reason slot is legal only in its own state")
  void reasonSlotsAreStateScoped() {
    assertEquals(Optional.of("RELOAD"),
        status(ChatState.LOADING, b -> b.loading = Optional.of(LoadingCause.RELOAD)).reasonName());
    assertEquals(Optional.of("CRASH_LOOP"),
        status(ChatState.FAILED, b -> b.failed = Optional.of(FailedCode.CRASH_LOOP)).reasonName());
    assertEquals(Optional.of("PROFILE_CANNOT_FIT"), status(ChatState.REFUSED,
        b -> b.refused = Optional.of(RefusedCode.PROFILE_CANNOT_FIT)).reasonName());
    assertEquals(Optional.empty(), status(ChatState.READY, b -> {}).reasonName());

    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.READY, b -> b.loading = Optional.of(LoadingCause.RELOAD)));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.READY, b -> b.failed = Optional.of(FailedCode.CRASH_LOOP)));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.OFF, b -> b.refused = Optional.of(RefusedCode.INVALID_CONFIG)));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.LOADING, b -> b.unload = Optional.of(UnloadReason.SELF_TEST)));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.READY, b -> b.blocked = Optional.of(BlockedBy.GPU_BUSY)));
  }

  @Test
  @DisplayName("ChatStatus: UNLOADED needs a reason, and a pressure unload names gpu_busy or game_mode")
  void unloadRules() {
    var pressure = status(ChatState.UNLOADED, b -> {
      b.unload = Optional.of(UnloadReason.RESIDENCY_PRESSURE);
      b.detail = Optional.of(StopDetail.GAME_MODE);
      b.blocked = Optional.of(BlockedBy.GAME_MODE);
    });
    assertEquals(Optional.of("RESIDENCY_PRESSURE"), pressure.reasonName());

    assertThrows(IllegalArgumentException.class, () -> status(ChatState.UNLOADED, b -> {}));
    assertThrows(IllegalArgumentException.class, () -> status(ChatState.UNLOADED,
        b -> b.unload = Optional.of(UnloadReason.RESIDENCY_PRESSURE)));
    assertThrows(IllegalArgumentException.class, () -> status(ChatState.UNLOADED, b -> {
      b.unload = Optional.of(UnloadReason.RESIDENCY_PRESSURE);
      b.detail = Optional.of(StopDetail.HANG);
    }));
  }

  @Test
  @DisplayName("ChatStatus: generating is exactly inFlight > 0")
  void generatingMatchesInFlight() {
    var busy = status(ChatState.READY, b -> {
      b.generating = true;
      b.inFlight = 2;
    });
    assertTrue(busy.generating());
    assertThrows(IllegalArgumentException.class, () -> status(ChatState.READY, b -> {
      b.generating = true;
      b.inFlight = 0;
    }));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.READY, b -> b.inFlight = 1));
    assertThrows(IllegalArgumentException.class,
        () -> status(ChatState.READY, b -> b.queued = -1));
  }

  @Test
  @DisplayName("Activation names its reason exactly when not effective")
  void activation() {
    assertTrue(new Activation(true, Optional.empty()).effective());
    assertEquals(Optional.of("policy"), new Activation(false, Optional.of("policy")).reasonIfNot());
    assertThrows(IllegalArgumentException.class, () -> new Activation(false, Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> new Activation(true, Optional.of("x")));
    assertThrows(IllegalArgumentException.class, () -> new Activation(false, Optional.of(" ")));
  }

  @Test
  @DisplayName("ChatRefusal and Demand reject negative durations")
  void refusalAndDemand() {
    var refusal = new ChatRefusal(RefusalReason.GPU_BUSY, Optional.of(Duration.ofSeconds(30)),
        true);
    assertEquals(RefusalReason.GPU_BUSY, refusal.reason());
    assertTrue(refusal.forceAllowed());
    assertThrows(IllegalArgumentException.class,
        () -> new ChatRefusal(RefusalReason.BUSY, Optional.of(Duration.ofSeconds(-1)), false));
    var context = new EngineContext(EngineContext.ClientKind.WEBVIEW, "local-webview",
        Optional.empty(), Optional.empty(), "first-party", "BUTTON",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND, Optional.empty());
    assertEquals(DemandKind.HUMAN_CHAT,
        new Demand(DemandKind.HUMAN_CHAT, context, Duration.ofSeconds(30)).kind());
    assertThrows(IllegalArgumentException.class,
        () -> new Demand(DemandKind.AUX, context, Duration.ofMillis(-1)));
  }

  /** Mutable inputs for one ChatStatus, defaulting to an idle, consistent snapshot. */
  private static final class Fields {
    Optional<LoadingCause> loading = Optional.empty();
    Optional<UnloadReason> unload = Optional.empty();
    Optional<RefusedCode> refused = Optional.empty();
    Optional<FailedCode> failed = Optional.empty();
    Optional<StopDetail> detail = Optional.empty();
    Optional<BlockedBy> blocked = Optional.empty();
    boolean generating;
    int inFlight;
    int queued;
  }

  private static ChatStatus status(ChatState state, java.util.function.Consumer<Fields> edit) {
    Fields f = new Fields();
    edit.accept(f);
    return new ChatStatus(state, f.loading, f.unload, f.refused, f.failed, f.detail, f.blocked,
        SINCE, true, f.generating, f.inFlight, f.queued, Optional.empty(), 1L);
  }

  private static List<String> names(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
  }
}
