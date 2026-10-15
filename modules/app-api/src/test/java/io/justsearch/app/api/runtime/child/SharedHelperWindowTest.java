/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: the shared helper window opens on demand, is shared by consecutive and
 * concurrent leases, ends once after its idle period and at shutdown, forces only its start, and
 * never leaves a launch outside an open window, including one made at the very end of an idle
 * period. Deterministic: a settable clock and a captured idle scheduler.
 */
final class SharedHelperWindowTest {
  private record Written(String type, boolean forced, Map<String, Object> fields) {}

  private final List<Written> written = new CopyOnWriteArrayList<>();
  private final List<Runnable> idleChecks = new ArrayList<>();
  private Instant now = Instant.parse("2026-10-14T10:00:00Z");
  private final Clock clock = new Clock() {
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  };
  private Object savedRecorder;

  @BeforeEach void record() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    savedRecorder = field.get(null);
    OwnedHelper.recordTo(new LedgerSink() {
      @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
        written.add(new Written(type.token(), true, new LinkedHashMap<>(fields)));
        return true;
      }

      @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
        written.add(new Written(type.token(), false, new LinkedHashMap<>(fields)));
        return true;
      }
    }, new EngineIdentity(4242, Optional.empty(), "shared-window"));
  }

  @AfterEach void restore() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    field.set(null, savedRecorder);
  }

  private OwnedHelper.SharedWindow window() {
    return OwnedHelper.sharedWindow("tesseract.exe", "OCR", Duration.ofMinutes(5),
        Duration.ofSeconds(30), clock, (delay, check) -> {
          assertEquals(Duration.ofSeconds(30), delay);
          idleChecks.add(check);
        });
  }

  private long count(String type) {
    return written.stream().filter(w -> w.type().equals(type)).count();
  }

  private void runIdleChecks() {
    List<Runnable> due = new ArrayList<>(idleChecks);
    idleChecks.clear();
    due.forEach(Runnable::run);
  }

  @Test void consecutiveLeasesShareOneWindowThatEndsOnceAfterIdle() {
    var shared = window();
    for (int i = 0; i < 100; i++) {
      var lease = shared.acquire();
      now = now.plusSeconds(1);
      lease.close();
    }
    assertEquals(1, count("helper-window-start"));
    assertEquals(0, count("helper-window-end"), "a window with recent leases stays open");
    now = now.plusSeconds(30);
    runIdleChecks();
    assertEquals(1, count("helper-window-end"));
    assertTrue(written.getFirst().forced(), "the start record is forced once");
    assertTrue(written.stream().filter(w -> w.type().equals("helper-window-end")).noneMatch(Written::forced),
        "the end record has no forced write of its own");
  }

  @Test void concurrentLeasesShareTheWindow() throws Exception {
    var shared = window();
    CountDownLatch all = new CountDownLatch(8);
    CountDownLatch release = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      threads.add(Thread.ofPlatform().start(() -> {
        var lease = shared.acquire();
        try {
          all.countDown();
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          lease.close();
        }
      }));
    }
    assertTrue(all.await(10, TimeUnit.SECONDS));
    release.countDown();
    for (Thread t : threads) t.join(10_000);
    assertEquals(1, count("helper-window-start"));
    runIdleChecks();
    assertEquals(1, count("helper-window-end"));
  }

  @Test void aLeaseAtTheEndOfAnIdlePeriodIsNeverOutsideAnOpenWindow() {
    var shared = window();
    shared.acquire().close();
    now = now.plusSeconds(30);
    // The new lease arrives just before the idle check runs: the stale check must not end it.
    var late = shared.acquire();
    runIdleChecks();
    assertEquals(1, count("helper-window-start"));
    assertEquals(0, count("helper-window-end"), "the window stays open under the late lease");
    late.close();
    // The idle check ran first: the next lease opens a fresh window before it returns.
    now = now.plusSeconds(30);
    runIdleChecks();
    assertEquals(1, count("helper-window-end"));
    var after = shared.acquire();
    assertEquals(2, count("helper-window-start"));
    assertEquals("helper-window-start", written.getLast().type(), "the start precedes any launch");
    after.close();
  }

  @Test void aLeaseThatCouldOutliveTheDeadlineOpensAFreshWindow() {
    var shared = window();
    var first = shared.acquire();
    now = now.plus(Duration.ofMinutes(6)); // less than five minutes remain before the deadline
    var second = shared.acquire();
    assertEquals(2, count("helper-window-start"));
    assertEquals(0, count("helper-window-end"), "the earlier window stays open for its own lease");
    first.close();
    assertEquals(1, count("helper-window-end"));
    String firstId = String.valueOf(written.getFirst().fields().get("intentId"));
    assertEquals(firstId, String.valueOf(written.getLast().fields().get("intentId")));
    second.close();
  }

  @Test void shutdownEndsAnIdleWindowNowAndALeasedOneAtItsRelease() {
    var shared = window();
    shared.acquire().close();
    shared.close();
    assertEquals(1, count("helper-window-end"));
    var lease = shared.acquire();
    shared.close();
    assertEquals(1, count("helper-window-end"), "a leased window outlives the shutdown request");
    lease.close();
    assertEquals(2, count("helper-window-end"));
    runIdleChecks();
    assertEquals(2, count("helper-window-end"), "a stale idle check ends nothing twice");
  }
}
