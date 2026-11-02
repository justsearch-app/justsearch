/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Orthogonal-to-{@link ExecutorTag} discriminator naming the transport an invocation or
 * unprompted emission arrived through.
 *
 * <p>Per slice 490 §4.B (substrate factoring) — promoted from slice 489 §8's proposal of a
 * cross-cutting transport axis. {@code ExecutorTag} answers <em>who</em> is executing
 * ({@code UI} / {@code AGENT} / {@code CLI}); {@code TransportTag} answers <em>how the
 * invocation arrived</em>. The two axes do not co-vary — e.g., a URL-bar paste and a rail
 * click are both {@code UI} executor but distinguishable by transport
 * ({@code URL_BAR} vs {@code BUTTON}).
 *
 * <p>Per slice 489 §13 anti-pattern #4 ("do not gate on {@code TransportTag} inside
 * {@link ConfirmStrategy}"): transport-specific overlays are a separate primitive
 * (resolver decoration), not a flag on the canonical execution gate. {@code TransportTag}
 * is descriptive, not prescriptive.
 *
 * <p>Values reflect the union of consumer needs across slices 487 (LLM-emitter consumer
 * cluster), 489 (URL-substrate consumer cluster), 490 (proactive-emission consumer
 * cluster), and 491 (chat-substrate stream consumer). New transports add by appending an
 * enum value without forking {@code ExecutorTag}.
 *
 * <p><strong>Forward-compat scaffolding</strong> (Pass-8 follow-up note). v1 has live
 * producers for only {@link #BUTTON} (HTTP {@code /api/operations/{id}/invoke}) and
 * {@link #SYSTEM_INTERNAL} (the legacy 2-arg dispatch default). The remaining values
 * ({@code URL_BAR}, {@code URL_DEEPLINK}, {@code LLM_EMISSION}, {@code PALETTE},
 * {@code RAIL}, {@code AGENT_LOOP}, {@code MCP}, {@code PLUGIN_EMITTED},
 * {@code SCHEDULED}, {@code RULE_ENGINE}) are anticipated by the {@code
 * OperationExecutorImpl.validateProvenance} whitelist + by slices 487 / 489 / 491 but
 * have no producer in this branch yet. They are kept ahead of the producers so the
 * validator's contract is stable across consumer-cluster rollout; the alternative
 * (extending the enum + the validator with every new producer) is the wrong shape.
 */
public enum TransportTag {
  /** URL pasted/typed in the browser bar or arrived via popstate. */
  URL_BAR,
  /** OS-level deep-link (Tauri, {@code justsearch://...} from another app). */
  URL_DEEPLINK,
  /** Markdown URL emitted by the LLM in chat (slice 487's consumer cluster). */
  LLM_EMISSION,
  /** Command palette invocation. */
  PALETTE,
  /** Form button or {@code <jf-action-button>} click. */
  BUTTON,
  /** Rail navigation click. */
  RAIL,
  /** Agent tool-call from within the agent loop (slice 491's {@code ToolIterating}). */
  AGENT_LOOP,
  /**
   * Backend workflow-runner tool-call (tempdoc 560 §4.3) — a {@code WorkflowShapeRunner} ToolStep
   * dispatching an Operation through the same gate→consent→route path as the agent loop. Distinct
   * from {@link #AGENT_LOOP} purely so the lattice + audit ledger attribute the action to the
   * workflow that fired it, not the agent loop. Same SourceTier (UNTRUSTED) — no gate-behavior change.
   */
  WORKFLOW,
  /** External MCP tool invocation. */
  MCP,
  /**
   * Plugin-emitted dispatch — a trusted plugin's own code invoking an Operation. Per the
   * follow-up provenance-integrity discipline: a {@code TRUSTED_PLUGIN}-tier caller may
   * claim this value (or {@code SYSTEM_INTERNAL} / {@code AGENT_LOOP}) but not user-
   * facing transport values (BUTTON / URL_BAR / LLM_EMISSION / etc.). The dispatcher's
   * {@code validateProvenance} enforces this restriction at entry.
   */
  PLUGIN_EMITTED,
  /** Scheduled trigger / cron / event-trigger (slice 486 §15.8 schedule substrate; G47). */
  SCHEDULED,
  /**
   * Rule-engine detection firing (slice 490 §4.A advisory consumers; e.g., PII detector,
   * unlinked-reference detector, tutorial nudge).
   */
  RULE_ENGINE,
  /**
   * System-internal trigger — backend self-initiated dispatch (e.g., recovery operation
   * fired by health-event resolution, indexing pipeline orchestration). Default fallback
   * when no caller-supplied transport context exists.
   */
  SYSTEM_INTERNAL,
  /** HTTP caller without proof of an app-window gesture (manifest token only). */
  HTTP_API
}
