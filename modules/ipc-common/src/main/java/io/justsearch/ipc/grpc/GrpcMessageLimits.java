/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

/**
 * The size bounds that used to be the Head-to-Worker channel's, and outlived it as the Engine's
 * document-fetch budget.
 *
 * <p>Both values began as one number read by both ends of a gRPC channel — a drift class tempdoc
 * 882 item 5 named after finding the two ends had disagreed since the first commit (the server
 * advertised 32 MiB while the client never called {@code maxInboundMessageSize}, so grpc-java's
 * 4 MiB default silently capped replies such as {@code FetchDocuments} at roughly 21 full-size
 * documents in one call). Lane F stage A deleted the channel: item A9 the server end, item A10 the
 * client end.
 *
 * <p>The class is kept because the bound was never really about a channel. It is the size at which
 * one {@code FetchDocuments} answer stops being a reasonable unit of work, and
 * {@code BoundedDocumentFetch} still derives its byte budget from it while
 * {@code WorkerSearchService} still trims to the character cap — the same two-ends-one-value shape,
 * now inside one JVM. The name is a fossil of where the number came from; the number is live.
 */
public final class GrpcMessageLimits {
  private GrpcMessageLimits() {}

  /**
   * Max size, in bytes, of a single document-fetch answer. Was the channel's inbound message limit
   * until items A9/A10 deleted both ends of the channel; {@code BoundedDocumentFetch} derives its
   * default byte budget from it and is the reader that remains.
   */
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
