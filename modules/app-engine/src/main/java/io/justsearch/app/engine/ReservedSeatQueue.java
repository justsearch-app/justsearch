/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Authoritative dispatch buffer for a fixed pool's seats. Used seats are exactly entries.size()
 * plus outside (submissions not yet buffered and tasks handed to workers). All three transitions
 * share one lock: reserve, buffer, and worker extraction. External removals change only entries,
 * so inherited Collection removal APIs cannot leave a separate reservation behind. Entries have
 * occurrence identity, including when the same Runnable is submitted more than once.
 *
 * <p>Admission never calls the executor under the queue lock: ThreadPoolExecutor shutdown holds its main
 * lock while draining this queue, whereas worker creation needs that main lock.
 */
final class ReservedSeatQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
  private enum State { PENDING, QUEUED, RUNNING, REMOVED }

  static final class Seat implements Runnable {
    final Runnable task;
    private final ReservedSeatQueue owner;
    private State state = State.PENDING;

    Seat(ReservedSeatQueue owner, Runnable task) { this.owner = owner; this.task = task; }
    @Override public void run() { task.run(); }
  }

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition available = lock.newCondition();
  private final Condition space = lock.newCondition();
  private final LinkedList<Seat> entries = new LinkedList<>();
  private final int capacity;
  private int outside;
  final ThreadLocal<Boolean> executing = ThreadLocal.withInitial(() -> false);
  BooleanSupplier worker = () -> false;

  ReservedSeatQueue(int capacity) { this.capacity = capacity; }

  Seat reserve(Runnable task) {
    lock.lock();
    try {
      if (usedLocked() == capacity) return null;
      Seat seat = new Seat(this, task);
      outside++;
      return seat;
    } finally { lock.unlock(); }
  }

  void reject(Seat seat) {
    lock.lock();
    try {
      if (seat.state == State.PENDING) {
        outside--;
        seat.state = State.REMOVED;
      } else if (seat.state == State.QUEUED) {
        removeEntry(seat);
      }
      // A shutdown recheck may already have removed it. A worker may already own it
      // if addWorker(null) failed after a successful offer; that worker returns its seat.
      space.signalAll();
    } finally { lock.unlock(); }
  }

  void completed() {
    lock.lock();
    try { outside--; space.signalAll(); }
    finally { lock.unlock(); }
  }

  int used() {
    lock.lock();
    try { return usedLocked(); }
    finally { lock.unlock(); }
  }

  private int usedLocked() { return outside + entries.size(); }

  private boolean offerLocked(Runnable task) {
    Seat seat;
    if (task instanceof Seat reserved && reserved.owner == this && reserved.state == State.PENDING) {
      seat = reserved;
    } else {
      if (usedLocked() == capacity) return false;
      seat = new Seat(this, task);
    }
    entries.addLast(seat);
    if (seat.state == State.PENDING && task == seat) outside--;
    seat.state = State.QUEUED;
    available.signal();
    return true;
  }

  @Override
  public boolean offer(Runnable task) {
    Objects.requireNonNull(task, "task");
    lock.lock();
    try { return offerLocked(task); }
    finally { lock.unlock(); }
  }

  @Override
  public void put(Runnable task) throws InterruptedException {
    Objects.requireNonNull(task, "task");
    lock.lockInterruptibly();
    try { while (!offerLocked(task)) space.await(); }
    finally { lock.unlock(); }
  }

  @Override
  public boolean offer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
    Objects.requireNonNull(task, "task");
    long remaining = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (!offerLocked(task)) {
        if (remaining <= 0) return false;
        remaining = space.awaitNanos(remaining);
      }
      return true;
    } finally { lock.unlock(); }
  }

  private Runnable extract() {
    Seat seat = entries.pollFirst();
    if (seat == null) return null;
    if (worker.getAsBoolean() && !executing.get()) {
      outside++;
      seat.state = State.RUNNING;
      return seat;
    } else {
      seat.state = State.REMOVED;
      space.signalAll();
    }
    return seat.task;
  }

  @Override
  public Runnable poll() {
    lock.lock();
    try { return extract(); }
    finally { lock.unlock(); }
  }

  @Override
  public Runnable take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (entries.isEmpty()) available.await();
      return extract();
    } finally { lock.unlock(); }
  }

  @Override
  public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
    long remaining = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (entries.isEmpty()) {
        if (remaining <= 0) return null;
        remaining = available.awaitNanos(remaining);
      }
      return extract();
    } finally { lock.unlock(); }
  }

  @Override
  public Runnable peek() {
    lock.lock();
    try { return entries.isEmpty() ? null : entries.getFirst().task; }
    finally { lock.unlock(); }
  }

  @Override
  public int size() {
    lock.lock();
    try { return entries.size(); }
    finally { lock.unlock(); }
  }

  @Override
  public int remainingCapacity() {
    lock.lock();
    try { return capacity - usedLocked(); }
    finally { lock.unlock(); }
  }

  private void removeEntry(Seat seat) {
    if (entries.remove(seat)) {
      seat.state = State.REMOVED;
      space.signalAll();
    }
  }

  @Override
  public boolean remove(Object task) {
    lock.lock();
    try {
      for (Seat seat : entries) {
        // Executor shutdown rechecks use the internal occurrence; callers use the original.
        if (task == seat || (task != null && task.equals(seat.task))) {
          removeEntry(seat);
          return true;
        }
      }
      return false;
    } finally { lock.unlock(); }
  }

  @Override
  public Iterator<Runnable> iterator() {
    final Iterator<Seat> snapshot;
    lock.lock();
    try { snapshot = new ArrayList<>(entries).iterator(); }
    finally { lock.unlock(); }
    return new Iterator<>() {
      private Seat current;
      @Override public boolean hasNext() { return snapshot.hasNext(); }
      @Override public Runnable next() { current = snapshot.next(); return current.task; }
      @Override public void remove() {
        if (current == null) throw new IllegalStateException();
        lock.lock();
        try { removeEntry(current); current = null; }
        finally { lock.unlock(); }
      }
    };
  }

  @Override
  public void clear() {
    lock.lock();
    try {
      entries.forEach(seat -> seat.state = State.REMOVED);
      entries.clear();
      space.signalAll();
    } finally { lock.unlock(); }
  }

  @Override
  public int drainTo(Collection<? super Runnable> target) { return drainTo(target, Integer.MAX_VALUE); }

  @Override
  public int drainTo(Collection<? super Runnable> target, int maxElements) {
    Objects.requireNonNull(target, "target");
    if (target == this) throw new IllegalArgumentException("Cannot drain to self");
    lock.lock();
    try {
      int count = 0;
      for (Seat seat : new ArrayList<>(entries)) {
        if (count >= maxElements) break;
        if (seat.state != State.QUEUED) continue;
        target.add(seat.task);
        removeEntry(seat);
        count++;
      }
      return count;
    } finally { lock.unlock(); }
  }
}
