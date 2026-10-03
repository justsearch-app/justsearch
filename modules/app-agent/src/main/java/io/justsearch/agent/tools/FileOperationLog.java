/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import io.justsearch.telemetry.DiagnosticFileRetention;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction log for file operations. Writes a JSON log per batch to {@code
 * {dataDir}/file-operations/{batchId}.json}. Supports undo by recording every operation and its
 * outcome.
 */
public final class FileOperationLog {
  private static final Logger LOG = LoggerFactory.getLogger(FileOperationLog.class);
  /**
   * v2 (tempdoc 909 items 7/8) adds {@code executed[].destinationDigest} for COPY operations — the
   * content identity a COPY-undo must match before it deletes the file. v1 journals stay readable
   * (nothing was removed or re-shaped), and a v1 entry's ABSENT digest is what tells the undo it
   * cannot prove identity, so it preserves rather than deletes. The bump is what makes an older app
   * refuse a v2 journal loudly instead of undoing it under v1's weaker guarantee.
   */
  public static final int CURRENT_SCHEMA_VERSION = 2;
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final Path logDir;

  public FileOperationLog(Path logDir) {
    this.logDir = logDir;
    try {
      Files.createDirectories(logDir);
    } catch (IOException e) {
      LOG.warn("Failed to create transaction log directory: {}", logDir, e);
    }
    DiagnosticFileRetention.pruneBefore(
        logDir, "", Instant.now().minus(java.time.Duration.ofDays(30)));
  }

  void startBatch(String batchId, String explanation, List<FileOperation> operations) {
    Map<String, Object> log = new HashMap<>();
    log.put("batchId", batchId);
    log.put("schemaVersion", CURRENT_SCHEMA_VERSION);
    log.put("timestamp", Instant.now().toString());
    log.put("explanation", explanation);
    log.put(
        "operations",
        operations.stream()
            .map(
                op ->
                    Map.of(
                        "op", op.op().name(),
                        "source", op.source() != null ? op.source().toString() : "",
                        "destination", op.destination().toString()))
            .toList());
    log.put("executed", new ArrayList<Map<String, Object>>());
    writeBatchLog(batchId, log);
  }

  /**
   * Records a successful operation.
   *
   * @param destinationDigest the content identity of what the operation left at the destination, or
   *     null when it has none (schema v2, tempdoc 909 items 7/8). Only a COPY records one: undoing a
   *     COPY DELETES the destination, so it is the one undo that must prove it is deleting the
   *     agent's own bytes. A MOVE-undo moves the file back, which loses nothing, and MKDIR-undo only
   *     removes an empty directory.
   */
  void recordSuccess(String batchId, int index, String destinationDigest) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "OK");
          entry.put("timestamp", Instant.now().toString());
          if (destinationDigest != null && !destinationDigest.isBlank()) {
            entry.put("destinationDigest", destinationDigest);
          }
          executed.add(entry);
        });
  }

  void recordFailure(String batchId, int index, String error) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "FAILED");
          entry.put("error", error);
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  void recordSkip(String batchId, int index, String reason) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "SKIPPED");
          entry.put("reason", reason);
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  /** As {@link #recordSuccess}, for an operation whose destination was auto-suffixed. */
  void recordRename(
      String batchId, int index, Path originalDest, Path resolvedDest, String destinationDigest) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "OK_RENAMED");
          entry.put("originalDestination", originalDest.toString());
          entry.put("resolvedDestination", resolvedDest.toString());
          entry.put("timestamp", Instant.now().toString());
          if (destinationDigest != null && !destinationDigest.isBlank()) {
            entry.put("destinationDigest", destinationDigest);
          }
          executed.add(entry);
        });
  }

  void finalizeBatch(String batchId) {
    updateBatchLog(batchId, log -> log.put("finalized", Instant.now().toString()));
  }

  /** Read a batch log from disk. Returns null if the batch does not exist. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> readBatch(String batchId) {
    Path logFile = logDir.resolve(batchId + ".json");
    if (!Files.exists(logFile)) {
      return null;
    }
    try {
      Map<String, Object> log = MAPPER.readValue(logFile.toFile(), Map.class);
      requireReadableVersion(log);
      return log;
    } catch (UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "file-operation-journal", "cannot read batch " + batchId, e);
    }
  }

  /** List recent batch logs sorted by modification time (newest first). */
  public List<Map<String, Object>> listBatches(int limit) {
    try {
      if (!Files.isDirectory(logDir)) {
        return List.of();
      }
      try (var stream = Files.list(logDir)) {
        return stream
            .filter(p -> p.toString().endsWith(".json"))
            .sorted(
                Comparator.<Path, FileTime>comparing(
                        p -> {
                          try {
                            return Files.getLastModifiedTime(p);
                          } catch (IOException e) {
                            return FileTime.fromMillis(0);
                          }
                        })
                    .reversed())
            .limit(limit)
            .map(this::readBatchFile)
            .filter(Objects::nonNull)
            .toList();
      }
    } catch (IOException e) {
      LOG.error("Failed to list batch logs", e);
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readBatchFile(Path path) {
    try {
      Map<String, Object> log = MAPPER.readValue(path.toFile(), Map.class);
      requireReadableVersion(log);
      return log;
    } catch (UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      LOG.error("Failed to read batch log file: {}", path, e);
      return null;
    }
  }

  private void writeBatchLog(String batchId, Map<String, Object> log) {
    try {
      Path logFile = logDir.resolve(batchId + ".json");
      atomicWrite(logFile, log);
    } catch (UnsupportedStoreVersionException e) {
      throw e;
    } catch (IOException e) {
      LOG.error("Failed to write transaction log for batch {}", batchId, e);
    }
  }

  @SuppressWarnings("unchecked")
  private void updateBatchLog(String batchId, Consumer<Map<String, Object>> updater) {
    try {
      Path logFile = logDir.resolve(batchId + ".json");
      if (!Files.exists(logFile)) {
        LOG.warn("Batch log not found: {}", batchId);
        return;
      }
      Map<String, Object> log = MAPPER.readValue(logFile.toFile(), Map.class);
      requireReadableVersion(log);
      updater.accept(log);
      atomicWrite(logFile, log);
    } catch (UnsupportedStoreVersionException | CorruptDurableStoreException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "file-operation-journal", "cannot update batch " + batchId, e);
    }
  }

  /** Write JSON to a temp file, then atomic-rename to the target. */
  private void atomicWrite(Path target, Map<String, Object> data) throws IOException {
    AtomicFileWrites.replace(target, MAPPER.writeValueAsBytes(data));
  }

  private static void requireReadableVersion(Map<String, Object> log) {
    Object raw = log.get("schemaVersion");
    int version = raw instanceof Number number ? number.intValue() : 0;
    if (version > CURRENT_SCHEMA_VERSION) {
      throw new UnsupportedStoreVersionException(
          "file-operation-journal", version, CURRENT_SCHEMA_VERSION);
    }
  }

}
