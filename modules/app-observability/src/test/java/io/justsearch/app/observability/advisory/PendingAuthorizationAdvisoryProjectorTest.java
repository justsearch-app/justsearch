package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RenderHint;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.observability.operations.PendingAuthorizationEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PendingAuthorizationAdvisoryProjector")
final class PendingAuthorizationAdvisoryProjectorTest {

  private static final Instant T0 = Instant.parse("2026-07-02T10:00:00Z");

  private final PendingAuthorizationAdvisoryProjector projector =
      new PendingAuthorizationAdvisoryProjector();

  private static PendingAuthorizationEvent event(String pendingId) {
    return event(pendingId, TransportTag.MCP);
  }

  private static PendingAuthorizationEvent event(String pendingId, TransportTag transport) {
    return new PendingAuthorizationEvent(
        pendingId,
        "core.ingest-files",
        SourceTier.UNTRUSTED,
        RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM,
        T0,
        T0.plusSeconds(300),
        transport);
  }

  @Test
  @DisplayName("classId is authorization.pending")
  void classId() {
    assertEquals(AdvisoryClassId.of("authorization.pending"), projector.classId());
  }

  @Test
  @DisplayName("emission policy is REQUIRES_ACK")
  void emissionPolicyIsRequiresAck() {
    assertEquals(RenderHint.REQUIRES_ACK, projector.emissionPolicy().renderHint());
    assertEquals(EmissionPolicy.requiresAck(), projector.emissionPolicy());
  }

  @Test
  @DisplayName("an MCP-transport gate-firing projects")
  void mcpEventProjects() {
    var result = projector.project(event("pa-abc123"));

    assertTrue(result.isPresent());
    AdvisoryProjection p = result.get();
    assertEquals(T0, p.occurredAt());
    assertEquals("pa-abc123", p.classExtras().get("pendingId"));
    assertEquals("core.ingest-files", p.classExtras().get("operationId"));
    assertEquals("MEDIUM", p.classExtras().get("riskTier"));
    assertEquals("TYPED_CONFIRM", p.classExtras().get("gateBehavior"));
  }

  @Test
  @DisplayName("classExtras carries expiresAt — tempdoc 807 item 3")
  void classExtrasCarriesExpiresAt() {
    // Sandbox round 13 F3: pendings really do expire (5-min TTL) but no surface said when, so a
    // client could not tell the user how long an approval request is valid and the
    // expired-pending-approval ceremony was unperformable as written.
    AdvisoryProjection p = projector.project(event("pa-abc123")).orElseThrow();

    assertEquals(T0.plusSeconds(300).toString(), p.classExtras().get("expiresAt"));
  }

  @Test
  @DisplayName(
      "a non-MCP (browser) gate-firing does NOT project — avoids a redundant advisory for an"
          + " action the user just triggered themselves via the ceremony dialog")
  void nonMcpEventDoesNotProject() {
    assertFalse(projector.project(event("pa-abc123", TransportTag.BUTTON)).isPresent());
    assertFalse(projector.project(event("pa-def456", TransportTag.URL_BAR)).isPresent());
    assertFalse(projector.project(event("pa-ghi789", TransportTag.SYSTEM_INTERNAL)).isPresent());
  }

  @Test
  @DisplayName("classExtras never carries requestedBy (event withholds it by design)")
  void neverCarriesRequestedBy() {
    var result = projector.project(event("pa-abc123"));

    assertTrue(result.isPresent());
    assertTrue(!result.get().classExtras().containsKey("requestedBy"));
  }

  @Test
  @DisplayName("dedupKey is the pendingId itself")
  void dedupKeyIsPendingId() {
    assertEquals("pa-abc123", projector.dedupKey(event("pa-abc123")));
    assertEquals("pa-xyz789", projector.dedupKey(event("pa-xyz789")));
  }

  @Test
  @DisplayName("dedupKey is idempotent")
  void dedupKeyIdempotent() {
    var ev = event("pa-abc123");
    assertEquals(projector.dedupKey(ev), projector.dedupKey(ev));
  }
  @Test
  void terminalDecisionsDoNotAnnounceAnotherApprovalRequest() throws Exception {
    for (String disposition : java.util.List.of("DENIED", "APPROVED", "EXPIRED")) {
      PendingAuthorizationEvent terminal;
      try {
        terminal = PendingAuthorizationEvent.class.getConstructor(String.class, String.class,
            SourceTier.class, RiskTier.class, GateBehavior.class, Instant.class, Instant.class,
            TransportTag.class, String.class).newInstance("pa-terminal", "core.ingest-files",
                SourceTier.UNTRUSTED, RiskTier.MEDIUM, GateBehavior.TYPED_CONFIRM, T0,
                T0.plusSeconds(300), TransportTag.MCP, disposition);
      } catch (NoSuchMethodException beforeLifecycleEvents) {
        terminal = event("pa-terminal");
      }
      assertTrue(projector.project(terminal).isEmpty(), "terminal " + disposition + " must not request approval");
    }
  }
}
