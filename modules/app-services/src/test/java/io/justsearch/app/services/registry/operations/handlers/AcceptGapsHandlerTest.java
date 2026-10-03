/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.EngineProvenance;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AcceptGapsHandlerTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";
  private static final String HASH = "a".repeat(64);
  private static final String ARGUMENTS = "{\"reindexKey\":\"" + KEY
      + "\",\"gapListHash\":\"" + HASH + "\"}";

  @Test
  void webviewApprovalFreezesExactListAndDispatchesOneRecordedDecision() {
    var ingestion = mock(RecordedIngestionService.class);
    var context = TestEngineContexts.ui();
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI,
        Instant.parse("2026-09-24T00:00:00Z"), Optional.empty());
    var handler = new AcceptGapsHandler(ingestion);
    var prepared = handler.prepare(ARGUMENTS, provenance, context);
    handler.validatePreparation(prepared);
    when(ingestion.acceptGaps(KEY, HASH, context)).thenReturn(OperationResult.success("accepted"));

    var response = handler.executePrepared(prepared, provenance, context,
        mock(OperationRecordHandle.class)).response();

    assertEquals("accepted", response.message());
    verify(ingestion).acceptGaps(KEY, HASH, context);
  }

  @Test
  void mcpCannotPrepareGapAcceptanceAndMalformedReplayCannotExecute() {
    var ingestion = mock(RecordedIngestionService.class);
    var handler = new AcceptGapsHandler(ingestion);
    var mcp = TestEngineContexts.mcp();
    var provenance = EngineProvenance.invocation(mcp, ExecutorTag.AGENT,
        Instant.parse("2026-09-24T00:00:00Z"), Optional.empty());
    assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare(ARGUMENTS, provenance, mcp));
    assertThrows(IllegalArgumentException.class,
        () -> io.justsearch.app.api.operations.RecordedGapAcceptancePlan.decode("null"));
  }
}
