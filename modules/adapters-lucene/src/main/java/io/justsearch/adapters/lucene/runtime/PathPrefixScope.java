/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/** A frozen literal directory scope, shared by deletion, queue filtering and replay proofs. */
public record PathPrefixScope(String prefix, List<String> excludedSubtrees) {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public PathPrefixScope {
    prefix = normalized(prefix);
    if (excludedSubtrees == null || excludedSubtrees.size() > 1024) {
      throw new IllegalArgumentException("Invalid excluded subtree list");
    }
    var exclusions = new TreeSet<String>();
    for (String subtree : excludedSubtrees) {
      String normalized = normalized(subtree);
      if (normalized.equals(prefix) || !normalized.startsWith(prefix)) {
        throw new IllegalArgumentException("Excluded subtree must be a descendant of its prefix");
      }
      exclusions.add(normalized);
    }
    excludedSubtrees = List.copyOf(exclusions);
  }

  private static String normalized(String path) {
    if (path == null || path.isBlank() || path.length() > 32768) {
      throw new IllegalArgumentException("Invalid directory prefix");
    }
    return QueryFilterBuilder.normalizePathPrefix(java.nio.file.Path.of(path).normalize().toString());
  }

  public boolean excludes(String path) {
    if (path == null) return false;
    String normalized = QueryFilterBuilder.normalizePathPrefix(path);
    return excludedSubtrees.stream().anyMatch(normalized::startsWith);
  }

  public boolean contains(String path) {
    return path != null && QueryFilterBuilder.normalizePathPrefix(path).startsWith(prefix)
        && !excludes(path);
  }

  public String encode() {
    var node = JSON.createObjectNode();
    node.put("version", 1);
    node.put("prefix", prefix);
    var exclusions = node.putArray("excludedSubtrees");
    excludedSubtrees.forEach(exclusions::add);
    return JSON.writeValueAsString(node);
  }

  public static boolean isLegacy(String payload) {
    return payload != null && !payload.startsWith("{");
  }

  public static PathPrefixScope decode(String payload) {
    if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Invalid prefix receipt");
    if (isLegacy(payload)) return new PathPrefixScope(payload, List.of());
    var node = JSON.readTree(payload);
    if (!node.isObject() || !node.path("version").isInt() || node.path("version").intValue() != 1
        || !node.path("prefix").isString() || !node.path("excludedSubtrees").isArray()
        || node.size() != 3) throw new IllegalArgumentException("Invalid prefix receipt");
    var exclusions = new java.util.ArrayList<String>();
    for (var entry : node.path("excludedSubtrees")) {
      if (!entry.isString()) throw new IllegalArgumentException("Invalid prefix exclusion");
      exclusions.add(entry.stringValue());
    }
    return new PathPrefixScope(node.path("prefix").stringValue(), exclusions);
  }
}
