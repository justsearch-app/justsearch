package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryClassRegistry;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.HealthRecoveryProjector;
import io.justsearch.app.observability.advisory.OperationCompletionProjector;
import io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthSubstrateInitTest {

  @Test
  void run_returnsFullyPopulatedOutput() {
    HealthRecoveryProjector projector = new HealthRecoveryProjector();
    OperationCompletionProjector opCompletion = new OperationCompletionProjector();
    AdvisoryClassRegistry classRegistry =
        AdvisoryClassRegistry.builder().register(projector).register(opCompletion).build();
    AdvisoryChangeRegistry changes = new AdvisoryChangeRegistry(classRegistry, Clock.systemUTC());
    Map<AdvisoryClassId, AdvisoryLog> logs =
        Map.of(
            HealthRecoveryProjector.CLASS_ID,
            new AdvisoryLog(),
            OperationCompletionProjector.CLASS_ID,
            new AdvisoryLog());
    ConditionRecoveryIndexChangeRegistry recoveryChanges =
        new ConditionRecoveryIndexChangeRegistry();

    HealthSubstrateInit.Output out =
        HealthSubstrateInit.run(new io.justsearch.core.execution.TestEngineExecutors(), 256, projector, changes, logs, recoveryChanges);

    assertNotNull(out.conditionStore());
    assertNotNull(out.occurrenceLog());
    assertNotNull(out.healthEventChangeRegistry());
    assertNotNull(out.headSource());
    assertNotNull(out.lifecycleSnapshotTap());
    assertNotNull(out.workerSnapshotTap());
    assertNotNull(out.headHealthEventsEmitter());
    assertNotNull(out.readinessReconciliationTrigger());
    out.readinessReconciliationTrigger().close();
  }
}
