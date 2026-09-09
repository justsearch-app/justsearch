/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.gpl.GplEvalSnapshot;
import io.justsearch.app.services.gpl.GplJobCoordinator;
import io.justsearch.app.services.gpl.GplRevalidationTrigger;
import io.justsearch.app.services.gpl.GplTrainingTripleStore;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: GPL training-triple orchestration helpers extracted from
 * {@code HeadAssembly}. Static utility class — no state. Encapsulates the auto-trigger
 * Thread lifecycle, MIME facet fetch, eval snapshot capture, and coordinator construction
 * (about 150 LOC of orchestration that doesn't belong in the bootstrap's body).
 */
public final class GplOrchestration {
  private static final EngineContext ENGINE_CONTEXT = io.justsearch.app.services.intent.EngineProvenance.internal(
      "gpl-orchestration", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);


  private static final Logger log = LoggerFactory.getLogger(GplOrchestration.class);

  private GplOrchestration() {}

  /**
   * Starts a virtual-thread auto-trigger loop that polls the Worker every 30s. When the Worker
   * state is IDLE and the doc count is stable across two consecutive polls, fires
   * {@code coordinator.runAsync()} via the trigger's evaluate logic. Returns null if either
   * {@code coordinator} or {@code client} is null.
   */
  public static Thread startAutoTrigger(
      GplJobCoordinator coordinator,
      Supplier<KnowledgeClient> clientSupplier,
      OnlineAiService aiService,
      Path snapshotFile,
      GplRevalidationTrigger trigger) {
    if (coordinator == null || clientSupplier == null) {
      return null;
    }
    return Thread.ofVirtual()
        .name("gpl-auto-trigger")
        .start(() -> autoTriggerLoop(coordinator, clientSupplier, aiService, snapshotFile, trigger));
  }

  private static void autoTriggerLoop(
      GplJobCoordinator coordinator,
      Supplier<KnowledgeClient> clientSupplier,
      OnlineAiService aiService,
      Path snapshotFile,
      GplRevalidationTrigger trigger) {
    long prevDocCount = -1L;
    long prevUptimeMs = -1L;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(30_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      try {
        KnowledgeClient client = clientSupplier.get();
        if (client == null) {
          continue;
        }
        io.justsearch.ipc.StatusResponse status = client.getStatus(ENGINE_CONTEXT);
        String state = status.getCore().getState();
        long docCount = status.getCore().getDocCount();
        long uptimeMs = status.getCore().getUptimeMs();
        if (uptimeMs < prevUptimeMs) {
          log.info(
              "GPL auto-trigger: Worker restart detected (uptime {}ms < {}ms); resetting stabilization counter",
              uptimeMs, prevUptimeMs);
          prevDocCount = -1L;
        }
        prevUptimeMs = uptimeMs;
        if ("IDLE".equals(state) && docCount > 0 && docCount == prevDocCount && aiService.isAvailable()) {
          Map<String, Long> mimeCounts = fetchMimeFacets(client);
          if (mimeCounts == null) {
            log.debug("GPL auto-trigger: MIME facets unavailable, skipping evaluation");
            prevDocCount = docCount;
            continue;
          }
          GplEvalSnapshot lastEval = GplEvalSnapshot.load(snapshotFile);
          GplRevalidationTrigger.TriggerResult result = trigger.evaluate(lastEval, docCount, mimeCounts);
          if (result.shouldRun()) {
            log.info("GPL revalidation triggered (docCount={}): {}", docCount, result.reasons());
            coordinator.runAsync();
          } else {
            log.debug("GPL auto-trigger: no revalidation needed (docCount={})", docCount);
          }
          prevDocCount = -1L;
        } else {
          prevDocCount = docCount;
        }
      } catch (Exception e) {
        log.debug("GPL auto-trigger: error polling worker status", e);
      }
    }
  }

  /** Fetches MIME-type distribution via a facet-only search. Returns null on failure. */
  public static Map<String, Long> fetchMimeFacets(KnowledgeClient client) {
    try {
      io.justsearch.ipc.SearchRequest req =
          io.justsearch.ipc.SearchRequest.newBuilder()
              .setQuery("*:*")
              .setQuerySyntax(io.justsearch.ipc.SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
              .setLimit(0)
              // Tempdoc 787 item 4b: a bare request (no pipeline) resolves on the Worker via
              // SearchPlanner's `deprecated_mode_fallback` branch. PipelineConfigs.TEXT is that
              // branch's leg set stated explicitly (sparse+lmart+expansion), so the wire shape
              // becomes current without changing which legs run for this facet-only probe.
              .setPipeline(io.justsearch.ipc.PipelineConfigs.TEXT)
              .setFacets(
                  io.justsearch.ipc.FacetSpec.newBuilder()
                      .setInclude(true)
                      .addFields(
                          io.justsearch.ipc.FacetFieldSpec.newBuilder()
                              .setField("mime")
                              .setSize(100)
                              .build())
                      .build())
              .build();
      io.justsearch.ipc.SearchResponse resp = client.search(req, ENGINE_CONTEXT);
      io.justsearch.ipc.FacetCounts counts = resp.getFacetsMap().get("mime");
      return counts != null ? new HashMap<>(counts.getCountsMap()) : Map.of();
    } catch (Exception e) {
      log.warn("Failed to fetch MIME facets for GPL trigger — skipping evaluation", e);
      return null;
    }
  }

  /** Captures + persists a GPL eval snapshot after a successful job completion. Best-effort. */
  public static void captureSnapshot(
      KnowledgeClient client, GplJobCoordinator coordinator, Path snapshotFile) {
    try {
      io.justsearch.ipc.StatusResponse status = client.getStatus(ENGINE_CONTEXT);
      Map<String, Long> mimeCounts = fetchMimeFacets(client);
      long triples = coordinator.getStatus().tripleCount();
      GplEvalSnapshot.capture(status, mimeCounts, triples).save(snapshotFile);
      log.info(
          "GPL eval snapshot saved: docCount={}, mimeTypes={}, triples={}",
          status.getCore().getDocCount(),
          mimeCounts.size(),
          triples);
    } catch (Exception e) {
      log.warn("Failed to capture GPL eval snapshot (non-fatal)", e);
    }
  }

  /**
   * Tempdoc 519 F5 step 4: encapsulates the holder-array + snapshot-callback + auto-trigger
   * wiring previously inlined in {@code HeadAssembly}. Constructs the snapshot file path,
   * the coordinator (with the holder pattern that lets the snapshot callback reach the
   * coordinator after createCoordinator returns), and the auto-trigger thread. Returns a
   * record bundling all three.
   *
   * @param onAfterSnapshot callback fired after a snapshot is captured (used by the bootstrap
   *     to optionally fire {@code startLambdaMartTrainingAsync} when LambdaMART is enabled).
   */
  public static Wired wire(
      Path dataDir,
      Supplier<KnowledgeClient> clientSupplier,
      OnlineAiService aiService,
      KnowledgeHttpApiAdapter agentSearchAdapter,
      Runnable onAfterSnapshot) {
    Path snapshotFile = dataDir.resolve("gpl-eval-snapshot.json");
    GplJobCoordinator[] coordinatorHolder = new GplJobCoordinator[1];
    GplJobCoordinator coordinator =
        createCoordinator(
            dataDir,
            clientSupplier,
            aiService,
            agentSearchAdapter,
            () -> {
              KnowledgeClient client = clientSupplier.get();
              if (client != null) {
                captureSnapshot(client, coordinatorHolder[0], snapshotFile);
              }
              onAfterSnapshot.run();
            });
    coordinatorHolder[0] = coordinator;
    Thread autoTriggerThread =
        startAutoTrigger(
            coordinator, clientSupplier, aiService, snapshotFile, new GplRevalidationTrigger());
    return new Wired(coordinator, autoTriggerThread, snapshotFile);
  }

  /** Bundle returned by {@link #wire}. */
  public record Wired(GplJobCoordinator coordinator, Thread autoTriggerThread, Path snapshotFile) {}

  /** Constructs the GPL job coordinator. Returns null if dependencies are unavailable. */
  public static GplJobCoordinator createCoordinator(
      Path dataDir,
      Supplier<KnowledgeClient> clientSupplier,
      OnlineAiService aiService,
      KnowledgeHttpApiAdapter adapter,
      Runnable onJobCompleted) {
    if (clientSupplier == null || aiService == null) {
      log.debug("GplJobCoordinator not created: clientSupplier or aiService unavailable");
      return null;
    }
    try {
      GplTrainingTripleStore tripleStore = new GplTrainingTripleStore(dataDir);
      boolean rerankerAvailable = adapter != null && adapter.isRerankerConfigured();
      return new GplJobCoordinator(
          clientSupplier, aiService, rerankerAvailable, tripleStore, onJobCompleted);
    } catch (Exception e) {
      log.warn("Failed to create GplJobCoordinator; GPL features unavailable", e);
      return null;
    }
  }
}
