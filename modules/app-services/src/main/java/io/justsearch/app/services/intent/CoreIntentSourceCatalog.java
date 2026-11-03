/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.IntentSource;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.IntentSourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.util.List;
import java.util.Optional;

/**
 * The CORE-tier {@link IntentSourceCatalog} — registered intent ingresses contributed by
 * JustSearch itself.
 *
 * <p>Per tempdoc 487 §4.1 and §6 Phase 1 step 7: every existing intent ingress
 * re-registers here without behavior change. Future intent sources (plugin-emitted,
 * scheduled triggers, MCP) register either via plugin contribution or via additions to
 * this catalog.
 *
 * <p>Id naming follows the platform's {@code NamespacedId} regex
 * ({@code ^core\.[a-z][a-z0-9-]*$}) — sub-namespacing within an id uses hyphens, not
 * additional dots. The tempdoc-illustrated three-dot forms (e.g.,
 * {@code core.llm.chat-emission}) are rendered here as the regex-compatible
 * {@code core.llm-chat-emission} per the {@link IntentSourceRef} Javadoc.
 *
 * <p><strong>Substrate-shape rule (Pass-9 commitment 1, tempdoc 487 §7):</strong> every
 * intent ingress is a named entry in this catalog. New ingresses register here; new
 * source flavors with novel trust postures require Pass-8 with proof-by-example.
 */
public final class CoreIntentSourceCatalog {

  /** Stable namespace prefix matching the rest of the CORE catalogs. */
  public static final String NAMESPACE = "core";

  // ===== UI ingresses (TRUSTED — direct user gesture) =====

  public static final IntentSourceRef UI_RAIL = new IntentSourceRef("core.ui-rail");
  public static final IntentSourceRef UI_PALETTE = new IntentSourceRef("core.ui-palette");
  public static final IntentSourceRef UI_BUTTON = new IntentSourceRef("core.ui-button");

  // ===== URL ingresses (MEDIUM — user-mediated but indirect) =====

  public static final IntentSourceRef URL_BAR = new IntentSourceRef("core.url-bar");
  public static final IntentSourceRef URL_DEEPLINK = new IntentSourceRef("core.url-deeplink");
  public static final IntentSourceRef OS_TAURI_DEEPLINK =
      new IntentSourceRef("core.os-tauri-deeplink");

  // ===== LLM ingresses (UNTRUSTED — model-emitted) =====

  public static final IntentSourceRef LLM_CHAT_EMISSION =
      new IntentSourceRef("core.llm-chat-emission");
  public static final IntentSourceRef LLM_AGENT_TOOL_CALL =
      new IntentSourceRef("core.llm-agent-tool-call");
  /** Backend workflow runner tool-call (tempdoc 560 §4.3) — UNTRUSTED, like the agent loop. */
  public static final IntentSourceRef WORKFLOW_TOOL_CALL =
      new IntentSourceRef("core.workflow-tool-call");

  // ===== External protocol ingresses (UNTRUSTED — external AI tool) =====

  public static final IntentSourceRef MCP_EXTERNAL =
      new IntentSourceRef("core.mcp-external");

  public static final IntentSourceRef HTTP_API = new IntentSourceRef("core.http-api");

  // ===== System ingresses =====

  public static final IntentSourceRef SYSTEM_INTERNAL =
      new IntentSourceRef("core.system-internal");

  private CoreIntentSourceCatalog() {}

  /** Build the CORE intent-source catalog. */
  public static IntentSourceCatalog catalog() {
    Provenance core = Provenance.core("1.0");
    List<IntentSource> sources =
        List.of(
            source(
                UI_RAIL,
                "ui-rail",
                core,
                SourceTier.TRUSTED,
                "core.rail-click",
                TransportTag.RAIL),
            source(
                UI_PALETTE,
                "ui-palette",
                core,
                SourceTier.TRUSTED,
                "core.palette-selection",
                TransportTag.PALETTE),
            source(
                UI_BUTTON,
                "ui-button",
                core,
                SourceTier.TRUSTED,
                "core.action-button",
                TransportTag.BUTTON),
            source(
                URL_BAR,
                "url-bar",
                core,
                SourceTier.MEDIUM,
                "core.url-string",
                TransportTag.URL_BAR),
            source(
                URL_DEEPLINK,
                "url-deeplink",
                core,
                SourceTier.MEDIUM,
                "core.url-string",
                TransportTag.URL_DEEPLINK),
            // Tauri deeplinks share the URL_DEEPLINK transport on the wire — the OS
            // routes the URL from an external app; we trust Tauri's protocol handler
            // but not the originating app's payload. The OS_TAURI_DEEPLINK source id
            // is the platform-level identifier, but the wire transport collapses to
            // URL_DEEPLINK so findByTransport(URL_DEEPLINK) resolves to the canonical
            // URL_DEEPLINK source above. OS_TAURI_DEEPLINK has no transport binding
            // here — it's reachable by id only (or via a future TAURI_DEEPLINK
            // TransportTag if/when the wire shape diverges).
            source(
                OS_TAURI_DEEPLINK,
                "os-tauri-deeplink",
                core,
                SourceTier.MEDIUM,
                "core.url-string",
                null),
            source(
                LLM_CHAT_EMISSION,
                "llm-chat-emission",
                core,
                SourceTier.UNTRUSTED,
                "core.markdown-url",
                TransportTag.LLM_EMISSION),
            source(
                LLM_AGENT_TOOL_CALL,
                "llm-agent-tool-call",
                core,
                SourceTier.UNTRUSTED,
                "core.agent-tool-call",
                TransportTag.AGENT_LOOP),
            source(
                WORKFLOW_TOOL_CALL,
                "workflow-tool-call",
                core,
                SourceTier.UNTRUSTED,
                "core.workflow-tool-call",
                TransportTag.WORKFLOW),
            source(
                MCP_EXTERNAL,
                "mcp-external",
                core,
                SourceTier.UNTRUSTED,
                "core.mcp-json-rpc",
                TransportTag.MCP),
            source(HTTP_API, "http-api", core, SourceTier.UNTRUSTED,
                "core.http-api", TransportTag.HTTP_API),
            // System-internal: TRUSTED. Backend-originated, deterministic, no user
            // payload (e.g., legacy 2-arg OperationDispatcher.dispatch fallback).
            source(
                SYSTEM_INTERNAL,
                "system-internal",
                core,
                SourceTier.TRUSTED,
                "core.system-internal",
                TransportTag.SYSTEM_INTERNAL));
    return IntentSourceCatalog.of(NAMESPACE, sources);
  }

  private static IntentSource source(
      IntentSourceRef id,
      String i18nSlug,
      Provenance provenance,
      SourceTier sourceTier,
      String extractorId,
      TransportTag transport) {
    return new IntentSource(
        id,
        Presentation.of(
            new I18nKey("intent-source.core." + i18nSlug + ".label"),
            new I18nKey("intent-source.core." + i18nSlug + ".description")),
        provenance,
        sourceTier,
        extractorId,
        false,
        Optional.ofNullable(transport));
  }
}
