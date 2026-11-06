/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One ownership decision for query filters, selected IDs, projections and retrieval fallbacks.
 * Instances are read-attempt local: neither filesystem uncertainty nor parent facts are cached
 * beyond the reader/request that established them. This metadata is produced by the index owner,
 * never accepted from HTTP headers or request bodies. */
public final class ReadOwnership {
  public static final String METADATA = "read_ownership";
  public enum Owner { PUBLIC, APP_DATA, HISTORY, UNCERTAIN }
  private final Path data = InternalCollections.dataDirectory();
  private final Map<String, Owner> sources = new HashMap<>();
  private Path realData;

  public Owner owner(String id, Map<String, ?> metadata, Function<String, Map<String, ?>> parents) {
    return owner(id, metadata == null ? Map.of() : metadata, parents, new HashSet<>());
  }

  private Owner owner(String id, Map<String, ?> metadata,
      Function<String, Map<String, ?>> parents, java.util.Set<String> visited) {
    if (id != null && (!visited.add(id) || visited.size() > 64)) return Owner.PUBLIC;
    Owner result = Owner.PUBLIC;
    String certified = text(metadata.get(METADATA));
    if (certified != null) {
      try { result = Owner.valueOf(certified); }
      catch (IllegalArgumentException invalid) { return Owner.UNCERTAIN; }
    }
    String collection = text(metadata.get("collection"));
    if (InternalCollections.includesHistory(collection)) return Owner.HISTORY;
    if (InternalCollections.HELP.equals(collection)) result = combine(result, Owner.APP_DATA);
    // The index owner already joined the parent on the same immutable reader. Keep that decision
    // across the transport/fallback, including PUBLIC for an ordinary offline source's chunk.
    if (certified != null) return result;
    String path = text(metadata.get("path"));
    String parentId = text(metadata.get("parent_doc_id"));
    Map<String, ?> parentFacts = parentId == null || parentId.isBlank() || parents == null
        ? null : parents.apply(parentId);
    String sourceCollection = collection;
    if ((sourceCollection == null || sourceCollection.isBlank()) && parentFacts != null) {
      sourceCollection = text(parentFacts.get("collection"));
    }
    for (String source : new String[] {path, parentId == null ? id : null}) {
      if (source != null) result = combine(result, sourceOwner(source, sourceCollection));
    }
    // A chunk ID may be synthetic, but a positive private identity still cannot be overridden
    // by its projected path or by a parent's ordinary collection.
    if (id != null && parentId != null) {
      Owner identity = sourceOwner(id, sourceCollection);
      if (identity != Owner.UNCERTAIN) result = combine(result, identity);
    }
    String parent = text(metadata.get("parent_doc_id"));
    if (parent != null && !parent.isBlank()) {
      if (parents == null) return combine(result, sourceOwner(parent, collection));
      Map<String, ?> facts = parentFacts;
      result = combine(result, facts == null || facts.isEmpty() ? sourceOwner(parent, collection)
          : owner(parent, facts, parents, visited));
    }
    return result;
  }

  private Owner sourceOwner(String source, String collection) {
    Owner owner = sources.computeIfAbsent(source, this::sourceOwner);
    boolean legacyHistory = legacyHistorySource(source, collection);
    if (owner == Owner.APP_DATA && legacyHistory) return Owner.HISTORY;
    return owner == Owner.UNCERTAIN && !legacyHistory ? Owner.PUBLIC : owner;
  }

  /** Only legacy untagged history-shaped sources need a positive identity proof. Offline ordinary
   * sources retain their indexed visibility; failure to resolve is not evidence of ownership. */
  static boolean legacyHistorySource(String source, String collection) {
    if ((collection != null && !collection.isBlank()) || source == null) return false;
    try {
      Path parent = Path.of(source).getParent();
      if (parent != null) for (Path component : parent) {
        if (component.toString().equalsIgnoreCase(InternalCollections.AGENT_HISTORY)) return true;
      }
    } catch (java.nio.file.InvalidPathException invalid) { /* No identifiable history directory. */ }
    return false;
  }

  private Owner sourceOwner(String source) {
    try {
      Path path = Path.of(source);
      if (!path.isAbsolute()) return Owner.PUBLIC; // Non-file projections have no filesystem owner.
      path = path.normalize();
      if (path.startsWith(data.resolve("agent-history"))) return Owner.HISTORY;
      if (path.startsWith(data)) return Owner.APP_DATA;
      try {
        Path real = path.toRealPath();
        if (realData == null) {
          try { realData = data.toRealPath(); }
          catch (java.io.IOException unavailable) { realData = data; }
        }
        if (real.startsWith(realData.resolve("agent-history"))) return Owner.HISTORY;
        return real.startsWith(realData) ? Owner.APP_DATA : Owner.PUBLIC;
      } catch (java.io.IOException | SecurityException unavailable) { return Owner.UNCERTAIN; }
    } catch (java.nio.file.InvalidPathException invalid) { return Owner.UNCERTAIN; }
  }

  private static Owner combine(Owner left, Owner right) {
    if (left == Owner.HISTORY || right == Owner.HISTORY) return Owner.HISTORY;
    if (left == Owner.UNCERTAIN || right == Owner.UNCERTAIN) return Owner.UNCERTAIN;
    return left == Owner.APP_DATA || right == Owner.APP_DATA ? Owner.APP_DATA : Owner.PUBLIC;
  }

  private static String text(Object value) { return Objects.toString(value, null); }

  public static boolean hiddenFromDefault(Owner owner) {
    return owner == Owner.HISTORY || owner == Owner.UNCERTAIN;
  }

  public static boolean privateDocument(String id, Map<String, ?> metadata) {
    return new ReadOwnership().owner(id, metadata, null) != Owner.PUBLIC;
  }
}
