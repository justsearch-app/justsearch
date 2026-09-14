/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prepared-only contract tests for {@link ReindexHandler}. */
final class ReindexHandlerTest {
  private static final EngineContext CONTEXT = TestEngineContexts.internal();
  private static final InvocationProvenance PROVENANCE =
      InvocationProvenance.systemInternal(Instant.EPOCH);

  @Test
  void preparationFreezesDefaultAndExplicitForcePolicies(@TempDir Path directory) {
    Path root = directory.resolve("watched").toAbsolutePath().normalize();
    ReindexHandler handler = handler(RecordedIngestionService.unavailable(),
        context -> List.of(new RootBinding(root, null)), context -> "generation-1", List.of());

    RecordedRootPlan defaultPlan = plan(handler.prepare("{}", PROVENANCE, CONTEXT));
    assertFalse(defaultPlan.roots().getFirst().force(), "omitted force defaults to false");

    RecordedRootPlan forcedPlan = plan(handler.prepare("{\"force\":true}", PROVENANCE, CONTEXT));
    assertTrue(forcedPlan.roots().getFirst().force(), "force=true is frozen into the root policy");

    RecordedRootPlan explicitFalse = plan(handler.prepare("{\"force\":false}", PROVENANCE, CONTEXT));
    assertFalse(explicitFalse.roots().getFirst().force(), "force=false remains explicit and false");
    assertEquals("generation-1", forcedPlan.generation());
  }

  @Test
  void preparationPartitionsNestedRootsAndPreservesNullableLabels(@TempDir Path directory) {
    Path parent = directory.resolve("watched").toAbsolutePath().normalize();
    Path nested = parent.resolve("nested").normalize();
    ReindexHandler handler = handler(RecordedIngestionService.unavailable(),
        context -> List.of(new RootBinding(parent, null), new RootBinding(nested, "documents")),
        context -> "generation-nested", List.of("*.tmp"));

    RecordedRootPlan plan = plan(handler.prepare("{}", PROVENANCE, CONTEXT));

    assertEquals("generation-nested", plan.generation());
    assertEquals(2, plan.roots().size(), "different labels retain nested ownership");
    RecordedRootPlan.Root outer = plan.roots().stream()
        .filter(root -> root.path().equals(parent)).findFirst().orElseThrow();
    RecordedRootPlan.Root inner = plan.roots().stream()
        .filter(root -> root.path().equals(nested)).findFirst().orElseThrow();
    assertNull(outer.collection(), "an unlabeled watched root stays unlabeled");
    assertEquals("documents", inner.collection());
    assertEquals(List.of(nested), outer.excludedSubtrees());
    assertEquals(List.of("*.tmp"), outer.excludePatterns());
    assertEquals(List.of("*.tmp"), inner.excludePatterns());
  }

  @Test
  void malformedArgumentsAreTypedBadRequestPreparationRefusals() {
    ReindexHandler handler = handler(RecordedIngestionService.unavailable(),
        context -> List.of(), context -> "generation-1", List.of());

    OperationPreparationRefused refusal = assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare("not-json", PROVENANCE, CONTEXT));
    assertFalse(refusal.refusal().success());
    assertEquals("BAD_REQUEST", refusal.refusal().errorCode().orElseThrow());
  }

  @Test
  void directExecutionAndMissingRecordAreRefusedBeforeIngestion() {
    CapturingIngestion ingestion = new CapturingIngestion();
    ReindexHandler handler = handler(ingestion, context -> List.of(), context -> "generation-1", List.of());
    OperationPreparation prepared = handler.prepare("{}", PROVENANCE, CONTEXT);

    IllegalStateException direct = assertThrows(IllegalStateException.class,
        () -> handler.execute("{}", CONTEXT));
    assertEquals("Reindex requires an accepted prepared invocation", direct.getMessage());
    NullPointerException missing = assertThrows(NullPointerException.class,
        () -> handler.executePrepared(prepared, PROVENANCE, CONTEXT, null));
    assertEquals("Accepted reindex record", missing.getMessage());
    assertFalse(ingestion.invoked, "refused paths do not call the recorded owner");
  }

  @Test
  void preparedExecutionPassesExactRecordAndContextAndReturnsDelayedCompletion(@TempDir Path directory) {
    AtomicInteger rootReads = new AtomicInteger();
    AtomicInteger generationReads = new AtomicInteger();
    AtomicInteger exclusionReads = new AtomicInteger();
    AtomicBoolean executing = new AtomicBoolean();
    Path root = directory.resolve("watched").toAbsolutePath().normalize();
    CapturingIngestion ingestion = new CapturingIngestion();
    ReindexHandler handler = new ReindexHandler(ingestion,
        context -> {
          assertFalse(executing.get(), "root snapshot belongs to preparation");
          rootReads.incrementAndGet();
          return List.of(new RootBinding(root, "documents"));
        },
        context -> {
          assertFalse(executing.get(), "generation belongs to preparation");
          generationReads.incrementAndGet();
          return "generation-1";
        },
        () -> {
          assertFalse(executing.get(), "exclusions belong to preparation");
          exclusionReads.incrementAndGet();
          return List.of("*.tmp");
        });

    OperationPreparation prepared = handler.prepare("{\"force\":true}", PROVENANCE, CONTEXT);
    OperationRecordHandle record = record("reindex-key");
    executing.set(true);
    OperationExecution execution = handler.executePrepared(prepared, PROVENANCE, CONTEXT, record);

    assertSame(record, ingestion.record);
    assertSame(CONTEXT, ingestion.context);
    assertSame(ingestion.accepted, execution.response());
    assertFalse(execution.completion().toCompletableFuture().isDone(),
        "the accepted response is not actual completion");
    assertEquals(1, rootReads.get(), "execution must not re-read watched roots");
    assertEquals(1, generationReads.get(), "execution must not re-read generation");
    assertEquals(1, exclusionReads.get(), "execution must not re-read exclusions");

    ingestion.completion.complete(ingestion.finished);
    assertSame(ingestion.finished, execution.completion().toCompletableFuture().join());
  }

  @Test
  void unavailableRecordedOwnerFailsAcceptedPreparedExecution(@TempDir Path directory) {
    Path root = directory.resolve("watched").toAbsolutePath().normalize();
    ReindexHandler handler = handler(RecordedIngestionService.unavailable(),
        context -> List.of(new RootBinding(root, null)), context -> "generation-1", List.of());
    OperationPreparation prepared = handler.prepare("{}", PROVENANCE, CONTEXT);

    IllegalStateException unavailable = assertThrows(IllegalStateException.class,
        () -> handler.executePrepared(prepared, PROVENANCE, CONTEXT, record("unavailable-key")));
    assertTrue(unavailable.getMessage().contains("Recorded ingestion owner is unavailable"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"force", "file", "schema", "content"})
  void frozenCorrelationMismatchRefusesBeforeRecordedOwner(String mismatch, @TempDir Path directory) {
    var ingestion = new CapturingIngestion();
    var handler = handler(ingestion, context -> List.of(), context -> "generation-1", List.of());
    var plan = new RecordedRootPlan("generation-1", List.of(new RecordedRootPlan.Root(
        directory, "documents", mismatch.equals("force"), mismatch.equals("file"), List.of(), List.of())));
    var prepared = new OperationPreparation("{}",
        mismatch.equals("schema") ? "other.v1" : RecordedRootPlan.SCHEMA, plan.toReplayPayload(),
        mismatch.equals("content") ? OperationPreparation.Content.CONTENT : OperationPreparation.Content.METADATA);
    assertThrows(IllegalArgumentException.class,
        () -> handler.executePrepared(prepared, PROVENANCE, CONTEXT, record("mismatch")));
    assertFalse(ingestion.invoked);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "null", "{\"force\":0}", "{\"force\":\"true\"}"})
  void wrongArgumentShapeRefusesBeforeAuthorityReads(String arguments) {
    var handler = new ReindexHandler(RecordedIngestionService.unavailable(),
        context -> { throw new AssertionError("roots must not be read"); },
        context -> { throw new AssertionError("generation must not be read"); },
        () -> { throw new AssertionError("exclusions must not be read"); });
    var refusal = assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare(arguments, PROVENANCE, CONTEXT));
    assertEquals("BAD_REQUEST", refusal.refusal().errorCode().orElseThrow());
  }

  private static ReindexHandler handler(RecordedIngestionService ingestion,
      java.util.function.Function<EngineContext, List<RootBinding>> roots,
      java.util.function.Function<EngineContext, String> generation,
      List<String> exclusions) {
    return new ReindexHandler(ingestion, roots, generation, () -> exclusions);
  }

  private static RecordedRootPlan plan(OperationPreparation preparation) {
    assertEquals(RecordedRootPlan.SCHEMA, preparation.replaySchema());
    assertEquals(OperationPreparation.Content.METADATA, preparation.content());
    return RecordedRootPlan.fromReplayPayload(preparation.replayPayloadJson());
  }

  private static OperationRecordHandle record(String key) {
    return new OperationRecordHandle() {
      @Override public long id() { return 17L; }
      @Override public String key() { return key; }
      @Override public void checkpoint(String cursor, long completed, long failed) {}
    };
  }

  private static final class CapturingIngestion implements RecordedIngestionService {
    private final OperationResult accepted = OperationResult.success("accepted");
    private final OperationResult finished = OperationResult.success("finished");
    private final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    private OperationRecordHandle record;
    private EngineContext context;
    private boolean invoked;

    @Override
    public OperationExecution execute(OperationRecordHandle record, EngineContext context) {
      this.record = record;
      this.context = context;
      invoked = true;
      return new OperationExecution(accepted, completion);
    }

    @Override
    public void maintain() {}
  }
}
