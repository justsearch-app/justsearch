/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.javalin.http.HttpStatus;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.RetrieveContextController;
import java.util.Map;
import org.slf4j.Logger;

public final class KnowledgeRoutes {
  private KnowledgeRoutes() {}

  public static void register(
      Javalin app, KnowledgeSearchController knowledgeSearchController, Logger log) {
    register(app, knowledgeSearchController, null, log);
  }

  public static void register(
      Javalin app,
      KnowledgeSearchController knowledgeSearchController,
      RetrieveContextController retrieveContextController,
      Logger log) {
    if (knowledgeSearchController == null) {
      return;
    }
    app.post("/api/knowledge/search", knowledgeSearchController::handleSearch, ActivityRole.USER_ACTION);
    app.post("/api/knowledge/collection-facet", knowledgeSearchController::handleCollectionFacet, ActivityRole.OBSERVATION);
    app.get("/api/knowledge/search", ctx ->
        ctx.status(HttpStatus.METHOD_NOT_ALLOWED)
            .json(Map.of("error", "Use POST for /api/knowledge/search")), ActivityRole.USER_ACTION);
    app.get("/api/knowledge/status", knowledgeSearchController::handleStatus, ActivityRole.OBSERVATION);
    app.get("/api/knowledge/suggest", knowledgeSearchController::handleSuggest, ActivityRole.USER_ACTION);
    app.post("/api/knowledge/folders", knowledgeSearchController::handleListFolders, ActivityRole.USER_ACTION);
    app.post("/api/knowledge/folder-files", knowledgeSearchController::handleListFolderFiles, ActivityRole.USER_ACTION);
    // Tempdoc 580 §17 P3 — the search-interaction disposition sink (FE click → canonical stream).
    app.post("/api/knowledge/disposition", knowledgeSearchController::handleDisposition, ActivityRole.USER_ACTION);

    // RAG context retrieval endpoints (for MCP tools and external agents)
    if (retrieveContextController != null) {
      app.post(
          "/api/knowledge/retrieve-context",
          retrieveContextController::handleRetrieveContext, ActivityRole.USER_ACTION);
      app.post(
          "/api/knowledge/match-citations",
          retrieveContextController::handleMatchCitations, ActivityRole.USER_ACTION);
      log.info("RAG context retrieval endpoints registered");
    }

    log.info("Knowledge Server API endpoints registered");
  }
}
