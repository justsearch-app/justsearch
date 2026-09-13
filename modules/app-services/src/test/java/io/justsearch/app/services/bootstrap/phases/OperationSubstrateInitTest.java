package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationSubstrateInitTest {

  @Test
  void run_returnsFullyPopulatedOutputAndRegistersNavigateHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    OperationCatalog agentTools = OperationCatalog.of("core", List.of());

    OperationSubstrateInit.Output out =
        OperationSubstrateInit.run(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class), new io.justsearch.core.execution.TestEngineExecutors(),
            handlers,
            ops,
            agentTools,
            req -> true,
            new io.justsearch.app.observability.surface.CoreSurfaceCatalog(),
            io.justsearch.agent.api.encryption.StoreCipher.disabled());

    assertNotNull(out.operationHistoryResourceCatalog());
    assertNotNull(out.operationHistoryStore());
    assertNotNull(out.operationHistoryChangeRegistry());
    assertNotNull(out.advisoryClassRegistry());
    assertNotNull(out.advisoryChangeRegistry());
    assertNotNull(out.advisoryResourceCatalog());
    assertNotNull(out.advisoryLogs());
    assertNotNull(out.promptCatalog());
    assertNotNull(out.intentSourceCatalog());
    assertNotNull(out.operationExecutor());
    assertNotNull(out.capabilitiesChangeRegistry());
    assertNotNull(out.intentEnvelopeChangeRegistry());
    assertNotNull(out.backendIntentRouter());
    assertNotNull(out.healthRecoveryProjector());
    // Tempdoc 875 C.3: durable grants are never wired without the argument scope that bounds them —
    // the phase constructs both and setDurableGrantStore requires both, so this pins the wiring.
    assertNotNull(out.durableGrantStore());
    assertNotNull(out.durableGrantScope());

    // The phase function registers the navigate-to-surface handler as a side effect.
    assertTrue(
        handlers.resolve(CoreOperationCatalog.NAVIGATE_TO_SURFACE).isPresent(),
        "NAVIGATE_TO_SURFACE handler should be registered by the phase function");
  }
}
