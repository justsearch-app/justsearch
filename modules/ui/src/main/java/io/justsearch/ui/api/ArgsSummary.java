/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

/**
 * Shared privacy-bounded, truncated summary of invocation args for an approval ceremony —
 * extracted (tempdoc 655) from {@code OperationsController} so the MCP gate path
 * ({@code McpToolSurface}) produces byte-identical summaries for the same arguments, rather than
 * two independently-authored truncation rules drifting apart.
 *
 * <p><b>Privacy boundary (tempdoc 550 F3, deliberate).</b> This rides on a transient consent
 * surface shown to the human deciding the action right now, who has a legitimate need to see WHAT
 * they are approving. It is NOT logging: the action ledger still omits args, and nothing here is
 * persisted beyond the pending record's own TTL. Raw-argument fallback is bounded to 200 chars.
 * Prepared invocations instead use the explicit OperationApprovalPreview from the frozen value;
 * its byte bound refuses oversize targets rather than truncating their identity.
 */
public final class ArgsSummary {

  private ArgsSummary() {}

  /** Empty/{@code "{}"} args yield {@code ""} (nothing to show). */
  public static String summarize(String argumentsJson) {
    if (argumentsJson == null) {
      return "";
    }
    String trimmed = argumentsJson.trim();
    if (trimmed.isEmpty() || "{}".equals(trimmed)) {
      return "";
    }
    int max = 200;
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
  }
}
