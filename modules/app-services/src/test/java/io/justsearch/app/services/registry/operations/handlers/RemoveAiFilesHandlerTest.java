/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.services.TestEngineContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@code core.remove-ai-files} and "Download again" at the operation boundary (design D4). */
final class RemoveAiFilesHandlerTest {
  private static final String DIGEST = "a".repeat(64);

  /** Records what reached the install owner. */
  private static final class Owner implements BrainInstallService {
    final List<String> calls = new ArrayList<>();
    AiInstallException refuse;
    DownloadedFiles.Removal outcome;

    @Override
    public AiInstallService.Attempt startInstall(boolean acceptTerms) {
      calls.add("start");
      return attempt();
    }

    @Override
    public AiInstallService.Attempt startInstall(boolean acceptTerms, boolean enableReranker,
        boolean enablePictureReading, Set<String> includePackages) {
      calls.add("start include " + new java.util.TreeSet<>(includePackages));
      return attempt();
    }

    @Override
    public AiInstallService.RemovalAttempt removeFiles(String packageId, String previewDigest) {
      calls.add("remove " + packageId + " " + previewDigest);
      if (refuse != null) throw refuse;
      var started = new DownloadedFiles.Removal();
      started.state = "running";
      return new AiInstallService.RemovalAttempt(started, CompletableFuture.completedFuture(outcome));
    }

    @Override
    public Map<String, Object> cancelInstall() {
      return Map.of();
    }

    @Override
    public AiInstallService.Attempt repairInstall(boolean acceptTerms) {
      return attempt();
    }

    private static AiInstallService.Attempt attempt() {
      return new AiInstallService.Attempt(new AiInstallStatus(),
          CompletableFuture.completedFuture(new AiInstallStatus()));
    }
  }

  private static DownloadedFiles.Removal outcome(String state, String message) {
    var removal = new DownloadedFiles.Removal();
    removal.state = state;
    removal.message = message;
    return removal;
  }

  @Test
  void aPartIdAndItsPreviewReachTheOwnerAndAFullRemovalSucceeds() throws Exception {
    var owner = new Owner();
    owner.outcome = outcome("removed", "4 files removed");
    var handler = new RemoveAiFilesHandler(() -> owner);

    var execution = handler.executeRecorded(
        "{\"packageId\":\"chat\",\"previewDigest\":\"" + DIGEST + "\"}", null,
        TestEngineContexts.internal(), null);

    assertTrue(execution.response().success());
    assertEquals(List.of("remove chat " + DIGEST), owner.calls);
    var finished = execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(finished.success());
    assertEquals("4 files removed", finished.message());
  }

  @Test
  void filesLeftBehindNeverReportSuccess() throws Exception {
    var owner = new Owner();
    owner.outcome = outcome("partly-removed", "1 file removed, 1 file kept");
    var execution = new RemoveAiFilesHandler(() -> owner).executeRecorded(
        "{\"packageId\":\"reranker\",\"previewDigest\":\"" + DIGEST + "\"}", null,
        TestEngineContexts.internal(), null);

    var finished = execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertFalse(finished.success());
    assertEquals("FILES_NOT_REMOVED", finished.errorCode().orElseThrow());
  }

  @Test
  void aRefusalIsReturnedWithItsPlainReason() {
    var owner = new Owner();
    owner.refuse = new AiInstallException(409, ApiErrorCode.FILES_NOT_REMOVED, "Switch answers off first");

    var execution = new RemoveAiFilesHandler(() -> owner).executeRecorded(
        "{\"packageId\":\"chat\",\"previewDigest\":\"" + DIGEST + "\"}", null,
        TestEngineContexts.internal(), null);

    assertFalse(execution.response().success());
    assertEquals("Switch answers off first", execution.response().message());
    assertEquals("FILES_NOT_REMOVED", execution.response().errorCode().orElseThrow());
  }

  @Test
  void pathsAndUnknownPartsAreRefusedBeforeTheOwnerIsAsked() {
    var owner = new Owner();
    var handler = new RemoveAiFilesHandler(() -> owner);

    for (String args : List.of(
        "{\"packageId\":\"embedding\",\"previewDigest\":\"" + DIGEST + "\"}",
        "{\"packageId\":\"chat\",\"previewDigest\":\"" + DIGEST + "\",\"path\":\"/models\"}",
        "{\"packageId\":\"chat\",\"previewDigest\":\"short\"}")) {
      assertThrows(OperationPreparationRefused.class,
          () -> handler.prepare(args, null, TestEngineContexts.internal()), args);
      assertFalse(handler.executeRecorded(args, null, TestEngineContexts.internal(), null)
          .response().success(), args);
    }
    assertTrue(owner.calls.isEmpty());
  }

  @Test
  void theApprovalNamesWhatIsRemovedAndWhatIsNotTouched() {
    var preview = new RemoveAiFilesHandler(Owner::new).approvalPreview(OperationPreparation.passthrough(
        "{\"packageId\":\"chat\",\"previewDigest\":\"" + DIGEST + "\"}"));

    assertTrue(preview.summary().contains("the answer model and its picture-reading file"), preview.summary());
    assertTrue(preview.summary().contains("conversations are not touched"), preview.summary());
  }

  @Test
  void downloadAgainCarriesItsPartsToTheAcceptedStart() {
    var owner = new Owner();

    new StartAiInstallHandler(() -> owner).executeRecorded(
        "{\"acceptTerms\":true,\"includePackages\":[\"reranker\"]}", null,
        TestEngineContexts.internal(), null);
    new StartAiInstallHandler(() -> owner).executeRecorded(
        "{\"acceptTerms\":true}", null, TestEngineContexts.internal(), null);

    assertEquals(List.of("start include [reranker]", "start"), owner.calls);
  }
}
