/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

/**
 * The normal-quit action given what the request body said (Spec F §4.3). The lifecycle module hands
 * the parsed body to an action of this type; a plain {@link Runnable} action still runs, without it.
 */
@FunctionalInterface
public interface LifecycleShutdownAction {
  void shutdown(LifecycleShutdownRequest request);
}
