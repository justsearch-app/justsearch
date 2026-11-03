/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Chrome-zone placement axis on a {@link Surface}.
 *
 * <p>Closed enum mapping to the shell chrome's zones plus audience-specific
 * values (DEEPLINK, HEADLESS_AGENT_TOOL).
 *
 * <p>The chrome's per-zone dispatchers filter the SurfaceCatalog by
 * {@code placement} + {@code audience ∈ visibleAudienceSet}.
 */
public enum Placement {

  /** Top global search bar. */
  COMMAND,

  /** Left vertical icon nav. */
  RAIL,

  /** Main content pane — the {@code activeView} target. */
  STAGE,

  /** Right inspection sidebar. */
  HUD,

  /** Bottom-right mini dashboard. */
  STATUS,

  /**
   * Collapsible secondary surface; reachable via a chrome-managed expand
   * gesture. May expand into the STAGE or HUD zone on demand.
   */
  DRAWER,

  /** Full-screen overlay. */
  MODAL,

  /**
   * URL-only; reachable via {@code ?surface=<id>} but does not occupy a
   * chrome zone. Replaces today's per-flag debug-route if-branches in
   * {@code main.jsx} (slice 449 phase 6 keeps this declared but does not yet
   * generalize the dispatcher; that's deferred per §4 chrome-dispatcher
   * refactor).
   */
  DEEPLINK,

  /**
   * Consumed by agent (LLM tool) APIs; no human chrome zone. Surfaces with
   * this placement must declare {@link Audience#AGENT}.
   */
  HEADLESS_AGENT_TOOL
}
