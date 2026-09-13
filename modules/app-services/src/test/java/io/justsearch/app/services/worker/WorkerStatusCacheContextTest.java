/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class WorkerStatusCacheContextTest {

  @Test
  void synchronousStatusMissForwardsTheSuppliedCallerContext() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = readyBootstrap(client);
    when(client.getStatus(org.mockito.ArgumentMatchers.any()))
        .thenReturn(StatusResponse.getDefaultInstance());
    WorkerStatusCache cache = new WorkerStatusCache(bootstrap);
    cache.setWorkerCapability(readyCapability());
    EngineContext caller = TestEngineContexts.mcp();

    cache.status(caller);

    ArgumentCaptor<EngineContext> context = ArgumentCaptor.forClass(EngineContext.class);
    verify(client).getStatus(context.capture());
    assertSame(caller, context.getValue());
  }

  @Test
  void synchronousFacetMissForwardsTheSuppliedCallerContext() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = readyBootstrap(client);
    when(client.search(
            org.mockito.ArgumentMatchers.any(SearchRequest.class),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(SearchResponse.getDefaultInstance());
    WorkerStatusCache cache = new WorkerStatusCache(bootstrap);
    cache.setWorkerCapability(readyCapability());
    EngineContext caller = TestEngineContexts.ui();

    cache.refreshFacetSnapshotIfStale(caller);

    ArgumentCaptor<EngineContext> context = ArgumentCaptor.forClass(EngineContext.class);
    verify(client)
        .search(org.mockito.ArgumentMatchers.any(SearchRequest.class), context.capture());
    assertSame(caller, context.getValue());
  }

  private static KnowledgeServerBootstrap readyBootstrap(KnowledgeClient client) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.client()).thenReturn(client);
    return bootstrap;
  }

  private static WorkerCapability readyCapability() {
    WorkerCapability capability = new WorkerCapability();
    capability.transition(CapabilityHealth.READY, null);
    return capability;
  }
}
