/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import io.justsearch.configuration.EnvRegistry;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads files using BITS (Windows preferred) with curl.exe fallback.
 *
 * <p>Extracted from v1 {@code AiInstallService}. Handles cancellation, progress reporting, and
 * platform-specific download mechanics. The download infrastructure is correct and battle-tested —
 * this class is a direct extraction, not a rewrite.
 */
public final class DownloadExecutor implements ResumableFetch.Transfer {
  private static final Logger log = LoggerFactory.getLogger(DownloadExecutor.class);

  /** Poll result sentinels for {@link #runCurl}. */
  private static final int CURL_CANCELLED = -1;

  private static final int CURL_LAUNCH_FAILED = -2;

  /**
   * The {@code DisplayName} every BITS job this app starts carries. Also the handle the orphan sweep
   * ({@link #sweepOrphanedBitsJobs}) uses to find jobs a crash left behind, so the two must agree —
   * hence one constant rather than two literals.
   */
  static final String BITS_DISPLAY_NAME = "JustSearch AI";

  /** Max consecutive polls a resumed BITS job may stay {@code Suspended} before we give up. */
  private static final int MAX_SUSPENDED_POLLS = 20;

  /** How long the poll loop waits between BITS snapshots. */
  private static final long BITS_POLL_INTERVAL_MS = 750L;

  /**
   * Hard cap on how long BITS may report no {@code BytesTransferred} progress before we stop waiting
   * — including while it retries a {@code TransientError} on its own schedule. Without a hard cap,
   * tolerating transient errors would turn a dead network into an install that never finishes.
   */
  static final long BITS_NO_PROGRESS_DEADLINE_MS = 60_000L;

  /** Max BITS-reported errors tolerated while it retries; beyond this the job is not coming back. */
  static final int MAX_BITS_ERROR_COUNT = 5;

  /** Transport tier 0: BITS first (Windows), then curl — the historical chain. */
  static final int TIER_BITS_THEN_CURL = 0;

  /** Transport tier 2: curl only, forced onto HTTP/1.1. */
  static final int TIER_CURL_HTTP1_1 = 2;

  /** Bytes of curl's merged output retained for diagnostics (tail, so the error survives). */
  static final int CURL_OUTPUT_TAIL_BYTES = 2048;

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** Callback for download progress updates. */
  @FunctionalInterface
  public interface ProgressCallback {
    void onProgress(long bytesDownloaded, long bytesTotal);
  }

  private final AtomicBoolean cancelRequested;
  private volatile Process curlProcess;
  private volatile String bitsJobId;
  private volatile String suspendedBitsJobId;
  private volatile TransportFailure lastFailure;

  public DownloadExecutor(AtomicBoolean cancelRequested) {
    this.cancelRequested = Objects.requireNonNull(cancelRequested);
  }

  /**
   * Downloads the URL to destPartial, reporting progress via callback. Returns true on success,
   * false on failure or cancellation. Tries BITS first on Windows, falls back to curl.exe.
   */
  public boolean download(String url, Path destPartial, ProgressCallback callback) {
    return download(url, destPartial, callback, TIER_BITS_THEN_CURL);
  }

  /**
   * Downloads at a given transport tier: tier 0 tries BITS then curl, every higher tier goes
   * straight to curl (tier 2 forces HTTP/1.1). Retrying an identical transport against a host that
   * just reset the connection is the cheapest thing to avoid, so a retry escalates instead.
   */
  private boolean download(String url, Path destPartial, ProgressCallback callback, int tier) {
    if (usesBits(tier) && isWindows()) {
      try {
        boolean ok = downloadWithBits(url, destPartial, callback);
        if (ok) {
          lastFailure = null;
          return true;
        }
        if (cancelRequested.get()) {
          lastFailure = TransportFailure.cancelled();
          return false;
        }
      } catch (BitsTransferException e) {
        lastFailure = TransportFailure.bits(e.jobState(), e.getMessage());
        log.info("BITS download failed; falling back to curl.exe: {}", e.getMessage());
      } catch (Exception e) {
        lastFailure = TransportFailure.bits("", String.valueOf(e.getMessage()));
        log.info("BITS download failed; falling back to curl.exe: {}", e.getMessage());
      }
    }
    return runCurlTransport(url, destPartial, callback, tier);
  }

  /**
   * Resume-aware transfer. BITS cannot adopt a {@code .partial} it did not create, so a byte-offset
   * resume goes down the curl path where {@code --continue-at -} issues a real HTTP {@code Range}
   * request; BITS is used only for a suspended job it already owns, or a from-zero start.
   */
  @Override
  public boolean transfer(
      String url,
      Path destPartial,
      DownloadResume.Decision decision,
      ProgressCallback callback,
      int transportTier) {
    lastFailure = null;
    if (shouldInjectTransportFault()) {
      lastFailure = TransportFaultInjector.syntheticFailure();
      log.warn("Failing transfer of {} synthetically: {}", destPartial.getFileName(),
          lastFailure.summary());
      return false;
    }
    DownloadResume.Action action =
        decision == null ? DownloadResume.Action.FRESH : decision.action();
    if (action == DownloadResume.Action.RESUME_BITS) {
      try {
        if (resumeBitsJob(decision.bitsJobId(), callback)) return true;
      } catch (Exception e) {
        log.info(
            "BITS resume of job {} failed; restarting from zero: {}",
            decision.bitsJobId(),
            e.getMessage());
      }
      if (cancelRequested.get()) {
        lastFailure = TransportFailure.cancelled();
        return false;
      }
      // The job held the bytes, not our .partial — a dead job means there is nothing to keep.
      removeBitsJobBestEffort(decision.bitsJobId());
      deleteBestEffort(destPartial);
      return download(url, destPartial, callback, transportTier);
    }
    if (action == DownloadResume.Action.RESUME_RANGE) {
      if (runCurlTransport(url, destPartial, callback, transportTier)) return true;
      if (cancelRequested.get()) {
        lastFailure = TransportFailure.cancelled();
        return false;
      }
      // Range refused (CURLE_RANGE_ERROR) or the remaining range is otherwise unfetchable — the
      // partial is worthless, so give up the bytes rather than the download.
      log.info(
          "Resume from byte {} failed ({}); restarting {} from zero",
          decision.resumeFromBytes(),
          lastFailure == null ? "unknown" : lastFailure.code(),
          destPartial.getFileName());
      deleteBestEffort(destPartial);
      return download(url, destPartial, callback, transportTier);
    }
    return download(url, destPartial, callback, transportTier);
  }

  /**
   * Only tier 0 spends BITS' multi-second poll budget; every escalated attempt goes straight to
   * curl, because a retry exists precisely to avoid repeating the chain that just failed.
   */
  static boolean usesBits(int tier) {
    return tier == TIER_BITS_THEN_CURL;
  }

  /** Why the last {@link #transfer} returned false, or null when the last one succeeded. */
  @Override
  public TransportFailure lastFailure() {
    return lastFailure;
  }

  /**
   * Reads the dev-only fault-injection percentage here rather than in {@link
   * TransportFaultInjector}: this class is the one the app-services env/sysprop guardrail
   * allowlists, and the injector stays a pure decision so it is testable without properties.
   */
  private static boolean shouldInjectTransportFault() {
    return TransportFaultInjector.shouldFailAttempt(
        TransportFaultInjector.parsePct(
            System.getProperty(TransportFaultInjector.PCT_PROPERTY)));
  }

  /**
   * Request cancellation of any active download. A BITS transfer is SUSPENDED rather than removed —
   * {@code Remove-BitsTransfer} deletes the job's bytes, which is what made cancelling a 10 GB
   * install destroy every byte of progress. The suspended job id is exposed via {@link
   * #suspendedBitsJobId()} so the caller can record it for the next run.
   */
  public void cancel() {
    suspendBitsBestEffort();
    cancelCurlBestEffort();
  }

  /** Id of the BITS job suspended by the last {@link #cancel()}, or null. */
  @Override
  public String suspendedBitsJobId() {
    return suspendedBitsJobId;
  }

  /** Removes a recorded BITS job that is no longer usable, so it doesn't linger in the queue. */
  @Override
  public void abandonResumeHandle(String jobId) {
    removeBitsJobBestEffort(jobId);
  }

  /**
   * Verifies a downloaded file against its expected size and SHA-256. Throws {@link
   * IllegalStateException} on mismatch (fail-closed). The size check is skipped when {@code
   * expectedSize <= 0} (size unknown in the registry). Shared by the fresh-download path and any
   * future re-verification of already-present files.
   */
  public static void verify(Path file, long expectedSize, String expectedSha256) throws Exception {
    if (expectedSize > 0) {
      long size = Files.size(file);
      if (size != expectedSize) {
        throw new IllegalStateException("Size mismatch: expected " + expectedSize + ", got " + size);
      }
    }
    String got = sha256(file);
    if (!got.equalsIgnoreCase(expectedSha256)) {
      throw new IllegalStateException("SHA-256 mismatch");
    }
  }

  /** SHA-256 hash of a file (lowercase hex). */
  public static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var in = new BufferedInputStream(Files.newInputStream(file))) {
      byte[] buf = new byte[1024 * 1024];
      int r;
      while ((r = in.read(buf)) >= 0) {
        if (r > 0) digest.update(buf, 0, r);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Best-effort atomic move (falls back to non-atomic on Windows if needed). */
  public static void moveAtomicBestEffort(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // -- BITS download ----------------------------------------------------------

  private boolean downloadWithBits(String url, Path destPartial, ProgressCallback callback)
      throws Exception {
    String jobId = startBitsJob(url, destPartial);
    bitsJobId = jobId;
    suspendedBitsJobId = null;
    return awaitBitsJob(jobId, callback);
  }

  /**
   * Resumes a BITS job suspended by an earlier {@link #cancel()} — including one from a previous
   * process, since BITS jobs live in the system service, not in this JVM. Throws when the job is
   * gone or unusable; the caller then restarts from zero.
   */
  private boolean resumeBitsJob(String jobId, ProgressCallback callback) throws Exception {
    if (jobId == null || jobId.isBlank()) return false;
    if (!isWindows()) return false;
    String script =
        "$ErrorActionPreference='Stop'; "
            + "Get-BitsTransfer -JobId '"
            + psEscape(jobId)
            + "' | Resume-BitsTransfer -Asynchronous | Out-Null";
    runPowerShell(script, Duration.ofSeconds(30));
    bitsJobId = jobId;
    suspendedBitsJobId = null;
    log.info("Resumed suspended BITS job {}", jobId);
    return awaitBitsJob(jobId, callback);
  }

  /** Polls a running BITS job to completion, reporting progress. */
  private boolean awaitBitsJob(String jobId, ProgressCallback callback) throws Exception {
    return pollBitsJob(new LiveBitsControl(), jobId, callback, cancelRequested::get);
  }

  /** The side effects the BITS poll loop needs, injected so the loop itself is unit-testable. */
  interface BitsControl {
    BitsSnapshot snapshot(String jobId) throws Exception;

    void complete(String jobId) throws Exception;

    void remove(String jobId);

    void suspend();

    void sleep(long millis) throws InterruptedException;

    long nowMs();
  }

  /**
   * Polls a BITS job to a terminal state.
   *
   * <p><b>{@code TransientError} is not terminal.</b> BITS retries a transient failure on its own
   * {@code RetryInterval}/{@code RetryTimeout} schedule; round 16 threw on the first poll that saw
   * one, discarding that machinery and converting a recoverable connection reset into a package
   * failure. Waiting is bounded on both axes so a dead network cannot hang the install: a hard
   * {@link #BITS_NO_PROGRESS_DEADLINE_MS} without {@code BytesTransferred} progress, and {@link
   * #MAX_BITS_ERROR_COUNT} BITS-reported errors. Only {@code Error} and {@code Cancelled} are
   * immediately fatal.
   */
  static boolean pollBitsJob(
      BitsControl control, String jobId, ProgressCallback callback, BooleanSupplier cancelled)
      throws Exception {
    int suspendedPolls = 0;
    long lastBytes = -1L;
    long lastProgressAtMs = control.nowMs();
    while (true) {
      if (cancelled.getAsBoolean()) {
        control.suspend();
        return false;
      }
      BitsSnapshot snap = control.snapshot(jobId);
      if (snap != null && callback != null) {
        callback.onProgress(snap.bytesTransferred(), snap.bytesTotal());
      }
      if (snap == null) {
        throw new IllegalStateException("BITS job disappeared");
      }
      if (snap.bytesTransferred() > lastBytes) {
        lastBytes = snap.bytesTransferred();
        lastProgressAtMs = control.nowMs();
      }
      switch (snap.jobState()) {
        case "Transferred" -> {
          control.complete(jobId);
          return true;
        }
        case "Error", "Cancelled" -> {
          control.remove(jobId);
          throw new BitsTransferException(snap.jobState(), snap.errorDescription());
        }
        case "TransientError" -> {
          if (snap.errorCount() > MAX_BITS_ERROR_COUNT) {
            control.remove(jobId);
            throw new BitsTransferException(
                "TransientError",
                "BITS reported "
                    + snap.errorCount()
                    + " errors while retrying: "
                    + snap.errorDescription());
          }
          failIfStalled(control, jobId, lastProgressAtMs, snap.errorDescription());
          suspendedPolls = 0;
          control.sleep(BITS_POLL_INTERVAL_MS);
        }
        case "Suspended" -> {
          // A job we just resumed should leave Suspended promptly; if it never does, the resume
          // did not take and looping forever would hang the install.
          if (++suspendedPolls > MAX_SUSPENDED_POLLS) {
            throw new IllegalStateException("BITS job stayed suspended after resume");
          }
          control.sleep(BITS_POLL_INTERVAL_MS);
        }
        default -> {
          suspendedPolls = 0;
          failIfStalled(control, jobId, lastProgressAtMs, "no bytes transferred");
          control.sleep(BITS_POLL_INTERVAL_MS);
        }
      }
    }
  }

  private static void failIfStalled(
      BitsControl control, String jobId, long lastProgressAtMs, String detail) {
    if (control.nowMs() - lastProgressAtMs <= BITS_NO_PROGRESS_DEADLINE_MS) return;
    control.remove(jobId);
    throw new BitsTransferException(
        "Stalled",
        "no BITS progress for "
            + BITS_NO_PROGRESS_DEADLINE_MS / 1000
            + "s ("
            + detail
            + ")");
  }

  /** A BITS job that ended in a state we cannot transfer from, carrying that state for triage. */
  static final class BitsTransferException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final String jobState;

    BitsTransferException(String jobState, String description) {
      super("BITS failed (" + jobState + "): " + description);
      this.jobState = jobState;
    }

    String jobState() {
      return jobState;
    }
  }

  /** The production {@link BitsControl}: real PowerShell calls plus this executor's bookkeeping. */
  private final class LiveBitsControl implements BitsControl {
    @Override
    public BitsSnapshot snapshot(String jobId) throws Exception {
      return getBitsSnapshot(jobId);
    }

    @Override
    public void complete(String jobId) throws Exception {
      completeBitsJob(jobId);
      bitsJobId = null;
    }

    @Override
    public void remove(String jobId) {
      removeBitsJobBestEffort(jobId);
      bitsJobId = null;
    }

    @Override
    public void suspend() {
      suspendBitsBestEffort();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }

    @Override
    public long nowMs() {
      return System.nanoTime() / 1_000_000L;
    }
  }

  // -- Curl download ----------------------------------------------------------

  /** Runs curl at {@code tier} and records why it failed, so the retry policy can classify it. */
  private boolean runCurlTransport(
      String url, Path destPartial, ProgressCallback callback, int tier) {
    TailBuffer tail = new TailBuffer(CURL_OUTPUT_TAIL_BYTES);
    int code = runCurl(url, destPartial, callback, tier == TIER_CURL_HTTP1_1, tail);
    if (code == 0) {
      lastFailure = null;
      return true;
    }
    if (code == CURL_CANCELLED) {
      lastFailure = TransportFailure.cancelled();
    } else if (code == CURL_LAUNCH_FAILED) {
      lastFailure = TransportFailure.curlLaunchFailed(tail.text());
    } else {
      lastFailure = TransportFailure.curlExit(code, tail.text());
    }
    return false;
  }

  /**
   * The curl argument list, split out so it is assertable without running a process.
   *
   * <p>{@code --continue-at -} is what makes resume work: curl reads the existing size of
   * {@code destPartial} and asks for {@code Range: bytes=<size>-}. On a missing/empty file it starts
   * at zero, so the same invocation serves both the fresh and the resumed case.
   *
   * <p>{@code --retry-all-errors} is the round-16 lesson, proven with a local empty-reply server:
   * plain {@code --retry N} covers only curl's own transient set, so an exit 52 or 35 made exactly
   * ONE connection in milliseconds. The speed floor kills a transfer that is open but not moving,
   * which {@code --connect-timeout} alone does not catch.
   */
  static List<String> curlCommand(String url, Path destPartial, boolean forceHttp11) {
    List<String> cmd = new ArrayList<>();
    cmd.add("curl.exe");
    cmd.add("--fail");
    cmd.add("--location");
    cmd.add("--silent");
    cmd.add("--show-error");
    cmd.add("--retry");
    cmd.add("3");
    cmd.add("--retry-delay");
    cmd.add("2");
    cmd.add("--retry-all-errors");
    cmd.add("--retry-connrefused");
    cmd.add("--connect-timeout");
    cmd.add("20");
    cmd.add("--speed-limit");
    cmd.add("1024");
    cmd.add("--speed-time");
    cmd.add("60");
    cmd.add("--user-agent");
    cmd.add(userAgent());
    if (forceHttp11) {
      cmd.add("--http1.1");
    }
    cmd.add("--continue-at");
    cmd.add("-");
    cmd.add("--output");
    cmd.add(destPartial.toAbsolutePath().toString());
    cmd.add(url);
    return List.copyOf(cmd);
  }

  /** {@code JustSearch/<app version>}, from the same authority the rest of the app reports. */
  static String userAgent() {
    String version = EnvRegistry.APP_VERSION.get().orElse("").trim();
    return "JustSearch/" + (version.isEmpty() ? "dev" : version);
  }

  /**
   * Runs curl.exe, returning its exit code — 0 on success, {@link #CURL_CANCELLED} when stopped by
   * cancellation, {@link #CURL_LAUNCH_FAILED} when it could not be run, otherwise curl's own code
   * (33, CURLE_RANGE_ERROR, means the server refused the resume Range request).
   */
  private int runCurl(
      String url, Path destPartial, ProgressCallback callback, boolean forceHttp11, TailBuffer tail) {
    try {
      ProcessBuilder pb = new ProcessBuilder(curlCommand(url, destPartial, forceHttp11));
      pb.redirectErrorStream(true);
      curlProcess = OwnedHelper.start(pb, "curl download");
      try {
        InputStream stream = curlProcess.getInputStream();
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    stream.transferTo(tail);
                  } catch (Exception ignored) {
                    // best-effort drain
                  }
                });
      } catch (Exception ignored) {
        // best-effort
      }

      while (curlProcess.isAlive()) {
        if (cancelRequested.get()) {
          cancelCurlBestEffort();
          return CURL_CANCELLED;
        }
        long sz = sizeBestEffort(destPartial);
        if (callback != null) {
          callback.onProgress(sz, 0);
        }
        Thread.sleep(750);
      }
      int code = curlProcess.waitFor();
      curlProcess = null;
      if (code != 0) {
        // Round 16's investigator could recover only this number from the head log; the tail is
        // what turns "exit 52" into an explanation, and it rides along to the install status.
        log.warn("curl.exe failed with exit code {}: {}", code, tail.text());
      }
      return code;
    } catch (Exception e) {
      log.warn("Download failed: {}", e.getMessage());
      tail.append(String.valueOf(e.getMessage()));
      return CURL_LAUNCH_FAILED;
    }
  }

  /**
   * A bounded tail of a process' output: an {@link OutputStream} that keeps only the last N bytes.
   * curl's failure text is at the END of its output, and the whole point is to keep the diagnosis
   * without letting a runaway stream into memory or into the API's error field.
   */
  static final class TailBuffer extends OutputStream {
    private final byte[] ring;
    private int next;
    private boolean wrapped;

    TailBuffer(int capacity) {
      this.ring = new byte[Math.max(1, capacity)];
    }

    @Override
    public synchronized void write(int b) {
      ring[next] = (byte) b;
      next = (next + 1) % ring.length;
      if (next == 0) wrapped = true;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      for (int i = 0; i < len; i++) {
        write(b[off + i]);
      }
    }

    /** Appends text produced outside the process' own stream (e.g. a launch exception). */
    synchronized void append(String text) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      write(bytes, 0, bytes.length);
    }

    /** The retained tail, trimmed; empty when nothing was written. */
    synchronized String text() {
      int length = wrapped ? ring.length : next;
      if (length == 0) return "";
      byte[] out = new byte[length];
      if (wrapped) {
        System.arraycopy(ring, next, out, 0, ring.length - next);
        System.arraycopy(ring, 0, out, ring.length - next, next);
      } else {
        System.arraycopy(ring, 0, out, 0, length);
      }
      return new String(out, StandardCharsets.UTF_8).trim();
    }
  }

  // -- Cancellation -----------------------------------------------------------

  /**
   * Suspends the active BITS job, keeping its transferred bytes so the next run can resume it. If
   * the suspend itself fails there is nothing resumable, so the job is removed instead of being left
   * stuck in the queue.
   */
  private void suspendBitsBestEffort() {
    String jobId = bitsJobId;
    if (jobId == null || jobId.isBlank()) return;
    try {
      String script =
          "$ErrorActionPreference='Stop'; "
              + "Get-BitsTransfer -JobId '"
              + psEscape(jobId)
              + "' | Suspend-BitsTransfer -Confirm:$false | Out-Null";
      runPowerShell(script, Duration.ofSeconds(10));
      suspendedBitsJobId = jobId;
      log.info("Suspended BITS job {} (progress preserved for resume)", jobId);
    } catch (Exception e) {
      log.debug("BITS suspend failed; removing the job instead (best-effort)", e);
      removeBitsJobBestEffort(jobId);
      suspendedBitsJobId = null;
    }
    bitsJobId = null;
  }

  /** Destroys a BITS job and its bytes. Only for jobs that are unusable or already terminal. */
  private void removeBitsJobBestEffort(String jobId) {
    if (jobId == null || jobId.isBlank()) return;
    removeBitsJobViaPowerShell(jobId);
    if (jobId.equals(bitsJobId)) bitsJobId = null;
    if (jobId.equals(suspendedBitsJobId)) suspendedBitsJobId = null;
  }

  /**
   * The PowerShell half of {@link #removeBitsJobBestEffort}, without this executor's own bookkeeping
   * — so the orphan sweep, which has no executor and no fields to clear, removes jobs through exactly
   * the same command.
   */
  private static void removeBitsJobViaPowerShell(String jobId) {
    if (jobId == null || jobId.isBlank()) return;
    try {
      String script =
          "$ErrorActionPreference='SilentlyContinue'; "
              + "Get-BitsTransfer -JobId '"
              + psEscape(jobId)
              + "' | Remove-BitsTransfer -Confirm:$false | Out-Null";
      runPowerShell(script, Duration.ofSeconds(10));
    } catch (Exception e) {
      log.debug("BITS remove failed (best-effort)", e);
    }
  }

  private void cancelCurlBestEffort() {
    Process p = curlProcess;
    if (p == null) return;
    try {
      p.destroyForcibly();
    } catch (Exception e) {
      log.debug("curl process destroy failed (best-effort)", e);
    }
    curlProcess = null;
  }

  // -- BITS PowerShell helpers ------------------------------------------------

  /**
   * Starts an asynchronous BITS job. The retry schedule is passed explicitly rather than inherited
   * from machine policy: {@code -RetryInterval 60 -RetryTimeout 300} is the budget {@link
   * #pollBitsJob} tolerates a {@code TransientError} for, so the two must agree.
   */
  static String startBitsScript(String url, Path dest) {
    return "$ErrorActionPreference='Stop'; "
        + "$job = Start-BitsTransfer -Source '"
        + psEscape(url)
        + "' -Destination '"
        + psEscape(dest.toAbsolutePath().toString())
        + "' -Asynchronous -RetryInterval 60 -RetryTimeout 300 "
        + "-DisplayName '"
        + psEscape(BITS_DISPLAY_NAME)
        + "' -Description 'JustSearch AI model download'; "
        + "$job.JobId.Guid";
  }

  // -- Orphaned-job sweep (tempdoc 840) ---------------------------------------

  /**
   * Which enumerated {@code JustSearch AI} BITS jobs are orphans: in the queue, claimed by no current
   * resume sidecar. Returned in enumeration order, de-duplicated.
   *
   * <p>Pure and package-private on purpose. The one way this sweep can do damage is by removing a job
   * an install is about to resume, and that decision must be testable without Windows — the same
   * reason {@link BitsControl} exists. Ids are compared case-insensitively and with an optional
   * {@code {…}} wrapper stripped, because a GUID's spelling is not part of its identity.
   *
   * @param enumerated job ids currently in the BITS queue under this app's display name
   * @param claimed job ids recorded by the resume sidecars of the downloads this run plans
   */
  static List<String> orphanedBitsJobIds(
      Collection<String> enumerated, Collection<String> claimed) {
    List<String> orphans = new ArrayList<>();
    if (enumerated == null) return orphans;
    Set<String> keep = new HashSet<>();
    if (claimed != null) {
      for (String id : claimed) {
        if (id != null && !id.isBlank()) keep.add(normalizeJobId(id));
      }
    }
    Set<String> alreadyListed = new HashSet<>();
    for (String id : enumerated) {
      if (id == null || id.isBlank()) continue;
      String normalized = normalizeJobId(id);
      if (keep.contains(normalized)) continue;
      if (alreadyListed.add(normalized)) orphans.add(id.trim());
    }
    return orphans;
  }

  private static String normalizeJobId(String raw) {
    String id = raw.trim();
    if (id.length() > 2 && id.startsWith("{") && id.endsWith("}")) {
      id = id.substring(1, id.length() - 1);
    }
    return id.toLowerCase(Locale.ROOT);
  }

  /**
   * Removes {@code JustSearch AI} BITS jobs that no {@code claimedJobIds} entry claims, and returns
   * how many were removed.
   *
   * <p>{@code ResumableFetch} writes its resume sidecar with a null {@code bitsJobId} before every
   * transfer and only writes the id back when a transfer fails <em>gracefully</em>. A crash or hard
   * kill mid-transfer therefore leaves a live job in the BITS queue with nothing recording it, and
   * {@code Transfer.abandonResumeHandle} only ever fires for an id a sidecar DOES record — so those
   * jobs sat in the queue for its 90-day default lifetime. The display name {@link #startBitsScript}
   * sets is the handle that makes them findable again.
   *
   * <p>Entirely best-effort: every PowerShell failure logs at debug and the install proceeds. Non-
   * Windows is a no-op.
   */
  static int sweepOrphanedBitsJobs(Collection<String> claimedJobIds) {
    if (!isWindows()) return 0;
    List<String> enumerated;
    try {
      enumerated = enumerateBitsJobIdsByDisplayName();
    } catch (Exception e) {
      log.debug("BITS orphan sweep skipped; could not enumerate jobs ({})", e.toString());
      return 0;
    }
    List<String> orphans = orphanedBitsJobIds(enumerated, claimedJobIds);
    if (orphans.isEmpty()) return 0;
    log.info(
        "Reclaiming {} orphaned BITS job(s) of {} in the queue (no resume sidecar claims them)",
        orphans.size(),
        enumerated.size());
    for (String jobId : orphans) {
      removeBitsJobViaPowerShell(jobId);
    }
    return orphans.size();
  }

  /** Job ids of every queued BITS job this app started, matched on the display name it sets. */
  private static List<String> enumerateBitsJobIdsByDisplayName() throws Exception {
    String script =
        "$ErrorActionPreference='SilentlyContinue'; "
            + "Get-BitsTransfer | Where-Object { $_.DisplayName -eq '"
            + psEscape(BITS_DISPLAY_NAME)
            + "' } | ForEach-Object { $_.JobId.Guid }";
    String out = runPowerShell(script, Duration.ofSeconds(20));
    List<String> ids = new ArrayList<>();
    for (String line : out.split("\\R")) {
      String id = line.trim();
      if (!id.isBlank()) ids.add(id);
    }
    return ids;
  }

  private static String startBitsJob(String url, Path dest) throws Exception {
    String out = runPowerShell(startBitsScript(url, dest), Duration.ofSeconds(30));
    String id = out.trim();
    if (id.isBlank()) throw new IllegalStateException("BITS did not return a JobId");
    return id;
  }

  private static void completeBitsJob(String jobId) throws Exception {
    String id = psEscape(jobId);
    String script =
        "$ErrorActionPreference='Stop'; "
            + "Get-BitsTransfer -JobId '"
            + id
            + "' | Complete-BitsTransfer -Confirm:$false | Out-Null";
    runPowerShell(script, Duration.ofSeconds(30));
  }

  private static BitsSnapshot getBitsSnapshot(String jobId) throws Exception {
    String id = psEscape(jobId);
    String script =
        "$ErrorActionPreference='Stop'; "
            + "$job = Get-BitsTransfer -JobId '"
            + id
            + "'; "
            + "$obj = [PSCustomObject]@{ "
            + "JobState = $job.JobState.ToString(); "
            + "BytesTotal = "
            + bitsCountExpression("$job.BytesTotal")
            + "; "
            + "BytesTransferred = "
            + bitsCountExpression("$job.BytesTransferred")
            + "; "
            + "ErrorCount = [Int32]$job.ErrorCount; "
            + "ErrorDescription = $job.ErrorDescription }; "
            + "$obj | ConvertTo-Json -Compress";
    String out = runPowerShell(script, Duration.ofSeconds(10)).trim();
    if (out.isBlank()) return null;
    return JSON.readValue(out, BitsSnapshot.class);
  }

  static String bitsCountExpression(String expression) {
    return "(& { $v = "
        + expression
        + "; if ($null -eq $v) { 0 } else { $u = [UInt64]$v; "
        + "if ($u -eq [UInt64]::MaxValue -or $u -gt [UInt64]([Int64]::MaxValue)) "
        + "{ 0 } else { [Int64]$u } } })";
  }

  record BitsSnapshot(
      String JobState,
      long BytesTotal,
      long BytesTransferred,
      int ErrorCount,
      String ErrorDescription) {
    String jobState() {
      return JobState == null ? "" : JobState;
    }

    long bytesTotal() {
      return BytesTotal;
    }

    long bytesTransferred() {
      return BytesTransferred;
    }

    int errorCount() {
      return ErrorCount;
    }

    String errorDescription() {
      return ErrorDescription == null ? "" : ErrorDescription;
    }
  }

  private static String runPowerShell(String script, Duration timeout) throws Exception {
    Objects.requireNonNull(script, "script");
    ProcessBuilder pb =
        new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
    pb.redirectErrorStream(true);
    Process p = OwnedHelper.start(pb, "BITS download control");
    boolean ok = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!ok) {
      OwnedHelper.terminate(p, "timeout");
      throw new IllegalStateException("PowerShell timed out");
    }
    byte[] bytes = p.getInputStream().readAllBytes();
    String out = new String(bytes, StandardCharsets.UTF_8);
    if (p.exitValue() != 0) {
      throw new IllegalStateException(
          "PowerShell failed (exit " + p.exitValue() + "): " + out.trim());
    }
    return out;
  }

  // -- Utilities --------------------------------------------------------------

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static String psEscape(String raw) {
    if (raw == null) return "";
    return raw.replace("'", "''");
  }

  private static void deleteBestEffort(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      log.debug("Failed to delete {} (best-effort)", p, e);
    }
  }

  private static long sizeBestEffort(Path p) {
    try {
      return Files.exists(p) ? Files.size(p) : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
