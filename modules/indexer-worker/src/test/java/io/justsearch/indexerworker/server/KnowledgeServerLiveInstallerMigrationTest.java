/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.CandidateIndexTargetCapture;
import io.justsearch.ipc.MigrationStartRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real recorded installer candidate, producer transfer, pointer, replay and retirement paths. */
@Timeout(180)
final class KnowledgeServerLiveInstallerMigrationTest {
  @Test
  void installerBudgetChangeGovernsTheNextFailedNativeMigration(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      assertEquals(-1, field(fixture.server, "migrationCutoverMaxFailedJobs"));
      fixture.promoteInstaller(true);
      // Exercise behavior as well as the applied cache; no server or Engine restart intervenes.
      var producer = (DefaultWorkerAppServices) fixture.server.appServices();
      assertTrue(producer.pauseProducerForCutover(10_000));
      try {
        Path failed = Files.writeString(tempDir.resolve("failed.txt"), "unreadable unit");
        JobQueue queue = fixture.server.jobQueueForTests();
        assertEquals(1, queue.enqueue(List.of(failed)));
        assertEquals(1, queue.pollPending(1).size());
        queue.markFailed(failed, IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED,
            "fixture.failed", IngestionRetryPolicy.NONE));
        assertEquals(1, queue.failureSummary().failedCount());
      } finally {
        producer.resumeProducerAfterCutover();
      }
      String serving = fixture.manager.readStateBestEffort().active_generation();
      var started = fixture.server.appServices().ingestService().startMigration(
          MigrationStartRequest.newBuilder().setReason("manual").build(), CallContext.none());
      assertTrue(started.getAccepted(), started::getError);
      assertTrue(fixture.server.beginUnrecordedBuildingLiveAsync(started.getBuildingGenerationId(),
          fixture.restarts::incrementAndGet).get(30, TimeUnit.SECONDS));
      fixture.awaitMonitor();
      assertEquals(serving, fixture.manager.readStateBestEffort().active_generation());
      assertEquals(IndexGenerationManager.MigrationState.FAILED.name(),
          fixture.manager.readStateBestEffort().migration_state());
      assertEquals(0, field(fixture.server, "migrationCutoverMaxFailedJobs"));
      assertEquals(0, fixture.restarts.get());
    }
  }

  @Test
  void settledInstallerCanRecoverItsPromotedEncodersWithoutAnotherMigration(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      String lastApplied = fixture.encoder.snapshot().appliedVersion();
      assertNotNull(lastApplied);
      fixture.promoteInstaller(false);
      assertEquals(lastApplied, fixture.encoder.snapshot().appliedVersion(),
          "known-missing B retains the last successful applied version");
      assertNotEquals(lastApplied, fixture.encoder.snapshot().desiredVersion(),
          "B's desired projection differs from the historical applied projection");
      assertNotNull(field(fixture.server, "recordedCandidate"), "the operation still owns settlement");
      assertTrue(Files.exists(fixture.sourcePath), "a nonterminal receipt retains the predecessor");
      fixture.encoder.transition(ComponentState.FAILED, "encoders.failed", "native failure");
      assertEquals(ComponentRecoveryAction.Outcome.REFUSED,
          fixture.server.recoverEncoders(new RecoveryRequest(fixture.encoder)).outcome());

      fixture.lifecycle.terminalAllowed = true;
      fixture.server.notifyRecordedServicesPublished();
      assertFalse(Files.exists(fixture.sourcePath));
      assertNull(fixture.manager.readStateBestEffort().building_generation());
      assertNull(field(fixture.server, "recordedCandidate"));
      assertEquals(IndexGenerationManager.BootDisposition.NATIVE,
          field(fixture.server, "generationBootDisposition"));
      IndexCompositionPlan plan = (IndexCompositionPlan) field(fixture.server, "initialIndexCompositionPlan");
      assertNotNull(plan);
      assertTrue(plan.projection().reranker().enabled(),
          "recovery must retain B's selected projection, rather than A's disabled boot plan");
      EncoderSet promoted;
      try (var serving = fixture.server.captureServingView()) {
        promoted = serving.encoderSet();
        assertNotNull(promoted);
      }
      var identity = promoted.modelIdentity();
      var result = fixture.server.recoverEncoders(new RecoveryRequest(fixture.encoder));
      // The selected reranker has no tokenizer. Its exact known-missing plan is recoverable
      // to DEGRADED without loading models or opening an external process.
      assertEquals(ComponentRecoveryAction.Outcome.DEGRADED, result.outcome(), result::toString);
      assertEquals(lastApplied, fixture.encoder.snapshot().appliedVersion());
      assertTrue(promoted.isClosed());
      try (var serving = fixture.server.captureServingView()) {
        assertTrue(serving.encoderSet() != promoted);
        assertEquals(identity, serving.encoderSet().modelIdentity());
        assertEquals(fixture.building, serving.activeGenerationPath().getFileName().toString());
      }
      assertSame(plan, field(fixture.server, "initialIndexCompositionPlan"));
      assertSame(fixture.candidate, field(fixture.server, "startupConfiguration"));
      assertEquals(fixture.lifecycle.accepted.models(),
          fixture.manager.manifestForOwnedPath(fixture.manager.resolveGenerationPathStrict(fixture.building)).models());
      assertEquals(0, fixture.restarts.get());
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final TestEngineComponents components = TestEngineComponents.fourComponents();
    private final ComponentHandle encoder = components.handle("encoders");
    private final InstallerLifecycle lifecycle = new InstallerLifecycle(encoder);
    private final AtomicInteger restarts = new AtomicInteger();
    private final ConfigStore previousConfiguration = ConfigStore.globalOrNull();
    private final KnowledgeServer server;
    private final ResolvedConfig candidate;
    private final IndexGenerationManager manager;
    private final Path sourcePath;
    private String building;

    private Fixture(Path tempDir) throws Exception {
      var values = new HashMap<String, String>();
      values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
      values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
      values.put("justsearch.ssot.path", tempDir.resolve("data/SSOT").toString());
      values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
      values.put("index.schema_mismatch.policy", "BLUE_GREEN_MIGRATE");
      values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
      for (var role : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED}) values.put(role.configKey(), "false");
      values.put("index.migration.cutover.max_failed_jobs", "-1");
      var initial = TestResolvedConfigHelper.fromEntries(values);
      values.remove("index.migration.cutover.max_failed_jobs");
      values.put(EnvRegistry.RERANK_ENABLED.configKey(), "true");
      candidate = TestResolvedConfigHelper.fromEntries(values);
      ConfigStore.setGlobal(new ConfigStore(initial));
      server = new KnowledgeServer(executors, WorkerConfig.load(initial), null,
          ManagedChildRegistry.noop(), lifecycle, components.handle("index"), encoder, initial,
          () -> ConfigStore.global().get(), new ReentrantReadWriteLock(true));
      server.start();
      if (server.deferredModelInit != null) server.deferredModelInit.get(30, TimeUnit.SECONDS);
      assertTrue(server.awaitIndexRecoveryModels(30_000));
      manager = (IndexGenerationManager) field(server, "indexGenerationManager");
      sourcePath = manager.resolveGenerationPathStrict(manager.readStateBestEffort().active_generation());
      Path downloaded = Files.writeString(tempDir.resolve("downloaded.onnx"), "downloaded model without tokenizer");
      var models = Map.of("reranker", new IndexGenerationManager.ModelArtifact(downloaded.toString(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(downloaded)))));
      lifecycle.accepted = new RecordedIngestionLifecycle.RecordedCandidate(candidate,
          CandidateIndexTargetCapture.capture(candidate), models);
    }

    private void promoteInstaller(boolean terminalAllowed) throws Exception {
      lifecycle.terminalAllowed = terminalAllowed;
      lifecycle.source = manager.readStateBestEffort().active_generation();
      building = manager.startRecordedMigration(InstallerLifecycle.OPERATION, "installer_model_activation",
          lifecycle.accepted.target().fingerprint(), lifecycle.source, List.of()).building_generation();
      lifecycle.authorized = true;
      assertTrue(server.beginRecordedBuildingLiveAsync(InstallerLifecycle.OPERATION,
          restarts::incrementAndGet).get(30, TimeUnit.SECONDS));
      awaitMonitor();
      assertTrue(lifecycle.published);
      assertEquals(building, manager.readStateBestEffort().active_generation());
      server.notifyRecordedServicesPublished();
      if (terminalAllowed) {
        // Run the existing maintenance path explicitly so the policy regression isolates the
        // next migration's budget, rather than depending on the maintenance timer.
        var retirement = KnowledgeServer.class.getDeclaredMethod("retryCommittedGenerationRetirement");
        retirement.setAccessible(true);
        retirement.invoke(server);
        assertFalse(Files.exists(sourcePath));
      }
    }

    private void awaitMonitor() throws Exception {
      Thread monitor = (Thread) field(server, "migrationCutoverThread");
      assertNotNull(monitor);
      monitor.join(30_000);
      assertFalse(monitor.isAlive(), "the physical cutover monitor must finish");
    }

    @Override public void close() throws Exception {
      try { server.close(); }
      finally {
        try { components.close(); executors.close(); }
        finally { TestResolvedConfigHelper.restoreGlobal(previousConfiguration); }
      }
    }
  }

  private static final class InstallerLifecycle implements RecordedIngestionLifecycle {
    private static final String OPERATION = "01994180-0000-7000-8000-000000000206";
    private final ComponentHandle encoder;
    private RecordedCandidate accepted;
    private String source;
    private boolean authorized;
    private volatile boolean published;
    private volatile boolean terminalAllowed;

    private InstallerLifecycle(ComponentHandle encoder) { this.encoder = encoder; }
    @Override public IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) {
      return !authorized || published ? new IndexGenerationManager.BootOwnership.Native()
          : new IndexGenerationManager.BootOwnership.Recorded(OPERATION, source,
              "installer_model_activation", accepted.target().fingerprint(), true, true, List.of());
    }
    @Override public Optional<RecordedCandidate> recordedCandidate(String key) { return Optional.of(accepted); }
    @Override public boolean recordedCutoverReady(String key) { return authorized; }
    @Override public boolean committedBulkTerminal(String key) { return published && terminalAllowed; }
    @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
      return JobQueue.RecordedClaimDecision.ALLOW;
    }
    @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier online) { return () -> {}; }
    @Override public IndexGenerationManager.State promoteRecordedGeneration(
        String key, JobQueue queue, CheckedPromotion promotion) throws IOException { return promotion.promote(); }
    @Override public PreparedCompositeProjection prepareRecordedGenerationProjection(String key, JobQueue queue) {
      return new PreparedCompositeProjection() {
        private EngineComponentRegistry.PreparedBatch observation;
        private final CommittedProjection callbacks = new CommittedProjection() {
          @Override public void includeComponentObservation(EngineComponentSnapshot.Component row) {
            observation = encoder.prepareReplacement(row);
          }
          @Override public void admitBeforePointer() { observation.validate(); }
          @Override public void afterPointerCommitted() {
            observation.install();
            ConfigStore.setGlobal(new ConfigStore(accepted.configuration()));
          }
          @Override public void afterRuntimePublished() { observation.notifyObservers(); published = true; }
          @Override public void abortBeforePointer() {}
        };
        @Override public IndexGenerationManager.State withOwnerLocks(CheckedPromotion promotion)
            throws IOException { return promotion.promote(); }
        @Override public CommittedProjection callbacks() { return callbacks; }
        @Override public void abortBeforePointer() {}
      };
    }
  }

  private static final class RecoveryRequest implements ComponentRecoveryAction.Request {
    private final ComponentHandle handle;
    private final EngineComponentSnapshot.Component expected;
    private final AtomicReference<EngineComponentSnapshot.Component> admitted = new AtomicReference<>();
    private RecoveryRequest(ComponentHandle handle) { this.handle = handle; expected = handle.snapshot(); }
    @Override public EngineComponentSnapshot.Component expected() { return expected; }
    @Override public EngineComponentSnapshot.Component current() { return handle.snapshot(); }
    @Override public Optional<EngineComponentSnapshot.Component> admitted() { return Optional.ofNullable(admitted.get()); }
    @Override public boolean begin() {
      var started = handle.tryBeginRecovery(expected, "component.recovering", "test");
      started.ifPresent(admitted::set);
      return started.isPresent();
    }
    @Override public Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component current, ComponentState state, String reasonCode, String evidence) {
      return handle.tryTransitionIfUnchanged(current, state, reasonCode, evidence);
    }
    @Override public boolean cancelled() { return false; }
  }

  private static Object field(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }
}
