/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.EngineProvenance;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CancelReindexHandlerTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";
  private static final String ARGUMENTS = "{\"reindexKey\":\"" + KEY + "\"}";

  @Test
  void webviewApprovalNamesExactKeyAndInvokesOwnerOnce() {
    var ingestion = mock(RecordedIngestionService.class);
    var context = TestEngineContexts.ui();
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI,
        Instant.parse("2026-09-27T00:00:00Z"), Optional.empty());
    var handler = new CancelReindexHandler(ingestion);
    var prepared = handler.prepare(ARGUMENTS, provenance, context);
    when(ingestion.cancelReindex(KEY, context)).thenReturn(OperationResult.success("recorded"));

    assertEquals("Cancel recorded rebuild " + KEY, handler.approvalPreview(prepared).summary());
    assertEquals("recorded", handler.executePrepared(prepared, provenance, context, null)
        .response().message());
    verify(ingestion).cancelReindex(KEY, context);
  }

  @Test
  void nonWebviewAndMalformedTargetsCannotPrepareCancellation() {
    var handler = new CancelReindexHandler(mock(RecordedIngestionService.class));
    var mcp = TestEngineContexts.mcp();
    var provenance = EngineProvenance.invocation(mcp, ExecutorTag.AGENT,
        Instant.parse("2026-09-27T00:00:00Z"), Optional.empty());
    assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare(ARGUMENTS, provenance, mcp));
    var ui = TestEngineContexts.ui();
    assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare("{\"reindexKey\":\"invalid\"}", provenance, ui));
    assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare(ARGUMENTS.substring(0, ARGUMENTS.length() - 1)
            + ",\"extra\":true}", provenance, ui));
  }
}
