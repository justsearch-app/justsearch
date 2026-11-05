/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import io.justsearch.app.services.ai.pack.PackAllowlistService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The other owners that can place or load managed model files register with the install owner's
 * removal lock (design D4): a pack import and a runtime activation or deactivation are refused
 * while files are being removed, and a removal is refused while one of them runs.
 */
@Tag("cross-platform")
final class ModelFileRemovalRoutesTest {

  @TempDir Path tmp;

  /** A lease whose release blocks until the test lets the owner finish. */
  private static final class HeldLease {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final OperationLeaseService service = mock(OperationLeaseService.class);

    HeldLease() {
      OperationLeaseHandle lease = mock(OperationLeaseHandle.class);
      doAnswer(call -> {
        entered.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
        return null;
      }).when(lease).release(any());
      when(service.register(anyString(), any(), anyLong(), anyMap())).thenReturn(lease);
      when(service.register(anyString(), any(), anyLong(), anyMap(), any())).thenReturn(lease);
    }
  }

  private UiSettingsStore storeWith(ModelFileRemovalFence fence) {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    store.bindModelFileRemovalFence(fence);
    return store;
  }

  @Test
  void aPackImportIsRefusedWhileRemovingAndARunningImportRefusesTheRemoval() throws Exception {
    var fence = new ModelFileRemovalFence();
    var store = storeWith(fence);
    var imports = new AiPackImportService(OnlineAiService.unavailable(), store, null,
        new io.justsearch.app.services.policy.EnterprisePolicyServiceImpl(),
        new PackAllowlistService(Set.of()));

    try (var _ = fence.begin("chat")) {
      var refused = assertThrows(ModelFileRemovalFence.Refused.class,
          () -> imports.startImport(tmp.resolve("pack.zip"), false));
      assertEquals(ModelFileRemovalFence.REMOVING, refused.getMessage());
      assertEquals("idle", imports.getStatus().state, "the refused import never started");
    }

    var held = new HeldLease();
    imports.setOperationLeaseService(held.service);
    imports.startImport(tmp.resolve("missing.zip"), false);
    assertTrue(held.entered.await(10, TimeUnit.SECONDS), "the import owner is still finishing");
    var refused = assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("chat"));
    assertEquals("An AI pack import is running - try again when it finishes", refused.getMessage());
    held.release.countDown();
    imports.awaitThreadCompletion(10_000);
    fence.begin("chat").close();
  }

  @Test
  void installedModelActivationIsRefusedWhileRemoving() throws Exception {
    var owner = new AiInstallService(null, null, null, null, tmp);
    var handler = new io.justsearch.app.services.registry.operations.handlers.ActivateInstalledModelsHandler(
        () -> new io.justsearch.app.services.braininstall.BrainInstallServiceImpl(owner),
        mock(io.justsearch.app.api.operations.RecordedIngestionService.class), context -> java.util.List.of(),
        () -> null, java.util.List::of);
    String args = "{\"source\":\""
        + io.justsearch.app.api.status.MigrationSource.INSTALLER_MODEL_ACTIVATION.wire() + "\"}";

    try (var _ = owner.removalFence().begin("reranker")) {
      var refused = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
          () -> handler.prepare(args, null, io.justsearch.app.services.TestEngineContexts.internal()));
      assertEquals(ModelFileRemovalFence.REMOVING, refused.refusal().message());
      assertEquals("MODEL_FILES_REMOVING", refused.refusal().errorCode().orElseThrow());
    }
    var afterwards = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
        () -> handler.prepare(args, null, io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals("ACTIVATION_CANDIDATE_UNAVAILABLE", afterwards.refusal().errorCode().orElseThrow(),
        "with the lock released it reaches its own candidate check");
  }

  @Test
  void aRuntimeActivationOrDeactivationIsRefusedWhileRemovingAndARunningOneRefusesTheRemoval()
      throws Exception {
    String previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", tmp.toString());
    var fence = new ModelFileRemovalFence();
    try (var executors = new TestEngineExecutors();
        var activation = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            storeWith(fence), null, null)) {
      try (var _ = fence.begin("chat")) {
        assertEquals(ModelFileRemovalFence.REMOVING, assertThrows(ModelFileRemovalFence.Refused.class,
            () -> activation.startActivate("default", "compact")).getMessage());
        assertEquals(ModelFileRemovalFence.REMOVING, assertThrows(ModelFileRemovalFence.Refused.class,
            activation::startDeactivate).getMessage());
      }

      var held = new HeldLease();
      activation.setOperationLeaseService(held.service);
      var attempt = activation.startDeactivate();
      assertTrue(held.entered.await(10, TimeUnit.SECONDS));
      assertEquals("A graphics runtime change is running - try again when it finishes",
          assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("chat")).getMessage());
      held.release.countDown();
      attempt.completion().toCompletableFuture().handle((status, failure) -> null).get(10, TimeUnit.SECONDS);
      fence.begin("chat").close();
    } finally {
      if (previousHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", previousHome);
    }
  }
}
