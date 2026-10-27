/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Current-only mechanism races; actual public fail-before checks live in the HTTP test classes. */
@Timeout(20)
final class RequestReleaseOwnerTest {
  @Test
  void blockedReleaseRetainsItsRecordWhileTerminalCaptureAndAnotherAfterProgress() throws Exception {
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    var owner = RequestReleaseOwner.install(app);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    app.start("127.0.0.1", 0);
    try {
      var a = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 500);
      var lease = mock(OperationLeaseHandle.class);
      doAnswer(call -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return null;
      }).when(lease).release(OpLeaseOutcome.FAILURE);
      a.attributes.put(RequestReleaseOwner.MUTATION_LEASE_ATTRIBUTE, lease);
      owner.before(a.context, true);
      terminal(owner, a.request);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, retained(owner), "selection is not acknowledgement");
      assertNull(registrationField(a, "creatingContext"), "worker handoff clears the borrowed Context");

      var b = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 200);
      var workB = mock(EngineWorkHandle.class);
      b.attributes.put(RequestEngineWork.ATTRIBUTE, workB);
      owner.before(b.context, true);
      owner.after(b.context);
      verify(workB).close();
      assertEquals(1, retained(owner), "another after is not blocked by projection I/O");

      var c = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 200);
      var workC = mock(EngineWorkHandle.class);
      c.attributes.put(RequestEngineWork.ATTRIBUTE, workC);
      owner.before(c.context, true);
      terminal(owner, c.request); // Must return while the only worker is still blocked on A.
      assertEquals(2, retained(owner));
      verify(workC, never()).close();
      terminal(owner, c.request);
      owner.after(c.context); // READY duplicate must neither reread nor fan out.
      release.countDown();
      await(() -> retainedUnchecked(owner) == 0);
      verify(lease, times(1)).release(OpLeaseOutcome.FAILURE);
      verify(workC, times(1)).close();
      LocalApiServer.stopHttpTransport(app);
      assertTrue(owner.drained());
    } finally {
      release.countDown();
      app.stop();
    }
  }

  @Test
  void oldAfterCannotReadNewRegistrationOnTheRecycledRequest() throws Exception {
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    var owner = RequestReleaseOwner.install(app);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    app.start("127.0.0.1", 0);
    try {
      Map<String, Object> attributes = new HashMap<>();
      Request request = mock(Request.class, RETURNS_DEEP_STUBS);
      var old = new Borrowed(attributes, request, 500);
      var lease = mock(OperationLeaseHandle.class);
      doAnswer(call -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return null;
      }).when(lease).release(OpLeaseOutcome.FAILURE);
      old.attributes.put(RequestReleaseOwner.MUTATION_LEASE_ATTRIBUTE, lease);
      owner.before(old.context, true);
      terminal(owner, request);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      attributes.clear(); // Exact request object is recycled; Context identity is not.
      var current = new Borrowed(attributes, request, 200);
      var work = mock(EngineWorkHandle.class);
      attributes.put(RequestEngineWork.ATTRIBUTE, work);
      owner.before(current.context, true);
      clearInvocations(request, old.context);
      owner.after(old.context);
      verify(request, never()).getAttribute(RequestEngineWork.ATTRIBUTE);
      verify(old.context, never()).statusCode();
      verify(work, never()).close();
      owner.after(current.context);
      verify(work, times(1)).close();
      release.countDown();
      await(() -> retainedUnchecked(owner) == 0);
      verify(lease, times(1)).release(OpLeaseOutcome.FAILURE);
      verify(lease, never()).release(OpLeaseOutcome.SUCCESS);
      LocalApiServer.stopHttpTransport(app);
      assertTrue(owner.drained());
    } finally {
      release.countDown();
      app.stop();
    }
  }

  @Test
  void firstNegativeAcknowledgementBoundsFailedRecordsWithoutStoppingExistingCleanup() throws Exception {
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    var owner = RequestReleaseOwner.install(app);
    app.start("127.0.0.1", 0);
    try {
      var failed = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 200);
      var remaining = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 200);
      var broken = mock(EngineWorkHandle.class);
      var live = mock(EngineWorkHandle.class);
      doThrow(new IllegalStateException("controlled observable close failure")).when(broken).close();
      failed.attributes.put(RequestEngineWork.ATTRIBUTE, broken);
      remaining.attributes.put(RequestEngineWork.ATTRIBUTE, live);
      owner.before(failed.context, true);
      owner.before(remaining.context, true);
      assertThrows(IllegalStateException.class, () -> owner.after(failed.context));
      assertEquals(2, retained(owner));
      for (int i = 0; i < 10; i++) {
        var refused = new Borrowed(new HashMap<>(), mock(Request.class, RETURNS_DEEP_STUBS), 200);
        assertThrows(IllegalStateException.class, () -> owner.before(refused.context, true));
        owner.before(refused.context, false);
        assertEquals(2, retained(owner), "refused/exempt traffic must not grow the retained set");
      }
      owner.after(remaining.context);
      verify(live).close();
      assertEquals(1, retained(owner));
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(app));
      assertFalse(owner.drained(), "FAILED is retained; stop cannot wash it into ACKED");
      verify(broken, times(1)).close();
    } finally { app.stop(); }
  }

  @Test
  void laterStartupListenerFailureDrainsWorkerWithoutAnAcceptingTransport() throws Exception {
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    var owner = RequestReleaseOwner.install(app);
    var lockField = RequestReleaseOwner.class.getDeclaredField("monitor");
    var startedField = RequestReleaseOwner.class.getDeclaredField("started");
    var workerField = RequestReleaseOwner.class.getDeclaredField("worker");
    var exitedField = RequestReleaseOwner.class.getDeclaredField("workerExited");
    for (Field field : new Field[] {lockField, startedField, workerField, exitedField}) field.setAccessible(true);
    Object monitor = lockField.get(owner);
    var confirmed = new java.util.concurrent.atomic.AtomicBoolean();
    var controlled = new IllegalStateException("controlled later startup failure");
    app.events(events -> events.serverStarting(() -> {
      try {
        boolean alreadyStarted;
        synchronized (monitor) { alreadyStarted = startedField.getBoolean(owner); }
        // Lifecycle listeners are HashSet-ordered. Establish the real state, never a lucky order
        // or a fake started flag. Event dispatch aborts at our throw, so start cannot run twice.
        if (!alreadyStarted) {
          var start = RequestReleaseOwner.class.getDeclaredMethod("start");
          start.setAccessible(true);
          start.invoke(owner);
        }
        Thread worker;
        synchronized (monitor) {
          assertTrue(startedField.getBoolean(owner));
          assertFalse(exitedField.getBoolean(owner));
          worker = (Thread) workerField.get(owner);
          assertNotNull(worker);
        }
        // Only a startup-entry premise; release acknowledgement still requires owner.drained.
        await(() -> worker.getState() == Thread.State.WAITING);
        confirmed.set(true);
      } catch (Exception premiseFailure) {
        if (premiseFailure instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new AssertionError("actual worker-start premise was not established", premiseFailure);
      }
      throw controlled;
    }));
    assertSame(controlled, assertThrows(IllegalStateException.class, () -> app.start("127.0.0.1", 0)));
    assertTrue(confirmed.get(), "a real worker must be waiting before the controlled listener throws");
    assertTrue(app.jettyServer().server().isStopped());
    LocalApiServer.stopHttpTransport(app);
    assertTrue(owner.drained(), "never-started STOPPED transport must still release its started worker");
  }

  @Test
  void failedBindDrainsOldWorkerAndFreshEphemeralServerOwnsItsOwnWorker() throws Exception {
    var failed = Javalin.create(config -> config.showJavalinBanner = false);
    var old = RequestReleaseOwner.install(failed);
    try (var occupied = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      assertThrows(RuntimeException.class, () -> failed.start("127.0.0.1", occupied.getLocalPort()));
      LocalApiServer.stopHttpTransport(failed);
      assertTrue(old.drained());
      var fresh = Javalin.create(config -> config.showJavalinBanner = false);
      var current = RequestReleaseOwner.install(fresh);
      assertNotSame(old, current);
      try {
        fresh.start("127.0.0.1", 0);
        assertTrue(fresh.port() > 0);
        LocalApiServer.stopHttpTransport(fresh);
        assertTrue(current.drained());
        assertTrue(old.drained());
      } finally { fresh.stop(); }
    } finally { failed.stop(); }
  }

  private static void terminal(RequestReleaseOwner owner, Request request) throws Exception {
    var method = RequestReleaseOwner.class.getDeclaredMethod("terminal", Request.class);
    method.setAccessible(true);
    method.invoke(owner, request);
  }

  private static Object registrationField(Borrowed borrowed, String name) throws Exception {
    Object registration = borrowed.attributes.get("__request_release__");
    Field field = registration.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(registration);
  }

  private static int retained(RequestReleaseOwner owner) throws Exception {
    Field lock = RequestReleaseOwner.class.getDeclaredField("monitor");
    lock.setAccessible(true);
    Field records = RequestReleaseOwner.class.getDeclaredField("requests");
    records.setAccessible(true);
    synchronized (lock.get(owner)) { return ((Set<?>) records.get(owner)).size(); }
  }

  private static int retainedUnchecked(RequestReleaseOwner owner) {
    try { return retained(owner); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  private static final class Borrowed {
    final Map<String, Object> attributes;
    final Request request;
    final Context context = mock(Context.class);

    Borrowed(Map<String, Object> attributes, Request request, int status) {
      this.attributes = attributes;
      this.request = request;
      when(context.req()).thenReturn(request);
      when(context.statusCode()).thenReturn(status);
      when(request.getResponse().getStatus()).thenReturn(status);
      when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
      when(context.attribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
      doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
          .when(context).attribute(anyString(), any());
    }
  }
}
