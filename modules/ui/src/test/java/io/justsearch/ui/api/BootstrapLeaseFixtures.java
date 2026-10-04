/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.function.Function;

/** Makes a mocked bootstrap exercise its bound-client operation rather than bypassing the lease. */
public final class BootstrapLeaseFixtures {
  private BootstrapLeaseFixtures() {}

  public static void bind(KnowledgeServerBootstrap bootstrap, KnowledgeClient client) {
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.client()).thenReturn(client);
    when(bootstrap.captureClient()).thenReturn(lease);
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
  }
}
