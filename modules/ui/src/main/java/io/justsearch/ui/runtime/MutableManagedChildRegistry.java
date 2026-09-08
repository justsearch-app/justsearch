/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The process-scoped mutable child register; its installed writer is the manifest publisher. */
public final class MutableManagedChildRegistry implements ManagedChildRegistry {
  @FunctionalInterface
  interface Writer {
    void persist(List<ManagedChild> children) throws IOException;
  }

  @FunctionalInterface
  interface SnapshotOperation<T> {
    T apply(List<ManagedChild> children) throws IOException;
  }

  private List<ManagedChild> children = List.of();
  private Writer writer;

  synchronized void seed(List<ManagedChild> predecessor) {
    if (writer != null) throw new IllegalStateException("registry already activated");
    children = predecessor == null ? List.of() : List.copyOf(predecessor);
  }

  synchronized void installWriter(Writer writer) {
    if (this.writer != null) throw new IllegalStateException("registry writer already installed");
    this.writer = writer;
  }

  @Override
  public synchronized List<ManagedChild> snapshot() {
    return children;
  }

  /** Hold the registry stable while a registry-ordered manifest operation completes. */
  synchronized <T> T withStableSnapshot(SnapshotOperation<T> operation) throws IOException {
    return operation.apply(children);
  }

  @Override
  public synchronized void register(ManagedChild child) throws IOException {
    if (child == null) throw new IllegalArgumentException("child must be non-null");
    List<ManagedChild> next = new ArrayList<>(children);
    next.removeIf(existing -> existing.id().equals(child.id()));
    next.add(child);
    persistThenSet(next);
  }

  @Override
  public synchronized void remove(String childId) throws IOException {
    List<ManagedChild> next = new ArrayList<>(children);
    if (!next.removeIf(child -> child.id().equals(childId))) return;
    persistThenSet(next);
  }

  private void persistThenSet(List<ManagedChild> next) throws IOException {
    if (writer == null) throw new IOException("managed-child registry is not persistence-ready");
    List<ManagedChild> immutable = List.copyOf(next);
    writer.persist(immutable);
    children = immutable;
  }
}
