/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.BootTrace;
import io.justsearch.app.services.bootstrap.PhaseRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 541 §4.2 — Javalin route serving the composition-substrate boot-trace endpoint.
 *
 * <p>Modeled on tempdoc 521 §16.8's {@code InfraRoutes} pattern: route registration extracted
 * from {@code LocalApiServer.setupRoutes} so the route set is statically testable via {@code
 * LegacyEndpointGuardTest} (which inspects Javalin's internal router without spinning up an
 * HTTP server).
 *
 * <p>One route:
 *
 * <ul>
 *   <li>{@code GET /api/boot/phases?process={head|worker|brain}} — JSON envelope wrapping the
 *       sealed {@link BootTrace} snapshot. Returns 200 once the {@link HeadAssembly} has
 *       completed construction (the substrate's typed primitive is in place); 503 on partial
 *       boot if {@code HeadAssembly == null} (shouldn't happen — Javalin doesn't bind routes
 *       until HeadAssembly has constructed).
 * </ul>
 *
 * <p>Critical-analysis note: the §4.2 SSE channel proposed in tempdoc 541 is intentionally
 * <strong>not</strong> registered here. HTTP server binding happens <em>after</em> HeadAssembly
 * construction completes — so an SSE consumer cannot connect during boot. By the time a client
 * subscribes, the BootTrace is already sealed. The endpoint returns the snapshot directly.
 * For per-process discriminators other than {@code head}, see §4.2 + §5.1: Worker exposes its
 * own gRPC introspection RPC (separate slice); Brain co-resides with Head and is reached via
 * {@code ?process=brain} once {@code BrainAssembly} ships in 541 P8.
 *
 * <p>JSON envelope shape:
 *
 * <pre>{@code
 * {
 *   "boot": {
 *     "process": "head",
 *     "bootStartedAtMs": 1234567890000,
 *     "bootCompletedAtMs": 1234567891234,
 *     "totalDurationMs": 1234,
 *     "phases": [
 *       {
 *         "name": "infra",
 *         "eagerness": "EAGER",
 *         "startedAtMs": ...,
 *         "completedAtMs": ...,
 *         "durationMs": ...,
 *         "outcome": "READY",
 *         "reasonCode": null,
 *         "spanId": null
 *       },
 *       ...
 *     ]
 *   }
 * }
 * }</pre>
 */
public final class BootRoutes {
  private static final Logger log = LoggerFactory.getLogger(BootRoutes.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private BootRoutes() {}

  public static void register(Javalin app, HeadAssembly headAssembly) {
    if (headAssembly == null) {
      log.warn("BootRoutes.register skipped: HeadAssembly is null (route not bound)");
      return;
    }
    app.get(
        "/api/boot/phases",
        ctx -> {
          String process = ctx.queryParam("process");
          if (process == null || process.isBlank()) {
            process = BootTrace.HEAD;
          }
          if (!BootTrace.HEAD.equals(process)
              && !BootTrace.WORKER.equals(process)
              && !BootTrace.BRAIN.equals(process)) {
            ctx.status(400)
                .json(
                    Map.of(
                        "error",
                            "Invalid process discriminator: " + process
                                + ". Allowed: head | worker | brain.",
                        "errorCode", ApiErrorCode.INVALID_REQUEST.name()));
            return;
          }
          // Tempdoc 541 §5.1: Worker introspection routes through gRPC (separate slice).
          // Brain co-resides; once 541 P8 ships BrainAssembly its trace is reachable here.
          if (BootTrace.WORKER.equals(process)) {
            ctx.status(501)
                .json(
                    Map.of(
                        "error",
                            "Worker boot-phase introspection not yet implemented. WorkerAssembly is a future tempdoc.",
                        "errorCode", ApiErrorCode.NOT_SUPPORTED.name()));
            return;
          }
          if (BootTrace.BRAIN.equals(process)) {
            // Tempdoc 541 §5.1 + P8: BrainAssembly wraps ILM as one Phase Output. Available
            // after ServicePhase completes. Returns 503 if Head's boot isn't past Service yet.
            var brain = headAssembly.brainAssembly();
            if (brain == null) {
              ctx.status(503)
                  .json(
                      Map.of(
                          "error",
                              "BrainAssembly not yet available (HeadAssembly still in pre-Service phases)",
                          "errorCode",
                          ApiErrorCode.SERVICE_UNAVAILABLE.name()));
              return;
            }
            try {
              ctx.contentType("application/json")
                  .result(
                      JSON.writerWithDefaultPrettyPrinter()
                          .writeValueAsString(brainEnvelope(brain.bootTrace())));
            } catch (Exception e) {
              log.warn("/api/boot/phases?process=brain failed: {}", e.getMessage(), e);
              ctx.status(500)
                  .json(
                      Map.of(
                          "error",
                          e.getMessage() == null ? e.toString() : e.getMessage(),
                          "errorCode",
                          ApiErrorCode.INTERNAL_ERROR.name()));
            }
            return;
          }
          try {
            BootTrace trace = headAssembly.bootTrace();
            // Fix-pass A.1: synthesize the agent-tools-registration phase row from the
            // Memoized's current state — the sealed BootTrace's PENDING entry stays
            // truthful for boot-time, but the rendered envelope flips it to READY once
            // the Memoized has resolved (post-Worker-connect).
            ctx.contentType("application/json")
                .result(
                    JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(envelopeWithLazyState(trace, headAssembly)));
          } catch (Exception e) {
            log.warn("/api/boot/phases GET failed: {}", e.getMessage(), e);
            ctx.status(500)
                .json(
                    Map.of(
                        "error",
                        e.getMessage() == null ? e.toString() : e.getMessage(),
                        "errorCode",
                        ApiErrorCode.INTERNAL_ERROR.name()));
          }
        });
  }

  // §12.D: the original `envelope(BootTrace)` method was superseded by the head-specific
  // `envelopeWithLazyState` (Memoized synthesis + rebuilds field) and the brain-specific
  // `brainEnvelope` (projection flag). UnreferencedCodeTest flagged it as dead. Removed.

  /**
   * Fix-pass A.1 + Tier 4: head-process envelope with Memoized lazy-state synthesis +
   * RebuildHistory ring buffer. The sealed BootTrace holds the agent-tools-registration
   * phase as LAZY/PENDING. When the Memoized has resolved, this method substitutes a
   * synthesized row reflecting current resolved state. Tier 4 adds the {@code rebuilds}
   * field carrying the post-boot RebuildHistory snapshot (oldest→newest).
   */
  static Map<String, Object> envelopeWithLazyState(BootTrace trace, HeadAssembly head) {
    List<Map<String, Object>> phases = new java.util.ArrayList<>(phaseList(trace.phases()));
    var memoized = head.agentToolsRegistration();
    if (memoized != null && memoized.isResolved()) {
      for (int i = 0; i < phases.size(); i++) {
        Map<String, Object> p = phases.get(i);
        if ("agent-tools-registration".equals(p.get("name"))) {
          Map<String, Object> synthesized = new LinkedHashMap<>(p);
          boolean registered = Boolean.TRUE.equals(memoized.resolvedValue().orElse(false));
          synthesized.put("outcome", registered ? PhaseRecord.READY : PhaseRecord.DEGRADED);
          synthesized.put("reasonCode", registered ? "resolved" : "agent_tools.registration_failed");
          // §12.G: populate timing from the Memoized's captured resolution timestamps.
          memoized
              .startedAtMs()
              .ifPresent(t -> synthesized.put("startedAtMs", t));
          memoized
              .resolvedAtMs()
              .ifPresent(t -> synthesized.put("completedAtMs", t));
          if (memoized.startedAtMs().isPresent() && memoized.resolvedAtMs().isPresent()) {
            synthesized.put(
                "durationMs",
                memoized.resolvedAtMs().getAsLong() - memoized.startedAtMs().getAsLong());
          }
          phases.set(i, synthesized);
          break;
        }
      }
    }
    Map<String, Object> boot = new LinkedHashMap<>();
    boot.put("process", trace.process());
    boot.put("bootStartedAtMs", trace.bootStartedAtMs());
    boot.put("bootCompletedAtMs", trace.bootCompletedAtMs());
    boot.put("totalDurationMs", trace.totalDurationMs().orElse(null));
    boot.put("phases", phases);
    var rh = head.rebuildHistory();
    boot.put("rebuilds", rh == null ? List.of() : phaseList(rh.snapshot()));
    boot.put(
        "rebuildHistoryCapacity", rh == null ? 0 : rh.capacity());
    boot.put("rebuildHistoryTotal", rh == null ? 0L : rh.totalAppends());
    return Map.of("boot", boot);
  }

  /**
   * Fix-pass C.3: brain-process envelope. Marks {@code projection: true} because Brain is
   * co-resident with Head — its trace is projected from Head's service-phase window rather
   * than being its own composition-root boot. When the Brain process is ever split into a
   * separate JVM, this projection flag becomes false and the trace reflects an actual Brain
   * boot.
   */
  static Map<String, Object> brainEnvelope(BootTrace trace) {
    Map<String, Object> boot = new LinkedHashMap<>();
    boot.put("process", trace.process());
    boot.put("projection", true);
    boot.put("projectionNote",
        "Brain is co-resident with Head; this trace is projected from the ILM-construction"
            + " window inside Head's ServicePhase. When Brain splits into a separate JVM,"
            + " projection becomes false and the trace reflects that process's own boot.");
    boot.put("bootStartedAtMs", trace.bootStartedAtMs());
    boot.put("bootCompletedAtMs", trace.bootCompletedAtMs());
    boot.put("totalDurationMs", trace.totalDurationMs().orElse(null));
    boot.put("phases", phaseList(trace.phases()));
    return Map.of("boot", boot);
  }

  private static List<Map<String, Object>> phaseList(List<PhaseRecord> phases) {
    return phases.stream().map(BootRoutes::phaseMap).toList();
  }

  private static Map<String, Object> phaseMap(PhaseRecord p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", p.name());
    m.put("eagerness", p.eagerness().name());
    m.put("startedAtMs", p.startedAtMs());
    m.put("completedAtMs", p.completedAtMs());
    m.put("durationMs", p.durationMs());
    m.put("outcome", p.outcome());
    m.put("reasonCode", p.reasonCode());
    m.put("spanId", p.spanId());
    return m;
  }
}
