/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.BackgroundRunService;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class BackgroundWorkLifetimeTest {
  @Test
  void scheduledChildUsesFreshIdentityAndClosesOnlyAfterActualRunUnwinds() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var caller = TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND);
    EngineContext stale;
    try (var original = admission.admit(caller, false)) { stale = original.context(); }
    var started = new CompletableFuture<EngineContext>();
    var completed = new CompletableFuture<Void>();
    var release = new CountDownLatch(1);
    var agent = mock(AgentService.class);
    doAnswer(call -> {
      EngineContext context = call.getArgument(3);
      try (var observed = admission.attach(context)) {
        observed.onCompletion(() -> completed.complete(null));
        started.complete(context);
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release missing");
      }
      throw new IllegalStateException("deliberate run failure still releases its child");
    }).when(agent).runAgent(any(), any(), eq(true), any());
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(agent, executors, admission);
    try {
      background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "probe"))),
          Duration.ZERO, stale);
      var child = started.get(3, TimeUnit.SECONDS);
      assertNotEquals(stale.workId(), child.workId());
      assertEquals(caller.withWorkId(child.workId().orElseThrow()), child);
      assertThrows(io.justsearch.app.api.EngineAdmissionException.class, () -> admission.attach(caller));
      release.countDown();
      completed.get(3, TimeUnit.SECONDS);
      assertThrows(io.justsearch.app.api.EngineAdmissionException.class, () -> admission.attach(child));
      try (var next = admission.attach(caller)) { assertTrue(next.context().workId().isPresent()); }
    } finally {
      release.countDown();
      background.shutdown();
      executors.close();
    }
  }

  @Test
  void productionRegistryRefusesBackgroundTimersPastCanonicalQueueBound() {
    var agent = mock(AgentService.class);
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(agent, executors);
    try {
      int queueCapacity = executors.limits(EngineExecutorSpec.Kind.BACKGROUND).maxQueue();
      var registration = executors.snapshot().registrations().stream()
          .filter(row -> row.spec().name().equals("head.background-agent-run"))
          .findFirst()
          .orElseThrow();
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec().kind());
      assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec().mode());
      assertEquals(1, registration.spec().threadCount());
      assertEquals(queueCapacity, registration.spec().queueCapacity());
      assertEquals(1, registration.spec().maxInstances());
      for (int i = 0; i < queueCapacity; i++) {
        background.schedule(
            AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "queued"))),
            Duration.ofDays(1),
            TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
      }

      EngineExecutorRejectedException refusal =
          assertThrows(
              EngineExecutorRejectedException.class,
              () ->
                  background.schedule(
                      AgentRequest.singleTurn(
                          List.of(Map.of("role", "user", "content", "refused"))),
                      Duration.ofDays(1),
                      TestRequestContexts.browser()
                          .withUrgency(EngineContext.Urgency.BACKGROUND)));
      assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT, refusal.reason());
      verifyNoInteractions(agent);
    } finally {
      background.shutdown();
      executors.close();
    }
  }

  @Test
  void shutdownCancelsPendingBackgroundTimerWithoutRunningItAndIsIdempotent() {
    var agent = mock(AgentService.class);
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(agent, executors);
    try {
      background.schedule(
          AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "pending"))),
          Duration.ofDays(1),
          TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
      background.shutdown();
      background.shutdown();

      verifyNoInteractions(agent);
      assertTrue(executors.snapshot().registrations().isEmpty());
    } finally {
      background.shutdown();
      executors.close();
    }
  }
}
