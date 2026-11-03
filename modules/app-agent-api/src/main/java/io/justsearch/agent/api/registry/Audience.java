/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Access-control audience axis on a {@link Surface}.
 *
 * <p>Per slice 449 §0 D2 (revised per §B.A.1): audience is who's allowed to see
 * the chrome surface. It is <strong>orthogonal</strong> to data-emission privacy
 * ({@link Privacy} on Resource, {@link DataClass} sets on DiagnosticChannel) —
 * those govern what the BE allows the data to contain; audience governs which
 * subjects in the chrome's {@code visibleAudienceSet} can mount the surface.
 *
 * <p>Closed enum, governed by {@code 10-kernel/04-shape-governance.md}.
 *
 * <p>Trust ordering for the audience-composition rule (slice 449 §0 D2):
 * {@code USER < OPERATOR < DEVELOPER}. {@link #AGENT} is excluded from the
 * ordering — agent surfaces are consumed by headless tool APIs, not human
 * chrome, and don't compose into the human-audience ordering.
 */
public enum Audience {

  /**
   * Visible by default in the chrome's {@code visibleAudienceSet} (default
   * = {@code {USER}}). Surfaces consuming primitives whose consumer-permission
   * floor lifts above USER cannot declare USER audience — the validator
   * rejects.
   */
  USER,

  /**
   * Consumed by agent (LLM tool) APIs, not rendered in human chrome.
   * Excluded from the human-audience trust ordering. Surfaces with
   * {@link #AGENT} audience must declare a non-human placement (e.g.,
   * {@link Placement#HEADLESS_AGENT_TOOL}).
   */
  AGENT,

  /**
   * Visible only when the chrome's {@code visibleAudienceSet} includes
   * {@link #OPERATOR} — typically via a Settings → "Show operator surfaces"
   * toggle. Surfaces consuming a {@link DiagnosticChannel} with
   * {@link ConsumerPermission#TRUSTED_PLUGIN} or
   * {@link ConsumerPermission#OPERATOR_OVERRIDE} have their floor lifted to
   * OPERATOR by the audience-composition rule.
   */
  OPERATOR,

  /**
   * Visible only with a debug build flag. Reserved for substrate
   * introspection surfaces; not for
   * end-user features.
   */
  DEVELOPER
}
