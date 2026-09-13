/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsWitness;
import org.junit.jupiter.api.Test;

/** C2's database has one implementation; producers depend on its app-api port. */
@AnalyzeClasses(packages = "io.justsearch", importOptions = {
    ImportOption.DoNotIncludeTests.class, ExecutorArchitectureTest.NoTestFixtures.class})
class OperationStoreArchitectureTest {
  private static final String RUNNER =
      "io.justsearch.app.observability.operations.OperationAttemptRunnerImpl";

  @ArchTest
  static final ArchRule HISTORY_ACK_OWNER = classes().should(new ArchCondition<JavaClass>(
      "acknowledge history delivery only through the single source projector") {
    @Override public void check(JavaClass item, ConditionEvents events) {
      for (var call : item.getCodeUnitAccessesFromSelf()) {
        if (call.getTargetOwner().isAssignableTo(OperationStore.class)
            && call.getName().equals("acknowledgeHistoryProjection")
            && !item.getFullName().equals("io.justsearch.app.observability.operations.OperationHistoryProjector")) {
          events.add(SimpleConditionEvent.violated(item,
              item.getFullName() + " acknowledges history outside its projector at " + call.getSourceCodeLocation()));
        }
      }
    }
  });

  @Test
  void historyAckRuleRejectsASecondProjectionOwner() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedHistoryAck.class, OperationStore.class);
    assertTrue(HISTORY_ACK_OWNER.evaluate(imported).hasViolation());
  }

  static final class UnauthorizedHistoryAck {
    void acknowledge(OperationStore store) { store.acknowledgeHistoryProjection("foreign"); }
  }

  @ArchTest
  static final ArchRule ATTEMPT_WRITER = classes().should(new ArchCondition<JavaClass>(
      "change attempt lifecycle only through the shared runner") {
    @Override public void check(JavaClass item, ConditionEvents events) {
      for (var call : item.getCodeUnitAccessesFromSelf()) {
        if (call.getTargetOwner().isAssignableTo(OperationStore.class)
            && java.util.Set.of("accept", "start", "resume", "rejectBeforeStart", "finish", "armSettingsRevision")
                .contains(call.getName())
            && !item.getFullName().equals(RUNNER) && !item.getFullName().startsWith(RUNNER + "$")) {
          events.add(SimpleConditionEvent.violated(item,
              item.getFullName() + " changes OperationStore." + call.getName()
                  + " at " + call.getSourceCodeLocation()));
        }
      }
    }
  });

  @ArchTest
  static final ArchRule SETTINGS_COMMIT_OWNER = classes().should(new ArchCondition<JavaClass>(
      "enter settings ownership only through the runner and mark commitment only through the fixed owner") {
    @Override public void check(JavaClass item, ConditionEvents events) {
      for (var call : item.getCodeUnitAccessesFromSelf()) {
        boolean ownerEntry = call.getTargetOwner().isAssignableTo(
            io.justsearch.app.api.settings.SettingsCommitOwner.class)
            && java.util.Set.of("reserve", "apply", "releaseAfterTerminal", "retainForRestart",
                "inspectRecovery", "reconcile").contains(call.getName());
        boolean commitment = call.getTargetOwner().isAssignableTo(
            io.justsearch.app.api.settings.SettingsCommitOwner.AttemptControl.class)
            && java.util.Set.of("committed", "uncertain").contains(call.getName());
        boolean runner = item.getFullName().equals(RUNNER) || item.getFullName().startsWith(RUNNER + "$" );
        boolean owner = item.getFullName().equals(
            "io.justsearch.app.services.settings.SettingsCommitCoordinator");
        if ((ownerEntry && !runner) || (commitment && !owner)) {
          events.add(SimpleConditionEvent.violated(item,
              item.getFullName() + " bypasses settings commit authority at " + call.getSourceCodeLocation()));
        }
      }
    }
  });

  @Test
  void settingsRuleRejectsProducerReservationAndForgedReceipt() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedSettingsOwner.class,
        UnauthorizedSettingsReceipt.class, io.justsearch.app.api.settings.SettingsCommitOwner.class,
        io.justsearch.app.api.settings.SettingsCommitOwner.AttemptControl.class);
    var result = SETTINGS_COMMIT_OWNER.evaluate(imported);
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().getDetails().stream().anyMatch(line -> line.contains("UnauthorizedSettingsOwner")));
    assertTrue(result.getFailureReport().getDetails().stream().anyMatch(line -> line.contains("UnauthorizedSettingsReceipt")));
  }

  static final class UnauthorizedSettingsOwner {
    void reserve(io.justsearch.app.api.settings.SettingsCommitOwner owner) {
      owner.reserve(1, "foreign", new SettingsWitness(0, null));
    }
  }

  static final class UnauthorizedSettingsReceipt {
    void commit(io.justsearch.app.api.settings.SettingsCommitOwner.AttemptControl control,
        io.justsearch.app.api.settings.SettingsCommitOwner.Receipt receipt) { control.committed(receipt); }
  }

  @ArchTest
  static final ArchRule IMPLEMENTATION_OWNER = classes().that().implement(OperationStore.class)
      .should().resideInAPackage("io.justsearch.app.observability.operations");

  @ArchTest
  static final ArchRule PRODUCER_BOUNDARY = noClasses().that()
      .resideOutsideOfPackages("io.justsearch.app.observability.operations..",
          "io.justsearch.ui", "io.justsearch.applauncher")
      .should().dependOnClassesThat()
      .haveFullyQualifiedName("io.justsearch.app.observability.operations.SqliteOperationStore");

  @Test
  void ownerRuleRejectsAnAlternateProducerImplementation() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedStore.class, OperationStore.class);
    assertTrue(IMPLEMENTATION_OWNER.evaluate(imported).hasViolation());
  }

  @Test
  void attemptWriterRuleRejectsAProducerWritingTerminalState() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedWriter.class, OperationStore.class);
    assertTrue(ATTEMPT_WRITER.evaluate(imported).hasViolation());
  }

  @Test
  void attemptWriterRuleRejectsAProducerArmingSettings() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedSettingsMarker.class, OperationStore.class);
    assertTrue(ATTEMPT_WRITER.evaluate(imported).hasViolation());
  }

  static final class UnauthorizedSettingsMarker {
    void arm(OperationStore store) { store.armSettingsRevision(1, 0); }
  }

  static final class UnauthorizedWriter {
    void finish(OperationStore store) {
      store.finish(1, io.justsearch.app.api.operations.OperationState.COMPLETE,
          new io.justsearch.app.api.operations.OperationReceipt("SUCCESS", null));
    }
  }

  static final class UnauthorizedStore implements OperationStore {
    @Override public boolean armSettingsRevision(long id, long expectedRevision) { return false; }
    @Override public java.util.List<io.justsearch.app.api.operations.OperationHistoryRow> recentHistory(int limit) {
      return java.util.List.of();
    }
    @Override public java.util.List<io.justsearch.app.api.operations.OperationHistoryRow> pendingHistoryProjection(int limit) {
      return java.util.List.of();
    }
    @Override public boolean acknowledgeHistoryProjection(String key) { return false; }
    @Override public long historyProjectionUpperId() { return 0; }
    @Override public java.util.List<io.justsearch.app.api.operations.OperationHistoryRow> pendingHistoryProjectionAfter(int limit, long completedAt, long id, long maximumId) {
      return java.util.List.of();
    }
    @Override public AutoCloseable subscribeCompletions(java.util.function.Consumer<io.justsearch.app.api.operations.OperationRecord> listener) {
      throw new UnsupportedOperationException();
    }
    @Override public Acceptance accept(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor,
        io.justsearch.core.context.EngineContext context,
        io.justsearch.agent.api.registry.InvocationProvenance provenance) {
      throw new UnsupportedOperationException();
    }
    @Override public Acceptance acceptPrepared(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor,
        io.justsearch.core.context.EngineContext context,
        io.justsearch.agent.api.registry.InvocationProvenance provenance, java.util.UUID nonce) {
      throw new UnsupportedOperationException();
    }
    @Override public io.justsearch.app.api.operations.OperationOutcomeView outcome(String key) {
      throw new UnsupportedOperationException();
    }
    @Override public Acceptance accept(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor,
        io.justsearch.core.context.EngineContext context,
        io.justsearch.agent.api.registry.InvocationProvenance provenance,
        io.justsearch.app.api.operations.OperationHistoryMode historyMode) {
      throw new UnsupportedOperationException();
    }
    @Override public java.util.Optional<io.justsearch.app.api.operations.OperationRecord> find(String key) {
      return java.util.Optional.empty();
    }
    @Override public java.util.Optional<io.justsearch.app.api.operations.OperationRecord> lookup(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor) {
      return java.util.Optional.empty();
    }
    @Override public java.util.Optional<Preparation> pendingPreparation(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor) { return java.util.Optional.empty(); }
    @Override public java.util.Optional<Preparation> savePreparation(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor, Preparation preparation) {
      return java.util.Optional.empty();
    }
    @Override public Acceptance acceptPrepared(String key,
        io.justsearch.app.api.operations.OperationDescriptor descriptor,
        io.justsearch.core.context.EngineContext context,
        io.justsearch.agent.api.registry.InvocationProvenance provenance, java.util.UUID nonce,
        io.justsearch.app.api.operations.OperationHistoryMode historyMode) {
      throw new UnsupportedOperationException();
    }
    @Override public java.util.Optional<Preparation> acceptedPreparation(long id) {
      return java.util.Optional.empty();
    }
    @Override public boolean start(long id) { return false; }
    @Override public boolean resume(long id) { return false; }
    @Override public io.justsearch.app.api.operations.OperationRecord rejectBeforeStart(long id, io.justsearch.app.api.operations.OperationReceipt receipt) {
      throw new UnsupportedOperationException();
    }
    @Override public boolean checkpoint(long id, String cursor, long completed, long failed) { return false; }
    @Override public java.util.Optional<io.justsearch.app.api.operations.OperationRecord> finish(long id, io.justsearch.app.api.operations.OperationState state,
        io.justsearch.app.api.operations.OperationReceipt receipt) { return java.util.Optional.empty(); }
    @Override public java.util.List<io.justsearch.app.api.operations.OperationRecord> openRecords() {
      return java.util.List.of();
    }
    @Override public long historySinceMillis() { return 0; }
    @Override public void pruneHistory() {}
    @Override public java.util.Optional<Recovery> recovery() { return java.util.Optional.empty(); }
    @Override public void close() {}
  }
}
