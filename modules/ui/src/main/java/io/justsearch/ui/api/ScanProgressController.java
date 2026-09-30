/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.scan.ScanProgressEvent;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.worker.ScanProgressRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 419 / T4 — SSE endpoint that lets the local UI subscribe to live scan progress.
 *
 * <p>{@code GET /api/scans/{scanId}/progress}. The response is an SSE stream emitting:
 *
 * <ul>
 *   <li>{@code event: progress} — one per {@link ScanProgressEvent} from the worker. Payload
 *       carries the typed counters (filesWalked, filesAdmitted, filesSkipped, bytesWalked)
 *       plus the (privacy-hashed) currentDirectory.
 *   <li>{@code event: complete} — emitted once when the scan reaches a terminal state.
 *       Payload carries the terminalReasonCode (empty string on clean completion,
 *       {@code CLIENT_CANCELLED}, {@code IO_ERROR}, etc. on failure paths).
 * </ul>
 *
 * <p>Closing the SSE connection (EventSource.close() on the client) propagates a cancel
 * to the worker via the registered {@link io.justsearch.app.services.worker.CancelToken} —
 * the worker's scan terminates within the next batch and emits a CLIENT_CANCELLED terminal.
 *
 * <p>Late subscribers (connecting after the scan completes, but within the registry's
 * retention window — default 30s) see the retained event suffix replayed. Subscribers past
 * retention see a single synthetic {@code complete} event with terminalReasonCode
 * {@code UNKNOWN_SCAN_OR_RETENTION_EXPIRED}.
 */
public final class ScanProgressController {
  private static final Logger log = LoggerFactory.getLogger(ScanProgressController.class);

  private final ScanProgressRegistry registry;
  private final SseWriter sseWriter;

  public ScanProgressController(ScanProgressRegistry registry, HeadApiMetricCatalog apiCatalog) {
    this.registry = registry;
    this.sseWriter = new SseWriter(apiCatalog);
  }

  /** Handler for {@code GET /api/scans/{scanId}/progress}. */
  public void handleScanProgress(Context ctx) {
    String scanId = ctx.pathParam("scanId");
    if (scanId == null || scanId.isBlank()) {
      ctx.status(400).result("scanId path parameter is required");
      return;
    }
    boolean clientStillConnected = true;
    try (var subscription = registry.subscribe(scanId)) {
      sseWriter.initSseHeaders(ctx, "/api/scans/{scanId}/progress");
      for (ScanProgressEvent event : subscription) {
        if (!clientStillConnected) {
          break;
        }
        boolean ok =
            sseWriter.writeEvent(
                ctx,
                event.complete() ? "complete" : "progress",
                toEventPayload(event));
        if (!ok) {
          // Client disconnected mid-stream — propagate cancel so the worker stops walking.
          if (registry.cancel(scanId)) {
            log.debug("SSE subscriber for scanId={} disconnected; cancelled scan", scanId);
          }
          break;
        }
        if (event.complete()) {
          break;
        }
      }
    } catch (RuntimeException e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, null)) return;
      log.warn("Scan progress stream for scanId={} failed: {}", scanId, e.getMessage());
      sseWriter.writeEvent(ctx, "error", Map.of("message", e.getMessage() == null ? "" : e.getMessage()));
    }
  }

  private static Map<String, Object> toEventPayload(ScanProgressEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("scanId", event.scanId());
    payload.put("filesWalked", event.filesWalked());
    payload.put("filesAdmitted", event.filesAdmitted());
    payload.put("filesSkipped", event.filesSkipped());
    payload.put("bytesWalked", event.bytesWalked());
    payload.put("currentDirectory", event.currentDirectory());
    payload.put("complete", event.complete());
    if (event.complete()) {
      payload.put("terminalReasonCode", event.terminalReasonCode());
    }
    return payload;
  }
}
