/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Invocation policy, including the durable record's declared classification.
 *
 * <p>Per tempdoc 429 §6 + §C.D: five core axes (risk, confirm, audit, retry,
 * requiredCapabilities) drive the executor's gating, retry, audit recording, and
 * capability resolution. Revision 4 added the {@code undoSupported} flag (declarative
 * per §E.3) as a sixth axis to mark operations whose handlers override
 * {@link OperationHandler#undo(String)}.
 *
 * <p>Tempdoc 879 removed the former {@code rateLimit} axis: every declaration site in the
 * tree passed {@code Optional.empty()}, nothing throttled anything, and its only validator
 * rule was therefore unreachable. An axis no operation ever declares is not a policy — it is
 * residue that reads as one.
 *
 * <p>Validators (per §A.7 trimmed list) enforce structural invariants across axes —
 * e.g., {@code risk == HIGH && audit == NONE} is an ERROR; {@code confirm == TYPED &&
 * !confirmTextKey.isPresent()} is unrepresentable since {@link ConfirmStrategy.Typed}
 * carries the key as a constructor parameter (type-system invariant per §"Type-system
 * invariants").
 */
public record OperationPolicy(
    RiskTier risk,
    ConfirmStrategy confirm,
    AuditPolicy audit,
    RetryPolicy retry,
    Set<RequiredCapability> requiredCapabilities,
    boolean undoSupported,
    Optional<ResourceRef> advisoryClass,
    Optional<OperationRef> inverseOperationRef,
    Optional<String> capabilityFamily,
    OperationKind recordKind) {

  public OperationPolicy {
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(confirm, "confirm");
    Objects.requireNonNull(audit, "audit");
    Objects.requireNonNull(retry, "retry");
    Objects.requireNonNull(advisoryClass, "advisoryClass");
    Objects.requireNonNull(inverseOperationRef, "inverseOperationRef");
    Objects.requireNonNull(capabilityFamily, "capabilityFamily");
    Objects.requireNonNull(recordKind, "recordKind");
    requiredCapabilities =
        requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
  }

  /**
   * Tempdoc 560 WS3 (§4.2 effect-inverse): the operation that semantically reverses this one.
   * When present, the FE Effect Journal (tempdoc 543) materializes it into an {@code
   * invoke-operation} inverse Effect, so undoing this operation's journal entry re-issues the
   * declared inverse — a backend-declared symmetric pair (e.g. {@code add-watched-root} ↔
   * {@code remove-watched-root}) without the FE having to hand-author the inverse in a manifest.
   *
   * <p>This is the Java side the prior FE work (effects/index.ts §32 R-P2) explicitly named as a
   * pending wire extension: the FE consulted only manifest-declared inverses and returned null for
   * backend operations. Backend-undoable operations that override {@link OperationHandler#undo} are
   * a distinct mechanism (the executionId bridge) — {@code inverseOperationRef} declares a sibling
   * operation, not a handler-level undo.
   *
   * @return a fresh policy with the inverse pointer set; all other axes copied.
   */
  public OperationPolicy withInverseOperationRef(OperationRef inverse) {
    return new OperationPolicy(
        risk,
        confirm,
        audit,
        retry,
        requiredCapabilities,
        undoSupported,
        advisoryClass,
        Optional.of(Objects.requireNonNull(inverse, "inverse")),
        capabilityFamily,
        recordKind);
  }

  /**
   * Tempdoc 560 §28 (4d) — the capability family this operation belongs to. A durable "allow-always"
   * grant issued against a {@link io.justsearch.app.services.intent.Grant.CapabilityFamily} scope
   * auto-approves every operation declaring the same family from the granting source tier (a wider
   * caveat than the per-operation durable grant). Absent ⇒ the operation is in no family and only a
   * per-operation grant can cover it. Kept off the registry wire view (a backend trust concern).
   *
   * @return a fresh policy with the capability-family pointer set; all other axes copied.
   */
  public OperationPolicy withCapabilityFamily(String family) {
    return new OperationPolicy(
        risk,
        confirm,
        audit,
        retry,
        requiredCapabilities,
        undoSupported,
        advisoryClass,
        inverseOperationRef,
        Optional.of(Objects.requireNonNull(family, "family")),
        recordKind);
  }

  /** Backend record classification; declaring a kind requires its corresponding recovery owner. */
  public OperationPolicy withRecordKind(OperationKind kind) {
    return new OperationPolicy(risk, confirm, audit, retry, requiredCapabilities, undoSupported,
        advisoryClass, inverseOperationRef, capabilityFamily, kind);
  }

  /** Existing declarations remain ordinary operations until their recorded owners are connected. */
  public OperationPolicy(RiskTier risk, ConfirmStrategy confirm, AuditPolicy audit, RetryPolicy retry,
      Set<RequiredCapability> requiredCapabilities, boolean undoSupported,
      Optional<ResourceRef> advisoryClass, Optional<OperationRef> inverseOperationRef,
      Optional<String> capabilityFamily) {
    this(risk, confirm, audit, retry, requiredCapabilities, undoSupported, advisoryClass,
        inverseOperationRef, capabilityFamily, OperationKind.OPERATION);
  }

  /**
   * Backwards-compat constructor (the pre-560-§28 canonical 8-arg shape). Defaults
   * {@link #capabilityFamily} to {@link Optional#empty()} so Operations declared before 4d compile
   * unchanged and belong to no capability family until their authors opt in via
   * {@link #withCapabilityFamily} or the full constructor.
   */
  public OperationPolicy(
      RiskTier risk,
      ConfirmStrategy confirm,
      AuditPolicy audit,
      RetryPolicy retry,
      Set<RequiredCapability> requiredCapabilities,
      boolean undoSupported,
      Optional<ResourceRef> advisoryClass,
      Optional<OperationRef> inverseOperationRef) {
    this(
        risk,
        confirm,
        audit,
        retry,
        requiredCapabilities,
        undoSupported,
        advisoryClass,
        inverseOperationRef,
        Optional.empty());
  }

  /**
   * Backwards-compat constructor (slice 490 §6.3 shape, pre-560). Defaults
   * {@link #inverseOperationRef} to {@link Optional#empty()} so Operations declared before WS3
   * compile unchanged and declare no inverse until their authors opt in via
   * {@link #withInverseOperationRef} or the canonical constructor.
   */
  public OperationPolicy(
      RiskTier risk,
      ConfirmStrategy confirm,
      AuditPolicy audit,
      RetryPolicy retry,
      Set<RequiredCapability> requiredCapabilities,
      boolean undoSupported,
      Optional<ResourceRef> advisoryClass) {
    this(
        risk,
        confirm,
        audit,
        retry,
        requiredCapabilities,
        undoSupported,
        advisoryClass,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Backwards-compat constructor (slice 429 shape, pre-490 §6.3). Defaults
   * {@link #advisoryClass} to {@link Optional#empty()} so Operations declared before
   * slice 490 compile unchanged and emit no advisories until their authors opt in.
   *
   * <p>Group B2 follow-up: replaced the v1 boolean {@code emitAdvisoryOnCompletion}
   * with this typed {@code advisoryClass} field. The boolean answered "does this
   * Operation emit an advisory?" but the implicit answer to "what class of advisory?"
   * was hardcoded to {@code core.advisory-operation-completed}. The {@code
   * advisoryClass} field generalizes: each Operation declares <em>which</em>
   * advisory-shaped Resource it emits into; the {@code OperationExecutorImpl} routes
   * via a registered {@code Map<ResourceRef, Consumer<AdvisoryEvent>>}.
   */
  public OperationPolicy(
      RiskTier risk,
      ConfirmStrategy confirm,
      AuditPolicy audit,
      RetryPolicy retry,
      Set<RequiredCapability> requiredCapabilities,
      boolean undoSupported) {
    this(
        risk,
        confirm,
        audit,
        retry,
        requiredCapabilities,
        undoSupported,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
