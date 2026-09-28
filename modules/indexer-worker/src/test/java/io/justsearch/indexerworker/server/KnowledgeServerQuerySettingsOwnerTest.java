/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerQuerySettingsOwnerTest {
  @Test
  void queryOnlyPublicationRetainsIssuedAThenRetiresItWithoutReplacingIndexOwner(
      @TempDir Path dir) throws Exception {
    var desired = new ResolvedConfigBuilder()
        .putDefault("justsearch.data.dir", dir.toString()).build();
    var projection = EncoderConfigurationProjection.from(desired);
    var indexSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(projection.indexDigest()),
            Set.of(), Set.of()));
    var index = new EncoderSet(indexSurface, new EncoderSet.ModelIdentity(
        IndexFingerprint.ModelFingerprint.present("test"),
        IndexFingerprint.ModelFingerprint.present("test"),
        IndexFingerprint.ModelFingerprint.present("test"), false, 768));
    var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
            Set.of(), Set.of()));
    var queryA = new QueryRoleSet(querySurface);
    var prior = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var runtime = mock(RunningRuntime.class);
    var producer = mock(DefaultWorkerAppServices.class);
    var successor = mock(DefaultWorkerAppServices.class);
    when(successor.ingestService()).thenReturn(mock(WorkerIngestService.class));
    when(producer.prepareQueryServingSuccessor(any(), any(), any())).thenReturn(successor);
    var transfer = mock(DefaultWorkerAppServices.ProducerTransfer.class);
    when(producer.prepareProducerTransferTo(successor)).thenReturn(transfer);
    try (var executors = new TestEngineExecutors()) {
      var encoderComponent = mock(ComponentHandle.class);
      var spec = new ComponentSpec("encoders", false, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ZERO, 0);
      when(encoderComponent.snapshot()).thenReturn(new EngineComponentSnapshot.Component(
          spec, ComponentState.READY, null, Instant.now(), 0L, null, null, null, 0, null));
      var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          RecordedIngestionLifecycle.denied(), null, encoderComponent);
      server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
      server.infraCtx = new InfraContext(WorkerBootFixture.workerConfig(dir),
          mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
          server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
      var old = newServingView(producer, runtime, dir.resolve("active"));
      attach(old, "attachEncoderSet", EncoderSet.class, index);
      attach(old, "attachQueryRoleSet", QueryRoleSet.class, queryA);
      set(server, "servingView", old);
      server.appServices = producer;
      try (var issuedA = server.captureServingView()) {
        assertSame(producer, issuedA.services());
        var aborted = server.prepareQueryRoleSettings(new UiSettings(), desired, Set.of(), prior);
        aborted.abort();
        verify(successor).close();
        try (var stillA = server.captureServingView()) {
          assertSame(producer, stillA.services());
        }

        clearInvocations(successor);
        var prepared = server.prepareQueryRoleSettings(new UiSettings(), desired, Set.of(), prior);
        prepared.withOwnerLocks(() -> {
          prepared.validate();
          prepared.install();
        });
        prepared.notifyObservers();
        prepared.retire();
        verify(transfer).install();
        try (var nowB = server.captureServingView()) {
          assertSame(successor, nowB.services());
          assertSame(index, nowB.encoderSet());
        }
        verify(producer, never()).close();
        assertFalse(queryA.isClosed());
      }
      verify(producer).close();
      assertTrue(queryA.isClosed());
      assertFalse(index.isClosed());
      server.close();
    }
  }

  private static Object newServingView(DefaultWorkerAppServices services,
      RunningRuntime runtime, Path path) throws Exception {
    Class<?> type = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(services, runtime, runtime, path);
  }

  private static void attach(Object view, String name, Class<?> type, Object value)
      throws Exception {
    var method = view.getClass().getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(view, value);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
