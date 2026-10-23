/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.Message;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DeletionCompletionReceiptTest {
  @Test void WX2BufferedReceiptsDifferFromCompletedWithoutChangingLegacyFields(@TempDir Path temp)
      throws Exception {
    var generations = new IndexGenerationManager(temp.resolve("index"));
    generations.initializeOrLoad();
    String building = generations.startMigration("receipt").building_generation();
    generations.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    var queue = mock(SwitchBufferCapableQueue.class);
    when(queue.putSwitchBufferForGeneration(eq(building), anyString(), anyString(), anyString()))
        .thenReturn(true);
    var switching = new IngestSwitchBufferOps(mock(JobQueue.class), generations);
    String path = temp.resolve("excluded").toString();
    var prefix = switching.bufferDeleteByPathDuringSwitching(queue, path);
    var file = switching.bufferDeleteByIdDuringSwitching(queue, path);
    verify(queue, times(2)).putSwitchBufferForGeneration(eq(building), anyString(), anyString(), anyString());
    assertTrue(buffered(prefix));
    assertTrue(buffered(file));
    assertEquals(0, prefix.getDeletedJobs());
    assertEquals("", prefix.getError());
    assertTrue(file.getSuccess());
    assertEquals("", file.getError());
    assertFalse(buffered(IngestResponses.deleteByPathResponse(0, "")));
    assertFalse(buffered(IngestResponses.deleteByIdResponse(true, "")));
    assertFalse(buffered(io.justsearch.ipc.DeleteByPathResponse.parseFrom(new byte[0])));
    assertFalse(buffered(io.justsearch.ipc.DeleteByIdResponse.parseFrom(new byte[] {8, 1})));
  }

  private static boolean buffered(Message response) {
    var field = response.getDescriptorForType().findFieldByName("buffered");
    assertNotNull(field, "WX2 requires an atomic buffered receipt from the Worker");
    return (Boolean) response.getField(field);
  }
}
