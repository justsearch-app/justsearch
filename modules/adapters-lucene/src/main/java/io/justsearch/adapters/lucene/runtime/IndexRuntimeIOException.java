/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.LockObtainFailedException;

/**
 * Categorized runtime exception used by the Lucene runtime to surface IO failures upstream without
 * flattening them into IllegalStateException.
 */
public final class IndexRuntimeIOException extends RuntimeException {
  public enum Reason {
    DISK_IO,
    DISK_FULL,
    LOCKED,
    SCHEMA_MISMATCH,
    CORRUPT_INDEX,
    CONFIGURATION,
    BACKPRESSURE,
    DRAINING
  }

  private final Reason reason;
  private final boolean indexStorageFailure;

  public IndexRuntimeIOException(Reason reason, String message, Throwable cause) {
    this(reason, message, cause, false);
  }

  private IndexRuntimeIOException(Reason reason, String message, Throwable cause,
      boolean indexStorageFailure) {
    super(message, cause);
    this.reason = reason == null ? Reason.DISK_IO : reason;
    this.indexStorageFailure = indexStorageFailure;
  }

  public Reason reason() {
    return reason;
  }

  /** Preserve index-storage provenance without treating document/analyzer IO as an outage. */
  public static IndexRuntimeIOException fromIndexOperation(
      IOException cause, String message, Path indexDirectory) {
    boolean storage = originatesInIndexStorage(cause, indexDirectory);
    // Unproven/document IO must not acquire a shared-storage reason from its message.
    Reason reason = storage ? LuceneRuntimeUtils.classifyIOException(cause) : Reason.DISK_IO;
    return new IndexRuntimeIOException(reason, message, cause, storage);
  }

  static IndexRuntimeIOException fromIndexOperation(
      IOException cause, String message, RuntimeSession session) {
    Path directory = session.indexPath;
    LifecycleSnapshot snapshot = session.snapshot;
    if (snapshot != null && snapshot.directory() != null
        && FilterDirectory.unwrap(snapshot.directory()) instanceof FSDirectory filesystem) {
      // FSDirectory retains its canonical path, including when the configured root is a symlink.
      directory = filesystem.getDirectory();
    }
    return fromIndexOperation(cause, message, directory);
  }

  /** Opening components consumes no documents: any IO there belongs to the shared index. */
  static IndexRuntimeIOException fromIndexOpen(IOException cause, String message) {
    return new IndexRuntimeIOException(LuceneRuntimeUtils.classifyIOException(cause), message, cause, true);
  }

  public boolean isIndexStorageFailure() {
    return indexStorageFailure;
  }

  private static boolean originatesInIndexStorage(Throwable failure, Path indexDirectory) {
    boolean directoryFrame = false;
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
      if (cause instanceof FileSystemException filesystem && filesystem.getFile() != null) {
        // An explicit outside path remains document-specific even below a Lucene call.
        if (indexDirectory == null) return false;
        try {
          Path file = Path.of(filesystem.getFile());
          return file.isAbsolute() && file.normalize()
              .startsWith(indexDirectory.toAbsolutePath().normalize());
        } catch (InvalidPathException invalidPath) {
          return false;
        }
      }
      directoryFrame |= cause instanceof CorruptIndexException
          || cause instanceof LockObtainFailedException;
      for (StackTraceElement frame : cause.getStackTrace()) {
        // Plain IOExceptions have no path. Lucene storage frames prove Directory/Input/Output
        // provenance; IndexWriter alone does not, since it also invokes document token streams.
        directoryFrame |= frame.getClassName().startsWith("org.apache.lucene.store.");
      }
    }
    return directoryFrame;
  }
}
