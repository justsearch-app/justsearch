package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 912 item 2 — commit-reason accounting at the one commit funnel
 * ({@link CommitOps#commitAndTrack(CommitReason)}).
 *
 * <p>885's A3 arm could measure that the commit count barely moved under three multiplicative
 * cadence relaxations but could not say WHICH trigger held the floor, because no per-trigger count
 * existed. These tests pin the two properties that make the new breakdown trustworthy as
 * attribution evidence: each reason accrues to its own slot, and the total is exactly their sum —
 * so a reader can never be shown a breakdown that fails to account for every commit.
 */
class CommitReasonAccountingTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  /** Opens a real runtime with one uncommitted document pending, so each commit does real work. */
  private RunningRuntime openWithPendingDoc(String name, int docs) throws Exception {
    Path dir = tempDir.resolve(name);
    Files.createDirectories(dir);
    RunningRuntime runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    for (int i = 0; i < docs; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, name + "-" + i, SchemaFields.DOC_UID, name + "-" + i + "#0")));
    }
    return runtime;
  }

  /**
   * The load-bearing property: a mixed sequence of reasons lands in per-reason slots whose sum is
   * the total. Asserted against a distribution (3/2/1) rather than one commit per reason, so a
   * counter that incremented the wrong slot, or incremented every slot, fails rather than passing
   * on symmetry.
   */
  @Test
  void aMixedCommitSequenceLandsInPerReasonSlotsAndNowhereElse() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("mixed", 6)) {
      CommitOps ops = runtime.commitOps();
      CommitCounters counters = runtime.session().commitCount;
      long before = counters.get();

      ops.commitAndTrack(CommitReason.TIMER);
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      ops.commitAndTrack(CommitReason.TIMER);
      ops.commitAndTrack(CommitReason.BACKFILL_NER);
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      ops.commitAndTrack(CommitReason.TIMER);

      assertEquals(3L, counters.get(CommitReason.TIMER), "TIMER must count its own three commits");
      assertEquals(
          2L,
          counters.get(CommitReason.INDEXING_LOOP_IDLE),
          "INDEXING_LOOP_IDLE must count its own two commits");
      assertEquals(
          1L, counters.get(CommitReason.BACKFILL_NER), "BACKFILL_NER must count its one commit");
      assertEquals(
          0L,
          counters.get(CommitReason.DRAIN),
          "A reason that never fired must stay at zero, not absorb another reason's commits");
      assertEquals(before + 6L, counters.get(), "The total must be the sum of the per-reason slots");

      // The snapshot is the shape a reader attributes from, so it must expose exactly the three
      // reasons that fired with exactly their counts. Asserting the whole map (not its sum, which
      // merely restates CommitCounters.get()'s own definition) is what makes this non-vacuous.
      assertEquals(
          Map.of(
              CommitReason.TIMER, 3L,
              CommitReason.INDEXING_LOOP_IDLE, 2L,
              CommitReason.BACKFILL_NER, 1L),
          counters.snapshot(),
          "The snapshot must expose the reasons that fired, with their counts, and nothing else");
    }
  }

  /**
   * The blue/green cutover commit — the single most consequential commit in the system, the one
   * that stamps {@code build_state=COMPLETE} on the Green about to be promoted — used to be
   * recorded as {@code UNKNOWN}, because {@code commitWithBuildState} called the no-arg
   * {@code commitAndTrack()} (tempdoc 912 item 1). Asserting UNKNOWN stays at zero is the half of
   * this that would have caught the original defect.
   */
  @Test
  void theCutoverCommitIsAttributedToMigrationCutoverNotUnknown() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("cutover", 1)) {
      CommitCounters counters = runtime.session().commitCount;
      runtime
          .commitOps()
          .commitWithBuildState(
              LuceneRuntimeTypes.BuildState.COMPLETE, CommitReason.MIGRATION_CUTOVER);

      assertEquals(1L, counters.get(CommitReason.MIGRATION_CUTOVER));
      assertEquals(
          0L,
          counters.get(CommitReason.UNKNOWN),
          "the cutover commit must not fall through to UNKNOWN");
      assertEquals(
          "COMPLETE",
          runtime.latestCommitUserDataBestEffort().get("build_state"),
          "attribution must not have cost the build-state stamp the cutover depends on");
    }
  }

  /**
   * The switch-buffer replay used to call the low-level {@code CommitOps.commit()} directly, which
   * skips the counter, the {@code pendingDocs} reset, the telemetry event and the
   * {@code CommitCompletedListener}. That primitive is package-private now, so this pins the
   * behaviour the routing restored rather than merely that it compiles.
   */
  @Test
  void theSwitchBufferReplayCommitIsCountedAndNotifiesTheListener() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("switchbuffer", 3)) {
      CommitCounters counters = runtime.session().commitCount;
      java.util.List<CommitReason> notified = new java.util.ArrayList<>();
      runtime.commitOps().setCommitCompletedListener(notified::add);

      assertTrue(runtime.session().pendingDocs.get() > 0, "documents are pending before the commit");
      runtime.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);

      assertEquals(1L, counters.get(CommitReason.SWITCH_BUFFER_REPLAY));
      assertEquals(
          0L, runtime.session().pendingDocs.get(), "the funnel resets pendingDocs; commit() did not");
      assertEquals(
          java.util.List.of(CommitReason.SWITCH_BUFFER_REPLAY),
          notified,
          "the post-commit listener — which keeps the embedding fingerprint in sync — must fire");
    }
  }

  /**
   * A null reason is attributed to UNKNOWN rather than dropped. Dropping it would break the
   * total-equals-sum property in the one case a caller is most likely to hit by accident.
   */
  @Test
  void aNullReasonIsCountedAsUnknownRatherThanDropped() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("nullreason", 2)) {
      CommitCounters counters = runtime.session().commitCount;
      runtime.commitOps().commitAndTrack((CommitReason) null);
      runtime.commitOps().commitAndTrack();

      assertEquals(
          2L, counters.get(CommitReason.UNKNOWN), "Both unattributed commits must land in UNKNOWN");
      assertEquals(
          counters.get(),
          counters.snapshot().values().stream().mapToLong(Long::longValue).sum(),
          "An unattributed commit must still be accounted for in the breakdown");
    }
  }

  /**
   * The reason reaching the counter must be the same one reaching telemetry. Pinning both from one
   * commit is what stops the breakdown and the {@code index.runtime.commit_total} counter — which
   * the live attribution run reads — from disagreeing about which trigger fired.
   */
  @Test
  void theCounterAndTheTelemetryEventSeeTheSameReason() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("telemetry", 1)) {
      java.util.List<CommitReason> observed = new java.util.ArrayList<>();
      runtime.session().telemetryEvents =
          new LuceneRuntimeTypes.TelemetryEvents() {
            @Override
            public void onCommit(long latencyMs, CommitReason reason) {
              observed.add(reason);
            }
          };

      runtime.commitOps().commitAndTrack(CommitReason.BACKFILL_COMBINED);

      assertEquals(
          java.util.List.of(CommitReason.BACKFILL_COMBINED),
          observed,
          "Telemetry must see the caller's reason");
      assertEquals(
          1L,
          runtime.session().commitCount.get(CommitReason.BACKFILL_COMBINED),
          "The counter must see the SAME reason telemetry saw");
      assertTrue(
          runtime.session().commitCount.get(CommitReason.UNKNOWN) == 0L,
          "An attributed commit must not also land in UNKNOWN");
    }
  }
}
