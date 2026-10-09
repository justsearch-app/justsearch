/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the CPU recourse of turned-away query legs did (see {@link NativeSessionHandle#acquire}): a
 * foreground query leg the device cannot seat runs on the handle's warm CPU session instead.
 *
 * <p>Two records, for two readers:
 *
 * <ul>
 *   <li>process-wide, when the recourse last successfully served a query leg ({@link #lastTakenNanos()}), which the
 *       worker health reports so status can say that searches are being answered on the CPU;
 *   <li>per leg, on the calling thread, which device the leg's lease was on and whether the
 *       recourse took it ({@link #observe()}), which the search's trace records.
 * </ul>
 */
public final class QueryCpuRecourse {

  /**
   * How long after the recourse last successfully served a query leg status still says searches are answered on
   * the CPU: 60 000 ms. Long enough to span the gaps between an agent's or a user's searches while
   * bulk work holds the device, short enough that the notice clears within a minute of the device
   * serving queries again.
   */
  public static final long RECENT_MS = 60_000L;

  private static final AtomicLong LAST_TAKEN_NANOS = new AtomicLong();
  private static volatile boolean taken;

  private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();

  private QueryCpuRecourse() {}

  /**
   * When the recourse last successfully served a query leg in this process, on {@link System#nanoTime()}, or
   * empty when it never has.
   */
  public static OptionalLong lastTakenNanos() {
    return taken ? OptionalLong.of(LAST_TAKEN_NANOS.get()) : OptionalLong.empty();
  }

  /**
   * Starts observing the leases handed out on this thread until the observation is closed. Nested
   * observations are independent; closing one restores the enclosing one.
   */
  public static Observation observe() {
    Observation observation = new Observation(CURRENT.get());
    CURRENT.set(observation);
    return observation;
  }

  /** A lease was handed out on this thread: {@code cpu} when it is a CPU lease. */
  static void leased(boolean cpu) {
    Observation observation = CURRENT.get();
    if (observation != null) observation.cpu = cpu;
  }

  /**
   * The recourse took a query leg on this thread, because the device was {@code cause} ({@code
   * contended}: no seat within the seat wait; {@code stuck}: its breaker is open).
   */
  static void took(String cause) {
    Observation observation = CURRENT.get();
    if (observation != null) observation.cpuRecourse = cause;
  }

  /**
   * The device that served a query leg ({@code cpu} or {@code gpu}), and the device's state when
   * the CPU recourse took it ({@code contended} or {@code stuck}), or null when it did not.
   */
  public record Served(String device, String cpuRecourse) {

    /** The leg's note in the search trace, e.g. {@code device=cpu; cpu-recourse=contended}. */
    public String traceDetail() {
      return cpuRecourse == null
          ? "device=" + device
          : "device=" + device + "; cpu-recourse=" + cpuRecourse;
    }
  }

  /** The leases handed out on one thread while it is open. */
  public static final class Observation implements AutoCloseable {
    private final Observation enclosing;
    private Boolean cpu;
    private String cpuRecourse;

    private Observation(Observation enclosing) {
      this.enclosing = enclosing;
    }

    /** Marks a successfully returned query encoding; attempted or detached runs do not count. */
    public void succeeded() {
      if (Boolean.TRUE.equals(cpu) && cpuRecourse != null) {
        LAST_TAKEN_NANOS.set(System.nanoTime());
        taken = true;
      }
    }

    /** The last lease's device and whether the recourse took the leg, or null without a lease. */
    public Served served() {
      Boolean onCpu = cpu;
      if (onCpu == null) return null;
      return new Served(onCpu ? "cpu" : "gpu", cpuRecourse);
    }

    @Override
    public void close() {
      if (CURRENT.get() == this) {
        if (enclosing == null) CURRENT.remove();
        else CURRENT.set(enclosing);
      }
    }
  }
}
