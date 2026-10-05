/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code operating} block on the real status path: it is assembled in the same pass as the
 * rest of the response, so its chat reason code equals {@code components.generative.reason_code}
 * and its assembly time equals {@code observed_at}.
 */
@DisplayName("/api/status operating block is assembled in the same pass")
final class StatusOperatingStateTest {

  private final StatusComponentFixture components = new StatusComponentFixture();

  @AfterEach
  void close() {
    components.close();
  }

  @Test
  @DisplayName("chat projects the ENGINE axis and the same generative component the response shows")
  void chatMatchesGenerativeComponentOfSamePass() {
    components.transition(
        "generative", ComponentState.FAILED, "inference.crashed", "inference crashed");
    StatusLifecycleHandler handler = handler("Down");

    StatusResponse response = handler.buildStatusSnapshot();
    OperatingStateView operating = response.operating();

    assertNotNull(operating, "the status response carries the operating block");
    assertEquals(ChatState.FAILED, operating.chat().state());
    assertEquals(
        response.components().generative().reason_code(), operating.chat().reasonCode());
    assertEquals("inference.crashed", operating.chat().reasonCode());
    assertEquals(response.observedAt(), operating.assembledAt());
    assertEquals(List.of(), operating.degradations());
  }

  @Test
  @DisplayName("a healthy engine is READY, and repeated reads of the same state keep seq")
  void healthyEngineIsReadyAndSeqIsStable() {
    StatusLifecycleHandler handler = handler("Healthy");

    StatusResponse first = handler.buildStatusSnapshot();
    StatusResponse second = handler.buildStatusSnapshot();

    assertEquals(ChatState.READY, first.operating().chat().state());
    assertEquals(first.operating().seq(), second.operating().seq());
  }

  @Test
  @DisplayName("a wired GPU memory reader fills operating.gpu after a background first sample; closing it returns gpu to unknown")
  void gpuReaderFillsTheBlock() throws InterruptedException {
    StatusLifecycleHandler handler = handler("Healthy");
    long self = ProcessHandle.current().pid();
    AtomicInteger closes = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    GpuMemoryReader reader =
        new GpuMemoryReader() {
          @Override
          public GpuMemorySample sampleNow() {
            entered.countDown();
            try {
              // Bounded, so a regression that waits for this sample fails instead of hanging.
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the first sample");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
                Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
                Optional.of("RTX"), OptionalLong.of(12_000L), OptionalLong.of(9_000L),
                Map.of(self, new GpuMemorySample.ProcessMemory(4_000L, 50L),
                    999_999_999L, new GpuMemorySample.ProcessMemory(5_000L, 0L)),
                Instant.now());
          }

          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    handler.setGpuMemoryReader(reader);

    // The first sample runs on its own thread; a status pass while it is in flight does not wait.
    assertTrue(entered.await(10, TimeUnit.SECONDS), "the first sample started off the status path");
    // Preemptive: a status pass that waited for the first sample would block on it.
    OperatingGpuView during = assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(5), () -> handler.buildStatusSnapshot().operating().gpu());
    assertEquals(OperatingGpuView.unknown(), during);
    release.countDown();

    OperatingGpuView gpu = handler.buildStatusSnapshot().operating().gpu();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (gpu.source() != GpuMemorySource.PDH && System.nanoTime() < deadline) {
      Thread.sleep(10);
      gpu = handler.buildStatusSnapshot().operating().gpu();
    }
    assertEquals(GpuMemorySource.PDH, gpu.source());
    assertEquals(3_000L, gpu.adapterFreeBytes());
    assertEquals(4_000L, gpu.ourDedicatedBytes(), "this JVM is the Engine");
    assertEquals(5_000L, gpu.externalUsedBytes());

    handler.closeGpuMemoryReader();
    assertEquals(1, closes.get());
    assertEquals(OperatingGpuView.unknown(), handler.buildStatusSnapshot().operating().gpu());
  }

  private StatusLifecycleHandler handler(String engineState) {
    var graph = components.capabilities();
    StatusLifecycleHandler handler = new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> new InferenceRuntimeView(
            "OFFLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L), true, engineState,
            "", "", ""),
        null,
        null,
        null,
        Instant.now().minusSeconds(60),
        () -> "OK",
        null,
        null,
        null,
        graph.worker(),
        graph.inference());
    components.attach(handler);
    return handler;
  }
}
