/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The user's own llama-server, named by {@code justsearch.inference.external_endpoint} (ADR-0051
 * decision 2, reconciliation ruling CH4).
 *
 * <p>This explicit setting is the only way the Engine uses a server it did not start. Such a
 * server is reported and never stopped. Only a loopback HTTP URL with an explicit port is
 * accepted, because every request to it is built against the loopback host; anything else is
 * refused with a reason rather than silently reinterpreted.
 */
record ExternalInferenceEndpoint(String url, int port) {
  static final String CONFIG_KEY = "justsearch.inference.external_endpoint";

  /**
   * Empty when the setting is blank (the default: no user server).
   *
   * @throws IllegalArgumentException naming why a set value cannot be used
   */
  static Optional<ExternalInferenceEndpoint> parse(String raw) {
    if (raw == null || raw.isBlank()) return Optional.empty();
    String value = raw.trim();
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw refused(value, "it is not a URL");
    }
    if (!"http".equalsIgnoreCase(uri.getScheme())) {
      throw refused(value, "only http:// is supported");
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!host.equals("127.0.0.1") && !host.equals("localhost")) {
      throw refused(value, "the host must be 127.0.0.1 or localhost");
    }
    int port = uri.getPort();
    if (port < 1 || port > 65_535) {
      throw refused(value, "it must name a port, as in http://127.0.0.1:8080");
    }
    String path = uri.getRawPath();
    if ((path != null && !path.isEmpty() && !path.equals("/"))
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw refused(value, "it must be the server root, without a path");
    }
    return Optional.of(new ExternalInferenceEndpoint(value, port));
  }

  private static IllegalArgumentException refused(String value, String why) {
    return new IllegalArgumentException(
        CONFIG_KEY + "=" + value + " is refused: " + why + ". Set it to the loopback URL of your"
            + " own llama-server, or clear it to let JustSearch run its own.");
  }
}
