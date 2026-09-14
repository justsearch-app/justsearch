/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.navigate-to-surface} (slice 491 §9.D Phase E E3).
 *
 * <p>Gives the agent loop a tool-call form of navigation that complements the URL-emission
 * path. Where {@code URLExtractor} parses {@code justsearch://} URLs from the model's free
 * text, this Operation lets the agent invoke "navigate to surface X" explicitly via a
 * structured tool call. Both paths terminate in a {@link ShellAddress.Navigation} envelope
 * forwarded by {@link BackendIntentRouter} onto {@code /api/intent/stream}; the FE
 * {@code IntentRouter} consumes the envelope and activates the surface.
 *
 * <p>Args: {@code {"surfaceId": "core.<surface-id>"}}.
 *
 * <p>The handler does NOT call {@code SurfaceCatalog.findById} before dispatching — surface
 * resolution is FE-authoritative (the dual-router rule from tempdoc 487 §4.3). The FE's
 * IntentRouter rejects the navigation if the surface is unknown; the LLM gets feedback via
 * the {@code IntentDispatchResult.Forwarded} envelope's eventual outcome (or a structural
 * error if the surfaceId is malformed).
 */
public final class NavigateToSurfaceHandler implements OperationHandler {

  private static final Logger LOG = LoggerFactory.getLogger(NavigateToSurfaceHandler.class);

  private final Supplier<BackendIntentRouter> routerSupplier;
  private final Clock clock;

  public NavigateToSurfaceHandler(Supplier<BackendIntentRouter> routerSupplier) {
    this(routerSupplier, Clock.systemUTC());
  }

  public NavigateToSurfaceHandler(Supplier<BackendIntentRouter> routerSupplier, Clock clock) {
    this.routerSupplier = Objects.requireNonNull(routerSupplier, "routerSupplier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    // Test-friendly fallback (no upstream provenance available); production callers
    // route through the F6 overload below which carries the real transport.
    return execute(
        argumentsJson,
        io.justsearch.app.services.intent.EngineProvenance.invocation(
            engineContext, io.justsearch.agent.api.registry.ExecutorTag.UI, Instant.now(clock), Optional.empty()), engineContext);
  }

  @Override
  public OperationResult execute(String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    String surfaceId;
    try {
      JsonNode node = HandlerJson.MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
      JsonNode sidNode = node.get("surfaceId");
      if (sidNode == null || !sidNode.isTextual() || sidNode.asText().isBlank()) {
        return OperationResult.failure(
            "Missing required argument: surfaceId", "BAD_REQUEST", Map.of(), false);
      }
      surfaceId = sidNode.asText();
    } catch (RuntimeException e) {
      return OperationResult.failure(
          "Invalid args JSON: " + e.getMessage(), "BAD_REQUEST", Map.of(), false);
    }

    SurfaceRef target = new SurfaceRef(surfaceId);
    // F6: tag the dispatched Navigation Intent with the actual upstream transport
    // from the InvocationProvenance instead of hardcoding AGENT_LOOP. UI-initiated
    // invocations of this Operation now flow through the lattice as their real
    // tier (PALETTE / BUTTON / RAIL → TRUSTED), not misrepresented as AGENT_LOOP.
    TransportTag transport = provenance.transport();
    Intent intent =
        new Intent(new ShellAddress.Navigation(target, StateSnapshot.empty()), transport);

    BackendIntentRouter router = routerSupplier.get();
    if (router == null) {
      return OperationResult.failure(
          "Backend intent router unavailable",
          "SERVICE_UNAVAILABLE",
          Map.of("surfaceId", surfaceId),
          true);
    }
    try {
      IntentDispatchResult result = router.dispatch(intent, provenance, engineContext);
      Map<String, Object> structured = new LinkedHashMap<>();
      structured.put("surfaceId", surfaceId);
      if (result instanceof IntentDispatchResult.Forwarded f) {
        structured.put("envelopeId", f.envelopeId());
        structured.put("outcome", "forwarded");
      } else if (result instanceof IntentDispatchResult.Dispatched d) {
        structured.put("outcome", "dispatched");
        structured.put("success", d.result().success());
      }
      return OperationResult.success("Navigation dispatched to " + surfaceId, structured);
    } catch (RuntimeException e) {
      LOG.debug("NavigateToSurfaceHandler dispatch failed for {}: {}", surfaceId, e.getMessage());
      return OperationResult.failure(
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
          "DISPATCH_FAILED",
          Map.of("surfaceId", surfaceId),
          false);
    }
  }
}
