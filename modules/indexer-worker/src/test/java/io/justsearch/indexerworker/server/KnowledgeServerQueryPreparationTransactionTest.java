/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.ort.EncoderRole;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical query-owner rollback coverage through the real settings transaction. */
final class KnowledgeServerQueryPreparationTransactionTest {
  @Test
  void laterPhysicalOwnerFailureRestoresExactQueryA(@TempDir Path dir) throws Exception {
    var marker = new IllegalStateException("later generative owner rejected B");
    try (var f = new TransactionFixture(dir, Failure.LATER_OWNER, marker)) {
      f.candidate.setChatEnabled(true);

      var failure = assertThrows(IllegalStateException.class, f::apply);

      assertSame(marker, failure);
      f.assertExactARestored();
    }
  }

  @Test
  void registryValidationFailureRestoresExactQueryA(@TempDir Path dir) throws Exception {
    var marker = new IllegalStateException("prepared registry batch rejected B");
    try (var f = new TransactionFixture(dir, Failure.VALIDATION, marker)) {
      var failure = assertThrows(IllegalStateException.class, f::apply);

      assertSame(marker, failure);
      f.assertExactARestored();
    }
  }

  @Test
  void precommitCancellationRestoresExactQueryA(@TempDir Path dir) throws Exception {
    try (var f = new TransactionFixture(dir, Failure.CANCELLATION, null)) {
      assertThrows(CancellationException.class, f::apply);

      f.assertExactARestored();
    }
  }

  @Test
  void settingsReplacementIoFailureRestoresExactQueryA(@TempDir Path dir) throws Exception {
    var marker = new IOException("settings move rejected before replacement");
    try (var f = new TransactionFixture(dir, Failure.REPLACEMENT, marker)) {
      var failure = assertThrows(IllegalStateException.class, f::apply);

      assertSame(marker, failure.getCause());
      f.assertExactARestored();
    }
  }

  private enum Failure { LATER_OWNER, VALIDATION, CANCELLATION, REPLACEMENT }

  private static final class TransactionFixture implements AutoCloseable {
    private final KnowledgeServerQuerySettingsOwnerTest.QueryFixture query;
    private final UiSettingsStore settings;
    private final ConfigStore config;
    private final ResolvedConfig exactA;
    private final SettingsWitness witnessA = new SettingsWitness(0, null);
    private final byte[] bytesA;
    private final EngineComponentRegistry.ApplyLease applyLease =
        mock(EngineComponentRegistry.ApplyLease.class);
    private final EngineComponentRegistry.PreparedBatch registryBatch =
        mock(EngineComponentRegistry.PreparedBatch.class);
    private final List<InferenceSurface> composed = new ArrayList<>();
    private final AtomicBoolean committed = new AtomicBoolean();
    private final AtomicBoolean uncertain = new AtomicBoolean();
    private final Object attemptControl;
    private final SettingsCommitCoordinator coordinator;
    private final SettingsCommitOwner.Reservation reservation;
    private final org.mockito.MockedStatic<InferenceCompositionRoot> composition;
    private final UiSettings candidate;
    private final long reservationId = 1L;

    TransactionFixture(Path dir, Failure failure, Throwable marker) throws Exception {
      query = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
      composition = query.composition();
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(
          any(), any(), any(), any(), any())).thenAnswer(call -> {
            var projection = call.<EncoderConfigurationProjection>getArgument(0);
            var selection = call.<QueryRoleSelection>getArgument(1);
            var fresh = query.freshSurface(false);
            var adapted = new InferenceSurface(Optional.empty(), Optional.empty(), fresh.reranker(),
                Optional.empty(), Optional.empty(), Optional.empty(), null, fresh.handles(),
                new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
                    Set.of(EncoderRole.RERANKER), Set.of(), Optional.of(selection)));
            composed.add(adapted);
            return adapted;
          });

      settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          dir.resolve("settings.json"));
      settings.replacePrepared(settings.prepareExact(new UiSettings(), witnessA, query.selection));
      bytesA = Files.readAllBytes(settings.settingsPath());
      exactA = query.configuration;
      config = new ConfigStore(exactA);

      var registry = mock(EngineComponentRegistry.class);
      when(registry.tryApply())
          .thenReturn(new EngineComponentRegistry.ApplyAttempt.Acquired(applyLease));
      when(registry.prepareBatch(anyMap())).thenReturn(registryBatch);
      if (failure == Failure.VALIDATION) {
        doThrow(marker).when(registryBatch).validate();
      }
      var components = new FixedSettingsComponentComposer(registry);
      components.register("encoders", queryOwner());
      if (failure == Failure.LATER_OWNER) {
        components.register("generative", (ignoredCandidate, ignoredDesired, ignoredKeys) -> {
          throw (RuntimeException) marker;
        });
      }
      components.seal();

      coordinator = coordinator(dir, components, failure == Failure.REPLACEMENT ? marker : null);
      coordinator.inspectRecovery(List.of());
      reservation = coordinator.reserve(
          reservationId, OperationKeys.generate(Clock.systemUTC()), witnessA);
      attemptControl = attemptControl(failure != Failure.CANCELLATION);
      candidate = settings.load();
      Path modelDir = dir.resolve("candidate-reranker");
      Files.createDirectories(modelDir);
      Files.writeString(
          modelDir.resolve("model_fp16.onnx"), "candidate-model", StandardCharsets.UTF_8);
      Files.writeString(
          modelDir.resolve("tokenizer.json"), "candidate-tokenizer", StandardCharsets.UTF_8);
      candidate.setRerankerModelPath(modelDir.toString());
    }

    private FixedSettingsComponentComposer.Owner queryOwner() {
      return (candidateSettings, desired, changedKeys) -> {
        var prepared = query.server.prepareQueryRoleSettings(candidateSettings, desired, changedKeys,
            settings.inspect().queryRoles());
        assertEquals(io.justsearch.core.component.ComponentState.READY,
            prepared.observation().state(),
            "physical query B must be realizable before fault injection");
        return new FixedSettingsComponentComposer.QueryRolePreparedOwner() {
          @Override public QueryRoleSelection selection() { return prepared.selection(); }
          @Override public Optional<io.justsearch.core.component.ComposeEvidence> composition() {
            return Optional.of(prepared.composition());
          }
          @Override public EngineComponentSnapshot.Component observation() {
            return prepared.observation();
          }
          @Override public void includeObservation(EngineComponentSnapshot.Component unexpected) {
            throw new UnsupportedOperationException("Query owner has no generation projection");
          }
          @Override public void withOwnerLocks(Runnable publication) {
            prepared.withOwnerLocks(publication);
          }
          @Override public void validate() { prepared.validate(); }
          @Override public void install() { prepared.install(); }
          @Override public void notifyObservers() { prepared.notifyObservers(); }
          @Override public void retire() { prepared.retire(); }
          @Override public void abort() { prepared.abort(); }
          @Override public void abort(Throwable cause) { prepared.abort(cause); }
        };
      };
    }

    private SettingsCommitCoordinator coordinator(Path dir, SettingsComponentComposer components,
        Throwable replacementFailure) throws Exception {
      var replacementType =
          Class.forName(SettingsCommitCoordinator.class.getName() + "$Replacement");
      Object replacement = Proxy.newProxyInstance(replacementType.getClassLoader(),
          new Class<?>[] {replacementType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);
            if (replacementFailure != null) throw replacementFailure;
            settings.replacePrepared((UiSettingsStore.PreparedSettings) args[0]);
            return null;
          });
      var constructor = java.util.Arrays.stream(
          SettingsCommitCoordinator.class.getDeclaredConstructors())
          .filter(candidate -> candidate.getParameterCount() == 8
              && candidate.getParameterTypes()[5] == replacementType)
          .findFirst().orElseThrow();
      constructor.setAccessible(true);
      Function<UiSettings, ResolvedConfig> prepareConfig = ui -> new ResolvedConfigBuilder()
          .putDefault("justsearch.data.dir", dir.toString())
          .putSettings("justsearch.rerank.model_path", ui.getRerankerModelPath())
          .build();
      Function<UiSettings, OperationResult> prepareResponse =
          ignored -> OperationResult.success("prepared");
      return (SettingsCommitCoordinator) constructor.newInstance(
          settings, config, (Runnable) () -> {}, prepareConfig, prepareResponse,
          replacement, (BooleanSupplier) () -> false, components);
    }

    private Object attemptControl(boolean admit) throws Exception {
      Class<?> type = Class.forName(SettingsCommitOwner.class.getName() + "$AttemptControl");
      return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
          (proxy, method, args) -> switch (method.getName()) {
            case "admitCommit" -> admit;
            case "committed" -> { committed.set(true); yield null; }
            case "uncertain" -> { uncertain.set(true); yield null; }
            case "toString" -> "physical-query-attempt-control";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Unexpected AttemptControl method: " + method);
          });
    }

    void apply() throws Exception {
      Class<?> controlType = attemptControl.getClass().getInterfaces()[0];
      var apply = SettingsCommitCoordinator.class.getMethod(
          "apply", SettingsCommitOwner.Reservation.class, UiSettings.class, controlType);
      try {
        apply.invoke(coordinator, reservation, candidate, attemptControl);
      } catch (InvocationTargetException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new IllegalStateException(cause);
      }
    }

    void assertExactARestored() throws Exception {
      assertFalse(committed.get(), "a precommit failure cannot report a committed receipt");
      assertFalse(uncertain.get(), "a witnessed precommit failure must remain cleanly precommit");
      assertArrayEquals(bytesA, Files.readAllBytes(settings.settingsPath()));
      var snapshot = settings.inspect();
      assertEquals(witnessA, snapshot.witness());
      assertEquals(query.selection, snapshot.queryRoles());
      assertSame(exactA, config.get());
      assertEquals(2, composed.size(), "B and restored A must be separate physical compositions");
      QueryRoleSelection selectedB = composed.get(0).componentObservation()
          .querySelection().orElseThrow();
      assertNotEquals(query.selection, selectedB);
      assertEquals(Path.of(candidate.getRerankerModelPath()).toAbsolutePath().normalize(),
          selectedB.reranker().model().path().getParent());
      assertEquals(query.selection,
          composed.get(1).componentObservation().querySelection().orElseThrow());
      assertNotSame(composed.get(0).handles().getFirst(), composed.get(1).handles().getFirst());
      verify(composed.get(0).handles().getFirst(), atLeastOnce()).close();
      verify(composed.get(1).handles().getFirst(), never()).close();
      verify(query.candidate).close();
      verify(query.producer, never()).pauseProducerForCutover(anyLong());
      verify(applyLease).close();
      query.assertRestored();
      try (var serving = query.server.captureServingView()) {
        QueryRoleSet restored = querySet(serving);
        assertNotSame(query.queryA, restored);
        assertFalse(restored.isClosed());
        assertEquals(query.selection,
            restored.surfaceForOwner().componentObservation().querySelection().orElseThrow());
      }
      assertTrue(query.queryA.isClosed());
      assertFalse(query.index.isClosed());
    }

    @Override public void close() throws Exception {
      coordinator.releaseAfterTerminal(reservationId);
      composition.close();
      query.close();
    }
  }

  private static QueryRoleSet querySet(KnowledgeServer.ServingLease lease) throws Exception {
    var captured = lease.getClass().getDeclaredField("captured");
    captured.setAccessible(true);
    Object view = captured.get(lease);
    var roles = view.getClass().getDeclaredField("queryRoleSet");
    roles.setAccessible(true);
    return (QueryRoleSet) roles.get(view);
  }
}
