/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.EngineAdmissionService.StatusReservation;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exact front-request release lifetime for one concrete transport, independent of Engine executors.
 *
 * <p>The one daemon platform worker is a deliberate transport-lifetime exception: executor registry
 * shutdown and Jetty queue shutdown both precede possible terminal releases. It reads only this
 * identity set of actual requests, never a second job queue. The monitor guards metadata only;
 * handle calls, callbacks, logging and projection I/O always run outside it. No other lock is taken
 * while holding it. Captured records contain no servlet objects or thread interruption actions.
 */
final class RequestReleaseOwner {
  private static final Logger log = LoggerFactory.getLogger(RequestReleaseOwner.class);
  private static final String ATTRIBUTE = "__request_release__";
  static final String MUTATION_LEASE_ATTRIBUTE = "__upgrade_mutation_lease__";
  static final String STATUS_RESERVATION_ATTRIBUTE = "status-admission-reservation";
  private final Object monitor = new Object();
  private final Set<Registration> requests = Collections.newSetFromMap(new IdentityHashMap<>());
  private Thread worker;
  private boolean started;
  private boolean transportStopped;
  private boolean workerExited = true;
  private Throwable unavailable;
  private Throwable workerFailure;
  private StopAttempt stopAttempt;

  private enum State { LIVE, READY, RELEASING, ACKED, FAILED }

  private static final class Registration {
    final RequestReleaseOwner owner;
    // Identity only while LIVE: Context.attribute delegates to a recyclable servlet request.
    Context creatingContext;
    State state = State.LIVE;
    OperationLeaseHandle mutation;
    StatusReservation status;
    EngineWorkHandle work;
    boolean operationResponse;
    OpLeaseOutcome outcome;

    Registration(RequestReleaseOwner owner, Context context) {
      this.owner = owner;
      this.creatingContext = context;
    }
  }

  private static final class StopAttempt {
    Thread thread;
    boolean exited;
    Throwable failure;
  }

  static RequestReleaseOwner install(Javalin app) {
    Server server = app.jettyServer().server();
    if (server.getBean(RequestReleaseOwner.class) != null) {
      throw new IllegalStateException("Request release owner already installed");
    }
    var owner = new RequestReleaseOwner();
    server.addBean(owner); // Plain bean: Jetty must not close this owner before late releases.
    server.addEventListener(new LifeCycle.Listener() {
      @Override public void lifeCycleStopped(LifeCycle ignored) { owner.transportStopped(); }
    });
    app.events(events -> events.serverStarting(() -> {
      try {
        owner.start();
        // SERVER_STARTING runs after Javalin creates its actual default connectors.
        server.addBeanToAllConnectors(new HttpChannel.Listener() {
          @Override public void onComplete(Request request) { owner.terminal(request); }
        });
      } catch (RuntimeException | Error failure) {
        owner.startupFailed(failure, server.isStopped());
        throw failure;
      }
    }));
    return owner;
  }

  private void start() {
    Thread starting = Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false)
        .name("http-request-release").unstarted(this::releaseLoop);
    synchronized (monitor) {
      if (started || transportStopped || unavailable != null) throw unavailable();
      started = true;
      workerExited = false;
      worker = starting;
    }
    try {
      starting.start();
    } catch (RuntimeException | Error failure) {
      synchronized (monitor) {
        unavailable = failure;
        workerExited = true; // start failed; no Runnable can report exit.
        monitor.notifyAll();
      }
      throw failure;
    }
  }

  private void startupFailed(Throwable failure, boolean neverStartedTransport) {
    synchronized (monitor) {
      if (unavailable == null) unavailable = failure;
      if (neverStartedTransport) transportStopped = true;
      monitor.notifyAll();
    }
  }

  void before(Context context, boolean relevant) {
    if (relevant) register(context);
  }

  static void requireLiveIfInstalled(Context context) {
    var servletRequest = context.req();
    if (servletRequest == null) return; // Genuinely standalone controller context.
    Request request = Request.getBaseRequest(servletRequest);
    if (request == null) return;
    var channel = request.getHttpChannel();
    if (channel == null || channel.getServer() == null) return; // Unbound standalone fixture, never a serving Jetty request.
    var owner = channel.getServer().getBean(RequestReleaseOwner.class);
    // Concrete server composition, never a missing/stale per-request attribute, decides ownership.
    if (owner != null) owner.requireLive(context);
  }

  private void requireLive(Context context) {
    Object attribute = context.attribute(ATTRIBUTE);
    synchronized (monitor) {
      if (!(attribute instanceof Registration registration) || registration.owner != this
          || registration.state != State.LIVE || registration.creatingContext != context) {
        throw new IllegalStateException("HTTP request no longer owns acquisition");
      }
    }
  }

  private void register(Context context) {
    Registration registration;
    synchronized (monitor) {
      if (!started || transportStopped || workerExited || unavailable != null) throw unavailable();
      registration = new Registration(this, context);
      requests.add(registration);
    }
    try {
      context.attribute(ATTRIBUTE, registration);
    } catch (RuntimeException | Error failure) {
      synchronized (monitor) {
        registration.creatingContext = null;
        registration.state = State.FAILED;
        if (unavailable == null) unavailable = failure;
        monitor.notifyAll();
      }
      throw failure;
    }
  }

  void after(Context context) {
    Object attribute = context.attribute(ATTRIBUTE);
    if (!(attribute instanceof Registration registration) || registration.owner != this) return;
    synchronized (monitor) {
      // This check MUST precede every handle/status lookup through the borrowed old Context.
      if (registration.state != State.LIVE || registration.creatingContext != context) return;
      try {
        capture(registration, context.req(), context.statusCode());
        registration.state = State.RELEASING;
      } catch (RuntimeException | Error failure) {
        captureFailed(registration, failure);
        throw failure;
      }
    }
    Throwable failure = release(registration);
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }

  private void terminal(Request request) {
    Registration captured = null;
    try {
      Object attribute = request.getAttribute(ATTRIBUTE);
      if (!(attribute instanceof Registration registration) || registration.owner != this) return;
      captured = registration;
      synchronized (monitor) {
        if (registration.state != State.LIVE) return;
        capture(registration, request, request.getResponse().getStatus());
        if (registration.mutation == null && registration.status == null && registration.work == null) {
          registration.state = State.ACKED;
          requests.remove(registration);
        } else registration.state = State.READY;
        monitor.notifyAll();
      }
    } catch (RuntimeException | Error failure) {
      // No external logging/release on a possibly nonblocking terminal callback.
      synchronized (monitor) {
        if (captured != null && captured.state == State.LIVE) captureFailed(captured, failure);
        else if (unavailable == null) unavailable = failure;
        monitor.notifyAll();
      }
    }
  }

  private void captureFailed(Registration registration, Throwable failure) {
    registration.creatingContext = null;
    registration.state = State.FAILED;
    if (unavailable == null) unavailable = failure;
    monitor.notifyAll();
  }

  // Only ordinary resolved request attributes, under the short metadata monitor. No handle calls.
  private void capture(Registration registration, jakarta.servlet.http.HttpServletRequest request,
      int statusCode) {
    registration.mutation = (OperationLeaseHandle) request.getAttribute(MUTATION_LEASE_ATTRIBUTE);
    registration.status = (StatusReservation) request.getAttribute(STATUS_RESERVATION_ATTRIBUTE);
    registration.work = (EngineWorkHandle) request.getAttribute(RequestEngineWork.ATTRIBUTE);
    registration.operationResponse = Boolean.TRUE.equals(request.getAttribute(RequestEngineWork.OPERATION_RESPONSE_ATTRIBUTE));
    registration.outcome = statusCode >= 500 ? OpLeaseOutcome.FAILURE : OpLeaseOutcome.SUCCESS;
    registration.creatingContext = null;
  }

  private Throwable release(Registration registration) {
    Throwable failure = null;
    try {
      if (registration.mutation != null) {
        try { registration.mutation.release(registration.outcome); }
        catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
      }
      if (registration.status != null) {
        try { registration.status.close(); }
        catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
      }
      if (registration.work != null) {
        if (registration.operationResponse) {
          try { registration.work.waitingClientGone(); }
          catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
        }
        try { registration.work.close(); }
        catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
      }
    } finally {
      synchronized (monitor) {
        registration.state = failure == null ? State.ACKED : State.FAILED;
        if (failure == null) requests.remove(registration);
        else if (unavailable == null) unavailable = failure;
        monitor.notifyAll();
      }
    }
    return failure;
  }

  private void releaseLoop() {
    Throwable structuralFailure = null;
    try {
      for (;;) {
        Registration selected = null;
        synchronized (monitor) {
          while (selected == null) {
            for (Registration request : requests) {
              if (request.state == State.READY) {
                selected = request;
                selected.state = State.RELEASING;
                break;
              }
            }
            if (selected != null) break;
            if (transportStopped && requests.isEmpty()) return;
            monitor.wait();
          }
        }
        Throwable failure = release(selected);
        if (failure != null) log.error("HTTP request release failed; new acquisitions are unavailable", failure);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      structuralFailure = interrupted;
    } catch (RuntimeException | Error failure) {
      structuralFailure = failure;
    } finally {
      synchronized (monitor) {
        workerFailure = structuralFailure;
        if (structuralFailure != null && unavailable == null) unavailable = structuralFailure;
        workerExited = true; // Actual Runnable exit, never Future cancellation or Thread.isAlive.
        monitor.notifyAll();
      }
    }
  }

  private void transportStopped() {
    synchronized (monitor) {
      transportStopped = true;
      monitor.notifyAll();
    }
  }

  boolean drained() {
    synchronized (monitor) { return transportStopped && requests.isEmpty() && workerExited; }
  }

  void stop(Javalin transport, long deadline) {
    synchronized (monitor) {
      if (Thread.currentThread() == worker) throw new IllegalStateException("HTTP release worker cannot wait for itself");
    }
    Server server = transport.jettyServer().server();
    // Also covers a later SERVER_STARTING listener failing before Jetty ever starts: there
    // is no lifeCycleStopped event for an already-STOPPED server, but the observation is real.
    if (server.isStopped()) transportStopped();
    StopAttempt attempt;
    boolean launch = false;
    synchronized (monitor) {
      if (stopAttempt == null || stopAttempt.exited) {
        attempt = new StopAttempt();
        attempt.thread = Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false)
            .name("http-transport-stop").unstarted(() -> {
          Throwable failure = null;
          try {
            try { if (!server.isStopped()) transport.stop(); }
            catch (RuntimeException | Error problem) { failure = problem; }
            try { awaitDrain(deadline); }
            catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
            Throwable structural;
            synchronized (monitor) { structural = workerFailure; }
            if (structural != null && structural != failure
                && (failure == null || structural != failure.getCause())) failure = combine(failure, structural);
          } finally {
            synchronized (monitor) {
              attempt.failure = failure;
              attempt.exited = true;
              monitor.notifyAll();
            }
          }
        });
        // Construction may throw. Publish only a fully constructed, startable attempt;
        // a genuinely started attempt then remains active through its Runnable's finally.
        stopAttempt = attempt;
        launch = true;
      } else attempt = stopAttempt;
    }
    if (Thread.currentThread() == attempt.thread) throw new IllegalStateException("HTTP stop attempt cannot wait for itself");
    if (launch) {
      try { attempt.thread.start(); }
      catch (RuntimeException | Error failure) {
        synchronized (monitor) {
          attempt.failure = failure;
          attempt.exited = true;
          monitor.notifyAll();
        }
        throw failure;
      }
    }
    try {
      synchronized (monitor) {
        while (!attempt.exited) waitUntil(deadline);
      }
    } catch (InterruptedException interrupted) {
      attempt.thread.interrupt(); // Only the existing stop invocation, never the release worker.
      Thread.currentThread().interrupt();
      throw new IllegalStateException("HTTP transport stop interrupted", interrupted);
    } catch (IllegalStateException exhausted) {
      attempt.thread.interrupt();
      throw exhausted;
    }
    if (attempt.failure instanceof RuntimeException failure) throw failure;
    if (attempt.failure instanceof Error failure) throw failure;
  }

  private void awaitDrain(long deadline) {
    try {
      synchronized (monitor) {
        for (;;) {
          if (unavailable != null) throw new IllegalStateException("HTTP request release did not acknowledge", unavailable);
          if (transportStopped && requests.isEmpty() && workerExited) return;
          waitUntil(deadline);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("HTTP request release drain interrupted", interrupted);
    }
  }

  private void waitUntil(long deadline) throws InterruptedException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new IllegalStateException("HTTP transport stop exhausted its supervision budget");
    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
  }

  private IllegalStateException unavailable() {
    return new IllegalStateException("HTTP request release owner is unavailable");
  }

  private static Throwable combine(Throwable first, Throwable next) {
    if (first == null) return next;
    if (first != next) first.addSuppressed(next);
    return first;
  }
}
