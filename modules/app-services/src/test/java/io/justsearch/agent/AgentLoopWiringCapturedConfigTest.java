/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.agent.api.registry.IntentPreviewer;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.bootstrap.phases.AgentLoopWiring;
import io.justsearch.app.services.observability.health.HeadHealthEventsEmitter;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AgentLoopWiringCapturedConfigTest {

  @Test
  void composedCitationResolverFollowsCapturedStoreAfterGlobalReplacement() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore captured = store(0.61);
    ConfigStore.setGlobal(captured);
    var docs = new CapturingDocuments();

    try {
      var service =
          (AgentLoopService)
              AgentLoopWiring.wire(
                  true,
                  mock(OnlineAiService.class),
                  mock(OperationCatalog.class),
                  mock(OperationDispatcher.class),
                  name -> name,
                  null,
                  context -> List.of(),
                  null,
                  null,
                  mock(HeadHealthEventsEmitter.class),
                  () -> "",
                  mock(BackendIntentRouter.class),
                  mock(ConsentCapsuleAuthority.class),
                  mock(IntentPreviewer.class),
                  name -> true,
                  docs);

      ConfigStore.setGlobal(store(0.97));
      captured.update(store(0.83).get());

      AgentCitationResolver resolver = citationResolver(service);
      resolver.resolve(
          "Captured configuration applies.",
          List.of(
              new AgentEvent.AgentSource(
                  "doc-1", 0, "/doc", "Doc", "captured configuration applies", 1, 1, "")),
          new EngineContext(
              EngineContext.ClientKind.INTERNAL,
              "captured-config-test",
              Optional.empty(),
              Optional.empty(),
              "core",
              "test",
              EngineContext.Survival.INTERACTIVE,
              EngineContext.Urgency.FOREGROUND));

      assertEquals(0.83, docs.threshold.get());
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private static AgentCitationResolver citationResolver(AgentLoopService service) throws Exception {
    Field stepRunnerField = AgentLoopService.class.getDeclaredField("stepRunner");
    stepRunnerField.setAccessible(true);
    AgentStepRunner stepRunner = (AgentStepRunner) stepRunnerField.get(service);
    Field resolverField = AgentStepRunner.class.getDeclaredField("citationResolver");
    resolverField.setAccessible(true);
    return (AgentCitationResolver) resolverField.get(stepRunner);
  }

  private static ConfigStore store(double threshold) {
    return new ConfigStore(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.citation.match_threshold", Double.toString(threshold))));
  }

  private static final class CapturingDocuments implements DocumentService {
    @Override
    public java.util.concurrent.CompletionStage<DocumentRecord> fetch(
        String docId, EngineContext context) {
      return CompletableFuture.failedFuture(new AssertionError("Expected literal citation matching"));
    }

    private final AtomicReference<Double> threshold = new AtomicReference<>();

    @Override
    public java.util.concurrent.CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answer,
        List<VerificationSource> sources,
        double similarityThreshold,
        EngineContext context) {
      threshold.set(similarityThreshold);
      return CompletableFuture.completedFuture(
          new CitationMatchResult(List.of(), 0, 0, 0, 0, ScorerKind.NONE, List.of()));
    }
  }
}
