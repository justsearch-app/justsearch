/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerIngestServiceAppliedGenerationTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void realCommitReadbackDistinguishesGenerationsAndIgnoresUncommittedMetadata() throws Exception {
    Path base = tempDir.resolve("real-commits");
    var generations = new IndexGenerationManager(base);
    var metadata = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>(
        committed("{\"physical\":\"committed\"}"));
    CommitMetadataSource source = () -> new java.util.HashMap<String, Object>(metadata.get());
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0), source, values -> {
      assertEquals(metadata.get().get(IndexFingerprint.COMMIT_META_KEY),
          values.get(IndexFingerprint.COMMIT_META_KEY));
    });
    var configuration = new ResolvedConfigBuilder().putDefault("index.commit.meta.enabled", "true").build();
    var observations = new java.util.ArrayList<AppliedIndexGeneration>();
    for (int iteration = 0; iteration < 2; iteration++) {
      if (iteration != 0) {
        generations.startMigration("same-inputs-new-generation");
        generations.promoteBuildingGenerationToActive();
      }
      var active = generations.initializeOrLoad();
      metadata.set(committed("{\"physical\":\"committed\"}"));
      try (var runtime = schema.atPath(active.activeGenerationPath()).withConfig(configuration)
          .withExecutorRegistrations(testLuceneExecutors()).open()) {
        runtime.commitOps().stopCommitTimer();
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, "committed-document", SchemaFields.DOC_UID, "committed-document-uid",
            SchemaFields.CONTENT, "applied input evidence")));
        runtime.commitOps().commitAndTrack();
        var service = worker(base, active.activeGenerationPath(), runtime);
        var observation = service.captureAppliedGeneration(CallContext.none());
        assertEquals(active.activeGenerationId(), observation.generationId());
        assertEquals(runtime.latestCommitUserDataBestEffort().get(IndexFingerprint.COMMIT_META_INPUTS_KEY),
            observation.target().canonicalInputsJson());
        metadata.set(committed("{\"physical\":\"desired-only\"}"));
        assertEquals(observation, service.captureAppliedGeneration(CallContext.none()),
            "changing the metadata producer without a commit must not change applied inputs");
        observations.add(observation);
      }
    }
    assertNotEquals(observations.get(0).generationId(), observations.get(1).generationId());
    assertEquals(observations.get(0).target(), observations.get(1).target());
  }

  @AfterEach
  void resetFingerprintProviders() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  @Test
  void observesCommittedBlueDuringBuildAndIgnoresDesiredFingerprintChanges() throws Exception {
    Path base = tempDir.resolve("blue-building");
    IndexGenerationManager generations = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout blue = generations.initializeOrLoad();
    generations.startMigration("green-build");
    Map<String, String> committed = committed("{\"physical\":\"blue\"}");
    LuceneRuntime runtime = runtime(committed);
    WorkerIngestService service = worker(base, blue.activeGenerationPath(), runtime);

    AppliedIndexGeneration first = service.captureAppliedGeneration(CallContext.none());
    IndexFingerprint.installModelFingerprintProviders(
        () -> IndexFingerprint.ModelFingerprint.present("f".repeat(64)),
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured);
    AppliedIndexGeneration afterDesiredChange =
        service.captureAppliedGeneration(CallContext.none());

    assertEquals(blue.activeGenerationId(), first.generationId());
    assertEquals(new IndexTargetSnapshot(
        committed.get(IndexFingerprint.COMMIT_META_KEY),
        committed.get(IndexFingerprint.COMMIT_META_INPUTS_KEY)), first.target());
    assertEquals(
        first,
        afterDesiredChange,
        "desired fingerprint providers cannot alter the committed applied observation");
  }

  @Test
  void missingUnreadableOrMalformedCommittedMetadataRefuses() throws Exception {
    Path base = tempDir.resolve("invalid-metadata");
    IndexGenerationManager generations = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout active = generations.initializeOrLoad();

    assertUnavailable(worker(base, active.activeGenerationPath(), runtime(Map.of())));
    assertUnavailable(
        worker(base, active.activeGenerationPath(), runtime(committed("not-json"))));
    assertUnavailable(worker(base, active.activeGenerationPath(), runtime(committed("[]"))));
    assertUnavailable(worker(base, active.activeGenerationPath(), runtime(committed("{} {}"))));
    assertUnavailable(worker(base, active.activeGenerationPath(), runtime(committed("{\"x\":1,\"x\":2}"))));

    LuceneRuntime unreadable = mock(RunningRuntime.class);
    when(unreadable.latestCommitUserDataBestEffort())
        .thenThrow(new IllegalStateException("reader unavailable"));
    assertUnavailable(worker(base, active.activeGenerationPath(), unreadable));
  }

  @Test
  void generationMovementDuringCommitReadAbortsRatherThanMixingEvidence() throws Exception {
    Path base = tempDir.resolve("moving-generation");
    IndexGenerationManager generations = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout blue = generations.initializeOrLoad();
    generations.startMigration("moving-generation-test");
    LuceneRuntime runtime = mock(RunningRuntime.class);
    when(runtime.latestCommitUserDataBestEffort())
        .thenAnswer(
            ignored -> {
              generations.promoteBuildingGenerationToActive();
              return committed("{\"physical\":\"blue\"}");
            });
    WorkerIngestService service = worker(base, blue.activeGenerationPath(), runtime);

    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class,
        () -> service.captureAppliedGeneration(CallContext.none()));

    assertEquals(WorkerServiceException.Status.ABORTED, failure.status());
  }

  @Test
  void cancellationAndUnavailableGenerationStateRetainTypedFailures() throws Exception {
    WorkerIngestService unavailable =
        worker(tempDir.resolve("missing-state"), tempDir.resolve("missing-state/indices/g"),
            runtime(committed("{}")));
    WorkerServiceException missingState = assertThrows(
        WorkerServiceException.class,
        () -> unavailable.captureAppliedGeneration(CallContext.none()));
    assertEquals(WorkerServiceException.Status.UNAVAILABLE, missingState.status());

    WorkerServiceException cancelled = assertThrows(
        WorkerServiceException.class,
        () -> unavailable.captureAppliedGeneration(cancelledContext()));
    assertEquals(WorkerServiceException.Status.CANCELLED, cancelled.status());

    Path base = tempDir.resolve("cancel-during-read");
    IndexGenerationManager generations = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout active = generations.initializeOrLoad();
    AtomicInteger polls = new AtomicInteger();
    CallContext cancelledAfterCommitRead = context(() -> polls.incrementAndGet() > 1);
    WorkerIngestService available =
        worker(base, active.activeGenerationPath(), runtime(committed("{}")));
    WorkerServiceException cancelledDuringRead = assertThrows(
        WorkerServiceException.class,
        () -> available.captureAppliedGeneration(cancelledAfterCommitRead));
    assertEquals(WorkerServiceException.Status.CANCELLED, cancelledDuringRead.status());
    assertEquals(2, polls.get(), "capture polls before and after committed metadata read");
  }

  private static LuceneRuntime runtime(Map<String, String> metadata) {
    LuceneRuntime runtime = mock(RunningRuntime.class);
    when(runtime.latestCommitUserDataBestEffort()).thenReturn(metadata);
    return runtime;
  }

  private static Map<String, String> committed(String inputs) throws Exception {
    String fingerprint = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(inputs.getBytes(StandardCharsets.UTF_8)));
    return Map.of(
        IndexFingerprint.COMMIT_META_KEY, fingerprint,
        IndexFingerprint.COMMIT_META_INPUTS_KEY, inputs);
  }

  private static WorkerIngestService worker(
      Path base, Path servingPath, LuceneRuntime runtime) {
    return new WorkerIngestService(
        null,
        null,
        null,
        IndexingPacing.unthrottled(),
        base,
        servingPath,
        null,
        runtime,
        null,
        0L);
  }

  private static void assertUnavailable(WorkerIngestService service) {
    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class,
        () -> service.captureAppliedGeneration(CallContext.none()));
    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
  }

  private static CallContext cancelledContext() {
    return context(() -> true);
  }

  private static CallContext context(CallContext.CancelSignal cancellation) {
    CallContext base = CallContext.none();
    return new CallContext(
        null,
        null,
        cancellation,
        base.engineContext(),
        base.provenance(),
        base.childLifetime());
  }
}
