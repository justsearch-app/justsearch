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
  /** Background generation; waits for READY and never triggers a load. */
  BACKGROUND,
  /** Tokenize, templates and model listing: keeps a drain waiting but is not generating. */
  AUX
}
