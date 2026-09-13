/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Target-process reconciliation oracle over the existing upgrade transport.
 *
 * <p>Identity is bound to the durable shell intent, the running Head version/PID, and the target
 * binary's embedded compatibility register. A reachable API alone is never a positive result.
 */
final class UpgradeReconciliationProbe {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OwnerRegister OWNER_REGISTER = loadOwnerRegister();

  private final Path intentPath;
  private final Supplier<String> runningVersion;
  private final BooleanSupplier headReady;
  private final BooleanSupplier workerReady;

  UpgradeReconciliationProbe(
      Path dataDir,
      Supplier<String> runningVersion,
      BooleanSupplier headReady,
      BooleanSupplier workerReady) {
    this.intentPath =
        dataDir == null ? null : dataDir.resolve("upgrade").resolve("intent.v1.json");
    this.runningVersion = runningVersion == null ? () -> "" : runningVersion;
    this.headReady = headReady == null ? () -> false : headReady;
    this.workerReady = workerReady == null ? () -> false : workerReady;
  }

  void reconcile(Context ctx) {
    ReconcileRequest request = parseRequest(ctx);
    long actualPid = ProcessHandle.current().pid();
    String version = safeVersion();
    String configurationError = validateConfiguration(request, actualPid, version);
    if (configurationError != null) {
      ctx.status(409).json(response(request, actualPid, false, false, configurationError));
      return;
    }

    boolean headHealthy = headReady.getAsBoolean();
    boolean workerHealthy = workerReady.getAsBoolean();
    if (!headHealthy || !workerHealthy) {
      ctx.status(503)
          .json(
              response(
                  request,
                  actualPid,
                  headHealthy,
                  workerHealthy,
                  headHealthy ? "Worker is not ready" : "Head durable owners are not ready"));
      return;
    }
    ctx.json(response(request, actualPid, true, true, null));
  }

  private String validateConfiguration(
      ReconcileRequest request, long actualPid, String runningVersionValue) {
    if (OWNER_REGISTER.error() != null) return OWNER_REGISTER.error();
    if (intentPath == null || !Files.isRegularFile(intentPath)) {
      return "Durable upgrade intent is unavailable";
    }
    if (!request.targetVersion().equals(runningVersionValue)) {
      return "Target version does not match the running Head";
    }
    if (request.headPid() != actualPid) {
      return "Target Head process id does not match";
    }
    if (!matchesRuntimeOwners(request.owners())) {
      return "Requested owner formats do not match the target runtime register";
    }
    try {
      JsonNode intent = JSON.readTree(Files.readAllBytes(intentPath));
      if (intent == null
          || !"RECONCILING".equals(text(intent, "phase"))
          || !request.attemptId().equals(text(intent, "attemptId"))
          || !matchesStopEvidence(request, intent)
          || !request.sourceVersion().equals(text(intent, "sourceVersion"))
          || !request.targetVersion().equals(text(intent, "targetVersion"))
          || request.releaseSequence() != longValue(intent, "releaseSequence")) {
        return "Request identity does not match the durable reconciling intent";
      }
      return null;
    } catch (Exception e) {
      return "Durable upgrade intent could not be read";
    }
  }

  private Map<String, Object> response(
      ReconcileRequest request,
      long actualPid,
      boolean headHealthy,
      boolean workerHealthy,
      String error) {
    boolean ready = error == null && headHealthy && workerHealthy;
    List<Map<String, Object>> owners = new ArrayList<>();
    for (RuntimeOwner owner : OWNER_REGISTER.owners()) {
      boolean healthy =
          switch (owner.processOwner()) {
            case "HEAD" -> headHealthy;
            case "WORKER" -> workerHealthy;
            // SHELL has already validated its intent/sequence and EXTERNAL entries are
            // compatibility declarations rather than process-opened stores.
            default -> true;
          };
      owners.add(
          Map.of(
              "ownerId", owner.ownerId(),
              "formatVersion", owner.formatVersion(),
              "healthy", healthy));
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("schemaVersion", 1);
    response.put("attemptId", request.attemptId());
    response.put("stopEvidenceKind", request.stopEvidenceKind());
    if ("PREPARED".equals(request.stopEvidenceKind())) {
      response.put("shutdownNonce", request.shutdownNonce());
    }
    response.put("targetVersion", request.targetVersion());
    response.put("headPid", actualPid);
    response.put("ready", ready);
    response.put("headReady", headHealthy);
    response.put("workerReady", workerHealthy);
    response.put("owners", owners);
    if (error != null) response.put("error", error);
    return response;
  }

  private static String evidenceKind(JsonNode node, String field) {
    String kind = text(node, field);
    if (kind == null) return "PREPARED"; // Existing durable intents use the prepared path.
    if (!"PREPARED".equals(kind) && !"ENGINE_UNRECOVERABLE".equals(kind)) {
      throw new IllegalArgumentException("Unknown stop evidence kind");
    }
    return kind;
  }

  private static boolean matchesStopEvidence(ReconcileRequest request, JsonNode intent) {
    JsonNode evidence = intent.get("stopEvidence");
    String kind = evidenceKind(evidence, "kind");
    if (!kind.equals(request.stopEvidenceKind())) return false;
    if ("PREPARED".equals(kind)) {
      return (evidence == null || evidence.size() == 1)
          && request.shutdownNonce().equals(text(intent, "shutdownNonce"));
    }
    String previousPid = text(evidence, "enginePid");
    return request.attemptId().equals(text(evidence, "attemptId"))
        && longValue(evidence, "confirmedGoneAtEpochMs") > 0
        && (previousPid == null || longValue(evidence, "enginePid") > 0)
        && text(intent, "preparationId") == null
        && text(intent, "shutdownNonce") == null
        && text(intent, "shutdownReceipt") == null
        && text(intent, "headShutdownReceipt") == null
        && text(intent, "headPid") == null;
  }

  private String safeVersion() {
    try {
      String value = runningVersion.get();
      return value == null ? "" : value.trim();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static boolean matchesRuntimeOwners(List<RuntimeOwner> requested) {
    List<RuntimeOwner> runtime = OWNER_REGISTER.owners();
    if (requested.size() != runtime.size()) return false;
    for (int i = 0; i < runtime.size(); i++) {
      RuntimeOwner expected = runtime.get(i);
      RuntimeOwner actual = requested.get(i);
      if (!expected.ownerId().equals(actual.ownerId())
          || expected.formatVersion() != actual.formatVersion()) {
        return false;
      }
    }
    return true;
  }

  private static ReconcileRequest parseRequest(Context ctx) {
    try {
      JsonNode root = JSON.readTree(ctx.body());
      if (root == null || intValue(root, "schemaVersion") != 1) {
        throw new IllegalArgumentException("schemaVersion 1 is required");
      }
      List<RuntimeOwner> owners = new ArrayList<>();
      JsonNode ownerNodes = root.get("owners");
      if (ownerNodes == null || !ownerNodes.isArray()) {
        throw new IllegalArgumentException("owners must be an array");
      }
      for (JsonNode owner : ownerNodes) {
        owners.add(
            new RuntimeOwner(
                requiredText(owner, "ownerId"),
                intValue(owner, "formatVersion"),
                ""));
      }
      owners.sort(Comparator.comparing(RuntimeOwner::ownerId));
      String kind = evidenceKind(root, "stopEvidenceKind");
      String nonce = text(root, "shutdownNonce");
      if ("PREPARED".equals(kind)) {
        nonce = requiredText(root, "shutdownNonce");
      } else if (nonce != null && !nonce.isEmpty()) {
        throw new IllegalArgumentException("Dead Engine evidence cannot carry a shutdown nonce");
      }
      return new ReconcileRequest(
          requiredText(root, "attemptId"),
          kind,
          nonce == null ? "" : nonce,
          requiredText(root, "sourceVersion"),
          requiredText(root, "targetVersion"),
          longValue(root, "releaseSequence"),
          longValue(root, "headPid"),
          List.copyOf(owners));
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid upgrade reconciliation request", e);
    }
  }

  private static OwnerRegister loadOwnerRegister() {
    try (InputStream in =
        UpgradeReconciliationProbe.class.getResourceAsStream(
            "/governance/store-recoverability.v1.json")) {
      if (in == null) return new OwnerRegister(List.of(), "Runtime owner register is unavailable");
      JsonNode root = JSON.readTree(in);
      JsonNode stores = root == null ? null : root.get("durableStores");
      if (stores == null || !stores.isArray() || stores.isEmpty()) {
        return new OwnerRegister(List.of(), "Runtime owner register is empty");
      }
      List<RuntimeOwner> owners = new ArrayList<>();
      for (JsonNode store : stores) {
        if (!"READY".equals(requiredText(store, "status"))) {
          return new OwnerRegister(List.of(), "Runtime owner register contains a non-ready owner");
        }
        owners.add(
            new RuntimeOwner(
                requiredText(store, "id"),
                intValue(store, "currentVersion"),
                requiredText(store, "owner")));
      }
      owners.sort(Comparator.comparing(RuntimeOwner::ownerId));
      if (owners.stream().map(RuntimeOwner::ownerId).distinct().count() != owners.size()) {
        return new OwnerRegister(List.of(), "Runtime owner register contains duplicate owners");
      }
      return new OwnerRegister(List.copyOf(owners), null);
    } catch (Exception e) {
      return new OwnerRegister(List.of(), "Runtime owner register is invalid");
    }
  }

  private static String requiredText(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static int intValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    int result = value.asInt();
    if (result < 0) throw new IllegalArgumentException(field + " must be non-negative");
    return result;
  }

  private static long longValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    long result = value.asLong();
    if (result < 0) throw new IllegalArgumentException(field + " must be non-negative");
    return result;
  }

  record RuntimeOwner(String ownerId, int formatVersion, String processOwner) {}

  private record OwnerRegister(List<RuntimeOwner> owners, String error) {}

  private record ReconcileRequest(
      String attemptId,
      String stopEvidenceKind,
      String shutdownNonce,
      String sourceVersion,
      String targetVersion,
      long releaseSequence,
      long headPid,
      List<RuntimeOwner> owners) {}
}
