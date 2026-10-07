/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity-safe predecessor cleanup performed before any child-capable bootstrap begins.
 *
 * <p>ADR-0051 decision 2: no child outlives the Engine that started it. Spec B step 7: a child the
 * children ledger records inside its Engine's job ({@code child-start contained: true}) ends with
 * that job, and {@code LedgerReconciler} (run earlier, from {@code DefaultChildControl.openForEngine})
 * records its end ({@code owner-died}, or {@code child-survived}); this class never ends such a
 * child and keeps its record. Every other registered child whose identity (PID, start time and
 * executable) still matches a live process is terminated, the predecessor's llama-server included:
 * the fallback while this Engine has no job (containment unavailable) and for children of an
 * earlier version, which recorded no containment. Spec F step 13 retires the fallback one release
 * after the Engine job ships.
 *
 * <p>A record whose identity cannot be read is left alone with its live process (it may be
 * unrelated), and a record proved stale by a mismatch is dropped without touching the process that
 * now holds the PID.
 */
public final class ManagedChildReconciler {
  private static final Logger log = LoggerFactory.getLogger(ManagedChildReconciler.class);
  /** FILETIME ticks (100 ns since 1601) per millisecond, and the 1601 to 1970 offset in ms. */
  private static final long TICKS_PER_MS = 10_000L;
  private static final long FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L;

  private final ManagedChildRegistry registry;
  private final ContainedPredecessors contained;
  private final Termination termination;
  private RecordedFallback fallback;

  @FunctionalInterface
  private interface RecordedFallback {
    boolean stop(ManagedChild child) throws IOException;
  }

  /** Whether the children ledger records a registered child inside its Engine's job. */
  @FunctionalInterface
  public interface ContainedPredecessors {
    /** No child is known to be contained: every matched live child takes the fallback. */
    ContainedPredecessors NONE = child -> false;

    boolean contained(ManagedChild child);
  }

  @FunctionalInterface
  interface Termination {
    boolean terminate(ProcessHandle handle);
  }

  /** The fallback for every child: no containment is known. */
  public ManagedChildReconciler(ManagedChildRegistry registry) {
    this(registry, ContainedPredecessors.NONE, ManagedChildReconciler::terminateMatched);
  }

  /** Leaves the children {@code contained} names to their Engine's job; the rest take the fallback. */
  public static ManagedChildReconciler withContainment(
      ManagedChildRegistry registry, ContainedPredecessors contained) {
    return new ManagedChildReconciler(registry, contained, ManagedChildReconciler::terminateMatched);
  }

  /** The boot fallback's intent and result belong to the Engine that performs the stop. */
  public static ManagedChildReconciler withRecordedFallback(ManagedChildRegistry registry,
      ContainedPredecessors contained,
      io.justsearch.app.api.runtime.child.DefaultChildControl control, Path dataDir) {
    ManagedChildReconciler reconciler = withContainment(registry, contained);
    reconciler.fallback = child -> control.stopPredecessor(dataDir, child);
    return reconciler;
  }

  ManagedChildReconciler(ManagedChildRegistry registry, Termination termination) {
    this(registry, ContainedPredecessors.NONE, termination);
  }

  ManagedChildReconciler(ManagedChildRegistry registry, ContainedPredecessors contained,
      Termination termination) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contained = Objects.requireNonNull(contained, "contained");
    this.termination = Objects.requireNonNull(termination, "termination");
  }

  /**
   * The children the ledger in {@code dataDir} records inside an Engine job, matched by PID and
   * birth (the record's creation FILETIME at the millisecond of the manifest's start instant).
   * {@link ContainedPredecessors#NONE} when this Engine has no job, when the ledger cannot be read,
   * or for a child it has no such record of: each of those takes the fallback.
   */
  public static ContainedPredecessors fromChildrenLedger(Path dataDir, boolean engineContained) {
    if (!engineContained) return ContainedPredecessors.NONE;
    Set<String> births = new HashSet<>();
    try {
      for (LedgerRecord record : ExitLedgerReader.read(
          LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records()) {
        if (record.type() != LedgerRecordType.CHILD_START) continue;
        if (!record.field("contained").isBoolean() || !record.field("contained").asBoolean()) continue;
        Optional<String> birth = record.text("creationFileTime");
        if (birth.isEmpty() || !birth.get().matches("[1-9][0-9]{0,19}")) continue;
        long ms = Long.parseLong(birth.get()) / TICKS_PER_MS - FILETIME_EPOCH_OFFSET_MS;
        births.add(record.field("pid").asLong() + "/" + ms);
      }
    } catch (IOException | RuntimeException unreadable) {
      log.warn("children ledger unreadable; predecessor children take the boot fallback: {}",
          unreadable.toString());
      return ContainedPredecessors.NONE;
    }
    return child -> {
      try {
        return births.contains(child.pid() + "/" + Instant.parse(child.startedAt()).toEpochMilli());
      } catch (RuntimeException invalid) {
        return false;
      }
    };
  }

  public void reconcile() throws IOException {
    for (ManagedChild child : registry.snapshot()) reconcile(child);
  }

  private void reconcile(ManagedChild child) throws IOException {
    Optional<ProcessHandle> found = ProcessHandle.of(child.pid());
    if (found.isEmpty() || !found.get().isAlive()) {
      if (fallback != null) fallback.stop(child);
      registry.remove(child.id());
      return;
    }
    ProcessHandle handle = found.get();
    ManagedChild.IdentityMatch identity = child.identityOf(handle);
    if (identity == ManagedChild.IdentityMatch.UNKNOWN) {
      return; // unknown identity: preserve evidence and do not touch the live process
    }
    if (identity == ManagedChild.IdentityMatch.MISMATCH) {
      registry.remove(child.id()); // proved stale/mismatched record; live process is unrelated
      return;
    }
    // A contained survivor is the ledger's to record (child-survived): left running, record kept.
    if (contained.contained(child)) return;
    // Fallback: a verified-owned predecessor child, of every kind, ends with its Engine.
    if (fallback != null ? fallback.stop(child) : termination.terminate(handle)) registry.remove(child.id());
  }

  private static boolean terminateMatched(ProcessHandle handle) {
    handle.destroy();
    try {
      if (handle.isAlive()) handle.onExit().get(5, TimeUnit.SECONDS);
      if (handle.isAlive()) {
        handle.destroyForcibly();
        handle.onExit().get(5, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    return !handle.isAlive();
  }
}
