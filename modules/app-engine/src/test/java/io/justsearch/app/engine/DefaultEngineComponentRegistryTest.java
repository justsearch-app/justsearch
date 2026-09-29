/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentSpec.ComposeCapability;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.ComposeEvidence.Mode;
import io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

final class DefaultEngineComponentRegistryTest {
  @Test
  void wrappedHandlePublishesInitialRecoveredVersionWithHeldCauseAndDesiredVersion() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var handle = new ReasonRetainingComponentHandle(registry.register(spec("index", Set.of())));
      handle.setDesiredVersion("desired-b");
      handle.transition(ComponentState.FAILED, "worker.lost", "physical failure");
      var before = handle.snapshot();
      var replacement = new EngineComponentSnapshot.Component(before.spec(), before.state(),
          before.reasonCode(), before.stateSince(), before.stateSinceMonotonicNanos(), "applied-a",
          before.desiredVersion(), before.lastCompose(), before.recoveryAttempts(), before.evidence());
      var prepared = handle.prepareReplacement(replacement);
      lock.writeLock().lock();
      try {
        prepared.validate();
        prepared.install();
      } finally {
        lock.writeLock().unlock();
      }
      prepared.notifyObservers();
      assertEquals(replacement, handle.snapshot());
      assertEquals(replacement, prepared.snapshot().components().getFirst());
    }
  }

  @Test
  void exactConditionalTransitionReturnsPublicationBeforeAListenerCanAdvanceIt() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var handle = registry.register(spec("index", Set.of()));
      handle.transition(ComponentState.FAILED, "worker.lost", "physical owner failed");
      var expected = handle.snapshot();
      try (var ignored = registry.subscribe(snapshot -> {
        var row = snapshot.components().getFirst();
        if (row.state() == ComponentState.STARTING) {
          handle.transition(ComponentState.READY, "worker.ready", "listener raced completion");
        }
      })) {
        var publication = handle.tryTransitionIfUnchanged(expected, ComponentState.STARTING,
            "component.recovering", "attempt admitted").orElseThrow();
        assertEquals(ComponentState.STARTING, publication.state());
        assertEquals("attempt admitted", publication.evidence());
        assertEquals(ComponentState.READY, handle.snapshot().state());
      }
    }
  }

  @Test
  void exactConditionalTransitionRejectsStaleCompareAndSwapWithoutPublishing() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = registry.register(spec("index", Set.of()));
      handle.transition(ComponentState.FAILED, "worker.lost", "old row");
      var expected = handle.snapshot();
      handle.transition(ComponentState.READY, "worker.ready", "new row");
      var current = registry.snapshot();
      assertTrue(handle.tryTransitionIfUnchanged(expected, ComponentState.STARTING,
          "component.recovering", "stale attempt").isEmpty());
      assertEquals(current, registry.snapshot());
    }
  }

  @Test
  void exactConditionalTransitionMatchedNoOpReturnsCurrentRowWithoutRevisionOrListener() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = registry.register(spec("index", Set.of()));
      handle.transition(ComponentState.FAILED, "worker.lost", "same row");
      var expected = handle.snapshot();
      long revision = registry.snapshot().revision();
      var notifications = new AtomicInteger();
      try (var ignored = registry.subscribe(snapshot -> notifications.incrementAndGet())) {
        var returned = handle.tryTransitionIfUnchanged(expected, expected.state(),
            expected.reasonCode(), expected.evidence()).orElseThrow();
        assertEquals(expected, returned);
        assertEquals(revision, registry.snapshot().revision());
        assertEquals(0, notifications.get());
      }
    }
  }

  @Test
  void retentionExactTransitionReturnsHeldReasonBeforeListenerCanClearIt() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var raw = registry.register(spec("index", Set.of()));
      var handle = new ReasonRetainingComponentHandle(raw);
      handle.transition(ComponentState.FAILED, "worker.index_corrupt", "repair stored index");
      var expected = handle.snapshot();
      try (var ignored = registry.subscribe(snapshot -> {
        var row = snapshot.components().getFirst();
        if (row.state() == ComponentState.STARTING) {
          raw.transition(ComponentState.READY, null, "listener cleared recovery");
        }
      })) {
        var returned = handle.tryTransitionIfUnchanged(expected, ComponentState.STARTING,
            "component.recovering", "retrying applied configuration").orElseThrow();
        assertEquals(ComponentState.STARTING, returned.state());
        assertEquals("worker.index_corrupt", returned.reasonCode());
        assertEquals("repair stored index", returned.evidence());
        assertEquals(ComponentState.READY, raw.snapshot().state());
      }
    }
  }

  @Test
  void recoveryResultCarriesOnlyValidTerminalObservations() {
    var observation = new EngineComponentSnapshot.Component(spec("index", Set.of()),
        ComponentState.READY, null, Instant.now(), System.nanoTime(), null, null, null, 0, null);
    assertEquals(ComponentRecoveryAction.Outcome.RECOVERED,
        ComponentRecoveryAction.Result.recovered(observation).outcome());
    assertSame(observation, ComponentRecoveryAction.Result.recovered(observation).observation());
    assertEquals(ComponentRecoveryAction.Outcome.REFUSED,
        ComponentRecoveryAction.Result.REFUSED.outcome());
    assertThrows(IllegalArgumentException.class, () ->
        ComponentRecoveryAction.Result.recovered(observationWithState(ComponentState.FAILED)));
    assertThrows(NullPointerException.class, () ->
        ComponentRecoveryAction.Result.failed(null));
    assertThrows(NullPointerException.class, () ->
        ComponentRecoveryAction.Result.recovered(null));
    assertThrows(IllegalArgumentException.class, () ->
        ComponentRecoveryAction.Result.failed(observation));
    assertThrows(IllegalArgumentException.class, () ->
        new ComponentRecoveryAction.Result(ComponentRecoveryAction.Outcome.REFUSED, observation));
    assertThrows(IllegalArgumentException.class, () ->
        new ComponentRecoveryAction.Result(ComponentRecoveryAction.Outcome.SUPERSEDED, observation));
    assertNull(ComponentRecoveryAction.Result.SUPERSEDED.observation());
  }

  private static EngineComponentSnapshot.Component observationWithState(ComponentState state) {
    return new EngineComponentSnapshot.Component(spec("index", Set.of()), state, null,
        Instant.now(), System.nanoTime(), null, null, null, 0, null);
  }

  @Test
  void recoveryAdmissionPublishesStateAndCountTogetherAndReturnsItsExactObservation() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var handle = registry.register(spec("index", Set.of()));
      handle.setAppliedVersion("applied-a");
      handle.transition(ComponentState.FAILED, "worker.lost", "physical owner failed");
      var expected = handle.snapshot();
      long revision = registry.snapshot().revision();
      var seen = new ArrayList<EngineComponentSnapshot>();
      try (var ignored = registry.subscribe(snapshot -> {
        assertFalse(lock.isWriteLockedByCurrentThread());
        seen.add(snapshot);
        if (snapshot.components().getFirst().state() == ComponentState.STARTING) {
          handle.transition(ComponentState.READY, null, "new owner serves");
        }
      })) {
        var admitted = handle.tryBeginRecovery(expected, "component.recovering", "same config retry")
            .orElseThrow();
        assertEquals(2, seen.size());
        assertEquals(revision + 1, seen.getFirst().revision());
        assertEquals(admitted, seen.getFirst().components().getFirst());
        assertEquals(ComponentState.STARTING, admitted.state());
        assertEquals(1, admitted.recoveryAttempts());
        assertEquals("applied-a", admitted.appliedVersion());
        assertEquals(ComponentState.READY, handle.snapshot().state());
        assertFalse(handle.transitionIfUnchanged(admitted, ComponentState.FAILED,
            "worker.spawn_failed", "obsolete recovery completion"));
      }
    }
  }

  @Test
  void recoveryAdmissionRejectsAChangedConfigurationWithoutSpendingAnAttempt() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = registry.register(spec("index", Set.of()));
      handle.setAppliedVersion("applied-a");
      handle.transition(ComponentState.FAILED, "worker.lost", "physical owner failed");
      var expected = handle.snapshot();
      handle.setAppliedVersion("applied-b");
      var current = registry.snapshot();
      assertTrue(handle.tryBeginRecovery(expected, "component.recovering", "stale retry").isEmpty());
      assertEquals(current, registry.snapshot());
      assertEquals(0, handle.snapshot().recoveryAttempts());
    }
  }

  @Test
  void recoveryAdmissionRetainsFatalRemedyAndOnlyOneContenderCanClaimTheObservation()
      throws Exception {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = new ReasonRetainingComponentHandle(registry.register(spec("index", Set.of())));
      handle.transition(ComponentState.FAILED, "worker.index_schema_mismatch", "repair stored schema");
      var expected = handle.snapshot();
      var start = new CountDownLatch(1);
      var accepted = new AtomicInteger();
      var failure = new AtomicReference<Throwable>();
      Runnable claim = () -> {
        try {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          if (handle.tryBeginRecovery(expected, "component.recovering", "generic retry").isPresent()) {
            accepted.incrementAndGet();
          }
        } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
      };
      Thread first = Thread.ofPlatform().start(claim);
      Thread second = Thread.ofPlatform().start(claim);
      start.countDown();
      first.join(5_000);
      second.join(5_000);
      assertFalse(first.isAlive());
      assertFalse(second.isAlive());
      assertNull(failure.get());
      assertEquals(1, accepted.get());
      assertEquals(1, handle.snapshot().recoveryAttempts());
      assertEquals(ComponentState.STARTING, handle.snapshot().state());
      assertEquals("worker.index_schema_mismatch", handle.snapshot().reasonCode());
      assertEquals("repair stored schema", handle.snapshot().evidence());
    }
  }

  @Test
  void combinedSettingsAndEncoderPublicationHasOneRevisionAndCompleteObserverView() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var generative = registry.register(spec("generative", Set.of()));
      var encoders = registry.register(spec("encoders", Set.of()));
      var observed = new AtomicReference<EngineComponentSnapshot>();
      try (var ignored = registry.subscribe(observed::set)) {
        long before = registry.snapshot().revision();
        var generativeBefore = generative.snapshot();
        var encoderBefore = encoders.snapshot();
        var prepared = registry.prepareBatch(Map.of(
            "generative", new EngineComponentSnapshot.Component(generative.spec(),
                ComponentState.READY, null, generativeBefore.stateSince(),
                generativeBefore.stateSinceMonotonicNanos(), "chat-b", "chat-b", null, 0, null),
            "encoders", new EngineComponentSnapshot.Component(encoders.spec(),
                ComponentState.READY, null, encoderBefore.stateSince(),
                encoderBefore.stateSinceMonotonicNanos(), "model-b", "model-b", null, 0, null)));
        lock.writeLock().lock();
        try {
          prepared.validate();
          prepared.install();
          assertNull(observed.get());
        } finally { lock.writeLock().unlock(); }
        prepared.notifyObservers();

        assertEquals(before + 1, registry.snapshot().revision());
        assertEquals(registry.snapshot(), observed.get());
        assertEquals(ComponentState.READY, generative.snapshot().state());
        assertEquals(ComponentState.READY, encoders.snapshot().state());
        assertEquals("chat-b", generative.snapshot().appliedVersion());
        assertEquals("model-b", encoders.snapshot().appliedVersion());
      }
    }
  }

  @Test
  void handleBoundPublicationNotifiesOnlyAfterPhysicalViewAndSharedLockRelease() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var handle = registry.register(spec("encoders", Set.of()));
      var physicalViewPublished = new AtomicBoolean();
      var observed = new AtomicReference<EngineComponentSnapshot>();
      try (var ignored = registry.subscribe(snapshot -> {
        assertFalse(lock.isWriteLockedByCurrentThread());
        assertTrue(physicalViewPublished.get());
        observed.set(snapshot);
      })) {
        var before = handle.snapshot();
        var desired = new EngineComponentSnapshot.Component(before.spec(), ComponentState.RELOADING,
            null, before.stateSince(), before.stateSinceMonotonicNanos(), before.appliedVersion(),
            before.desiredVersion(), before.lastCompose(), before.recoveryAttempts(),
            "A serves text while B composes");
        var prepared = handle.prepareReplacement(desired);
        lock.writeLock().lock();
        try {
          prepared.validate();
          prepared.install();
          physicalViewPublished.set(true);
          assertNull(observed.get());
          assertThrows(IllegalStateException.class, prepared::notifyObservers);
        } finally {
          lock.writeLock().unlock();
        }
        prepared.notifyObservers();
        assertEquals(ComponentState.RELOADING, observed.get().components().getFirst().state());
        assertThrows(IllegalStateException.class, prepared::notifyObservers);
      }
    }
  }

  @Test
  void preparedBatchBuildsSnapshotBeforeCommitAndNotifiesOutsideSharedLock() {
    var lock = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget(), lock)) {
      var handle = registry.register(spec("index", Set.of()));
      var before = handle.snapshot();
      var observed = new AtomicReference<EngineComponentSnapshot>();
      try (var ignored = registry.subscribe(snapshot -> {
        assertFalse(lock.isWriteLockedByCurrentThread());
        observed.set(snapshot);
      })) {
        var desired = new EngineComponentSnapshot.Component(handle.spec(), ComponentState.READY,
            null, Instant.EPOCH, 0L, "applied", "desired", null, 0, null);
        var prepared = registry.prepareBatch(Map.of("index", desired));

        assertEquals(before, handle.snapshot());
        assertEquals(2L, prepared.snapshot().revision());
        assertEquals(ComponentState.READY, prepared.snapshot().components().getFirst().state());

        prepared.commit();

        assertEquals(prepared.snapshot().components().getFirst(), handle.snapshot());
        assertNotEquals(Instant.EPOCH, handle.snapshot().stateSince());
        assertSame(prepared.snapshot(), observed.get());
        assertThrows(IllegalStateException.class, prepared::commit);
      }
    }
  }

  @Test
  void preparedBatchPreservesContinuousReadyEpochWhenOnlyVersionChanges() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var index = registry.register(spec("index", Set.of()));
      index.transition(ComponentState.READY, null, "serving A");
      var before = index.snapshot();
      var desired = new EngineComponentSnapshot.Component(index.spec(), ComponentState.READY,
          null, Instant.EPOCH, 0L, "version-b", "version-b", null, 0, "serving B");

      registry.prepareBatch(Map.of("index", desired)).commit();

      assertEquals(before.stateSince(), index.snapshot().stateSince());
      assertEquals(before.stateSinceMonotonicNanos(),
          index.snapshot().stateSinceMonotonicNanos());
      assertEquals("version-b", index.snapshot().appliedVersion());
    }
  }

  @Test
  void conditionalPublicationRejectsObsoleteObservationsButAcceptsMatchingNoOps() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var index = registry.register(spec("index", Set.of()));
      var api = registry.register(spec("api", Set.of()));
      index.transition(ComponentState.STARTING, "worker.starting", "opening");
      var observed = index.snapshot();
      var publications = new AtomicInteger();
      var subscription = registry.subscribe(snapshot -> publications.incrementAndGet());
      try (subscription) {
        long revision = registry.snapshot().revision();
        assertTrue(index.transitionIfUnchanged(observed,
            ComponentState.STARTING, "worker.starting", "opening"));
        assertEquals(revision, registry.snapshot().revision());
        assertEquals(0, publications.get());
        api.transition(ComponentState.READY, null, null);
        assertTrue(index.transitionIfUnchanged(observed, ComponentState.READY, null, null),
            "another component's observation cannot invalidate an index sample");
        var ready = index.snapshot();
        int beforeStale = publications.get();
        assertFalse(index.transitionIfUnchanged(observed,
            ComponentState.FAILED, "worker.lost", "obsolete failure"));
        assertEquals(ready, index.snapshot());
        assertEquals(beforeStale, publications.get());
        index.transition(ComponentState.RELOADING, "component.recovering", null);
        index.transition(ComponentState.READY, null, null);
        assertFalse(index.transitionIfUnchanged(ready,
            ComponentState.UNAVAILABLE, "worker.lost", "old ready epoch"));
      }
    }
  }

  @Test
  void onlyOneConcurrentConditionalWriterCanReplaceTheSameObservation() throws Exception {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = registry.register(spec("index", Set.of()));
      var observed = handle.snapshot();
      var start = new CountDownLatch(1);
      var won = new AtomicInteger();
      var failure = new AtomicReference<Throwable>();
      var threads = new ArrayList<Thread>();
      for (var state : List.of(ComponentState.STARTING, ComponentState.FAILED)) {
        var thread = new Thread(() -> {
          try {
            await(start);
            if (handle.transitionIfUnchanged(observed, state, "worker.starting", null)) {
              won.incrementAndGet();
            }
          } catch (RuntimeException | Error error) {
            failure.compareAndSet(null, error);
          }
        });
        threads.add(thread);
        thread.start();
      }
      start.countDown();
      for (var thread : threads) thread.join(TimeUnit.SECONDS.toMillis(5));
      assertTrue(threads.stream().noneMatch(Thread::isAlive));
      assertNull(failure.get(), "neither conditional publisher may crash");
      assertEquals(1, won.get());
      assertEquals(2, registry.snapshot().revision());
    }
  }

  @Test
  void readinessPublicationRejectsAnApiChangeBetweenObservationAndCommit() {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var index = new ReasonRetainingComponentHandle(
          registry.register(spec("index", Set.of())));
      var api = registry.register(spec("api", Set.of()));
      index.transition(ComponentState.STARTING, "worker.starting", null);
      api.transition(ComponentState.READY, null, null);
      var sampled = registry.snapshot();
      var sameIndex = index.snapshot();
      assertTrue(index.transitionIfUnchanged(sampled,
          ComponentState.STARTING, "worker.starting", null));
      assertEquals(sampled.revision(), registry.snapshot().revision());

      // The physical API owner closes after the sampler saw API READY. An index-only
      // comparison would still match and falsely promote this obsolete conjunction.
      api.transition(ComponentState.ABSENT, null, null);
      assertEquals(sameIndex, index.snapshot());
      long afterApiClose = registry.snapshot().revision();
      assertFalse(index.transitionIfUnchanged(sampled, ComponentState.READY, null, null));
      assertEquals(afterApiClose, registry.snapshot().revision());
      assertEquals(ComponentState.STARTING, index.snapshot().state());

      api.transition(ComponentState.READY, null, null);
      assertFalse(index.transitionIfUnchanged(sampled, ComponentState.READY, null, null));
      assertTrue(index.transitionIfUnchanged(registry.snapshot(), ComponentState.READY, null, null));
    }
  }

  @Test
  void reasonRetentionDoesNotHoldAPublicationLockAcrossRegistryCallbacks() throws Exception {
    try (var registry = new DefaultEngineComponentRegistry(budget())) {
      var handle = new ReasonRetainingComponentHandle(
          registry.register(spec("index", Set.of())));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var faultFailure = new AtomicReference<Throwable>();
      var subscription = registry.subscribe(snapshot -> {
        if (snapshot.revision() == 2) {
          entered.countDown();
          await(release);
        }
      });
      var fault = new Thread(() -> {
        try {
          handle.transition(ComponentState.FAILED,
              "worker.index_corrupt", "precise corruption evidence");
        } catch (RuntimeException | Error failure) {
          faultFailure.set(failure);
        }
      });
      try (subscription) {
        fault.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        handle.transition(ComponentState.RELOADING, "component.recovering", "retry narration");
        assertEquals(ComponentState.RELOADING, handle.snapshot().state());
        assertEquals("worker.index_corrupt", handle.snapshot().reasonCode());
        assertEquals("precise corruption evidence", handle.snapshot().evidence());
      } finally {
        release.countDown();
        fault.join(TimeUnit.SECONDS.toMillis(5));
      }
      assertFalse(fault.isAlive());
      assertNull(faultFailure.get(), "the blocked callback must be released, not time out");
      var failedObservation = handle.snapshot();
      handle.transition(ComponentState.READY, null, null);
      assertFalse(handle.transitionIfUnchanged(failedObservation,
          ComponentState.UNAVAILABLE, "worker.lost", "obsolete sample"));
      assertNull(handle.snapshot().reasonCode());
      assertNull(handle.snapshot().evidence());
      handle.transition(ComponentState.FAILED, "worker.spawn.failed", "failed boot");
      handle.transition(ComponentState.RELOADING, "component.recovering", "boot retry");
      assertEquals("component.recovering", handle.snapshot().reasonCode());
      assertEquals("boot retry", handle.snapshot().evidence());
    }
  }

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
      index.recordRecoveryAttempt(null);
      assertEquals(2, index.snapshot().recoveryAttempts());
      assertEquals(complete.evidence(), index.snapshot().evidence(),
          "count-only recovery publication must preserve the physical cause");
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
