/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import tools.jackson.databind.JsonNode;
import io.justsearch.app.api.gpl.GplEvalData;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.configuration.PlatformPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugStateController implements io.justsearch.app.api.DebugStateProvider {
  private static final Logger log = LoggerFactory.getLogger(DebugStateController.class);
  private final JsonNode configRoot;
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private final EventBuffer eventBuffer;
  private final Telemetry telemetry;
  private final ObjectMapper mapper = new ObjectMapper();
  private GplStatusProvider gplJobCoordinator; // nullable; set via setGplJobCoordinator()
  private Supplier<GplEvalData> gplEvalSnapshotSupplier; // nullable; set via setter
  private RerankerService lambdaMartReranker; // nullable; set via setter

  public DebugStateController(
      JsonNode configRoot,
      KnowledgeServerBootstrap knowledgeServer,
      EventBuffer eventBuffer,
      Telemetry telemetry) {
    // Tempdoc 412 Phase 3: engineMonitorSupplier removed (Phase 0 finding 2: EngineMonitor was
    // dead code; setters never called in production). Inference status flows through
    // AppFacade.inferenceSnapshot() in StatusLifecycleHandler.
    this.configRoot = configRoot;
    this.knowledgeServer = knowledgeServer;
    this.eventBuffer = eventBuffer;
    this.telemetry = telemetry;
  }

  /** Late-binds the Knowledge Server after async Worker startup. */
  public void setKnowledgeServer(KnowledgeServerBootstrap ks) {
    this.knowledgeServer = ks;
  }

  /** Injects the GPL job coordinator for status reporting. Call after construction. */
  public void setGplJobCoordinator(GplStatusProvider coordinator) {
    this.gplJobCoordinator = coordinator;
  }

  /** Injects a supplier for the last GPL evaluation snapshot. Call after construction. */
  public void setGplEvalSnapshotSupplier(Supplier<GplEvalData> supplier) {
    this.gplEvalSnapshotSupplier = supplier;
  }

  /** Injects the LambdaMART reranker for status reporting. Call after construction. */
  public void setLambdaMartReranker(RerankerService reranker) {
    this.lambdaMartReranker = reranker;
  }

  public void handleGetState(Context ctx) {
    ctx.json(buildDebugState());
  }

  /** Returns the raw Lucene commit user data map from the Worker. */
  public void handleGetCommitMetadata(Context ctx) {
    if (knowledgeServer == null || !knowledgeServer.isReady()) {
      ctx.status(503).json(Map.of("error", "Worker not available"));
      return;
    }
    try {
      Map<String, String> commitMetadata = knowledgeServer.client().getCommitMetadata();
      ctx.json(commitMetadata);
    } catch (Exception e) {
      log.debug("Failed to fetch commit metadata: {}", e.getMessage());
      ctx.status(502).json(Map.of("error", e.getMessage()));
    }
  }

  /** Builds the debug state snapshot as a Jackson ObjectNode (reusable outside HTTP context). */
  @Override
  public ObjectNode buildDebugState() {
    ObjectNode root = mapper.createObjectNode();

    // System
    ObjectNode system = root.putObject("system");
    system.put("uptime_ms", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
    system.put("pid", ProcessHandle.current().pid());
    system.put("memory_usage_mb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);

    // Config
    if (configRoot != null) {
      root.set("config", configRoot);
    } else {
      root.putObject("config").put("error", "Config not available");
    }

    // Worker
    ObjectNode worker = root.putObject("worker");
    if (knowledgeServer != null && knowledgeServer.isReady()) {
      try {
        var snapshot = knowledgeServer.client().getDebugWorkerState();
        ObjectNode snapNode = (ObjectNode) mapper.valueToTree(snapshot);
        worker.setAll(snapNode);
        // Lane F stage A item A11 deleted the Worker process: the index half now runs inside this
        // JVM, so the only pid there is to report is ours. `port` is gone rather than zeroed —
        // there is no port at all now, and a debug surface emitting -1/0 for one would be stating
        // a fact that no longer exists. `inProcess` says why it vanished instead of leaving a
        // reader of /api/debug/state to guess.
        worker.put("pid", ProcessHandle.current().pid());
        worker.put("inProcess", true);

      } catch (Exception e) {
        worker.put("error", "Failed to fetch worker status: " + e.getMessage());
        log.warn("Failed to fetch worker status", e);
      }
    } else {
      worker.put("status", "UNAVAILABLE");
      if (knowledgeServer == null) {
          worker.put("reason", "Not configured");
      } else {
          worker.put("reason", "Not ready");
          // Item A11: the "if a spawner exists" guard becomes "if a client is bound" — the same
          // question ("is there an index half at all?") now that the half is composed in-process.
          // Same reasoning as the ready arm: our pid, and no `port` key.
          if (knowledgeServer.hasClient()) {
            worker.put("pid", ProcessHandle.current().pid());
            worker.put("inProcess", true);
          }
      }
    }

    // Worker log path (best-effort): <dataDir>/logs/worker.log. Item A11 deleted WorkerSpawner,
    // which is what used to redirect the child process's stdio here; the reported path is
    // unchanged, so this stays a path advertisement rather than a claim about a writer.
    try {
      Path logPath = PlatformPaths.resolveDataDir().resolve("logs").resolve("worker.log");
      worker.put("log_path", logPath.toString());
    } catch (Exception e) {
      worker.put("log_path", "");
      worker.put(
          "log_path_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    // Tempdoc 412 Phase 3: EngineMonitor block removed (Phase 0 finding 2: dead code).
    // LLM/inference status is exposed via /api/status -> InferenceRuntimeView, which carries
    // strictly more information than the prior debug-state "llm" sub-object. The use_thinking
    // flag is reachable via /api/effective-config; no need to duplicate it here.

    // GPL training job status (P1-A)
    if (gplJobCoordinator != null) {
      try {
        GplJobStatus gplStatus = gplJobCoordinator.getStatus();
        ObjectNode gpl = root.putObject("gpl");
        gpl.put("status", gplStatus.status().name());
        gpl.put("processed_docs", gplStatus.processedDocs());
        gpl.put("total_docs", gplStatus.totalDocs());
        gpl.put("triple_count", gplStatus.tripleCount());
        if (gplStatus.lastRunAt() != null) {
          gpl.put("last_run_at", gplStatus.lastRunAt().toString());
        }
        if (gplStatus.lastError() != null) {
          gpl.put("last_error", gplStatus.lastError());
        }

        // Event-driven revalidation: last-eval snapshot
        if (gplEvalSnapshotSupplier != null) {
          try {
            GplEvalData lastEval = gplEvalSnapshotSupplier.get();
            if (lastEval != null) {
              ObjectNode evalNode = gpl.putObject("last_eval");
              java.time.Instant evalAt = lastEval.evaluatedAt();
              if (evalAt != null) {
                evalNode.put("evaluated_at", evalAt.toString());
              }
              evalNode.put("doc_count", lastEval.docCount());
              evalNode.put("triple_count", lastEval.tripleCount());
              ObjectNode mimes = evalNode.putObject("mime_distribution");
              lastEval.mimeDistribution().forEach(mimes::put);
            }
          } catch (Exception e) {
            log.debug("Failed to fetch GPL eval snapshot", e);
          }
        }
      } catch (Exception e) {
        root.putObject("gpl").put("error", e.getMessage());
        log.debug("Failed to fetch GPL status", e);
      }
    }

    // Reranking status â€” clarifies which reranking systems are active and how they are gated.
    // LambdaMART (feature-based, ~1-5ms) and ONNX cross-encoder (~200ms) are independent.
    ObjectNode reranking = root.putObject("reranking");
    ObjectNode lambdaMart = reranking.putObject("lambdamart");
    lambdaMart.put("active", lambdaMartReranker != null && lambdaMartReranker.isLoaded());
    lambdaMart.put("gated_by", "model loaded (automatic after GPL training)");
    // E2E-7: Training lifecycle status for debugging.
    if (lambdaMartReranker != null) {
      LambdaMartTrainingStatus ts = lambdaMartReranker.getTrainingStatus();
      ObjectNode training = lambdaMart.putObject("training");
      training.put("status", ts.status().name());
      if (ts.ndcg10() != null) training.put("ndcg10", ts.ndcg10());
      if (ts.mrr10() != null) training.put("mrr10", ts.mrr10());
      if (ts.trainGroups() != null) training.put("train_groups", ts.trainGroups());
      if (ts.evalGroups() != null) training.put("eval_groups", ts.evalGroups());
      if (ts.lastTrainedAt() != null) training.put("last_trained_at", ts.lastTrainedAt().toString());
      if (ts.error() != null) training.put("error", ts.error());
    }
    reranking.put(
        "cross_encoder_note",
        "ONNX cross-encoder is separately gated by JUSTSEARCH_RERANK_ENABLED."
            + " Skipped when LambdaMART is active.");

    return root;
  }

  /**
   * GET /api/debug/events
   * Returns recent events from the event buffer.
   *
   * Query params:
   * - limit: max events to return (default 20)
   */
  public void handleGetEvents(Context ctx) {
    int limit = 20;
    String limitParam = ctx.queryParam("limit");
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException e) {
        // Keep default
      }
    }

    List<Map<String, Object>> events = eventBuffer.recent(limit).stream()
        .map(EventBuffer.Event::toMap)
        .toList();

    ctx.json(Map.of(
        "events", events,
        "total", eventBuffer.size()
    ));
  }

  /**
   * GET /api/debug/worker-log
   * Returns the last N bytes of the worker log file.
   *
   * Query params:
   * - bytes: max bytes to return (default 100000)
   */
  public void handleGetWorkerLog(Context ctx) {
    Path logPath;
    try {
      logPath = PlatformPaths.resolveDataDir().resolve("logs").resolve("worker.log");
    } catch (Exception e) {
      Map<String, Object> logErr = ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "Log path not available", telemetry, ApiErrorHandler.routeOf(ctx));
      logErr.put("details", e.getMessage());
      ctx.status(404).json(logErr);
      return;
    }
    if (!Files.exists(logPath)) {
      Map<String, Object> pathErr = ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "Log file not found", telemetry, ApiErrorHandler.routeOf(ctx));
      pathErr.put("path", logPath.toString());
      ctx.status(404).json(pathErr);
      return;
    }

    int maxBytes = 100_000; // 100KB default
    String bytesParam = ctx.queryParam("bytes");
    if (bytesParam != null) {
      try {
        maxBytes = Integer.parseInt(bytesParam);
      } catch (NumberFormatException e) {
        // Keep default
      }
    }

    try {
      long fileSize = Files.size(logPath);
      String content;

      if (fileSize <= maxBytes) {
        content = Files.readString(logPath);
      } else {
        // Read last N bytes
        try (var raf = new java.io.RandomAccessFile(logPath.toFile(), "r")) {
          raf.seek(fileSize - maxBytes);
          // Skip to next newline to avoid partial lines
          while (raf.read() != '\n' && raf.getFilePointer() < fileSize) {
            // Keep reading until newline
          }
          byte[] buffer = new byte[(int)(fileSize - raf.getFilePointer())];
          raf.readFully(buffer);
          content = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
        }
      }

      ctx.contentType("text/plain");
      ctx.result(content);
    } catch (Exception e) {
      log.error("Failed to read worker log", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.IO_ERROR, "Failed to read log: " + e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }
}
