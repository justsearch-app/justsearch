/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP routing layer for the diagnostics-export endpoint. Service-impl logic for
 * {@code DiagnosticsService} lives in
 * {@code io.justsearch.app.services.diagnostics.DiagnosticsServiceImpl}
 * (tempdoc 519 §9 Step 3). The impl composes the
 * {@code DebugStateProvider} / {@code StatusSnapshotProvider} SPIs (extracted in this step)
 * which {@code DebugStateController} and {@code StatusLifecycleHandler} implement.
 *
 * <p>Tempdoc 518 Wave D.1 ({@code handleRecentTraces}) lives here as HTTP routing because it
 * tails {@code traces.ndjson} directly — no service-impl logic to extract.
 */
public final class DiagnosticsController {
  private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DiagnosticsService diagnosticsService;
  private final Telemetry telemetry;

  public DiagnosticsController(DiagnosticsService diagnosticsService, Telemetry telemetry) {
    this.diagnosticsService = diagnosticsService;
    this.telemetry = telemetry;
  }

  /** Cap on the optional FE-telemetry request body; larger payloads are ignored, not errors. */
  private static final int MAX_FE_TELEMETRY_BODY_CHARS = 32_768;

  public void handleExport(Context ctx) {
    try {
      Path outZip = diagnosticsService.exportDiagnostics(extractFeTelemetry(ctx), RequestEngineContext.get(ctx));
      ctx.json(Map.of("success", true, "path", outZip.toAbsolutePath().toString()));
    } catch (Exception e) {
      log.error("Diagnostics export failed", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.DIAGNOSTICS_EXPORT_FAILED,
                  "Diagnostics export failed",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Optional {@code feTelemetry} object from the POST body (e.g. the FE wire-drift ring
   * summary), serialized back to JSON for verbatim embedding. A missing, oversized, or
   * malformed body must never fail the export — this is telemetry, not input.
   */
  private static String extractFeTelemetry(Context ctx) {
    try {
      String body = ctx.body();
      if (body == null || body.isBlank() || body.length() > MAX_FE_TELEMETRY_BODY_CHARS) {
        return null;
      }
      var node = MAPPER.readTree(body).get("feTelemetry");
      if (node == null || !node.isObject()) {
        return null;
      }
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (Exception e) {
      log.debug("Ignoring unparseable feTelemetry body on diagnostics export", e);
      return null;
    }
  }

  /**
   * GET /api/diagnostics/traces — return the most recent N spans from traces.ndjson as a JSON
   * array. Tempdoc 518 Appendix G Wave D.1 + Slice 3 tail-by-bytes fix (commits ee48c89d2 +
   * 38da77b53). Best-effort: missing file returns empty + {@code tracesAvailable: false}.
   *
   * <p>Tail-by-bytes (RandomAccessFile.seek) instead of full-file forward scan — the Brain
   * panel polls every 5s, and traces.ndjson can reach multi-MB. Cost is O(TAIL_BYTES) not
   * O(fileSize).
   */
  public void handleRecentTraces(Context ctx) {
    int limit = 20;
    try {
      String raw = ctx.queryParam("limit");
      if (raw != null && !raw.isBlank()) {
        limit = Integer.parseInt(raw);
      }
    } catch (NumberFormatException ignored) {
      // best-effort default
    }
    if (limit < 1) limit = 1;
    if (limit > 100) limit = 100;

    Path tracesFile = PlatformPaths.resolveDataDir().resolve("telemetry").resolve("traces.ndjson");
    if (!Files.isRegularFile(tracesFile)) {
      ctx.json(Map.of("spans", List.of(), "tracesAvailable", false));
      return;
    }

    final int TAIL_BYTES = 256 * 1024;
    List<Object> spans = new ArrayList<>();
    try {
      long fileSize = Files.size(tracesFile);
      String tail;
      if (fileSize <= TAIL_BYTES) {
        tail = Files.readString(tracesFile, StandardCharsets.UTF_8);
      } else {
        try (RandomAccessFile raf = new RandomAccessFile(tracesFile.toFile(), "r")) {
          raf.seek(fileSize - TAIL_BYTES);
          int b;
          while ((b = raf.read()) != -1 && b != '\n') {
            // advance past the (possibly truncated) first line in the window
          }
          long remaining = fileSize - raf.getFilePointer();
          byte[] tailBytes = new byte[(int) remaining];
          raf.readFully(tailBytes);
          tail = new String(tailBytes, StandardCharsets.UTF_8);
        }
      }
      ArrayDeque<Object> window = new ArrayDeque<>(limit + 1);
      for (String line : tail.split("\n", -1)) {
        if (line.isBlank()) continue;
        try {
          Object parsed = MAPPER.readValue(line, Object.class);
          window.addLast(parsed);
          if (window.size() > limit) {
            window.pollFirst();
          }
        } catch (Exception ignored) {
          // skip malformed line
        }
      }
      while (!window.isEmpty()) {
        spans.add(window.pollLast());
      }
    } catch (IOException e) {
      log.warn("Failed to read traces.ndjson (best-effort): {}", e.getMessage());
    }
    ctx.json(Map.of("spans", spans, "tracesAvailable", true));
  }
}
