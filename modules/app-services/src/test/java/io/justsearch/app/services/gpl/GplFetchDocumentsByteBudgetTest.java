/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.services.worker.BoundedDocumentFetch;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import io.justsearch.ipc.grpc.GrpcMessageLimits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tempdoc 885 item 6 [R6b] — the {@code FetchDocuments} byte budget, asserted at the caller that
 * motivated it.
 *
 * <p>{@code GplJobCoordinator} pages the corpus at {@code BATCH_SIZE = 50} and used to hand the
 * whole page to one {@code fetchDocuments} call. With every document at the worker's 200 000-char
 * content cap that assembles a reply of up to 28.6 MiB against a 32 MiB transport ceiling. This
 * test drives a real coordinator run over exactly that shape and asserts the property on every
 * request the client actually received — the count-of-ids form of the bound, which is the only form
 * the Head can enforce (it cannot know a document's size before fetching it).
 *
 * <p>Precision note: a test that merely asserted "the run completed" would pass on the pre-fix code
 * too, because a mocked client never enforces the ceiling. The assertion is therefore on the
 * captured request sizes, and it fails on the pre-fix code (one request of 50).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GPL FetchDocuments byte budget (885 item 6 R6b)")
final class GplFetchDocumentsByteBudgetTest {

  private static final int GPL_BATCH_SIZE = 50;
  private static final int WORKER_CONTENT_CAP_CHARS =
      GrpcMessageLimits.MAX_DOCUMENT_CONTENT_CHARS;

  @TempDir Path tempDir;

  @Mock KnowledgeClient knowledgeClient;
  @Mock OnlineAiService onlineAiService;

  private GplTrainingTripleStore tripleStore;
  private GplJobCoordinator coordinator;

  @BeforeEach
  void setUp() {
    tripleStore = new GplTrainingTripleStore(tempDir);
    coordinator = new GplJobCoordinator(() -> knowledgeClient, onlineAiService, false, tripleStore);
  }

  @Test
  @DisplayName("a 50-document batch of 200k-char documents never rides one over-budget request")
  void fiftyMaximalDocumentsAreSplitUnderTheTransportCeiling() throws Exception {
    List<String> docIds = new ArrayList<>();
    for (int i = 0; i < GPL_BATCH_SIZE; i++) {
      docIds.add("doc-" + i);
    }
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addAllDocIds(docIds)
            .setTotalCount(GPL_BATCH_SIZE)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(GPL_BATCH_SIZE).build();
    when(knowledgeClient.listAllDocumentIds(0, GPL_BATCH_SIZE)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(GPL_BATCH_SIZE, GPL_BATCH_SIZE)).thenReturn(emptyPage);

    // Every document is exactly at the worker's cap — the worst case the budget exists for.
    String maximalContent = "x".repeat(WORKER_CONTENT_CAP_CHARS);
    List<List<String>> requests = new ArrayList<>();
    when(knowledgeClient.fetchDocuments(any()))
        .thenAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              List<String> requested = inv.getArgument(0, List.class);
              requests.add(List.copyOf(requested));
              FetchDocumentsResponse.Builder response = FetchDocumentsResponse.newBuilder();
              for (String id : requested) {
                response.addDocuments(
                    DocumentContent.newBuilder()
                        .setDocId(id)
                        .setContent(maximalContent)
                        .setFound(true)
                        .build());
              }
              return response.build();
            });

    when(onlineAiService.isAvailable()).thenReturn(true);
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              @SuppressWarnings("unchecked")
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("what is x");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    assertTrue(coordinator.runAsync());
    assertTrue(
        coordinator.awaitCompletion(60, TimeUnit.SECONDS), "job did not reach a terminal state");
    GplJobStatus status = coordinator.getStatus();
    assertEquals(
        GplJobStatus.Status.COMPLETED, status.status(), "job should complete: " + status.lastError());

    assertFalse(requests.isEmpty(), "the coordinator must have fetched the batch");
    assertTrue(
        requests.size() > 1,
        "50 maximal documents cannot legitimately ride one request; the batch must have been split");
    long ceiling = GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES;
    for (List<String> request : requests) {
      long worstCaseBytes =
          (long) request.size() * WORKER_CONTENT_CAP_CHARS * 3L + (long) request.size() * 8_192L;
      assertTrue(
          worstCaseBytes < ceiling,
          "a request of "
              + request.size()
              + " ids can assemble "
              + worstCaseBytes
              + " bytes, past the "
              + ceiling
              + "-byte transport ceiling");
    }
    // Nothing was lost by the split: every id in the batch was requested exactly once, in order.
    List<String> flattened = new ArrayList<>();
    requests.forEach(flattened::addAll);
    assertEquals(docIds, flattened, "every doc id must be fetched exactly once, in order");
    assertEquals(GPL_BATCH_SIZE, (int) status.processedDocs(), "all 50 documents processed");
  }
}
