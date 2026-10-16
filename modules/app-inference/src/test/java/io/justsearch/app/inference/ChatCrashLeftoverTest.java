/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A crashed llama-server leaves a process in its own job (a crash reporter, a child it started).
 * The crash-recovery restart (T28) ends that process through the job and records it as an
 * intended cleanup, rather than waiting the 12 s stop bound for it and then refusing the restart
 * beside it. Windows only: elsewhere nothing can be ended and the restart keeps refusing.
 */
@EnabledOnOs(OS.WINDOWS)
final class ChatCrashLeftoverTest {
  /** Well under the 12 s a leftover process used to be awaited for. */
  private static final Duration RELAUNCH_BOUND = Duration.ofSeconds(8);

  @TempDir Path tmp;
  private Path fakeDir;
  private DefaultChildControl control;
  private InferenceLifecycleManager manager;
  private final AtomicLong clock = new AtomicLong(System.nanoTime());
  private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

  @AfterEach
  void tearDown() throws Exception {
    try {
      try {
        if (manager != null) manager.close();
      } finally {
        if (control != null) control.close();
      }
    } finally {
      try {
        HealthyFakeLlamaServer.releaseLingerers(fakeDir);
      } finally {
        HealthyFakeLlamaServer.endAll(fakeDir);
      }
    }
  }

  @Test
  void aCrashRestartEndsWhatTheCrashedServerLeftInItsJob() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    LedgerSink sink = (type, fields) -> {
      if (type == LedgerRecordType.CHILD_NOTE) notes.add(String.valueOf(fields.get("token")));
      return true;
    };
    control = DefaultChildControl.of(sink, ProcessTerminator.forCurrentOs(),
        ManagedChildRegistry.noop());
    Path executable = HealthyFakeLlamaServer.install(fakeDir, "--spawn-lingerer");
    Path model = Files.createFile(tmp.resolve("model.gguf"));
    ResolvedConfig captured = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(),
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false),
        NoopInferenceTelemetryEvents.INSTANCE, control, captured, ignored -> () -> {});
    useTestClock(manager);
    manager.switchToOnlineMode();
    assertTrue(manager.isOnline(), "the manager starts online");
    assertTrue(await(() -> lingerers() == 1, Duration.ofSeconds(10)), "the server left a lingerer");
    ProcessHandle lingerer = HealthyFakeLlamaServer.lingerers(fakeDir).getFirst();
    String identity = lingerer.pid() + "@" + lingerer.info().startInstant().orElseThrow();
    advance(Duration.ofMinutes(11));

    long crashed = System.nanoTime();
    HealthyFakeLlamaServer.crashLatest(fakeDir);

    boolean relaunched = await(() -> launches() == 2 && manager.isOnline(), RELAUNCH_BOUND);
    long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - crashed);
    System.out.println("crash to relaunch online: " + tookMs + " ms; notes=" + notes);
    assertTrue(relaunched, "the crash-recovery restart is not held by what the crash left;"
        + " launches=" + launches() + " online=" + manager.isOnline() + " after " + tookMs + " ms");
    assertFalse(lingerer.isAlive(), "what the crashed server left in its job is ended");
    List<String> ended = notes.stream()
        .filter(note -> note.startsWith("intended_cleanup:") && note.endsWith(":job_member:" + identity))
        .toList();
    assertEquals(1, ended.size(), "the ended member is recorded once as an intended cleanup: " + notes);
  }

  private void useTestClock(InferenceLifecycleManager target) throws Exception {
    Field field = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    field.setAccessible(true);
    Object ops = field.get(target);
    Field monotonic = ops.getClass().getDeclaredField("monotonicNanos");
    monotonic.setAccessible(true);
    monotonic.set(ops, (LongSupplier) clock::get);
  }

  private void advance(Duration by) {
    clock.addAndGet(by.toNanos());
  }

  private int launches() {
    try {
      return HealthyFakeLlamaServer.launches(fakeDir).size();
    } catch (java.io.IOException unreadable) {
      return -1;
    }
  }

  private int lingerers() {
    try {
      return HealthyFakeLlamaServer.lingerers(fakeDir).size();
    } catch (java.io.IOException unreadable) {
      return -1;
    }
  }

  private static boolean await(BooleanSupplier condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    return condition.getAsBoolean();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
