/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.grpc.GrpcMessageLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 885 item 6 [R6b] — the FetchDocuments pager's own arithmetic and ordering. */
@DisplayName("BoundedDocumentFetch")
final class BoundedDocumentFetchTest {

  /**
   * Review S8. Every other assertion in this class is <em>relative</em> — the worst case fits the
   * budget, the budget fits the ceiling — so all of them survive the budget changing, including
   * changing to something far too small or far too large. The per-call result-size bound is one of
   * the four operation contracts design §6 requires to survive the collapse of the transport, and
   * after item A10 the constant it is derived from ({@code GrpcMessageLimits}) has no transport
   * behind it at all: it becomes a number in a class named after something that no longer exists,
   * which is exactly the kind of value that gets "tidied" later. So the value itself is pinned
   * absolutely here. Changing it should require editing this line and saying why.
   */
  @Test
  @DisplayName("the default byte budget is 8 MiB")
  void theDefaultBudgetIsPinnedAbsolutely() {
    assertEquals(
        8L * 1024 * 1024,
        BoundedDocumentFetch.DEFAULT_BYTE_BUDGET,
        "the per-call result-size bound must not drift with a constant borrowed from the deleted"
            + " transport");
  }

  @Test
  @DisplayName("the default page size keeps the worst case well under the transport ceiling")
  void defaultPageSizeStaysUnderTheCeiling() {
    int pageSize = BoundedDocumentFetch.maxDocsPerRequest(BoundedDocumentFetch.DEFAULT_BYTE_BUDGET);
    assertTrue(pageSize >= 1, "a page must carry at least one document");
    long worstCase = pageSize * BoundedDocumentFetch.worstCaseBytesPerDocument();
    assertTrue(
        worstCase <= BoundedDocumentFetch.DEFAULT_BYTE_BUDGET,
        "page worst case " + worstCase + " must fit the budget");
    assertTrue(
        worstCase < GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES,
        "page worst case " + worstCase + " must fit the transport ceiling");
    // The pre-fix batch size, proved unsafe by the same arithmetic.
    assertTrue(
        pageSize < 50,
        "50 maximal documents were the defect; the page size must be smaller than that");
  }

  @Test
  @DisplayName("a budget smaller than one document still yields a page of one, never zero")
  void tinyBudgetStillMakesProgress() {
    assertEquals(1, BoundedDocumentFetch.maxDocsPerRequest(1L));
    assertEquals(1, BoundedDocumentFetch.maxDocsPerRequest(0L));
  }

  @Test
  @DisplayName("pages are concatenated in request order with nothing lost or duplicated")
  void pagingPreservesOrderAndCompleteness() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 47; i++) {
      ids.add("doc-" + i);
    }
    List<List<String>> requests = new ArrayList<>();
    // A budget that fits exactly 5 documents per request.
    long budget = 5 * BoundedDocumentFetch.worstCaseBytesPerDocument();

    FetchDocumentsResponse merged =
        BoundedDocumentFetch.fetchAll(
            page -> {
              requests.add(List.copyOf(page));
              FetchDocumentsResponse.Builder b = FetchDocumentsResponse.newBuilder();
              page.forEach(
                  id -> b.addDocuments(DocumentContent.newBuilder().setDocId(id).setFound(true)));
              return b.build();
            },
            ids,
            budget);

    assertEquals(10, requests.size(), "47 ids at 5 per request is 10 requests");
    requests.forEach(r -> assertTrue(r.size() <= 5, "no request may exceed the page size"));
    assertEquals(47, merged.getDocumentsCount());
    for (int i = 0; i < 47; i++) {
      assertEquals("doc-" + i, merged.getDocuments(i).getDocId(), "order is preserved at " + i);
    }
  }

  @Test
  @DisplayName("an empty or null id list performs no RPC")
  void emptyInputPerformsNoRpc() {
    int[] calls = {0};
    assertEquals(
        0,
        BoundedDocumentFetch.fetchAll(
                page -> {
                  calls[0]++;
                  return FetchDocumentsResponse.getDefaultInstance();
                },
                List.of())
            .getDocumentsCount());
    assertEquals(
        0,
        BoundedDocumentFetch.fetchAll(
                page -> {
                  calls[0]++;
                  return FetchDocumentsResponse.getDefaultInstance();
                },
                null)
            .getDocumentsCount());
    assertEquals(0, calls[0], "no RPC for an empty batch");
  }
}
