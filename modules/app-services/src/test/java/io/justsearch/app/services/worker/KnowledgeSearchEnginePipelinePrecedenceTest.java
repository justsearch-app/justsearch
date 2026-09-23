/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class KnowledgeSearchEnginePipelinePrecedenceTest {

  private ConfigStore previousConfig;

  @BeforeEach
  void installConfiguration() {
    previousConfig = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfiguration() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void requestPipelineThenModeThenCapabilityAutoDetermineActualWorkerRequest() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var publicationLock = ConfigStore.global().publicationLock();
    KnowledgeServerBootstrap.ClientLease lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(publicationLock);
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    List<SearchRequest> sent = new ArrayList<>();
    when(client.search(any(SearchRequest.class), any()))
        .thenAnswer(
            invocation -> {
              sent.add(invocation.getArgument(0));
              return SearchResponse.getDefaultInstance();
            });
    KnowledgeSearchEngine engine =
        new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class),
            io.justsearch.app.api.OnlineAiService.unavailable(), null, ConfigStore.global());

    PipelineConfig explicit =
        new PipelineConfig(false, true, true, "cc", false, false, 7, false, false);
    engine.search(request("explicit", "text", explicit), TestEngineContexts.internal());
    engine.search(request("mode", "text", null), TestEngineContexts.internal());
    engine.search(request("auto", null, null), TestEngineContexts.internal());

    assertEquals(3, sent.size(), "each owner-level search must issue one Worker request");

    io.justsearch.ipc.PipelineConfig explicitWire = sent.get(0).getPipeline();
    assertEquals("cc", explicitWire.getFusionAlgorithm());
    assertTrue(explicitWire.getDenseEnabled());
    assertTrue(explicitWire.getSpladeEnabled());
    assertFalse(explicitWire.getSparseEnabled());
    assertFalse(explicitWire.getDenseAuto(), "an explicit pipeline overrides the conflicting mode");

    io.justsearch.ipc.PipelineConfig modeWire = sent.get(1).getPipeline();
    assertTrue(modeWire.getSparseEnabled());
    assertFalse(modeWire.getDenseEnabled());
    assertFalse(modeWire.getSpladeEnabled());
    assertEquals("none", modeWire.getFusionAlgorithm());
    assertFalse(modeWire.getDenseAuto(), "an explicit text mode selects the text preset");

    io.justsearch.ipc.PipelineConfig autoWire = sent.get(2).getPipeline();
    assertTrue(autoWire.getSparseEnabled());
    assertFalse(autoWire.getDenseEnabled(), "the Worker resolves the capability-derived dense leg");
    assertFalse(autoWire.getSpladeEnabled());
    assertEquals("rrf", autoWire.getFusionAlgorithm());
    assertTrue(autoWire.getDenseAuto(), "absence of both overrides selects capability-derived AUTO");
  }

  private static KnowledgeSearchRequest request(
      String query, String mode, PipelineConfig pipeline) {
    return new KnowledgeSearchRequest(
        query,
        10,
        mode,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        false,
        false,
        pipeline);
  }
}
