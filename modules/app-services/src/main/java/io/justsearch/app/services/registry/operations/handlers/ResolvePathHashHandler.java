/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.resolve-path-hash} (slice 445 §A.9).
 *
 * <p>The substrate-routed equivalent of {@code POST /api/library/resolve-hash}.
 * Wraps {@link IndexingService#resolvePathHash} so FE consumers reading the
 * {@code core.indexing-jobs} Resource catalog can resolve a {@code pathHash}
 * to a path through the same Operation pipeline as other actions.
 *
 * <p>This Operation is the privacy resolver pinned in
 * {@link io.justsearch.app.observability.indexing.IndexingJobsResourceCatalog}'s
 * {@code Privacy.HASHED_REQUIRES_RESOLVER}. ADR-0028 +
 * {@code LibraryResolveHashOnlyCallerPin} continue to bound the caller set
 * for the underlying {@code PathResolutionStore}; this handler is a new
 * approved caller path through {@link IndexingService} (already in the pin's
 * approved-callers list).
 *
 * <p>Args JSON shape: {@code {"pathHash": "<sha256-hex>"}}.
 *
 * <p>Result success structuredData mirrors the underlying API: {@code found},
 * and when found, {@code path}, {@code lastSeenAtMs}, {@code removedAtMs}.
 */
public final class ResolvePathHashHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ResolvePathHashHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public ResolvePathHashHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String pathHash;
    try {
      JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
      JsonNode hashNode = root.get("pathHash");
      if (hashNode == null || hashNode.isNull() || hashNode.asString().isBlank()) {
        return OperationResult.failure("Missing required argument: pathHash");
      }
      pathHash = hashNode.asString().trim();
    } catch (RuntimeException e) {
      return OperationResult.failure("Invalid arguments JSON: " + e.getMessage());
    }
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ResolvePathHashHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      Map<String, Object> result = indexing.resolvePathHash(pathHash, engineContext);
      boolean found = Boolean.TRUE.equals(result.get("found"));
      if (!found) {
        // Slice 450 §2.1 — head-side fallback: the worker's PathResolutionStore
        // only holds per-file hashes (registered when indexing jobs flow). Watched
        // roots (parent folders shown by the core.indexed-roots Resource) are
        // never stored there because they are not jobs — only their children are.
        // So when the worker reports not-found, walk the watched-roots set
        // head-side, re-hash with the same SHA-256, and return the match.
        // This preserves ADR-0028 + LibraryResolveHashOnlyCallerPin semantics
        // because the data is already head-side and loopback-only.
        Map<String, Object> headSide = resolveAgainstWatchedRoots(indexing, pathHash, engineContext);
        if (Boolean.TRUE.equals(headSide.get("found"))) {
          return OperationResult.success("Path resolved (head-side)", headSide);
        }
      }
      return OperationResult.success(
          found ? "Path resolved" : "No path on record for that hash", result);
    } catch (RuntimeException e) {
      log.error("ResolvePathHashHandler: resolvePathHash threw", e);
      return OperationResult.failure("Resolve path-hash failed: " + e.getMessage());
    }
  }

  private static Map<String, Object> resolveAgainstWatchedRoots(
      IndexingService indexing, String pathHash, EngineContext engineContext) {
    try {
      var roots = indexing.getWatchedRoots(engineContext);
      for (var root : roots) {
        String hashed = sha256Hex(root.path().toString());
        if (hashed.equalsIgnoreCase(pathHash)) {
          Map<String, Object> hit = new java.util.LinkedHashMap<>();
          hit.put("found", true);
          hit.put("path", root.path().toString());
          hit.put(
              "lastSeenAtMs",
              root.lastIndexed() != null ? root.lastIndexed().toEpochMilli() : 0L);
          hit.put("removedAtMs", 0L);
          return hit;
        }
      }
    } catch (RuntimeException e) {
      log.debug("ResolvePathHashHandler: watched-roots fallback threw", e);
    }
    return Map.of("found", false);
  }

  private static String sha256Hex(String value) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest =
          md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
