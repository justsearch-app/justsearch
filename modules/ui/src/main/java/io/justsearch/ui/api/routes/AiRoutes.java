/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.justsearch.app.services.conversation.shapes.BatchSummarizeShape;
import io.justsearch.app.services.conversation.shapes.HierarchicalSummarizeShape;
import io.justsearch.app.services.conversation.shapes.NavigateChatShape;
import io.justsearch.app.services.conversation.shapes.RAGAskShape;
import io.justsearch.app.services.conversation.shapes.SummarizeShape;
import io.justsearch.ui.api.AiInstallController;
import io.justsearch.ui.api.AiModelsController;
import io.justsearch.ui.api.AiPackController;
import io.justsearch.ui.api.AiRuntimeController;
import io.justsearch.ui.api.ChatController;
import io.justsearch.ui.api.PreviewController;

public final class AiRoutes {
  private AiRoutes() {}

  public static void register(
      Javalin app,
      PreviewController previewController,
      AiInstallController aiInstallController,
      AiPackController aiPackController,
      AiRuntimeController aiRuntimeController,
      AiModelsController aiModelsController,
      ChatController chatController) {
    // Tempdoc 491 Phase C1: /api/chat/summarize routes through ConversationEngine via the
    // SummarizeShape. The legacy /api/summarize + /api/summarize/stream endpoints are
    // removed (no wrapper intermediate per §9 Phase C — clients migrate atomically).
    app.post(
        "/api/chat/summarize",
        chatController.handler(SummarizeShape.ID, "/api/chat/summarize"), ActivityRole.USER_ACTION);
    // Tempdoc 491 Phase C2.2: /api/chat/batch-summarize routes through the
    // BatchSummarizeShape; legacy /api/summarize/batch/stream is removed in the same commit.
    app.post(
        "/api/chat/batch-summarize",
        chatController.handler(BatchSummarizeShape.ID, "/api/chat/batch-summarize"), ActivityRole.USER_ACTION);
    // Tempdoc 491 Phase C2.3: /api/chat/hierarchical-summarize routes through the
    // SHAPE_DRIVEN HierarchicalSummarizeShape (HierarchicalShapeRunner); legacy
    // /api/summarize/hierarchical/stream is removed in the same commit.
    app.post(
        "/api/chat/hierarchical-summarize",
        chatController.handler(
            HierarchicalSummarizeShape.ID, "/api/chat/hierarchical-summarize"), ActivityRole.USER_ACTION);
    // Tempdoc 491 Phase C3: /api/chat/ask routes through the substrate-driven RAGAskShape;
    // legacy /api/ask/stream is removed in the same commit.
    app.post("/api/chat/ask", chatController.handler(RAGAskShape.ID, "/api/chat/ask"), ActivityRole.USER_ACTION);
    // Slice 487 Phase 2.4: /api/chat/url-emit hosts the URL-emission consumer
    // (NavigateChatShape). The LLM emits Markdown URLs in its response;
    // URLExtractor parses them on onDone and dispatches each via BackendIntentRouter,
    // emitting navigate.url_extracted / navigate.url_dispatched / navigate.url_rejected
    // SSE events. The endpoint name uses "url-emit" rather than "navigate" because
    // the endpoint dispatches both Navigation and Invocation envelopes (the URL
    // grammar supports both shapes); "navigate" would describe only half the surface.
    app.post(
        "/api/chat/url-emit",
        chatController.handler(NavigateChatShape.ID, "/api/chat/url-emit"), ActivityRole.USER_ACTION);
    // Slice 496 §3.B: FreeChat — plain persistent conversation.
    app.post(
        "/api/chat/free",
        chatController.handler(
            io.justsearch.app.services.conversation.shapes.FreeChatShape.ID,
            "/api/chat/free"), ActivityRole.USER_ACTION);
    // Slice 496 §3.C: Extract — structured output with JSON schema constraint.
    app.post(
        "/api/chat/extract",
        chatController.handler(
            io.justsearch.app.services.conversation.shapes.ExtractShape.ID,
            "/api/chat/extract"), ActivityRole.USER_ACTION);
    // Slice 497: Dynamic dispatch — the unified chat surface reads shapeId from the body
    // so the FE picks the ConversationShape per-message via affordance state.
    app.post("/api/chat/dispatch", chatController.dynamicHandler("/api/chat/dispatch"), ActivityRole.USER_ACTION);
    app.get("/api/chat/conversations", chatController::handleListSessions, ActivityRole.OBSERVATION);
    app.get("/api/chat/conversations/{sessionId}/history", chatController::handleLoadHistory, ActivityRole.OBSERVATION);
    app.delete("/api/chat/conversations/{sessionId}", chatController::handleDeleteConversation, ActivityRole.USER_ACTION);
    // The reader's Stop on a running turn, named by the turnId it was dispatched with.
    app.post("/api/chat/conversations/{sessionId}/stop", chatController::handleStopTurn, ActivityRole.USER_ACTION);
    // Slice 513 — conversation branching.
    app.post("/api/chat/conversations/{sessionId}/branch", chatController::handleBranchConversation, ActivityRole.USER_ACTION);
    // Tempdoc 610 Phase C — effective-context floor (rewind): set / clear.
    app.post(
        "/api/chat/conversations/{sessionId}/context-floor",
        chatController::handleSetContextFloor, ActivityRole.USER_ACTION);
    app.delete(
        "/api/chat/conversations/{sessionId}/context-floor",
        chatController::handleClearContextFloor, ActivityRole.USER_ACTION);
    // Tempdoc 610 Phase D — compaction (summarize-then-floor).
    app.post("/api/chat/conversations/{sessionId}/compact", chatController::handleCompact, ActivityRole.USER_ACTION);
    // Tempdoc 610 §E.2 — edit the compaction summary in place (no re-summarize).
    app.post(
        "/api/chat/conversations/{sessionId}/context-floor/summary",
        chatController::handleEditContextFloorSummary, ActivityRole.USER_ACTION);
    // Tempdoc 610 §E.3 — per-message exclude from the effective context (toggle).
    app.post(
        "/api/chat/conversations/{sessionId}/messages/{messageId}/exclude",
        chatController::handleToggleMessageExcluded, ActivityRole.USER_ACTION);
    // Tempdoc 610 §J.3 — per-source exclude from retrieval (toggle); sourceId rides in the body.
    app.post(
        "/api/chat/conversations/{sessionId}/sources/exclude",
        chatController::handleToggleSourceExcluded, ActivityRole.USER_ACTION);
    // Tempdoc 838 — the conversation's name: set / clear. The one durable authority for a title,
    // which the browser used to own alone.
    app.post("/api/chat/conversations/{sessionId}/title", chatController::handleSetTitle, ActivityRole.USER_ACTION);
    app.delete("/api/chat/conversations/{sessionId}/title", chatController::handleClearTitle, ActivityRole.USER_ACTION);
    // The model's automatic name for a conversation, which the web UI asks for on its own after the
    // first answer: background inference that yields in the chat lane, never a person acting.
    app.post(
        "/api/chat/conversations/{sessionId}/auto-title",
        chatController::handleAutoTitle, ActivityRole.OBSERVATION);
    app.get("/api/preview", previewController::handlePreview, ActivityRole.USER_ACTION);
    app.post(PreviewController.PRESENCE_PATH, previewController::handlePresence, ActivityRole.OBSERVATION);
    app.post(PreviewController.DOCUMENT_IDS_PATH, previewController::handleListDocumentIds, ActivityRole.USER_ACTION);

    app.get("/api/ai/install/manifest", aiInstallController::handleGetManifest, ActivityRole.OBSERVATION);
    app.get("/api/ai/install/status", aiInstallController::handleGetStatus, ActivityRole.OBSERVATION);
    app.get("/api/ai/install/plan-preview", aiInstallController::handleGetPlanPreview, ActivityRole.OBSERVATION);
    app.post("/api/ai/install/start", aiInstallController::handleStart, ActivityRole.USER_ACTION);
    app.post("/api/ai/install/cancel", aiInstallController::handleCancel, ActivityRole.USER_ACTION);
    app.post("/api/ai/install/repair", aiInstallController::handleRepair, ActivityRole.USER_ACTION);
    // Tempdoc 840 Phase 4 — pause/resume an in-flight run (NOT a cancel: the run keeps its op-lease
    // and its place in the set), and per-component decline / re-enable. Every one of these answers
    // with the post-call AiInstallStatus, like start/cancel/repair.
    app.post("/api/ai/install/pause", aiInstallController::handlePause, ActivityRole.USER_ACTION);
    app.post("/api/ai/install/resume", aiInstallController::handleResume, ActivityRole.USER_ACTION);
    app.post(
        "/api/ai/install/packages/{packageId}/decline",
        aiInstallController::handleDeclinePackage, ActivityRole.USER_ACTION);
    app.delete(
        "/api/ai/install/packages/{packageId}/decline",
        aiInstallController::handleAcceptPackage, ActivityRole.USER_ACTION);

    app.get("/api/ai/packs/status", aiPackController::handleGetStatus, ActivityRole.OBSERVATION);
    app.get("/api/ai/packs/installed", aiPackController::handleGetInstalled, ActivityRole.OBSERVATION);
    app.post("/api/ai/packs/preflight", aiPackController::handlePreflight, ActivityRole.USER_ACTION);
    app.post("/api/ai/packs/import", aiPackController::handleImport, ActivityRole.USER_ACTION);

    app.get("/api/ai/runtime/status", aiRuntimeController::handleGetStatus, ActivityRole.OBSERVATION);
    app.post("/api/ai/runtime/activate", aiRuntimeController::handleActivate, ActivityRole.USER_ACTION);
    app.post("/api/ai/runtime/deactivate", aiRuntimeController::handleDeactivate, ActivityRole.USER_ACTION);

    // Tempdoc 656 Task 4: registry-vs-disk reconciliation, read-only.
    app.get("/api/ai/models/status", aiModelsController::handleGetStatus, ActivityRole.OBSERVATION);

    // Tempdoc 491 §9.B: /api/summarize/batch/stream removed in C2.2;
    // /api/summarize/hierarchical/stream removed in C2.3; /api/ask/stream removed in C3.
  }
}
