package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 / T3 — verifies the {@link CancelToken} substrate behavior. The end-to-end
 * "HTTP abort cancels the in-flight scan" assertion lives in the SSE/integration test layer
 * (T6 + T4); this unit test pins the token's own contract.
 *
 * <p>Lane F stage A item A10 re-homed {@code CancelToken} off {@code io.grpc.Context}, so the two
 * cases that pinned delegation to the gRPC context are re-expressed against the property that
 * delegation existed to deliver: a cancel issued from anywhere is observable by the producer, both
 * as a flag and as a one-shot notification. Nothing was dropped — {@code onCancel} is now what the
 * in-process producer wires to its own cancel signal, so it carries the assertion the context
 * previously carried.
 */
final class CancelTokenTest {

  @Test
  @DisplayName("Newly created token is not cancelled")
  void newTokenIsNotCancelled() {
    CancelToken token = new CancelToken();
    assertFalse(token.isCancelled(), "Fresh token must not report cancelled");
  }

  @Test
  @DisplayName("cancel() flips isCancelled() and notifies a registered handler")
  void cancelNotifiesRegisteredHandler() {
    CancelToken token = new CancelToken();
    AtomicInteger fired = new AtomicInteger();
    token.onCancel(fired::incrementAndGet);
    assertEquals(0, fired.get(), "a handler must not fire before the cancel");

    token.cancel("test cancel");

    assertTrue(token.isCancelled(), "Token reports cancelled after cancel()");
    assertEquals(1, fired.get(), "the registered handler must observe the cancel");
    assertEquals("test cancel", token.reason());
  }

  @Test
  @DisplayName("cancel() is idempotent — second call does not throw and does not re-notify")
  void cancelIsIdempotent() {
    CancelToken token = new CancelToken();
    AtomicInteger fired = new AtomicInteger();
    token.onCancel(fired::incrementAndGet);
    token.cancel();
    token.cancel("second cancel"); // must not throw
    assertTrue(token.isCancelled());
    assertEquals(1, fired.get(), "a handler runs exactly once, not once per cancel() call");
    assertEquals("client cancelled", token.reason(), "the first reason wins");
  }

  @Test
  @DisplayName("Cancel from one thread is visible to another thread (cross-thread propagation)")
  void crossThreadCancelIsVisible() throws InterruptedException {
    CancelToken token = new CancelToken();
    Thread canceller =
        new Thread(
            () -> {
              try {
                Thread.sleep(50);
                token.cancel("cross-thread");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    canceller.start();
    long start = System.nanoTime();
    while (!token.isCancelled() && (System.nanoTime() - start) < 2_000_000_000L) {
      Thread.sleep(10);
    }
    canceller.join();
    assertTrue(token.isCancelled(), "Token must observe cancel issued from another thread");
  }

  @Test
  @DisplayName("a handler registered after the cancel fires immediately, on the calling thread")
  void lateHandlerFiresImmediately() {
    CancelToken token = new CancelToken();
    token.cancel("pre-registration");

    AtomicInteger fired = new AtomicInteger();
    Thread onThread = Thread.currentThread();
    Thread[] ranOn = new Thread[1];
    token.onCancel(
        () -> {
          ranOn[0] = Thread.currentThread();
          fired.incrementAndGet();
        });

    assertEquals(
        1,
        fired.get(),
        "an already-cancelled token must not silently swallow a late registration — the producer"
            + " that registers after losing the race would otherwise never stop");
    assertEquals(onThread, ranOn[0], "it runs inline, on the registering thread");
  }
}
