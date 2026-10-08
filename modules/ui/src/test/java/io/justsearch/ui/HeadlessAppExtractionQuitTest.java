/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.EngineExit;
import io.justsearch.app.engine.QuitBudget;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S15: a retained index owner must not leave its extraction exits unrecorded at halt. */
final class HeadlessAppExtractionQuitTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonedQuitRecordsExtractionBeforeExit(boolean ignoresEof, @TempDir Path data) throws Exception {
    var records = new CopyOnWriteArrayList<Map.Entry<LedgerRecordType, Map<String, ?>>>();
    try (var control = DefaultChildControl.of((type, fields) -> {
      records.add(Map.entry(type, fields));
      return true;
    }, ProcessTerminator.forCurrentOs(), ManagedChildRegistry.noop())) {
      Path args = data.resolve("child.args");
      Files.writeString(args, "-cp\n\"" + System.getProperty("java.class.path").replace("\\", "/")
          + "\"\n" + Probe.class.getName() + "\n" + ignoresEof + "\n", StandardCharsets.UTF_8);
      var spec = new ChildSpec(ChildKind.EXTRACTION,
          List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
              "@" + args),
          null, Map.of(), new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
          new ChildSpec.CooperativeStop(ChildSpec.CooperativeStop.Action.CLOSE_STDIN,
              Duration.ofSeconds(2), Set.of(0)), "quit-extraction");
      ChildHandle child = control.start(spec); // retains the PID and OS creation identity at spawn
      assertEquals('R', child.stdout().read(), "the extraction child reached its active request");
      var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
      List<EngineShutdownSequence.Step> steps = HeadlessApp.orderedShutdownSteps(null, null, null,
          null, null, null, null, null, OperationLeaseService.noOp(), null,
          mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
          mock(io.justsearch.app.api.operations.OperationStore.class), attempts,
          wait -> { throw new IllegalStateException("non-yielding ingest unit"); });
      // The same test compiles against the before-state. There it runs the existing binding,
      // then fails on the missing extraction exit, rather than on a missing API/compile error.
      try {
        var binding = HeadlessApp.class.getDeclaredMethod("withTerminalExtractionStop", List.class,
            DefaultChildControl.class);
        binding.setAccessible(true);
        @SuppressWarnings("unchecked")
        var bound = (List<EngineShutdownSequence.Step>) binding.invoke(null, steps, control);
        steps = bound;
      } catch (NoSuchMethodException beforeRepair) { /* exercise the original binding */ }
      AtomicInteger exit = new AtomicInteger(-1);
      long started = System.nanoTime();
      new EngineShutdownSequence(data, steps, code -> {
        assertTrue(records.stream().anyMatch(row -> row.getKey() == LedgerRecordType.CHILD_EXIT),
            "an active ingest extraction child exits without its owner record before halt");
        exit.set(code);
      }).runAndExit(Reason.QUIT, QuitBudget.fromNow());
      assertEquals(EngineExit.FATAL_OR_UNCAUGHT, exit.get());
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 7_700);
      var outcome = child.exit().toCompletableFuture().get(1, TimeUnit.SECONDS);
      assertEquals(ExitClass.REQUESTED, outcome.exitClass());
      assertEquals(Optional.of(StopReason.QUIT), outcome.reason());
      assertEquals(1, records.stream().filter(row -> row.getKey() == LedgerRecordType.STOP_INTENT).count());
      assertEquals(1, records.stream().filter(row -> row.getKey() == LedgerRecordType.CHILD_EXIT).count());
      assertThrows(IllegalStateException.class, () -> control.start(spec), "quit fences new extraction");
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      System.out.write('R');
      System.out.flush();
      if (Boolean.parseBoolean(args[0])) {
        while (true) Thread.sleep(1_000);
      }
      while (System.in.read() != -1) { }
    }
  }
}
