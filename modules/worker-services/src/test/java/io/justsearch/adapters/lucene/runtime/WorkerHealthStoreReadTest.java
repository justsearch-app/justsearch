/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.services.WorkerHealthService;
import io.justsearch.ipc.HealthCheckRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Uses real count methods; only the underlying searcher read fails. */
final class WorkerHealthStoreReadTest {
  @Test
  void healthRejectsIOExceptionThatBestEffortDocCountReportsAsZero() throws Exception {
    SearcherBridge bridge = mock(SearcherBridge.class);
    IOException unreadable = new IOException("index reader unavailable");
    when(bridge.withSearcher(any())).thenThrow(unreadable);
    IndexCountOps counts = new IndexCountOps(bridge);

    assertEquals(0, counts.docCount(), "best-effort callers retain their zero fallback");
    assertSame(unreadable, assertThrows(IOException.class, counts::docCountOrThrow));
    var health = new WorkerHealthService("test", null, counts, null);
    var response = health.check(HealthCheckRequest.getDefaultInstance());
    assertFalse(response.getServing(), "an unreadable index cannot certify serving health");
    assertTrue(response.getVersion().contains("Lucene: index reader unavailable"));
  }
}
