/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Focused proof of the generic component-recovery arm and its single physical slot. */
final class ComponentRecoveryMonitorTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @Test
  void failedSourceRestorationUsesRemainingBudgetWithoutRetryingMissingModels() throws Exception {
    for (boolean automatic : new boolean[] {true, false}) {
      try (var components = new TestEngineComponents()) {
        var encoders = components.register(spec("encoders", false, Duration.ZERO, 2));
        encoders.transition(ComponentState.UNAVAILABLE, null, "missing_roles=EMBEDDING");
        var now = new AtomicLong(1_000L);
        var attempts = new AtomicInteger();
        var escalations = new AtomicInteger();
        var actionFailure = new AtomicReference<Throwable>();
        ComponentRecoveryAction action = request -> {
          assertTrue(request.begin());
          attempts.incrementAndGet();
          return failed(request, encoders, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              "Exact A remains unavailable");
        };
        try (var monitor = monitor(components, bootstrapWithoutClient(),
            new BootRecoveryPolicy(2, 10_000L, 60_000L), now::get)) {
          monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
              ignored -> escalations.incrementAndGet());
          monitor.tick();
          assertEquals(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
              monitor.requestComponentRecovery("encoders"));
          assertEquals(0, attempts.get());
          var admitted = encoders.tryBeginRecovery(encoders.snapshot(),
              LifecycleReasonCode.COMPONENT_RECOVERING.code(), "Mandatory A restoration")
              .orElseThrow();
          assertTrue(encoders.transitionIfUnchanged(admitted, ComponentState.UNAVAILABLE,
              LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(), "B refused; A restore failed"));
          if (automatic) {
            monitor.tick();
            awaitIdle(monitor);
            assertEquals(0, attempts.get(), "attempt 2 must observe its 20 second backoff");
            now.addAndGet(19_999L);
            monitor.tick();
            awaitIdle(monitor);
            assertEquals(0, attempts.get(), "attempt 2 must not start before its backoff");
            now.incrementAndGet();
            monitor.tick();
          } else {
            assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
                monitor.requestComponentRecovery("encoders"));
          }
          awaitIdle(monitor);
          assertNoAsyncFailure(actionFailure);
          assertEquals(1, attempts.get());
          assertEquals(2, encoders.snapshot().recoveryAttempts());
          assertEquals(ComponentRecoveryAuthority.Outcome.EXHAUSTED,
              monitor.requestComponentRecovery("encoders"));
          monitor.tick();
          assertEquals(0, escalations.get());
        }
      }
    }
  }

  @Test
  void unavailableNonEncoderDoesNotInheritSourceRestorationRecovery() {
    try (var components = new TestEngineComponents()) {
      var generative = components.register(spec("generative", false, Duration.ZERO, 2));
      generative.transition(ComponentState.UNAVAILABLE,
          LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(), "Unavailable owner");
      var attempts = new AtomicInteger();
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          attempts.incrementAndGet();
          return ComponentRecoveryAction.Result.REFUSED;
        }), ignored -> {});
        monitor.tick();
        assertEquals(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
            monitor.requestComponentRecovery("generative"));
        assertEquals(0, attempts.get());
      }
    }
  }

  @Test
  void actionlessEssentialApiEscalatesAutomaticallyWithoutInventingLocalAttempts() {
    try (var components = new TestEngineComponents()) {
      var api = components.register(spec("api", true, Duration.ZERO, 0));
      api.transition(ComponentState.FAILED, "api.failed", "listener failed");
      var escalations = new AtomicInteger();
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, null), row -> {
          assertEquals(api.snapshot(), row);
          escalations.incrementAndGet();
        });
        assertEquals(ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE,
            monitor.requestComponentRecovery("api"));
        assertEquals(0, escalations.get());
        monitor.tick();
        monitor.tick();
        assertEquals(1, escalations.get());
        assertEquals(0, api.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void essentialIndexFatalReasonsEscalateOnFirstTickWithoutAdmittingRecovery() {
    for (var reason : new LifecycleReasonCode[] {
        LifecycleReasonCode.INDEX_CORRUPT,
        LifecycleReasonCode.INDEX_SCHEMA_OPEN_REFUSED}) {
      try (var components = new TestEngineComponents()) {
        var index = components.register(spec("index", true, Duration.ZERO, 2));
        index.transition(ComponentState.FAILED, reason.code(), "observable fatal index refusal");
        var physicalCalls = new AtomicInteger();
        var escalated = new AtomicReference<EngineComponentSnapshot.Component>();
        ComponentRecoveryAction action = request -> {
          physicalCalls.incrementAndGet();
          throw new AssertionError("a known fatal index reason must escalate before recovery");
        };
        var bootstrap = mock(KnowledgeServerBootstrap.class);
        when(bootstrap.hasClient()).thenReturn(true);
        when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
        try (var monitor = monitor(components, bootstrap)) {
          monitor.componentRecoveryBindings(bindings(components, action), escalated::set);

          monitor.tick();

          assertEquals(index.snapshot(), escalated.get());
          assertEquals(0, physicalCalls.get());
          assertEquals(0, index.snapshot().recoveryAttempts());
        }
      }
    }
  }

  @Test
  void optionalIndexFatalReasonsUseLocalRecoveryWithoutEscalating() throws Exception {
    for (var reason : new LifecycleReasonCode[] {
        LifecycleReasonCode.INDEX_CORRUPT,
        LifecycleReasonCode.INDEX_SCHEMA_OPEN_REFUSED}) {
      try (var components = new TestEngineComponents()) {
        var optional = components.register(spec("index", false, Duration.ZERO, 2));
        optional.transition(ComponentState.FAILED, reason.code(), "optional fatal index row");
        var physicalCalls = new AtomicInteger();
        var escalations = new AtomicInteger();
        ComponentRecoveryAction action = request -> {
          assertTrue(request.begin());
          physicalCalls.incrementAndGet();
          return recovered(request, optional, "optional owner ready", "recovered");
        };
        try (var monitor = monitor(components, bootstrapWithoutClient())) {
          monitor.componentRecoveryBindings(bindings(components, action),
              ignored -> escalations.incrementAndGet());

          monitor.tick();
          awaitIdle(monitor);

          assertEquals(1, physicalCalls.get());
          assertEquals(1, optional.snapshot().recoveryAttempts());
          assertEquals(ComponentState.READY, optional.snapshot().state());
          assertEquals(0, escalations.get());
        }
      }
    }
  }

  @Test
  void bindingsMustCoverEveryRegisteredComponentIncludingOptionalOnes() {
    try (var components = new TestEngineComponents()) {
      var optional = components.register(spec("generative", false, Duration.ZERO, 2));
      var required = components.register(spec("api", true, Duration.ZERO, 2));
      var bootstrap = bootstrapWithoutClient();
      try (var monitor = monitor(components, bootstrap)) {
        var missingOptional = Map.of("api", new ComponentRecoveryBinding(required, request ->
            ComponentRecoveryAction.Result.REFUSED));
        assertThrows(IllegalArgumentException.class,
            () -> monitor.componentRecoveryBindings(missingOptional, ignored -> {}));

        try (var foreign = new TestEngineComponents()) {
          var foreignApi = foreign.register(spec("api", true, Duration.ZERO, 2));
          var foreignBindings = bindings(components, null);
          foreignBindings.put("api", new ComponentRecoveryBinding(foreignApi, null));
          assertThrows(IllegalArgumentException.class,
              () -> monitor.componentRecoveryBindings(foreignBindings, ignored -> {}));
        }

        var noOwner = bindings(components, null);
        monitor.componentRecoveryBindings(noOwner, ignored -> {});
        optional.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
            "optional owner missing");
        assertEquals(ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE,
            monitor.requestComponentRecovery("generative"));
        assertEquals(0, optional.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void oneHeldOptionalAttemptSharesSlotWithAutomaticAndManualRequests() throws Exception {
    try (var components = new TestEngineComponents()) {
      var optional = components.register(spec("generative", false, Duration.ofMillis(1), 2));
      optional.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return failed(request, optional, "physical retry failed", "retry failed");
      };
      var bootstrap = bootstrapWithoutClient();
      try (var monitor = monitor(components, bootstrap)) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("generative"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread.sleep(5);
        monitor.tick();
        assertEquals(ComponentState.FAILED, optional.snapshot().state());
        monitor.tick();
        assertEquals(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
            monitor.requestComponentRecovery("generative"));
        release.countDown();
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals(1, optional.snapshot().recoveryAttempts());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void everyStartingComponentGetsItsOwnDeadlineObservation() throws Exception {
    try (var components = new TestEngineComponents()) {
      var required = components.register(spec("api", true, Duration.ofMillis(1), 2));
      var optional = components.register(spec("encoders", false, Duration.ofMillis(1), 2));
      required.transition(ComponentState.STARTING, LifecycleReasonCode.INDEX_STARTING.code(),
          "api bind");
      optional.transition(ComponentState.STARTING, LifecycleReasonCode.INDEX_STARTING.code(),
          "encoder load");
      var bootstrap = bootstrapWithoutClient();
      try (var monitor = monitor(components, bootstrap)) {
        monitor.componentRecoveryBindings(bindings(components, null), ignored -> {});
        Thread.sleep(5);
        monitor.tick();
        assertEquals(ComponentState.FAILED, required.snapshot().state());
        assertEquals(ComponentState.FAILED, optional.snapshot().state());
      }
    }
  }

  @Test
  void automaticDispatchAdvancesPastARefusedOptionalOwner() throws Exception {
    try (var components = new TestEngineComponents()) {
      var encoders = components.register(spec("encoders", false, Duration.ZERO, 2));
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      encoders.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "encoder owner lost");
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "index owner lost");
      var optionalCalls = new AtomicInteger();
      var indexCalls = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction optionalAction = request -> {
        optionalCalls.incrementAndGet();
        assertTrue(request.admitted().isEmpty());
        return ComponentRecoveryAction.Result.REFUSED;
      };
      ComponentRecoveryAction indexAction = request -> {
        assertTrue(request.begin());
        indexCalls.incrementAndGet();
        return recovered(request, index, "index owner ready", "recovered");
      };
      var recoveryBindings = new HashMap<String, ComponentRecoveryBinding>();
      recoveryBindings.put("encoders", new ComponentRecoveryBinding(
          encoders, observed(actionFailure, optionalAction)));
      recoveryBindings.put("index", new ComponentRecoveryBinding(
          index, observed(actionFailure, indexAction)));
      var escalations = new AtomicInteger();
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(recoveryBindings, ignored -> escalations.incrementAndGet());
        monitor.tick();
        awaitIdle(monitor);
        assertEquals(1, optionalCalls.get());
        assertEquals(0, indexCalls.get());
        assertEquals(0, encoders.snapshot().recoveryAttempts());

        monitor.tick();
        await(() -> indexCalls.get() == 1 && index.snapshot().state() == ComponentState.READY);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals(1, indexCalls.get());
        assertEquals(1, index.snapshot().recoveryAttempts());
        assertEquals(0, escalations.get());
      }
    }
  }

  @Test
  void automaticBudgetIsTwoAndOptionalExhaustionDoesNotEscalate() throws Exception {
    long[] now = {1_000};
    try (var components = new TestEngineComponents()) {
      var optional = components.register(spec("generative", false, Duration.ZERO, 5));
      optional.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var attempts = new AtomicInteger();
      var escalations = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        return failed(request, optional, "physical retry failed", "retry failed");
      };
      var bootstrap = bootstrapWithoutClient();
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> now[0])) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> escalations.incrementAndGet());
        monitor.tick();
        await(() -> attempts.get() == 1 && optional.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        now[0] += 1_000;
        monitor.tick();
        await(() -> attempts.get() == 2 && optional.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        now[0] += 1_000;
        monitor.tick();
        awaitIdle(monitor);
        assertEquals(2, attempts.get());
        assertEquals(2, optional.snapshot().recoveryAttempts());
        assertEquals(0, escalations.get());
        assertEquals(ComponentRecoveryAuthority.Outcome.EXHAUSTED,
            monitor.requestComponentRecovery("generative"));
        assertEquals(LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
            optional.snapshot().reasonCode(), "the exhausted budget must reach lifecycle consumers");
      }
    }
  }

  @Test
  void effectiveBudgetUsesTheComponentAndPolicyMinimum() throws Exception {
    long[] now = {1_000};
    try (var components = new TestEngineComponents()) {
      var optional = components.register(spec("generative", false, Duration.ZERO, 1));
      optional.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var attempts = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        return failed(request, optional, "physical retry failed", "retry failed");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> now[0])) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> {});
        monitor.tick();
        await(() -> attempts.get() == 1 && optional.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        now[0] += 1_000;
        monitor.tick();
        assertEquals(1, attempts.get(), "spec budget one must cap the policy budget");
        assertEquals(ComponentRecoveryAuthority.Outcome.EXHAUSTED,
            monitor.requestComponentRecovery("generative"));
      }
    }
  }

  @Test
  void pendingInitialIndexFutureKeepsGenericRecoveryFromReplacingItsOwner() {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "initial owner deadline");
      var bootstrap = bootstrapWithoutClient();
      var startup = new CompletableFuture<Void>();
      try (var monitor = monitor(components, bootstrap)) {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          throw new AssertionError("initial owner must retain the physical open");
        }), ignored -> {});
        monitor.observeInitialStartup(startup);
        assertEquals(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
            monitor.requestComponentRecovery("index"));
        monitor.tick();
        assertEquals(0, index.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void pendingInitialOwnerEscalatesAfterRetentionDeadlineWithoutReplacingOrCancellingIt()
      throws Exception {
    for (var state : new ComponentState[] {ComponentState.STARTING, ComponentState.FAILED}) {
      try (var components = new TestEngineComponents()) {
        var index = components.register(spec("index", true, Duration.ofMillis(1), 2));
        index.transition(state, LifecycleReasonCode.INDEX_STARTING.code(), "initial open");
        var startup = new CompletableFuture<Void>();
        var escalations = new AtomicInteger();
        try (var monitor = monitor(components, bootstrapWithoutClient())) {
          monitor.componentRecoveryBindings(bindings(components, request -> {
            throw new AssertionError("the stuck initial owner must never be replaced");
          }), row -> {
            assertEquals(ComponentState.FAILED, row.state());
            escalations.incrementAndGet();
          });
          monitor.observeInitialStartup(startup);
          Thread.sleep(5);

          monitor.tick();
          monitor.tick();

          assertEquals(1, escalations.get());
          assertEquals(0, index.snapshot().recoveryAttempts());
          assertFalse(startup.isDone(), "escalation must retain the actual physical owner");
          assertEquals(LifecycleReasonCode.COMPONENT_START_DEADLINE.code(),
              index.snapshot().reasonCode());
        }
      }
    }
  }

  @Test
  void stuckAdmittedEssentialRecoveryEscalatesDespiteProgressAndRetainsItsSlot()
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ofMillis(1), 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(), "open failed");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var physicalCalls = new AtomicInteger();
      var escalations = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        physicalCalls.incrementAndGet();
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return failed(request, index, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "owner eventually returned");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> escalations.incrementAndGet());
        try {
          assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
              monitor.requestComponentRecovery("index"));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          Thread.sleep(5);
          // A physical progress publication starts a new state clock, but retains the same owner.
          index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
              "intermediate failure");
          index.transition(ComponentState.STARTING, LifecycleReasonCode.COMPONENT_RECOVERING.code(),
              "still opening");

          monitor.tick();
          monitor.tick();

          assertEquals(1, escalations.get());
          assertEquals(ComponentState.FAILED, index.snapshot().state());
          assertEquals(1, index.snapshot().recoveryAttempts());
          assertEquals(1, physicalCalls.get());
          assertTrue(monitor.recoveryAttemptRunningForTest(),
              "restart dispatch must not release or cancel a retained recovery owner");
          assertEquals(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
              monitor.requestComponentRecovery("index"));
        } finally {
          release.countDown();
          awaitIdle(monitor);
        }
        assertNoAsyncFailure(actionFailure);
      }
    }
  }

  @Test
  void essentialFailuresEscalateWhileAnOptionalOwnerHoldsTheOnlySlot() throws Exception {
    for (String failure : new String[] {"fatal", "exhausted", "blocked"}) {
      try (var components = new TestEngineComponents()) {
        var index = components.register(spec("index", true, Duration.ofMillis(1), 2));
        var optional = components.register(spec("generative", false, Duration.ofMillis(1), 2));
        optional.transition(ComponentState.FAILED, LifecycleReasonCode.INFERENCE_CRASHED.code(),
            "optional owner lost");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var physicalCalls = new AtomicInteger();
        var escalations = new AtomicInteger();
        var actionFailure = new AtomicReference<Throwable>();
        ComponentRecoveryAction action = request -> {
          assertEquals("generative", request.expected().spec().name());
          assertTrue(request.begin());
          physicalCalls.incrementAndGet();
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
          return failed(request, optional, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              "optional owner eventually returned");
        };
        try (var monitor = monitor(components, bootstrapWithoutClient())) {
          monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
              row -> {
                assertEquals("index", row.spec().name());
                escalations.incrementAndGet();
              });
          try {
            assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
                monitor.requestComponentRecovery("generative"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(5);
            monitor.tick();
            assertEquals(0, escalations.get(), "a stuck optional owner alone cannot escalate");
            assertTrue(monitor.recoveryAttemptRunningForTest());

            index.transition(ComponentState.FAILED, "fatal".equals(failure)
                ? LifecycleReasonCode.INDEX_CORRUPT.code() : LifecycleReasonCode.INDEX_FAILED.code(),
                "essential owner lost");
            if ("exhausted".equals(failure)) {
              index.recordRecoveryAttempt("first physical attempt");
              index.recordRecoveryAttempt("second physical attempt");
            }
            monitor.tick();
            monitor.tick();

            assertEquals(1, escalations.get(), failure);
            assertTrue(monitor.recoveryAttemptRunningForTest());
            assertEquals(1, physicalCalls.get());
            assertEquals("exhausted".equals(failure) ? 2 : 0, index.snapshot().recoveryAttempts());
            assertEquals("exhausted".equals(failure)
                ? LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code()
                : "fatal".equals(failure) ? LifecycleReasonCode.INDEX_CORRUPT.code()
                    : LifecycleReasonCode.INDEX_FAILED.code(), index.snapshot().reasonCode());
          } finally {
            release.countDown();
            awaitIdle(monitor);
          }
          assertNoAsyncFailure(actionFailure);
        }
      }
    }
  }

  @Test
  void initialUnavailableOwnerEscalatesAtItsRetentionDeadlineWithoutReplacement()
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ofSeconds(60), 2));
      index.transition(ComponentState.READY, null, "initial owner published readiness");
      var startup = new CompletableFuture<Void>();
      var nowNanos = new AtomicLong();
      var escalations = new AtomicInteger();
      try (var monitor = monitor(components, bootstrapWithoutClient(), nowNanos::get)) {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          throw new AssertionError("the initial owner must retain its physical open");
        }), ignored -> escalations.incrementAndGet());
        monitor.observeInitialStartup(startup);
        assertTrue(index.transitionIfUnchanged(index.snapshot(), ComponentState.UNAVAILABLE,
            LifecycleReasonCode.INDEX_FAILED.code(), "fresh contact observation failed"));
        nowNanos.set(Duration.ofSeconds(60).toNanos() - 1);

        monitor.tick();
        assertEquals(ComponentState.UNAVAILABLE, index.snapshot().state());
        assertEquals(0, escalations.get());
        nowNanos.incrementAndGet();
        monitor.tick();
        monitor.tick();

        assertEquals(ComponentState.FAILED, index.snapshot().state());
        assertEquals(LifecycleReasonCode.INDEX_FAILED.code(), index.snapshot().reasonCode());
        assertEquals(1, escalations.get());
        assertEquals(0, index.snapshot().recoveryAttempts());
        assertFalse(startup.isDone(), "deadline observation must not cancel physical ownership");
      }
    }
  }

  @Test
  void expiredOptionalOwnerCannotOverwriteUnrelatedIndexObservations() throws Exception {
    for (var state : new ComponentState[] {
        ComponentState.STARTING, ComponentState.UNAVAILABLE, ComponentState.READY}) {
      try (var components = new TestEngineComponents()) {
        var index = components.register(spec("index", true, Duration.ofSeconds(180), 2));
        var optional = components.register(spec("generative", false, Duration.ofSeconds(60), 2));
        index.transition(state, null, "unrelated index observation");
        optional.transition(ComponentState.FAILED, LifecycleReasonCode.INFERENCE_CRASHED.code(),
            "optional owner lost");
        var nowNanos = new AtomicLong();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var escalations = new AtomicInteger();
        var actionFailure = new AtomicReference<Throwable>();
        ComponentRecoveryAction action = request -> {
          assertEquals("generative", request.expected().spec().name());
          assertTrue(request.begin());
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
          return failed(request, optional, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              "optional owner eventually returned");
        };
        try (var monitor = monitor(components, bootstrapWithoutClient(), nowNanos::get)) {
          monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
              ignored -> escalations.incrementAndGet());
          try {
            assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
                monitor.requestComponentRecovery("generative"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            nowNanos.set(Duration.ofSeconds(60).toNanos());

            monitor.tick();
            monitor.tick();

            assertEquals(state, index.snapshot().state(), "only the matching owner can expire");
            assertEquals(ComponentState.FAILED, optional.snapshot().state());
            assertEquals(0, escalations.get(), "optional failure cannot restart a healthy index");
            assertEquals(0, index.snapshot().recoveryAttempts());
            assertTrue(monitor.recoveryAttemptRunningForTest());
          } finally {
            release.countDown();
            awaitIdle(monitor);
          }
          assertEquals(1, optional.snapshot().recoveryAttempts());
          assertNoAsyncFailure(actionFailure);
        }
      }
    }
  }

  @Test
  void unrelatedRecoveryUsesItsOwnLongerDeadlineBeforeEscalatingEssentialFailure()
      throws Exception {
    assertOptionalOwnerRetention(Duration.ofSeconds(60), Duration.ofSeconds(180),
        Duration.ofSeconds(70), true);
  }

  @Test
  void stuckOptionalOwnerUsesItsOwnShorterDeadlineToUnblockEssentialEscalation()
      throws Exception {
    assertOptionalOwnerRetention(Duration.ofSeconds(180), Duration.ofSeconds(60),
        Duration.ofSeconds(59), true);
  }

  @Test
  void unrelatedRecoveryCompletingWithinItsDeadlineLeavesIndexLocalRecoveryAvailable()
      throws Exception {
    assertOptionalOwnerRetention(Duration.ofSeconds(60), Duration.ofSeconds(180),
        Duration.ofSeconds(70), false);
  }

  private void assertOptionalOwnerRetention(Duration indexDeadline, Duration optionalDeadline,
      Duration indexFailureAt, boolean ownerStuck) throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, indexDeadline, 2));
      var optional = components.register(spec("generative", false, optionalDeadline, 2));
      index.transition(ComponentState.READY, null, "index still serving");
      optional.transition(ComponentState.FAILED, LifecycleReasonCode.INFERENCE_CRASHED.code(),
          "optional owner lost");
      var nowNanos = new AtomicLong();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var optionalCalls = new AtomicInteger();
      var indexCalls = new AtomicInteger();
      var escalations = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        if ("generative".equals(request.expected().spec().name())) {
          optionalCalls.incrementAndGet();
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
          return recovered(request, optional, null, "optional owner returned");
        }
        assertEquals("index", request.expected().spec().name());
        indexCalls.incrementAndGet();
        return recovered(request, index, null, "index local recovery succeeded");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(), nowNanos::get)) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            row -> {
              assertEquals("index", row.spec().name());
              escalations.incrementAndGet();
            });
        try {
          assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
              monitor.requestComponentRecovery("generative"));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          nowNanos.set(indexFailureAt.toNanos());
          monitor.tick();
          assertEquals(0, escalations.get(), "optional recovery alone cannot escalate");
          index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
              "new recoverable index failure");

          monitor.tick();

          assertEquals(0, escalations.get(),
              "an unrelated owner still within its own deadline must not trigger restart");
          assertEquals(ComponentState.STARTING, optional.snapshot().state());
          assertEquals(0, index.snapshot().recoveryAttempts());
          assertTrue(monitor.recoveryAttemptRunningForTest());
          nowNanos.set(optionalDeadline.toNanos() - 1);
          monitor.tick();
          assertEquals(0, escalations.get(), "the occupied owner's complete deadline applies");
          assertEquals(ComponentState.STARTING, optional.snapshot().state());

          if (ownerStuck) {
            nowNanos.incrementAndGet();
            monitor.tick();
            monitor.tick();
            assertEquals(1, escalations.get(), "a genuinely stuck owner must permit escalation");
            assertEquals(ComponentState.FAILED, optional.snapshot().state());
            assertEquals(ComponentState.FAILED, index.snapshot().state());
            assertEquals(0, index.snapshot().recoveryAttempts());
            assertTrue(monitor.recoveryAttemptRunningForTest(),
                "escalation must leave the physical slot owned until its action returns");
          }
        } finally {
          release.countDown();
          awaitIdle(monitor);
        }
        if (!ownerStuck) {
          monitor.tick();
          awaitIdle(monitor);
          assertEquals(ComponentState.READY, index.snapshot().state());
          assertEquals(1, index.snapshot().recoveryAttempts());
          assertEquals(1, indexCalls.get());
          assertEquals(0, escalations.get(), "normal completion must preserve local index recovery");
        } else {
          assertEquals(0, indexCalls.get());
        }
        assertEquals(1, optionalCalls.get());
        assertEquals(1, optional.snapshot().recoveryAttempts());
        assertNoAsyncFailure(actionFailure);
      }
    }
  }

  @Test
  void retainedOwnerEscalationRetriesOnNextTickAndHonorsConcurrentReady() throws Exception {
    for (boolean recoverDuringPublication : new boolean[] {false, true}) {
      try (var components = new TestEngineComponents()) {
        var index = components.register(spec("index", true, Duration.ofMillis(1), 2));
        index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_STARTING.code(),
            "initial open timed out");
        var startup = new CompletableFuture<Void>();
        var escalations = new AtomicInteger();
        try (var monitor = monitor(components, bootstrapWithoutClient())) {
          monitor.componentRecoveryBindings(bindings(components, null), ignored -> {
            if (escalations.incrementAndGet() == 1) {
              throw new IllegalStateException("restart dispatch temporarily unavailable");
            }
          });
          monitor.observeInitialStartup(startup);
          components.subscribe(snapshot -> {
            var row = snapshot.components().get(0);
            if (recoverDuringPublication
                && LifecycleReasonCode.COMPONENT_START_DEADLINE.code().equals(row.reasonCode())) {
              index.transition(ComponentState.READY, null, "late physical open succeeded");
            }
          });
          Thread.sleep(5);

          monitor.tick();
          assertEquals(recoverDuringPublication ? 0 : 1, escalations.get());
          monitor.tick();
          monitor.tick();

          assertEquals(recoverDuringPublication ? 0 : 2, escalations.get());
          assertEquals(recoverDuringPublication ? ComponentState.READY : ComponentState.FAILED,
              index.snapshot().state());
          assertFalse(startup.isDone());
          assertEquals(0, index.snapshot().recoveryAttempts());
        }
      }
    }
  }

  @Test
  void completedInitialFutureHandsOverLateClientExactlyOnce() {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      var startup = CompletableFuture.completedFuture(null);
      var initialHandovers = new AtomicInteger();
      var publications = new AtomicInteger();
      try (var monitor = monitor(components, bootstrap)) {
        monitor.componentRecoveryBindings(bindings(components, null), ignored -> {});
        monitor.onRecoveryConnected(ignored -> initialHandovers.incrementAndGet());
        monitor.onRecoveryPublished(ignored -> publications.incrementAndGet());
        monitor.observeInitialStartup(startup);
        monitor.tick();
        monitor.tick();
        assertEquals(1, initialHandovers.get());
        assertEquals(0, publications.get());
      }
    }
  }

  @Test
  void failedInitialHandoverReleasesOwnerAndUsesGenericBudgetBeforeEscalation()
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      var handovers = new AtomicInteger();
      var attempts = new AtomicInteger();
      var escalations = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      var firstActionEntered = new CountDownLatch(1);
      var releaseFirstAction = new CountDownLatch(1);
      ComponentRecoveryAction action = request -> {
        if (attempts.get() == 0) {
          firstActionEntered.countDown();
          assertTrue(releaseFirstAction.await(5, TimeUnit.SECONDS));
        }
        assertTrue(request.begin());
        int attempt = attempts.incrementAndGet();
        return failed(request, index,
            LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "physical retry " + attempt + " failed");
      };
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(2, 0, 0),
          () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> escalations.incrementAndGet());
        monitor.onRecoveryConnected(ignored -> {
          handovers.incrementAndGet();
          throw new IllegalStateException("strict binding rejected initial owner");
        });
        monitor.observeInitialStartup(CompletableFuture.completedFuture(null));

        monitor.tick();
        assertTrue(firstActionEntered.await(5, TimeUnit.SECONDS));
        try {
          assertEquals(ComponentState.FAILED, index.snapshot().state());
          assertEquals(LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              index.snapshot().reasonCode());
          assertEquals("Initial index handover failed: strict binding rejected initial owner",
              index.snapshot().evidence());
          assertEquals(1, handovers.get(), "the failed initial handover is not memoized forever");
        } finally {
          releaseFirstAction.countDown();
        }
        await(() -> attempts.get() == 1 && index.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals(1, handovers.get());
        assertEquals(1, index.snapshot().recoveryAttempts());

        monitor.tick();
        await(() -> attempts.get() == 2 && index.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals(1, handovers.get());
        assertEquals(2, index.snapshot().recoveryAttempts());

        monitor.tick();
        assertEquals(2, attempts.get());
        assertEquals(1, escalations.get());
      }
    }
  }

  @Test
  void essentialExhaustionEscalatesExactlyOnce() throws Exception {
    long[] now = {1_000};
    try (var components = new TestEngineComponents()) {
      var required = components.register(spec("api", true, Duration.ZERO, 5));
      required.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var attempts = new AtomicInteger();
      var escalations = new AtomicInteger();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        return failed(request, required, "physical retry failed", "retry failed");
      };
      var bootstrap = bootstrapWithoutClient();
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> now[0])) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)), ignored ->
            escalations.incrementAndGet());
        monitor.tick();
        await(() -> attempts.get() == 1 && required.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        now[0] += 1_000;
        monitor.tick();
        await(() -> attempts.get() == 2 && required.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        now[0] += 1_000;
        monitor.tick();
        assertEquals(2, attempts.get());
        assertEquals(1, escalations.get());
        monitor.tick();
        assertEquals(1, escalations.get());
      }
    }
  }

  @Test
  void readyThenFailedBetweenTicksStartsAFreshRecoveryEpisode() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "initial owner lost");
      var attempts = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        return recovered(request, handle, "owner ready", "recovered");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.tick();
        await(() -> attempts.get() == 1 && handle.snapshot().state() == ComponentState.READY);
        awaitIdle(monitor);

        handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
            "owner failed again before the next tick");
        monitor.tick();
        await(() -> attempts.get() == 2 && handle.snapshot().state() == ComponentState.READY);
        awaitIdle(monitor);
        assertEquals(2, attempts.get());
        assertEquals(2, handle.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void failedEvidenceDoesNotResetTheCumulativeBudget() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "initial failure");
      var attempts = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        return failed(request, handle, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "physical retry failed");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.tick();
        await(() -> attempts.get() == 1 && handle.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);

        handle.transition(ComponentState.FAILED, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "new failure evidence");
        monitor.tick();
        await(() -> attempts.get() == 2 && handle.snapshot().state() == ComponentState.FAILED);
        awaitIdle(monitor);

        handle.transition(ComponentState.FAILED, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "third failure evidence");
        monitor.tick();
        awaitIdle(monitor);
        assertEquals(2, attempts.get());
        assertEquals(2, handle.snapshot().recoveryAttempts());
        assertEquals(ComponentRecoveryAuthority.Outcome.EXHAUSTED,
            monitor.requestComponentRecovery("generative"));
      }
    }
  }

  @Test
  void delayedOlderReadySnapshotCannotCloseTheCurrentRecoveryEpisode() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "initial failure");
      var firstReadySnapshot = new AtomicReference<EngineComponentSnapshot>();
      var readyEntered = new CountDownLatch(1);
      var releaseReadyListener = new CountDownLatch(1);
      components.subscribe(snapshot -> {
        var row = snapshot.components().get(0);
        if (row.state() == ComponentState.READY && row.recoveryAttempts() == 0) {
          firstReadySnapshot.set(snapshot);
          readyEntered.countDown();
          try {
            assertTrue(releaseReadyListener.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
        }
      });
      var actionStarted = new CountDownLatch(1);
      var releaseAction = new CountDownLatch(1);
      var attempts = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        attempts.incrementAndGet();
        actionStarted.countDown();
        assertTrue(releaseAction.await(5, TimeUnit.SECONDS));
        return failed(request, handle, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "retry failed");
      };
      var publisher = new Thread(() -> handle.transition(ComponentState.READY, null,
          "older owner ready"));
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 10_000, 10_000), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        var occurrences = new java.util.concurrent.CopyOnWriteArrayList<RecoveryOccurrence>();
        monitor.onRecoveryOccurrence(occurrences::add);

        publisher.start();
        assertTrue(readyEntered.await(5, TimeUnit.SECONDS));
        handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
            "failure superseded the older ready row");
        monitor.tick();
        assertTrue(actionStarted.await(5, TimeUnit.SECONDS));

        releaseReadyListener.countDown();
        publisher.join(5_000);
        assertFalse(publisher.isAlive());
        releaseAction.countDown();
        awaitIdle(monitor);
        assertEquals(1, attempts.get());
        assertEquals(1, occurrences.stream()
            .filter(occurrence -> occurrence.kind() == RecoveryOccurrence.Kind.ATTEMPTED)
            .count());
        assertEquals(0, occurrences.stream()
            .filter(occurrence -> occurrence.kind() == RecoveryOccurrence.Kind.RECOVERED)
            .count());

        monitor.tick();
        awaitIdle(monitor);
        assertEquals(1, attempts.get(), "the stale READY row must not reset recovery backoff");
        assertEquals(1, handle.snapshot().recoveryAttempts());
        assertTrue(firstReadySnapshot.get() != null);
      } finally {
        releaseReadyListener.countDown();
        releaseAction.countDown();
        publisher.join(5_000);
      }
    }
  }

  @Test
  void exactReadyTerminalFollowedByNewFailureCannotTriggerIndexPublication() throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "index owner lost");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      var initialHandovers = new AtomicInteger();
      var publications = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        var terminal = recovered(request, index, "index ready", "ready");
        index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
            "new index failure");
        return terminal;
      };
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.onRecoveryConnected(ignored -> initialHandovers.incrementAndGet());
        monitor.onRecoveryPublished(ignored -> publications.incrementAndGet());
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("index"));
        awaitIdle(monitor);
        assertEquals(0, initialHandovers.get());
        assertEquals(0, publications.get());
        assertEquals(ComponentState.FAILED, index.snapshot().state());
      }
    }
  }

  @Test
  void deadlineFailureThenExactReadyCompletionIsAccepted() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ofMillis(1), 2));
      handle.transition(ComponentState.STARTING, LifecycleReasonCode.INDEX_STARTING.code(),
          "waiting for owner");
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        return recovered(request, handle, "owner ready", "ready");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        Thread.sleep(10);
        monitor.tick();
        await(() -> handle.snapshot().state() == ComponentState.READY);
        awaitIdle(monitor);
        assertEquals(1, handle.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void monitorCannotStartUntilTheCompleteBindingMapIsSealed() {
    try (var components = TestEngineComponents.fourComponents();
        var monitor = new KnowledgeServerHealthMonitor(
            processExecutors, bootstrapWithoutClient(), 10_000)) {
      monitor.componentRegistry(components);
      assertThrows(IllegalStateException.class, monitor::start);
      monitor.componentRecoveryBindings(bindings(components, null), ignored -> {});
      monitor.start();
    }
  }

  @Test
  void generativeHarnessBarrierHoldsFailedAttemptZeroAndTheSharedSlot(@TempDir Path data)
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var generative = components.register(spec("generative", false, Duration.ZERO, 2));
      generative.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "managed child exited");
      var calls = new AtomicInteger();
      var bootstrap = bootstrapWithoutClient();
      when(bootstrap.dataDirForHarness()).thenReturn(data);
      Map<String, String> environment = Map.of(
          "JUSTSEARCH_SUPERVISOR_HARNESS", "1",
          "JUSTSEARCH_GENERATIVE_RECOVERY_BARRIER", "1");
      try (var monitor = monitor(components, bootstrap, BootRecoveryPolicy.defaults(),
          System::currentTimeMillis, environment::get)) {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          calls.incrementAndGet();
          assertTrue(request.begin());
          return failed(request, generative, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              "private runtime held absent");
        }), ignored -> {});

        monitor.tick();
        Path reached = data.resolve("runtime/generative-recovery-1-reached.json");
        await(() -> Files.isRegularFile(reached));
        var marker = new ObjectMapper().readTree(Files.readString(reached));
        assertEquals("component-recovery-pre-admission", marker.path("point").asText());
        assertEquals("generative", marker.path("component").asText());
        assertEquals("FAILED", marker.path("state").asText());
        assertEquals(0, marker.path("recoveryAttempts").asInt());
        assertEquals(ComponentState.FAILED, generative.snapshot().state());
        assertEquals(0, generative.snapshot().recoveryAttempts());
        assertEquals(0, calls.get());
        assertEquals(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
            monitor.requestComponentRecovery("generative"));

        Files.writeString(data.resolve("runtime/generative-recovery-1-release"), "release");
        awaitIdle(monitor);
        assertEquals(1, calls.get());
        assertEquals(ComponentState.FAILED, generative.snapshot().state());
        assertEquals(1, generative.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void interruptedGenerativeHarnessBarrierLeavesFailedAttemptZeroAndClearsSlot(@TempDir Path data)
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var generative = components.register(spec("generative", false, Duration.ZERO, 2));
      generative.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "managed child exited");
      var calls = new AtomicInteger();
      var bootstrap = bootstrapWithoutClient();
      when(bootstrap.dataDirForHarness()).thenReturn(data);
      Map<String, String> environment = Map.of(
          "JUSTSEARCH_SUPERVISOR_HARNESS", "1",
          "JUSTSEARCH_GENERATIVE_RECOVERY_BARRIER", "1");
      var monitor = monitor(components, bootstrap, BootRecoveryPolicy.defaults(),
          System::currentTimeMillis, environment::get);
      try {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          calls.incrementAndGet();
          return ComponentRecoveryAction.Result.REFUSED;
        }), ignored -> {});
        monitor.tick();
        await(() -> Files.isRegularFile(
            data.resolve("runtime/generative-recovery-1-reached.json")));

        monitor.close();

        assertEquals(0, calls.get());
        assertEquals(ComponentState.FAILED, generative.snapshot().state());
        assertEquals(0, generative.snapshot().recoveryAttempts());
        assertFalse(monitor.recoveryAttemptRunningForTest());
      } finally {
        monitor.close();
      }
    }
  }

  @Test
  void ordinaryAutomaticGenerativeRecoveryDoesNotCreateHarnessBarrier(@TempDir Path data)
      throws Exception {
    try (var components = new TestEngineComponents()) {
      var generative = components.register(spec("generative", false, Duration.ZERO, 2));
      generative.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "managed child exited");
      var bootstrap = bootstrapWithoutClient();
      when(bootstrap.dataDirForHarness()).thenReturn(data);
      try (var monitor = monitor(components, bootstrap, BootRecoveryPolicy.defaults(),
          System::currentTimeMillis, ignored -> null)) {
        monitor.componentRecoveryBindings(bindings(components, request -> {
          assertTrue(request.begin());
          return failed(request, generative, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
              "ordinary retry failed");
        }), ignored -> {});
        monitor.tick();
        awaitIdle(monitor);
        assertEquals(1, generative.snapshot().recoveryAttempts());
        assertFalse(Files.exists(data.resolve("runtime/generative-recovery-1-reached.json")));
      }
    }
  }

  @Test
  void restoredKnownMissingRolesStopAutomaticAndManualRecovery() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("encoders", false, Duration.ZERO, 2));
      handle.transition(ComponentState.UNAVAILABLE,
          LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(), "source restoration failed");
      var attempts = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        attempts.incrementAndGet();
        assertTrue(request.begin());
        assertThrows(IllegalArgumentException.class, () -> request.complete(request.current(),
            ComponentState.UNAVAILABLE, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "must not preserve the retryable failure"));
        var terminal = request.complete(request.current(), ComponentState.UNAVAILABLE,
            null, "missing_roles=CITATION").orElseThrow();
        assertThrows(IllegalArgumentException.class,
            () -> ComponentRecoveryAction.Result.recovered(terminal));
        return ComponentRecoveryAction.Result.degraded(terminal);
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("encoders"));
        awaitIdle(monitor);
        assertEquals(ComponentState.UNAVAILABLE, handle.snapshot().state());
        assertEquals("missing_roles=CITATION", handle.snapshot().evidence());
        monitor.tick();
        awaitIdle(monitor);
        assertEquals(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
            monitor.requestComponentRecovery("encoders"));
        assertEquals(1, attempts.get());
        assertEquals(1, handle.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void essentialRecoveryCannotEscapeFailureAsUnavailable() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("index", true, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED,
          LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(), "index failed");
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        assertThrows(IllegalArgumentException.class, () -> request.complete(request.current(),
            ComponentState.UNAVAILABLE, null, "must not escape the essential budget"));
        return ComponentRecoveryAction.Result.failed(request.complete(request.current(),
            ComponentState.FAILED, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "index remains failed").orElseThrow());
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("index"));
        awaitIdle(monitor);
        assertEquals(ComponentState.FAILED, handle.snapshot().state());
        assertEquals(1, handle.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void progressObservationThenExactReadyCompletionIsAccepted() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        handle.transition(ComponentState.STARTING, LifecycleReasonCode.COMPONENT_RECOVERING.code(),
            "physical owner progress");
        var current = handle.snapshot();
        var terminal = request.complete(current, ComponentState.READY, "owner ready", "ready")
            .orElseThrow();
        assertTrue(request.complete(current, ComponentState.READY, "owner ready", "ready")
            .isEmpty());
        return ComponentRecoveryAction.Result.recovered(terminal);
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.tick();
        await(() -> handle.snapshot().state() == ComponentState.READY);
        awaitIdle(monitor);
        assertEquals(1, handle.snapshot().recoveryAttempts());
        assertEquals("ready", handle.snapshot().evidence());
      }
    }
  }

  @Test
  void busyUnrelatedRecoveryStillSamplesIndexHealthAndCompletesInitialHandover() throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      var generative = components.register(spec("generative", false, Duration.ZERO, 2));
      generative.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "generative owner lost");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      var healthChecks = new AtomicInteger();
      when(bootstrap.tryCheckHealth()).thenAnswer(ignored -> {
        healthChecks.incrementAndGet();
        return java.util.Optional.of(true);
      });
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return failed(request, generative, LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "generative retry failed");
      };
      var handovers = new AtomicInteger();
      var startup = CompletableFuture.completedFuture(null);
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.onRecoveryConnected(ignored -> handovers.incrementAndGet());
        monitor.observeInitialStartup(startup);
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("generative"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        monitor.tick();
        assertEquals(1, healthChecks.get());
        assertEquals(1, handovers.get());
        assertEquals(ComponentState.STARTING, generative.snapshot().state());
        release.countDown();
        awaitIdle(monitor);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void recoveryPublishedCallbackMayCloseMonitorWithoutSelfAwait() throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "index owner lost");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      var callbackReturned = new CountDownLatch(1);
      var initialHandovers = new AtomicInteger();
      var publications = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        return recovered(request, index, "index ready", "ready");
      };
      var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> 1_000L);
      monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
      monitor.onRecoveryConnected(ignored -> initialHandovers.incrementAndGet());
      monitor.onRecoveryPublished(ignored -> {
        try {
          publications.incrementAndGet();
          monitor.close();
        } finally {
          callbackReturned.countDown();
        }
      });
      try {
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("index"));
        assertTrue(callbackReturned.await(5, TimeUnit.SECONDS));
        assertEquals(0, initialHandovers.get());
        assertEquals(1, publications.get());
      } finally {
        monitor.close();
      }
    }
  }

  @Test
  void throwingRecoveryPublishedCallbackDoesNotRetryReadyPhysicalOwner() throws Exception {
    try (var components = new TestEngineComponents()) {
      var index = components.register(spec("index", true, Duration.ZERO, 2));
      index.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "index owner lost");
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.indexComponent()).thenReturn(index);
      when(bootstrap.hasClient()).thenReturn(true);
      when(bootstrap.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      var physicalAttempts = new AtomicInteger();
      var publications = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        physicalAttempts.incrementAndGet();
        assertTrue(request.begin());
        return recovered(request, index, "index ready", "ready");
      };
      try (var monitor = monitor(components, bootstrap, new BootRecoveryPolicy(5, 0, 0),
          () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {});
        monitor.onRecoveryPublished(ignored -> {
          publications.incrementAndGet();
          throw new IllegalStateException("activation failed after publication");
        });

        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("index"));
        awaitIdle(monitor);
        monitor.tick();
        awaitIdle(monitor);

        assertEquals(ComponentState.READY, index.snapshot().state());
        assertEquals(1, physicalAttempts.get());
        assertEquals(1, publications.get());
      }
    }
  }

  @Test
  void throwingEscalationCallbackIsRetriedOnTheNextTick() {
    try (var components = new TestEngineComponents()) {
      var required = components.register(spec("api", true, Duration.ZERO, 0));
      required.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var escalations = new AtomicInteger();
      ComponentRecoveryAction action = request -> {
        throw new AssertionError("the zero-budget component must escalate before invoking its owner");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient(),
          new BootRecoveryPolicy(5, 0, 0), () -> 1_000L)) {
        monitor.componentRecoveryBindings(bindings(components, action), ignored -> {
          if (escalations.incrementAndGet() == 1) {
            throw new IllegalStateException("transient escalation callback failure");
          }
        });
        monitor.tick();
        monitor.tick();
        assertEquals(2, escalations.get());
      }
    }
  }

  @Test
  void staleRowAfterActionStartsCannotBeOverwrittenByItsFailure() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "old row");
      var actionCalled = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        actionCalled.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        var stale = request.admitted().orElseThrow();
        assertTrue(request.complete(stale, ComponentState.FAILED,
            LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(), "stale retry failed")
            .isEmpty());
        return ComponentRecoveryAction.Result.SUPERSEDED;
      };
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("generative"));
        assertTrue(actionCalled.await(5, TimeUnit.SECONDS));
        handle.transition(ComponentState.READY, null, "newer owner is ready");
        release.countDown();
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals(ComponentState.READY, handle.snapshot().state());
        assertEquals("newer owner is ready", handle.snapshot().evidence());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void beginIsOneShotAndOnlyTheFirstCallCounts() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "owner lost");
      var secondBegin = new AtomicReference<Boolean>();
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        assertTrue(request.begin());
        secondBegin.set(request.begin());
        return failed(request, handle, "physical retry failed", "retry failed");
      };
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("generative"));
        await(() -> secondBegin.get() != null);
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertFalse(secondBegin.get());
        await(() -> handle.snapshot().state() == ComponentState.FAILED);
        assertEquals(1, handle.snapshot().recoveryAttempts());
      }
    }
  }

  @Test
  void ownerRowChangeBeforeBeginRefusesWithoutSpendingRecovery() throws Exception {
    try (var components = new TestEngineComponents()) {
      var handle = components.register(spec("generative", false, Duration.ZERO, 2));
      handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
          "old row");
      var actionCalled = new CountDownLatch(1);
      var actionFailure = new AtomicReference<Throwable>();
      ComponentRecoveryAction action = request -> {
        handle.transition(ComponentState.FAILED, LifecycleReasonCode.INDEX_FAILED.code(),
            "newer physical observation");
        actionCalled.countDown();
        assertFalse(request.begin());
        return ComponentRecoveryAction.Result.REFUSED;
      };
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, observed(actionFailure, action)),
            ignored -> {});
        assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
            monitor.requestComponentRecovery("generative"));
        assertTrue(actionCalled.await(5, TimeUnit.SECONDS));
        awaitIdle(monitor);
        assertNoAsyncFailure(actionFailure);
        assertEquals("newer physical observation", handle.snapshot().evidence());
        assertEquals(0, handle.snapshot().recoveryAttempts());
        assertEquals(ComponentState.FAILED, handle.snapshot().state());
      }
    }
  }

  @Test
  void genericTickStillRunsReadinessCallback() {
    try (var components = new TestEngineComponents()) {
      var ticks = new AtomicInteger();
      try (var monitor = monitor(components, bootstrapWithoutClient())) {
        monitor.componentRecoveryBindings(bindings(components, null), ignored -> {});
        monitor.onTick(ticks::incrementAndGet);
        monitor.tick();
        assertEquals(1, ticks.get());
      }
    }
  }

  private KnowledgeServerHealthMonitor monitor(TestEngineComponents components,
      KnowledgeServerBootstrap bootstrap) {
    return monitor(components, bootstrap, BootRecoveryPolicy.defaults(), System::currentTimeMillis);
  }

  private KnowledgeServerHealthMonitor monitor(TestEngineComponents components,
      KnowledgeServerBootstrap bootstrap, java.util.function.LongSupplier ownerClockNanos) {
    var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L,
        System::currentTimeMillis, BootRecoveryPolicy.defaults(),
        io.justsearch.configuration.SystemAccess::rawEnvVar, ownerClockNanos);
    monitor.componentRegistry(components);
    return monitor;
  }

  private KnowledgeServerHealthMonitor monitor(TestEngineComponents components,
      KnowledgeServerBootstrap bootstrap, BootRecoveryPolicy policy, java.util.function.LongSupplier now) {
    return monitor(components, bootstrap, policy, now,
        io.justsearch.configuration.SystemAccess::rawEnvVar);
  }

  private KnowledgeServerHealthMonitor monitor(TestEngineComponents components,
      KnowledgeServerBootstrap bootstrap, BootRecoveryPolicy policy,
      java.util.function.LongSupplier now, Function<String, String> environment) {
    var monitor = new KnowledgeServerHealthMonitor(
        processExecutors, bootstrap, 10_000L, now, policy, environment);
    monitor.componentRegistry(components);
    return monitor;
  }

  private static KnowledgeServerBootstrap bootstrapWithoutClient() {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(false);
    return bootstrap;
  }

  private static Map<String, ComponentRecoveryBinding> bindings(TestEngineComponents components,
      ComponentRecoveryAction action) {
    var result = new HashMap<String, ComponentRecoveryBinding>();
    for (var row : components.snapshot().components()) {
      result.put(row.spec().name(), new ComponentRecoveryBinding(
          components.handle(row.spec().name()), action));
    }
    return result;
  }

  private static ComponentSpec spec(String name, boolean essential, Duration deadline, int budget) {
    return new ComponentSpec(name, essential, Set.of(), ComponentSpec.ComposeCapability.IN_PLACE,
        deadline, budget);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "condition did not become true");
  }

  private static void awaitIdle(KnowledgeServerHealthMonitor monitor) throws Exception {
    await(() -> !monitor.recoveryAttemptRunningForTest());
  }

  private static ComponentRecoveryAction observed(AtomicReference<Throwable> failures,
      ComponentRecoveryAction delegate) {
    return request -> {
      try {
        return delegate.recover(request);
      } catch (Throwable failure) {
        failures.compareAndSet(null, failure);
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new RuntimeException(failure);
      }
    };
  }

  private static void assertNoAsyncFailure(AtomicReference<Throwable> failure) {
    Throwable cause = failure.get();
    if (cause != null) {
      throw new AssertionError("physical recovery action failed on its executor", cause);
    }
  }

  private static ComponentRecoveryAction.Result failed(ComponentRecoveryAction.Request request,
      ComponentHandle handle, String reason, String evidence) {
    var terminal = request.complete(handle.snapshot(), ComponentState.FAILED, reason, evidence)
        .orElseThrow(() -> new AssertionError("recovery completion was superseded"));
    return ComponentRecoveryAction.Result.failed(terminal);
  }

  private static ComponentRecoveryAction.Result recovered(ComponentRecoveryAction.Request request,
      ComponentHandle handle, String reason, String evidence) {
    var terminal = request.complete(handle.snapshot(), ComponentState.READY, reason, evidence)
        .orElseThrow(() -> new AssertionError("recovery completion was superseded"));
    return ComponentRecoveryAction.Result.recovered(terminal);
  }

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }
}
