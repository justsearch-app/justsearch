/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;

/**
 * Route registration for agent tool execution endpoints.
 *
 * <p>Per tempdoc 491 §C4 (2026-05-12): every agent route lives under the unified
 * {@code /api/chat/*} namespace, alongside the substrate-driven summarize / batch /
 * hierarchical / ask shapes. The legacy {@code /api/agent/*} paths were removed in the same
 * commit (no wrapper intermediate per §9.B). FE callers in {@code modules/ui-web/src/api/
 * domains/agent.ts} are migrated to the new paths atomically.
 *
 * <p>Wire shape (event sequence + payloads) is unchanged; only the URL prefix moved.
 */
public final class AgentRoutes {
  private AgentRoutes() {}

  /**
   * Tempdoc 585 §B.5 — the agent-control endpoint family is served by three cohesive controllers
   * (run/control core + read-axis {@code session} + tools-axis {@code tools}); this register binds
   * each route to its owning controller. The URLs are unchanged — only the Java owner moved.
   */
  public static void register(
      Javalin app,
      AgentController controller,
      AgentSessionController session,
      AgentToolsController tools) {
    if (controller == null) {
      return;
    }
    // Agent run + lifecycle (formerly /api/agent/*).
    app.post("/api/chat/agent", controller::handleRunStream);
    // Tempdoc 565 §15.C enforcement — the ONE approval endpoint ("a run is a run" all the way down).
    // The shared <jf-authorization-host> ceremony POSTs here for BOTH an agent tool-call gate and a
    // workflow GateStep/ToolStep gate; the controller dispatches agent-gate → workflow-gate → 404. The
    // forked /api/chat/agent/{approve,reject} + /api/chat/workflow/{approve,reject} routes were retired.
    app.post("/api/chat/approve", controller::handleApprove);
    app.post("/api/chat/reject", controller::handleReject);
    app.get("/api/chat/approval", controller::handleApproval);
    // Tempdoc 561 P-D — update the live autonomy dial for a running session.
    app.post("/api/chat/agent/autonomy", controller::handleAutonomy);
    // Tempdoc 565 §30 — the DIRECTION authority's interject: queue a mid-run human steering directive.
    app.post("/api/chat/agent/steer", controller::handleSteeringDirective);
    // Tempdoc 577 Ext III — the over-budget remedy: grant a running session additional tokens.
    app.post("/api/chat/agent/budget", controller::handleRaiseBudget);
    // Tempdoc 577 Move 2 — resolve a held budget gate (finalize | stop; continue = the raise above).
    app.post("/api/chat/agent/budget-decision", controller::handleBudgetDecision);
    // Tempdoc 577 §2.14 Root II — resolve a held context-pressure gate (continue | summarize | stop).
    app.post("/api/chat/agent/context-decision", controller::handleContextDecision);
    // Tempdoc 577 §2.14 Root I — attach a new SSE observer to a LIVE run (reattach / multi-observer).
    // POST (no body) so the FE reuses the same consumeShapeStream reader the resume endpoint uses.
    app.post("/api/chat/agent/{sessionId}/attach", controller::handleAttachStream);
    // Tempdoc 585 §D Phase 3 (C3) — attach an AG-UI-protocol observer to the same live run (the
    // sibling AgUiEventTranslator projection). Under /api/chat/* — the removed /api/agent/* namespace
    // is NOT resurrected (Hard Invariant #3).
    app.post("/api/chat/agent/{sessionId}/ag-ui", controller::handleAgUiAttachStream);

    // Tools-axis (AgentToolsController, tempdoc 585 §B.5): tool inventory + FE virtual-operations sidecar.
    app.get("/api/chat/agent/tools", tools::handleListTools);
    // Tempdoc 508 §11.5 / §13.5 — FE-published virtual operations.
    app.post("/api/chat/agent/virtual-operations", tools::handleVirtualOperationsPublish);
    app.get("/api/chat/agent/virtual-operations", tools::handleVirtualOperationsRead);
    // Tempdoc 508 §11.5 / §13.5 Phase B — FE delivers virtual tool
    // execution results back to the blocking agent loop.
    app.post("/api/chat/agent/tool-result", tools::handleVirtualToolResult);

    // Session enumeration + addressed flows. The pure reads are served by AgentSessionController
    // (tempdoc 585 §B.5, narrowed to AgentRunQueries); the resume STREAMS + cancel stay on the
    // run/control core (they need isAvailable()/the control surface/the SSE writer).
    app.get("/api/chat/sessions/last", session::handleSessionLast);
    app.post("/api/chat/sessions/resume-last", controller::handleResumeLastStream);
    app.get("/api/chat/sessions", session::handleListSessions);
    app.get("/api/chat/sessions/{id}", session::handleSessionDetail);
    app.post("/api/chat/sessions/{id}/resume", controller::handleResumeSessionStream);
    // Tempdoc 585 §D Phase 3 (C2) — time-travel fork: branch a new run from a finished one.
    app.post("/api/chat/sessions/{id}/fork", controller::handleForkSessionStream);
    app.get("/api/chat/sessions/{id}/transcript", session::handleSessionTranscript);
    app.get("/api/chat/sessions/{id}/events", session::handleSessionEvents);
    app.delete("/api/chat/sessions/{id}", controller::handleCancelSession);

    // History + undo (agent-shape-specific tool execution audit) — read-axis (AgentSessionController).
    app.post("/api/chat/agent/undo", session::handleUndo);
    app.get("/api/chat/agent/history", session::handleHistory);
    app.get("/api/chat/agent/history/{batchId}", session::handleHistoryDetail);
  }
}
