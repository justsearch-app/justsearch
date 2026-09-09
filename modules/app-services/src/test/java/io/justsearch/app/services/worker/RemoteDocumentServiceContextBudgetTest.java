/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The 200k-character cap on the fallback context, including header and separator overhead.
 *
 * <p>Lane F stage A item A10 moved the harness off a real Netty server and the memory-mapped signal
 * bus onto {@link TestKnowledgeClient}. The property survives the transport untouched: the budget
 * is arithmetic {@code RemoteDocumentService} does on the documents it got back, and the only thing
 * the wire contributed was a way to make {@code retrieveContext} fail so the fallback runs. That
 * failure is now thrown directly — {@code RemoteDocumentService} catches {@code Exception} and
 * falls back, so the arm reached is the same one, for the same reason.
 */
@DisplayName("RemoteDocumentService fallback context budgeting")
final class RemoteDocumentServiceContextBudgetTest {

  private static final int MAX_CONTEXT_CHARS = 200_000;

  private KnowledgeClient client;
  private String prevDataDir;
  private Path tempDataDir;

  @BeforeEach
  void setUp() throws Exception {
    prevDataDir = System.getProperty("justsearch.data.dir");
    tempDataDir = Files.createTempDirectory("justsearch-scale002-test-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    client =
        new TestKnowledgeClient(
            new FailingRetrieveContextCalls(
                Map.of(
                    "doc-1", "A".repeat(100_000),
                    "doc-2", "B".repeat(150_000))));
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
      client = null;
    }
    if (prevDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", prevDataDir);
    }
  }

  @Test
  @DisplayName("caps fallback context to 200k chars including header+separator overhead")
  void capsFallbackContextIncludingOverhead() throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(() -> client);

    Set<String> docIds = new LinkedHashSet<>();
    docIds.add("doc-1");
    docIds.add("doc-2");

    var result =
        service
            .retrieveContextWithMeta("what is this?", docIds, 5, io.justsearch.app.services.TestEngineContexts.internal())
            .toCompletableFuture()
            // Generous timeout; test validates correctness (200K cap), not latency.
            .get(6, TimeUnit.SECONDS);

    assertFalse(
        result.usedChunks(), "Should indicate fallback to full docs when retrieveContext fails");
    assertEquals(0, result.chunksUsed(), "Fallback should report chunksUsed=0");
    assertEquals(2, result.docsUsed(), "Should include both docs (second truncated to fit cap)");

    String context = result.context();
    assertEquals(MAX_CONTEXT_CHARS, context.length(), "Context must be strictly capped to maxChars");

    assertTrue(context.startsWith("[1] doc-1\n"), "Should include numbered header for doc-1");
    assertTrue(context.contains(ContextBudgeter.SECTION_SEPARATOR), "Should include section separator");
    assertTrue(context.contains("[2] doc-2\n"), "Should include numbered header for doc-2");
    assertTrue(context.endsWith("B"), "Final truncated content should end with doc-2 content");

    // Verify the truncation math is strict and counts overhead.
    String header1 = ContextBudgeter.sectionHeader(1, "doc-1");
    String header2 = ContextBudgeter.sectionHeader(2, "doc-2");
    String sep = ContextBudgeter.SECTION_SEPARATOR;
    String a = "A".repeat(100_000);
    String b = "B".repeat(150_000);
    int remainingForB =
        MAX_CONTEXT_CHARS - (header1.length() + a.length() + sep.length() + header2.length());
    String expected = header1 + a + sep + header2 + b.substring(0, remainingForB);
    assertEquals(expected, context, "Context should be truncated exactly to budget including overhead");
  }

  private static final class FailingRetrieveContextCalls extends TestKnowledgeClient.SearchCalls {
    private final Map<String, String> docs;

    private FailingRetrieveContextCalls(Map<String, String> docs) {
      this.docs = docs;
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      throw new KnowledgeClientException(
          KnowledgeClientException.Status.UNAVAILABLE, "forced failure for fallback test");
    }

    @Override
    public FetchDocumentsResponse fetchDocuments(FetchDocumentsRequest request) {
      FetchDocumentsResponse.Builder out = FetchDocumentsResponse.newBuilder();
      for (String docId : request.getDocIdsList()) {
        String content = docs.getOrDefault(docId, "");
        out.addDocuments(
            DocumentContent.newBuilder().setDocId(docId).setContent(content).setFound(true).build());
      }
      return out.build();
    }
  }
}
