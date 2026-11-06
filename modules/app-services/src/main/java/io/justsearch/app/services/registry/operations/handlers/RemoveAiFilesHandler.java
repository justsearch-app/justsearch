/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.remove-ai-files} (design D4): removes one part's downloaded files when
 * nothing uses them. It forwards a part id and the confirmed preview digest, never a path; the
 * install owner decides eligibility and ownership, and its refusal is returned as a plain reason.
 */
public final class RemoveAiFilesHandler implements OperationHandler {
  private static final Set<String> PARTS = Set.of("reranker", "chat");

  private final Supplier<BrainInstallService> install;

  public RemoveAiFilesHandler(Supplier<BrainInstallService> install) {
    this.install = Objects.requireNonNull(install, "install");
  }

  /** The validated request. */
  record Request(String packageId, String previewDigest) {}

  static Request request(String argumentsJson) {
    JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
    String packageId = root.path("packageId").isString() ? root.path("packageId").asString() : "";
    String digest = root.path("previewDigest").isString() ? root.path("previewDigest").asString() : "";
    if (!root.isObject() || root.size() != 2 || !PARTS.contains(packageId)
        || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("A removable part and its preview are required");
    }
    return new Request(packageId, digest);
  }

  @Override
  public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    try {
      request(argumentsJson);
    } catch (RuntimeException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "A removable part and its preview are required", "BAD_REQUEST", Map.of(), false));
    }
    return OperationPreparation.passthrough(argumentsJson);
  }

  @Override
  public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    Request request = request(prepared.argumentsJson());
    String part = "chat".equals(request.packageId())
        ? "the answer model and its picture-reading file" : "Best match first";
    return new OperationApprovalPreview("Remove the downloaded files of " + part
        + " (the list shown, " + request.previewDigest().substring(0, 12) + "). Each file is"
        + " verified first; files another part needs or that JustSearch can't prove it downloaded"
        + " are kept. Source documents, the index and conversations are not touched.");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext context) {
    return executeRecorded(argumentsJson, null, context, null).response();
  }

  @Override
  public OperationExecution executeRecorded(String argumentsJson, InvocationProvenance provenance,
      EngineContext context, OperationRecordHandle record) {
    final Request request;
    try {
      request = request(argumentsJson);
    } catch (RuntimeException invalid) {
      return OperationExecution.finished(OperationResult.failure(
          "A removable part and its preview are required", "BAD_REQUEST", Map.of(), false));
    }
    BrainInstallService service;
    try {
      service = install.get();
    } catch (RuntimeException unavailable) {
      service = null;
    }
    if (service == null) {
      return OperationExecution.finished(OperationResult.failure(
          "AI install service unavailable", "FILES_NOT_REMOVED", Map.of(), true));
    }
    final io.justsearch.app.api.AiInstallService.RemovalAttempt attempt;
    try {
      attempt = service.removeFiles(request.packageId(), request.previewDigest());
    } catch (AiInstallException refused) {
      return OperationExecution.finished(OperationResult.failure(refused.getMessage(),
          refused.errorCode().name(), Map.of("packageId", request.packageId()), false));
    }
    var completion = attempt.completion().thenApply(RemoveAiFilesHandler::result);
    return new OperationExecution(OperationResult.success("Removing files",
        asMap(attempt.started())), completion);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(DownloadedFiles.Removal removal) {
    return HandlerJson.MAPPER.convertValue(removal, Map.class);
  }

  private static OperationResult result(DownloadedFiles.Removal removal) {
    Map<String, Object> data = asMap(removal);
    return "removed".equals(removal.state)
        ? OperationResult.success(removal.message, data)
        : OperationResult.failure(removal.message, "FILES_NOT_REMOVED", data, false);
  }
}
