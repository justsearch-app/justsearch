/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real sampler and full-snapshot conditional publication, not a copied predicate. */
final class IndexReadinessPublicationTest {
  @TempDir Path indexBase;

  @Test
  void attachedClientEstablishesReadinessWhileCapabilityIsPending() {
    try (var fixture = fixture()) {
      assertFalse(fixture.capability.available());
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
      verify(fixture.client, times(1)).getWorkerOperationalView(any());
    }
  }

  @Test
  void optionalCompatibilityDiagnosticDoesNotBlockEssentialIndexReadiness() {
    try (var fixture = fixture()) {
      var incompatible = WorkerOperationalViewBuilder.from(view(true)).withCompatibility(
          new io.justsearch.app.api.status.CompatibilityStatusView(
              "BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "",
              "COMPATIBLE", true, "embedding_legacy"));
      when(fixture.client.getWorkerOperationalView(any())).thenReturn(incompatible);
      fixture.handler.sampleAndBuildStatusSnapshot();
      var response = fixture.handler.buildStatusSnapshot();
      assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
      assertEquals("DEGRADED", response.readiness().components().get("indexServing").state());
      assertEquals("index.embedding_legacy",
          response.readiness().components().get("indexServing").reasonCode());
      verify(fixture.client, times(1)).getWorkerOperationalView(any());
    }
  }

  @Test
  void successfulButSlowContactCannotPublishAFreshReadyState() {
    try (var fixture = fixture()) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      when(fixture.client.getWorkerOperationalView(any())).thenAnswer(invocation -> {
        fixture.clock.addAndGet(31_000);
        return view(true);
      });
      var result = fixture.handler.sampleAndBuildStatusSnapshot();
      assertTrue(result.meta().workerRpcStale());
      assertEquals(ComponentState.UNAVAILABLE,
          fixture.components.handle("index").snapshot().state());
      verify(fixture.client, times(2)).getWorkerOperationalView(any());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"api", "contact", "health", "freshness"})
  void eachFailedConjunctionInputDemotesReady(String missing) {
    try (var fixture = fixture()) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
      switch (missing) {
        case "api" -> fixture.components.handle("api").transition(ComponentState.ABSENT, null, null);
        case "contact" -> when(fixture.client.getWorkerOperationalView(any()))
            .thenThrow(new IllegalStateException("contact lost"));
        case "health" -> when(fixture.client.getWorkerOperationalView(any())).thenReturn(view(false));
        case "freshness" -> fixture.clock.addAndGet(31_000);
        default -> throw new AssertionError(missing);
      }
      if ("freshness".equals(missing)) {
        fixture.handler.buildStatusSnapshot();
        verify(fixture.client, times(1)).getWorkerOperationalView(any());
      } else {
        fixture.handler.sampleAndBuildStatusSnapshot();
        verify(fixture.client, times(2)).getWorkerOperationalView(any());
      }
      assertEquals(ComponentState.UNAVAILABLE,
          fixture.components.handle("index").snapshot().state());
      var result = fixture.components.handle("index").snapshot();
      assertEquals("contact".equals(missing) || "freshness".equals(missing)
          ? "worker.lost" : "worker.unavailable", result.reasonCode());
      String expectedEvidence = switch (missing) {
        case "api" -> "apiReady=false";
        case "contact" -> "contact lost";
        case "health" -> "indexHealthy=false";
        case "freshness" -> "contactFresh=false";
        default -> throw new AssertionError(missing);
      };
      assertTrue(result.evidence().contains(expectedEvidence));
    }
  }

  @ParameterizedTest
  @EnumSource(value = ComponentState.class, names = {"ABSENT", "STARTING", "RELOADING", "FAILED"})
  void failedContactPreservesPhysicalState(ComponentState state) {
    try (var fixture = fixture()) {
      fixture.components.handle("index").transition(state, "physical.reason", "physical evidence");
      when(fixture.client.getWorkerOperationalView(any()))
          .thenThrow(new IllegalStateException("contact lost"));
      fixture.handler.sampleAndBuildStatusSnapshot();
      var result = fixture.components.handle("index").snapshot();
      assertEquals(state, result.state());
      assertEquals("physical.reason", result.reasonCode());
      assertEquals("physical evidence", result.evidence());
    }
  }

  @Test
  void cachedHealthySampleCannotPromoteANewPhysicalStart() {
    try (var fixture = fixture()) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      fixture.components.handle("index").transition(ComponentState.STARTING, "worker.starting", null);
      fixture.handler.buildStatusSnapshot();
      assertEquals(ComponentState.STARTING, fixture.components.handle("index").snapshot().state());
      verify(fixture.client, times(1)).getWorkerOperationalView(any());
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
    }
  }

  @ParameterizedTest
  @EnumSource(value = ComponentState.class, names = {"ABSENT", "RELOADING"})
  void healthyIncumbentCannotErasePhysicalOwnership(ComponentState state) {
    try (var fixture = fixture()) {
      fixture.components.handle("index").transition(state, "physical.reason", null);
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals(state, fixture.components.handle("index").snapshot().state());
    }
  }

  @ParameterizedTest
  @EnumSource(value = ComponentState.class, names = {"FAILED", "UNAVAILABLE"})
  void freshHealthyObservationCanRecoverRetainedClient(ComponentState state) {
    try (var fixture = fixture()) {
      fixture.components.handle("index").transition(state, "worker.lost", null);
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
    }
  }

  @Test
  void freshRequestsShareObservationOwnershipWhileCachedReadsRemainNonblocking() throws Exception {
    try (var fixture = fixture(); var threads = java.util.concurrent.Executors.newFixedThreadPool(3)) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var secondStarted = new java.util.concurrent.CountDownLatch(1);
      var firstThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      when(fixture.client.getWorkerOperationalView(any())).thenAnswer(invocation -> {
        if (calls.incrementAndGet() == 1) {
          firstThread.set(Thread.currentThread());
          entered.countDown();
          assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
          throw new IllegalStateException("older failed contact");
        }
        return view(true);
      });
      var first = threads.submit(fixture.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var second = threads.submit(() -> {
          secondThread.set(Thread.currentThread());
          secondStarted.countDown();
          return fixture.handler.sampleAndBuildStatusSnapshot();
        });
        assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        java.lang.management.ThreadInfo info;
        do {
          info = threadBean.getThreadInfo(secondThread.get().threadId());
          if (info != null && info.getLockOwnerId() == firstThread.get().threadId()) break;
          if (second.isDone()) break;
          Thread.yield();
        } while (System.nanoTime() < deadline);
        assertTrue(info != null && info.getLockOwnerId() == firstThread.get().threadId(),
            "the second fresh request must contend on the actual first sampler owner");
        var cached = threads.submit(fixture.handler::buildStatusSnapshot)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(cached.meta().workerRpcStale(), "cached reads must not wait for the stalled RPC");
        assertEquals(1, calls.get());
        release.countDown();
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
        assertFalse(fixture.handler.buildStatusSnapshot().meta().workerRpcStale());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void staleReadDemotesBlockedSamplerAndQueuesFreshRecovery() throws Exception {
    try (var fixture = fixture();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var trigger = new io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger(executors)) {
      var initial = new java.util.concurrent.CountDownLatch(1);
      var blocked = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var recovered = new java.util.concurrent.CountDownLatch(1);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var superseded = new java.util.concurrent.atomic.AtomicReference<io.justsearch.app.api.status.StatusResponse>();
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      when(fixture.client.getWorkerOperationalView(any())).thenAnswer(invocation -> {
        if (calls.incrementAndGet() == 2) {
          blocked.countDown();
          assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        return view(true);
      });
      trigger.wireTo(fixture.components);
      trigger.attach(() -> {
        try {
          var response = fixture.handler.sampleAndBuildStatusSnapshot();
          if (calls.get() == 2) superseded.set(response);
        } catch (RuntimeException | Error error) {
          failure.set(error);
        } finally {
          if (calls.get() == 1) initial.countDown();
          if (calls.get() >= 3) recovered.countDown();
        }
      });
      try {
        assertTrue(initial.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var field = trigger.getClass().getDeclaredField("executor");
        field.setAccessible(true);
        var samplerExecutor = (java.util.concurrent.ExecutorService) field.get(trigger);
        samplerExecutor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, calls.get(), "initial READY publication must not enqueue a hidden sample");
        fixture.clock.addAndGet(31_000);
        trigger.request();
        assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // This must complete before release: the cached path does not acquire the RPC lock.
        var stale = fixture.handler.buildStatusSnapshot();
        assertTrue(stale.meta().workerRpcStale());
        assertEquals(ComponentState.UNAVAILABLE, fixture.components.handle("index").snapshot().state());
        release.countDown();
        assertTrue(recovered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertNull(failure.get());
        samplerExecutor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3, calls.get(), "exactly one follow-up, with no self-publication feedback");
        assertTrue(superseded.get().meta().workerRpcStale(), "invalidated RPC cannot become the cache");
        assertEquals(ComponentState.READY, fixture.components.handle("index").snapshot().state());
        assertFalse(fixture.handler.buildStatusSnapshot().meta().workerRpcStale());
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"api", "index"})
  void observationOvertakenByAComponentTransitionCannotPublish(String owner) {
    try (var fixture = fixture()) {
      when(fixture.client.getWorkerOperationalView(any())).thenAnswer(invocation -> {
        if ("api".equals(owner)) {
          fixture.components.handle("api").transition(ComponentState.ABSENT, null, null);
          fixture.components.handle("api").transition(ComponentState.READY, null, null);
        } else {
          fixture.components.handle("index").transition(ComponentState.FAILED, "worker.failed", null);
        }
        return view(true);
      });
      fixture.handler.sampleAndBuildStatusSnapshot();
      assertEquals("api".equals(owner) ? ComponentState.STARTING : ComponentState.FAILED,
          fixture.components.handle("index").snapshot().state());
      verify(fixture.client, times(1)).getWorkerOperationalView(any());
    }
  }

  private Fixture fixture() {
    var components = TestEngineComponents.fourComponents();
    components.handle("api").transition(ComponentState.READY, null, null);
    components.handle("index").transition(ComponentState.STARTING, "worker.starting", null);
    var capability = new RegistryBackedCapability(components, "index", "worker");
    var client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(any())).thenReturn(view(true));
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.hasClient()).thenReturn(true);
    when(server.client()).thenReturn(client);
    var handler = new StatusLifecycleHandler(
        mock(OnlineAiService.class), mock(io.justsearch.agent.api.AgentService.class), () -> null,
        server, null, indexBase, Instant.now(), () -> "OK", null, null, null,
        capability, new InferenceCapability(false));
    handler.setIndexComponent(components, components.handle("index"));
    var clock = new AtomicLong(System.currentTimeMillis());
    handler.setClockForTesting(clock::get);
    return new Fixture(components, capability, client, handler, clock);
  }

  private static WorkerOperationalView view(boolean healthy) {
    return WorkerOperationalViewBuilder.from(WorkerOperationalView.fallback("SERVING"))
        .withCore(new CoreIndexView(healthy, 10, 0, "SERVING", 0, 0));
  }

  private record Fixture(TestEngineComponents components, RegistryBackedCapability capability,
      KnowledgeClient client, StatusLifecycleHandler handler, AtomicLong clock) implements AutoCloseable {
    @Override public void close() { components.close(); }
  }
}
