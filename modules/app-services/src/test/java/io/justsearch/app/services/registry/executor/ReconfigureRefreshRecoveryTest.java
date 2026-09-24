/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.handlers.ReconfigureHandler;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The committed file witness cannot terminalize a refresh until its component is restored. */
final class ReconfigureRefreshRecoveryTest {
  @TempDir Path directory;

  @Test
  void crashAfterSettingsMoveRecomposesAcceptedRefreshBeforeTerminalizing() throws Exception {
    Path db = directory.resolve("operations.db");
    Path file = directory.resolve("settings.json");
    String key = OperationKeys.generate(Clock.systemUTC());
    var witness = new SettingsWitness(0, null);
    var candidate = new SettingsV2(null, null, null, null, witness, key, null, null, null);
    String arguments = JsonMapper.builder().build().writeValueAsString(
        new ReconfigureHandler.Envelope(candidate, null, true));
    var descriptor = OperationDescriptor.invocation(OperationKind.RECONFIGURE,
        "core.reconfigure", arguments, false);
    var context = EngineProvenance.internal("refresh-recovery", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.EPOCH,
        Optional.empty());
    UUID nonce = UUID.randomUUID();
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var handler = new ReconfigureHandler(() -> null);
    var prepared = handler.prepare(arguments, provenance, context);
    var envelope = codec.freeze(key, nonce, descriptor, prepared, context, provenance);
    try (var operations = new SqliteOperationStore(db)) {
      operations.savePreparation(key, descriptor,
          new OperationStore.Preparation(nonce, codec.encode(envelope)));
      var row = operations.acceptPrepared(key, descriptor, context, provenance, nonce).record();
      assertTrue(operations.start(row.id()));
      assertTrue(operations.armSettingsRevision(row.id(), 0));
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(1, key)));
      // Crash after the file move, before component publication or terminal write.
    }

    try (var operations = new SqliteOperationStore(db)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
      var installed = new AtomicInteger();
      SettingsComponentComposer components = new SettingsComponentComposer() {
        @Override public Prepared prepare(UiSettings ui, ResolvedConfig resolved,
            Map<String, Set<String>> affected) {
          throw new AssertionError("Accepted refresh context was dropped");
        }

        @Override public Prepared prepare(UiSettings ui, ResolvedConfig resolved,
            Map<String, Set<String>> affected, SettingsCandidateContext recovered) {
          assertTrue(recovered.forceGenerativeRefresh());
          assertEquals(Set.of("modelRefresh"), affected.get("generative"));
          assertEquals(new SettingsWitness(1, key), settings.inspect().witness());
          return new Prepared() {
            @Override public void validate() {}
            @Override public void install() { installed.incrementAndGet(); }
            @Override public void notifyObservers() {}
            @Override public void retire() {}
            @Override public void abort() {}
          };
        }
      };
      var owner = new SettingsCommitCoordinator(settings,
          new ConfigStore(ConfigStoreRebuilder.prepare(settings.load())), () -> {},
          ignored -> OperationResult.success("prepared"), () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      assertEquals(0, installed.get());
      assertTrue(runner.reconcileSettingsAfterComposition());
      assertEquals(1, installed.get());
      assertEquals(OperationState.COMPLETE, operations.find(key).orElseThrow().state());
    }
  }
}
