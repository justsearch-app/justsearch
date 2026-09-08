/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
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

  /**
   * A named step. The name is what appears in {@code errors}, so it is a diagnostic surface: it
   * should say which resource did not close, not which method threw.
   */
  public record Step(String name, StepAction action) {}

  /** The outcome of one ordered close. */
  public record Result(
      boolean clean, String workerOutcome, List<String> errors, Reason reason,
      Map<String, String> outcomes) {

    public Result {
      workerOutcome = workerOutcome == null ? "UNKNOWN" : workerOutcome;
      errors = errors == null ? List.of() : List.copyOf(errors);
      outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
    }
  }

  private final Path receiptPath;
  private final List<Step> steps;
  private final IntConsumer exit;
  private final AtomicReference<Result> result = new AtomicReference<>();
  private final AtomicBoolean exitRequested = new AtomicBoolean();

  /**
   * @param dataDir the data directory; the receipt lands under {@code upgrade/}
   * @param steps the ordered close, first to last — 7.3's sequence, bound by the caller
   * @param exit how to end the process; injected so tests can observe the code without dying
   */
  public EngineShutdownSequence(Path dataDir, List<Step> steps, IntConsumer exit) {
    this.receiptPath = dataDir.resolve("upgrade").resolve(RECEIPT_FILE);
    this.steps = List.copyOf(steps);
    this.exit = exit;
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
      }
    }
    String workerOutcome = outcomes.getOrDefault(INDEX_HALF_STEP, "UNKNOWN");
    if (!"GRACEFUL".equals(workerOutcome) && !"UNKNOWN".equals(workerOutcome)) {
      errors.add("worker-" + workerOutcome.toLowerCase(Locale.ROOT));
    }
    boolean clean = errors.isEmpty();
    log.info(
        "Ordered shutdown complete (reason={}, clean={}, errors={})", reason.wire(), clean, errors);
    return new Result(clean, workerOutcome, errors, reason, outcomes);
  }

  /**
   * Runs the sequence and exits. The cooperative and watcher triggers.
   *
   * <p>The exit is guarded separately from the sequence: the JVM hook fires during the exit the
   * endpoint requested, and a second {@code exit.accept} would re-enter shutdown.
   */
  public void runAndExit(Reason reason) {
    if (!exitRequested.compareAndSet(false, true)) {
      return;
    }
    Result shutdown = run(reason);
    exit.accept(shutdown.clean() ? 0 : 1);
  }

  /**
   * Runs the sequence, writes the nonce-bound receipt, and exits. The updater's path.
   *
   * <p>The receipt is the updater's proof the Engine stopped cleanly, and a MISSING receipt is a
   * fail-closed signal to the shell — so a failure to write it is swallowed here on purpose. The
   * shell treats absence as "did not stop cleanly", which is the safe reading.
   */
  public void runAndExitWithReceipt(String preparationId, String shutdownNonce) {
    if (!exitRequested.compareAndSet(false, true)) {
      return;
    }
    Result shutdown = run(Reason.UPGRADE);
    try {
      AtomicFileWrites.replace(
          receiptPath,
          JSON.writeValueAsBytes(
              Map.of(
                  "schemaVersion", 1,
                  "preparationId", preparationId,
                  "shutdownNonce", shutdownNonce,
                  "headPid", ProcessHandle.current().pid(),
                  "clean", shutdown.clean(),
                  "workerOutcome", shutdown.workerOutcome(),
                  "errors", shutdown.errors(),
                  "completedAt", Instant.now().toString())));
    } catch (Exception e) {
      log.warn("Could not write the shutdown receipt: {}", e.toString());
    }
    exit.accept(shutdown.clean() ? 0 : 1);
  }

  /** The memoised result, if the sequence has run. Test and diagnostic surface. */
  public Result resultIfRun() {
    return result.get();
  }
}
