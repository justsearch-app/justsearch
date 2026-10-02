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
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComposeEvidence;
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
  private static final ComposeEvidence QUERY_COMPOSITION = new ComposeEvidence(
      ComposeEvidence.Mode.IN_PLACE, "candidate_fits_after_source_release", 10L, 20L);

  @Test
  void sharedPolicyDispatchesEveryDeclaredOwnerInOneBatch() {
    String key = "policy.gpu_acceleration_enabled";
    var before = ResolvedConfig.builder().putDefault(key, "true").build();
    var after = ResolvedConfig.builder().putDefault(key, "false").build();
    var affected = ConfigApplyScopes.classify(before, after).component();
    assertEquals(Map.of("encoders", Set.of(key)), affected);
    var registry = emptyRegistry();
    when(registry.snapshot()).thenReturn(new EngineComponentSnapshot(0, List.of(
        declared("encoders", Set.of(key)), declared("generative", Set.of(key)),
        declared("index", Set.of("unrelated")))));
    var lease = mock(EngineComponentRegistry.ApplyLease.class);
    var batch = mock(EngineComponentRegistry.PreparedBatch.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenReturn(batch);
    var query = mock(FixedSettingsComponentComposer.QueryRolePreparedOwner.class);
    when(query.selection()).thenReturn(new QueryRoleSelection(
        QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()));
    when(query.composition()).thenReturn(java.util.Optional.of(QUERY_COMPOSITION));
    when(query.observation()).thenReturn(declared("encoders", Set.of(key)));
    var generative = new RecordingOwner("generative");
    var composer = new FixedSettingsComponentComposer(registry);
    composer.register("encoders", (candidate, desired, keys) -> {
      assertEquals(Set.of(key), keys);
      return query;
    });
    composer.register("generative", (candidate, desired, keys) -> {
      assertEquals(Set.of(key), keys);
      return generative.prepare(candidate, desired, keys);
    });
    composer.seal();

    var prepared = composer.prepare(CANDIDATE, after, affected);
    assertEquals(1, generative.preparedCount);
    prepared.validate();
    prepared.install();
    prepared.notifyObservers();
    prepared.retire();
    verify(registry).prepareBatch(org.mockito.ArgumentMatchers.argThat(
        rows -> rows.keySet().equals(Set.of("encoders", "generative"))));
    verify(batch).install();
    verify(query).install();
    assertEquals(1, generative.prepared.installCount);
    verify(lease).close();
  }

  @Test
  void missingSharedConsumerRefusesBeforeTakingApplyPermit() {
    String key = "policy.gpu_acceleration_enabled";
    var registry = emptyRegistry();
    when(registry.snapshot()).thenReturn(new EngineComponentSnapshot(0,
        List.of(declared("generative", Set.of(key)))));
    var composer = new FixedSettingsComponentComposer(registry);
    composer.register("encoders", new RecordingOwner("encoders"));
    composer.seal();
    var refused = assertThrows(SettingsCommitOwner.Refused.class,
        () -> composer.prepare(CANDIDATE, DESIRED, Map.of("encoders", Set.of(key))));
    assertEquals("COMPONENT_PREPARATION_REQUIRED", refused.response().errorCode().orElseThrow());
    assertEquals("generative", refused.response().errorDetails().get("component"));
    verify(registry, never()).tryApply();
  }

  private static EngineComponentSnapshot.Component declared(String name, Set<String> keys) {
    var spec = new ComponentSpec(name, false, keys, BESIDE, Duration.ofSeconds(1), 1);
    return new EngineComponentSnapshot.Component(spec, READY, null, Instant.EPOCH, 0,
        "applied-" + name, "desired-" + name, null, 0, null);
  }

  @Test
  void missingPhysicalOwnerRefusesWithAComponentCodeBeforeTakingApplyPermit() {
    EngineComponentRegistry registry = emptyRegistry();
    FixedSettingsComponentComposer composer = new FixedSettingsComponentComposer(registry);
    composer.seal();

    SettingsCommitOwner.Refused refusal = assertThrows(SettingsCommitOwner.Refused.class,
        () -> composer.prepare(CANDIDATE, DESIRED, Map.of("encoders", Set.of("model"))));

    assertEquals("COMPONENT_PREPARATION_REQUIRED", refusal.response().errorCode().orElseThrow());
    assertEquals("encoders", refusal.response().errorDetails().get("component"));
    verify(registry, never()).tryApply();
  }

  @Test
  void encoderOwnerMustSupplyASelectionBeforeItsObservationCanCommit() {
    EngineComponentRegistry registry = emptyRegistry();
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    FixedSettingsComponentComposer composer = new FixedSettingsComponentComposer(registry);
    RecordingOwner incomplete = new RecordingOwner("encoders");
    composer.register("encoders", incomplete);
    composer.seal();

    assertThrows(IllegalStateException.class,
        () -> composer.prepare(CANDIDATE, DESIRED, Map.of("encoders", Set.of("model"))));
    assertEquals(1, incomplete.prepared.abortCount);
    verify(lease).close();
    verify(registry, never()).prepareBatch(anyMap());
  }

  @Test
  void encoderOwnerSelectionIsExposedByThePreparedComposite() {
    EngineComponentRegistry registry = emptyRegistry();
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.PreparedBatch batch = mock(EngineComponentRegistry.PreparedBatch.class);
    AtomicReference<Map<String, EngineComponentSnapshot.Component>> replacements =
        new AtomicReference<>();
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenAnswer(invocation -> {
      replacements.set(invocation.getArgument(0));
      return batch;
    });
    var selection = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    FixedSettingsComponentComposer.QueryRolePreparedOwner prepared =
        mock(FixedSettingsComponentComposer.QueryRolePreparedOwner.class);
    when(prepared.selection()).thenReturn(selection);
    when(prepared.composition()).thenReturn(java.util.Optional.of(QUERY_COMPOSITION));
    when(prepared.observation()).thenReturn(
        new RecordingPrepared("encoders", null, new ArrayList<>()).observation());
    FixedSettingsComponentComposer composer = new FixedSettingsComponentComposer(registry);
    composer.register("encoders", (candidate, desired, keys) -> prepared);
    composer.seal();

    var composite = composer.prepare(CANDIDATE, DESIRED,
        Map.of("encoders", Set.of("model")));
    assertEquals(selection, composite.queryRoleSelection().orElseThrow());
    assertEquals(QUERY_COMPOSITION, composite.composition().orElseThrow());
    composite.validate();
    assertEquals(QUERY_COMPOSITION, replacements.get().get("encoders").lastCompose());
    composite.abort();
    verify(lease).close();
  }

  @Test
  void secondOwnerPreparationFailureAbortsEarlierOwnerAndClosesExactApplyLease() {
    EngineComponentRegistry registry = emptyRegistry();
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
    assertEquals(failure, first.prepared.abortCause);
    assertEquals(0, second.preparedCount);
    verify(registry, never()).prepareBatch(anyMap());
    verify(lease, times(1)).close();
  }

  @Test
  void installedFaultObservationFallsBetweenFirstAndSecondOwnerPreparation() {
    EngineComponentRegistry registry = emptyRegistry();
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
    assertEquals(marker, first.prepared.abortCause);
    verify(registry, never()).prepareBatch(anyMap());
    verify(lease).close();
  }

  @Test
  void restoredOwnerMayAnnotateFailureWithoutRetainingTheApplyPermit() {
    EngineComponentRegistry registry = emptyRegistry();
    EngineComponentRegistry.ApplyLease firstLease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.ApplyLease secondLease = mock(EngineComponentRegistry.ApplyLease.class);
    when(registry.tryApply()).thenReturn(new Acquired(firstLease), new Acquired(secondLease));
    RecordingOwner first = new RecordingOwner("first");
    RecordingOwner second = new RecordingOwner("second");
    first.annotateAbortCause = true;
    second.prepareFailure = new IllegalStateException("second owner refused");
    FixedSettingsComponentComposer composer = composer(registry, first, second);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> composer.prepare(CANDIDATE, DESIRED, affected("first", "second")));

    assertEquals(1, failure.getSuppressed().length);
    verify(firstLease).close();
    second.prepareFailure = null;
    var next = composer.prepare(CANDIDATE, DESIRED, affected("first", "second"));
    next.abort();
    verify(registry, times(2)).tryApply();
    verify(secondLease).close();
  }

  @Test
  void precommitValidationRefusalAbortsAllOwnersAndLeavesInstallationUntouched() {
    EngineComponentRegistry registry = emptyRegistry();
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
    EngineComponentRegistry registry = emptyRegistry();
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
    EngineComponentRegistry registry = emptyRegistry();
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

  @Test
  void generationObservationJoinsSettingsOwnerInOnePrecommitBatch() {
    EngineComponentRegistry registry = emptyRegistry();
    EngineComponentRegistry.ApplyLease lease = mock(EngineComponentRegistry.ApplyLease.class);
    EngineComponentRegistry.PreparedBatch batch = mock(EngineComponentRegistry.PreparedBatch.class);
    AtomicReference<Map<String, EngineComponentSnapshot.Component>> replacements =
        new AtomicReference<>();
    when(registry.tryApply()).thenReturn(new Acquired(lease));
    when(registry.prepareBatch(anyMap())).thenAnswer(invocation -> {
      replacements.set(invocation.getArgument(0));
      return batch;
    });
    var owner = new RecordingOwner("generative");
    var composer = new FixedSettingsComponentComposer(registry);
    composer.register("generative", owner);
    composer.seal();
    var prepared = composer.prepare(CANDIDATE, DESIRED,
        Map.of("generative", Set.of("chatProfile")));
    var encoderSpec = new ComponentSpec("encoders", false, Set.of(), BESIDE,
        Duration.ofSeconds(1), 1);
    var encoder = new EngineComponentSnapshot.Component(encoderSpec, READY, null,
        Instant.EPOCH, 0, "model-b", "model-b", null, 0, null);
    prepared.includeObservation(encoder);
    assertThrows(IllegalStateException.class, () -> prepared.includeObservation(encoder));

    prepared.validate();
    prepared.install();
    prepared.notifyObservers();
    prepared.retire();

    assertEquals(Set.of("generative", "encoders"), replacements.get().keySet());
    assertEquals(encoder, replacements.get().get("encoders"));
    verify(registry, times(1)).prepareBatch(anyMap());
    verify(batch, times(1)).install();
    verify(lease).close();
  }

  private static EngineComponentRegistry emptyRegistry() {
    var registry = mock(EngineComponentRegistry.class);
    when(registry.snapshot()).thenReturn(new EngineComponentSnapshot(0, List.of()));
    return registry;
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
    private boolean annotateAbortCause;
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
      prepared.annotateAbortCause = annotateAbortCause;
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
    private boolean annotateAbortCause;
    private Throwable abortCause;

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

    @Override public void includeObservation(EngineComponentSnapshot.Component unexpected) {
      throw new UnsupportedOperationException("Recording owner has no generation projection");
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

    @Override
    public void abort(Throwable cause) {
      abortCause = cause;
      if (annotateAbortCause) cause.addSuppressed(new IllegalStateException("A restored"));
      abort();
    }
  }
}
