/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.BulkWorkPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec E revision 4, check E-P3a (step E7b). On P16_PLUS facts the Engine's bulk-work gate takes the
 * plan's profile: while chat generates, bulk work runs on the generation duty floor (50%, SLOWED
 * [chat_generating]); external memory use with chat idle reports EXTERNAL_MEMORY to the chat
 * controller; with neither, nothing slows and nothing is reported.
 */
@Timeout(120)
final class DevicePlanP16PacingTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 16 * GIB;

  @AfterEach
  void restore() throws Exception {
    DevicePlanTestSupport.uninstall();
    ConfigStoreRebuilder.rememberAutoDetected(Map.of());
  }

  @Test
  void p16PlusGeneratingSlowsBulkAndExternalPressureIsReported() throws Exception {
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        30_000L, 5_000);
    var reading = new AtomicReference<>(sample(10 * GIB, 300 * MIB));
    var tick = new AtomicReference<Runnable>();
    var signals = new CopyOnWriteArrayList<PressureSignal>();
    var profiles = new CopyOnWriteArrayList<Object>();
    try {
      ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "99"));
      UiSettings settings = new UiSettings();
      ConfigStore store = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
      Object state = DevicePlanTestSupport.install(store, DevicePlanTestSupport.device(16_376),
          DevicePlanTestSupport.DEFAULT_WEIGHTS, Set.of(ChatModelProfile.STANDARD));
      ConfigStoreRebuilder.rebuild(store, settings);
      DevicePlanTestSupport.follow(root, state);

      ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
      when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
        tick.set(inv.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      root.startGpuResidency(new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return reading.get();
        }

        @Override public void close() {}
      }, manual, () -> {});
      var source = (BootstrapInferenceFactory.GenerationLifetimeSource) root.executors();
      source.bindPressureReceiver(signals::add);
      bindProfileReceiver(source, profiles);
      BulkWorkGate gate = root.bulkWork();

      assertEquals(BulkWorkGate.Profile.P16_PLUS, gate.snapshot().profile(),
          "the gate takes the plan's profile");

      // Neither: no slowing and no report.
      for (int i = 0; i < 3; i++) tick.get().run();
      assertEquals(BulkWorkGate.Mode.RUN, gate.verdict().mode());
      assertTrue(gate.verdict().reasons().isEmpty(), gate.verdict().toString());
      assertFalse(gate.held());
      assertTrue(signals.isEmpty(), signals.toString());

      // Generating: the duty floor at the generation duty, SLOWED [chat_generating].
      gate.setGenerating(true);
      assertEquals(BulkWorkGate.Mode.DUTY_FLOOR, gate.verdict().mode());
      assertEquals(List.of(BulkWorkGate.Reason.CHAT_GENERATING), gate.verdict().reasons());
      assertTrue(gate.dutyFloor());
      assertEquals(BulkWorkPolicy.GENERATION_DUTY_PCT, gate.generationDutyPct());
      assertEquals(BulkWorkPolicy.GENERATION_MAX_DEBT_MS, gate.generationMaxDebtMs());
      assertTrue(signals.isEmpty(), "generation alone reports no pressure");
      gate.setGenerating(false);

      // External pressure with chat idle: the chat controller hears EXTERNAL_MEMORY.
      reading.set(sample(300 * MIB, 9 * GIB));
      for (int i = 0; i < 3; i++) tick.get().run();
      assertEquals(List.of(PressureSignal.EXTERNAL_MEMORY), signals);
      assertTrue(gate.held(), "bulk GPU work holds while external use holds");
      assertEquals(List.of(BulkWorkGate.Reason.GPU_BUSY_EXTERNAL), gate.verdict().reasons());
      assertEquals(BulkWorkGate.Profile.P16_PLUS, gate.snapshot().profile(),
          "pressure never changes the profile");
      if (!profiles.isEmpty()) {
        assertEquals(List.of("P16_PLUS"), profiles.stream().map(Object::toString).toList(),
            "the chat controller takes the plan's profile once");
      }
    } finally {
      root.close();
      root.processResources().close();
    }
  }

  /** The chat controller's profile input, on a build that has one. */
  private static void bindProfileReceiver(Object source, List<Object> profiles) throws Exception {
    java.lang.reflect.Method bind;
    try {
      bind = BootstrapInferenceFactory.GenerationLifetimeSource.class.getMethod(
          "bindProfileReceiver", java.util.function.Consumer.class);
    } catch (NoSuchMethodException absent) {
      return;
    }
    bind.invoke(source, (java.util.function.Consumer<Object>) profiles::add);
  }

  private static GpuMemorySample sample(long free, long external) {
    long used = TOTAL - free;
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
        Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
        Optional.of("NVIDIA GeForce RTX 4080"), OptionalLong.of(TOTAL), OptionalLong.of(used),
        Map.of(ProcessHandle.current().pid(), new ProcessMemory(used - external, 0L)),
        Instant.now());
  }
}
