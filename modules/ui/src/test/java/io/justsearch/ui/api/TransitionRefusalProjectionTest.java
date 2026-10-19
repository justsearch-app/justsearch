/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.GenerativeSettingsComponentOwner;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settings or chat-mode request refused because the chat server is changing state reaches the
 * client as a transient, retryable refusal (503 or 409 per the error contract), never as a 500
 * PERMANENT fault (b6d536 item 5). The refusals come from the production owners: the generative
 * settings owner refused by a busy lifecycle manager, and the settings commit coordinator while
 * another settings transaction holds it. They are projected by the real HTTP handlers.
 */
final class TransitionRefusalProjectionTest {

  @TempDir Path directory;

  private record Projected(int status, Map<?, ?> body) {}

  @Test
  void aBusyChatServerRefusalIsTransientThroughTheSettingsRoute() throws Exception {
    SettingsCommitOwner.Refused refusal = generativeRefusal(
        ModeTransitionException.Reason.ALREADY_TRANSITIONING,
        "Chat is busy: a reply is still being generated");

    Projected projected = throughSettings(refusal);

    assertEquals(503, projected.status(), "a transition is not a server fault: " + projected);
    assertEquals("TRANSIENT", projected.body().get("errorClass"));
    assertEquals(true, projected.body().get("retryable"));
    assertEquals("GENERATIVE_PREPARATION_REFUSED", projected.body().get("errorCode"));
  }

  @Test
  void aBusyChatServerRefusalIsTransientThroughTheChatModeRoute() throws Exception {
    SettingsCommitOwner.Refused refusal = generativeRefusal(
        ModeTransitionException.Reason.ALREADY_TRANSITIONING,
        "Chat is busy: a reply is still being generated");

    Projected projected = throughChatMode(refusal);

    assertEquals(503, projected.status(), "a transition is not a server fault: " + projected);
    assertEquals("TRANSIENT", projected.body().get("errorClass"));
    assertEquals(true, projected.body().get("retryable"));
  }

  @Test
  void anInterruptedTransitionRefusalIsTransient() throws Exception {
    SettingsCommitOwner.Refused refusal = generativeRefusal(
        ModeTransitionException.Reason.INTERRUPTED, "Interrupted while the chat server drained");

    Projected projected = throughSettings(refusal);

    assertEquals(503, projected.status(), "a transition is not a server fault: " + projected);
    assertEquals("TRANSIENT", projected.body().get("errorClass"));
    assertEquals(true, projected.body().get("retryable"));
  }

  @Test
  void aPermanentGenerativeRefusalStaysAPermanentFault() throws Exception {
    SettingsCommitOwner.Refused refusal = generativeRefusal(
        ModeTransitionException.Reason.INVALID_CONFIG, "Model file not found");

    Projected projected = throughSettings(refusal);

    assertEquals(500, projected.status());
    assertEquals("PERMANENT", projected.body().get("errorClass"));
    assertEquals(false, projected.body().get("retryable"));
  }

  @Test
  void anOccupiedSettingsTransactionIsATransientConflict() throws Exception {
    var settings = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var coordinator = new SettingsCommitCoordinator(settings,
        new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())), () -> {},
        candidate -> io.justsearch.agent.api.registry.OperationResult.success("prepared"));
    coordinator.inspectRecovery(List.of());
    Clock clock = Clock.systemUTC();
    coordinator.reserve(1L, OperationKeys.generate(clock), new SettingsWitness(0, null));
    SettingsCommitOwner.Refused occupied = assertThrows(SettingsCommitOwner.Refused.class,
        () -> coordinator.reserve(2L, OperationKeys.generate(clock), new SettingsWitness(0, null)));

    Projected projected = throughSettings(occupied);

    assertEquals(409, projected.status());
    assertEquals("RECONFIGURE_IN_PROGRESS", projected.body().get("errorCode"));
    assertEquals("TRANSIENT", projected.body().get("errorClass"));
    assertEquals(true, projected.body().get("retryable"), "the contender may retry: " + projected);
  }

  // ==================== helpers ====================

  /** The refusal the production generative owner raises when the lifecycle manager refuses. */
  private SettingsCommitOwner.Refused generativeRefusal(
      ModeTransitionException.Reason reason, String message) throws Exception {
    ResolvedConfig desired =
        ResolvedConfig.builder().putDefault("justsearch.llm.enabled", "true").build();
    var manager = mock(InferenceLifecycleManager.class);
    when(manager.prepareResolvedConfig(any(), any(), anyBoolean(), anyBoolean()))
        .thenThrow(new ModeTransitionException(reason, message));
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(3), 1);
    var before = new EngineComponentSnapshot.Component(spec, ComponentState.READY, null,
        Instant.parse("2026-10-06T00:00:00Z"), 1L, "A", "A", null, 0, "A");
    var staging = new EngineComponentSnapshot.Component(spec, ComponentState.RELOADING, null,
        before.stateSince(), before.stateSinceMonotonicNanos(), "A", "A", null, 0, "preparing");
    var handle = mock(ComponentHandle.class);
    when(handle.snapshot()).thenReturn(before, staging);
    when(handle.transitionIfUnchanged(any(EngineComponentSnapshot.Component.class), any(),
        any(), any())).thenReturn(true);
    var candidate = new UiSettings();
    candidate.setChatEnabled(true);
    var owner = new GenerativeSettingsComponentOwner(manager, handle, directory, false);
    return assertThrows(SettingsCommitOwner.Refused.class, () -> owner.prepare(
        candidate, desired, Set.of("chatEnabled"), SettingsCandidateContext.NONE));
  }

  private static Projected throughSettings(RuntimeException refusal) {
    var service = mock(SettingsService.class);
    when(service.applyPublic(any(), any(), any())).thenThrow(refusal);
    List<Integer> statuses = new ArrayList<>();
    List<Object> bodies = new ArrayList<>();
    Context context = context("/api/settings/v2", statuses, bodies);
    when(context.body()).thenReturn("{}");
    when(context.method()).thenReturn(io.javalin.http.HandlerType.POST);
    new SettingsController(null, Path.of("."), null, service).handleUpdateSettingsV2(context);
    return projected(statuses, bodies);
  }

  private static Projected throughChatMode(RuntimeException refusal) throws Exception {
    var service = mock(BrainRuntimeService.class);
    when(service.switchInferenceMode(any(), any(), any())).thenThrow(refusal);
    List<Integer> statuses = new ArrayList<>();
    List<Object> bodies = new ArrayList<>();
    Context context = context("/api/inference/mode", statuses, bodies);
    when(context.bodyAsClass(Map.class)).thenReturn(Map.of("mode", "online"));
    new InferenceHandlers(null, null, null, null, null, null, null, service, () -> null)
        .handleSetInferenceMode(context);
    return projected(statuses, bodies);
  }

  private static Context context(String path, List<Integer> statuses, List<Object> bodies) {
    Context context = mock(Context.class);
    when(context.path()).thenReturn(path);
    when(context.status(anyInt())).thenAnswer(invocation -> {
      statuses.add(invocation.getArgument(0));
      return context;
    });
    when(context.json(any())).thenAnswer(invocation -> {
      bodies.add(invocation.getArgument(0));
      return context;
    });
    return context;
  }

  private static Projected projected(List<Integer> statuses, List<Object> bodies) {
    assertEquals(1, statuses.size(), "one status: " + statuses);
    assertEquals(1, bodies.size(), "one body: " + bodies);
    return new Projected(statuses.get(0), assertInstanceOf(Map.class, bodies.get(0)));
  }
}
