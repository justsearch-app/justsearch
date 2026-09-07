/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Producer-side mechanism for a {@link DiagnosticChannel}'s emissions.
 *
 * <p>Per slice 448 §4: declared on the channel so consumers can reason about delivery
 * semantics. Only {@link #IN_PROCESS_LOGBACK} is implemented; {@link #EXTERNAL_OBSERVER} is a
 * forward-compat slot — wire shape supports it, nothing produces it.
 *
 * <p>Lane F stage A item A16 removed the third value, {@code WORKER_GRPC_STREAM} ("Worker → Head
 * log forwarding via server-streaming gRPC"). It was a forward-compat slot for a cross-process
 * producer, and there is no second process: A6 composed the index half into this JVM, A9/A10
 * deleted the gRPC server, client and the {@code InfraDiagnosticsService} streaming pattern it
 * named as its precedent, and A11 deleted the Worker process itself. A slot reserved for a
 * transport that was deleted is not forward-compat, it is residue.
 */
public enum ProducerKind {

  /** In-process Logback appender forwarding events to the substrate. V1 implementation. */
  IN_PROCESS_LOGBACK,

  /**
   * Third-party producers (e.g., OTel collector forwarding spans into the substrate).
   * Forward-compat slot for V1.
   */
  EXTERNAL_OBSERVER
}
