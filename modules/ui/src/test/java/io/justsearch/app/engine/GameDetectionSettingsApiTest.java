/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.ui.api.EffectiveConfigController;
import io.justsearch.ui.api.SettingsController;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Settings API -> committed config -> running Engine sampler, with E4's fake game signals. */
@Timeout(180)
final class GameDetectionSettingsApiTest {
  private static final long GIB = 1024L * 1024L * 1024L;
  private static final long TOTAL = 12 * GIB;
  private static final long GAME_PID = 4_000_000_001L;
  private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

  @Test
  void settingsApiTurnsDetectionOffAndBackOnWithoutRestart(@TempDir Path tmp) throws Exception {
    try (var fixture = new Fixture(tmp)) {
      var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class), 30_000L, 5_000);
      var tick = new AtomicReference<Runnable>();
      try {
        root.bulkWork().setProfile(BulkWorkGate.Profile.P12);
        root.startGpuResidency(reader(), userContext(new AtomicBoolean()), manual(tick), () -> {});
        assertTrue(root.gpuResidency().latest().game());
        assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE), root.bulkWork().verdict().reasons());

        fixture.post("{\"llm\":{\"gameDetection\":\"off\"}}");
        tick.get().run();
        assertFalse(root.bulkWork().held(), "settings API off resumes bulk on the next tick without a restart");
        assertFalse(root.gpuResidency().latest().game());
        assertEquals("off", fixture.config.get().ai().gpuGameDetection());
        assertEquals("off", JSON.readTree(JSON.writeValueAsString(fixture.store.load())).path("gpuGameDetection").asString());
        fixture.assertReadout("off");

        // An omitted field preserves the explicit override in the accepted partial patch.
        fixture.post("{\"ui\":{\"theme\":\"dark\"}}");
        tick.get().run();
        assertFalse(root.bulkWork().held());
        assertEquals("off", fixture.config.get().ai().gpuGameDetection());

        fixture.post("{\"llm\":{\"gameDetection\":\"auto\"}}");
        tick.get().run();
        assertTrue(root.gpuResidency().latest().game());
        assertEquals(List.of(BulkWorkGate.Reason.GAME_MODE), root.bulkWork().verdict().reasons());
        fixture.assertReadout("auto");
      } finally {
        root.close();
        root.processResources().close();
      }
    }
  }

  @Test
  void settingsApiRejectsInvalidDetectionWithoutCommit(@TempDir Path tmp) throws Exception {
    try (var fixture = new Fixture(tmp)) {
      Context request = fixture.request("{\"llm\":{\"gameDetection\":\"disabled\"}}");
      var status = new java.util.concurrent.atomic.AtomicInteger(200);
      when(request.status(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
        status.set(inv.getArgument(0));
        return request;
      });
      fixture.controller.handleUpdateSettingsV2(request);
      assertEquals(400, status.get(), "invalid game-detection values must be refused");
      assertEquals(0, fixture.store.inspect().witness().acceptedRevision());
      assertEquals("auto", fixture.config.get().ai().gpuGameDetection());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final UiSettingsStore store;
    final ConfigStore config;
    final ConfigStore prior = ConfigStore.globalOrNull();
    final io.justsearch.app.observability.operations.SqliteOperationStore operations;
    final SettingsController controller;

    Fixture(Path tmp) throws Exception {
      store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
      config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
      ConfigStore.setGlobal(config);
      operations = new io.justsearch.app.observability.operations.SqliteOperationStore(tmp.resolve("operations.db"));
      var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("Game detection must apply without restarting the Engine"); },
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("Settings committed"), () -> false);
      var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
          java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      controller = new SettingsController(store, tmp, null,
          new io.justsearch.app.services.settings.SettingsServiceImpl(store, runner));
    }

    Context request(String body) {
      var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
      tree.set("witness", JSON.valueToTree(store.inspect().witness()));
      tree.put("operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()));
      Context ctx = mock(Context.class);
      when(ctx.body()).thenReturn(JSON.writeValueAsString(tree));
      when(ctx.path()).thenReturn("/api/settings/v2");
      when(ctx.method()).thenReturn(io.javalin.http.HandlerType.POST);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
      return ctx;
    }

    void post(String body) {
      Context ctx = request(body);
      controller.handleUpdateSettingsV2(ctx);
      var response = ArgumentCaptor.forClass(Object.class);
      verify(ctx).json(response.capture());
      assertEquals("COMPLETE", JSON.readTree(JSON.writeValueAsString(response.getValue())).path("state").asString());
    }

    void assertReadout(String value) {
      Context get = mock(Context.class);
      when(get.json(any())).thenReturn(get);
      controller.handleGetSettingsV2(get);
      var settings = ArgumentCaptor.forClass(Object.class);
      verify(get).json(settings.capture());
      assertEquals(value, JSON.readTree(JSON.writeValueAsString(settings.getValue())).path("llm").path("gameDetection").asString());
      Context debug = mock(Context.class);
      when(debug.json(any())).thenReturn(debug);
      new EffectiveConfigController(() -> 8080, store, null, null, null, config).handleGetEffectiveConfig(debug);
      var effective = ArgumentCaptor.forClass(Object.class);
      verify(debug).json(effective.capture());
      var tree = JSON.readTree(JSON.writeValueAsString(effective.getValue()));
      boolean found = false;
      for (var entry : tree.path("resolvedConfig")) {
        if ("justsearch.gpu.game_detection".equals(entry.path("key").asString())) {
          assertEquals(value, entry.path("value").asString());
          assertEquals("settings.json", entry.path("source").asString());
          assertEquals(300, entry.path("ordinal").asInt());
          found = true;
        }
      }
      assertTrue(found, "effective-config includes the committed game-detection override");
    }

    @Override public void close() throws Exception {
      operations.close();
      ConfigStore.restoreGlobal(config, prior);
    }
  }

  private static ScheduledExecutorService manual(AtomicReference<Runnable> tick) {
    ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
    when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
      tick.set(inv.getArgument(0));
      return mock(ScheduledFuture.class);
    });
    return manual;
  }

  private static GpuMemoryReader reader() {
    return new GpuMemoryReader() {
      @Override public GpuMemorySample sampleNow() {
        return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
            Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
            Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL),
            OptionalLong.of(4 * GIB),
            Map.of(ProcessHandle.current().pid(), new ProcessMemory(2 * GIB, 0L), GAME_PID,
                new ProcessMemory(2 * GIB, 0L)),
            Instant.now());
      }

      @Override public void close() {}
    };
  }

  private static UserContextReader userContext(AtomicBoolean closed) {
    return new UserContextReader() {
      @Override public UserContextSample sampleNow() {
        return new UserContextSample(NotificationState.RUNNING_D3D_FULL_SCREEN,
            Optional.of(false), OptionalLong.of(GAME_PID), true, false);
      }

      @Override public void close() {
        closed.set(true);
      }
    };
  }

}
