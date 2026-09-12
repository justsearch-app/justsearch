/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Production HTTP after-filter, dispatcher, durable runner and aggregate admission owner. */
@Timeout(20)
final class OperationAdmissionLifetimeTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"success", "failure", "cancel", "fatal"})
  void httpResponseDoesNotReleaseActualOperationWork(String outcome) throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var actual = new CompletableFuture<OperationResult>();
    var handlerContext = new AtomicReference<EngineContext>();
    var id = new OperationRef("core.async-quota-probe");
    var handlers = new HandlerRegistry();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Recorded handler required");
      }
      @Override public OperationExecution executeRecorded(String args, InvocationProvenance provenance,
          EngineContext context, OperationRecordHandle record) {
        handlerContext.set(context);
        return new OperationExecution(OperationResult.success("Started"), actual);
      }
    });
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"));
        var events = Executors.newSingleThreadExecutor();
        var client = HttpClient.newHttpClient()) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var dispatcher = new OperationExecutorImpl(runner, admission, handlers);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, admission, admission).install(app);
      app.get("/quota-start", ctx -> ctx.result(dispatcher.dispatch(operation(id, AuditPolicy.METADATA_ONLY),
          "{}", RequestEngineContext.get(ctx)).message()));
      app.get("/quota-next", ctx -> ctx.result("Next"));
      app.start("127.0.0.1", 0);
      assertEquals(200, get(client, app, "/quota-start"));
      assertEquals(1, admission.activeWorkCount(), "HTTP response must leave the async owner admitted");
      try (var attached = admission.attach(handlerContext.get())) {
        assertEquals(handlerContext.get().workId(), attached.context().workId());
      }
      assertEquals(429, get(client, app, "/quota-next"));
      switch (outcome) {
        case "success" -> actual.complete(OperationResult.success("Done"));
        case "failure" -> actual.completeExceptionally(new IllegalStateException("Owner failed"));
        case "cancel" -> actual.cancel(false);
        case "fatal" -> actual.completeExceptionally(new AssertionError("Owner exited fatally"));
        default -> throw new AssertionError(outcome);
      }
      assertEquals(0, admission.activeWorkCount());
      assertEquals(200, get(client, app, "/quota-next"));
    } finally {
      actual.completeExceptionally(new IllegalStateException("Fixture cleanup"));
      app.stop();
    }
  }

  @Test
  void synchronousRefusalAndThrowReleaseAdmittedLibraryWork() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var id = new OperationRef("core.throw-quota-probe");
    var handlers = new HandlerRegistry();
    handlers.register(id, (args, context) -> {
      assertTrue(context.workId().isPresent(), "library invocation receives its newly admitted identity");
      throw new IllegalStateException("Owner rejected");
    });
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var dispatcher = new OperationExecutorImpl(runner, admission, handlers);
      for (var audit : AuditPolicy.values()) {
        assertThrows(IllegalStateException.class,
            () -> dispatcher.dispatch(operation(id, audit), "{}", TestRequestContexts.browser()));
        assertEquals(0, admission.activeWorkCount());
        assertFalse(dispatcher.dispatch(operation(id, audit), "[]", TestRequestContexts.browser()).success());
        assertEquals(0, admission.activeWorkCount());
      }
    }
  }

  private static int get(HttpClient client, Javalin app, String path) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  private static Operation operation(OperationRef id, AuditPolicy audit) {
    return new Operation(id, Presentation.of(new I18nKey("test.quota"), new I18nKey("test.quota.desc")),
        Interface.of("{\"type\":\"object\",\"additionalProperties\":false}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.LOW, ConfirmStrategy.None.INSTANCE, audit,
            RetryPolicy.noRetry(), Set.of(), false), OperationAvailability.empty(), OperationLineage.empty(),
        Binding.of(id), new Provenance(TrustTier.CORE, "test", "1.0"), Set.of(ExecutorTag.UI));
  }
}
