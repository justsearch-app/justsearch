/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.5, the accepted run-thread deviation: a detached native run's thread is a named daemon
 * platform thread, and a run that never returns cannot keep the process alive after the Engine
 * shuts down. A run that never returns keeps its thread, its seat and its GPU memory until the
 * process exits; that is the named limitation (ADR-0050), and this pins its bound.
 *
 * <p>Real seam: a real {@link NativeSessionHandle} whose native session is a mock that never
 * returns, the lease's production {@link SessionHandle.Lease#run}, and, for the lifetime, a child
 * JVM whose main thread leaves such a run detached and returns.
 */
final class DetachedRunThreadLifetimeTest {

  private static final String MARKER = "detached-run-thread";

  @Test
  void aDetachedRunThreadIsANamedDaemonPlatformThread() throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    NativeSessionHandle handle = gpuHandle(neverReturns(never));
    try {
      Thread runThread = detachOneRun(handle);

      assertTrue(runThread.isAlive(), "the run is still in flight");
      assertTrue(runThread.isDaemon(), "a detached run must not keep the process alive");
      assertFalse(runThread.isVirtual(), "a native call must not pin a virtual carrier");
      assertTrue(runThread.getName().matches("ort-query-run-\\d+"), runThread::getName);
    } finally {
      // Let the run return so its thread releases the seat and the handle can retire.
      never.countDown();
      handle.close();
    }
  }

  @Test
  void aRunThatNeverReturnsDoesNotKeepTheProcessAlive(@TempDir Path tempDir) throws Exception {
    Path argFile = tempDir.resolve("probe.args");
    List<String> lines = new ArrayList<>();
    for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      // Keep the agents the test JVM runs with (the mock maker); nothing else.
      if (jvmArg.startsWith("-javaagent:")) lines.add(quoted(jvmArg));
    }
    lines.add("-cp");
    lines.add(quoted(System.getProperty("java.class.path")));
    lines.add(Probe.class.getName());
    Files.write(argFile, lines, StandardCharsets.UTF_8);
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path output = tempDir.resolve("probe.out");

    Process probe =
        new ProcessBuilder(java.toString(), "@" + argFile)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    // Inside the suite's 30 s test timeout, so a process kept alive is an assertion failure.
    boolean exited = probe.waitFor(20, TimeUnit.SECONDS);
    if (!exited) probe.destroyForcibly();
    String printed = Files.readString(output, StandardCharsets.UTF_8);

    assertTrue(exited, () -> "the process stayed alive with a detached run: " + printed);
    assertEquals(0, probe.exitValue(), printed);
    assertTrue(
        printed.contains(MARKER + " alive=true daemon=true"),
        () -> "the probe left a live detached run when main returned: " + printed);
  }

  /**
   * The child JVM: leaves one native run detached, still running and never to return, and lets
   * main return without closing anything, as an Engine shutdown with a stuck run would.
   */
  static final class Probe {
    public static void main(String[] args) throws Exception {
      NativeSessionHandle handle = gpuHandle(neverReturns(new CountDownLatch(1)));
      Thread runThread = detachOneRun(handle);
      System.out.println(
          MARKER + " alive=" + runThread.isAlive() + " daemon=" + runThread.isDaemon()
              + " name=" + runThread.getName());
      System.out.flush();
    }
  }

  private static Thread detachOneRun(NativeSessionHandle handle) throws Exception {
    SessionHandle.Lease lease =
        handle.acquire(
            new SessionAcquisitionRequest(
                SessionAcquisitionRequest.Urgency.FOREGROUND,
                System.nanoTime() + Duration.ofMillis(200).toNanos(),
                () -> false,
                /* mayCreateGpuSession= */ false,
                /* releaseCallerAtDeadline= */ true));
    var missed =
        assertThrows(SessionAcquireDeadlineExceededException.class, () -> lease.run(Map.of()));
    assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, missed.phase());
    lease.close();
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith("ort-query-run-"))
        .filter(Thread::isAlive)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no detached run thread"));
  }

  private static OrtSession neverReturns(CountDownLatch never) throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap()))
        .thenAnswer(
            invocation -> {
              never.await();
              return mock(OrtSession.Result.class);
            });
    return session;
  }

  private static NativeSessionHandle gpuHandle(OrtSession gpuSession) throws Exception {
    Path model = Path.of("missing", "model.onnx");
    NativeSessionHandle handle =
        NativeSessionHandle.builder("detached-run-lifetime-test", model)
            .gpuModelPath(model)
            .shouldUseGpu(() -> true)
            .runtime(RuntimePolicy.defaults())
            .policy(
                ModelSessionPolicy.forFallback(
                    new GpuSessionConfig(0, 512L * 1024 * 1024),
                    null,
                    /* deferCpuSession= */ true,
                    /* gpuRetryEnabled= */ false,
                    NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS))
            .build();
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }

  /** One argument-file token: quoted, with forward slashes so no backslash is read as an escape. */
  private static String quoted(String value) {
    return "\"" + value.replace('\\', '/') + "\"";
  }
}
