/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Derivation-table tests for {@link RuntimeStatus} (Mode → ENGINE axis, plus ADOPTION / LEASE). */
final class RuntimeStatusTest {

  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  @Test
  void engineAxisDerivation() {
    RuntimeStatus.Condition offline = RuntimeStatus.deriveEngine(Mode.OFFLINE, 3L, NOW);
    assertEquals(RuntimeStatus.ENGINE_DOWN, offline.status());
    assertEquals(RuntimeStatus.REASON_ENGINE_DOWN, offline.reason());

    RuntimeStatus.Condition indexing = RuntimeStatus.deriveEngine(Mode.INDEXING, 3L, NOW);
    assertEquals(RuntimeStatus.ENGINE_DOWN, indexing.status());
    assertEquals(RuntimeStatus.REASON_GPU_YIELDED_TO_INDEXING, indexing.reason());

    RuntimeStatus.Condition transitioning = RuntimeStatus.deriveEngine(Mode.TRANSITIONING, 3L, NOW);
    assertEquals(RuntimeStatus.ENGINE_STARTING, transitioning.status());

    RuntimeStatus.Condition online = RuntimeStatus.deriveEngine(Mode.ONLINE, 3L, NOW);
    assertEquals(RuntimeStatus.ENGINE_HEALTHY, online.status());

    // Null defaults to OFFLINE/Down.
    assertEquals(RuntimeStatus.ENGINE_DOWN, RuntimeStatus.deriveEngine(null, 0L, NOW).status());

    // observedSpecVersion is stamped through.
    assertEquals(3L, online.observedSpecVersion());
  }

  @Test
  void adoptionAxisDerivation() {
    assertEquals("external", RuntimeStatus.deriveAdoption(true, 0L, NOW).status());
    assertEquals("own", RuntimeStatus.deriveAdoption(false, 0L, NOW).status());
  }

  @Test
  void leaseAxisDerivation() {
    assertEquals("CHAT", RuntimeStatus.deriveLease(RuntimeGpuLease.Holder.CHAT, 0L, NOW).status());
    assertEquals("INDEXING", RuntimeStatus.deriveLease(RuntimeGpuLease.Holder.INDEXING, 0L, NOW).status());
    assertEquals("NONE", RuntimeStatus.deriveLease(RuntimeGpuLease.Holder.NONE, 0L, NOW).status());
    assertEquals("NONE", RuntimeStatus.deriveLease(null, 0L, NOW).status());
  }

  @Test
  void indexingLeaseSurvivesTransitionUntilModeCommits() {
    RuntimeGpuLease lease = new RuntimeGpuLease();
    lease.mirrorFromMode(Mode.INDEXING);
    assertEquals(RuntimeGpuLease.Holder.INDEXING, lease.holder());
    lease.mirrorFromMode(Mode.TRANSITIONING);
    assertEquals("INDEXING", RuntimeStatus.deriveLease(lease.holder(), 0L, NOW).status());
    lease.mirrorFromMode(Mode.ONLINE);
    assertEquals(RuntimeGpuLease.Holder.CHAT, lease.holder());
    lease.mirrorFromMode(Mode.OFFLINE);
    assertEquals(RuntimeGpuLease.Holder.NONE, lease.holder());
  }

  @Test
  void fullDeriveHasAllThreeAxes() {
    RuntimeStatus s = RuntimeStatus.derive(Mode.ONLINE, false, RuntimeGpuLease.Holder.CHAT, 7L, NOW);
    assertTrue(s.condition(RuntimeStatus.Axis.ENGINE).isPresent());
    assertTrue(s.condition(RuntimeStatus.Axis.ADOPTION).isPresent());
    assertTrue(s.condition(RuntimeStatus.Axis.LEASE).isPresent());
    assertEquals(RuntimeStatus.ENGINE_HEALTHY, s.condition(RuntimeStatus.Axis.ENGINE).orElseThrow().status());
    assertEquals("CHAT", s.condition(RuntimeStatus.Axis.LEASE).orElseThrow().status());
    assertEquals(7L, s.condition(RuntimeStatus.Axis.ENGINE).orElseThrow().observedSpecVersion());
  }
}
