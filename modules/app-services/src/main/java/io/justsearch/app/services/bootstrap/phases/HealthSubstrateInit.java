/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.HealthRecoveryProjector;
import io.justsearch.app.observability.health.ConditionRecoveryIndex;
import io.justsearch.app.observability.health.ConditionRecoveryIndexBuilder;
import io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.observability.health.HeadHealthEventsEmitter;
import io.justsearch.app.services.observability.health.LifecycleSnapshotTap;
import io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger;
import io.justsearch.app.services.observability.health.WorkerSnapshotTap;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Tempdoc 519 §7 / Step 7: static phase function that initializes the health substrate —
 * condition store, occurrence log, change registry (with recovery-index broadcast + advisory
 * projection subscriptions), head source, lifecycle/worker snapshot taps, and the head health
 * events emitter. Replaces {@code HeadAssembly#initHealthSubstrate}.
 */
public final class HealthSubstrateInit {

  private HealthSubstrateInit() {}

  /** Bundled output from the health substrate phase. RuleRunner is built separately. */
  public record Output(
      ConditionStore conditionStore,
      OccurrenceLog occurrenceLog,
      HealthEventChangeRegistry healthEventChangeRegistry,
      Source headSource,
      LifecycleSnapshotTap lifecycleSnapshotTap,
      WorkerSnapshotTap workerSnapshotTap,
      HeadHealthEventsEmitter headHealthEventsEmitter,
      // Tempdoc 876 §B.2a: the non-request trigger for the taps above. Constructed here (they are
      // all substrate-owned), wired to capability transitions in OrchestrationPhase, given its
      // thunk by CoreApiAssembly, closed with the substrate in HeadAssembly.close().
      ReadinessReconciliationTrigger readinessReconciliationTrigger) {}

  /**
   * Initializes the health substrate. Subscribes:
   *
   * <ul>
   *   <li>An untyped listener that rebuilds the {@link ConditionRecoveryIndex} on each
   *       health-event envelope and broadcasts it via the recovery-index registry.
   *   <li>A typed listener that projects health-event changes through
   *       {@link HealthRecoveryProjector} and appends the resulting advisory record into the
   *       projector's advisory log.
   * </ul>
   *
   * @param occurrenceBufferSize size of the per-process occurrence log buffer.
   * @param healthRecoveryProjector advisory projector built in operation-substrate init.
   * @param advisoryChangeRegistry advisory registry built in operation-substrate init.
   * @param advisoryLogs advisory logs map keyed by {@link AdvisoryClassId}.
   * @param conditionRecoveryIndexChangeRegistry change registry for the recovery-index stream.
   */
  public static Output run(io.justsearch.core.execution.EngineExecutorRegistry executors,
      int occurrenceBufferSize,
      HealthRecoveryProjector healthRecoveryProjector,
      AdvisoryChangeRegistry advisoryChangeRegistry,
      Map<AdvisoryClassId, AdvisoryLog> advisoryLogs,
      ConditionRecoveryIndexChangeRegistry conditionRecoveryIndexChangeRegistry) {
    ConditionStore conditionStore = new ConditionStore();
    OccurrenceLog occurrenceLog = new OccurrenceLog(occurrenceBufferSize);
    HealthEventChangeRegistry healthEventChangeRegistry = new HealthEventChangeRegistry();
    healthEventChangeRegistry.subscribe(
        envelope -> {
          ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(conditionStore);
          conditionRecoveryIndexChangeRegistry.broadcast(index);
        });
    healthEventChangeRegistry.subscribeTyped(
        change ->
            advisoryChangeRegistry
                .project(healthRecoveryProjector, change)
                .ifPresent(
                    record -> advisoryLogs.get(healthRecoveryProjector.classId()).append(record)));
    Source headSource = Source.forProcess("head", UUID.randomUUID().toString(), null);
    LifecycleSnapshotTap lifecycleSnapshotTap =
        new LifecycleSnapshotTap(
            conditionStore, healthEventChangeRegistry, headSource, Clock.systemUTC());
    WorkerSnapshotTap workerSnapshotTap =
        new WorkerSnapshotTap(
            conditionStore, occurrenceLog, healthEventChangeRegistry, headSource, Clock.systemUTC());
    HeadHealthEventsEmitter headHealthEventsEmitter =
        new HeadHealthEventsEmitter(
            occurrenceLog, healthEventChangeRegistry, headSource, Clock.systemUTC());
    ReadinessReconciliationTrigger readinessReconciliationTrigger =
        new ReadinessReconciliationTrigger(executors);
    return new Output(
        conditionStore,
        occurrenceLog,
        healthEventChangeRegistry,
        headSource,
        lifecycleSnapshotTap,
        workerSnapshotTap,
        headHealthEventsEmitter,
        readinessReconciliationTrigger);
  }
}
