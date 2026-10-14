/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.configuration.SystemAccess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owner receipts for finite helpers. A witness proves membership; it never supplies intent. */
public final class OwnedHelper extends Process {
  private record Recorder(LedgerSink sink, EngineIdentity owner, java.nio.file.Path directory, boolean nestedContainment) {
    boolean append(LedgerRecordType type, Map<String, Object> fields) {
      return append(type, fields, true);
    }

    boolean append(LedgerRecordType type, Map<String, Object> fields, boolean force) {
      fields.put("owner", Map.of("pid", owner.pid(), "creationFileTime", owner.creationFileTime(),
          "instanceId", owner.instanceId()));
      if (nestedContainment) fields.put("ownerContainment", "KILL_ON_CLOSE");
      fields.put("incarnation", RuntimeManifest.IncarnationInfo.parse(
          SystemAccess.rawEnvVar(RuntimeManifest.IncarnationInfo.ENVIRONMENT)));
      return force ? sink.append(type, fields) : sink.appendUnforced(type, fields);
    }
  }

  private static volatile Recorder recorder;
  private record Pending(LedgerRecordType type, Map<String, Object> fields) {}
  private static final java.util.ArrayDeque<Pending> beforeRecorder = new java.util.ArrayDeque<>();
  private static int pendingLost;
  private static Instant pendingLostSince;
  private final Process process;
  private final ProcessTerminator terminator;
  private final ProcessTerminator.OsProcess held;
  private final Recorder records;
  private final String id = "h-" + UUID.randomUUID();
  private final String purpose;
  private final String birth;
  private final String creatorBirth;
  private final boolean grouped;
  private String stopReason;
  private boolean cooperative;
  private TerminateResult terminated;
  private boolean recorded;
  private boolean awaited;
  private int waiters;
  private java.util.Optional<Instant> exitTime = java.util.Optional.empty();

  public static void recordTo(LedgerSink sink, EngineIdentity owner) {
    recordTo(sink, owner, null);
  }

  /**
   * The Engine's recorder. With the children ledger's {@code directory}, the Engine job's witness
   * also reads the helper windows other processes (the extraction child) record there.
   */
  public static synchronized void recordTo(LedgerSink sink, EngineIdentity owner, java.nio.file.Path directory) {
    install(sink, owner, directory);
    ProcessIntents.readWindowsFrom(directory);
  }

  private static synchronized void install(LedgerSink sink, EngineIdentity owner, java.nio.file.Path directory) {
    recorder = new Recorder(sink, owner, directory, false);
    while (!beforeRecorder.isEmpty()) {
      Pending pending = beforeRecorder.removeFirst();
      recorder.append(pending.type(), pending.fields());
    }
    if (pendingLost > 0) {
      recorder.append(LedgerRecordType.LEDGER_GAP, new LinkedHashMap<>(Map.of(
          "reason", "EVIDENCE_LOST", "count", pendingLost, "since", pendingLostSince,
          "source", "helper_boot_buffer")));
      pendingLost = 0;
      pendingLostSince = null;
    }
  }

  public static java.util.List<String> childArguments() {
    Recorder records = recorder;
    return records == null || records.directory() == null ? java.util.List.of()
        : java.util.List.of("--helper-ledger=" + records.directory().toAbsolutePath());
  }

  /** A managed parser owns its helpers; its parent Engine remains the sole rotating writer. */
  public static void recordChild(String[] args) {
    recordChild(args, false);
  }

  /** Called only after the parser established its own kill-on-close boundary. */
  public static synchronized void recordChild(String[] args, boolean nestedContainment) {
    for (String arg : args) {
      if (!arg.startsWith("--helper-ledger=")) continue;
      java.nio.file.Path dir = java.nio.file.Path.of(arg.substring("--helper-ledger=".length()));
      if (!dir.isAbsolute()) return;
      ProcessHandle self = ProcessHandle.current();
      var writer = io.justsearch.app.api.runtime.ledger.ExitLedgerWriter.appendOnly(dir,
          io.justsearch.app.api.runtime.ledger.LedgerFamily.CHILDREN,
          io.justsearch.app.api.runtime.ledger.LedgerWriterId.currentProcess(
              io.justsearch.app.api.runtime.ledger.LedgerWriterId.Role.ENGINE,
              self.info().startInstant().orElse(Instant.now())), java.time.Clock.systemUTC());
      install(LedgerSink.of(writer), new EngineIdentity(self.pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(self.pid()), "parser-" + UUID.randomUUID()), dir);
      recorder = new Recorder(recorder.sink(), recorder.owner(), dir, nestedContainment);
      return;
    }
  }

  public static Process start(ProcessBuilder builder, String purpose) throws IOException {
    return acquire(builder.start(), purpose);
  }

  /** Also usable at a caller-owned spawn seam; never accepts a PID or a ProcessHandle. */
  public static Process own(Process process, String purpose) throws IOException {
    if (process instanceof OwnedHelper) return process;
    try { if (process.pid() <= 0) return process; process.toHandle(); }
    catch (UnsupportedOperationException fake) { return process; }
    return acquire(process, purpose);
  }

  private static final java.util.Set<Process> rollbackSurvivors =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Failed admission retains the spawn object until its death is confirmed. */
  private static Process acquire(Process process, String purpose) throws IOException {
    try { return new OwnedHelper(process, purpose); }
    catch (IOException | RuntimeException failure) {
      Instant failureAt = Instant.now();
      boolean interrupted = Thread.interrupted();
      boolean ended = false;
      try {
        process.destroyForcibly(); // original spawn object, never a fresh PID lookup
        ended = process.waitFor(2, TimeUnit.SECONDS);
      } catch (InterruptedException cancelled) {
        interrupted = true;
        failure.addSuppressed(cancelled);
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (!ended) {
        rollbackSurvivors.add(process);
        try { process.onExit().thenRun(() -> rollbackSurvivors.remove(process)); }
        catch (RuntimeException watching) { failure.addSuppressed(watching); }
      }
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("reason", "WRITE_FAILED");
      fields.put("source", "helper_ownership_failed");
      fields.put("since", failureAt);
      fields.put("purpose", purpose);
      fields.put("pid", process.pid());
      fields.put("count", 1);
      fields.put("surviving", !ended);
      fields.put("code", ended ? process.exitValue() : null);
      boolean recordingInterrupted = Thread.interrupted();
      try { record(null, LedgerRecordType.LEDGER_GAP, fields); }
      catch (RuntimeException recording) { failure.addSuppressed(recording); }
      finally { if (recordingInterrupted) Thread.currentThread().interrupt(); }
      throw new OwnershipFailure(purpose, failure, ended ? null : process);
    }
  }

  /** The caller can inspect/retry the exact survivor; a global owner also retains it until exit. */
  public static final class OwnershipFailure extends IOException {
    private final Process survivor;
    private OwnershipFailure(String purpose, Throwable cause, Process survivor) {
      super("Could not own helper (" + purpose + "); rollback "
          + (survivor == null ? "confirmed exit" : "did not confirm exit"), cause);
      this.survivor = survivor;
    }
    public java.util.Optional<Process> survivor() { return java.util.Optional.ofNullable(survivor); }
  }

  private OwnedHelper(Process process, String purpose) throws IOException {
    this.process = process;
    this.purpose = purpose;
    records = recorder;
    terminator = ProcessTerminator.forCurrentOs();
    // A launch inside an open window of this process for its image: the forced window start
    // already attributes it, so its receipts are written unforced and forced as a group.
    grouped = ProcessIntents.openWindowFor(process.info().command().orElse(null), Instant.now());
    held = terminator.open(process);
    try {
      birth = held.creationFileTime().orElse(null);
      creatorBirth = SelfIdentity.BIRTH;
      Map<String, Object> fields = identity();
      fields.put("purpose", purpose);
      append(LedgerRecordType.HELPER_START, fields);
      ProcessIntents.owned(process.pid(), birth);
      process.onExit().thenRun(this::exitObserved);
    } catch (RuntimeException failure) { held.close(); throw failure; }
  }

  /** NTSTATUS severity ERROR, including access violation; a stop never excuses a real crash. */
  public static boolean crashCode(int code) { return (code & 0xC0000000) == 0xC0000000; }

  public static void terminate(Process process, String reason) {
    if (process instanceof OwnedHelper helper) helper.stop(reason);
    else process.destroyForcibly(); // test seam/third-party Process, never a PID lookup
  }

  /** The owner writes intent before closing a protocol input that asks its child to quit. */
  public static void expectInputClose(Process process, String reason) {
    if (process instanceof OwnedHelper helper) helper.intent(reason, true);
  }

  private synchronized void intent(String reason, boolean inputClose) {
    if (stopReason != null || !process.isAlive()) return;
    stopReason = purpose + ": " + reason;
    cooperative = inputClose;
    ProcessIntents.intent(process.pid(), birth, stopReason);
    Map<String, Object> fields = identity();
    fields.put("reason", stopReason);
    fields.put("requestedAt", Instant.now());
    append(LedgerRecordType.HELPER_INTENT, fields);
  }

  private synchronized void stop(String reason) {
    if (!process.isAlive()) { awaited = true; complete(); return; }
    intent(reason, false); // durable attempt before acting
    // A caller's catch/finally may cancel again while the first native stop is completing.
    // Preserve the successful call; a later ALREADY_EXITED cannot erase its provenance.
    if (!TerminateResult.OK.equals(terminated)) terminated = held.terminate(terminator.ourCode());
    if (!process.isAlive()) complete();
  }

  /** How long an exit nobody awaits or stopped waits for its owner before it is recorded. */
  static final long UNAWAITED_EXIT_GRACE_MS = 2_000;

  /**
   * The OS reports the exit, possibly before the owner's wait returns. Record it now only when a
   * forced stop intent already decides its class; otherwise let a waiting owner record it, or
   * give a late owner a short grace before recording an exit no one asked for.
   */
  private synchronized void exitObserved() {
    if (recorded) return;
    if (stopReason != null && !cooperative) { complete(); return; }
    if (waiters > 0) return;
    java.util.concurrent.CompletableFuture.delayedExecutor(UNAWAITED_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
        .execute(() -> { synchronized (this) { if (waiters == 0) complete(); } });
  }

  /** A wait that ended without observing the exit leaves it to the OS notification path. */
  private synchronized void waitEnded() {
    waiters--;
    if (waiters == 0 && !recorded && !process.isAlive()) exitObserved();
  }

  private synchronized void awaitedExit() {
    awaited = true;
    complete();
  }

  /** Writes the one helper-exit record, with its final class and reason. */
  private synchronized void complete() {
    if (recorded || process.isAlive()) return;
    exitTime = held.exitedAt();
    int code = process.exitValue();
    boolean forced = stopReason != null && TerminateResult.OK.equals(terminated)
        && code == terminator.ourCode();
    // A recorded stop intent, or an owner that awaited the helper, makes the exit intended; a
    // cooperative stop also needs the child's clean code. A crash code is never intended.
    boolean intended = !crashCode(code) && (stopReason == null ? awaited : !cooperative || code == 0);
    String reason = intended ? (stopReason != null ? stopReason : purpose + ": awaited completion")
        : crashCode(code) ? purpose + ": crash exit code"
        : stopReason != null ? stopReason + "; exit code " + code + " was not the expected clean exit"
        : purpose + ": exited with no owner wait or stop request";
    Map<String, Object> fields = identity();
    fields.put("code", code);
    fields.put("exitClass", intended ? "REQUESTED" : "UNEXPECTED");
    fields.put("reason", reason);
    fields.put("exitedAt", exitTime.map(Instant::toString));
    fields.put("observedAt", Instant.now());
    fields.put("forced", forced);
    append(LedgerRecordType.HELPER_EXIT, fields);
    recorded = true;
    ProcessIntents.outcome(process.pid(), birth, intended ? "REQUESTED" : "UNEXPECTED", reason);
    held.close();
  }

  private Map<String, Object> identity() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("helperId", id);
    fields.put("pid", process.pid());
    fields.put("creationFileTime", birth);
    fields.put("creatorPid", ProcessHandle.current().pid());
    fields.put("creatorCreationFileTime", creatorBirth);
    return fields;
  }

  private void append(LedgerRecordType type, Map<String, Object> fields) {
    // A pre-interrupted caller can still launch a helper. FileChannel would discard its
    // start receipt on that interrupt; preserve cancellation while writing the owner record.
    boolean interrupted = Thread.interrupted();
    try { record(records, type, fields, !grouped); }
    finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  /** This process's identity, the owner of the windows it opens and the creator of its helpers. */
  private static final class SelfIdentity {
    static final long PID = ProcessHandle.current().pid();
    static final String BIRTH = ProcessTerminator.forCurrentOs().creationFileTime(PID).orElse(null);
  }

  /** The longest an unforced record waits before it is forced to disk with the others pending. */
  static final long GROUP_FLUSH_MS = 1_000;
  /** Recorders holding unforced records not yet forced to disk; guarded by the class lock. */
  private static final java.util.Set<Recorder> unflushed = new java.util.LinkedHashSet<>();
  private static boolean flushScheduled;

  // Finite config probes run before ChildControl opens under the data-directory lock.
  // Retain their owner receipts, including closed windows, until that writer attaches.
  private static void record(Recorder selected, LedgerRecordType type, Map<String, Object> fields) {
    record(selected, type, fields, true);
  }

  private static synchronized void record(Recorder selected, LedgerRecordType type,
      Map<String, Object> fields, boolean force) {
    Recorder target = selected == null ? recorder : selected;
    if (target != null) {
      target.append(type, fields, force);
      if (!force) pendingFlush(target);
    } else if (beforeRecorder.size() < 4096) beforeRecorder.addLast(new Pending(type, fields));
    else {
      if (pendingLost++ == 0) pendingLostSince = Instant.now();
    }
  }

  /** Unforced records are forced as a group: at window close, at shutdown, and within 1 s. */
  private static synchronized void pendingFlush(Recorder target) {
    unflushed.add(target);
    if (flushScheduled) return;
    flushScheduled = true;
    java.util.concurrent.CompletableFuture.delayedExecutor(GROUP_FLUSH_MS, TimeUnit.MILLISECONDS)
        .execute(OwnedHelper::flushPending);
  }

  /** Forces every unforced record written so far to disk, one force per ledger. */
  public static void flushPending() {
    java.util.List<Recorder> due;
    synchronized (OwnedHelper.class) {
      due = java.util.List.copyOf(unflushed);
      unflushed.clear();
      flushScheduled = false;
    }
    for (Recorder target : due) target.sink().flush();
  }

  /** Third-party helper admission is limited to the declared image and this closed time window. */
  public static Intent window(String image, String purpose, Duration max) {
    return new Intent(recorder, image, purpose, max);
  }

  public static final class Intent implements AutoCloseable {
    private final Recorder records;
    private final String id = "i-" + UUID.randomUUID();
    private final Instant started = nativePrecisionNow();
    private final Instant deadline;
    private final String image;
    private final String purpose;
    private boolean closed;
    private Intent(Recorder records, String image, String purpose, Duration max) {
      this.records = records;
      this.image = image;
      this.purpose = purpose;
      deadline = started.plus(max);
      ProcessIntents.windowOpened(id, image, purpose, started, deadline, SelfIdentity.PID, SelfIdentity.BIRTH);
      append(LedgerRecordType.HELPER_WINDOW_START, started);
    }
    private void append(LedgerRecordType type, Instant at) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("intentId", id);
      fields.put("image", image);
      fields.put("reason", purpose);
      // Strings preserve 100 ns bounds instead of the general ledger's millisecond timestamps.
      fields.put("requestedAt", started.toString());
      fields.put("deadlineAt", deadline.toString());
      fields.put("closedAt", type == LedgerRecordType.HELPER_WINDOW_END ? at.toString() : null);
      record(records, type, fields);
    }
    @Override public synchronized void close() {
      if (!closed) {
        Instant at = nativePrecisionNow();
        append(LedgerRecordType.HELPER_WINDOW_END, at);
        ProcessIntents.windowClosed(id, at);
        closed = true;
        flushPending(); // the receipts written unforced inside this window
      }
    }
  }

  private static Instant nativePrecisionNow() {
    return nativePrecision(Instant.now());
  }

  private static Instant nativePrecision(Instant now) {
    return Instant.ofEpochSecond(now.getEpochSecond(), now.getNano() / 100 * 100L);
  }

  /** The idle period after which a shared window with no leases ends. */
  public static final Duration SHARED_WINDOW_IDLE = Duration.ofSeconds(30);

  /**
   * One window shared by every caller that may let a launch of {@code image} happen (task b6d536
   * b101). The first {@link SharedWindow#acquire lease} opens it, forcing its start record once;
   * consecutive and concurrent leases share it; it ends {@link #SHARED_WINDOW_IDLE} after the last
   * lease is released, and at {@link SharedWindow#close}. Records scale with bursts of activity,
   * not with calls. A caller that cannot launch {@code image} takes no lease.
   *
   * @param leaseMax the longest a lease may hold; a lease that could outlive the open window's
   *     deadline opens a fresh window, and the earlier window ends when its own leases are released
   */
  public static SharedWindow sharedWindow(String image, String purpose, Duration leaseMax) {
    return sharedWindow(image, purpose, leaseMax, SHARED_WINDOW_IDLE, java.time.Clock.systemUTC(),
        (delay, task) -> java.util.concurrent.CompletableFuture.delayedExecutor(
            delay.toMillis(), TimeUnit.MILLISECONDS).execute(task));
  }

  /** Test seam: a chosen clock and a scheduler that runs the idle check after a delay. */
  static SharedWindow sharedWindow(String image, String purpose, Duration leaseMax, Duration idle,
      java.time.Clock clock, java.util.function.BiConsumer<Duration, Runnable> after) {
    return new SharedWindow(image, purpose, leaseMax, idle, clock, after);
  }

  /** See {@link OwnedHelper#sharedWindow}. Thread-safe. */
  public static final class SharedWindow implements AutoCloseable {
    private final String image;
    private final String purpose;
    private final Duration leaseMax;
    private final Duration span;
    private final Duration idle;
    private final java.time.Clock clock;
    private final java.util.function.BiConsumer<Duration, Runnable> after;
    private final java.util.List<Open> draining = new java.util.ArrayList<>();
    private Open current;
    private long generation;
    private boolean closing;

    private SharedWindow(String image, String purpose, Duration leaseMax, Duration idle,
        java.time.Clock clock, java.util.function.BiConsumer<Duration, Runnable> after) {
      this.image = java.util.Objects.requireNonNull(image, "image");
      this.purpose = java.util.Objects.requireNonNull(purpose, "purpose");
      this.leaseMax = leaseMax;
      this.span = leaseMax.multipliedBy(2);
      this.idle = idle;
      this.clock = clock;
      this.after = after;
    }

    private final class Open {
      private final Recorder records = recorder;
      private final String id = "i-" + UUID.randomUUID();
      private final Instant started;
      private final Instant deadline;
      private int leases;

      private Open(Instant started) {
        this.started = started;
        this.deadline = started.plus(span);
        ProcessIntents.windowOpened(id, image, purpose, started, deadline, SelfIdentity.PID, SelfIdentity.BIRTH);
        // Forced once: it gates attribution of every launch made under this window.
        record(records, LedgerRecordType.HELPER_WINDOW_START, fields(null), true);
      }

      private void end() {
        Instant at = nativePrecision(clock.instant());
        // Not forced on its own: a lost end is reconstructed from the forced start, its deadline
        // and its owner's exit. The flush forces it with the receipts written inside the window.
        record(records, LedgerRecordType.HELPER_WINDOW_END, fields(at), false);
        ProcessIntents.windowClosed(id, at);
        flushPending();
      }

      private Map<String, Object> fields(Instant closedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("intentId", id);
        fields.put("image", image);
        fields.put("reason", purpose);
        fields.put("requestedAt", started.toString());
        fields.put("deadlineAt", deadline.toString());
        fields.put("closedAt", closedAt == null ? null : closedAt.toString());
        return fields;
      }
    }

    /** Opens a window if none can cover this lease; its start record precedes any launch. */
    public synchronized Lease acquire() {
      Instant now = nativePrecision(clock.instant());
      closing = false;
      if (current != null && now.plus(leaseMax).isAfter(current.deadline)) {
        if (current.leases == 0) current.end();
        else draining.add(current);
        current = null;
      }
      if (current == null) current = new Open(now);
      current.leases++;
      generation++;
      return new Lease(current);
    }

    private synchronized void release(Open window) {
      window.leases--;
      if (window.leases > 0) return;
      if (window != current) {
        draining.remove(window);
        window.end();
      } else if (closing) {
        current = null;
        window.end();
      } else {
        long expected = ++generation;
        after.accept(idle, () -> idleExpired(expected));
      }
    }

    private synchronized void idleExpired(long expected) {
      if (expected != generation || current == null || current.leases > 0) return;
      Open window = current;
      current = null;
      window.end();
    }

    /** Shutdown: ends an idle window now; a leased window ends when its last lease is released. */
    @Override public synchronized void close() {
      closing = true;
      generation++;
      if (current != null && current.leases == 0) {
        Open window = current;
        current = null;
        window.end();
      }
      flushPending(); // shutdown: nothing written unforced stays unforced
    }

    /** One caller's hold on the shared window; release it when the caller can no longer launch. */
    public final class Lease implements AutoCloseable {
      private final Open window;
      private boolean released;

      private Lease(Open window) {
        this.window = window;
      }

      @Override public void close() {
        synchronized (SharedWindow.this) {
          if (released) return;
          released = true;
          release(window);
        }
      }
    }
  }

  @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
  @Override public InputStream getInputStream() { return process.getInputStream(); }
  @Override public InputStream getErrorStream() { return process.getErrorStream(); }
  @Override public int waitFor() throws InterruptedException {
    synchronized (this) { waiters++; }
    try { int code = process.waitFor(); awaitedExit(); return code; }
    catch (InterruptedException interrupted) { stop("interrupted"); throw interrupted; }
    finally { waitEnded(); }
  }
  @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    synchronized (this) { waiters++; }
    try { boolean exited = process.waitFor(timeout, unit); if (exited) awaitedExit(); return exited; }
    catch (InterruptedException interrupted) { stop("interrupted"); throw interrupted; }
    finally { waitEnded(); }
  }
  @Override public int exitValue() { return process.exitValue(); }
  @Override public boolean isAlive() { return process.isAlive(); }
  @Override public long pid() { return process.pid(); }
  @Override public ProcessHandle toHandle() { return process.toHandle(); }
  @Override public void destroy() { stop("cancelled"); }
  @Override public Process destroyForcibly() { stop("cancelled"); return this; }
  @Override public boolean supportsNormalTermination() { return process.supportsNormalTermination(); }
}
