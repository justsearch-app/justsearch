/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity disambiguation service: clusters raw entity mentions by similarity.
 *
 * <p>Uses token-blocking + Double Metaphone for candidate generation, SoftTFIDF for scoring,
 * and greedy one-pass cluster assignment. Results are persisted to SQLite and exposed
 * as an immutable {@link EntityClusterSnapshot} via volatile swap for lock-free query-time reads.
 *
 * <p>Thread-safe. Follows the lazy-init pattern used by NerService.
 */
public final class DisambiguationService implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(DisambiguationService.class);

  // Per-type SoftTFIDF thresholds (from research doc U5)
  static final double PERSON_THRESHOLD = 0.85;
  static final double ORG_THRESHOLD = 0.80;
  static final double LOCATION_THRESHOLD = 0.90;
  // Design: greedy one-pass cluster assignment (not Union-Find transitive merge).
  // Transitive merging deferred — unnecessary for typical entity cardinalities (<100K).
  static final int MAX_CLUSTER_SIZE = 50;
  static final double JW_INNER_THETA = 0.9;

  private final EntityClusterStore store;
  private volatile EntityClusterSnapshot snapshot = EntityClusterSnapshot.EMPTY;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private boolean needsClose;
  private final Map<String, TypeIndex> typeIndices = new HashMap<>();
  private boolean indicesLoaded;
  private boolean snapshotDirty;
  private boolean deferSnapshot;
  private BooleanSupplier continueWork = () -> true;
  private Object completedRevision;
  private Object scanningRevision;
  private Object scanningReader;
  private int scanCursor = -1;
  private final Set<String> scannedSources = new HashSet<>();

  private static final class TypeIndex {
    final List<String> normalized = new ArrayList<>();
    final List<String> clusterIds = new ArrayList<>();
    final Map<String, String> canonicals = new HashMap<>();
    final Map<String, Integer> sizes = new HashMap<>();
    final Map<String, Set<Integer>> tokens = new HashMap<>();
    final Map<String, Set<Integer>> phonetics = new HashMap<>();
    final Set<String> rawForms = new HashSet<>();
    final Set<String> corpus = new HashSet<>();
    final SoftTFIDF scorer = new SoftTFIDF(List.of(), JW_INNER_THETA);

    void add(String raw, String form, String clusterId, String canonical) {
      addToBlockingIndices(form, normalized.size(), tokens, phonetics);
      normalized.add(form);
      clusterIds.add(clusterId);
      canonicals.put(clusterId, canonical);
      sizes.merge(clusterId, 1, Integer::sum);
      rawForms.add(raw);
    }
  }

  private void checkpoint() throws SQLException {
    if (!continueWork.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new SQLException("Disambiguation paused before completing the page");
    }
  }

  /** Applies the indexing loop's pacing and hold checks during clustering as well as harvesting. */
  public synchronized void replaceSources(
      Map<String, Map<String, List<String>>> documents, BooleanSupplier continuation)
      throws SQLException {
    continueWork = continuation;
    try {
      replaceSources(documents);
    } finally {
      continueWork = () -> true;
    }
  }

  /** Starts or resumes a pass; a reader change makes a physical document cursor invalid. */
  public synchronized boolean prepareReconciliation(Object revision, Object reader) {
    if (java.util.Objects.equals(completedRevision, revision)) return false;
    if (!java.util.Objects.equals(scanningRevision, revision) || scanningReader != reader) {
      scanningRevision = revision;
      scanningReader = reader;
      scanCursor = -1;
      scannedSources.clear();
    }
    return true;
  }

  public synchronized int reconciliationCursor() {
    return scanCursor;
  }

  /**
   * Writes ownership before clusters; retries repair any partially persisted cluster batch.
   * Snapshot publication waits for successful pass completion.
   */
  public synchronized void replaceSources(Map<String, Map<String, List<String>>> documents)
      throws SQLException {
    Map<String, List<String>> mentions = new java.util.LinkedHashMap<>();
    for (var document : documents.entrySet()) {
      checkpoint();
      store.replaceSource(document.getKey(), document.getValue());
      document.getValue().forEach((type, forms) ->
          mentions.computeIfAbsent(type, ignored -> new ArrayList<>()).addAll(forms));
    }
    deferSnapshot = true;
    try {
      processBatch(mentions);
    } finally {
      deferSnapshot = false;
    }
    scannedSources.addAll(documents.keySet());
  }

  /** A failed snapshot publication leaves both cursor and completion unacknowledged. */
  public synchronized void acknowledgeReconciliation(int cursor, boolean exhausted) throws SQLException {
    if (exhausted) {
      try {
        if (store.retainSources(scannedSources)) {
          indicesLoaded = false;
          snapshotDirty = true;
        }
      } catch (SQLException | RuntimeException failure) {
        indicesLoaded = false;
        snapshotDirty = true;
        throw failure;
      }
      publishSnapshotIfDirty();
      completedRevision = scanningRevision;
      scannedSources.clear();
    }
    scanCursor = cursor;
  }


  private static final DoubleMetaphone METAPHONE = new DoubleMetaphone();

  public DisambiguationService(Path dataDir) {
    this(new EntityClusterStore(dataDir.resolve("entity-clusters.db")));
  }

  DisambiguationService(EntityClusterStore store) {
    this.store = store;
  }

  /** Opens the SQLite store and builds the initial snapshot. */
  public synchronized void open() throws SQLException, IOException {
    if (needsClose) {
      throw new SQLException("DisambiguationService requires successful close before reopening");
    }
    needsClose = true;
    try {
      store.open();
      completedRevision = null;
      scanningRevision = null;
      scanningReader = null;
      indicesLoaded = false;
      rebuildSnapshot();
      log.info("DisambiguationService initialized, snapshot has {} types", snapshotTypeCount());
      initialized.set(true);
    } catch (SQLException | IOException | RuntimeException | Error failure) {
      try {
        close();
      } catch (IOException | RuntimeException | Error closeFailure) {
        if (closeFailure != failure) failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Returns true if the service has been initialized. */
  public boolean isAvailable() {
    return initialized.get();
  }

  /** Returns the current cluster snapshot for query-time use (volatile read, ~1-5ns). */
  public EntityClusterSnapshot snapshot() {
    return snapshot;
  }

  /**
   * Processes a batch of new raw entity mentions through the disambiguation pipeline.
   *
   * <p>For each entity type, normalizes mentions, finds candidates via blocking, scores with
   * SoftTFIDF, and assigns to existing clusters or creates singletons. Rebuilds the snapshot
   * after the batch completes if cluster membership changed or publication needs retrying.
   *
   * @param newMentionsByType map of entity type name ("PERSON", "ORGANIZATION", "LOCATION") to
   *     list of raw mention forms
   * @return number of new cluster entries created
   */
  public synchronized int processBatch(Map<String, List<String>> newMentionsByType) throws SQLException {
    if (newMentionsByType == null || newMentionsByType.isEmpty()) {
      publishSnapshotIfDirty();
      return 0;
    }

    loadIndices();
    int created = 0;
    try {
      for (var entry : newMentionsByType.entrySet()) {
        String typeName = entry.getKey();
        List<String> rawForms = entry.getValue();
        EntityType type = parseEntityType(typeName);
        if (type == null || rawForms == null || rawForms.isEmpty()) {
          continue;
        }

        created += processTypesBatch(type, typeName, rawForms);
      }
    } catch (SQLException | RuntimeException failure) {
      indicesLoaded = false;
      throw failure;
    }
    publishSnapshotIfDirty();
    if (created > 0) {
      log.info("Disambiguation batch: {} new entries", created);
    }

    return created;
  }

  private int processTypesBatch(EntityType type, String typeName, List<String> rawForms)
      throws SQLException {
    TypeIndex index = typeIndices.computeIfAbsent(typeName, ignored -> new TypeIndex());
    double threshold = thresholdForType(type);
    List<String> addedForms = new ArrayList<>();

    int created = 0;
    for (String rawForm : rawForms) {
      checkpoint();
      if (rawForm == null || rawForm.isBlank() || index.rawForms.contains(rawForm)) {
        continue;
      }

      String normalized = EntityNormalizer.normalize(rawForm, type);
      if (normalized.isBlank()) {
        continue;
      }

      // Find candidates via blocking
      Set<Integer> candidates = findCandidates(normalized, index.tokens, index.phonetics);

      // Score against candidates
      String bestClusterId = null;
      double bestScore = 0.0;

      for (int idx : candidates) {
        checkpoint();
        double score = index.scorer.score(normalized, index.normalized.get(idx));
        if (score >= threshold && score > bestScore) {
          String candidateClusterId = index.clusterIds.get(idx);
          int currentSize = index.sizes.getOrDefault(candidateClusterId, 0);
          if (currentSize < MAX_CLUSTER_SIZE) {
            bestScore = score;
            bestClusterId = candidateClusterId;
          }
        }
      }

      String assignedCanonical;
      String assignedClusterId;
      if (bestClusterId != null) {
        // Assign to existing cluster
        assignedCanonical = index.canonicals.get(bestClusterId);
        assignedClusterId = bestClusterId;
        snapshotDirty = true;
        store.upsert(rawForm, typeName, bestClusterId, assignedCanonical, bestScore);
      } else {
        // Create singleton cluster
        assignedClusterId = UUID.randomUUID().toString();
        assignedCanonical = rawForm;
        snapshotDirty = true;
        store.upsert(rawForm, typeName, assignedClusterId, rawForm, 1.0);
      }

      index.add(rawForm, normalized, assignedClusterId, assignedCanonical);
      if (index.corpus.add(normalized)) addedForms.add(normalized);
      created++;
    }

    for (String form : addedForms) index.scorer.addMention(form);
    return created;
  }

  private void loadIndices() throws SQLException {
    if (indicesLoaded) return;
    typeIndices.clear();
    for (ClusterEntry entry : store.loadAll()) {
      checkpoint();
      EntityType type = parseEntityType(entry.entityType());
      if (type == null) continue;
      TypeIndex index = typeIndices.computeIfAbsent(entry.entityType(), ignored -> new TypeIndex());
      String normalized = EntityNormalizer.normalize(entry.rawForm(), type);
      index.add(entry.rawForm(), normalized, entry.clusterId(), entry.canonicalForm());
      if (index.corpus.add(normalized)) index.scorer.addMention(normalized);
    }
    indicesLoaded = true;
  }

  private void publishSnapshotIfDirty() throws SQLException {
    if (snapshotDirty && !deferSnapshot) rebuildSnapshot();
  }

  /** Rebuilds the volatile snapshot from the SQLite store. */
  private void rebuildSnapshot() throws SQLException {
    List<ClusterEntry> entries = store.loadAll();
    this.snapshot = EntityClusterSnapshot.fromEntries(entries);
    snapshotDirty = false;
  }

  // ==================== Blocking Index ====================

  private static Set<Integer> findCandidates(
      String normalized,
      Map<String, Set<Integer>> tokenIndex,
      Map<String, Set<Integer>> phoneticIndex) {
    Set<Integer> candidates = new HashSet<>();
    for (String token : EntityNormalizer.tokenize(normalized)) {
      Set<Integer> tokenHits = tokenIndex.get(token);
      if (tokenHits != null) candidates.addAll(tokenHits);

      String code = METAPHONE.doubleMetaphone(token);
      if (code != null && !code.isEmpty()) {
        Set<Integer> phoneticHits = phoneticIndex.get(code);
        if (phoneticHits != null) candidates.addAll(phoneticHits);
      }
    }
    return candidates;
  }

  private static void addToBlockingIndices(
      String normalized,
      int idx,
      Map<String, Set<Integer>> tokenIndex,
      Map<String, Set<Integer>> phoneticIndex) {
    for (String token : EntityNormalizer.tokenize(normalized)) {
      tokenIndex.computeIfAbsent(token, k -> new HashSet<>()).add(idx);
      String code = METAPHONE.doubleMetaphone(token);
      if (code != null && !code.isEmpty()) {
        phoneticIndex.computeIfAbsent(code, k -> new HashSet<>()).add(idx);
      }
    }
  }

  // ==================== Helpers ====================

  private static double thresholdForType(EntityType type) {
    return switch (type) {
      case PERSON -> PERSON_THRESHOLD;
      case ORGANIZATION -> ORG_THRESHOLD;
      case LOCATION -> LOCATION_THRESHOLD;
    };
  }

  private static EntityType parseEntityType(String name) {
    try {
      return EntityType.valueOf(name);
    } catch (IllegalArgumentException e) {
      log.warn("Unknown entity type: {}", name);
      return null;
    }
  }

  private int snapshotTypeCount() {
    return snapshot.isEmpty() ? 0 : 1; // simplified; non-empty means at least one type
  }

  /** Clears all cluster data and resets to empty state. Used by profiling reset. */
  public synchronized void reset() throws SQLException {
    completedRevision = null;
    scanningRevision = null;
    scanningReader = null;
    scanCursor = -1;
    scannedSources.clear();
    indicesLoaded = false;
    try {
      store.deleteAll();
    } catch (SQLException | RuntimeException | Error failure) {
      snapshotDirty = true;
      throw failure;
    }
    typeIndices.clear();
    snapshotDirty = false;
    snapshot = EntityClusterSnapshot.EMPTY;
    log.info("DisambiguationService reset to empty state");
  }

  @Override
  public synchronized void close() throws IOException {
    initialized.set(false);
    indicesLoaded = false;
    typeIndices.clear();
    snapshot = EntityClusterSnapshot.EMPTY;
    try {
      store.close();
    } catch (IOException | RuntimeException | Error failure) {
      needsClose = true;
      throw failure;
    }
    needsClose = false;
  }
}
