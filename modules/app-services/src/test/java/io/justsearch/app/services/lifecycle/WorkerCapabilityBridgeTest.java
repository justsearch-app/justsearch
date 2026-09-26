package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.core.component.ComponentState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 627 Deliverable 10 — the component registry is the single lifecycle authority the
 * supervisor writes and every capability surface reads. This pins the projection that replaces the
 * old mutable capability mirror: a listener registered on the read-only view observes producer
 * transitions directly.
 */
final class WorkerCapabilityBridgeTest {

  @Test
  @DisplayName("a listener on the single shared capability observes every supervisor transition")
  void singleInstanceListenerObservesSupervisorTransitions() {
    try (var cap = RegistryCapabilityTestFixture.worker()) {
      List<CapabilityHealth> observed = new ArrayList<>();
      cap.addListener((prev, next) -> observed.add(next));

      // Drive the lifecycle the KS bootstrap/supervisor publishes; the read-only projection sees
      // each state change directly, without a second mutable instance to mirror onto.
      cap.transition(ComponentState.READY, null);
      cap.transition(ComponentState.FAILED, "Health check failed");
      cap.transition(ComponentState.READY, null);

      assertEquals(
          List.of(CapabilityHealth.READY, CapabilityHealth.DEGRADED, CapabilityHealth.READY),
          observed,
          "the listener observes every state-change transition from the registry authority");
      assertEquals(CapabilityHealth.READY, cap.health());
      assertNull(cap.pendingReason(), "READY clears pendingReason (per Capability contract)");
    }
  }

  @Test
  @DisplayName("an unobserved registered component is unavailable without a manufactured reason")
  void absentRegistryStateIsUnavailableWithoutAManufacturedReason() {
    try (var cap = RegistryCapabilityTestFixture.worker()) {
      assertEquals(CapabilityHealth.OFFLINE, cap.health());
      assertNull(cap.pendingReason(), "the registry has not observed a cause yet");
      assertNull(cap.pendingDetail(), "the registry has not observed diagnostic evidence yet");
      assertFalse(cap.available(), "ABSENT is unavailable to capability consumers");
    }
  }
}
