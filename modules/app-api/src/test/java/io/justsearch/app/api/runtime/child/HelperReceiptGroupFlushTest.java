/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Task b6d536 b101: inside an open window only the window start is forced to disk. The receipts of
 * the helpers launched in it are still written, one start and one exit each, but unforced, and
 * they are forced as a group: within a second while any are pending, and at the window's close.
 * A helper launched outside any window keeps its own forced receipts. Real Windows processes.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
final class HelperReceiptGroupFlushTest {
  /** One ledger event in order: an append (forced or not) or a group force. */
  private record Event(String type, boolean forced, long atNanos) {}

  private final List<Event> events = new CopyOnWriteArrayList<>();
  private Object saved;

  /** Records every append and every group force; flush() is the ledger's group force. */
  private final class Sink implements LedgerSink {
    @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
      events.add(new Event(type.token(), true, System.nanoTime()));
      return true;
    }

    @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
      events.add(new Event(type.token(), false, System.nanoTime()));
      return true;
    }

    public boolean flush() {
      events.add(new Event("flush", true, System.nanoTime()));
      return true;
    }
  }

  @BeforeEach void record() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    saved = field.get(null);
    OwnedHelper.recordTo(new Sink(), new EngineIdentity(ProcessHandle.current().pid(),
        ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "group-flush"));
  }

  @AfterEach void restore() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    field.set(null, saved);
  }

  private static void runAwaited(String purpose) throws Exception {
    Process p = OwnedHelper.start(new ProcessBuilder(io.justsearch.configuration.PlatformPaths.isLinux() ? List.of(java.nio.file.Path.of("/bin/true").toRealPath().toString()) : List.of("cmd.exe", "/c", "exit 0"))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD), purpose);
    assertTrue(p.waitFor(15, TimeUnit.SECONDS), purpose + " exited");
  }

  private List<Event> of(String type) {
    return events.stream().filter(e -> e.type().equals(type)).toList();
  }

  @Test void receiptsInsideAWindowAreUnforcedAndForcedAsAGroup() throws Exception {
    var window = OwnedHelper.sharedWindow(io.justsearch.configuration.PlatformPaths.isLinux() ? java.nio.file.Path.of("/bin/true").toRealPath().getFileName().toString() : "cmd.exe", "group flush probe", Duration.ofMinutes(1));
    try (var _ = window.acquire()) {
      for (int i = 0; i < 3; i++) runAwaited("group flush helper " + i);
      List<Event> receipts = events.stream().filter(e -> e.type().startsWith("helper-")
          && !e.type().startsWith("helper-window")).toList();
      assertEquals(6, receipts.size(), "each helper still has its start and its exit: " + events);
      assertTrue(receipts.stream().noneMatch(Event::forced),
          "no per-helper forced write inside the window: " + events);
      assertEquals(List.of("helper-window-start"), events.stream().filter(e -> e.forced()
          && !e.type().equals("flush")).map(Event::type).toList(), "only the window start is forced");
      // Pending receipts are forced as a group within a second, while the window stays open.
      long lastReceipt = receipts.getLast().atNanos();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline && events.stream().noneMatch(e -> e.type().equals("flush")
          && e.atNanos() > lastReceipt)) {
        Thread.sleep(20);
      }
      for (Event receipt : receipts) {
        assertTrue(events.stream().anyMatch(e -> e.type().equals("flush") && e.atNanos() > receipt.atNanos()
                && e.atNanos() - receipt.atNanos() <= TimeUnit.MILLISECONDS.toNanos(1_500)),
            "each pending receipt is forced within about a second, while the window is open: " + events);
      }
      assertTrue(of("flush").size() < receipts.size(), "forced as a group, not once per receipt: " + events);
    }
    window.close();
    Event end = of("helper-window-end").getFirst();
    assertTrue(!end.forced(), "the window end is not forced on its own");
    assertTrue(events.stream().anyMatch(e -> e.type().equals("flush") && e.atNanos() >= end.atNanos()),
        "closing the window forces what it wrote: " + events);
  }

  @Test void receiptsOutsideAnyWindowStayForced() throws Exception {
    runAwaited("unwindowed helper");
    List<Event> receipts = events.stream().filter(e -> e.type().equals("helper-start")
        || e.type().equals("helper-exit")).toList();
    assertEquals(2, receipts.size(), events.toString());
    assertTrue(receipts.stream().allMatch(Event::forced), "a helper outside a window is forced: " + events);
  }
}
