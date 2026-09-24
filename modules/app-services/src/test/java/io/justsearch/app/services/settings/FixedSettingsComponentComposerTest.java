/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.core.component.ComponentSpec.ComposeCapability.BESIDE;
import static io.justsearch.core.component.ComponentState.READY;
import static io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt.Acquired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

/** Contract tests for fixed boot owner composition and one registry publication. */
final class FixedSettingsComponentComposerTest {
  private static final UiSettings CANDIDATE = new UiSettings();
  private static final ResolvedConfig DESIRED = ConfigStoreRebuilder.prepare(CANDIDATE);

  @Test
  void missingPhysicalOwnerRefusesWithAComponentCodeBeforeTakingApplyPermit() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    FixedSettingsComponentComposer composer = new FixedSettingsComponentComposer(registry);
    composer.seal();

    SettingsCommitOwner.Refused refusal = assertThrows(SettingsCommitOwner.Refused.class,
        () -> composer.prepare(CANDIDATE, DESIRED, Map.of("encoders", Set.of("model"))));

    assertEquals("COMPONENT_PREPARATION_REQUIRED", refusal.response().errorCode().orElseThrow());
    assertEquals("encoders", refusal.response().errorDetails().get("component"));
    verify(registry, never()).tryApply();
  }

  @Test
  void secondOwnerPreparationFailureAbortsEarlierOwnerAndClosesExactApplyLease() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));

    RecordingOwner first = new RecordingOwner("first");
    RecordingOwner second = new RecordingOwner("second");
    IllegalStateException failure = new IllegalStateException("second owner refused");
    second.prepareFailure = failure;
    FixedSettingsComponentComposer composer = composer(registry, first, second);

    IllegalStateException actual = assertThrows(IllegalStateException.class,
        () -> composer.prepare(CANDIDATE, DESIRED, affected("first", "second")));

    assertEquals(failure, actual);
    assertEquals(1, first.prepared.abortCount);
    assertEquals(0, second.preparedCount);
    verify(registry, never()).prepareBatch(anyMap());
    verify(lease, times(1)).close();
  }

  @Test
  void installedFaultObservationFallsBetweenFirstAndSecondOwnerPreparation() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    RecordingOwner first = new RecordingOwner("first");
    RecordingOwner second = new RecordingOwner("second");
    FixedSettingsComponentComposer composer = composer(registry, first, second);
    var marker = new IllegalStateException("fault marker");

    assertEquals(marker, assertThrows(IllegalStateException.class,
        () -> composer.prepare(CANDIDATE, DESIRED, affected("first", "second"),
            SettingsCandidateContext.NONE, component -> {
              assertEquals("first", component);
              assertEquals(1, first.preparedCount);
              assertEquals(0, second.preparedCount);
              throw marker;
            })));
    assertEquals(1, first.prepared.abortCount);
    verify(registry, never()).prepareBatch(anyMap());
    verify(lease).close();
  }

  @Test
  void precommitValidationRefusalAbortsAllOwnersAndLeavesInstallationUntouched() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.PreparedBatch batch = mock(EngineComponentRegistry.PreparedBatch.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenReturn(batch);
    doThrow(new IllegalStateException("stale registry revision")).when(batch).validate();

    RecordingOwner first = new RecordingOwner("first");
    RecordingOwner second = new RecordingOwner("second");
    FixedSettingsComponentComposer composer = composer(registry, first, second);
    SettingsComponentComposer.Prepared prepared = composer.prepare(
        CANDIDATE, DESIRED, affected("first", "second"));

    assertThrows(IllegalStateException.class, prepared::validate);
    prepared.abort();

    assertEquals(1, first.prepared.abortCount);
    assertEquals(1, second.prepared.abortCount);
    assertEquals(1, first.prepared.validateCount);
    assertEquals(1, second.prepared.validateCount);
    assertEquals(0, first.prepared.installCount);
    assertEquals(0, second.prepared.installCount);
    verify(batch, never()).install();
    verify(lease, times(1)).close();
  }

  @Test
  void failedOwnerCleanupRetainsTheApplyPermit() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.PreparedBatch batch = mock(EngineComponentRegistry.PreparedBatch.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenReturn(batch);

    RecordingOwner first = new RecordingOwner("first");
    RecordingOwner second = new RecordingOwner("second");
    FixedSettingsComponentComposer composer = composer(registry, first, second);
    SettingsComponentComposer.Prepared prepared = composer.prepare(
        CANDIDATE, DESIRED, affected("first", "second"));
    first.prepared.retireFailure = new IllegalStateException("still live");

    assertThrows(IllegalStateException.class, prepared::retire);
    verify(lease, never()).close();

    second.prepareFailure = new IllegalStateException("second refused");
    first.abortFailure = new IllegalStateException("abort still live");
    assertThrows(IllegalStateException.class,
        () -> composer.prepare(CANDIDATE, DESIRED, affected("first", "second")));
    verify(lease, never()).close();
  }

  @Test
  void successfulInstallUsesOneBatchAndNotifiesAndRetiresOutsidePublicationLock() {
    EngineComponentRegistry registry = mock(EngineComponentRegistry.class);
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.PreparedBatch batch = mock(EngineComponentRegistry.PreparedBatch.class);
    ReentrantReadWriteLock publicationLock = new ReentrantReadWriteLock();
    List<String> events = new ArrayList<>();
    AtomicReference<Map<String, EngineComponentSnapshot.Component>> replacements =
        new AtomicReference<>();
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenAnswer(invocation -> {
      org.junit.jupiter.api.Assertions.assertTrue(publicationLock.isWriteLockedByCurrentThread(),
          "The registry batch must observe the final precommit publication boundary");
      replacements.set(invocation.getArgument(0));
      events.add("batch.prepare");
      return batch;
    });
    doAnswer(invocation -> {
      assertFalse(publicationLock.isWriteLockedByCurrentThread());
      events.add("lease.close");
      return null;
    }).when(lease).close();
    doAnswer(invocation -> {
      events.add("batch.validate");
      return null;
    }).when(batch).validate();
    doAnswer(invocation -> {
      events.add("batch.install");
      return null;
    }).when(batch).install();
    doAnswer(invocation -> {
      assertFalse(publicationLock.isWriteLockedByCurrentThread());
      events.add("batch.notify");
      return null;
    }).when(batch).notifyObservers();

    RecordingOwner first = new RecordingOwner("first", publicationLock, events);
    RecordingOwner second = new RecordingOwner("second", publicationLock, events);
    FixedSettingsComponentComposer composer = composer(registry, first, second);
    SettingsComponentComposer.Prepared prepared = composer.prepare(
        CANDIDATE, DESIRED, affected("first", "second"));

    prepared.withOwnerLocks(() -> {
      publicationLock.writeLock().lock();
      try {
        prepared.validate();
        prepared.install();
      } finally {
        publicationLock.writeLock().unlock();
      }
    });
    prepared.notifyObservers();
    prepared.retire();

    assertEquals(List.of("first", "second"), List.copyOf(replacements.get().keySet()));
    assertEquals(List.of(
        "first.prepare", "second.prepare",
        "first.lock.enter", "second.lock.enter",
        "first.validate", "second.validate", "batch.prepare", "batch.validate",
        "first.install", "second.install", "batch.install",
        "second.lock.exit", "first.lock.exit",
        "batch.notify", "first.notify", "second.notify",
        "first.retire", "second.retire", "lease.close"), events);
    verify(registry, times(1)).prepareBatch(anyMap());
    verify(batch, times(1)).notifyObservers();
    verify(lease, times(1)).close();
  }

  private static FixedSettingsComponentComposer composer(EngineComponentRegistry registry,
      RecordingOwner first, RecordingOwner second) {
    FixedSettingsComponentComposer composer = new FixedSettingsComponentComposer(registry);
    composer.register("first", first);
    composer.register("second", second);
    composer.seal();
    return composer;
  }

  private static Map<String, Set<String>> affected(String first, String second) {
    return Map.of(first, Set.of("first.key"), second, Set.of("second.key"));
  }

  private static final class RecordingOwner implements FixedSettingsComponentComposer.Owner {
    private final String name;
    private final ReentrantReadWriteLock publicationLock;
    private final List<String> events;
    private RuntimeException prepareFailure;
    private RuntimeException abortFailure;
    private RecordingPrepared prepared;
    private int preparedCount;

    private RecordingOwner(String name) {
      this(name, null, new ArrayList<>());
    }

    private RecordingOwner(String name, ReentrantReadWriteLock publicationLock,
        List<String> events) {
      this.name = name;
      this.publicationLock = publicationLock;
      this.events = events;
    }

    @Override
    public FixedSettingsComponentComposer.PreparedOwner prepare(UiSettings candidate,
        ResolvedConfig desired, Set<String> changedKeys) {
      events.add(name + ".prepare");
      if (prepareFailure != null) throw prepareFailure;
      preparedCount++;
      prepared = new RecordingPrepared(name, publicationLock, events);
      prepared.abortFailure = abortFailure;
      return prepared;
    }
  }

  private static final class RecordingPrepared
      implements FixedSettingsComponentComposer.PreparedOwner {
    private final String name;
    private final ReentrantReadWriteLock publicationLock;
    private final List<String> events;
    private int validateCount;
    private int installCount;
    private int abortCount;
    private RuntimeException retireFailure;
    private RuntimeException abortFailure;

    private RecordingPrepared(String name, ReentrantReadWriteLock publicationLock,
        List<String> events) {
      this.name = name;
      this.publicationLock = publicationLock;
      this.events = events;
    }

    @Override
    public EngineComponentSnapshot.Component observation() {
      ComponentSpec spec = new ComponentSpec(name, true, Set.of(), BESIDE,
          Duration.ofSeconds(1), 1);
      return new EngineComponentSnapshot.Component(spec, READY, null, Instant.EPOCH, 0,
          "prepared-" + name, "desired-" + name, null, 0, "test");
    }

    @Override
    public void validate() {
      validateCount++;
      events.add(name + ".validate");
    }

    @Override
    public void withOwnerLocks(Runnable publication) {
      assertFalse(publicationLock.isWriteLockedByCurrentThread(),
          "Owner lifecycle locks must precede publication write");
      synchronized (this) {
        events.add(name + ".lock.enter");
        try { publication.run(); }
        finally { events.add(name + ".lock.exit"); }
      }
    }

    @Override
    public void install() {
      installCount++;
      events.add(name + ".install");
    }

    @Override
    public void notifyObservers() {
      assertFalse(publicationLock != null && publicationLock.isWriteLockedByCurrentThread());
      events.add(name + ".notify");
    }

    @Override
    public void retire() {
      assertFalse(publicationLock != null && publicationLock.isWriteLockedByCurrentThread());
      events.add(name + ".retire");
      if (retireFailure != null) throw retireFailure;
    }

    @Override
    public void abort() {
      abortCount++;
      events.add(name + ".abort");
      if (abortFailure != null) throw abortFailure;
    }
  }
}
