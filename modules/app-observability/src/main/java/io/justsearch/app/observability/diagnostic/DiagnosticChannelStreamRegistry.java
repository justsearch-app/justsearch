/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registry mapping each {@link DiagnosticChannelRef} to its own {@link SseStreamChannel}.
 *
 * <p>Per slice 448 phase 3 D2: V1 registers exactly one channel
 * ({@code core.engine-log}); the multi-channel shape is built in from day one so plugin-
 * supplied {@link DiagnosticChannelCatalog}s plug in mechanically when they ship.
 *
 * <p>Each {@link SseStreamChannel} carries the slice 436 universal envelope discipline
 * (monotonic seq, ring buffer for resume, listener-removal-on-throw). The registry adds
 * routing-by-id on top.
 *
 * <p>Stream-id naming convention: {@code system:diagnostic-<channel-id-value>} (e.g.,
 * {@code system:diagnostic-core.engine-log}). Mirrors the {@link StreamId#system(String)}
 * factory used by infrastructure-class streams.
 */
public final class DiagnosticChannelStreamRegistry {

  private final Map<DiagnosticChannelRef, SseStreamChannel> channels;

  public DiagnosticChannelStreamRegistry(DiagnosticChannelCatalog... catalogs) {
    Objects.requireNonNull(catalogs, "catalogs");
    final Map<DiagnosticChannelRef, SseStreamChannel> built = new LinkedHashMap<>();
    for (final DiagnosticChannelCatalog catalog : catalogs) {
      Objects.requireNonNull(catalog, "catalogs[i]");
      for (final DiagnosticChannel definition : catalog.definitions()) {
        final DiagnosticChannelRef id = definition.id();
        if (built.containsKey(id)) {
          throw new IllegalStateException(
              "DiagnosticChannelRef conflict across catalogs: " + id.value());
        }
        // StreamId regex restricts to [a-z][a-z0-9-]*; the channel id namespacing uses
        // dots (e.g., "core.engine-log"), so flatten to a stream-id-safe form.
        final String streamSlug = "diagnostic-" + id.value().replace('.', '-');
        built.put(id, new SseStreamChannel(StreamId.system(streamSlug)));
      }
    }
    this.channels = Map.copyOf(built);
  }

  /** Returns the SSE stream channel for the requested id; throws if unregistered. */
  public SseStreamChannel channel(DiagnosticChannelRef id) {
    Objects.requireNonNull(id, "id");
    final SseStreamChannel channel = channels.get(id);
    if (channel == null) {
      throw new IllegalArgumentException(
          "DiagnosticChannelRef not registered: " + id.value());
    }
    return channel;
  }

  /** Publishes an envelope on the channel for {@code id}. Called by the appender. */
  public void publish(DiagnosticChannelRef id, DiagnosticEventEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    channel(id).publish(SseFrameKind.UPDATE, envelope);
  }

  /** Returns the set of registered channel ids; useful for tests + introspection. */
  public Set<DiagnosticChannelRef> registeredIds() {
    return channels.keySet();
  }
}
