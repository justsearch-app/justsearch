/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Recognises a llama-server startup exit caused by a lost port bind (reconciliation ruling CH3).
 *
 * <p>llama-server binds its HTTP port before it loads the model. When the port is taken it logs
 * {@value #MARKER} and exits, which is the same "process exited during startup" shape a context
 * rung that does not fit produces. Reading the marker from the launch's own log is what keeps a
 * port race from being mistaken for a window that does not fit: a bind failure is retried once on
 * a fresh port at the same rung, never stepped down. Verified against the bundled b8571 build:
 * {@code start: couldn't bind HTTP server socket, hostname: 127.0.0.1, port: N} followed by
 * {@code main: exiting due to HTTP server error}, exit code 1.
 */
final class LlamaServerBindFailure {
  static final String MARKER = "couldn't bind HTTP server socket";

  private LlamaServerBindFailure() {}

  /** True when this launch's output shows that llama-server could not bind its HTTP port. */
  static boolean isBindFailure(String launchOutput) {
    return launchOutput != null && launchOutput.contains(MARKER);
  }
}
