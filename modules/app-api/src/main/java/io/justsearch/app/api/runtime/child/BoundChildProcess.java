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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Linux PDEATHSIG is tied to the creating thread. This platform thread lives for the JVM. */
public final class BoundChildProcess {
  private BoundChildProcess() {}


  private static final class Spawner {
    static final LinkedBlockingQueue<FutureTask<Process>> QUEUE = new LinkedBlockingQueue<>();
    static final ConcurrentHashMap<Long, LinuxProcessIdentity> CHILDREN = new ConcurrentHashMap<>();
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
    FutureTask<Process> task = new FutureTask<>(() -> spawn(original, executable));
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

  private static Process spawn(ProcessBuilder builder, Path helper) throws IOException {
    Process process = builder.start();
    try {
      // ProcessBuilder observes the wrapper's exec, not its workload's exec. Do not publish the
      // wrapper image/identity as a successful workload, or report setup refusal as readiness.
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      boolean executed = false;
      while (process.isAlive()) {
        try {
          Path image = Files.readSymbolicLink(Path.of("/proc", Long.toString(process.pid()), "exe"));
          if (!image.equals(helper)) { executed = true; break; }
        } catch (java.nio.file.NoSuchFileException racingExit) {
          // The kernel may have reaped it before the JDK's reaper updates Process.isAlive().
        }
        if (System.nanoTime() >= deadline) throw new IOException("Linux child wrapper did not exec");
        TimeUnit.MILLISECONDS.sleep(1);
      }
      if (!executed && !process.isAlive() && process.exitValue() == 125)
        throw new IOException("Linux child wrapper refused launch (exit 125)");
      Optional<LinuxProcessIdentity> identity = LinuxProcessIdentity.read(process.pid());
      identity.ifPresent(value -> Spawner.CHILDREN.put(process.pid(), value));
      process.onExit().thenRun(() -> identity.ifPresent(value -> Spawner.CHILDREN.remove(process.pid(), value)));
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
      throw new IOException("Linux bound child launch failed", failure);
    }
  }

  public static Optional<Boolean> contains(ProcessHandle process) {
    LinuxProcessIdentity bound = Spawner.CHILDREN.get(process.pid());
    if (bound == null) return Optional.empty();
    return LinuxProcessIdentity.read(process.pid()).map(bound::equals);
  }
}
