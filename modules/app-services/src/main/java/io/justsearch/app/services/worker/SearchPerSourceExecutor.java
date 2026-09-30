/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineFutures;
import io.justsearch.core.execution.EngineTaskGroup;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns bounded per-source search fanout and its exact admitted-work lifetime. */
public final class SearchPerSourceExecutor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SearchPerSourceExecutor.class);
  private static final int CHILD_WAIT_SECONDS = 10;

  private final EngineExecutorRegistry.Registration foregroundRegistration;
  private final EngineExecutorRegistry.Registration backgroundRegistration;
  private final EngineAdmissionService admission;
  private boolean closed;

  /** Creates stable foreground and background virtual executor registrations. */
  public SearchPerSourceExecutor(
      EngineExecutorRegistry executors, EngineAdmissionService admission) {
    Objects.requireNonNull(executors, "executors");
    this.admission = Objects.requireNonNull(admission, "admission");
    EngineExecutorRegistry.Registration foreground = null;
    try {
      int maxInstances = executors.maxConcurrentWork();
      foreground = Objects.requireNonNull(executors.register(EngineExecutorSpec.virtual(
          "head.search-per-source.foreground", EngineExecutorSpec.Kind.FOREGROUND, maxInstances)));
      EngineExecutorRegistry.Registration background = Objects.requireNonNull(
          executors.register(EngineExecutorSpec.virtual(
              "head.search-per-source.background", EngineExecutorSpec.Kind.BACKGROUND, maxInstances)),
          "background registration");
      this.foregroundRegistration = foreground;
      this.backgroundRegistration = background;
    } catch (RuntimeException | Error failure) {
      if (foreground != null) {
        try {
          foreground.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  /** Executes one filtered search per source and round-robin merges the responses. */
  public SearchResponse execute(
      KnowledgeClient client,
      SearchRequest baseReq,
      List<String> sources,
      int totalLimit,
      EngineContext engineContext) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(baseReq, "baseReq");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(engineContext, "engineContext");

    EngineWorkHandle work = admission.attach(engineContext);
    List<EngineWorkHandle.Registration> cancellationRegistrations = new ArrayList<>();
    Throwable bodyFailure = null;
    try {
      EngineExecutorRegistry.Registration registration = registrationFor(work.context().urgency());
      int perSourceLimit = Math.max(1, (int) Math.ceil((double) totalLimit / sources.size()));
      List<SearchResponse> responses = new ArrayList<>();
      try (EngineTaskGroup group = EngineTaskGroup.open(
          () -> io.opentelemetry.context.Context.taskWrapping(registration.openVirtual()),
          () -> work.retain()::close)) {
        List<CompletableFuture<SearchResponse>> futures = new ArrayList<>();
        for (String source : sources) {
          SearchRequest perSourceReq = baseReq.toBuilder()
              .setLimit(perSourceLimit)
              .setFilters(baseReq.getFilters().toBuilder()
                  .addMetaSource(source.toLowerCase(Locale.ROOT))
                  .build())
              .build();
          CompletableFuture<SearchResponse> future = group.submit(
              () -> client.search(perSourceReq, work.context()));
          futures.add(future);
          cancellationRegistrations.add(work.onCancel(reason -> future.cancel(true)));
        }

        for (CompletableFuture<SearchResponse> future : futures) {
          try {
            responses.add(future.get(CHILD_WAIT_SECONDS, TimeUnit.SECONDS));
          } catch (TimeoutException timeout) {
            try {
              group.close();
            } catch (RuntimeException | Error cleanupFailure) {
              timeout.addSuppressed(cleanupFailure);
            }
            throw new CompletionException(timeout);
          } catch (Exception failure) {
            EngineFutures.rethrowExecutorRefusal(failure);
            EngineFutures.rethrowCancellation(failure);
            rethrowFatal(failure);
            log.debug("385: Per-source retrieval failed for one source: {}", failure.getMessage());
          }
        }

        if (responses.isEmpty()) {
          log.debug("385: All per-source calls failed, falling back to unfiltered retrieval");
          return client.search(baseReq, work.context());
        }
        return mergeSearchResponses(
            responses, totalLimit, baseReq, request -> client.search(request, work.context()));
      }
    } catch (RuntimeException | Error failure) {
      bodyFailure = failure;
      throw failure;
    } finally {
      Throwable cleanupFailure = null;
      for (EngineWorkHandle.Registration registration : cancellationRegistrations) {
        try {
          registration.close();
        } catch (RuntimeException | Error registrationFailure) {
          cleanupFailure = aggregate(cleanupFailure, registrationFailure);
        }
      }
      try {
        work.close();
      } catch (RuntimeException | Error closeFailure) {
        cleanupFailure = aggregate(cleanupFailure, closeFailure);
      }
      if (cleanupFailure != null) {
        if (bodyFailure != null) bodyFailure.addSuppressed(cleanupFailure);
        else rethrow(cleanupFailure);
      }
    }
  }

  private static void rethrowFatal(Throwable failure) {
    Throwable cause = failure;
    while (cause instanceof CompletionException || cause instanceof ExecutionException) {
      cause = cause.getCause();
    }
    if (cause instanceof Error error) throw error;
  }

  private static Throwable aggregate(Throwable aggregate, Throwable failure) {
    if (aggregate == null) return failure;
    if (aggregate != failure) aggregate.addSuppressed(failure);
    return aggregate;
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
    if (failure instanceof Error errorFailure) throw errorFailure;
  }

  private EngineExecutorRegistry.Registration registrationFor(EngineContext.Urgency urgency) {
    return urgency == EngineContext.Urgency.FOREGROUND
        ? foregroundRegistration : backgroundRegistration;
  }

  /** Merges source responses, with optional unfiltered backfill. */
  static SearchResponse mergeSearchResponses(
      List<SearchResponse> responses,
      int totalLimit,
      SearchRequest backfillReq,
      java.util.function.Function<SearchRequest, SearchResponse> backfillFn) {
    List<List<SearchResult>> perSourceHits = new ArrayList<>();
    long totalHits = 0;
    long matchCount = 0;
    long maxTookMs = 0;
    for (SearchResponse r : responses) {
      perSourceHits.add(new ArrayList<>(r.getResultsList()));
      totalHits += r.getTotalHits();
      matchCount += r.getMatchCount();
      maxTookMs = Math.max(maxTookMs, r.getTookMs());
    }

    List<SearchResult> interleaved = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int maxRank = perSourceHits.stream().mapToInt(List::size).max().orElse(0);
    for (int rank = 0; rank < maxRank && interleaved.size() < totalLimit; rank++) {
      for (var hits : perSourceHits) {
        if (rank < hits.size()) {
          SearchResult hit = hits.get(rank);
          if (seen.add(hit.getId())) {
            interleaved.add(hit);
            if (interleaved.size() >= totalLimit) break;
          }
        }
      }
    }

    if (interleaved.size() < totalLimit) {
      log.debug("385: Per-source retrieval returned {} of {} requested, backfilling",
          interleaved.size(), totalLimit);
      try {
        SearchResponse backfill = backfillFn.apply(backfillReq);
        for (SearchResult hit : backfill.getResultsList()) {
          if (interleaved.size() >= totalLimit) break;
          if (seen.add(hit.getId())) interleaved.add(hit);
        }
        totalHits = Math.max(totalHits, backfill.getTotalHits());
        matchCount = Math.max(matchCount, backfill.getMatchCount());
      } catch (Exception failure) {
        EngineFutures.rethrowExecutorRefusal(failure);
        EngineFutures.rethrowCancellation(failure);
        rethrowFatal(failure);
        log.debug("385: Backfill retrieval failed: {}", failure.getMessage());
      }
    }

    boolean anySpladeExecuted = false;
    boolean anyVectorBlocked = false;
    boolean anyHybridFallback = false;
    String vectorBlockedReason = "";
    String hybridFallbackReason = "";
    String spladeSkipReason = "";
    for (SearchResponse r : responses) {
      if (!r.hasSearchTrace() || !r.getSearchTrace().hasDegradation()) continue;
      io.justsearch.ipc.TraceDegradation d = r.getSearchTrace().getDegradation();
      anySpladeExecuted |= d.getSpladeExecuted();
      if (d.getVectorBlocked()) {
        anyVectorBlocked = true;
        if (!d.getVectorBlockedReason().isBlank()) vectorBlockedReason = d.getVectorBlockedReason();
      }
      if (d.getHybridFallback()) {
        anyHybridFallback = true;
        if (!d.getHybridFallbackReason().isBlank()) hybridFallbackReason = d.getHybridFallbackReason();
      }
      if (!d.getSpladeSkipReason().isBlank()) spladeSkipReason = d.getSpladeSkipReason();
    }

    SearchResponse template = responses.getFirst();
    SearchResponse.Builder merged = template.toBuilder()
        .clearResults()
        .setTotalHits(totalHits)
        .setMatchCount(matchCount)
        .setTookMs(maxTookMs)
        .clearFacets();
    if (template.hasSearchTrace()) {
      io.justsearch.ipc.SearchTrace.Builder mt = template.getSearchTrace().toBuilder();
      mt.setDegradation(
          io.justsearch.ipc.TraceDegradation.newBuilder()
              .setSpladeExecuted(anySpladeExecuted)
              .setVectorBlocked(anyVectorBlocked)
              .setVectorBlockedReason(vectorBlockedReason)
              .setHybridFallback(anyHybridFallback)
              .setHybridFallbackReason(hybridFallbackReason)
              .setSpladeSkipReason(spladeSkipReason)
              .build());
      mt.clearQpp();
      merged.setSearchTrace(mt.build());
    }
    for (SearchResult hit : interleaved) merged.addResults(hit);
    return merged.build();
  }

  /** Closes both stable registrations, attempting the second even if the first fails. */
  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    Throwable failure = null;
    try {
      foregroundRegistration.close();
    } catch (RuntimeException | Error closeFailure) {
      failure = closeFailure;
    }
    try {
      backgroundRegistration.close();
    } catch (RuntimeException | Error closeFailure) {
      if (failure == null) failure = closeFailure;
      else if (failure != closeFailure) failure.addSuppressed(closeFailure);
    }
    if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
    if (failure instanceof Error errorFailure) throw errorFailure;
  }
}
