/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.tokenizer;

/** A failed factory transfers its partial assembly to the caller's session retirement path. */
public final class AssemblyBuildFailure extends RuntimeException implements AutoCloseable {
  private final AutoCloseable assembly;
  private boolean closed;

  public AssemblyBuildFailure(Throwable cause, AutoCloseable assembly) {
    super("Assembly construction failed; native ownership retained", cause);
    this.assembly = java.util.Objects.requireNonNull(assembly, "assembly");
  }

  public synchronized boolean tokenizerClosed() { return closed; }

  @Override
  public synchronized void close() {
    if (closed) return;
    try {
      assembly.close();
      closed = true;
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Partial assembly retirement failed", failure);
    }
  }
}
