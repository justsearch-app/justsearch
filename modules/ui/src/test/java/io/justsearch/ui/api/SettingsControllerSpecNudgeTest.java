package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import io.javalin.http.Context;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 737 Phase 1: a persisted settings write that CHANGES {@code chatEnabled} must fire the
 * spec-write nudge (the runtime reconciler's {@code specChanged()} hook) exactly once; writes that
 * do not change it must not fire. Guards the "spec writes converge now, not at next boot" seam —
 * the nudge had zero production callers when first implemented (wrong-gate class).
 */
@DisplayName("SettingsController — chatEnabled spec-write nudge")
final class SettingsControllerSpecNudgeTest {

  @TempDir Path tmp;

  private UiSettingsStore store;
  private AtomicInteger nudges;
  private SettingsController controller;
  private io.justsearch.app.observability.operations.SqliteOperationStore operations;
  private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

  @BeforeEach
  void setUp() throws Exception {
    store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    nudges = new AtomicInteger();
    operations = new io.justsearch.app.observability.operations.SqliteOperationStore(tmp.resolve("operations.db"));
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(store.load()));
    var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(store, config,
        () -> { throw new AssertionError("Unexpected settings restart"); },
        candidate -> io.justsearch.agent.api.registry.OperationResult.success("Settings committed"),
        () -> false, inMemoryComponents());
    var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
        java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
            io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
    controller = new SettingsController(store, tmp, null,
        new io.justsearch.app.services.settings.SettingsServiceImpl(store, runner, nudges::incrementAndGet));
  }

  @AfterEach void closeOperations() throws Exception { operations.close(); }

  private String envelope(String body) {
    var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
    tree.set("witness", JSON.valueToTree(store.inspect().witness()));
    tree.put("operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()));
    return JSON.writeValueAsString(tree);
  }

  private Context contextWithBody(String body) {
    return contextWithEnvelope(envelope(body));
  }

  private Context contextWithEnvelope(String body) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.path()).thenReturn("/api/settings/v2");
    when(ctx.method()).thenReturn(io.javalin.http.HandlerType.POST);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
    return ctx;
  }

  private Context contextWithBodyAndIntent(String body, String intent) {
    Context ctx = contextWithBody(body);
    when(ctx.header(SettingsController.UI_MODE_INTENT_HEADER)).thenReturn(intent);
    return ctx;
  }

  private static io.justsearch.app.services.settings.SettingsComponentComposer inMemoryComponents() {
    return (candidate, desired, affected) -> new io.justsearch.app.services.settings.SettingsComponentComposer.Prepared() {
      @Override public void validate() { }
      @Override public void install() { }
      @Override public void notifyObservers() { }
      @Override public void retire() { }
      @Override public void abort() { }
    };
  }

  @Test
  @DisplayName("write that sets chatEnabled true (from unset) fires the nudge once")
  void changedChatEnabledFires() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    assertEquals(1, nudges.get());
    assertEquals(Boolean.TRUE, store.load().getChatEnabled());
  }

  @Test
  @DisplayName("write that does not touch chatEnabled does not fire")
  void unrelatedWriteDoesNotFire() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"theme\":\"dark\"}}"));
    assertEquals(0, nudges.get());
  }

  @Test
  @DisplayName("write repeating the current chatEnabled value does not fire")
  void unchangedValueDoesNotFire() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    assertEquals(1, nudges.get());
  }

  @Test
  @DisplayName("toggling back fires again — one nudge per actual change")
  void toggleFiresPerChange() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":false}}"));
    assertEquals(2, nudges.get());
  }

  @Test
  @DisplayName("stale partial patches conflict; a new observed attempt preserves both changes")
  void concurrentPartialPatchesDoNotClobberOneAnother() {
    Context first = contextWithBody("{\"ui\":{\"mode\":\"advanced\"}}");
    Context second = contextWithBody("{\"ui\":{\"theme\":\"dark\"}}");
    controller.handleUpdateSettingsV2(first);
    controller.handleUpdateSettingsV2(second);
    verify(second).status(409);
    assertEquals("advanced", store.inspect().settings().getMode());
    assertEquals("system", store.inspect().settings().getTheme());
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"theme\":\"dark\"}}"));
    assertEquals("advanced", store.inspect().settings().getMode());
    assertEquals("dark", store.inspect().settings().getTheme());
  }

  @Test
  void uncomposedControllerCannotCreateAnUnrecordedWriter() {
    var context = contextWithBody("{\"ui\":{\"chatEnabled\":true}}");
    new SettingsController(store, tmp, null).handleUpdateSettingsV2(context);
    verify(context).status(503);
    assertEquals(0, store.inspect().witness().acceptedRevision());
    assertEquals(0, nudges.get());
  }

  @Test
  @DisplayName("a late timed-out mode intent cannot overwrite a newer intent")
  void staleModeIntentCannotOverwriteNewerMode() {
    controller.handleUpdateSettingsV2(contextWithBodyAndIntent(
        "{\"ui\":{\"mode\":\"advanced\"}}", "client-a:2"));
    controller.handleUpdateSettingsV2(contextWithBodyAndIntent(
        "{\"ui\":{\"mode\":\"simple\",\"theme\":\"dark\"}}", "client-a:1"));

    assertEquals("advanced", store.load().getMode());
    assertEquals("dark", store.load().getTheme(),
        "a stale mode token must not discard unrelated fields in the same partial patch");
  }
}
