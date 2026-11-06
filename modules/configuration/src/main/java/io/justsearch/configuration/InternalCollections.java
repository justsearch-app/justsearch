/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.util.Locale;
import java.util.Set;

/** Collection identities owned by the application, shared by resolution and admission policy. */
public final class InternalCollections {
  public static final String HELP = "justsearch-help";
  public static final String AGENT_HISTORY = "agent-history";
  public static final Set<String> RESERVED = Set.of(HELP, AGENT_HISTORY);

  private InternalCollections() {}

  public static boolean isReserved(String collection) {
    return collection != null && RESERVED.contains(collection.trim().toLowerCase(Locale.ROOT));
  }

  /** Resolve once from the same configuration authority used by the Engine. */
  public static java.nio.file.Path dataDirectory() {
    var store = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var configured = store == null ? null : store.get().paths().dataDir();
    return (configured == null ? PlatformPaths.resolveDataDir() : configured).toAbsolutePath().normalize();
  }

  public static boolean isDataPath(String path) {
    if (path == null || path.isBlank()) return false;
    try {
      var candidate = java.nio.file.Path.of(path).toAbsolutePath().normalize();
      var data = dataDirectory();
      if (candidate.startsWith(data)) return true;
      try { return candidate.toRealPath().startsWith(data.toRealPath()); }
      catch (java.io.IOException unavailable) { return false; }
    }
    catch (java.nio.file.InvalidPathException invalid) { return false; }
  }

  public static boolean includesHistory(Object collections) {
    if (collections instanceof String value) return AGENT_HISTORY.equalsIgnoreCase(value.trim());
    return collections instanceof Iterable<?> values
        && java.util.stream.StreamSupport.stream(values.spliterator(), false)
            .anyMatch(InternalCollections::includesHistory);
  }

}
