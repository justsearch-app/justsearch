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
  private record Recorder(LedgerSink sink, EngineIdentity owner, java.nio.file.Path directory) {
    boolean append(LedgerRecordType type, Map<String, Object> fields) {
      fields.put("owner", Map.of("pid", owner.pid(), "creationFileTime", owner.creationFileTime(),
          "instanceId", owner.instanceId()));
      fields.put("incarnation", RuntimeManifest.IncarnationInfo.parse(
          SystemAccess.rawEnvVar(RuntimeManifest.IncarnationInfo.ENVIRONMENT)));
      return sink.append(type, fields);
    }
  }

  private static volatile Recorder recorder;
  private final Process process;
  private final ProcessTerminator terminator;
  private final ProcessTerminator.OsProcess held;
  private final Recorder records;
  private final String id = "h-" + UUID.randomUUID();
  private final String purpose;
  private final String birth;
  private String stopReason;
  private boolean cooperative;
  private TerminateResult terminated;
  private boolean recorded;
  private boolean handled;
  private java.util.Optional<Instant> exitTime = java.util.Optional.empty();

  public static void recordTo(LedgerSink sink, EngineIdentity owner) {
    recordTo(sink, owner, null);
  }

  public static void recordTo(LedgerSink sink, EngineIdentity owner, java.nio.file.Path directory) {
    recorder = new Recorder(sink, owner, directory);
  }

  public static java.util.List<String> childArguments() {
    Recorder records = recorder;
    return records == null || records.directory() == null ? java.util.List.of()
        : java.util.List.of("--helper-ledger=" + records.directory().toAbsolutePath());
  }

  /** A managed parser owns its helpers; its parent Engine remains the sole rotating writer. */
  public static void recordChild(String[] args) {
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
      recordTo(LedgerSink.of(writer), new EngineIdentity(self.pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(self.pid()), "parser-" + UUID.randomUUID()), dir);
      return;
    }
  }

  public static Process start(ProcessBuilder builder, String purpose) throws IOException {
    return new OwnedHelper(builder.start(), purpose);
  }

  /** Also usable at a caller-owned spawn seam; never accepts a PID or a ProcessHandle. */
  public static Process own(Process process, String purpose) throws IOException {
    if (process instanceof OwnedHelper) return process;
    try { if (process.pid() <= 0) return process; process.toHandle(); }
    catch (UnsupportedOperationException fake) { return process; }
    return new OwnedHelper(process, purpose);
  }

  private OwnedHelper(Process process, String purpose) throws IOException {
    this.process = process;
    this.purpose = purpose;
    records = recorder;
    terminator = ProcessTerminator.forCurrentOs();
    held = terminator.open(process);
    birth = held.creationFileTime().orElse(null);
    Map<String, Object> fields = identity();
    fields.put("purpose", purpose);
    append(LedgerRecordType.HELPER_START, fields);
    process.onExit().thenRun(() -> {
      synchronized (this) {
        complete(false);
      }
    });
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
    Map<String, Object> fields = identity();
    fields.put("reason", stopReason);
    fields.put("requestedAt", Instant.now());
    append(LedgerRecordType.HELPER_INTENT, fields);
  }

  private synchronized void stop(String reason) {
    if (!process.isAlive()) { complete(true); return; }
    intent(reason, false); // durable attempt before acting
    // A caller's catch/finally may cancel again while the first native stop is completing.
    // Preserve the successful call; a later ALREADY_EXITED cannot erase its provenance.
    if (!TerminateResult.OK.equals(terminated)) terminated = held.terminate(terminator.ourCode());
    if (!process.isAlive()) complete(false);
  }

  private synchronized void complete(boolean awaited) {
    if (process.isAlive() || (recorded && (!awaited || handled || (stopReason != null && !cooperative)))) return;
    if (!recorded) exitTime = held.exitedAt();
    handled |= awaited;
    int code = process.exitValue();
    boolean forced = stopReason != null && TerminateResult.OK.equals(terminated)
        && code == terminator.ourCode();
    boolean intended = !crashCode(code) && (stopReason == null ? awaited
        : forced || (cooperative && awaited && code == 0));
    Map<String, Object> fields = identity();
    fields.put("code", code);
    fields.put("exitClass", intended ? "REQUESTED" : "UNEXPECTED");
    fields.put("reason", intended ? (stopReason != null ? stopReason : purpose + ": awaited completion") : null);
    fields.put("exitedAt", exitTime.map(Instant::toString));
    fields.put("observedAt", Instant.now());
    fields.put("forced", forced);
    append(LedgerRecordType.HELPER_EXIT, fields);
    recorded = true;
    held.close();
  }

  private Map<String, Object> identity() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("helperId", id);
    fields.put("pid", process.pid());
    fields.put("creationFileTime", birth);
    return fields;
  }

  private void append(LedgerRecordType type, Map<String, Object> fields) {
    if (records != null) records.append(type, fields);
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
      append(LedgerRecordType.HELPER_WINDOW_START, started);
    }
    private void append(LedgerRecordType type, Instant at) {
      if (records == null) return;
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("intentId", id);
      fields.put("image", image);
      fields.put("reason", purpose);
      // Strings preserve 100 ns bounds instead of the general ledger's millisecond timestamps.
      fields.put("requestedAt", started.toString());
      fields.put("deadlineAt", deadline.toString());
      fields.put("closedAt", type == LedgerRecordType.HELPER_WINDOW_END ? at.toString() : null);
      records.append(type, fields);
    }
    @Override public synchronized void close() {
      if (!closed) { append(LedgerRecordType.HELPER_WINDOW_END, nativePrecisionNow()); closed = true; }
    }
  }

  private static Instant nativePrecisionNow() {
    Instant now = Instant.now();
    return Instant.ofEpochSecond(now.getEpochSecond(), now.getNano() / 100 * 100L);
  }

  @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
  @Override public InputStream getInputStream() { return process.getInputStream(); }
  @Override public InputStream getErrorStream() { return process.getErrorStream(); }
  @Override public int waitFor() throws InterruptedException {
    try { int code = process.waitFor(); complete(true); return code; }
    catch (InterruptedException interrupted) { stop("interrupted"); throw interrupted; }
  }
  @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    try { boolean exited = process.waitFor(timeout, unit); if (exited) complete(true); return exited; }
    catch (InterruptedException interrupted) { stop("interrupted"); throw interrupted; }
  }
  @Override public int exitValue() { return process.exitValue(); }
  @Override public boolean isAlive() { return process.isAlive(); }
  @Override public long pid() { return process.pid(); }
  @Override public ProcessHandle toHandle() { return process.toHandle(); }
  @Override public void destroy() { stop("cancelled"); }
  @Override public Process destroyForcibly() { stop("cancelled"); return this; }
  @Override public boolean supportsNormalTermination() { return process.supportsNormalTermination(); }
}
