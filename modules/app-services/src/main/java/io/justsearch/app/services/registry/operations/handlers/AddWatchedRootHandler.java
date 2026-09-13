/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.add-watched-root}.
 *
 * <p>Slice 3a-2-c — LibraryView Add Folder migration. Delegates to
 * {@link IndexingService#addWatchedRoot(String, Path)} via lazy supplier
 * (mirrors ClearFailedJobsHandler / ReindexHandler patterns).
 *
 * <p>Args shape: {@code {"path": string, "collection"?: string}}. Path is
 * required; collection defaults to {@code "default"} when absent.
 *
 * <p>Tempdoc 913 D6: a supplied collection is validated through {@link IngestCollectionPolicy}, the
 * same ONE authority {@code POST /api/indexing/roots} uses. It was not, and this handler — not the
 * REST route — is the path every UI and agent invocation takes, so an agent could create a watched
 * root tagged {@code agent-history} and every document that root's scan admits would inherit the
 * transcript corpus's default-EXCLUDED search posture. A watched root's collection tags documents
 * exactly as an ad-hoc ingest does, so it is subject to the same reserved-name guard.
 */
public final class AddWatchedRootHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(AddWatchedRootHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public AddWatchedRootHandler(Supplier<IndexingService> indexingSupplier) {
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

    // Tempdoc 913 D6 — the reserved-name guard, routed through the ONE authority rather than a
    // second copy of the rule (IndexingController does the same at its own boundary). Deliberately
    // OUTSIDE the JSON try above: a rejected collection is a caller error with its own message, not
    // a malformed-arguments failure. The typed INVALID_REQUEST code is the Operation-layer spelling
    // of the 400 the REST route returns, so a consumer branching on the code sees one answer from
    // both surfaces.
    try {
      collection = io.justsearch.app.api.knowledge.IngestCollectionPolicy.normalizeRequested(collection);
    } catch (IllegalArgumentException e) {
      return OperationResult.failure(
          e.getMessage(), io.justsearch.app.api.ApiErrorCode.INVALID_REQUEST.name(), Map.of(), false);
    }

    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("AddWatchedRootHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }

    try {
      Path p = Paths.get(pathArg).toAbsolutePath().normalize();
      // Slice 450 §2.3 — match the REST handler's validation:
      // POST /api/indexing/roots returns INVALID_PATH when the path doesn't
      // resolve to an existing directory. The Operation handler must apply
      // the same check so it can't add a root that no walker will ever
      // visit (silent indexing failure mode).
      if (!Files.isDirectory(p)) {
        return OperationResult.failure(
            "Path does not exist or is not a directory: " + p);
      }
      indexing.addWatchedRoot(collection, p, engineContext);
      return OperationResult.success(
          "Added watched root " + p, Map.of("path", p.toString(), "collection", collection));
    } catch (Exception e) {
      log.error("AddWatchedRootHandler: addWatchedRoot threw", e);
      return OperationResult.failure("Add watched root failed: " + e.getMessage());
    }
  }
}
