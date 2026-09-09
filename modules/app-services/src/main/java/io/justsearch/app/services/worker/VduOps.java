/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import io.justsearch.ipc.CircuitBreakerOpenException;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VDU (Visual Document Understanding) RPC operations.
 *
 * <p>Handles pending counts, VDU result updates, processing marks, and recovery. All methods
 * delegate to the Worker via {@link IngestRpcExecutor}. Extracted from {@link
 * KnowledgeClient}.
 */
final class VduOps {
    private static final Logger log = LoggerFactory.getLogger(VduOps.class);

    private final IngestRpcExecutor rpc;
    private final java.util.function.Function<EngineContext, StatusResponse> statusSupplier;

    VduOps(IngestRpcExecutor rpc, java.util.function.Function<EngineContext, StatusResponse> statusSupplier) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    int countPendingEmbeddings(EngineContext engineContext) {
        try {
            StatusResponse response = statusSupplier.apply(engineContext);
            return response.getCore().getPendingEmbeddingCount();
        } catch (Exception e) {
            log.debug("Failed to count pending embeddings", e);
            return 0;
        }
    }

    int countPendingVdu(EngineContext engineContext) {
        try {
            StatusResponse response = statusSupplier.apply(engineContext);
            return response.getCore().getPendingVduCount();
        } catch (Exception e) {
            log.debug("Failed to count pending VDU", e);
            return 0;
        }
    }

    boolean updateVduResult(
            String docId,
            String extractedContent,
            VduUpdateOutcome outcome,
            String enrichment,
            int pageCount, EngineContext engineContext) {
        try {
            var builder =
                    UpdateVduResultRequest.newBuilder()
                            .setDocId(docId)
                            .setOutcome(outcome)
                            .setVduEnrichment(enrichment != null ? enrichment : "")
                            .setPageCount(pageCount);

            // Only set extracted_content when present (proto3 optional allows presence detection)
            if (extractedContent != null) {
                builder.setExtractedContent(extractedContent);
            }

            var request = builder.build();

            var response =
                    rpc.execute(
                            "updateVduResult",
                            KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION,
                            stub -> stub.updateVduResult(request), engineContext);

            if (!response.getSuccess()) {
                log.error("updateVduResult failed for {}: {}", docId, response.getError());
                return false;
            }

            log.debug("updateVduResult success for: {} (outcome={})", docId, outcome);
            return true;

        } catch (CircuitBreakerOpenException e) {
            log.debug("updateVduResult rejected by circuit breaker for {}", docId);
            return false;
        } catch (Exception e) {
            log.error("updateVduResult RPC failed for: {}", docId, e);
            return false;
        }
    }

    List<String> queryPendingVduDocIds(EngineContext engineContext) {
        return queryPendingVduDocIds(100, engineContext);
    }

    List<String> queryPendingVduDocIds(int limit, EngineContext engineContext) {
        try {
            var request = QueryPendingVduRequest.newBuilder().setLimit(limit).build();

            var response =
                    rpc.execute(
                            "queryPendingVdu",
                            KnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.queryPendingVdu(request), engineContext);

            log.debug(
                    "queryPendingVduDocIds: returned {} of {} pending",
                    response.getDocIdsCount(),
                    response.getTotalCount());
            return response.getDocIdsList();

        } catch (CircuitBreakerOpenException e) {
            log.debug("queryPendingVduDocIds rejected by circuit breaker");
            return List.of();
        } catch (Exception e) {
            log.error("queryPendingVdu RPC failed", e);
            return List.of();
        }
    }

    int markVduProcessing(String docId, int maxRetries, EngineContext engineContext) {
        try {
            var request =
                    MarkVduProcessingRequest.newBuilder()
                            .setDocId(docId)
                            .setMaxRetries(maxRetries)
                            .build();

            var response =
                    rpc.execute(
                            "markVduProcessing",
                            KnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.markVduProcessing(request), engineContext);

            if (!response.getSuccess()) {
                log.warn("markVduProcessing failed for {}: {}", docId, response.getError());
                return -1;
            }

            log.debug(
                    "markVduProcessing success for {}: retry {}", docId, response.getRetryCount());
            return response.getRetryCount();

        } catch (CircuitBreakerOpenException e) {
            log.debug("markVduProcessing rejected by circuit breaker for {}", docId);
            return -1;
        } catch (Exception e) {
            log.error("markVduProcessing RPC failed for: {}", docId, e);
            return -1;
        }
    }

    int recoverVduProcessing(EngineContext engineContext) {
        try {
            var request = RecoverVduProcessingRequest.newBuilder().build();

            var response =
                    rpc.execute(
                            "recoverVduProcessing",
                            KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION,
                            stub -> stub.recoverVduProcessing(request), engineContext);

            int recovered = response.getRecoveredCount();
            if (recovered > 0) {
                log.info("recoverVduProcessing: recovered {} stuck documents", recovered);
            } else {
                log.debug("recoverVduProcessing: no stuck documents found");
            }
            return recovered;

        } catch (CircuitBreakerOpenException e) {
            log.debug("recoverVduProcessing rejected by circuit breaker");
            return 0;
        } catch (Exception e) {
            log.error("recoverVduProcessing RPC failed", e);
            return 0;
        }
    }
}
