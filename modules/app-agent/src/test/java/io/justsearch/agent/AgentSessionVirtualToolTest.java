package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.AgentSession.VirtualToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 508 §11.5 / §13.5 Phase B — AgentSession virtual-tool
 * future map tests. Covers the register / complete / 404 / cancel-
 * fan-out paths in isolation from the loop.
 */
final class AgentSessionVirtualToolTest {

  private AgentSession session() {
    return new AgentSession(List.of(Map.of("role", "system", "content", "x")), 1000, EngineContextTestFixtures.AGENT_LOOP);
  }

  @Test
  void registerVirtualToolGateReturnsAnUnresolvedFuture() {
    var s = session();
    CompletableFuture<VirtualToolResult> future = s.registerVirtualToolGate("call-1");
    assertNotNull(future);
    assertFalse(future.isDone(), "future should be unresolved until completeVirtualTool fires");
  }

  @Test
  void completeVirtualToolWithSuccessResolvesFuture() throws Exception {
    var s = session();
    CompletableFuture<VirtualToolResult> future = s.registerVirtualToolGate("call-2");
    boolean delivered = s.completeVirtualTool("call-2", VirtualToolResult.success("hello"));
    assertTrue(delivered);
    assertTrue(future.isDone());
    VirtualToolResult result = future.get();
    assertTrue(result.success());
    assertEquals("hello", result.output());
  }

  @Test
  void completeVirtualToolWithFailureCarriesErrorDetail() throws Exception {
    var s = session();
    CompletableFuture<VirtualToolResult> future = s.registerVirtualToolGate("call-3");
    boolean delivered = s.completeVirtualTool("call-3", VirtualToolResult.failure("boom"));
    assertTrue(delivered);
    VirtualToolResult result = future.get();
    assertFalse(result.success());
    assertEquals("boom", result.errorDetail());
  }

  @Test
  void completeVirtualToolReturnsFalseForUnknownCallId() {
    var s = session();
    boolean delivered = s.completeVirtualTool("never-registered", VirtualToolResult.success("x"));
    assertFalse(delivered, "404 expected for stale / unknown callId");
  }

  @Test
  void completeVirtualToolIsIdempotentByCallId() {
    var s = session();
    s.registerVirtualToolGate("call-4");
    assertTrue(s.completeVirtualTool("call-4", VirtualToolResult.success("a")));
    // Second call for the same id returns false — the future was
    // already removed from the map.
    assertFalse(s.completeVirtualTool("call-4", VirtualToolResult.success("b")));
  }

  @Test
  void cancelSessionFansOutFailureToPendingVirtualTools() throws Exception {
    var s = session();
    CompletableFuture<VirtualToolResult> f1 = s.registerVirtualToolGate("call-5");
    CompletableFuture<VirtualToolResult> f2 = s.registerVirtualToolGate("call-6");
    s.cancel();
    assertTrue(f1.isDone());
    assertTrue(f2.isDone());
    VirtualToolResult r1 = f1.get();
    VirtualToolResult r2 = f2.get();
    assertFalse(r1.success());
    assertFalse(r2.success());
    assertTrue(r1.errorDetail().contains("cancelled"));
    // After cancel, subsequent completes are no-ops.
    assertFalse(s.completeVirtualTool("call-5", VirtualToolResult.success("late")));
    assertFalse(r2.errorDetail() == null);
  }
}
