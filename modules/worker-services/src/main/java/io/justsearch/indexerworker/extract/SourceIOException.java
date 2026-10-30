/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;

/** Source capture failure, distinct from parser and temporary-storage failures. */
final class SourceIOException extends FileSystemException {
  SourceIOException(Path source, IOException cause) {
    super(source.toAbsolutePath().toString(), null, cause.getMessage());
    initCause(cause);
  }
}
