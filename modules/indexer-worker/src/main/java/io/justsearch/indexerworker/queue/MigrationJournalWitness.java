/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.ContendedFileReads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import tools.jackson.databind.json.JsonMapper;

/** Queue admission evidence stored beside the generation state, never in a queue backup. */
public final class MigrationJournalWitness {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MigrationJournalWitness.class);
  public static final String REFUSAL = "index.migration_journal_unavailable";
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private final Path indexBase;
  private final Path path;
  private final Path triagePath;
  private long acknowledged;

  public MigrationJournalWitness(Path indexBase, Path database) throws IOException {
    this.indexBase = indexBase;
    path = indexBase.resolve("migration-journal-admissions");
    triagePath = database.resolveSibling(database.getFileName() + ".triage");
    if (Files.exists(path)) {
      try {
        acknowledged = Long.parseLong(new String(
            ContendedFileReads.readAllBytes(path), StandardCharsets.UTF_8).trim());
        if (acknowledged < 0) throw new NumberFormatException("negative admission count");
      } catch (IOException | NumberFormatException invalid) {
        throw new IOException(REFUSAL + ": admission witness is invalid", invalid);
      }
    }
  }

  public long acknowledged() { return acknowledged; }

  public void acknowledge(long count) throws SQLException {
    if (count < acknowledged) throw new SQLException(REFUSAL + ": admission count regressed");
    if (count == acknowledged && Files.exists(path)) return;
    try {
      AtomicFileWrites.replaceStrict(path, (count + "\n").getBytes(StandardCharsets.UTF_8));
      acknowledged = count;
    } catch (IOException failure) {
      throw new SQLException(REFUSAL + ": cannot persist admission witness", failure);
    }
  }

  public record TriageReceipt(String quarantine, Boolean backup, Boolean unfinished, Boolean complete) {
    public TriageReceipt {
      java.util.Objects.requireNonNull(backup, "backup");
      java.util.Objects.requireNonNull(unfinished, "unfinished");
      java.util.Objects.requireNonNull(complete, "complete");
      if (quarantine == null || !quarantine.matches(
          "jobs[.]db[.]corrupt(?:-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})?")) {
        throw new IllegalArgumentException("Invalid queue quarantine identity");
      }
    }
  }

  public TriageReceipt triageReceipt() throws IOException {
    if (!Files.exists(triagePath)) return null;
    try {
      var receipt = JSON.readValue(
          ContendedFileReads.readAllBytes(triagePath), TriageReceipt.class);
      if (receipt == null) throw new IOException("Empty queue triage receipt");
      return receipt;
    } catch (RuntimeException invalid) {
      throw new IOException(REFUSAL + ": triage receipt is invalid", invalid);
    }
  }

  /** Persist the triage decision before its first filesystem mutation, including an interrupted run. */
  public TriageReceipt beginTriage(Path database) throws IOException {
    var prior = triageReceipt();
    if (prior != null && !prior.complete()) return prior;
    boolean unfinished = new io.justsearch.indexerworker.index.IndexGenerationManager(indexBase)
        .requiresMigrationJournalForBoot();
    String quarantine = "jobs.db.corrupt";
    if (Files.exists(database.resolveSibling(quarantine))) quarantine += "-" + java.util.UUID.randomUUID();
    var receipt = new TriageReceipt(quarantine,
        Files.exists(database.resolveSibling("jobs.db.bak")), unfinished, false);
    writeTriageReceipt(receipt);
    if (unfinished) throw new IOException(REFUSAL + ": triage refused during unfinished migration");
    return receipt;
  }

  public void completeTriage(TriageReceipt receipt) throws IOException {
    writeTriageReceipt(new TriageReceipt(
        receipt.quarantine(), receipt.backup(), receipt.unfinished(), true));
  }

  private void writeTriageReceipt(TriageReceipt receipt) throws IOException {
    AtomicFileWrites.replaceStrict(triagePath,
        JSON.writeValueAsBytes(receipt));
  }

  /**
   * Validate a private copy, including schema initialization, before opening the live queue.
   * SQLite may checkpoint or migrate before discovering corruption; only the copy may do so.
   * The caller holds the index-root lock and the Engine owns the data directory.
   */
  public void validateUnfinished(Path database) throws IOException {
    var receipt = triageReceipt();
    if (receipt != null && (!receipt.complete() || receipt.unfinished())) {
      throw new IOException(REFUSAL + ": triage interrupted an unfinished migration");
    }
    if (!Files.isRegularFile(database) || Files.size(database) == 0) {
      throw new IOException(REFUSAL + ": unfinished migration has no job journal");
    }
    Path scratch = Files.createTempDirectory(database.getParent(), ".migration-journal-check-");
    try {
      Path copy = scratch.resolve("jobs.db");
      Files.copy(database, copy);
      Path wal = database.resolveSibling(database.getFileName() + "-wal");
      if (Files.exists(wal)) Files.copy(wal, scratch.resolve("jobs.db-wal"));
      try (var queue = new SqliteJobQueue(copy)) {
        queue.openWithIntegrityCheck();
        if (queue.switchBufferAdmissions() < acknowledged) {
          throw new IOException(REFUSAL + ": job journal predates acknowledged admissions");
        }
      } catch (SQLException invalid) {
        throw new IOException(REFUSAL + ": unfinished migration job journal is invalid", invalid);
      }
    } finally {
      // Disposable validation bytes must not mask the refusal or block a healthy journal on
      // Windows when a scanner briefly holds a completed copy open.
      io.justsearch.configuration.FileOps.deleteRecursivelyBestEffort(scratch, log);
    }
  }
}
