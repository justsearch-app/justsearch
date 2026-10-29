/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.configuration.SystemAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Linux PDEATHSIG is tied to the creating thread. This platform thread lives for the JVM. */
public final class BoundChildProcess {
  private BoundChildProcess() {}

  // The original Process object owns this birth even after reaping. A PID-only cache cannot
  // do that safely; weak keys retire metadata when the original spawn reference is released.
  private static final java.util.Map<Process, LinuxProcessIdentity> BIRTHS =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  private static final class Spawner {
    static final LinkedBlockingQueue<FutureTask<Process>> QUEUE = new LinkedBlockingQueue<>();
    static {
      Thread.ofPlatform().daemon().name("linux-bound-child-spawn").start(() -> {
        for (;;) {
          try { QUEUE.take().run(); }
          catch (InterruptedException ignored) { /* Never retire the parent of living children. */ }
        }
      });
    }
  }

  /** Resolve from the application jar/classes, independent of cwd and installed launcher. */
  public static Path helper() throws IOException {
    try {
      Path location = Path.of(BoundChildProcess.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI()).toAbsolutePath();
      if (Files.isRegularFile(location)) {
        Path lib = location.getParent();
        Path helper = lib.getParent().resolve(io.justsearch.configuration.NativeTargets.current().relativePath("bound-exec"));
        if (Files.isRegularFile(helper) && Files.isExecutable(helper)) return helper.toRealPath();
      } else {
        // Gradle classes: the native output belongs to this app-api build, never another checkout.
        for (Path dir = location; dir != null; dir = dir.getParent()) {
          Path helper = dir.resolve(io.justsearch.configuration.NativeTargets.current().relativePath("bound-exec"));
          if (Files.isRegularFile(helper) && Files.isExecutable(helper)) return helper.toRealPath();
          if (Files.exists(dir.resolve("settings.gradle.kts"))) break;
        }
      }
    } catch (Exception failure) {
      throw new IOException("Cannot resolve Linux child lifetime helper", failure);
    }
    throw new IOException("Linux child lifetime helper missing or not executable beside application lib/");
  }

  /** Boot probes the actual helper; absence or an unusable backend refuses child-capable work. */
  public static void initialize() throws IOException {
    if (!io.justsearch.configuration.PlatformPaths.isLinux()) return;
    Process probe = start(new ProcessBuilder("/bin/true").redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD));
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          if (!probe.waitFor(2, TimeUnit.SECONDS) || probe.exitValue() != 0)
            throw new IOException("Linux child lifetime helper probe failed");
          return;
        } catch (InterruptedException cancelled) { interrupted = true; }
      }
    } finally {
      if (probe.isAlive()) probe.destroyForcibly();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  public static Process start(ProcessBuilder original) throws IOException {
    if (!io.justsearch.configuration.PlatformPaths.isLinux()) return original.start();
    Path executable = helper();
    var command = java.util.List.copyOf(original.command());
    var argv = new ArrayList<String>();
    argv.add(executable.toString());
    argv.add("exec");
    argv.add(Long.toString(ProcessHandle.current().pid()));
    argv.add("--");
    argv.addAll(command);
    original.command(argv);
    FutureTask<Process> task = new FutureTask<>(() -> spawn(original, executable, Files::readSymbolicLink));
    Spawner.QUEUE.add(task);
    // Cancellation must not abandon an already queued or running spawn. Return its exact owner
    // object with the interrupt preserved, so the caller's existing cancellation path owns cleanup.
    boolean interrupted = false;
    try {
      for (;;) {
        try { return task.get(); }
        catch (InterruptedException cancelled) { interrupted = true; }
        catch (ExecutionException failure) {
          if (failure.getCause() instanceof IOException io) throw io;
          if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
          if (failure.getCause() instanceof Error error) throw error;
          throw new IOException("Linux bound child spawn failed", failure.getCause());
        }
      }
    } finally {
      original.command(command);
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  interface ExeLinkReader {
    Path read(Path link) throws IOException;
  }

  static Process spawn(ProcessBuilder builder, Path helper, ExeLinkReader exeLinkReader) throws IOException {
    Process process = builder.start();
    try {
      Optional<LinuxProcessIdentity> identity = LinuxProcessIdentity.captureSpawned(process);
      // ProcessBuilder observes the wrapper's exec, not its workload's exec. Do not publish the
      // wrapper image/identity as a successful workload, or report setup refusal as readiness.
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      boolean executed = false;
      while (process.isAlive()) {
        try {
          Path image = exeLinkReader.read(Path.of("/proc", Long.toString(process.pid()), "exe"));
          if (!image.equals(helper)) { executed = true; break; }
        } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException racingExit) {
          // The kernel may have reaped it before the JDK's reaper updates Process.isAlive().
        }
        if (System.nanoTime() >= deadline) throw new IOException("Linux child wrapper did not exec");
        TimeUnit.MILLISECONDS.sleep(1);
      }
      if (!executed && !process.isAlive() && process.exitValue() == 125) {
        String detail = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        throw new IOException("Linux child wrapper refused launch (exit 125): " + detail);
      }
      if (identity.isEmpty()) identity = LinuxProcessIdentity.captureSpawned(process);
      identity.ifPresent(value -> BIRTHS.put(process, value));
      return process;
    } catch (IOException | RuntimeException | InterruptedException failure) {
      process.destroyForcibly();
      boolean interrupted = false;
      try {
        for (;;) {
          try { process.waitFor(); break; }
          catch (InterruptedException cancelled) { interrupted = true; }
        }
      } finally { if (interrupted) Thread.currentThread().interrupt(); }
      String target = builder.command().get(4);
      String cwd = builder.directory() == null ? "" : " (in directory \"" + builder.directory() + "\")";
      throw new IOException("Cannot run program \"" + target + "\"" + cwd + ": " + failure.getMessage(), failure);
    }
  }

  static Optional<LinuxProcessIdentity> birthOf(Process process) {
    return Optional.ofNullable(BIRTHS.get(process));
  }

  public static Optional<Boolean> contains(ProcessHandle process) {
    if (!process.isAlive()) return Optional.empty();
    var observed = LinuxProcessIdentity.read(process.pid());
    if (observed.isEmpty()) return Optional.empty();
    synchronized (BIRTHS) {
      return BIRTHS.entrySet().stream().filter(entry -> entry.getKey().toHandle().equals(process))
          .map(entry -> entry.getValue().equals(observed.get())).findFirst();
    }
  }
}
