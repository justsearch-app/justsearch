/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiPackImportStatus;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.packimport.PackImportServiceImpl;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.registry.operations.handlers.ImportAiPackHandler;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class AiPackOperationCompletionTest {
  @TempDir Path directory;
  private String previousHome;
  private String previousData;

  @BeforeEach void setHome() {
    previousHome = System.getProperty("justsearch.home");
    previousData = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.home", directory.toString());
    System.setProperty("justsearch.data.dir", directory.toString());
  }

  @AfterEach void restoreHome() {
    restore("justsearch.home", previousHome);
    restore("justsearch.data.dir", previousData);
  }

  @Test void acceptedRowAndSingleFlightSurviveLeaseCleanup() throws Exception {
    var owner = owner();
    var lease = mock(OperationLeaseHandle.class);
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap())).thenReturn(lease);
    owner.setOperationLeaseService(leases);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return null; })
        .when(lease).release(any());
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      String arguments = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
          java.util.Map.of("path", directory.resolve("missing-pack.zip").toString()));
      var request = new OperationAttemptRunner.Request(null,
          OperationDescriptor.invocation(OperationKind.OPERATION, "core.import-ai-pack", arguments, false),
          TestEngineContexts.internal(), null);
      var accepted = runner.accept(request);
      var handler = new ImportAiPackHandler(() -> new PackImportServiceImpl(owner));
      var result = runner.start(accepted, record -> handler.executeRecorded(
          arguments, null, request.context(), record));
      assertTrue(result.response().success());
      assertEquals("running", result.response().structuredData().get("state"));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertFalse(result.completion().toCompletableFuture().isDone());
      assertEquals(OperationState.RUNNING, store.find(accepted.accepted().key()).orElseThrow().state());
      assertThrows(IllegalStateException.class, () -> owner.startImport(directory.resolve("other.zip"), false));
      release.countDown();
      var terminal = result.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals("PACK_NOT_FOUND", terminal.failureReason());
      verify(lease).release(OpLeaseOutcome.FAILURE);
    } finally { release.countDown(); owner.awaitThreadCompletion(5000); }
  }

  @Test void registrationRefusalReleasesGuardAndSnapshotsRemainIndependent() throws Exception {
    var owner = owner();
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap())).thenThrow(new IllegalStateException("Frozen"));
    owner.setOperationLeaseService(leases);
    assertThrows(IllegalStateException.class, () -> owner.startImport(directory.resolve("missing.zip"), false));
    assertEquals("PACK_IMPORT_START_FAILED", owner.getStatus().errorCode);
    owner.setOperationLeaseService(OperationLeaseService.noOp());
    var attempt = owner.startImport(directory.resolve("missing.zip"), false);
    var terminal = attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertEquals("running", attempt.started().state);
    assertEquals("failed", terminal.state);
    terminal.state = "mutated";
    attempt.started().packId = "mutated";
    assertEquals("failed", owner.getStatus().state);
    assertNotEquals("mutated", owner.getStatus().packId);
    owner.awaitThreadCompletion(5000);
  }

  @Test void leaseFailureCannotCompleteSuccessfullyOrStrandSingleFlight() throws Exception {
    var owner = owner();
    var lease = mock(OperationLeaseHandle.class);
    doThrow(new IllegalStateException("Cleanup failed")).when(lease).release(any());
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap())).thenReturn(lease);
    owner.setOperationLeaseService(leases);
    var attempt = owner.startImport(directory.resolve("missing.zip"), false);
    assertThrows(java.util.concurrent.ExecutionException.class,
        () -> attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    assertEquals("PACK_IMPORT_OWNER_FAILED", owner.getStatus().errorCode);
    owner.setOperationLeaseService(OperationLeaseService.noOp());
    owner.startImport(directory.resolve("missing.zip"), false).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    owner.awaitThreadCompletion(5000);
  }

  @Test void staleProgressCannotRevokeClaimedOwnership() throws Exception {
    var owner = owner();
    var statusField = AiPackImportService.class.getDeclaredField("status");
    statusField.setAccessible(true);
    var status = (AiPackImportStatus) statusField.get(owner);
    var runningField = AiPackImportService.class.getDeclaredField("running");
    runningField.setAccessible(true);
    var running = (AtomicBoolean) runningField.get(owner);
    status.state = "running";
    status.updatedAtEpochMs = 1;
    running.set(true);
    assertEquals("running", owner.getStatus().state);
    assertTrue(running.get());
    assertThrows(IllegalStateException.class, () -> owner.startImport(directory.resolve("missing.zip"), false));
    running.set(false);
    assertEquals("failed", owner.getStatus().state, "unowned stale status still reaps");
  }

  private AiPackImportService owner() {
    return new AiPackImportService(OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE), null,
        new EnterprisePolicyServiceImpl(), new PackAllowlistService(Set.of()));
  }

  private static void restore(String key, String previous) {
    if (previous == null) System.clearProperty(key);
    else System.setProperty(key, previous);
  }
}
