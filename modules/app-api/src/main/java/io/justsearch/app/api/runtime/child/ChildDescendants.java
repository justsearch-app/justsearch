/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers observed descendant identities across a wrapper's exit. This is a waiting barrier,
 * never permission to terminate a process. The Engine's containment owns orphan termination.
 * An unreadable enumeration stays unknown: it cannot certify that no descendants survived.
 */
final class ChildDescendants {
  private final ProcessHandle root;
  private final Map<ProcessHandle, Instant> observed = new LinkedHashMap<>();
  private String failure;

  ChildDescendants(ProcessHandle root) {
    this.root = root;
    observe();
  }

  synchronized void observe() {
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
        else if (process.isAlive()) failure = "descendant identity unavailable for pid " + process.pid();
      });
    }
  }

  synchronized List<ProcessHandle> snapshot() {
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

  synchronized void trackingUnavailable() {
    failure = "descendant tracking unavailable for pid " + root.pid();
  }

  private static boolean sameProcess(ProcessHandle process, Instant birth) {
    if (!process.isAlive()) return false;
    // Missing identity is uncertainty, so continue waiting; a different birth is another process.
    return process.info().startInstant().map(birth::equals).orElse(true);
  }
}
