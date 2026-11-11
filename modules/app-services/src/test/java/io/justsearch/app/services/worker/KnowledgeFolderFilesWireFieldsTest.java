/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListFolderFilesResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class KnowledgeFolderFilesWireFieldsTest {
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() {
    previousConfig = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void folderFileApiStripsRerankOnlyFieldsAtBothReadAuthorities(boolean includePrivateData) {
    var wire = ListFolderFilesResponse.newBuilder().setTotalCount(1)
        .addFiles(FolderFileEntry.newBuilder().setDocId("doc")
            .putFields("title", "T").putFields("parent_opening", "opening")).build();
    var client = mock(KnowledgeClient.class);
    when(client.listFolderFiles(anyString(), anyInt(), anyList(), any())).thenReturn(wire);
    when(client.listFolderFiles(anyString(), anyInt(), anyList(), eq(true), any())).thenReturn(wire);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(lease.withClient(any())).thenAnswer(call ->
        ((java.util.function.Function<KnowledgeClient, ?>) call.getArgument(0)).apply(client));
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.captureClient()).thenReturn(lease);
    var adapter = new KnowledgeHttpApiAdapter(server, mock(SearchPerSourceExecutor.class));

    var response = adapter.listFolderFiles(new FolderFilesRequest("folder", 10, List.of()),
        includePrivateData, TestEngineContexts.internal());

    assertEquals(Map.of("title", "T"), response.files().getFirst().fields());
    assertEquals("opening", wire.getFiles(0).getFieldsOrThrow("parent_opening"));
    verify(lease).close();
  }
}
