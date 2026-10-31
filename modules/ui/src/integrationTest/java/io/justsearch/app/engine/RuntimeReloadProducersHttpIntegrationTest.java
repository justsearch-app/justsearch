/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.api.*;
import io.justsearch.ui.api.routes.DebugRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Native runtime/producer, real Engine root/bootstrap and actual admin HTTP handler. Models stubbed. */
@Timeout(60)
final class RuntimeReloadProducersHttpIntegrationTest {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
  private static final EngineContext CONTEXT = EngineProvenance.internal(
      "reload-producer-check", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @Test
  void oldProducerMustExitBeforeItsRuntimeDrains() throws Exception {
    try (var f = new Fixture()) {
      var runtime = field(f.server, "ingestLifecycle");
      var closing = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old = f.spyProducer();
      org.mockito.stubbing.Answer<Object> stop = invocation -> {
        closing.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      };
      doAnswer(stop).when(old).close();
      doAnswer(stop).when(old).closeNativeLoopRetainingModels();
      var reload = CompletableFuture.supplyAsync(f::post);
      try {
        assertTrue(closing.await(5, TimeUnit.SECONDS));
        assertTrue(((io.justsearch.adapters.lucene.runtime.RunningRuntime) runtime).isAcceptingWrites(),
            "old producer still owns work: runtime must remain open until producer close succeeds");
      } finally { release.countDown(); }
      assertReloadSuccess(reload.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void admittedWatcherMutationMustExitBeforeItsRuntimeDrains() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(old.mutationAdmission(), "lock");
      CompletableFuture<HttpResponse<String>> reload;
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        reload = CompletableFuture.supplyAsync(f::post);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!gate.hasQueuedThreads() && !reload.isDone() && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertTrue(runtime.isAcceptingWrites(), "an issued watcher effect still owns the native runtime");
        assertTrue(gate.hasQueuedThreads(), "reload must drain the admitted watcher mutation");
        assertFalse(reload.isDone());
      }
      assertReloadSuccess(reload.get(10, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class,
          () -> old.mutationAdmission().enter(old.mutationOwnerToken()),
          "a late old callback cannot enter after its runtime closes");
    }
  }

  @Test
  void shutdownWinningDuringProducerDrainCannotPublishAReplacement() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(old.mutationAdmission(), "lock");
      CompletableFuture<HttpResponse<String>> reload;
      CompletableFuture<Void> closed;
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        reload = CompletableFuture.supplyAsync(f::post);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!gate.hasQueuedThreads() && !reload.isDone() && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertTrue(gate.hasQueuedThreads(), "the issued mutation keeps the native swap pending");
        closed = CompletableFuture.runAsync(() -> assertDoesNotThrow(f.server::close));
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!(boolean) field(f.server, "closeStarted") && System.nanoTime() < until) Thread.sleep(5);
        assertTrue((boolean) field(f.server, "closeStarted"));
      }
      var refused = reload.get(10, TimeUnit.SECONDS);
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("during close"), refused.body());
      closed.get(10, TimeUnit.SECONDS);
      assertTrue(f.server.awaitClosed(0));
      assertThrows(IllegalStateException.class, f.server::captureServingView);
    }
  }

  @Test
  void mutationDrainTimeoutRestoresAdmissionAndRetryWorks() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        var response = f.post();
        assertEquals(500, response.statusCode(), response.body());
        assertTrue(response.body().contains("active producer mutations"), response.body());
        assertTrue(runtime.isAcceptingWrites());
        assertTrue(old.recordedWriterReady());
        assertNotNull(f.client.search("stillopen", 10, CONTEXT));
      }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void refusedProducerCloseRetainsItsRuntimeUntilRetry() throws Exception {
    try (var f = new Fixture()) {
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var mayClose = new java.util.concurrent.atomic.AtomicBoolean();
      var old = f.spyProducer();
      org.mockito.stubbing.Answer<Object> stop = invocation -> {
        if (!mayClose.get()) throw new java.io.IOException("producer still owns an extraction");
        return invocation.callRealMethod();
      };
      doAnswer(stop).when(old).close();
      doAnswer(stop).when(old).closeNativeLoopRetainingModels();
      try {
        var refused = f.post();
        assertEquals(500, refused.statusCode(), refused.body());
        assertTrue(runtime.isAcceptingWrites(), "a refused producer drain must retain its runtime");
        assertSame(old, f.server.appServices(), "the refused owner must remain reachable for cleanup");
      } finally { mayClose.set(true); }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void fiveHttpReloadsImmediatelyAcceptSearchAndIngestionWithoutFalseRecovery() throws Exception {
    try (var f = new Fixture()) {
      for (int i = 0; i < 5; i++) {
        var observation = new java.util.concurrent.atomic.AtomicReference<Optional<Boolean>>();
        var old = f.spyProducer();
        org.mockito.stubbing.Answer<Object> stop = invocation -> {
          // A real monitor poll overlaps the physical transition on another thread, every time.
          observation.set(CompletableFuture.supplyAsync(f.bootstrap::tryCheckHealth)
              .get(3, TimeUnit.SECONDS));
          return invocation.callRealMethod();
        };
        doAnswer(stop).when(old).close();
        doAnswer(stop).when(old).closeNativeLoopRetainingModels();
        assertReloadSuccess(f.post());
        assertEquals(Optional.empty(), observation.get(),
            "planned reload must not publish index.failed and retire the client's executors");
        assertEquals(ComponentState.READY, f.root.indexComponent().snapshot().state());
        assertNotNull(f.client.search("reloadproducer", 10, CONTEXT));
        Path file = directory.resolve("immediate-" + i + ".txt");
        Files.writeString(file, "reloadproducer immediate ingestion " + i);
        assertEquals(1, f.client.submitBatch(List.of(file), CONTEXT).getAcceptedCount());
        assertEquals(Optional.of(true), f.bootstrap.tryCheckHealth(),
            "the replacement must accept a fresh physical health observation immediately");
      }
    }
  }

  @Test
  void retainedUnpublishedProducerMustDrainBeforeRuntimeClose() throws Exception {
    try (var f = new Fixture()) {
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var pending = mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      var mayClose = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(ignored -> {
        if (!mayClose.get()) throw new java.io.IOException("unpublished producer still owns work");
        return null;
      }).when(pending).close();
      set(f.server, "pendingAppServices", pending);
      try {
        assertEquals(500, f.post().statusCode());
        assertTrue(runtime.isAcceptingWrites(), "retained unpublished work must drain before runtime close");
        assertSame(pending, field(f.server, "pendingAppServices"));
      } finally { mayClose.set(true); }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void statusSamplerDuringReloadCannotPublishIndexFailure() throws Exception {
    try (var f = new Fixture()) {
      for (String name : List.of("api", "generative")) {
        var component = f.root.components().register(new io.justsearch.core.component.ComponentSpec(
            name, name.equals("api"), java.util.Set.of(),
            io.justsearch.core.component.ComponentSpec.ComposeCapability.IN_PLACE, Duration.ZERO, 0));
        if (name.equals("api")) component.transition(ComponentState.READY, null, null);
      }
      var type = Class.forName("io.justsearch.ui.api.StatusLifecycleHandler");
      var constructor = type.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      var sampler = constructor.newInstance(null, null, null, f.bootstrap, null,
          directory.resolve("index"), java.time.Instant.now(), null, null, null, null, null, null);
      var bind = type.getDeclaredMethod("setIndexComponent",
          io.justsearch.core.component.EngineComponentRegistry.class,
          io.justsearch.core.component.ComponentHandle.class);
      bind.setAccessible(true);
      bind.invoke(sampler, f.root.components(), f.root.indexComponent());
      try {
        var observed = new java.util.concurrent.atomic.AtomicReference<ComponentState>();
        var old = f.spyProducer();
        org.mockito.stubbing.Answer<Object> stop = invocation -> {
          CompletableFuture.runAsync(() -> {
            assertDoesNotThrow(() -> {
              var sample = type.getDeclaredMethod("sampleWorkerIndependently", long.class);
              sample.setAccessible(true);
              sample.invoke(sampler, 2_000L);
              var lanes = (java.util.Map<?, ?>) field(sampler, "observations");
              var publication = lanes.get("index-publication");
              if (publication != null) {
                var completion = publication.getClass().getDeclaredMethod("completion");
                completion.setAccessible(true);
                ((CompletableFuture<?>) completion.invoke(publication)).get(3, TimeUnit.SECONDS);
              }
              observed.set(f.root.indexComponent().snapshot().state());
            });
          }).get(5, TimeUnit.SECONDS);
          return invocation.callRealMethod();
        };
        doAnswer(stop).when(old).close();
        doAnswer(stop).when(old).closeNativeLoopRetainingModels();
        assertReloadSuccess(f.post());
        assertEquals(ComponentState.READY, observed.get(),
            "a planned capture refusal is not an observed index failure");
        assertEquals(ComponentState.READY, f.root.indexComponent().snapshot().state());
      } finally {
        ((io.justsearch.core.component.EngineComponentRegistry.Subscription)
            field(sampler, "componentSubscription")).close();
      }
    }
  }

  @Test
  void previouslyWatchedRootKeepsIngestingAfterReload() throws Exception {
    try (var f = new Fixture()) {
      Path watched = Files.createDirectories(directory.resolve("watched"));
      var registration = f.server.appServices().ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(watched.toString())
              .setRootAdded(true).setCollection("default").build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(registration.getWatching(), registration.getErrorMessage());
      Files.writeString(watched.resolve("before.txt"), "producerwatchbeforecanary");
      f.awaitSearchable("producerwatchbeforecanary");
      assertReloadSuccess(f.post());
      Files.writeString(watched.resolve("after.txt"), "producerwatchaftercanary");
      f.awaitSearchable("producerwatchaftercanary");
    }
  }

  @Test
  void replacementKeepsTheSameEmbeddingAndNerBindingsWithoutClosingSharedNer() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var embedding = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      var ner = mock(io.justsearch.indexerworker.ner.NerService.class);
      var nerClosed = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(ignored -> { nerClosed.set(true); return null; }).when(ner).close();
      old.wireEmbeddingProvider(embedding);
      old.wireNerService(ner);
      assertReloadSuccess(f.post());
      assertFalse(nerClosed.get(), "producer close must retain generation-owned NER for its successor");
      var fresh = (DefaultWorkerAppServices) f.server.appServices();
      var loop = (io.justsearch.indexerworker.loop.IndexingLoop) field(fresh, "indexingLoop");
      assertSame(embedding, loop.getEmbeddingLifecycle().embeddingProvider(),
          "a successful reload must leave enrichment able to drain its pending backlog");
      var bindings = (io.justsearch.indexerworker.server.EncoderBindings) field(fresh, "encoderBindings");
      assertSame(ner, bindings.nerService());
    }
  }

  @Test
  void failedOpenerKeepsTheWaitingWatcherDeleteUntilRetry() throws Exception {
    try (var f = new Fixture()) {
      var files = f.watchedFiles();
      var refused = f.reloadWithSettledDeleteWaiting(files.gone(), () -> {
        throw new IllegalStateException("injected failed opener after native drain");
      });
      assertEquals(500, refused.statusCode(), refused.body());
      f.awaitReceipts(1, "a failed opener must keep the waiting watcher delete as a durable obligation");
      f.useNormalReload();
      assertReloadSuccess(f.post());
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "only the applied root walk retires the obligation");
    }
  }

  @Test
  void failedReconstructionKeepsTheWaitingWatcherDeleteUntilRetry() throws Exception {
    try (var f = new Fixture()) {
      var files = f.watchedFiles();
      var old = f.spyProducer();
      // A's close follows the watcher handoff to B: the replacement fails after B was installed.
      doThrow(new java.io.IOException("injected reconstruction failure after watcher handoff"))
          .doCallRealMethod().when(old).close();
      var refused = f.reloadWithSettledDeleteWaiting(files.gone(), f::reopen);
      assertEquals(500, refused.statusCode(), refused.body());
      f.awaitReceipts(1, "a failed reconstruction must keep the waiting watcher delete as a durable obligation");
      f.useNormalReload();
      assertReloadSuccess(f.post());
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "only the applied root walk retires the obligation");
    }
  }

  @Test
  void successfulNativeReloadDischargesAnExistingWatcherReceipt() throws Exception {
    try (var f = new Fixture()) {
      f.unwatchedFilesWithReceipt();
      assertReloadSuccess(f.post());
      // No extra watch call and no waiting: the reload returns after the root's obligation is met.
      assertEquals(0, f.count("reloadobligationgone"),
          "a successful native reload must discharge the existing receipt before it returns");
      assertEquals(1, f.count("reloadobligationkept"));
      assertEquals(List.of(), f.receipts());
    }
  }

  @Test
  void receiptForAnUnavailableRootWaitsWithoutFailingTheReload() throws Exception {
    try (var f = new Fixture()) {
      var files = f.unwatchedFilesWithReceipt();
      Path away = files.root().resolveSibling(files.root().getFileName() + "-away");
      Files.move(files.root(), away);
      assertReloadSuccess(f.post());
      assertEquals(1, f.receipts().size(), "the unavailable root's receipt must stay pending");
      assertEquals(1, f.count("reloadobligationgone"), "nothing is applied to an unavailable root");
      Files.move(away, files.root());
      assertReloadSuccess(f.post());
      assertEquals(List.of(), f.receipts());
      assertEquals(0, f.count("reloadobligationgone"));
      assertEquals(1, f.count("reloadobligationkept"));
    }
  }

  @Test
  void receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall() throws Exception {
    try (var f = new Fixture()) {
      var files = f.unwatchedFilesWithReceipt();
      Path root = files.root().toAbsolutePath().normalize();
      var ledger = directory.resolve("index").resolve("root-volumes.properties");
      String ownVolume = f.recordedVolume(ledger, root);
      f.recordVolume(ledger, root, "windows-volume:dead");
      var service = f.server.appServices().ingestService();
      var held = service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
              .setRootPath(root.toString()).setCollection("default").build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertFalse(held.getWatching(), "precondition: the watch waits for the root's own volume");
      assertEquals(1, f.receipts().size(), "precondition: the receipt waits with the root");
      f.recordVolume(ledger, root, ownVolume);
      // The periodic sync's call finds the volume back; nothing calls watch.
      service.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
              .setRootPath(root.toString()).setForce(false).build(),
          io.justsearch.indexerworker.services.CallContext.none());
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "the returned root's pending receipt must be applied without a watch call");
    }
  }

  @Test
  void failedNativeReloadAndRetryKeepTheRootWatched() throws Exception {
    try (var f = new Fixture()) {
      var files = f.watchedFiles();
      f.useReload(() -> { throw new IllegalStateException("injected failed opener"); });
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      f.useNormalReload();
      assertReloadSuccess(f.post());
      Files.delete(files.gone());
      Files.writeString(files.root().resolve("added.txt"), "reloadobligationadded after retry");
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationadded", 1);
      f.awaitCount("reloadobligationkept", 1);
    }
  }

  @Test
  @Timeout(180)
  void watcherObligationSurvivesProcessDeathAfterFailedHandoff() throws Exception {
    assertEquals(71, crashChild("failed-handoff"), "the selected crash cut must execute");
    try (var f = new Fixture()) {
      assertEquals(1, f.receipts().size(), "the waiting delete must be durable before process death");
      f.watch(directory.resolve("watched"));
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "the applied reconciliation retires its receipt after the search effect");
    }
  }

  @Test
  @Timeout(180)
  void watcherObligationSurvivesProcessDeathAfterSuccessfulHandoff() throws Exception {
    // Killed as the old owner's close returns: the handoff is done, reconciliation has not begun.
    assertEquals(71, crashChild("close"), "the selected crash cut must execute");
    assertWaitingDeleteRecovered();
  }

  @Test
  @Timeout(180)
  void watcherObligationSurvivesProcessDeathAfterNativeReconciliation() throws Exception {
    // Killed once the successor's loop is active, still inside the fence the DELETE waits on.
    assertEquals(71, crashChild("activatePreparedIndexingLoop"), "the selected crash cut must execute");
    assertWaitingDeleteRecovered();
  }

  @Test
  @Timeout(240)
  void automaticRootRestoreAppliesTheWaitingDeleteWithoutAWatchCall() throws Exception {
    assertEquals(71, crashChild("persisted.close"), "the selected crash cut must execute");
    // The restart restores the persisted root by itself (no watch call): the pending receipt
    // must be applied within awaitCount's bound, not left to a later periodic sync.
    try (var f = new Fixture(true)) {
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "the applied reconciliation retires its receipt after the search effect");
    }
  }

  /** After the restart and its watch call, the waiting DELETE has its effect; the sibling stays. */
  private void assertWaitingDeleteRecovered() throws Exception {
    try (var f = new Fixture()) {
      f.watch(directory.resolve("watched"));
      f.awaitCount("reloadobligationgone", 0);
      f.awaitCount("reloadobligationkept", 1);
      f.awaitReceipts(0, "the applied reconciliation retires its receipt after the search effect");
    }
  }

  private int crashChild(String cut) throws Exception {
    Path output = directory.resolve("child-" + cut + ".log");
    Path arguments = directory.resolve("child.args");
    Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    Process child = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "@" + arguments,
        CrashChild.class.getName(), cut, directory.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(120, TimeUnit.SECONDS), "owned crash child must exit");
      assertEquals(71, child.exitValue(), () -> "crash point not reached: " + tail(output));
      return child.exitValue();
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      }
    }
  }

  private static String tail(Path log) {
    try {
      var lines = Files.readAllLines(log);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    } catch (Exception failure) { return failure.toString(); }
  }

  /** Runs one real Engine in its own JVM and halts it at the selected durability boundary. */
  static final class CrashChild {
    public static void main(String[] args) {
      try {
        var test = new RuntimeReloadProducersHttpIntegrationTest();
        test.directory = Path.of(args[1]);
        boolean persisted = args[0].startsWith("persisted.");
        var f = test.new Fixture(persisted);
        if ("failed-handoff".equals(args[0])) {
          var files = f.watchedFiles();
          var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
              field(((DefaultWorkerAppServices) f.server.appServices()).mutationAdmission(), "lock");
          var refused = f.reloadWithSettledDeleteWaiting(files.gone(), () -> {
            throw new IllegalStateException("injected failed opener before process death");
          });
          assertEquals(500, refused.statusCode(), refused.body());
          // The released callback has finished once it neither waits for nor holds admission.
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while ((gate.hasQueuedThreads() || gate.getReadLockCount() > 0)
              && System.nanoTime() < until) Thread.sleep(5);
          assertFalse(gate.hasQueuedThreads() || gate.getReadLockCount() > 0);
          Runtime.getRuntime().halt(71);
        }
        // A successful native reload with a settled DELETE waiting on its fence, parked at the exit
        // of the named DefaultWorkerAppServices lifecycle method, where this process then dies.
        var files = persisted ? f.persistedFiles() : f.watchedFiles();
        String cut = args[0].substring(args[0].indexOf('.') + 1);
        new net.bytebuddy.agent.builder.AgentBuilder.Default()
            .with(net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(net.bytebuddy.matcher.ElementMatchers.named(DefaultWorkerAppServices.class.getName()))
            .transform((builder, type, loader, module, domain) -> builder.visit(
                net.bytebuddy.asm.Advice.to(ParkAtExit.class).on(
                    net.bytebuddy.matcher.ElementMatchers.named(cut)
                        .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(0)))))
            .installOn(net.bytebuddy.agent.ByteBuddyAgent.install());
        var reload = CompletableFuture.supplyAsync(() -> {
          try {
            return f.reloadWithSettledDeleteWaiting(files.gone(), () -> {
              System.setProperty(ParkAtExit.STATE, "armed");
              return f.reopen();
            });
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"parked".equals(System.getProperty(ParkAtExit.STATE)) && !reload.isDone()
            && System.nanoTime() < until) Thread.sleep(1);
        if ("parked".equals(System.getProperty(ParkAtExit.STATE))) Runtime.getRuntime().halt(71);
        System.out.println("crash point not reached: reload done=" + reload.isDone());
        Runtime.getRuntime().halt(70);
      } catch (Throwable failure) {
        failure.printStackTrace(System.out);
        Runtime.getRuntime().halt(72); // Engine threads must not keep a failed child alive.
      }
    }
  }

  /** Inlined at a lifecycle method's exit once armed: parks that thread so the process dies there. */
  public static final class ParkAtExit {
    static final String STATE = "justsearch.test.reloadCrashCut";

    private ParkAtExit() {}

    @net.bytebuddy.asm.Advice.OnMethodExit
    public static void exit() {
      if ("armed".equals(System.getProperty(STATE))) {
        System.setProperty(STATE, "parked");
        while ("parked".equals(System.getProperty(STATE))) {
          java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
      }
    }
  }

  private record WatchedFiles(Path root, Path gone, Path kept) {}

  final class Fixture implements AutoCloseable {
    final AutoCloseable volumeEvidence;
    final ConfigStore previous = ConfigStore.globalOrNull();
    final String previousHome = System.getProperty("user.home");
    final String previousDataDir = System.getProperty(EnvRegistry.DATA_DIR.configKey());
    final EngineRoot root;
    final KnowledgeServerBootstrap bootstrap;
    final KnowledgeServer server;
    final KnowledgeClient client;
    final Javalin http;
    final HttpClient transport = HttpClient.newHttpClient();
    final List<Thread> migrationThreads;
    final java.util.concurrent.atomic.AtomicBoolean liveStartObserved = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.FutureTask<Void> clientClose;
    Thread clientCloser;
    java.util.concurrent.FutureTask<Void> httpClose;
    Thread httpCloser;
    java.util.concurrent.FutureTask<Void> transportClose;
    Thread transportCloser;
    boolean closed;
    final ch.qos.logback.classic.Logger migrationLogger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KnowledgeServer.class);
    final ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> liveStarts =
        new ch.qos.logback.core.AppenderBase<>() {
          @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            if (event.getMessage().startsWith("Live Green start dispatched:")) liveStartObserved.set(true);
          }
        };

    Fixture() throws Exception { this(false); }

    /**
     * {@code production}: root membership persists in the data folder and the bootstrap's automatic
     * root producers run, so a restart restores and re-watches persisted roots by itself.
     */
    Fixture(boolean production) throws Exception { this(production, null); }

    Fixture(boolean production,
        io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook migrationHook) throws Exception {
      this(production, migrationHook, 60_000);
    }

    Fixture(boolean production,
        io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook migrationHook,
        long telemetryFlushMs) throws Exception {
      volumeEvidence = knownVolume();
      System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
      System.setProperty(EnvRegistry.DATA_DIR.configKey(), directory.resolve("data").toString());
      var builder = ResolvedConfig.builder();
      builder.putDefault(EnvRegistry.TELEMETRY_FLUSH_MS.configKey(), Long.toString(telemetryFlushMs));
      builder.putDefault(EnvRegistry.DATA_DIR.configKey(), directory.resolve("data").toString());
      builder.putDefault(EnvRegistry.INDEX_BASE_PATH.configKey(), directory.resolve("index").toString());
      builder.putDefault("justsearch.ssot.path", directory.resolve("SSOT").toString());
      builder.putDefault(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
      builder.putDefault(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
      for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED}) builder.putDefault(key.configKey(), "false");
      if (migrationHook != null) builder.putDefault("index.schema_mismatch.policy", "blue_green_migrate");
      var configuration = builder.build();
      ConfigStore.setGlobal(new ConfigStore(configuration));
      var built = new java.util.concurrent.atomic.AtomicReference<KnowledgeServer>();
      root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
          (gauge, executors, ingestion, index, encoders) -> {
            var worker = WorkerConfig.load(configuration);
            var result = new KnowledgeServer(executors, worker, new InProcessWorkerSignalBus(gauge),
                io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, index,
                encoders, configuration);
            {
              try {
                var install = KnowledgeServer.class.getDeclaredMethod("installMigrationBarrierForTests",
                    io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook.class);
                install.setAccessible(true);
                io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook observed = transition -> {
                  if (transition.point().equals("migration-before-live-green-open")) liveStartObserved.set(true);
                  if (migrationHook != null) migrationHook.await(transition);
                };
                install.invoke(result, observed);
              } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot install migration observation barrier", failure);
              }
            }
            built.set(result);
            return result;
          }, 5_000, 100, ignored -> {}, () -> {}, production
              ? OperationAuthority.load(directory.resolve("data")) : OperationAuthority.inMemory());
      var config = new KnowledgeServerConfig(false, directory.resolve("data"), directory, directory,
          5_000L, 5_000L, 1, 5_000L, 1_000L, 300_000L, 100, 5_000L, 0);
      bootstrap = new KnowledgeServerBootstrap(root.executors(), config, null, root.components(),
          root.indexComponent(), root, production, root.publicationLock());
      bootstrap.startInitial(ignored -> {});
      server = built.get();
      assertTrue(server.awaitIndexRecoveryModels(10_000));
      synchronized (server) {
        migrationThreads = java.util.stream.Stream.of((Thread) inherited(server, "migrationEnumeratorThread"),
            (Thread) inherited(server, "migrationCutoverThread")).filter(java.util.Objects::nonNull).toList();
      }
      assertFalse(liveStartObserved.get(), "fixture boot must not dispatch a live migration starter");
      client = bootstrap.client();
      assertTrue(bootstrap.checkHealth());
      // Head publishes readiness after attaching a physically verified bootstrap to its API.
      // This fixture mounts that API's real admin handler without the rest of HeadAssembly.
      root.indexComponent().transition(ComponentState.READY, null, null);
      assertEquals(ComponentState.READY, root.indexComponent().snapshot().state());
      var head = mock(HeadAssembly.class);
      when(head.workers()).thenReturn(new WorkerServices(client, null, null, null));
      var api = mock(LocalApiServer.class, CALLS_REAL_METHODS);
      set(api, "HeadAssemblyRef", head);
      var handler = LocalApiServer.class.getDeclaredMethod("handleAdminRuntimeReload", Context.class);
      handler.setAccessible(true);
      http = Javalin.create();
      DebugRoutes.register(http, mock(DebugStateController.class), mock(EffectiveConfigController.class),
          mock(ChunkInfoController.class), ctx -> {}, mock(LogLevelController.class),
          mock(TimeSeriesController.class), mock(SessionPoliciesController.class), ctx -> {},
          ctx -> handler.invoke(api, ctx));
      http.start("127.0.0.1", 0);
      // This existing log is emitted before the untracked live-start thread is dispatched.
      liveStarts.setContext(migrationLogger.getLoggerContext());
      liveStarts.start();
      migrationLogger.addAppender(liveStarts);
    }

    io.justsearch.indexerworker.queue.SwitchBufferCapableQueue queue() throws Exception {
      return (io.justsearch.indexerworker.queue.SwitchBufferCapableQueue) field(server, "jobQueue");
    }

    List<io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp> receipts()
        throws Exception {
      return queue().listSwitchBufferOpsStrict().stream()
          .filter(op -> io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION
              .equals(op.op()))
          .toList();
    }

    void awaitReceipts(int expected, String message) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (receipts().size() != expected && System.nanoTime() < until) Thread.sleep(25);
      assertEquals(expected, receipts().size(), message);
    }

    long count(String marker) { return client.search(marker, 10, CONTEXT).getResultsCount(); }

    void awaitCount(String marker, long expected) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (count(marker) != expected && System.nanoTime() < until) Thread.sleep(50);
      assertEquals(expected, count(marker), marker);
    }

    void watch(Path root) {
      var registration = server.appServices().ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(root.toString())
              .setRootAdded(true).setCollection("default").build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(registration.getWatching(), registration.getErrorMessage());
    }

    private WatchedFiles files(boolean watched) throws Exception {
      Path root = Files.createDirectories(directory.resolve("watched"));
      var files = new WatchedFiles(root,
          Files.writeString(root.resolve("gone.txt"), "reloadobligationgone distinct content"),
          Files.writeString(root.resolve("kept.txt"), "reloadobligationkept distinct content"));
      if (watched) watch(root);
      assertEquals(2, client.submitBatch(List.of(files.gone(), files.kept()), CONTEXT)
          .getAcceptedCount());
      awaitCount("reloadobligationgone", 1);
      awaitCount("reloadobligationkept", 1);
      return files;
    }

    WatchedFiles watchedFiles() throws Exception { return files(true); }

    /** Both files indexed under a root the person added, so its membership persists. */
    WatchedFiles persistedFiles() throws Exception {
      Path root = Files.createDirectories(directory.resolve("watched"));
      var files = new WatchedFiles(root,
          Files.writeString(root.resolve("gone.txt"), "reloadobligationgone distinct content"),
          Files.writeString(root.resolve("kept.txt"), "reloadobligationkept distinct content"));
      client.addWatchedRoot("default", root, CONTEXT);
      awaitCount("reloadobligationgone", 1);
      awaitCount("reloadobligationkept", 1);
      return files;
    }

    /** A durable receipt as a failed or interrupted earlier reload leaves it for a watched root. */
    WatchedFiles unwatchedFilesWithReceipt() throws Exception {
      var files = files(false);
      Files.delete(files.gone());
      watch(files.root());
      var registry = inherited(server.appServices().ingestService(), "rootWatcherRegistry");
      var lookup = registry.getClass().getDeclaredMethod("subscription", Path.class);
      lookup.setAccessible(true);
      var subscription = lookup.invoke(registry, files.root());
      var identity = subscription.getClass().getDeclaredMethod("rootIdentity");
      identity.setAccessible(true);
      var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(
          files.root().toAbsolutePath().normalize().toString(),
          identity.invoke(subscription).toString(), "default");
      assertTrue(queue().putSwitchBuffer(debt.key(),
          io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION, debt.encode()));
      assertEquals(1, count("reloadobligationgone"), "the receipt's delete is not yet applied");
      return files;
    }

    String recordedVolume(Path ledger, Path root) throws Exception {
      var entries = new java.util.Properties();
      try (var in = Files.newInputStream(ledger)) { entries.load(in); }
      String volume = entries.getProperty(root.toString());
      assertNotNull(volume, "precondition: the person's add recorded the root's volume");
      return volume;
    }

    /** Rewrites the root's recorded volume, as if another volume (or its own) were at its path. */
    void recordVolume(Path ledger, Path root, String volume) throws Exception {
      var entries = new java.util.Properties();
      try (var in = Files.newInputStream(ledger)) { entries.load(in); }
      entries.setProperty(root.toString(), volume);
      try (var out = Files.newOutputStream(ledger)) { entries.store(out, null); }
    }

    void useReload(java.util.function.Supplier<io.justsearch.adapters.lucene.runtime.RunningRuntime> opener) {
      server.appServices().ingestService().setRuntimeReloadTrigger(
          reason -> server.swapRuntime(opener, Duration.ofSeconds(30), reason));
    }

    void useNormalReload() { useReload(this::reopen); }

    io.justsearch.adapters.lucene.runtime.RunningRuntime reopen() {
      try {
        var build = KnowledgeServer.class.getDeclaredMethod("buildIndexRuntime", Path.class,
            java.util.function.Supplier.class);
        build.setAccessible(true);
        java.util.function.Supplier<Optional<?>> none = Optional::empty;
        return ((io.justsearch.adapters.lucene.runtime.LuceneRuntimeBuilder)
            build.invoke(server, field(server, "activeIndexPath"), none)).open();
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    /** Deletes the file, lets its DELETE settle behind the native fence, then runs the opener. */
    HttpResponse<String> reloadWithSettledDeleteWaiting(Path file,
        java.util.function.Supplier<io.justsearch.adapters.lucene.runtime.RunningRuntime> opener)
        throws Exception {
      var services = server.appServices();
      var pending = (java.util.Map<?, ?>) inherited(inherited(services, "workerWatcher"),
          "pendingDeletes");
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(((DefaultWorkerAppServices) services).mutationAdmission(), "lock");
      Files.delete(file);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!pending.containsKey(file) && System.nanoTime() < until) Thread.sleep(5);
      assertTrue(pending.containsKey(file), "the watcher must hold the DELETE before the reload");
      var waiting = new java.util.concurrent.atomic.AtomicBoolean();
      useReload(() -> {
        try {
          long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (!(pending.isEmpty() && gate.hasQueuedThreads()) && System.nanoTime() < settle) {
            Thread.sleep(10);
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        waiting.set(pending.isEmpty() && gate.hasQueuedThreads());
        return opener.get();
      });
      var response = post();
      assertTrue(waiting.get(), "the settled DELETE must wait behind the reload's mutation fence");
      return response;
    }

    DefaultWorkerAppServices spyProducer() throws Exception {
      var producer = spy((DefaultWorkerAppServices) server.appServices());
      var retire = KnowledgeServer.class.getDeclaredMethod("retireServingView");
      retire.setAccessible(true);
      retire.invoke(server);
      var publish = KnowledgeServer.class.getDeclaredMethod("publishServingView",
          io.justsearch.indexerworker.server.WorkerAppServices.class);
      publish.setAccessible(true);
      publish.invoke(server, producer);
      return producer;
    }

    HttpResponse<String> post() {
      try {
        return transport.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + http.port() + "/api/admin/runtime/reload"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    void awaitSearchable(String marker) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (client.search(marker, 10, CONTEXT).getResultsCount() == 0) {
        assertTrue(System.nanoTime() < until, "watched-root ingestion did not deliver " + marker);
        Thread.sleep(50);
      }
    }

    @Override public void close() throws Exception { close(Duration.ofSeconds(5)); }

    synchronized void close(Duration allowance) throws Exception {
      if (closed) return;
      long deadline = System.nanoTime() + Math.max(0, allowance.toNanos());
      var failures = new java.util.ArrayList<Throwable>();
      boolean supported = !liveStartObserved.get()
          && mockingDetails(root.operationAttempts()).getInvocations().stream()
              .noneMatch(invocation -> invocation.getMethod().getName().equals("start"));
      if (!supported) failures.add(new AssertionError("Fixture cannot own live/recorded migration starters"));
      try {
        root.admission().beginClosing();
        root.admission().cancelInteractive("fixture closing");
      } catch (Exception | AssertionError failure) { failures.add(failure); }
      // Signal both boot-owned groups even if the independent recorded drain fails.
      for (Thread producer : migrationThreads) producer.interrupt();
      try { root.quiesceProducers(Duration.ZERO); }
      catch (Exception | AssertionError failure) { failures.add(failure); }
      if (clientClose == null) {
        clientClose = new java.util.concurrent.FutureTask<>(() -> { client.close(); return null; });
        clientCloser = new Thread(clientClose, "fixture-client-close");
        clientCloser.setDaemon(true);
        clientCloser.start();
      }
      // Start each independent cancellation before waiting for any physical group.
      if (httpClose == null) {
        httpClose = new java.util.concurrent.FutureTask<>(() -> { http.stop(); return null; });
        httpCloser = new Thread(httpClose, "fixture-http-stop");
        httpCloser.setDaemon(true);
        httpCloser.start();
      }
      if (transportClose == null) {
        transportClose = new java.util.concurrent.FutureTask<>(() -> { transport.close(); return null; });
        transportCloser = new Thread(transportClose, "fixture-transport-close");
        transportCloser.setDaemon(true);
        transportCloser.start();
      }
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) clientClose.get(remaining, TimeUnit.NANOSECONDS);
        else if (clientClose.isDone()) clientClose.get();
        for (Thread producer : migrationThreads) {
          remaining = deadline - System.nanoTime();
          if (producer.isAlive() && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(producer, remaining);
        }
        remaining = deadline - System.nanoTime();
        if (clientCloser.isAlive() && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(clientCloser, remaining);
        while (clientExecutorsLive() && (remaining = deadline - System.nanoTime()) > 0) {
          TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)));
        }
        remaining = deadline - System.nanoTime();
        root.admission().awaitDrained(Duration.ofNanos(Math.max(0, remaining)));
      } catch (Exception | AssertionError failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        failures.add(failure);
      }
      for (var action : List.of(httpClose, transportClose)) {
        try {
          long remaining = deadline - System.nanoTime();
          if (remaining > 0) action.get(remaining, TimeUnit.NANOSECONDS);
          else if (action.isDone()) action.get();
        } catch (Exception | AssertionError failure) {
          if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
          failures.add(failure);
        }
      }
      for (Thread closer : List.of(httpCloser, transportCloser)) {
        long remaining = deadline - System.nanoTime();
        try { if (closer.isAlive() && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(closer, remaining); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); failures.add(failure); }
      }
      boolean drained = clientClose.state() == java.util.concurrent.Future.State.SUCCESS && !clientCloser.isAlive()
          && httpClose.state() == java.util.concurrent.Future.State.SUCCESS && !httpCloser.isAlive()
          && transportClose.state() == java.util.concurrent.Future.State.SUCCESS && !transportCloser.isAlive()
          && migrationThreads.stream().noneMatch(Thread::isAlive) && !clientExecutorsLive()
          && root.admission().activeWorkCount() == 0;
      if (!drained) failures.add(new IllegalStateException("Fixture physical drain incomplete: admissions="
          + root.admission().activeWorkCount() + ", migrations="
          + migrationThreads.stream().filter(Thread::isAlive).map(Thread::getName).toList()
          + ", clientExecutors=" + root.executors().snapshot()));
      if (supported && drained && failures.isEmpty()) {
        try {
          bootstrap.close();
          root.close();
          root.processResources().close();
          migrationLogger.detachAppender(liveStarts);
          liveStarts.stop();
          closed = true;
          if (previousDataDir == null) System.clearProperty(EnvRegistry.DATA_DIR.configKey());
          else System.setProperty(EnvRegistry.DATA_DIR.configKey(), previousDataDir);
          if (previousHome == null) System.clearProperty("user.home");
          else System.setProperty("user.home", previousHome);
          if (previous != null) ConfigStore.setGlobal(previous);
          else {
            var clear = ConfigStore.class.getDeclaredMethod("clearGlobal");
            clear.setAccessible(true);
            clear.invoke(null);
          }
        } catch (Exception | AssertionError failure) { failures.add(failure); }
      }
      try { volumeEvidence.close(); }
      catch (Exception failure) { failures.add(failure); }
      if (!failures.isEmpty()) {
        var incomplete = new Exception("Fixture cleanup incomplete; owners and backing directory retained");
        failures.forEach(incomplete::addSuppressed);
        throw incomplete;
      }
    }

    private boolean clientExecutorsLive() {
      return root.executors().snapshot().registrations().stream()
          .anyMatch(registration -> (registration.spec().name().startsWith("engine-knowledge-")
              || registration.spec().name().startsWith("knowledge-client-")) && registration.liveInstances() != 0);
    }

  }

  private static AutoCloseable knownVolume() throws Exception {
    var lookup = Class.forName("io.justsearch.indexerworker.services.RootIdentity")
        .getDeclaredField("VOLUME_IDENTITY_LOOKUP");
    lookup.setAccessible(true);
    Object previous = lookup.get(null);
    String volume = System.getProperty("os.name", "").startsWith("Windows")
        ? "windows-volume:739" : "linux-volume-v1:739:ext4:Lw";
    lookup.set(null, (java.util.function.Function<Path, String>) ignored -> volume);
    return () -> lookup.set(null, previous);
  }

  private static void assertReloadSuccess(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(new tools.jackson.databind.ObjectMapper().readTree(response.body())
        .path("swapDurationMs").isNumber(), response.body());
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  /** Reads a declared field through Mockito spy subclasses as well. */
  private static Object inherited(Object target, String name) throws Exception {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException next) { /* look in the superclass */ }
    }
    throw new NoSuchFieldException(name);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
