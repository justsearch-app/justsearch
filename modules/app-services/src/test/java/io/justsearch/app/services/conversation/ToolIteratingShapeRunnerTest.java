package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.AgentRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-D — regression guard for the HTTP→{@link AgentRequest} boundary. Live validation
 * caught that the autonomy dial reached the FE wire but was DROPPED here (defaulting to ASSIST), so
 * an {@code auto}-dial MEDIUM write still gated as {@code typed_confirm}. This pins the pass-through.
 */
final class ToolIteratingShapeRunnerTest {

  @Test
  @DisplayName("parseRequest carries autonomyLevel + conversationId through to the AgentRequest")
  void parseRequestCarriesAutonomyLevel() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "maxIterations", 3,
                "conversationId", "conv-1",
                "autonomyLevel", "auto"));
    assertEquals("auto", r.autonomyLevel());
    assertEquals("conv-1", r.conversationId());
  }

  @Test
  @DisplayName("parseRequest leaves autonomyLevel null when absent (backend defaults to ASSIST)")
  void parseRequestDefaultsAutonomyLevelWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertNull(r.autonomyLevel());
  }

  @Test
  @DisplayName("parseRequest carries docIds (scope chips) through to the AgentRequest")
  void parseRequestCarriesDocIds() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "docIds", List.of("/docs/taxes.md", "/docs/invoices.md")));
    assertEquals(List.of("/docs/taxes.md", "/docs/invoices.md"), r.docIds());
  }

  @Test
  @DisplayName("parseRequest defaults docIds to empty (unscoped) when absent")
  void parseRequestDefaultsDocIdsWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertEquals(List.of(), r.docIds());
  }

  // ==================== sampling override (lane F PR 0b) ====================

  private static Map<String, Object> bodyWithSampling(Object sampling) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
    body.put("sampling", sampling);
    return body;
  }

  @Test
  @DisplayName("parseRequest carries the sampling override through to the AgentRequest")
  void parseRequestCarriesSamplingOverride() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            bodyWithSampling(Map.of("temperature", 0.0, "top_p", 0.5, "seed", 20260907)));
    assertEquals(0.0, r.sampling().temperature());
    assertEquals(0.5, r.sampling().topP());
    assertEquals(20260907L, r.sampling().seed());
  }

  @Test
  @DisplayName("parseRequest accepts a partial sampling override (absent knobs stay null)")
  void parseRequestAcceptsPartialSamplingOverride() {
    AgentRequest r = ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", 7)));
    assertNull(r.sampling().temperature());
    assertNull(r.sampling().topP());
    assertEquals(7L, r.sampling().seed());
  }

  @Test
  @DisplayName("parseRequest leaves sampling null when absent (byte-identical to pre-PR-0b)")
  void parseRequestDefaultsSamplingWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertNull(r.sampling());
  }

  @Test
  @DisplayName("parseRequest normalises an all-absent sampling object to null")
  void parseRequestNormalisesEmptySamplingToNull() {
    // One representation of "no override", so no downstream site has to distinguish the two.
    assertNull(ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of())).sampling());
  }

  @Test
  @DisplayName("parseRequest rejects a malformed sampling override (same 400 shape as bad messages)")
  void parseRequestRejectsMalformedSampling() {
    // Not an object.
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling("hot")));
    // Object with a non-numeric value.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolIteratingShapeRunner.parseRequest(
                bodyWithSampling(Map.of("temperature", "hot"))));
    // Out of the range SamplingParams itself enforces — refused at the boundary rather than
    // thrown mid-run, several LLM calls in.
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("temperature", 5.0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("top_p", 1.5))));
  }
}
