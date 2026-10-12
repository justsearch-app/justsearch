/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** Who wants a generation, which decides whether the demand may load, wait or is skipped. */
public enum DemandKind {
  /** The app's own chat submit. */
  HUMAN_CHAT,
  /** An agent (MCP client, CLI, the OpenAI-compatible proxy). */
  AGENT_CHAT,
  /** Query assistance (understanding, expansion); never waits and never loads. */
  ASSIST,
  /**
   * Background generation the app starts on its own (an automatic conversation title); never
   * triggers a load, and yields to picture reading, self-tests and other exchanges until it has
   * waited past the aging bound.
   */
  BACKGROUND,
  /** Tokenize, templates and model listing: keeps a drain waiting but is not generating. */
  AUX
}
