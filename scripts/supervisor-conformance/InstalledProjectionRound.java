/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.RecordedGapAcceptancePlan;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedBulkPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.app.services.registry.operations.handlers.AcceptGapsHandler;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.PipelineConfigs;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Run with the installed UI distribution jars against an isolated, retained standard-model A. */
public final class InstalledProjectionRound {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final long WAIT_MS = 180_000;
  private static final String GAP_CUT_FILE = "installed-projection-gap-cut.txt";

  private InstalledProjectionRound() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 5 && (args.length != 6
        || !("--gap".equals(args[5]) || "--gap-restart".equals(args[5])
            || "--gap-halt".equals(args[5]) || "--gap-resume".equals(args[5])
            || "--cancel".equals(args[5])))) {
      throw new IllegalArgumentException(
          "Expected data, index, models root, watched root, vector query file and optional --gap, --gap-restart, --gap-halt, --gap-resume or --cancel");
    }
    Path data = Path.of(args[0]).toAbsolutePath();
    Path index = Path.of(args[1]).toAbsolutePath();
    Path models = Path.of(args[2]).toAbsolutePath();
    Path watched = Path.of(args[3]).toAbsolutePath();
    String query = Files.readString(Path.of(args[4])).split("\\s+", 2)[0];
    if (args.length == 6 && "--cancel".equals(args[5])) {
      runCancel(data, index, models, watched, query);
      return;
    }
    if (args.length == 6 && "--gap-resume".equals(args[5])) {
      List<String> cut = Files.readAllLines(data.resolve(GAP_CUT_FILE));
      require(cut.size() == 4, "forced gap cut marker is incomplete");
      HeldSource recoveredSource = new HeldSource(List.of(
          projection("updated", 1, cut.get(3) + "old"),
          projection("deleted", 1, cut.get(3) + "deleted")));
      recoveredSource.failEnumerationAfterFirst();
      resumeGap(data, index, models, recoveredSource, cut.get(0), query,
          cut.get(2), cut.get(1), "GAP_RESUME");
      return;
    }
    String marker = "installedprojection" + System.nanoTime();
    AcceptedProjection oldUpdate = projection("updated", 1, marker + "old");
    AcceptedProjection newUpdate = projection("updated", 2, marker + "new");
    AcceptedProjection oldDelete = projection("deleted", 1, marker + "deleted");
    AcceptedProjection delete = new AcceptedProjection("installed-fixture", "deleted", 2,
        AcceptedProjection.Kind.DELETE, null);
    AcceptedProjection addition = projection("added", 1, marker + "added");
    HeldSource source = new HeldSource(List.of(oldUpdate, oldDelete));
    CountDownLatch restart = new CountDownLatch(1);
    String key;

    try (Epoch first = open(data, index, models, restart, source)) {
      require(await(() -> vectorReady(first.client, query), WAIT_MS),
          "installed standard-model A did not answer VECTOR search");
      System.out.println("INSTALLED_PROJECTION_A_VECTOR "
          + first.client.search(query, 10, PipelineConfigs.VECTOR, context()).getResultsCount());
      Operation operation = new CoreOperationCatalog()
          .findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
      HandlerRegistry handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.BULK_REINDEX,
          new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
              first.root.recordedIngestion(), ignored -> List.of(new RootBinding(watched, "documents")),
              first::client, List::of));
      OperationAuthority authority = first.root.authority();
      OperationExecutorImpl executor = new OperationExecutorImpl(first.root.operationAttempts(),
          first.root.admission(), handlers, null, Map.of(), CLOCK, authority.trust(),
          authority.sources(), null, authority.capsules());
      EngineContext origin = context();
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
          Instant.now(CLOCK), Optional.empty());
      String arguments = "{\"corpusIds\":[\"documents\"]}";
      key = OperationKeys.generate(CLOCK);
      var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments,
          provenance, origin, key, true);
      String capsule = authority.capsules().mintPrepared(operation.id().value(), arguments,
          SourceTier.valueOf(origin.sourceTier()), key, prepared.preparationNonce());
      require(executor.dispatch(operation, arguments, provenance, Optional.of(capsule),
          origin, key, prepared.preparationNonce()).success(), "bulk dispatch failed");
      require(restart.await(WAIT_MS, TimeUnit.MILLISECONDS), "bulk did not request restart");
      var row = first.operations.find(key).orElseThrow();
      var plan = new RecordedBulkPlanResolver().resolve(row,
          first.operations.acceptedPreparation(row.id()).orElseThrow());
      require(plan.projectionSourceIds().equals(List.of("installed-fixture")),
          "accepted source set was not frozen");
      first.handoff();
    }

    if (args.length == 6 && "--gap-restart".equals(args[5])) {
      runGapRestart(data, index, models, source, key, query);
      return;
    }
    if (args.length == 6 && "--gap-halt".equals(args[5])) {
      runGapHalt(data, index, models, source, key, query, marker);
      return;
    }
    if (args.length == 6) {
      runGap(data, index, models, source, key, query);
      return;
    }

    source.holdNext();
    try (Epoch second = open(data, index, models, new CountDownLatch(1), source)) {
      require(source.awaitHeld(WAIT_MS), "B did not enumerate registered source");
      source.setRows(List.of(newUpdate, delete, addition));
      second.client.indexAndReturn(newUpdate, ProjectionDurability.NRT, context());
      second.client.deleteAndAcknowledge(delete, ProjectionDurability.NRT, context());
      second.client.indexAndReturn(addition, ProjectionDurability.NRT, context());
      require(searchable(second.client, marker + "new"), "A missed accepted update");
      require(searchable(second.client, marker + "added"), "A missed accepted addition");
      source.release();
      require(await(() -> second.operations.find(key)
          .map(row -> row.state() == OperationState.COMPLETE).orElse(false), WAIT_MS),
          "B did not promote after exact replay");
      require(("g-" + key).equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "wrong candidate promoted");
      require(searchable(second.client, marker + "new"), "B missed accepted update");
      require(searchable(second.client, marker + "added"), "B missed accepted addition");
      require(!searchable(second.client, marker + "old"), "B retained old revision");
      require(!searchable(second.client, marker + "deleted"), "B retained deleted projection");
    } finally {
      source.release();
    }
    try (Epoch third = open(data, index, models, new CountDownLatch(1), source)) {
      require(third.operations.find(key).orElseThrow().state() == OperationState.COMPLETE,
          "third boot lost operation terminal state");
      require(searchable(third.client, marker + "new"), "third boot lost update");
      require(searchable(third.client, marker + "added"), "third boot lost addition");
      require(!searchable(third.client, marker + "deleted"), "third boot resurrected deletion");
      require(await(() -> vectorReady(third.client, query), WAIT_MS),
          "promoted B did not answer VECTOR search");
      System.out.println("INSTALLED_PROJECTION_B_VECTOR "
          + third.client.search(query, 10, PipelineConfigs.VECTOR, context()).getResultsCount());
    }
    System.out.println("INSTALLED_PROJECTION_PASS " + key);
  }

  private static void runGap(Path data, Path index, Path models, HeldSource source,
      String key, String query) throws Exception {
    String original = new IndexGenerationManager(index)
        .readStateBestEffort().active_generation();
    source.failEnumerationAfterFirst();
    String decisionKey;
    try (Epoch second = open(data, index, models, new CountDownLatch(1), source)) {
      require(await(() -> "awaiting_acceptance".equals(second.operations.outcome(key).phase()),
          WAIT_MS), "incomplete registered source did not await candidate-bound approval");
      var outcome = second.operations.outcome(key);
      require(second.operations.find(key).orElseThrow().state()
          == OperationState.COMPLETE_WITH_GAPS, "gap wait lost recoverable operation state");
      require(outcome.result().gaps().stream()
          .anyMatch(gap -> "PROJECTION_SOURCE_INCOMPLETE".equals(gap.reason())),
          "incomplete source gap was not reported");
      String hash = outcome.result().gapListHash();
      require(hash != null, "gap list hash was not recorded");
      require(original.equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "B promoted without approval");
      require(vectorReady(second.client, query), "A stopped answering VECTOR while B waited");
      System.out.println("INSTALLED_PROJECTION_GAP_A_VECTOR "
          + second.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());

      GapDecision approved = decideGap(second, key, hash);
      require(approved.result.success(),
          "distinct recorded gap approval dispatch failed: " + approved.result);
      decisionKey = approved.key;
      require(await(() -> second.operations.find(key)
          .map(row -> row.state() == OperationState.FAILED && row.receipt() != null
              && "PROMOTED_WITH_GAPS".equals(row.receipt().code())).orElse(false), WAIT_MS),
          "approved gap did not promote exact B");
      require(second.operations.find(decisionKey).orElseThrow().state() == OperationState.COMPLETE,
          "recorded gap decision did not complete");
      require(("g-" + key).equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "wrong gap candidate promoted");
    }
    try (Epoch third = open(data, index, models, new CountDownLatch(1), source)) {
      require(third.operations.find(key).orElseThrow().state() == OperationState.FAILED,
          "third boot lost promoted-with-gaps diagnostic");
      require(third.operations.find(decisionKey).orElseThrow().state() == OperationState.COMPLETE,
          "third boot lost recorded decision");
      require(await(() -> vectorReady(third.client, query), WAIT_MS),
          "promoted gap B did not answer VECTOR search");
      System.out.println("INSTALLED_PROJECTION_GAP_B_VECTOR "
          + third.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());
    }
    System.out.println("INSTALLED_PROJECTION_GAP_PASS " + key);
  }

  private static void runCancel(Path data, Path index, Path models, Path watched,
      String query) throws Exception {
    String original = new IndexGenerationManager(index)
        .readStateBestEffort().active_generation();
    HeldSource source = new HeldSource(List.of(projection("retained", 1, "cancel-fixture")));
    CountDownLatch restart = new CountDownLatch(1);
    String key;
    try (Epoch first = open(data, index, models, restart, source)) {
      require(await(() -> vectorReady(first.client, query), WAIT_MS),
          "A did not answer VECTOR before candidate cancellation");
      System.out.println("INSTALLED_PROJECTION_CANCEL_A_BEFORE_VECTOR "
          + first.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());
      Operation operation = new CoreOperationCatalog()
          .findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
      HandlerRegistry handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.BULK_REINDEX,
          new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK,
              first.root.recordedIngestion(),
              ignored -> List.of(new RootBinding(watched, "documents")),
              first::client, List::of));
      OperationAuthority authority = first.root.authority();
      OperationExecutorImpl executor = new OperationExecutorImpl(first.root.operationAttempts(),
          first.root.admission(), handlers, null, Map.of(), CLOCK, authority.trust(),
          authority.sources(), null, authority.capsules());
      EngineContext origin = context();
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
          Instant.now(CLOCK), Optional.empty());
      String arguments = "{\"corpusIds\":[\"documents\"]}";
      key = OperationKeys.generate(CLOCK);
      var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments,
          provenance, origin, key, true);
      String capsule = authority.capsules().mintPrepared(operation.id().value(), arguments,
          SourceTier.valueOf(origin.sourceTier()), key, prepared.preparationNonce());
      try (var owner = first.root.admission().admit(origin, false)) {
        require(executor.dispatch(operation, arguments, provenance, Optional.of(capsule),
            owner.context(), key, prepared.preparationNonce()).success(),
            "installed cancel bulk dispatch failed");
        require(restart.await(WAIT_MS, TimeUnit.MILLISECONDS),
            "installed cancel bulk did not create B before restart");
        var beforeCancel = new IndexGenerationManager(index).readStateBestEffort();
        require(("g-" + key).equals(beforeCancel.building_generation())
            && original.equals(beforeCancel.active_generation()),
            "installed cancel must retain exact A and an uncommitted accepted B");
        owner.cancel("cancel accepted bulk before pointer commitment");
        var row = first.operations.find(key).orElseThrow();
        require(row.state() == OperationState.RUNNING
            && "cancelled".equals(first.operations.bulkReindexProgress(row.id())
                .orElseThrow().refusalCode()),
            "installed cancel did not durably checkpoint precommit refusal");
      }
      first.handoff();
    }

    try (Epoch recovered = open(data, index, models, new CountDownLatch(1), source)) {
      require(await(() -> recovered.operations.find(key)
          .map(row -> row.state() == OperationState.CANCELLED).orElse(false), WAIT_MS),
          "recovered installed cancellation did not terminalize");
      recovered.handoff();
    }
    var state = new IndexGenerationManager(index).readStateBestEffort();
    require(original.equals(state.active_generation()) && state.previous_generation() == null,
        "cancelled installed candidate did not leave exact A alone");
    require(!Files.exists(index.resolve("indices/g-" + key)),
        "cancelled installed candidate still owns B");
    try (Epoch reopened = open(data, index, models, new CountDownLatch(1), source)) {
      require(reopened.operations.find(key).orElseThrow().state() == OperationState.CANCELLED,
          "reopened A lost terminal cancellation");
      require(await(() -> vectorReady(reopened.client, query), WAIT_MS),
          "reopened A did not answer VECTOR after B retirement");
      System.out.println("INSTALLED_PROJECTION_CANCEL_A_AFTER_VECTOR "
          + reopened.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());
    }
    System.out.println("INSTALLED_PROJECTION_CANCEL_PASS " + key);
  }

  private static void runGapRestart(Path data, Path index, Path models, HeldSource source,
      String key, String query) throws Exception {
    String original = new IndexGenerationManager(index)
        .readStateBestEffort().active_generation();
    source.failEnumerationAfterFirst();
    String firstHash;
    try (Epoch second = open(data, index, models, new CountDownLatch(1), source)) {
      require(await(() -> "awaiting_acceptance".equals(second.operations.outcome(key).phase()),
          WAIT_MS), "first incomplete source did not enter the durable gap wait");
      firstHash = second.operations.outcome(key).result().gapListHash();
      require(firstHash != null, "first gap witness has no hash");
      require(original.equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "first gap wait promoted B");
      require(vectorReady(second.client, query), "A did not answer VECTOR at first gap wait");
      second.handoff();
    }
    resumeGap(data, index, models, source, key, query, original, firstHash, "GAP_RESTART");
  }

  private static void runGapHalt(Path data, Path index, Path models, HeldSource source,
      String key, String query, String marker) throws Exception {
    String original = new IndexGenerationManager(index)
        .readStateBestEffort().active_generation();
    source.failEnumerationAfterFirst();
    try (Epoch second = open(data, index, models, new CountDownLatch(1), source)) {
      require(await(() -> "awaiting_acceptance".equals(second.operations.outcome(key).phase()),
          WAIT_MS), "incomplete source did not enter the durable gap wait before process halt");
      String firstHash = second.operations.outcome(key).result().gapListHash();
      require(firstHash != null, "forced gap cut has no hash");
      require(original.equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "forced gap cut promoted B before approval");
      require(vectorReady(second.client, query), "A did not answer VECTOR before process halt");
      Path cut = data.resolve(GAP_CUT_FILE);
      Files.writeString(cut, key + "\n" + firstHash + "\n" + original + "\n" + marker + "\n",
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try (FileChannel channel = FileChannel.open(cut, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      System.out.println("INSTALLED_PROJECTION_GAP_HALT_READY " + key);
      System.out.flush();
      Runtime.getRuntime().halt(73);
    }
    throw new AssertionError("forced gap cut returned after halt");
  }

  private static void resumeGap(Path data, Path index, Path models, HeldSource source,
      String key, String query, String original, String firstHash, String label) throws Exception {
    String approvedKey;
    try (Epoch third = open(data, index, models, new CountDownLatch(1), source)) {
      require(await(() -> "awaiting_acceptance".equals(third.operations.outcome(key).phase()),
          WAIT_MS), "Engine restart lost the durable gap wait");
      require(await(() -> {
        var enumerator = third.client.getDebugWorkerState(context()).migrationEnumerator();
        return enumerator.done() && !enumerator.running();
      }, WAIT_MS), "restarted source enumeration did not settle");
      require(original.equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "restart promoted B before approval");
      require(vectorReady(third.client, query), "A did not answer VECTOR after gap-wait restart");
      System.out.println("INSTALLED_PROJECTION_" + label + "_A_VECTOR "
          + third.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());
      GapDecision stale = decideGap(third, key, firstHash);
      require(!stale.result.success()
          && "GAP_LIST_STALE".equals(stale.result.errorCode().orElse(null)),
          "pre-restart hash authorized changed physical evidence: " + stale.result);
      require(third.operations.find(stale.key).orElseThrow().state() == OperationState.FAILED,
          "stale decision did not retain its own failed operation row");
      String refreshedHash = third.operations.outcome(key).result().gapListHash();
      require(refreshedHash != null && !refreshedHash.equals(firstHash),
          "physical witness refresh did not change the exact hash");
      require(original.equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "stale approval promoted B");
      GapDecision approved = decideGap(third, key, refreshedHash);
      require(approved.result.success(), "refreshed recorded approval failed: " + approved.result);
      approvedKey = approved.key;
      require(await(() -> third.operations.find(key)
          .map(row -> row.state() == OperationState.FAILED && row.receipt() != null
              && "PROMOTED_WITH_GAPS".equals(row.receipt().code())).orElse(false), WAIT_MS),
          "refreshed approval did not promote B");
      require(third.operations.find(approvedKey).orElseThrow().state() == OperationState.COMPLETE,
          "refreshed decision did not complete");
      require(("g-" + key).equals(new IndexGenerationManager(index)
          .readStateBestEffort().active_generation()), "wrong B promoted after restart");
      third.handoff();
    }

    try (Epoch fourth = open(data, index, models, new CountDownLatch(1), source)) {
      require(fourth.operations.find(key).orElseThrow().state() == OperationState.FAILED,
          "fourth boot lost promoted-with-gaps diagnostic");
      require(fourth.operations.find(approvedKey).orElseThrow().state() == OperationState.COMPLETE,
          "fourth boot lost recorded approval");
      require(await(() -> vectorReady(fourth.client, query), WAIT_MS),
          "reopened B did not answer VECTOR after gap-wait restart");
      System.out.println("INSTALLED_PROJECTION_" + label + "_B_VECTOR "
          + fourth.client.search(query, 10, PipelineConfigs.VECTOR, context())
              .getResultsCount());
    }
    System.out.println("INSTALLED_PROJECTION_" + label + "_PASS " + key);
  }

  private static GapDecision decideGap(Epoch epoch, String key, String hash) {
    Operation decision = new CoreOperationCatalog()
        .findByIdValue(CoreOperationCatalog.ACCEPT_GAPS.value()).orElseThrow();
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.ACCEPT_GAPS,
        new AcceptGapsHandler(epoch.root.recordedIngestion()));
    OperationAuthority authority = epoch.root.authority();
    OperationExecutorImpl executor = new OperationExecutorImpl(epoch.root.operationAttempts(),
        epoch.root.admission(), handlers, null, Map.of(), CLOCK, authority.trust(),
        authority.sources(), null, authority.capsules());
    EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
        "installed-projection-gap-round", Optional.of("fixture-session"), Optional.empty(),
        TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.FOREGROUND);
    var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI,
        Instant.now(CLOCK), Optional.empty());
    String arguments = new RecordedGapAcceptancePlan(key, hash).toReplayPayload();
    String decisionKey = OperationKeys.generate(CLOCK);
    var prepared = (OperationDispatchPlan.Ready) executor.prepare(decision, arguments,
        provenance, origin, decisionKey, true);
    String capsule = authority.capsules().mintPrepared(decision.id().value(), arguments,
        SourceTier.valueOf(origin.sourceTier()), decisionKey, prepared.preparationNonce());
    return new GapDecision(decisionKey, executor.dispatch(decision, arguments, provenance,
        Optional.of(capsule), origin, decisionKey, prepared.preparationNonce()));
  }

  private record GapDecision(String key, OperationResult result) {}

  private static AcceptedProjection projection(String id, long revision, String content) {
    return new AcceptedProjection("installed-fixture", id, revision,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"" + content + "\"}");
  }

  private static EngineContext context() {
    return EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
        "installed-projection-round", Optional.of("fixture-session"), Optional.empty(),
        TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
  }

  private static boolean searchable(KnowledgeClient client, String marker) {
    return client.search(marker, 10, context()).getResultsCount() > 0;
  }

  private static boolean vectorReady(KnowledgeClient client, String query) {
    var response = client.search(query, 10, PipelineConfigs.VECTOR, context());
    return response.getResultsCount() > 0
        && "VECTOR".equals(response.getSearchTrace().getEffectiveMode());
  }

  private static boolean await(BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    do {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    return condition.getAsBoolean();
  }

  private static void require(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }

  private static Epoch open(Path data, Path index, Path models, CountDownLatch restart,
      HeldSource source)
      throws Exception {
    ConfigStore.setGlobal(new ConfigStore(new ResolvedConfigBuilder().contributeBaseSources()
        .putDefault("justsearch.data.dir", data.toString())
        .putDefault("justsearch.index.base_path", index.toString())
        .putDefault("justsearch.models.dir", models.toString()).build()));
    var operations = new SqliteOperationStore(data.resolve("operations.db"));
    var attempts = new OperationAttemptRunnerImpl(operations, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
        new RecordedIngestPlanResolver());
    var authority = OperationAuthority.load(data);
    var root = new EngineRoot(operations, attempts,
        (gauge, executors, ingestion, indexComponent, encoderComponent) ->
            new KnowledgeServer(executors, WorkerConfig.load(),
                new InProcessWorkerSignalBus(gauge),
                io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion,
                indexComponent, encoderComponent), 30_000L, 5_000,
        code -> { throw new AssertionError("terminal writer exited " + code); },
        restart::countDown, authority);
    try {
      root.registerProjectionSeedSource(source);
      KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      return new Epoch(root, operations, client);
    } catch (Throwable failure) {
      root.close();
      root.executors().close();
      operations.close();
      throw failure;
    }
  }

  private record Epoch(EngineRoot root, SqliteOperationStore operations,
      KnowledgeClient client) implements AutoCloseable {
    void handoff() {
      root.admission().beginClosing();
      root.operationAttempts().beginClosing();
      root.admission().cancelInteractive("requested restart");
      root.quiesceProducers();
      require(root.admission().activeWorkCount() == 0, "first epoch kept admitted work");
      require(root.operationAttempts().awaitDrained(java.time.Duration.ZERO),
          "first epoch kept a live runner");
    }

    @Override public void close() throws java.io.IOException {
      try {
        root.close();
      } finally {
        try {
          root.executors().close();
        } finally {
          operations.close();
        }
      }
    }
  }

  private static final class HeldSource implements ProjectionSeedSource {
    private final AtomicReference<List<AcceptedProjection>> rows;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;
    private volatile boolean failEnumerationAfterFirst;

    HeldSource(List<AcceptedProjection> initial) {
      rows = new AtomicReference<>(List.copyOf(initial));
    }

    @Override public String sourceId() { return "installed-fixture"; }

    void setRows(List<AcceptedProjection> next) { rows.set(List.copyOf(next)); }

    void failEnumerationAfterFirst() { failEnumerationAfterFirst = true; }

    void holdNext() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    boolean awaitHeld(long timeoutMs) throws InterruptedException {
      return entered.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void release() {
      CountDownLatch pending = release;
      if (pending != null) pending.countDown();
    }

    @Override public void enumerate(Consumer<AcceptedProjection> sink) throws java.io.IOException {
      List<AcceptedProjection> snapshot = rows.get();
      CountDownLatch pending = release;
      if (pending != null) {
        entered.countDown();
        try {
          if (!pending.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw new java.io.IOException("source hold timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException("source hold interrupted", interrupted);
        }
      }
      if (failEnumerationAfterFirst) {
        sink.accept(snapshot.get(0));
        throw new java.io.IOException("registered source failed after first projection");
      }
      snapshot.forEach(sink);
    }
  }
}
