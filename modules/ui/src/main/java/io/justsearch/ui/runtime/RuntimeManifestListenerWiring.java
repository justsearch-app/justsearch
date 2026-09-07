/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 501 Phase 29 / §13.1 polish item: extract the manifest-listener
 * wiring out of {@code HeadlessApp}.
 *
 * <p>Three pieces of wiring previously sat side-by-side in {@code HeadlessApp.main}:
 *
 * <ol>
 *   <li>The initial worker-state publish — one shot after {@code connectWorker}
 *       resolves, projecting either {@code worker.state="ready"} or
 *       {@code worker.state="failed"} into the manifest.
 *   <li>The {@link InferenceCapability} listener (Phase 13) — runs on every
 *       inference transition, projects the AI sub-record.
 *   <li>The {@link WorkerCapability} listener (Phase 22) — runs on every
 *       worker transition, projects the Worker sub-record + recomputed
 *       lifecycle.
 * </ol>
 *
 * <p>All three share the same lifecycle-derivation step
 * ({@code LifecycleProjection.derive(workerCap, inferenceCap)}) and the
 * same defensive try/catch wrapping ("publish failures must not break
 * boot"). Keeping them as inline blocks in {@code HeadlessApp} duplicated
 * structure and made the wiring re-projection-drift surface unnecessarily
 * large. This helper owns the wiring; {@code HeadlessApp} now calls
 * {@link #wire} once.
 */
public final class RuntimeManifestListenerWiring {

  private static final Logger log = LoggerFactory.getLogger(RuntimeManifestListenerWiring.class);

  private RuntimeManifestListenerWiring() {}

  /**
   * Wire all manifest listeners + perform the initial worker-state and AI
   * publishes.
   *
   * @param publisher the runtime-manifest publisher
   * @param bootstrap HeadAssembly (carries WorkerCapability +
   *     InferenceCapability via {@code bootstrap.capabilities().worker() / .inference()}).
   *     Tempdoc 519 §31 Phase 5 renamed the prior {@code AppFacadeBootstrap}
   *     to {@code HeadAssembly}; capability accessors moved from direct
   *     methods to the typed {@code CapabilityGraph} accessor.
   * @param knowledgeServer the Worker bootstrap, or {@code null} when none could be constructed.
   *     Tempdoc 825: non-null no longer implies connected — the initial worker-state branch reads
   *     {@code hasClient()}
   * @param knowledgeServerStartError reason string when the bootstrap did not connect, else
   *     {@code null}
   * @param indexBasePathSupplier supplier returning the current index
   *     base path (read each time a publish call is composed so config
   *     changes propagate)
   */
  public static void wire(
      RuntimeManifestPublisher publisher,
      HeadAssembly bootstrap,
      KnowledgeServerBootstrap knowledgeServer,
      String knowledgeServerStartError,
      Supplier<Path> indexBasePathSupplier,
      String modeIntent) {
    final InferenceCapability infCap = bootstrap.capabilities().inference();
    final WorkerCapability workCap = bootstrap.capabilities().worker();

    // Inference listener (Phase 13).
    infCap.addListener(
        (prev, curr) -> {
          try {
            LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
            // Tempdoc 682 Item 2: read the llama-server build pin (expected) + the
            // /props-observed running build (actual) fresh on every publish — by the time
            // inference reaches READY the /props observation has landed, so the manifest
            // carries the expected-vs-actual pair (either side null = unknown, supported).
            publisher.publishAi(
                curr.name(),
                infCap.required(),
                infCap.pendingReason(),
                curr == CapabilityHealth.READY,
                ls.name(),
                bootstrap.expectedLlamaServerBuild(),
                bootstrap.actualLlamaServerBuild(),
                bootstrap.llamaServerThinkingSupport(),
                bootstrap.launchedContextWindow());
            publisher.publishMode(modeIntent, realizedMode(workCap, infCap));
            // Tempdoc 842 §2.5: the realized chat identity rides the SAME listener that keeps
            // `mode` fresh. Every engine start, stop and profile switch is an inference transition,
            // so the block appears, updates and clears with the engine it describes.
            publisher.publishChat(bootstrap.realizedChatIdentity());
          } catch (Exception e) {
            log.warn("Runtime manifest publishAi (listener) failed (non-fatal)", e);
          }
        });

    // Initial worker-state publish (Phase 12).
    try {
      LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
      String lifecycleStr = ls.name();
      // Tempdoc 825: a non-null bootstrap no longer implies a CONNECTED one — a failed boot now
      // keeps its (restartable) instance so the health monitor's recovery arm can re-attempt it.
      // Branch on the client, which is what "worker ready" actually means here.
      if (knowledgeServer != null && knowledgeServer.hasClient()) {
        Path idx = indexBasePathSupplier.get();
        publisher.publishWorkerReady(
            null, idx != null ? idx.toString() : null, lifecycleStr);
      } else {
        String reason =
            knowledgeServerStartError != null && !knowledgeServerStartError.isBlank()
                ? knowledgeServerStartError
                : "Worker bootstrap did not produce a connected handle";
        publisher.publishWorkerFailed(reason, lifecycleStr);
      }
    } catch (Exception e) {
      log.warn("Runtime manifest worker-state publish failed (non-fatal)", e);
    }

    // Worker listener (Phase 22).
    workCap.addListener(
        (prev, curr) -> {
          try {
            LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
            if (curr == CapabilityHealth.READY) {
              Path idx = indexBasePathSupplier.get();
              publisher.publishWorkerReady(
                  null, idx != null ? idx.toString() : null, ls.name());
            } else if (curr == CapabilityHealth.OFFLINE
                || curr == CapabilityHealth.DEGRADED
                || curr == CapabilityHealth.RECOVERING) {
              String reason = workCap.pendingReason();
              publisher.publishWorkerFailed(
                  reason != null && !reason.isBlank()
                      ? reason
                      : "Worker capability degraded",
                  ls.name());
            } else {
              publisher.publishLifecycle(ls.name());
            }
            publisher.publishMode(modeIntent, realizedMode(workCap, infCap));
          } catch (Exception e) {
            log.warn("Runtime manifest publishWorker (listener) failed (non-fatal)", e);
          }
        });

    // Initial AI publish (Phase 13).
    try {
      LifecycleState ls = LifecycleProjection.derive(workCap, infCap);
      publisher.publishAi(
          infCap.health().name(),
          infCap.required(),
          infCap.pendingReason(),
          infCap.health() == CapabilityHealth.READY,
          ls.name(),
          bootstrap.expectedLlamaServerBuild(),
          bootstrap.actualLlamaServerBuild(),
          bootstrap.llamaServerThinkingSupport(),
          bootstrap.launchedContextWindow());
    } catch (Exception e) {
      log.warn("Runtime manifest initial-AI publish failed (non-fatal)", e);
    }

    // Initial mode publish (tempdoc 657) — the configured intent + the coarse realized capability.
    try {
      publisher.publishMode(modeIntent, realizedMode(workCap, infCap));
    } catch (Exception e) {
      log.warn("Runtime manifest initial-mode publish failed (non-fatal)", e);
    }

    // Initial chat publish (tempdoc 842 §2.5). Almost always a no-op at boot — the engine is not
    // online yet, so the projection is null and publishChat short-circuits — but it makes the
    // adopted-engine case (a stack that inherits an already-running llama-server) carry its
    // identity without waiting for the next transition.
    try {
      publisher.publishChat(bootstrap.realizedChatIdentity());
    } catch (Exception e) {
      log.warn("Runtime manifest initial-chat publish failed (non-fatal)", e);
    }
  }

  /**
   * Coarse projection of the <em>realized</em> capability (tempdoc 657) from the worker + inference
   * capabilities — so the manifest's advertised mode never outruns what is actually loaded:
   *
   * <ul>
   *   <li>{@code full} — worker ready and inference ready;
   *   <li>{@code retrieval-only} — worker ready, and inference either not required (the intended
   *       shape for MCP Lite / a headless-no-LLM run) or the LLM is simply not up;
   *   <li>{@code degraded} — worker not ready.
   * </ul>
   */
  private static String realizedMode(WorkerCapability workCap, InferenceCapability infCap) {
    if (workCap.health() != CapabilityHealth.READY) {
      return "degraded";
    }
    if (infCap.health() == CapabilityHealth.READY) {
      return "full";
    }
    return "retrieval-only";
  }

  // Lane F stage A item A11: readGrpcPort() is gone with the memory-mapped signal bus it read from.
  // The index half is composed inside this JVM by EngineRoot — there is no worker process, no port
  // and no channel — so the manifest's `worker.grpcPort` is published as null (the field is already
  // declared nullable, see RuntimeManifestPublisher#publishWorkerReady) rather than as a fabricated
  // 0/-1. Nothing here reports a pid either: the manifest's own `pid` is this process's, and
  // RuntimeManifestPublisher already takes it from ProcessHandle.current().
}
