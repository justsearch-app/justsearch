/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.SourceContentHash;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AdmissionSourceWitnessTest {
  @TempDir Path temporary;

  @Test
  void queueAndExtractorUseIdenticalWitnesses() throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "source");
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      var observe = SqliteJobQueue.class.getDeclaredMethod("observeAdmissionSource", JobQueue.EnqueueEntry.class);
      observe.setAccessible(true);
      Object source = observe.invoke(queue, new JobQueue.EnqueueEntry(file, Files.size(file), null, null, temporary));
      assertNotNull(source);
      var witness = source.getClass().getDeclaredMethod("witness");
      witness.setAccessible(true);
      Object queued = witness.invoke(source);
      Class<?> snapshot = Class.forName("io.justsearch.indexerworker.loop.FileFreshnessSnapshot");
      assertEquals(snapshot, queued.getClass(), "admission and extraction must use one witness implementation");
      var capture = snapshot.getDeclaredMethod("capture", Path.class);
      capture.setAccessible(true);
      Object extracted = capture.invoke(null, file);
      // Observation time is bookkeeping rather than source identity.
      for (var component : snapshot.getRecordComponents()) {
        if (component.getName().equals("observedAtMs")) continue;
        assertEquals(component.getAccessor().invoke(extracted), component.getAccessor().invoke(queued),
            component.getName());
      }
      for (String timing : new String[] {"before-hash", "after-hash"}) {
        try (var _ = mockStatic(SourceContentHash.class, call -> {
          boolean admissionHash = call.getMethod().getName().equals("admissionIdentity");
          if (admissionHash && timing.equals("before-hash")) {
            Files.writeString(file, Files.readString(file) + " changed");
          }
          Object result = call.callRealMethod();
          if (admissionHash && timing.equals("after-hash")) {
            Files.writeString(file, Files.readString(file) + " changed");
          }
          return result;
        })) {
          assertNull(observe.invoke(queue,
              new JobQueue.EnqueueEntry(file, Files.size(file), null, null, temporary)),
              "the queue witness must bracket its real hash: " + timing);
        }
      }
    }
  }
}
