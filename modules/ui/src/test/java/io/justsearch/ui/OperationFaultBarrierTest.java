/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class OperationFaultBarrierTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000001";
  @TempDir Path data;

  @Test
  void automaticProducerIsolationRequiresTheSelectedHarnessProof() {
    var noop = OperationAttemptRunnerImpl.NO_FAULT_HOOK;
    for (String scenario : java.util.List.of("processing", "operation")) {
      assertTrue(OperationFaultBarrier.automaticRootProducersEnabled(
          Map.of("JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario)::get, noop));
      assertFalse(OperationFaultBarrier.automaticRootProducersEnabled(
          Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1", "JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario)::get, noop));
    }
    for (String scenario : java.util.List.of("writer", "migration", "lock-boot", "lock-ingest", "")) {
      assertTrue(OperationFaultBarrier.automaticRootProducersEnabled(
          Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1", "JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario)::get, noop));
    }
    assertTrue(OperationFaultBarrier.automaticRootProducersEnabled(Map.<String, String>of()::get, noop));
    assertFalse(OperationFaultBarrier.automaticRootProducersEnabled(selection()::get,
        OperationFaultBarrier.fromEnvironment(data, selection()::get)));
  }

  @Test
  void absentSelectionUsesTheExactNoopAndAnySelectionRequiresHarnessMode() {
    assertSame(OperationAttemptRunnerImpl.NO_FAULT_HOOK, OperationFaultBarrier.fromEnvironment(data, Map.<String, String>of()::get));
    var env = new HashMap<>(selection());
    env.remove("JUSTSEARCH_SUPERVISOR_HARNESS");
    assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data, env::get));
    assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data,
        Map.of("JUSTSEARCH_OPERATION_FAULT_KEY", KEY)::get));
  }

  @Test
  void blankSelectorsArePresentAndCannotSilentlyDisableTheBarrier() {
    for (String field : java.util.List.of("JUSTSEARCH_OPERATION_FAULT_POINT",
        "JUSTSEARCH_OPERATION_FAULT_KEY", "JUSTSEARCH_OPERATION_FAULT_KIND")) {
      for (String blank : java.util.List.of("", " ")) {
        var env = new HashMap<String, String>();
        env.put(field, blank);
        assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data, env::get));
        env.put("JUSTSEARCH_SUPERVISOR_HARNESS", "1");
        assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data, env::get));
      }
    }
  }

  @Test
  void incompleteOrUnknownSelectionIsRejectedBeforeAnyObservation() {
    for (String field : selection().keySet()) {
      var env = new HashMap<>(selection());
      env.remove(field);
      assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data, env::get));
    }
    var env = new HashMap<>(selection());
    env.put("JUSTSEARCH_OPERATION_FAULT_POINT", "arbitrary-write");
    assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(data, env::get));
  }

  @Test
  void phaseFamiliesAreAcceptedOnlyForTheirOperationKinds() {
    var bulkPhases = java.util.List.of(
        "bulk-partial-capture", "bulk-before-building-checkpoint", "bulk-after-promotion",
        "installer-before-marker", "installer-before-arm", "installer-before-pointer",
        "installer-pointer-before-settings", "installer-settings-before-publication",
        "installer-before-receipt");
    var ordinaryPhases = java.util.List.of("before-accept", "after-accept", "after-effect");
    for (String phase : bulkPhases) {
      assertNotSame(OperationAttemptRunnerImpl.NO_FAULT_HOOK,
          OperationFaultBarrier.fromEnvironment(data.resolve(phase), selection(phase, "reindex")::get));
      for (String ordinaryKind : java.util.List.of("ingest", "settings-apply", "reconfigure")) {
        assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(
            data.resolve(phase + "-" + ordinaryKind), selection(phase, ordinaryKind)::get));
      }
    }
    for (String phase : ordinaryPhases) {
      for (String ordinaryKind : java.util.List.of("ingest", "settings-apply", "reconfigure")) {
        assertNotSame(OperationAttemptRunnerImpl.NO_FAULT_HOOK,
            OperationFaultBarrier.fromEnvironment(data.resolve(phase + "-" + ordinaryKind),
                selection(phase, ordinaryKind)::get));
      }
      assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(
          data.resolve(phase + "-reindex"), selection(phase, "reindex")::get));
    }
    assertNotSame(OperationAttemptRunnerImpl.NO_FAULT_HOOK,
        OperationFaultBarrier.fromEnvironment(data.resolve("compose"),
            selection("settings-mid-compose", "reconfigure")::get));
    for (String wrongKind : java.util.List.of("ingest", "settings-apply", "reindex")) {
      assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(
          data.resolve("compose-" + wrongKind),
          selection("settings-mid-compose", wrongKind)::get));
    }
    assertThrows(IllegalArgumentException.class, () -> OperationFaultBarrier.fromEnvironment(
        data.resolve("unknown-kind"), selection("bulk-partial-capture", "reindexing")::get));
  }

  @Test
  void selfExitIsRestrictedToTheExactInstallerReceiptBoundary() {
    var wrong = new HashMap<>(selection("installer-before-pointer", "reindex"));
    wrong.put("JUSTSEARCH_OPERATION_FAULT_SELF_EXIT", "1");
    assertThrows(IllegalArgumentException.class,
        () -> OperationFaultBarrier.fromEnvironment(data, wrong::get));
    wrong.put("JUSTSEARCH_OPERATION_FAULT_POINT", "installer-before-receipt");
    wrong.put("JUSTSEARCH_OPERATION_FAULT_KIND", "ingest");
    assertThrows(IllegalArgumentException.class,
        () -> OperationFaultBarrier.fromEnvironment(data, wrong::get));
  }

  @Test
  void exactBoundaryPublishesEvidenceAndSuccessorDoesNotRetrigger() throws Exception {
    var hook = OperationFaultBarrier.fromEnvironment(data, selection()::get);
    var boundary = new OperationAttemptRunnerImpl.FaultBoundary("after-effect", OperationKind.INGEST,
        KEY, "01994180-0000-7000-8000-000000000002", 17, "ingest-receipt:1:9:hash", 1, 0);
    hook.accept(new OperationAttemptRunnerImpl.FaultBoundary("before-accept", OperationKind.INGEST,
        KEY, KEY, -1, null, 0, 0));
    hook.accept(new OperationAttemptRunnerImpl.FaultBoundary("after-effect", OperationKind.SETTINGS_APPLY,
        KEY, KEY, 16, null, 0, 0));
    hook.accept(new OperationAttemptRunnerImpl.FaultBoundary("after-effect", OperationKind.INGEST,
        "01994180-0000-7000-8000-000000000003", KEY, 16, null, 1, 0));
    Path reached = data.resolve("runtime/operation-fault-reached.json");
    assertFalse(Files.exists(reached));
    Files.createDirectories(reached.getParent());
    Path release = data.resolve("runtime/operation-fault-release");
    Files.writeString(release, "release");
    hook.accept(boundary);
    String evidence = Files.readString(reached);
    var json = JsonMapper.builder().build().readTree(evidence);
    assertEquals("ingest", json.path("parentKind").asText());
    assertEquals(KEY, json.path("parentKey").asText());
    assertEquals(boundary.operationKey(), json.path("operationKey").asText());
    assertEquals(17, json.path("operationRecordId").asLong());
    assertEquals(1, json.path("completed").asLong());
    assertEquals(ProcessHandle.current().pid(), json.path("pid").asLong());
    Files.delete(release);
    var successor = OperationFaultBarrier.fromEnvironment(data, selection()::get);
    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> successor.accept(boundary));
    assertEquals(evidence, Files.readString(reached));
  }

  @Test
  void eachBulkBoundaryPublishesExactEvidenceOnceAcrossSuccessorHook() throws Exception {
    for (String phase : java.util.List.of(
        "bulk-partial-capture", "bulk-before-building-checkpoint", "bulk-after-promotion",
        "installer-before-marker", "installer-before-arm", "installer-before-pointer",
        "installer-pointer-before-settings", "installer-settings-before-publication",
        "installer-before-receipt")) {
      Path scenarioData = data.resolve(phase);
      var selector = selection(phase, "reindex");
      var hook = OperationFaultBarrier.fromEnvironment(scenarioData, selector::get);
      Path runtime = scenarioData.resolve("runtime");
      Path reached = runtime.resolve("operation-fault-reached.json");
      Path release = runtime.resolve("operation-fault-release");
      Files.createDirectories(runtime);
      Files.writeString(release, "release");
      var exact = new OperationAttemptRunnerImpl.FaultBoundary(
          phase, OperationKind.REINDEX, KEY, KEY, 17, null, 0, 0);

      hook.accept(new OperationAttemptRunnerImpl.FaultBoundary(
          "not-the-selected-phase", OperationKind.REINDEX, KEY, KEY, 17, null, 0, 0));
      hook.accept(new OperationAttemptRunnerImpl.FaultBoundary(
          phase, OperationKind.INGEST, KEY, KEY, 17, null, 0, 0));
      hook.accept(new OperationAttemptRunnerImpl.FaultBoundary(
          phase, OperationKind.REINDEX, "01994180-0000-7000-8000-000000000002", KEY, 17, null, 0, 0));
      assertFalse(Files.exists(reached), "only exact phase, kind and parent key may trigger " + phase);

      hook.accept(exact);
      String evidence = Files.readString(reached);
      var json = JsonMapper.builder().build().readTree(evidence);
      assertEquals(phase, json.path("phase").asText());
      assertEquals("reindex", json.path("parentKind").asText());
      assertEquals(KEY, json.path("parentKey").asText());
      assertEquals(KEY, json.path("operationKey").asText());
      assertEquals(17, json.path("operationRecordId").asLong());
      assertEquals(ProcessHandle.current().pid(), json.path("pid").asLong());

      Files.delete(release);
      var successor = OperationFaultBarrier.fromEnvironment(scenarioData, selector::get);
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> successor.accept(exact));
      assertEquals(evidence, Files.readString(reached), "successor cannot retrigger " + phase);
    }
  }

  private static Map<String, String> selection() {
    return selection("after-effect", "ingest");
  }

  private static Map<String, String> selection(String phase, String kind) {
    return Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1", "JUSTSEARCH_OPERATION_FAULT_POINT", phase,
        "JUSTSEARCH_OPERATION_FAULT_KEY", KEY, "JUSTSEARCH_OPERATION_FAULT_KIND", kind);
  }
}
