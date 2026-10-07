/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static io.justsearch.app.api.runtime.child.ChildControlHarness.await;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.closeStdin;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.gpuSpec;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Spec B §8 T1-T9, T13, T14 and the manifest adapter, on real fake-child processes. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class DefaultChildControlTest {
  private static final long GIB = 1024L * 1024 * 1024;

  @TempDir Path dataDir;
  private ChildControlHarness h;

  @BeforeEach
  void harness() {
    h = new ChildControlHarness(dataDir);
  }

  @AfterEach
  void conforms() throws IOException {
    h.close();
    h.assertLedgerConforms();
  }

  @Test
  @DisplayName("T1: stop(NOW) is REQUESTED with our code, one intent before the terminate, one exit")
  void stopNowIsRequestedWithOurCode() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    assertEquals(ChildHandle.State.RUNNING, child.state());

    ExitOutcome outcome = await(h.control().stop(child,
        StopRequest.of(StopReason.TIMEOUT, StopMode.NOW, "extraction-pool")
            .withDetail(StopDetail.REQUEST)));

    assertEquals(ExitClass.REQUESTED, outcome.exitClass());
    assertEquals(Optional.of(StopReason.TIMEOUT), outcome.reason());
    assertEquals(Optional.of(StopDetail.REQUEST), outcome.detail());
    assertEquals(OptionalInt.of(h.terminator.ourCode()), outcome.code());
    assertTrue(outcome.forced());
    assertEquals(TerminateResult.OK, outcome.terminateResult());
    assertEquals(ExitOutcome.Phase.STOPPING, outcome.phase());
    assertTrue(outcome.ledgerDurable());
    assertEquals(ChildHandle.State.EXITED, child.state());
    assertEquals(outcome, await(child.exit()));

    assertEquals(List.of("child-spawn", "child-start", "stop-intent", "child-exit"),
        h.types(child.childId()));
    LedgerRecord intent = h.records(LedgerRecordType.STOP_INTENT).getFirst();
    LedgerRecord exit = h.records(LedgerRecordType.CHILD_EXIT).getFirst();
    assertEquals(intent.text("intentId"), exit.text("intentId"));
    assertEquals(Optional.of("extraction-pool"), intent.text("requestedBy"));
    assertEquals(Optional.of("request"), exit.text("detail"));
    assertEquals(h.terminator.ourCode(), exit.field("code").asInt());
    assertEquals(h.terminator.codeDistinctive(), exit.field("codeDistinctive").asBoolean());
    if (h.terminator.codeDistinctive()) {
      assertEquals(TerminateCodes.JS_CHILD_STOP, exit.field("code").asInt());
      assertEquals(Optional.of("JS_CHILD_STOP"), exit.text("codeName"));
      assertTrue(exit.text("exitedAt").isPresent(), "the OS exit time is read on Windows");
    }
    assertTrue(h.control().list().isEmpty());
    assertEquals(List.of(outcome), h.control().recentExits(32));
  }

  @Test
  @DisplayName("T2: a child that exits by itself between the intent and the terminate is UNEXPECTED")
  void exitBeforeTerminateIsUnexpected() throws Exception {
    Path signal = dataDir.resolve("exit-now");
    ChildHandle child = h.control().start(spec("exit-on-signal-file:" + signal + ":7"));
    // The latch: the child exits with 7 after the intent is durable, before our terminate runs.
    h.terminator.beforeTerminate = process -> {
      Files.createFile(signal);
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    };

    ExitOutcome outcome =
        await(h.control().stop(child, StopRequest.of(StopReason.RECYCLE, StopMode.NOW)));

    assertEquals(ExitClass.UNEXPECTED, outcome.exitClass());
    assertEquals(OptionalInt.of(7), outcome.code());
    assertEquals(TerminateResult.ALREADY_EXITED, outcome.terminateResult());
    assertFalse(outcome.forced());
    assertEquals(Optional.of(StopReason.RECYCLE), outcome.reason(), "the intent is still named");
    assertEquals(List.of("child-spawn", "child-start", "stop-intent", "child-exit"),
        h.types(child.childId()));
    assertEquals(Optional.of("ALREADY_EXITED"),
        h.records(LedgerRecordType.CHILD_EXIT).getFirst().text("terminateResult"));
  }

  @Test
  @DisplayName("T3: stopping an exited child returns its recorded UNEXPECTED exit and writes nothing")
  void stopAfterExitWritesNothing() throws Exception {
    ChildHandle child = h.control().start(spec("exit:5:0"));
    ExitOutcome exited = await(child.exit());

    ExitOutcome stopped =
        await(h.control().stop(child, StopRequest.of(StopReason.QUIT, StopMode.NOW)));

    assertEquals(exited, stopped);
    assertEquals(ExitClass.UNEXPECTED, stopped.exitClass());
    assertEquals(OptionalInt.of(5), stopped.code());
    assertEquals(Optional.empty(), stopped.reason());
    assertEquals(TerminateResult.NOT_CALLED, stopped.terminateResult());
    assertEquals(List.of("child-spawn", "child-start", "child-exit"), h.types(child.childId()));
    assertFalse(h.events.contains("terminate"));
  }

  @Test
  @DisplayName("T4: eight concurrent stops write one intent and share one outcome; the first wins")
  void concurrentStopsShareOneIntent() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    StopReason[] reasons = {StopReason.TIMEOUT, StopReason.RECYCLE, StopReason.CANCELLED,
        StopReason.QUIT, StopReason.ROLLBACK, StopReason.RECONFIGURE, StopReason.DEACTIVATE,
        StopReason.IDLE_UNLOAD};
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<ExitOutcome>> stops = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (StopReason reason : reasons) {
      CompletableFuture<ExitOutcome> result = new CompletableFuture<>();
      stops.add(result);
      Thread thread = Thread.ofPlatform().start(() -> {
        try {
          go.await();
          h.control().stop(child, StopRequest.of(reason, StopMode.NOW))
              .whenComplete((o, e) -> {
                if (e != null) result.completeExceptionally(e);
                else result.complete(o);
              });
        } catch (InterruptedException e) {
          result.completeExceptionally(e);
        }
      });
      threads.add(thread);
    }
    go.countDown();
    for (Thread thread : threads) thread.join();

    List<ExitOutcome> outcomes = new ArrayList<>();
    for (CompletableFuture<ExitOutcome> stop : stops) outcomes.add(stop.get(60, TimeUnit.SECONDS));
    assertEquals(1, Set.copyOf(outcomes).size(), "one outcome for every caller");
    List<LedgerRecord> intents = h.records(LedgerRecordType.STOP_INTENT);
    assertEquals(1, intents.size());
    assertEquals(1, h.records(LedgerRecordType.CHILD_EXIT).size());
    assertEquals(intents.getFirst().text("reason").orElseThrow(),
        outcomes.getFirst().reason().orElseThrow().name(), "the recorded first reason wins");
  }

  @Test
  @DisplayName("T4: NOW escalates a pending COOPERATIVE stop at once, with stop-escalate")
  void nowEscalatesCooperativeStop() throws Exception {
    ChildHandle child =
        h.control().start(spec("ignore-eof", closeStdin(Duration.ofSeconds(60), 0)));
    long started = System.nanoTime();
    CompletableFuture<ExitOutcome> cooperative = h.control()
        .stop(child, StopRequest.of(StopReason.QUIT, StopMode.COOPERATIVE)).toCompletableFuture();
    CompletableFuture<ExitOutcome> now = h.control()
        .stop(child, StopRequest.of(StopReason.TIMEOUT, StopMode.NOW)).toCompletableFuture();

    ExitOutcome outcome = now.get(30, TimeUnit.SECONDS);
    assertEquals(outcome, cooperative.get(1, TimeUnit.SECONDS));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(30)) < 0,
        "the 60 s grace was cut");
    assertEquals(ExitClass.REQUESTED, outcome.exitClass());
    assertEquals(Optional.of(StopReason.QUIT), outcome.reason(), "the first reason wins");
    assertTrue(outcome.forced());
    assertEquals(List.of("child-spawn", "child-start", "stop-intent", "stop-escalate", "child-exit"),
        h.types(child.childId()));
  }

  @Test
  @DisplayName("T5: COOPERATIVE stops: eof:0 is REQUESTED unforced, ignore-eof forced, eof:3 UNEXPECTED")
  void cooperativeStops() throws Exception {
    ChildHandle clean = h.control().start(spec("eof:0", closeStdin(Duration.ofSeconds(20), 0)));
    ChildHandle stubborn =
        h.control().start(spec("ignore-eof", closeStdin(Duration.ofSeconds(1), 0)));
    ChildHandle failing = h.control().start(spec("eof:3", closeStdin(Duration.ofSeconds(20), 0)));
    StopRequest quit = StopRequest.of(StopReason.QUIT, StopMode.COOPERATIVE);

    ExitOutcome cleanExit = await(h.control().stop(clean, quit));
    ExitOutcome stubbornExit = await(h.control().stop(stubborn, quit));
    ExitOutcome failingExit = await(h.control().stop(failing, quit));

    assertEquals(ExitClass.REQUESTED, cleanExit.exitClass());
    assertEquals(OptionalInt.of(0), cleanExit.code());
    assertFalse(cleanExit.forced());
    assertEquals(TerminateResult.NOT_CALLED, cleanExit.terminateResult());
    assertEquals(Optional.of(StopMode.COOPERATIVE), cleanExit.mode());
    assertTrue(h.records(LedgerRecordType.CHILD_EXIT, clean.childId()).getFirst()
        .text("cooperativeAt").isPresent());

    assertEquals(ExitClass.REQUESTED, stubbornExit.exitClass());
    assertTrue(stubbornExit.forced());
    assertEquals(OptionalInt.of(h.terminator.ourCode()), stubbornExit.code());

    assertEquals(ExitClass.UNEXPECTED, failingExit.exitClass());
    assertEquals(OptionalInt.of(3), failingExit.code());
    assertEquals(Optional.of(StopReason.QUIT), failingExit.reason());
  }

  @Test
  @DisplayName("T6: stopAll of three children shares one 2 s grace (under 3 s in total)")
  void stopAllSharesOneGrace() throws Exception {
    List<ChildHandle> children = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      children.add(h.control().start(spec("ignore-eof", closeStdin(Duration.ofSeconds(2), 0))));
    }
    long started = System.nanoTime();
    List<ExitOutcome> outcomes = await(h.control().stopAll(children,
        StopRequest.of(StopReason.QUIT, StopMode.COOPERATIVE, "extraction-pool")));
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertTrue(took.compareTo(Duration.ofSeconds(3)) < 0, "took " + took);
    assertTrue(took.compareTo(Duration.ofMillis(1900)) >= 0, "the grace was waited: " + took);
    assertEquals(3, outcomes.size());
    for (ExitOutcome outcome : outcomes) {
      assertEquals(ExitClass.REQUESTED, outcome.exitClass());
      assertTrue(outcome.forced());
    }
  }

  @Test
  @DisplayName("T7: a terminator that never kills gives SURVIVED after 10 s; the retry succeeds")
  void survivorIsRetriedUnderTheSameIntent() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    h.terminator.neverKill = true;

    ExecutionException survived = assertThrows(ExecutionException.class, () -> await(
        h.control().stop(child, StopRequest.of(StopReason.RECONFIGURE, StopMode.NOW))));
    assertInstanceOf(ChildSurvivedException.class, survived.getCause());
    assertEquals(ChildHandle.State.SURVIVED, child.state());
    assertEquals(10_000, h.terminator.virtualWaitMs.get(), "5 s, one retry, 5 s");
    LedgerRecord record = h.records(LedgerRecordType.CHILD_SURVIVED).getFirst();
    assertEquals(2, record.field("attempts").asInt());
    assertEquals(Optional.of("terminate_timeout"), record.text("basis"));
    assertEquals(1, h.control().list().size(), "the handle stays owned");

    h.terminator.neverKill = false;
    ExitOutcome outcome =
        await(h.control().stop(child, StopRequest.of(StopReason.QUIT, StopMode.NOW)));

    assertEquals(ExitClass.REQUESTED, outcome.exitClass());
    assertEquals(Optional.of(StopReason.RECONFIGURE), outcome.reason(), "the original intent");
    assertTrue(outcome.forced());
    assertEquals(List.of("child-spawn", "child-start", "stop-intent", "child-survived",
        "child-exit"), h.types(child.childId()));
  }

  @Test
  @DisplayName("T8: the stop-intent is on disk before terminate() is called")
  void intentIsDurableBeforeTerminate() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    List<String> onDiskAtTerminate = new ArrayList<>();
    h.terminator.beforeTerminate = process -> onDiskAtTerminate.addAll(
        Files.readAllLines(LedgerFamily.CHILDREN.current(h.ledgerDir)));

    await(h.control().stop(child, StopRequest.of(StopReason.CANCELLED, StopMode.NOW)));

    assertTrue(h.events.indexOf("append:stop-intent") < h.events.indexOf("terminate"), h.events.toString());
    assertTrue(onDiskAtTerminate.stream().anyMatch(line -> line.contains("\"type\":\"stop-intent\"")),
        "the intent line was durable when terminate ran");
  }

  @Test
  @DisplayName("T9: a throwing ledger never blocks the stop; the outcome says the record was lost")
  void throwingLedgerStillStops() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    h.appendHook = (type, fields, next) -> {
      if (type == LedgerRecordType.STOP_INTENT) throw new IllegalStateException("disk gone");
      return next.append(type, fields);
    };

    ExitOutcome outcome =
        await(h.control().stop(child, StopRequest.of(StopReason.QUIT, StopMode.NOW)));

    assertEquals(ExitClass.REQUESTED, outcome.exitClass(), "the API knows it called terminate");
    assertFalse(outcome.ledgerDurable());
    assertFalse(child.processStartedAt().toString().isEmpty());
    assertTrue(ProcessHandle.of(child.pid()).map(p -> !p.isAlive()).orElse(true));
  }

  @Test
  @DisplayName("T9: a failed intent write is a ledger-gap on the next write; the stop proceeds")
  void failedIntentWriteLeavesAGap() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"));
    Path file = LedgerFamily.CHILDREN.current(h.ledgerDir);
    Path aside = h.ledgerDir.resolve("aside.jsonl");
    h.appendHook = (type, fields, next) -> {
      if (type != LedgerRecordType.STOP_INTENT) return next.append(type, fields);
      try {
        Files.move(file, aside);
        Files.createDirectory(file); // the append cannot open a directory: a real I/O failure
        boolean durable = next.append(type, fields);
        Files.delete(file);
        Files.move(aside, file, StandardCopyOption.ATOMIC_MOVE);
        return durable;
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    };

    ExitOutcome outcome =
        await(h.control().stop(child, StopRequest.of(StopReason.QUIT, StopMode.NOW)));

    assertFalse(outcome.ledgerDurable());
    assertEquals(ExitClass.REQUESTED, outcome.exitClass());
    List<String> types = h.records().stream().map(r -> r.type().token()).toList();
    assertEquals(List.of("child-spawn", "child-start", "ledger-gap", "child-exit"), types);
    assertEquals(Optional.of("WRITE_FAILED"),
        h.records(LedgerRecordType.LEDGER_GAP).getFirst().text("reason"));
  }

  @Test
  @DisplayName("T13: the GPU poll recovers at sample 3 (75% of the expected release)")
  void gpuPollRecovers() throws Exception {
    h.virtualTime();
    h.vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML,
        8 * GIB, 8 * GIB - GIB / 10, 8 * GIB - GIB / 2, 7 * GIB);
    ChildHandle child = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));

    await(h.control().stop(child, StopRequest.of(StopReason.RECONFIGURE, StopMode.NOW)));
    assertTrue(h.control().awaitGpuRelease(Duration.ofSeconds(30)));

    LedgerRecord release = h.records(LedgerRecordType.GPU_RELEASE).getFirst();
    assertEquals(Optional.of("recovered"), release.text("result"));
    assertEquals(750, release.field("waitedMs").asLong());
    assertEquals(8 * GIB, release.field("usedBeforeBytes").asLong());
    assertEquals(7 * GIB, release.field("usedAfterBytes").asLong());
    assertEquals(GIB, release.field("expectedBytes").asLong());
    assertEquals(Optional.of("NVML"), release.text("probe"));
  }

  @Test
  @DisplayName("T13: without an expected size, two stable samples after a drop are a recovery")
  void gpuPollRecoversWhenStableAfterDrop() throws Exception {
    h.virtualTime();
    h.vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.PDH,
        8 * GIB, 8 * GIB, 6 * GIB, 6 * GIB + 10L * 1024 * 1024);
    ChildHandle child = h.control().start(gpuSpec("sleep", OptionalLong.empty()));

    await(h.control().stop(child, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW)));
    assertTrue(h.control().awaitGpuRelease(Duration.ofSeconds(30)));

    LedgerRecord release = h.records(LedgerRecordType.GPU_RELEASE).getFirst();
    assertEquals(Optional.of("recovered"), release.text("result"));
    assertEquals(750, release.field("waitedMs").asLong());
    assertTrue(release.field("expectedBytes").isNull());
  }

  @Test
  @DisplayName("T13: no release within 5 s is TIMEOUT, and fails nothing")
  void gpuPollTimesOut() throws Exception {
    h.virtualTime();
    h.vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB);
    ChildHandle child = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));

    ExitOutcome outcome =
        await(h.control().stop(child, StopRequest.of(StopReason.RECONFIGURE, StopMode.NOW)));
    assertTrue(h.control().awaitGpuRelease(Duration.ofSeconds(30)));

    assertEquals(ExitClass.REQUESTED, outcome.exitClass());
    LedgerRecord release = h.records(LedgerRecordType.GPU_RELEASE).getFirst();
    assertEquals(Optional.of("timeout"), release.text("result"));
    assertEquals(5_000, release.field("waitedMs").asLong());
  }

  @Test
  @DisplayName("T13: with no probe the release is UNMEASURED")
  void gpuPollWithoutProbeIsUnmeasured() throws Exception {
    h.virtualTime();
    ChildHandle child = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));

    await(h.control().stop(child, StopRequest.of(StopReason.VDU_ENTER, StopMode.NOW)));
    assertTrue(h.control().awaitGpuRelease(Duration.ofSeconds(30)));

    LedgerRecord release = h.records(LedgerRecordType.GPU_RELEASE).getFirst();
    assertEquals(Optional.of("unmeasured"), release.text("result"));
    assertEquals(Optional.of("NONE"), release.text("probe"));
    assertTrue(release.field("usedBeforeBytes").isNull());
  }

  @Test
  @DisplayName("T13: QUIT, UPGRADE and ENGINE_RESTART skip the GPU poll")
  void engineEndingStopsSkipThePoll() throws Exception {
    h.virtualTime();
    ChildControlHarness.ScriptedVram vram =
        new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB);
    h.vram = vram;
    for (StopReason reason :
        List.of(StopReason.QUIT, StopReason.UPGRADE, StopReason.ENGINE_RESTART)) {
      ChildHandle child = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
      await(h.control().stop(child, StopRequest.of(reason, StopMode.NOW)));
    }
    assertTrue(h.control().awaitGpuRelease(Duration.ZERO));
    assertEquals(0, vram.reads.get());
    assertTrue(h.records(LedgerRecordType.GPU_RELEASE).isEmpty());
  }

  @Test
  @DisplayName("T14: start(gpu) and awaitGpuRelease wait for a pending poll")
  void gpuStartWaitsForPendingPoll() throws Exception {
    h.virtualTime();
    CountDownLatch release = new CountDownLatch(1);
    ChildControlHarness.ScriptedVram vram = new ChildControlHarness.ScriptedVram(
        GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
    vram.onRead = n -> {
      if (n == 2) release.await(); // the poll's first sample blocks until released
    };
    h.vram = vram;
    ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
    await(h.control().stop(first, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW)));
    while (vram.reads.get() < 2) Thread.sleep(5);

    assertFalse(h.control().awaitGpuRelease(Duration.ofMillis(200)), "the poll is pending");
    CompletableFuture<ChildHandle> second = CompletableFuture.supplyAsync(() -> {
      try {
        return h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(500);
    assertFalse(second.isDone(), "start(gpu) waits on the barrier");
    assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size());

    release.countDown();
    ChildHandle next = second.get(60, TimeUnit.SECONDS);
    List<String> types = h.records().stream().map(r -> r.type().token()).toList();
    assertTrue(types.indexOf("gpu-release") < types.lastIndexOf("child-spawn"), types.toString());
    await(h.control().stop(next, StopRequest.of(StopReason.QUIT, StopMode.NOW)));
  }

  @Test
  @DisplayName("Manifest adapter: a child is registered while it runs and removed at its exit")
  void manifestRegistrationFollowsTheChild() throws Exception {
    ChildHandle child = h.control().start(spec("sleep"),
        new ChildControl.ManifestEntry(null, null, "d".repeat(64)));

    ManagedChild entry = h.registry.registered.getFirst();
    assertEquals(ManagedChild.Kind.EXTRACTION, entry.kind());
    assertEquals(child.pid(), entry.pid());
    assertEquals("stdio", entry.endpoint());
    assertEquals("d".repeat(64), entry.declaredConfigHash());
    assertEquals(DefaultChildControl.argvHash(spec("sleep").argv()), entry.realizedArgvHash());

    await(h.control().stop(child, StopRequest.of(StopReason.QUIT, StopMode.NOW)));
    assertEquals(List.of(entry.id()), h.registry.removed);
  }

  /**
   * Manifest adapter: a child that exits while it is registered leaves no entry behind. (No
   * display name: the evidence runner selects this case by its method name.)
   */
  @Test
  void exitWhileRegisteringRemovesTheEntry() throws Exception {
    // The child dies inside register, and register returns only once the exit watcher has
    // recorded that exit: the exit is observed before the control knows the entry's id.
    h.registry.duringRegister = entry -> {
      ProcessHandle.of(entry.pid()).ifPresent(ProcessHandle::destroyForcibly);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (h.control().recentExits(1).isEmpty()) {
        if (System.nanoTime() - deadline > 0) throw new IOException("the exit was not recorded");
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
      }
    };

    ChildHandle child = h.control().start(spec("sleep"),
        new ChildControl.ManifestEntry(null, null, "d".repeat(64)));

    ManagedChild entry = h.registry.registered.getFirst();
    assertEquals(ChildHandle.State.EXITED, child.state());
    assertEquals(ExitClass.UNEXPECTED, await(child.exit()).exitClass());
    assertTrue(h.registry.snapshot().isEmpty(),
        "the manifest keeps no entry for a child that is gone: " + h.registry.snapshot());
    assertEquals(List.of(entry.id()), h.registry.removed);
  }

  @Test
  @DisplayName("Manifest adapter: a refused registration stops the child (ROLLBACK) and throws")
  void refusedRegistrationRollsBack() throws Exception {
    h.registry.refuse = true;

    assertThrows(IOException.class, () -> h.control().start(spec("sleep")));

    LedgerRecord exit = h.records(LedgerRecordType.CHILD_EXIT).getFirst();
    assertEquals(Optional.of("REQUESTED"), exit.text("exitClass"));
    assertEquals(Optional.of("ROLLBACK"), exit.text("reason"));
    long pid = h.records(LedgerRecordType.CHILD_START).getFirst().field("pid").asLong();
    assertTrue(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    assertTrue(h.control().list().isEmpty());
  }

  /**
   * S19: a chat server that survived its registration rollback blocks every chat-server start until
   * a retry ends it.
   */
  @Test
  void aChatServerRollbackSurvivorBlocksEveryChatServerStart() throws Exception {
    h.registry.refuse = true;
    h.terminator.neverKill = true;
    assertThrows(IOException.class, () -> h.control().start(chat(ChildKind.LLAMA_SERVER)));
    assertEquals(1, h.control().list().size(), "the rollback survivor stays held");
    h.registry.refuse = false;

    assertThrows(IOException.class, () -> h.control().start(chat(ChildKind.LLAMA_SERVER)),
        "no main server starts beside the survivor");
    assertThrows(IOException.class, () -> h.control().start(chat(ChildKind.LLAMA_SELF_TEST)),
        "no self-test starts beside the survivor");
    assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size(), "nothing was spawned");
    ChildHandle extraction = h.control().start(spec("sleep"));
    assertEquals(ChildHandle.State.RUNNING, extraction.state(), "other roles are not blocked");

    h.terminator.neverKill = false;
    ChildHandle next = h.control().start(chat(ChildKind.LLAMA_SERVER));
    assertEquals(ChildHandle.State.RUNNING, next.state(), "the start proceeds once a retry ended it");
    List<LedgerRecord> exits = h.records(LedgerRecordType.CHILD_EXIT);
    assertEquals(1, exits.size());
    assertEquals(Optional.of("ROLLBACK"), exits.getFirst().text("reason"), "its first intent");
  }

  /** S19: a self-test whose stop failed blocks the main chat server until it is gone. */
  @Test
  void aSelfTestThatSurvivedItsStopBlocksTheMainServer() throws Exception {
    ChildHandle selfTest = h.control().start(chat(ChildKind.LLAMA_SELF_TEST));
    h.terminator.neverKill = true;
    assertThrows(ExecutionException.class, () -> await(h.control().stop(selfTest,
        StopRequest.of(StopReason.SELF_TEST, StopMode.NOW, "self-test"))));
    assertEquals(ChildHandle.State.SURVIVED, selfTest.state());

    IOException refused =
        assertThrows(IOException.class, () -> h.control().start(chat(ChildKind.LLAMA_SERVER)));
    assertTrue(refused.getMessage().contains("pid " + selfTest.pid()), refused.getMessage());
    assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size(), "nothing was spawned");

    h.terminator.neverKill = false;
    h.control().start(chat(ChildKind.LLAMA_SERVER));
    assertEquals(ChildHandle.State.EXITED, selfTest.state());
    assertEquals(Optional.of(StopReason.SELF_TEST),
        await(selfTest.exit()).reason(), "the self-test keeps its first intent");
  }

  /** S19: a chat-server start waits for a chat server's stop that is still running. */
  @Test
  @Timeout(30)
  void aChatServerStartWaitsForAStopStillRunning() throws Exception {
    ChildHandle first = h.control().start(chat(ChildKind.LLAMA_SERVER));
    CountDownLatch terminating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    h.terminator.beforeTerminate = process -> {
      terminating.countDown();
      release.await();
    };
    var stop = h.control().stop(first, StopRequest.of(StopReason.RECONFIGURE, StopMode.NOW));
    assertTrue(terminating.await(10, TimeUnit.SECONDS));
    var next = CompletableFuture.supplyAsync(() -> {
      try {
        return h.control().start(chat(ChildKind.LLAMA_SERVER));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(300);
    assertFalse(next.isDone(), "the start waits for the running stop");
    assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size());

    release.countDown();
    await(stop);
    assertEquals(ChildHandle.State.RUNNING, next.get(30, TimeUnit.SECONDS).state());
    List<String> order = h.records().stream()
        .filter(r -> r.type() == LedgerRecordType.CHILD_EXIT
            || r.type() == LedgerRecordType.CHILD_SPAWN)
        .map(r -> r.type().token()).toList();
    assertEquals(List.of("child-spawn", "child-exit", "child-spawn"), order,
        "the second server is spawned after the first one's exit");
  }

  private static ChildSpec chat(ChildKind kind) {
    return new ChildSpec(kind, FakeChild.argv("sleep"), null, java.util.Map.of(),
        new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
        ChildSpec.CooperativeStop.NONE, "fake-" + kind.name().toLowerCase(java.util.Locale.ROOT));
  }
}
