/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class PictureReadingInstallIntentTest {
  @Test
  void consentedPictureIntentReachesTheInstallerThroughTheOperationAndFacade() {
    var helper = mock(AiInstallService.class);
    when(helper.startInstall(true, false, true)).thenReturn(completed());
    var handler = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(helper));
    var operation = handler.executeRecorded(
        "{\"acceptTerms\":true,\"enablePictureReadingOnCompletion\":true}", null,
        TestEngineContexts.internal(), null);
    assertTrue(operation.response().success(), operation.response().toString());
    assertTrue(operation.completion().toCompletableFuture().join().success());
    verify(helper).startInstall(true, false, true);
    verifyNoMoreInteractions(helper);
  }

  @Test
  void anOrdinaryInstallKeepsItsExistingEntryWithoutAPictureEnable() {
    var helper = mock(AiInstallService.class);
    when(helper.startInstall(true)).thenReturn(completed());
    var handler = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(helper));
    var operation = handler.executeRecorded("{\"acceptTerms\":true}", null,
        TestEngineContexts.internal(), null);
    assertTrue(operation.response().success(), operation.response().toString());
    assertTrue(operation.completion().toCompletableFuture().join().success());
    verify(helper).startInstall(true);
    verifyNoMoreInteractions(helper);
  }

  private static AiInstallService.Attempt completed() {
    var status = new AiInstallStatus();
    status.state = "completed";
    return new AiInstallService.Attempt(status, CompletableFuture.completedFuture(status));
  }
}
