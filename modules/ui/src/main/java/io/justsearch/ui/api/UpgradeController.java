/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.OperationLease;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OperationLeaseSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Session-token-protected Head half of the two-step upgrade prepare/shutdown handshake. */
final class UpgradeController {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final OperationLeaseService leases;
  private final UpgradeShutdownAction orderlyShutdown;
  private final Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient;
  private final UpgradeReconciliationProbe reconciliation;
  private String noncePreparationId;
  private String shutdownNonce;
  private CommitPhase commitPhase = CommitPhase.OPEN;
  private CommitReservation commitReservation;
  private boolean preparationInProgress;
  private boolean cancellationInProgress;

  UpgradeController(OperationLeaseService leases, Runnable orderlyShutdown) {
    this(leases, (preparationId, nonce) -> orderlyShutdown.run(), null, null, null, null, null);
  }

  UpgradeController(
      OperationLeaseService leases,
      UpgradeShutdownAction orderlyShutdown,
      Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient) {
    this(leases, orderlyShutdown, workerClient, null, null, null, null);
  }

  UpgradeController(
      OperationLeaseService leases,
      UpgradeShutdownAction orderlyShutdown,
      Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient,
      Path dataDir,
      Supplier<String> runningVersion,
      BooleanSupplier headReady,
      BooleanSupplier workerReady) {
    this.leases = leases;
    this.orderlyShutdown = orderlyShutdown;
    this.workerClient = workerClient;
    this.reconciliation =
        new UpgradeReconciliationProbe(dataDir, runningVersion, headReady, workerReady);
  }

  void reconcile(Context ctx) {
    reconciliation.reconcile(ctx);
  }

  void prepare(Context ctx) {
    if (!reservePreparation()) {
      preparationBusy(ctx);
      return;
    }
    try {
      OperationLeaseSnapshot snapshot = leases.freezeAdmission("application upgrade");
      snapshot = leases.requestCancellation(snapshot.preparationId());
      String nonce = nonceFor(snapshot.preparationId());
      ctx.json(response(snapshot, nonce, prepareWorker(snapshot.preparationId())));
    } finally {
      releasePreparation();
    }
  }

  void cancel(Context ctx) {
    UpgradeRequest request = requiredRequest(ctx);
    if (!reserveCancellation(request)) {
      preparationMismatch(ctx);
      return;
    }
    String preparationId = request.preparationId();
    if (!cancelWorker(preparationId)) {
      releaseCancellationReservation(request);
      ctx.status(503)
          .json(
              Map.of(
                  "cancelled", false,
                  "preparationId", preparationId,
                  "error", "Worker cancellation could not be acknowledged",
                  "errorCode", "UPGRADE_CANCEL_PENDING"));
      return;
    }
    leases.releaseAdmission(preparationId);
    clearPreparation(request);
    ctx.json(
        Map.of(
            "schemaVersion", 1,
            "cancelled", true,
            "preparationId", preparationId,
            "shutdownNonce", request.shutdownNonce()));
  }

  void commitShutdown(Context ctx) {
    UpgradeRequest request = requiredRequest(ctx);
    String preparationId = request.preparationId();
    OperationLeaseSnapshot snapshot = leases.snapshot();
    if (!snapshot.admissionFrozen()
        || !preparationId.equals(snapshot.preparationId())
        || !ownsNonce(request)) {
      preparationMismatch(ctx);
      return;
    }
    List<OperationLease> blockers = blocking(snapshot);
    Map<String, Object> worker = workerStatus(preparationId);
    if (!blockers.isEmpty() || !Boolean.TRUE.equals(worker.get("ready"))) {
      ctx.status(409).json(response(snapshot, request.shutdownNonce(), worker));
      return;
    }
    byte[] response =
        JSON.writeValueAsBytes(
            Map.of(
                "schemaVersion", 1,
                "shutdownAccepted", true,
                "preparationId", preparationId,
                "shutdownNonce", request.shutdownNonce(),
                "admissionFrozen", true,
                "activeLeaseCount", 0,
                "issuedAtEpochMs", System.currentTimeMillis()));
    CommitReservation reservation = claimCommit(request);
    if (reservation == null) {
      preparationMismatch(ctx);
      return;
    }
    Thread dispatch;
    try {
      UpgradeShutdownAction action =
          orderlyShutdown instanceof UpgradeShutdownBridge bridge
              ? bridge.boundAction()
              : orderlyShutdown;
      dispatch =
          Thread.ofPlatform()
              .name("engine-upgrade-shutdown")
              .unstarted(() -> action.shutdown(preparationId, request.shutdownNonce()));
    } catch (RuntimeException e) {
      restoreOpen(reservation);
      ctx.status(503)
          .json(
              Map.of(
                  "shutdownAccepted", false,
                  "preparationId", preparationId,
                  "error", "Upgrade shutdown action is not ready",
                  "errorCode", "UPGRADE_SHUTDOWN_NOT_READY"));
      return;
    }
    try {
      ctx.contentType("application/json");
      ctx.res().setContentLength(response.length);
      ctx.res().getOutputStream().write(response);
      ctx.res().flushBuffer();
    } catch (IOException e) {
      restoreOpen(reservation);
      throw new UncheckedIOException("failed to acknowledge orderly upgrade shutdown", e);
    } catch (RuntimeException e) {
      restoreOpen(reservation);
      throw e;
    }
    acknowledge(reservation);
    // Starting after the successful flush keeps API close off its own request thread. A failure
    // from here cannot undo an acknowledgement; absence of the nonce-bound receipt holds install.
    dispatch.start();
  }

  private static Map<String, Object> response(
      OperationLeaseSnapshot snapshot, String shutdownNonce, Map<String, Object> worker) {
    List<OperationLease> blockers = blocking(snapshot);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("schemaVersion", 1);
    response.put("preparationId", snapshot.preparationId());
    response.put("shutdownNonce", shutdownNonce);
    response.put("admissionFrozen", snapshot.admissionFrozen());
    response.put("ready", blockers.isEmpty() && Boolean.TRUE.equals(worker.get("ready")));
    response.put("blockers", blockers);
    response.put(
        "interruptibleWithLoss",
        snapshot.activeLeases().stream()
            .filter(
                lease ->
                    lease.criticality()
                        == io.justsearch.app.api.OpCriticality.INTERRUPTIBLE_WITH_LOSS)
            .toList());
    response.put("cancellationRequestedOpIds", snapshot.cancellationRequestedOpIds());
    response.put("activeLeases", snapshot.activeLeases());
    response.put("worker", worker);
    return response;
  }

  private Map<String, Object> prepareWorker(String preparationId) {
    if (workerClient == null) return Map.of("required", false, "ready", true);
    try {
      var client = workerClient.get();
      if (client == null) {
        return Map.of(
            "required", true, "ready", false, "blockers", List.of("Worker is unavailable"));
      }
      return workerMap(client.prepareUpgrade(preparationId));
    } catch (RuntimeException e) {
      return Map.of(
          "required", true, "ready", false, "blockers", List.of("Worker prepare failed"));
    }
  }

  private Map<String, Object> workerStatus(String preparationId) {
    if (workerClient == null) return Map.of("required", false, "ready", true);
    try {
      var client = workerClient.get();
      if (client == null) {
        return Map.of(
            "required", true, "ready", false, "blockers", List.of("Worker is unavailable"));
      }
      return workerMap(client.upgradeStatus(preparationId));
    } catch (RuntimeException e) {
      return Map.of(
          "required", true, "ready", false, "blockers", List.of("Worker status failed"));
    }
  }

  private boolean cancelWorker(String preparationId) {
    if (workerClient == null) return true;
    try {
      var client = workerClient.get();
      if (client == null) return false;
      client.cancelUpgrade(preparationId);
      return true;
    } catch (RuntimeException ignored) {
      // Keep Head admission frozen so the caller can retry without admitting writes while Worker
      // cancellation is unacknowledged.
      return false;
    }
  }

  private static Map<String, Object> workerMap(
      io.justsearch.app.api.WorkerQuiescenceSnapshot worker) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("required", true);
    result.put("preparationId", worker.preparationId());
    result.put("ready", worker.ready());
    result.put("loopQuiesced", worker.loopQuiesced());
    result.put("queueCheckpointed", worker.queueCheckpointed());
    result.put("migrationState", worker.migrationState());
    result.put("blockers", worker.blockers());
    return result;
  }

  private static List<OperationLease> blocking(OperationLeaseSnapshot snapshot) {
    // Until operation owners expose an explicit cancellation acknowledgement protocol,
    // every active lease must drain. Treating "interruptible" as already interrupted
    // would let the installer race a writer that is still running.
    return List.copyOf(snapshot.activeLeases());
  }

  private synchronized String nonceFor(String preparationId) {
    if (!preparationId.equals(noncePreparationId)) {
      noncePreparationId = preparationId;
      shutdownNonce = java.util.UUID.randomUUID().toString();
      commitPhase = CommitPhase.OPEN;
      commitReservation = null;
      cancellationInProgress = false;
    }
    return shutdownNonce;
  }

  private synchronized boolean ownsNonce(UpgradeRequest request) {
    return request.preparationId().equals(noncePreparationId)
        && request.shutdownNonce().equals(shutdownNonce);
  }

  private synchronized CommitReservation claimCommit(UpgradeRequest request) {
    if (!ownsNonce(request)
        || commitPhase != CommitPhase.OPEN
        || preparationInProgress
        || cancellationInProgress) return null;
    commitPhase = CommitPhase.ACKNOWLEDGING;
    commitReservation = new CommitReservation(request.preparationId(), request.shutdownNonce());
    return commitReservation;
  }

  private synchronized void restoreOpen(CommitReservation reservation) {
    if (commitPhase == CommitPhase.ACKNOWLEDGING && commitReservation == reservation) {
      commitPhase = CommitPhase.OPEN;
      commitReservation = null;
    }
  }

  private synchronized void acknowledge(CommitReservation reservation) {
    if (commitPhase != CommitPhase.ACKNOWLEDGING || commitReservation != reservation) {
      throw new IllegalStateException("upgrade commit reservation is no longer active");
    }
    commitPhase = CommitPhase.ACKNOWLEDGED;
  }

  private synchronized boolean reserveCancellation(UpgradeRequest request) {
    if (!ownsNonce(request)
        || commitPhase != CommitPhase.OPEN
        || preparationInProgress
        || cancellationInProgress)
      return false;
    cancellationInProgress = true;
    return true;
  }

  private synchronized void releaseCancellationReservation(UpgradeRequest request) {
    if (ownsNonce(request) && commitPhase == CommitPhase.OPEN) {
      cancellationInProgress = false;
    }
  }

  private synchronized void clearPreparation(UpgradeRequest request) {
    if (!ownsNonce(request)) return;
    noncePreparationId = null;
    shutdownNonce = null;
    commitPhase = CommitPhase.OPEN;
    commitReservation = null;
    cancellationInProgress = false;
  }

  private synchronized boolean reservePreparation() {
    if (preparationInProgress
        || cancellationInProgress
        || commitPhase != CommitPhase.OPEN) return false;
    preparationInProgress = true;
    return true;
  }

  private synchronized void releasePreparation() {
    preparationInProgress = false;
  }

  private static void preparationBusy(Context ctx) {
    ctx.status(409)
        .json(
            Map.of(
                "error", "Another upgrade preparation transaction is in progress",
                "errorCode", "UPGRADE_PREPARATION_BUSY"));
  }

  private static void preparationMismatch(Context ctx) {
    ctx.status(409)
        .json(
            Map.of(
                "error", "Preparation capability does not own the active admission barrier",
                "errorCode", "UPGRADE_PREPARATION_MISMATCH"));
  }

  private record UpgradeRequest(String preparationId, String shutdownNonce) {}

  private enum CommitPhase {
    OPEN,
    ACKNOWLEDGING,
    ACKNOWLEDGED
  }

  private record CommitReservation(String preparationId, String shutdownNonce) {}

  private static UpgradeRequest requiredRequest(Context ctx) {
    try {
      JsonNode root = JSON.readTree(ctx.body());
      JsonNode schema = root == null ? null : root.get("schemaVersion");
      JsonNode preparation = root == null ? null : root.get("preparationId");
      JsonNode nonce = root == null ? null : root.get("shutdownNonce");
      String preparationId = preparation == null ? null : preparation.asText();
      String shutdownNonce = nonce == null ? null : nonce.asText();
      if (schema == null || schema.asInt() != 1) {
        throw new IllegalArgumentException("schemaVersion 1 is required");
      }
      if (preparationId == null || preparationId.isBlank()) {
        throw new IllegalArgumentException("preparationId is required");
      }
      if (shutdownNonce == null || shutdownNonce.isBlank()) {
        throw new IllegalArgumentException("shutdownNonce is required");
      }
      return new UpgradeRequest(preparationId, shutdownNonce);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Request body must contain schemaVersion, preparationId, and shutdownNonce", e);
    }
  }
}
