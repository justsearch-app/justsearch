/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static io.justsearch.agent.AgentLoopServiceTest.*;
import static io.justsearch.agent.ModelRequestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WP6.2 S8.2 (review 4) — two sends with the same words are two sends. The window gives each steer
 * send a request id; the run echoes it in that direction's queued report, so a send whose answer was
 * lost is settled by its own direction's report, never by another send's with the same text.
 */
class AgentDirectionRequestIdTest {

  @Test
  void queuedReportEchoesTheRequestIdOfItsOwnSendOnly() {
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("c1", "core_search", "{}"),
        ScriptedResponse.textOnly("done"));
    var tool = new StubTool("search", RiskTier.LOW, "result");
    var service = observed(new AgentLoopService(ai, stubCatalog(tool), stubExecutor(tool), stubEmitter(),
        null, null, null, null));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Answer in French.", "req-1"));
        assertTrue(service.injectSteeringDirective(run.id(), "Answer in French.", "req-2"));
        // A client that sends no request id (an older window) is still accepted.
        assertTrue(service.injectSteeringDirective(run.id(), "Be brief.", null));
      }
    };
    service.runAgent(new AgentRequest(history(), List.of(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    var acks = run.acks();
    assertEquals(List.of("queued", "queued", "queued", "applied", "applied", "applied"), run.outcomes());
    // Each queued report names its own send; the two same-text directions stay two directions.
    assertEquals(
        Arrays.asList("req-1", "req-2", null),
        acks.subList(0, 3).stream().map(AgentDirectionRequestIdTest::requestIdOf).toList());
    assertNotEquals(idOf(acks.get(0)), idOf(acks.get(1)));
    assertFalse(AgentEventPayloads.base(acks.get(2)).containsKey("clientRequestId"),
        "a direction sent without a request id reports none");
    // The final reports are tied to the queued one by directiveId and repeat no request id.
    for (var applied : acks.subList(3, 6)) {
      assertFalse(AgentEventPayloads.base(applied).containsKey("clientRequestId"), applied.toString());
    }
    assertEquals(List.of("Answer in French.", "Answer in French.", "Be brief."),
        directionsIn(ai.sent().get(1)));
  }

  private static String requestIdOf(AgentEvent.DirectiveAcknowledged ack) {
    return (String) AgentEventPayloads.base(ack).get("clientRequestId");
  }
}
