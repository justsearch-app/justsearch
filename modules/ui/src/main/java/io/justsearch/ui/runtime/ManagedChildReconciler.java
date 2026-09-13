/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Identity-safe predecessor cleanup performed before any child-capable bootstrap begins. */
public final class ManagedChildReconciler implements AutoCloseable {
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
  private static final String HTTP_EXECUTOR_NAME = "head.child-reconciliation.http";

  private final ManagedChildRegistry registry;
  private final String declaredLlamaConfigHash;
  private final Termination termination;
  private final EngineExecutorRegistry.Registration httpRegistration;
  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();
  private boolean closed;

  public ManagedChildReconciler(
      EngineExecutorRegistry processExecutors,
      ManagedChildRegistry registry,
      String declaredLlamaConfigHash) {
    this(processExecutors, registry, declaredLlamaConfigHash, ManagedChildReconciler::terminateMatched);
  }

  @FunctionalInterface
  interface Termination {
    boolean terminate(ProcessHandle handle);
  }

  ManagedChildReconciler(
      EngineExecutorRegistry processExecutors,
      ManagedChildRegistry registry,
      String declaredLlamaConfigHash,
      Termination termination) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.declaredLlamaConfigHash = declaredLlamaConfigHash;
    this.termination = Objects.requireNonNull(termination, "termination");
    HttpResources resources = openHttpResources(processExecutors);
    this.httpRegistration = resources.registration();
    this.http = resources.client();
  }

  private static HttpResources openHttpResources(EngineExecutorRegistry processExecutors) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                HTTP_EXECUTOR_NAME,
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.PLATFORM,
                1,
                background.maxQueue(),
                1));
    try {
      ExecutorService executor =
          registration.open(
              runnable -> {
                Thread thread = new Thread(runnable, "head-child-reconciliation-http");
                thread.setDaemon(true);
                return thread;
              });
      HttpClient client =
          HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).executor(executor).build();
      return new HttpResources(registration, client);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private record HttpResources(
      EngineExecutorRegistry.Registration registration, HttpClient client) {}

  public void reconcile() throws IOException {
    for (ManagedChild child : registry.snapshot()) reconcile(child);
  }

  private void reconcile(ManagedChild child) throws IOException {
    Optional<ProcessHandle> found = ProcessHandle.of(child.pid());
    if (found.isEmpty() || !found.get().isAlive()) {
      registry.remove(child.id());
      return;
    }
    ProcessHandle handle = found.get();
    ManagedChild.IdentityMatch identity = child.identityOf(handle);
    if (identity == ManagedChild.IdentityMatch.UNKNOWN) {
      return; // unknown identity: preserve evidence and do not touch the live process
    }
    if (identity == ManagedChild.IdentityMatch.MISMATCH) {
      registry.remove(child.id()); // proved stale/mismatched record; live process is unrelated
      return;
    }

    if (child.kind() == ManagedChild.Kind.LLAMA_SERVER
        && declaredLlamaConfigHash != null
        && declaredLlamaConfigHash.equals(child.declaredConfigHash())
        && healthyLlama(child.endpoint())) {
      return; // retained for managed adoption by app-inference
    }
    if (termination.terminate(handle)) registry.remove(child.id());
  }

  private boolean healthyLlama(String endpoint) {
    if (endpoint == null || !endpoint.startsWith("http://127.0.0.1:")) return false;
    try {
      HttpResponse<String> health = get(endpoint + "/health");
      if (health.statusCode() < 200 || health.statusCode() >= 300) return false;
      HttpResponse<String> props = get(endpoint + "/props");
      if (props.statusCode() < 200 || props.statusCode() >= 300) return false;
      JsonNode root = json.readTree(props.body());
      JsonNode alias = root.get("model_alias");
      JsonNode model = root.get("model_path");
      JsonNode ctx = root.get("n_ctx");
      return (alias != null && alias.isTextual() && !alias.asText().isBlank())
          || (model != null && model.isTextual() && !model.asText().isBlank())
          || (ctx != null && ctx.canConvertToInt() && ctx.asInt() > 0);
    } catch (Exception e) {
      return false;
    }
  }

  private HttpResponse<String> get(String uri) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(uri)).timeout(PROBE_TIMEOUT).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static boolean terminateMatched(ProcessHandle handle) {
    handle.destroy();
    try {
      if (handle.isAlive()) handle.onExit().get(5, TimeUnit.SECONDS);
      if (handle.isAlive()) {
        handle.destroyForcibly();
        handle.onExit().get(5, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    return !handle.isAlive();
  }

  /** Stops the probe client within the bounded HTTP shutdown window and releases its registration. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      http.shutdownNow();
      boolean terminated = false;
      try {
        terminated = http.awaitTermination(PROBE_TIMEOUT);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      // HttpClient.close() waits indefinitely in the JDK when the client is not terminated. Only
      // invoke it after the bounded shutdown has completed; the registry registration remains the
      // owner of the supplied executor and is always released, even after a timeout.
      if (terminated || http.isTerminated()) {
        http.close();
      }
    } finally {
      httpRegistration.close();
    }
  }
}
