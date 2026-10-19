/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceRef;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Projects a {@link Surface} + {@link StateSnapshot} pair into a canonical
 * {@code justsearch://surface/...} URL string.
 *
 * <p>Per slice 489 §5.5 / §7.1 / §12: a sibling of {@code URLOperationEmitter}
 * (shipped by slice 487 Phase 2.1 as forward-compat substrate; no production
 * consumer in V1). Where {@code URLOperationEmitter} handles operation
 * invocation URLs, this emitter handles surface activation addresses — the
 * {@link ShellAddress.Navigation} half of the address taxonomy.
 *
 * <p>Surface-navigation URL grammar:
 *
 * <pre>
 *   justsearch://surface/&lt;surfaceId&gt;[?key=value&amp;key=value...]
 * </pre>
 *
 * <p>Like the frontend URL parser, keys are sorted alphabetically and array
 * values produce repeated keys in array order ({@code ?ids=a&ids=b} not
 * {@code ?ids=a,b}). Value encoding differs: Java uses {@code URLEncoder}
 * (space → +, apostrophe → %27); the frontend uses {@code encodeURIComponent}
 * (space → %20, apostrophe unchanged).
 *
 * <p>Per slice 489 §13 anti-pattern #3: the Java and TS implementations of
 * the grammar must stay in sync. The frontend URL parser and this emitter
 * share key ordering and repeated-key handling, with the encoding differences above.
 * A future wire-contract integration ({@code contracts/wire/}) can drive both sides from one source,
 * but is out of scope for slice 489.
 *
 * <p>Per slice 489 §13 anti-pattern #6 (and the parametric
 * {@link OperationEmitter#allowedAudiences} hoist precedent — slice 491
 * Phase B commit {@code d4befdca8}): audience filtering for URL-emittable
 * surfaces is a future enhancement layered via constructor parameterization;
 * the v1 emitter has no audience filter (surfaces are already audience-gated
 * upstream via the registry validator).
 *
 * <p>Stateless. Pure function. Safe to call from any thread.
 */
public final class URLSurfaceEmitter {

  public static final String SCHEME = "justsearch";
  public static final String HOST = "surface";

  /**
   * Project a Surface activation address (target + state) into a canonical URL string.
   *
   * @param target the {@link SurfaceRef} naming the surface
   * @param state the state values to encode as query args; empty produces a
   *     bare {@code justsearch://surface/&lt;id&gt;} with no query string
   * @return the canonical URL string
   * @throws NullPointerException if either argument is null
   */
  public String toUrl(SurfaceRef target, StateSnapshot state) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(state, "state");
    StringBuilder sb = new StringBuilder();
    sb.append(SCHEME).append("://").append(HOST).append('/').append(target.value());
    String query = encodeQuery(state.values());
    if (!query.isEmpty()) {
      sb.append('?').append(query);
    }
    return sb.toString();
  }

  /**
   * Project a {@link ShellAddress.Navigation} address into a canonical URL string.
   *
   * <p>Convenience overload for callers that already have a {@code Navigation}
   * address in hand (e.g., the FE→backend handshake or the canonicalization
   * step in the §7 router pipeline).
   */
  public String toUrl(ShellAddress.Navigation address) {
    Objects.requireNonNull(address, "address");
    return toUrl(address.target(), address.state());
  }

  /**
   * Project a Surface manifest into a URL template (the bare surface URL with
   * no state arguments).
   *
   * <p>Useful for catalog-wide URL emission (e.g., for documentation, for
   * surfacing "all addressable surfaces" to MCP clients, for E2E test fixture
   * generation).
   */
  public String toUrlTemplate(Surface surface) {
    Objects.requireNonNull(surface, "surface");
    return toUrl(surface.id(), StateSnapshot.empty());
  }

  // ----- internal helpers -----

  private static String encodeQuery(Map<String, Object> values) {
    if (values.isEmpty()) {
      return "";
    }
    // StateSnapshot's compact constructor enforces non-null values via Map.copyOf,
    // so the emitter does not need to defend against nulls in this map. Items
    // inside a List<?> value, however, can in principle be null when callers
    // construct the list manually — defensive skip preserved for that case.
    //
    // Canonical URL convention: sort keys and encode each value.
    Map<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
    sorted.putAll(values);
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> entry : sorted.entrySet()) {
      String encodedKey = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
      Object value = entry.getValue();
      if (value instanceof List<?> list) {
        for (Object item : list) {
          if (item != null) {
            parts.add(encodedKey + "=" + encode(item));
          }
        }
      } else if (value instanceof Object[] arr) {
        for (Object item : arr) {
          if (item != null) {
            parts.add(encodedKey + "=" + encode(item));
          }
        }
      } else {
        parts.add(encodedKey + "=" + encode(value));
      }
    }
    return String.join("&", parts);
  }

  private static String encode(Object value) {
    return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
  }
}
