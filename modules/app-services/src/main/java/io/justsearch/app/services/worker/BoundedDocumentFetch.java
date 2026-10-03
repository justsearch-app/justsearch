/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.grpc.GrpcMessageLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tempdoc 885 item 6 [R6b] — the byte budget for {@code FetchDocuments}.
 *
 * <p><b>The defect.</b> {@code FetchDocuments} caps each document's content at 200 000 chars
 * ({@code WorkerSearchService.MAX_CONTENT_CHARS}) but places no bound on the number of documents in
 * one response. {@code GplJobCoordinator} pages the whole corpus 50 doc-ids at a time and hands the
 * page straight to {@code fetchDocuments}, so a page of 50 large documents assembles a reply of up
 * to 50 x 200 000 x 3 bytes = 28.6 MiB against a 32 MiB {@link
 * GrpcMessageLimits#MAX_INBOUND_MESSAGE_BYTES} ceiling — a margin that survives only because
 * documents are usually small. When it fails it fails as a {@code RESOURCE_EXHAUSTED} on the whole
 * page, losing 50 documents at once, not one.
 *
 * <p><b>The fix, and why it is a pager and not a proto field.</b> The batch is split into requests
 * small enough that the WORST case fits, with the caller's list order preserved. No proto change:
 * design decision 4 defers a request-level {@code max_total_bytes} until the lane F decision (a
 * single-JVM engine has no gRPC boundary to bound), and the caller fix does not need one.
 *
 * <p>The bound is a per-document worst case, not a measurement: the Head cannot know a document's
 * size before fetching it, so budgeting on anything observed would still admit a page that
 * overflows. 3 bytes/char is the UTF-8 worst case for the BMP; supplementary-plane code points cost
 * 4 bytes but occupy 2 Java chars, so per-char they are cheaper, and the bound holds.
 */
public final class BoundedDocumentFetch {

  /** The per-document content cap, shared with the producer that trims to it. */
  static final int MAX_CONTENT_CHARS = GrpcMessageLimits.MAX_DOCUMENT_CONTENT_CHARS;

  /** UTF-8 worst case per Java char within the BMP. */
  static final int MAX_UTF8_BYTES_PER_CHAR = 3;

  /** Doc id, the metadata map (title / path / mime) and protobuf framing, generously. */
  static final int PER_DOC_OVERHEAD_BYTES = 8_192;

  /**
   * A quarter of the transport ceiling. The headroom is deliberate: the ceiling is what the channel
   * REJECTS, so budgeting up to it would make every safety margin depend on the worst case being
   * exactly right.
   */
  static final long DEFAULT_BYTE_BUDGET = GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES / 4L;

  private BoundedDocumentFetch() {}

  /** Worst-case bytes one document can contribute to a {@code FetchDocumentsResponse}. */
  static long worstCaseBytesPerDocument() {
    return (long) MAX_CONTENT_CHARS * MAX_UTF8_BYTES_PER_CHAR + PER_DOC_OVERHEAD_BYTES;
  }

  /** How many doc ids may ride one request under {@code byteBudget}. Never less than one. */
  public static int maxDocsPerRequest(long byteBudget) {
    long perDoc = worstCaseBytesPerDocument();
    long fits = byteBudget / perDoc;
    return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, fits));
  }

  /**
   * Fetches every id in {@code docIds}, splitting into as many {@code FetchDocuments} calls as the
   * byte budget requires and concatenating the results in request order.
   *
   * @param rpc the {@code fetchDocuments} call to page (a method reference on the knowledge client)
   */
  public static FetchDocumentsResponse fetchAll(
      Function<List<String>, FetchDocumentsResponse> rpc, List<String> docIds) {
    return fetchAll(rpc, docIds, DEFAULT_BYTE_BUDGET);
  }

  static FetchDocumentsResponse fetchAll(
      Function<List<String>, FetchDocumentsResponse> rpc, List<String> docIds, long byteBudget) {
    FetchDocumentsResponse.Builder merged = FetchDocumentsResponse.newBuilder();
    if (docIds == null || docIds.isEmpty()) {
      return merged.build();
    }
    int pageSize = maxDocsPerRequest(byteBudget);
    for (int from = 0; from < docIds.size(); from += pageSize) {
      int to = Math.min(from + pageSize, docIds.size());
      List<String> page = new ArrayList<>(docIds.subList(from, to));
      FetchDocumentsResponse response = rpc.apply(page);
      if (response != null) {
        merged.addAllDocuments(response.getDocumentsList());
      }
    }
    return merged.build();
  }
}
