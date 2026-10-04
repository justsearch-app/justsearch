/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassRegistry;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.AdvisoryRecord;
import io.justsearch.app.observability.advisory.PendingAuthorizationAdvisoryProjector;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.core.execution.TestEngineExecutors;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the "consumed pending still advertised on the SSE snapshot" defect: a
 * fresh subscriber to the {@code authorization.pending} advisory stream saw "Approval requested"
 * for pendings that were already approved/expired, because {@link AdvisoryLog} is a pure
 * append-only ring buffer of creation-time projections that {@link
 * AuthorizationController#handleApprove} never reconciled against {@link
 * PendingAuthorizationStore}'s live set on consume.
 */
@DisplayName("AdvisoryStreamController — snapshot liveness")
final class AdvisoryStreamControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static final Instant T0 = Instant.parse("2026-07-15T10:00:00Z");

  private static AdvisoryChangeRegistry changeRegistry() {
    var classRegistry =
        AdvisoryClassRegistry.builder()
            .register(new PendingAuthorizationAdvisoryProjector())
            .build();
    return new AdvisoryChangeRegistry(classRegistry, Clock.fixed(T0, ZoneOffset.UTC));
  }

  /** Mirrors the shape {@link PendingAuthorizationAdvisoryProjector#project} emits. */
  private static AdvisoryRecord advisoryFor(String pendingId) {
    return new AdvisoryRecord(
        PendingAuthorizationAdvisoryProjector.CLASS_ID.value(),
        PendingAuthorizationAdvisoryProjector.CLASS_ID.value() + ":" + pendingId,
        T0,
        "REQUIRES_ACK",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(
            "pendingId", pendingId,
            "operationId", "core.ingest-files",
            "riskTier", "MEDIUM",
            "gateBehavior", "TYPED_CONFIRM"));
  }

  @SuppressWarnings("unchecked")
  private static List<AdvisoryRecord> advisoriesFrom(Map<String, Object> extras) {
    return (List<AdvisoryRecord>) extras.get("advisories");
  }

  @Test
  @DisplayName(
      "with no liveness filter supplied (the default, and the exact pre-fix constructor shape),"
          + " a consumed pending is STILL replayed — documents the accept-all default"
          + " intentionally used by classes with no live-store notion (operation.completed,"
          + " health.recoverable), and reproduces the live defect this class used to inherit"
          + " from that default before ResourceApiModule was given an explicit filter")
  void withoutLiveFilterConsumedPendingIsStillReplayed() {
    AdvisoryLog log = new AdvisoryLog();
    PendingAuthorizationStore store =
        new PendingAuthorizationStore(Clock.fixed(T0, ZoneOffset.UTC), Duration.ofMinutes(5));
    String consumedId =
        store.create(
            "core.ingest-files",
            "{}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "test",
            null,
            TransportTag.MCP,
            TestRequestContexts.mcp("advisory"),
            TestRequestContexts.provenance(TestRequestContexts.mcp("advisory"), io.justsearch.agent.api.registry.ExecutorTag.AGENT));
    log.append(advisoryFor(consumedId));
    assertTrue(store.consume(consumedId).isPresent(), "precondition: pending was consumed");

    // Same 4-arg constructor production used before the fix (still used today by the
    // operation.completed / health.recoverable controllers, which have no live-store notion).
    AdvisoryStreamController controller =
        new AdvisoryStreamController(
            processExecutors,
              PendingAuthorizationAdvisoryProjector.CLASS_ID, log, changeRegistry(), mock(Telemetry.class));
    try {
      List<AdvisoryRecord> advisories = advisoriesFrom(controller.snapshotExtras());
      // Confirms the failure mode this whole test class exists to fix: the accept-all default
      // by itself does NOT filter a consumed pending. This is the exact assertion that failed
      // (AssertionFailedError: expected <true> but was <false>) when run against the unfixed
      // ResourceApiModule wiring — captured verbatim as the RED baseline before the fix. Prod
      // no longer relies on this default for authorization.pending (see the next two tests);
      // it stays accept-all here only for classes that have nothing to filter against.
      assertTrue(
          advisories.stream().anyMatch(r -> consumedId.equals(r.classExtras().get("pendingId"))),
          "documents the pre-fix defect: a consumed pending IS still replayed without a filter");
    } finally {
      controller.shutdown();
    }
  }

  @Test
  @DisplayName(
      "with a store-backed liveness filter, a consumed pending is excluded and a live one is"
          + " kept")
  void withLiveFilterConsumedPendingExcludedLiveKept() {
    AdvisoryLog log = new AdvisoryLog();
    PendingAuthorizationStore store =
        new PendingAuthorizationStore(Clock.fixed(T0, ZoneOffset.UTC), Duration.ofMinutes(5));

    String consumedId =
        store.create(
            "core.ingest-files",
            "{}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "test",
            null,
            TransportTag.MCP,
            TestRequestContexts.mcp("advisory"),
            TestRequestContexts.provenance(TestRequestContexts.mcp("advisory"), io.justsearch.agent.api.registry.ExecutorTag.AGENT));
    String liveId =
        store.create(
            "core.reindex-library",
            "{}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "test",
            null,
            TransportTag.MCP,
            TestRequestContexts.mcp("advisory"),
            TestRequestContexts.provenance(TestRequestContexts.mcp("advisory"), io.justsearch.agent.api.registry.ExecutorTag.AGENT));
    log.append(advisoryFor(consumedId));
    log.append(advisoryFor(liveId));
    assertTrue(store.consume(consumedId).isPresent(), "precondition: pending was consumed");

    // Mirrors ResourceApiModule's production wiring: filter checks classExtras.pendingId
    // against the live store.
    AdvisoryStreamController controller =
        new AdvisoryStreamController(
            processExecutors,
              PendingAuthorizationAdvisoryProjector.CLASS_ID,
            log,
            changeRegistry(),
            mock(Telemetry.class),
            (AdvisoryRecord record) -> {
              Object pendingId = record.classExtras().get("pendingId");
              return pendingId instanceof String id && store.peek(id).isPresent();
            });
    try {
      List<AdvisoryRecord> advisories = advisoriesFrom(controller.snapshotExtras());
      assertEquals(1, advisories.size());
      assertEquals(liveId, advisories.get(0).classExtras().get("pendingId"));
    } finally {
      controller.shutdown();
    }
  }

  @Test
  @DisplayName("an expired-but-unswept pending is also excluded (not just consumed)")
  void expiredPendingExcluded() {
    AdvisoryLog log = new AdvisoryLog();
    Instant createdAt = T0;
    Duration ttl = Duration.ofMinutes(5);
    // A clock that starts at creation time, then jumps forward past the TTL for peek() —
    // simulating a pending that was gated-then-abandoned and swept lazily, not consumed.
    java.util.concurrent.atomic.AtomicReference<Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(createdAt);
    Clock movableClock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    PendingAuthorizationStore store = new PendingAuthorizationStore(movableClock, ttl);
    String expiredId =
        store.create(
            "core.ingest-files",
            "{}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "test",
            null,
            TransportTag.MCP,
            TestRequestContexts.mcp("advisory"),
            TestRequestContexts.provenance(TestRequestContexts.mcp("advisory"), io.justsearch.agent.api.registry.ExecutorTag.AGENT));
    log.append(advisoryFor(expiredId));
    now.set(createdAt.plus(ttl).plusSeconds(1));

    AdvisoryStreamController controller =
        new AdvisoryStreamController(
            processExecutors,
              PendingAuthorizationAdvisoryProjector.CLASS_ID,
            log,
            changeRegistry(),
            mock(Telemetry.class),
            (AdvisoryRecord record) -> {
              Object pendingId = record.classExtras().get("pendingId");
              return pendingId instanceof String id && store.peek(id).isPresent();
            });
    try {
      List<AdvisoryRecord> advisories = advisoriesFrom(controller.snapshotExtras());
      assertTrue(advisories.isEmpty(), "an expired pending must not appear in the snapshot");
    } finally {
      controller.shutdown();
    }
  }
}
