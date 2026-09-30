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
}
