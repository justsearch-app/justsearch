/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKind;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.services.intent.EngineProvenance;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.agent.api.registry.TrustGateDeniedException;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.app.observability.advisory.OperationCompletionEvent;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Trust-tier-aware {@link OperationDispatcher} implementation.
 *
 * <p>Per tempdoc 429 §C decision A: substrate types live in {@code app-agent-api},
 * behavior lives in {@code app-services}. This is the behavioral counterpart to the
 * substrate-side {@link OperationDispatcher} interface.
 *
 * <p>Per §A.5 + §B.D + §C.A.5: the trust-tier switch routes CORE through the handler
 * registry, TRUSTED_PLUGIN identically (V1's trust model is "you wrote it, or you
 * know who did" — equivalence is intentional, not dead code), UNTRUSTED_PLUGIN throws
 * with a V1.5 sandbox doc pointer.
 *
 * <p>Per §E.3: {@link #undo(Operation, String, EngineContext)} checks
 * {@code op.policy().undoSupported()} before delegating; operations without undo
 * support fail fast with a typed denial, never reaching the handler. Per tempdoc 875 §C.7 it
 * then meets the SAME trust lattice a forward dispatch meets — a reversal is an operation and
 * inherits the risk class of its forward form.
 */
public final class OperationExecutorImpl implements OperationDispatcher {
  private final OperationAttemptRunner attempts;
  private static void validateEngineContext(
      EngineContext engineContext, InvocationProvenance provenance) {
    var projected = EngineProvenance.invocation(engineContext, provenance.executor(),
        provenance.occurredAt(), provenance.signedIntentToken());
    if (!projected.equals(provenance)) {
      throw new IllegalArgumentException("Invocation provenance disagrees with Engine context");
    }
  }

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(OperationExecutorImpl.class);


  private final HandlerRegistry handlers;
  // Slice 444b: optional callback that receives one entry per completed dispatch.
  // Null in legacy/test wiring; non-null when HeadAssembly injects the
  // OperationHistoryStore + ChangeRegistry pair.
  private final Consumer<OperationHistoryEntry> historyEmitter;
  // Slice 490 §6.3 + Group B2 follow-up: routing table mapping advisory-class
  // ResourceRef → per-class emitter. Empty in legacy/test wiring; populated when
  // HeadAssembly injects the advisory substrate. When an Operation declares
  // {@code OperationPolicy.advisoryClass} present, the executor looks up the emitter
  // by ResourceRef and publishes the event. Future advisory classes register
  // additional entries here without touching the executor.
  private final Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters;
  private final Clock clock;
  // Slice 487 §4.4 + tempdoc 550 thesis III: the ONE intent-gate computation (source-tier
  // derivation + (SourceTier × RiskTier) lattice + Global Hard Stop override). Built from the
  // injected TrustEvaluator + IntentSourceCatalog; null in legacy/test wiring (deps absent), in
  // which case the dispatcher skips the lattice (backward compat). When present (production wiring
  // via HeadAssembly) the dispatcher enforces the gate between validateProvenance and
  // inputValidator.validate, and the SAME instance is shared with the Preview endpoint so a
  // preview can never disagree with enforcement (the F1 drift class is structurally impossible).
  private final IntentGateEvaluator intentGateEvaluator;
  private final java.util.function.Function<RequiredCapability, Boolean> capabilityResolver;
  // Tempdoc 550 Slice A1 (Authorize face): optional consent-capsule verifier. Null in
  // legacy/test wiring. When present, a valid bound capsule in the confirmation token
  // satisfies a non-AUTO gate with cryptographic proof of user approval for THIS exact
  // (operation, args) — ADDITIVELY, alongside the legacy non-blank-token path that
  // un-migrated callers (ActionButton, agent-loop) still use. Removing the legacy path
  // is the flagged wide migration (550 §Review-package C2), not done here.
  private final io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService;
  // Tempdoc 550 Outcome face: optional sink for trust-gate decisions. Null in legacy/test
  // wiring. When present, enforceTrustLattice emits one AuthorizationOutcomeEntry per non-AUTO
  // decision (GATED / DENIED / APPROVED) so the action ledger — and the 538 trust-firing audit
  // as a read-view of it — sees gate FIRINGS, not only completed-dispatch outcomes. ADDITIVE:
  // the emit is a pure side-effect; the gate's throw/return (fail-closed) semantics are
  // unchanged.
  private final Consumer<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>
      authorizationOutcomeEmitter;
  /**
   * Tempdoc 550 E2: wire the process-wide emergency stop the lattice consults. Forwarded into the
   * one {@link IntentGateEvaluator} (the verdict reflects it) so enforcement and the shared Preview
   * read the same hard-stop state. Late-bind, like other optional collaborators. No-op when the
   * lattice is absent (legacy/test wiring).
   */
  public void setGlobalHardStop(GlobalHardStop hardStop) {
    if (intentGateEvaluator != null) {
      intentGateEvaluator.setHardStopSignal(hardStop != null ? hardStop::isEngaged : null);
    }
  }

  /** The shared intent-gate computation (tempdoc 550 thesis III); null when the lattice is absent. */
  public IntentGateEvaluator intentGateEvaluator() {
    return intentGateEvaluator;
  }

  // Tempdoc 550 thesis IV: optional durable "allow-always" grants. When a durable grant covers
  // (operation, risk, sourceTier) AND the scope covers this invocation's arguments, a non-AUTO gate is
  // satisfied WITHOUT a fresh capsule. Late-bind (null = no durable grants wired; legacy/test paths).
  private volatile io.justsearch.app.services.intent.DurableGrantStore durableGrantStore;
  // Tempdoc 875 C.3: the argument-scope collaborator supplied WITH the store — never null while the
  // store is set, so durable grants cannot be wired without a scope.
  private volatile io.justsearch.app.services.intent.DurableGrantScope durableGrantScope;

  /**
   * Wire the durable allow-always grant store the gate consults (tempdoc 550 thesis IV) together with
   * the argument scope that bounds it (tempdoc 875 C.3).
   *
   * <p>The scope is REQUIRED: a durable grant is args-independent by model, which is only defensible
   * for an operation whose reach is fixed. Requiring the scope at the wiring site makes
   * "grants wired without a containment answer" unrepresentable rather than merely discouraged.
   */
  public void setDurableGrantStore(
      io.justsearch.app.services.intent.DurableGrantStore store,
      io.justsearch.app.services.intent.DurableGrantScope scope) {
    Objects.requireNonNull(scope, "scope");
    this.durableGrantScope = scope;
    this.durableGrantStore = store;
  }
  // Slice 3a-2-c Phase C: declarative input-schema validation. The validator
  // is process-singleton (cached compiled schemas keyed by OperationRef) so
  // creating it per-executor is fine; concurrent dispatch reuses compiled
  // schemas via ConcurrentHashMap.
  private final OperationInputSchemaValidator inputValidator =
      new OperationInputSchemaValidator();

  public OperationExecutorImpl(OperationAttemptRunner attempts, HandlerRegistry handlers) {
    this(attempts, handlers, null, Map.of(), Clock.systemUTC(), null, null);
  }

  /** Pre-slice-490 constructor — legacy callers compile unchanged with no advisory wiring. */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers, Consumer<OperationHistoryEntry> historyEmitter, Clock clock) {
    this(attempts, handlers, historyEmitter, Map.of(), clock, null, null);
  }

  /**
   * Pre-Group-B2 constructor — single-emitter form (slice 490 §6.3 v1 shape). The
   * single emitter is registered under
   * {@code core.advisory-operation-completed}'s ResourceRef. New callers should
   * use the {@code Map} form directly.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Consumer<OperationCompletionEvent> advisoryEmitter,
      Clock clock) {
    this(attempts,
        handlers,
        historyEmitter,
        advisoryEmitter == null
            ? Map.of()
            : Map.of(
                new ResourceRef("core.advisory-operation-completed"), advisoryEmitter),
        clock,
        null,
        null);
  }

  /**
   * Slice 490 Group B2 constructor — multi-emitter routing form. Pre-slice-487 callers
   * (no trust lattice). Delegates to the slice-487 6-arg form with null lattice deps.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock) {
    this(attempts, handlers, historyEmitter, advisoryEmitters, clock, null, null);
  }

  /**
   * Slice 487 §4.4 canonical constructor — accepts the trust lattice + source-tier
   * lookup. Production wiring (HeadAssembly) uses this form; the lattice fires
   * for every dispatch.
   *
   * <p>When {@code trustEvaluator} or {@code intentSourceCatalog} is null, the lattice
   * is skipped (legacy/test compat). Both must be present for the lattice to enforce.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog) {
    this(attempts, handlers, historyEmitter, advisoryEmitters, clock, trustEvaluator, intentSourceCatalog, null);
  }

  /**
   * Tempdoc 502 §B1: canonical constructor with capability resolver. When
   * {@code capabilityResolver} is non-null, the dispatcher checks each operation's
   * {@code requiredCapabilities} before invoking the handler. If any required
   * capability is unavailable, dispatch returns CAPABILITY_UNAVAILABLE without
   * reaching the handler.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver) {
    this(attempts,
        handlers,
        historyEmitter,
        advisoryEmitters,
        clock,
        trustEvaluator,
        intentSourceCatalog,
        capabilityResolver,
        null);
  }

  /**
   * Tempdoc 550 Slice A1 canonical constructor — adds the optional {@link
   * io.justsearch.app.services.intent.ConsentCapsuleService}. When non-null, a valid
   * consent capsule satisfies a non-AUTO gate (additive to the legacy non-blank-token
   * path). Production wiring (HeadAssembly / OperationSubstrateInit) uses this form.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService) {
    this(attempts,
        handlers,
        historyEmitter,
        advisoryEmitters,
        clock,
        trustEvaluator,
        intentSourceCatalog,
        capabilityResolver,
        capsuleService,
        null);
  }

  /**
   * Tempdoc 550 Outcome-face canonical constructor — adds the optional gate-decision sink
   * ({@code authorizationOutcomeEmitter}). When non-null, every non-AUTO trust-gate decision
   * is recorded so the action ledger / trust audit can read gate firings. Production wiring
   * (OperationSubstrateInit) uses this form.
   */
  public OperationExecutorImpl(OperationAttemptRunner attempts,
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService,
      Consumer<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>
          authorizationOutcomeEmitter) {
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.historyEmitter = historyEmitter;
    this.advisoryEmitters =
        advisoryEmitters == null ? Map.of() : Map.copyOf(advisoryEmitters);
    this.clock = Objects.requireNonNull(clock, "clock");
    // Tempdoc 550 thesis III: collapse source-tier derivation + lattice + hard-stop into the one
    // IntentGateEvaluator. Absent trust deps (legacy/test wiring) → null → lattice skipped.
    this.intentGateEvaluator =
        (trustEvaluator != null && intentSourceCatalog != null)
            ? new IntentGateEvaluator(trustEvaluator, intentSourceCatalog)
            : null;
    this.capabilityResolver = capabilityResolver;
    this.capsuleService = capsuleService;
    this.authorizationOutcomeEmitter = authorizationOutcomeEmitter;
  }

  @Override
  public OperationResult dispatch(Operation op, String argumentsJson, EngineContext engineContext) {
    // Project the caller's required context for direct UI dispatch without a signed intent.
    // Explicit executor/token callers use the canonical overload below.
    return dispatch(op, argumentsJson,
        EngineProvenance.invocation(engineContext,
            io.justsearch.agent.api.registry.ExecutorTag.UI, clock.instant(), Optional.empty()), engineContext);
  }

  @Override
  public OperationResult dispatch(
      Operation op, String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    return dispatch(op, argumentsJson, provenance, Optional.empty(), engineContext);
  }

  @Override
  public OperationResult dispatch(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      Optional<String> confirmationToken, EngineContext engineContext) {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(confirmationToken, "confirmationToken");
    validateEngineContext(engineContext, provenance);
    // Slice 490 follow-up — provenance integrity validation. A {@code TRUSTED_PLUGIN}
    // caller cannot spoof user-facing transports (BUTTON / URL_BAR / LLM_EMISSION etc.)
    // — only system-tier or plugin-tier transports are admissible. {@code CORE}
    // dispatch is unrestricted (callers within the head process are trusted to claim
    // their actual transport); {@code UNTRUSTED_PLUGIN} throws below regardless.
    validateProvenance(op, provenance);

    // Slice 487 §4.4: (SourceTier × RiskTier) → GateBehavior lattice. Runs between
    // validateProvenance (transport spoofing defense) and inputValidator.validate
    // (schema validation). Skipped silently when trust deps absent (legacy/test wiring).
    if (intentGateEvaluator != null) {
      enforceTrustLattice(op, argumentsJson, provenance, confirmationToken, engineContext);
    }

    return executeAttempt(op, argumentsJson, provenance, engineContext, null);
  }

  /** Acceptance precedes validation/effect; only durable completion produces history/advisories. */
  private OperationResult executeAttempt(Operation op, String argumentsJson,
      InvocationProvenance provenance, EngineContext context, String undoId) {
    Instant startedAt = clock.instant();
    // C2-3 adds keyed transport and the catalog's recordKind projection. The unkeyed audit
    // suppression is preserved here; keyed calls will always accept regardless of audit policy.
    OperationAttemptRunner.PreparedAttempt prepared = op.policy().audit() == AuditPolicy.NONE ? null
        : attempts.accept(new OperationAttemptRunner.Request(null,
            OperationDescriptor.invocation(OperationKind.OPERATION, op.id().value(), argumentsJson, undoId != null),
            context, provenance));
    if (prepared != null) {
      if (prepared.existing()) {
        return attempts.start(prepared, ignored -> {
          throw new IllegalStateException("Existing acceptance must never execute");
        }).response();
      }
      var unused = prepared.completion().thenAccept(row -> {
        boolean success = row.state() == OperationState.COMPLETE;
        OperationOutcome outcome = success
            ? (undoId == null ? OperationOutcome.SUCCESS : OperationOutcome.UNDONE) : OperationOutcome.FAILURE;
        Optional<String> undoExecution = success && undoId == null && op.policy().undoSupported()
            ? Optional.ofNullable(row.receipt()).map(io.justsearch.app.api.operations.OperationReceipt::executionId)
            : Optional.empty();
        emitHistory(op, startedAt, outcome, success ? null : row.failureReason(), provenance, undoExecution);
      });
    }
    try {
      OperationResult refusal = preflight(op, argumentsJson, undoId != null);
      if (refusal != null) {
        if (prepared != null) attempts.rejectBeforeStart(prepared, refusal.errorCode().orElse("HANDLER_FAILED"));
        else emitHistory(op, startedAt, OperationOutcome.FAILURE, refusal.message(), provenance, Optional.empty());
        return refusal;
      }
      if (prepared != null) {
        return attempts.start(prepared,
            handle -> invokeHandler(op, argumentsJson, provenance, context, undoId, handle)).response();
      }
      OperationExecution execution = invokeHandler(op, argumentsJson, provenance, context, undoId, null);
      var unused = execution.completion().thenAccept(result -> {
        OperationOutcome outcome = result.success()
            ? (undoId == null ? OperationOutcome.SUCCESS : OperationOutcome.UNDONE) : OperationOutcome.FAILURE;
        emitHistory(op, startedAt, outcome, null, provenance,
            result.success() && undoId == null && op.policy().undoSupported() ? result.executionId() : Optional.empty());
      });
      return execution.response();
    } catch (RuntimeException failure) {
      if (prepared == null) {
        emitHistory(op, startedAt, OperationOutcome.FAILURE, failure.getMessage(), provenance, Optional.empty());
      } else {
        try { attempts.rejectBeforeStart(prepared, "UNCAUGHT_EXCEPTION"); }
        catch (RuntimeException storageFailure) { failure.addSuppressed(storageFailure); }
      }
      throw failure;
    }
  }

  private OperationResult preflight(Operation op, String argumentsJson, boolean undo) {
    if (capabilityResolver != null) {
      var missing = checkCapabilities(op);
      if (missing != null) return OperationResult.failure(capabilityUnavailableMessage(missing),
          "CAPABILITY_UNAVAILABLE", Map.of("capability", missing), true);
    }
    if (undo) return null;
    var invalid = inputValidator.validate(op, argumentsJson);
    return invalid.map(value -> OperationResult.failure(value.message(), "BAD_REQUEST", value.details(), false))
        .orElse(null);
  }

  private OperationExecution invokeHandler(Operation op, String argumentsJson, InvocationProvenance provenance,
      EngineContext context, String undoId, OperationRecordHandle handle) {
    if (op.provenance().tier() == TrustTier.UNTRUSTED_PLUGIN) {
      throw new UnsupportedOperationException("Untrusted plugin operations require V1.5 sandbox infrastructure");
    }
    OperationHandler handler = handlers.resolve(new OperationRef(op.binding().handlerId()))
        .orElseThrow(() -> new IllegalStateException("No handler registered for binding " + op.binding().handlerId()));
    if (undoId != null) return handle == null ? OperationExecution.finished(handler.undo(undoId, context))
        : handler.undoRecorded(undoId, context, handle);
    return handle == null ? OperationExecution.finished(handler.execute(argumentsJson, provenance, context))
        : handler.executeRecorded(argumentsJson, provenance, context, handle);
  }

  private void emitHistory(
      Operation op,
      Instant startTime,
      OperationOutcome outcome,
      String diagnosticsLink,
      InvocationProvenance provenance,
      Optional<String> executionId) {
    Instant completedAt = clock.instant();
    // Tempdoc 879: the declared {@code OperationPolicy.audit} axis is the authority for
    // whether an invocation is recorded. AuditPolicy.NONE means "no audit record", so the
    // history entry is suppressed; METADATA_ONLY emits the metadata-shaped entry below
    // (id / actor / timestamps / outcome / provenance — never arguments).
    //
    // WHY this is a scoped guard and NOT an early `return`: the per-Operation advisory
    // emission below lives inside this same method, and core.ping-backend declares
    // AuditPolicy.NONE *together with* advisoryClass core.advisory-operation-completed.
    // An early return here would silently kill the advisory pipeline for its only
    // NONE-declaring producer. Audit policy governs history retention, not advisory
    // delivery — keep the suppression wrapped around the historyEmitter block only.
    boolean auditSuppressed = op.policy().audit() == AuditPolicy.NONE;
    if (!auditSuppressed && historyEmitter != null) {
      try {
        historyEmitter.accept(
            new OperationHistoryEntry(
                op.id(),
                // Slice 444b: head-process identifier. Per slice 490 §4.B the canonical
                // answer to "who triggered this?" is the typed {@code provenance} field
                // (initiator + transport + executor). The {@code actor} string remains
                // literal "head" for wire-shape stability — derivation from provenance
                // would be a silent contract change for FE / MCP / audit consumers that
                // grep on the value.
                "head",
                startTime,
                completedAt,
                outcome,
                Optional.ofNullable(diagnosticsLink),
                provenance,
                // Tempdoc 550 G6: carry the backend execution id (undo-supported ops) so the
                // unified ledger can collapse this row with the FE Effect Journal entry that
                // dispatched it (journalEntryId ↔ executionId).
                executionId));
      } catch (RuntimeException e) {
        // History emission must never break dispatch. Swallow with no log to avoid
        // recursive log paths; a separate health-event would surface persistent breakage.
      }
    }
    // Slice 490 §6.3 + Group B2 follow-up: per-Operation typed-class advisory
    // emission. The dispatcher looks up the operation's declared
    // {@code OperationPolicy.advisoryClass} (Optional<ResourceRef>) in the routing
    // table and fires the matching emitter. Missing entry means the operation
    // declared a class no emitter is registered for — log-only (don't break
    // dispatch). Empty Optional means the operation opted out — no emission.
    Optional<ResourceRef> advisoryClass = op.policy().advisoryClass();
    if (advisoryClass.isPresent()) {
      Consumer<OperationCompletionEvent> emitter =
          advisoryEmitters.get(advisoryClass.get());
      if (emitter != null) {
        try {
          emitter.accept(
              new OperationCompletionEvent(
                  op.id(),
                  outcome,
                  completedAt,
                  Optional.ofNullable(diagnosticsLink),
                  provenance,
                  executionId));
        } catch (RuntimeException e) {
          // Same discipline as historyEmitter: advisory emission must never break
          // dispatch. Swallow with no log to avoid recursive paths.
        }
      }
    }
  }

  private String checkCapabilities(Operation op) {
    var required = op.policy().requiredCapabilities();
    if (required.isEmpty()) return null;
    for (RequiredCapability req : required) {
      if (!Boolean.TRUE.equals(capabilityResolver.apply(req))) {
        return switch (req) {
          case RequiredCapability.WorkerOnline w -> "worker-online";
          case RequiredCapability.InferenceOnline i -> "inference-online";
        };
      }
    }
    return null;
  }

  // Tempdoc 737 §12b: denials name the unblocking action instead of just the missing
  // capability id (conforms to 725's actionable-errors shape). The error CODE and Map
  // payload ("capability" -> capability id) are unchanged for wire compat — only this
  // human/agent-facing message text improves.
  private static String capabilityUnavailableMessage(String missingCap) {
    return switch (missingCap) {
      case "worker-online" ->
          "Required capability unavailable: worker-online. The knowledge worker is not "
              + "reachable; it restarts automatically — retry shortly, or run core.restart-worker.";
      case "inference-online" ->
          "Required capability unavailable: inference-online. Inference is not running; call "
              + "core.set-chat-enabled {\"enabled\":true} (or core.activate-runtime-variant "
              + "after install) to bring it up, then retry.";
      default -> "Required capability unavailable: " + missingCap;
    };
  }

  /**
   * Slice 490 follow-up — provenance-integrity validation. The dispatcher's caller may
   * supply any {@link InvocationProvenance}; this validator rejects user-facing
   * {@link TransportTag} values when the registered Operation comes from a plugin-tier
   * declaration. Rationale: plugin code dispatching an Operation cannot claim its
   * dispatch arrived "from a button click" or "from an LLM emission" without
   * compromising the audit trail; only plugin-tier or system-tier transports are
   * admissible. {@code CORE}-tier Operations are dispatched by trusted in-process code
   * and may claim any transport — they're the producers of user-facing transport
   * claims (e.g. {@code OperationsController}'s {@link InvocationProvenance#uiButton}).
   *
   * <p>Throws {@link IllegalArgumentException} on rejection. The dispatcher's caller is
   * expected to either supply a substrate-supplied factory (e.g.
   * {@link InvocationProvenance#systemInternal}) or know what tier its Operation is.
   */
  private static void validateProvenance(Operation op, InvocationProvenance provenance) {
    TrustTier tier = op.provenance().tier();
    if (tier == TrustTier.CORE) {
      return; // CORE callers may claim any transport.
    }
    if (tier == TrustTier.TRUSTED_PLUGIN) {
      switch (provenance.transport()) {
        case PLUGIN_EMITTED, SYSTEM_INTERNAL, AGENT_LOOP, WORKFLOW, SCHEDULED, RULE_ENGINE -> {
          return;
        }
        default ->
            throw new IllegalArgumentException(
                "Operation "
                    + op.id().value()
                    + " is TRUSTED_PLUGIN-tier; transport "
                    + provenance.transport()
                    + " is not admissible. Allowed: PLUGIN_EMITTED, SYSTEM_INTERNAL, "
                    + "AGENT_LOOP, WORKFLOW, SCHEDULED, RULE_ENGINE.");
      }
    }
    // UNTRUSTED_PLUGIN is rejected later in the dispatch switch with the V1.5 sandbox
    // doc pointer; no transport validation needed here.
  }

  @Override
  public OperationResult undo(Operation op, String executionId, EngineContext engineContext) {
    // Legacy 2-arg overload — defaults to system-internal provenance and no confirmation
    // token, exactly as the 2-arg dispatch does. Callers that know their transport should
    // use the 4-arg overload so the gate sees the real source tier.
    return undo(
        op, executionId, EngineProvenance.invocation(engineContext,
            io.justsearch.agent.api.registry.ExecutorTag.UI, clock.instant(), Optional.empty()),
        Optional.empty(), engineContext);
  }

  /**
   * Tempdoc 875 §C.7 — <em>the reversal of an operation is an operation and inherits its risk
   * class.</em> Before this, {@code undo} checked {@code undoSupported} and delegated: the trust
   * lattice that every forward dispatch meets never ran, so the reverse of a HIGH-risk operation
   * was dispatched with no gate at all — in exactly the arm where the reversal can be MORE
   * destructive than the forward op (a COPY-undo is a recursive delete).
   *
   * <p>The gate here is the SAME computation, not a parallel one: {@link #enforceTrustLattice}
   * with the operation's own declared risk (unchanged — this does not alter what is permitted on
   * the forward path), the caller's transport, and the reversal's canonical arguments
   * ({@link OperationDispatcher#undoArguments}). It is therefore satisfied by exactly what
   * satisfies the forward gate: a durable grant inside its risk ceiling AND argument scope, or a
   * capsule bound to this (operation, undo-arguments). A reversal carries no path arguments of
   * its own, so an argument scope that governs the operation cannot prove containment for it and
   * fails closed — a standing "allow always" grant never silently authorizes the undo of a
   * containment-governed operation; the user is asked.
   */
  @Override
  public OperationResult undo(
      Operation op,
      String executionId,
      InvocationProvenance provenance,
      Optional<String> confirmationToken, EngineContext engineContext) {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(executionId, "executionId");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(confirmationToken, "confirmationToken");
    validateEngineContext(engineContext, provenance);
    // An unsupported action has no effect or existing outcome to expose. Preserve the
    // immediate refusal rather than eliciting confirmation for an unavailable reversal.
    if (!op.policy().undoSupported()) {
      return OperationResult.failure("Undo not supported by " + op.id().value());
    }
    // Same order as dispatch: transport-spoofing defense, then the lattice.
    validateProvenance(op, provenance);
    if (intentGateEvaluator != null) {
      enforceTrustLattice(
          op, OperationDispatcher.undoArguments(executionId), provenance, confirmationToken, engineContext);
    }
    return executeAttempt(op, OperationDispatcher.undoArguments(executionId), provenance, engineContext, executionId);
  }

  /**
   * Slice 487 §4.4: composes the source-side {@link SourceTier} (derived from the
   * intent source's catalog entry via the transport tag) with the operation-side
   * {@link io.justsearch.agent.api.registry.RiskTier} into a {@link GateBehavior},
   * then enforces:
   *
   * <ul>
   *   <li>{@code AUTO} — proceed; no further action.
   *   <li>{@code INLINE_CONFIRM} / {@code TYPED_CONFIRM} — require a non-empty
   *       confirmation token. Absent → throw {@link ConfirmationRequiredException}.
   *       The caller (HTTP endpoint, agent loop) catches and surfaces the
   *       trust-aware elicitation UX, then re-dispatches with a token.
   *   <li>{@code DENY} — throw {@link TrustGateDeniedException}. Today's V1
   *       lattice cell values produce no DENY outcomes; the path is reserved.
   * </ul>
   *
   * <p>{@code SourceTier} fallback: when the transport has no registered
   * {@code IntentSource} in the catalog (an unregistered ingress — Pass-9
   * commitment 4 violation), the lattice treats the dispatch as
   * {@code UNTRUSTED} so the gate behavior errs on the side of caution. The
   * BackendIntentRouter logs the unregistered-ingress condition separately.
   */
  private void enforceTrustLattice(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      Optional<String> confirmationToken, EngineContext engineContext) {
    // Tempdoc 550 thesis III: ONE structural verdict (derived source tier + (SourceTier × RiskTier)
    // lattice gate + Global Hard Stop override), the same computation/instance the Preview endpoint
    // reads. The E2 hard-stop DENY (engaged → DENY every UNTRUSTED dispatch, user-driven untouched)
    // is folded into the verdict's gate, so the DENY case below records DENIED + throws — the
    // circuit breaker enforced at the sole chokepoint, outside the agent's control.
    IntentGateEvaluator.IntentVerdict verdict =
        intentGateEvaluator.evaluate(op.policy().risk(), provenance.transport());
    SourceTier sourceTier = verdict.sourceTier();
    GateBehavior gate = verdict.gateBehavior();
    switch (gate) {
      case AUTO -> {
        // proceed
      }
      case INLINE_CONFIRM, TYPED_CONFIRM -> {
        // Tempdoc 550 thesis IV + 560 §28 (4d): a durable "allow-always" grant satisfies the gate
        // without a fresh capsule — either a per-operation grant, or a grant for this operation's
        // declared capability family (the wider caveat). Checked BEFORE the capsule; recorded APPROVED.
        // Tempdoc 875 C.1: this is the ONE place that knows a confirmation was skipped, so it is where
        // both narrowing rules land. (1) Risk ceiling — the store refuses HIGH outright, matching
        // IntentGateEvaluator.agentGate's issuance floor. (2) Argument scope — the grant covers this
        // invocation only if its arguments fall inside the containment it was granted against. When the
        // scope says no, we do NOT deny and do NOT emit APPROVED: we fall through to the capsule path,
        // so the user gets the ordinary confirm dialog, which names the arguments.
        var durable = this.durableGrantStore;
        var scope = this.durableGrantScope;
        if (durable != null
            && durable.isAllowed(
                op.id().value(), op.policy().capabilityFamily(), op.policy().risk(), engineContext)
            && scope.coversArguments(op, argumentsJson, engineContext)) {
          emitGateOutcome(op, provenance, sourceTier, gate,
              io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED);
          return; // durable-grant-satisfied
        }
        String token = confirmationToken.orElse("");
        // Tempdoc 550 A1 + C2 (steps 3+4 complete): the ONLY thing that satisfies a
        // non-AUTO gate is a valid consent capsule — bound to THIS (operation, args),
        // single-use, unexpired, unforgeable without the per-process session key. This
        // holds for ALL source tiers; the V1 nominal token (any non-blank string) is gone.
        // Every FE caller that can reach a non-AUTO gate mints a capsule at the user-approval
        // gesture: ActionButton + OpButton (TRUSTED×HIGH), the Effect typed-confirm path
        // (WA-1), and BrainSurface's host-API invoke (WA-2). The URL/deeplink (MEDIUM),
        // agent-loop, and MCP paths carry no token and are correctly gated until they too
        // route through an approval that mints a capsule (C3 ceremony). With the nominal
        // path removed, the audit caller-migration is complete: a fabricated or stale
        // non-capsule token from ANY source now fails closed.
        if (capsuleService != null
            && capsuleService.verifyAndConsume(token, op.id().value(), argumentsJson)) {
          // Tempdoc 550 Outcome face: record the gate firing as APPROVED, then proceed.
          emitGateOutcome(op, provenance, sourceTier, gate,
              io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED);
          return; // capsule-satisfied
        }
        emitGateOutcome(op, provenance, sourceTier, gate,
            io.justsearch.app.observability.operations.AuthorizationDisposition.GATED);
        throw new ConfirmationRequiredException(op.id(), gate, op.policy().confirm(), sourceTier);
      }
      case DENY -> {
        emitGateOutcome(op, provenance, sourceTier, gate,
            io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED);
        throw new TrustGateDeniedException(op.id(), sourceTier);
      }
    }
  }

  /**
   * Tempdoc 550 Outcome face: record one trust-gate decision (no-op when the sink is unwired).
   * A pure side-effect — it does not alter the gate's throw/return (fail-closed) semantics.
   */
  private void emitGateOutcome(
      Operation op,
      InvocationProvenance provenance,
      SourceTier sourceTier,
      GateBehavior gate,
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition) {
    if (authorizationOutcomeEmitter == null) {
      return;
    }
    try {
      authorizationOutcomeEmitter.accept(
          new io.justsearch.app.observability.operations.AuthorizationOutcomeEntry(
              op.id().value(),
              provenance.transport(),
              sourceTier,
              op.policy().risk(),
              gate,
              disposition,
              clock.instant()));
    } catch (RuntimeException e) {
      // Security-gate-adjacent (tempdoc 550): the gate-firing emit is ADDITIVE. The emitter now
      // also fans the firing into the unified action-ledger change-stream (SSE publish +
      // subscriber callbacks) — a wider failure surface. It must NEVER alter the gate's
      // fail-closed semantics: a throw here would replace the ConfirmationRequired/TrustGateDenied
      // throw (or, on the capsule-APPROVED path, the proceed-return) with an unexpected failure.
      // Same discipline as emitHistory: swallow so the gate decision stands. P3 (tempdoc 550):
      // the swallow is no longer silent — a WARN surfaces a persistent emission failure (the gate
      // decision still stood; only the ledger/audit record of it was lost). Safe to log here: the
      // emitter (AuthorizationOutcomeStore append + ledger SSE broadcast) does not log back into
      // the gate path, so there's no recursive-log hazard. (A metric counter is a follow-on if a
      // registry is wired into the executor.)
      LOG.warn(
          "Trust-gate outcome emit failed for op={} disposition={} ({}); gate decision stood, "
              + "ledger/audit record lost",
          op.id().value(),
          disposition,
          e.toString());
    }
  }
}
