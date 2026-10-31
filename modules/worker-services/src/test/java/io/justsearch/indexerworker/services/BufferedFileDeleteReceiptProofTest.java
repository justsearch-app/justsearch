/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Receipt volume evidence (#910), with real native folder identity and filesystem probes. */
@EnabledOnOs(OS.LINUX)
@ExtendWith(NoVolumeIdentityExtension.class)
final class BufferedFileDeleteReceiptProofTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void matchingReceiptProvesAbsenceWithoutALedgerAndPreservesRecreatedFiles(boolean changedDevice)
      throws Exception {
    try (var ignored = withReceiptVolume(RootIdentity.capture(root).volume())) {
      String identity = RootIdentity.capture(root).toString();
      if (changedDevice) identity = identity.replaceFirst("dev=[0-9a-f]+", "dev=ffffffff");
      var queue = mock(JobQueue.class);
      when(queue.enqueueReappearedFile(any(), nullable(String.class))).thenReturn(true);
      var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      var service = service(queue, runtime);
      Path file = root.resolve("gone.txt");
      var receipt = receipt(file, identity);

      assertEquals(WorkerIngestService.BufferedDeleteReplay.SETTLED,
          service.replayBufferedFileDelete(runtime, receipt));
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(file.toString());
      Files.writeString(file, "recreated content");
      assertEquals(WorkerIngestService.BufferedDeleteReplay.SETTLED,
          service.replayBufferedFileDelete(runtime, receipt));
      verify(runtime.indexingCoordinator(), times(1)).deleteByIdAndChunks(file.toString());
      verify(queue).enqueueReappearedFile(any(), nullable(String.class));
    }
  }

  @Test
  void mismatchedOrMissingReceiptEvidenceNeverDeletes() throws Exception {
    String volume = RootIdentity.capture(root).volume();
    try (var ignored = withReceiptVolume(volume)) {
      String identity = RootIdentity.capture(root).toString();
      String[] fields = volume.split(":");
      var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      var service = service(mock(JobQueue.class), runtime);
      Path file = root.resolve("missing.txt");
      for (String invalid : java.util.Arrays.asList(
          null,
          identity.replaceFirst("ino=[0-9]+", "ino=0"),
          identity.replace(volume, "null"),
          identity.replace(volume, LinuxRootIdentityLease.encodeVolume("different", "ext4", Path.of("/"))),
          identity.replace(volume, LinuxRootIdentityLease.encodeVolume(fields[1], "different-type", Path.of("/"))),
          identity.replace(volume, LinuxRootIdentityLease.encodeVolume(fields[1], fields[2], Path.of("/different-subtree"))),
          identity.replace(volume, volume + "extra"),
          identity.replace(root.toString(), root.resolve("other").toString()))) {
        assertEquals(WorkerIngestService.BufferedDeleteReplay.UNCERTAIN,
            service.replayBufferedFileDelete(runtime, receipt(file, invalid)), String.valueOf(invalid));
      }
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(any());
    }
    try (var ignoredVolume = NoVolumeIdentityExtension.withVolumeIdentity(false);
         var ignoredReceipt = withReceiptVolume(null)) {
      var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      assertEquals(WorkerIngestService.BufferedDeleteReplay.UNCERTAIN,
          service(mock(JobQueue.class), runtime).replayBufferedFileDelete(runtime,
              receipt(root.resolve("missing.txt"), RootIdentity.capture(root).toString())),
          "equal folder identities without a volume cannot prove absence");
      verify(runtime.indexingCoordinator(), never()).deleteByIdAndChunks(any());
    }
  }

  private SwitchBufferDelete receipt(Path file, String identity) {
    if (identity == null) return new SwitchBufferDelete(file.toString(), SwitchBufferDelete.Origin.LEGACY, null);
    return new SwitchBufferDelete(file.toString(), SwitchBufferDelete.Origin.WATCHER,
        root.toString(), identity, "documents");
  }

  private static WorkerIngestService service(JobQueue queue, RunningRuntime runtime) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  private static MockedStatic<LinuxRootIdentityLease> withReceiptVolume(String volume) {
    return mockStatic(LinuxRootIdentityLease.class, call ->
        call.getMethod().getName().equals("volume") ? volume : call.callRealMethod());
  }
}
