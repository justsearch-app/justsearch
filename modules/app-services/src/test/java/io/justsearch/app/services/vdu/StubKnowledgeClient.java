package io.justsearch.app.services.vdu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub knowledge client for unit testing VDU components.
 *
 * <p>Answers the VDU calls in-process, without constructing a real client.
 * Tracks method invocations for verification.
 */
public class StubKnowledgeClient {

    // Configurable return values
    private int pendingVduCount = 0;
    private int pendingEmbeddingsCount = 0;
    private List<String> pendingVduDocIds = new ArrayList<>();
    private int recoveredCount = 0;
    private boolean updateVduResultSuccess = true;
    private int markVduProcessingRetryCount = 1;  // -1 = max retries exceeded

    // Tracking for verification
    private int recoverVduProcessingCalls = 0;
    private int countPendingVduCalls = 0;
    private int countPendingEmbeddingsCalls = 0;
    private int queryPendingVduDocIdsCalls = 0;
    private final List<String> markedProcessingDocIds = new ArrayList<>();
    private final Map<String, VduUpdateRecord> vduUpdates = new HashMap<>();

    // ========== Simulated Methods ==========

    public int recoverVduProcessing() {
        recoverVduProcessingCalls++;
        return recoveredCount;
    }

    public int countPendingVdu() {
        countPendingVduCalls++;
        return pendingVduCount;
    }

    public int countPendingEmbeddings() {
        countPendingEmbeddingsCalls++;
        return pendingEmbeddingsCount;
    }

    public List<String> queryPendingVduDocIds() {
        queryPendingVduDocIdsCalls++;
        return new ArrayList<>(pendingVduDocIds);
    }

    public int markVduProcessing(String docId, int maxRetries) {
        markedProcessingDocIds.add(docId);
        return markVduProcessingRetryCount;
    }

    public boolean updateVduResult(String docId, String extractedContent,
                                   String vduStatus, String enrichment, int pageCount) {
        vduUpdates.put(docId, new VduUpdateRecord(extractedContent, vduStatus, enrichment, pageCount));
        return updateVduResultSuccess;
    }

    // ========== Configuration Methods ==========

    public StubKnowledgeClient withPendingVduCount(int count) {
        this.pendingVduCount = count;
        return this;
    }

    public StubKnowledgeClient withPendingEmbeddingsCount(int count) {
        this.pendingEmbeddingsCount = count;
        return this;
    }

    public StubKnowledgeClient withPendingVduDocIds(List<String> docIds) {
        this.pendingVduDocIds = new ArrayList<>(docIds);
        return this;
    }

    public StubKnowledgeClient withRecoveredCount(int count) {
        this.recoveredCount = count;
        return this;
    }

    public StubKnowledgeClient withUpdateVduResultSuccess(boolean success) {
        this.updateVduResultSuccess = success;
        return this;
    }

    public StubKnowledgeClient withMarkVduProcessingRetryCount(int count) {
        this.markVduProcessingRetryCount = count;
        return this;
    }

    // ========== Verification Methods ==========

    public int getRecoverVduProcessingCalls() {
        return recoverVduProcessingCalls;
    }

    public int getCountPendingVduCalls() {
        return countPendingVduCalls;
    }

    public int getCountPendingEmbeddingsCalls() {
        return countPendingEmbeddingsCalls;
    }

    public int getQueryPendingVduDocIdsCalls() {
        return queryPendingVduDocIdsCalls;
    }

    public List<String> getMarkedProcessingDocIds() {
        return new ArrayList<>(markedProcessingDocIds);
    }

    public Map<String, VduUpdateRecord> getVduUpdates() {
        return new HashMap<>(vduUpdates);
    }

    public VduUpdateRecord getVduUpdate(String docId) {
        return vduUpdates.get(docId);
    }

    public void reset() {
        pendingVduCount = 0;
        pendingEmbeddingsCount = 0;
        pendingVduDocIds.clear();
        recoveredCount = 0;
        updateVduResultSuccess = true;
        markVduProcessingRetryCount = 1;
        recoverVduProcessingCalls = 0;
        countPendingVduCalls = 0;
        countPendingEmbeddingsCalls = 0;
        queryPendingVduDocIdsCalls = 0;
        markedProcessingDocIds.clear();
        vduUpdates.clear();
    }

    /**
     * Record of a VDU update call for verification.
     */
    public record VduUpdateRecord(
        String extractedContent,
        String vduStatus,
        String enrichment,
        int pageCount
    ) {}
}
