/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
   * after the batch completes.
   *
   * @param newMentionsByType map of entity type name ("PERSON", "ORGANIZATION", "LOCATION") to
   *     list of raw mention forms
   * @return number of new cluster entries created
   */
  public int processBatch(Map<String, List<String>> newMentionsByType) throws SQLException {
    if (newMentionsByType == null || newMentionsByType.isEmpty()) {
      return 0;
    }

    int created = 0;
    for (var entry : newMentionsByType.entrySet()) {
      String typeName = entry.getKey();
      List<String> rawForms = entry.getValue();
      EntityType type = parseEntityType(typeName);
      if (type == null || rawForms == null || rawForms.isEmpty()) {
        continue;
      }

      created += processTypesBatch(type, typeName, rawForms);
    }

    if (created > 0) {
      rebuildSnapshot();
      log.info("Disambiguation batch: {} new entries, snapshot rebuilt", created);
    }

    return created;
  }

  private int processTypesBatch(EntityType type, String typeName, List<String> rawForms)
      throws SQLException {
    // Load existing entries for this type
    List<ClusterEntry> existing = store.loadAll();
    List<ClusterEntry> typeEntries = new ArrayList<>();
    for (ClusterEntry e : existing) {
      if (typeName.equals(e.entityType())) {
        typeEntries.add(e);
      }
    }

    // Build blocking indices from existing entries
    List<String> existingNormalized = new ArrayList<>();
    List<String> existingClusterIds = new ArrayList<>();
    Map<String, String> clusterIdToCanonical = new HashMap<>();
    Map<String, Integer> clusterSizes = new HashMap<>();

    for (ClusterEntry e : typeEntries) {
      existingNormalized.add(EntityNormalizer.normalize(e.rawForm(), type));
      existingClusterIds.add(e.clusterId());
      clusterIdToCanonical.put(e.clusterId(), e.canonicalForm());
      clusterSizes.merge(e.clusterId(), 1, Integer::sum);
    }

    // Token + phonetic blocking indices
    Map<String, Set<Integer>> tokenIndex = buildTokenIndex(existingNormalized);
    Map<String, Set<Integer>> phoneticIndex = buildPhoneticIndex(existingNormalized);

    // Build SoftTFIDF scorer with all known normalized forms
    Collection<String> corpus = new HashSet<>(existingNormalized);
    SoftTFIDF scorer = new SoftTFIDF(corpus, JW_INNER_THETA);
    double threshold = thresholdForType(type);

    // Track existing raw forms to skip duplicates
    Set<String> knownRawForms = new HashSet<>();
    for (ClusterEntry e : typeEntries) {
      knownRawForms.add(e.rawForm());
    }

    int created = 0;
    for (String rawForm : rawForms) {
      if (rawForm == null || rawForm.isBlank() || knownRawForms.contains(rawForm)) {
        continue;
      }

      String normalized = EntityNormalizer.normalize(rawForm, type);
      if (normalized.isBlank()) {
        continue;
      }

      // Find candidates via blocking
      Set<Integer> candidates = findCandidates(normalized, tokenIndex, phoneticIndex);

      // Score against candidates
      String bestClusterId = null;
      double bestScore = 0.0;

      for (int idx : candidates) {
        if (idx >= existingNormalized.size()) continue;
        double score = scorer.score(normalized, existingNormalized.get(idx));
        if (score >= threshold && score > bestScore) {
          String candidateClusterId = existingClusterIds.get(idx);
          int currentSize = clusterSizes.getOrDefault(candidateClusterId, 0);
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
        assignedCanonical = clusterIdToCanonical.get(bestClusterId);
        assignedClusterId = bestClusterId;
        store.upsert(rawForm, typeName, bestClusterId, assignedCanonical, bestScore);
        clusterSizes.merge(bestClusterId, 1, Integer::sum);
      } else {
        // Create singleton cluster
        assignedClusterId = UUID.randomUUID().toString();
        assignedCanonical = rawForm;
        store.upsert(rawForm, typeName, assignedClusterId, rawForm, 1.0);
        clusterSizes.put(assignedClusterId, 1);
      }

      // Add to indices for subsequent mentions in this batch
      int newIdx = existingNormalized.size();
      existingNormalized.add(normalized);
      existingClusterIds.add(assignedClusterId);
      clusterIdToCanonical.put(assignedClusterId, assignedCanonical);
      addToBlockingIndices(normalized, newIdx, tokenIndex, phoneticIndex);
      knownRawForms.add(rawForm);
      created++;
    }

    return created;
  }

  /** Rebuilds the volatile snapshot from the SQLite store. */
  private void rebuildSnapshot() throws SQLException {
    List<ClusterEntry> entries = store.loadAll();
    this.snapshot = EntityClusterSnapshot.fromEntries(entries);
  }

  // ==================== Blocking Index ====================

  private static Map<String, Set<Integer>> buildTokenIndex(List<String> normalizedForms) {
    Map<String, Set<Integer>> index = new HashMap<>();
    for (int i = 0; i < normalizedForms.size(); i++) {
      for (String token : EntityNormalizer.tokenize(normalizedForms.get(i))) {
        index.computeIfAbsent(token, k -> new HashSet<>()).add(i);
      }
    }
    return index;
  }

  private static Map<String, Set<Integer>> buildPhoneticIndex(List<String> normalizedForms) {
    Map<String, Set<Integer>> index = new HashMap<>();
    for (int i = 0; i < normalizedForms.size(); i++) {
      for (String token : EntityNormalizer.tokenize(normalizedForms.get(i))) {
        String code = METAPHONE.doubleMetaphone(token);
        if (code != null && !code.isEmpty()) {
          index.computeIfAbsent(code, k -> new HashSet<>()).add(i);
        }
      }
    }
    return index;
  }

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
  public void reset() throws SQLException {
    store.deleteAll();
    snapshot = EntityClusterSnapshot.EMPTY;
    initialized.set(false);
    log.info("DisambiguationService reset to empty state");
  }

  @Override
  public synchronized void close() throws IOException {
    initialized.set(false);
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
