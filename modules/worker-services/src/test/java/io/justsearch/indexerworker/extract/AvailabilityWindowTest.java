/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
final class AvailabilityWindowTest {
  @Test void failedAvailabilitySpawnDoesNotWriteAWindow(@TempDir Path dir) throws Exception {
    Path invalid = dir.resolve("tesseract.exe"); Files.writeString(invalid, "invalid executable; never launches");
    exercise(invalid, false);
  }
  @Test void realAvailabilityForcesOnlyItsWindowStart() throws Exception {
    exercise(Path.of("F:/scoop/apps/tesseract/current/tesseract.exe"), true);
  }
  private static void exercise(Path exe, boolean launches) throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder"); field.setAccessible(true);
    Object saved = field.get(null); List<String> forced = new ArrayList<>(); List<String> all = new ArrayList<>();
    // Receipts other tests buffered before any recorder attached are replayed into the first one:
    // set them aside so only this check's records reach its sink.
    var queueField = OwnedHelper.class.getDeclaredField("beforeRecorder"); queueField.setAccessible(true);
    @SuppressWarnings("unchecked") var queue = (ArrayDeque<Object>) queueField.get(null);
    var savedQueue = new ArrayDeque<>(queue); queue.clear();
    try {
      OwnedHelper.recordTo(new LedgerSink() {
        @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
          forced.add(type.token()); all.add(type.token());
          if (fields.containsKey("pid")) System.out.println("availability " + type.token() + " " + fields);
          return true;
        }
        public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) { all.add(type.token()); return true; }
      }, new EngineSelfIntentWriter.EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "runtime-window"));
      var method = TikaOcrRuntime.class.getDeclaredMethod("checkExecutable", Path.class); method.setAccessible(true);
      try { method.invoke(null, exe); assertTrue(launches, "invalid executable must fail to spawn"); }
      catch (java.lang.reflect.InvocationTargetException failure) {
        assertFalse(launches, "real Tesseract availability succeeds: " + failure.getCause());
        assertInstanceOf(java.io.IOException.class, failure.getCause());
      }
      System.out.println("availability launched=" + launches + " all=" + all + " forced=" + forced);
      if (launches) assertEquals(List.of("helper-window-start"), forced, "only the window start is forced");
      else assertEquals(List.of(), all, "an unsuccessful spawn writes no helper window or launch receipt");
    } finally { field.set(null, saved); queue.clear(); queue.addAll(savedQueue); }
  }
}
