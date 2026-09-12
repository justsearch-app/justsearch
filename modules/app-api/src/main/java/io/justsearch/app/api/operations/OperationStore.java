/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.io.IOException;

/**
 * Lifetime of the Engine's durable acceptance store. The outer composition owns this instance;
 * neither the application nor index half may close it before the index drain has finished.
 */
public interface OperationStore extends AutoCloseable {
  /** Recovery performed by this open, including completion of an interrupted quarantine. */
  java.util.Optional<Recovery> recovery();

  record Recovery(java.nio.file.Path preservedDirectory, long historySinceMillis) {}

  @Override
  void close() throws IOException;
}
