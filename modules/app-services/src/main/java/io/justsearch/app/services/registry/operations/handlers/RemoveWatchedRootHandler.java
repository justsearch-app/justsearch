/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.remove-watched-root}.
 *
 * <p>Slice 3a-2-c — LibraryView Remove Folder migration. Delegates to
 * {@link IndexingService#removeWatchedRoot(String, Path)} via lazy supplier.
 *
 * <p>Args shape: {@code {"path": string, "collection"?: string}}. Returns
 * {@code structuredData.deletedJobs} with the int returned by the underlying
 * service (number of deleted jobs from the queue, or -1 on error per
 * IndexingService docstring).
 */
public final class RemoveWatchedRootHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(RemoveWatchedRootHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public RemoveWatchedRootHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String pathArg;
    String collection;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode pathNode = root.get("path");
      if (pathNode == null || !pathNode.isTextual() || pathNode.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: path");
      }
      pathArg = pathNode.asString();
      JsonNode collectionNode = root.get("collection");
      collection =
          collectionNode != null && collectionNode.isTextual() && !collectionNode.asString().isBlank()
              ? collectionNode.asString()
              : "default";
    } catch (Exception e) {
      return HandlerJson.invalidArgs(e);
    }

    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("RemoveWatchedRootHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }

    try {
      Path p = Paths.get(pathArg);
      int deletedJobs = indexing.removeWatchedRoot(collection, p, engineContext);
      return OperationResult.success(
          "Removed watched root " + p + " (" + deletedJobs + " jobs deleted)",
          Map.of(
              "path", p.toAbsolutePath().toString(),
              "collection", collection,
              "deletedJobs", deletedJobs));
    } catch (Exception e) {
      log.error("RemoveWatchedRootHandler: removeWatchedRoot threw", e);
      return OperationResult.failure("Remove watched root failed: " + e.getMessage());
    }
  }
}
