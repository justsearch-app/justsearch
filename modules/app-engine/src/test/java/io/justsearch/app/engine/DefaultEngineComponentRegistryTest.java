/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentSpec.ComposeCapability;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.ComposeEvidence.Mode;
import io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultEngineComponentRegistryTest {
  @Test
  void publishesSortedImmutableCoherentSnapshotsWithoutResettingStateClockOnNoOp() {
    var budget = budget();
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      var index = registry.register(spec("index", Set.of("index.mode")));
      registry.register(spec("api", Set.of()));

      var registered = registry.snapshot();
      assertEquals(List.of("api", "index"), registered.components().stream()
          .map(row -> row.spec().name()).toList());
      assertThrows(UnsupportedOperationException.class,
          () -> registered.components().add(registered.components().getFirst()));
      assertThrows(UnsupportedOperationException.class,
          () -> index.spec().dependencyKeys().add("other"));

      index.transition(ComponentState.STARTING, "index.starting", "boot");
      var starting = index.snapshot();
      long transitionRevision = registry.snapshot().revision();
      index.transition(ComponentState.STARTING, "index.starting", "boot");
      assertEquals(transitionRevision, registry.snapshot().revision());
      assertEquals(starting.stateSince(), index.snapshot().stateSince());
      assertEquals(starting.stateSinceMonotonicNanos(),
          index.snapshot().stateSinceMonotonicNanos());

      index.transition(ComponentState.STARTING, "index.starting", "opening generation");
      assertEquals(transitionRevision + 1, registry.snapshot().revision());
      assertEquals(starting.stateSince(), index.snapshot().stateSince());
      index.transition(ComponentState.READY, null, "serving");
      assertNotEquals(starting.stateSinceMonotonicNanos(),
          index.snapshot().stateSinceMonotonicNanos());

      index.setAppliedVersion("applied-digest");
      index.setDesiredVersion("desired-digest");
      index.setLastCompose(new ComposeEvidence(Mode.BESIDE, "capacity available", 20L, 10L));
      index.recordRecoveryAttempt("retry after transient failure");
      var complete = index.snapshot();
      assertEquals("applied-digest", complete.appliedVersion());
      assertEquals("desired-digest", complete.desiredVersion());
      assertEquals(Mode.BESIDE, complete.lastCompose().mode());
      assertEquals(1, complete.recoveryAttempts());
      assertEquals("retry after transient failure", complete.evidence());
    }
  }

  @Test
  void concurrentPublicationsHaveUniqueRevisionsAndStaleCallbacksCanReadCurrentSnapshot()
      throws Exception {
    var budget = budget();
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      var handle = registry.register(spec("index", Set.of("index.mode")));
      var staleEntered = new CountDownLatch(1);
      var releaseStale = new CountDownLatch(1);
      var delivered = new CopyOnWriteArrayList<Long>();
      var currentWhenHandled = new CopyOnWriteArrayList<Long>();
      try (var ignored = registry.subscribe(snapshot -> {
        delivered.add(snapshot.revision());
        if (snapshot.revision() == 2) {
          staleEntered.countDown();
          await(releaseStale);
        }
        currentWhenHandled.add(registry.snapshot().revision());
      })) {
        var first = new Thread(
            () -> handle.transition(ComponentState.STARTING, "index.starting", "boot"));
        first.start();
        assertTrue(staleEntered.await(5, TimeUnit.SECONDS));
        handle.setDesiredVersion("desired-v2");
        releaseStale.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(first.isAlive());
      }

      assertEquals(Set.of(2L, 3L), new HashSet<>(delivered));
      assertEquals(List.of(3L, 3L), currentWhenHandled.stream().sorted().toList());

      int publications = 8;
      var start = new CountDownLatch(1);
      var threads = new ArrayList<Thread>();
      var revisions = new CopyOnWriteArrayList<Long>();
      try (var ignored = registry.subscribe(snapshot -> revisions.add(snapshot.revision()))) {
        for (int i = 0; i < publications; i++) {
          String digest = "digest-" + i;
          var thread = new Thread(() -> {
            await(start);
            handle.setAppliedVersion(digest);
          });
          threads.add(thread);
          thread.start();
        }
        start.countDown();
        for (var thread : threads) thread.join(TimeUnit.SECONDS.toMillis(5));
      }
      assertTrue(threads.stream().noneMatch(Thread::isAlive));
      assertEquals(publications, revisions.size());
      assertEquals(publications, new HashSet<>(revisions).size());
      assertEquals(3 + publications, registry.snapshot().revision());
    }
  }

  @Test
  void listenerLifecycleIsolatedRuntimeFailuresButPropagatesErrors() {
    var budget = budget();
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      var handle = registry.register(spec("index", Set.of()));
      var observed = new ArrayList<Long>();
      var healthy = registry.subscribe(snapshot -> observed.add(snapshot.revision()));
      var failing = registry.subscribe(snapshot -> {
        throw new IllegalStateException("observer defect");
      });
      handle.setDesiredVersion("one");
      assertEquals(List.of(2L), observed);
      failing.close();
      healthy.close();
      handle.setDesiredVersion("two");
      assertEquals(List.of(2L), observed);

      registry.subscribe(snapshot -> {
        throw new AssertionError("fatal observer failure");
      });
      assertThrows(AssertionError.class, () -> handle.setDesiredVersion("three"));
      assertEquals("three", handle.snapshot().desiredVersion(),
          "observer errors do not roll back the owner's committed publication");
    }
  }

  @Test
  void applyLeaseIsSinglePermitSameThreadNonReentrantAndPreservedOnWrongClose()
      throws Exception {
    var budget = budget();
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      var acquired = assertInstanceOf(ApplyAttempt.Acquired.class, registry.tryApply());
      assertEquals(1, retainedCount(budget));
      assertEquals(ApplyAttempt.Reason.REENTRANT,
          assertInstanceOf(ApplyAttempt.Refused.class, registry.tryApply()).reason());

      var otherAttempt = new AtomicReference<ApplyAttempt>();
      var wrongClose = new AtomicReference<IllegalStateException>();
      var thread = new Thread(() -> {
        otherAttempt.set(registry.tryApply());
        try {
          acquired.lease().close();
        } catch (IllegalStateException failure) {
          wrongClose.set(failure);
        }
      });
      thread.start();
      thread.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(thread.isAlive());
      assertEquals(ApplyAttempt.Reason.BUSY,
          assertInstanceOf(ApplyAttempt.Refused.class, otherAttempt.get()).reason());
      assertInstanceOf(IllegalStateException.class, wrongClose.get());
      assertEquals(1, retainedCount(budget));
      assertThrows(IllegalStateException.class, registry::close);
      assertEquals(1, retainedCount(budget));

      acquired.lease().close();
      acquired.lease().close();
      assertEquals(0, retainedCount(budget));
      var replacement = assertInstanceOf(ApplyAttempt.Acquired.class, registry.tryApply());
      assertEquals(1, retainedCount(budget));
      replacement.lease().close();
    }
  }

  @Test
  void successfulCloseRefusesRegistrationMutationSubscriptionAndApply() {
    var budget = budget();
    var registry = new DefaultEngineComponentRegistry(budget);
    var handle = registry.register(spec("index", Set.of()));
    registry.close();
    registry.close();

    assertEquals(ApplyAttempt.Reason.CLOSED,
        assertInstanceOf(ApplyAttempt.Refused.class, registry.tryApply()).reason());
    assertThrows(IllegalStateException.class,
        () -> registry.register(spec("api", Set.of())));
    assertThrows(IllegalStateException.class, () -> registry.subscribe(snapshot -> {}));
    assertThrows(IllegalStateException.class,
        () -> handle.transition(ComponentState.READY, null, null));
    assertEquals(ComponentState.ABSENT, handle.snapshot().state());
  }

  @Test
  void validatesStableSpecsAndOpaqueOptionalEvidence() {
    assertThrows(IllegalArgumentException.class,
        () -> spec(" ", Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new ComponentSpec("index", true, Set.of(), ComposeCapability.IN_PLACE,
            Duration.ofMillis(-1), 1));
    assertThrows(IllegalArgumentException.class,
        () -> new ComposeEvidence(Mode.IN_PLACE, " ", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ComposeEvidence(Mode.IN_PLACE, null, -1L, null));

    var budget = budget();
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      var handle = registry.register(spec("index", Set.of()));
      assertThrows(IllegalArgumentException.class, () -> handle.setAppliedVersion(" "));
      handle.setAppliedVersion("opaque-owner-digest");
      handle.setAppliedVersion(null);
      assertNull(handle.snapshot().appliedVersion());

      var negativeNanoOrigin = new EngineComponentSnapshot.Component(handle.spec(),
          ComponentState.ABSENT, null, Instant.EPOCH, -1L, null, null, null, 0, null);
      assertEquals(-1L, negativeNanoOrigin.stateSinceMonotonicNanos(),
          "System.nanoTime origins may legally be negative");
    }
  }

  private static ComponentSpec spec(String name, Set<String> dependencyKeys) {
    return new ComponentSpec(name, true, dependencyKeys, ComposeCapability.CHOOSES_PER_APPLY,
        Duration.ZERO, 2);
  }

  private static RetainedStateBudget budget() {
    var budget = new RetainedStateBudget();
    budget.declare(DefaultEngineComponentRegistry.ATTEMPTED_CONFIGURATIONS, 1, "D1");
    return budget;
  }

  private static int retainedCount(RetainedStateBudget budget) {
    return budget.snapshot().stream()
        .filter(row -> row.kind().equals(DefaultEngineComponentRegistry.ATTEMPTED_CONFIGURATIONS))
        .findFirst().orElseThrow().count();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for latch");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for latch", failure);
    }
  }
}
