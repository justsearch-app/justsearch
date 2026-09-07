package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.inference.EncoderRuntimeResponse;
import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.ort.EncoderRole;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EncoderRuntimeController} (tempdoc 422). Mirrors
 * {@link SessionPoliciesControllerTest}'s shape: null-client degradation, happy-path
 * pass-through, late-bind flip, unbind.
 */
@DisplayName("EncoderRuntimeController (tempdoc 422)")
class EncoderRuntimeControllerTest {

  @Test
  @DisplayName("null client → snapshotStatus='worker-unreachable', empty encoders map")
  void nullClientReturnsWorkerUnreachable() {
    EncoderRuntimeController controller = new EncoderRuntimeController(null);
    EncoderRuntimeResponse response = controller.buildResponse();

    assertEquals("worker-unreachable", response.snapshotStatus());
    assertTrue(response.encoders().isEmpty());
  }

  @Test
  @DisplayName(
      "client reports worker-unreachable getSessionPolicies → snapshotStatus passthrough")
  void workerUnreachablePassthrough() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> policies = new LinkedHashMap<>();
    policies.put("configStatus", "worker-unreachable");
    policies.put("runtime", new LinkedHashMap<>());
    policies.put("models", new TreeMap<>());
    when(client.getSessionPolicies()).thenReturn(policies);

    EncoderRuntimeController controller = new EncoderRuntimeController(client);
    EncoderRuntimeResponse response = controller.buildResponse();
    assertEquals("worker-unreachable", response.snapshotStatus());
  }

  @Test
  @DisplayName("empty models map → snapshotStatus='policy-unavailable'")
  void emptyModelsReturnsPolicyUnavailable() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> policies = new LinkedHashMap<>();
    policies.put("configStatus", "ok");
    policies.put("runtime", new LinkedHashMap<>());
    policies.put("models", new TreeMap<>());
    when(client.getSessionPolicies()).thenReturn(policies);
    when(client.getEncoderOrtCudaViews()).thenReturn(Map.of());

    EncoderRuntimeController controller = new EncoderRuntimeController(client);
    EncoderRuntimeResponse response = controller.buildResponse();
    assertEquals("policy-unavailable", response.snapshotStatus());
  }

  @Test
  @DisplayName(
      "happy path: 2 encoders policy, both have OrtCudaView → emits both keyed by consumerName")
  void happyPathTwoEncoders() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> policies = buildPoliciesEnvelope();
    Map<String, Object> models = new TreeMap<>();
    models.put("EMBEDDING", policyForGpu());
    models.put("CITATION", policyForCpu());
    policies.put("models", models);
    when(client.getSessionPolicies()).thenReturn(policies);

    Map<EncoderRole, OrtCudaView> views = new EnumMap<>(EncoderRole.class);
    views.put(EncoderRole.EMBEDDING, gpuAvailable());
    views.put(EncoderRole.CITATION, OrtCudaView.notConfigured());
    when(client.getEncoderOrtCudaViews()).thenReturn(views);

    EncoderRuntimeController controller = new EncoderRuntimeController(client);
    EncoderRuntimeResponse response = controller.buildResponse();

    assertEquals("ok", response.snapshotStatus());
    assertEquals(2, response.encoders().size());

    // Keys use EncoderRole.consumerName(), not the uppercase enum name.
    EncoderRuntimeView embed = response.encoders().get("embed");
    assertNotNull(embed);
    assertEquals("cuda", embed.currentAccelerator());
    assertEquals("CUDA", embed.configuredAccelerator());

    EncoderRuntimeView citation = response.encoders().get("citation");
    assertNotNull(citation);
    assertEquals("cpu", citation.currentAccelerator());
    assertEquals("CPU", citation.configuredAccelerator());
    assertTrue(citation.explanation().contains("CPU by design"));
  }

  @Test
  @DisplayName("unknown role key in policies map is skipped, not thrown")
  void unknownRoleKeyIsSkipped() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> policies = buildPoliciesEnvelope();
    Map<String, Object> models = new TreeMap<>();
    models.put("FUTURE_ROLE", policyForCpu());
    models.put("EMBEDDING", policyForGpu());
    policies.put("models", models);
    when(client.getSessionPolicies()).thenReturn(policies);

    Map<EncoderRole, OrtCudaView> views = new EnumMap<>(EncoderRole.class);
    views.put(EncoderRole.EMBEDDING, gpuAvailable());
    when(client.getEncoderOrtCudaViews()).thenReturn(views);

    EncoderRuntimeController controller = new EncoderRuntimeController(client);
    EncoderRuntimeResponse response = controller.buildResponse();

    assertEquals("ok", response.snapshotStatus());
    assertEquals(1, response.encoders().size());
    assertNotNull(response.encoders().get("embed"));
  }

  @Test
  @DisplayName("setClient late-bind flips from worker-unreachable to ok")
  void setClientLateBindReplacesNull() {
    EncoderRuntimeController controller = new EncoderRuntimeController(null);
    assertEquals("worker-unreachable", controller.buildResponse().snapshotStatus());

    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Object> policies = buildPoliciesEnvelope();
    Map<String, Object> models = new TreeMap<>();
    models.put("EMBEDDING", policyForGpu());
    policies.put("models", models);
    when(client.getSessionPolicies()).thenReturn(policies);

    Map<EncoderRole, OrtCudaView> views = new EnumMap<>(EncoderRole.class);
    views.put(EncoderRole.EMBEDDING, gpuAvailable());
    when(client.getEncoderOrtCudaViews()).thenReturn(views);

    controller.setClient(client);
    assertEquals("ok", controller.buildResponse().snapshotStatus());
  }

  @Test
  @DisplayName("setClient(null) reverts to worker-unreachable (Worker unbind)")
  void setClientNullRevertsToWorkerUnreachable() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    EncoderRuntimeController controller = new EncoderRuntimeController(client);
    controller.setClient(null);
    assertEquals("worker-unreachable", controller.buildResponse().snapshotStatus());
  }

  // ---------- helpers ----------

  private static Map<String, Object> buildPoliciesEnvelope() {
    Map<String, Object> policies = new LinkedHashMap<>();
    policies.put("configStatus", "ok");
    policies.put("runtime", new LinkedHashMap<>());
    return policies;
  }

  private static Map<String, Object> policyForGpu() {
    Map<String, Object> p = new LinkedHashMap<>();
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("executionProvider", "CUDA");
    p.put("variant", variant);
    return p;
  }

  private static Map<String, Object> policyForCpu() {
    Map<String, Object> p = new LinkedHashMap<>();
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("executionProvider", "CPU");
    p.put("variant", variant);
    return p;
  }

  private static OrtCudaView gpuAvailable() {
    return new OrtCudaView(true, true, true, "cuda12", "C:/native", "", List.of());
  }
}
