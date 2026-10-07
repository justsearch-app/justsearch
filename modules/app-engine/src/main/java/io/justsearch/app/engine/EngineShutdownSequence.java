/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The one ordered shutdown, owned by the composition root and taking a reason (design 7.3, stage B
 * item B4).
 *
 * <p><b>What moved, and what could not.</b> 7.3 says the owner is the composition root, "seeded from
 * {@code HeadShutdownCoordinator}". Stage B's checklist read that as re-homing two halves —
 * {@code HeadlessApp.performOrderedShutdown} and the coordinator — into this module. Only one of
 * them can actually move: the eight steps close {@code LocalApiServer}, {@code HeadAssembly} and
 * six other types that live in {@code modules/ui} and {@code app-services}, and the dependency edge
 * runs {@code ui -> app-engine}. Importing them here would invert it and breach ArchUnit rule 6b.
 *
 * <p>So what moves is the <b>sequence</b>, which is what 7.3 is actually about: the order, the
 * reason, the error accounting, the idempotency, the receipt and the single exit. What stays with
 * the components is the <em>binding</em> of each step to the object it closes — {@code HeadlessApp}
 * supplies an ordered list of named {@link Step}s. That is a smaller move than the checklist
 * describes and a truthful one; a literal move was not available at any price short of moving the
 * API server into the root.
 *
 * <p><b>Idempotency is load-bearing, not hygiene.</b> Three triggers can arrive at once: the
 * cooperative endpoint, the JVM shutdown hook, and B3's request-file watcher. The JVM hook in
 * particular fires <em>during</em> an exit the other two started. Running the sequence twice would
 * close an already-closed index and report the second close's failures as the shutdown's outcome,
 * so the result is memoised and the exit guarded.
 *
 * <p><b>Every step runs even if an earlier one throws.</b> A failure is recorded and the sequence
 * continues, because the steps release different resources — an exception closing telemetry must
 * not leave the index lock held. {@code clean} is false when anything failed, and the exit code
 * follows it.
 *
 * <p><b>The Engine says when it ends itself</b> (Spec B §6.1). The cooperative exits ({@link
 * #runAndExit}, {@link #runAndExitEscalated}, {@link #runAndExitWithReceipt}) and a close the JVM
 * hook starts record one {@code engine-self-intent} first, before the ordered close: the reason
 * and the exit code the Engine expects to leave ({@link #selfIntentOf}). A fatal exit records
 * none, so it stays UNEXPECTED; a hook that joins an exit already claimed records none either.
 */
public final class EngineShutdownSequence {

  private static final Logger log = LoggerFactory.getLogger(EngineShutdownSequence.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The nonce-bound receipt the updater reads. Contract unchanged from the coordinator. */
  public static final String RECEIPT_FILE = "head-shutdown-receipt.v1.json";

  /**
   * The step name whose outcome the receipt reports as {@code workerOutcome}.
   *
   * <p>Named rather than positional so re-ordering the steps cannot silently change which one the
   * updater reads.
   */
  public static final String INDEX_HALF_STEP = "index-half";

  /** One step of the ordered close. Returns an outcome label, or {@code null} if it has none. */
  @FunctionalInterface
  public interface StepAction {
    String run(Reason reason) throws Exception;
  }

  @FunctionalInterface
  public interface CompletionAction {
    void complete(Result preliminary) throws Exception;
  }

  /**
   * Records the Engine's intent to end itself ({@code EngineSelfIntentWriter.record}). Called at
   * most once per process, before the ordered close; a failure must not stop the exit.
   */
  @FunctionalInterface
  public interface SelfIntent {
    SelfIntent NONE = (reason, detail, expectedCode) -> {};

    void record(StopReason reason, Optional<StopDetail> detail, int expectedCode) throws Exception;
  }

  /** What the Engine records before ending itself: the reason and the code it expects to leave. */
  public record SelfIntentRecord(StopReason reason, Optional<StopDetail> detail, int expectedCode) {
    public SelfIntentRecord {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /**
   * A named step. The name is what appears in {@code errors}, so it is a diagnostic surface: it
   * should say which resource did not close, not which method threw.
   */
  public record Step(String name, StepAction action) {}

  /** The outcome of one ordered close. */
  public record Result(
      boolean clean, String workerOutcome, List<String> errors, Reason reason,
      Map<String, String> outcomes, NativeQuiescence nativeQuiescence) {

    public Result {
      workerOutcome = workerOutcome == null ? "UNKNOWN" : workerOutcome;
      errors = errors == null ? List.of() : List.copyOf(errors);
      outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
      Objects.requireNonNull(nativeQuiescence, "nativeQuiescence");
    }
  }

  private final Path receiptPath;
  private final List<Step> steps;
  private final IntConsumer exit;
  private final IntConsumer hardStop;
  private final Supplier<NativeQuiescence> nativeQuiescence;
  private final CompletionAction completion;
  private final SelfIntent selfIntent;
  private final AtomicBoolean selfIntentRecorded = new AtomicBoolean();
  private final AtomicReference<Result> result = new AtomicReference<>();
  private final Object exitAuthority = new Object();
  private boolean exitRequested;
  private boolean fatalExitRequested;
  private boolean exitSelected;

  /**
   * @param dataDir the data directory; the receipt lands under {@code upgrade/}
   * @param steps the ordered close, first to last — 7.3's sequence, bound by the caller
   * @param exit how to end the process; injected so tests can observe the code without dying
   */
  public EngineShutdownSequence(Path dataDir, List<Step> steps, IntConsumer exit) {
    this(dataDir, steps, exit, ignored -> {});
  }

  public EngineShutdownSequence(
      Path dataDir, List<Step> steps, IntConsumer exit, CompletionAction completion) {
    this(dataDir, steps, exit, exit, () -> NativeQuiescence.QUIESCED, completion);
  }

  /** Process composition supplies a separate hard stop for unquiesced native ownership. */
  public EngineShutdownSequence(Path dataDir, List<Step> steps, IntConsumer exit,
      IntConsumer hardStop, Supplier<NativeQuiescence> nativeQuiescence,
      CompletionAction completion) {
    this(dataDir, steps, exit, hardStop, nativeQuiescence, completion, SelfIntent.NONE);
  }

  /** The Engine's composition: {@code selfIntent} records each exit the Engine starts itself. */
  public EngineShutdownSequence(Path dataDir, List<Step> steps, IntConsumer exit,
      IntConsumer hardStop, Supplier<NativeQuiescence> nativeQuiescence,
      CompletionAction completion, SelfIntent selfIntent) {
    this.receiptPath = dataDir.resolve("upgrade").resolve(RECEIPT_FILE);
    this.steps = List.copyOf(steps);
    this.exit = Objects.requireNonNull(exit, "exit");
    this.hardStop = Objects.requireNonNull(hardStop, "hardStop");
    this.nativeQuiescence = Objects.requireNonNull(nativeQuiescence, "nativeQuiescence");
    this.completion = Objects.requireNonNull(completion, "completion");
    this.selfIntent = Objects.requireNonNull(selfIntent, "selfIntent");
  }

  /**
   * The self-intent of a cooperative exit for {@code reason} (Spec B §6.1, §6.2): QUIT and UPGRADE
   * expect 0, RESTART expects {@link EngineExit#REQUESTED_RESTART}; a HANG close is an Engine
   * restart (detail {@code hang}) that exits 0. The escalated restart is {@link
   * #escalatedSelfIntent}.
   */
  public static SelfIntentRecord selfIntentOf(Reason reason) {
    return switch (Objects.requireNonNull(reason, "reason")) {
      case QUIT -> new SelfIntentRecord(StopReason.QUIT, Optional.empty(), EngineExit.OK);
      case UPGRADE -> new SelfIntentRecord(StopReason.UPGRADE, Optional.empty(), EngineExit.OK);
      case RESTART -> new SelfIntentRecord(
          StopReason.ENGINE_RESTART, Optional.empty(), EngineExit.REQUESTED_RESTART);
      case HANG -> new SelfIntentRecord(
          StopReason.ENGINE_RESTART, Optional.of(StopDetail.HANG), EngineExit.OK);
    };
  }

  /**
   * The device-lost restart's self-intent (Spec A §7.3, reconciliation F10): reason {@code
   * DEVICE_LOST}, expecting the requested-restart code. The Engine counts these records to limit
   * device-lost restarts.
   */
  public static SelfIntentRecord deviceLostSelfIntent() {
    return new SelfIntentRecord(StopReason.DEVICE_LOST, Optional.empty(),
        EngineExit.REQUESTED_RESTART);
  }

  /** The escalated restart's self-intent: an Engine restart, detail {@code escalated}, code 5. */
  public static SelfIntentRecord escalatedSelfIntent() {
    return new SelfIntentRecord(StopReason.ENGINE_RESTART, Optional.of(StopDetail.ESCALATED),
        EngineExit.ESCALATED_RESTART);
  }

  private void recordSelfIntent(SelfIntentRecord intent) {
    if (!selfIntentRecorded.compareAndSet(false, true)) return;
    try {
      selfIntent.record(intent.reason(), intent.detail(), intent.expectedCode());
    } catch (Exception e) {
      // The intent is evidence, never a gate: the exit proceeds and is classified without it.
      log.warn("Could not record the Engine's self-intent: {}", e.toString());
    }
  }

  /**
   * Runs the ordered close once, for this reason, and returns its memoised result.
   *
   * <p>The reason of the FIRST caller wins. A second trigger arriving mid-sequence gets the running
   * shutdown's result rather than starting a second one with a different reason — a `quit` that
   * overtook a `restart` would stop llama-server (7.3 step 6) that the restart meant to leave for
   * adoption.
   */
  public Result run(Reason reason) {
    Result existing = result.get();
    if (existing != null) {
      return existing;
    }
    synchronized (result) {
      existing = result.get();
      if (existing == null) {
        existing = execute(reason);
        result.set(existing);
      }
      return existing;
    }
  }

  /** A JVM-initiated exit is already in progress; never request System.exit again from its hook. */
  public void runFromJvmShutdownHook(Reason reason) {
    boolean claimed;
    synchronized (exitAuthority) {
      claimed = exitRequested;
    }
    // A hook joining an exit this sequence already claimed adds no intent: a cooperative exit has
    // recorded its own and a fatal one records none.
    if (!claimed) recordSelfIntent(selfIntentOf(reason));
    Result shutdown = run(reason);
    if (shutdown.nativeQuiescence() != NativeQuiescence.QUIESCED) {
      // Other JVM hooks (including ORT's) may already be running concurrently. A late halt
      // shortens this unsafe interval and sets a fatal exit code; it cannot undo an earlier race.
      hardStop.accept(EngineExit.FATAL_OR_UNCAUGHT);
    }
  }

  private Result execute(Reason reason) {
    log.info("Ordered shutdown starting (reason={})", reason.wire());
    List<String> errors = new ArrayList<>();
    Map<String, String> outcomes = new LinkedHashMap<>();
    for (Step step : steps) {
      try {
        String outcome = step.action().run(reason);
        if (outcome != null) {
          outcomes.put(step.name(), outcome);
        }
      } catch (Exception e) {
        // Recorded, never rethrown: the remaining steps release different resources, and an
        // exception closing telemetry must not leave the index lock held.
        log.warn("Ordered shutdown step {} failed: {}", step.name(), e.toString());
        errors.add(step.name());
        if (INDEX_HALF_STEP.equals(step.name())) {
          outcomes.put(INDEX_HALF_STEP, "FAILED");
        }
      }
    }
    String workerOutcome = outcomes.getOrDefault(INDEX_HALF_STEP, "UNKNOWN");
    if (!"GRACEFUL".equals(workerOutcome) && !"UNKNOWN".equals(workerOutcome)) {
      errors.add("worker-" + workerOutcome.toLowerCase(Locale.ROOT));
    }
    NativeQuiescence nativeStatus;
    try {
      nativeStatus = Objects.requireNonNull(nativeQuiescence.get(), "native quiescence result");
    } catch (RuntimeException statusFailure) {
      log.warn("Native quiescence could not be read", statusFailure);
      nativeStatus = NativeQuiescence.UNQUIESCED;
    }
    outcomes.put("native-quiescence", nativeStatus.name());
    if (nativeStatus != NativeQuiescence.QUIESCED) errors.add("native-unquiesced");
    boolean clean = errors.isEmpty();
    Result preliminary = new Result(clean, workerOutcome, errors, reason, outcomes, nativeStatus);
    try {
      completion.complete(preliminary);
    } catch (Exception e) {
      log.warn("Ordered shutdown completion failed: {}", e.toString());
      errors.add("shutdown-completion");
      clean = false;
    }
    log.info(
        "Ordered shutdown complete (reason={}, clean={}, errors={})", reason.wire(), clean, errors);
    return new Result(clean, workerOutcome, errors, reason, outcomes, nativeStatus);
  }

  /**
   * Runs the sequence and exits. The cooperative and watcher triggers.
   *
   * <p>The exit is guarded separately from the sequence: the JVM hook fires during the exit the
   * endpoint requested, and a second {@code exit.accept} would re-enter shutdown.
   */
  public void runAndExit(Reason reason) {
    if (!claimExit(false)) {
      return;
    }
    recordSelfIntent(selfIntentOf(reason));
    Result shutdown = run(reason);
    int completedCode =
        shutdown.reason() == Reason.RESTART ? EngineExit.REQUESTED_RESTART : EngineExit.OK;
    terminate(shutdown, selectExitCode(shutdown.clean() ? completedCode : EngineExit.FATAL_OR_UNCAUGHT));
  }

  /**
   * Closes in restart order after essential-component recovery is exhausted. A clean close is
   * charged to the host's transient restart budget; an unclean close retains the fatal code.
   */
  public void runAndExitEscalated() {
    if (!claimExit(false)) {
      return;
    }
    recordSelfIntent(escalatedSelfIntent());
    Result shutdown = run(Reason.RESTART);
    int completedCode = shutdown.clean()
        ? EngineExit.ESCALATED_RESTART : EngineExit.FATAL_OR_UNCAUGHT;
    terminate(shutdown, selectExitCode(completedCode));
  }

  /**
   * Closes in restart order after the chat controller found the GPU lost within the device-lost
   * limit (Spec A §7.3): the self-intent names {@code DEVICE_LOST}, and a clean close exits with
   * the requested-restart code, so the supervisor starts a new Engine that probes the device again.
   */
  public void runAndExitDeviceLost() {
    if (!claimExit(false)) {
      return;
    }
    recordSelfIntent(deviceLostSelfIntent());
    Result shutdown = run(Reason.RESTART);
    int completedCode = shutdown.clean()
        ? EngineExit.REQUESTED_RESTART : EngineExit.FATAL_OR_UNCAUGHT;
    terminate(shutdown, selectExitCode(completedCode));
  }

  /**
   * Runs the ordered close and exits with the transient fatal code for an admitted process fault.
   *
   * <p>The fatal request and final code selection share {@link #exitAuthority} with the cooperative
   * paths. If a cooperative close is already running, a fatal request that arrives before its code
   * is selected upgrades that one exit to {@link EngineExit#FATAL_OR_UNCAUGHT}; a request observed
   * after selection cannot revise an exit already in progress.
   */
  public void runAndExitFatal(Reason reason) {
    if (!claimExit(true)) {
      return;
    }
    Result shutdown = run(reason);
    terminate(shutdown, selectExitCode(EngineExit.FATAL_OR_UNCAUGHT));
  }

  /**
   * Runs the sequence, writes the nonce-bound receipt, and exits. The updater's path.
   *
   * <p>The receipt is the updater's proof the Engine stopped cleanly, and a MISSING receipt is a
   * fail-closed signal to the shell — so a failure to write it is swallowed here on purpose. The
   * shell treats absence as "did not stop cleanly", which is the safe reading.
   */
  public void runAndExitWithReceipt(String preparationId, String shutdownNonce) {
    if (!claimExit(false)) {
      return;
    }
    recordSelfIntent(selfIntentOf(Reason.UPGRADE));
    Result shutdown = run(Reason.UPGRADE);
    int selectedExitCode = selectExitCode(shutdown.clean() ? 0 : 1);
    boolean cleanReceipt = shutdown.clean() && selectedExitCode == 0;
    try {
      AtomicFileWrites.replace(
          receiptPath,
          JSON.writeValueAsBytes(
              Map.of(
                  "schemaVersion", 1,
                  "preparationId", preparationId,
                  "shutdownNonce", shutdownNonce,
                  "headPid", ProcessHandle.current().pid(),
                  "clean", cleanReceipt,
                  "workerOutcome", shutdown.workerOutcome(),
                  "errors", shutdown.errors(),
                  "completedAt", Instant.now().toString())));
    } catch (Exception e) {
      log.warn("Could not write the shutdown receipt: {}", e.toString());
    }
    terminate(shutdown, selectedExitCode);
  }

  private void terminate(Result shutdown, int code) {
    if (shutdown.nativeQuiescence() == NativeQuiescence.QUIESCED) exit.accept(code);
    else hardStop.accept(code);
  }

  private boolean claimExit(boolean fatal) {
    synchronized (exitAuthority) {
      if (fatal) {
        fatalExitRequested = true;
      }
      if (exitRequested) {
        return false;
      }
      exitRequested = true;
      return true;
    }
  }

  private int selectExitCode(int preferredCode) {
    synchronized (exitAuthority) {
      if (exitSelected) {
        throw new IllegalStateException("exit code was already selected by another owner");
      }
      int selectedCode = fatalExitRequested ? EngineExit.FATAL_OR_UNCAUGHT : preferredCode;
      exitSelected = true;
      return selectedCode;
    }
  }

  /** The memoised result, if the sequence has run. Test and diagnostic surface. */
  public Result resultIfRun() {
    return result.get();
  }
}
