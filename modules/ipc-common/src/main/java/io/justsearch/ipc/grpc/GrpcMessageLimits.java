/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

/**
 * Shared gRPC inbound message-size limit for the Head-to-Worker channel.
 *
 * <p>Single value read by BOTH ends of the channel - the Worker's gRPC server
 * ({@code KnowledgeServerGrpcWiring}) and the Head's client
 * ({@code RemoteKnowledgeClient}). Before tempdoc 882 item 5 the two ends had drifted since the
 * first commit: the server advertised 32 MiB while the client never called
 * {@code maxInboundMessageSize}, so grpc-java's 4 MiB default silently capped replies (e.g.
 * {@code FetchDocuments}, reachable at roughly 21 full-size documents in one unary call).
 */
public final class GrpcMessageLimits {
  private GrpcMessageLimits() {}

  /** Max inbound message size, in bytes, for both the Worker gRPC server and the Head client. */
  public static final int MAX_INBOUND_MESSAGE_BYTES = 32 * 1024 * 1024;

  /**
   * Max characters of document content the Worker returns per document on {@code FetchDocuments}
   * (tempdoc 885 item 6 [R6b]).
   *
   * <p>Shared for the same reason as the size limit above: the producer trims to it
   * ({@code WorkerSearchService}) and the Head's pager sizes its batches by it
   * ({@code BoundedDocumentFetch}), so a change on one side that the other did not see would put
   * the byte budget quietly back over the transport ceiling — the exact drift class this class was
   * created for.
   */
  public static final int MAX_DOCUMENT_CONTENT_CHARS = 200_000;
}
