/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker for the LLM engine to prevent cascading failures after native crashes.
 *
 * <h2>States</h2>
 * <ul>
 *   <li><strong>CLOSED</strong> - Normal operation, requests allowed</li>
 *   <li><strong>OPEN</strong> - Failure threshold exceeded, requests rejected</li>
 *   <li><strong>HALF_OPEN</strong> - Testing if recovery is possible</li>
 * </ul>
 *
 * <h2>Transitions</h2>
 * <ul>
 *   <li>CLOSED → OPEN: After {@code failureThreshold} consecutive failures OR any fatal error</li>
 *   <li>OPEN → HALF_OPEN: After {@code recoveryTimeout} has passed</li>
 *   <li>HALF_OPEN → CLOSED: After a successful request</li>
 *   <li>HALF_OPEN → OPEN: After any failed recovery request, regardless of the failure count</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EngineCircuitBreaker breaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));
 *
 * void processRequest() throws BackendException {
 *   breaker.requireClosed();  // Throws if circuit is open
 *   try {
 *     // ... process request ...
 *     breaker.recordSuccess();
 *   } catch (Exception e) {
 *     breaker.recordFailure(e);
 *     throw e;
 *   }
 * }
 * }</pre>
 */
public final class EngineCircuitBreaker {
  private static final Logger LOG = LoggerFactory.getLogger(EngineCircuitBreaker.class);

  public enum State {
    CLOSED,    // Normal operation
    OPEN,      // Rejecting requests
    HALF_OPEN  // Testing recovery
  }

  private final int failureThreshold;
  private final Duration recoveryTimeout;
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicReference<Instant> openedAt = new AtomicReference<>(null);
  private final AtomicReference<Throwable> lastFailure = new AtomicReference<>(null);
  private final AtomicReference<String> tripReason = new AtomicReference<>(null);

  /**
   * Creates a circuit breaker with the specified thresholds.
   *
   * @param failureThreshold number of consecutive failures before opening (0 = never auto-open)
   * @param recoveryTimeout time to wait before attempting recovery
   */
  public EngineCircuitBreaker(int failureThreshold, Duration recoveryTimeout) {
    this.failureThreshold = Math.max(0, failureThreshold);
    this.recoveryTimeout = recoveryTimeout != null ? recoveryTimeout : Duration.ofMinutes(1);
  }

  /**
   * Creates a circuit breaker with default settings (5 failures, 1 minute recovery).
   */
  public EngineCircuitBreaker() {
    this(5, Duration.ofMinutes(1));
  }

  /**
   * Returns the current circuit state.
   */
  public State state() {
    updateStateIfNeeded();
    return state.get();
  }

  /**
   * Returns true if the circuit is closed (normal operation).
   */
  public boolean isClosed() {
    return state() == State.CLOSED;
  }

  /**
   * Returns true if the circuit is open (rejecting requests).
   */
  public boolean isOpen() {
    State current = state();
    return current == State.OPEN || current == State.HALF_OPEN;
  }

  /**
   * Throws BackendException if the circuit is open.
   *
   * @throws BackendException with category FATAL if circuit is open
   */
  public void requireClosed() throws BackendException {
    updateStateIfNeeded();
    State current = state.get();
    if (current == State.OPEN) {
      throw BackendException.circuitOpen();
    }
    // HALF_OPEN allows one request through for testing
  }

  /**
   * Records a successful operation, potentially closing the circuit.
   */
  public void recordSuccess() {
    consecutiveFailures.set(0);
    State previous = state.getAndSet(State.CLOSED);
    if (previous != State.CLOSED) {
      LOG.info("Circuit breaker CLOSED after successful request");
      tripReason.set(null);
      lastFailure.set(null);
      openedAt.set(null);
    }
  }

  /**
   * Records a failed operation, potentially opening the circuit.
   *
   * @param failure the exception that caused the failure
   */
  public void recordFailure(Throwable failure) {
    lastFailure.set(failure);

    // Fatal errors immediately open the circuit
    if (isFatal(failure)) {
      tripCircuit("fatal error: " + summarize(failure));
      return;
    }

    // Non-fatal errors increment counter
    int failures = consecutiveFailures.incrementAndGet();
    if (state.get() == State.HALF_OPEN) {
      tripCircuit("recovery request failed: " + summarize(failure));
    } else if (failureThreshold > 0 && failures >= failureThreshold) {
      tripCircuit("consecutive failures: " + failures);
    }
  }

  /**
   * Manually opens the circuit.
   *
   * @param reason human-readable reason for opening
   */
  public void trip(String reason) {
    tripCircuit(reason);
  }

  /**
   * Manually resets the circuit to closed state.
   */
  public void reset() {
    consecutiveFailures.set(0);
    state.set(State.CLOSED);
    openedAt.set(null);
    tripReason.set(null);
    lastFailure.set(null);
    LOG.info("Circuit breaker manually reset to CLOSED");
  }

  /**
   * Returns the last failure that was recorded, if any.
   */
  public Throwable lastFailure() {
    return lastFailure.get();
  }

  /**
   * Returns the reason the circuit was tripped, if any.
   */
  public String tripReason() {
    return tripReason.get();
  }

  /**
   * Returns the number of consecutive failures.
   */
  public int consecutiveFailures() {
    return consecutiveFailures.get();
  }

  // --- Internal ---

  private void tripCircuit(String reason) {
    State previous = state.getAndSet(State.OPEN);
    if (previous != State.OPEN) {
      openedAt.set(Instant.now());
      tripReason.set(reason);
      LOG.warn("Circuit breaker OPENED: {}", reason);
    }
  }

  private void updateStateIfNeeded() {
    if (state.get() == State.OPEN) {
      Instant opened = openedAt.get();
      if (opened != null && Instant.now().isAfter(opened.plus(recoveryTimeout))) {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
          LOG.info("Circuit breaker transitioning to HALF_OPEN, testing recovery");
        }
      }
    }
  }

  /**
   * Determines if a failure is fatal (requires engine restart).
   */
  private static boolean isFatal(Throwable t) {
    if (t == null) {
      return false;
    }

    // Java Errors are always fatal (OOM, StackOverflow, etc.)
    if (t instanceof Error) {
      return true;
    }

    // BackendException with FATAL category
    if (t instanceof BackendException be && be.category() == BackendException.Category.FATAL) {
      return true;
    }

    // Check message for native crash patterns
    String msg = t.getMessage();
    if (msg != null) {
      String lower = msg.toLowerCase(Locale.ROOT);
      if (lower.contains("ggml_assert") ||
          lower.contains("sigsegv") ||
          lower.contains("sigabrt") ||
          lower.contains("access violation") ||
          lower.contains("fatal error") ||
          lower.contains("native_error")) {
        return true;
      }
    }

    // Check cause recursively
    Throwable cause = t.getCause();
    return cause != null && cause != t && isFatal(cause);
  }

  private static String summarize(Throwable t) {
    if (t == null) {
      return "unknown";
    }
    String msg = t.getMessage();
    if (msg == null || msg.isBlank()) {
      return t.getClass().getSimpleName();
    }
    // Truncate long messages
    if (msg.length() > 100) {
      return msg.substring(0, 100) + "...";
    }
    return msg;
  }
}
