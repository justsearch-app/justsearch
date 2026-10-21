/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.containment.WindowsEngineJob;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries exact Windows job membership across a wrapper's exit. Other platforms retain the
 * original descendant handles observed while an ancestor was alive; only those birth-checked
 * handles authorize cleanup. On Windows {@link #end} ends the root's own nested job. An unreadable
 * query blocks this attempt; later callers retry rather than latching a failure.
 */
final class ChildDescendants implements AutoCloseable {
  private final ProcessHandle root;
  private final WindowsEngineJob.ChatProcess contained;
  private final Map<ProcessHandle, Instant> observed = new LinkedHashMap<>();
  private String failure;

  ChildDescendants(ProcessHandle root) {
    this.root = root;
    this.contained = null;
    observe();
  }

  ChildDescendants(Process process) {
    this.root = process.toHandle();
    this.contained = process instanceof WindowsEngineJob.ChatProcess chat ? chat : null;
    if (contained == null) observe();
  }

  synchronized void observe() {
    failure = null;
    try {
      if (root.isAlive()) capture(root);
      // Descendants can themselves launch children, including after the original wrapper exits.
      for (ProcessHandle process : List.copyOf(observed.keySet())) {
        if (sameProcess(process, observed.get(process))) capture(process);
      }
    } catch (RuntimeException unreadable) {
      failure = "descendant enumeration unavailable for pid " + root.pid();
    }
  }

  private void capture(ProcessHandle parent) {
    try (var descendants = parent.descendants()) {
      descendants.forEach(process -> {
        var birth = process.info().startInstant();
        if (birth.isPresent()) observed.putIfAbsent(process, birth.get());
        else if (ProcessTerminator.isRunning(process))
          failure = "descendant identity unavailable for pid " + process.pid();
      });
    }
  }

  synchronized List<ProcessHandle> snapshot() {
    if (contained != null) return contained.scopeDescendants();
    observe();
    if (failure != null) throw new IllegalStateException(failure);
    try {
      return observed.entrySet().stream().filter(e -> sameProcess(e.getKey(), e.getValue()))
          .map(Map.Entry::getKey).toList();
    } catch (RuntimeException unreadable) {
      failure = "descendant identity unavailable for pid " + root.pid();
      throw new IllegalStateException(failure, unreadable);
    }
  }

  /** Ends the job on Windows; elsewhere TERM, then KILL, through the retained JDK handles. */
  synchronized void end(List<ProcessHandle> members) throws java.io.IOException {
    if (contained != null) {
      contained.endScope();
      return;
    }
    java.io.IOException failure = null;
    for (ProcessHandle member : members) {
      try {
        if (ProcessTerminator.isRunning(member)) member.destroy();
      } catch (RuntimeException denied) {
        if (failure == null) failure = new java.io.IOException("descendant TERM refused", denied);
        else failure.addSuppressed(denied);
      }
    }
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
    boolean interrupted = false;
    for (ProcessHandle member : members) {
      try {
        ProcessTerminator.awaitExit(member,
            java.time.Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
      } catch (InterruptedException stop) {
        interrupted = true;
        break;
      }
    }
    // Always attempt escalation for every survivor, even when the grace wait was interrupted.
    for (ProcessHandle member : members) {
      try {
        if (ProcessTerminator.isRunning(member)) member.destroyForcibly();
      } catch (RuntimeException denied) {
        if (failure == null) failure = new java.io.IOException("descendant KILL refused", denied);
        else failure.addSuppressed(denied);
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    if (failure != null && members.stream().anyMatch(ProcessTerminator::isRunning)) throw failure;
  }

  @Override
  public synchronized void close() {
    if (contained != null) contained.releaseScope();
  }

  private static boolean sameProcess(ProcessHandle process, Instant birth) {
    if (!ProcessTerminator.isRunning(process)) return false;
    // Missing identity is uncertainty, so continue waiting; a different birth is another process.
    return process.info().startInstant().map(birth::equals).orElse(true);
  }
}
