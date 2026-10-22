/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.DeleteByIdRequest;
import org.junit.jupiter.api.Test;

class HistoryDeleteAcknowledgementTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

  @Test
  void settledDeleteRefusesCutoverInsteadOfAcknowledgingABufferedDelete() throws Exception {
    java.nio.file.Files.writeString(temp.resolve("state.json"), """
        {"format_version":2,"active_generation":"g-active","building_generation":"g-building",
        "previous_generation":null,"migration_state":"SWITCHING","migration_paused":false,
        "pause_reason":null,"paused_at_ms":null,"updated_at_ms":1}
        """);
    var queue = mock(JobQueue.class, withSettings().extraInterfaces(
        io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.class));
    when(((io.justsearch.indexerworker.queue.SwitchBufferCapableQueue) queue)
        .putSwitchBufferForGeneration(any(), anyString(), anyString(), anyString())).thenReturn(true);
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        temp, temp.resolve("indices/g-building"), runtime, runtime, null, 0L);
    // Field 2 = require_settled. Parsing the wire bytes keeps this check runnable against the
    // prior generated protobuf too: there it is an unknown field and the old service ignores it.
    var fields = com.google.protobuf.UnknownFieldSet.newBuilder().addField(2,
        com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
    var wire = DeleteByIdRequest.newBuilder().setDocId("/history/run.md").setUnknownFields(fields).build().toByteArray();
    var request = DeleteByIdRequest.parseFrom(wire);
    assertThrows(WorkerServiceException.class, () -> service.deleteById(request, CallContext.none()));
    verify(queue, never()).deleteByExactPath(anyString());
  }

  @Test
  void queueCleanupFailureCannotAcknowledgeDeletion() {
    var queue = mock(JobQueue.class);
    when(queue.deleteByExactPath(anyString())).thenReturn(-1);
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
    var reply = service.deleteById(DeleteByIdRequest.newBuilder().setDocId("/history/run.md").build(), CallContext.none());
    assertFalse(reply.getSuccess(), "a surviving ingest job can recreate the deleted transcript's index document");
    verify(queue).deleteByExactPath(anyString());
  }
}
