/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

/**
 * What a {@code POST /api/lifecycle/shutdown} body says (Spec F §4.3): the supervisor's quit
 * deadline and the incarnation it addressed. Every field is optional, because today's Tauri shell
 * posts an empty object; the endpoint is always a quit.
 *
 * @param deadlineAtEpochMs when the supervisor forces the Engine (it forces at this instant minus
 *     {@code quitForceMarginMs}); null when the body names none
 * @param incarnation the {@code <session>/<n>} the supervisor addressed; null when absent
 * @param detail the stop detail, e.g. {@code session_end}; null when absent
 */
public record LifecycleShutdownRequest(Long deadlineAtEpochMs, String incarnation, String detail) {

  /** A body that names nothing: {@code {}}, an empty body, or one this endpoint cannot read. */
  public static final LifecycleShutdownRequest NONE = new LifecycleShutdownRequest(null, null, null);
}
