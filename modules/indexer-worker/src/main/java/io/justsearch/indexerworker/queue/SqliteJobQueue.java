/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryLadder;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed durable job queue for file ingestion.
 *
 * <p>This queue persists pending file paths to disk, ensuring durability across
 * worker restarts. All database access is serialized using a lock to prevent
 * concurrent write conflicts.
 *
 * <p>Job states:
 * <ul>
 *   <li>PENDING - Awaiting processing</li>
 *   <li>PROCESSING - Currently being indexed</li>
 *   <li>DONE - Successfully indexed</li>
 *   <li>FAILED - Failed after max attempts</li>
 * </ul>
 */
public final class SqliteJobQueue implements SwitchBufferCapableQueue {
  private static final Logger log = LoggerFactory.getLogger(SqliteJobQueue.class);

  /**
   * Maximum untyped-failure attempts before marking a job {@code FAILED}.
   *
   * <p>Tempdoc 885 item 21d: ONE home. {@code KnowledgeServer} used to pass a bare literal {@code 3}
   * at the single construction site, so the cap existed in two places that agreed by coincidence.
   * It is also no longer the terminal signal for a classified transient outcome — see {@link
   * #markFailedWithOutcome}; it governs only the untyped {@link #markFailed(Path, String)} path.
   */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** Job states on the {@code jobs} table. The vocabulary the change-feed projects onto the wire. */
  static final String STATE_PENDING = "PENDING";

  private java.util.function.Supplier<List<IngestCollectionPolicy.RootBinding>> declaredCollectionRoots = List::of;

  @Override
  public void useDeclaredCollectionRoots(
      java.util.function.Supplier<List<IngestCollectionPolicy.RootBinding>> roots) {
    declaredCollectionRoots = Objects.requireNonNull(roots);
  }

  private java.util.function.Supplier<List<IngestCollectionPolicy.RootBinding>> frozenCollectionRoots = List::of;

  /** In-memory recorded-plan snapshot, owned and retired by the generation publisher. */
  public void useFrozenCollectionRoots(
      java.util.function.Supplier<List<IngestCollectionPolicy.RootBinding>> roots) {
    frozenCollectionRoots = Objects.requireNonNull(roots);
  }

  private static IngestCollectionPolicy.RootBinding collectionOwner(Path path,
      List<IngestCollectionPolicy.RootBinding> frozen, List<IngestCollectionPolicy.RootBinding> declared) {
    var owner = IngestCollectionPolicy.owningRoot(path, frozen);
    return owner == null ? IngestCollectionPolicy.owningRoot(path, declared) : owner;
  }

  /** Repair pre-fix NULL admissions before any boot writer or replay can observe them. */
  public void recoverLegacyCollections(String generation,
      List<IngestCollectionPolicy.RootBinding> declared,
      io.justsearch.app.api.operations.RecordedRootPlan plan) throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      if (!activeClaims.isEmpty()) throw new IllegalStateException("Collection recovery requires a stopped writer");
      var frozen = plan == null ? List.<IngestCollectionPolicy.RootBinding>of() : plan.roots().stream()
          .map(root -> new IngestCollectionPolicy.RootBinding(root.path(), root.collection())).toList();
      inTransaction(() -> {
        var receipts = switchBufferOps.listAllStrict();
        // A raw path does not record a NULL hint: it omits collection evidence altogether.
        // Its original namespace (or uncertainty) remains owned by the existing legacy replay.
        var unattributed = new HashSet<String>();
        for (var op : receipts) {
          if (!"UPSERT".equals(op.op()) || !(op.generation().isBlank() || generation.equals(op.generation()))) continue;
          String path = SwitchBufferUpsert.decode(op.payload()).path();
          if (op.payload().stripLeading().startsWith("{")) unattributed.remove(path);
          else unattributed.add(path);
        }
        var owners = new java.util.LinkedHashMap<String, IngestCollectionPolicy.RootBinding>();
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT path,unit_revision FROM jobs WHERE collection IS NULL AND walk_seen_epoch IS NULL")) {
          while (rows.next()) {
            String path = rows.getString(1);
            String unit = rows.getString(2);
            if (unattributed.contains(path) || RootOrigin.fromUnit(unit) != null
                || (unit != null && unit.startsWith("release030/"))) continue;
            var owner = collectionOwner(Path.of(path), frozen, declared);
            if (owner != null) owners.put(path, owner);
          }
        }
        for (var op : receipts) {
          if (!"UPSERT".equals(op.op()) || !(op.generation().isBlank() || generation.equals(op.generation()))) continue;
          if (!op.payload().stripLeading().startsWith("{")) continue;
          var file = SwitchBufferUpsert.decode(op.payload());
          if (file.collection() != null) continue;
          if (receipts.stream().anyMatch(later -> later.acceptedOrder() > op.acceptedOrder()
              && later.key().equals(op.key()) && "UPSERT".equals(later.op())
              && SwitchBufferUpsert.decode(later.payload()).collection() != null)) continue;
          var owner = collectionOwner(Path.of(file.path()), frozen, declared);
          // Unknown ownership alone does not prove removal. A later scoped deletion is
          // production Remove's durable evidence; never turn an unrelated NULL admission into DELETE.
          boolean removed = owner == null && receipts.stream().anyMatch(later ->
              later.acceptedOrder() > op.acceptedOrder()
                  && (later.generation().isBlank() || generation.equals(later.generation()))
                  && "DELETE_PREFIX".equals(later.op())
                  && PathPrefixScope.decode(later.payload()).contains(file.path()));
          if (owner == null && !removed) continue;
          String label = owner == null ? null : owner.collection();
          // A later reserved admission retains its search exclusions and receipt authority.
          var current = watcherRowLocked(file.path());
          if (current != null && IngestCollectionPolicy.isReserved(current.get("collection"))) continue;
          if (owner != null && label == null) continue; // A frozen default is already exact.
          String kind = owner == null ? "DELETE" : "UPSERT";
          String payload = owner == null
              ? new SwitchBufferDelete(file.path(), SwitchBufferDelete.Origin.EXPLICIT, null).encode()
              : new SwitchBufferUpsert(file.path(), label, file.provenance(), file.unitRevision(),
                  file.sourceSha256(), file.ingestionRoot()).encode();
          // Preserve accepted_order: repair must not move an admission past a later deletion.
          try (var update = connection.prepareStatement(
              "UPDATE switch_buffer SET op=?,payload=?,revision=? WHERE generation=? AND key=? AND revision=?")) {
            update.setString(1, kind); update.setString(2, payload);
            update.setString(3, UUID.randomUUID().toString()); update.setString(4, op.generation());
            update.setString(5, op.key()); update.setString(6, op.revision());
            if (update.executeUpdate() != 1) throw new SQLException("Legacy receipt changed during recovery");
          }
        }
        for (var entry : owners.entrySet()) {
          if (entry.getValue().collection() == null) continue;
          try (var update = connection.prepareStatement(
              "UPDATE jobs SET collection=?,state='PENDING',content_hash=NULL WHERE path=? AND collection IS NULL")) {
            update.setString(1, entry.getValue().collection()); update.setString(2, entry.getKey());
            update.executeUpdate();
          }
        }
        claimCursor = null;
        return null;
      });
    } finally {
      unlockAfterChanges();
    }
  }

  static final String STATE_PROCESSING = "PROCESSING";

  static final String STATE_DONE = "DONE";

  static final String STATE_FAILED = "FAILED";

  /**
   * Tempdoc 885 item 21b — a transient failure run that outlived the seven-day retry window. A
   * VISIBLE terminal state, distinct from {@code FAILED} (which means "this file cannot be
   * parsed"): {@code RETRY_EXHAUSTED} means "we kept trying for a week and never got to read it".
   * Reset by anything that re-enqueues the path (a rescan, a watcher event on an mtime/size
   * change), because re-admission resets the prior retry run.
   */
  static final String STATE_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

  /**
   * #758: a row being retried because its source exists but cannot be read
   * ({@code RETRY_UNTIL_AVAILABLE} with a source-unreadable reason). It never becomes terminal, so
   * the failed-files surface lists it explicitly — as "indexed, source unreadable" when the index
   * holds its content — distinct from a file that is missing.
   */
  static final String SOURCE_UNREADABLE_RETRY =
      "(state IN ('PENDING', 'PROCESSING') AND last_reason_code IN ('"
          + io.justsearch.indexerworker.ingest.IngestionReasonCodes.SOURCE_UNREADABLE + "', '"
          + io.justsearch.indexerworker.ingest.IngestionReasonCodes.INDEXED_SOURCE_UNREADABLE
          + "'))";

  /** Rows the failed-files surface lists: terminal failures plus unreadable-source retries. */
  static final String ON_FAILED_SURFACE =
      "state IN ('FAILED', 'RETRY_EXHAUSTED') OR " + SOURCE_UNREADABLE_RETRY;

  /** The state the failed-files surface shows: the reason code for an unreadable-source retry. */
  static final String FAILED_SURFACE_STATE =
      "CASE WHEN " + SOURCE_UNREADABLE_RETRY + " THEN last_reason_code ELSE state END";

  /** SQLite busy timeout in milliseconds. */
  private static final int BUSY_TIMEOUT_MS = 5000;

  /**
   * Minimum free disk space (50 MB) required to accept new enqueues. Below this threshold the
   * queue refuses enqueue() to prevent SQLite write-during-full-disk corruption. Failure mode
   * surfaces today as a SQLITE_IOERR_FULL caught and logged, but pre-checking is cheaper and
   * fail-closed.
   */
  private static final long MIN_FREE_DISK_BYTES = 50L * 1024 * 1024;

  @Override
  public void returnUnfinishedClaims(java.util.Collection<IndexJob> claims) {
    if (claims == null || claims.isEmpty()) return;
    lockTimed();
    try {
      ensureOpen();
      List<IndexJob> issued = claims.stream().filter(this::isIssuedClaim).toList();
      inTransaction(() -> {
        try (var update = connection.prepareStatement("UPDATE jobs SET state = 'PENDING', last_updated = ? "
            + "WHERE path = ? AND state = 'PROCESSING' AND scan_id IS ? AND unit_revision IS ?")) {
          long now = System.currentTimeMillis();
          for (IndexJob claim : issued) {
            String path = normalizePath(claim.path());
            if (ownsClaim(claim) && closedUnsuccessfulMember(path)) {
              skipRecordedMember(path, IngestionOutcomeClass.SKIPPED_POLICY, "ENUMERATION_STOPPED");
              continue;
            }
            update.setLong(1, now); update.setString(2, path);
            update.setString(3, claim.scanId()); update.setString(4, claim.unitRevision());
            executeMutation(update::executeUpdate);
          }
        }
        return null;
      });
      issued.forEach(this::releaseClaim);
      // Returning an obsolete owner can also expose its already-PENDING replacement.
      if (!issued.isEmpty()) {
        claimCursor = null;
        claimOrderVersion++;
      }
    } catch (SQLException failure) {
      throw new OutcomeWriteException("Could not return unfinished processing claims", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public WalkProgress beginRecordedWalk(String operationKey, String planHash, boolean createIfMissing) {
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.begin(connection, operationKey, planHash, createIfMissing, false), true);
  }

  @Override
  public WalkProgress beginCapturedWalk(String operationKey, String planHash, boolean createIfMissing) {
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.begin(connection, operationKey, planHash, createIfMissing, true), true);
  }

  @Override
  public java.util.Optional<CapturedWalkSettlement> capturedWalkSettlement(String operationKey) {
    return accessRecordedWalk(() -> SqliteCapturedWalkOps.settlement(connection, operationKey), false);
  }

  @Override
  public int enqueueRecordedEntries(String operationKey, long epoch,
      List<EnqueueEntry> entries, String collection) {
    if (!hasSufficientDiskSpace()) throw new IllegalStateException("Recorded queue admission is unavailable");
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.enqueueRecorded(
        connection, operationKey, epoch, entries, collection, System.currentTimeMillis()), true);
  }

  /**
   * Accepts a finite recorded walk member set and its candidate replay obligations atomically.
   *
   * <p>The recorded admission helper remains the authority for membership, source hashes and walk
   * revision. The UPSERT rows are written with the same connection before the surrounding queue
   * transaction commits, so a journal failure rolls back both the jobs rows and walk projection.
   */
  @Override
  public int enqueueRecordedEntriesAndBufferForGeneration(
      String generation, String operationKey, long epoch,
      List<EnqueueEntry> entries, String collection) {
    if (generation == null || generation.isBlank()) {
      throw new IllegalArgumentException("Generation must be non-blank");
    }
    if (!hasSufficientDiskSpace()) {
      throw new IllegalStateException("Recorded queue admission is unavailable");
    }
    List<EnqueueEntry> witnessedEntries = new ArrayList<>(entries.size());
    try {
      for (EnqueueEntry entry : entries) {
        String hash = entry.plannedSourceSha256() == null
            ? io.justsearch.indexerworker.loop.SourceContentHash.sha256(entry.path())
            : entry.plannedSourceSha256();
        witnessedEntries.add(new EnqueueEntry(
            entry.path(), entry.sizeBytes(), entry.provenance(), hash, entry.ingestionRoot()));
      }
    } catch (IOException | RuntimeException unreadable) {
      throw new IllegalStateException(
          "Recorded candidate admission requires exact source bytes", unreadable);
    }
    return accessRecordedWalk(() -> {
      int accepted = SqliteIngestionWalkOps.enqueueRecorded(
          connection, operationKey, epoch, witnessedEntries, collection, System.currentTimeMillis());
      if (accepted > 0) {
        bufferRecordedFileAdmissionsInTransaction(generation, operationKey, epoch, witnessedEntries);
      }
      return accepted;
    }, true);
  }

  private void bufferRecordedFileAdmissionsInTransaction(
      String generation, String operationKey, long epoch, List<EnqueueEntry> entries)
      throws SQLException {
    var paths = new java.util.LinkedHashSet<String>();
    for (EnqueueEntry entry : entries) {
      paths.add(normalizePath(Objects.requireNonNull(entry, "entry").path()));
    }
    for (String path : paths) {
      String declaredCollection;
      String originator;
      String transport;
      String revision;
      String sourceHash;
      String ingestionRoot;
      try (var query = connection.prepareStatement(
          "SELECT collection, originator, transport, scan_id, walk_seen_epoch, "
              + "unit_revision, planned_source_sha256, ingestion_root "
              + "FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (var row = query.executeQuery()) {
          if (!row.next()) throw new SQLException("Recorded admission disappeared: " + path);
          declaredCollection = row.getString("collection");
          originator = row.getString("originator");
          transport = row.getString("transport");
          if (!Objects.equals(operationKey, row.getString("scan_id"))) {
            throw new SQLException("Recorded admission has a different operation key: " + path);
          }
          long memberEpoch = row.getLong("walk_seen_epoch");
          if (row.wasNull() || memberEpoch != epoch) {
            throw new SQLException("Recorded admission has a different enumeration epoch: " + path);
          }
          revision = row.getString("unit_revision");
          sourceHash = row.getString("planned_source_sha256");
          ingestionRoot = row.getString("ingestion_root");
        }
      }
      if (revision == null || sourceHash == null) {
        throw new SQLException("Recorded admission has no exact source witness: " + path);
      }
      JobQueue.EnqueueProvenance provenance = originator == null && transport == null
          ? null : new JobQueue.EnqueueProvenance(originator, transport);
      String payload = new SwitchBufferUpsert(path, declaredCollection, provenance,
          revision, sourceHash, ingestionRoot == null ? null : Path.of(ingestionRoot)).encode();
      if (!switchBufferOps.putForGenerationInTransaction(
          connection, generation, "path:" + path, "UPSERT", payload)) {
        throw new SQLException("Generation-scoped recorded journal admission was refused");
      }
    }
  }

  @Override
  public boolean hasIssuedRecordedClaims(String operationKey) {
    lock.lock();
    try {
      ensureOpen();
      return hasIssuedRecordedClaimsLocked(operationKey);
    } finally {
      unlockAfterChanges();
    }
  }

  private boolean hasIssuedRecordedClaimsLocked(String operationKey) {
    Objects.requireNonNull(operationKey, "operationKey");
    return activeClaims.values().stream()
        .anyMatch(claim -> claim.walkEpoch() != null && operationKey.equals(claim.scanId()));
  }

  @Override
  public java.util.Optional<WalkProgress> recordedWalk(String operationKey) {
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.find(connection, operationKey), false);
  }

  @Override
  public java.util.Optional<SealedWalkReceipt> sealedRecordedWalkReceipt(String operationKey) {
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.sealedReceipt(
        connection, operationKey, SqliteJobQueue::sha256), false);
  }

  @Override
  public WalkProgress closeRecordedWalkEnumeration(String operationKey, long epoch, WalkEnumerationOutcome outcome) {
    return accessRecordedWalk(() -> {
      var before = SqliteIngestionWalkOps.find(connection, operationKey)
          .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded walk state is unavailable"));
      SqliteIngestionWalkOps.closeEnumeration(connection, operationKey, epoch, outcome, System.currentTimeMillis());
      if (before.enumerationClosedAt() == null) {
        List<String> retired = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT path FROM jobs WHERE scan_id = ? "
            + "AND walk_seen_epoch IS NOT NULL AND "
            + (outcome == WalkEnumerationOutcome.COMPLETE ? "walk_seen_epoch <> ?" : "state IN ('PENDING', 'PROCESSING')"))) {
          query.setString(1, operationKey);
          if (outcome == WalkEnumerationOutcome.COMPLETE) query.setLong(2, epoch);
          try (var rows = query.executeQuery()) {
            while (rows.next()) {
              String path = rows.getString(1);
              IndexJob claim = activeClaims.get(path);
              if (outcome == WalkEnumerationOutcome.COMPLETE || claim == null || !ownsClaim(claim)) retired.add(path);
            }
          }
        }
        for (String path : retired) skipRecordedMember(path,
            outcome == WalkEnumerationOutcome.COMPLETE ? IngestionOutcomeClass.STALE_SOURCE : IngestionOutcomeClass.SKIPPED_POLICY,
            outcome == WalkEnumerationOutcome.COMPLETE ? "SOURCE_REMOVED" : "ENUMERATION_" + outcome.name());
      }
      return SqliteIngestionWalkOps.find(connection, operationKey).orElseThrow();
    }, true);
  }

  @Override
  public WalkProgress trySealRecordedWalk(String operationKey) {
    return accessRecordedWalk(() -> sealRecordedWalk(operationKey), true);
  }

  @Override
  public WalkProgress retireRefusedRecordedWalk(String operationKey, String expectedPlanHash) {
    return accessRecordedWalk(() -> {
      var before = SqliteIngestionWalkOps.find(connection, operationKey)
          .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded walk state is unavailable"));
      if (!before.planHash().equals(expectedPlanHash)) throw new JobQueue.RecordedWalkGapException("Recorded plan changed");
      if (before.sealedAt() != null || hasIssuedRecordedClaimsLocked(operationKey)) return before;
      if (before.enumerationOutcome() != WalkEnumerationOutcome.COMPLETE) {
        throw new JobQueue.RecordedWalkGapException("Refused retirement requires completed enumeration");
      }
      List<String> pending = new ArrayList<>();
      try (var query = connection.prepareStatement("SELECT path FROM jobs WHERE scan_id = ? "
          + "AND walk_seen_epoch IS NOT NULL AND state IN ('PENDING', 'PROCESSING')")) {
        query.setString(1, operationKey);
        try (var rows = query.executeQuery()) { while (rows.next()) pending.add(rows.getString(1)); }
      }
      for (String path : pending) skipRecordedMember(path, IngestionOutcomeClass.SKIPPED_POLICY, "RECOVERY_REFUSED");
      return SqliteIngestionWalkOps.find(connection, operationKey).orElseThrow();
    }, true);
  }

  private WalkProgress sealRecordedWalk(String operationKey) throws SQLException {
    boolean issued = hasIssuedRecordedClaimsLocked(operationKey);
    return SqliteIngestionWalkOps.seal(connection, operationKey, issued, SqliteJobQueue::sha256, System.currentTimeMillis());
  }

  private void sealBeforeMaintenance(String path) throws SQLException {
    try (var query = connection.prepareStatement("SELECT scan_id FROM jobs WHERE path = ? AND walk_seen_epoch IS NOT NULL")) {
      query.setString(1, path);
      try (var row = query.executeQuery()) { if (row.next()) sealRecordedWalk(row.getString(1)); }
    }
  }

  private boolean closedUnsuccessfulMember(String path) throws SQLException {
    try (var query = connection.prepareStatement("SELECT scan_id FROM jobs WHERE path = ? AND walk_seen_epoch IS NOT NULL")) {
      query.setString(1, path);
      try (var row = query.executeQuery()) {
        if (!row.next()) return false;
        var progress = SqliteIngestionWalkOps.find(connection, row.getString(1))
            .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded unit state is unavailable"));
        return progress.enumerationOutcome() == WalkEnumerationOutcome.FAILED
            || progress.enumerationOutcome() == WalkEnumerationOutcome.CANCELLED;
      }
    }
  }

  /** Borrow the existing outcome transaction. Actual issued objects remain owned until their return. */
  private int skipRecordedMember(String path, IngestionOutcomeClass outcomeClass, String reason) throws SQLException {
    if (connection.getAutoCommit()) throw new SQLException("Administrative coverage requires a transaction");
    var receipt = SqliteIngestionWalkOps.currentReceipt(connection, path, SqliteIngestionWalkOps.Coverage.SKIPPED);
    if (receipt == null) throw new JobQueue.RecordedWalkGapException("Administrative recorded coverage is unavailable");
    var outcome = IngestionOutcome.of(outcomeClass, reason, io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE);
    try (var update = connection.prepareStatement("UPDATE jobs SET state = 'DONE', content_hash = NULL, "
        + "retry_after = NULL, last_updated = ?, last_outcome_class = ?, last_reason_code = ?, "
        + "last_retry_policy = ?, last_diagnostic_summary = ?, last_outcome_at = ? WHERE path = ?")) {
      bindOutcomeUpdate(update, 1, System.currentTimeMillis(), outcome, path);
      int count = executeMutation(update::executeUpdate);
      if (count != 1) throw new SQLException("Administrative recorded member disappeared");
      insertLedgerEvent(path, outcome, null, receipt);
      return count;
    }
  }

  /** Administrative selectors are fixed SQL owned by the queue's maintenance callers. */
  private int removeJobsAdministratively(String predicate, List<String> arguments, boolean sourceRemoved) throws SQLException {
    return removeJobsAdministratively(predicate, arguments, sourceRemoved, Set.of());
  }

  /** Administrative removal with exact normalized survivor paths excluded before row handling. */
  private int removeJobsAdministratively(String predicate, List<String> arguments,
      boolean sourceRemoved, Set<String> excludedPaths) throws SQLException {
    return connection.getAutoCommit()
        ? inTransaction(() -> removeJobsAdministrativelyInTransaction(predicate, arguments, sourceRemoved, excludedPaths))
        : removeJobsAdministrativelyInTransaction(predicate, arguments, sourceRemoved, excludedPaths);
  }

  private int removeJobsAdministrativelyInTransaction(String predicate, List<String> arguments,
      boolean sourceRemoved, Set<String> excludedPaths) throws SQLException {
    List<String> paths = new ArrayList<>();
    try (var query = connection.prepareStatement("SELECT path FROM jobs WHERE " + predicate)) {
      for (int i = 0; i < arguments.size(); i++) query.setString(i + 1, arguments.get(i));
      try (var rows = query.executeQuery()) { while (rows.next()) paths.add(rows.getString(1)); }
    }
    int affected = 0;
    for (String path : paths) {
      if (excludedPaths.contains(path)) continue;
      boolean recorded;
      WalkProgress progress = null;
      try (var query = connection.prepareStatement("SELECT scan_id, walk_seen_epoch FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (var row = query.executeQuery()) {
          if (!row.next()) throw new SQLException("Administrative member disappeared");
          row.getLong(2); recorded = !row.wasNull();
          if (recorded) progress = SqliteIngestionWalkOps.find(connection, row.getString(1))
              .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded unit state is unavailable"));
        }
      }
      if (recorded && progress.sealedAt() == null) {
        affected += skipRecordedMember(path,
            sourceRemoved ? IngestionOutcomeClass.STALE_SOURCE : IngestionOutcomeClass.SKIPPED_POLICY,
            sourceRemoved ? "SOURCE_REMOVED" : "ADMINISTRATIVE_CLEAR");
      } else if (!recorded || progress.acknowledgedRevision() == progress.revision()) {
        try (var delete = connection.prepareStatement("DELETE FROM jobs WHERE path = ?")) {
          delete.setString(1, path); affected += executeMutation(delete::executeUpdate);
        }
      }
    }
    return affected;
  }

  @Override
  public List<String> unacknowledgedCapturedWalkKeys(String afterKey, int limit) {
    if (limit < 1 || limit > 256) throw new IllegalArgumentException("Captured inventory limit must be 1..256");
    return accessRecordedWalk(() -> {
      List<String> keys = new ArrayList<>();
      try (var query = connection.prepareStatement("SELECT operation_key FROM ingestion_walk_progress "
          + "WHERE captured_plan = 1 AND sealed_at IS NOT NULL AND acknowledged_revision < revision "
          + "AND operation_key > ? ORDER BY operation_key LIMIT ?")) {
        query.setString(1, afterKey == null ? "" : afterKey);
        query.setInt(2, limit);
        try (var rows = query.executeQuery()) {
          while (rows.next()) keys.add(rows.getString(1));
        }
      }
      return List.copyOf(keys);
    }, false);
  }

  @Override
  public boolean acknowledgeRecordedWalk(String operationKey, long revision) {
    return accessRecordedWalk(() -> SqliteIngestionWalkOps.acknowledge(connection, operationKey, revision), true);
  }

  @Override
  public WalkSubscription subscribeRecordedWalks(java.util.function.Consumer<String> subscriber) {
    lock.lock();
    try { ensureOpen(); return walkChanges.subscribe(subscriber); }
    finally { unlockAfterChanges(); }
  }

  private <T> T accessRecordedWalk(SqlWork<T> work, boolean mutation) {
    lockTimed();
    try {
      ensureOpen();
      return mutation ? inTransaction(work) : work.run();
    } catch (SQLException failure) {
      throw new IllegalStateException("Recorded walk storage is unavailable", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  private final Path dbPath;
  private final ReentrantLock lock = new ReentrantLock();
  private static final ThreadLocal<Integer> FORGET_CALLBACK_DEPTH = ThreadLocal.withInitial(() -> 0);
  private final int maxAttempts;
  private Connection connection;
  private Throwable connectionFailure;
  // Only live processing capabilities: object identity prevents an older commit from finishing
  // a replacement claim for the same path. Dead JVMs cannot deliver callbacks after restart.
  private final Map<String, IndexJob> activeClaims = new HashMap<>();
  static final int CLAIM_SKIP_BUDGET = 256;
  private volatile boolean claimBudgetExhausted;
  private ClaimKey claimCursor;
  private long claimOrderVersion;
  private long claimWalkVersion;
  private Long claimRetryDeadline;

  private record ClaimKey(long updated, String path) implements Comparable<ClaimKey> {
    @Override public int compareTo(ClaimKey other) {
      int time = Long.compare(updated,other.updated);
      return time != 0 ? time : java.util.Arrays.compareUnsigned(
          path.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          other.path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private record ClaimCandidate(ClaimKey key, String collection, String originator, String transport,
      String scanId, String revision, Long walkEpoch, String plannedHash, String ingestionRoot) {}

  private boolean existedBeforeOpen;

  /**
   * Slice 445 producer scaffolding. Captures per-row INSERT/UPDATE/DELETE on the
   * {@code jobs} table via SQLite update + commit hooks and broadcasts typed
   * {@link IndexingJobsChangeStream.Delta} events to subscribers (e.g., the
   * subscription that backs the {@code core.indexing-jobs} TABULAR Resource — a
   * server-streaming RPC until lane F stage A item A7, a bounded in-process
   * hand-off since). Lazily attached in {@link #open()}; detached in {@link #close()}.
   */
  private IndexingJobsChangeStream changeStream;
  private SqliteRecordedWalkChanges walkChanges;

  // ==================== Health Tracking ====================

  // QueueDbHealthSnapshot record moved to JobQueue interface (Step 3 module split).

  private volatile boolean dbHealthy = true;
  private volatile long lastQuickCheckAtMs;
  private volatile boolean lastQuickCheckOk;
  private volatile long lastBackupAtMs;
  private volatile long lastDbErrorAtMs;

  /**
   * Hook called after each migration step (for testing migration rollback).
   * If this throws, the migration should roll back.
   */
  @FunctionalInterface
  interface MigrationStepHook {
    void afterStep(int version) throws SQLException;
  }

  private final MigrationStepHook migrationStepHook;
  private final java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy;
  private final SqliteQueueSwitchBufferOps switchBufferOps;

  private final java.util.function.LongUnaryOperator retryDelay;

  /** Tempdoc 885 item 21e — enqueue/dequeue rates and lock-wait, RISK-002's instrument. */
  private final QueueThroughputMeters meters = new QueueThroughputMeters();

  /** Tempdoc 885 item 21e — late-bound per-outcome counter sink; null until the catalog exists. */
  private volatile java.util.function.Consumer<String> outcomeObserver;
  private final List<String> pendingOutcomeMetrics = new ArrayList<>();

  /**
   * Creates a SqliteJobQueue backed by the specified SQLite database file.
   *
   * @param dbPath Path to the jobs.db file
   */
  public SqliteJobQueue(Path dbPath) {
    this(dbPath, DEFAULT_MAX_ATTEMPTS, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts) {
    this(dbPath, maxAttempts, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count and a write-failure callback.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   * @param onSwitchBufferWriteFailure Optional callback invoked once per switch-buffer write
   *     failure (may be null). Tempdoc 417 Phase 3c: replaces the legacy
   *     {@code Telemetry.Counter} parameter to decouple this layer from the soon-to-be-retired
   *     {@code Telemetry} interface.
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, null);
  }

  /**
   * Creates a SqliteJobQueue with all options (including test hook).
   *
   * <p>The migrationStepHook is package-private for testing migration rollback scenarios.
   */
  SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure,
                 MigrationStepHook migrationStepHook) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, migrationStepHook, ignored -> RecordedClaimDecision.DENY);
  }

  /** Recorded membership is fenced from open; the supplied authority is checked per unit claim. */
  public SqliteJobQueue(Path dbPath, java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy) {
    this(dbPath, recordedClaimPolicy, DEFAULT_MAX_ATTEMPTS, null);
  }

  /**
   * Constructor-bound authority for recorded membership. The callback runs under the queue lock;
   * it must not call a queue, operation store or runner, and must not perform an effect.
   * Existing no-authority constructors deny recorded work while preserving legacy jobs.
   */
  public SqliteJobQueue(Path dbPath, java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy,
      int maxAttempts, Runnable onSwitchBufferWriteFailure) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, null, recordedClaimPolicy);
  }

  /** Timing seam for integration fixtures; ordinary constructors preserve the production ladder. */
  public SqliteJobQueue(Path dbPath, java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy,
      int maxAttempts, Runnable onSwitchBufferWriteFailure, java.util.function.LongUnaryOperator retryDelay) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, null, recordedClaimPolicy, retryDelay);
  }

  private SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure,
      MigrationStepHook migrationStepHook, java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, migrationStepHook, recordedClaimPolicy,
        java.util.function.LongUnaryOperator.identity());
  }

  private SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure,
      MigrationStepHook migrationStepHook, java.util.function.Function<String, RecordedClaimDecision> recordedClaimPolicy,
      java.util.function.LongUnaryOperator retryDelay) {
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.dbPath = dbPath;
    this.maxAttempts = maxAttempts;
    this.migrationStepHook = migrationStepHook;
    this.recordedClaimPolicy = Objects.requireNonNull(recordedClaimPolicy, "recordedClaimPolicy");
    this.switchBufferOps =
        new SqliteQueueSwitchBufferOps(
            lock, this::ensureOpenAndGetConnection, onSwitchBufferWriteFailure,
            this::recordDbError);
  }

  private Connection ensureOpenAndGetConnection() {
    ensureOpen();
    return connection;
  }

  /**
   * Tempdoc 885 item 21e — the throughput + lock-wait instrument RISK-002 has been "monitoring"
   * without since tempdoc 269. Owned by the queue (it is the only thing that knows when a row is
   * admitted or claimed) and read by {@code WorkerOpsMetricCatalog}'s suppliers at flush time.
   */
  public QueueThroughputMeters throughputMeters() {
    return meters;
  }

  /**
   * Late-bound per-outcome counter sink, wired by {@code KnowledgeServer} once the telemetry
   * registry exists. Mirrors {@code onSwitchBufferWriteFailure}: the queue is constructed long
   * before the catalog, so the sink no-ops until it is set rather than the queue holding a
   * half-built catalog.
   */
  public void setOutcomeObserver(java.util.function.Consumer<String> observer) {
    this.outcomeObserver = observer;
  }

  private void recordOutcomeMetric(String outcomeClass) {
    var observer = this.outcomeObserver;
    if (observer == null) {
      return;
    }
    try {
      // NOT outcomeClassName(): that returns the literal "null" for logging, which would reach the
      // metric as a tag VALUE "null" instead of the schema's UNKNOWN.
      observer.accept(outcomeClass);
    } catch (RuntimeException e) {
      log.debug("Queue outcome observer failed: {}", e.getMessage());
    }
  }

  /**
   * Acquires the queue's single write lock, recording how long the caller waited. Used on the two
   * paths RISK-002 is actually about — the one dequeue caller and the many enqueue callers — so the
   * measurement covers the contention that matters without instrumenting forty read paths whose
   * wait is the same fact measured again.
   */
  private void lockTimed() {
    if (lock.tryLock()) {
      meters.recordLockWaitMs(0L);
      return;
    }
    long start = meters.nowMs();
    lock.lock();
    meters.recordLockWaitMs(Math.max(0L, meters.nowMs() - start));
  }

  @Override
  public void open() throws SQLException, IOException {
    lock.lock();
    boolean acquiredConnection = false;
    try {
      if (connectionFailure != null) throw new SQLException(
          "Queue connection cleanup requires successful close before open", connectionFailure);
      if (connection != null) throw new SQLException("Queue is already open; close before reopening");
      Files.createDirectories(dbPath.getParent());

      // Capture whether DB existed BEFORE opening (JDBC will create empty file if missing)
      existedBeforeOpen = Files.exists(dbPath) && Files.size(dbPath) > 0;
      if (existedBeforeOpen) {
        SqliteQueueMigrationOps.refuseFutureSchema(dbPath);
      }

      String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
      connection = DriverManager.getConnection(jdbcUrl);
      acquiredConnection = true;

      // Configure SQLite for better concurrency
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        stmt.execute("PRAGMA journal_mode = WAL");
        stmt.execute("PRAGMA synchronous = NORMAL");
        // REPLACE must fire the implicit DELETE's count triggers, including for plain SQL.
        stmt.execute("PRAGMA recursive_triggers = ON");
        // Enable incremental auto-vacuum. On new databases this takes effect immediately.
        // On existing databases with auto_vacuum=0, this has no effect until a full VACUUM.
        stmt.execute("PRAGMA auto_vacuum = 2");
      }

      initSchema();
      switchBufferOps.retirementAdmissions(() -> {
        try { return releaseRecovery != null && releaseRecovery.retiringBuild() ? releaseRecovery.retainedOrderFloor() : 0; }
        catch (IOException invalid) { throw new IllegalStateException("Retained admission order unavailable",invalid); }
      }, () -> {
        if (releaseRecovery != null && releaseRecovery.retiringBuild()) {
          releaseRecovery.admissionsChanged();
          rootClaimsPrepared = false;
        }
        claimCursor = null; claimOrderVersion++;
      }, () -> {
        try { return releaseRecovery != null && releaseRecovery.retiringBuild()
            ? releaseRecovery.retirementReceipts(List.of()) : List.of(); }
        catch (IOException invalid) { throw new IllegalStateException("Retained projection admission unavailable",invalid); }
      }, predecessor -> {
        if (releaseRecovery != null && releaseRecovery.retiringBuild()
            && "UPSERT".equals(predecessor.op()) && !predecessor.payload().stripLeading().startsWith("{")) {
          try { releaseRecovery.retainRawHistory(predecessor); }
          catch (IOException invalid) { throw new IllegalStateException("Original raw receipt was not retained",invalid); }
        }
      });

      // Run integrity check on existing databases (throws SQLException on corruption)
      performIntegrityCheck();
      openedQueueSession = UUID.randomUUID().toString();
      rootClaimsPrepared = false;
      retirementClaimsBinding = null;
      if (releaseRecovery != null) {
        releaseRecovery.openedSession(openedQueueSession);
        if (releaseRecovery.retiringBuild() && releaseRecovery.rootsJournaled()) {
          try {
            releaseRecovery.validateRootHeads(switchBufferOps.listAllStrict().stream()
                .filter(r -> "SYNC_ROOT".equals(r.op())).toList());
            applyRetainedPrefixConversions();
            releaseRecovery.bindAdmissions(releaseRecovery.retirementReceipts(switchBufferOps.listAllStrict()));
          } catch (IOException invalid) {
            throw new IOException(MigrationJournalWitness.REFUSAL + ": " + invalid.getMessage(), invalid);
          }
        }
      }

      claimCursor = null;
      claimBudgetExhausted = false;
      // Attach the bounded change stream after the schema is ready.
      changeStream = new IndexingJobsChangeStream(connection, lock, this::ensureOpen);
      walkChanges = new SqliteRecordedWalkChanges(connection);
      installClaimCursorInvalidation();
      installOutcomeMetrics();

      log.info("SqliteJobQueue opened: {}", dbPath);
    } catch (SQLException | IOException | RuntimeException | Error failure) {
      if (acquiredConnection) {
        try { closeWalkChanges(); }
        catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
        if (changeStream != null) {
          try { changeStream.close(); }
          catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
          } finally { changeStream = null; }
        }
        try {
          // An incomplete open owns no usable queue. Close directly without checkpointing or
          // restoring auto-commit: failed migration rollback may have left a partial transaction.
          connection.close();
          connection = null;
        } catch (SQLException | RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
          connectionFailure = cleanupFailure;
        }
      }
      throw failure;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Opens the queue and forces an integrity check regardless of whether the database
   * existed before opening. Use this after restoring from a backup to validate the
   * restored database.
   */
  @Override
  public void openWithIntegrityCheck() throws SQLException, IOException {
    open();
  }

  /** Release-v13 claims remain queued until their policy boundaries have been prepared. */
  static final String RELEASE030 = "release030/";
  private Release030RecoveryState releaseRecovery;
  private String openedQueueSession;
  private boolean rootClaimsPrepared;
  private String retirementClaimsBinding;
  public Release030RecoveryState release030RecoveryState() { return releaseRecovery; }
  public String openedQueueSession() { return openedQueueSession; }

  private boolean requiresRelease030Boundary;
  private boolean release030ExplicitFilesPrepared = true;
  private final Set<Path> release030ExactFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Set<Path> release030RetirementFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile String release030RetirementGeneration;
  private Map<Path, String> release030RetirementCollections = Map.of();

  /** Startup only: root recovery is older than every newly acknowledged mutation. */
  public void journalRelease030Roots(String generation, List<Path> roots) throws SQLException {
    journalRelease030Roots(generation, roots, root -> null);
  }

  public void journalRelease030Roots(String generation, List<Path> roots,
      java.util.function.Function<Path,String> creatorCollection) throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      inTransaction(() -> {
        for (Path root : roots) {
          String key = Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX + PathNormalizer.normalizeKey(root);
          try (var query = connection.prepareStatement(
              "SELECT op, payload FROM switch_buffer WHERE generation=? AND key=?")) {
            query.setString(1, generation); query.setString(2, key);
            try (var row = query.executeQuery()) {
              if (row.next()) {
                if (!"SYNC_ROOT".equals(row.getString(1))
                    || !Path.of(SwitchBufferSyncRoot.decode(row.getString(2)).rootPath()).equals(root))
                  throw new SQLException("Release root receipt has conflicting ownership");
                continue; // A kill before sidecar publication must not renew accepted_order.
              }
            }
          }
          if (!switchBufferOps.putForGenerationInTransaction(connection, generation, key, "SYNC_ROOT",
              new SwitchBufferSyncRoot(PathNormalizer.normalizeKey(root), false, null).encode()))
            throw new SQLException("Release root recovery could not be journaled");
          var accepted = switchBufferOps.listAllStrictForGeneration(generation).stream()
              .filter(op -> op.key().equals(key)).findFirst().orElseThrow();
          try {
            releaseRecovery.captureFreshRootCollection(accepted, creatorCollection.apply(root));
            releaseRecovery.captureRootScope(accepted,retirementScope(accepted,switchBufferOps.listAllStrict()));
          }
          catch (IOException invalid) { throw new SQLException("ROOT creator namespace was not retained", invalid); }
        }
        return null;
      });
    } finally { unlockAfterChanges(); }
  }

  public boolean hasUnfinishedFileJob(String path) {
    lock.lock();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement(
          "SELECT 1 FROM jobs WHERE path=? AND state IN ('PENDING','PROCESSING')")) {
        query.setString(1, path);
        try (var row = query.executeQuery()) { return row.next(); }
      }
    } catch (SQLException unreadable) {
      throw new IllegalStateException("Release file retry cannot be confirmed", unreadable);
    } finally { lock.unlock(); }
  }

  public String release030RetirementCollection(Path path) {
    lock.lock();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement("SELECT collection FROM jobs WHERE path=?")) {
        query.setString(1, PathNormalizer.normalizeKey(path));
        try (var row = query.executeQuery()) {
          if (row.next() && row.getString(1) != null) return row.getString(1);
        }
      }
      String collection = release030RetirementCollections.get(path);
      return collection == null || collection.isBlank() ? null : collection;
    } catch (SQLException unreadable) {
      throw new IllegalStateException("Release file collection cannot be confirmed", unreadable);
    } finally { lock.unlock(); }
  }

  @Override
  public String release030RetirementGeneration() { return release030RetirementGeneration; }

  public void retainRelease030Retirement(String generation, java.util.Collection<Path> exactFiles, Map<Path, String> collections) {
    release030RetirementCollections = Map.copyOf(collections);
    release030RetirementFiles.addAll(exactFiles);
    release030RetirementGeneration = generation;
  }

  public void finishRelease030Retirement() {
    release030RetirementGeneration = null;
    release030RetirementFiles.clear();
    release030RetirementCollections = Map.of();
  }

  private final Set<Path> release030IndependentDebt = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public boolean isRelease030RetirementFile(Path file) {
    return file != null && release030RetirementFiles.contains(file.toAbsolutePath().normalize());
  }

  /** A historical index path cannot grant authority to a newer unowned admission. */
  public boolean hasUnownedModernAdmission(Path file) {
    lockTimed();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement(
          "SELECT 1 FROM jobs WHERE path=? AND ingestion_root IS NULL "
              + "AND (unit_revision IS NULL OR unit_revision NOT LIKE 'release030/%')")) {
        query.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = query.executeQuery()) { return row.next(); }
      }
    } catch (SQLException unreadable) {
      throw new IllegalStateException("Release exact-file admission cannot be confirmed", unreadable);
    } finally { lock.unlock(); }
  }

  @Override
  public boolean isRelease030ExactFile(Path file) {
    if (isRelease030RetirementFile(file)) return !hasUnownedModernAdmission(file);
    if (file == null || (release030ExplicitFilesPrepared
        && !release030ExactFiles.contains(file.toAbsolutePath().normalize()))) return false;
    lockTimed();
    try {
      ensureOpen();
      // Startup legacy replay precedes boundary repair. The durable v13 identity protects
      // queued exact paths already, while claims remain held until their boundaries are ready.
      if (!release030ExplicitFilesPrepared) return hasRelease030ReplayAdmission(file);
      try (var query = connection.prepareStatement(
          "SELECT 1 FROM jobs WHERE path=? AND ingestion_root=path AND unit_revision LIKE 'release030/%'")) {
        query.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = query.executeQuery()) { return row.next(); }
      }
    } catch (SQLException unreadable) {
      throw new IllegalStateException("Legacy admission identity is unreadable", unreadable);
    } finally { lock.unlock(); }
  }

  /** Retain original unfinished work and exhausted debt already satisfied on retained Green. */
  public boolean hasRelease030ReplayAdmission(Path file) throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement("""
          SELECT 1 FROM jobs WHERE path=? AND unit_revision LIKE 'release030/%'
            AND (state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED')
              OR unit_revision LIKE 'release030/debt/%')
          """)) {
        query.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = query.executeQuery()) { return row.next(); }
      }
    } finally { lock.unlock(); }
  }

  /** Original exhausted, never-indexed exact admissions retain debt without holding Green. */
  public boolean hasRelease030IndependentDebt() throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      // DONE still proves why this retained native candidate may have timed out: the file can
      // return and finish on Green while FAILED, before the next boot retries cutover.
      try (var query = connection.createStatement(); var row = query.executeQuery(
          "SELECT 1 FROM jobs WHERE unit_revision LIKE 'release030/debt/%' LIMIT 1")) {
        return row.next();
      }
    } finally { lock.unlock(); }
  }

  public JobQueue.JobStateCounts nativeCutoverCountsStrict() {
    lockTimed();
    try {
      var counts = jobStateCountsStrict();
      var paths = new ArrayList<>(release030IndependentDebt);
      long pending = 0, ready = 0, processing = 0, failed = 0;
      for (int start = 0; start < paths.size(); start += 256) {
        var batch = paths.subList(start, Math.min(start + 256, paths.size()));
        String parameters = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
        try (var query = connection.prepareStatement("""
            SELECT state, COALESCE(retry_after,0) FROM jobs WHERE unit_revision LIKE 'release030/debt/%'
              AND path IN (""" + parameters + ")")) {
          for (int i = 0; i < batch.size(); i++) query.setString(i + 1, PathNormalizer.normalizeKey(batch.get(i)));
          try (var rows = query.executeQuery()) {
            long now = System.currentTimeMillis();
            while (rows.next()) {
              switch (rows.getString(1)) {
                case "PENDING" -> { pending++; if (rows.getLong(2) <= now) ready++; }
                case "PROCESSING" -> processing++;
                case "FAILED", "RETRY_EXHAUSTED" -> failed++;
                default -> { }
              }
            }
          }
        }
      }
      return new JobQueue.JobStateCounts(counts.pendingCount() - pending,
          Math.max(0L, counts.pendingReadyCount() - ready), counts.processingCount() - processing,
          counts.doneCount(), counts.failedCount() - failed);
    } catch (SQLException unreadable) {
      throw new IllegalStateException("Native cutover debt is unreadable", unreadable);
    } finally { lock.unlock(); }
  }

  /**
   * Initializes the database schema and runs migrations.
   *
   * <p>This method handles both fresh databases and existing databases that need
   * migration. It uses PRAGMA user_version for version tracking and applies
   * migrations sequentially using a ladder pattern.
   *
   * @throws SQLException if schema initialization or migration fails
   * @throws IOException if backup fails before migration
   */
  private void initSchema() throws SQLException, IOException {
    // A kill after migration must not erase the fact that these were release-v13 jobs. Capture
    // before any DDL, leaving the migration itself lossless (including every saved retry field).
    if (SqliteQueueMigrationOps.getSchemaVersion(connection) == 13) {
      // Mark a known v13 database before its existing atomic migration. Its new v17 unit
      // revisions carry this generation, including a backup or kill before queue repair.
      int generation;
      try (var query = connection.createStatement(); var row = query.executeQuery("PRAGMA application_id")) {
        if (!row.next()) throw new SQLException("Missing queue database identity");
        generation = row.getInt(1);
      }
      if (generation == 0) {
        try (var update = connection.createStatement()) {
          update.execute("PRAGMA application_id=" + ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
        }
      }
      Release030RecoveryState.capture(dbPath, connection);
    }
    var recovery = Release030RecoveryState.load(dbPath, retryDelay.applyAsLong(60_000L));
    releaseRecovery = recovery;
    requiresRelease030Boundary = recovery != null && !recovery.preparedFor(release030RecoveryIdentity());
    release030ExplicitFilesPrepared = recovery == null;
    release030ExactFiles.clear();
    release030IndependentDebt.clear();
    // Create base tables if they don't exist
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_INDEX);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_UPDATED_INDEX);
      stmt.execute(SqliteSchema.CREATE_SWITCH_BUFFER_TABLE);
    }

    // Run versioned migrations
    SqliteQueueMigrationOps.runMigrations(connection, this::performBackup, migrationStepHook);
  }

  /** Returns the buffered count, or throws when the observation is unavailable. */
  @Override
  public long switchBufferDepth() { return switchBufferOps.depth(); }

  public long switchBufferAdmissions() throws SQLException {
    lock.lock();
    try { return switchBufferOps.admissions(); }
    finally { lock.unlock(); }
  }

  public void attachMigrationJournalWitness(MigrationJournalWitness witness) throws SQLException {
    lock.lock();
    try { switchBufferOps.attachAdmissionWitness(witness); }
    finally { lock.unlock(); }
  }

  /** Returns state and runnable counts, or throws when the observation is unavailable. */
  @Override
  public JobQueue.JobStateCounts jobStateCounts() { return switchBufferOps.stateCounts(); }

  @Override
  public JobQueue.JobStateCounts jobStateCountsStrict() { return switchBufferOps.stateCountsStrict(); }

  /** Byte weight of remaining PENDING/PROCESSING work; unknown sizes counted, not summed. */
  @Override
  public JobQueue.PendingBytes pendingBytes() { return switchBufferOps.pendingBytes(); }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   */
  @Override
  public boolean putSwitchBuffer(String key, String op, String payload) {
    return switchBufferOps.put(key, op, payload);
  }

  @Override
  public boolean putSwitchBufferForGeneration(
      String generation, String key, String op, String payload) {
    return switchBufferOps.putForGeneration(generation, key, op, payload);
  }

  @Override
  public ProjectionAdmission admitProjectionForGeneration(String generation, String payload) {
    var projection = io.justsearch.app.api.indexing.AcceptedProjection.decode(payload);
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> switchBufferOps.admitProjectionInTransaction(
          connection, generation, projection));
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Generation-scoped projection admission failed", failure);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void scopeLegacyPrefixReceipts(
      java.util.function.Function<String, PathPrefixScope> scope) {
    lock.lock();
    try {
      ensureOpen();
      inTransaction(() -> {
        try {
        for (var op : switchBufferOps.listAllStrict()) {
          if (!"DELETE_PREFIX".equals(op.op())
              || !PathPrefixScope.isLegacy(op.payload())) continue;
          SwitchBufferOp converted = null;
          if (releaseRecovery != null && releaseRecovery.retiringBuild()) {
            converted = releaseRecovery.conversionSuccessor(op);
          }
          if (converted == null) {
            converted = new SwitchBufferOp(op.generation(),op.key(),op.op(),scope.apply(op.payload()).encode(),
                op.lastUpdatedMs(),UUID.randomUUID().toString(),op.acceptedOrder());
            if (releaseRecovery != null && releaseRecovery.retiringBuild())
              releaseRecovery.retainPrefixConversion(op,converted);
          }
          replaceLegacyPrefix(op,converted);
        }
        return null;
        } catch (IOException invalid) { throw new SQLException("Legacy conversion checkpoint failed",invalid); }
      });
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Legacy prefix receipts could not be scoped", failure);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean putSyncRoot(String key, SwitchBufferSyncRoot payload) {
    return switchBufferOps.putSyncRoot(key, payload);
  }

  @Override
  public boolean putSyncRootForGeneration(
      String generation, String key, SwitchBufferSyncRoot payload) {
    return putSyncRootForGeneration(generation, key, payload, null);
  }

  @Override public boolean putSyncRootForGeneration(
      String generation, String key, SwitchBufferSyncRoot payload, String collection) {
    if (releaseRecovery == null || !releaseRecovery.retiringBuild())
      return switchBufferOps.putSyncRootForGeneration(generation, key, payload);
    lockTimed();
    try {
      ensureOpen();
      if (!releaseRecovery.rootsInventoried()) throw new IOException("ROOT inventory was not published");
      var previous = switchBufferOps.listAllStrictForGeneration(generation).stream().filter(op -> op.key().equals(key)).findFirst().orElse(null);
      if (previous == null) previous = releaseRecovery.requiredRootHead(generation,key);
      releaseRecovery.replacingRoot(generation,key,previous);
      return inTransaction(() -> {
        if (!switchBufferOps.putSyncRootForGeneration(generation,key,payload)) throw new SQLException("ROOT write failed");
        var accepted = switchBufferOps.listAllStrictForGeneration(generation).stream().filter(op -> op.key().equals(key)).findFirst().orElse(null);
        if (accepted == null) throw new SQLException("ROOT head unavailable");
        try {
          releaseRecovery.captureFreshRootCollection(accepted,collection);
          releaseRecovery.captureRootScope(accepted,retirementScope(accepted,releaseRecovery.rootReceipts()));
        } catch (IOException invalid) { throw new SQLException("ROOT policy not retained",invalid); }
        return accepted;
      }) != null && retainCommittedRoot(generation,key);
    } catch (SQLException | IOException failure) {
      throw new IllegalStateException("ROOT receipt was not retained; caller must not acknowledge",failure);
    } finally { unlockAfterChanges(); }
  }

  private boolean retainCommittedRoot(String generation,String key) throws IOException {
    var accepted = switchBufferOps.listAllStrictForGeneration(generation).stream()
        .filter(op -> op.key().equals(key)).findFirst().orElseThrow();
    releaseRecovery.retainRootHead(accepted);
    return true;
  }

  @Override public SwitchBufferOp retirementRootReceipt(String generation,String key) {
    lockTimed();try {
      ensureOpen();var receipt = switchBufferOps.listAllStrictForGeneration(generation).stream().filter(op -> op.key().equals(key)).findFirst().orElse(null);
      if (receipt == null || !"SYNC_ROOT".equals(receipt.op()) || releaseRecovery == null
          || !releaseRecovery.rootReceipts().contains(receipt)) throw new IllegalStateException("Unretained ROOT head");
      return receipt;
    } catch (IOException failure) { throw new IllegalStateException("ROOT authority unavailable",failure); }
    finally { unlockAfterChanges(); }
  }
  public void inventoryRetirementRoots() throws IOException,SQLException {
    lockTimed();try {
      var roots = switchBufferOps.listAllStrict().stream().filter(r -> "SYNC_ROOT".equals(r.op())).toList();
      if (!releaseRecovery.rootsInventoried()) {
        var legacyFiles = new HashMap<String,SwitchBufferUpsert>();
        for (var op : switchBufferOps.listAllStrict()) if ("UPSERT".equals(op.op()) && !op.payload().stripLeading().startsWith("{")) {
          var expectation = legacyQueueExpectation(op);
          if (expectation != null) legacyFiles.put(op.retirementUuid(),expectation);
        }
        var scopes = new HashMap<String,PathPrefixScope>();
        for (var root : roots) {
          var retained = releaseRecovery.rootScope(root);
          scopes.put(root.retirementUuid(),retained == null ? retirementScope(root,roots) : retained);
        }
        releaseRecovery.inventoryRoots(roots,legacyFiles,scopes);
      }
      else releaseRecovery.validateRootHeads(roots);
    } finally { unlockAfterChanges(); }
  }
  /** Original Q metadata, never inferred from the A image being verified. */
  public SwitchBufferUpsert legacyQueueExpectation(SwitchBufferOp op) throws SQLException {
    lockTimed();
    try {
    ensureOpen();
    var file = SwitchBufferUpsert.decode(op.payload());
    try (var query = connection.prepareStatement("SELECT collection,ingestion_root,originator,transport FROM jobs WHERE path=?")) {
      query.setString(1,file.path());
      try (var row = query.executeQuery()) {
        if (!row.next()) return null;
        // A released DONE/FAILED row may predate ingestion_root. Its known namespace
        // still belongs to this receipt; the raw path authorizes only that exact file.
        String boundary = row.getString(2);
        return new SwitchBufferUpsert(file.path(),Objects.toString(row.getString(1),"default"),
            row.getString(3) == null && row.getString(4) == null ? null
                : new JobQueue.EnqueueProvenance(row.getString(3),row.getString(4)),
            null,null,Path.of(boundary == null ? file.path() : boundary));
      }
    }
    } finally { unlockAfterChanges(); }
  }

  @Override public void retainRetirementRootCollection(SwitchBufferOp root,String collection) {
    lockTimed();try { releaseRecovery.retainRootCollection(root,collection); }
    catch(IOException invalid){throw new IllegalStateException("ROOT collection was not saved",invalid);}
    finally{unlockAfterChanges();}
  }
  @Override public String retirementRootCollection(SwitchBufferOp root) {
    return releaseRecovery==null?null:releaseRecovery.rootCollection(root);
  }
  @Override public boolean retirementRootPolicyKnown(SwitchBufferOp root) {
    return releaseRecovery != null && releaseRecovery.rootPolicy(root) != null;
  }
  @Override public boolean retirementRootLegacyExecution(SwitchBufferOp root) {
    return releaseRecovery != null && "LEGACY_EXECUTION".equals(releaseRecovery.rootPolicy(root));
  }
  @Override public SwitchBufferUpsert retirementRootFileScope(SwitchBufferOp root,String path) {
    var member=retirementRootFiles().apply(path);
    boolean administrative=root.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX);
    if(member==null || !administrative && !root.equals(member.owner())
        || administrative && member.owner().acceptedOrder()>root.acceptedOrder())
      throw new IllegalStateException("ROOT member metadata is unavailable");
    return member.scope();
  }
  @Override public SwitchBufferOp retirementSourceRoot(String path) {
    var member=retirementRootFiles().apply(path);
    return member==null?null:member.owner();
  }
  @Override public java.util.function.Function<String,RetirementRootFile> retirementRootFiles() {
    lockTimed();try {
      if(releaseRecovery==null)return path->null;
      var orders=retirementOrders(retirementReplayOps());
      return path->{
        lockTimed();try {
          var row=watcherRowLocked(path);if(row==null)return null;
          var origin=RootOrigin.fromUnit(row.get("unit_revision"));
          if(origin==null || validatedUnitOrder(correctionCandidate(row),orders)!=origin.acceptedOrder())return null;
          var owner=orders.receipts().get(origin.uuid());if(owner==null)return null;
          var provenance=row.get("originator")==null && row.get("transport")==null?null
              :new JobQueue.EnqueueProvenance(row.get("originator"),row.get("transport"));
          return new RetirementRootFile(owner,new SwitchBufferUpsert(path,row.get("collection"),provenance,
              null,null,Path.of(row.get("ingestion_root"))));
        }catch(IOException|SQLException invalid){throw new IllegalStateException("Current source ROOT unavailable",invalid);}
        finally{unlockAfterChanges();}
      };
    }catch(IOException invalid){throw new IllegalStateException("Current ROOT history unavailable",invalid);}
    finally{unlockAfterChanges();}
  }

  /** Only the actual retained FILE and its unchanged complete row may vouch for a conditional event. */
  public boolean retirementFileOwnsCurrentRow(SwitchBufferOp receipt) {
    lockTimed();try {
      ensureOpen();
      if (releaseRecovery == null || !releaseRecovery.retiringBuild() || !"UPSERT".equals(receipt.op())) return false;
      var orders = retirementOrders(retirementReplayOps());
      if (!receipt.equals(orders.receipts().get(receipt.retirementUuid()))) return false;
      var file = SwitchBufferUpsert.decode(receipt.payload());
      var row = watcherRowLocked(file.path());
      return file.unitRevision() != null && receipt.key().equals("path:"+file.path()) && row != null
          && file.unitRevision().equals(row.get("unit_revision"))
          && validatedUnitOrder(correctionCandidate(row),orders) == receipt.acceptedOrder();
    } catch (IOException | SQLException invalid) { throw new IllegalStateException("Current FILE ownership unavailable",invalid); }
    finally { unlockAfterChanges(); }
  }

  /** Read-only settlement of a conditional event's freshly observed native projection. */
  public boolean matchesRetirementConditionalDelete(SwitchBufferOp receipt,Map<String,String> observedRow,
      String collection) {
    lockTimed();try {
      ensureOpen();
      if(releaseRecovery==null || !releaseRecovery.retiringBuild() || collection==null || collection.isBlank()
          || !"DELETE".equals(receipt.op()))return false;
      boolean retained=releaseRecovery.retainsRemovalPacket(receipt) || releaseRecovery.deletionCommitted(receipt);
      if(!retained) {
        try(var query=connection.prepareStatement(
            "SELECT op,payload,last_updated,revision,accepted_order FROM switch_buffer WHERE generation=? AND key=?")) {
          query.setString(1,receipt.generation());query.setString(2,receipt.key());
          try(var current=query.executeQuery()) {
            if(!current.next() || !receipt.equals(new SwitchBufferOp(receipt.generation(),receipt.key(),
                current.getString(1),current.getString(2),current.getLong(3),current.getString(4),current.getLong(5))))return false;
          }
        }
      }
      var deletion=SwitchBufferDelete.decode(receipt.payload());String path=deletion.path();
      if(deletion.origin()==SwitchBufferDelete.Origin.EXPLICIT || !receipt.key().equals("path:"+path)
          || activeClaims.containsKey(path) || hasUnownedModernAdmission(Path.of(path)))return false;
      var row=watcherRowLocked(path);
      if(!Objects.equals(row,observedRow) || correctionHasRecordedOwner(path,row))return false;
      if(row==null) {
        try(var query=connection.prepareStatement("SELECT 1 FROM ingestion_ledger WHERE path_hash=? AND operation_key IS NOT NULL LIMIT 1")) {
          query.setString(1,sha256(path));try(var ledger=query.executeQuery()){return !ledger.next();}
        }
      }
      if(!STATE_DONE.equals(row.get("state")) || row.get("retry_after")!=null
          || !Objects.equals(collection,row.get("collection")))return false;
      String unit=row.get("unit_revision");
      return unit!=null && unit.startsWith("release030/job/") && row.get("ingestion_root")==null
          && row.get("planned_source_sha256")==null && row.get("originator")==null && row.get("transport")==null
          && releaseRecovery.preparedFor(release030RecoveryIdentity());
    }catch(SQLException|IOException invalid){throw new IllegalStateException("Conditional DELETE proof unavailable",invalid);}
    finally{unlockAfterChanges();}
  }

  public Set<String> retirementRootCollections(SwitchBufferOp op) {
    lockTimed();try {
      String declared=retirementRootCollection(op);
      return declared == null ? Set.of() : Set.of(declared);
    }
    finally{unlockAfterChanges();}
  }
  private java.util.function.BiPredicate<String,String> retainedRootScopes(List<SwitchBufferOp> journal) throws IOException {
    if (releaseRecovery == null || !releaseRecovery.retiringBuild()) return (path,unit) -> false;
    var roots = new ArrayList<String>();
    for (var root : releaseRecovery.rootReceipts())
      roots.add(PathNormalizer.normalizePathPrefix(SwitchBufferSyncRoot.decode(root.payload()).rootPath()));
    var exact = new HashSet<String>();
    for (Path path : releaseRecovery.retirementPaths().keySet()) exact.add(PathNormalizer.normalizeKey(path));
    for (Path path : release030ExactFiles) exact.add(PathNormalizer.normalizeKey(path));
    for (Path path : release030IndependentDebt) exact.add(PathNormalizer.normalizeKey(path));
    for (var receipt : journal) {
      if ("UPSERT".equals(receipt.op())) exact.add(SwitchBufferUpsert.decode(receipt.payload()).path());
      if ("DELETE".equals(receipt.op())) exact.add(SwitchBufferDelete.decode(receipt.payload()).path());
    }
    return (path,unit) -> unit != null && (unit.startsWith("root1:") || unit.startsWith("root2:")) || exact.contains(path)
        || roots.stream().anyMatch(prefix -> path.equals(prefix.substring(0,prefix.length()-1)) || path.startsWith(prefix));
  }
  private record RetirementDelete(long order, String path,
      PathPrefixScope prefix, String collection) {
    boolean matches(String candidate, String owner) {
      return path != null ? path.equals(candidate) : prefix != null ? prefix.contains(candidate)
          : Objects.equals(collection, owner == null || owner.isBlank() ? "default" : owner);
    }
  }

  private List<RetirementDelete> retirementDeletes(List<SwitchBufferOp> journal) throws IOException {
    var byVersion = new java.util.LinkedHashMap<String,SwitchBufferOp>();
    if (releaseRecovery != null) for (var r : releaseRecovery.committedDeletes()) byVersion.put(r.retirementUuid(), r);
    for (var r : journal) if (List.of("DELETE","DELETE_PREFIX","DELETE_COLLECTION").contains(r.op()))
      byVersion.put(r.retirementUuid(), r);
    var result = new ArrayList<RetirementDelete>();
    for (var receipt : byVersion.values()) {
      switch (receipt.op()) {
        case "DELETE" -> {
          var deletion = SwitchBufferDelete.decode(receipt.payload());
          if (deletion.origin() == SwitchBufferDelete.Origin.EXPLICIT)
            result.add(new RetirementDelete(receipt.acceptedOrder(), deletion.path(), null, null));
        }
        case "DELETE_PREFIX" -> result.add(new RetirementDelete(receipt.acceptedOrder(), null,
            PathPrefixScope.decode(receipt.payload()), null));
        case "DELETE_COLLECTION" -> result.add(new RetirementDelete(receipt.acceptedOrder(), null, null, receipt.payload()));
        default -> throw new IOException("Invalid retained delete receipt");
      }
    }
    return List.copyOf(result);
  }

  private long latestDelete(List<RetirementDelete> deletes, String path, String collection) {
    return deletes.stream().filter(d -> d.matches(path,collection)).mapToLong(RetirementDelete::order).max().orElse(0);
  }
  private record RetirementRoot(PathPrefixScope scope,
      JobQueue.EnqueueProvenance provenance, String collection, String policy) {}
  private record RetirementOrders(Map<String,Long> roots, Map<String,Long> files, Map<String,Long> fileWinners,
      Map<String,List<SwitchBufferUpsert>> filesByPath, Map<String,RetirementRoot> rootIntents,
      Map<String,Release030RecoveryState.Correction> corrections,
      Map<String,List<Release030RecoveryState.Correction>> predecessors, Map<String,SwitchBufferOp> receipts) {}

  private PathPrefixScope retirementScope(SwitchBufferOp receipt,List<SwitchBufferOp> roots) throws IOException {
    var declarations = new HashSet<>(
        io.justsearch.configuration.persistence.WatchedRootsFormat.declaredPaths(dbPath.getParent()));
    for (var root : roots) if (root.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX))
      declarations.add(Path.of(SwitchBufferSyncRoot.decode(root.payload()).rootPath()).toAbsolutePath().normalize());
    var intent=SwitchBufferSyncRoot.decode(receipt.payload());
    Path boundary=Path.of(intent.rootPath()).toAbsolutePath().normalize();
    return new PathPrefixScope(intent.rootPath(),declarations.stream()
        .filter(root -> !root.equals(boundary) && root.startsWith(boundary)).map(Path::toString).toList());
  }

  private RetirementOrders retirementOrders(List<SwitchBufferOp> journal) throws IOException {
    var roots = new HashMap<String,Long>();
    var receipts = releaseRecovery.rootReceipts();
    var intents = new HashMap<String,RetirementRoot>();
    for (var receipt : receipts) {
      var intent = SwitchBufferSyncRoot.decode(receipt.payload());
      Path boundary = Path.of(intent.rootPath()).toAbsolutePath().normalize();
      String canonical = PathNormalizer.normalizeKey(boundary);
      if (!canonical.equals(intent.rootPath())
          && !PathNormalizer.normalizePathPrefix(canonical).equals(intent.rootPath())) continue;
      var scope = releaseRecovery.rootScope(receipt);
      if (scope == null) scope = retirementScope(receipt,receipts); // Existing frozen root1 compatibility.
      roots.put(receipt.retirementUuid(), receipt.acceptedOrder());
      intents.put(receipt.retirementUuid(), new RetirementRoot(scope,
          intent.provenance(), retirementRootCollection(receipt), releaseRecovery.rootPolicy(receipt)));
    }
    var files = new HashMap<String,Long>();
    var winners = new HashMap<String,Long>();
    var filesByPath = new HashMap<String,List<SwitchBufferUpsert>>();
    for (var receipt : journal) if ("UPSERT".equals(receipt.op())) {
      var file = SwitchBufferUpsert.decode(receipt.payload());
      if (file.unitRevision() != null) {
        files.put(file.path() + "\0" + file.unitRevision(), receipt.acceptedOrder());
        winners.merge(file.path(),receipt.acceptedOrder(),Math::max);
        filesByPath.computeIfAbsent(file.path(),ignored->new ArrayList<>()).add(file);
      }
    }
    var corrections = new HashMap<String,Release030RecoveryState.Correction>();
    var predecessors = new HashMap<String,List<Release030RecoveryState.Correction>>();
    for (var version : releaseRecovery.corrections()) {
      if (corrections.put(version.replacement().unitRevision(),version) != null)
        throw new IOException("Duplicate correction identity");
      if (version.predecessor() != null) predecessors.computeIfAbsent(
          Objects.toString(version.predecessor().get("unit_revision"),""), ignored -> new ArrayList<>()).add(version);
    }
    var owners = new HashMap<String,SwitchBufferOp>();
    for (var receipt : journal) owners.put(receipt.retirementUuid(),receipt);
    for (var version : corrections.values()) if (version.owner() != null
        && !Objects.equals(owners.get(version.owner().retirementUuid()),version.owner()))
      throw new IOException("Correction lacks its actual original owner");
    return new RetirementOrders(Map.copyOf(roots), Map.copyOf(files), Map.copyOf(winners), Map.copyOf(filesByPath), Map.copyOf(intents),
        Map.copyOf(corrections), Map.copyOf(predecessors), Map.copyOf(owners));
  }

  private long actualUnitOrder(String path, String unit, RetirementOrders orders) throws IOException {
    var origin = RootOrigin.fromUnit(unit);
    if (origin != null) {
      if (!Objects.equals(orders.roots().get(origin.uuid()), origin.acceptedOrder()))
        throw new IOException("Unknown ROOT unit receipt");
      return origin.acceptedOrder();
    }
    Long exact = orders.files().get(path + "\0" + unit);
    return exact != null ? exact : unit != null && unit.startsWith("release030/") ? 0 : -1;
  }
  /** Receipt position alone never authorizes a changed row, even within one admission binding. */
  private long validatedUnitOrder(ClaimCandidate row, RetirementOrders orders) throws IOException, SQLException {
    return validatedUnitOrder(row,orders,false);
  }
  private long validatedUnitOrder(ClaimCandidate row, RetirementOrders orders, boolean retainedPredecessor)
      throws IOException, SQLException {
    String path = row.key().path();
    if (!path.equals(PathNormalizer.normalizeKey(Path.of(path)))) return -1;
    var correction = orders.corrections().get(row.revision());
    if (correction != null) {
      // Check the complete immutable packet before consulting its original owner. In particular,
      // a planned-hash ROOT never falls back to the ordinary null-plan ROOT predicate.
      if (!matchesCorrection(row,correction.replacement())) return -1;
      return correctionOwnerOrder(correction.owner(),correction.scope(),orders);
    }
    if (!retainedPredecessor) for (var pending : orders.predecessors().getOrDefault(Objects.toString(row.revision(),""),List.of())) {
      if (matchesPredecessor(row,pending.predecessor())) return -1;
    }
    long order = actualUnitOrder(path, row.revision(), orders);
    var origin = RootOrigin.fromUnit(row.revision());
    if (origin != null) {
      var intent = orders.rootIntents().get(origin.uuid());
      String boundary = PathNormalizer.normalizeKey(Path.of(intent.scope().prefix()));
      var provenance = intent.provenance();
      if (row.revision().startsWith("root2:")) {
        if (intent.policy() == null
            || !intent.scope().contains(path) || !Objects.equals(boundary,row.ingestionRoot())
            || row.walkEpoch() != null || correctionHasRecordedOwner(path,watcherRowLocked(path))
            || row.plannedHash() != null
            || io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(Path.of(path),Path.of(boundary))
            || !row.revision().split(":",-1)[4].equals(SwitchBufferCapableQueue.rootMemberDigest(
                path,row.collection(),row.originator(),row.transport(),row.ingestionRoot(),row.plannedHash()))) return -1;
        return order;
      }
      if (intent.collection() == null || !intent.scope().contains(path) || !Objects.equals(boundary, row.ingestionRoot())
          || io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(Path.of(path), Path.of(boundary))
          || !Objects.equals(provenance == null ? null : provenance.originator(), row.originator())
          || !Objects.equals(provenance == null ? null : provenance.transport(), row.transport())
          || !Objects.equals(normalizedRetirementCollection(intent.collection()), normalizedRetirementCollection(row.collection()))
          || row.plannedHash() != null) return -1;
      return order;
    }
    if (order > 0) {
      for (var file : orders.filesByPath().getOrDefault(path, List.of())) {
        if (!Objects.equals(file.unitRevision(), row.revision())) continue;
        var provenance = file.provenance();
        if (Objects.equals(file.sourceSha256(), row.plannedHash())
            && Objects.equals(file.collection(), row.collection())
            && Objects.equals(PathNormalizer.normalizeKey(file.ingestionRoot()), row.ingestionRoot())
            && Objects.equals(provenance == null ? null : provenance.originator(), row.originator())
            && Objects.equals(provenance == null ? null : provenance.transport(), row.transport())) return order;
      }
      return -1;
    }
    return order;
  }

  private static String normalizedRetirementCollection(String collection) {
    return collection == null || collection.isBlank() ? "default" : collection;
  }

  private static boolean matchesCorrection(ClaimCandidate row, SwitchBufferUpsert file) {
    var provenance = file.provenance();
    return file.path().equals(row.key().path()) && Objects.equals(file.unitRevision(),row.revision())
        && Objects.equals(file.sourceSha256(),row.plannedHash()) && Objects.equals(file.collection(),row.collection())
        && Objects.equals(PathNormalizer.normalizeKey(file.ingestionRoot()),row.ingestionRoot())
        && Objects.equals(provenance == null ? null : provenance.originator(),row.originator())
        && Objects.equals(provenance == null ? null : provenance.transport(),row.transport())
        && row.walkEpoch() == null;
  }

  private static boolean matchesPredecessor(ClaimCandidate row, Map<String,String> predecessor) {
    return predecessor != null && row.key().path().equals(predecessor.get("path"))
        && Objects.equals(row.revision(),predecessor.get("unit_revision"))
        && Objects.equals(row.collection(),predecessor.get("collection"))
        && Objects.equals(row.originator(),predecessor.get("originator"))
        && Objects.equals(row.transport(),predecessor.get("transport"))
        && Objects.equals(row.plannedHash(),predecessor.get("planned_source_sha256"))
        && Objects.equals(row.ingestionRoot(),predecessor.get("ingestion_root"))
        && Objects.equals(row.scanId(),predecessor.get("scan_id"))
        && Objects.equals(row.walkEpoch() == null ? null : row.walkEpoch().toString(),predecessor.get("walk_seen_epoch"));
  }

  private long correctionOwnerOrder(SwitchBufferOp owner, SwitchBufferUpsert scope,
      RetirementOrders orders) throws IOException {
    if (owner != null && !Objects.equals(orders.receipts().get(owner.retirementUuid()),owner)) return -1;
    if (owner != null && "SYNC_ROOT".equals(owner.op())) {
      var intent = orders.rootIntents().get(owner.retirementUuid());
      if (intent == null || intent.policy() == null || !intent.scope().contains(scope.path())
          || intent.collection() != null && !Objects.equals(intent.collection(),scope.collection())
          || intent.provenance() != null && !Objects.equals(intent.provenance(),scope.provenance())
          || !Objects.equals(PathNormalizer.normalizeKey(Path.of(intent.scope().prefix())),
              PathNormalizer.normalizeKey(scope.ingestionRoot()))
          || io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(
              Path.of(scope.path()),scope.ingestionRoot())) return -1;
      return owner.acceptedOrder();
    }
    if (owner != null && "DELETE".equals(owner.op()))
      return nativeConditionalCorrectionOwner(owner,scope,orders) ? owner.acceptedOrder() : -1;
    if (owner != null) {
      var frozen = releaseRecovery.legacyExpectation(owner);
      if (!"UPSERT".equals(owner.op()) || owner.payload().stripLeading().startsWith("{")
          || !Objects.equals(frozen,scope)) return -1;
      return owner.acceptedOrder();
    }
    String collection = releaseRecovery.retirementPaths().get(Path.of(scope.path()));
    return collection != null && Path.of(scope.path()).equals(scope.ingestionRoot())
        && Objects.equals(normalizedRetirementCollection(collection),normalizedRetirementCollection(scope.collection()))
        && (scope.provenance() == null
            || matchesIndependentRetirementScope(scope,watcherReconciliationRow(scope.path()))
            || orders.corrections().values().stream().anyMatch(version -> version.owner() == null
                && version.scope().equals(scope)
                && matchesIndependentRetirementScope(scope,version.predecessor()))) ? 0 : -1;
  }

  /** An unjournalled B difference retains its exact bounded Q attribution, never a ROOT role. */
  private static boolean matchesIndependentRetirementScope(SwitchBufferUpsert scope,Map<String,String> row) {
    var provenance = scope.provenance();
    return row != null && scope.path().equals(row.get("path"))
        && scope.path().equals(row.get("ingestion_root"))
        && isAdmissionRevision(row.get("unit_revision"))
        && Objects.equals(normalizedRetirementCollection(scope.collection()),normalizedRetirementCollection(row.get("collection")))
        && Objects.equals(provenance == null ? null : provenance.originator(),row.get("originator"))
        && Objects.equals(provenance == null ? null : provenance.transport(),row.get("transport"));
  }

  /** A native conditional event owns present-file reconciliation, never a historical ROOT role. */
  private boolean nativeConditionalCorrectionOwner(SwitchBufferOp owner, SwitchBufferUpsert scope,
      RetirementOrders orders) throws IOException {
    var deletion=SwitchBufferDelete.decode(owner.payload());
    if (deletion.origin()!=SwitchBufferDelete.Origin.LEGACY || !owner.generation().isBlank()
        || !owner.key().equals("path:"+deletion.path()) || !deletion.path().equals(scope.path())
        || !scope.path().equals(PathNormalizer.normalizeKey(scope.ingestionRoot()))
        || scope.collection()==null || scope.collection().isBlank() || scope.provenance()!=null
        || scope.unitRevision()!=null || scope.sourceSha256()!=null
        || !orders.filesByPath().getOrDefault(scope.path(),List.of()).isEmpty()
        || orders.rootIntents().values().stream().noneMatch(root -> "LEGACY_EXECUTION".equals(root.policy()))) return false;
    try {
      if (!releaseRecovery.preparedFor(release030RecoveryIdentity())
          || hasUnownedModernAdmission(Path.of(scope.path()))) return false;
      var row=watcherRowLocked(scope.path());
      if (row==null || correctionHasRecordedOwner(scope.path(),row)) return false;
      var seen=new HashSet<String>();
      while (true) {
        var version=orders.corrections().get(row.get("unit_revision"));
        if (version==null) {
          // A cancelled SQL transition may leave mutable retry fields on its original unit.
          var predecessor=row;
          version=orders.predecessors().getOrDefault(row.get("unit_revision"),List.of()).stream()
              .filter(v -> Objects.equals(v.owner(),owner) && v.scope().equals(scope)
                  && sameCorrectionPredecessor(v.predecessor(),predecessor)).findFirst().orElse(null);
          if (version==null) break;
        } else if (!matchesCorrection(correctionCandidate(row),version.replacement())) return false;
        if (!Objects.equals(version.owner(),owner) || !version.scope().equals(scope)
            || version.predecessor()==null || !seen.add(version.replacement().unitRevision())) return false;
        row=version.predecessor();
        // An intent whose SQL rolled back points to the same original unit.
        if (row.get("unit_revision")==null) return false;
        if (row.get("unit_revision").startsWith("release030/job/")) break;
      }
      String unit=row.get("unit_revision");
      String cohort=releaseRecovery.nativeCohort();
      return cohort!=null && unit!=null && unit.matches("release030/job/"+cohort+"/[0-9a-f]{32}")
          && STATE_DONE.equals(row.get("state")) && row.get("retry_after")==null
          && Objects.equals(scope.collection(),row.get("collection"))
          && row.get("ingestion_root")==null && row.get("planned_source_sha256")==null
          && row.get("originator")==null && row.get("transport")==null
          && !correctionHasRecordedOwner(scope.path(),row);
    } catch (SQLException invalid) { throw new IOException("Native conditional predecessor unavailable",invalid); }
  }

  private boolean currentCorrectionOwner(SwitchBufferOp owner, SwitchBufferUpsert scope,
      RetirementOrders orders, List<RetirementDelete> deletes) throws IOException {
    long order = correctionOwnerOrder(owner,scope,orders);
    if (order < 0 || orders.fileWinners().getOrDefault(scope.path(),0L) > order
        || latestDelete(deletes,scope.path(),scope.collection()) > order) return false;
    for (var root : orders.rootIntents().entrySet())
      if (root.getValue().scope().contains(scope.path()) && orders.roots().get(root.getKey()) > order
          && !orders.receipts().get(root.getKey()).key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX)
          // A force-false declaration does not create ownership of independent B differences.
          // Their current Q row still has to pass the complete correction/claim checks.
          && !((owner==null || "DELETE".equals(owner.op())) && root.getValue().policy()!=null
              && !SwitchBufferSyncRoot.decode(orders.receipts().get(root.getKey()).payload()).force())) return false;
    return true;
  }

  /** An actual ordinary indexed owner remains responsible when force=false did not admit it. */
  private boolean preservedIndexedRootOwner(SwitchBufferOp owner,SwitchBufferUpsert scope,
      Map<String,String> row,RetirementOrders orders,List<RetirementDelete> deletes) throws IOException,SQLException {
    return preservedIndexedRootOwner(owner,scope,row,orders,deletes,false);
  }
  private boolean preservedIndexedRootOwner(SwitchBufferOp owner,SwitchBufferUpsert scope,
      Map<String,String> row,RetirementOrders orders,List<RetirementDelete> deletes,boolean predecessor) throws IOException,SQLException {
    if(owner==null || !"SYNC_ROOT".equals(owner.op())
        || owner.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX)
        || retirementRootLegacyExecution(owner) || row==null || !STATE_DONE.equals(row.get("state"))
        || row.get("retry_after")!=null
        || validatedUnitOrder(correctionCandidate(row),orders,predecessor)!=owner.acceptedOrder()
        || correctionOwnerOrder(owner,scope,orders)!=owner.acceptedOrder()
        || orders.fileWinners().getOrDefault(scope.path(),0L)>owner.acceptedOrder()
        || latestDelete(deletes,scope.path(),scope.collection())>owner.acceptedOrder())return false;
    for(var later:orders.rootIntents().entrySet()) {
      if(!later.getValue().scope().contains(scope.path()) || orders.roots().get(later.getKey())<=owner.acceptedOrder())continue;
      var receipt=orders.receipts().get(later.getKey());
      if(receipt.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX))continue;
      if(later.getValue().policy()==null || SwitchBufferSyncRoot.decode(receipt.payload()).force())return false;
    }
    return true;
  }

  private boolean preservedCorrectionOwner(Release030RecoveryState.Correction correction,
      RetirementOrders orders,List<RetirementDelete> deletes) throws IOException,SQLException {
    var seen=new HashSet<String>();var current=correction;
    while(current.predecessor()!=null) {
      var predecessor=current.predecessor();
      var earlier=orders.corrections().get(predecessor.get("unit_revision"));
      if(earlier==null)return preservedIndexedRootOwner(current.owner(),current.scope(),predecessor,orders,deletes,true);
      if(!seen.add(earlier.replacement().unitRevision()) || !Objects.equals(earlier.owner(),correction.owner())
          || !earlier.scope().equals(correction.scope())
          || !matchesCorrection(correctionCandidate(predecessor),earlier.replacement()))return false;
      current=earlier;
    }
    return false;
  }

  /** Current applicable retained ROOT, including a successor whose walk is still pending. */
  public SwitchBufferOp retirementCorrectionRoot(String path) {
    lockTimed();
    try {
      var orders = retirementOrders(retirementReplayOps());
      return releaseRecovery.rootReceipts().stream().filter(root -> {
        var intent = orders.rootIntents().get(root.retirementUuid());
        return intent != null && intent.scope().contains(path)
            && !root.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX);
      }).max(java.util.Comparator.comparingLong(SwitchBufferOp::acceptedOrder)).orElse(null);
    } catch (IOException invalid) { throw new IllegalStateException("Current ROOT ownership unavailable",invalid); }
    finally { unlockAfterChanges(); }
  }

  public void removeRetirementCollection(String collection, List<String> survivors) {
    lockTimed();
    try {
      ensureOpen();
      var excluded = normalizeSurvivorPaths(survivors);
      String cursor = "";
      while (true) {
        var page = new ArrayList<String>();
        try (var query = connection.prepareStatement(
            "SELECT path FROM jobs WHERE collection=? AND path>? ORDER BY path LIMIT 256")) {
          query.setString(1, collection); query.setString(2, cursor);
          try (var rows = query.executeQuery()) { while (rows.next()) page.add(rows.getString(1)); }
        }
        if (page.isEmpty()) break;
        String last = page.getLast();
        // The existing owner preserves recorded membership/ledger rules. Each transaction
        // materializes at most this page; an interrupted removal retains its replay receipt.
        removeJobsAdministratively("collection=? AND path>? AND path<=?",
            List.of(collection, cursor, last), true, excluded);
        cursor = last;
      }
    } catch (SQLException invalid) {
      throw new IllegalStateException("Retirement Q collection removal failed", invalid);
    } finally { unlockAfterChanges(); }
  }

  public record RootMember(String path,String unit,String collection,String boundary,String state,String hash,
      SwitchBufferOp root) {}
  /** Bounded pages of existing Q evidence; no independent membership census is persisted. */
  public List<RootMember> rootMembersAfter(String cursor) {
    lockTimed();try {
      var result=new ArrayList<RootMember>();
      var orders = retirementOrders(retirementReplayOps());
      var roots = orders.receipts();
      try(var q=connection.prepareStatement("SELECT path,unit_revision,collection,ingestion_root,state,content_hash,originator,transport,scan_id,walk_seen_epoch,planned_source_sha256 FROM jobs WHERE path>? AND (unit_revision LIKE 'root1:%' OR unit_revision LIKE 'root2:%') ORDER BY path LIMIT 256")) {
        q.setString(1,cursor);try(var r=q.executeQuery()){while(r.next()){
          var origin=RootOrigin.fromUnit(r.getString(2));
          var receipt=roots.get(origin.uuid());
          if (receipt == null || receipt.acceptedOrder()!=origin.acceptedOrder()) throw new IOException("Missing ROOT owner");
          long epoch=r.getLong(10); Long walkEpoch=r.wasNull()?null:epoch;
          var candidate = new ClaimCandidate(new ClaimKey(0,r.getString(1)),r.getString(3),r.getString(7),r.getString(8),
              r.getString(9),r.getString(2),walkEpoch,r.getString(11),r.getString(4));
          String memberState=validatedUnitOrder(candidate,orders)<0?"UNVERIFIED":r.getString(5);
          result.add(new RootMember(r.getString(1),r.getString(2),r.getString(3),r.getString(4),memberState,r.getString(6),receipt));
        }}
      }return List.copyOf(result);
    }catch(SQLException|IOException invalid){throw new IllegalStateException("ROOT member evidence unavailable",invalid);}
    finally{unlockAfterChanges();}
  }
  public java.util.function.ToLongBiFunction<String,String> retirementDeleteOrders() {
    lockTimed();
    try {
      var deletes = retirementDeletes(retirementReplayOps());
      return (path,collection) -> latestDelete(deletes,path,collection);
    } catch (IOException invalid) { throw new IllegalStateException("ROOT delete order unavailable",invalid); }
    finally { unlockAfterChanges(); }
  }

  /** Reconcile each validated admission set once, before claims or ordinary writer waits. */
  public void prepareRetirementClaims() throws SQLException, IOException {
    lockTimed();
    try {
      if (releaseRecovery == null || !releaseRecovery.retiringBuild() || !releaseRecovery.rootsInventoried()) return;
      var journal = retirementReplayOps();
      String binding = releaseRecovery.admissionBinding();
      if (rootClaimsPrepared && Objects.equals(retirementClaimsBinding, binding)) return;
      var orders = retirementOrders(journal);
      var deletes = retirementDeletes(journal);
      var historical = releaseRecovery.committedDeletes();
      var retained = retainedRootScopes(journal);
      inTransaction(() -> {
        reconcileRetirementClaimsInTransaction(orders, deletes, retained);
        return null;
      });
      // The SQL owner has committed. A retained recorded row is either a later validated
      // owner or is fenced by the same full-row predicate used at every actual claim.
      if (!Objects.equals(binding, releaseRecovery.admissionBinding()))
        throw new IOException("Retirement admissions changed during reconciliation");
      releaseRecovery.queuePrepared();
      for (var deletion : historical) releaseRecovery.markDeletionQueueComplete(deletion);
      retirementClaimsBinding = binding;
      rootClaimsPrepared = true;
      claimCursor = null;
      claimOrderVersion++;
    } finally { unlockAfterChanges(); }
  }

  private List<ClaimCandidate> retirementRowsAfter(String cursor, String end) throws SQLException {
    var page = new ArrayList<ClaimCandidate>();
    try (var query = connection.prepareStatement(
        "SELECT path,last_updated,collection,originator,transport,scan_id,unit_revision,"
            + "walk_seen_epoch,planned_source_sha256,ingestion_root FROM jobs WHERE path>?"
            + (end == null ? " ORDER BY path LIMIT 256" : " AND path<=? ORDER BY path"))) {
      query.setString(1, cursor);
      if (end != null) query.setString(2, end);
      try (var row = query.executeQuery()) {
        while (row.next()) {
          long epoch = row.getLong(8); Long walkEpoch = row.wasNull() ? null : epoch;
          page.add(new ClaimCandidate(new ClaimKey(row.getLong(2),row.getString(1)),
              row.getString(3),row.getString(4),row.getString(5),row.getString(6),row.getString(7),
              walkEpoch,row.getString(9),row.getString(10)));
        }
      }
    }
    return page;
  }

  private void reconcileRetirementClaimsInTransaction(RetirementOrders orders,
      List<RetirementDelete> deletes, java.util.function.BiPredicate<String,String> retained) throws SQLException {
    String cursor = "";
    while (true) {
      var page = retirementRowsAfter(cursor, null);
      if (page.isEmpty()) return;
      for (var row : page) {
        long order;
        try { order = validatedUnitOrder(row, orders); }
        catch (IOException | RuntimeException unknown) { continue; } // Unknown ownership stays claim-held.
        // A legacy cohort prefix establishes age, not its original full metadata. Without
        // a validated exact/ROOT owner it remains claim-held under any overriding delete.
        if (order > 0 && Math.max(latestDelete(deletes, row.key().path(), row.collection()),
            orders.fileWinners().getOrDefault(row.key().path(), 0L)) > order) {
          removeJobsAdministrativelyInTransaction("path=? AND unit_revision IS ? AND collection IS ?"
                  + " AND originator IS ? AND transport IS ? AND planned_source_sha256 IS ? AND ingestion_root IS ?",
              java.util.Arrays.asList(row.key().path(), row.revision(), row.collection(), row.originator(),
                  row.transport(), row.plannedHash(), row.ingestionRoot()), true, Set.of());
        }
      }
      String last = page.getLast().key().path();
      // Administrative coverage can retain DONE members or sealed unacknowledged rows.
      // Classify actual survivors, rather than treating affected-row counts as absence.
      for (var row : retirementRowsAfter(cursor, last)) {
        long deleted = latestDelete(deletes, row.key().path(), row.collection());
        if (deleted == 0) continue;
        try {
          long order = validatedUnitOrder(row, orders);
          if (order > deleted && order >= orders.fileWinners().getOrDefault(row.key().path(), 0L)) continue;
        } catch (IOException | RuntimeException unknown) { /* Must be claim-held below. */ }
        if (retirementAllowsClaim(row, orders, deletes, retained, true))
          throw new SQLException("Unreconciled retirement member remains claimable");
      }
      cursor = last;
    }
  }
  private boolean retirementAllowsClaim(ClaimCandidate row, RetirementOrders orders,
      List<RetirementDelete> deletes, java.util.function.BiPredicate<String,String> retained) {
    return retirementAllowsClaim(row, orders, deletes, retained, rootClaimsPrepared);
  }
  private boolean retirementAllowsClaim(ClaimCandidate row, RetirementOrders orders,
      List<RetirementDelete> deletes, java.util.function.BiPredicate<String,String> retained, boolean prepared) {
    if (orders == null) return true;
    try {
      long deleted = latestDelete(deletes, row.key().path(), row.collection());
      long newerFile = orders.fileWinners().getOrDefault(row.key().path(),0L);
      if (!retained.test(row.key().path(),row.revision()) && deleted == 0 && newerFile == 0) return true;
      if (!prepared) return false;
      var correction = orders.corrections().get(row.revision());
      if (correction != null && !currentCorrectionOwner(correction.owner(),correction.scope(),orders,deletes)
          && !preservedCorrectionOwner(correction,orders,deletes)) return false;
      long order = validatedUnitOrder(row, orders);
      return order >= 0 && (deleted == 0 || order > deleted) && (newerFile == 0 || order >= newerFile);
    } catch (IOException | SQLException | RuntimeException unavailable) { return false; }
  }

  private void replaceLegacyPrefix(SwitchBufferOp before, SwitchBufferOp after) throws SQLException {
    try (var update = connection.prepareStatement("UPDATE switch_buffer SET payload=?,revision=? WHERE generation=? AND key=? AND revision=? AND payload=? AND accepted_order=? AND last_updated=?")) {
      update.setString(1,after.payload()); update.setString(2,after.revision()); update.setString(3,before.generation());
      update.setString(4,before.key()); update.setString(5,before.revision()); update.setString(6,before.payload());
      update.setLong(7,before.acceptedOrder()); update.setLong(8,before.lastUpdatedMs());
      if (update.executeUpdate()!=1) throw new SQLException("Legacy prefix receipt changed during conversion");
    }
    if (releaseRecovery != null && releaseRecovery.retiringBuild()) releaseRecovery.admissionsChanged();
  }

  private void applyRetainedPrefixConversions() throws SQLException, IOException {
    var pairs = releaseRecovery.prefixConversions();
    inTransaction(() -> {
      var current = switchBufferOps.listAllStrict();
      for (var pair : pairs) if (current.contains(pair.predecessor())) replaceLegacyPrefix(pair.predecessor(),pair.successor());
      return null;
    });
  }

  /** Reconstruct a missing accepted exact unit from its receipt, never a fresh acknowledgement. */
  public void recoverRetirementExact(SwitchBufferUpsert file) {
    lockTimed();
    try {
      ensureOpen();
      inTransaction(() -> {
        var journal = retirementReplayOps();
        var matching = journal.stream().filter(op -> "UPSERT".equals(op.op())
            && SwitchBufferUpsert.decode(op.payload()).equals(file)).findFirst().orElseThrow(
                () -> new IllegalStateException("Exact recovery lacks its original receipt"));
        try {
        var orders = retirementOrders(journal);
        if (orders.fileWinners().getOrDefault(file.path(),0L)>matching.acceptedOrder()
            || latestDelete(retirementDeletes(journal),file.path(),file.collection())>matching.acceptedOrder()) return null;
        var row = watcherRowLocked(file.path());
        if (row != null) {
          long order = actualUnitOrder(file.path(),row.get("unit_revision"),orders);
          if (order<0 || order>=matching.acceptedOrder()) return null;
        }
        if (enqueueEntriesInTransaction(List.of(file.entry()),file.collection(),null,System.currentTimeMillis(),
            true,file.unitRevision())!=1) throw new SQLException("Short accepted exact recovery");
        claimCursor=null;
        return null;
        } catch (IOException invalid) { throw new SQLException("Exact recovery evidence unavailable",invalid); }
      });
    } catch (SQLException invalid) { throw new IllegalStateException("Accepted exact recovery failed",invalid); }
    finally { unlockAfterChanges(); }
  }

  private static final List<String> CORRECTION_PREDECESSOR_IDENTITY = List.of("path", "unit_revision",
      "collection", "originator", "transport", "scan_id", "walk_seen_epoch", "planned_source_sha256",
      "ingestion_root", "size_bytes");

  private static boolean sameCorrectionPredecessor(Map<String,String> left, Map<String,String> right) {
    if (left == null || right == null) return left == right;
    return CORRECTION_PREDECESSOR_IDENTITY.stream().allMatch(key -> Objects.equals(left.get(key),right.get(key)));
  }

  private static ClaimCandidate correctionCandidate(Map<String,String> row) {
    return new ClaimCandidate(new ClaimKey(Long.parseLong(row.get("last_updated")),row.get("path")),
        row.get("collection"),row.get("originator"),row.get("transport"),row.get("scan_id"),row.get("unit_revision"),
        row.get("walk_seen_epoch") == null ? null : Long.valueOf(row.get("walk_seen_epoch")),
        row.get("planned_source_sha256"),row.get("ingestion_root"));
  }

  private boolean correctionHasRecordedOwner(String path, Map<String,String> row) throws SQLException {
    if (row == null) return false;
    if (row.get("walk_seen_epoch") != null) return true;
    if (row.get("scan_id") != null && SqliteIngestionWalkOps.find(connection,row.get("scan_id")).isPresent()) return true;
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM ingestion_ledger WHERE path_hash=? AND unit_revision=? AND operation_key IS NOT NULL LIMIT 1")) {
      query.setString(1,sha256(path)); query.setString(2,row.get("unit_revision"));
      try (var result = query.executeQuery()) { return result.next(); }
    }
  }

  @Override public List<Integer> correctRetirementSources(List<RetirementSourceObservation> observations,
      java.util.function.BooleanSupplier cancelled) {
    if (observations.isEmpty()) return List.of();
    lockTimed();
    try {
      ensureOpen();
      if (releaseRecovery == null || !releaseRecovery.retiringBuild()
          || !Objects.equals(release030RetirementGeneration(),releaseRecovery.retiringBuilding()))
        return java.util.Collections.nCopies(observations.size(),0);
      return inTransaction(() -> {
        try {
          var journal = retirementReplayOps();
          var orders = retirementOrders(journal);
          var deletes = retirementDeletes(journal);
          var results = new ArrayList<Integer>();
          for (var observation : observations)
            results.add(correctRetirementSourceInTransaction(observation,journal,orders,deletes,cancelled));
          return List.copyOf(results);
        } catch (IOException invalid) { throw new SQLException("Correction ownership unavailable",invalid); }
      });
    } catch (SQLException invalid) { throw new IllegalStateException("Conditional retirement correction failed",invalid); }
    finally { unlockAfterChanges(); }
  }

  private int correctRetirementSourceInTransaction(RetirementSourceObservation observation,
      List<SwitchBufferOp> journal, RetirementOrders orders, List<RetirementDelete> deletes,
      java.util.function.BooleanSupplier cancelled) throws SQLException, IOException {
    var owner = observation.owner();
    var scope = observation.scope();
    var observedRow = observation.observedRow();
    var sourceState = observation.sourceState();
    var sourceSha256 = observation.sourceSha256();
    long size = observation.size();
    boolean targetMatches = observation.targetMatches();
    boolean removal = sourceSha256 == null && sourceState != null
        && (sourceState.equals("gone") || sourceState.startsWith("directory/")
            || sourceState.startsWith("link/") || sourceState.startsWith("other/"));
    if (sourceState == null || !targetMatches && !removal
        && !JobQueue.IngestionLedgerTransition.isSha256(sourceSha256)) return 0;
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Correction cancelled");
    var row = watcherRowLocked(scope.path());
    if (!Objects.equals(row,observedRow) || activeClaims.containsKey(scope.path())
        || row != null && STATE_PROCESSING.equals(row.get("state"))
        || correctionHasRecordedOwner(scope.path(),row)) return 0;
    Release030RecoveryState.Correction installed = row == null ? null : orders.corrections().get(row.get("unit_revision"));
    var versions = orders.corrections().values();
    var retained = versions.stream().filter(version -> Objects.equals(version.owner(),owner)
        && version.scope().equals(scope) && sameCorrectionPredecessor(version.predecessor(),row))
        .findFirst().orElse(null);
    if (owner != null && "DELETE".equals(owner.op()) && !observation.indexedParentMatches()) return 0;
    if (owner != null && !journal.contains(owner)
        || !(currentCorrectionOwner(owner,scope,orders,deletes)
            || (targetMatches || observation.indexedParentMatches())
                && (preservedIndexedRootOwner(owner,scope,row,orders,deletes)
                    || installed!=null && preservedCorrectionOwner(installed,orders,deletes)
                    || retained!=null && preservedCorrectionOwner(retained,orders,deletes)))) return 0;
    if (installed != null && (!Objects.equals(installed.owner(),owner) || !installed.scope().equals(scope)
        || !matchesCorrection(correctionCandidate(row),installed.replacement()))) return 0;
    // Stable source retries and terminal POLICY remain the same work. The packet does not
    // prove which bytes the extractor observed before a policy skip.
    boolean retainedPredecessor = retained != null;
    if (row != null && installed == null && !retainedPredecessor) {
      if (!STATE_DONE.equals(row.get("state"))) return 0;
      String unit = row.get("unit_revision");
      String cohort = releaseRecovery.nativeCohort();
      boolean nativeRow = cohort != null && unit != null
          && unit.matches("release030/(job|debt)/"+cohort+"/[0-9a-f]{32}");
      if (owner == null && !nativeRow && !matchesIndependentRetirementScope(scope,row)) return 0;
      var provenance = scope.provenance();
      if (!Objects.equals(normalizedRetirementCollection(row.get("collection")),normalizedRetirementCollection(scope.collection()))
          || !Objects.equals(row.get("originator"),provenance == null ? null : provenance.originator())
          || !Objects.equals(row.get("transport"),provenance == null ? null : provenance.transport())) return 0;
      String boundary = PathNormalizer.normalizeKey(scope.ingestionRoot());
      if (!Objects.equals(boundary,row.get("ingestion_root"))) {
        // A prefix alone cannot establish bootstrap provenance. Only this database's
        // retained preparation receipt plus its actual v13 unit permits a missing FILE.
        if (owner != null && "SYNC_ROOT".equals(owner.op()) || row.get("ingestion_root") != null
            || !scope.path().equals(boundary) || hasUnownedModernAdmission(Path.of(scope.path()))
            || !releaseRecovery.preparedFor(release030RecoveryIdentity())) return 0;
      }
      // A completed exact B row can outlive its FILE receipt. Its retained difference
      // and exact Q boundary still own the source; ROOT rows keep their separate proof.
      if (row.get("planned_source_sha256") != null
          && (owner != null || !matchesIndependentRetirementScope(scope,row)
              || !JobQueue.IngestionLedgerTransition.isSha256(row.get("planned_source_sha256")))) return 0;
      if (owner != null && "SYNC_ROOT".equals(owner.op())) {
        var origin = RootOrigin.fromUnit(row.get("unit_revision"));
        boolean administrative = owner.key().startsWith(Release030RecoveryState.RETIREMENT_ROOT_KEY_PREFIX);
        if (origin == null || (!administrative && !origin.uuid().equals(owner.retirementUuid()))
            || (!administrative && origin.acceptedOrder() != owner.acceptedOrder())
            || validatedUnitOrder(correctionCandidate(row),orders) != origin.acceptedOrder()
            || origin.acceptedOrder()>owner.acceptedOrder()) return 0;
      }
    }
    if (targetMatches) return 2;
    // Permission is not settlement: the caller must recheck absence, remove A's image,
    // commit it, then bring a fresh target proof. No fictitious UPSERT hash is admitted.
    if (removal) return row == null || STATE_DONE.equals(row.get("state")) ? 3 : 0;
    if (installed != null && sourceState.equals(installed.sourceState())) return 0;
    Release030RecoveryState.Correction version = versions.stream()
        .filter(v -> Objects.equals(v.owner(),owner) && v.scope().equals(scope)
            && sameCorrectionPredecessor(v.predecessor(),row) && sourceState.equals(v.sourceState()))
        .findFirst().orElse(null);
    if (version == null) {
      var provenance = scope.provenance();
      String unit = owner != null && "SYNC_ROOT".equals(owner.op())
          ? "root2:" + owner.retirementUuid() + ":" + owner.acceptedOrder() + ":"
              + UUID.randomUUID().toString().replace("-", "") + ":"
              + SwitchBufferCapableQueue.rootMemberDigest(scope.path(),scope.collection(),
                  provenance == null ? null : provenance.originator(),provenance == null ? null : provenance.transport(),
                  PathNormalizer.normalizeKey(scope.ingestionRoot()),sourceSha256)
          : UUID.randomUUID().toString().replace("-", "");
      var replacement = new SwitchBufferUpsert(scope.path(),scope.collection(),scope.provenance(),unit,
          sourceSha256,scope.ingestionRoot());
      version = new Release030RecoveryState.Correction(owner,releaseRecovery.retiringActive(),
          releaseRecovery.retiringBuilding(),scope,row,sourceState,replacement);
      releaseRecovery.retainCorrection(version); // Durable intent precedes SQL CAS.
    }
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Correction cancelled");
    var replacement = version.replacement();
    if (row != null && !STATE_DONE.equals(row.get("state"))) {
      String predicate = String.join(" AND ",row.keySet().stream().map(key -> key + " IS ?").toList());
      try (var update = connection.prepareStatement("UPDATE jobs SET unit_revision=?,planned_source_sha256=? WHERE " + predicate)) {
        update.setString(1,replacement.unitRevision()); update.setString(2,replacement.sourceSha256());
        int parameter=3; for (String value : row.values()) update.setString(parameter++,value);
        if (update.executeUpdate()!=1) throw new SQLException("Correction predecessor changed");
      }
    } else if (enqueueEntriesInTransaction(List.of(new JobQueue.EnqueueEntry(Path.of(scope.path()),size,
        replacement.provenance(),replacement.sourceSha256(),replacement.ingestionRoot())),
        replacement.collection(),null,System.currentTimeMillis(),true,replacement.unitRevision()) != 1)
      throw new SQLException("Short corrective admission");
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Correction cancelled");
    rootClaimsPrepared = false;
    claimCursor = null;
    claimOrderVersion++;
    return 1;
  }

  /** Queue-owned union is validated before claims and every replay; no checkpoint leaf calls Q. */
  public List<SwitchBufferOp> retirementReplayOps() {
    lockTimed();
    try {
      ensureOpen();
      var actual = switchBufferOps.listAllStrict();
      if (releaseRecovery == null || !releaseRecovery.retiringBuild()) return actual;
      var receipts = releaseRecovery.retirementReceipts(actual);
      if (releaseRecovery.rootsInventoried()) releaseRecovery.validateRootHeads(actual.stream().filter(r -> "SYNC_ROOT".equals(r.op())).toList());
      releaseRecovery.bindAdmissions(receipts);
      return receipts;
    } catch (IOException invalid) { throw new IllegalStateException(MigrationJournalWitness.REFUSAL + ": " + invalid.getMessage(),invalid); }
    finally { unlockAfterChanges(); }
  }

  public boolean retirementReplayComplete() {
    retirementReplayOps();
    return releaseRecovery != null && releaseRecovery.replayComplete();
  }

  /** Authorization is durable first. Conditional SQL removal is never a current-Q proof. */
  public boolean acknowledgeRetirementReplay(List<SwitchBufferOp> verified,
      Map<String,SwitchBufferUpsert> legacyFiles) {
    lockTimed();
    try {
      ensureOpen();
      return inTransaction(() -> {
        try {
          var before = retirementReplayOps();
          if (!before.equals(verified)) return false;
          releaseRecovery.retainRemovalPackets(verified,legacyFiles);
          var actual = switchBufferOps.listAllStrict();
          var removable = new ArrayList<SwitchBufferOp>();
          for (var expected : verified) {
            for (var row : actual) if (row.retirementUuid().equals(expected.retirementUuid())) {
              if (!row.equals(expected)) throw new SQLException("Retirement ACK receipt changed");
              removable.add(row);
            }
          }
          switchBufferOps.removeReplayedLocked(removable);
          var surviving = switchBufferOps.listAllStrict();
          if (surviving.stream().anyMatch(verified::contains)) return false;
          var after = retirementReplayOps();
          return after.equals(verified);
        } catch (IOException invalid) { throw new SQLException("Retirement removal packet could not be saved",invalid); }
      });
    } catch (SQLException invalid) { throw new IllegalStateException("Retirement conditional ACK failed",invalid); }
    finally { unlockAfterChanges(); }
  }

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  @Override
  public List<SwitchBufferCapableQueue.SwitchBufferOp> listSwitchBufferOps() {
    return switchBufferOps.listAll();
  }

  @Override
  public List<SwitchBufferCapableQueue.SwitchBufferOp> listSwitchBufferOpsStrict() {
    return switchBufferOps.listAllStrict();
  }

  @Override
  public List<SwitchBufferCapableQueue.SwitchBufferOp> listSwitchBufferOpsStrictForGeneration(
      String generation) {
    return switchBufferOps.listAllStrictForGeneration(generation);
  }

  @Override
  public int removeReplayedSwitchBufferOps(List<SwitchBufferCapableQueue.SwitchBufferOp> replayed) {
    var snapshot = List.copyOf(replayed);
    for (var entry : snapshot) {
      if (entry.revision() == null || entry.revision().isBlank()) {
        throw new IllegalArgumentException("Replayed buffer entry has no replacement identity");
      }
    }
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> switchBufferOps.removeReplayedLocked(snapshot));
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Failed to remove replayed switch-buffer versions", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int removeReplayedSwitchBufferOpsForGeneration(
      String generation, List<SwitchBufferCapableQueue.SwitchBufferOp> replayed) {
    if (generation == null || generation.isBlank()) {
      throw new IllegalArgumentException("Generation must be non-blank");
    }
    var snapshot = List.copyOf(replayed);
    for (var entry : snapshot) {
      if (!generation.equals(entry.generation())) {
        throw new IllegalArgumentException("Replay entry belongs to a different generation");
      }
      if (entry.revision() == null || entry.revision().isBlank()) {
        throw new IllegalArgumentException("Replayed buffer entry has no replacement identity");
      }
    }
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> switchBufferOps.removeReplayedLocked(snapshot));
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Failed to remove replayed switch-buffer versions", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Removes every unchanged version of a settled snapshot, or none. A removal that leaves any
   * selected version behind is rolled back in the same transaction, so a later receipt whose
   * effect supersedes an earlier one is never acknowledged without it.
   */
  public boolean removeEveryReplayedSwitchBufferOp(List<SwitchBufferCapableQueue.SwitchBufferOp> replayed) {
    var snapshot = List.copyOf(replayed);
    for (var entry : snapshot) {
      if (entry.revision() == null || entry.revision().isBlank()) {
        throw new IllegalArgumentException("Replayed buffer entry has no replacement identity");
      }
    }
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> {
        var removal = connection.setSavepoint();
        boolean complete = switchBufferOps.removeReplayedLocked(snapshot) == snapshot.size();
        if (!complete) connection.rollback(removal);
        connection.releaseSavepoint(removal);
        return complete;
      });
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Failed to remove replayed switch-buffer versions", failure);
    } finally {
      unlockAfterChanges();
    }
  }


  @Override
  public int enqueue(List<Path> paths, String collection) {
    return enqueueEntries(JobQueue.EnqueueEntry.ofUnknownSizes(paths), collection);
  }

  /**
   * Untagged enqueue of sized entries. Overridden (rather than inherited) because the interface
   * default routes back through {@link #enqueue(List, String)}, which lands here again — this is
   * the one hop that ends the cycle at the real write path.
   */
  @Override
  public int enqueueEntries(List<JobQueue.EnqueueEntry> entries, String collection) {
    return enqueueEntries(entries, collection, null, false);
  }

  /**
   * Committed boot root rebinding path. The declared collection is authoritative, including a
   * deliberate null that clears a collection carried by the replaced row. Scan ownership and
   * provenance still use the ordinary maintenance carry-forward rules.
   */
  @Override
  public int enqueueEntriesWithExactCollection(
      List<JobQueue.EnqueueEntry> entries, String collection) {
    return enqueueEntries(entries, collection, null, true);
  }

  /** Reads one committed boot root row under the queue lock. */
  @Override
  public boolean matchesExpectedCollection(Path path, String expectedCollection) {
    if (path == null) return false;
    String normalizedPath =
        PathNormalizer.normalizePath(path.toAbsolutePath().toString());
    String normalizedCollection =
        expectedCollection == null || expectedCollection.isBlank() ? null : expectedCollection;
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT collection FROM jobs WHERE path = ?")) {
        query.setString(1, normalizedPath);
        try (ResultSet row = query.executeQuery()) {
          return !row.next() || Objects.equals(normalizedCollection, row.getString(1));
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Expected collection row is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** Reads whether a non-terminal job remains under one literal normalized path prefix. */
  @Override
  public boolean hasNonterminalJobsByPathPrefixStrict(String pathPrefix) {
    return hasNonterminalJobsByPathPrefixStrict(pathPrefix, List.of());
  }

  @Override
  public boolean hasNonterminalJobsByPathPrefixStrict(String pathPrefix, List<String> excludedSubtrees) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must be non-blank");
    }
    String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
    if (lower == null || lower.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must normalize to non-blank");
    }
    String upper = upperBoundExclusive(lower);
    lock.lock();
    try {
      ensureOpen();
      var parameters = new ArrayList<String>(List.of(lower, upper));
      String predicate = prefixExclusions(excludedSubtrees, parameters);
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT 1 FROM jobs WHERE state IN ('PENDING', 'PROCESSING') "
              + "AND path >= ? AND path < ?" + predicate + " LIMIT 1")) {
        for (int i = 0; i < parameters.size(); i++) query.setString(i + 1, parameters.get(i));
        try (ResultSet row = query.executeQuery()) {
          return row.next();
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Non-terminal path-prefix scope is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** Reads a literal prefix while excluding exact certified replay paths. */
  @Override
  public boolean hasJobsByPathPrefixOutsideCertifiedReplayPaths(
      String pathPrefix, List<String> certifiedReplayPaths) {
    return hasJobsByPathPrefixOutsideCertifiedReplayPaths(pathPrefix, certifiedReplayPaths, List.of());
  }

  @Override
  public boolean hasJobsByPathPrefixOutsideCertifiedReplayPaths(String pathPrefix, List<String> certifiedReplayPaths, List<String> excludedSubtrees) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must be non-blank");
    }
    String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
    if (lower == null || lower.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must normalize to non-blank");
    }
    String upper = upperBoundExclusive(lower);
    Set<String> excluded = normalizeSurvivorPaths(certifiedReplayPaths);
    var scope = new PathPrefixScope(pathPrefix, excludedSubtrees);
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT path FROM jobs "
              + "WHERE path >= ? AND path < ?")) {
        query.setString(1, lower);
        query.setString(2, upper);
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            String path = rows.getString("path");
            if (excluded.contains(path) || scope.excludes(path)) continue;
            return true;
          }
          return false;
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException(
          "Path-prefix scope outside certified replay paths is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** Reads whether a non-terminal job remains in one collection, including legacy default rows. */
  @Override
  public boolean hasNonterminalJobsByCollectionStrict(String collection) {
    if (collection == null || collection.isBlank()) {
      throw new IllegalArgumentException("Collection scope must be non-blank");
    }
    String normalizedCollection = collection.trim();
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT 1 FROM jobs WHERE state IN ('PENDING', 'PROCESSING') "
              + "AND COALESCE(NULLIF(TRIM(collection), ''), ?) = ? LIMIT 1")) {
        query.setString(1, IngestCollectionPolicy.DEFAULT_COLLECTION);
        query.setString(2, normalizedCollection);
        try (ResultSet row = query.executeQuery()) {
          return row.next();
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Non-terminal collection scope is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Tempdoc 813 Slice B: the single write path for the jobs table. {@code size_bytes} is listed in
   * the INSERT precisely because re-admission explicitly resets prior fields — an unlisted column
   * would be reset to its default on every re-enqueue of an already-queued path, so the caller's
   * entry is the sole authority for the recorded size (a re-enqueue restates it, including
   * restating it as unknown). Tempdoc 812 D2 puts {@code scan_id} on the same footing for the same
   * reason: the enqueue call states which scan admitted the row, or the key is lost on re-enqueue.
   */
  @Override
  public int enqueueEntries(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId) {
    return enqueueEntries(entries, collection, scanId, false);
  }

  @Override
  public int enqueueMaintenanceScanEntries(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId) {
    return enqueueEntries(entries, collection, scanId, false, null, true);
  }

  private int enqueueEntries(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId,
      boolean exactCollection) {
    return enqueueEntries(entries, collection, scanId, exactCollection, null, false);
  }

  /** {@code unitRevision} names the admission; null mints a fresh one. */
  private int enqueueEntries(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId,
      boolean exactCollection, String unitRevision, boolean preserveRetryWaits) {
    if (entries == null || entries.isEmpty()) {
      return 0;
    }

    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing to enqueue {} jobs: insufficient free disk space (< {} MB)",
          entries.size(), MIN_FREE_DISK_BYTES / 1024 / 1024);
      if (preserveRetryWaits) throw new IngestionServiceUnavailableException("Insufficient disk for maintenance admission");
      return 0;
    }

    var declared = !exactCollection && unitRevision == null && (collection == null || collection.isBlank())
        ? declaredCollectionRoots.get() : List.<IngestCollectionPolicy.RootBinding>of();
    lockTimed();
    try {
      ensureOpen();

      // Tempdoc 885 item 21b: the UPSERT explicitly restores the former REPLACE defaults, so re-enqueueing a path clears attempts, retry_after,
      // error_message, the last-outcome columns AND first_failed_at — which is exactly what makes a
      // rescan (or a watcher event on an mtime/size change) revive a RETRY_EXHAUSTED row with a
      // fresh retry window. The shared conflict clause lists every reset field.
      //
      // Tempdoc 941 round 19 (F2): collection and scan_id are the two columns that must NOT ride
      // that reset, so each is carried through the row's own prior value when the caller states
      // nothing. They describe where the file BELONGS and which scan admitted it — facts a
      // maintenance re-enqueue does not know and therefore cannot restate. The periodic
      // syncDirectory walk re-enqueues every file missing from the index (SyncDirectoryOps: the
      // untagged enqueueEntries(batch)), and a permanently FAILED file is missing from the index
      // forever — so a corrupt PDF's collection and owning scan were blanked on every sync cycle,
      // and the failed-files drawer's "Scan <id>" line stopped rendering. The watcher and the
      // retry RPC re-enqueue the same way. NULL now means "I have nothing to say about this
      // column", not "clear it"; a caller that HAS something to say (a real scan) still
      // overwrites. size_bytes deliberately keeps the restate-or-lose-it rule from 813 Slice B:
      // there, the re-enqueue re-stats the file, so its silence really does mean unknown.
      long now = System.currentTimeMillis();
      String col = (collection != null && !collection.isBlank()) ? collection : null;
      // Tempdoc 812 D2: the enqueueing scan's identity rides the row so the Head can group the
      // per-document terminal outcomes into one durable scan-completion audit record.
      String scan = (scanId != null && !scanId.isBlank()) ? scanId : null;

      int count = inTransaction(
          () -> enqueueEntriesInTransaction(
              entries, col, scan, now, exactCollection, unitRevision, preserveRetryWaits, declared));

      meters.recordEnqueued(count);
      log.debug("Enqueued {} jobs (collection={}, scanId={})", count, col, scan);
      return count;
    } catch (SQLException e) {
      log.error("Failed to enqueue jobs", e);
      if (preserveRetryWaits) {
        recordDbError();
        throw new IngestionServiceUnavailableException("Maintenance admission failed", e);
      }
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /** Run once before workers claim v13 work. The checkpoint is retained until every root walks. */
  public String prepareRelease030Recovery(List<Path> roots) throws SQLException {
    return prepareRelease030Recovery(roots, ignored -> false);
  }

  public String prepareRelease030Recovery(List<Path> roots, java.util.function.Predicate<Path> absentFromBlue)
      throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      inTransaction(() -> {
        int generation;
        try (var query = connection.createStatement(); var row = query.executeQuery("PRAGMA application_id")) {
          if (!row.next()) throw new SQLException("Missing queue database identity");
          generation = row.getInt(1);
        }
        String originalPrefix = RELEASE030 + "job/" + generation + "/";
        boolean firstPreparation;
        try (var query = connection.prepareStatement("SELECT 1 FROM jobs WHERE unit_revision LIKE ? LIMIT 1")) {
          query.setString(1, originalPrefix + "%");
          try (var row = query.executeQuery()) { firstPreparation = row.next(); }
        }
        // A stale checkpoint is not provenance for a different modern database. Preserve its
        // unscoped work as bounded, durable quarantine, distinct from the v13 admission cohort.
        // The prefix survives checkpoint cleanup/restart and grants no exact-file authority.
        try (var update = connection.createStatement()) {
          update.executeUpdate("""
              UPDATE jobs SET unit_revision='release030-unscoped/' || unit_revision
              WHERE ingestion_root IS NULL AND state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED')
                AND unit_revision NOT LIKE 'release030/%' AND unit_revision NOT LIKE 'release030-unscoped/%'
              """);
        }
        int identity = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        if (identity == generation) identity = generation == Integer.MAX_VALUE ? 1 : generation + 1;
        // Longest boundary wins for nested roots. SQL compares normalized path components,
        // not LIKE patterns (a person's folder may contain '_' or '%').
        try (var update = connection.prepareStatement("""
            UPDATE jobs SET ingestion_root=? WHERE ingestion_root IS NULL
              AND unit_revision LIKE 'release030/%'
              AND state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED')
              AND (path=? OR (path>=? AND path<?))
            """)) {
          for (Path root : roots.stream().sorted(
              java.util.Comparator.comparingInt((Path path) -> path.getNameCount()).reversed()).toList()) {
            String key = PathNormalizer.normalizeKey(root);
            String prefix = PathNormalizer.normalizePathPrefix(key);
            update.setString(1, key);
            update.setString(2, key);
            update.setString(3, prefix);
            int separator = prefix.length() - 1;
            update.setString(4, prefix.substring(0, separator) + (char) (prefix.charAt(separator) + 1));
            update.executeUpdate();
          }
        }
        try (var update = connection.createStatement()) {
          // Outside every current root, only the exact legacy path is grandfathered. No parent
          // folder is admitted. Terminal failed rows need no recovery or cutover certificate.
          update.executeUpdate("UPDATE jobs SET ingestion_root=path WHERE ingestion_root IS NULL "
              + "AND unit_revision LIKE 'release030/%' "
              + "AND state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED')");
        }
        if (firstPreparation) {
          String cursor = "";
          while (true) {
            List<String> paths = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                SELECT path FROM jobs WHERE path>? AND ingestion_root=path
                  AND state='RETRY_EXHAUSTED' AND unit_revision LIKE ?
                ORDER BY path LIMIT 256
                """)) {
              query.setString(1, cursor);
              query.setString(2, originalPrefix + "%");
              try (var rows = query.executeQuery()) { while (rows.next()) paths.add(rows.getString(1)); }
            }
            if (paths.isEmpty()) break;
            try (var update = connection.prepareStatement(
                "UPDATE jobs SET unit_revision=replace(unit_revision,'release030/job/','release030/debt/') WHERE path=?")) {
              for (String path : paths) {
                if (!absentFromBlue.test(Path.of(path))) continue;
                update.setString(1, path); update.executeUpdate();
              }
            }
            cursor = paths.getLast();
          }
          // Only a constant prefix and the integer header generation enter this statement.
          String cohort = RELEASE030 + "%/" + generation + "/%";
          try (var update = connection.createStatement()) {
            update.executeUpdate("""
                UPDATE jobs SET state='PENDING', attempts=0, retry_after=NULL, first_failed_at=NULL,
                  error_message=NULL, last_outcome_class=NULL, last_reason_code=NULL,
                  last_retry_policy=NULL, last_diagnostic_summary=NULL, last_outcome_at=NULL
                WHERE state='RETRY_EXHAUSTED' AND unit_revision LIKE '%s'
                  OR (state='PENDING' AND last_outcome_class='WRITE_FAILED' AND unit_revision LIKE '%s')
                """.formatted(cohort, cohort));
          }
        }
        // Existing SQLite header slot, not a schema/table/version change. Commit the fresh
        // generation with the boundary/retry repair, then bind the forced sidecar to it.
        try (var update = connection.createStatement()) {
          update.execute("PRAGMA application_id=" + identity);
        }
        return 0;
      });
      requiresRelease030Boundary = false;
      release030ExplicitFilesPrepared = false;
      claimCursor = null;
      return release030RecoveryIdentity();
    } finally {
      unlockAfterChanges();
    }
  }

  /** Header generation and physical file witness; backups/replacements invalidate preparation. */
  public String release030RecoveryIdentity() throws SQLException {
    lockTimed();
    try {
      ensureOpen();
      try (var query = connection.createStatement(); var row = query.executeQuery("PRAGMA application_id")) {
        if (!row.next()) throw new SQLException("Missing queue database identity");
        var attributes = Files.readAttributes(dbPath, java.nio.file.attribute.BasicFileAttributes.class);
        Object fileKey = attributes.fileKey();
        return row.getInt(1) + "@" + (fileKey == null ? attributes.creationTime() : fileKey);
      } catch (IOException unavailable) {
        throw new SQLException("Queue database identity is unreadable", unavailable);
      }
    } finally { lock.unlock(); }
  }

  /** Bounded iteration over exact legacy files outside roots; never expands their parents. */
  public boolean prepareRelease030ExplicitFiles() throws SQLException {
    String cursor = "";
    boolean active = false;
    while (true) {
      List<Path> paths = new ArrayList<>();
      lockTimed();
      try {
        ensureOpen();
        try (var query = connection.prepareStatement("""
            SELECT path,unit_revision FROM jobs WHERE path>? AND ingestion_root=path
              AND unit_revision LIKE 'release030/%'
              AND state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED') ORDER BY path LIMIT 256
            """)) {
          query.setString(1, cursor);
          try (var rows = query.executeQuery()) {
            while (rows.next()) {
              Path path = Path.of(rows.getString(1));
              paths.add(path);
              if (rows.getString(2).startsWith(RELEASE030 + "debt/")) release030IndependentDebt.add(path);
            }
          }
        }
        if (paths.isEmpty()) {
          release030ExplicitFilesPrepared = true;
          claimCursor = null;
          if (!active) release030ExactFiles.clear();
          return !active;
        }
      } finally {
        unlockAfterChanges();
      }
      // Protection is shared by every producer/serving runtime using this queue, including
      // the successor composed during cutover. Unavailable exact files use normal I/O retries.
      active = true;
      release030ExactFiles.addAll(paths);
      cursor = PathNormalizer.normalizeKey(paths.getLast());
    }
  }

  @Override
  public int enqueueUpgradeRecoveryEntries(List<JobQueue.EnqueueEntry> entries, String collection) {
    if (entries.isEmpty()) return 0;
    if (!hasSufficientDiskSpace()) throw new IllegalStateException("Insufficient recovery disk space");
    lockTimed();
    try {
      ensureOpen();
      return inTransaction(() -> {
        try (var insert = connection.prepareStatement("""
            INSERT INTO jobs(path,state,attempts,last_updated,collection,size_bytes,ingestion_root,unit_revision)
              VALUES(?,'PENDING',0,?,?,?,?,lower(hex(randomblob(16))))
            ON CONFLICT(path) DO UPDATE SET state='PENDING',attempts=0,first_failed_at=NULL,
              retry_after=NULL,error_message=NULL,last_updated=excluded.last_updated,
              size_bytes=excluded.size_bytes,ingestion_root=excluded.ingestion_root,
              unit_revision=excluded.unit_revision
            WHERE jobs.state='DONE' AND jobs.walk_seen_epoch IS NULL
            """)) {
          for (var entry : entries) {
            // This older discovery must not replace a later acknowledged exact file unit.
            // Keep its boundary, attribution and retry/debt evidence intact; replay still proves
            // the current source and committed projection before it may retire the receipt.
            if (hasExactRecoveryAdmissionOwner(entry.path())) continue;
            insert.setString(1, PathNormalizer.normalizeKey(entry.path()));
            insert.setLong(2, System.currentTimeMillis());
            insert.setString(3, collection == null || collection.isBlank() ? null : collection);
            if (entry.sizeBytes() < 0) insert.setNull(4, java.sql.Types.BIGINT);
            else insert.setLong(4, entry.sizeBytes());
            insert.setString(5, PathNormalizer.normalizeKey(entry.ingestionRoot()));
            insert.addBatch();
          }
          insert.executeBatch();
        }
        return entries.size(); // Existing unfinished rows already retain their durable admission.
      });
    } catch (SQLException failure) {
      throw new IllegalStateException("Upgrade recovery admissions were not saved", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int enqueueRelease030LiveSyncEntries(List<JobQueue.EnqueueEntry> entries, String collection,
      boolean maintenance, java.util.function.BooleanSupplier cancelled) {
    if (entries.isEmpty()) return 0;
    if (!hasSufficientDiskSpace()) throw new IllegalStateException("Insufficient sync disk space");
    var declared = collection == null || collection.isBlank()
        ? declaredCollectionRoots.get() : List.<IngestCollectionPolicy.RootBinding>of();
    lockTimed();
    try {
      ensureOpen();
      String generation = release030RetirementGeneration();
      if (generation == null) throw new IllegalStateException("Retirement changed during sync");
      return inTransaction(() -> {
        var refreshable = refreshableRetirementFiles(retirementReplayOps(), generation, !maintenance);
        int accepted = 0;
        for (var entry : entries) {
          if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Retirement sync cancelled");
          }
          if (maintenance && preservesMaintenanceRetryWait(entry.path(), System.currentTimeMillis())) {
            accepted++; // Existing policy coverage, not a new admission or source witness.
            continue;
          }
          boolean exact = refreshable.contains(PathNormalizer.normalizeKey(entry.path()));
          JobQueue.EnqueueEntry admitted = entry;
          if (exact) {
            try {
              admitted = new JobQueue.EnqueueEntry(entry.path(), entry.sizeBytes(), entry.provenance(),
                  io.justsearch.indexerworker.loop.SourceContentHash.sha256(entry.path(), cancelled),
                  entry.ingestionRoot());
            } catch (IOException failure) {
              throw new IllegalStateException("Retirement sync source witness unavailable", failure);
            }
          }
          if (enqueueEntriesInTransaction(List.of(admitted), collection, null,
              System.currentTimeMillis(), false, null, false, declared) != 1) {
            throw new SQLException("Retirement sync admission incomplete");
          }
          if (exact) bufferAdmittedFile(generation, admitted);
          accepted++;
        }
        return accepted;
      });
    } catch (SQLException failure) {
      throw new IllegalStateException("Retirement sync admissions were not saved", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int enqueueRetirementRootEntries(SwitchBufferOp root,List<JobQueue.EnqueueEntry> entries,
      String collection,boolean maintenance,boolean replay,java.util.function.BooleanSupplier cancelled) {
    return enqueueRetirementRootEntries(root,entries,collection,maintenance,replay,cancelled,Map.of());
  }

  @Override
  public int enqueueRetirementRootEntries(SwitchBufferOp root,List<JobQueue.EnqueueEntry> entries,
      String collection,boolean maintenance,boolean replay,java.util.function.BooleanSupplier cancelled,
      Map<String,Map<String,String>> completedRows) {
    lockTimed();try {
      ensureOpen();
      if(releaseRecovery==null || !releaseRecovery.rootReceipts().contains(root))
        throw new IllegalStateException("ROOT intent is not retained");
      String retainedCollection = retirementRootCollection(root);
      if (!retirementRootPolicyKnown(root) || retainedCollection != null && !retainedCollection.equals(normalizedRetirementCollection(collection)))
        throw new IllegalStateException("ROOT namespace is unknown or conflicts with creator intent");
      return inTransaction(() -> {
        try {
          var journal = retirementReplayOps();
          var orders = retirementOrders(journal);
          var deletes=retirementDeletes(journal);int covered=0;
          boolean live = !replay && !maintenance
              && root.generation().equals(release030RetirementGeneration())
              && root.key().equals("sync_root:" + PathNormalizer.normalizePathPrefix(
                  SwitchBufferSyncRoot.decode(root.payload()).rootPath()));
          var refreshable = refreshableRetirementFiles(journal, root.generation(), live);
          for(var entry:entries) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IllegalStateException("ROOT admission cancelled");
            String path=normalizePath(entry.path());String unit=null,oldCollection=null,state=null;
            try(var q=connection.prepareStatement("SELECT unit_revision,collection,state,content_hash FROM jobs WHERE path=?")) {
              q.setString(1,path);try(var r=q.executeQuery()){if(r.next()){unit=r.getString(1);oldCollection=r.getString(2);state=r.getString(3);r.getString(4);}}
            }
            if(maintenance && preservesMaintenanceRetryWait(entry.path(),System.currentTimeMillis())){covered++;continue;}
            var current = watcherRowLocked(path);
            var currentOrigin = RootOrigin.fromUnit(unit);
            // The same receipt cannot renew or reinterpret an already admitted member. Its
            // fresh source correction (if necessary) was observed outside this transaction.
            if (replay && currentOrigin != null && currentOrigin.uuid().equals(root.retirementUuid())) {
              covered++; continue;
            }
            if (current != null && correctionHasRecordedOwner(path,current)) {covered++;continue;}
            String effective=collection == null || collection.isBlank() ? oldCollection : collection;
            long order=unit==null?-1:actualUnitOrder(path,unit,orders);
            if(latestDelete(deletes,path,effective)>root.acceptedOrder()) {covered++;continue;}
            if(replay && (order>root.acceptedOrder() || hasExactRetirementAdmissionOwner(path,orders)
                || state!=null && !"DONE".equals(state))) {covered++;continue;}
            var completed = completedRows.get(path);
            if (replay && completed != null && completed.equals(current)) {
              if (validatedUnitOrder(correctionCandidate(completed),orders)==root.acceptedOrder()) {covered++;continue;}
              var origin=RootOrigin.fromUnit(completed.get("unit_revision"));
              var owner=origin==null?null:orders.receipts().get(origin.uuid());
              var provenance=completed.get("originator")==null && completed.get("transport")==null ? null
                  : new JobQueue.EnqueueProvenance(completed.get("originator"),completed.get("transport"));
              var scope=new SwitchBufferUpsert(path,completed.get("collection"),provenance,null,null,Path.of(completed.get("ingestion_root")));
              if (!SwitchBufferSyncRoot.decode(root.payload()).force()
                  && preservedIndexedRootOwner(owner,scope,current,orders,deletes)) {covered++;continue;}
            }
            boolean exact=refreshable.contains(path);
            JobQueue.EnqueueEntry admitted=entry;
            if(exact&&!replay) {
              String source=io.justsearch.indexerworker.loop.SourceContentHash.sha256(entry.path(),cancelled);
              admitted=new JobQueue.EnqueueEntry(entry.path(),entry.sizeBytes(),entry.provenance(),source,entry.ingestionRoot());
            }
            String admittedUnit = exact && !replay ? null : root.retirementRootUnit();
            // The retained ROOT already resolved its frozen inheritance above, including default.
            if(enqueueEntriesInTransaction(List.of(admitted),effective,null,System.currentTimeMillis(),true,
                admittedUnit)!=1)throw new SQLException("Short ROOT admission");
            if (!(exact && !replay)) {
              // Native enqueue has now resolved null collection/provenance inheritance. Bind
              // the actual row inside this transaction; no root1 intermediate is published.
              var resolved = watcherRowLocked(path);
              String member = "root2:" + root.retirementUuid() + ":" + root.acceptedOrder() + ":"
                  + UUID.randomUUID().toString().replace("-", "") + ":"
                  + SwitchBufferCapableQueue.rootMemberDigest(path,resolved.get("collection"),
                      resolved.get("originator"),resolved.get("transport"),resolved.get("ingestion_root"),
                      resolved.get("planned_source_sha256"));
              try (var update = connection.prepareStatement("UPDATE jobs SET unit_revision=? WHERE path=? AND unit_revision=?")) {
                update.setString(1,member); update.setString(2,path); update.setString(3,admittedUnit);
                if (update.executeUpdate()!=1) throw new SQLException("ROOT member changed during admission");
              }
            }
            if(exact&&!replay)bufferAdmittedFile(root.generation(),admitted);
            covered++;
          }
          claimCursor=null;return covered;
        } catch(IOException invalid){throw new SQLException("ROOT admission authority unavailable",invalid);}
      });
    } catch(SQLException|IOException invalid){throw new IllegalStateException("ROOT admission incomplete",invalid);}
    finally{unlockAfterChanges();}
  }

  private Set<String> refreshableRetirementFiles(List<SwitchBufferOp> journal,
      String generation, boolean live) {
    var seen = new HashSet<String>();
    var result = new HashSet<String>();
    for (var op : journal.reversed()) {
      if (!"UPSERT".equals(op.op()) || op.generation() != null && !op.generation().isBlank()
          && !generation.equals(op.generation())) continue;
      var prior = SwitchBufferUpsert.decode(op.payload());
      String path = PathNormalizer.normalizeKey(Path.of(prior.path()));
      if (!seen.add(path)) continue;
      if (generation.equals(op.generation()) && prior.unitRevision() != null) result.add(path);
      else if (live && !op.payload().stripLeading().startsWith("{")) {
        try { if (releaseRecovery != null && releaseRecovery.legacyExpectation(op) != null) result.add(path); }
        catch (IOException invalid) { throw new IllegalStateException("Original raw expectation unavailable",invalid); }
      }
    }
    return Set.copyOf(result);
  }

  private void bufferAdmittedFile(String generation, JobQueue.EnqueueEntry entry) throws SQLException {
    String normalizedPath = normalizePath(entry.path());
    String effectiveCollection;
    String originator;
    String transport;
    String revision;
    String sourceHash;
    String ingestionRoot;
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT collection, originator, transport, unit_revision, planned_source_sha256, ingestion_root "
            + "FROM jobs WHERE path = ?")) {
      query.setString(1, normalizedPath);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) throw new SQLException("Atomic file batch lost an admitted row");
        effectiveCollection = row.getString("collection");
        originator = row.getString("originator");
        transport = row.getString("transport");
        revision = row.getString("unit_revision");
        sourceHash = row.getString("planned_source_sha256");
        ingestionRoot = row.getString("ingestion_root");
      }
    }
    if (revision == null || sourceHash == null) {
      throw new SQLException("Atomic file batch lost its exact source witness");
    }
    JobQueue.EnqueueProvenance effectiveProvenance = originator == null && transport == null
        ? null : new JobQueue.EnqueueProvenance(originator, transport);
    String payload = new SwitchBufferUpsert(
        normalizedPath, effectiveCollection, effectiveProvenance,
        revision, sourceHash, ingestionRoot == null ? null : Path.of(ingestionRoot)).encode();
    if (!switchBufferOps.putForGenerationInTransaction(
        connection, generation, "path:" + normalizedPath, "UPSERT", payload)) {
      throw new SQLException("Generation-scoped switch-buffer admission was refused");
    }

  }

  private boolean hasExactRetirementAdmissionOwner(String key,RetirementOrders orders) throws SQLException {
    var row=watcherRowLocked(key);
    if(row==null || !"DONE".equals(row.get("state"))) return false;
    for(var accepted:orders.filesByPath().getOrDefault(key,List.of())) {
      if(accepted.unitRevision().equals(row.get("unit_revision"))
          && accepted.sourceSha256().equals(row.get("planned_source_sha256"))
          && accepted.ingestionRoot()!=null && accepted.ingestionRoot().toString().equals(row.get("ingestion_root"))
          && Objects.equals(Objects.toString(accepted.collection(),"default"),Objects.toString(row.get("collection"),"default"))) return true;
    }
    return false;
  }

  private boolean hasExactRecoveryAdmissionOwner(Path path) throws SQLException {
    String key = PathNormalizer.normalizeKey(path);
    if (releaseRecovery!=null && releaseRecovery.retiringBuild()) {
      try { return hasExactRetirementAdmissionOwner(key,retirementOrders(retirementReplayOps())); }
      catch(IOException invalid){throw new SQLException("Retained exact ownership unavailable",invalid);}
    }
    try (var query = connection.prepareStatement("""
        SELECT j.unit_revision,j.planned_source_sha256,j.ingestion_root,j.collection,b.payload
        FROM jobs j JOIN switch_buffer b ON b.key=? AND b.op='UPSERT'
        WHERE j.path=? AND j.state='DONE'
        """)) {
      query.setString(1, "path:" + key);
      query.setString(2, key);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          var accepted = SwitchBufferUpsert.decode(rows.getString("payload"));
          if (key.equals(accepted.path()) && accepted.unitRevision() != null
              && accepted.unitRevision().equals(rows.getString("unit_revision"))
              && accepted.sourceSha256().equals(rows.getString("planned_source_sha256"))
              && accepted.ingestionRoot() != null
              && PathNormalizer.normalizeKey(accepted.ingestionRoot())
                  .equals(rows.getString("ingestion_root"))
              && Objects.equals(accepted.collection(), rows.getString("collection"))) return true;
        }
      }
    }
    return false;
  }

  private static final String MAINTENANCE_RETRY_WAIT =
      "jobs.walk_seen_epoch IS NULL AND (jobs.state='FAILED' "
          + "OR (jobs.state='PENDING' AND COALESCE(jobs.retry_after,0)>? "
          + "AND COALESCE(jobs.last_outcome_class,'') IN ('PARSER_TIMEOUT','IO_FAILED')))";

  private boolean preservesMaintenanceRetryWait(Path path, long now) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM jobs WHERE path=? AND (" + MAINTENANCE_RETRY_WAIT + ")")) {
      query.setString(1, normalizePath(path));
      query.setLong(2, now);
      try (var rows = query.executeQuery()) { return rows.next(); }
    }
  }

  private int enqueueEntriesInTransaction(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId, long now,
      boolean exactCollection, String unitRevision) throws SQLException {
    return enqueueEntriesInTransaction(
        entries, collection, scanId, now, exactCollection, unitRevision, false,
        List.of());
  }

  private int enqueueEntriesInTransaction(
      List<JobQueue.EnqueueEntry> entries, String collection, String scanId, long now,
      boolean exactCollection, String unitRevision, boolean preserveRetryWaits,
      List<IngestCollectionPolicy.RootBinding> declared)
      throws SQLException {
    String sql = """
        INSERT INTO jobs
          (path, state, attempts, last_updated, collection, size_bytes, scan_id, originator, transport, unit_revision, walk_seen_epoch, planned_source_sha256, ingestion_root)
        VALUES (
          ?, 'PENDING', 0, ?,
          CASE WHEN ? = 1 THEN ? ELSE COALESCE(?, (SELECT prior.collection FROM jobs prior WHERE prior.path = ?)) END,
          ?,
          ?,
          COALESCE(?, (SELECT prior.originator FROM jobs prior WHERE prior.path = ?)),
          COALESCE(?, (SELECT prior.transport FROM jobs prior WHERE prior.path = ?)),
          COALESCE(?, lower(hex(randomblob(16)))), ?, ?,
          COALESCE(?, (SELECT prior.ingestion_root FROM jobs prior WHERE prior.path = ?)))
        """ + SqliteSchema.REENQUEUE_JOB_ON_CONFLICT;
    if (preserveRetryWaits) {
      // A startup scan is discovery, not a request to retry a terminal failure or an unchanged
      // failing parser/file before its deadline.
      // Recorded memberships retain their own existing mutation contract. An exhausted row is
      // still revived; explicit/forced scans, watcher events and ordinary sync are unchanged.
      sql += " WHERE NOT (" + MAINTENANCE_RETRY_WAIT + ")";
    }
    var frozen = frozenCollectionRoots.get();
    int accepted = 0;
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      for (JobQueue.EnqueueEntry entry : entries) {
        if (entry == null || entry.path() == null) {
          continue;
        }
        String normalizedPath =
            PathNormalizer.normalizePath(entry.path().toAbsolutePath().toString());
        sealBeforeMaintenance(normalizedPath);
        var membership =
            SqliteIngestionWalkOps.maintenanceMembership(connection, normalizedPath, scanId);
        stmt.setString(1, normalizedPath);
        stmt.setLong(2, now);
        String effectiveCollection = collection;
        boolean exact = exactCollection;
        if (!exact && unitRevision == null && membership.epoch() == null
            && (collection == null || collection.isBlank())) {
          var owner = collectionOwner(entry.path(), frozen, declared);
          if (owner != null) {
            effectiveCollection = owner.collection() == null || owner.collection().isBlank() ? null : owner.collection();
            exact = true;
            // Internal corpora retain their default-search exclusion on hintless readmission.
            try (var prior = connection.prepareStatement("SELECT collection FROM jobs WHERE path=?")) {
              prior.setString(1, normalizedPath);
              try (var row = prior.executeQuery()) {
                if (row.next() && IngestCollectionPolicy.isReserved(row.getString(1))) {
                  effectiveCollection = row.getString(1);
                }
              }
            }
          }
        }
        stmt.setInt(3, exact ? 1 : 0);
        stmt.setString(4, effectiveCollection);
        stmt.setString(5, effectiveCollection);
        stmt.setString(6, normalizedPath); // carry-forward lookup for collection
        if (entry.sizeBytes() >= 0) {
          stmt.setLong(7, entry.sizeBytes());
        } else {
          stmt.setNull(7, java.sql.Types.INTEGER);
        }
        stmt.setString(8, membership.key());
        stmt.setString(9, entry.provenance() == null ? null : entry.provenance().originator());
        stmt.setString(10, normalizedPath);
        stmt.setString(11, entry.provenance() == null ? null : entry.provenance().transport());
        stmt.setString(12, normalizedPath);
        stmt.setString(13, unitRevision);
        if (membership.epoch() == null) stmt.setNull(14, java.sql.Types.BIGINT);
        else stmt.setLong(14, membership.epoch());
        stmt.setString(15, membership.plannedSourceSha256() == null
            ? entry.plannedSourceSha256() : membership.plannedSourceSha256());
        stmt.setString(16, PathNormalizer.normalizeKey(entry.ingestionRoot()));
        stmt.setString(17, normalizedPath);
        if (preserveRetryWaits) stmt.setLong(18, now);
        int changed = stmt.executeUpdate();
        if (changed > 0 && membership.epoch() != null) {
          SqliteIngestionWalkOps.noteMutation(connection, membership.key());
        }
        accepted += changed;
      }
    }
    return accepted;
  }

  /**
   * Accepts one file and its candidate replay obligation in one jobs.db transaction.
   *
   * <p>The normal jobs admission SQL remains the source of truth for unit revisions, source-plan
   * hashes and attribution. The switch-buffer insert uses the same connection and transaction, so
   * a failed journal write rolls back the accepted job instead of returning a half-admitted file.
   */
  @Override
  public boolean enqueueAndBufferFileForGeneration(
      String generation, JobQueue.EnqueueEntry entry, String collection, String scanId) {
    return entry != null && entry.path() != null
        && enqueueAndBufferFilesForGeneration(
            generation, List.of(entry), collection, scanId) == 1;
  }

  @Override
  public int enqueueAndBufferFilesForGeneration(
      String generation, List<JobQueue.EnqueueEntry> entries, String collection, String scanId) {
    return enqueueCandidateFileBatch(generation, entries, collection, scanId, false);
  }

  @Override
  public int enqueueEnumeratedFilesForGeneration(
      String generation, List<JobQueue.EnqueueEntry> entries, String collection) {
    return enqueueCandidateFileBatch(generation, entries, collection, null, true);
  }

  private record CandidateFileBatch(int covered, int enqueued) {}

  private int enqueueCandidateFileBatch(String generation, List<JobQueue.EnqueueEntry> entries,
      String collection, String scanId, boolean enumeration) {
    if (generation == null || generation.isBlank() || entries == null || entries.isEmpty()
        || entries.stream().anyMatch(entry -> entry == null || entry.path() == null)) {
      return 0;
    }
    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing atomic file batch: insufficient free disk space");
      return 0;
    }

    List<IngestCollectionPolicy.RootBinding> declared;
    List<JobQueue.EnqueueEntry> witnessedEntries = new ArrayList<>(entries.size());
    try {
      declared = !enumeration && (collection == null || collection.isBlank())
          ? declaredCollectionRoots.get() : List.of();
      for (JobQueue.EnqueueEntry entry : entries) {
        String hash = entry.plannedSourceSha256() == null
            ? io.justsearch.indexerworker.loop.SourceContentHash.sha256(entry.path())
            : entry.plannedSourceSha256();
        JobQueue.EnqueueProvenance provenance = enumeration
            ? new JobQueue.EnqueueProvenance("system", "MIGRATION_ENUMERATOR")
            : entry.provenance();
        witnessedEntries.add(new JobQueue.EnqueueEntry(
            entry.path(), entry.sizeBytes(), provenance, hash, entry.ingestionRoot()));
      }
    } catch (IOException | RuntimeException unreadable) {
      log.warn("Refusing candidate file admission without source or declaration evidence", unreadable);
      return 0;
    }

    lockTimed();
    try {
      ensureOpen();
      String normalizedCollection = collection == null || collection.isBlank() ? null : collection;
      String normalizedScan = scanId == null || scanId.isBlank() ? null : scanId;
      CandidateFileBatch committed = inTransaction(() -> {
        List<JobQueue.EnqueueEntry> admitted = witnessedEntries;
        int superseded = 0;
        if (enumeration) {
          admitted = new ArrayList<>(witnessedEntries.size());
          for (JobQueue.EnqueueEntry entry : witnessedEntries) {
            if (enumerationSupersededByAcceptedMutation(
                generation, normalizePath(entry.path()), entry.plannedSourceSha256(), normalizedCollection)) {
              superseded++;
            } else {
              admitted.add(entry);
            }
          }
        }
        int accepted = enqueueEntriesInTransaction(
            admitted, normalizedCollection, normalizedScan, System.currentTimeMillis(), enumeration, null, false, declared);
        if (accepted != admitted.size()) {
          throw new SQLException("Atomic file batch did not accept every entry");
        }
        for (JobQueue.EnqueueEntry entry : admitted) {
          bufferAdmittedFile(generation, entry);
        }
        return new CandidateFileBatch(accepted + superseded, accepted);
      });
      meters.recordEnqueued(committed.enqueued());
      return committed.covered();
    } catch (SQLException | RuntimeException failure) {
      recordDbError();
      log.error("Failed atomic file batch admission", failure);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /** The baseline walk cannot replace an accepted foreground mutation. */
  private boolean enumerationSupersededByAcceptedMutation(
      String generation, String path, String sourceSha256, String sourceCollection)
      throws SQLException {
    List<String> deletedCollections = new ArrayList<>();
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT key, op, payload FROM switch_buffer WHERE generation = ? "
            + "AND (key = ? OR op IN ('DELETE_PREFIX', 'DELETE_COLLECTION'))")) {
      query.setString(1, generation);
      query.setString(2, "path:" + path);
      try (ResultSet row = query.executeQuery()) {
        while (row.next()) {
          String op = row.getString("op");
          String payload = row.getString("payload");
          if ("DELETE_PREFIX".equals(op)
              && PathPrefixScope.decode(payload).contains(path)) return true;
          if ("DELETE_COLLECTION".equals(op)) {
            deletedCollections.add(payload);
          } else if (("path:" + path).equals(row.getString("key"))) {
            if ("DELETE".equals(op)) {
              // The enumerator already read these source bytes. Only an explicit deletion may
              // suppress that observed recreation; watcher and originless receipts are conditional.
              if (SwitchBufferDelete.decode(payload).origin() == SwitchBufferDelete.Origin.EXPLICIT) return true;
              continue;
            }
            if (!"UPSERT".equals(op)) return true;
            var prior = SwitchBufferUpsert.decode(payload);
            if (prior.sourceSha256() != null && !prior.sourceSha256().equals(sourceSha256)) {
              // Hashing the batch precedes the admission lock. A foreground writer may have
              // accepted newer bytes in that interval. Only replace a stale receipt when the
              // enumerator still witnesses the current source; otherwise retain the accepted work.
              try {
                if (!sourceSha256.equals(
                    io.justsearch.indexerworker.loop.SourceContentHash.sha256(Path.of(path)))) return true;
              } catch (IOException unreadable) {
                throw new SQLException("Cannot establish current enumeration source", unreadable);
              }
            }
            if ((prior.provenance() == null
                || !"MIGRATION_ENUMERATOR".equals(prior.provenance().transport()))
                && (prior.sourceSha256() == null
                    || (prior.sourceSha256().equals(sourceSha256)
                        && Objects.equals(prior.collection(), sourceCollection)))) return true;
          }
        }
      }
    }
    // Enumeration knows the owning root's exact binding, including the default (null).
    return deletedCollections.contains(sourceCollection);
  }

  @Override
  public boolean matchesAcceptedFileProjection(
      String path, String unitRevision, String sourceSha256) {
    return matchesAcceptedFileProjection(path, unitRevision, sourceSha256, sourceSha256);
  }

  @Override
  public boolean matchesAcceptedFileProjection(
      String path, String unitRevision, String sourceSha256, String indexedSourceSha256) {
    if (path == null || unitRevision == null
        || !JobQueue.IngestionLedgerTransition.isSha256(sourceSha256)
        || !JobQueue.IngestionLedgerTransition.isSha256(indexedSourceSha256)) return false;
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT state, unit_revision, planned_source_sha256, content_hash, scan_id, walk_seen_epoch "
              + "FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (ResultSet row = query.executeQuery()) {
          if (!row.next() || !STATE_DONE.equals(row.getString("state"))
              || !unitRevision.equals(row.getString("unit_revision"))
              || !sourceSha256.equals(row.getString("planned_source_sha256"))) return false;
          String committed = row.getString("content_hash");
          if (sourceSha256.equals(indexedSourceSha256)) {
            return committed == null || sourceSha256.equals(committed);
          }
          return indexedSourceSha256.equals(committed)
              && matchesCapturedFileProjection(row, path, unitRevision,
                  sourceSha256, indexedSourceSha256, false);
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Accepted file projection evidence is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean tryMatchesNativePolicySkippedAdmission(String candidate,
      List<SwitchBufferOp> expectedJournal, SwitchBufferOp expectedOp,
      Map<String, String> expectedRow) {
    final List<SwitchBufferOp> journal;
    final Map<String, String> row;
    try {
      journal = List.copyOf(expectedJournal);
      row = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(expectedRow));
    } catch (RuntimeException invalid) { return false; }
    if (!lock.tryLock()) return false;
    try {
      ensureOpen();
      return nativePolicyJournalMatches(candidate, journal)
          && nativePolicySkippedAdmissionMatches(candidate, journal, expectedOp, row);
    } catch (SQLException | RuntimeException unknown) {
      return false;
    } finally {
      // This read must never steal or deliver another writer's committed notifications.
      lock.unlock();
    }
  }

  @Override
  public List<SwitchBufferOp> readmitNativePolicySkippedAdmissions(String candidate,
      List<SwitchBufferOp> expectedJournal, List<NativePolicyReadmission> entries) {
    final List<SwitchBufferOp> journal;
    final List<NativePolicyReadmission> batch;
    try {
      journal = List.copyOf(expectedJournal);
      batch = List.copyOf(entries);
      if (batch.isEmpty() || batch.size() > 2_000 || candidate == null || candidate.isBlank()
          || io.justsearch.indexerworker.index.IndexGenerationManager.isRecordedGenerationIdentity(candidate)) {
        return List.of();
      }
      Set<String> paths = new HashSet<>();
      for (var item : batch) {
        if (!nativePolicyReadmissionJoins(item)
            || !nativePolicyReadmissionSurvives(journal, item.expectedOp())
            || !paths.add(PathNormalizer.normalizeKey(item.entry().path()))) return List.of();
      }
    } catch (RuntimeException invalid) { return List.of(); }
    lockTimed();
    Throwable primary = null;
    try {
      ensureOpen();
      List<SwitchBufferOp> committed = inTransaction(() -> {
        if (!nativePolicyJournalMatches(candidate, journal)) return List.of();
        // Complete all preservation reads before the first write in this reserved transaction.
        for (var item : batch) {
          if (!nativePolicySkippedAdmissionMatches(candidate, journal,
                  item.expectedOp(), item.expectedFullRow())
              || !nativePolicyReadmissionSurvives(journal, item.expectedOp())) return List.of();
        }
        long now = System.currentTimeMillis();
        for (var item : batch) {
          if (enqueueEntriesInTransaction(List.of(item.entry()), rootOwner(item.collection()),
              null, now, true, null) != 1) {
            throw new SQLException("Native policy replacement did not admit the complete batch");
          }
          String path = normalizePath(item.entry().path());
          var actual = watcherRowLocked(path);
          if (actual == null || !STATE_PENDING.equals(actual.get("state"))
              || !isAdmissionRevision(actual.get("unit_revision"))
              || Objects.equals(item.expectedFullRow().get("unit_revision"), actual.get("unit_revision"))
              || !Objects.equals(item.entry().plannedSourceSha256(), actual.get("planned_source_sha256"))
              || !Objects.equals(rootOwner(item.collection()), rootOwner(actual.get("collection")))
              || !Objects.equals(PathNormalizer.normalizeKey(item.entry().ingestionRoot()), actual.get("ingestion_root"))
              || !Objects.equals(item.entry().provenance().originator(), actual.get("originator"))
              || !Objects.equals(item.entry().provenance().transport(), actual.get("transport"))) {
            throw new SQLException("Native policy replacement lost its actual admission witness");
          }
          var payload = new SwitchBufferUpsert(path, actual.get("collection"),
              new EnqueueProvenance(actual.get("originator"), actual.get("transport")),
              actual.get("unit_revision"), actual.get("planned_source_sha256"),
              Path.of(actual.get("ingestion_root"))).encode();
          if (!switchBufferOps.putForGenerationInTransaction(
              connection, candidate, "path:" + path, "UPSERT", payload)) {
            throw new SQLException("Native policy replacement lost its atomic journal write");
          }
        }
        var current = switchBufferOps.listAllStrict();
        List<SwitchBufferOp> result = new ArrayList<>(batch.size());
        for (var item : batch) {
          String key = "path:" + normalizePath(item.entry().path());
          var op = current.stream().filter(value -> candidate.equals(value.generation())
              && key.equals(value.key())).findFirst().orElseThrow(
                  () -> new SQLException("Native policy replacement lost its final receipt"));
          if (op.revision() == null || op.revision().isBlank() || op.acceptedOrder() <= 0
              || !"UPSERT".equals(op.op())) throw new SQLException("Invalid native replacement receipt");
          var admitted = SwitchBufferUpsert.decode(op.payload());
          var row = watcherRowLocked(admitted.path());
          if (row == null || !Objects.equals(admitted.unitRevision(), row.get("unit_revision"))
              || !Objects.equals(admitted.sourceSha256(), row.get("planned_source_sha256"))) {
            throw new SQLException("Native policy replacement final row changed");
          }
          result.add(op);
        }
        return List.copyOf(result);
      });
      if (!committed.isEmpty()) meters.recordEnqueued(committed.size());
      return committed;
    } catch (SQLException failure) {
      // inTransaction preserves rollback, commit ambiguity, restore and witness failures.
      var unconfirmed = SqliteServiceAvailability.failure("Native policy readmission is unconfirmed", failure);
      primary = unconfirmed;
      throw unconfirmed;
    } catch (RuntimeException | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      // Ordinary admission delivery, outside configuration publication ownership. A failure
      // here is unconfirmed even when the transaction already committed; never return empty.
      try { unlockAfterChanges(); }
      catch (RuntimeException | Error deliveryFailure) {
        if (primary == null) throw deliveryFailure;
        if (primary != deliveryFailure) primary.addSuppressed(deliveryFailure);
      }
    }
  }

  private boolean nativePolicyJournalMatches(String candidate, List<SwitchBufferOp> expected) {
    return candidate != null && !candidate.isBlank()
        && !io.justsearch.indexerworker.index.IndexGenerationManager.isRecordedGenerationIdentity(candidate)
        && nativePolicyRelevantJournal().stream()
            .filter(op -> op.generation().isEmpty() || candidate.equals(op.generation())).toList()
            .equals(expected);
  }

  private List<SwitchBufferOp> nativePolicyRelevantJournal() {
    var journal = switchBufferOps.listAllStrict();
    for (var op : journal) {
      if (op.generation() == null || op.key() == null || op.op() == null || op.payload() == null
          || op.revision() == null || op.revision().isBlank() || op.acceptedOrder() <= 0
          || WatcherRootReconciliation.OPERATION.equals(op.op()) && !op.generation().isEmpty()) {
        throw new IllegalStateException("Native policy journal ownership is unavailable");
      }
    }
    return journal;
  }

  private boolean nativePolicySkippedAdmissionMatches(String candidate,
      List<SwitchBufferOp> journal, SwitchBufferOp op, Map<String, String> expected) throws SQLException {
    if (op == null || expected == null || !journal.contains(op)
        || !candidate.equals(op.generation()) || !"UPSERT".equals(op.op())
        || op.revision() == null || op.revision().isBlank() || op.acceptedOrder() <= 0) return false;
    final SwitchBufferUpsert file;
    try { file = SwitchBufferUpsert.decode(op.payload()); }
    catch (RuntimeException invalid) { return false; }
    if (file.ingestionRoot() == null || file.ingestionRoot().equals(Path.of(file.path()))
        || file.sourceSha256() == null || !isAdmissionRevision(file.unitRevision())
        || !file.path().equals(PathNormalizer.normalizeKey(Path.of(file.path())))
        || !("path:" + file.path()).equals(op.key())
        || IngestCollectionPolicy.isReserved(file.collection()) || file.provenance() == null
        || "WATCHER_RECONCILIATION".equals(file.provenance().transport())) return false;
    var row = watcherRowLocked(file.path());
    return row != null && row.equals(expected) && file.path().equals(row.get("path"))
        && STATE_DONE.equals(row.get("state")) && !activeClaims.containsKey(file.path())
        && "SKIPPED_POLICY".equals(row.get("last_outcome_class"))
        && "SKIPPED_TEMP_OR_SYSTEM".equals(row.get("last_reason_code"))
        && "NONE".equals(row.get("last_retry_policy")) && row.get("content_hash") == null
        && row.get("retry_after") == null && row.get("walk_seen_epoch") == null
        && !recordedWatcherRow(row) && !release030ExactFiles.contains(Path.of(file.path()))
        && !release030IndependentDebt.contains(Path.of(file.path()))
        && file.unitRevision().equals(row.get("unit_revision"))
        && file.sourceSha256().equals(row.get("planned_source_sha256"))
        && PathNormalizer.normalizeKey(file.ingestionRoot()).equals(row.get("ingestion_root"))
        && Objects.equals(rootOwner(file.collection()), rootOwner(row.get("collection")))
        && Objects.equals(file.provenance().originator(), row.get("originator"))
        && Objects.equals(file.provenance().transport(), row.get("transport"));
  }

  private static boolean nativePolicyReadmissionJoins(NativePolicyReadmission item) {
    if (item == null || item.expectedOp() == null || item.expectedFullRow() == null
        || item.entry() == null || item.entry().path() == null) return false;
    var old = SwitchBufferUpsert.decode(item.expectedOp().payload());
    var entry = item.entry();
    String path = normalizePath(entry.path());
    return "UPSERT".equals(item.expectedOp().op()) && old.path().equals(path)
        && path.equals(item.expectedFullRow().get("path"))
        && ("path:" + path).equals(item.expectedOp().key())
        && old.path().equals(PathNormalizer.normalizeKey(Path.of(old.path())))
        && path.equals(PathNormalizer.normalizeKey(entry.path()))
        && entry.ingestionRoot() != null && !entry.ingestionRoot().equals(entry.path().toAbsolutePath().normalize())
        && Objects.equals(PathNormalizer.normalizeKey(old.ingestionRoot()), PathNormalizer.normalizeKey(entry.ingestionRoot()))
        && entry.sizeBytes() >= 0 && IngestionLedgerTransition.isSha256(entry.plannedSourceSha256())
        && entry.provenance() != null && Objects.equals(old.provenance(), entry.provenance())
        && !"WATCHER_RECONCILIATION".equals(entry.provenance().transport())
        && Objects.equals(rootOwner(old.collection()), rootOwner(item.collection()))
        && !IngestCollectionPolicy.isReserved(item.collection());
  }

  private static boolean nativePolicyReadmissionSurvives(List<SwitchBufferOp> journal, SwitchBufferOp expected) {
    var file = SwitchBufferUpsert.decode(expected.payload());
    for (var later : journal) {
      if (later.acceptedOrder() <= expected.acceptedOrder()) continue;
      if ("DELETE".equals(later.op()) && file.path().equals(SwitchBufferDelete.decode(later.payload()).path())) return false;
      if ("DELETE_PREFIX".equals(later.op()) && PathPrefixScope.decode(later.payload()).contains(file.path())) return false;
      if ("DELETE_COLLECTION".equals(later.op())
          && Objects.equals(rootOwner(file.collection()), rootOwner(later.payload()))) return false;
      if (WatcherRootReconciliation.OPERATION.equals(later.op())) {
        var root = WatcherRootReconciliation.decode(later.payload());
        if (Path.of(file.path()).startsWith(Path.of(root.root()))
            && Objects.equals(rootOwner(file.collection()), rootOwner(root.collection()))) return false;
      }
    }
    return true;
  }

  /**
   * A terminal outcome of A's normal policy for a source that is not indexable as it is: not a
   * regular file, excluded, unsupported or too large. Outside a migration nothing more happens
   * to the file until it changes. Writes and UNCHANGED are judged by the index instead; failures
   * (I/O, unreadable, parser, sandbox, write), stale sources, deferrals and every retirement
   * reason (refused, stopped enumeration, missing boundary, removed) are no such effect.
   */
  private static boolean endedByPolicy(String state, String outcomeClass, String reason,
      String retryPolicy) {
    boolean terminal = STATE_DONE.equals(state) || (STATE_FAILED.equals(state)
        && io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE.name().equals(retryPolicy));
    if (!terminal || outcomeClass == null) return false;
    return switch (outcomeClass) {
      case "UNSUPPORTED", "BUDGET_EXCEEDED" -> true;
      case "SKIPPED_POLICY" ->
          io.justsearch.indexerworker.ingest.IngestionReasonCodes.NON_REGULAR_SOURCE.equals(reason)
              || io.justsearch.indexerworker.ingest.IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM
                  .equals(reason);
      default -> false;
    };
  }

  private static final String WATCHER_ADMISSION = "watcher-reconcile-v1:";
  private static final tools.jackson.databind.ObjectMapper WATCHER_JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  /** Ownership only: fresh root/source proof is still required on every use. */
  private record WatcherAdmission(String candidate, SwitchBufferOp root, String path, String owner,
      SwitchBufferOp predecessor, String plannedHash, String token,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String scanId) {}

  private static String rootOwner(String value) {
    return value == null || value.isBlank() || IngestCollectionPolicy.DEFAULT_COLLECTION.equals(value)
        ? null : value;
  }

  @Override
  public Map<String, String> watcherReconciliationRow(String path) {
    lock.lock();
    try {
      ensureOpen();
      return watcherRowLocked(path);
    } catch (SQLException failure) {
      throw new IllegalStateException("Watcher reconciliation row is unreadable", failure);
    } finally { lock.unlock(); }
  }

  private Map<String, String> watcherRowLocked(String path) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM jobs WHERE path=?")) {
      query.setString(1, path);
      try (var result = query.executeQuery()) {
        if (!result.next()) return null;
        Map<String, String> row = new java.util.LinkedHashMap<>();
        var metadata = result.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
          row.put(metadata.getColumnName(i), result.getString(i));
        }
        return java.util.Collections.unmodifiableMap(row);
      }
    }
  }

  @Override
  public boolean reconcileWatcherFile(WatcherReconciliationFile file, Runnable freshProof,
      Runnable removeIndex) {
    if (file != null && file.policyOmitted()) return tryMatchesWatcherPolicyOmission(file, freshProof);
    lock.lock();
    try {
      ensureOpen();
      return inTransaction(() -> {
        var journal = switchBufferOps.listAllStrict().stream()
            .filter(op -> op.generation().isEmpty() || file.candidate().equals(op.generation())).toList();
        var root = file.rootReceipt();
        var debt = WatcherRootReconciliation.decode(root.payload());
        Path path = Path.of(file.path());
        if (file.candidate() == null || file.candidate().isBlank()
            || !journal.equals(file.journal()) || !journal.contains(root)
            || !WatcherRootReconciliation.OPERATION.equals(root.op())
            || !root.generation().isEmpty() || !debt.key().equals(root.key()) || root.acceptedOrder() <= 0
            || file.sourceSha256() == null
            || !file.sourceSha256().isEmpty() && !IngestionLedgerTransition.isSha256(file.sourceSha256())
            || !path.isAbsolute() || !path.normalize().equals(path)
            || path.equals(Path.of(debt.root())) || !path.startsWith(Path.of(debt.root()))) {
          throw new IllegalStateException("Watcher reconciliation journal or root changed");
        }
        var row = watcherRowLocked(file.path());
        if (!Objects.equals(row, file.expectedRow())) return false;
        freshProof.run();
        if (activeClaims.containsKey(file.path()) || row != null && STATE_PROCESSING.equals(row.get("state"))) return false;
        if (row != null && (row.get("walk_seen_epoch") != null
            || row.get("unit_revision") != null && (row.get("unit_revision").startsWith("release030") || releaseRecovery != null && releaseRecovery.retiringBuild() && (row.get("unit_revision").startsWith("root1:") || row.get("unit_revision").startsWith("root2:")))
            || release030ExactFiles.contains(path) || release030IndependentDebt.contains(path)
            || recordedWatcherRow(row))) return independentWatcherEffect(file, row, debt);

        SwitchBufferOp predecessor = null;
        boolean laterAdmission = false;
        for (var op : journal) {
          if (!"UPSERT".equals(op.op())) continue;
          var upsert = SwitchBufferUpsert.decode(op.payload());
          if (!file.path().equals(upsert.path())) continue;
          if (!("path:" + file.path()).equals(op.key())) {
            throw new IllegalStateException("Watcher predecessor key is invalid");
          }
          // A later independent admission cannot be borrowed by this root receipt.
          if (op.acceptedOrder() >= root.acceptedOrder()) laterAdmission = true;
          else predecessor = op;
        }
        if (laterAdmission) return independentWatcherEffect(file, row, debt);
        String owner = rootOwner(debt.collection());
        boolean owned = false;
        boolean original = false;
        if (row != null && Objects.equals(owner, rootOwner(row.get("collection")))) {
          if (predecessor != null) {
            var admitted = SwitchBufferUpsert.decode(predecessor.payload());
            original = admitted.unitRevision() != null
                && admitted.unitRevision().equals(row.get("unit_revision"))
                && Objects.equals(admitted.sourceSha256(), row.get("planned_source_sha256"))
                && Objects.equals(owner, rootOwner(admitted.collection()))
                && (admitted.provenance() == null || Objects.equals(admitted.provenance().originator(), row.get("originator"))
                    && Objects.equals(admitted.provenance().transport(), row.get("transport")))
                && (admitted.ingestionRoot() == null || admitted.ingestionRoot().toString().equals(row.get("ingestion_root")));
          }
          WatcherAdmission prior = parseWatcherAdmission(row.get("unit_revision"));
          if (prior != null && prior.candidate().equals(file.candidate())
              && prior.path().equals(file.path()) && Objects.equals(prior.owner(), owner)
              && Objects.equals(prior.plannedHash(), row.get("planned_source_sha256"))
              && "system".equals(row.get("originator")) && "WATCHER_RECONCILIATION".equals(row.get("transport"))
              && debt.root().equals(row.get("ingestion_root")) && Objects.equals(prior.scanId(), row.get("scan_id"))
              && Objects.equals(prior.predecessor(), predecessor)
              && (prior.predecessor() != null || !conflictingWatcherPathReceipt(journal, prior, root))
              && prior.root().generation().equals(root.generation()) && prior.root().key().equals(root.key())
              && prior.root().payload().equals(root.payload())
              && (prior.root().equals(root) || root.acceptedOrder() > prior.root().acceptedOrder())) {
            owned = true;
          }
        }
        if (row != null && !original && !owned) {
          // An independent exact committed effect may survive without borrowing its queue owner.
          return independentWatcherEffect(file, row, debt);
        }
        String source = file.sourceSha256();
        if (row == null) {
          if (!file.orderedApply()) return true;
          if (source.isEmpty()) { removeIndex.run(); freshProof.run(); return true; }
          resetWatcherFile(file, debt, predecessor);
        } else if (source.isEmpty()) {
          if (file.orderedApply()) {
            if (!(STATE_PENDING.equals(row.get("state")) && eligibleWatcherWait(row))
                && !STATE_DONE.equals(row.get("state"))) return false;
            try (var delete = connection.prepareStatement("DELETE FROM jobs WHERE path=?")) {
              delete.setString(1, file.path()); delete.executeUpdate();
            }
            removeIndex.run();
          } else if (STATE_PENDING.equals(row.get("state")) && eligibleWatcherWait(row)) {
            wakeWatcherFile(file.path());
          }
        } else if (STATE_PENDING.equals(row.get("state"))) {
          if (eligibleWatcherWait(row)) {
            if (!source.equals(row.get("planned_source_sha256"))) resetWatcherFile(file, debt, predecessor);
            else wakeWatcherFile(file.path());
          }
        } else if (STATE_DONE.equals(row.get("state"))) {
          if (file.orderedApply() && !file.targetMatches()) resetWatcherFile(file, debt, predecessor);
        } else return false;
        freshProof.run();
        return true;
      });
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Conditional watcher reconciliation failed", failure);
    } finally { unlockAfterChanges(); }
  }

  /** Policy omission is a read, including for encoded watcher predecessor ownership. */
  private boolean tryMatchesWatcherPolicyOmission(WatcherReconciliationFile file, Runnable freshProof) {
    if (!lock.tryLock()) return false;
    try {
      ensureOpen();
      if (!nativePolicyJournalMatches(file.candidate(), file.journal())) return false;
      var root = file.rootReceipt();
      if (root == null || !file.journal().contains(root)
          || !WatcherRootReconciliation.OPERATION.equals(root.op())
          || !root.generation().isEmpty() || root.acceptedOrder() <= 0
          || root.revision() == null || root.revision().isBlank()) return false;
      var debt = WatcherRootReconciliation.decode(root.payload());
      Path path = Path.of(file.path());
      if (!debt.key().equals(root.key()) || !file.path().equals(PathNormalizer.normalizeKey(path))
          || path.equals(Path.of(debt.root())) || !path.startsWith(Path.of(debt.root()))
          || IngestCollectionPolicy.isReserved(debt.collection())
          || file.sourceSha256() != null || !file.targetMatches()) return false;
      var row = watcherRowLocked(file.path());
      if (!Objects.equals(row, file.expectedRow()) || activeClaims.containsKey(file.path())
          || release030ExactFiles.contains(path) || release030IndependentDebt.contains(path)) return false;
      SwitchBufferOp predecessor = null;
      SwitchBufferOp deletion = null;
      for (var op : file.journal()) {
        if ("UPSERT".equals(op.op())) {
          var admitted = SwitchBufferUpsert.decode(op.payload());
          if (!file.path().equals(admitted.path())) continue;
          if (!("path:" + file.path()).equals(op.key())
              || op.acceptedOrder() >= root.acceptedOrder()) return false;
          if (predecessor == null || predecessor.acceptedOrder() < op.acceptedOrder()) predecessor = op;
        } else if ("DELETE".equals(op.op())) {
          var removed = SwitchBufferDelete.decode(op.payload());
          if (!file.path().equals(removed.path())) continue;
          if (!("path:" + file.path()).equals(op.key())
              || op.acceptedOrder() >= root.acceptedOrder()) return false;
          if (removed.origin() == SwitchBufferDelete.Origin.WATCHER
              && debt.root().equals(removed.root()) && debt.rootIdentity().equals(removed.rootIdentity())
              && Objects.equals(rootOwner(debt.collection()), rootOwner(removed.collection()))
              && (deletion == null || deletion.acceptedOrder() < op.acceptedOrder())) deletion = op;
        }
      }
      if (row == null) {
        // A missing retained UPSERT is unknown. Only the same root's conditional DELETE
        // can explain an absent queue owner, and cannot borrow a later admission.
        if (deletion == null || predecessor != null
            && predecessor.acceptedOrder() >= deletion.acceptedOrder()) return false;
      } else {
        if (!STATE_DONE.equals(row.get("state")) || !file.path().equals(row.get("path"))
            || !"SKIPPED_POLICY".equals(row.get("last_outcome_class"))
            || !"SKIPPED_TEMP_OR_SYSTEM".equals(row.get("last_reason_code"))
            || !"NONE".equals(row.get("last_retry_policy")) || row.get("content_hash") != null
            || row.get("retry_after") != null || row.get("walk_seen_epoch") != null
            || recordedWatcherRow(row) || row.get("unit_revision") == null
            || row.get("unit_revision").startsWith("release030")
            || !Objects.equals(rootOwner(debt.collection()), rootOwner(row.get("collection")))) return false;
        boolean original = predecessor != null && nativePolicySkippedAdmissionMatches(
            file.candidate(), file.journal(), predecessor, row)
            && debt.root().equals(row.get("ingestion_root"))
            && Objects.equals(rootOwner(debt.collection()),
                rootOwner(SwitchBufferUpsert.decode(predecessor.payload()).collection()));
        WatcherAdmission prior = parseWatcherAdmission(row.get("unit_revision"));
        boolean owned = prior != null && prior.candidate().equals(file.candidate())
            && prior.path().equals(file.path()) && Objects.equals(prior.owner(), rootOwner(debt.collection()))
            && Objects.equals(prior.plannedHash(), row.get("planned_source_sha256"))
            && "system".equals(row.get("originator")) && "WATCHER_RECONCILIATION".equals(row.get("transport"))
            && debt.root().equals(row.get("ingestion_root")) && Objects.equals(prior.scanId(), row.get("scan_id"))
            && Objects.equals(prior.predecessor(), predecessor)
            && (prior.predecessor() != null || !conflictingWatcherPathReceipt(file.journal(), prior, root))
            && prior.root().generation().equals(root.generation()) && prior.root().key().equals(root.key())
            && prior.root().payload().equals(root.payload())
            && (prior.root().equals(root) || root.acceptedOrder() > prior.root().acceptedOrder());
        if (!original && !owned) return false;
      }
      freshProof.run();
      return true;
    } catch (SQLException | RuntimeException unknown) { return false; }
    finally { lock.unlock(); } // Never drain or deliver another writer's committed events.
  }

  private static boolean conflictingWatcherPathReceipt(List<SwitchBufferOp> journal,
      WatcherAdmission prior, SwitchBufferOp currentRoot) {
    return journal.stream().filter(op -> op.acceptedOrder() > prior.root().acceptedOrder()
        && op.acceptedOrder() < currentRoot.acceptedOrder()).anyMatch(op -> switch (op.op()) {
          case "UPSERT" -> prior.path().equals(SwitchBufferUpsert.decode(op.payload()).path());
          case "DELETE" -> prior.path().equals(SwitchBufferDelete.decode(op.payload()).path());
          default -> false;
        });
  }

  private boolean independentWatcherEffect(WatcherReconciliationFile file,
      Map<String, String> row, WatcherRootReconciliation debt) {
    return row != null && !file.sourceSha256().isEmpty() && file.targetMatches()
        && Objects.equals(rootOwner(debt.collection()), rootOwner(row.get("collection")))
        && matchesAcceptedFileProjection(file.path(), row.get("unit_revision"),
            row.get("planned_source_sha256"), file.sourceSha256());
  }

  private boolean recordedWatcherRow(Map<String, String> row) throws SQLException {
    if (row.get("scan_id") == null) return false;
    try (var query = connection.prepareStatement("SELECT 1 FROM ingestion_walk_progress WHERE operation_key=?")) {
      query.setString(1, row.get("scan_id"));
      try (var result = query.executeQuery()) { return result.next(); }
    }
  }

  private static boolean eligibleWatcherWait(Map<String, String> row) {
    return "ROOT_UNAVAILABLE".equals(row.get("last_reason_code"))
            && "RETRY_UNTIL_AVAILABLE".equals(row.get("last_retry_policy"))
        || "STALE_AFTER_EXTRACTION".equals(row.get("last_reason_code"))
            && "DEFER_WITHOUT_ATTEMPT".equals(row.get("last_retry_policy"));
  }

  private void wakeWatcherFile(String path) throws SQLException {
    try (var update = connection.prepareStatement("UPDATE jobs SET retry_after=NULL WHERE path=? AND retry_after IS NOT NULL")) {
      update.setString(1, path); update.executeUpdate();
    }
  }

  private void resetWatcherFile(WatcherReconciliationFile file, WatcherRootReconciliation debt,
      SwitchBufferOp predecessor) throws SQLException {
    var identity = new WatcherAdmission(file.candidate(), file.rootReceipt(), file.path(),
        rootOwner(debt.collection()), predecessor, file.sourceSha256(), UUID.randomUUID().toString(),
        file.expectedRow() == null ? null : file.expectedRow().get("scan_id"));
    String revision = WATCHER_ADMISSION + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(WATCHER_JSON.writeValueAsBytes(identity));
    var entry = new EnqueueEntry(Path.of(file.path()), file.size(),
        new EnqueueProvenance("system", "WATCHER_RECONCILIATION"), file.sourceSha256(), Path.of(debt.root()));
    if (enqueueEntriesInTransaction(List.of(entry), rootOwner(debt.collection()), null,
        System.currentTimeMillis(), true, revision) != 1) throw new SQLException("Watcher file admission refused");
  }

  private static WatcherAdmission parseWatcherAdmission(String revision) {
    if (revision == null || !revision.startsWith(WATCHER_ADMISSION)) return null;
    try {
      var identity = WATCHER_JSON.readValue(java.util.Base64.getUrlDecoder().decode(
          revision.substring(WATCHER_ADMISSION.length())), WatcherAdmission.class);
      if (identity.candidate() == null || identity.candidate().isBlank() || identity.root() == null
          || identity.path() == null || !Path.of(identity.path()).isAbsolute()
          || !Path.of(identity.path()).normalize().toString().equals(identity.path())
          || identity.token() == null || !UUID.fromString(identity.token()).toString().equals(identity.token())
          || !IngestionLedgerTransition.isSha256(identity.plannedHash())
          || identity.root().revision() == null || identity.root().revision().isBlank()
          || identity.root().acceptedOrder() <= 0
          || !WatcherRootReconciliation.OPERATION.equals(identity.root().op())
          || !identity.root().generation().isEmpty()
          || !WatcherRootReconciliation.decode(identity.root().payload()).key().equals(identity.root().key())
          || !Objects.equals(rootOwner(WatcherRootReconciliation.decode(identity.root().payload()).collection()), identity.owner())
          || identity.predecessor() != null && (!"UPSERT".equals(identity.predecessor().op())
              || identity.predecessor().acceptedOrder() >= identity.root().acceptedOrder()
              || !identity.path().equals(SwitchBufferUpsert.decode(identity.predecessor().payload()).path()))) return null;
      String canonical = WATCHER_ADMISSION + java.util.Base64.getUrlEncoder().withoutPadding()
          .encodeToString(WATCHER_JSON.writeValueAsBytes(identity));
      return canonical.equals(revision) ? identity : null;
    } catch (RuntimeException malformed) { return null; }
  }

  @Override
  public int enqueueRefusalCleanupAdmission(
      JobQueue.EnqueueEntry entry, String collection, String admission) {
    if (entry == null || !isAdmissionRevision(admission)) return 0;
    return enqueueEntries(List.of(entry), collection, null, false, admission, false);
  }

  @Override
  public boolean holdsRefusalCleanupAdmission(String path, String admission) {
    if (path == null || !isAdmissionRevision(admission)) return false;
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query =
          connection.prepareStatement("SELECT unit_revision FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (ResultSet row = query.executeQuery()) {
          return row.next() && admission.equals(row.getString(1));
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Queue admission ownership evidence is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** The shape of a minted unit revision: 32 lowercase hex digits. */
  private static boolean isAdmissionRevision(String revision) {
    return revision != null && revision.matches("[0-9a-f]{32}");
  }

  @Override
  public SourceFileWork sourceFileWork(String path, String indexedSourceSha256) {
    if (path == null) return SourceFileWork.NONE;
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT state, content_hash, last_outcome_class, last_reason_code, last_retry_policy,"
              + " retry_after FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (ResultSet row = query.executeQuery()) {
          if (!row.next()) return SourceFileWork.NONE;
          String state = row.getString("state");
          if (endedByPolicy(state, row.getString("last_outcome_class"),
              row.getString("last_reason_code"), row.getString("last_retry_policy"))) {
            return SourceFileWork.POLICY;
          }
          if (STATE_FAILED.equals(state) || STATE_RETRY_EXHAUSTED.equals(state)) {
            return SourceFileWork.FAILED;
          }
          if (STATE_PENDING.equals(state)) {
            long retryAfter = row.getLong("retry_after");
            return !row.wasNull() && retryAfter > System.currentTimeMillis()
                ? SourceFileWork.FAILED : SourceFileWork.OUTSTANDING;
          }
          if (!STATE_DONE.equals(state)) return SourceFileWork.OUTSTANDING;
          // Only a write records content_hash, and every admission clears it, so a hash here
          // was written by this admission or a later one; a retired, refused or skipped row has
          // none. A later admission's bytes supersede the accepted ones even when they differ
          // from its captured plan, because the file legitimately changed again.
          String committed = row.getString("content_hash");
          return JobQueue.IngestionLedgerTransition.isSha256(committed)
              && committed.equals(indexedSourceSha256)
              ? SourceFileWork.COMMITTED : SourceFileWork.NONE;
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Queue admission ownership evidence is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** Match a completed write or the existing atomic UNCHANGED freshness receipt to its target. */
  public boolean matchesAppliedFileProjection(String path, String indexedSourceSha256,
      String modifiedAt, String collection) {
    if (path == null) return false;
    lock.lock();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement("""
          SELECT j.content_hash, j.last_outcome_class, j.last_reason_code, j.last_retry_policy,
            l.source_modified_at, l.collection FROM jobs j LEFT JOIN ingestion_ledger l
            ON l.path_hash=? AND l.observed_at=j.last_outcome_at
              AND l.outcome_class=j.last_outcome_class AND l.reason_code=j.last_reason_code
              AND l.retry_policy=j.last_retry_policy
          WHERE j.path=? AND j.state='DONE' AND j.collection IS ?
          ORDER BY l.id DESC LIMIT 1
          """)) {
        query.setString(1, sha256(path)); query.setString(2, path); query.setString(3, collection);
        try (var row = query.executeQuery()) {
          if (!row.next() || endedByPolicy(STATE_DONE, row.getString(2), row.getString(3), row.getString(4))) return false;
          String committed = row.getString(1);
          if (JobQueue.IngestionLedgerTransition.isSha256(committed) && committed.equals(indexedSourceSha256)) return true;
          if (modifiedAt == null || !"SKIPPED_POLICY".equals(row.getString(2))
              || !"UNCHANGED".equals(row.getString(3)) || !"NONE".equals(row.getString(4))) return false;
          final long modified;
          try { modified = Long.parseLong(modifiedAt); }
          catch (NumberFormatException invalid) { throw new IllegalStateException("Indexed freshness evidence is unreadable", invalid); }
          long observedModified = row.getLong(5);
          return !row.wasNull() && observedModified == modified && Objects.equals(collection, row.getString(6));
        }
      }
    } catch (SQLException unreadable) {
      recordDbError();
      throw new IllegalStateException("Applied file completion evidence is unreadable", unreadable);
    } finally { lock.unlock(); }
  }

  @Override
  public boolean matchesAcceptedCapturedFileDeletion(
      String path, String unitRevision, String plannedSourceSha256) {
    if (path == null || path.isBlank() || unitRevision == null || unitRevision.isBlank()
        || !JobQueue.IngestionLedgerTransition.isSha256(plannedSourceSha256)) return false;
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT state, unit_revision, planned_source_sha256, content_hash, scan_id, "
              + "walk_seen_epoch FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (ResultSet row = query.executeQuery()) {
          if (!row.next() || !STATE_DONE.equals(row.getString("state"))
              || !unitRevision.equals(row.getString("unit_revision"))
              || !plannedSourceSha256.equals(row.getString("planned_source_sha256"))) return false;
          String committed = row.getString("content_hash");
          if (!JobQueue.IngestionLedgerTransition.isSha256(committed)) return false;
          var progress = SqliteIngestionWalkOps.find(connection, row.getString("scan_id"))
              .orElse(null);
          if (progress == null || progress.acknowledgedRevision() >= progress.revision()
              || SqliteIngestionWalkOps.sealedReceipt(
                  connection, progress.operationKey(), SqliteJobQueue::sha256).isEmpty()) {
            return false;
          }
          return matchesCapturedFileProjection(row, path, unitRevision,
              plannedSourceSha256, committed, true);
        }
      }
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Accepted captured file deletion evidence is unreadable",
          unavailable);
    } finally {
      lock.unlock();
    }
  }

  /** The existing captured ledger owns H1-to-H2 settlement; streaming claims cannot use it. */
  private boolean matchesCapturedFileProjection(ResultSet job, String path, String unitRevision,
      String plannedSourceSha256, String indexedSourceSha256, boolean requireSealed)
      throws SQLException {
    long epoch = job.getLong("walk_seen_epoch");
    if (job.wasNull() || job.getString("scan_id") == null) return false;
    var progress = SqliteIngestionWalkOps.find(connection, job.getString("scan_id")).orElse(null);
    if (progress == null || !progress.capturedPlan()
        || progress.enumerationEpoch() != epoch
        || progress.enumerationOutcome() != JobQueue.WalkEnumerationOutcome.COMPLETE
        || progress.manifestSha256() == null
        || (requireSealed && progress.sealedAt() == null)) return false;
    String sql = """
        SELECT COUNT(*) FROM ingestion_ledger l
        WHERE l.operation_key = ? AND l.path_hash = ? AND l.unit_revision = ?
          AND l.planned_source_sha256 = ? AND l.content_hash = ?
          AND l.terminal_coverage = 'INDEXED'
          AND l.outcome_class IN ('SUCCESS_FULL', 'SUCCESS_PARTIAL', 'SUCCESS_EMPTY')
          AND l.retry_policy = 'NONE'
          AND (? = 0 OR EXISTS (
            SELECT 1 FROM ingestion_walk_sealed_units s
            WHERE s.operation_key = l.operation_key AND s.path_hash = l.path_hash
              AND s.unit_revision = l.unit_revision AND s.ledger_id = l.id
              AND s.sealed_revision = ?))
        """;
    try (var query = connection.prepareStatement(sql)) {
      query.setString(1, progress.operationKey());
      query.setString(2, sha256(path));
      query.setString(3, unitRevision);
      query.setString(4, plannedSourceSha256);
      query.setString(5, indexedSourceSha256);
      query.setInt(6, progress.sealedAt() == null ? 0 : 1);
      query.setLong(7, progress.revision());
      try (var row = query.executeQuery()) {
        return row.next() && row.getLong(1) == 1;
      }
    }
  }

  /**
   * Tempdoc 885 §UD open item 1: the re-enqueue and the state read share ONE transaction, because
   * re-admission resets the state being reported. A read before the call would race
   * the indexing loop claiming the same row.
   */
  @Override
  public JobQueue.ReenqueueResult reenqueue(JobQueue.EnqueueEntry entry) {
    if (entry == null || entry.path() == null) {
      return new JobQueue.ReenqueueResult(0, null);
    }
    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing to re-enqueue {}: insufficient free disk space", entry.path());
      return new JobQueue.ReenqueueResult(0, null);
    }
    lockTimed();
    try {
      ensureOpen();
      String normalizedPath =
          PathNormalizer.normalizePath(entry.path().toAbsolutePath().toString());
      JobQueue.ReenqueueResult result =
          inTransaction(
          () -> {
            String previousState = null;
            try (PreparedStatement read =
                connection.prepareStatement("SELECT state FROM jobs WHERE path = ?")) {
              read.setString(1, normalizedPath);
              try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                  previousState = rs.getString(1);
                }
              }
            }
            sealBeforeMaintenance(normalizedPath);
            var membership = SqliteIngestionWalkOps.maintenanceMembership(connection, normalizedPath, null);
            // A deliberate retry is a new admission with a fresh retry window and revision.
            // Same-admission failure/recovery updates preserve the existing revision.
            String sql =
                """
                INSERT INTO jobs
                  (path, state, attempts, last_updated, collection, size_bytes, scan_id,
                   originator, transport, unit_revision, walk_seen_epoch, planned_source_sha256, ingestion_root)
                VALUES (?, 'PENDING', 0, ?,
                  (SELECT prior.collection FROM jobs prior WHERE prior.path = ?), ?,
                  ?,
                  COALESCE(?, (SELECT prior.originator FROM jobs prior WHERE prior.path = ?)),
                  COALESCE(?, (SELECT prior.transport FROM jobs prior WHERE prior.path = ?)),
                  lower(hex(randomblob(16))), ?, ?,
                  COALESCE(?, (SELECT prior.ingestion_root FROM jobs prior WHERE prior.path = ?)))
                """ + SqliteSchema.REENQUEUE_JOB_ON_CONFLICT;
            int accepted;
            try (PreparedStatement write = connection.prepareStatement(sql)) {
              write.setString(1, normalizedPath);
              write.setLong(2, System.currentTimeMillis());
              write.setString(3, normalizedPath);
              if (entry.sizeBytes() >= 0) {
                write.setLong(4, entry.sizeBytes());
              } else {
                write.setNull(4, java.sql.Types.INTEGER);
              }
              write.setString(5, membership.key());
              write.setString(6, entry.provenance() == null ? null : entry.provenance().originator());
              write.setString(7, normalizedPath);
              write.setString(8, entry.provenance() == null ? null : entry.provenance().transport());
              write.setString(9, normalizedPath);
              setNullableLong(write, 10, membership.epoch());
              write.setString(11, membership.plannedSourceSha256());
              write.setString(12, PathNormalizer.normalizeKey(entry.ingestionRoot()));
              write.setString(13, normalizedPath);
              accepted = executeMutation(write::executeUpdate) > 0 ? 1 : 0;
            }
            if (accepted > 0 && membership.epoch() != null) SqliteIngestionWalkOps.noteMutation(connection, membership.key());
            return new JobQueue.ReenqueueResult(accepted, previousState);
          });
      // After the commit, like enqueueEntries: a meter incremented inside the transaction would
      // still count a row a rollback threw away.
      if (result.accepted() > 0) {
        meters.recordEnqueued(result.accepted());
      }
      log.debug(
          "Re-enqueued {} job (previousState={})", result.accepted(), result.previousState());
      return result;
    } catch (SQLException e) {
      log.error("Failed to re-enqueue {}", entry.path(), e);
      return new JobQueue.ReenqueueResult(0, null);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public boolean hasPendingIngestWork() {
    lock.lock();
    try {
      ensureOpen();
      try (var query = connection.prepareStatement("""
          SELECT EXISTS(SELECT 1 FROM jobs WHERE state='PENDING' AND retry_after IS NULL)
            OR EXISTS(SELECT 1 FROM jobs WHERE state='PENDING' AND retry_after<=?)
          """)) {
        query.setLong(1,System.currentTimeMillis());
        try (var row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
      }
    } catch (SQLException failure) {
      recordDbError();
      log.debug("Pending-ingest readiness unavailable", failure);
      return false;
    } finally { lock.unlock(); }
  }

  @Override
  public boolean pollPendingBudgetExhausted() { return claimBudgetExhausted; }

  private boolean pendingWalkAllowsClaim(ClaimCandidate candidate, Map<String,Boolean> claimableWalks) throws SQLException {
    if (candidate.walkEpoch() == null) return true;
    Boolean cached=claimableWalks.get(candidate.scanId());
    if(cached!=null)return cached;
    try (var query = connection.prepareStatement("""
        SELECT 1 FROM ingestion_walk_progress WHERE operation_key=? AND sealed_at IS NULL
          AND (enumeration_outcome IS NULL OR enumeration_outcome='COMPLETE')
          AND (captured_plan=0 OR (enumeration_outcome='COMPLETE' AND manifest_sha256 IS NOT NULL))
        """)) {
      query.setString(1,candidate.scanId());
      try (var row = query.executeQuery()) {
        boolean allowed=row.next();
        claimableWalks.put(candidate.scanId(),allowed);
        return allowed;
      }
    }
  }

  private List<ClaimCandidate> claimCandidates(boolean retryDue, long now, int budget, ClaimKey after) throws SQLException {
    // Avoid walking future retries in age order when none are due. This seek uses the
    // deadline index; the candidate walk below uses a covering, age-ordered index.
    if (retryDue) {
      try (var exists = connection.prepareStatement(
          "SELECT 1 FROM jobs INDEXED BY idx_jobs_state_retry WHERE state='PENDING' AND retry_after<=? LIMIT 1")) {
        exists.setLong(1, now);
        try (var row = exists.executeQuery()) {
          if (!row.next()) return List.of();
        }
      }
    }
    String sql = "SELECT path,last_updated,collection,originator,transport,scan_id,unit_revision,"
        + "walk_seen_epoch,planned_source_sha256,ingestion_root FROM jobs INDEXED BY "
        + (retryDue ? "idx_jobs_pending_retry" : "idx_jobs_pending_ready")
        + " WHERE state='PENDING' AND " + (retryDue ? "retry_after<=?" : "retry_after IS NULL")
        + (after == null ? " AND last_updated>=?" : " AND (last_updated,path)>(?,?)")
        + " ORDER BY last_updated,path LIMIT ?";
    var candidates = new ArrayList<ClaimCandidate>();
    try (var query = connection.prepareStatement(sql)) {
      int parameter = 1;
      if (retryDue) query.setLong(parameter++,now);
      if (after != null) {
        query.setLong(parameter++,after.updated()); query.setString(parameter++,after.path());
      } else query.setLong(parameter++,Long.MIN_VALUE);
      query.setInt(parameter,budget);
      try (var row = query.executeQuery()) {
        while (row.next()) {
          long epoch = row.getLong(8); Long walkEpoch = row.wasNull() ? null : epoch;
          candidates.add(new ClaimCandidate(new ClaimKey(row.getLong(2),row.getString(1)),
              row.getString(3),row.getString(4),row.getString(5),row.getString(6),row.getString(7),
              walkEpoch,row.getString(9),row.getString(10)));
        }
      }
    }
    return candidates;
  }

  /** Observe every admission/retry write, including SQL helpers, without retaining job identities. */
  private void installClaimCursorInvalidation() throws SQLException {
    org.sqlite.Function.create(connection, "jobs_claim_order_changed", new org.sqlite.Function() {
      @Override protected void xFunc() throws SQLException {
        claimOrderVersion++;
        if (claimCursor != null && new ClaimKey(value_long(0), value_text(1)).compareTo(claimCursor) <= 0) {
          claimCursor = null;
        }
        result();
      }
    });
    try (var stmt = connection.createStatement()) {
      stmt.execute("""
          CREATE TEMP TRIGGER jobs_claim_insert AFTER INSERT ON main.jobs
          WHEN NEW.state='PENDING'
          BEGIN SELECT jobs_claim_order_changed(NEW.last_updated,NEW.path); END
          """);
      stmt.execute("""
          CREATE TEMP TRIGGER jobs_claim_update AFTER UPDATE ON main.jobs
          WHEN NEW.state='PENDING' AND (OLD.state IS NOT NEW.state OR OLD.last_updated IS NOT NEW.last_updated
            OR OLD.path IS NOT NEW.path OR OLD.retry_after IS NOT NEW.retry_after OR OLD.scan_id IS NOT NEW.scan_id
            OR OLD.walk_seen_epoch IS NOT NEW.walk_seen_epoch OR OLD.unit_revision IS NOT NEW.unit_revision
            OR OLD.planned_source_sha256 IS NOT NEW.planned_source_sha256)
          BEGIN SELECT jobs_claim_order_changed(NEW.last_updated,NEW.path); END
          """);
    }
  }

  private void installOutcomeMetrics() throws SQLException {
    org.sqlite.Function.create(connection, "jobs_terminal_outcome", new org.sqlite.Function() {
      @Override protected void xFunc() throws SQLException {
        pendingOutcomeMetrics.add(value_text(0));
        result();
      }
    });
    try (var stmt = connection.createStatement()) {
      stmt.execute("""
          CREATE TEMP TRIGGER jobs_terminal_outcome AFTER UPDATE ON main.jobs
          WHEN NEW.state IN ('DONE','FAILED','RETRY_EXHAUSTED')
            AND OLD.state NOT IN ('DONE','FAILED','RETRY_EXHAUSTED')
          BEGIN SELECT jobs_terminal_outcome(NEW.last_outcome_class); END
          """);
    }
  }

  @Override
  public void recordedClaimAuthorityChanged(String operationKey) {
    Objects.requireNonNull(operationKey, "operationKey");
    lock.lock();
    try {
      ensureOpen();
      claimCursor = null;
      claimOrderVersion++;
    } finally { unlockAfterChanges(); }
  }

  @Override
  public List<IndexJob> pollPending(int limit) {
    claimBudgetExhausted = false;
    if (limit <= 0) return List.of();
    lockTimed();
    try {
      ensureOpen();

      if (releaseRecovery != null && releaseRecovery.retiringBuild()) {
        try { prepareRetirementClaims(); }
        catch (IOException invalid) { throw new SQLException("Current retirement claims cannot be prepared", invalid); }
      }
      long now = System.currentTimeMillis();
      if (claimRetryDeadline != null && claimRetryDeadline <= now) claimCursor = null;
      try (var deadline = connection.prepareStatement(
          "SELECT MIN(retry_after) FROM jobs WHERE state='PENDING' AND retry_after>?")) {
        deadline.setLong(1,now);
        try (var row = deadline.executeQuery()) {
          if (row.next()) {
            long value = row.getLong(1); claimRetryDeadline = row.wasNull() ? null : value;
          }
        }
      }
      if (walkChanges != null && claimWalkVersion != walkChanges.changeVersion()) {
        claimCursor = null;
        claimWalkVersion = walkChanges.changeVersion();
      }
      var nextCursor = new ClaimKey[1];
      var budgetStopped = new boolean[1];
      long orderVersion = claimOrderVersion;

      // Local carrier for a candidate row selected before any mutation happens.
      record ClaimedRow(String path, String collection, JobQueue.EnqueueProvenance provenance,
        String scanId, String unitRevision, Long walkEpoch, RecordedClaimDecision decision,
        String plannedHash, String ingestionRoot) {}

      // Claim is atomic via an explicit transaction (BEGIN/COMMIT through the existing
      // inTransaction() helper), not a single UPDATE...RETURNING statement: SQLite's RETURNING
      // clause (a) may only reference columns of the table being updated - not a joined CTE - and
      // (b) its row order is documented as unspecified and empirically does NOT follow an
      // ORDER BY used to select the candidate set (verified: it follows the UPDATE's own
      // row-visitation order instead). So the SELECT below - not RETURNING - is the single
      // source of both the claimed set AND its order; the follow-up UPDATE only flips state for
      // exactly those paths. Wrapping both statements in one transaction preserves the original
      // atomicity/crash-safety intent (either the whole claim commits or none of it does; no
      // partial-progress window is visible to another connection).
      //
      // Tempdoc 731 §3.2 / PLAN I2: `last_updated ASC, path ASC` makes claim order deterministic
      // even when a batch shares one enqueue-time timestamp (the fixable determinism gap).
      List<IndexJob> result =
          inTransaction(
              () -> {
                RetirementOrders retirementOrders = null;
                List<RetirementDelete> retirementDeletes = List.of();
                java.util.function.BiPredicate<String,String> retained = (path,unit) -> false;
                if (releaseRecovery != null && releaseRecovery.retiringBuild()) {
                  try {
                    var journal = retirementReplayOps();
                    retirementOrders = retirementOrders(journal);
                    retirementDeletes = retirementDeletes(journal);
                    retained = retainedRootScopes(journal);
                  } catch (IOException invalid) { throw new SQLException("Retirement claim evidence unavailable",invalid); }
                }
                int budget = (int) Math.min(Integer.MAX_VALUE - 1L, (long) limit + CLAIM_SKIP_BUDGET);
                // The common case needs only the requested batch plus one lookahead row.
                // Expand to the remaining skip budget only when a denied source runs out;
                // fetching full metadata for 273 rows on every 16-row poll wastes read work.
                int sourceCap = (int) Math.min((long) limit + 1, (long) budget + 1);
                var ready = claimCandidates(false,now,sourceCap,claimCursor);
                var due = claimCandidates(true,now,sourceCap,claimCursor);
                int r = 0, d = 0, visited = 0;
                // Many denied candidates share one walk. Cache only its SQL precondition,
                // for this bounded poll; authority decisions below are always checked per row.
                var claimableWalks=new HashMap<String,Boolean>();
                long checkedWalkVersion=walkChanges==null?0:walkChanges.changeVersion();
                List<ClaimedRow> claimedRows = new ArrayList<>();
                while (claimedRows.size() < limit && visited < budget) {
                  // Re-read both sources after the last merged key before choosing again:
                  // the exhausted source's next row may precede the other source's lookahead.
                  if ((r == ready.size() && ready.size() == sourceCap)
                      || (d == due.size() && due.size() == sourceCap)) {
                    sourceCap = budget - visited + 1;
                    ready = claimCandidates(false,now,sourceCap,nextCursor[0]);
                    due = claimCandidates(true,now,sourceCap,nextCursor[0]);
                    r = 0; d = 0;
                  }
                  if (r == ready.size() && d == due.size()) break;
                  ClaimCandidate row;
                  if (d == due.size() || (r < ready.size()
                      && ready.get(r).key().compareTo(due.get(d).key()) <= 0)) {
                    row = ready.get(r);
                    r++;
                  } else {
                    row = due.get(d);
                    d++;
                  }
                  visited++;
                  nextCursor[0] = row.key();
                  long walkVersion=walkChanges==null?0:walkChanges.changeVersion();
                  if(walkVersion!=checkedWalkVersion) {
                    // A reentrant authority callback may have changed progress under this lock.
                    claimableWalks.clear();
                    checkedWalkVersion=walkVersion;
                  }
                  if (!retirementAllowsClaim(row,retirementOrders,retirementDeletes,retained)) continue;
                  if (requiresRelease030Boundary && row.ingestionRoot() == null) continue;
                  // All exact paths must have their no-deletion protection before any claim.
                  if (!release030ExplicitFilesPrepared && row.key().path().equals(row.ingestionRoot())) continue;
                  if (activeClaims.containsKey(row.key().path()) || !pendingWalkAllowsClaim(row,claimableWalks)) continue;
                  var decision = recordedClaimDecision(row.scanId(), row.walkEpoch(), row.plannedHash());
                  if (decision == RecordedClaimDecision.DENY) continue;
                  claimedRows.add(new ClaimedRow(row.key().path(), row.collection(),
                      row.originator() == null && row.transport() == null ? null
                          : new JobQueue.EnqueueProvenance(row.originator(),row.transport()),
                      row.scanId(),row.revision(),row.walkEpoch(),decision,row.plannedHash(),row.ingestionRoot()));
                }
                boolean more = r < ready.size() || d < due.size()
                    || ready.size() == sourceCap || due.size() == sourceCap;
                budgetStopped[0] = visited == budget && claimedRows.size() < limit && more;
                // Preserve the cursor across full batches too, so a denied prefix is paid only once.
                if (!more) nextCursor[0] = null;

                if (claimedRows.isEmpty()) {
                  return List.<IndexJob>of();
                }

                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < claimedRows.size(); i++) {
                  if (i > 0) {
                    placeholders.append(',');
                  }
                  placeholders.append('?');
                }
                // Tempdoc 730 review Scope-3 (defense-in-depth): guard the claim UPDATE with
                // `state = 'PENDING'` too, not just the upstream SELECT. Today this connection
                // holds `lock` for the whole SELECT+UPDATE transaction, so there is no real
                // window for another consumer to have moved a claimed path off PENDING between
                // the two statements — but a future multi-consumer/multi-connection change should
                // not silently regress into re-claiming (and thus double-processing) a row a
                // concurrent claim already moved to PROCESSING/DONE/FAILED.
                String updateSql =
                    "UPDATE jobs INDEXED BY sqlite_autoindex_jobs_1 SET state = 'PROCESSING', last_updated = ? WHERE state = 'PENDING' AND path IN ("
                        + placeholders
                        + ")";
                try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                  updateStmt.setLong(1, now);
                  for (int i = 0; i < claimedRows.size(); i++) {
                    updateStmt.setString(i + 2, claimedRows.get(i).path());
                  }
                  executeMutation(updateStmt::executeUpdate);
                }

                List<IndexJob> claimed = new ArrayList<>(claimedRows.size());
                for (ClaimedRow row : claimedRows) {
                  claimed.add(new IndexJob(Path.of(row.path()), row.collection(), row.provenance(),
                      row.scanId(), row.unitRevision(), row.walkEpoch(), row.decision() == RecordedClaimDecision.ALLOW_FORCE,
                      row.plannedHash(), row.ingestionRoot() == null ? null : Path.of(row.ingestionRoot())));
                }
                return claimed;
              });

      // A reentrant authority callback may have admitted earlier work during this poll.
      claimCursor = orderVersion == claimOrderVersion ? nextCursor[0] : null;
      claimBudgetExhausted = budgetStopped[0];
      for (IndexJob claim : result) activeClaims.put(normalizePath(claim.path()), claim);
      if (!result.isEmpty()) {
        meters.recordDequeued(result.size());
        log.debug("Claimed {} jobs for processing", result.size());
      }

      return result;
    } catch (SQLException e) {
      throw SqliteServiceAvailability.failure("Failed to poll pending jobs", e);
    } finally {
      unlockAfterChanges();
    }
  }

  private boolean isIssuedClaim(IndexJob claim) {
    return claim != null && activeClaims.get(normalizePath(claim.path())) == claim;
  }

  @Override
  public boolean ownsClaimForPublication(IndexJob claim) {
    lock.lock();
    try {
      ensureOpen();
      return ownsClaim(claim);
    } catch (SQLException unavailable) {
      recordDbError();
      throw new IllegalStateException("Claim publication ownership is unreadable", unavailable);
    } finally {
      lock.unlock();
    }
  }

  private boolean ownsClaim(IndexJob claim) throws SQLException {
    if (!isIssuedClaim(claim)) return false;
    String path = normalizePath(claim.path());
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT state, scan_id, unit_revision FROM jobs WHERE path = ?")) {
      query.setString(1, path);
      try (ResultSet result = query.executeQuery()) {
        return result.next() && STATE_PROCESSING.equals(result.getString(1))
            && Objects.equals(claim.scanId(), result.getString(2))
            && Objects.equals(claim.unitRevision(), result.getString(3));
      }
    }
  }

  private void releaseClaim(IndexJob claim) {
    String path = normalizePath(claim.path());
    if (activeClaims.get(path) == claim) {
      activeClaims.remove(path);
      if (claim.walkEpoch() != null && walkChanges != null) walkChanges.claimReleased(claim.scanId());
    }
  }

  private boolean cancelledClaim(IndexJob claim) throws SQLException {
    try (var query = connection.prepareStatement("""
        SELECT 1 FROM jobs WHERE path=? AND scan_id IS ? AND unit_revision IS ?
          AND state='DONE' AND last_reason_code='CANCELLED'
        """)) {
      query.setString(1, normalizePath(claim.path()));
      query.setString(2, claim.scanId());
      query.setString(3, claim.unitRevision());
      try (var row = query.executeQuery()) { return row.next(); }
    }
  }

  private boolean finishClaim(IndexJob claim, Runnable update, SqlWork<Void> superseded) {
    lock.lock();
    try {
      ensureOpen();
      if (!isIssuedClaim(claim)) return false;
      if (!ownsClaim(claim)) {
        if (claim.walkEpoch() != null && !cancelledClaim(claim)) inTransaction(superseded);
        claimCursor = null;
        claimOrderVersion++;
        releaseClaim(claim);
        return false;
      }
      update.run();
      releaseClaim(claim);
      return true;
    } catch (SQLException failure) {
      throw new OutcomeWriteException("Could not verify processing claim", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public boolean markClaimDone(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return finishClaim(claim, () -> markDoneWithOutcome(claim.path(), outcome, entry, claim),
        () -> recordSupersededOutcome(claim, outcome, entry, SqliteIngestionWalkOps.Coverage.SKIPPED));
  }

  @Override
  public boolean markClaimFailed(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return finishClaim(claim, () -> markFailedWithOutcome(claim.path(), outcome, entry, claim), () -> {
      SqliteIngestionWalkOps.requireFailureOutcome(outcome);
      // The replacement owns its new retry window. A superseded retryable attempt is only diagnostic.
      return recordSupersededOutcome(claim, outcome, entry,
          outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE
              ? SqliteIngestionWalkOps.Coverage.FAILED : null);
    });
  }

  @Override
  public boolean deferClaim(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    return deferClaim(claim, outcome, entry, 0L);
  }

  @Override
  public boolean deferClaim(IndexJob claim, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry,
      long retryAtMs) {
    return finishClaim(claim, () -> deferWithOutcome(claim.path(), outcome, entry, claim, retryAtMs),
        () -> recordSupersededOutcome(claim, outcome, entry, null));
  }

  @Override
  public boolean supersedeStreamingRecordedSource(IndexJob claim, String observedSha256,
      IngestionOutcome staleOutcome, JobQueue.IngestionLedgerEntry entry) {
    if (claim == null || claim.walkEpoch() == null || claim.plannedSourceSha256() == null
        || !IngestionLedgerTransition.isSha256(observedSha256)
        || claim.plannedSourceSha256().equals(observedSha256)) return false;
    lock.lock();
    try {
      ensureOpen();
      if (!ownsClaim(claim)) return false;
      boolean replaced = inTransaction(() -> supersedeStreamingRecordedSourceInTransaction(
          claim, observedSha256, staleOutcome, entry));
      if (replaced) releaseClaim(claim);
      return replaced;
    } catch (SQLException failure) {
      throw new OutcomeWriteException("Could not supersede recorded candidate source", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  private boolean supersedeStreamingRecordedSourceInTransaction(IndexJob claim,
      String observedSha256, IngestionOutcome staleOutcome, JobQueue.IngestionLedgerEntry entry)
      throws SQLException {
    String path = normalizePath(claim.path());
    var progress = SqliteIngestionWalkOps.find(connection, claim.scanId())
        .orElseThrow(() -> new SQLException("Recorded source owner is unavailable"));
    if (progress.capturedPlan() || progress.sealedAt() != null
        || progress.enumerationEpoch() != claim.walkEpoch()
        || progress.enumerationOutcome() == WalkEnumerationOutcome.FAILED
        || progress.enumerationOutcome() == WalkEnumerationOutcome.CANCELLED) return false;
    try (var query = connection.prepareStatement(
        "SELECT state, scan_id, walk_seen_epoch, unit_revision, planned_source_sha256 "
            + "FROM jobs WHERE path = ?")) {
      query.setString(1, path);
      try (var row = query.executeQuery()) {
        if (!row.next() || !STATE_PROCESSING.equals(row.getString(1))
            || !Objects.equals(claim.scanId(), row.getString(2))
            || row.getLong(3) != claim.walkEpoch() || row.wasNull()
            || !Objects.equals(claim.unitRevision(), row.getString(4))
            || !Objects.equals(claim.plannedSourceSha256(), row.getString(5))) return false;
      }
    }
    String generation = null;
    try (var query = connection.prepareStatement(
        "SELECT generation, op, payload FROM switch_buffer WHERE key = ? AND generation <> ''")) {
      query.setString(1, "path:" + path);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (!"UPSERT".equals(rows.getString(2))) continue;
          SwitchBufferUpsert upsert;
          try {
            upsert = SwitchBufferUpsert.decode(rows.getString(3));
          } catch (RuntimeException malformed) {
            throw new SQLException("Candidate source journal is unreadable", malformed);
          }
          if (path.equals(upsert.path()) && Objects.equals(claim.unitRevision(), upsert.unitRevision())
              && claim.plannedSourceSha256().equals(upsert.sourceSha256())) {
            if (generation != null) throw new SQLException("Candidate source journal has multiple owners");
            generation = rows.getString(1);
          }
        }
      }
    }
    if (generation == null) return false;
    String nextRevision = UUID.randomUUID().toString().replace("-", "");
    recordSupersededOutcome(claim, staleOutcome, entry, null);
    try (var update = connection.prepareStatement(
        "UPDATE jobs SET state = 'PENDING', attempts = 0, retry_after = NULL, "
            + "first_failed_at = NULL, error_message = NULL, "
            + "content_hash = NULL, last_updated = ?, unit_revision = ?, planned_source_sha256 = ?, "
            + "last_outcome_class = NULL, last_reason_code = NULL, last_retry_policy = NULL, "
            + "last_diagnostic_summary = NULL, last_outcome_at = NULL "
            + "WHERE path = ? AND state = 'PROCESSING' AND scan_id = ? "
            + "AND walk_seen_epoch = ? AND unit_revision = ? AND planned_source_sha256 = ?")) {
      update.setLong(1, System.currentTimeMillis());
      update.setString(2, nextRevision);
      update.setString(3, observedSha256);
      update.setString(4, path);
      update.setString(5, claim.scanId());
      update.setLong(6, claim.walkEpoch());
      update.setString(7, claim.unitRevision());
      update.setString(8, claim.plannedSourceSha256());
      if (update.executeUpdate() != 1) throw new SQLException("Recorded source claim changed");
    }
    var payload = new SwitchBufferUpsert(path, claim.collection(), claim.provenance(),
        nextRevision, observedSha256, claim.ingestionRoot()).encode();
    if (!switchBufferOps.putForGenerationInTransaction(
        connection, generation, "path:" + path, "UPSERT", payload)) {
      throw new SQLException("Candidate source journal refused replacement");
    }
    SqliteIngestionWalkOps.noteMutation(connection, claim.scanId());
    return true;
  }

  private Void recordSupersededOutcome(IndexJob claim, IngestionOutcome outcome,
      JobQueue.IngestionLedgerEntry entry, SqliteIngestionWalkOps.Coverage coverage) throws SQLException {
    String path = normalizePath(claim.path());
    insertLedgerEvent(path, outcome, ledgerEntryForClaim(path, entry, claim),
        SqliteIngestionWalkOps.claimReceipt(connection, claim, coverage, null));
    return null;
  }

  @Override
  public void markDone(Path path) {
    lock.lock();
    try {
      ensureOpen();
      if (SqliteIngestionWalkOps.currentReceipt(connection, normalizePath(path), null) != null) {
        throw new IllegalStateException("Recorded completion requires a typed outcome");
      }

      String sql = """
          UPDATE jobs SET state = 'DONE', content_hash = NULL, last_updated = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        stmt.setString(2, PathNormalizer.normalizePath(path.toAbsolutePath().toString()));
        executeMutation(stmt::executeUpdate);
      }

      log.debug("Marked job done: {}", path);
    } catch (SQLException e) {
      log.error("Failed to mark job done: {}", path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome) {
    markDone(path, outcome, null);
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    markDoneWithOutcome(path, outcome, entry, null);
  }

  private void markDoneWithOutcome(Path path, IngestionOutcome outcome,
      JobQueue.IngestionLedgerEntry entry, IndexJob claim) {
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                var receipt = claim == null
                    ? SqliteIngestionWalkOps.currentReceipt(connection, normalizedPath, SqliteIngestionWalkOps.Coverage.SKIPPED)
                    : SqliteIngestionWalkOps.claimReceipt(connection, claim, SqliteIngestionWalkOps.Coverage.SKIPPED, null);
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', content_hash = NULL, retry_after = NULL, last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ? AND NOT (state = 'DONE' AND last_reason_code IS 'CANCELLED')
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  long now = System.currentTimeMillis();
                  bindOutcomeUpdate(stmt, 1, now, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, ledgerEntryForClaim(normalizedPath, entry, claim), receipt);
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "markDone(outcome)", path);
      log.debug("Marked job done with outcome {}: {}", outcomeClassName(outcome), path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "Outcome-aware markDone failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths) {
    if (paths == null || paths.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();
      for (Path path : paths) {
        if (SqliteIngestionWalkOps.currentReceipt(connection, normalizePath(path), null) != null) {
          throw new IllegalStateException("Recorded completion requires a typed outcome");
        }
      }

      long now = System.currentTimeMillis();
      // SQLite has a default SQLITE_MAX_VARIABLE_NUMBER of 999.
      // Chunk into batches of 499 (2 params per row: state timestamp + path).
      int chunkSize = 499;
      var pathList = paths instanceof List<?> ? (List<Path>) paths
          : new ArrayList<>(paths);

      for (int offset = 0; offset < pathList.size(); offset += chunkSize) {
        int end = Math.min(offset + chunkSize, pathList.size());
        var chunk = pathList.subList(offset, end);

        StringBuilder sb = new StringBuilder("UPDATE jobs SET state = 'DONE', content_hash = NULL, last_updated = ? WHERE path IN (");
        for (int i = 0; i < chunk.size(); i++) {
          if (i > 0) sb.append(',');
          sb.append('?');
        }
        sb.append(')');

        try (PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
          stmt.setLong(1, now);
          for (int i = 0; i < chunk.size(); i++) {
            stmt.setString(2 + i, PathNormalizer.normalizePath(
                chunk.get(i).toAbsolutePath().toString()));
          }
          executeMutation(stmt::executeUpdate);
        }
      }

      log.debug("Marked {} jobs done (batch)", paths.size());
    } catch (SQLException e) {
      log.error("Failed to mark {} jobs done (batch)", paths.size(), e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths, IngestionOutcome outcome) {
    if (paths == null || paths.isEmpty()) return;
    List<JobQueue.IngestionLedgerTransition> transitions = new ArrayList<>(paths.size());
    for (Path path : paths) {
      transitions.add(new JobQueue.IngestionLedgerTransition(path, null));
    }
    markDoneTransitions(transitions, outcome);
  }

  @Override
  public void markDoneTransitions(
      java.util.Collection<JobQueue.IngestionLedgerTransition> transitions,
      IngestionOutcome outcome) {
    if (transitions == null || transitions.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();
      List<JobQueue.IngestionLedgerTransition> eligible = new ArrayList<>();
      Set<IndexJob> seenClaims = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (JobQueue.IngestionLedgerTransition transition : transitions) {
        if (transition == null) continue;
        if (transition.claim() != null && !seenClaims.add(transition.claim())) continue;
        if (transition.claim() == null || (isIssuedClaim(transition.claim()) && !cancelledClaim(transition.claim()))) {
          eligible.add(transition);
        }
      }
      long now = System.currentTimeMillis();
      int[] updates =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', content_hash = ?, last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                List<Integer> rowCounts = new ArrayList<>(eligible.size());
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  for (JobQueue.IngestionLedgerTransition transition : eligible) {
                    if (transition == null) continue;
                    String normalizedPath = normalizePath(transition.path());
                    if (transition.claim() == null && SqliteIngestionWalkOps.currentReceipt(
                        connection, normalizedPath, SqliteIngestionWalkOps.Coverage.INDEXED) != null) {
                      throw new SQLException("Recorded index completion requires an issued claim");
                    }
                    var receipt = SqliteIngestionWalkOps.claimReceipt(connection, transition.claim(),
                        SqliteIngestionWalkOps.Coverage.INDEXED, transition.committedContentHash());
                    int rows = 0;
                    if (transition.claim() == null || ownsClaim(transition.claim())) {
                      stmt.setString(1, transition.committedContentHash());
                      bindOutcomeUpdate(stmt, 2, now, outcome, normalizedPath);
                      rows = executeMutation(stmt::executeUpdate);
                    } else {
                      claimCursor = null;
                      claimOrderVersion++;
                    }
                    rowCounts.add(rows);
                    if (rows > 0 || transition.claim() != null) {
                      // The eligible list contains only exact issued claims. A superseded untagged
                      // claim still committed an effect; retain its history without completing R1.
                      insertLedgerEvent(normalizedPath, outcome,
                          ledgerEntryForClaim(normalizedPath, transition.entry(), transition.claim()), receipt);
                    }
                  }
                }
                int[] result = new int[rowCounts.size()];
                for (int i = 0; i < rowCounts.size(); i++) {
                  result[i] = rowCounts.get(i);
                }
                return result;
              });
      // Even obsolete issued claims are released only after the whole outcome transaction commits.
      for (JobQueue.IngestionLedgerTransition transition : transitions) {
        if (transition != null && transition.claim() != null) releaseClaim(transition.claim());
      }
      logBatchMisses(updates, "markDoneTransitions(outcome)");
      log.debug("Marked {} jobs done with outcome {}", transitions.size(), outcomeClassName(outcome));
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markDoneTransitions failed for "
              + transitions.size()
              + " path(s) (completion could not be confirmed)",
          e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markFailed(Path path, String errorMessage) {
    lock.lock();
    try {
      ensureOpen();

      // First, check current attempts
      String checkSql = "SELECT attempts FROM jobs WHERE path = ?";
      int currentAttempts = 0;

      String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
      if (SqliteIngestionWalkOps.currentReceipt(connection, normalizedPath, null) != null) {
        throw new IllegalStateException("Recorded failure requires an issued claim and typed outcome");
      }
      try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
        stmt.setString(1, normalizedPath);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            currentAttempts = rs.getInt(1);
          }
        }
      }

      // Increment attempts on failure (not on claim)
      int newAttempts = currentAttempts + 1;

      String newState = newAttempts >= maxAttempts ? "FAILED" : "PENDING";
      long now = System.currentTimeMillis();

      // Calculate retry_after with exponential backoff + jitter
      // Base backoff: 1s * 2^(newAttempts-1), capped at ~17 minutes
      // Jitter: random value in [0, min(1s, backoff)] to prevent retry storms
      Long retryAfter = null;
      if ("PENDING".equals(newState)) {
        long backoffMs = 1000L * (1L << Math.min(newAttempts - 1, 10));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
        retryAfter = now + backoffMs + jitter;
      }

      // Update with incremented attempts
      String updateSql = """
          UPDATE jobs SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
        stmt.setString(1, newState);
        stmt.setInt(2, newAttempts);
        stmt.setLong(3, now);
        stmt.setString(4, errorMessage);
        if (retryAfter != null) {
          stmt.setLong(5, retryAfter);
        } else {
          stmt.setNull(5, java.sql.Types.INTEGER);
        }
        stmt.setString(6, normalizedPath);
        executeMutation(stmt::executeUpdate);
      }

      if ("FAILED".equals(newState)) {
        log.warn("Job permanently failed after {} attempts: {}", newAttempts, path);
      } else {
        long backoffSeconds = retryAfter != null ? (retryAfter - now) / 1000 : 0;
        log.debug("Job failed (attempt {}), will retry after {}s: {}", newAttempts, backoffSeconds, path);
      }
    } catch (SQLException e) {
      log.error("Failed to mark job failed: {}", path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void recordOutcome(Path path, IngestionOutcome outcome) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  bindOutcomeOnly(stmt, 1, outcome, normalizedPath);
                  return executeMutation(stmt::executeUpdate);
                }
              });
      logIfNoRows(updated, "recordOutcome", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordOutcome failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public void markFailed(Path path, IngestionOutcome outcome) {
    markFailed(path, outcome, null);
  }

  @Override
  public void markFailed(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    markFailedWithOutcome(path, outcome, entry);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome) {
    defer(path, outcome, null);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    deferWithOutcome(path, outcome, entry, null, 0L);
  }

  @Override
  public void extendServiceOutageDeferrals(long retryAtMs) {
    lock.lock();
    try {
      ensureOpen();
      // The policy distinguishes service grace/outages from the counted file retry ladders.
      // The outcome class excludes other uncounted deferrals (stale sources/cloud placeholders).
      try (var update = connection.prepareStatement("""
          UPDATE jobs SET retry_after = ?
          WHERE state = 'PENDING' AND retry_after < ?
            AND last_retry_policy = 'DEFER_WITHOUT_ATTEMPT'
            AND last_outcome_class IN ('WRITE_FAILED', 'WRITE_UNAVAILABLE_DRAINING')
          """)) {
        update.setLong(1, retryAtMs);
        update.setLong(2, retryAtMs);
        executeMutation(update::executeUpdate);
      }
    } catch (SQLException failure) {
      throw new OutcomeWriteException("Could not extend service-outage deferrals", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  private void deferWithOutcome(Path path, IngestionOutcome outcome,
      JobQueue.IngestionLedgerEntry entry, IndexJob claim, long retryAtMs) {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                if (claim == null && SqliteIngestionWalkOps.currentReceipt(connection, normalizedPath, null) != null) {
                  throw new SQLException("Recorded deferral requires an issued claim");
                }
                String sql = """
                    UPDATE jobs
                    SET state = 'PENDING', last_updated = ?, retry_after = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  stmt.setLong(1, now);
                  stmt.setLong(2, retryAtMs > 0 ? retryAtMs : now + 1000L);
                  bindOutcomeOnly(stmt, 3, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, ledgerEntryForClaim(normalizedPath, entry, claim),
                        SqliteIngestionWalkOps.claimReceipt(connection, claim, null, null));
                    if (claim != null && closedUnsuccessfulMember(normalizedPath)) {
                      skipRecordedMember(normalizedPath, IngestionOutcomeClass.SKIPPED_POLICY, "ENUMERATION_STOPPED");
                    }
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "defer(outcome)", path);
      log.debug("Deferred job without incrementing attempts: {}", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "defer failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Failed-state transition driven by {@code outcome.retryPolicy()}.
   *
   * <p>{@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#NONE} forces the {@code
   * FAILED} terminal state on the first failure, regardless of attempt count. Outcomes carrying
   * {@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#DEFER_WITHOUT_ATTEMPT} are
   * rejected here — they belong to {@link #defer(Path, IngestionOutcome,
   * JobQueue.IngestionLedgerEntry)} and routing them through {@code markFailed} would silently
   * mark the job FAILED while incrementing the attempt counter.
   *
   * <p><b>Tempdoc 885 item 21a/21b.</b> {@link
   * io.justsearch.indexerworker.ingest.IngestionRetryPolicy#RETRY_WITH_BACKOFF} no longer consults
   * {@code maxAttempts} at all. Attempt count answers "how many times has this been tried", which
   * is a display fact; it never answered "is this file permanently unindexable", which is what the
   * terminal decision is about — and conflating them turned a twenty-minute network outage into a
   * permanent {@code FAILED}. The classification already carries the answer: a transient outcome
   * retries on {@link io.justsearch.indexerworker.ingest.IngestionRetryLadder}'s schedule until the
   * seven-day bound, then becomes {@code RETRY_EXHAUSTED} — a visible terminal state a rescan or a
   * file change resets. {@code attempts} is still incremented, and still shown.
   */
  private void markFailedWithOutcome(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    markFailedWithOutcome(path, outcome, entry, null);
  }

  private void markFailedWithOutcome(Path path, IngestionOutcome outcome,
      JobQueue.IngestionLedgerEntry entry, IndexJob claim) {
    if (outcome != null
        && outcome.retryPolicy()
            == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT) {
      throw new IllegalArgumentException(
          "Outcome with retryPolicy=DEFER_WITHOUT_ATTEMPT must be routed through defer(...), not markFailed(...)");
    }
    boolean terminal =
        outcome != null
            && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE;
    boolean transientOutcome =
        outcome != null
            && outcome.retryPolicy()
                == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.RETRY_WITH_BACKOFF;
    // #758: a source that exists but cannot be read now (or whose folder is
    // unavailable) is retried on the same counted, growing ladder, but never exhausts: nothing
    // about it says the file is gone or unindexable, and its indexed content stays searchable.
    boolean untilAvailable =
        outcome != null
            && outcome.retryPolicy()
                == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      FailedTransitionResult result =
          inTransaction(
              () -> {
                if (claim == null && SqliteIngestionWalkOps.currentReceipt(
                    connection, normalizedPath, SqliteIngestionWalkOps.Coverage.FAILED) != null) {
                  throw new SQLException("Recorded failure requires an issued claim");
                }
                if (claim != null && claim.walkEpoch() != null) {
                  SqliteIngestionWalkOps.claimReceipt(connection, claim, null, null);
                  SqliteIngestionWalkOps.requireFailureOutcome(outcome);
                }
                FailureRunState run = readFailureRun(normalizedPath);
                int newAttempts = run.attempts() + 1;
                long now = System.currentTimeMillis();
                // The run's origin: the first failure stamps it, later failures inherit it. This is
                // the only thing the seven-day bound can be measured from once attempts stopped
                // being the terminal signal.
                long firstFailedAt = run.firstFailedAtMs() > 0L ? run.firstFailedAtMs() : now;

                String newState;
                if (terminal) {
                  newState = STATE_FAILED;
                } else if (untilAvailable) {
                  newState = STATE_PENDING;
                } else if (transientOutcome) {
                  newState =
                      IngestionRetryLadder.exhausted(run.firstFailedAtMs(), now)
                          ? STATE_RETRY_EXHAUSTED
                          : STATE_PENDING;
                } else {
                  // outcome == null: the untyped legacy path, which keeps the attempts cap.
                  newState = newAttempts >= maxAttempts ? STATE_FAILED : STATE_PENDING;
                }

                Long retryAfter = null;
                if (STATE_PENDING.equals(newState)) {
                  long backoffMs =
                      transientOutcome || untilAvailable
                          ? IngestionRetryLadder.backoffMs(newAttempts)
                          : 1000L * (1L << Math.min(newAttempts - 1, 10));
                  long jitter =
                      ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
                  // No seven-day clamp for untilAvailable: the clamp would schedule every retry
                  // after the window at the boundary itself, i.e. immediately.
                  retryAfter =
                      untilAvailable
                          ? now + backoffMs + jitter
                          : transientOutcome
                          ? IngestionRetryLadder.nextRetryAtMs(
                              firstFailedAt, newAttempts, now, jitter)
                          : now + backoffMs + jitter;
                  retryAfter = now + retryDelay.applyAsLong(retryAfter - now);
                }

                String updateSql = """
                    UPDATE jobs
                    SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?,
                        first_failed_at = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
                  stmt.setString(1, newState);
                  stmt.setInt(2, newAttempts);
                  stmt.setLong(3, now);
                  stmt.setString(4, outcome != null ? outcome.diagnosticSummary() : null);
                  if (retryAfter != null) {
                    stmt.setLong(5, retryAfter);
                  } else {
                    stmt.setNull(5, java.sql.Types.INTEGER);
                  }
                  stmt.setLong(6, firstFailedAt);
                  bindOutcomeOnly(stmt, 7, outcome, normalizedPath);
                  int rows = executeMutation(stmt::executeUpdate);
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, ledgerEntryForClaim(normalizedPath, entry, claim),
                        SqliteIngestionWalkOps.claimReceipt(connection, claim,
                            STATE_FAILED.equals(newState) || STATE_RETRY_EXHAUSTED.equals(newState)
                                ? SqliteIngestionWalkOps.Coverage.FAILED : null, null));
                  }
                  if (rows > 0 && STATE_PENDING.equals(newState) && claim != null && closedUnsuccessfulMember(normalizedPath)) {
                    skipRecordedMember(normalizedPath, IngestionOutcomeClass.SKIPPED_POLICY, "ENUMERATION_STOPPED");
                    newState = STATE_DONE;
                    retryAfter = null;
                  }
                  return new FailedTransitionResult(rows, newAttempts, newState, retryAfter);
                }
              });
      logIfNoRows(result.updated(), terminal ? "markFailed(terminal)" : "markFailed(retryable)", path);

      if (STATE_DONE.equals(result.state())) {
        log.debug("Stopped recorded retry after enumeration closure: {}", path);
      } else if (STATE_RETRY_EXHAUSTED.equals(result.state())) {
        log.warn(
            "Job retry window exhausted after {} attempts over {} days; marked RETRY_EXHAUSTED: {}",
            result.attempts(),
            IngestionRetryLadder.MAX_RETRY_WINDOW_MS / 86_400_000L,
            path);
      } else if (STATE_FAILED.equals(result.state())) {
        log.warn("Job permanently failed after {} attempts: {}", result.attempts(), path);
      } else {
        long backoffSeconds =
            result.retryAfterMs() != null
                ? Math.max(0L, (result.retryAfterMs() - System.currentTimeMillis()) / 1000)
                : 0;
        log.debug(
            "Job failed with outcome {} (attempt {}), retry after {}s: {}",
            outcomeClassName(outcome),
            result.attempts(),
            backoffSeconds,
            path);
      }
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markFailed failed for " + path + " (completion could not be confirmed)", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Tempdoc 885 item 21b: the two facts a failure transition needs about the current failure run —
   * how many failures it has seen, and when it started. Read in one statement inside the same
   * transaction as the write, so the seven-day decision can never be made against a row another
   * thread has since reset.
   */
  private FailureRunState readFailureRun(String normalizedPath) throws SQLException {
    String sql = "SELECT attempts, first_failed_at FROM jobs WHERE path = ?";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, normalizedPath);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return new FailureRunState(0, 0L);
        }
        int attempts = rs.getInt(1);
        long firstFailedAt = rs.getLong(2);
        return new FailureRunState(attempts, rs.wasNull() ? 0L : firstFailedAt);
      }
    }
  }

  private record FailureRunState(int attempts, long firstFailedAtMs) {}

  private record FailedTransitionResult(int updated, int attempts, String state, Long retryAfterMs) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  /** Preserve each former autocommit statement's boundary while confirming commit in Java. */
  private int executeMutation(SqlWork<Integer> work) throws SQLException {
    return connection.getAutoCommit() ? inTransaction(work) : work.run();
  }

  private void unlockAfterChanges() {
    Runnable delivery = null;
    try {
      if (lock.getHoldCount() == 1 && changeStream != null) {
        try { deliverOutsideForgetEntry(connectionFailure == null
            ? changeStream::drainCommitted : () -> changeStream.drainCommitted(false)); }
        catch (RuntimeException | Error deliveryFailure) {
          if (connectionFailure == null) throw deliveryFailure;
          if (connectionFailure != deliveryFailure) connectionFailure.addSuppressed(deliveryFailure);
        } finally {
          if (connectionFailure != null) {
            // Xerial listener removal needs the native connection alive. Freeze/deliver confirmed
            // deltas first, detach capture next, and only then close the failed connection.
            try { changeStream.close(); }
            catch (RuntimeException | Error feedFailure) {
              if (connectionFailure != feedFailure) connectionFailure.addSuppressed(feedFailure);
            } finally {
              changeStream = null;
              closeFailedConnection();
            }
          }
        }
      }
    } finally {
      try {
        if (lock.getHoldCount() == 1 && walkChanges != null) delivery = walkChanges.takeDelivery();
      } finally { lock.unlock(); }
      if (delivery != null) deliverOutsideForgetEntry(delivery);
    }
  }

  private static void deliverOutsideForgetEntry(Runnable delivery) {
    int previous = FORGET_CALLBACK_DEPTH.get();
    FORGET_CALLBACK_DEPTH.set(previous + 1);
    try { delivery.run(); }
    finally {
      if (previous == 0) FORGET_CALLBACK_DEPTH.remove();
      else FORGET_CALLBACK_DEPTH.set(previous);
    }
  }

  @Override public boolean forgetEntryAllowed() {
    return !lock.isHeldByCurrentThread() && FORGET_CALLBACK_DEPTH.get() == 0;
  }

  @Override public java.util.Optional<JobQueue.ForgetQueueFence> tryForgetQueueFence() {
    if (!forgetEntryAllowed() || !lock.tryLock()) return java.util.Optional.empty();
    boolean retained = false;
    try {
      ensureOpen();
      Thread holder = Thread.currentThread();
      JobQueue.ForgetQueueFence fence = new JobQueue.ForgetQueueFence() {
        private boolean closed;
        @Override public void close() {
          if (Thread.currentThread() != holder) {
            throw new IllegalStateException("Forget queue fence must leave on its owning thread");
          }
          if (closed) return;
          closed = true;
          unlockAfterChanges();
        }
      };
      retained = true;
      return java.util.Optional.of(fence);
    } finally { if (!retained) lock.unlock(); }
  }

  @Override public boolean forgetJobsAbsent(String path, boolean folder) {
    lock.lock();
    try {
      ensureOpen();
      String key = PathNormalizer.normalizePath(path);
      if (key == null || key.isBlank()) throw new IllegalArgumentException("Forget requires an exact nonempty path");
      String predicate = folder ? "(path = ? OR (path >= ? AND path < ?))" : "path = ?";
      try (var query = connection.prepareStatement("SELECT 1 FROM jobs WHERE " + predicate + " LIMIT 1")) {
        query.setString(1, key);
        if (folder) {
          String prefix = PathNormalizer.normalizePathPrefix(key);
          query.setString(2, prefix);
          query.setString(3, upperBoundExclusive(prefix));
        }
        try (var row = query.executeQuery()) { return !row.next(); }
      }
    } catch (SQLException unavailable) {
      throw SqliteServiceAvailability.failure("Forget queue absence could not be verified", unavailable);
    } finally { unlockAfterChanges(); }
  }

  private <T> T inTransaction(SqlWork<T> work) throws SQLException {
    boolean wasAutoCommit = connection.getAutoCommit();
    boolean ended = false;
    boolean committed = false;
    Throwable failure = null;
    T result = null;
    try {
      connection.setAutoCommit(false);
      // Acquire the write reservation before any preservation reads. A deferred read snapshot
      // cannot reliably upgrade behind another writer, even with busy_timeout configured.
      // Keep JDBC's default DEFERRED mode: Xerial commit/rollback begins its next transaction,
      // and globally IMMEDIATE could fail reacquiring a lock after an already successful commit.
      // The zero-row write reserves this database without changing rows or emitting update hooks.
      try (Statement reservation = connection.createStatement()) {
        reservation.executeUpdate("UPDATE jobs SET state = state WHERE 0");
      }
      result = work.run();
      connection.commit();
      committed = true;
      ended = true;
    } catch (SQLException | RuntimeException | Error primary) {
      failure = primary;
      try { connection.rollback(); ended = true; }
      catch (SQLException | RuntimeException | Error rollbackFailure) {
        if (primary != rollbackFailure) primary.addSuppressed(rollbackFailure);
      }
    }
    if (ended) {
      try { connection.setAutoCommit(wasAutoCommit); }
      catch (SQLException | RuntimeException | Error restoreFailure) {
        if (failure == null) failure = restoreFailure;
        else if (failure != restoreFailure) failure.addSuppressed(restoreFailure);
        connectionFailure = failure;
      }
    } else {
      // Restoring auto-commit here could commit work whose rollback was never confirmed.
      connectionFailure = failure;
    }
    var outcomeMetrics = new ArrayList<>(pendingOutcomeMetrics);
    pendingOutcomeMetrics.clear();
    if (committed) {
      for (String outcomeClass : outcomeMetrics) recordOutcomeMetric(outcomeClass);
      try { switchBufferOps.acknowledgeAdmissions(); }
      catch (SQLException witnessFailure) {
        if (failure == null) failure = witnessFailure;
        else if (failure != witnessFailure) failure.addSuppressed(witnessFailure);
      }
    }
    // Materialize confirmed rows while the connection is still available. A reset failure
    // cannot turn a successful commit into rollback or silently omit its projection.
    if (committed && changeStream != null) {
      try { changeStream.commitSucceeded(); }
      catch (RuntimeException | Error projectionFailure) {
        if (failure == null) failure = projectionFailure;
        else if (failure != projectionFailure) failure.addSuppressed(projectionFailure);
      }
    }
    if (walkChanges != null) {
      if (committed) {
        try { walkChanges.commitSucceeded(); }
        catch (SQLException | RuntimeException | Error projectionFailure) {
          if (failure == null) failure = projectionFailure;
          else if (failure != projectionFailure) failure.addSuppressed(projectionFailure);
        }
      } else walkChanges.discardPending();
    }
    if (connectionFailure != null) {
      recordDbError();
      if (changeStream == null) closeFailedConnection();
    }
    if (failure instanceof SQLException sql) throw sql;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
    return result;
  }

  private void closeFailedConnection() {
    try { closeWalkChanges(); }
    catch (RuntimeException | Error closeFailure) {
      if (connectionFailure != closeFailure) connectionFailure.addSuppressed(closeFailure);
    }
    try { connection.close(); }
    catch (SQLException | RuntimeException | Error closeFailure) {
      if (connectionFailure != closeFailure) connectionFailure.addSuppressed(closeFailure);
    }
  }

  @Override
  public void recordIngestionEvent(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      insertLedgerEvent(normalizePath(path), outcome, entry);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordIngestionEvent failed for " + path, e);
    } finally {
      unlockAfterChanges();
    }
  }

  private void insertLedgerEvent(
      String normalizedPath, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry)
      throws SQLException {
    insertLedgerEvent(normalizedPath, outcome, entry, null);
  }

  private void insertLedgerEvent(String normalizedPath, IngestionOutcome outcome,
      JobQueue.IngestionLedgerEntry entry, SqliteIngestionWalkOps.UnitReceipt receipt)
      throws SQLException {
    if (outcome == null) {
      if (receipt != null) throw new SQLException("Recorded terminal outcome is required");
      return;
    }
    String pathHash = sha256(normalizedPath);
    if (receipt != null && entry != null && entry.pathHash() != null && !pathHash.equals(entry.pathHash())) {
      throw new SQLException("Recorded outcome path identity does not match its claim");
    }
    SqliteIngestionWalkOps.validateReceipt(receipt, outcome);
    if (!SqliteIngestionWalkOps.advanceReceipt(connection, receipt, pathHash)) return;
    String sql = """
        INSERT INTO ingestion_ledger (
          path_hash, collection, outcome_class, reason_code, retry_policy,
          diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
          source_kind, artifact_status, policy_id, parser_id, originator, transport,
          operation_key, unit_revision, content_hash, terminal_coverage, planned_source_sha256
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          CASE WHEN ? THEN ? ELSE (SELECT originator FROM jobs WHERE path = ?) END,
          CASE WHEN ? THEN ? ELSE (SELECT transport FROM jobs WHERE path = ?) END, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      JobQueue.IngestionLedgerEntry normalizedEntry = normalizeLedgerEntry(normalizedPath, entry);
      stmt.setString(1, normalizedEntry.pathHash());
      stmt.setString(2, normalizedEntry.collection());
      stmt.setString(3, outcome.outcomeClass().name());
      stmt.setString(4, outcome.reasonCode());
      stmt.setString(5, outcome.retryPolicy().name());
      stmt.setString(6, outcome.diagnosticSummary());
      stmt.setLong(7, outcome.observedAtMs());
      setNullableLong(stmt, 8, normalizedEntry.sourceSizeBytes());
      setNullableLong(stmt, 9, normalizedEntry.sourceModifiedAtMs());
      stmt.setString(10, normalizedEntry.sourceKind());
      stmt.setString(11, normalizedEntry.artifactStatus());
      stmt.setString(12, normalizedEntry.policyId());
      stmt.setString(13, normalizedEntry.parserId());
      // A supplied entry is a claim snapshot, including explicitly unknown legacy attribution.
      // Only a contextless queue operation may consult the current row; a newer admission must
      // never rewrite the origin of an older claim that is finishing now.
      stmt.setBoolean(14, entry != null);
      stmt.setString(15, normalizedEntry.originator());
      stmt.setString(16, normalizedPath);
      stmt.setBoolean(17, entry != null);
      stmt.setString(18, normalizedEntry.transport());
      stmt.setString(19, normalizedPath);
      stmt.setString(20, receipt == null ? null : receipt.operationKey());
      stmt.setString(21, receipt == null ? null : receipt.unitRevision());
      stmt.setString(22, receipt == null ? null : receipt.contentHash());
      stmt.setString(23, receipt == null || receipt.coverage() == null ? null : receipt.coverage().name());
      stmt.setString(24, receipt == null ? null : receipt.plannedSourceSha256());
      executeMutation(stmt::executeUpdate);
    }
  }

  private static JobQueue.IngestionLedgerEntry ledgerEntryForClaim(String path,
      JobQueue.IngestionLedgerEntry entry, IndexJob claim) {
    if (claim == null) return entry;
    var normalized = normalizeLedgerEntry(path, entry);
    var provenance = claim.provenance();
    return new JobQueue.IngestionLedgerEntry(normalized.pathHash(), claim.collection(),
        normalized.sourceSizeBytes(), normalized.sourceModifiedAtMs(), normalized.sourceKind(),
        normalized.artifactStatus(), normalized.policyId(), normalized.parserId(),
        provenance == null ? null : provenance.originator(), provenance == null ? null : provenance.transport());
  }

  private static JobQueue.IngestionLedgerEntry normalizeLedgerEntry(
      String normalizedPath, JobQueue.IngestionLedgerEntry entry) {
    if (entry == null) {
      return new JobQueue.IngestionLedgerEntry(
          sha256(normalizedPath), null, null, null, "UNKNOWN", "NOT_CREATED", "UNKNOWN", "UNKNOWN");
    }
    return new JobQueue.IngestionLedgerEntry(
        entry.pathHash() != null ? entry.pathHash() : sha256(normalizedPath),
        entry.collection(),
        entry.sourceSizeBytes(),
        entry.sourceModifiedAtMs(),
        entry.sourceKind() != null ? entry.sourceKind() : "UNKNOWN",
        entry.artifactStatus() != null ? entry.artifactStatus() : "NOT_CREATED",
        entry.policyId() != null ? entry.policyId() : "UNKNOWN",
        entry.parserId() != null ? entry.parserId() : "UNKNOWN",
        entry.originator(), entry.transport());
  }

  private static void setNullableLong(PreparedStatement stmt, int index, Long value)
      throws SQLException {
    if (value != null) {
      stmt.setLong(index, value);
    } else {
      stmt.setNull(index, java.sql.Types.INTEGER);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String normalizePath(Path path) {
    return PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static Set<String> normalizeSurvivorPaths(List<String> survivorPaths) {
    if (survivorPaths == null || survivorPaths.isEmpty()) return Set.of();
    Set<String> normalized = new HashSet<>();
    for (String survivor : survivorPaths) {
      if (survivor == null || survivor.isBlank()) {
        throw new IllegalArgumentException("Accepted survivor path must be non-blank");
      }
      normalized.add(normalizePath(Path.of(survivor)));
    }
    return Set.copyOf(normalized);
  }

  private static String outcomeClassName(IngestionOutcome outcome) {
    return outcome != null ? outcome.outcomeClass().name() : "null";
  }

  private void logIfNoRows(int updated, String operation, Path path) {
    if (updated == 0) {
      log.warn("{} affected no queue rows for {}", operation, path);
    }
  }

  private void logBatchMisses(int[] updates, String operation) {
    int misses = 0;
    for (int updated : updates) {
      if (updated == 0) {
        misses++;
      }
    }
    if (misses > 0) {
      log.warn("{} affected no queue rows for {} path(s)", operation, misses);
    }
  }

  private static void bindOutcomeUpdate(
      PreparedStatement stmt, int startIndex, long now, IngestionOutcome outcome, String path)
      throws SQLException {
    stmt.setLong(startIndex, now);
    bindOutcomeOnly(stmt, startIndex + 1, outcome, path);
  }

  private static void bindOutcomeOnly(
      PreparedStatement stmt, int startIndex, IngestionOutcome outcome, String path)
      throws SQLException {
    if (outcome != null) {
      stmt.setString(startIndex, outcome.outcomeClass().name());
      stmt.setString(startIndex + 1, outcome.reasonCode());
      stmt.setString(startIndex + 2, outcome.retryPolicy().name());
      stmt.setString(startIndex + 3, outcome.diagnosticSummary());
      stmt.setLong(startIndex + 4, outcome.observedAtMs());
    } else {
      stmt.setNull(startIndex, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 1, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 2, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 3, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 4, java.sql.Types.INTEGER);
    }
    stmt.setString(startIndex + 5, path);
  }

  @Override
  public int recoverStuckJobs() {
    return recoverUnownedProcessing(null);
  }

  /** Runtime recovery cannot reclaim an issued claim whose synchronous work has not exited. */
  @Override
  public int recoverStuckJobs(long olderThanMs) {
    return recoverUnownedProcessing(System.currentTimeMillis() - olderThanMs);
  }

  /** The same under-lock boundary governs fresh claims and interrupted claim recovery. */
  private RecordedClaimDecision recordedClaimDecision(String operationKey, Long walkEpoch, String plannedHash) {
    if (walkEpoch == null) return RecordedClaimDecision.ALLOW;
    if (operationKey == null || operationKey.isBlank()) return RecordedClaimDecision.DENY;
    try {
      var progress = SqliteIngestionWalkOps.find(connection, operationKey)
          .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded claim projection is unavailable"));
      if (progress.sealedAt() != null) return RecordedClaimDecision.DENY;
      if (progress.capturedPlan() && (progress.enumerationOutcome() != WalkEnumerationOutcome.COMPLETE
          || plannedHash == null)) return RecordedClaimDecision.DENY;
      // Streaming recorded members can carry a frozen source witness during a candidate build.
      // Their claim remains subject to the recorded owner policy and exact hash validation.
      if (plannedHash != null && !IngestionLedgerTransition.isSha256(plannedHash)) {
        return RecordedClaimDecision.DENY;
      }
      return Objects.requireNonNull(recordedClaimPolicy.apply(operationKey), "recorded claim decision");
    } catch (SQLException | RuntimeException unavailableAuthority) {
      log.warn("Recorded claim authority unavailable; keeping the unit fenced", unavailableAuthority);
      return RecordedClaimDecision.DENY;
    }
  }

  private int recoverUnownedProcessing(Long cutoff) {
    lockTimed();
    try {
      ensureOpen();
      int count = inTransaction(() -> {
        record UnownedRow(String path, String scanId, Long walkEpoch, String plannedHash) {}
        List<UnownedRow> unowned = new ArrayList<>();
        try (var query = connection.prepareStatement(
            "SELECT path, scan_id, walk_seen_epoch, planned_source_sha256 FROM jobs WHERE state = 'PROCESSING' AND (? IS NULL OR last_updated < ?)")) {
          if (cutoff == null) {
            query.setNull(1, java.sql.Types.BIGINT);
            query.setNull(2, java.sql.Types.BIGINT);
          } else {
            query.setLong(1, cutoff);
            query.setLong(2, cutoff);
          }
          try (var rows = query.executeQuery()) {
            while (rows.next()) {
              String path = rows.getString(1);
              IndexJob claim = activeClaims.get(path);
              if (claim != null && ownsClaim(claim)) continue;
              long epoch = rows.getLong(3);
              Long walkEpoch = rows.wasNull() ? null : epoch;
              unowned.add(new UnownedRow(path, rows.getString(2), walkEpoch, rows.getString(4)));
            }
          }
        }
        int recovered = 0;
        try (var update = connection.prepareStatement(
            "UPDATE jobs SET state = 'PENDING', last_updated = ? WHERE path = ? AND state = 'PROCESSING'")) {
          long now = System.currentTimeMillis();
          for (UnownedRow row : unowned) {
            String path = row.path();
            // Closing an orphaned stopped member records a skip, never authority to execute.
            // Requiring a live permit here would strand cancelled work after process restart.
            if (closedUnsuccessfulMember(path)) {
              recovered += skipRecordedMember(path, IngestionOutcomeClass.SKIPPED_POLICY, "ENUMERATION_STOPPED");
              continue;
            }
            if (recordedClaimDecision(row.scanId(), row.walkEpoch(), row.plannedHash()) == RecordedClaimDecision.DENY) continue;
            update.setLong(1, now);
            update.setString(2, path);
            recovered += executeMutation(update::executeUpdate);
          }
        }
        return recovered;
      });
      if (count > 0) log.info("Recovered {} unowned processing jobs", count);
      return count;
    } catch (SQLException failure) {
      log.error("Failed to recover unowned processing jobs", failure);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Refresh only exact issued processing units, so restart orphans retain their age and the
   * age-bounded {@link #recoverStuckJobs(long)} reaper reclaims only rows whose heartbeat went stale.
   */
  @Override
  public void heartbeatProcessing() {
    lock.lock();
    try {
      ensureOpen();
      inTransaction(() -> {
        try (PreparedStatement stmt = connection.prepareStatement("""
            UPDATE jobs SET last_updated = ? WHERE state = 'PROCESSING' AND path = ?
              AND scan_id IS ? AND unit_revision IS ? AND walk_seen_epoch IS ?
            """)) {
          long now = System.currentTimeMillis();
          for (IndexJob claim : activeClaims.values()) {
            stmt.setLong(1, now);
            stmt.setString(2, normalizePath(claim.path()));
            stmt.setString(3, claim.scanId());
            stmt.setString(4, claim.unitRevision());
            if (claim.walkEpoch() == null) stmt.setNull(5, java.sql.Types.BIGINT);
            else stmt.setLong(5, claim.walkEpoch());
            stmt.executeUpdate();
          }
        }
        return null;
      });
    } catch (SQLException e) {
      recordDbError();
      // Issued claims remain protected from runtime recovery until synchronous work returns.
      log.debug("Heartbeat of PROCESSING jobs failed (best-effort): {}", e.getMessage());
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public long queueDepth() {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          SELECT COALESCE(SUM(n),0) FROM job_counts
          WHERE state IN ('PENDING', 'PROCESSING')
          """;

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Failed to get queue depth", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public long completedCount() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "SELECT COALESCE(SUM(n),0) FROM job_counts WHERE state = 'DONE'";

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Failed to get completed count", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Removes old DONE/FAILED queue rows. The {@code ingestion_ledger} table is NOT touched here
   * — the audit trail outlives queue rows, and ledger pruning is handled by the dedicated
   * {@link #cleanupOldLedgerEvents(int)}.
   */
  @Override
  public int cleanupOldJobs(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();

      var retained = retainedRootScopes(retirementReplayOps());
      var retainedDeletes = releaseRecovery != null && releaseRecovery.retiringBuild()
          ? retirementDeletes(retirementReplayOps()) : List.<RetirementDelete>of();
      org.sqlite.Function.create(connection, "release_root_retained", new org.sqlite.Function() {
        @Override protected void xFunc() throws SQLException {
          result(retained.test(value_text(0),value_text(1))
              || latestDelete(retainedDeletes,value_text(0),value_text(2)) > 0 ? 1 : 0);
        }
      });
      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

      // Original exact receipts still settle native replay or authorize FAILED recovery.
      // Release them only after the recovery checkpoint is conclusively gone.
      String jobsWhere = """
          state IN ('DONE', 'FAILED', 'RETRY_EXHAUSTED') AND last_updated < ?
            AND (walk_seen_epoch IS NULL OR EXISTS (SELECT 1 FROM ingestion_walk_progress p
              WHERE p.operation_key = jobs.scan_id AND p.sealed_at IS NOT NULL AND p.acknowledged_revision = p.revision))
          """ + " AND release_root_retained(path,unit_revision,collection)=0" + (Files.notExists(Release030RecoveryState.path(dbPath), LinkOption.NOFOLLOW_LINKS)
              ? "" : " AND (unit_revision NOT LIKE 'release030/%' OR ingestion_root IS NOT path)");

      int deleted;
      try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM jobs WHERE " + jobsWhere)) {
        stmt.setLong(1, cutoff);
        deleted = inTransaction(() -> {
          validateRetentionCandidates("SELECT DISTINCT scan_id FROM jobs WHERE "
              + "walk_seen_epoch IS NOT NULL AND " + jobsWhere, cutoff);
          int count = stmt.executeUpdate();
          pruneAcknowledgedWalks(cutoff);
          return count;
        });
      }
      if (deleted > 0) {
        log.info("Cleaned up {} old completed/failed jobs", deleted);
        checkAndVacuum();
      }
      return deleted;
    } catch (SQLException | IOException e) {
      log.error("Failed to cleanup old jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int cleanupOldLedgerEvents(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();
      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
      String ledgerWhere = "observed_at < ? AND "
          + "(operation_key IS NULL OR EXISTS (SELECT 1 FROM ingestion_walk_progress p "
          + "WHERE p.operation_key = ingestion_ledger.operation_key AND p.sealed_at IS NOT NULL "
          + "AND p.acknowledged_revision = p.revision))"
          // Legacy stat receipts have no ledger unit revision. Keep current exact outcome
          // times (and any coincident audit rows) until their recovery is settled.
          + (Files.notExists(Release030RecoveryState.path(dbPath), LinkOption.NOFOLLOW_LINKS)
              ? "" : " AND observed_at NOT IN (SELECT last_outcome_at FROM jobs"
                  + " WHERE unit_revision LIKE 'release030/%' AND ingestion_root=path"
                  + " AND state='DONE' AND last_outcome_at IS NOT NULL)");
      try (PreparedStatement stmt =
          connection.prepareStatement("DELETE FROM ingestion_ledger WHERE " + ledgerWhere)) {
        stmt.setLong(1, cutoff);
        int deleted = inTransaction(() -> {
          validateRetentionCandidates("SELECT DISTINCT operation_key FROM ingestion_ledger WHERE "
              + "operation_key IS NOT NULL AND " + ledgerWhere, cutoff);
          try (var selection = connection.prepareStatement("DELETE FROM ingestion_walk_sealed_units WHERE ledger_id IN "
              + "(SELECT id FROM ingestion_ledger WHERE " + ledgerWhere + ")")) {
            selection.setLong(1, cutoff);
            selection.executeUpdate();
          }
          int count = stmt.executeUpdate();
          pruneAcknowledgedWalks(cutoff);
          return count;
        });
        if (deleted > 0) {
          log.info("Cleaned up {} old ingestion ledger events", deleted);
          checkAndVacuum();
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to cleanup old ledger events", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  private void pruneAcknowledgedWalks(long cutoff) throws SQLException {
    String pruneWhere = "sealed_at < ? AND acknowledged_revision = revision "
        + "AND NOT EXISTS (SELECT 1 FROM jobs WHERE jobs.scan_id = ingestion_walk_progress.operation_key) "
        + "AND NOT EXISTS (SELECT 1 FROM ingestion_ledger WHERE ingestion_ledger.operation_key = ingestion_walk_progress.operation_key)";
    validateRetentionCandidates("SELECT operation_key FROM ingestion_walk_progress WHERE " + pruneWhere, cutoff);
    try (var prune = connection.prepareStatement("DELETE FROM ingestion_walk_progress WHERE " + pruneWhere)) {
      prune.setLong(1, cutoff);
      prune.executeUpdate();
    }
  }

  /** Stream exactly the deletion's recorded candidates inside its transaction; no second state store. */
  private void validateRetentionCandidates(String candidateSql, long cutoff) throws SQLException {
    if (connection.getAutoCommit()) throw new SQLException("Receipt retention requires its transaction");
    try (var candidates = connection.prepareStatement(candidateSql)) {
      candidates.setLong(1, cutoff);
      try (var rows = candidates.executeQuery()) {
        while (rows.next()) {
          var receipt = SqliteIngestionWalkOps.find(connection, rows.getString(1))
              .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Retention receipt is unavailable"));
          if (receipt.sealedAt() == null || receipt.acknowledgedRevision() != receipt.revision()) {
            throw new JobQueue.RecordedWalkGapException("Retention receipt is not exactly acknowledged");
          }
        }
      }
    }
  }

  @Override
  public boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
    if (pathHash == null || reasonCode == null) return false;
    lock.lock();
    try {
      ensureOpen();
      String sql =
          """
          SELECT 1 FROM ingestion_ledger
          WHERE path_hash = ? AND reason_code = ? AND observed_at >= ?
          LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, reasonCode);
        stmt.setLong(3, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          return rs.next();
        }
      }
    } catch (SQLException e) {
      log.debug("hasRecentLedgerEvent probe failed (treating as 'no recent event'): {}", e.getMessage());
      return false;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<JobQueue.IngestionEventView> recentIngestionEvents(int limit) {
    lock.lock();
    try {
      ensureOpen();
      int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
      String sql = """
          SELECT id, path_hash, collection, outcome_class, reason_code, retry_policy,
                 diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
                 source_kind, artifact_status, policy_id, parser_id
          FROM ingestion_ledger
          ORDER BY observed_at DESC, id DESC
          LIMIT ?
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, cappedLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionEventView> events = new ArrayList<>();
          while (rs.next()) {
            events.add(readIngestionEventView(rs));
          }
          return List.copyOf(events);
        }
      }
    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Failed to list ingestion ledger events", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<JobQueue.IngestionOutcomeSummary> ingestionOutcomeSummary(long sinceMs) {
    lock.lock();
    try {
      ensureOpen();
      String sql = """
          SELECT outcome_class, reason_code, retry_policy, COUNT(*) AS event_count,
                 MAX(observed_at) AS last_observed_at
          FROM ingestion_ledger
          WHERE observed_at >= ?
          GROUP BY outcome_class, reason_code, retry_policy
          ORDER BY last_observed_at DESC
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionOutcomeSummary> summaries = new ArrayList<>();
          while (rs.next()) {
            summaries.add(
                new JobQueue.IngestionOutcomeSummary(
                    rs.getString("outcome_class"),
                    rs.getString("reason_code"),
                    rs.getString("retry_policy"),
                    rs.getLong("event_count"),
                    rs.getLong("last_observed_at")));
          }
          return List.copyOf(summaries);
        }
      }
    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Failed to summarize ingestion ledger outcomes", e);
    } finally {
      unlockAfterChanges();
    }
  }

  private static JobQueue.IngestionEventView readIngestionEventView(ResultSet rs)
      throws SQLException {
    return new JobQueue.IngestionEventView(
        rs.getLong("id"),
        rs.getString("path_hash"),
        rs.getString("collection"),
        rs.getString("outcome_class"),
        rs.getString("reason_code"),
        rs.getString("retry_policy"),
        rs.getString("diagnostic_summary"),
        rs.getLong("observed_at"),
        nullableLong(rs, "source_size_bytes"),
        nullableLong(rs, "source_modified_at"),
        rs.getString("source_kind"),
        rs.getString("artifact_status"),
        rs.getString("policy_id"),
        rs.getString("parser_id"));
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static final int VACUUM_WASTE_THRESHOLD_PERCENT = 25;
  private static final int VACUUM_PAGES = 500;

  /**
   * Checks the database waste ratio and runs incremental vacuum if above threshold.
   *
   * <p>Requires {@code PRAGMA auto_vacuum = 2} (INCREMENTAL) to be effective. On databases
   * created with {@code auto_vacuum = 0}, this is a safe no-op.
   */
  void checkAndVacuum() {
    try (Statement stmt = connection.createStatement()) {
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT page_count, freelist_count, page_size FROM pragma_page_count(), "
                  + "pragma_freelist_count(), pragma_page_size()")) {
        if (rs.next()) {
          long pageCount = rs.getLong("page_count");
          long freelistCount = rs.getLong("freelist_count");
          long pageSize = rs.getLong("page_size");
          if (pageCount > 0) {
            long wastePercent = (freelistCount * 100) / pageCount;
            if (wastePercent > VACUUM_WASTE_THRESHOLD_PERCENT) {
              stmt.execute("PRAGMA incremental_vacuum(" + VACUUM_PAGES + ")");
              long reclaimedBytes = Math.min(freelistCount, VACUUM_PAGES) * pageSize;
              log.info(
                  "Incremental vacuum: reclaimed ~{} KB (waste was {}%)",
                  reclaimedBytes / 1024,
                  wastePercent);
            }
          }
        }
      }
    } catch (SQLException e) {
      log.warn("Incremental vacuum check failed", e);
    }
  }

  @Override
  public long unownedFailureCount() {
    lock.lock();
    try {
      ensureOpen();
      // Only the original v13 admissions are exempt; a stale sidecar or a later admission
      // in the same database cannot lend its cohort to a genuinely new terminal failure.
      try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM jobs WHERE ingestion_root IS NULL "
              + "AND state IN ('FAILED','RETRY_EXHAUSTED') AND unit_revision LIKE 'release030/%'")) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException("Cannot count legacy unowned failures", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public FailureSummary failureSummary() {
    lock.lock();
    try {
      ensureOpen();

      long failedCount = 0L;
      String lastFailedPath = null;
      String lastFailedError = null;
      Long lastFailedAtMs = null;
      Long nextRetryAtMs = null;

      // 1) Failed count
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(n),0) FROM job_counts WHERE state IN ('FAILED', 'RETRY_EXHAUSTED')")) {
        if (rs.next()) {
          failedCount = rs.getLong(1);
        }
      }

      // 2) Last failed job (most recent last_updated)
      String lastFailedSql = """
          SELECT path,error_message,last_updated FROM (
            SELECT * FROM (SELECT path,error_message,last_updated FROM jobs
              WHERE state='FAILED' ORDER BY last_updated DESC LIMIT 1)
            UNION ALL
            SELECT * FROM (SELECT path,error_message,last_updated FROM jobs
              WHERE state='RETRY_EXHAUSTED' ORDER BY last_updated DESC LIMIT 1)
          ) ORDER BY last_updated DESC LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(lastFailedSql);
           ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          lastFailedPath = rs.getString(1);
          lastFailedError = rs.getString(2);
          long ts = rs.getLong(3);
          // last_updated is NOT NULL in schema; treat 0 as unknown defensively
          lastFailedAtMs = ts > 0 ? ts : null;
        }
      }

      // 3) Earliest retry_after for PENDING jobs (best-effort)
      // Note: PROCESSING jobs have retry_after null; we focus on PENDING backoff.
      String nextRetrySql = """
          SELECT MIN(retry_after)
          FROM jobs
          WHERE state = 'PENDING' AND retry_after IS NOT NULL
          """;
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(nextRetrySql)) {
        if (rs.next()) {
          long v = rs.getLong(1);
          if (!rs.wasNull() && v > 0) {
            nextRetryAtMs = v;
          }
        }
      }

      return new FailureSummary(failedCount, lastFailedPath, lastFailedError, lastFailedAtMs, nextRetryAtMs);

    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Cannot compute failure summary", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public List<FailedJobInfo> listFailedJobs(int limit) {
    lock.lock();
    try {
      ensureOpen();

      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = "SELECT path, error_message, attempts, last_updated, collection, "
          + FAILED_SURFACE_STATE + ", scan_id FROM jobs WHERE " + ON_FAILED_SURFACE
          + " ORDER BY last_updated DESC LIMIT ?";

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)));
          }
        }
      }
      return result;

    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Failed to list failed jobs", e);
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Exclusive upper bound for a half-open prefix range: the normalized prefix with its final
   * character incremented, so {@code path >= lower AND path < upper} selects exactly the paths
   * under {@code lower} without SQL {@code LIKE} wildcard hazards. Callers must pass a non-empty
   * normalized prefix ({@link PathNormalizer#normalizePathPrefix} guarantees a trailing separator,
   * so the last char is well-defined and never a high sentinel).
   */
  private static String upperBoundExclusive(String lower) {
    return lower.substring(0, lower.length() - 1) + (char) (lower.charAt(lower.length() - 1) + 1);
  }

  /**
   * Lists FAILED jobs whose path is under the given prefix (tempdoc 599 §16/B1). Combines the
   * {@link #listFailedJobs(int)} projection with the half-open range predicate from
   * {@link #countByPathPrefix(String)} — same normalization + PK-index-friendly, wildcard-safe
   * bounds. Backs the per-folder "failed files" drill-down. Returns empty on error or empty prefix.
   */
  @Override
  public List<FailedJobInfo> listFailedJobsByPathPrefix(String pathPrefix, int limit) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("listFailedJobsByPathPrefix called with empty prefix, refusing");
        return List.of();
      }
      String upper = upperBoundExclusive(lower);
      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = "SELECT path, error_message, attempts, last_updated, collection, "
          + FAILED_SURFACE_STATE + ", scan_id FROM jobs WHERE (" + ON_FAILED_SURFACE
          + ") AND path >= ? AND path < ? ORDER BY last_updated DESC LIMIT ?";

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        stmt.setInt(3, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)));
          }
        }
      }
      return result;
    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to list failed jobs by path prefix: {}", pathPrefix, e);
      return List.of();
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int clearFailedJobs() {
    lock.lock();
    try {
      ensureOpen();

      int affected = removeJobsAdministratively("state IN ('FAILED', 'RETRY_EXHAUSTED')", List.of(), false);
      if (affected > 0) {
        log.info("Cleared {} failed jobs", affected);
        checkAndVacuum();
      }
      return affected;

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear failed jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public int clearAll() {
    lock.lock();
    try {
      ensureOpen();

      int affected = removeJobsAdministratively("1", List.of(), false);
      if (affected > 0) {
        log.info("Cleared {} jobs (profiling reset)", affected);
        checkAndVacuum();
      }
      return affected;

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear all jobs", e);
      return 0;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Deletes all jobs whose path starts with the given prefix.
   * Deletes ALL states (PENDING, PROCESSING, DONE, FAILED).
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return number of jobs deleted, or -1 on error
   */
  @Override
  public int deleteByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String normalized = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (normalized == null || normalized.isBlank()) {
        log.warn("deleteByPathPrefix called with empty prefix, refusing");
        return 0;
      }

      // Half-open range on the PK-indexed path (path >= lower AND path < upper) instead of
      // LIKE 'prefix%', so `_`/`%` inside a normalized path are treated literally rather than
      // as SQL wildcards (which would over-delete unrelated jobs) — mirrors the wildcard-safe
      // listFailedJobsByPathPrefix/countByPathPrefix range queries.
      String upper = upperBoundExclusive(normalized);
      return removeJobsAdministratively("path >= ? AND path < ?", List.of(normalized, upper), true);

    } catch (SQLException e) {
      log.error("Failed to delete jobs by path prefix: {}", pathPrefix, e);
      return -1;
    } finally {
      unlockAfterChanges();
    }
  }

  /** Deletes a literal prefix while preserving exact accepted survivor paths. */
  @Override
  public int deleteByPathPrefixExcludingAcceptedSurvivors(
      String pathPrefix, List<String> survivorPaths) {
    return deleteByPathPrefixExcludingAcceptedSurvivors(pathPrefix, survivorPaths, List.of());
  }

  @Override
  public int deleteByPathPrefixExcludingAcceptedSurvivors(String pathPrefix, List<String> survivorPaths, List<String> excludedSubtrees) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must be non-blank");
    }
    String normalized = PathNormalizer.normalizePathPrefix(pathPrefix);
    if (normalized == null || normalized.isBlank()) {
      throw new IllegalArgumentException("Path-prefix scope must normalize to non-blank");
    }
    Set<String> excluded = normalizeSurvivorPaths(survivorPaths);
    String upper = upperBoundExclusive(normalized);
    lock.lock();
    try {
      ensureOpen();
      var parameters = new ArrayList<String>(List.of(normalized, upper));
      String predicate = "path >= ? AND path < ?" + prefixExclusions(excludedSubtrees, parameters);
      return removeJobsAdministratively(predicate, parameters, true, excluded);
    } catch (SQLException failure) {
      recordDbError();
      throw new IllegalStateException(
          "Strict path-prefix deletion with accepted survivors failed", failure);
    } finally {
      unlockAfterChanges();
    }
  }

  private static String prefixExclusions(List<String> subtrees, List<String> parameters) {
    var predicate = new StringBuilder();
    for (String subtree : subtrees) {
      String lower = PathNormalizer.normalizePathPrefix(subtree);
      if (lower == null || lower.isBlank()) throw new IllegalArgumentException("Empty exclusion");
      predicate.append(" AND NOT (path >= ? AND path < ?)");
      parameters.add(lower);
      parameters.add(upperBoundExclusive(lower));
    }
    return predicate.toString();
  }

  /**
   * Counts jobs whose path starts with the given prefix, broken down by sub-state (tempdoc 599
   * §9.2). Uses a half-open RANGE predicate ({@code path >= lower AND path < upper}) rather than
   * {@code LIKE} (tempdoc 599 Fix 2): it (a) uses the {@code path} PRIMARY KEY index — a case-
   * insensitive {@code LIKE} would force a full table scan, and this is called per-folder on every
   * live-status tick — and (b) avoids {@code LIKE} treating {@code _}/{@code %} in a real path as
   * wildcards (e.g. {@code F:\a_b} must not count {@code F:\aXb}). Correctness rests on stored job
   * paths and the prefix sharing the SAME {@link PathNormalizer} normalization (Windows-lowercased,
   * separators normalized) over the BINARY-collation PK. The lower bound ends with a separator (so
   * {@code F:\docs} does not match {@code F:\docs-other}); the upper bound increments its last char.
   * Returns all-zero on error or empty prefix.
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return per-state counts under the prefix
   */
  @Override
  public JobQueue.JobStateCounts countByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("countByPathPrefix called with empty prefix, refusing");
        return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
      }
      String upper = upperBoundExclusive(lower);

      // #758: a source retried because it cannot be read is a problem for the
      // user to see, not work in flight — counted with the failures so a folder holding a locked
      // file does not read "indexing" forever.
      String sql = "SELECT CASE WHEN " + SOURCE_UNREADABLE_RETRY + " THEN '" + STATE_FAILED
          + "' ELSE state END AS shown_state, COUNT(*) FROM jobs WHERE path >= ? AND path < ?"
          + " GROUP BY shown_state";

      long pending = 0L;
      long processing = 0L;
      long done = 0L;
      long failed = 0L;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            String state = rs.getString(1);
            long count = rs.getLong(2);
            switch (state == null ? "" : state) {
              case STATE_PENDING -> pending = count;
              case STATE_PROCESSING -> processing = count;
              case STATE_DONE -> done = count;
              // Tempdoc 885 item 21b: RETRY_EXHAUSTED is a terminal failure for every folder
              // projection. Leaving it in the `default` arm would have silently dropped those rows
              // out of the per-root totals, so a folder with exhausted files would read "100%".
              case STATE_FAILED, STATE_RETRY_EXHAUSTED -> failed += count;
              default -> log.debug("countByPathPrefix: unknown state {} ({} rows)", state, count);
            }
          }
        }
      }
      // pendingReadyCount: the queue's backoff distinction is not needed for the folder projection
      // (in-flight = pending + processing); report pendingReadyCount == pendingCount.
      return new JobQueue.JobStateCounts(pending, pending, processing, done, failed);
    } catch (SQLException e) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Cannot count jobs by path prefix", e);
    } finally {
      unlockAfterChanges();
    }
  }

  @Override
  public boolean enqueueReappearedFile(JobQueue.EnqueueEntry entry, String collection) {
    var declared = collection == null || collection.isBlank()
        ? declaredCollectionRoots.get() : List.<IngestCollectionPolicy.RootBinding>of();
    lock.lock();
    try {
      ensureOpen();
      String path = PathNormalizer.normalizePath(entry.path().toAbsolutePath().toString());
      return inTransaction(() -> {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT state FROM jobs WHERE path = ?")) {
          query.setString(1, path);
          try (ResultSet row = query.executeQuery()) {
            if (row.next() && ("PENDING".equals(row.getString(1))
                || "PROCESSING".equals(row.getString(1)))) return true;
          }
        }
        return enqueueEntriesInTransaction(List.of(entry), collection, null,
            System.currentTimeMillis(), false, null, false, declared) == 1;
      });
    } catch (SQLException failure) {
      log.warn("Cannot admit recreated file {}", entry.path(), failure);
      return false;
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Deletes a single job by exact path match.
   * Used by file watcher for single file deletions.
   *
   * @param path the exact path (must already be normalized)
   * @return number of jobs deleted (0 or 1), or -1 on error
   */
  @Override
  public int deleteByExactPath(String path) {
    lock.lock();
    try {
      ensureOpen();

      if (path == null || path.isBlank()) {
        log.warn("deleteByExactPath called with empty path, refusing");
        return 0;
      }

      return removeJobsAdministratively("path = ?", List.of(PathNormalizer.normalizePath(path)), true);

    } catch (SQLException e) {
      log.error("Failed to delete job by exact path: {}", path, e);
      return -1;
    } finally {
      unlockAfterChanges();
    }
  }

  private void ensureOpen() {
    if (connectionFailure != null) {
      recordDbError();
      throw new IngestionServiceUnavailableException(
          "SqliteJobQueue connection cleanup remains unresolved", connectionFailure);
    }
    try { SqliteServiceAvailability.requireOpen(connection, false); }
    catch (SQLException | IngestionServiceUnavailableException failure) {
      recordDbError();
      throw new IngestionServiceUnavailableException("Cannot inspect queue connection", failure);
    }
  }

  /**
   * Returns true if the filesystem hosting the queue DB has at least {@link #MIN_FREE_DISK_BYTES}
   * of free space. On any IO error during the probe (e.g., file store unavailable) returns true
   * (fail-open) — the disk-space probe must never be the reason a write fails. The actual write
   * still surfaces SQLITE_IOERR_FULL if the probe was wrong.
   */
  private boolean hasSufficientDiskSpace() {
    try {
      Path probePath = dbPath.toAbsolutePath();
      Path parent = probePath.getParent();
      Path target = (parent != null && Files.isDirectory(parent)) ? parent : probePath;
      long usable = Files.getFileStore(target).getUsableSpace();
      return usable >= MIN_FREE_DISK_BYTES;
    } catch (IOException e) {
      log.debug("Disk-space probe failed; allowing enqueue (fail-open): {}", e.getMessage());
      return true;
    }
  }

  // ==================== Backup ====================

  /**
   * Creates a backup of the jobs.db database using VACUUM INTO.
   *
   * <p>This creates a consistent snapshot of the database, even in WAL mode,
   * by using SQLite's VACUUM INTO command which produces a standalone copy.
   *
   * <p>The backup is written to a temp file first, then atomically moved to
   * {@code jobs.db.bak} to ensure we never have a partial backup file.
   *
   * <p><b>Note:</b> This method only creates a backup if the database existed
   * before {@link #open()} was called. Fresh databases don't need backup.
   *
   * @throws SQLException if the VACUUM INTO command fails
   * @throws IOException if the atomic move fails
   */
  public void performBackup() throws SQLException, IOException {
    if (!existedBeforeOpen) {
      log.debug("Skipping backup: database did not exist before open");
      return;
    }

    lock.lock();
    try {
      ensureOpen();

      Path tmp = dbPath.resolveSibling(dbPath.getFileName() + ".bak.tmp");
      Path bak = dbPath.resolveSibling(dbPath.getFileName() + ".bak");

      // An interrupted VACUUM INTO may leave a nonempty scratch file. Only discard
      // that scratch file; the live database and last completed backup remain owned.
      Files.deleteIfExists(tmp);

      // Escape single quotes in path for SQL literal
      String escapedPath = tmp.toAbsolutePath().toString().replace("'", "''");

      log.info("Creating backup: {} -> {}", dbPath, bak);

      try (Statement stmt = connection.createStatement()) {
        stmt.execute("VACUUM INTO '" + escapedPath + "'");
      }

      // Atomic move to final location
      Files.move(tmp, bak, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

      lastBackupAtMs = System.currentTimeMillis();
      log.info("Backup created successfully: {}", bak);
    } finally {
      unlockAfterChanges();
    }
  }

  // ==================== Integrity Check ====================

  /**
   * Performs a quick integrity check on the database using PRAGMA quick_check.
   *
   * <p>This method should be called after opening an existing database to detect
   * corruption early. It also verifies fresh databases. A passing check does not clear an
   * earlier recorded database error: structural integrity does not prove the failed query works.
   *
   * <p>PRAGMA quick_check returns multiple rows; the database is considered healthy
   * only if ALL rows return exactly "ok". Any other result indicates corruption.
   *
   * @return true if the database passes the integrity check
   * @throws SQLException with error code 11 (SQLITE_CORRUPT) if the check fails
   */
  public boolean performIntegrityCheck() throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      log.info("Running integrity check on jobs.db...");

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("PRAGMA quick_check")) {
        StringBuilder errors = new StringBuilder();
        int rowCount = 0;

        while (rs.next()) {
          String result = rs.getString(1);
          rowCount++;
          if (!"ok".equalsIgnoreCase(result)) {
            if (errors.length() > 0) {
              errors.append("; ");
            }
            errors.append(result);
          }
        }

        lastQuickCheckAtMs = System.currentTimeMillis();

        if (rowCount == 0 || errors.length() > 0) {
          lastQuickCheckOk = false; // NOPMD - read by health check accessor
          dbHealthy = false;
          String errorMsg = "Database integrity check failed: " + errors;
          log.error(errorMsg);
          // Use SQLite error code 11 for SQLITE_CORRUPT
          throw new SQLException(errorMsg, "SQLITE_CORRUPT", 11);
        }

        lastQuickCheckOk = true;
        log.info("Integrity check passed ({} rows checked)", rowCount);
        return true;
      }
    } catch (SQLException | RuntimeException failure) {
      lastQuickCheckAtMs = System.currentTimeMillis();
      lastQuickCheckOk = false;
      recordDbError();
      throw failure;
    } finally {
      unlockAfterChanges();
    }
  }

  private void closeWalkChanges() {
    if (walkChanges == null) return;
    try { walkChanges.close(); }
    finally { walkChanges = null; }
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      try { closeWalkChanges(); }
      catch (RuntimeException failure) { log.warn("Failed to close recorded walk notifications cleanly", failure); }
      if (changeStream != null) {
        try {
          changeStream.close();
        } catch (RuntimeException e) {
          log.warn("Failed to close IndexingJobsChangeStream cleanly", e);
        } finally {
          changeStream = null;
        }
      }
      if (connection != null) {
        try {
          // Design 7.3 step 7 / item B5: drain the WAL on EVERY ordered close, not just the
          // upgrade barrier. Before B5 the only caller was WorkerUpgradeQuiescence, so an Engine
          // that exited any other way left frames for the next start to replay.
          checkpointWalBestEffort();
          connection.close();
          connection = null;
          connectionFailure = null;
          activeClaims.clear();
          log.info("SqliteJobQueue closed");
        } catch (SQLException | RuntimeException | Error failure) {
          if (connectionFailure == null) connectionFailure = failure;
          else if (connectionFailure != failure) connectionFailure.addSuppressed(failure);
          recordDbError();
          if (failure instanceof SQLException sql) throw new IOException("Failed to close SqliteJobQueue", sql);
          if (failure instanceof RuntimeException runtime) throw runtime;
          throw (Error) failure;
        }
      }
    } finally {
      unlockAfterChanges();
    }
  }

  /**
   * Slice 445: accessor for the change-stream so the ingest service
   * ({@code WorkerIngestService.subscribeIndexingJobs}) can subscribe to
   * snapshot+delta frames. Returns {@code null} if the queue is closed or
   * not yet opened.
   */
  public IndexingJobsChangeStream changeStream() {
    return changeStream;
  }

  @Override
  public java.util.Optional<IndexingJobChangeFeed> indexingJobChangeFeed() {
    IndexingJobsChangeStream cs = changeStream;
    return cs == null ? java.util.Optional.empty() : java.util.Optional.of(cs);
  }

  // ==================== Health Observability ====================

  /**
   * Records a database error for health tracking.
   *
   * <p>Called from best-effort status helpers that swallow SQL exceptions.
   * This allows observability surfaces to detect when the queue DB is unhealthy
   * without requiring callers to handle exceptions.
   */
  private void recordDbError() {
    dbHealthy = false;
    lastDbErrorAtMs = System.currentTimeMillis();
  }

  /**
   * Returns a snapshot of the queue database health for observability surfaces.
   *
   * <p>This includes:
   * <ul>
   *   <li>Whether the DB is currently considered healthy</li>
   *   <li>When the last integrity check was run and whether it passed</li>
   *   <li>When the last backup was created</li>
   *   <li>When the last SQL error occurred in a status helper</li>
   * </ul>
   *
   * @return a snapshot of queue DB health
   */
  @Override
  public JobQueue.QueueDbHealthSnapshot queueDbHealthSnapshot() {
    return new JobQueue.QueueDbHealthSnapshot(
        dbHealthy,
        lastQuickCheckAtMs,
        lastQuickCheckOk,
        lastBackupAtMs,
        lastDbErrorAtMs);
  }

  /**
   * The close-path checkpoint. Never throws and never blocks the close.
   *
   * <p>Separate from {@link #checkpointWal()} because the two callers want opposite things on
   * failure: the upgrade barrier must REFUSE to proceed when frames remain (a half-drained WAL
   * under a binary swap is how a queue comes back inconsistent), while a close that cannot drain
   * must still close — the alternative is an Engine that will not shut down because SQLite is busy,
   * and the WAL is replayed on the next open anyway.
   */
  private void checkpointWalBestEffort() {
    try {
      if (!checkpointWal()) {
        log.info("WAL not fully drained on close; the next open replays the remaining frames");
      }
    } catch (RuntimeException e) {
      log.warn("WAL checkpoint on close failed (closing anyway): {}", e.getMessage());
    }
  }

  @Override
  public boolean checkpointWal() {
    lock.lock();
    try {
      ensureOpen();
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
        // SQLite returns (busy, log, checkpointed). A non-zero busy count means a writer still
        // owns frames and the upgrade must wait rather than pretending the queue is closed over.
        boolean complete = result.next() && result.getInt(1) == 0;
        if (!complete) {
          log.warn("WAL checkpoint could not drain all frames");
        }
        return complete;
      }
    } catch (SQLException e) {
      recordDbError();
      log.warn("WAL checkpoint failed: {}", e.getMessage());
      return false;
    } finally {
      unlockAfterChanges();
    }
  }
}
