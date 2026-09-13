/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for executing file system operations: MOVE, RENAME, MKDIR, COPY.
 *
 * <p>All paths are sandboxed to indexed roots. Operations are logged and can be undone. After
 * MOVE/RENAME operations, the Lucene index is updated to reflect new file paths.
 *
 * <p>Safety level is WRITE — requires user approval before execution.
 */
/**
 * Destructive file-operations tool. It is its own
 * {@link io.justsearch.agent.api.registry.OperationHandler}: the substrate dispatches
 * {@code execute(...)} and {@code undo(...)} directly against this class, and the
 * substrate's {@code OperationPolicy.undoSupported()} replaces the deleted
 * {@code supportsUndo()} contract.
 */
public final class FileOperationsTool implements OperationHandler {
  private static final Logger LOG = LoggerFactory.getLogger(FileOperationsTool.class);
  // Tempdoc 877 §2.1 — public (now same-package: AgentToolsOperationCatalog lives in this
  // io.justsearch.agent.tools package too) so fileOperations() can interpolate it into the
  // model-visible `operations` description. The deleted tool-local schema constant used to
  // spell the same number a second time; interpolating leaves one author for it.
  public static final int MAX_BATCH_SIZE = 50;

  private final FileOperationExecutor executor;
  private final FileOperationLog transactionLog;

  /**
   * The indexed roots BY NAME, used only to re-resolve a root-relative path the model echoed back
   * from a browse result (tempdoc 877 §2.7). Deliberately NOT the same thing as {@code
   * indexedRootsSupplier}, which is the executor's sandbox: that one decides what may be written,
   * this one only decides what a path string means.
   */
  private final AgentToolPaths.RootsView rootsView;

  /**
   * Creates a new FileOperationsTool.
   *
   * @param indexedRootsSupplier supplies current indexed roots for path sandboxing
   * @param indexUpdateCallback callback to update the search index after file moves
   * @param transactionLog shared transaction log for recording operations and undo
   */
  public FileOperationsTool(
      java.util.function.Function<EngineContext, List<Path>> indexedRootsSupplier,
      IndexUpdateCallback indexUpdateCallback,
      FileOperationLog transactionLog) {
    this(indexedRootsSupplier, indexUpdateCallback, transactionLog, null);
  }

  /**
   * Tempdoc 877 §2.7 — adds the root-name view the other four tools already have. Null-tolerant: a
   * null {@code rootsView} resolves nothing, which is exactly the pre-877 behaviour.
   */
  public FileOperationsTool(
      java.util.function.Function<EngineContext, List<Path>> indexedRootsSupplier,
      IndexUpdateCallback indexUpdateCallback,
      FileOperationLog transactionLog,
      AgentToolPaths.RootsView rootsView) {
    this.transactionLog = transactionLog;
    this.rootsView = rootsView == null ? AgentToolPaths.RootsView.of(null) : rootsView;
    this.executor =
        new FileOperationExecutor(indexedRootsSupplier, indexUpdateCallback, transactionLog);
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      JsonNode opsNode = args.get("operations");
      if (opsNode == null || !opsNode.isArray() || opsNode.isEmpty()) {
        return OperationResult.failure("No operations specified");
      }
      if (opsNode.size() > MAX_BATCH_SIZE) {
        return OperationResult.failure(
            "Too many operations: "
                + opsNode.size()
                + " exceeds limit of "
                + MAX_BATCH_SIZE
                + ". Split into smaller batches.");
      }

      List<FileOperation> operations = parseOperations(opsNode, engineContext);
      String explanation = ToolArgs.stringArg(args, "explanation", "File operations");

      ConflictStrategy strategy = ConflictStrategy.FAIL;
      String requestedStrategy = ToolArgs.stringArg(args, "conflict_strategy");
      if (requestedStrategy != null) {
        try {
          strategy = ConflictStrategy.valueOf(requestedStrategy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          return AgentToolErrors.badRequest(
              "core_file_operations", "Invalid conflict_strategy: " + requestedStrategy);
        }
      }

      // Validate all operations first
      var validation = executor.validate(operations, strategy, engineContext);
      if (!validation.allValid()) {
        return OperationResult.failure("Validation failed: " + validation.summary());
      }

      // Execute
      var report = executor.execute(operations, explanation, strategy, engineContext);

      if (report.allSucceeded()) {
        return OperationResult.success(report.summary(), report.batchId());
      } else {
        return OperationResult.failure(report.summary());
      }

    } catch (OperationArgException e) {
      // Request-validation problem (bad/missing operation fields) — a clean,
      // self-correcting message for the agent, not an "Execution error". Scoped to
      // THIS exception so a genuine IllegalArgumentException from validate/execute
      // still falls through to the logged generic handler below.
      return AgentToolErrors.badRequest("core_file_operations", e.getMessage());
    } catch (Exception e) {
      return AgentToolErrors.classify("core_file_operations", "Execution error", e);
    }
  }

  @Override
  public OperationResult undo(String executionId, EngineContext engineContext) {
    try {
      Map<String, Object> batch = transactionLog.readBatch(executionId);
      if (batch == null) {
        return OperationResult.failure("No operation log found for batch: " + executionId);
      }
      if (!batch.containsKey("finalized")) {
        return OperationResult.failure("Cannot undo unfinalized batch: " + executionId);
      }

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> operations =
          (List<Map<String, Object>>) batch.get("operations");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> executed =
          (List<Map<String, Object>>) batch.get("executed");

      if (operations == null || executed == null) {
        return OperationResult.failure("Malformed batch log: " + executionId);
      }

      // Collect indices and resolved destinations from successfully executed ops
      Set<Integer> successIndices = new LinkedHashSet<>();
      Map<Integer, Path> resolvedDestinations = new HashMap<>();
      // Tempdoc 909 items 7/8 — the content identity the COPY recorded for its destination, per op.
      // Absent for every journal written before schema v2, which is what makes the legacy case
      // detectable rather than silently deleted.
      Map<Integer, String> recordedDigests = new HashMap<>();
      // Tempdoc 577 §2.14 Root III (#16) — the per-op completion time, the conflict-detection
      // baseline: a target whose mtime is later than this was changed AFTER the agent acted.
      Map<Integer, Instant> executedAt = new HashMap<>();

      for (Map<String, Object> entry : executed) {
        int index = ((Number) entry.get("index")).intValue();
        String status = (String) entry.get("status");
        if ("OK".equals(status) || "OK_RENAMED".equals(status)) {
          successIndices.add(index);
          if ("OK_RENAMED".equals(status) && entry.containsKey("resolvedDestination")) {
            resolvedDestinations.put(index, Path.of((String) entry.get("resolvedDestination")));
          }
          if (entry.get("destinationDigest") instanceof String digest && !digest.isBlank()) {
            recordedDigests.put(index, digest);
          }
          Object ts = entry.get("timestamp");
          if (ts instanceof String s) {
            try {
              executedAt.put(index, Instant.parse(s));
            } catch (DateTimeParseException ignored) {
              // Unparseable timestamp ⇒ no conflict baseline; the op reverts as before.
            }
          }
        }
      }

      if (successIndices.isEmpty()) {
        return OperationResult.success("Nothing to undo (no successful operations in batch).");
      }

      // Build reverse operations in reverse order
      List<Integer> indices = new ArrayList<>(successIndices);
      Collections.reverse(indices);

      List<FileOperation> reverseMovesAndRenames = new ArrayList<>();
      List<UndoAction> directActions = new ArrayList<>();
      // Tempdoc 577 §2.14 Root III (#16) — conflict-detection: a target the USER changed since the
      // agent acted must NOT be blindly reverted (a COPY-undo deletes it; a MOVE-undo relocates the
      // user's edit). Such ops are collected here and SKIPPED, then reported "changed since — review
      // before undo", so the bulk undo is honest and never destroys a since-edited file.
      List<Path> conflicted = new ArrayList<>();

      for (int idx : indices) {
        Map<String, Object> opMap = operations.get(idx);
        FileOperation.OpType opType =
            FileOperation.OpType.valueOf((String) opMap.get("op"));
        String sourceStr = (String) opMap.get("source");
        Path source =
            sourceStr != null && !sourceStr.isEmpty() ? Path.of(sourceStr) : null;
        Path destination =
            resolvedDestinations.containsKey(idx)
                ? resolvedDestinations.get(idx)
                : Path.of((String) opMap.get("destination"));

        // MKDIR-undo only deletes an EMPTY directory (already guarded below), so it can never lose
        // user content — exempt it from the modified-since check. MOVE/RENAME/COPY-undo touch a
        // file target whose since-edit would be lost, so they are conflict-checked.
        if (opType != FileOperation.OpType.MKDIR
            && modifiedSince(destination, executedAt.get(idx))) {
          conflicted.add(destination);
          continue; // do not revert a target the user changed since the agent acted
        }

        switch (opType) {
          case MOVE, RENAME ->
              reverseMovesAndRenames.add(
                  new FileOperation(FileOperation.OpType.MOVE, destination, source));
          case MKDIR -> directActions.add(new UndoAction(opType, destination, null));
          case COPY ->
              directActions.add(new UndoAction(opType, destination, recordedDigests.get(idx)));
        }
      }

      StringBuilder result = new StringBuilder();
      int undoneCount = 0;
      int skippedCount = 0;
      // Tempdoc 875 §C.6 — COPY-undo targets that no longer sit inside an indexed root. Skipped
      // rather than deleted, and named in the summary so the user knows what was left behind.
      List<Path> outOfRoots = new ArrayList<>();
      // Tempdoc 909 items 7/8 — COPY-undo targets whose content no longer matches what the journal
      // recorded at copy time, and those whose journal predates the recorded identity entirely.
      // Both are preserved; they are reported apart because the remedy differs (one says "you
      // changed this", the other says "this app cannot tell").
      List<Path> contentChanged = new ArrayList<>();
      List<Path> unverifiable = new ArrayList<>();

      // Execute reverse MOVE/RENAME through executor for index updates
      if (!reverseMovesAndRenames.isEmpty()) {
        var validation = executor.validate(reverseMovesAndRenames, engineContext);
        if (!validation.allValid()) {
          return OperationResult.failure("Undo validation failed: " + validation.summary());
        }
        var report =
            executor.execute(reverseMovesAndRenames, "Undo of batch " + executionId, engineContext);
        undoneCount += report.successCount();
        result.append(report.summary());
      }

      // Handle MKDIR and COPY undo directly (no executor pipeline needed)
      for (UndoAction action : directActions) {
        try {
          if (action.opType == FileOperation.OpType.MKDIR) {
            if (Files.isDirectory(action.path) && isDirectoryEmpty(action.path)) {
              Files.delete(action.path);
              undoneCount++;
            } else {
              skippedCount++;
              LOG.info("Skipping MKDIR undo for non-empty directory: {}", action.path);
            }
          } else if (action.opType == FileOperation.OpType.COPY) {
            if (Files.exists(action.path)) {
              // Tempdoc 875 §C.6 — undoing a COPY is a DELETE (recursive for a directory), which
              // is strictly more dangerous than the forward operation. The MOVE/RENAME arm above
              // re-validates through executor.validate(...); this arm must re-prove containment
              // too, because the indexed roots can shrink between the operation and the undo.
              if (!executor.isWithinIndexedRoots(action.path, engineContext)) {
                skippedCount++;
                outOfRoots.add(action.path);
                LOG.warn(
                    "Skipping COPY undo: target is outside the indexed roots: {}", action.path);
              } else if (action.recordedDigest == null) {
                // Tempdoc 909 items 7/8 — no content identity was recorded, so this undo cannot
                // prove the file is still the agent's copy. Two ways to get here: a journal written
                // before schema v2, or a copy over FileContentDigest.MAX_DIGEST_BYTES, which is not
                // hashed. Deleting on an unprovable claim is the failure mode this whole guard
                // exists to end, so the conservative branch is the default one: preserve, say why.
                skippedCount++;
                unverifiable.add(action.path);
                LOG.warn(
                    "Skipping COPY undo: the operation log records no content identity for {}"
                        + " (either it predates this check, or the copy was above the size the app"
                        + " verifies), so the copy cannot be proven unchanged",
                    action.path);
              } else if (!FileContentDigest.matches(action.path, action.recordedDigest)) {
                // The bytes are not the ones the agent wrote: the user replaced, edited or restored
                // over the copy. mtime said nothing (a timestamp-preserving write is invisible to
                // it), which is exactly why identity is checked by content here.
                skippedCount++;
                contentChanged.add(action.path);
                LOG.warn(
                    "Skipping COPY undo: {} no longer holds the content the agent copied", action.path);
              } else if (Files.isDirectory(action.path)) {
                // WHY the check-then-delete window is accepted: the digest was read a few
                // microseconds ago and the delete is not atomic against it, so a write landing
                // inside that window is deleted unverified. Closing it would need an exclusive
                // lock on a file the USER owns, held across the verify and the delete — which
                // takes the user's own document hostage to an undo and can itself fail on a file
                // an editor has open. The window replaces an unbounded exposure (every
                // timestamp-preserving edit, forever) with a microsecond one, on an operation the
                // user explicitly asked for; it is a narrowing, not a guarantee.
                executor.deleteDirectory(action.path);
                undoneCount++;
              } else {
                Files.delete(action.path);
                undoneCount++;
              }
            } else {
              skippedCount++;
            }
          }
        } catch (IOException e) {
          LOG.warn("Undo action failed for {}: {}", action.path, e.getMessage());
          skippedCount++;
        }
      }

      if (result.isEmpty()) {
        result.append(
            String.format("Undo completed: %d operations reversed", undoneCount));
      }
      if (skippedCount > 0) {
        result.append(String.format(", %d skipped", skippedCount));
      }
      if (!outOfRoots.isEmpty()) {
        result.append(
            String.format(
                ", %d outside the indexed root folders — not deleted: %s",
                outOfRoots.size(),
                outOfRoots.stream().map(Path::toString).collect(Collectors.joining(", "))));
      }
      if (!contentChanged.isEmpty()) {
        result.append(
            String.format(
                ", %d no longer hold the content the agent copied — not deleted: %s",
                contentChanged.size(),
                contentChanged.stream().map(Path::toString).collect(Collectors.joining(", "))));
      }
      if (!unverifiable.isEmpty()) {
        result.append(
            String.format(
                ", %d could not be verified against the operation log — not deleted, remove them"
                    + " yourself if they are still unwanted: %s",
                unverifiable.size(),
                unverifiable.stream().map(Path::toString).collect(Collectors.joining(", "))));
      }
      // Tempdoc 577 §2.14 Root III (#16) — surface the conflict-skipped targets so the user knows
      // exactly what was NOT reverted (and why), instead of a silent partial undo.
      if (!conflicted.isEmpty()) {
        result.append(
            String.format(
                ", %d changed since the agent acted — not reverted (review before undo): %s",
                conflicted.size(),
                conflicted.stream().map(Path::toString).collect(Collectors.joining(", "))));
      }
      result.append(".");

      return OperationResult.success(result.toString());

    } catch (Exception e) {
      LOG.error("Undo failed for batch {}", executionId, e);
      return OperationResult.failure("Undo error: " + e.getMessage());
    }
  }

  private boolean isDirectoryEmpty(Path dir) throws IOException {
    try (var entries = Files.list(dir)) {
      return entries.findFirst().isEmpty();
    }
  }

  /**
   * Tempdoc 577 §2.14 Root III (#16) — the conflict-detection predicate: true iff {@code target}
   * exists and was modified AFTER the agent's recorded action time (i.e. the user changed it since,
   * so reverting would destroy that change). A small tolerance absorbs the gap between the
   * filesystem write and the log's {@code Instant.now()} record, avoiding false positives. A
   * missing target or unknown baseline is NOT a conflict (a different skip path handles those).
   *
   * <p>Tempdoc 875 §C.6 — a DIRECTORY target is walked, not stat'ed: a directory's own mtime tracks
   * entry add/remove only, so an edit to a file nested inside a copied tree left the tree looking
   * untouched and the undo deleted it recursively.
   */
  private boolean modifiedSince(Path target, Instant actionTime) {
    if (actionTime == null) return false;
    // Tempdoc 877 §2.x owns the tolerance value (one authority per fact); tempdoc 875 §C.6 owns
    // walking a directory target rather than stat'ing it.
    Instant threshold =
        actionTime.plus(
            Duration.ofMillis(io.justsearch.agent.AgentTimeouts.fileOpConflictToleranceMs()));
    try {
      if (!Files.exists(target)) return false;
      if (Files.isDirectory(target)) {
        return directoryModifiedSince(target, threshold);
      }
      return Files.getLastModifiedTime(target).toInstant().isAfter(threshold);
    } catch (IOException e) {
      return false; // cannot determine ⇒ do not block the undo
    }
  }

  /**
   * True iff {@code dir} itself or any entry beneath it was modified after {@code threshold}. Walks
   * with early exit — the first newer entry terminates the walk, so a large tree costs only as much
   * as it takes to find one conflict.
   */
  private boolean directoryModifiedSince(Path dir, Instant threshold) {
    var newerFound = new AtomicBoolean(false);
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
              return recordIfNewer(attrs, threshold, newerFound);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              return recordIfNewer(attrs, threshold, newerFound);
            }
          });
    } catch (IOException e) {
      // WHY the asymmetry with the non-existence case above: there, "cannot determine" costs
      // nothing (there is no file to lose). Here the action being guarded is a RECURSIVE DELETE,
      // so an unreadable subtree means we cannot clear the tree for deletion — the safe side is to
      // treat the unknown as a conflict and leave the tree alone.
      LOG.warn("Cannot verify whether {} changed since the agent acted; treating as changed", dir, e);
      return true;
    }
    return newerFound.get();
  }

  private static FileVisitResult recordIfNewer(
      BasicFileAttributes attrs, Instant threshold, AtomicBoolean newerFound) {
    if (attrs.lastModifiedTime().toInstant().isAfter(threshold)) {
      newerFound.set(true);
      return FileVisitResult.TERMINATE;
    }
    return FileVisitResult.CONTINUE;
  }

  /**
   * Parse the agent-supplied operations. Agent tool arguments are UNTRUSTED input
   * (§32.9 — "treat the LLM as an untrusted client"): a missing/misspelled field must
   * produce a clear, self-correcting validation error, never an NPE. Mirrors the
   * defensive idiom this class already uses for `explanation`/`conflict_strategy`.
   */
  private List<FileOperation> parseOperations(JsonNode opsNode, EngineContext engineContext) {
    List<FileOperation> operations = new ArrayList<>();
    int index = 0;
    for (JsonNode opNode : opsNode) {
      String opStr = ToolArgs.stringArg(opNode, "op");
      if (opStr == null || opStr.isBlank()) {
        throw new OperationArgException("operation " + index + ": missing required field 'op'");
      }
      FileOperation.OpType opType;
      try {
        opType = FileOperation.OpType.valueOf(opStr.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new OperationArgException(
            "operation " + index + ": unknown op '" + opStr + "' (expected one of MOVE, RENAME, MKDIR, COPY)");
      }

      String sourceStr = resolveAgainstRoots(ToolArgs.stringArg(opNode, "source"), engineContext);
      Path source = (sourceStr != null && !sourceStr.isEmpty()) ? Path.of(sourceStr) : null;

      // Accept `path` as an alias for the schema's canonical `destination`: smaller
      // local models routinely emit `path` (notably for mkdir).
      String destStr = ToolArgs.stringArg(opNode, "destination");
      if (destStr == null || destStr.isBlank()) {
        destStr = ToolArgs.stringArg(opNode, "path");
      }
      if (destStr == null || destStr.isBlank()) {
        throw new OperationArgException(
            "operation " + index + " (" + opStr + "): missing required field 'destination'");
      }

      operations.add(new FileOperation(opType, source, Path.of(resolveAgainstRoots(destStr, engineContext))));
      index++;
    }
    return operations;
  }

  /**
   * Tempdoc 877 §2.7 — the pre-pass the other four tools already had, and this one's absence was the
   * whole of finding 7: {@code core_browse_folders} emits ROOT-RELATIVE paths (a measured 227 §A.6
   * decision), and a model echoing one back as a destination hit a bare {@code Path.of} here and was
   * refused with {@code DEST_NOT_SANDBOXED} for naming a file the browse tool had just shown it.
   *
   * <p>Absolute in, absolute out. A root-relative path resolves. One that matches NO root is passed
   * through unchanged so {@code FileOperationExecutor}'s sandbox check still rejects it with its own
   * message — degrade-open on resolution, fail-closed on sandboxing, the split the other tools use.
   */
  private String resolveAgainstRoots(String raw, EngineContext engineContext) {
    if (raw == null || raw.isBlank() || AgentToolPaths.looksAbsolute(raw)) {
      return raw;
    }
    String resolved = rootsView.resolveRelative(raw, engineContext);
    return resolved == null ? raw : resolved;
  }

  /**
   * Thrown by {@link #parseOperations} for malformed/missing operation fields. A dedicated
   * type (not a bare IllegalArgumentException) so {@link #execute} can return a clean,
   * agent-facing validation message for THESE errors only — without also swallowing an
   * IllegalArgumentException raised deeper in validation/execution (which must stay a
   * logged "Execution error").
   */
  private static final class OperationArgException extends RuntimeException {
    OperationArgException(String message) {
      super(message);
    }
  }

  /**
   * One direct undo step. {@code recordedDigest} is the content identity the forward COPY wrote to
   * the journal, or null for MKDIR (which needs none) and for COPY entries from a pre-v2 journal.
   */
  private record UndoAction(FileOperation.OpType opType, Path path, String recordedDigest) {}

  /** Callback for updating the search index after file MOVE/RENAME operations. */
  @FunctionalInterface
  public interface IndexUpdateCallback {
    /**
     * Updates document paths in the search index.
     *
     * @param pathMappings map of old absolute path to new absolute path
     * @return number of parent documents updated
     */
    int updatePaths(Map<Path, Path> pathMappings, EngineContext engineContext);
  }
}
