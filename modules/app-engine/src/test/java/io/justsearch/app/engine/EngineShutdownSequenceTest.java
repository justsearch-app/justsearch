/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineShutdownSequence.Step;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage B item B4 — the ordered shutdown, now owned by the composition root.
 *
 * <p>The first two cases are {@code HeadShutdownCoordinatorTest}'s, moved with the behaviour they
 * pin rather than deleted: the receipt contract and the exit code are the updater's interface and
 * did not change. The rest are what B4 adds — the reason reaching the steps, and the reason of the
 * first caller winning.
 */
@DisplayName("EngineShutdownSequence — the one ordered close (stage B item B4)")
final class EngineShutdownSequenceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Step ok(String name) {
    return new Step(name, reason -> null);
  }

  @Test
  @DisplayName("the upgrade path is idempotent and writes a nonce-bound receipt")
  void upgradeShutdownIsIdempotentAndWritesNonceBoundReceipt(@TempDir Path dataDir)
      throws Exception {
    var stepCalls = new AtomicInteger();
    var exitCalls = new AtomicInteger();
    var exitCode = new AtomicInteger(-1);

    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new Step(
                    EngineShutdownSequence.INDEX_HALF_STEP,
                    reason -> {
                      stepCalls.incrementAndGet();
                      return "GRACEFUL";
                    })),
            code -> {
              exitCode.set(code);
              exitCalls.incrementAndGet();
            });

    sequence.runAndExitWithReceipt("prep-1", "nonce-1");
    sequence.runAndExitWithReceipt("prep-2", "nonce-2");

    assertEquals(1, stepCalls.get(), "the ordered close runs once, however many triggers arrive");
    assertEquals(1, exitCalls.get());
    assertEquals(0, exitCode.get());

    Path receipt = dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE);
    assertTrue(Files.isRegularFile(receipt));
    var body = JSON.readTree(Files.readString(receipt));
    assertEquals("prep-1", body.get("preparationId").stringValue());
    assertEquals("nonce-1", body.get("shutdownNonce").stringValue());
    assertTrue(body.get("headPid").asLong() > 0);
    assertTrue(body.get("clean").asBoolean());
    assertEquals("GRACEFUL", body.get("workerOutcome").stringValue());
    assertEquals(0, body.get("errors").size());
    assertFalse(body.get("completedAt").stringValue().isBlank());
  }

  @Test
  @DisplayName("a failed close produces a failure receipt and exit code 1")
  void failedOrderedShutdownProducesFailureReceiptAndExitCode(@TempDir Path dataDir)
      throws Exception {
    var exitCode = new AtomicInteger(-1);
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(new Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> "FORCED")),
            exitCode::set);

    sequence.runAndExitWithReceipt("prep-1", "nonce-1");

    assertEquals(1, exitCode.get());
    var body =
        JSON.readTree(
            Files.readString(
                dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
    assertEquals("FORCED", body.get("workerOutcome").stringValue());
    assertEquals("worker-forced", body.get("errors").get(0).stringValue());
  }

  @Test
  @DisplayName("a fatal request before upgrade exit selection prevents a clean receipt")
  void fatalRequestBeforeUpgradeSelectionProducesFailureReceipt(@TempDir Path dataDir)
      throws Exception {
    var stepEntered = new java.util.concurrent.CountDownLatch(1);
    var releaseStep = new java.util.concurrent.CountDownLatch(1);
    var exitCode = new AtomicInteger(-1);
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new Step(
                    EngineShutdownSequence.INDEX_HALF_STEP,
                    reason -> {
                      stepEntered.countDown();
                      assertTrue(releaseStep.await(2, java.util.concurrent.TimeUnit.SECONDS));
                      return "GRACEFUL";
                    })),
            exitCode::set);
    Thread upgrade =
        Thread.ofVirtual()
            .start(() -> sequence.runAndExitWithReceipt("prep-fatal", "nonce-fatal"));
    assertTrue(stepEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));

    sequence.runAndExitFatal(Reason.RESTART);
    releaseStep.countDown();
    upgrade.join(java.time.Duration.ofSeconds(2));

    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, exitCode.get());
    var body =
        JSON.readTree(
            Files.readString(
                dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
    assertFalse(body.get("clean").asBoolean());
  }

  @Test
  @DisplayName("an omitted index step remains unknown")
  void omittedIndexStepReportsUnknown(@TempDir Path dataDir) {
    var sequence =
        new EngineShutdownSequence(dataDir, List.of(ok("telemetry")), ignored -> {});

    var result = sequence.run(Reason.QUIT);

    assertTrue(result.clean());
    assertEquals("UNKNOWN", result.workerOutcome());
  }

  @Test
  @DisplayName("an exception closing the index half reports failed rather than unknown")
  void throwingIndexHalfReportsFailed(@TempDir Path dataDir) {
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new Step(
                    EngineShutdownSequence.INDEX_HALF_STEP,
                    ignored -> {
                      throw new IllegalStateException("close failed");
                    })),
            ignored -> {});

    var result = sequence.run(Reason.QUIT);

    assertFalse(result.clean());
    assertEquals("FAILED", result.workerOutcome());
    assertEquals(List.of("index-half", "worker-failed"), result.errors());
  }

  @Test
  @DisplayName("every reason reaches every step")
  void everyReasonReachesTheSteps(@TempDir Path dataDir) {
    for (Reason reason : Reason.values()) {
      List<Reason> seen = new ArrayList<>();
      var sequence =
          new EngineShutdownSequence(
              dataDir.resolve(reason.wire()),
              List.of(
                  new Step("a", r -> { seen.add(r); return null; }),
                  new Step("b", r -> { seen.add(r); return null; })),
              code -> {});
      sequence.run(reason);
      assertEquals(
          List.of(reason, reason),
          seen,
          "step 6 of design 7.3 branches on the reason, so it has to arrive at the steps");
    }
  }

  @Test
  @DisplayName("steps run in the order given, and a throwing step does not stop the rest")
  void everyStepRunsInOrderEvenAfterAFailure(@TempDir Path dataDir) {
    List<String> order = new ArrayList<>();
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new Step("first", r -> { order.add("first"); return null; }),
                new Step("boom", r -> { throw new IllegalStateException("no"); }),
                new Step("third", r -> { order.add("third"); return null; })),
            code -> {});

    var result = sequence.run(Reason.QUIT);

    assertEquals(
        List.of("first", "third"),
        order,
        "the steps release DIFFERENT resources — an exception closing one must not leave the index"
            + " lock held, so the sequence records the failure and carries on");
    assertFalse(result.clean());
    assertEquals(List.of("boom"), result.errors(), "the failing step is named, not the exception");
  }

  @Test
  @DisplayName("the reason of the first caller wins; a later trigger joins rather than re-running")
  void firstReasonWins(@TempDir Path dataDir) {
    List<Reason> seen = new ArrayList<>();
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(new Step("only", r -> { seen.add(r); return null; })),
            code -> {});

    var first = sequence.run(Reason.RESTART);
    var second = sequence.run(Reason.QUIT);

    assertEquals(List.of(Reason.RESTART), seen, "the close runs once");
    assertEquals(Reason.RESTART, first.reason());
    assertEquals(
        Reason.RESTART,
        second.reason(),
        "a quit that overtook a restart would stop llama-server (7.3 step 6) that the restart"
            + " deliberately leaves running for the next Engine to adopt");
  }

  @Test
  @DisplayName("runAndExit exits once even when called from two triggers")
  void exitIsGuardedSeparatelyFromTheSequence(@TempDir Path dataDir) {
    var exitCalls = new AtomicInteger();
    var sequence =
        new EngineShutdownSequence(dataDir, List.of(ok("only")), code -> exitCalls.incrementAndGet());

    sequence.runAndExit(Reason.QUIT);
    sequence.runAndExit(Reason.QUIT);
    sequence.runAndExitWithReceipt("p", "n");

    assertEquals(
        1,
        exitCalls.get(),
        "the JVM hook fires DURING the exit the endpoint requested; a second exit call would"
            + " re-enter shutdown");
  }

  @Test
  @DisplayName("a fatal request admitted before exit selection upgrades the one exit to code 1")
  void fatalRequestWinsBeforeExitSelection(@TempDir Path dataDir) throws Exception {
    var stepEntered = new java.util.concurrent.CountDownLatch(1);
    var releaseStep = new java.util.concurrent.CountDownLatch(1);
    var exitCode = new AtomicInteger(-1);
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new Step(
                    "blocked",
                    reason -> {
                      stepEntered.countDown();
                      assertTrue(releaseStep.await(2, java.util.concurrent.TimeUnit.SECONDS));
                      return null;
                    })),
            exitCode::set);
    Thread voluntary = Thread.ofVirtual().start(() -> sequence.runAndExit(Reason.QUIT));
    assertTrue(stepEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));

    sequence.runAndExitFatal(Reason.RESTART);
    releaseStep.countDown();
    voluntary.join(java.time.Duration.ofSeconds(2));

    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, exitCode.get());
    assertEquals(Reason.QUIT, sequence.resultIfRun().reason(), "the ordered close still runs once");
  }

  @Test
  @DisplayName("a fatal request cannot revise an exit code already selected")
  void selectedExitRemainsFinal(@TempDir Path dataDir) {
    var exitCode = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dataDir, List.of(ok("only")), exitCode::set);

    sequence.runAndExit(Reason.QUIT);
    sequence.runAndExitFatal(Reason.RESTART);

    assertEquals(0, exitCode.get());
  }

  @Test
  @DisplayName("an unrun sequence reports no result")
  void unrunSequenceHasNoResult(@TempDir Path dataDir) {
    var sequence = new EngineShutdownSequence(dataDir, List.of(ok("a")), code -> {});
    org.junit.jupiter.api.Assertions.assertNull(sequence.resultIfRun());
    sequence.run(Reason.QUIT);
    org.junit.jupiter.api.Assertions.assertNotNull(sequence.resultIfRun());
  }
}
