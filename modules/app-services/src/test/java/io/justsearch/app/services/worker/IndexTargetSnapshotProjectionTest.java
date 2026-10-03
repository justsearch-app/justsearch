/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.operations.IndexTargetSnapshot;
import org.junit.jupiter.api.Test;

final class IndexTargetSnapshotProjectionTest {
  @Test
  void projectsTheWorkersOpaqueTargetSnapshotThroughTheStandardIngestExecutor() throws Exception {
    String inputs = "{\"physical_shape\":\"fixture\"}";
    String fingerprint = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(inputs.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    IndexTargetSnapshot expected = new IndexTargetSnapshot(fingerprint, inputs);
    IngestServiceCalls calls = mock(IngestServiceCalls.class);
    when(calls.captureIndexTarget()).thenReturn(expected);

    try (var client = new TestKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), null, calls, null)) {
      assertEquals(expected, client.captureIndexTarget(
          io.justsearch.app.services.TestEngineContexts.durableInternal()));
      verify(calls).captureIndexTarget();
    }
  }

}
