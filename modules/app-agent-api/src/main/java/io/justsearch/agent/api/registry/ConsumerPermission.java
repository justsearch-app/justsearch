/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Consumer-side permission gate for subscribing to a {@link DiagnosticChannel}.
 *
 * <p>Per slice 448 phase-1 review C2 (2026-05-07): a wire-shape forward-compat slot for
 * trust-tiered consumer access. V1 declares the field on {@link DiagnosticChannel} and
 * defaults to {@link #CORE}; the engine-log channel declares {@link #OPERATOR_OVERRIDE}.
 * Enforcement plugs in when trust-model changes land per slice 448 §B.A.5 (the deferred
 * plugin permission mechanism — operators with explicit Logs UI gesture, signed plugins,
 * or marketplace trust tiers).
 *
 * <p>Like {@link ProducerKind#EXTERNAL_OBSERVER}, the values are
 * declared but not yet enforced. The validator does not constrain this field in V1; once
 * a permission-enforcement layer ships, plugin-supplied channels declaring
 * {@link #TRUSTED_PLUGIN} or {@link #OPERATOR_OVERRIDE} are gated against the consumer's
 * trust context.
 */
public enum ConsumerPermission {

  /**
   * The default. Consumer-side access is unrestricted at the substrate level. Privacy
   * concerns for CORE channels are governed by the per-event {@code Set<DataClass>} +
   * sub-category subscription parameters, not by consumer identity.
   */
  CORE,

  /**
   * Channel may only be subscribed by trusted plugins. Reserved for V1.5+ trust-model
   * enforcement; declared here so plugin-supplied channels can specify the desired
   * gating without retrofitting later.
   */
  TRUSTED_PLUGIN,

  /**
   * Channel requires explicit operator opt-in (e.g., a Logs UI gesture). Reserved for
   * channels whose contents are sensitive enough that even default operators should not
   * see them without confirmation. Used by {@code core.engine-log} so the substrate
   * carries the explicit "operator-driven" framing from V1.
   */
  OPERATOR_OVERRIDE
}
