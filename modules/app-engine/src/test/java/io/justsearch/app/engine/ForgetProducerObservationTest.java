/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.core.context.EngineContext;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Actual accepted SQLite metadata, with portable reflection only at the new FORGET boundary. */
final class ForgetProducerObservationTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;
  private int nextFixture;

  private String inspect(Path target, Object snapshot, OperationStore store) {
    Class<?> planType = assertDoesNotThrow(() -> Class.forName("io.justsearch.app.api.operations.ForgetPlan"));
    Class<?> snapshotType = assertDoesNotThrow(() -> Class.forName(OperationStore.class.getName() + "$ForgetAuthoritySnapshot"));
    var fields = new LinkedHashMap<String, Object>();
    fields.put("version", 1); fields.put("path", target.toString()); fields.put("scope", "FILE");
    fields.put("keepOut", null); fields.put("watchedRoots", List.of()); fields.put("source", "FILE_SYSTEM");
    Object plan = JSON.readValue(JSON.writeValueAsString(fields), planType);
    return assertDoesNotThrow(() -> {
      Class<?> owner = Class.forName("io.justsearch.app.engine.ForgetProducerObservation");
      var method = owner.getDeclaredMethod("inspect", planType, long.class, snapshotType, OperationStore.class);
      method.setAccessible(true);
      Object observed;
      try { observed = method.invoke(null, plan, Long.MAX_VALUE, snapshot, store); }
      catch (InvocationTargetException failed) { throw new AssertionError("Producer observation must remain readable", failed.getCause()); }
      var status = observed.getClass().getDeclaredMethod("status"); status.setAccessible(true);
      return status.invoke(observed).toString();
    });
  }
  private Object snapshot(OperationStore store) {
    return assertDoesNotThrow(() -> OperationStore.class.getMethod("captureForgetAuthority").invoke(store),
        "the accepted store must expose bounded monotonic producer authority");
  }
  private RecordedRootPlan.Root root(String name, boolean file, List<Path> excluded) {
    return new RecordedRootPlan.Root(directory.resolve(name), "documents", false, file, List.of(), excluded);
  }

  private final class Fixture implements AutoCloseable {
    final SqliteOperationStore store = new SqliteOperationStore(directory.resolve("operations-" + nextFixture++ + ".db"));
    final OperationAttemptRunnerImpl runner = new OperationAttemptRunnerImpl(store, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX), null, new RecordedIngestPlanResolver());
    Fixture() throws java.io.IOException, java.sql.SQLException {}
    OperationAttemptRunner.PreparedAttempt accept(OperationKind kind, String ref, RecordedRootPlan plan, boolean undo) {
      var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "producer-observation-test",
          Optional.empty(), Optional.of("jsa1:auto"), TransportTag.BUTTON,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
      String key = OperationKeys.generate(CLOCK); String arguments = "{}";
      var descriptor = OperationDescriptor.invocation(kind, ref, arguments, undo);
      var request = new OperationAttemptRunner.Request(key, descriptor, context, provenance);
      if (plan == null) return runner.accept(request);
      UUID nonce = UUID.randomUUID();
      var prepared = new OperationPreparation(arguments, RecordedRootPlan.SCHEMA, plan.toReplayPayload());
      String envelope = JSON.writeValueAsString(Map.of("version", 1, "key", key, "nonce", nonce,
          "descriptor", descriptor, "preparation", prepared, "context", context,
          "executor", ExecutorTag.UI, "occurredAt", provenance.occurredAt()));
      var stored = new OperationStore.Preparation(nonce, new OperationPreparedPayload(false, envelope));
      var saved = runner.withPreparation(request, scope -> runner.savePreparation(scope.request(), stored).orElseThrow());
      return runner.acceptPrepared(request, saved.nonce());
    }
    @Override public void close() throws java.io.IOException { store.close(); }
  }

  @Test void S55ParentsIncludeFutureRootsBeforeAnyChildOrQueueEntryExists() throws Exception {
    try (var f = new Fixture()) {
      var parent = f.accept(OperationKind.INGEST, "core.ingest-files",
          new RecordedRootPlan("frozen-generation", List.of(root("first", false, List.of()), root("future", false, List.of()))), false);
      assertEquals("OLD_PRODUCER", inspect(directory.resolve("future/target.txt"), snapshot(f.store), f.store));
      assertFalse(f.store.find(parent.accepted().key()).orElseThrow().state().terminal());
    }
  }
  @Test void S55DisjointAndExplicitlyExcludedRootsContinueWithoutCancellation() throws Exception {
    try (var f = new Fixture()) {
      var parent = f.accept(OperationKind.INGEST, "core.ingest-files", new RecordedRootPlan("frozen-generation",
          List.of(root("first", false, List.of(directory.resolve("first/excluded"))), root("second", false, List.of()))), false);
      assertEquals("CLEAR", inspect(directory.resolve("first/excluded/target.txt"), snapshot(f.store), f.store));
      assertEquals("CLEAR", inspect(directory.resolve("unrelated/target.txt"), snapshot(f.store), f.store));
      assertFalse(f.store.find(parent.accepted().key()).orElseThrow().state().terminal());
    }
  }
  @Test void S55SingleFilePlanDoesNotBroadenToAPrefixSibling() throws Exception {
    try (var f = new Fixture()) {
      f.accept(OperationKind.INGEST, "core.ingest-files", new RecordedRootPlan("frozen-generation",
          List.of(root("exact.txt", true, List.of()))), false);
      assertEquals("OLD_PRODUCER", inspect(directory.resolve("exact.txt"), snapshot(f.store), f.store));
      assertEquals("CLEAR", inspect(directory.resolve("exact.txt.more"), snapshot(f.store), f.store));
    }
  }
  @Test void S55OpenReindexScopeAlsoCoversFuturePublication() throws Exception {
    try (var f = new Fixture()) {
      f.accept(OperationKind.REINDEX, "core.reindex", new RecordedRootPlan("frozen-generation",
          List.of(root("future", false, List.of()))), false);
      assertEquals("OLD_PRODUCER", inspect(directory.resolve("future/target.txt"), snapshot(f.store), f.store));
    }
  }
  @Test void N55MissingAcceptedPreparationAndUnknownReindexIdentityAreNeverClear() throws Exception {
    try (var f = new Fixture()) {
      f.accept(OperationKind.INGEST, "core.ingest-files", null, false);
      assertEquals("UNKNOWN_SCOPE", inspect(directory.resolve("outside.txt"), snapshot(f.store), f.store));
    }
  }
  @Test void N55UnknownReindexProducerRemainsConservative() throws Exception {
    try (var f = new Fixture()) {
      f.accept(OperationKind.REINDEX, "core.custom-reindex", null, false);
      assertEquals("UNKNOWN_SCOPE", inspect(directory.resolve("outside.txt"), snapshot(f.store), f.store));
    }
  }
  @Test void S55BothInvokeAndUndoDigestOnlyFileOperationsWaitUntilActualTerminalPersistence() throws Exception {
    for (boolean undo : List.of(false, true)) {
      try (var f = new Fixture()) {
        var accepted = f.accept(OperationKind.OPERATION, "core.file-operations", null, undo);
        assertEquals("UNKNOWN_SCOPE", inspect(directory.resolve("outside.txt"), snapshot(f.store), f.store));
        f.runner.start(accepted, handle -> OperationExecution.finished(OperationResult.success("unit fixture terminal")));
        assertEquals("CLEAR", inspect(directory.resolve("outside.txt"), snapshot(f.store), f.store));
      }
    }
  }
  @Test void N55IncompleteSnapshotCannotBeAnEmptySuccessfulProof() throws Exception {
    try (var f = new Fixture()) {
      Object actual = snapshot(f.store);
      var type = actual.getClass();
      long sequence = (long) type.getMethod("acceptanceSequence").invoke(actual);
      Object incomplete = type.getConstructor(long.class, List.class, boolean.class).newInstance(sequence, List.of(), false);
      assertEquals("SNAPSHOT_INCOMPLETE", inspect(directory.resolve("outside.txt"), incomplete, f.store));
    }
  }
  @Test void S55AcceptedChildKeepsItsFrozenScopeAndParentKeepsNotYetStartedRoots() throws Exception {
    try (var f = new Fixture()) {
      var first = root("first", false, List.of());
      var parent = f.accept(OperationKind.INGEST, "core.ingest-files",
          new RecordedRootPlan("frozen-generation", List.of(first, root("future", false, List.of()))), false);
      var completion = new CompletableFuture<OperationResult>();
      var child = new java.util.concurrent.atomic.AtomicReference<OperationRecord>();
      try {
        f.runner.start(parent, handle -> {
          child.set(f.runner.acceptIngestChild(handle, first).accepted());
          return new OperationExecution(OperationResult.success("unit fixture holds enumeration"), completion);
        });
        assertEquals("OLD_PRODUCER", inspect(directory.resolve("future/target.txt"), snapshot(f.store), f.store));
        Object actual = snapshot(f.store); Class<?> type = actual.getClass();
        long sequence = (long) type.getMethod("acceptanceSequence").invoke(actual);
        Object childOnly = type.getConstructor(long.class, List.class, boolean.class).newInstance(sequence, List.of(child.get()), true);
        assertEquals("OLD_PRODUCER", inspect(directory.resolve("first/target.txt"), childOnly, f.store));
      } finally { completion.complete(OperationResult.success("unit fixture exits")); }
    }
  }
}
