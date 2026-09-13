/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.SubCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Marker;

/**
 * Logback appender that bridges Head-process log emissions into a {@link
 * DiagnosticChannelStreamRegistry}.
 *
 * <p>Per slice 448 phase 3: the first custom Logback appender in the JustSearch codebase.
 * Attached programmatically to the root logger at app startup (see {@link
 * io.justsearch.app.services.HeadAssembly}); not declared in {@code logback.xml}
 * because the appender depends on a runtime Java reference (the registry).
 *
 * <h3>append() flow</h3>
 *
 * <ol>
 *   <li><b>Recursion carve-out</b>: drop events marked with {@link
 *       DiagnosticChannelInternalMarker#get()}. Combined with Logback's built-in
 *       {@code AppenderBase.doAppend()} per-thread recursion guard (which handles
 *       same-thread re-entry — see Logback manual §"Filters" + §"Why is logging so
 *       complicated?"; an upstream-ThreadLocal-based guard in {@code AppenderBase}
 *       returns silently if {@code doAppend} is re-entered on the same thread), this
 *       prevents the firehose from observing itself across both same-thread and
 *       cross-thread emit paths.
 *   <li><b>Resolve {@link SubCategory}</b> via the channel's
 *       {@link io.justsearch.agent.api.registry.LoggerNamespaceSelector}.
 *   <li><b>Capture MDC</b> via {@code event.getMDCPropertyMap()}. Per slice 448 §B.A.G1
 *       fix: the captured MDC reflects the originating thread's state at log-call time
 *       (Logback's logging thread populates it before queueing). The appender does NOT
 *       need thread-context propagation gymnastics; this is the cleanest capture point.
 *   <li><b>Build {@link DiagnosticEvent}</b> with the channel's default {@code dataClasses}
 *       set. Per slice 448 §0 D1: per-event extension of the set comes when emitter
 *       sites declare event-specific classes (V1 uses channel default verbatim).
 *   <li><b>Publish</b> a {@link DiagnosticEventEnvelope#KIND_LOG_EVENT} envelope through
 *       the registry, which fans out to subscribed SSE clients.
 * </ol>
 *
 * <p>The appender does NOT perform any HTTP/Javalin work itself; that lives in
 * {@code DiagnosticChannelStreamController}. Keeping the appender free of HTTP coupling
 * makes it unit-testable in isolation.
 */
public final class DiagnosticChannelAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final DiagnosticChannelStreamRegistry registry;
  private final DiagnosticChannel channel;

  /**
   * Constructs the appender against the registry + a single-channel catalog. V1 ships
   * exactly one DiagnosticChannel (the {@code EngineLogDiagnosticChannelCatalog}'s
   * {@code core.engine-log}); the appender publishes every (non-marker-filtered) log event
   * to that channel. Multi-channel routing (one log event fanned to multiple channels by
   * sub-category) is a phase-5+ concern and would extend the catalog parameter to a list.
   */
  public DiagnosticChannelAppender(
      DiagnosticChannelStreamRegistry registry, DiagnosticChannelCatalog catalog) {
    this.registry = Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(catalog, "catalog");
    if (catalog.definitions().isEmpty()) {
      throw new IllegalArgumentException(
          "DiagnosticChannelAppender requires a catalog with at least one channel; got 0");
    }
    if (catalog.definitions().size() > 1) {
      throw new IllegalArgumentException(
          "DiagnosticChannelAppender V1 supports a single-channel catalog; got "
              + catalog.definitions().size()
              + " channels — multi-channel routing is a phase-5+ concern");
    }
    this.channel = catalog.definitions().get(0);
  }

  /** The channel id this appender publishes to. Useful for test assertions. */
  public DiagnosticChannelRef channelId() {
    return channel.id();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null) {
      return;
    }
    if (isDeliveryInternal(event)) {
      return;
    }
    final String loggerName = event.getLoggerName() == null ? "" : event.getLoggerName();
    final SubCategory subCategory = channel.selector().resolve(loggerName);
    // event.getMDCPropertyMap() returns the MDC at log-call time on the originating
    // thread (per slice 448 §B.A.G1 fix). Treat null or unavailable-MDC-adapter as empty
    // (the latter occurs in unit tests that build their own LoggerContext without
    // wiring SLF4J's MDCAdapter — production code paths via the global LoggerContext
    // always have it set).
    Map<String, String> mdcSnapshot;
    try {
      final Map<String, String> raw = event.getMDCPropertyMap();
      mdcSnapshot = raw == null ? Map.of() : raw;
    } catch (RuntimeException npeOrIse) {
      mdcSnapshot = Map.of();
    }
    final Map<String, String> mdc = mdcSnapshot;
    // ILoggingEvent.getThreadName() can be null when the event is constructed manually
    // (e.g., in tests) and not routed through Logback's deferred-processing pipeline.
    // Fall back to the current thread's name to keep the wire shape well-formed.
    final String threadName =
        event.getThreadName() == null ? Thread.currentThread().getName() : event.getThreadName();
    final String level = event.getLevel() == null ? "INFO" : event.getLevel().toString();
    final String message = event.getFormattedMessage() == null ? "" : event.getFormattedMessage();
    final DiagnosticEvent diag =
        new DiagnosticEvent(
            level,
            message,
            loggerName,
            threadName,
            Thread.currentThread().threadId(),
            Instant.ofEpochMilli(event.getTimeStamp()),
            mdc,
            channel.dataClasses(),
            subCategory);
    registry.publish(channel.id(), DiagnosticEventEnvelope.ofLogEvent(diag));
  }

  private static boolean isDeliveryInternal(ILoggingEvent event) {
    // Logback ILoggingEvent.getMarkerList() (1.5+) returns a list rather than a single
    // marker; fall back to getMarker() for backwards-compatibility with code that
    // assumes the legacy single-marker accessor.
    for (final Marker m : event.getMarkerList() == null ? java.util.List.<Marker>of() : event.getMarkerList()) {
      if (DiagnosticChannelInternalMarker.matches(m)) {
        return true;
      }
    }
    return false;
  }
}
