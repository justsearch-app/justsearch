/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one removal lock (design D4, fourth decision 2b). The AI install owner creates it and holds
 * it for a whole "Remove files" run; every other owner that can start using managed model files
 * either registers as a user for the length of its work (install, Repair, Download again, pack
 * import, runtime activation and deactivation) or asks it at its own entry point (settings,
 * installed-model activation, the managed answer server launch).
 *
 * <p>The two sides exclude each other in both orders under this object's monitor: a user cannot
 * enter while a removal holds the lock, and a removal cannot begin while any user is registered.
 * Neither waits: the loser is refused with a plain reason.
 */
public final class ModelFileRemovalFence {

  /** The refusal every fenced route gives while files are being removed. */
  public static final String REMOVING = "Removing files - try again when it finishes";

  private final Map<Long, String> users = new LinkedHashMap<>();
  private long nextUser;
  private String removing;

  /** A registration that ends when closed; closing is idempotent. */
  public interface Ticket extends AutoCloseable {
    @Override
    void close();
  }

  /** For owners composed without an install owner (isolated tests): nothing to register with. */
  public static final Ticket NONE = () -> {};

  /** {@link #enter} on {@code fence}, or {@link #NONE} when no fence is bound. */
  public static Ticket enterIfBound(ModelFileRemovalFence fence, String route) {
    return fence == null ? NONE : fence.enter(route);
  }

  /** A registered user of managed model files; closing it is idempotent. */
  public final class User implements Ticket {
    private final long id;
    private final AtomicBoolean closed = new AtomicBoolean();

    private User(long id) {
      this.id = id;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (ModelFileRemovalFence.this) {
        users.remove(id);
      }
    }
  }

  /** The held removal lock; closing it is idempotent. */
  public final class Removal implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    private Removal() {}

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (ModelFileRemovalFence.this) {
        removing = null;
      }
    }
  }

  /** Thrown to a route that tries to start while a removal holds the lock. */
  public static final class Refused extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message);
    }
  }

  /**
   * Registers {@code route} (plain words, e.g. "A download") as a user until the result closes.
   *
   * @throws Refused while a removal holds the lock
   */
  public synchronized User enter(String route) {
    if (removing != null) throw new Refused(REMOVING);
    long id = ++nextUser;
    users.put(id, route);
    return new User(id);
  }

  /** Point check for an entry that does not hold its work open here. */
  public synchronized void refuseWhileRemoving() {
    if (removing != null) throw new Refused(REMOVING);
  }

  /** The part whose files are being removed now, if any. */
  public synchronized Optional<String> removingPart() {
    return Optional.ofNullable(removing);
  }

  /**
   * Takes the lock for {@code packageId}.
   *
   * @throws Refused while another removal runs, or while a user is registered (its route is named)
   */
  synchronized Removal begin(String packageId) {
    if (removing != null) throw new Refused(REMOVING);
    if (!users.isEmpty()) {
      throw new Refused(users.values().iterator().next() + " is running - try again when it finishes");
    }
    removing = packageId;
    return new Removal();
  }
}
