/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.LoggerNamespaceSelector;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.Provenance;
import java.util.List;
import java.util.Set;

/**
 * Slice 448 V1: the canonical engine-log {@link DiagnosticChannel}.
 *
 * <p>Subscribes consumers to the Engine JVM's Logback emissions via SSE. Sub-category
 * resolution (CORE_DIAGNOSTIC / LIBRARY_TRACE / BOOT_TRACE / DELIVERY_INTERNAL) is wired
 * to the empirical-scan-driven default selector at
 * {@link LoggerNamespaceSelector#defaultEngineLog()}.
 *
 * <p>The default {@code dataClasses} set declares what the channel is structurally
 * capable of emitting (see slice 448 §0 D1 — empirical scan: 59% of lines contain user
 * paths, with substantial overlap between USER_PATHS and CONFIG_VALUES). Per-event
 * {@code Set<DataClass>} extensions may add classes for specific emissions; consumers
 * receive the union of channel-default and per-event sets.
 */
public final class EngineLogDiagnosticChannelCatalog implements DiagnosticChannelCatalog {

  /** Shared namespace with the other core catalogs. */
  public static final String NAMESPACE = "core";

  public static final DiagnosticChannelRef ENGINE_LOG_ID =
      new DiagnosticChannelRef("core.engine-log");

  /** SSE endpoint for live-tail consumption. Wired to the head Javalin server. */
  public static final String ENDPOINT = "/api/diagnostic-channels/engine-log/stream";

  private static final List<DiagnosticChannel> DEFINITIONS =
      List.of(
          new DiagnosticChannel(
              ENGINE_LOG_ID,
              Presentation.of(
                  new I18nKey("registry-diagnostic.engine-log.label"),
                  new I18nKey("registry-diagnostic.engine-log.description")),
              Set.of(
                  DataClass.USER_PATHS,
                  DataClass.CONFIG_VALUES,
                  DataClass.EXCEPTION_BODIES),
              ProducerKind.IN_PROCESS_LOGBACK,
              DeliveryMode.SSE_STREAM,
              LoggerNamespaceSelector.defaultEngineLog(),
              ENDPOINT,
              ConsumerPermission.OPERATOR_OVERRIDE,
              Provenance.core("1.0")));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<DiagnosticChannel> definitions() {
    return DEFINITIONS;
  }
}
