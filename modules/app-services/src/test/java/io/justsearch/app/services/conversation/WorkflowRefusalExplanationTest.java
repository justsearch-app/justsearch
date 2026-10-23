/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.BulkReindexFailureMessage;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WorkflowRefusalExplanationTest {
  @Test
  void recordedBulkRefusalKeepsItsCodeInsideTheCompletionOutput() {
    String key = "00000000-0000-7000-8000-000000000001";
    String code = "BULK_GENERATION_REFUSED";
    String explanation = BulkReindexFailureMessage.forOperation("core.bulk-reindex", code);
    var failure = OperationResult.failure(explanation, code, Map.of(), false);
    var receipt = new OperationResult(false, explanation, failure.executionId(), Map.of("operationKey", key),
        failure.errorCode(), failure.errorDetails(), failure.retryable());
    var plan = new OperationDispatchPlan.Recorded(key, receipt);
    BackendIntentRouter router = new BackendIntentRouter() {
      @Override public OperationDispatchPlan prepare(Intent intent, InvocationProvenance provenance,
          EngineContext context, String requestedKey, boolean includePreview) { return plan; }
      @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
          EngineContext context) { throw new AssertionError("Workflow must retain its recorded plan"); }
      @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
          EngineContext context, String recordedKey, UUID nonce) {
        assertEquals(key, recordedKey);
        assertNull(nonce);
        return new IntentDispatchResult.Dispatched(receipt);
      }
    };
    var core = new CoreOperationCatalog();
    var workflow = new Workflow(new WorkflowRef("core.refused-bulk"),
        Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")), Provenance.core("1"),
        Audience.USER, List.of(new WorkflowNode.ToolStep("rebuild", CoreOperationCatalog.BULK_REINDEX, "{}")),
        List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
    var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of()), List.of());
    var runner = new WorkflowShapeRunner(() -> engine, WorkflowCatalog.of("core", List.of(workflow)),
        () -> OperationCatalog.of("core", List.of()), () -> core,
        new GatedOperationExecutor(() -> router, () -> null, TransportTag.WORKFLOW),
        new WorkflowGateRegistry(), new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("workflowId", workflow.id().value()), Audience.USER, events::add,
        io.justsearch.app.services.TestEngineContexts.internal());
    var completion = events.stream().filter(event -> event.name().equals("tool_exec_completed"))
        .findFirst().orElseThrow();
    assertEquals(false, completion.payload().get("success"));
    assertEquals(Map.of("operationKey", key), completion.payload().get("structuredData"));
    String output = String.valueOf(completion.payload().get("output"));
    assertTrue(output.contains("could not safely start or resume the new index"), output);
    String detail = "(Code: BULK_GENERATION_REFUSED)";
    assertTrue(output.contains(detail), output);
    assertEquals(output.indexOf(detail), output.lastIndexOf(detail));
    assertEquals(Optional.of(code), receipt.errorCode());
  }
}
