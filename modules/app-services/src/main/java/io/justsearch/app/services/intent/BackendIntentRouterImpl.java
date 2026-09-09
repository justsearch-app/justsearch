/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.IntentSourceRef;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.intent.IntentEnvelopeEvent;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend half of the dual-{@code IntentRouter} topology (tempdoc 487 §4.3).
 *
 * <p>Resolves {@link ShellAddress.Invocation} envelopes via the
 * {@link OperationDispatcher}; broadcasts {@link ShellAddress.Navigation} envelopes onto
 * the always-on {@code /api/intent/stream} channel for the FE
 * {@code IntentRouter} to consume.
 *
 * <p>Trust evaluation (the {@code (SourceTier × RiskTier) → GateBehavior} lattice
 * specified in tempdoc §4.4) runs inside the {@code OperationDispatcher} implementation,
 * not at this boundary. The router treats every well-formed envelope as admittable; the
 * lattice decides whether it auto-fires, surfaces a confirm gate, or is denied. This
 * routing-vs-gating separation is the §4.3 handoff rule.
 *
 * <p>Envelope ids are server-assigned in {@link #forwardToFrontend} as
 * {@code ie-<uuid-no-dashes>}; they are stable per dispatch call and survive ring-buffer
 * replay on FE reconnect so the FE's LRU dedup gates on them. The id generator can be
 * overridden for deterministic tests.
 */
public final class BackendIntentRouterImpl implements BackendIntentRouter {

  private static final Logger LOG = LoggerFactory.getLogger(BackendIntentRouterImpl.class);

  private final OperationCatalog operationCatalog;
  private final OperationDispatcher operationDispatcher;
  private final IntentSourceCatalog intentSourceCatalog;
  private final IntentEnvelopeChangeRegistry envelopeRegistry;

  /**
   * Tempdoc 550 Slice F1 (Outcome face) — nullable. When present, every forwarded
   * Navigation is recorded as a {@link NavigationHistoryEntry} so the action is
   * reviewable like an Operation invocation. Null in legacy/test wiring that predates
   * the navigation ledger; recording is skipped silently then (mirrors the
   * trust-deps-absent escape hatch in {@code OperationExecutorImpl}).
   */
  private final NavigationHistoryStore navigationHistory;

  /**
   * Tempdoc 550 WA-4 (audit-only) — both nullable. When present, {@link #forwardToFrontend}
   * consults the (SourceTier × Surface.RiskTier) lattice when broadcasting a Navigation envelope
   * so the gate decision is COMPUTED and observable, but enforcement is intentionally NOT wired:
   * a non-AUTO result only emits a forward-compat WARN breadcrumb — the navigation is STILL
   * broadcast (suppressing it while returning {@code Forwarded} would report a navigation that did
   * not happen). Null in legacy/test wiring → the gate is not consulted at all (prior behavior).
   * With every surface at the default RiskTier.LOW, every cell is AUTO, so this is behavior-neutral
   * until a surface declares higher risk and the suppress + FE confirm-ceremony enforcement lands.
   */
  private final io.justsearch.agent.api.registry.TrustEvaluator trustEvaluator;

  private final io.justsearch.agent.api.registry.SurfaceCatalog surfaceCatalog;

  private final Supplier<String> envelopeIdSupplier;

  /**
   * Back-compat constructor (no navigation ledger): server-assigned UUID-derived
   * envelope ids, navigation recording disabled.
   */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry) {
    this(
        operationCatalog,
        operationDispatcher,
        intentSourceCatalog,
        envelopeRegistry,
        null,
        BackendIntentRouterImpl::defaultEnvelopeId);
  }

  /**
   * Production constructor: server-assigned UUID-derived envelope ids + navigation
   * ledger recording (tempdoc 550 Slice F1).
   */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry,
      NavigationHistoryStore navigationHistory) {
    this(
        operationCatalog,
        operationDispatcher,
        intentSourceCatalog,
        envelopeRegistry,
        navigationHistory,
        BackendIntentRouterImpl::defaultEnvelopeId);
  }

  /** Test constructor: injected id supplier for deterministic envelope ids. */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry,
      Supplier<String> envelopeIdSupplier) {
    this(
        operationCatalog,
        operationDispatcher,
        intentSourceCatalog,
        envelopeRegistry,
        null,
        envelopeIdSupplier);
  }

  /**
   * Navigation-ledger constructor. Delegates to the canonical with no navigation gating
   * (trustEvaluator + surfaceCatalog null).
   */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry,
      NavigationHistoryStore navigationHistory,
      Supplier<String> envelopeIdSupplier) {
    this(
        operationCatalog,
        operationDispatcher,
        intentSourceCatalog,
        envelopeRegistry,
        navigationHistory,
        envelopeIdSupplier,
        null,
        null);
  }

  /**
   * Production constructor (tempdoc 550 WA-4): navigation ledger + navigation gating, with
   * the default server-assigned envelope-id supplier.
   */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry,
      NavigationHistoryStore navigationHistory,
      io.justsearch.agent.api.registry.TrustEvaluator trustEvaluator,
      io.justsearch.agent.api.registry.SurfaceCatalog surfaceCatalog) {
    this(
        operationCatalog,
        operationDispatcher,
        intentSourceCatalog,
        envelopeRegistry,
        navigationHistory,
        BackendIntentRouterImpl::defaultEnvelopeId,
        trustEvaluator,
        surfaceCatalog);
  }

  /**
   * Canonical constructor (tempdoc 550 WA-4). {@code navigationHistory}, {@code
   * trustEvaluator}, and {@code surfaceCatalog} may all be null. When both trustEvaluator and
   * surfaceCatalog are present, {@link #forwardToFrontend} gates Navigation through the
   * (SourceTier × Surface.RiskTier) lattice.
   */
  public BackendIntentRouterImpl(
      OperationCatalog operationCatalog,
      OperationDispatcher operationDispatcher,
      IntentSourceCatalog intentSourceCatalog,
      IntentEnvelopeChangeRegistry envelopeRegistry,
      NavigationHistoryStore navigationHistory,
      Supplier<String> envelopeIdSupplier,
      io.justsearch.agent.api.registry.TrustEvaluator trustEvaluator,
      io.justsearch.agent.api.registry.SurfaceCatalog surfaceCatalog) {
    this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog");
    this.operationDispatcher = Objects.requireNonNull(operationDispatcher, "operationDispatcher");
    this.intentSourceCatalog = Objects.requireNonNull(intentSourceCatalog, "intentSourceCatalog");
    this.envelopeRegistry = Objects.requireNonNull(envelopeRegistry, "envelopeRegistry");
    this.navigationHistory = navigationHistory;
    this.envelopeIdSupplier = Objects.requireNonNull(envelopeIdSupplier, "envelopeIdSupplier");
    this.trustEvaluator = trustEvaluator;
    this.surfaceCatalog = surfaceCatalog;
  }

  @Override
  public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance, EngineContext engineContext) {
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(provenance, "provenance");
    return switch (intent.address()) {
      case ShellAddress.Invocation inv -> dispatchInvocation(inv, provenance, engineContext);
      // Navigation and Query (548 S4-A) are both forwarded verbatim to the FE intent
      // stream; the FE IntentRouter resolves Query to a search-surface activation.
      case ShellAddress.Navigation ignored -> forwardToFrontend(intent, provenance);
      case ShellAddress.Query ignored -> forwardToFrontend(intent, provenance);
      case ShellAddress.Answer ignored -> forwardToFrontend(intent, provenance);
    };
  }

  private IntentDispatchResult dispatchInvocation(
      ShellAddress.Invocation invocation, InvocationProvenance provenance, EngineContext engineContext) {
    OperationRef ref = invocation.target();
    Operation op =
        operationCatalog
            .findById(ref)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown OperationRef in Intent.invoke: " + ref.value()));
    // Slice 487 §4.4: thread the Invocation's confirmation token (optional) to the
    // dispatcher's trust-lattice-aware 4-arg overload. When present, the token
    // satisfies non-AUTO gate behaviors (INLINE_CONFIRM / TYPED_CONFIRM). When
    // absent and the lattice produces a non-AUTO gate, the dispatcher throws
    // ConfirmationRequiredException for the caller to surface elicitation UX.
    OperationResult result =
        operationDispatcher.dispatch(
            op, invocation.argsJson(), provenance, invocation.confirmationToken(), engineContext);
    return new IntentDispatchResult.Dispatched(result);
  }

  private IntentDispatchResult forwardToFrontend(Intent intent, InvocationProvenance provenance) {
    String envelopeId = envelopeIdSupplier.get();
    java.util.Optional<io.justsearch.agent.api.registry.IntentSource> source =
        intentSourceCatalog.findByTransport(intent.transport());
    IntentSourceRef sourceId =
        source
            .map(io.justsearch.agent.api.registry.IntentSource::id)
            .orElseGet(
                () -> {
                  // No registered source for this transport. Fall back to a synthetic
                  // sentinel id so the envelope remains well-formed for FE consumers,
                  // but log loudly because this indicates an unregistered ingress —
                  // a Pass-9 commitment 4 violation.
                  LOG.warn(
                      "No IntentSource registered for transport {}; emitting envelope {} with"
                          + " sentinel sourceId. This indicates an unregistered ingress.",
                      intent.transport(),
                      envelopeId);
                  return new IntentSourceRef("core.unregistered-transport");
                });

    // Tempdoc 550 WA-4 (audit-only): Navigation traverses the same (SourceTier × RiskTier)
    // lattice as an Operation invocation, keyed on the TARGET surface's RiskTier — so the gate
    // decision is COMPUTED and observable. Enforcement is intentionally NOT wired: with every
    // surface at the default LOW, every cell is AUTO, so there is no non-AUTO consumer today,
    // and suppressing the broadcast for a non-AUTO gate while still returning Forwarded would
    // report a navigation that did not happen (a false "navigated" receipt). A non-AUTO result
    // therefore only emits a forward-compat WARN breadcrumb here; the navigation is still
    // broadcast. Real enforcement (suppress + a confirm ceremony for the gated nav) lands with
    // the first higher-risk surface and its FE recovery affordance.
    if (trustEvaluator != null
        && surfaceCatalog != null
        && intent.address() instanceof ShellAddress.Navigation navAddr) {
      io.justsearch.agent.api.registry.SourceTier sourceTier =
          source
              .map(io.justsearch.agent.api.registry.IntentSource::sourceTier)
              .orElse(io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
      io.justsearch.agent.api.registry.RiskTier surfaceRisk =
          surfaceCatalog
              .findById(navAddr.target())
              .map(io.justsearch.agent.api.registry.Surface::riskTier)
              .orElse(io.justsearch.agent.api.registry.RiskTier.LOW);
      if (trustEvaluator.evaluate(sourceTier, surfaceRisk)
          != io.justsearch.agent.api.registry.GateBehavior.AUTO) {
        LOG.warn(
            "Navigation to surface {} would gate (sourceTier={}, surfaceRisk={}) but navigation"
                + " gating is not yet ENFORCED (no higher-risk surface / FE recovery exists);"
                + " broadcasting anyway. Envelope {}.",
            navAddr.target().value(),
            sourceTier,
            surfaceRisk,
            envelopeId);
      }
    }

    IntentEnvelopeEvent event = new IntentEnvelopeEvent(envelopeId, intent, provenance, sourceId);
    envelopeRegistry.broadcast(event);
    // Tempdoc 550 Slice F1 (Outcome face): record the forwarded Navigation as an
    // attributed action. Navigation has no backend success/failure axis (the surface
    // activation happens FE-side), so the record captures attribution, not an outcome.
    if (intent.address() instanceof ShellAddress.Navigation nav) {
      recordNavigation(envelopeId, nav, sourceId, provenance);
    }
    return new IntentDispatchResult.Forwarded(envelopeId);
  }

  /** Append a Navigation to the audit ledger (no-op when the ledger is unwired). */
  private void recordNavigation(
      String envelopeId,
      ShellAddress.Navigation nav,
      IntentSourceRef sourceId,
      InvocationProvenance provenance) {
    if (navigationHistory == null) {
      return;
    }
    NavigationHistoryEntry entry =
        new NavigationHistoryEntry(
            envelopeId, nav.target().value(), sourceId.value(), provenance.occurredAt(), provenance);
    // Tempdoc 550 F5: appending fans the navigation into the one action-event log via the store's
    // append-listener (wired in OperationSubstrateInit) — the live ledger stream updates without a
    // separate broadcast call here, so the store and the ledger cannot diverge.
    navigationHistory.append(entry);
  }


  private static String defaultEnvelopeId() {
    return "ie-" + UUID.randomUUID().toString().replace("-", "");
  }
}
