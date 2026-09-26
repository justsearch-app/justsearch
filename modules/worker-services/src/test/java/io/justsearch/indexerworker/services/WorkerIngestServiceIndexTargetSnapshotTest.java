/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerIngestServiceIndexTargetSnapshotTest {
  @TempDir Path tempDir;

  @BeforeEach
  void resetFingerprintProvidersBeforeTest() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  @AfterEach
  void resetFingerprintProvidersAfterTest() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  @Test
  void projectsThePhysicalTargetDuringBlueGreenFromOneMetadataAuthority() throws Exception {
    Path base = tempDir.resolve("green-index");
    var generations = new IndexGenerationManager(base);
    var initial = generations.initializeOrLoad();
    var migrating = generations.startMigration("target-snapshot-test");
    var ingest = mock(RunningRuntime.class);
    var serving = mock(RunningRuntime.class);
    WorkerIngestService service = worker(
        base, generations.resolveGenerationPathStrict(initial.activeGenerationId()), ingest, serving);
    Map<String, Object> metadata = new SsotCommitMetadataSource().build();
    var expected = new IndexTargetSnapshot(
        (String) metadata.get(IndexFingerprint.COMMIT_META_KEY),
        (String) metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));

    assertEquals(expected, service.captureIndexTarget(CallContext.none()));
    assertEquals("MIGRATING", migrating.migration_state());
  }

  @Test
  void physicalTargetIsAvailableFromReadOnlyServingRuntime() {
    WorkerIngestService service = worker("read-only-target", null, mock(RunningRuntime.class));
    Map<String, Object> metadata = new SsotCommitMetadataSource().build();
    assertEquals(new IndexTargetSnapshot((String) metadata.get(IndexFingerprint.COMMIT_META_KEY),
        (String) metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY)),
        service.captureIndexTarget(CallContext.none()));
  }

  @Test
  void missingServingRuntimeRefusesTheTarget() {
    WorkerIngestService service = worker("missing-runtime-index", null, null);

    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class, () -> service.captureIndexTarget(CallContext.none()));

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
  }

  @Test
  void indeterminateFingerprintRefusesEvenWhenCanonicalInputsExist() {
    IndexFingerprint.installModelFingerprintProviders(
        IndexFingerprint.ModelFingerprint::indeterminate,
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured);
    WorkerIngestService service = worker(
        "indeterminate-index", mock(RunningRuntime.class), mock(RunningRuntime.class));

    WorkerServiceException failure = assertThrows(
        WorkerServiceException.class, () -> service.captureIndexTarget(CallContext.none()));

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
  }

  @Test
  void cancellationBeforeOrDuringCaptureRefusesTheTarget() {
    var alreadyCancelled = context(() -> true);
    WorkerIngestService unavailable = worker("cancelled-index", null, null);
    WorkerServiceException entryFailure = assertThrows(
        WorkerServiceException.class, () -> unavailable.captureIndexTarget(alreadyCancelled));
    assertEquals(WorkerServiceException.Status.CANCELLED, entryFailure.status());

    AtomicInteger polls = new AtomicInteger();
    var cancelledDuringRead = context(() -> polls.incrementAndGet() > 1);
    WorkerIngestService available = worker(
        "cancel-during-read-index", mock(RunningRuntime.class), mock(RunningRuntime.class));
    WorkerServiceException readFailure = assertThrows(
        WorkerServiceException.class, () -> available.captureIndexTarget(cancelledDuringRead));
    assertEquals(WorkerServiceException.Status.CANCELLED, readFailure.status());
    assertEquals(2, polls.get(), "capture polls once before and once after the metadata read");
  }

  private WorkerIngestService worker(String name, RunningRuntime ingest, LuceneRuntime serving) {
    Path base = tempDir.resolve(name);
    return worker(base, base.resolve("indices/g-active"), ingest, serving);
  }

  private WorkerIngestService worker(
      Path base, Path servingPath, RunningRuntime ingest, LuceneRuntime serving) {
    return new WorkerIngestService(
        null,
        null,
        null,
        IndexingPacing.unthrottled(),
        base,
        servingPath,
        ingest,
        serving,
        null,
        0L);
  }

  private static CallContext context(CallContext.CancelSignal cancel) {
    CallContext base = CallContext.none();
    return new CallContext(
        null,
        null,
        cancel,
        base.engineContext(),
        base.provenance(),
        base.childLifetime());
  }
}
