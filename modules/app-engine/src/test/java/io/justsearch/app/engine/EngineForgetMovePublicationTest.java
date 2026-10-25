/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static io.justsearch.app.engine.EngineForgetInProcessTest.field;
import static io.justsearch.app.engine.EngineForgetInProcessTest.key;
import static io.justsearch.app.engine.EngineForgetInProcessTest.webview;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.UpdatePathsResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real physical tool/terminal row/root retirement/forget and surviving Engine publication. */
@Timeout(90)
final class EngineForgetMovePublicationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test void MD55ExpiredMoveCannotRepublishAfterCompletedOutsideFileForget() throws Exception {
    stalePublication("MOVE", false, false);
  }
  @Test void MD55CancelledMoveBeforeSignalCannotRepublishAfterCompletedOutsideFileForget() throws Exception {
    stalePublication("MOVE", true, false);
  }
  @Test void MD55ExpiredRenameCannotRekeyAfterRootRetirementAndCompletedForget() throws Exception {
    stalePublication("RENAME", false, false);
  }
  @Test void MD55CancelledUndoCannotRepublishAfterCompletedOutsideFileForget() throws Exception {
    stalePublication("MOVE", true, true);
  }

  @Test void MD55ExplicitElapsedRequestDeadlineWithoutCancellationCannotPublishAtWorkerFence() throws Exception {
    Path a = Files.createDirectory(directory.resolve("clock-root"));
    Path original = Files.writeString(a.resolve("before.txt"), "clock physical bytes");
    Path destination = a.resolve("after.txt");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(a))) {
      f.seed(original, "clock indexed bytes"); Files.move(original, destination);
      // Isolate the absolute-clock predicate at the real Worker service. The integrated cases
      // above retain the actual Engine context; Engine also has an independent timed caller wait.
      var maintenance = CallContext.none();
      var expired = new CallContext(null, null, CallContext.CancelSignal.NEVER,
          maintenance.engineContext(), maintenance.provenance(), maintenance.childLifetime(), System.nanoTime() - 1);
      assertFalse(expired.cancelled());
      var response = f.server.appServices().ingestService().updateDocumentPaths(
          io.justsearch.ipc.UpdatePathsRequest.newBuilder().addMappings(io.justsearch.ipc.PathMapping.newBuilder()
              .setOldPath(original.toString()).setNewPath(destination.toString())).build(), expired);
      assertEquals(0, response.getUpdatedCount(), "an explicitly bounded non-singleton context must respect its elapsed deadline");
      f.runtime.commitOps().maybeRefreshBlocking();
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(original)));
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(destination)));
      assertTrue(f.identities.lookup(DocumentIdentityStore.pathHash(key(destination))).isEmpty());
      assertEquals("clock physical bytes", Files.readString(destination));
    }
  }

  @Test void MD55AlreadyAdmittedPublisherKeepsForgetIncompleteUntilExplicitRetry() throws Exception {
    Path a = Files.createDirectory(directory.resolve("first-root"));
    Path b = Files.createDirectory(directory.resolve("retired-root"));
    Path original = Files.writeString(a.resolve("source.txt"), "fenced physical content");
    Path target = b.resolve("target.txt");
    var beforeFence = new CountDownLatch(1); var enterFence = new CountDownLatch(1);
    var insideFence = new CountDownLatch(1); var finishPublisher = new CountDownLatch(1);
    var response = new CompletableFuture<UpdatePathsResponse>();
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(a, b));
        var work = f.root.admission().admit(interactive(), false)) {
      f.seed(original, "fenced indexed content");
      var services = (DefaultWorkerAppServices) f.server.appServices(); var actual = services.ingestService();
      var held = spy(actual); var heldLoop = spy(f.loop);
      var firstPublication = new AtomicBoolean(true);
      doAnswer(invocation -> {
        if (firstPublication.compareAndSet(true, false)) {
          beforeFence.countDown(); assertTrue(enterFence.await(10, TimeUnit.SECONDS));
        }
        Supplier<?> body = invocation.getArgument(0); return f.loop.fileMutationFence().guard(body);
      }).when(heldLoop).withFileMutationFence(any());
      set(held, WorkerIngestService.class, "indexingLoop", heldLoop);
      var identities = spy(f.identities);
      doAnswer(invocation -> {
        assertTrue(f.loop.fileMutationFence().isHeldByCurrentThread());
        insideFence.countDown(); assertTrue(finishPublisher.await(10, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      }).when(identities).rekey(any(String.class), any(String.class), org.mockito.ArgumentMatchers.anyLong());
      held.setDocumentIdentityStore(identities);
      doAnswer(invocation -> {
        try { var result = (UpdatePathsResponse) invocation.callRealMethod(); response.complete(result); return result; }
        catch (Throwable failure) { response.completeExceptionally(failure); throw failure; }
      }).when(held).updateDocumentPaths(any(), any());
      set(services, DefaultWorkerAppServices.class, "ingestService", held);
      var tool = new FileOperationsTool(context -> f.authority.roots().watchedPaths(),
          f.client::updateDocumentPaths, new FileOperationLog(directory.resolve("file-operations")));
      String arguments = JSON.writeValueAsString(Map.of("operations", List.of(Map.of("op", "MOVE",
          "source", original.toString(), "destination", target.toString()))));
      String operationKey = OperationKeys.generate(Clock.systemUTC()); var context = work.context();
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Clock.systemUTC().instant(), Optional.empty());
      var accepted = f.attempts.accept(new OperationAttemptRunner.Request(operationKey,
          OperationDescriptor.invocation(OperationKind.OPERATION, "core.file-operations", arguments, false), context, provenance));
      var physical = new FutureTask<>(() -> f.attempts.start(accepted,
          handle -> OperationExecution.finished(tool.execute(arguments, context))).response());
      Thread.ofVirtual().start(physical);
      try {
        assertTrue(beforeFence.await(3, TimeUnit.SECONDS));
        assertTrue(f.client.removeWatchedPath(b, webview()) >= 0);
        enterFence.countDown(); assertTrue(insideFence.await(3, TimeUnit.SECONDS));
        work.cancel("user_stop_after_publication_admission");
        assertTrue(physical.get(3, TimeUnit.SECONDS).success());
        assertEquals(OperationState.COMPLETE, f.operations.find(operationKey).orElseThrow().state());
        var first = f.forgetAttempt(target, "FILE");
        var unfinished = JSON.valueToTree(first.errorDetails().get("forget"));
        assertEquals("INCOMPLETE", unfinished.path("status").asString(), first.message());
        assertEquals("NOT_REQUIRED", unfinished.path("keepOut").asString());
        assertFalse(response.isDone(), "an already-admitted publisher still owns the actual file fence");
        finishPublisher.countDown(); assertEquals(1, response.get(3, TimeUnit.SECONDS).getUpdatedCount());
        f.runtime.commitOps().maybeRefreshBlocking();
        assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(target)));
        String forgetKey = unfinished.path("operationKey").asString();
        var retried = f.root.forgetService().retry(forgetKey, webview());
        assertEquals("COMPLETE", JSON.valueToTree(retried.structuredData().get("forget")).path("status").asString());
        assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(target)));
        assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
        assertEquals("fenced physical content", Files.readString(target));
      } finally {
        enterFence.countDown(); finishPublisher.countDown();
        set(services, DefaultWorkerAppServices.class, "ingestService", actual);
      }
    } finally { enterFence.countDown(); finishPublisher.countDown(); }
  }

  private void stalePublication(String operation, boolean cancelled, boolean undo) throws Exception {
    Path a = Files.createDirectory(directory.resolve("root-a"));
    Path b = Files.createDirectory(directory.resolve("root-b"));
    Path original = Files.writeString(a.resolve("original.txt"), "physical original content");
    Path destination = (operation.equals("RENAME") ? a : b).resolve("renamed.txt");
    var publisherEntered = new CountDownLatch(1);
    var publisherRelease = new CountDownLatch(1);
    var callerCompleted = new CountDownLatch(1);
    var signalRelease = new CountDownLatch(1);
    var response = new CompletableFuture<UpdatePathsResponse>();
    var callContext = new AtomicReference<CallContext>();
    Thread termination = null;
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(a, b),
            cancelled ? 60_000 : 1_000);
        var work = f.root.admission().admit(interactive(), false)) {
      f.seed(original, "old indexed original content");
      var tool = new FileOperationsTool(context -> f.authority.roots().watchedPaths(),
          f.client::updateDocumentPaths, new FileOperationLog(directory.resolve("file-operations")));
      String arguments = JSON.writeValueAsString(Map.of("operations", List.of(Map.of("op", operation,
          "source", original.toString(), "destination", destination.toString()))));
      String originalExecution = null;
      if (undo) {
        var forward = tool.execute(arguments, work.context());
        assertTrue(forward.success(), forward.message());
        originalExecution = forward.executionId().orElseThrow();
        f.runtime.commitOps().maybeRefreshBlocking();
        assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(destination)));
      }
      Path target = undo ? original : destination;
      Path publicationSource = undo ? destination : original;
      Path retiredRoot = undo || operation.equals("RENAME") ? a : b;
      String oldHash = DocumentIdentityStore.pathHash(key(publicationSource));
      String newHash = DocumentIdentityStore.pathHash(key(target));
      String originalUid = f.identities.lookup(oldHash).orElseThrow().docUid();
      var actualServices = (DefaultWorkerAppServices) f.server.appServices();
      var actualIngest = actualServices.ingestService();
      var heldLoop = spy(f.loop);
      var first = new AtomicBoolean(true);
      doAnswer(invocation -> {
        if (first.compareAndSet(true, false)) {
          publisherEntered.countDown(); assertTrue(publisherRelease.await(20, TimeUnit.SECONDS));
        }
        Supplier<?> body = invocation.getArgument(0);
        return f.loop.fileMutationFence().guard(body);
      }).when(heldLoop).withFileMutationFence(any());
      var observingIngest = spy(actualIngest);
      set(observingIngest, WorkerIngestService.class, "indexingLoop", heldLoop);
      doAnswer(invocation -> {
        callContext.set(invocation.getArgument(1));
        try {
          var result = (UpdatePathsResponse) invocation.callRealMethod(); response.complete(result); return result;
        } catch (Throwable failure) { response.completeExceptionally(failure); throw failure; }
      }).when(observingIngest).updateDocumentPaths(any(), any());
      set(actualServices, DefaultWorkerAppServices.class, "ingestService", observingIngest);
      var alarm = new CompletableFuture<Runnable>();
      var timer = mock(ScheduledExecutorService.class);
      when(timer.schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any())).thenAnswer(invocation -> {
        alarm.complete(invocation.getArgument(0)); return mock(ScheduledFuture.class);
      });
      set(f.client, EngineKnowledgeClient.class, "deadlines", timer);
      var context = work.context();
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Clock.systemUTC().instant(), Optional.empty());
      String operationKey = OperationKeys.generate(Clock.systemUTC());
      var descriptor = OperationDescriptor.invocation(OperationKind.OPERATION, "core.file-operations", arguments, undo);
      var accepted = f.attempts.accept(new OperationAttemptRunner.Request(operationKey, descriptor, context, provenance));
      String execution = originalExecution;
      var physical = new FutureTask<>(() -> f.attempts.start(accepted,
          handle -> OperationExecution.finished(undo ? tool.undo(execution, context) : tool.execute(arguments, context))).response());
      Thread.ofVirtual().start(physical);
      try {
        assertTrue(publisherEntered.await(10, TimeUnit.SECONDS));
        Runnable expire = alarm.get(3, TimeUnit.SECONDS);
        Object budget = capturedBudget(expire);
        @SuppressWarnings("unchecked")
        Consumer<Throwable> originalFailure = (Consumer<Throwable>) field(budget, budget.getClass(), "fail");
        set(budget, budget.getClass(), "fail", (Consumer<Throwable>) failure -> {
          originalFailure.accept(failure); callerCompleted.countDown();
          try { assertTrue(signalRelease.await(20, TimeUnit.SECONDS)); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        if (!cancelled) {
          long deadline = callContext.get().deadlineNanos();
          while (deadline - System.nanoTime() > 0) Thread.sleep(1);
        }
        termination = Thread.ofVirtual().start(() -> {
          if (cancelled) work.cancel("user_stop"); else expire.run();
        });
        assertTrue(callerCompleted.await(3, TimeUnit.SECONDS));
        OperationResult physicalResult = physical.get(5, TimeUnit.SECONDS);
        assertTrue(physicalResult.success(), physicalResult.message());
        assertEquals(OperationState.COMPLETE, f.operations.find(operationKey).orElseThrow().state(),
            "actual file-operation wrapper may terminalize while its worker is still held");
        assertFalse(response.isDone(), "the old MOVE publication still owns its actual Engine worker");
        assertEquals("physical original content", Files.readString(target));
        assertTrue(Files.notExists(publicationSource));
        assertTrue(f.client.removeWatchedPath(retiredRoot, webview()) >= 0);
        assertFalse(f.authority.roots().watchedPaths().contains(retiredRoot));
        var forgotten = JSON.valueToTree(f.forget(target, "FILE").structuredData().get("forget"));
        assertEquals("COMPLETE", forgotten.path("status").asString());
        assertEquals("NOT_REQUIRED", forgotten.path("keepOut").asString());
        assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
        publisherRelease.countDown();
        UpdatePathsResponse late = response.get(5, TimeUnit.SECONDS);
        assertEquals(0, late.getUpdatedCount(), "an expired/cancelled original call cannot report a late identity or row update");
        assertEquals(List.of(publicationSource.toString()), late.getFailedPathsList());
        f.runtime.commitOps().maybeRefreshBlocking();
        assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(target)),
            "completed forget remains absent after the old publisher resumes");
        assertEquals(originalUid, f.identities.lookup(oldHash).orElseThrow().docUid());
        assertTrue(f.identities.lookup(newHash).isEmpty(), "there is no late durable identity rekey");
        assertEquals("physical original content", Files.readString(target));
        assertEquals("COMPLETE", f.root.forgetService().observe(forgotten.path("operationKey").asString(), webview())
            .orElseThrow().status().name());
      } finally {
        publisherRelease.countDown(); signalRelease.countDown();
        if (termination != null) termination.join(5_000);
        set(actualServices, DefaultWorkerAppServices.class, "ingestService", actualIngest);
      }
    } finally { publisherRelease.countDown(); signalRelease.countDown(); }
  }

  private static EngineContext interactive() {
    return EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "forget-move-engine",
        Optional.empty(), Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }
  private static Object capturedBudget(Runnable alarm) throws Exception {
    for (var field : alarm.getClass().getDeclaredFields()) {
      if (field.getType().getSimpleName().equals("Budget")) { field.setAccessible(true); return field.get(alarm); }
    }
    throw new AssertionError("deadline action must retain the actual call Budget");
  }
  private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
}
