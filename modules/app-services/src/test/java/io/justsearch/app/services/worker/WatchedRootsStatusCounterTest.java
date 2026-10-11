package io.justsearch.app.services.worker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class WatchedRootsStatusCounterTest {
 @TempDir Path temp;
 @Test void statusCounterTracksRootsAndNotDuplicateRemoval() {
  var state = new WatchedRootsState(new ConcurrentHashMap<Path,Instant>(),
      new WatchedRootsStore(temp.resolve("roots.json"), null));
  var root = temp.resolve("docs"); long before = statusCounter();
  state.markNeverIndexed(root);
  assertEquals(before + 1, statusCounter(), "root ownership publication must invalidate status");
  state.markNeverIndexed(root); state.isWalkCompleted(root); state.getWalkError(root);
  assertEquals(before + 1, statusCounter(), "identical ownership and reads must not invalidate");
  state.removeRootAndNested(root); assertEquals(before + 2, statusCounter());
  state.removeRootAndNested(root); assertEquals(before + 2, statusCounter());
 }

  private static long statusCounter() {
    try {
      var type = Class.forName("io.justsearch.core.status.StatusChangeCounter");
      return (long) type.getMethod("current").invoke(type.getMethod("process").invoke(null));
    } catch (ClassNotFoundException baseline) { return 0L; }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }
}
