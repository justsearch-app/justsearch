/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.executor.SettingsResetTestSupport;
import io.justsearch.configuration.resolved.ConfigStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SettingsResetCoordinatorTest {
  private static final Set<OperationKind> KINDS = Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE);
  @TempDir Path temp;

  @Test
  void normalResetPreservesAdminAndGeometryPreferencesAndNeverRestarts() throws Exception {
    var settings = settings(false);
    var initial = new UiSettings();
    initial.setTheme("dark"); initial.setVimMode(true); initial.setServerExecutablePath("admin-path");
    initial.setMaxTokens(8192); initial.setExcludePatterns(List.of("*.private"));
    settings.replacePrepared(settings.prepare(initial, new SettingsWitness(0, null)));
    var restarts = new AtomicInteger();
    try (var operations = operations()) {
      var owner = owner(settings, restarts::incrementAndGet, settings::replacePrepared);
      var runner = runner(operations, owner);
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.normal("{}", settings.inspect().witness()));
      var result = runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle)));
      assertEquals(OperationState.COMPLETE, result.record().state());
      var committed = settings.inspect();
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), committed.witness());
      assertEquals("system", committed.settings().getTheme());
      assertEquals(1024, committed.settings().getMaxTokens());
      assertEquals(List.of(), committed.settings().getExcludePatterns());
      assertTrue(committed.settings().isVimMode());
      assertEquals("admin-path", committed.settings().getServerExecutablePath());
      assertEquals(0, restarts.get());
      assertEquals(OperationState.COMPLETE, runner.start(attempt, handle -> {
        fail("A retry cannot perform the reset again"); return null;
      }).record().state());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "runtime", "error", "error-and-restart-error"})
  void recoveryClearsAndRestartsOnlyAfterDurableCompletionOutsideOwnerLock(String fault) throws Exception {
    var settings = settings(true);
    var restarts = new AtomicInteger();
    var cleared = new AtomicInteger();
    var primary = new AssertionError("clear callback");
    var secondary = new AssertionError("restart callback");
    var active = new AtomicReference<OperationAttemptRunner.PreparedAttempt>();
    var ownerRef = new AtomicReference<SettingsCommitCoordinator>();
    try (var operations = operations()) {
      Runnable restart = () -> {
        restarts.incrementAndGet();
        assertEquals(OperationState.COMPLETE, operations.find(active.get().accepted().key()).orElseThrow().state());
        assertOutsideLock(ownerRef.get(), active.get());
        if (fault.equals("error-and-restart-error")) throw secondary;
      };
      var owner = owner(settings, restart, settings::replacePrepared); ownerRef.set(owner);
      var runner = runner(operations, owner);
      settings.setOnRecoveryCleared(() -> {
        cleared.incrementAndGet();
        assertEquals(OperationState.COMPLETE, operations.find(active.get().accepted().key()).orElseThrow().state());
        assertOutsideLock(owner, active.get());
        if (fault.equals("runtime")) throw new IllegalStateException("clear callback");
        if (fault.startsWith("error")) throw primary;
      });
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      active.set(attempt);
      if (fault.startsWith("error")) {
        assertSame(primary, assertThrows(AssertionError.class,
            () -> runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle)))));
        if (fault.equals("error-and-restart-error")) assertArrayEquals(new Throwable[] {secondary}, primary.getSuppressed());
      } else {
        var result = runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle)));
        assertEquals(OperationState.COMPLETE, result.record().state());
        assertFalse(runner.persistenceFailure().toCompletableFuture().isDone());
      }
      assertEquals(OperationState.COMPLETE, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
      assertEquals(1, restarts.get()); assertEquals(1, cleared.get());
      assertTrue(settings.lastRecovery().isEmpty());
      owner.releaseAfterTerminal(attempt.accepted().id());
      assertEquals(1, restarts.get()); assertEquals(1, cleared.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"arm", "before-move"})
  void precommitFailureRetainsQuarantineButReleasesFenceForAnotherConfirmedReset(String fault) throws Exception {
    var settings = settings(true);
    var failMove = new AtomicBoolean(fault.equals("before-move"));
    var restarts = new AtomicInteger();
    var preparations = new AtomicInteger();
    try (var operations = operations()) {
      var owner = new SettingsCommitCoordinator(settings, config(settings), restarts::incrementAndGet,
          candidate -> { preparations.incrementAndGet(); return ConfigStoreRebuilder.prepare(candidate); },
          candidate -> OperationResult.success("prepared"), prepared -> {
            if (failMove.get()) throw new IOException("before move"); settings.replacePrepared(prepared);
          });
      var runner = runner(operations, owner);
      if (fault.equals("arm")) sql("CREATE TRIGGER reject_arm BEFORE UPDATE OF accepted_settings_revision ON operations "
          + "WHEN NEW.accepted_settings_revision IS NOT NULL BEGIN SELECT RAISE(FAIL, 'arm'); END");
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      if (fault.equals("arm")) assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
          () -> runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle))));
      else assertThrows(IllegalStateException.class,
          () -> runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle))));
      assertEquals(OperationState.FAILED, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(fault.equals("arm") ? 0 : 1, preparations.get());
      assertFalse(Files.exists(settings.settingsPath()));
      assertTrue(settings.lastRecovery().isPresent()); assertEquals(0, restarts.get());
      assertThrows(SettingsCommitOwner.Refused.class, () -> owner.reserve(attempt.accepted().id(),
          attempt.accepted().key(), new SettingsWitness(0, null)));
      if (fault.equals("arm")) sql("DROP TRIGGER reject_arm");
      failMove.set(false);
      var retry = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      assertEquals(OperationState.COMPLETE, runner.start(retry,
          handle -> OperationExecution.finished(runner.applySettingsReset(handle))).record().state());
      assertEquals(1, restarts.get()); assertTrue(settings.lastRecovery().isEmpty());
    }
  }

  @Test
  void terminalFailureKeepsRecoveryConditionAndCommittedFenceUntilRestart() throws Exception {
    var settings = settings(true);
    var restarts = new AtomicInteger(); var cleared = new AtomicInteger();
    settings.setOnRecoveryCleared(cleared::incrementAndGet);
    try (var operations = operations()) {
      var owner = owner(settings, restarts::incrementAndGet, settings::replacePrepared);
      var runner = runner(operations, owner);
      sql("CREATE TRIGGER reject_complete BEFORE UPDATE OF state ON operations "
          + "WHEN NEW.state='COMPLETE' BEGIN SELECT RAISE(FAIL, 'complete'); END");
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
          () -> runner.start(attempt, handle -> OperationExecution.finished(runner.applySettingsReset(handle))));
      assertEquals(OperationState.RUNNING, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(new SettingsWitness(1, attempt.accepted().key()), settings.inspect().witness());
      assertTrue(settings.lastRecovery().isPresent()); assertEquals(0, cleared.get()); assertEquals(1, restarts.get());
      assertThrows(SettingsCommitOwner.Refused.class, () -> owner.reserve(attempt.accepted().id(),
          attempt.accepted().key(), settings.inspect().witness()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unchanged", "changed", "committed-bad-preparation"})
  void bootUsesExactCommitFirstOtherwiseRequiresUnchangedFrozenQuarantine(String variant) throws Exception {
    var settings = settings(true);
    String key;
    try (var operations = operations()) {
      var runner = runner(operations, owner(settings, () -> {}, settings::replacePrepared));
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      key = attempt.accepted().key(); operations.start(attempt.accepted().id());
      assertTrue(operations.armSettingsRevision(attempt.accepted().id(), 0));
      if (variant.equals("changed")) Files.writeString(settings.settingsPath().resolveSibling("settings.json.corrupt-new"), "changed");
      if (variant.equals("committed-bad-preparation")) {
        settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(1, key)));
        sql("UPDATE operations SET preparation_payload='invalid-envelope'");
      }
    }
    var reopenedSettings = settings(false); var restarts = new AtomicInteger();
    try (var operations = operations()) {
      var owner = owner(reopenedSettings, restarts::incrementAndGet, reopenedSettings::replacePrepared);
      runner(operations, owner);
      var row = operations.find(key).orElseThrow();
      assertEquals(switch (variant) {
        case "unchanged" -> OperationState.FAILED;
        case "changed" -> OperationState.RUNNING;
        default -> OperationState.COMPLETE;
      }, row.state());
      assertEquals(0, restarts.get());
      if (!variant.equals("committed-bad-preparation")) {
        assertEquals(SettingsCommitOwner.RecoveryReason.UNREADABLE_WITNESS,
            owner.recoveryIssue().toCompletableFuture().join().reason());
      }
      if (variant.equals("changed")) {
        // A new confirmation cannot bypass an unresolved armed dependency.
        assertThrows(SettingsCommitOwner.Refused.class, () -> owner.reserve(row.id(), row.key(), new SettingsWitness(0, null)));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"new-quarantine", "unexpected-zero-file"})
  void ambiguousRecoveryReplacementCannotUseSyntheticZeroWitnessAsPrior(String variant) throws Exception {
    var settings = settings(true); var restarts = new AtomicInteger();
    try (var operations = operations()) {
      var owner = owner(settings, restarts::incrementAndGet, prepared -> {
        if (variant.equals("new-quarantine")) {
          Files.writeString(settings.settingsPath().resolveSibling("settings.json.corrupt-extra"), "changed");
        } else {
          settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(0, null)));
        }
        throw new IOException("ambiguous replacement");
      });
      var runner = runner(operations, owner);
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      assertThrows(IllegalStateException.class, () -> runner.start(attempt,
          handle -> OperationExecution.finished(runner.applySettingsReset(handle))));
      assertEquals(OperationState.RUNNING, operations.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(1, restarts.get()); assertTrue(settings.lastRecovery().isPresent());
    }
  }

  @Test
  void resetWithoutAcceptedPreparationNeverArmsOrWrites() throws Exception {
    var settings = settings(true);
    try (var operations = operations()) {
      var owner = owner(settings, () -> fail("No reservation can request restart"), settings::replacePrepared);
      var runner = runner(operations, owner);
      var accepted = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.recovery("{}", settings.recoveryFingerprint()));
      sql("UPDATE operations SET preparation_nonce=NULL, preparation_sealed=NULL, preparation_payload=NULL");
      assertThrows(IllegalArgumentException.class, () -> runner.start(accepted,
          handle -> OperationExecution.finished(runner.applySettingsReset(handle))));
      var row = operations.find(accepted.accepted().key()).orElseThrow();
      assertEquals(OperationState.FAILED, row.state()); assertNull(row.expectedSettingsRevision());
      assertFalse(Files.exists(settings.settingsPath())); assertTrue(settings.lastRecovery().isPresent());
    }
  }

  @Test
  void normalResetPrecommitRecoveryRequiresTheEntirePreparedWitnessPair() throws Exception {
    var settings = settings(false);
    String original = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
    String replacement = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
    settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(4, original)));
    String key;
    try (var operations = operations()) {
      var runner = runner(operations, owner(settings, () -> {}, settings::replacePrepared));
      var attempt = SettingsResetTestSupport.accept(runner, SettingsResetPreparation.normal("{}", settings.inspect().witness()));
      key = attempt.accepted().key(); operations.start(attempt.accepted().id());
      assertTrue(operations.armSettingsRevision(attempt.accepted().id(), 4));
      settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(4, replacement)));
    }
    try (var operations = operations()) {
      var owner = owner(settings, () -> fail("Boot must wait without restart"), settings::replacePrepared);
      runner(operations, owner);
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      assertEquals(SettingsCommitOwner.RecoveryReason.CONTRADICTORY_WITNESS,
          owner.recoveryIssue().toCompletableFuture().join().reason());
    }
  }

  private static void assertOutsideLock(SettingsCommitCoordinator owner, OperationAttemptRunner.PreparedAttempt attempt) {
    var observed = new java.util.concurrent.CompletableFuture<OperationAttemptRunner.Reconciliation>();
    Thread.ofPlatform().daemon().name("settings-reset-lock-probe").start(() -> {
      try { observed.complete(owner.reconcile(attempt.accepted())); }
      catch (Throwable failure) { observed.completeExceptionally(failure); }
    });
    assertDoesNotThrow(() -> observed.get(2, TimeUnit.SECONDS));
  }

  private UiSettingsStore settings(boolean corrupt) throws Exception {
    Path path = temp.resolve("settings.json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, path);
    if (corrupt) { Files.writeString(path, "{broken"); settings.load(); assertTrue(settings.lastRecovery().isPresent()); }
    return settings;
  }
  private SqliteOperationStore operations() throws Exception { return new SqliteOperationStore(temp.resolve("operations.db")); }
  private void sql(String sql) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db").toAbsolutePath());
        var statement = connection.createStatement()) { statement.execute(sql); }
  }
  private static ConfigStore config(UiSettingsStore settings) {
    // Mirror boot's serving snapshot. A normal reset keeps operator settings that are already
    // applied; a quarantined recovery has no readable settings until the successor boots.
    UiSettings serving;
    try {
      serving = settings.inspect().settings();
    } catch (io.justsearch.configuration.persistence.CorruptDurableStoreException quarantined) {
      serving = new UiSettings();
    }
    return new ConfigStore(ConfigStoreRebuilder.prepare(serving));
  }
  private static SettingsCommitCoordinator owner(UiSettingsStore settings, Runnable restart, SettingsCommitCoordinator.Replacement replacement) {
    return new SettingsCommitCoordinator(settings, config(settings), restart, ConfigStoreRebuilder::prepare,
        candidate -> OperationResult.success("prepared"), replacement);
  }
  private static OperationAttemptRunnerImpl runner(SqliteOperationStore operations, SettingsCommitCoordinator owner) {
    return new OperationAttemptRunnerImpl(operations, Clock.systemUTC(), KINDS, owner);
  }
}
