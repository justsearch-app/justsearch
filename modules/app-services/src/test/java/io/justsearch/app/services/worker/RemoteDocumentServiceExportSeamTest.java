/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RemoteDocumentService production-extracted export seam")
final class RemoteDocumentServiceExportSeamTest {

  @Test
  @DisplayName("maps persisted extraction provenance from the Worker response")
  void mapsExtractionProvenance() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.fetchDocumentSlice("doc-1", 0, 200_000))
        .thenReturn(
            FetchDocumentSliceResponse.newBuilder()
                .setDocId("doc-1")
                .setFound(true)
                .setContent("complete text")
                .setNextOffsetChars(13)
                .setTotalChars(13)
                .setExtractionStatus("SUCCESS_FULL")
                .setContentTruncated(false)
                .setExtractionPolicyId("policy-v3")
                .setExtractionParserId("tika-3.2")
                .setSourceSha256("a".repeat(64))
                .build());

    var slice =
        new RemoteDocumentService(() -> client)
            .fetchSlice("doc-1", 0, 200_000)
            .toCompletableFuture()
            .join();

    assertEquals("SUCCESS_FULL", slice.extractionStatus());
    assertEquals(Boolean.FALSE, slice.contentTruncated());
    assertEquals("policy-v3", slice.extractionPolicyId());
    assertEquals("tika-3.2", slice.extractionParserId());
    assertEquals("a".repeat(64), slice.sourceSha256());
    assertEquals(13, slice.totalChars());
  }

  @Test
  @DisplayName("preserves unknown truncation when a legacy Worker omits the optional field")
  void preservesUnknownContentTruncation() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.fetchDocumentSlice("legacy", 0, 20_000))
        .thenReturn(
            FetchDocumentSliceResponse.newBuilder()
                .setDocId("legacy")
                .setFound(true)
                .setContent("text")
                .setNextOffsetChars(4)
                .setTotalChars(4)
                .build());

    var slice =
        new RemoteDocumentService(() -> client)
            .fetchSlice("legacy", 0, 20_000)
            .toCompletableFuture()
            .join();

    assertNull(slice.contentTruncated());
    assertFalse(slice.truncated());
  }

  @Test
  @DisplayName("maps the existing Worker parent-ID RPC without folder browsing")
  void mapsDocumentIdPage() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.listAllDocumentIds(0, 50_000))
        .thenReturn(
            ListAllDocumentIdsResponse.newBuilder()
                .addAllDocIds(List.of("C:/root/a.txt", "C:/root/nested/b.txt"))
                .setTotalCount(2)
                .setTookMs(7)
                .build());

    var page =
        new RemoteDocumentService(() -> client)
            .listAllDocumentIds(0, 50_000)
            .toCompletableFuture()
            .join();

    assertEquals(List.of("C:/root/a.txt", "C:/root/nested/b.txt"), page.docIds());
    assertEquals(2, page.totalCount());
    assertEquals(7, page.tookMs());
    assertTrue(page.docIds().get(1).contains("nested"));
  }
}
