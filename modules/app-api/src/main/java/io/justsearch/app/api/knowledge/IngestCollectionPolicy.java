/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.nio.file.Path;
import java.util.List;
import io.justsearch.configuration.InternalCollections;
import java.util.Set;

/**
 * Tempdoc 811 decision C-2a — the ONE authority for "which collection does an ad-hoc ingest write
 * into?".
 *
 * <p>Before 811 both ad-hoc ingest surfaces (HTTP {@code POST /api/knowledge/ingest} and the
 * Operation-backed {@code core.ingest-files} / MCP {@code justsearch_ingest} tool) passed a literal
 * {@code null} collection, so the documents they wrote were unlabeled: absent from Library&gt;Folders,
 * passing every collection clause (including the agent-history {@code MUST_NOT}), and unreachable by
 * any prune path. This class gives them an addressable identity.
 *
 * <p>Resolution order: an explicit caller-supplied collection wins; otherwise a path under a
 * registered watched root inherits that root's collection (so an ad-hoc ingest of an already-indexed
 * folder does not fork a second label for the same documents); otherwise the path is out-of-root and
 * gets {@link #OUT_OF_ROOT}.
 */
public final class IngestCollectionPolicy {

  /** Default collection for ad-hoc ingests of paths that are under no registered watched root. */
  public static final String OUT_OF_ROOT = "mcp-ingest";

  /**
   * The index-wide default bucket. Not a real Lucene {@code collection} term — documents indexed
   * without a collection carry no {@code collection} field at all ({@code IndexingDocumentOps}
   * writes it only when non-blank) and are reported as {@code "default"} by the roots API.
   */
  public static final String DEFAULT_COLLECTION = "default";

  /**
   * App-internal collections a user ingest must never impersonate: {@code justsearch-help} is the
   * bundled help corpus ({@code KnowledgeServerBootstrap.tryIngestHelpFiles}) and {@code
   * agent-history} is the transcript corpus that {@code QueryFilterBuilder.addCollectionScope}
   * default-excludes. Letting a caller tag documents with either would let arbitrary content
   * inherit an app-internal document's search posture.
   */
  private static final Set<String> RESERVED = InternalCollections.RESERVED;

  private IngestCollectionPolicy() {}

  /** A watched root and the collection its documents are tagged with (null = index default). */
  public record RootBinding(Path path, String collection) {}

  /** Returns the reserved, app-internal collection names, lowercase. */
  public static Set<String> reservedCollections() {
    return RESERVED;
  }

  /** True when {@code collection} names an app-internal corpus a caller must not write into. */
  public static boolean isReserved(String collection) {
    return InternalCollections.isReserved(collection);
  }

  /**
   * Validates a caller-supplied collection.
   *
   * @param raw the requested value; {@code null} means "not supplied"
   * @return the trimmed collection, or {@code null} when none was supplied
   * @throws IllegalArgumentException when supplied but blank or reserved; the message is
   *     caller-facing and names the reason
   */
  public static String normalizeRequested(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("collection must be a non-empty string when supplied");
    }
    if (isReserved(trimmed)) {
      throw new IllegalArgumentException(
          "collection '"
              + trimmed
              + "' is reserved for app-internal documents ("
              + String.join(", ", RESERVED.stream().sorted().toList())
              + ") and cannot be assigned to a user ingest");
    }
    return trimmed;
  }

  /**
   * Resolves the collection an ingested path should be tagged with.
   *
   * @param requested an already-{@linkplain #normalizeRequested validated} caller value, or null
   * @param input the path being ingested
   * @param roots the registered watched roots (may be null/empty when the Worker is unreachable)
   * @return the collection tag, or {@code null} to mean "the index default" — which is what an
   *     in-root path under a root that itself carries no collection has always resolved to
   */
  public static String resolve(String requested, Path input, List<RootBinding> roots) {
    if (requested != null && !requested.isBlank()) {
      return requested.trim();
    }
    RootBinding best = null;
    int bestDepth = -1;
    if (roots != null && input != null) {
      Path target = input.toAbsolutePath().normalize();
      for (RootBinding r : roots) {
        if (r == null || r.path() == null) {
          continue;
        }
        Path rootPath = r.path().toAbsolutePath().normalize();
        // Path.startsWith is element-wise (and case-insensitive on Windows), so it cannot match a
        // sibling that merely shares a textual prefix.
        if (target.startsWith(rootPath) && rootPath.getNameCount() > bestDepth) {
          best = r;
          bestDepth = rootPath.getNameCount();
        }
      }
    }
    if (best == null) {
      return OUT_OF_ROOT;
    }
    String rootCollection = best.collection();
    return rootCollection == null || rootCollection.isBlank() ? null : rootCollection;
  }

  /**
   * True when a collection may be bulk-deleted by the removal route. Refuses the reserved
   * app-internal corpora and the {@link #DEFAULT_COLLECTION} bucket — the latter because "delete
   * everything untagged" is a whole-index wipe wearing a collection's clothes.
   */
  public static boolean isDeletable(String collection) {
    return collection != null
        && !collection.isBlank()
        && !isReserved(collection)
        && !DEFAULT_COLLECTION.equalsIgnoreCase(collection.trim());
  }
}
