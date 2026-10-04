/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Invocation-side provenance: <em>who or what triggered this dispatch / emission</em>.
 *
 * <p>Per slice 490 §4.B (substrate factoring): the cross-cutting answer to "who fired
 * this?" needed by every emission class — Operation invocations
 * ({@code OperationHistoryEntry}), LLM-emitted URLs (slice 487 chat-receipt UI),
 * URL-substrate routes (slice 489 §8), proactive advisories (slice 490 advisory
 * Resources), and chat-substrate stream events (slice 491 §8). Standalone primitive
 * resolving slice 489 §17 Open Question 1 toward "independent sibling slice consumed by
 * 487 + 489 + 490 + 491."
 *
 * <p>Distinguished from declaration-side {@link Provenance} (which answers
 * <em>where does this Operation come from?</em>): declaration-side is per-registry-entry
 * static; invocation-side is per-dispatch dynamic. Both records may coexist on the same
 * emission event (the dispatched Operation's {@code Operation.provenance} declares the
 * Operation's origin; this record describes how today's invocation arrived).
 *
 * <p>Per slice 489 §13 anti-pattern #4: this record carries no policy gates. It is
 * descriptive metadata. Transport-specific gating overlays (e.g., "LLM-emitted
 * destructive ops require typed confirmation regardless of {@code ConfirmStrategy}") are
 * resolver-decoration concerns, not flags on this record or on
 * {@link ConfirmStrategy}.
 *
 * <p>Field set is v1 — minimum to satisfy the four consumer clusters. Future additions
 * (e.g., {@code ChatTurnRef origin} for slice 491 conversation correlation;
 * {@code OperationInvocationId id} for replay/audit cross-reference) are additive and
 * land when their consumer arrives.
 *
 * <p>{@code correlationId} (tempdoc 561 P-A1) is the optional cross-domain join key the
 * v1 docstring above foresaw ("conversation correlation / replay-audit cross-reference,
 * land when their consumer arrives"). The agent loop stamps its {@code sessionId} here at
 * the tool-call dispatch site ({@code AgentToolDispatcher#dispatchToolCall}); the value
 * then rides the same provenance object the router forwards to the executor, lands on
 * {@code OperationHistoryEntry.provenance} via {@code emitHistory}, and lets the
 * cross-domain {@code ActionEvent} ledger be filtered to a single agent session — the
 * consumer that fixes the empty-History defect (561 §9, P-B1). {@link Optional#empty()}
 * for every non-correlated dispatch (UI buttons, URL routes, system-internal); only the
 * agent loop (and future presence-axis triggers, P-D2) supplies it. Consumer-first per
 * 561 §8.1 invariant 3: this field ships with its History-projection consumer, not ahead
 * of it.
 *
 * <p>Architectural boundary: {@code app-agent-api} has no Jackson databind dependency
 * (annotations only, per {@code Interface.java:16-19} §E.5). This record uses no
 * Jackson annotations because every field has a default Jackson encoding (enum,
 * {@code Optional<String>}, {@code Instant}).
 */
public record InvocationProvenance(
    TransportTag transport,
    ExecutorTag executor,
    Optional<String> initiator,
    Instant occurredAt,
    Optional<String> signedIntentToken,
    Optional<String> correlationId) {

  /** Structural projection; the dispatch authority validates the registered source tier. */
  public static InvocationProvenance fromEngineContext(
      io.justsearch.core.context.EngineContext context, ExecutorTag executor,
      Instant occurredAt, Optional<String> signedIntentToken) {
    Objects.requireNonNull(context, "context");
    return new InvocationProvenance(TransportTag.valueOf(context.transport()), executor,
        Optional.of(context.clientId()), occurredAt, signedIntentToken, context.sessionId());
  }

  public InvocationProvenance {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(signedIntentToken, "signedIntentToken");
    Objects.requireNonNull(correlationId, "correlationId");
    initiator.ifPresent(
        s -> {
          if (s.isBlank()) {
            throw new IllegalArgumentException("initiator, when present, must be non-blank");
          }
        });
    signedIntentToken.ifPresent(
        s -> {
          if (s.isBlank()) {
            throw new IllegalArgumentException(
                "signedIntentToken, when present, must be non-blank");
          }
        });
    correlationId.ifPresent(
        s -> {
          if (s.isBlank()) {
            throw new IllegalArgumentException(
                "correlationId, when present, must be non-blank");
          }
        });
  }

  /**
   * Pre-561 5-arg compatibility constructor. Tempdoc 561 P-A1 added the
   * {@code correlationId} component (the cross-domain join key) as the canonical
   * 6th. This delegating constructor preserves every pre-561 callsite that built
   * the 5-arg {@code (transport, executor, initiator, occurredAt, signedIntentToken)}
   * shape (token-carrying capsule dispatch) — correlationId defaults absent.
   */
  public InvocationProvenance(
      TransportTag transport,
      ExecutorTag executor,
      Optional<String> initiator,
      Instant occurredAt,
      Optional<String> signedIntentToken) {
    this(transport, executor, initiator, occurredAt, signedIntentToken, Optional.empty());
  }

  /**
   * Pre-slice-487 4-arg compatibility constructor. Slice 487 §4.1 + Appendix B.10:
   * adds the {@code signedIntentToken} component for OWASP "verifiable intent
   * capsule" forward compatibility — V1 dispatch does not consume the token. This
   * delegating constructor lets all existing callsites (the factories below plus
   * any external direct callers) keep their pre-487 shape; new callers that want
   * to attach a token use the canonical 5-arg constructor directly.
   */
  public InvocationProvenance(
      TransportTag transport,
      ExecutorTag executor,
      Optional<String> initiator,
      Instant occurredAt) {
    this(transport, executor, initiator, occurredAt, Optional.empty(), Optional.empty());
  }

  /**
   * Convenience: system-internal trigger with no initiator. Used as the default fallback
   * when a dispatch has no caller-supplied provenance context (e.g., legacy callsites of
   * the 2-arg {@link OperationDispatcher#dispatch(Operation, String, EngineContext)} overload).
   */
  public static InvocationProvenance systemInternal(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.SYSTEM_INTERNAL, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /**
   * Convenience: HTTP button click from the local UI shell. Used by the
   * {@code /api/operations/{id}/invoke} endpoint as the default transport when the
   * caller does not supply richer transport context.
   */
  public static InvocationProvenance uiButton(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.BUTTON, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /**
   * Convenience: agent-loop tool-call dispatch. Used by {@code AgentLoopService} (or its
   * encapsulating {@code ToolIteratingShape} adapter per slice 491) when the loop
   * dispatches an Operation as a tool call.
   *
   * <p>Pass-8 follow-up note: this factory has no production callsite in this branch
   * — it's forward-compat for slice 491's {@code ToolIteratingShape} adapter. Kept
   * here so the agent-loop migration has a stable factory to consume (rather than
   * each adapter reaching for the canonical constructor directly).
   */
  public static InvocationProvenance agentLoop(Instant occurredAt) {
    return agentLoop(occurredAt, Optional.empty());
  }

  /**
   * Tempdoc 561 P-A1: agent-loop tool-call dispatch carrying the loop's
   * {@code sessionId} as the cross-domain {@code correlationId}. This is the
   * production stamping site ({@code AgentToolDispatcher#dispatchToolCall}) — every
   * agent-dispatched Operation rides this provenance, so the {@code ActionEvent}
   * ledger row it produces (via {@code emitHistory}) carries the session and the
   * agent History tab can project itself by filtering on it (P-B1).
   */
  public static InvocationProvenance agentLoop(
      Instant occurredAt, Optional<String> correlationId) {
    return new InvocationProvenance(
        TransportTag.AGENT_LOOP, ExecutorTag.AGENT, Optional.empty(), occurredAt,
        Optional.empty(), correlationId);
  }

  // ----- Slice 489 §17.5 — FE→backend transport stamping factories -----
  //
  // Per the slice 489 round-5 resolution: the FE supplies an `X-JustSearch-Transport`
  // header at /api/operations/{id}/invoke; OperationsController maps the header value
  // to one of these factories. Validation against caller trust tier remains the
  // dispatcher's responsibility (OperationExecutorImpl.validateProvenance).

  /** URL pasted/typed in the browser bar, arrived via popstate, or hydrated from a bookmark. */
  public static InvocationProvenance urlBar(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.URL_BAR, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /** OS-level deep-link (Tauri, {@code justsearch://...} from another app). */
  public static InvocationProvenance urlDeeplink(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.URL_DEEPLINK, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /** Command palette invocation. */
  public static InvocationProvenance palette(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.PALETTE, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /** Rail navigation click. */
  public static InvocationProvenance rail(Instant occurredAt) {
    return new InvocationProvenance(
        TransportTag.RAIL, ExecutorTag.UI, Optional.empty(), occurredAt);
  }

  /** External MCP tool invocation; initiator carries the MCP client id when known. */
  public static InvocationProvenance mcp(Instant occurredAt, Optional<String> initiator) {
    return new InvocationProvenance(TransportTag.MCP, ExecutorTag.UI, initiator, occurredAt);
  }

  /**
   * Generic factory mapping any {@link TransportTag} to an {@code InvocationProvenance}.
   *
   * <p>Used by {@code OperationsController} when the FE supplies a transport hint that
   * doesn't match one of the named convenience factories. The {@code executor} defaults
   * to {@link ExecutorTag#UI} because every transport that arrives via
   * {@code /api/operations/{id}/invoke} is a UI-side ingress; agent-loop dispatches
   * (AGENT executor) and CLI dispatches (CLI executor) use their own provenance
   * construction paths upstream of the controller.
   */
  public static InvocationProvenance fromTransport(
      TransportTag transport, Optional<String> initiator, Instant occurredAt) {
    Objects.requireNonNull(transport, "transport");
    return new InvocationProvenance(transport, ExecutorTag.UI, initiator, occurredAt);
  }
}
