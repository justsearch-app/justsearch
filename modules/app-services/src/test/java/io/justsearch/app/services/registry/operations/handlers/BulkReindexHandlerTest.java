/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class BulkReindexHandlerTest {
  private static final EngineContext CONTEXT = TestEngineContexts.internal();
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final String CANONICAL_INPUTS = "{\"dimension\":768}";
  private static final String USER_ARGUMENTS = "{\"corpusIds\":[\"legacy-label\"]}";

  @ParameterizedTest
  @EnumSource(RecordedBulkPlan.Profile.class)
  void prepareFreezesAllWatchedRootsExclusionsAndPhysicalTargetWithoutStartingMigration(
      RecordedBulkPlan.Profile profile) {
    List<RootBinding> watched = new ArrayList<>(List.of(
        binding("watched-one"), binding("watched-two")));
    List<String> exclusions = new ArrayList<>(List.of("*.tmp", "cache/**"));
    FakeIndexingService indexing = new FakeIndexingService(
        List.of("serving-g1", "serving-g1"), List.of("serving-g1", "serving-g1"));
    FakeIngestionService ingestion = new FakeIngestionService();
    BulkReindexHandler handler = handler(profile, ingestion, watched, exclusions, indexing);

    OperationPreparation prepared = handler.prepare(arguments(profile), provenance(), CONTEXT);
    watched.clear();
    exclusions.clear();
    RecordedBulkPlan plan = RecordedBulkPlan.fromReplayPayload(prepared.replayPayloadJson());

    assertEquals(profile, plan.profile());
    assertEquals(2, plan.scope().roots().size());
    assertEquals(List.of("*.tmp", "cache/**"), plan.scope().roots().getFirst().excludePatterns());
    assertTrue(plan.scope().roots().stream().allMatch(root -> root.force() && !root.singleFile()));
    assertEquals("serving-g1", plan.scope().generation());
    assertEquals(indexing.target, plan.target());
    assertEquals(profile == RecordedBulkPlan.Profile.USER_BULK ? 2 : 0,
        indexing.servingGenerationReads);
    assertEquals(profile == RecordedBulkPlan.Profile.RECOVERY_REBUILD ? 2 : 0,
        indexing.rebuildGenerationReads);
    assertEquals(1, indexing.targetReads);
    assertEquals(0, indexing.startMigrationCalls);
    assertEquals(0, ingestion.executeCalls);
  }

  @Test
  void refusesPreparationWhenServingGenerationChangesAcrossTargetCapture() {
    FakeIndexingService indexing = new FakeIndexingService(
        List.of("serving-g1", "serving-g2"), List.of("serving-g1", "serving-g1"));
    FakeIngestionService ingestion = new FakeIngestionService();
    BulkReindexHandler handler = handler(RecordedBulkPlan.Profile.USER_BULK, ingestion,
        new ArrayList<>(List.of(binding("watched"))), new ArrayList<>(List.of("*.tmp")), indexing);

    assertThrows(IllegalStateException.class,
        () -> handler.prepare(USER_ARGUMENTS, provenance(), CONTEXT));
    assertEquals(2, indexing.servingGenerationReads);
    assertEquals(0, indexing.rebuildGenerationReads);
    assertEquals(1, indexing.targetReads);
    assertEquals(0, indexing.startMigrationCalls);
    assertEquals(0, ingestion.executeCalls);
  }

  @Test
  void preparationRejectsInvalidProfileArgumentsBeforeCapturingAuthority() {
    FakeIndexingService indexing = new FakeIndexingService(List.of("serving-g1", "serving-g1"));
    BulkReindexHandler userBulk = handler(RecordedBulkPlan.Profile.USER_BULK,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), indexing);
    BulkReindexHandler recovery = handler(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), indexing);

    assertThrows(OperationPreparationRefused.class,
        () -> userBulk.prepare("{}", provenance(), CONTEXT));
    assertThrows(OperationPreparationRefused.class,
        () -> recovery.prepare("{\"unexpected\":true}", provenance(), CONTEXT));
    assertEquals(0, indexing.servingGenerationReads);
    assertEquals(0, indexing.rebuildGenerationReads);
    assertEquals(0, indexing.targetReads);
  }

  @Test
  void recoveryProfileUsesReadOnlyGenerationWitnessWhileUserBulkRequiresWriterWitness() {
    FakeIndexingService indexing = new FakeIndexingService(
        List.of("serving-g1"), List.of("serving-g1", "serving-g1"));
    indexing.servingGenerationUnavailable = true;
    BulkReindexHandler recovery = handler(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), indexing);

    OperationPreparation prepared = recovery.prepare("{}", provenance(), CONTEXT);

    assertEquals("serving-g1",
        RecordedBulkPlan.fromReplayPayload(prepared.replayPayloadJson()).scope().generation());
    assertEquals(2, indexing.rebuildGenerationReads,
        "recovery checks the read-only witness both before and after target capture");
    assertEquals(1, indexing.targetReads);
    assertEquals(0, indexing.servingGenerationReads);

    BulkReindexHandler userBulk = handler(RecordedBulkPlan.Profile.USER_BULK,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), indexing);
    assertThrows(IllegalStateException.class,
        () -> userBulk.prepare(USER_ARGUMENTS, provenance(), CONTEXT));
    assertEquals(1, indexing.servingGenerationReads,
        "ordinary bulk reindex continues to require the ingest-serving generation");
    assertEquals(1, indexing.targetReads,
        "ordinary preparation refuses before capturing target when its writer is absent");
  }

  @Test
  void directExecutionAndPreparedExecutionWithoutAcceptedRecordRefuseWithoutEffects() {
    FakeIndexingService indexing = new FakeIndexingService(
        List.of("serving-g1", "serving-g1"), List.of("serving-g1", "serving-g1"));
    FakeIngestionService ingestion = new FakeIngestionService();
    BulkReindexHandler handler = handler(RecordedBulkPlan.Profile.USER_BULK, ingestion,
        new ArrayList<>(List.of(binding("watched"))), new ArrayList<>(List.of("*.tmp")), indexing);
    OperationPreparation prepared = handler.prepare(USER_ARGUMENTS, provenance(), CONTEXT);

    assertThrows(IllegalStateException.class, () -> handler.execute(USER_ARGUMENTS, CONTEXT));
    assertThrows(NullPointerException.class,
        () -> handler.executePrepared(prepared, provenance(), CONTEXT, null));
    assertEquals(0, ingestion.executeCalls);
    assertEquals(0, indexing.startMigrationCalls);
  }

  @Test
  void preparedExecutionReturnsTheRecordedOwnersActualPendingCompletion() {
    FakeIndexingService indexing = new FakeIndexingService(List.of("serving-g1", "serving-g1"));
    FakeIngestionService ingestion = new FakeIngestionService();
    CompletableFuture<OperationResult> pending = new CompletableFuture<>();
    OperationExecution expected = new OperationExecution(OperationResult.success("accepted"), pending);
    ingestion.execution = expected;
    BulkReindexHandler handler = handler(RecordedBulkPlan.Profile.USER_BULK, ingestion,
        new ArrayList<>(List.of(binding("watched"))), new ArrayList<>(List.of("*.tmp")), indexing);
    OperationPreparation prepared = handler.prepare(USER_ARGUMENTS, provenance(), CONTEXT);
    OperationRecordHandle record = new OperationRecordHandle() {
      @Override public long id() { return 7L; }
      @Override public String key() { return "accepted-key"; }
      @Override public void checkpoint(String cursor, long completed, long failed) {}
    };

    OperationExecution actual = handler.executePrepared(prepared, provenance(), CONTEXT, record);

    assertSame(expected, actual);
    assertSame(pending, actual.completion());
    assertFalse(actual.completion().toCompletableFuture().isDone());
    assertSame(record, ingestion.lastRecord);
    assertEquals(1, ingestion.executeCalls);
    assertEquals(0, indexing.startMigrationCalls);
  }

  @Test
  void approvalPreviewBoundsDisplayedRootsAndDescribesRestartSpanningOperation() {
    List<RootBinding> roots = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      roots.add(binding(String.format("preview-root-%02d", index)));
    }
    BulkReindexHandler handler = handler(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
        new FakeIngestionService(), roots, new ArrayList<>(List.of("*.tmp")),
        new FakeIndexingService(List.of("serving-g1", "serving-g1")));
    OperationPreparation prepared = handler.prepare("{}", provenance(), CONTEXT);

    String preview = handler.approvalPreview(prepared).summary();

    assertTrue(preview.contains("Rebuild all 8 watched locations"));
    assertTrue(preview.contains("Additional locations: 2"));
    assertTrue(preview.contains("continue through Engine restarts"));
    assertTrue(preview.contains("preview-root-00"));
    assertTrue(preview.contains("preview-root-05"));
    assertFalse(preview.contains("preview-root-06"));
    assertFalse(preview.contains("preview-root-07"));
    assertFalse(preview.contains(CANONICAL_INPUTS));
  }

  @Test
  void preparedPlanCannotBeValidatedByTheOtherProducerProfile() {
    FakeIndexingService indexing = new FakeIndexingService(List.of("serving-g1", "serving-g1"));
    OperationPreparation userPlan = handler(RecordedBulkPlan.Profile.USER_BULK,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), indexing).prepare(USER_ARGUMENTS, provenance(), CONTEXT);
    BulkReindexHandler rebuild = handler(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
        new FakeIngestionService(), new ArrayList<>(List.of(binding("watched"))),
        new ArrayList<>(List.of("*.tmp")), new FakeIndexingService(List.of("g1", "g1")));

    assertThrows(IllegalArgumentException.class, () -> rebuild.validatePreparation(userPlan));
  }

  private static BulkReindexHandler handler(RecordedBulkPlan.Profile profile,
      FakeIngestionService ingestion, List<RootBinding> watched, List<String> exclusions,
      FakeIndexingService indexing) {
    return new BulkReindexHandler(profile, ingestion, ignored -> watched, () -> indexing, () -> exclusions);
  }

  private static String arguments(RecordedBulkPlan.Profile profile) {
    return profile == RecordedBulkPlan.Profile.USER_BULK ? USER_ARGUMENTS : "{}";
  }

  private static RootBinding binding(String path) {
    return new RootBinding(Path.of(path).toAbsolutePath().normalize(), "documents");
  }

  private static InvocationProvenance provenance() {
    return EngineProvenance.invocation(CONTEXT, ExecutorTag.UI, OCCURRED_AT, Optional.empty());
  }

  private static final class FakeIndexingService implements IndexingService {
    private final List<String> servingGenerations;
    private final List<String> rebuildGenerations;
    private final IndexTargetSnapshot target = new IndexTargetSnapshot(sha256(CANONICAL_INPUTS), CANONICAL_INPUTS);
    private int servingGenerationReads;
    private int rebuildGenerationReads;
    private int targetReads;
    private int startMigrationCalls;
    private boolean servingGenerationUnavailable;

    private FakeIndexingService(List<String> servingGenerations) {
      this(servingGenerations, servingGenerations);
    }

    private FakeIndexingService(List<String> servingGenerations, List<String> rebuildGenerations) {
      this.servingGenerations = servingGenerations;
      this.rebuildGenerations = rebuildGenerations;
    }

    @Override public List<Path> getWatchedPaths(EngineContext context) { return List.of(); }
    @Override public void addWatchedPath(Path path, EngineContext context) {}
    @Override public int removeWatchedPath(Path path, EngineContext context) { return 0; }
    @Override public void flush(EngineContext context) {}

    @Override public String captureServingGeneration(EngineContext context) {
      int index = Math.min(servingGenerationReads, servingGenerations.size() - 1);
      servingGenerationReads++;
      if (servingGenerationUnavailable) {
        throw new IllegalStateException("Ingest writer is unavailable");
      }
      return servingGenerations.get(index);
    }

    @Override public String captureRebuildGeneration(EngineContext context) {
      int index = Math.min(rebuildGenerationReads, rebuildGenerations.size() - 1);
      rebuildGenerationReads++;
      return rebuildGenerations.get(index);
    }

    @Override public IndexTargetSnapshot captureIndexTarget(EngineContext context) {
      targetReads++;
      return target;
    }

    @Override public MigrationOutcome startMigration(String reason, EngineContext context) {
      startMigrationCalls++;
      return new MigrationOutcome(true, true);
    }
  }

  private static final class FakeIngestionService implements RecordedIngestionService {
    private int executeCalls;
    private OperationRecordHandle lastRecord;
    private OperationExecution execution = OperationExecution.finished(OperationResult.success("completed"));

    @Override public OperationExecution execute(OperationRecordHandle parent, EngineContext context) {
      executeCalls++;
      lastRecord = parent;
      return execution;
    }

    @Override public void maintain() {}
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
