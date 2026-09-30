package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.core.execution.EngineExecutorRejectedException;

import io.justsearch.app.api.scan.ScanProgressEvent;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 / T4 — exercises {@link ScanProgressRegistry} with the four classes of
 * subscribers the design must handle: early (subscribe before any events), mid-flight
 * (subscribe after some events), late-but-within-retention, and past-retention.
 */
final class ScanProgressRegistryTest {

  private ScanProgressRegistry registry;
  private TestEngineExecutors processExecutors;

  @BeforeEach
  void setUp() {
    processExecutors = new TestEngineExecutors();
    // Tight retention for the past-retention test; other tests don't depend on the value.
    registry = new ScanProgressRegistry(processExecutors, 50);
  }

  @AfterEach
  void tearDown() {
    registry.close();
    processExecutors.close();
  }

  @Test
  @DisplayName("Unknown scanId returns a synthetic UNKNOWN_SCAN_OR_RETENTION_EXPIRED terminal event")
  void unknownScanReturnsSyntheticTerminal() {
    List<ScanProgressEvent> events = drain(registry.subscribe("never-existed"));
    assertEquals(1, events.size());
    assertTrue(events.get(0).complete());
    assertEquals("UNKNOWN_SCAN_OR_RETENTION_EXPIRED", events.get(0).terminalReasonCode());
  }

  @Test
  @DisplayName("Late subscriber within retention sees full historical replay")
  void lateSubscriberSeesReplay() {
    String scanId = "scan-1";
    registry.register(scanId, new CancelToken());
    registry.record(scanId, progress(scanId, 10, false, ""));
    registry.record(scanId, progress(scanId, 20, false, ""));
    registry.record(scanId, progress(scanId, 30, true, "")); // terminal

    List<ScanProgressEvent> events = drain(registry.subscribe(scanId));
    assertEquals(3, events.size(), "Late subscriber must see the full event sequence");
    assertEquals(10, events.get(0).filesWalked());
    assertEquals(20, events.get(1).filesWalked());
    assertEquals(30, events.get(2).filesWalked());
    assertTrue(events.get(2).complete());
  }

  @Test
  @DisplayName("Early subscriber receives events as they arrive")
  void earlySubscriberStreams() throws Exception {
    String scanId = "scan-streaming";
    registry.register(scanId, new CancelToken());

    List<ScanProgressEvent> received = new ArrayList<>();
    CountDownLatch terminalLatch = new CountDownLatch(1);
    Thread subscriber =
        new Thread(
            () -> {
              for (ScanProgressEvent event : registry.subscribe(scanId)) {
                received.add(event);
                if (event.complete()) {
                  terminalLatch.countDown();
                }
              }
            });
    subscriber.setDaemon(true);
    subscriber.start();
    Thread.sleep(50); // let the subscriber start blocking on the queue

    registry.record(scanId, progress(scanId, 5, false, ""));
    registry.record(scanId, progress(scanId, 15, true, ""));

    assertTrue(terminalLatch.await(5, TimeUnit.SECONDS), "Subscriber must observe terminal event");
    assertEquals(2, received.size());
    assertEquals(5, received.get(0).filesWalked());
    assertEquals(15, received.get(1).filesWalked());
  }

  @Test
  @DisplayName("Past-retention subscriber sees the synthetic terminal")
  void pastRetentionSubscriberSeesSyntheticTerminal() throws Exception {
    String scanId = "scan-stale";
    registry.register(scanId, new CancelToken());
    registry.record(scanId, progress(scanId, 1, true, "")); // terminal immediately

    Thread.sleep(100); // exceeds the 50ms retention configured in @BeforeEach
    registry.pruneNow();

    List<ScanProgressEvent> events = drain(registry.subscribe(scanId));
    assertEquals(1, events.size());
    assertEquals("UNKNOWN_SCAN_OR_RETENTION_EXPIRED", events.get(0).terminalReasonCode());
  }

  @Test
  @DisplayName("cancel() fires the CancelToken; cancel on unknown scan is a safe no-op")
  void cancelFiresToken() {
    String scanId = "scan-cancel";
    CancelToken token = new CancelToken();
    registry.register(scanId, token);
    assertFalse(token.isCancelled());

    boolean cancelled = registry.cancel(scanId);
    assertTrue(cancelled);
    assertTrue(token.isCancelled());

    assertFalse(registry.cancel("never-existed"));
  }

  @Test
  @DisplayName("markComplete after no events still terminates subscribers")
  void markCompleteWithoutEvents() throws Exception {
    String scanId = "scan-empty";
    registry.register(scanId, new CancelToken());

    CountDownLatch terminalLatch = new CountDownLatch(1);
    Thread subscriber =
        new Thread(
            () -> {
              for (ScanProgressEvent event : registry.subscribe(scanId)) {
                if (event.complete()) {
                  terminalLatch.countDown();
                }
              }
            });
    subscriber.setDaemon(true);
    subscriber.start();
    Thread.sleep(50);

    registry.markComplete(scanId, progress(scanId, 0, true, "ROOT_NOT_DIRECTORY"));

    assertTrue(terminalLatch.await(5, TimeUnit.SECONDS),
        "Subscriber must terminate even if no progress events were recorded");
  }

  @Test
  @DisplayName("Two subscribers both see the full stream")
  void twoSubscribersBothSeeEvents() throws Exception {
    String scanId = "scan-fanout";
    registry.register(scanId, new CancelToken());

    List<ScanProgressEvent> a = new ArrayList<>();
    List<ScanProgressEvent> b = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);
    Runnable consumer =
        () -> {
          List<ScanProgressEvent> sink = new ArrayList<>();
          for (ScanProgressEvent event : registry.subscribe(scanId)) {
            sink.add(event);
            if (event.complete()) {
              break;
            }
          }
          synchronized (a) {
            (a.isEmpty() ? a : b).addAll(sink);
            latch.countDown();
          }
        };
    Thread t1 = new Thread(consumer); t1.setDaemon(true); t1.start();
    Thread t2 = new Thread(consumer); t2.setDaemon(true); t2.start();
    Thread.sleep(80);

    registry.record(scanId, progress(scanId, 1, false, ""));
    registry.record(scanId, progress(scanId, 2, true, ""));

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(2, a.size());
    assertEquals(2, b.size());
  }

  @Test
  @DisplayName("activeBufferCount tracks register / prune lifecycle")
  void bufferCountReflectsLifecycle() throws Exception {
    assertEquals(0, registry.activeBufferCount());
    registry.register("a", new CancelToken());
    registry.register("b", new CancelToken());
    assertEquals(2, registry.activeBufferCount());

    registry.markComplete("a", progress("a", 0, true, ""));
    registry.markComplete("b", progress("b", 0, true, ""));
    Thread.sleep(100);
    registry.pruneNow();
    assertEquals(0, registry.activeBufferCount(),
        "Pruner must drop completed buffers older than the retention window");
  }

  @Test
  void replayRetainsBoundedSuffixAndSlowSubscriberExpires() {
    registry.register("bounded", new CancelToken());
    try (var slow = registry.subscribe("bounded")) {
      for (int i = 1; i <= 100; i++) {
        registry.record("bounded", progress("bounded", i, i == 100, ""));
      }
      var expired = drain(slow);
      assertEquals("UNKNOWN_SCAN_OR_RETENTION_EXPIRED", expired.getFirst().terminalReasonCode());
      try (var late = registry.subscribe("bounded")) {
        var retained = drain(late);
        assertEquals(64, retained.size());
        assertEquals(37, retained.getFirst().filesWalked());
        assertEquals(100, retained.getLast().filesWalked());
        assertTrue(retained.getLast().complete());
      }
    }
  }

  @Test
  void subscriberCapacityReturnsOnExplicitCloseAndTerminalConsumption() {
    registry.register("slots", new CancelToken());
    var subscriptions = new ArrayList<ScanProgressRegistry.Subscription>();
    try {
      for (int i = 0; i < 48; i++) subscriptions.add(registry.subscribe("slots"));
      assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
          assertThrows(EngineExecutorRejectedException.class, () -> registry.subscribe("slots")).reason());
      subscriptions.getFirst().close();
      subscriptions.getFirst().close();
      try (var replacement = registry.subscribe("slots")) {
        registry.record("slots", progress("slots", 1, true, ""));
        assertTrue(replacement.next().complete());
        try (var available = registry.subscribe("slots")) {
          assertTrue(available.next().complete());
        }
      }
    } finally {
      subscriptions.forEach(ScanProgressRegistry.Subscription::close);
    }
  }

  @Test
  void idleExpiryReleasesCapacity() {
    registry.close();
    registry = new ScanProgressRegistry(processExecutors, 50, 10);
    registry.register("idle", new CancelToken());
    var subscriptions = new ArrayList<ScanProgressRegistry.Subscription>();
    try {
      for (int i = 0; i < 48; i++) subscriptions.add(registry.subscribe("idle"));
      assertThrows(EngineExecutorRejectedException.class, () -> registry.subscribe("idle"));
      assertFalse(subscriptions.getFirst().hasNext());
      try (var replacement = registry.subscribe("idle")) {
        assertFalse(replacement.hasNext());
      }
    } finally {
      subscriptions.forEach(ScanProgressRegistry.Subscription::close);
    }
  }

  @Test
  void stagedTerminalStillOwnsCapacityUntilDeliveredOrClosed() {
    registry.register("complete", new CancelToken());
    registry.record("complete", progress("complete", 7, true, ""));
    var subscriptions = new ArrayList<ScanProgressRegistry.Subscription>();
    try {
      for (int i = 0; i < 48; i++) {
        var subscription = registry.subscribe(i % 2 == 0 ? "complete" : "unknown");
        subscriptions.add(subscription);
        assertTrue(subscription.hasNext());
      }
      assertThrows(EngineExecutorRejectedException.class, () -> registry.subscribe("complete"));
      assertTrue(subscriptions.getFirst().next().complete());
      try (var replacement = registry.subscribe("complete")) {
        assertTrue(replacement.hasNext());
        assertThrows(EngineExecutorRejectedException.class, () -> registry.subscribe("complete"));
      }
      try (var replacement = registry.subscribe("complete")) {
        assertTrue(replacement.next().complete());
      }
    } finally {
      subscriptions.forEach(ScanProgressRegistry.Subscription::close);
    }
  }

  @Test
  void retainedReleasedSubscriptionObjectsDoNotPinReplayRings() throws Exception {
    var field = ScanProgressRegistry.Subscription.class.getDeclaredField("buffer");
    field.setAccessible(true);
    var retained = new ArrayList<ScanProgressRegistry.Subscription>();
    for (int i = 0; i < 100; i++) {
      String scan = "retained-" + i;
      registry.register(scan, new CancelToken());
      var subscription = registry.subscribe(scan);
      retained.add(subscription);
      assertNotNull(field.get(subscription), "precondition: cursor owns the replay ring");
      registry.record(scan, progress(scan, 1, true, ""));
      if (i % 2 == 0) assertTrue(subscription.next().complete());
      else subscription.close();
    }
    for (var subscription : retained) {
      assertNull(field.get(subscription), "released cursor must sever its replay ownership");
      subscription.close();
    }
  }

  @Test
  void fullActiveRegistryRefusesAndCompletedEntryMakesRoom() {
    for (int i = 0; i < 64; i++) registry.register("scan-" + i, new CancelToken());
    assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
        assertThrows(EngineExecutorRejectedException.class,
            () -> registry.register("overflow", new CancelToken())).reason());
    registry.markComplete("overflow", progress("overflow", 0, true, "RPC_FAILED"));
    assertEquals(64, registry.activeBufferCount());
    registry.record("scan-0", progress("scan-0", 1, true, ""));
    registry.register("replacement", new CancelToken());
    assertEquals(64, registry.activeBufferCount());
    assertEquals("UNKNOWN_SCAN_OR_RETENTION_EXPIRED",
        drain(registry.subscribe("scan-0")).getFirst().terminalReasonCode());
  }

  @Test
  void interruptReleasesSubscriberAndCloseWakesWaitingReader() throws Exception {
    registry.register("waiting", new CancelToken());
    var subscriptions = new ArrayList<ScanProgressRegistry.Subscription>();
    for (int i = 0; i < 47; i++) subscriptions.add(registry.subscribe("waiting"));
    try (var waiting = registry.subscribe("waiting")) {
      var done = new java.util.concurrent.CompletableFuture<Boolean>();
      Thread reader = Thread.ofPlatform().daemon().start(() -> {
        Thread.currentThread().interrupt();
        done.complete(!waiting.hasNext() && Thread.currentThread().isInterrupted());
      });
      assertTrue(done.get(5, TimeUnit.SECONDS));
      reader.join(5000);
      try (var replacement = registry.subscribe("waiting")) {
        var terminal = new java.util.concurrent.CompletableFuture<ScanProgressEvent>();
        Thread closingReader = Thread.ofPlatform().daemon().start(() -> terminal.complete(replacement.next()));
        registry.close();
        assertEquals("UNKNOWN_SCAN_OR_RETENTION_EXPIRED", terminal.get(5, TimeUnit.SECONDS).terminalReasonCode());
        closingReader.join(5000);
      }
    } finally {
      subscriptions.forEach(ScanProgressRegistry.Subscription::close);
    }
    assertThrows(EngineExecutorRejectedException.class, () -> registry.register("after-close", new CancelToken()));
  }

  // ===== helpers =====

  private static ScanProgressEvent progress(String scanId, long walked, boolean complete, String reason) {
    return new ScanProgressEvent(scanId, walked, 0L, 0L, 0L, "", complete, reason);
  }

  private static List<ScanProgressEvent> drain(Iterable<ScanProgressEvent> iter) {
    List<ScanProgressEvent> out = new ArrayList<>();
    for (ScanProgressEvent event : iter) {
      out.add(event);
      if (event.complete()) {
        break;
      }
    }
    return out;
  }
}
