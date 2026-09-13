package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.KnowledgeClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionPoliciesController}'s thin HTTP-adapter shape after the
 * tempdoc 397 §14.28 U4 gRPC-bridge migration. The client-facing JSON parsing + failure-mode
 * logic lives in {@link KnowledgeClient#getSessionPolicies} (per the
 * {@code UiApiGuardrailsTest} rule that ui.api must not depend on proto types); this
 * controller only handles the null-client degradation.
 */
@DisplayName("SessionPoliciesController (§14.28 U4)")
class SessionPoliciesControllerTest {

  @Test
  @DisplayName("null client → configStatus='worker-unreachable', empty maps")
  void nullClientReturnsWorkerUnreachable() {
    SessionPoliciesController controller = new SessionPoliciesController(null);
    Map<String, Object> response = controller.buildResponse(TestRequestContexts.browser());

    assertEquals("worker-unreachable", response.get("configStatus"));
    assertTrue(response.get("runtime") instanceof Map);
    assertTrue(((Map<?, ?>) response.get("runtime")).isEmpty());
    assertTrue(response.get("models") instanceof Map);
    assertTrue(((Map<?, ?>) response.get("models")).isEmpty());
  }

  @Test
  @DisplayName("client returns map → controller passes through unchanged")
  void clientMapPassesThrough() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> mockResponse = new LinkedHashMap<>();
    mockResponse.put("configStatus", "ok");
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("arena", new LinkedHashMap<>());
    mockResponse.put("runtime", runtime);
    Map<String, Object> models = new TreeMap<>();
    models.put("EMBEDDING", new LinkedHashMap<>());
    mockResponse.put("models", models);
    when(client.getSessionPolicies(TestRequestContexts.browser())).thenReturn(mockResponse);

    SessionPoliciesController controller = new SessionPoliciesController(client);
    Map<String, Object> response = controller.buildResponse(TestRequestContexts.browser());

    assertEquals("ok", response.get("configStatus"));
    assertEquals(runtime, response.get("runtime"));
    assertEquals(models, response.get("models"));
  }

  @Test
  @DisplayName("setClient late-bind replaces null client and escapes worker-unreachable")
  void setClientLateBindReplacesNull() {
    // Tempdoc 400 Phase 2.1 (LR1-c): LocalApiServer wires null at
    // construction in runHeadlessEval mode and late-binds once the Worker
    // is ready. This test guards that setClient actually flips the
    // controller's output from worker-unreachable to the authoritative
    // PolicySnapshot response.
    SessionPoliciesController controller = new SessionPoliciesController(null);
    assertEquals(
        "worker-unreachable", controller.buildResponse(TestRequestContexts.browser()).get("configStatus"));

    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("configStatus", "ok");
    ready.put("runtime", new LinkedHashMap<>());
    ready.put("models", new TreeMap<>());
    when(client.getSessionPolicies(TestRequestContexts.browser())).thenReturn(ready);

    controller.setClient(client);

    Map<String, Object> response = controller.buildResponse(TestRequestContexts.browser());
    assertEquals("ok", response.get("configStatus"));
  }

  @Test
  @DisplayName("setClient(null) reverts to worker-unreachable (Worker unbind)")
  void setClientNullRevertsToWorkerUnreachable() {
    // Covers the ks=null branch in LocalApiServer.lateBindKnowledgeServer:
    // if the late-bind fires with a null ks (Worker boot failed), the
    // controller must return to worker-unreachable rather than retain a
    // stale reference.
    KnowledgeClient client = mock(KnowledgeClient.class);
    SessionPoliciesController controller = new SessionPoliciesController(client);
    controller.setClient(null);
    assertEquals(
        "worker-unreachable", controller.buildResponse(TestRequestContexts.browser()).get("configStatus"));
  }
}
