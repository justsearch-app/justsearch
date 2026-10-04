/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and executes file operations with path sandboxing and transaction logging. All source
 * and destination paths must be within user-configured indexed roots.
 */
final class FileOperationExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(FileOperationExecutor.class);
  private static final int MAX_SUFFIX_ATTEMPTS = 1000;

  private final java.util.function.Function<EngineContext, List<Path>> indexedRootsSupplier;
  private final FileOperationsTool.IndexUpdateCallback indexUpdateCallback;
  private final FileOperationLog transactionLog;

  FileOperationExecutor(
      java.util.function.Function<EngineContext, List<Path>> indexedRootsSupplier,
      FileOperationsTool.IndexUpdateCallback indexUpdateCallback,
      FileOperationLog transactionLog) {
    this.indexedRootsSupplier = indexedRootsSupplier;
    this.indexUpdateCallback = indexUpdateCallback;
    this.transactionLog = transactionLog;
  }

  // ===== Validation =====

  /** Validate with default FAIL strategy. */
  ValidationReport validate(List<FileOperation> operations, EngineContext engineContext) {
    return validate(operations, ConflictStrategy.FAIL, engineContext);
  }

  ValidationReport validate(List<FileOperation> operations, ConflictStrategy strategy, EngineContext engineContext) {
    List<Path> roots = indexedRootsSupplier.apply(engineContext);
    List<ValidationResult> results = new ArrayList<>();
    for (int i = 0; i < operations.size(); i++) {
      results.add(validateOperation(operations.get(i), roots, i, strategy));
    }
    return new ValidationReport(results);
  }

  private ValidationResult validateOperation(
      FileOperation op, List<Path> roots, int index, ConflictStrategy strategy) {
    // Source exists check (if required)
    if (op.requiresSource()) {
      if (op.source() == null) {
        return ValidationResult.invalid(index, op, "SOURCE_REQUIRED", "Source path is required");
      }
      if (!Files.exists(op.source())) {
        return ValidationResult.invalid(
            index, op, "SOURCE_MISSING", "Source does not exist: " + op.source());
      }
      if (!isWithinRoots(op.source(), roots)) {
        return ValidationResult.invalid(
            index, op, "SOURCE_NOT_SANDBOXED", "Source is outside indexed roots: " + op.source());
      }
    }

    // Destination checks
    if (op.destination() == null) {
      return ValidationResult.invalid(index, op, "DEST_REQUIRED", "Destination path is required");
    }
    if (!isWithinRoots(op.destination(), roots)) {
      return ValidationResult.invalid(
          index,
          op,
          "DEST_NOT_SANDBOXED",
          "Destination is outside indexed roots: " + op.destination());
    }

    // Destination parent exists (except for MKDIR which creates directories)
    if (op.op() != FileOperation.OpType.MKDIR) {
      Path destParent = op.destination().getParent();
      if (destParent != null && !Files.exists(destParent)) {
        return ValidationResult.invalid(
            index,
            op,
            "DEST_PARENT_MISSING",
            "Destination parent does not exist: " + destParent);
      }
    }

    // Destination conflict check (MKDIR is idempotent, so skip it)
    if (op.op() != FileOperation.OpType.MKDIR && Files.exists(op.destination())) {
      if (strategy == ConflictStrategy.FAIL) {
        return ValidationResult.invalid(
            index, op, "DEST_EXISTS", "Destination already exists: " + op.destination());
      }
      // SKIP and AUTO_SUFFIX handle conflicts at execution time
    }

    return ValidationResult.valid(index, op);
  }

  /**
   * Containment check against the CURRENT indexed roots, for callers outside the validate/execute
   * pipeline — specifically undo's direct COPY reversal, which deletes and must therefore re-prove
   * containment the way the forward operation did (tempdoc 875 §C.6). Reading the live roots is the
   * point: a root the user removed between the operation and the undo makes the target out of
   * bounds. Fails closed — a supplier that throws or returns null, empty roots, or an IOException
   * while canonicalizing all yield false. Reading the roots is inside the guard deliberately: the
   * supplier is the live indexing service, so "the Worker is down" must read as "containment cannot
   * be proven", not as an exception escaping into the undo path.
   */
  boolean isWithinIndexedRoots(Path path, EngineContext engineContext) {
    List<Path> roots;
    try {
      roots = indexedRootsSupplier.apply(engineContext);
    } catch (RuntimeException e) {
      LOG.warn("Indexed-root lookup failed; containment not proven for: {}", path, e);
      return false;
    }
    if (roots == null) {
      LOG.warn("Indexed-root lookup returned null; containment not proven for: {}", path);
      return false;
    }
    return isWithinRoots(path, roots);
  }

  private boolean isWithinRoots(Path path, List<Path> roots) {
    try {
      // For new paths (MKDIR dest, MOVE dest), toRealPath() would fail since the path doesn't exist
      // yet. Resolve the closest existing ancestor instead.
      Path resolved = resolveClosestExistingAncestor(path);
      for (Path root : roots) {
        // Tempdoc 875 §E S4: canonicalize per root and skip the ones that cannot be resolved (a
        // detached network share, an ACL-blocked mount). Letting one such root abort the loop would
        // deny a path that sits squarely inside a later, perfectly good root. Skipping only ever
        // shrinks the accepted set, so it cannot loosen the sandbox.
        Path rootReal;
        try {
          rootReal = root.toRealPath();
        } catch (IOException | RuntimeException e) {
          LOG.warn("Skipping unresolvable indexed root during sandboxing: {}", root);
          continue;
        }
        if (resolved.startsWith(rootReal)) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      LOG.warn("Failed to resolve path for sandboxing: {}", path, e);
      return false;
    }
  }

  private Path resolveClosestExistingAncestor(Path path) throws IOException {
    Path abs = path.toAbsolutePath().normalize();
    if (Files.exists(abs)) {
      return abs.toRealPath();
    }
    // Walk up ALL ancestors to find the first existing one, then re-append the missing segments.
    // This prevents symlink-based sandbox escapes through deeply nested MKDIR paths.
    List<Path> missingSegments = new ArrayList<>();
    Path current = abs;
    while (current != null && !Files.exists(current)) {
      missingSegments.add(current.getFileName());
      current = current.getParent();
    }
    if (current == null) {
      return abs;
    }
    Path resolved = current.toRealPath();
    for (int i = missingSegments.size() - 1; i >= 0; i--) {
      resolved = resolved.resolve(missingSegments.get(i));
    }
    return resolved;
  }

  // ===== Execution =====

  /** Execute with default FAIL strategy. */
  ExecutionReport execute(List<FileOperation> operations, String explanation, EngineContext engineContext) {
    return execute(operations, explanation, ConflictStrategy.FAIL, engineContext);
  }

  ExecutionReport execute(
      List<FileOperation> operations, String explanation, ConflictStrategy strategy, EngineContext engineContext) {
    String batchId = UUID.randomUUID().toString();
    List<ExecutionResult> results = new ArrayList<>();
    Map<Path, Path> pathMappings = new HashMap<>();

    transactionLog.startBatch(batchId, explanation, operations);

    for (int i = 0; i < operations.size(); i++) {
      FileOperation op = operations.get(i);
      try {
        // Handle destination conflicts at execution time for SKIP/AUTO_SUFFIX
        boolean renamed = false;
        if (op.op() != FileOperation.OpType.MKDIR && Files.exists(op.destination())) {
          if (strategy == ConflictStrategy.SKIP) {
            String reason = "Destination already exists: " + op.destination();
            results.add(ExecutionResult.skipped(i, op, reason));
            transactionLog.recordSkip(batchId, i, reason);
            continue;
          } else if (strategy == ConflictStrategy.AUTO_SUFFIX) {
            Path originalDest = op.destination();
            Path resolvedDest = resolveUniqueName(originalDest);
            op = new FileOperation(op.op(), op.source(), resolvedDest);
            renamed = true;
          }
        }

        // Collect per-operation mappings so a failed operation's walk entries don't pollute
        // the batch-level map (e.g., Files.walk succeeds but Files.move fails)
        Map<Path, Path> opMappings = new HashMap<>();
        executeOperation(op, opMappings);
        pathMappings.putAll(opMappings);
        results.add(ExecutionResult.success(i, op));

        // Tempdoc 909 items 7/8 — record WHAT the copy left behind, not just that it happened.
        // Undoing a COPY deletes the destination, and by then it is an ordinary file in the user's
        // folders; without a recorded content identity the undo can only compare mtimes, which any
        // timestamp-preserving edit defeats. Only COPY needs it (see recordSuccess's javadoc).
        String destinationDigest =
            op.op() == FileOperation.OpType.COPY ? FileContentDigest.of(op.destination()) : null;
        if (renamed) {
          transactionLog.recordRename(
              batchId, i, operations.get(i).destination(), op.destination(), destinationDigest);
        } else {
          transactionLog.recordSuccess(batchId, i, destinationDigest);
        }
      } catch (Exception e) {
        LOG.error("File operation {} failed: {}", i, op, e);
        results.add(ExecutionResult.failure(i, op, e.getMessage()));
        transactionLog.recordFailure(batchId, i, e.getMessage());
        break; // fail-fast
      }
    }

    // Update index for MOVE/RENAME operations
    if (!pathMappings.isEmpty()) {
      try {
        int updatedCount = indexUpdateCallback.updatePaths(pathMappings, engineContext);
        LOG.info("Updated {} index entries after file operations", updatedCount);
      } catch (Exception e) {
        LOG.error("Index update failed after file operations (files moved successfully)", e);
        // Don't fail the tool call — files are already moved, index update is best-effort
      }
    }

    transactionLog.finalizeBatch(batchId);
    return new ExecutionReport(batchId, results);
  }

  private void executeOperation(FileOperation op, Map<Path, Path> pathMappings) throws IOException {
    switch (op.op()) {
      case MOVE, RENAME -> {
        // For directories, collect all child file paths BEFORE the move so the index
        // can update each document's path individually (not just the top-level directory)
        boolean isDir = Files.isDirectory(op.source());
        if (isDir) {
          try (var stream = Files.walk(op.source())) {
            stream
                .filter(Files::isRegularFile)
                .forEach(
                    file ->
                        pathMappings.put(
                            file, op.destination().resolve(op.source().relativize(file))));
          }
        }
        try {
          Files.move(op.source(), op.destination(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          LOG.debug(
              "ATOMIC_MOVE not supported for {} -> {}, using copy+delete",
              op.source(),
              op.destination());
          if (isDir) {
            copyDirectory(op.source(), op.destination());
            deleteDirectory(op.source());
          } else {
            Files.copy(op.source(), op.destination(), StandardCopyOption.COPY_ATTRIBUTES);
            Files.delete(op.source());
          }
        }
        if (!isDir) {
          pathMappings.put(op.source(), op.destination());
        }
      }

      case MKDIR -> Files.createDirectories(op.destination());

      case COPY -> {
        if (Files.isDirectory(op.source())) {
          copyDirectory(op.source(), op.destination());
        } else {
          Files.copy(op.source(), op.destination(), StandardCopyOption.COPY_ATTRIBUTES);
        }
        // COPY creates new files — no path mapping needed (they'll be picked up by indexing)
      }
    }
  }

  /**
   * Resolve a unique destination name by appending a numeric suffix. Example: "file.txt" becomes
   * "file (1).txt", "file (2).txt", etc.
   */
  private Path resolveUniqueName(Path dest) throws IOException {
    String fileName = dest.getFileName().toString();
    String baseName;
    String extension;
    int dotIdx = fileName.lastIndexOf('.');
    if (dotIdx > 0) {
      baseName = fileName.substring(0, dotIdx);
      extension = fileName.substring(dotIdx); // includes the dot
    } else {
      baseName = fileName;
      extension = "";
    }
    Path parent = dest.getParent();
    for (int n = 1; n <= MAX_SUFFIX_ATTEMPTS; n++) {
      Path candidate = parent.resolve(baseName + " (" + n + ")" + extension);
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IOException(
        "Could not resolve unique name after " + MAX_SUFFIX_ATTEMPTS + " attempts: " + dest);
  }

  private void copyDirectory(Path source, Path dest) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dest.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file,
                dest.resolve(source.relativize(file)),
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Delete a directory tree. Package-private so undo logic can reuse it. */
  void deleteDirectory(Path dir) throws IOException {
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
            Files.delete(d);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  // ===== Result types =====

  record ValidationResult(
      int index, FileOperation operation, boolean valid, String errorCode, String message) {

    static ValidationResult valid(int index, FileOperation op) {
      return new ValidationResult(index, op, true, null, null);
    }

    static ValidationResult invalid(int index, FileOperation op, String code, String msg) {
      return new ValidationResult(index, op, false, code, msg);
    }
  }

  record ValidationReport(List<ValidationResult> results) {

    boolean allValid() {
      return results.stream().allMatch(ValidationResult::valid);
    }

    String summary() {
      return results.stream()
          .filter(r -> !r.valid())
          .map(r -> String.format("[%d] %s: %s", r.index(), r.errorCode(), r.message()))
          .reduce((a, b) -> a + "; " + b)
          .orElse("No errors");
    }
  }

  record ExecutionResult(
      int index, FileOperation operation, boolean success, boolean skipped, String errorMessage) {

    static ExecutionResult success(int index, FileOperation op) {
      return new ExecutionResult(index, op, true, false, null);
    }

    static ExecutionResult failure(int index, FileOperation op, String error) {
      return new ExecutionResult(index, op, false, false, error);
    }

    static ExecutionResult skipped(int index, FileOperation op, String reason) {
      return new ExecutionResult(index, op, true, true, reason);
    }
  }

  record ExecutionReport(String batchId, List<ExecutionResult> results) {

    int successCount() {
      return (int) results.stream().filter(r -> r.success() && !r.skipped()).count();
    }

    int skippedCount() {
      return (int) results.stream().filter(ExecutionResult::skipped).count();
    }

    int failureCount() {
      return (int) results.stream().filter(r -> !r.success()).count();
    }

    boolean allSucceeded() {
      return results.stream().allMatch(ExecutionResult::success);
    }

    String summary() {
      int skipped = skippedCount();
      if (allSucceeded() && skipped == 0) {
        return String.format("All %d operations completed successfully.", results.size());
      }
      if (allSucceeded() && skipped > 0) {
        return String.format(
            "%d of %d operations completed, %d skipped (destination exists).",
            successCount(), results.size(), skipped);
      }
      return String.format(
          "%d of %d operations succeeded, %d failed, %d skipped.",
          successCount(), results.size(), failureCount(), skipped);
    }
  }
}
