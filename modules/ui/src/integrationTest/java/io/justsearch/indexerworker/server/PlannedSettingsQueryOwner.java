/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.EngineComponentRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Real KnowledgeServer query ownership and settings publication, with no native model loading. */
public final class PlannedSettingsQueryOwner implements AutoCloseable {
  private final io.justsearch.core.execution.TestEngineExecutors executors =
      new io.justsearch.core.execution.TestEngineExecutors();
  private final KnowledgeServer server;
  private final EncoderSet index;

  public PlannedSettingsQueryOwner(FixedSettingsComponentComposer composer,
      EngineComponentRegistry registry, ConfigStore config, UiSettingsStore settings) throws Exception {
    ResolvedConfig initial = config.get();
    Path dir = initial.paths().dataDir();
    var spec = new ComponentSpec("encoders", false,
        InferenceCompositionRoot.componentDependencies(), ComponentSpec.ComposeCapability.BESIDE,
        Duration.ZERO, 0);
    var component = registry.register(spec);
    var projection = EncoderConfigurationProjection.from(initial);
    index = new EncoderSet(surface(projection.indexDigest()), new EncoderSet.ModelIdentity(
        io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present("test"),
        io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present("test"),
        io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present("test"),
        false, 768));
    var query = new QueryRoleSet(surface(projection.queryDigest()));
    var worker = new io.justsearch.indexerworker.WorkerConfig(dir, 60_000L, "test",
        java.util.Map.of(), "test", 500L);
    server = new KnowledgeServer(executors, worker, null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(),
        null, component, initial, config::get, config.publicationLock(),
        () -> new io.justsearch.core.component.DeviceMemoryLine(32L << 30, 32L << 30));
    server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
    var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
    server.infraCtx = new InfraContext(worker,
        mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
        server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
    var producer = mock(DefaultWorkerAppServices.class);
    when(producer.prepareQueryServingSuccessor(any(), any(), any())).thenAnswer(call -> {
      var fresh = mock(DefaultWorkerAppServices.class);
      when(fresh.ingestService()).thenReturn(mock(io.justsearch.indexerworker.services.WorkerIngestService.class));
      when(producer.prepareProducerTransferTo(fresh)).thenReturn(mock(DefaultWorkerAppServices.ProducerTransfer.class));
      return fresh;
    });
    Class<?> viewType = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
    var constructor = viewType.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object view = constructor.newInstance(producer, runtime, runtime, dir.resolve("active"));
    for (var entry : java.util.Map.of("attachEncoderSet", (Object) index,
        "attachQueryRoleSet", query).entrySet()) {
      var method = viewType.getDeclaredMethod(entry.getKey(), entry.getValue().getClass());
      method.setAccessible(true);
      method.invoke(view, entry.getValue());
    }
    set("servingView", view);
    set("initialEncoderSet", index);
    set("initialQueryRoleSet", query);
    set("startupConfiguration", initial);
    server.appServices = producer;
    server.startDeferredModelInitialization(() -> {});
    server.deferredModelInit.join();
    composer.register("encoders", (candidate, desired, keys) -> {
      var prepared = server.prepareQueryRoleSettings(candidate, desired, keys,
          settings.inspect().queryRoles());
      return new FixedSettingsComponentComposer.QueryRolePreparedOwner() {
        @Override public io.justsearch.app.api.settings.QueryRoleSelection selection() {
          return prepared.selection();
        }
        @Override public Optional<io.justsearch.core.component.ComposeEvidence> composition() {
          return Optional.of(prepared.composition());
        }
        @Override public io.justsearch.core.component.EngineComponentSnapshot.Component observation() {
          return prepared.observation();
        }
        @Override public void withOwnerLocks(Runnable publication) { prepared.withOwnerLocks(publication); }
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component unexpected) {
          throw new UnsupportedOperationException("Query owner has no generation projection");
        }
        @Override public void validate() { prepared.validate(); }
        @Override public void install() { prepared.install(); }
        @Override public void notifyObservers() { prepared.notifyObservers(); }
        @Override public void retire() { prepared.retire(); }
        @Override public void abort() { prepared.abort(); }
        @Override public void abort(Throwable cause) { prepared.abort(cause); }
      };
    });
  }

  private static InferenceSurface surface(String digest) {
    return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(digest), Set.of(), Set.of()));
  }

  private void set(String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }

  public boolean indexClosed() { return index.isClosed(); }

  @Override public void close() throws Exception {
    server.close();
    executors.close();
  }
}
