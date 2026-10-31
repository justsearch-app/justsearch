/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkVectorPresence;
import org.junit.jupiter.api.Test;

class RagChunkCoverageGateTest {
  @Test
  void unavailableCoverageWithholdsHybridAndAFreshReadRestoresIt() throws Exception {
    IndexCountOps counts = mock(IndexCountOps.class);
    when(counts.queryChunkVectorPresenceCount()).thenReturn(
        new ChunkVectorPresence(-1, -1), new ChunkVectorPresence(10000, 9499),
        new ChunkVectorPresence(10000, 9500), new ChunkVectorPresence(10000, 9501));
    var ops = new RagContextOps(null, counts, null, null, null);
    var gate = RagContextOps.class.getDeclaredMethod("isChunkVectorCoverageReady");
    gate.setAccessible(true);
    assertEquals(false, gate.invoke(ops));
    assertEquals(false, gate.invoke(ops));
    assertEquals(true, gate.invoke(ops));
    assertEquals(true, gate.invoke(ops));
    verify(counts, times(4)).queryChunkVectorPresenceCount();
  }
}
