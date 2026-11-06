/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HistoryRetrievalFallbackTest {
  @TempDir Path directory;

  @Test void failingRetrievalCannotExposeTaggedOrAliasedHistoryThroughSuccessfulFetch() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    Path history = Files.createDirectories(data.resolve("agent-history"));
    Files.writeString(history.resolve("run.md"), "privatealiasmarker");
    Path alias = Files.createSymbolicLink(directory.resolve("alias"), data);
    Path ordinary = Files.writeString(directory.resolve("notes.txt"), "publicmarker");
    var previous = ConfigStore.globalOrNull();
    var configured = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of("justsearch.data.dir", data.toString())));
    ConfigStore.setGlobal(configured);
    try {
      var client = mock(KnowledgeClient.class);
      when(client.retrieveContext(any(RetrieveContextParams.class), any())).thenThrow(new IllegalStateException("retrieval unavailable"));
      when(client.retrieveContext(anyString(), anySet(), anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("retrieval unavailable"));
      var response = FetchDocumentsResponse.newBuilder()
          .addDocuments(DocumentContent.newBuilder().setDocId("opaque-history").setFound(true)
              .setContent("privatetaggedmarker").putMetadata("collection", "agent-history"))
          .addDocuments(DocumentContent.newBuilder().setDocId(alias.resolve("agent-history/run.md").toString())
              .setFound(true).setContent("privatealiasmarker"))
          .addDocuments(DocumentContent.newBuilder().setDocId(ordinary.toString()).setFound(true).setContent("publicmarker"))
          .build();
      when(client.fetchDocuments(anyList(), any())).thenReturn(response);
      var documents = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
      Set<String> ids = new LinkedHashSet<>(response.getDocumentsList().stream().map(DocumentContent::getDocId).toList());
      for (boolean dangling : List.of(false, true)) {
        if (dangling) Files.move(data, directory.resolve("offline-data"));
        var params = RetrieveContextParams.of("question", 5, 4096, ids, List.of(), List.of());
        for (var result : List.of(
            documents.retrieveContext(params, TestEngineContexts.internal()).toCompletableFuture().join(),
            documents.retrieveContextWithMeta("question", ids, 5, 4096, TestEngineContexts.internal()).toCompletableFuture().join())) {
          assertFalse(result.context().contains("private"), result.toString());
          assertTrue(result.context().contains("publicmarker"), result.toString());
          assertEquals(1, result.docsUsed());
        }
      }
      verify(client, atLeast(4)).fetchDocuments(anyList(), any());
    } finally { ConfigStore.restoreGlobal(configured, previous); }
  }
}
