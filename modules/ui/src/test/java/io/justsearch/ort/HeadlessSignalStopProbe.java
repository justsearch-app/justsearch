/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionAcquireDeadlineExceededException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** One isolated JVM, fake native call, real session leases/admission and real Unix signal delivery. */
public final class HeadlessSignalStopProbe {
  private HeadlessSignalStopProbe() {}

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    String mode = args[1];
    if (mode.endsWith("children")) {
      var containment = io.justsearch.app.api.runtime.containment.EngineContainment.installAtBoot();
      if (containment.status() != io.justsearch.app.api.runtime.containment.EngineContainment.Status.CONTAINED)
        throw new IllegalStateException("containment unavailable: " + containment.unavailableReason());
      for (String role : List.of("extraction", "mcp")) {
        Process helper = io.justsearch.app.api.runtime.child.BoundChildProcess.start(new ProcessBuilder("/bin/sleep", "60"));
        var identity = io.justsearch.app.api.runtime.child.LinuxProcessIdentity.read(helper.pid()).orElseThrow();
        write(dir, role + ".json", new tools.jackson.databind.ObjectMapper().writeValueAsString(identity));
      }
    }
    Object router = router(mode.startsWith("deadline") ? 1_200L : 15_000L);
    if (router != null) invoke(router, "install");
    if (mode.equals("preboot")) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> write(dir, "hook", "safe")));
      write(dir, "ready", "preboot");
      await(dir, "release");
      if (router != null && (boolean) invoke(router, "beginNativeInitialization")) {
        write(dir, "native-after-signal", "unexpected");
      }
      return;
    }
    if (router != null) invoke(router, "beginNativeInitialization");
    var admission = new EngineAdmissionController(4, 4, 1);
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "signal-probe",
        Optional.empty(), Optional.empty(), "local", "HTTP",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    var owner = admission.admit(context, false);
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(ignored -> {
      write(dir, "native-entered", "held");
      await(dir, "release");
      write(dir, "native-returned", "yes");
      return mock(OrtSession.Result.class);
    });
    var handle = handle(session);
    Thread caller = Thread.ofPlatform().name("probe-inference-caller").start(() -> {
      try (owner) {
        if (mode.equals("queued")) await(dir, "release");
        boolean detached = mode.equals("detached");
        var request = new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(20), () -> false, false, detached);
        try (var lease = handle.acquire(request)) {
          if (detached) {
            try { lease.runWithin(Map.of(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)); }
            catch (SessionAcquireDeadlineExceededException expected) {
              write(dir, "detached", "caller released; native lease retained");
            }
          } else lease.run(Map.of()).close();
        }
      } catch (Exception failure) {
        failure.printStackTrace();
        Runtime.getRuntime().halt(90);
      }
    });
    if (mode.equals("detached")) await(dir, "detached");
    else if (!mode.equals("queued")) await(dir, "native-entered");
    var intentMethod = Class.forName("io.justsearch.ui.HeadlessApp")
        .getDeclaredMethod("engineSelfIntent", Path.class, String.class);
    intentMethod.setAccessible(true);
    var intent = (EngineShutdownSequence.SelfIntent) intentMethod.invoke(null, dir, "signal-probe");
    var sequence = new EngineShutdownSequence(dir, List.of(
        new EngineShutdownSequence.Step("admission", ignored -> {
          write(dir, "draining", "yes");
          admission.beginClosing();
          if (mode.equals("deadline-executor")) new java.util.concurrent.CountDownLatch(1).await();
          if (!admission.awaitDrained(Duration.ofSeconds(30))) throw new IllegalStateException("held work");
          caller.join();
          return null;
        }),
        new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, ignored -> {
          handle.close();
          write(dir, "drained", "yes");
          return "GRACEFUL";
        })), System::exit, code -> Runtime.getRuntime().halt(code),
        () -> handle.retirementStatus() == io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED
            ? NativeQuiescence.QUIESCED : NativeQuiescence.UNQUIESCED, ignored -> {},
        (reason, detail, code) -> {
          write(dir, "self-intent", reason.name());
          intent.record(reason, detail, code);
        });
    // Inject a hook dispatcher that always invokes the simulated ORT destructor first.
    // Thus baseline "draining" cannot be observed before "hook"; scheduler timing cannot
    // accidentally make the baseline pass. The production competing-hook probe remains separate.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      write(dir, "hook", Files.exists(dir.resolve("drained")) ? "safe" : "unsafe");
      write(dir, "release", "destructor ran");
      sequence.runFromJvmShutdownHook(Reason.QUIT);
    }, "simulated-ort-first-hook-dispatch"));
    boolean boot = mode.equals("boot") || mode.equals("deadline-boot") || mode.equals("boot-failure");
    if (!boot && router != null) publish(router, sequence);
    if (mode.equals("deadline-fatal-first")) {
      Thread.ofPlatform().start(() -> sequence.runAndExitFatal(Reason.RESTART));
      await(dir, "draining");
    }
    write(dir, "ready", mode);
    if (mode.equals("deadline-fatal")) {
      await(dir, "fatal");
      sequence.runAndExitFatal(Reason.RESTART);
      write(dir, "fatal-requested", "yes");
    }
    if (boot) {
      if (router != null) {
        // Observation only: acknowledge actual signal delivery before the parent allows boot
        // publication. Taking the same monitor observes the handler's guarded pending state.
        Field pending = router.getClass().getDeclaredField("signalStatus");
        pending.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (;;) {
          synchronized (router) { if (pending.getInt(router) != 0) break; }
          if (System.nanoTime() >= deadline) throw new AssertionError("signal was not delivered");
          Thread.sleep(5);
        }
        write(dir, "signal-observed", "pending before publication");
      }
      await(dir, "publish");
      if (mode.equals("boot-failure")) {
        if (router != null) invoke(router, "bootFailed");
        write(dir, "fatal-cleanup", "held");
        await(dir, "release");
        sequence.runAndExitFatal(Reason.RESTART);
      } else if (router != null) publish(router, sequence);
    }
    new java.util.concurrent.CountDownLatch(1).await();
  }

  private static NativeSessionHandle handle(OrtSession session) throws Exception {
    Path missing = Path.of("missing", "model.onnx");
    var handle = NativeSessionHandle.builder("signal-probe", missing).gpuModelPath(missing)
        .shouldUseGpu(() -> true).runtime(RuntimePolicy.defaults())
        .policy(ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 512L * 1024 * 1024),
            null, true, false, NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS)).build();
    for (var entry : Map.<String, Object>of("gpuSession", session,
        "gpuSessionAttempted", true, "gpuAvailable", true).entrySet()) {
      Field field = NativeSessionHandle.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.set(handle, entry.getValue());
    }
    return handle;
  }

  private static Object router(long deadline) throws Exception {
    try {
      Class<?> type = Class.forName("io.justsearch.app.engine.EngineSignalStop");
      var constructor = type.getDeclaredConstructor(IntConsumer.class, IntConsumer.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance((IntConsumer) System::exit,
          (IntConsumer) code -> Runtime.getRuntime().halt(code), deadline);
    } catch (ClassNotFoundException baseline) {
      return null; // Exercise the baseline's actual default JVM signal path, not a substitute.
    }
  }

  private static Object invoke(Object target, String method) throws Exception {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static void publish(Object router, EngineShutdownSequence sequence) throws Exception {
    router.getClass().getMethod("publish", EngineShutdownSequence.class).invoke(router, sequence);
    invoke(router, "ready");
  }

  private static void await(Path dir, String file) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
    while (!Files.exists(dir.resolve(file))) {
      if (System.nanoTime() >= deadline) throw new IllegalStateException("missing marker " + file);
      Thread.sleep(5);
    }
  }

  private static void write(Path dir, String name, String text) {
    try { Files.writeString(dir.resolve(name), text); }
    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }
}
