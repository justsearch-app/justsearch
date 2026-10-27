/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.services.bootstrap.phases.BootstrapDocumentService;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.ApiErrorHandler;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.RetrieveContextController;
import io.justsearch.ui.api.routes.KnowledgeRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Searches and retrievals that arrive while an admin runtime reload has no serving view, or after
 * a failed reload has left no verified client, get a retryable 503, never a 500. Real Engine root, bootstrap and Knowledge Server through
 * {@link RuntimeReloadProducersHttpIntegrationTest}'s fixture, with the production knowledge
 * routes mounted beside its admin reload handler. Models stubbed.
 */
@Timeout(120)
final class RuntimeReloadServingUnavailableHttpIntegrationTest {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MARKER = "reloadservingmarker";
  private static final String MESSAGE = "Index serving view is unavailable";
  private static final String CLIENT_MESSAGE = "Knowledge Server client is unavailable";
  private static final EngineContext CONTEXT = EngineProvenance.internal(
      "reload-serving-check", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @Test
  void drainAndOpeningPhasesAnswerRetryableUnavailableAndResultsMatchAcrossTheReload()
      throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      var before = resultIds(api.search(MARKER, null));
      var beforeFiltered = resultIds(api.search(MARKER, directory.toString()));
      assertFalse(before.isEmpty(), "precondition: the distinctive document is searchable");
      assertEquals(before, beforeFiltered, "precondition: the path filter keeps the document");

      var opening = new CountDownLatch(1);
      var finishOpening = new CountDownLatch(1);
      f.useReload(() -> {
        opening.countDown();
        try {
          assertTrue(finishOpening.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        return f.reopen();
      });

      // A search admitted before retirement holds its captured view across the whole reload start.
      var admitted = api.search.getAdapter().openSearch(new KnowledgeSearchRequest(MARKER, 10,
          null, null, null, List.of(), null, null, null, null, false, false, null), CONTEXT);
      CompletableFuture<HttpResponse<String>> reload;
      try {
        reload = CompletableFuture.supplyAsync(f::post);
        awaitRefusing(f, reload);
        assertEquals(1, clientHolders(f), "the admitted search still holds its client lease");
        assertWindowUnavailable(f, api, document, "retirement drain");
        assertEquals(1, clientHolders(f), "refused captures leave the client holder count unchanged");
        // The admitted search completes later stages from its captured services.
        assertEquals(before, admitted.response().results().stream()
            .map(io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit::id).toList());
        assertTrue(admitted.statusFacts(CONTEXT).docCount() >= 1,
            "status capture runs on the retained view while the reload waits for it");
        assertFalse(reload.isDone(), "retirement waits for the admitted search");
      } finally {
        admitted.close();
      }

      assertTrue(opening.await(10, TimeUnit.SECONDS), "the reload reaches runtime opening");
      assertWindowUnavailable(f, api, document, "runtime opening");
      assertEquals(0, clientHolders(f));
      finishOpening.countDown();

      assertReloadSuccess(reload.get(30, TimeUnit.SECONDS));
      assertEquals(before, resultIds(api.search(MARKER, null)),
          "the same search answers identically right after the reload");
      assertEquals(beforeFiltered, resultIds(api.search(MARKER, directory.toString())),
          "the filtered search answers identically right after the reload");
    }
  }

  @Test
  void reconstructionPhaseAnswersRetryableUnavailableAndTheReloadThenServes() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      var before = resultIds(api.search(MARKER, null));
      // The spied producer is published only to pause reconstruction; nothing searches on it.
      var old = f.spyProducer();
      var reconstructing = new CountDownLatch(1);
      var finishReconstruction = new CountDownLatch(1);
      doAnswer(invocation -> {
        reconstructing.countDown();
        assertTrue(finishReconstruction.await(30, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      }).when(old).installNativeWatcherHandoffTo(any());
      f.useNormalReload();
      var reload = CompletableFuture.supplyAsync(f::post);
      try {
        assertTrue(reconstructing.await(10, TimeUnit.SECONDS), "the reload reaches reconstruction");
        assertWindowUnavailable(f, api, document, "service reconstruction");
        assertEquals(0, clientHolders(f));
      } finally {
        finishReconstruction.countDown();
      }
      assertReloadSuccess(reload.get(30, TimeUnit.SECONDS));
      assertEquals(before, resultIds(api.search(MARKER, null)),
          "the same search answers identically right after the reload");
    }
  }

  @Test
  void consecutiveAndConcurrentReloadsUnderSearchLoopsNeverAnswer500() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      indexDistinctiveDocument(f, api);
      var responses = new AtomicInteger();
      // Each opening waits until the loops have sent searches into the window, or briefly if done.
      f.useReload(() -> {
        int seen = responses.get();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (responses.get() < seen + 2 && System.nanoTime() < until) Thread.onSpinWait();
        return f.reopen();
      });
      var statuses = new ConcurrentLinkedQueue<String>();
      var start = new CountDownLatch(1);
      ExecutorService loops = Executors.newFixedThreadPool(2);
      try {
        var searching = new ArrayList<java.util.concurrent.Future<?>>();
        for (int loop = 0; loop < 2; loop++) {
          searching.add(loops.submit(() -> {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 40; i++) {
              var response = api.search(MARKER, null);
              statuses.add(response.statusCode() + " " + response.body());
              if (response.statusCode() == 503) assertUnavailable(response);
              else assertEquals(200, response.statusCode(), response.body());
              responses.incrementAndGet();
              Thread.sleep(10);
            }
            return null;
          }));
        }
        start.countDown();
        assertReloadSuccess(f.post());
        assertReloadSuccess(f.post());
        var first = CompletableFuture.supplyAsync(f::post);
        var second = CompletableFuture.supplyAsync(f::post);
        assertReloadSuccess(first.get(30, TimeUnit.SECONDS));
        assertReloadSuccess(second.get(30, TimeUnit.SECONDS));
        for (var running : searching) running.get(60, TimeUnit.SECONDS);
      } finally {
        loops.shutdownNow();
        assertTrue(loops.awaitTermination(10, TimeUnit.SECONDS));
      }
      assertEquals(80, statuses.size());
      assertTrue(statuses.stream().noneMatch(status -> status.startsWith("500")), statuses.toString());
      assertTrue(statuses.stream().anyMatch(status -> status.startsWith("503")),
          "the loops must have reached a reload window: " + statuses);
      var recovered = api.search(MARKER, null);
      assertEquals(200, recovered.statusCode(), recovered.body());
      assertFalse(resultIds(recovered).isEmpty(), "searches recover after the final publication");
    }
  }

  @Test
  void failedOpeningLeavesUnavailableWithoutStaleSuccessAndRetryRecovers() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      f.useReload(() -> { throw new IllegalStateException("injected failed opener"); });
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      assertFalse(f.server.runtimeReloadInProgress(), "the failed reload has finished");
      assertFinishedFailureUnavailable(f, api, document);

      f.useNormalReload();
      assertReloadSuccess(f.post());
      var recovered = api.search(MARKER, null);
      assertEquals(200, recovered.statusCode(), recovered.body());
      assertFalse(resultIds(recovered).isEmpty());
    }
  }

  @Test
  void failedReconstructionLeavesUnavailableWithoutStaleSuccessAndRetryRecovers() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      var old = f.spyProducer();
      doThrow(new java.io.IOException("injected reconstruction failure after watcher handoff"))
          .doCallRealMethod().when(old).close();
      f.useNormalReload();
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      assertFalse(f.server.runtimeReloadInProgress(), "the failed reload has finished");
      assertFinishedFailureUnavailable(f, api, document);

      assertReloadSuccess(f.post());
      var recovered = api.search(MARKER, null);
      assertEquals(200, recovered.statusCode(), recovered.body());
      assertFalse(resultIds(recovered).isEmpty());
    }
  }

  @Test
  void refusedHolderDrainRestoresTheIncumbentAndALaterReloadSucceeds() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      indexDistinctiveDocument(f, api);
      f.useNormalReload();
      HttpResponse<String> refused;
      try (var held = api.search.getAdapter().openSearch(new KnowledgeSearchRequest(MARKER, 10,
          null, null, null, List.of(), null, null, null, null, false, false, null), CONTEXT)) {
        assertNotNull(held.response());
        refused = f.post();
      }
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("active serving holders"), refused.body());
      var served = api.search(MARKER, null);
      assertEquals(200, served.statusCode(), served.body());
      assertFalse(resultIds(served).isEmpty(), "the incumbent view serves new searches");
      assertReloadSuccess(f.post());
      var after = api.search(MARKER, null);
      assertEquals(200, after.statusCode(), after.body());
      assertFalse(resultIds(after).isEmpty());
    }
  }

  @Test
  void callsDuringTheWindowCompletePromptlyAndReleaseTheirOwnership() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      var opening = new CountDownLatch(1);
      var finishOpening = new CountDownLatch(1);
      f.useReload(() -> {
        opening.countDown();
        try {
          assertTrue(finishOpening.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        return f.reopen();
      });
      var reload = CompletableFuture.supplyAsync(f::post);
      try {
        assertTrue(opening.await(10, TimeUnit.SECONDS));
        int baseline = f.root.admission().activeWorkCount();
        long started = System.nanoTime();
        // A caller with a 2 s deadline is answered before it, without waiting for the reload.
        var response = api.send("/api/knowledge/search", "{\"query\":\"" + MARKER + "\"}",
            Duration.ofSeconds(2));
        assertUnavailable(response);
        // A cancelled caller's request is abandoned promptly and leaves nothing owned behind.
        var abandoned = api.transport.sendAsync(api.request("/api/knowledge/retrieve-context",
            "{\"query\":\"" + MARKER + "\",\"doc_ids\":[\"" + json(document) + "\"]}",
            Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString());
        abandoned.cancel(true);
        // The refusal may already have arrived; either way the caller is not left waiting.
        try {
          assertUnavailable(abandoned.get(2, TimeUnit.SECONDS));
        } catch (java.util.concurrent.CancellationException cancelled) {
          // cancelled before the refusal arrived
        } catch (java.util.concurrent.ExecutionException failed) {
          assertInstanceOf(java.util.concurrent.CancellationException.class, failed.getCause());
        }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
            "calls during the window must not wait for the gated reload");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (f.root.admission().activeWorkCount() != baseline && System.nanoTime() < until) {
          Thread.sleep(10);
        }
        assertEquals(baseline, f.root.admission().activeWorkCount(), "admitted work is released");
        assertEquals(0, clientHolders(f), "no client lease is left behind");
        assertFalse(reload.isDone(), "the reload stayed gated throughout");
      } finally {
        finishOpening.countDown();
      }
      assertReloadSuccess(reload.get(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void failedOpeningThenAHealthPollStillAnswersRetryableUnavailableAndRecovers() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      Path document = indexDistinctiveDocument(f, api);
      f.useReload(() -> { throw new IllegalStateException("injected failed opener"); });
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      assertFalse(f.server.runtimeReloadInProgress(), "the failed reload has finished");

      // The health monitor's poll finds no view and no reload, and records the loss.
      try {
        f.bootstrap.tryCheckHealth();
      } catch (IllegalStateException recorded) {
        // the poll rethrows the capture failure it recorded
      }
      assertFalse(clientVerified(f), "precondition: the poll leaves the client unverified");
      for (int attempt = 0; attempt < 3; attempt++) {
        assertUnavailable(api.search(MARKER, null), CLIENT_MESSAGE, "search after the health poll");
      }
      assertUnavailable(api.search(MARKER, directory.toString()), CLIENT_MESSAGE,
          "filtered search after the health poll");
      assertUnavailable(api.send("/api/knowledge/retrieve-context",
          "{\"query\":\"" + MARKER + "\",\"doc_ids\":[\"" + json(document) + "\"]}"),
          CLIENT_MESSAGE, "retrieve-context after the health poll");
      // Suggest checks the worker capability first; the recorded loss keeps its readiness 503.
      var suggest = api.send("/api/knowledge/suggest?query=" + MARKER.substring(0, 6), null);
      assertEquals(503, suggest.statusCode(), suggest.body());
      assertEquals("SERVICE_UNAVAILABLE", JSON.readTree(suggest.body()).path("errorCode").asText());
      assertEquals("Knowledge Server not ready", JSON.readTree(suggest.body()).path("error").asText());
      assertUnavailable(api.send("/api/knowledge/collection-facet", "{\"maxDocsScanned\":10}"),
          CLIENT_MESSAGE, "collection facets after the health poll");
      assertEquals(0, clientHolders(f));

      f.useNormalReload();
      assertReloadSuccess(f.post());
      assertEquals(java.util.Optional.of(true), f.bootstrap.tryCheckHealth(),
          "the next health poll verifies the republished client");
      var recovered = api.search(MARKER, null);
      assertEquals(200, recovered.statusCode(), recovered.body());
      assertFalse(resultIds(recovered).isEmpty());
    }
  }

  @Test
  void anHttpSearchHeldBetweenStagesAcrossRetirementCompletesFromItsCapturedView()
      throws Exception {
    var holding = new java.util.concurrent.atomic.AtomicBoolean();
    var reranking = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    // Holds one search between retrieval and its later stages, still on its captured view.
    var reranker = new io.justsearch.app.api.gpl.RerankerService() {
      @Override public boolean isLoaded() { return true; }
      @Override public List<Integer> rerank(float[] sparse, float[] vectors, float[] splade, int n) {
        if (holding.compareAndSet(true, false)) {
          reranking.countDown();
          try {
            assertTrue(release.await(20, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        }
        return java.util.stream.IntStream.range(0, n).boxed().toList();
      }
      @Override public io.justsearch.app.api.gpl.LambdaMartTrainingStatus getTrainingStatus() {
        return null;
      }
    };
    try (var f = fixture(); var api = new Api(f, reranker)) {
      indexDistinctiveDocument(f, api);
      var before = resultIds(api.search(MARKER, null));
      holding.set(true);
      var held = CompletableFuture.supplyAsync(() -> api.search(MARKER, null));
      CompletableFuture<HttpResponse<String>> reload;
      try {
        assertTrue(reranking.await(10, TimeUnit.SECONDS), "the search reaches its reranking stage");
        assertEquals(1, clientHolders(f), "the held search retains its client lease");
        f.useNormalReload();
        reload = CompletableFuture.supplyAsync(f::post);
        awaitRefusing(f, reload);
        assertFenced(api.search(MARKER, null), "a new search while the held one drains");
        assertFalse(held.isDone(), "the held search is still between its stages");
        assertFalse(reload.isDone(), "retirement waits for the held search");
      } finally {
        release.countDown();
      }
      var completed = held.get(20, TimeUnit.SECONDS);
      assertEquals(before, resultIds(completed),
          "the held search finishes its later stages from its captured view");
      assertReloadSuccess(reload.get(30, TimeUnit.SECONDS));
      assertEquals(0, clientHolders(f));
      assertEquals(before, resultIds(api.search(MARKER, null)));
    }
  }

  @Test
  void refusedProducerMutationDrainRestoresTheIncumbentAndALaterReloadSucceeds() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      indexDistinctiveDocument(f, api);
      f.useNormalReload();
      var old = (io.justsearch.indexerworker.server.DefaultWorkerAppServices) f.server.appServices();
      HttpResponse<String> refused;
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        refused = f.post();
      }
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("active producer mutations"), refused.body());
      assertIncumbentServesThenReloadSucceeds(f, api);
    }
  }

  @Test
  void interruptedDrainsRestoreTheIncumbentAndALaterReloadSucceeds() throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      indexDistinctiveDocument(f, api);
      // Interrupted while draining a search's serving holder.
      try (var held = api.search.getAdapter().openSearch(new KnowledgeSearchRequest(MARKER, 10,
          null, null, null, List.of(), null, null, null, null, false, false, null), CONTEXT)) {
        assertNotNull(held.response());
        var reload = new Reloader(f);
        awaitRefusing(f, reload.outcome);
        assertFenced(api.search(MARKER, null), "search while the holder drain waits");
        var failure = reload.interrupt();
        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("active serving holders"), failure.toString());
      }
      assertIncumbentServes(api);

      // Interrupted while draining an admitted producer mutation.
      var old = (io.justsearch.indexerworker.server.DefaultWorkerAppServices) f.server.appServices();
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(old.mutationAdmission(), "lock");
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        var reload = new Reloader(f);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!gate.hasQueuedThreads() && !reload.outcome.isDone() && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertTrue(gate.hasQueuedThreads(), "the reload waits for the admitted mutation");
        assertFenced(api.search(MARKER, null), "search while the mutation drain waits");
        var failure = reload.interrupt();
        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("active producer mutations"), failure.toString());
      }
      assertIncumbentServesThenReloadSucceeds(f, api);
    }
  }

  @Test
  void callerDeadlineAndCancellationDuringTheWindowCompletePromptlyAndReleaseOwnership()
      throws Exception {
    try (var f = fixture(); var api = new Api(f)) {
      indexDistinctiveDocument(f, api);
      int baseline = f.root.admission().activeWorkCount();
      // Worker searches admitted before retirement stay issued until their caller gives up.
      var old = f.spyProducer();
      var search = spy(old.searchService());
      var issued = new CountDownLatch(2);
      doAnswer(invocation -> {
        io.justsearch.indexerworker.services.CallContext call = invocation.getArgument(1);
        issued.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!call.cancelled() && System.nanoTime() < until) Thread.sleep(5);
        return invocation.callRealMethod();
      }).when(search).search(any(), any());
      doReturn(search).when(old).searchService();

      var cancellable = f.root.admission().admitRequest(CONTEXT, false, false);
      ExecutorService callers = Executors.newFixedThreadPool(2);
      try {
        long deadlineAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
        var deadline = callers.submit(() -> io.justsearch.app.services.worker.HeadSearchDeadline
            .within(deadlineAt, () -> f.client.search(MARKER, 10, CONTEXT)));
        var cancelled = callers.submit(() -> f.client.search(MARKER, 10, cancellable.context()));
        assertTrue(issued.await(10, TimeUnit.SECONDS), "both searches are issued");
        f.useNormalReload();
        var reload = CompletableFuture.supplyAsync(f::post);
        awaitRefusing(f, reload);
        assertFenced(api.search(MARKER, null), "a new search during the window");

        long cancelledAt = System.nanoTime();
        cancellable.cancel("caller cancelled during the reload window");
        var cancellation = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> cancelled.get(2, TimeUnit.SECONDS)).getCause();
        assertTrue(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(1),
            "the cancelled caller completes promptly");
        assertInstanceOf(io.justsearch.app.api.EngineWorkCancelledException.class, cancellation);

        var expired = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> deadline.get(5, TimeUnit.SECONDS)).getCause();
        assertTrue(System.nanoTime() - deadlineAt < TimeUnit.SECONDS.toNanos(1),
            "the caller completes at its deadline, not at the reload's end");
        var timedOut = assertInstanceOf(
            io.justsearch.app.api.knowledge.KnowledgeClientException.class, expired);
        assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
            timedOut.status(), timedOut.toString());

        // Both issued searches exit, so the drain completes and the reload publishes.
        assertReloadSuccess(reload.get(30, TimeUnit.SECONDS));
      } finally {
        cancellable.close();
        callers.shutdownNow();
        assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
      }
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (f.root.admission().activeWorkCount() != baseline && System.nanoTime() < until) {
        Thread.sleep(10);
      }
      assertEquals(baseline, f.root.admission().activeWorkCount(), "admitted work is released");
      assertEquals(0, clientHolders(f), "no client lease is left behind");
      var after = api.search(MARKER, null);
      assertEquals(200, after.statusCode(), after.body());
      assertFalse(resultIds(after).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------------------------

  private RuntimeReloadProducersHttpIntegrationTest.Fixture fixture() throws Exception {
    var owner = new RuntimeReloadProducersHttpIntegrationTest();
    owner.directory = directory;
    return owner.new Fixture();
  }

  /** The production knowledge routes over the fixture's bootstrap, as the Head mounts them. */
  private static final class Api implements AutoCloseable {
    final HttpClient transport = HttpClient.newHttpClient();
    final ExecutorService documents = Executors.newFixedThreadPool(2);
    final SearchPerSourceExecutor perSource;
    final KnowledgeSearchController search;
    final Javalin http;

    Api(RuntimeReloadProducersHttpIntegrationTest.Fixture f) { this(f, null); }

    Api(RuntimeReloadProducersHttpIntegrationTest.Fixture f,
        io.justsearch.app.api.gpl.RerankerService reranker) {
      var bootstrap = f.bootstrap;
      var config = new ConfigStore(ConfigStore.global().get(), bootstrap.publicationLock());
      perSource = new SearchPerSourceExecutor(f.root.executors(), f.root.admission());
      search = new KnowledgeSearchController(bootstrap, perSource, null,
          OnlineAiService.unavailable(), reranker, null, config);
      search.setWorkerCapability(bootstrap.workerCapability());
      DocumentService docs = BootstrapDocumentService.create(documents, documents,
          bootstrap::client, null, () -> {
            var lease = bootstrap.captureClient();
            return new RemoteDocumentService.ClientCapture() {
              @Override public KnowledgeClient client() { return lease.client(); }
              @Override public <T> T withClient(java.util.function.Function<KnowledgeClient, T> action) {
                return lease.withClient(action);
              }
              @Override public void close() { lease.close(); }
            };
          });
      var retrieve = new RetrieveContextController(bootstrap, () -> docs,
          OnlineAiService.unavailable(), () -> "", config);
      http = Javalin.create();
      KnowledgeRoutes.register(http, search, retrieve,
          org.slf4j.LoggerFactory.getLogger(RuntimeReloadServingUnavailableHttpIntegrationTest.class));
      http.start("127.0.0.1", 0);
    }

    HttpRequest request(String path, String body, Duration timeout) {
      var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + path))
          .timeout(timeout);
      return body == null ? builder.GET().build()
          : builder.header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    HttpResponse<String> send(String path, String body, Duration timeout) {
      try {
        return transport.send(request(path, body, timeout), HttpResponse.BodyHandlers.ofString());
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    HttpResponse<String> send(String path, String body) { return send(path, body, Duration.ofSeconds(20)); }

    HttpResponse<String> search(String query, String pathPrefix) {
      String filters = pathPrefix == null ? ""
          : ",\"filters\":{\"pathPrefix\":\"" + json(pathPrefix) + "\"}";
      return send("/api/knowledge/search", "{\"query\":\"" + query + "\",\"limit\":10" + filters + "}");
    }

    @Override public void close() throws Exception {
      try {
        http.stop();
        transport.close();
      } finally {
        documents.shutdownNow();
        assertTrue(documents.awaitTermination(10, TimeUnit.SECONDS));
        perSource.close();
      }
    }
  }

  /** Ids in the HTTP result order. */
  private static List<String> resultIds(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    var ids = new ArrayList<String>();
    for (JsonNode hit : JSON.readTree(response.body()).path("results")) ids.add(hit.path("id").asText());
    return ids;
  }

  private Path indexDistinctiveDocument(RuntimeReloadProducersHttpIntegrationTest.Fixture f,
      Api api) throws Exception {
    Path file = Files.writeString(directory.resolve("serving-window.txt"),
        MARKER + " distinctive content for the reload window");
    assertEquals(1, f.client.submitBatch(List.of(file), CONTEXT).getAcceptedCount());
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (resultIds(api.search(MARKER, null)).isEmpty()) {
      assertTrue(System.nanoTime() < until, "the distinctive document was not indexed");
      Thread.sleep(50);
    }
    return file;
  }

  /** Waits until the reload has fenced new captures, without holding a capture itself. */
  private static void awaitRefusing(RuntimeReloadProducersHttpIntegrationTest.Fixture f,
      CompletableFuture<?> reload) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    for (;;) {
      try (var ignored = f.server.captureServingView()) {
        assertFalse(reload.isDone(), "the reload finished before fencing captures");
        assertTrue(System.nanoTime() < until, "the reload never fenced new captures");
      } catch (IllegalStateException fenced) {
        return;
      }
      Thread.sleep(5);
    }
  }

  /** R1, R2, R4 and R5 at one reload phase. */
  private void assertWindowUnavailable(RuntimeReloadProducersHttpIntegrationTest.Fixture f, Api api,
      Path document, String phase) throws Exception {
    assertUnavailable(api.search(MARKER, null), phase + ": search");
    assertUnavailable(api.search(MARKER, directory.toString()), phase + ": filtered search");
    assertUnavailable(api.send("/api/knowledge/retrieve-context",
        "{\"query\":\"" + MARKER + "\",\"doc_ids\":[\"" + json(document) + "\"]}"),
        phase + ": retrieve-context with doc ids");
    assertUnavailable(api.send("/api/knowledge/retrieve-context",
        "{\"query\":\"" + MARKER + "\"}"), phase + ": open retrieve-context");
    assertUnavailable(api.send("/api/knowledge/collection-facet", "{\"maxDocsScanned\":10}"),
        phase + ": collection facets");
    assertUnavailable(api.send("/api/knowledge/suggest?query=" + MARKER.substring(0, 6), null),
        phase + ": suggest");
    assertUnavailable(api.send("/api/knowledge/folders",
        "{\"parentPath\":\"" + json(directory.toString()) + "\"}"), phase + ": folders");
    assertUnavailable(api.send("/api/knowledge/folder-files",
        "{\"folderPath\":\"" + json(directory.toString()) + "\"}"), phase + ": folder files");
    // The direct client capture refuses with the same classification and releases its admission.
    int baseline = f.root.admission().activeWorkCount();
    var direct = assertThrows(RuntimeException.class, () -> f.client.search(MARKER, 10, CONTEXT));
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(direct),
        phase + ": direct client capture " + direct);
    assertEquals(baseline, f.root.admission().activeWorkCount(),
        phase + ": a refused direct capture releases its admission");
  }

  /** R8: a finished failure says only that the view is unavailable, never stale success. */
  private void assertFinishedFailureUnavailable(RuntimeReloadProducersHttpIntegrationTest.Fixture f,
      Api api, Path document) throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      assertUnavailable(api.search(MARKER, null), "search after the failed reload");
    }
    assertUnavailable(api.send("/api/knowledge/retrieve-context",
        "{\"query\":\"" + MARKER + "\",\"doc_ids\":[\"" + json(document) + "\"]}"),
        "retrieve-context after the failed reload");
    assertEquals(0, clientHolders(f));
  }

  private static void assertUnavailable(HttpResponse<String> response) {
    assertUnavailable(response, "");
  }

  private static void assertUnavailable(HttpResponse<String> response, String what) {
    assertUnavailable(response, MESSAGE, what);
  }

  private static void assertUnavailable(HttpResponse<String> response, String message, String what) {
    assertEquals(503, response.statusCode(), what + ": " + response.body());
    var retryAfter = response.headers().firstValue("Retry-After");
    assertTrue(retryAfter.isPresent() && retryAfter.get().matches("[1-9][0-9]*"),
        what + ": Retry-After " + retryAfter);
    JsonNode body = JSON.readTree(response.body());
    assertEquals("SERVICE_UNAVAILABLE", body.path("errorCode").asText(), what + ": " + response.body());
    assertEquals("TRANSIENT", body.path("errorClass").asText(), what);
    assertTrue(body.path("retryable").asBoolean(), what);
    assertTrue(body.path("retrySafe").asBoolean(), what);
    assertEquals(message, body.path("error").asText(), what);
  }

  /** Retirement refuses the capture (how it is projected is the repair's concern). */
  private static void assertFenced(HttpResponse<String> response, String what) {
    assertNotEquals(200, response.statusCode(), what + ": " + response.body());
    assertEquals(MESSAGE, JSON.readTree(response.body()).path("error").asText(), what);
  }

  private static void assertReloadSuccess(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(JSON.readTree(response.body()).path("swapDurationMs").isNumber(), response.body());
  }

  private static int clientHolders(RuntimeReloadProducersHttpIntegrationTest.Fixture f)
      throws Exception {
    var field = f.bootstrap.getClass().getDeclaredField("clientHolders");
    field.setAccessible(true);
    return (int) field.get(f.bootstrap);
  }

  /** A runtime reload on its own thread, so the test can interrupt its drain. */
  private static final class Reloader {
    final CompletableFuture<Throwable> outcome = new CompletableFuture<>();
    final Thread thread;

    Reloader(RuntimeReloadProducersHttpIntegrationTest.Fixture f) {
      thread = new Thread(() -> {
        try {
          f.server.swapRuntime(f::reopen, Duration.ofSeconds(30),
              io.justsearch.adapters.lucene.runtime.SwapReason.ADMIN_TRIGGERED);
          outcome.complete(null);
        } catch (Throwable failure) {
          outcome.complete(failure);
        }
      }, "interruptible-reload");
      thread.start();
    }

    Throwable interrupt() throws Exception {
      thread.interrupt();
      var failure = outcome.get(5, TimeUnit.SECONDS);
      thread.join(5_000);
      assertNotNull(failure, "the interrupted reload must refuse");
      return failure;
    }
  }

  private static void assertIncumbentServes(Api api) {
    var served = api.search(MARKER, null);
    assertEquals(200, served.statusCode(), served.body());
    assertFalse(resultIds(served).isEmpty(), "the incumbent view serves new searches");
  }

  private static void assertIncumbentServesThenReloadSucceeds(
      RuntimeReloadProducersHttpIntegrationTest.Fixture f, Api api) throws Exception {
    assertIncumbentServes(api);
    f.useNormalReload();
    assertReloadSuccess(f.post());
    assertIncumbentServes(api);
  }

  private static boolean clientVerified(RuntimeReloadProducersHttpIntegrationTest.Fixture f)
      throws Exception {
    return (boolean) field(f.bootstrap, "clientVerified");
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static String json(Object value) {
    String text = JSON.writeValueAsString(value.toString());
    return text.substring(1, text.length() - 1);
  }
}
