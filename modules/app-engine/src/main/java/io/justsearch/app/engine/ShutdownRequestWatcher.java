/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls for {@link ShutdownRequest} and runs the ordered shutdown when one appears (design 7.3,
 * stage B item B3).
 *
 * <p><b>Its own thread, and that is the entire point.</b> Design 7.3 is explicit that this must
 * never run on the API pool: the case a supervisor exists for is an Engine that answers no HTTP,
 * and a watcher sharing the pool with the API front would be starved by exactly the condition it
 * exists to resolve. One scheduled single-thread executor, named, so the separation is assertable
 * rather than assumed.
 *
 * <p><b>Started after readiness, deliberately.</b> A request that arrives during boot is left on
 * disk rather than acted on half-way through startup; the supervisor's deadline covers the case
 * where an Engine never reaches readiness at all, and killing a booting process is the supervisor's
 * job, not the booting process's.
 *
 * <p><b>The poll is the mechanism, not a fallback.</b> A filesystem watch would be lighter, but the
 * request can be written by a Rust process or a Node process on any filesystem the user's data
 * directory happens to live on, including network paths where change notification is unreliable. A
 * poll that costs one {@code isRegularFile} per second is the honest choice.
 */
public final class ShutdownRequestWatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ShutdownRequestWatcher.class);

  /** The watcher thread's name. Asserted against, so it is part of the contract. */
  public static final String THREAD_NAME = "engine-shutdown-request-watcher";

  /** Poll cadence. A shutdown that starts a second late is not a defect; a busy loop is. */
  public static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;

  /**
   * The deadline {@code commit-shutdown} stamps on its request (item B6).
   *
   * <p>Generous on purpose. The updater has already waited for every lease to drain before it
   * committed, so what remains is the ordered close itself — closing the index and checkpointing
   * SQLite over a large corpus. A deadline that expires mid-close would have the supervisor kill an
   * Engine that is writing the index, which is the one thing the ordered shutdown exists to avoid.
   */
  public static final long UPGRADE_DEADLINE_MS = 120_000L;

  private final Path runtimeDir;
  private final Consumer<ShutdownRequest> onRequest;
  private final Predicate<ShutdownRequest> accepts;
  private final long pollIntervalMs;
  private final AtomicBoolean fired = new AtomicBoolean();
  private volatile ScheduledExecutorService executor;

  /**
   * @param runtimeDir the {@code <dataDir>/runtime/} directory to watch
   * @param accepts whether a well-formed request should be acted on — the seam where nonce policy
   *     lives, because the watcher does not know which nonce the updater minted and should not
   *     pretend to. Returning {@code false} logs and ignores, and the file is deleted so the
   *     rejected request is not re-read every second.
   * @param onRequest what to run when a request is accepted; in production, the ordered shutdown
   */
  public ShutdownRequestWatcher(
      Path runtimeDir,
      Predicate<ShutdownRequest> accepts,
      Consumer<ShutdownRequest> onRequest,
      long pollIntervalMs) {
    this.runtimeDir = runtimeDir;
    this.accepts = accepts == null ? r -> true : accepts;
    this.onRequest = onRequest;
    this.pollIntervalMs = pollIntervalMs;
  }

  /** Starts polling. Idempotent; a second call is ignored. */
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, THREAD_NAME);
              t.setDaemon(true);
              return t;
            });
    executor.scheduleWithFixedDelay(
        this::pollOnce, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    log.info(
        "Watching {} for shutdown requests every {}ms on {}",
        ShutdownRequest.pathIn(runtimeDir),
        pollIntervalMs,
        THREAD_NAME);
  }

  /**
   * One poll. Package-private so a test can drive it without waiting on a scheduler.
   *
   * <p>Never throws: this runs on a {@code scheduleWithFixedDelay} task, and an escaping exception
   * would cancel the schedule silently — the watcher would stop watching and nothing would say so.
   */
  void pollOnce() {
    try {
      if (fired.get()) {
        return;
      }
      Optional<ShutdownRequest> request = ShutdownRequest.read(runtimeDir);
      if (request.isEmpty()) {
        // Absent, malformed or unknown-reason. ShutdownRequest.read has already logged why.
        return;
      }
      ShutdownRequest req = request.get();
      if (!accepts.test(req)) {
        log.warn(
            "Ignoring a shutdown request with reason {} (issuedBy={}): it was refused by the"
                + " acceptance check, most likely a nonce that does not match this Engine's"
                + " upgrade. Deleting it so it is not re-read every poll.",
            req.reason().wire(),
            req.issuedBy());
        ShutdownRequest.clear(runtimeDir);
        return;
      }
      if (!fired.compareAndSet(false, true)) {
        return;
      }
      log.info(
          "Shutdown request accepted (reason={}, issuedBy={}, deadlineEpochMs={})",
          req.reason().wire(),
          req.issuedBy(),
          req.deadlineEpochMs());
      // Consume BEFORE acting: the ordered shutdown can take seconds, and a request still on disk
      // when the next Engine starts would shut the new one down too.
      ShutdownRequest.clear(runtimeDir);
      onRequest.accept(req);
    } catch (Exception e) {
      log.warn("Shutdown-request poll failed (continuing to watch): {}", e.toString());
    }
  }

  /** Whether a request has been accepted and acted on. */
  public boolean hasFired() {
    return fired.get();
  }

  @Override
  public synchronized void close() {
    ScheduledExecutorService e = executor;
    executor = null;
    if (e != null) {
      e.shutdownNow();
    }
  }
}
