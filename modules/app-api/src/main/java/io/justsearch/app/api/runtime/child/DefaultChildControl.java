/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.containment.WindowsEngineJob;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Engine's {@link ChildControl} (Spec B §2): every child it starts, and every stop of one, is
 * recorded in {@code children.v1.jsonl} before the action.
 *
 * <p><b>Stop (§2.2).</b> Under the handle's monitor a stop only changes state; the ledger writes
 * and the waits run outside it.
 *
 * <ol start="0">
 *   <li>EXITED returns the recorded exit and writes nothing. STOPPING returns the same future; NOW
 *       over a COOPERATIVE stop appends {@code stop-escalate} and cuts the grace. SURVIVED retries
 *       the terminate under the first intent.
 *   <li>Allocate the intent; state STOPPING.
 *   <li>Append {@code stop-intent} on the caller's thread, before anything acts. A failed write
 *       does not stop the stop; the outcome says {@code ledgerDurable=false}.
 *   <li>GPU child and the reason continues the Engine: sample adapter memory.
 *   <li>COOPERATIVE with a cooperative step: close stdin and wait out the grace ({@link #stopAll}
 *       shares one window).
 *   <li>Still alive: {@code terminate(ourCode)}.
 *   <li>Wait 5 s; one more terminate and 5 s; then SURVIVED, {@code child-survived}, and the stop
 *       completes with {@link ChildSurvivedException}. The handle stays owned.
 *   <li>Read the code and exit time, classify (§3.1), append {@code child-exit}, close the handle.
 *   <li>GPU child and the reason continues the Engine: poll adapter memory asynchronously and
 *       append {@code gpu-release}; {@link #awaitGpuRelease} waits for it.
 * </ol>
 *
 * <p>The exit watcher ({@code Process.onExit}) writes the exit of a RUNNING child itself
 * (UNEXPECTED) and leaves a STOPPING child's to the stop, so each child gets exactly one {@code
 * child-exit}.
 *
 * <p>While the manifest still lists children (Spec B §9 C10, until step 10), every child is also
 * registered in the {@link ManagedChildRegistry}, which stays mandatory: a child whose registration
 * fails is stopped with {@code ROLLBACK} and never handed out as started; if that stop does not
 * confirm its exit, {@link ChildRegistrationRefusedException#survivor()} hands it to the caller.
 * The ledger never blocks anything.
 *
 * <p>A chat server ({@code LLAMA_SERVER} or {@code LLAMA_SELF_TEST}) whose stop failed blocks the
 * start of every chat server until a retry of its stop confirms it gone. What an exited chat root
 * left running is ended through its Windows job, or retained JDK handles elsewhere ({@link
 * #endRemaining}, and before the next chat start), each member recorded first as an intended
 * cleanup. Any survivor blocks the next chat start until it exits.
 *
 * <p>A GPU start waits for every pending release poll to finish ({@link #GPU_RELEASE_BOUND}) and
 * is refused only when one is still running after that. Each adapter reading is bounded ({@link
 * #GPU_READ_BOUND}): one that does not answer ends its poll as unmeasured, so a hung driver call
 * cannot keep GPU starts refused.
 */
public final class DefaultChildControl implements ChildControl {
  static final Duration TERMINATE_WAIT = Duration.ofSeconds(5);
  static final int TERMINATE_CALLS = 2;
  static final Duration GPU_POLL_INTERVAL = Duration.ofMillis(250);
  static final Duration GPU_POLL_MAX = Duration.ofSeconds(5);
  static final long GPU_STABLE_BYTES = 64L * 1024 * 1024;
  static final double GPU_RELEASE_FRACTION = 0.75;
  /** How long one adapter memory reading may take before it counts as unmeasured. */
  static final Duration GPU_READ_BOUND = Duration.ofSeconds(2);
  /**
   * How long a GPU start waits for pending release polls: one poll's maximum, the sample interval
   * and the read bound it may overrun by, and a margin for its ledger record. A poll that ran
   * before the start was asked for ends sooner.
   */
  static final Duration GPU_RELEASE_BOUND =
      GPU_POLL_MAX.plus(GPU_POLL_INTERVAL).plus(GPU_READ_BOUND).plusSeconds(1);
  static final int RECENT_EXITS = 32;
  private static final Duration GRACE_SLICE = Duration.ofMillis(50);
  private static final Duration CLOSE_WAIT = Duration.ofSeconds(12);
  private static final int MAX_EXECUTABLE = 1024;
  private static final int MAX_NOTE = 256;
  private static final String REQUESTED_BY = "child-control";
  /** A stop's bound: both terminate waits and a margin for the exit record. */
  private static final Duration STOP_BOUND =
      TERMINATE_WAIT.multipliedBy(TERMINATE_CALLS).plusSeconds(2);
  /** The kinds that hold the chat server's port and adapter memory: one role (S19). */
  private static final Set<ChildKind> CHAT_SERVER_KINDS =
      Set.of(ChildKind.LLAMA_SERVER, ChildKind.LLAMA_SELF_TEST);
  /** Retries a survivor's stop before a chat-server start; the survivor keeps its first intent. */
  private static final StopRequest SURVIVOR_RETRY =
      StopRequest.of(StopReason.ROLLBACK, StopMode.NOW, REQUESTED_BY);
  /** The owner instance named by a control that is not the Engine's ({@link #of}). */
  private static final String EMBEDDED_OWNER = "embedded";

  /** Sleeps between GPU samples; virtual in tests. */
  @FunctionalInterface
  interface Sleeper {
    Sleeper REAL = duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos());

    void sleep(Duration duration) throws InterruptedException;
  }

  /**
   * Whether a just-started child is in the Engine's job (Spec B §2.10, Spec F §1.2): {@link
   * EngineContainment#contains}; empty when there is no job or the query fails.
   */
  @FunctionalInterface
  interface JobMembership {
    JobMembership NONE = child -> Optional.empty();

    Optional<Boolean> contains(ProcessHandle child);
  }

  private final LedgerSink ledger;
  private final ProcessTerminator terminator;
  private final VramProbe vram;
  private final Clock clock;
  private final Sleeper sleeper;
  private final ManagedChildRegistry registry;
  private final EngineIdentity owner;
  private final ThreadFactory threads;
  private final JobMembership job;

  private final Map<String, Held> live = new ConcurrentHashMap<>();
  /** A root exit does not release the chat role while its observed descendants still run. */
  private final Map<String, Held> chatScopes = new ConcurrentHashMap<>();
  private final Deque<ExitOutcome> recent = new ArrayDeque<>();
  private final Map<String, CompletableFuture<Void>> gpuPolls = new ConcurrentHashMap<>();
  private final Object vramReads = new Object();
  /** A reading past its bound that has not answered yet; none is started beside it. */
  private CompletableFuture<OptionalLong> unansweredRead;
  private volatile boolean closed;
  private final Object extractionAdmission = new Object();
  private boolean extractionClosed;

  DefaultChildControl(LedgerSink ledger, ProcessTerminator terminator, VramProbe vram, Clock clock,
      Sleeper sleeper, ManagedChildRegistry registry, EngineIdentity owner, ThreadFactory threads,
      JobMembership job) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.terminator = Objects.requireNonNull(terminator, "terminator");
    this.vram = Objects.requireNonNull(vram, "vram");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.threads = Objects.requireNonNull(threads, "threads");
    this.job = Objects.requireNonNull(job, "job");
  }

  /**
   * The Engine's control over {@code <dataDir>/runtime/exit-ledger/children.v1.jsonl}. Call only
   * while holding {@code app.lock}: it first closes what an earlier Engine left open ({@link
   * LedgerReconciler}, never killing), then starts this writer session (which may rotate).
   *
   * @param instanceId this Engine's manifest {@code instanceId}, the owner named in every child
   * @param containment the Engine job {@link EngineContainment#installAtBoot} returned; every
   *     {@code child-start} records whether the child is in it ({@code contained})
   */
  public static DefaultChildControl openForEngine(Path dataDir, String instanceId,
      ManagedChildRegistry registry, VramProbe vram, EngineContainment containment) {
    Objects.requireNonNull(containment, "containment");
    Clock clock = Clock.systemUTC();
    ProcessTerminator terminator = ProcessTerminator.forCurrentOs();
    ProcessHandle self = ProcessHandle.current();
    Instant startedAt = self.info().startInstant().orElse(clock.instant());
    Path ledgerDir = LedgerFamily.directory(Objects.requireNonNull(dataDir, "dataDir"));
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(ledgerDir, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, startedAt), clock);
    new LedgerReconciler(ledgerDir, LedgerSink.of(writer), LedgerReconciler.osIdentity(terminator))
        .reconcile();
    writer.startSession();
    EngineIdentity owner =
        new EngineIdentity(self.pid(), terminator.creationFileTime(self.pid()), instanceId);
    containment.recordProcesses(LedgerSink.of(writer), owner);
    OwnedHelper.recordTo(LedgerSink.of(writer), owner, ledgerDir);
    return new DefaultChildControl(LedgerSink.of(writer), terminator, vram, clock, Sleeper.REAL,
        registry, owner, controlThreads(), containment::contains);
  }

  /**
   * A control of this process's children that records into {@code ledger} and stops them with
   * {@code terminator}. For compositions other than the Engine's boot (embedded roots and tests),
   * which own no {@code children.v1.jsonl}; the Engine uses {@link #openForEngine}.
   */
  public static DefaultChildControl of(
      LedgerSink ledger, ProcessTerminator terminator, ManagedChildRegistry registry) {
    ProcessHandle self = ProcessHandle.current();
    return new DefaultChildControl(ledger, terminator, VramProbe.none(), Clock.systemUTC(),
        Sleeper.REAL, registry,
        new EngineIdentity(self.pid(), terminator.creationFileTime(self.pid()), EMBEDDED_OWNER),
        controlThreads(), JobMembership.NONE);
  }

  /** Records the identity-matched boot fallback using this Engine's children writer. */
  public boolean stopPredecessor(Path dataDir, ManagedChild child) throws IOException {
    return new PredecessorChildStop(this::append, terminator, clock, owner).stop(dataDir, child);
  }

  /**
   * {@link #of} with the OS terminator and no ledger: every record reports not durable, so each
   * {@link ExitOutcome#ledgerDurable()} is false. Children are still registered in {@code registry}.
   */
  public static DefaultChildControl unrecorded(ManagedChildRegistry registry) {
    return of((type, fields) -> false, ProcessTerminator.forCurrentOs(), registry);
  }

  private static ThreadFactory controlThreads() {
    return Thread.ofPlatform().daemon().name("child-control-", 0).factory();
  }

  // ----------------------------------------------------------------------------------- start

  @Override
  public ChildHandle start(ChildSpec spec, ManifestEntry manifest) throws IOException {
    Objects.requireNonNull(spec, "spec");
    if (spec.kind() == ChildKind.EXTRACTION) {
      synchronized (extractionAdmission) {
        if (extractionClosed) throw new IllegalStateException("extraction is terminal");
        return startAdmitted(spec, manifest);
      }
    }
    return startAdmitted(spec, manifest);
  }

  private ChildHandle startAdmitted(ChildSpec spec, ManifestEntry manifest) throws IOException {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(manifest, "manifest");
    if (closed) throw new IllegalStateException("child control is closed");
    if (CHAT_SERVER_KINDS.contains(spec.kind())) requireNoChatServerSurvivor(spec.kind());
    if (spec.gpu()) {
      try {
        // The start waits for the release poll itself to end (released or timed out), so how slow
        // the adapter is decides nothing; only a poll still running past its bound refuses.
        if (!awaitGpuRelease(GPU_RELEASE_BOUND)) {
          throw new IOException("GPU release still pending after " + GPU_RELEASE_BOUND.toMillis()
              + " ms; retry after the release poll finishes");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted waiting for a GPU release");
      }
    }
    String childId = "c-" + UUID.randomUUID();
    boolean spawnDurable = append(LedgerRecordType.CHILD_SPAWN, spawnFields(childId, spec));

    ProcessBuilder builder = new ProcessBuilder(spec.argv());
    if (spec.workingDir() != null) builder.directory(spec.workingDir().toFile());
    if (!spec.envOverrides().isEmpty()) builder.environment().putAll(spec.envOverrides());
    if (!spec.pathPrefix().isEmpty()) prependPath(builder.environment(), spec.pathPrefix());
    if (spec.stdio() instanceof ChildSpec.Stdio.AppendToLog log) {
      builder.redirectErrorStream(true);
      builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.file().toFile()));
    }
    Process process;
    try {
      process = CHAT_SERVER_KINDS.contains(spec.kind()) && io.justsearch.configuration.PlatformPaths.isWindows()
          ? WindowsEngineJob.createChat(builder) : BoundChildProcess.start(builder);
    } catch (IOException | RuntimeException failure) {
      recordStartFailure(childId, spec.kind(), "spawn_failed");
      throw failure;
    }
    ProcessTerminator.OsProcess os;
    try {
      os = terminator.open(process);
    } catch (IOException | RuntimeException failure) {
      // Without our handle there is no recorded terminate: end it with the JDK and say so.
      process.destroyForcibly();
      awaitQuietly(process);
      if (process instanceof WindowsEngineJob.ChatProcess chat) chat.releaseScope();
      recordStartFailure(childId, spec.kind(), "handle_unavailable");
      throw failure instanceof IOException io ? io : new IOException(failure);
    }
    if (spec.stdio() instanceof ChildSpec.Stdio.AppendToLog) closeQuietly(process.getOutputStream());

    Optional<Instant> startedAt = process.info().startInstant();
    Held held = new Held(childId, spec, process, os, startedAt.orElse(clock.instant()));
    // The Engine job's witness classifies this member's exit from what this owner records.
    ProcessIntents.owned(held.pid, os.creationFileTime().orElse(null));
    if (held.descendants != null) {
      chatScopes.put(childId, held);
    }
    held.ledgerDurable = spawnDurable;
    var _ = process.onExit().whenComplete((ignored, failure) -> onProcessExit(held));
    boolean startDurable = append(LedgerRecordType.CHILD_START, startFields(held, startedAt));
    live.put(childId, held);
    boolean exitedWhileStarting;
    synchronized (held) {
      held.ledgerDurable &= startDurable;
      held.state = ChildHandle.State.RUNNING;
      exitedWhileStarting = held.exitedWhileStarting;
    }
    if (exitedWhileStarting) {
      recordExit(held, ExitOutcome.Phase.STARTING);
      return held;
    }
    register(held, manifest);
    if (process instanceof WindowsEngineJob.ChatProcess chat) {
      try {
        chat.resume();
      } catch (IOException failure) {
        var _ = beginStop(held, SURVIVOR_RETRY, Optional.empty());
        throw failure;
      }
    }
    return held;
  }

  /**
   * A chat server (main or self-test) whose stop failed may still hold the chat port and adapter
   * memory, so no chat server starts beside it (S19): each such child, SURVIVED or with its stop
   * still running, has its stop retried under its first intent (or awaited) first, and the start is
   * refused while one is not confirmed gone. Nothing is spawned or recorded for a refused start.
   */
  private void requireNoChatServerSurvivor(ChildKind starting) throws IOException {
    for (Held held : List.copyOf(live.values())) {
      if (!CHAT_SERVER_KINDS.contains(held.spec.kind())) continue;
      CompletableFuture<ExitOutcome> stop;
      synchronized (held) {
        switch (held.state) {
          case STOPPING -> stop = held.stopFuture;
          case SURVIVED -> stop = null;
          default -> {
            continue;
          }
        }
      }
      if (stop == null) stop = beginStop(held, SURVIVOR_RETRY, Optional.empty());
      try {
        stop.get(STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted retrying the stop of a " + held.spec.kind()
            + " child (pid " + held.pid() + ")");
      } catch (ExecutionException | TimeoutException survived) {
        throw new IOException("a " + held.spec.kind() + " child (pid " + held.pid()
            + ") survived its stop; no " + starting + " starts beside it",
            survived.getCause() != null ? survived.getCause() : survived);
      }
    }
    for (Held held : List.copyOf(chatScopes.values())) {
      if (held.state() != ChildHandle.State.EXITED) continue;
      endScope(held, "before_" + starting + "_start", starting, true);
      if (chatScopes.remove(held.childId, held)) held.descendants.close();
    }
  }

  @Override
  public int endRemaining(ChildHandle child, StopRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    Held held = own(child);
    if (held.state() != ChildHandle.State.EXITED) {
      throw new IllegalStateException("child " + held.childId + " has not exited");
    }
    if (held.descendants == null) return 0;
    return endScope(held, request.reason().name(), held.spec.kind(), false);
  }

  /**
   * Ends what an exited chat root left running: each member is recorded first
   * ({@code child-note} {@code intended_cleanup:<reason>:job_member:<pid>@<start>}), then its Windows job or
   * retained descendant handles are ended and every recorded member awaited, and each outcome recorded after its
   * intent: {@code cleanup_ended:...} or {@code cleanup_not_ended:...:<cause>}. A member not ended
   * keeps its scope held, so it is counted and refuses chat starts until it exits; that exit is
   * recorded too. When the job cannot be ended at all a chat start is refused with "chat start
   * blocked: could not end leftover chat processes (<native error>)". A GPU child's memory is
   * sampled before and its release polled after (unless its stop ended the Engine), so the next GPU
   * start waits for it. Off Windows the original JDK handles receive TERM, a one-second grace window,
   * then KILL. Linux zombies have exited regardless of their parent's reaping.
   *
   * @return how many members were ended
   */
  private int endScope(Held held, String reason, ChildKind starting, boolean admission)
      throws IOException {
    List<ProcessHandle> members;
    try {
      members = held.descendants.snapshot();
    } catch (IllegalStateException unknown) {
      throw new IOException("chat start blocked: " + unknown.getMessage(), unknown);
    }
    // A member recorded as not ended stays counted after its job was closed, until its own exit.
    members = new ArrayList<>(members);
    for (var entry : held.notEndedMembers.entrySet()) {
      ProcessHandle leftover = entry.getValue();
      if (!ProcessTerminator.isRunning(leftover)) {
        if (held.notEndedMembers.remove(entry.getKey(), leftover)) {
          append(LedgerRecordType.CHILD_NOTE,
              noteFields(held.childId, "leftover_exited:job_member:" + entry.getKey()));
        }
        continue;
      }
      boolean listed = members.stream().anyMatch(member -> member.pid() == leftover.pid());
      if (!listed) {
        members.add(leftover);
      }
    }
    if (members.isEmpty()) return 0;
    Intent stopped;
    synchronized (held) {
      stopped = held.intent;
    }
    // As for the root's own exit, an Engine-ending stop polls no release (T13).
    boolean measured =
        held.spec.gpu() && (stopped == null || !stopped.request().reason().endsEngine());
    OptionalLong before = measured ? readVram(held.spec.gpuDevice()) : OptionalLong.empty();
    Map<ProcessHandle, String> identities = new LinkedHashMap<>();
    for (ProcessHandle member : members) {
      String identity = member.pid() + "@"
          + member.info().startInstant().map(Instant::toString).orElse("unknown");
      identities.put(member, identity);
      noteDurability(held, append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId,
          "intended_cleanup:" + reason + ":job_member:" + identity)));
    }
    try {
      held.descendants.end(members);
    } catch (IOException | RuntimeException failure) {
      String cause = String.valueOf(failure.getMessage());
      for (var member : identities.entrySet()) {
        notEnded(held, reason, member.getKey(), member.getValue(), cause);
      }
      throw new IOException((admission ? "chat start blocked: " : "")
          + "could not end leftover chat processes (" + cause + ")", failure);
    }
    long deadline = System.nanoTime() + TERMINATE_WAIT.toNanos();
    ProcessHandle outlived = null;
    InterruptedException interrupted = null;
    for (var member : identities.entrySet()) {
      boolean ended = false;
      if (interrupted == null) {
        try {
          ended = ProcessTerminator.awaitExit(member.getKey(),
              Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        } catch (InterruptedException stop) {
          interrupted = stop;
        }
      }
      if (ended || !ProcessTerminator.isRunning(member.getKey())) {
        noteDurability(held, append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId,
            "cleanup_ended:" + reason + ":job_member:" + member.getValue())));
      } else {
        notEnded(held, reason, member.getKey(), member.getValue(),
            interrupted != null ? "interrupted"
                : io.justsearch.configuration.PlatformPaths.isWindows()
                    ? "outlived_job_end" : "outlived_cleanup");
        if (outlived == null) outlived = member.getKey();
      }
    }
    if (interrupted != null) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("interrupted ending what a " + held.spec.kind()
          + " child (root pid " + held.pid() + ") left running");
    }
    if (outlived != null) {
      throw new IOException("a " + held.spec.kind() + " descendant (pid " + outlived.pid()
          + (io.justsearch.configuration.PlatformPaths.isWindows()
              ? ") outlived the end of its job; no " : ") outlived cleanup; no ")
          + starting + " starts beside it");
    }
    if (before.isPresent()) {
      synchronized (held) {
        held.usedBefore = before;
      }
      String poll = held.childId + "/scope";
      CompletableFuture<Void> release = registerGpuPoll(poll);
      startGpuPoll(held, poll, release);
    }
    return members.size();
  }

  /**
   * Records that a job member was not ended, and why ({@code cleanup_not_ended:<reason>:job_member:
   * <pid>@<start>:<cause>}). Its scope stays held, so it is still counted and still refuses a chat
   * start beside it, and its own later exit is recorded once ({@code leftover_exited:job_member:
   * <pid>@<start>}).
   */
  private void notEnded(Held held, String reason, ProcessHandle member, String identity,
      String cause) {
    String token = "cleanup_not_ended:" + reason + ":job_member:" + identity + ":" + cause;
    noteDurability(held, append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId,
        token.length() > MAX_NOTE ? token.substring(0, MAX_NOTE) : token)));
    // Linux observes later exits (including unreaped zombies) when the admission barrier retries.
    // Do not leave a JDK onExit reaper waiting forever for a zombie's parent to reap it.
    if (held.notEndedMembers.putIfAbsent(identity, member) == null
        && !io.justsearch.configuration.PlatformPaths.isLinux()) {
      member.onExit().thenRun(() -> {
        if (held.notEndedMembers.remove(identity, member)) {
          append(LedgerRecordType.CHILD_NOTE,
              noteFields(held.childId, "leftover_exited:job_member:" + identity));
        }
      });
    }
  }

  /** Registers the manifest entry; on failure stops the child (ROLLBACK) and throws. */
  private void register(Held held, ManifestEntry manifest) throws IOException {
    try {
      ManagedChild entry = ManagedChild.fromProcess(held.process, manifestKind(held.spec.kind()),
          manifest.endpoint() != null ? manifest.endpoint()
              : held.spec.stdio() instanceof ChildSpec.Stdio.Pipes ? "stdio" : null,
          manifest.modelPath(),
          manifest.declaredConfigHash() != null ? manifest.declaredConfigHash()
              : argvHash(held.spec.argv()),
          argvHash(held.spec.argv()));
      if (held.os.processIdentity().isPresent()
          && !held.os.processIdentity().equals(Optional.ofNullable(entry.processIdentity())))
        throw new IOException("Linux child identity changed before registration");
      registry.register(entry);
      boolean exitedWhileRegistering;
      synchronized (held) {
        held.registryId = entry.id();
        exitedWhileRegistering = held.state == ChildHandle.State.EXITED;
      }
      // A child that exited while it was being registered may have had its exit recorded before
      // the id was known, so that exit could not remove the entry: remove it here (removal is
      // idempotent, so an exit that saw the id as well does no harm).
      if (exitedWhileRegistering) {
        try {
          registry.remove(entry.id());
        } catch (IOException | RuntimeException retained) {
          // The manifest keeps the record; its reconciliation finds the process gone.
        }
      }
    } catch (IOException | RuntimeException failure) {
      Throwable suppressed = null;
      try {
        stop(held, StopRequest.of(StopReason.ROLLBACK, StopMode.NOW, REQUESTED_BY))
            .toCompletableFuture().get(STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        suppressed = interrupted;
      } catch (ExecutionException | TimeoutException survived) {
        // The handle stays owned (SURVIVED): close(), later stops and the next chat-server start
        // retry it, and the exception hands it to the caller, which keeps owning it.
        suppressed = survived.getCause() != null ? survived.getCause() : survived;
      }
      ChildRegistrationRefusedException refused =
          new ChildRegistrationRefusedException(failure, held);
      if (suppressed != null) refused.addSuppressed(suppressed);
      throw refused;
    }
  }

  private void recordStartFailure(String childId, ChildKind kind, String token) {
    append(LedgerRecordType.CHILD_NOTE, noteFields(childId, token));
    Instant now = clock.instant();
    ExitOutcome outcome = new ExitOutcome(childId, kind, ExitClass.UNKNOWN, Optional.empty(),
        Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty(), now,
        Optional.empty(), Optional.empty(), TerminateResult.NOT_CALLED, false,
        ExitOutcome.Phase.STARTING, true);
    boolean durable = append(LedgerRecordType.CHILD_EXIT, exitFields(outcome, null, null, false));
    remember(durable ? outcome : withDurability(outcome, false));
  }

  // ------------------------------------------------------------------------------------ stop

  /**
   * Terminal extraction fence, also used when the index owner cannot close after an abandoned
   * unit. The spawn fence and snapshot share a lock, so no extraction child can escape the batch.
   * Chat children remain exclusively owned by their controller.
   */
  public CompletionStage<List<ExitOutcome>> stopExtraction(StopRequest request) {
    List<ChildHandle> children;
    synchronized (extractionAdmission) {
      extractionClosed = true;
      children = live.values().stream().filter(child -> child.kind() == ChildKind.EXTRACTION)
          .map(child -> (ChildHandle) child).toList();
    }
    return stopAll(children, request);
  }

  @Override
  public CompletionStage<ExitOutcome> stop(ChildHandle child, StopRequest request) {
    Objects.requireNonNull(request, "request");
    return beginStop(own(child), request, Optional.empty()).copy();
  }

  @Override
  public CompletionStage<List<ExitOutcome>> stopAll(
      Collection<ChildHandle> children, StopRequest request) {
    Objects.requireNonNull(request, "request");
    Optional<Instant> sharedGrace = Optional.of(clock.instant());
    List<CompletableFuture<ExitOutcome>> stops = new ArrayList<>();
    for (ChildHandle child : List.copyOf(children)) {
      stops.add(beginStop(own(child), request, sharedGrace));
    }
    return CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new))
        .thenApply(done -> stops.stream().map(CompletableFuture::join).toList());
  }

  private CompletableFuture<ExitOutcome> beginStop(
      Held held, StopRequest request, Optional<Instant> sharedGrace) {
    Intent intent;
    CompletableFuture<ExitOutcome> future;
    boolean begin = false;
    boolean escalate = false;
    boolean retry = false;
    synchronized (held) {
      switch (held.state) {
        case EXITED -> {
          return held.exit;
        }
        case STOPPING -> {
          intent = held.intent;
          future = held.stopFuture;
          if (request.mode() == StopMode.NOW && intent.request().mode() == StopMode.COOPERATIVE
              && !held.escalated) {
            held.escalated = true;
            escalate = true;
          }
        }
        case SURVIVED -> {
          intent = held.intent;
          held.state = ChildHandle.State.STOPPING;
          held.stopFuture = new CompletableFuture<>();
          future = held.stopFuture;
          retry = true;
        }
        case STARTING, RUNNING -> {
          intent = new Intent("s-" + UUID.randomUUID(), request, clock.instant());
          held.intent = intent;
          held.state = ChildHandle.State.STOPPING;
          held.stopFuture = new CompletableFuture<>();
          future = held.stopFuture;
          begin = true;
        }
        default -> throw new IllegalStateException("unknown state " + held.state);
      }
    }
    if (escalate) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("childId", held.childId);
      fields.put("intentId", intent.intentId());
      fields.put("mode", StopMode.NOW);
      noteDurability(held, append(LedgerRecordType.STOP_ESCALATE, fields));
    }
    if (begin) {
      noteDurability(held, append(LedgerRecordType.STOP_INTENT, intentFields(held, intent)));
      ProcessIntents.intent(held.pid, held.os.creationFileTime().orElse(null),
          intent.request().reason().name());
      launch(held, () -> runStop(held, false, sharedGrace));
    } else if (retry) {
      launch(held, () -> runStop(held, true, Optional.empty()));
    }
    return future;
  }

  private void launch(Held held, Runnable body) {
    Thread thread = threads.newThread(body);
    try {
      thread.start();
    } catch (RuntimeException | Error failure) {
      runStopFailed(held, failure);
      throw failure;
    }
  }

  /** Steps 3 to 7 on the stop's own thread; {@code fromTerminate} retries a survivor. */
  private void runStop(Held held, boolean fromTerminate, Optional<Instant> sharedGrace) {
    try {
      Intent intent;
      synchronized (held) {
        intent = held.intent;
      }
      if (!fromTerminate) {
        if (held.spec.gpu() && !intent.request().reason().endsEngine()) {
          OptionalLong before = readVram(held.spec.gpuDevice());
          synchronized (held) {
            held.usedBefore = before;
          }
        }
        if (intent.request().mode() == StopMode.COOPERATIVE
            && held.spec.cooperativeStop().action() == ChildSpec.CooperativeStop.Action.CLOSE_STDIN
            && !escalated(held)) {
          cooperate(held, sharedGrace);
        }
      }
      if (held.descendants != null && !io.justsearch.configuration.PlatformPaths.isWindows())
        held.descendants.observe();
      boolean exited = held.os.awaitExit(Duration.ZERO);
      for (int call = 0; !exited && call < TERMINATE_CALLS; call++) {
        Instant calledAt = clock.instant();
        TerminateResult result = held.os.terminate(terminator.ourCode());
        synchronized (held) {
          if (held.terminateCalledAt == null) held.terminateCalledAt = calledAt;
          if (held.terminateResult.status() != TerminateResult.Status.OK) {
            held.terminateResult = result;
          }
          held.terminateCalls++;
        }
        exited = held.os.awaitExit(TERMINATE_WAIT);
      }
      if (!exited) {
        survived(held);
        return;
      }
      if (held.descendants != null && !io.justsearch.configuration.PlatformPaths.isWindows()) {
        try {
          endScope(held, intent.request().reason().name(), held.spec.kind(), false);
        } catch (IOException | RuntimeException unverified) {
          // The root has exited; retain its scope as the start barrier for any survivor or
          // unreadable membership. Owners also verify endRemaining before certifying cleanup.
          String token = "cleanup_not_ended:" + intent.request().reason() + ":"
              + unverified.getMessage();
          noteDurability(held, append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId,
              token.length() > MAX_NOTE ? token.substring(0, MAX_NOTE) : token)));
        }
      }
      recordExit(held, ExitOutcome.Phase.STOPPING);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      runStopFailed(held, interrupted);
    } catch (RuntimeException | Error failure) {
      runStopFailed(held, failure);
      if (failure instanceof VirtualMachineError fatal) throw fatal;
    }
  }

  /** Step 4: close stdin off-thread (a blocked writer cannot hold it) and wait out the grace. */
  private void cooperate(Held held, Optional<Instant> sharedGrace) throws InterruptedException {
    Instant at = clock.instant();
    synchronized (held) {
      held.cooperativeAt = at;
    }
    threads.newThread(() -> closeQuietly(held.process.getOutputStream())).start();
    Instant deadline = sharedGrace.orElse(at).plus(held.spec.cooperativeStop().grace());
    while (!escalated(held)) {
      Duration left = Duration.between(clock.instant(), deadline);
      if (left.isNegative() || left.isZero()) return;
      if (held.os.awaitExit(left.compareTo(GRACE_SLICE) < 0 ? left : GRACE_SLICE)) return;
    }
  }

  private void survived(Held held) {
    ChildView view;
    Intent intent;
    int calls;
    CompletableFuture<ExitOutcome> future;
    synchronized (held) {
      if (held.state != ChildHandle.State.STOPPING) return; // the watcher recorded its exit
      held.state = ChildHandle.State.SURVIVED;
      view = held.view();
      intent = held.intent;
      calls = held.terminateCalls;
      future = held.stopFuture;
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", held.childId);
    fields.put("intentId", intent.intentId());
    fields.put("attempts", Math.max(1, calls));
    fields.put("basis", "terminate_timeout");
    noteDurability(held, append(LedgerRecordType.CHILD_SURVIVED, fields));
    future.completeExceptionally(new ChildSurvivedException(view));
  }

  private void runStopFailed(Held held, Throwable failure) {
    CompletableFuture<ExitOutcome> future;
    synchronized (held) {
      if (held.state != ChildHandle.State.STOPPING) return;
      held.state = ChildHandle.State.SURVIVED;
      future = held.stopFuture;
    }
    future.completeExceptionally(failure);
  }

  private boolean escalated(Held held) {
    synchronized (held) {
      return held.escalated;
    }
  }

  // ------------------------------------------------------------------------------------ exit

  /** The exit watcher: records a RUNNING (or SURVIVED) child's exit; a stop records its own. */
  private void onProcessExit(Held held) {
    ExitOutcome.Phase phase;
    synchronized (held) {
      switch (held.state) {
        case STARTING -> {
          held.exitedWhileStarting = true;
          return;
        }
        case RUNNING -> phase = ExitOutcome.Phase.RUNNING;
        case SURVIVED -> phase = ExitOutcome.Phase.STOPPING;
        default -> {
          return;
        }
      }
    }
    recordExit(held, phase);
  }

  /** Step 7: classify, append {@code child-exit}, release. Runs once per child. */
  private void recordExit(Held held, ExitOutcome.Phase phase) {
    Intent intent;
    Instant cooperativeAt;
    Instant terminateCalledAt;
    TerminateResult terminateResult;
    synchronized (held) {
      if (held.state == ChildHandle.State.EXITED) return;
      held.state = ChildHandle.State.EXITED;
      intent = held.intent;
      cooperativeAt = held.cooperativeAt;
      terminateCalledAt = held.terminateCalledAt;
      terminateResult = held.terminateResult;
    }
    OptionalInt code = held.os.exitCode();
    Optional<Instant> exitedAt = held.os.exitedAt();
    Instant observedAt = clock.instant();
    ExitClass exitClass = classify(intent != null, code, terminateResult, cooperativeAt != null,
        held.spec.cooperativeStop().expectedCodes(), terminator.ourCode());
    boolean forced = exitClass == ExitClass.REQUESTED
        && terminateResult.status() == TerminateResult.Status.OK
        && code.getAsInt() == terminator.ourCode();
    // The intent's mode, as recorded in stop-intent; an escalation has its own stop-escalate record.
    Optional<StopMode> mode =
        intent == null ? Optional.empty() : Optional.of(intent.request().mode());
    ExitOutcome outcome = new ExitOutcome(held.childId, held.spec.kind(), exitClass,
        intent == null ? Optional.empty() : Optional.of(intent.request().reason()),
        intent == null ? Optional.empty() : intent.request().detail(), mode, code, exitedAt,
        observedAt, intent == null ? Optional.empty() : Optional.of(intent.requestedAt()),
        Optional.ofNullable(terminateCalledAt), terminateResult, forced, phase, true);
    boolean durable = append(LedgerRecordType.CHILD_EXIT, exitFields(outcome,
        intent == null ? null : intent.intentId(), cooperativeAt, terminator.codeDistinctive()));
    ProcessIntents.outcome(held.pid, held.os.creationFileTime().orElse(null), exitClass.name(),
        outcome.reason().map(Enum::name).orElse(
            exitClass == ExitClass.UNEXPECTED ? ProcessIntents.NO_INTENT : null),
        exitedAt.orElse(observedAt));
    CompletableFuture<ExitOutcome> stopFuture;
    boolean allDurable;
    String registryId;
    synchronized (held) {
      held.ledgerDurable &= durable;
      allDurable = held.ledgerDurable;
      stopFuture = held.stopFuture;
      registryId = held.registryId;
    }
    ExitOutcome recorded = allDurable ? outcome : withDurability(outcome, false);
    held.os.close();
    live.remove(held.childId);
    if (registryId != null) {
      try {
        registry.remove(registryId);
      } catch (IOException | RuntimeException retained) {
        // The manifest keeps the record; its reconciliation finds the process gone.
      }
    }
    remember(recorded);
    // The pending release is registered before the exit is published, so a waiter woken by the
    // exit (a model switch starting the replacement) already sees it in awaitGpuRelease.
    CompletableFuture<Void> release =
        intent != null && held.spec.gpu() && !intent.request().reason().endsEngine()
            ? registerGpuPoll(held.childId) : null;
    // Completing a future invokes synchronous continuations on this thread. The producer must
    // already run before such a continuation can call start() and wait on its release barrier.
    if (release != null) startGpuPoll(held, held.childId, release);
    held.exit.complete(recorded);
    if (stopFuture != null) stopFuture.complete(recorded);
  }

  /** Spec B §3.1 R1-R5, decided by the kernel's exit code and our own calls, never by a clock. */
  static ExitClass classify(boolean intent, OptionalInt code, TerminateResult terminate,
      boolean cooperated, Set<Integer> expectedCodes, int ourCode) {
    if (code.isEmpty()) return ExitClass.UNKNOWN; // R5
    if (!intent) return ExitClass.UNEXPECTED; // R1
    if (terminate.status() == TerminateResult.Status.OK && code.getAsInt() == ourCode) {
      return ExitClass.REQUESTED; // R2
    }
    if (cooperated && expectedCodes.contains(code.getAsInt())) return ExitClass.REQUESTED; // R3
    return ExitClass.UNEXPECTED; // R4
  }

  /** Spec B §3.2: a diagnostic name for a code; it never changes the class. */
  static String codeName(int code) {
    return switch (code) {
      case 0 -> "OK";
      case TerminateCodes.JS_CHILD_STOP -> "JS_CHILD_STOP";
      case TerminateCodes.JS_ENGINE_STOP -> "JS_ENGINE_STOP";
      case PosixProcessTerminator.SIGKILL_CODE -> "SIGKILL";
      case 0xC0000135 -> "STATUS_DLL_NOT_FOUND";
      case 0xC0000005 -> "STATUS_ACCESS_VIOLATION";
      case 0xC0000409 -> "STATUS_STACK_BUFFER_OVERRUN";
      case 0xC000013A -> "STATUS_CONTROL_C_EXIT";
      default -> null;
    };
  }

  // ------------------------------------------------------------------------------------- GPU

  /** One poll per key: a child's exit, and the end of what it left running, are each once. */
  private CompletableFuture<Void> registerGpuPoll(String key) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    gpuPolls.put(key, done);
    return done;
  }

  private void startGpuPoll(Held held, String key, CompletableFuture<Void> done) {
    Thread poll = threads.newThread(() -> {
      try {
        pollGpu(held);
      } finally {
        gpuPolls.remove(key, done);
        done.complete(null);
      }
    });
    try {
      poll.start();
    } catch (RuntimeException | Error failure) {
      gpuPolls.remove(key, done);
      done.complete(null);
    }
  }

  /** Step 8 (§2.6): sample until the release shows, at most 5 s; never fails anything. */
  private void pollGpu(Held held) {
    OptionalLong before;
    synchronized (held) {
      before = held.usedBefore;
    }
    OptionalLong expected = held.spec.gpuFootprintBytes();
    Instant started = clock.instant();
    String result = "unmeasured";
    OptionalLong after = OptionalLong.empty();
    if (vram.source() != GpuMemorySource.NONE && before.isPresent()) {
      result = "timeout";
      long previous = -1;
      boolean dropped = false;
      try {
        while (true) {
          sleeper.sleep(GPU_POLL_INTERVAL);
          OptionalLong used = readVram(held.spec.gpuDevice());
          if (used.isEmpty()) {
            result = "unmeasured";
            if (vramReadUnanswered()) {
              append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId,
                  "gpu_read_unanswered:bound_ms=" + GPU_READ_BOUND.toMillis()));
            }
            break;
          }
          after = used;
          long now = used.getAsLong();
          if (expected.isPresent()) {
            if (now <= before.getAsLong() - GPU_RELEASE_FRACTION * expected.getAsLong()) {
              result = "recovered";
              break;
            }
          } else {
            if (now < before.getAsLong()) dropped = true;
            if (dropped && previous >= 0 && previous < before.getAsLong()
                && Math.abs(now - previous) <= GPU_STABLE_BYTES) {
              result = "recovered";
              break;
            }
          }
          previous = now;
          if (Duration.between(started, clock.instant()).compareTo(GPU_POLL_MAX) >= 0) break;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", held.childId);
    fields.put("result", result);
    fields.put("waitedMs", Math.max(0, Duration.between(started, clock.instant()).toMillis()));
    fields.put("usedBeforeBytes", before);
    fields.put("usedAfterBytes", after);
    fields.put("expectedBytes", expected);
    fields.put("probe", vram.source());
    append(LedgerRecordType.GPU_RELEASE, fields);
  }

  /**
   * One adapter reading, bounded by {@link #GPU_READ_BOUND}: a read that does not answer in time
   * is empty (the release poll then ends unmeasured), and while it still has not answered every
   * later read is empty at once rather than queueing behind it, so a hung driver call can neither
   * hold a release poll, nor a stop, nor a chat start past its bound.
   */
  private OptionalLong readVram(Optional<GpuDevice> device) {
    if (vram.source() == GpuMemorySource.NONE) return readVramNow(device);
    CompletableFuture<OptionalLong> read = new CompletableFuture<>();
    synchronized (vramReads) {
      if (unansweredRead != null && !unansweredRead.isDone()) return OptionalLong.empty();
      unansweredRead = null;
    }
    try {
      threads.newThread(() -> read.complete(readVramNow(device))).start();
    } catch (RuntimeException | Error failure) {
      return OptionalLong.empty();
    }
    try {
      return read.get(GPU_READ_BOUND.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException unanswered) {
      synchronized (vramReads) {
        unansweredRead = read;
      }
      return OptionalLong.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return OptionalLong.empty();
    } catch (ExecutionException impossible) {
      return OptionalLong.empty();
    }
  }

  private boolean vramReadUnanswered() {
    synchronized (vramReads) {
      return unansweredRead != null && !unansweredRead.isDone();
    }
  }

  private OptionalLong readVramNow(Optional<GpuDevice> device) {
    try {
      OptionalLong used = vram.adapterUsedBytes(device);
      return used.isPresent() && used.getAsLong() >= 0 ? used : OptionalLong.empty();
    } catch (RuntimeException unreadable) {
      return OptionalLong.empty();
    }
  }

  @Override
  public boolean awaitGpuRelease(Duration max) throws InterruptedException {
    List<CompletableFuture<Void>> pending = List.copyOf(gpuPolls.values());
    if (pending.isEmpty()) return true;
    try {
      CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
          .get(Math.max(0, max.toMillis()), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException stillPending) {
      return false;
    } catch (ExecutionException impossible) {
      return pending.stream().allMatch(CompletableFuture::isDone);
    }
  }

  // ------------------------------------------------------------------------ notes and views

  @Override
  public void note(ChildHandle child, String token) {
    Held held = own(child);
    Objects.requireNonNull(token, "token");
    if (token.isEmpty() || token.length() > MAX_NOTE) {
      throw new IllegalArgumentException("a note token is 1-" + MAX_NOTE + " characters");
    }
    append(LedgerRecordType.CHILD_NOTE, noteFields(held.childId, token));
  }

  @Override
  public List<ChildView> list() {
    List<ChildView> views = new ArrayList<>();
    for (Held held : live.values()) {
      synchronized (held) {
        if (held.state != ChildHandle.State.EXITED) views.add(held.view());
      }
    }
    return List.copyOf(views);
  }

  @Override
  public List<ExitOutcome> recentExits(int max) {
    synchronized (recent) {
      return recent.stream().limit(Math.max(0, max)).toList();
    }
  }

  private void remember(ExitOutcome outcome) {
    synchronized (recent) {
      recent.addFirst(outcome);
      while (recent.size() > RECENT_EXITS) recent.removeLast();
    }
  }

  /** Stops whatever is still held (QUIT, NOW) and refuses new starts. */
  @Override
  public void close() {
    closed = true;
    List<ChildHandle> remaining = List.copyOf(live.values());
    try {
      if (!remaining.isEmpty()) {
        stopAll(remaining, StopRequest.of(StopReason.QUIT, StopMode.NOW, REQUESTED_BY))
            .toCompletableFuture().get(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException survivors) {
      // Survivors stay held and recorded; the Engine's job ends them with the Engine.
    } finally {
      for (Held scope : chatScopes.values()) scope.descendants.close();
      chatScopes.clear();
    }
  }

  // --------------------------------------------------------------------------------- records

  private boolean append(LedgerRecordType type, Map<String, ?> fields) {
    try {
      return ledger.append(type, fields);
    } catch (RuntimeException failure) {
      return false; // the action proceeds; the outcome reports the lost record
    }
  }

  private void noteDurability(Held held, boolean durable) {
    synchronized (held) {
      held.ledgerDurable &= durable;
    }
  }

  /**
   * Puts {@code prefix} in front of the child's {@code PATH} in the environment the child inherits.
   * Windows keeps the variable's own spelling ({@code Path} or {@code PATH}) and a second spelling
   * would be a second variable, so the existing key is reused whatever its case.
   */
  static void prependPath(Map<String, String> environment, List<Path> prefix) {
    String key = environment.keySet().stream()
        .filter(name -> name.equalsIgnoreCase("PATH"))
        .findFirst()
        .orElse("PATH");
    String inherited = environment.get(key);
    String joined = String.join(java.io.File.pathSeparator,
        prefix.stream().map(Path::toString).toList());
    environment.put(key, inherited == null || inherited.isBlank()
        ? joined : joined + java.io.File.pathSeparator + inherited);
  }

  private Map<String, Object> spawnFields(String childId, ChildSpec spec) {
    Map<String, Object> ownerFields = new LinkedHashMap<>();
    ownerFields.put("pid", owner.pid());
    ownerFields.put("creationFileTime", owner.creationFileTime());
    ownerFields.put("instanceId", owner.instanceId());
    if (io.justsearch.configuration.PlatformPaths.isLinux()) ownerFields.put("processIdentity", LinuxProcessIdentity.read(owner.pid()));
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", childId);
    fields.put("kind", spec.kind());
    fields.put("label", spec.label());
    fields.put("owner", ownerFields);
    fields.put("gpu", spec.gpu());
    return fields;
  }

  private Map<String, Object> startFields(Held held, Optional<Instant> startedAt) {
    String executable = held.process.info().command()
        .filter(command -> !command.isBlank())
        .map(command -> ManagedChild.normalizePath(Path.of(command)))
        .orElse(held.spec.argv().getFirst());
    if (executable.length() > MAX_EXECUTABLE) executable = executable.substring(0, MAX_EXECUTABLE);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", held.childId);
    fields.put("pid", held.pid);
    fields.put("processStartedAt", startedAt);
    fields.put("creationFileTime", held.os.creationFileTime());
    if (io.justsearch.configuration.PlatformPaths.isLinux()) fields.put("processIdentity", held.os.processIdentity());
    fields.put("executable", executable);
    fields.put("argvHash", argvHash(held.spec.argv()));
    // Spec B §2.10: whether the child is in the Engine's job; null when there is no job to ask.
    fields.put("contained", contained(held.process));
    fields.put("gpuDevice", held.spec.gpuDevice().map(GpuDevice::luid));
    return fields;
  }

  private Boolean contained(Process process) {
    try {
      return job.contains(process.toHandle()).orElse(null);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private Map<String, Object> intentFields(Held held, Intent intent) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", held.childId);
    fields.put("intentId", intent.intentId());
    fields.put("reason", intent.request().reason());
    fields.put("detail", intent.request().detail().map(StopDetail::token));
    fields.put("mode", intent.request().mode());
    fields.put("requestedAt", intent.requestedAt());
    fields.put("requestedBy", intent.request().requestedBy());
    return fields;
  }

  private static Map<String, Object> exitFields(
      ExitOutcome outcome, String intentId, Instant cooperativeAt, boolean codeDistinctive) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", outcome.childId());
    fields.put("intentId", intentId);
    fields.put("exitClass", outcome.exitClass());
    fields.put("reason", outcome.reason());
    fields.put("detail", outcome.detail().map(StopDetail::token));
    fields.put("mode", outcome.mode());
    fields.put("code", outcome.code());
    fields.put("codeName",
        outcome.code().isPresent() ? codeName(outcome.code().getAsInt()) : null);
    fields.put("codeDistinctive", codeDistinctive);
    fields.put("exitedAt", outcome.exitedAt());
    fields.put("observedAt", outcome.observedAt());
    fields.put("terminateCalledAt", outcome.terminateCalledAt());
    fields.put("terminateResult", outcome.terminateResult().wireValue());
    fields.put("cooperativeAt", cooperativeAt);
    fields.put("forced", outcome.forced());
    fields.put("phase", outcome.phase());
    return fields;
  }

  private static Map<String, Object> noteFields(String childId, String token) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", childId);
    fields.put("token", token);
    return fields;
  }

  private static ExitOutcome withDurability(ExitOutcome o, boolean durable) {
    return new ExitOutcome(o.childId(), o.kind(), o.exitClass(), o.reason(), o.detail(), o.mode(),
        o.code(), o.exitedAt(), o.observedAt(), o.requestedAt(), o.terminateCalledAt(),
        o.terminateResult(), o.forced(), o.phase(), durable);
  }

  /** SHA-256 of the argv, each argument NUL-terminated; the argv itself is never recorded. */
  static String argvHash(List<String> argv) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest((String.join("\0", argv) + "\0").getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ManagedChild.Kind manifestKind(ChildKind kind) {
    return kind == ChildKind.EXTRACTION ? ManagedChild.Kind.EXTRACTION
        : ManagedChild.Kind.LLAMA_SERVER;
  }

  private Held own(ChildHandle child) {
    Objects.requireNonNull(child, "child");
    if (child instanceof Held held && held.control() == this) return held;
    throw new IllegalArgumentException("not a child of this control: " + child.childId());
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException | RuntimeException ignored) {
      // An already-broken pipe is closed enough.
    }
  }

  private static void awaitQuietly(Process process) {
    try {
      process.waitFor(TERMINATE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** One recorded stop intent. */
  private record Intent(String intentId, StopRequest request, Instant requestedAt) {}

  /** One held child. Mutable fields are guarded by its own monitor. */
  private final class Held implements ChildHandle {
    final String childId;
    final ChildSpec spec;
    final Process process;
    final ProcessTerminator.OsProcess os;
    final long pid;
    final Instant processStartedAt;
    final ChildDescendants descendants;
    final CompletableFuture<ExitOutcome> exit = new CompletableFuture<>();

    State state = State.STARTING;
    boolean exitedWhileStarting;
    Intent intent;
    boolean escalated;
    CompletableFuture<ExitOutcome> stopFuture;
    Instant cooperativeAt;
    Instant terminateCalledAt;
    TerminateResult terminateResult = TerminateResult.NOT_CALLED;
    int terminateCalls;
    OptionalLong usedBefore = OptionalLong.empty();
    boolean ledgerDurable = true;
    String registryId;
    /** Members recorded as not ended, until their own exit is recorded; by pid@start. */
    final Map<String, ProcessHandle> notEndedMembers = new ConcurrentHashMap<>();

    Held(String childId, ChildSpec spec, Process process, ProcessTerminator.OsProcess os,
        Instant processStartedAt) {
      this.childId = childId;
      this.spec = spec;
      this.process = process;
      this.os = os;
      this.pid = process.pid();
      this.processStartedAt = processStartedAt;
      this.descendants = CHAT_SERVER_KINDS.contains(spec.kind())
          ? new ChildDescendants(process) : null;
    }

    DefaultChildControl control() {
      return DefaultChildControl.this;
    }

    ChildView view() {
      return new ChildView(childId, spec.kind(), pid, processStartedAt, state, spec.gpu(),
          spec.label());
    }

    @Override
    public String childId() {
      return childId;
    }

    @Override
    public ChildKind kind() {
      return spec.kind();
    }

    @Override
    public long pid() {
      return pid;
    }

    @Override
    public Instant processStartedAt() {
      return processStartedAt;
    }

    @Override
    public List<ProcessHandle> descendants() {
      return descendants == null ? ChildHandle.super.descendants() : descendants.snapshot();
    }

    @Override
    public OutputStream stdin() {
      requirePipes();
      return process.getOutputStream();
    }

    @Override
    public InputStream stdout() {
      requirePipes();
      return process.getInputStream();
    }

    @Override
    public InputStream stderr() {
      requirePipes();
      return process.getErrorStream();
    }

    private void requirePipes() {
      if (!(spec.stdio() instanceof ChildSpec.Stdio.Pipes)) {
        throw new IllegalStateException("child " + childId + " has no pipes");
      }
    }

    @Override
    public CompletionStage<ExitOutcome> exit() {
      return exit.copy();
    }

    @Override
    public synchronized State state() {
      return state;
    }

    @Override
    public String toString() {
      return "ChildHandle[" + childId + ", " + spec.kind() + ", pid " + pid + "]";
    }
  }
}
