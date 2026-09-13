/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreparedDispatchAdmissionTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void frozenOriginUsesTheCorrectRealWorkOwnerUntilAsyncCompletion(boolean sameCaller) throws Exception {
    var admission = new EngineAdmissionController(1, 3, 1);
    var id = new OperationRef("core.prepared-admission-proof");
    var op = new Operation(id, Presentation.of(new I18nKey("test.prepared"), new I18nKey("test.prepared.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(), Set.of(), false).withRecordKind(OperationKind.NOTE),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1"), Set.of(ExecutorTag.AGENT));
    var origin = TestRequestContexts.mcp("frozen-origin");
    var originProvenance = EngineProvenance.invocation(origin, ExecutorTag.AGENT, Clock.systemUTC().instant(), Optional.empty());
    var seenContext = new AtomicReference<EngineContext>();
    var seenProvenance = new AtomicReference<InvocationProvenance>();
    var effect = new CompletableFuture<OperationResult>();
    var handlers = new HandlerRegistry();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Raw handler cannot execute");
      }
      @Override public OperationPreparation prepare(String args, InvocationProvenance provenance, EngineContext context) {
        return new OperationPreparation(args, "admission-proof.v1", "{}");
      }
      @Override public OperationApprovalPreview approvalPreview(OperationPreparation value) {
        return new OperationApprovalPreview("Use the frozen admission proof target");
      }
      @Override public void validatePreparation(OperationPreparation value) {
        if (!"admission-proof.v1".equals(value.replaySchema()) || !"{}".equals(value.replayPayloadJson())
            || value.content() != OperationPreparation.Content.METADATA) throw new IllegalArgumentException("Unsupported proof payload");
      }
      @Override public OperationExecution executePrepared(OperationPreparation value, InvocationProvenance provenance,
          EngineContext context, OperationRecordHandle record) {
        seenContext.set(context); seenProvenance.set(provenance);
        return new OperationExecution(OperationResult.success("started"), effect);
      }
    });
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var capsules = new ConsentCapsuleService();
      var executor = new OperationExecutorImpl(new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of()),
          admission, handlers, null, Map.of(), Clock.systemUTC(), new CoreTrustEvaluator(),
          CoreIntentSourceCatalog.catalog(), null, capsules);
      String key = OperationKeys.generate(Clock.systemUTC());
      ConfirmationRequiredException pending;
      try (var request = admission.admit(origin, false)) {
        pending = assertThrows(ConfirmationRequiredException.class,
            () -> executor.dispatch(op, "{}", originProvenance, Optional.empty(), request.context(), key));
        assertEquals(1, admission.activeWorkCount());
      }
      assertEquals(0, admission.activeWorkCount());
      var caller = sameCaller ? origin : TestRequestContexts.browser();
      try (var request = admission.admit(caller, false)) {
        var currentProvenance = EngineProvenance.invocation(request.context(), ExecutorTag.UI, Clock.systemUTC().instant(), Optional.empty());
        String token = capsules.mintPrepared(id.value(), "{}", EngineProvenance.sourceTier(caller), key, pending.preparationNonce());
        assertTrue(executor.dispatch(op, "{}", currentProvenance, Optional.of(token), request.context(), key, pending.preparationNonce()).success());
        assertEquals(sameCaller ? 1 : 2, admission.activeWorkCount());
        assertEquals(origin.withWorkId(seenContext.get().workId().orElseThrow()), seenContext.get());
        assertEquals(originProvenance, seenProvenance.get());
        assertEquals(sameCaller, request.context().workId().equals(seenContext.get().workId()));
        assertEquals(OperationState.RUNNING, store.find(key).orElseThrow().state());
      }
      assertEquals(1, admission.activeWorkCount(), "Async original work survives the retry request closing");
      effect.complete(OperationResult.success("finished"));
      assertEquals(0, admission.activeWorkCount());
      assertEquals(OperationState.COMPLETE, store.find(key).orElseThrow().state());
    } finally {
      effect.complete(OperationResult.success("cleanup"));
    }
  }
}
