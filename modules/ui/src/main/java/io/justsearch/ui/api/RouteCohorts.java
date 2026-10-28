/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.List;
import java.util.Map;

/**
 * Tempdoc 583 §D.3a — groups HTTP routes into domain cohorts for the route manifest / API explorer.
 *
 * <p>A declarative {path-prefix → cohort} table (most-specific first). Attribution by path is robust
 * and independent of how a route happens to be registered (static {@code *Routes} vs the
 * {@code ResourceApiModule} vs inline) — the manifest just needs a stable grouping for display.
 * Cohorts mirror the 583 decomposition's domains (knowledge, conversation, agent, resource,
 * inference, observability, …). Unmatched paths fall back to {@code "other"}.
 */
final class RouteCohorts {
  private RouteCohorts() {}

  /** Ordered {prefix → cohort}; first matching prefix wins, so list more-specific prefixes first. */
  private static final List<Map.Entry<String, String>> PREFIXES =
      List.of(
          Map.entry("/api/chat/agent", "agent"),
          Map.entry("/api/agent", "agent"),
          Map.entry("/api/chat", "conversation"),
          Map.entry("/api/knowledge", "knowledge"),
          Map.entry("/api/documents/presence", "knowledge"),
          Map.entry("/api/document", "knowledge"),
          Map.entry("/api/preview", "knowledge"),
          Map.entry("/api/indexing-jobs", "resource"),
          Map.entry("/api/indexing", "indexing"),
          Map.entry("/api/registry", "registry"),
          Map.entry("/api/messages", "i18n"),
          Map.entry("/api/schemas", "schema"),
          Map.entry("/api/ai", "ai-runtime"),
          Map.entry("/api/inference", "inference"),
          Map.entry("/api/gpu", "inference"),
          Map.entry("/api/encoder", "inference"),
          Map.entry("/api/worker", "inference"),
          Map.entry("/api/runtime", "runtime"),
          Map.entry("/.well-known", "runtime"),
          Map.entry("/api/metrics", "observability"),
          Map.entry("/api/health", "observability"),
          Map.entry("/api/status", "observability"),
          Map.entry("/api/telemetry", "observability"),
          Map.entry("/api/diagnostics", "observability"),
          Map.entry("/api/diagnostic-channels", "resource"),
          Map.entry("/api/advisory", "resource"),
          Map.entry("/api/operation-history", "resource"),
          Map.entry("/api/operations", "resource"),
          Map.entry("/api/undo", "resource"),
          Map.entry("/api/action-ledger", "resource"),
          Map.entry("/api/authorizations", "resource"),
          Map.entry("/api/condition-recovery-index", "resource"),
          Map.entry("/api/runtime-context", "resource"),
          Map.entry("/api/intent", "resource"),
          Map.entry("/api/memory", "interaction"),
          Map.entry("/api/feedback", "interaction"),
          Map.entry("/api/thread", "interaction"),
          Map.entry("/api/presence", "interaction"),
          Map.entry("/api/debug", "debug"),
          Map.entry("/api/boot", "boot"),
          Map.entry("/api/plugins", "plugins"),
          Map.entry("/api/policy", "config"),
          Map.entry("/api/settings", "config"),
          Map.entry("/api/mcp", "mcp"),
          Map.entry("/mcp", "mcp"),
          Map.entry("/v1", "openai-compat"),
          Map.entry("/infra", "infra"));

  /** The cohort for a route path; {@code "other"} if no prefix matches. */
  static String cohortOf(String path) {
    for (Map.Entry<String, String> e : PREFIXES) {
      if (path.equals(e.getKey()) || path.startsWith(e.getKey() + "/") || path.startsWith(e.getKey())) {
        return e.getValue();
      }
    }
    return "other";
  }
}
