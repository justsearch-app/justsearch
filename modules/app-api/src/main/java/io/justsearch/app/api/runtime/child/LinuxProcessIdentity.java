/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/** Linux birth identity. Start ticks are decimal strings, never Windows FILETIME or wall time. */
public record LinuxProcessIdentity(String kind, long pid, String bootId, String startTicks) {
  public static final String KIND = "linux-proc-v1";
  private static final Path BOOT_FILE = Path.of("/proc/sys/kernel/random/boot_id");
  private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

  public LinuxProcessIdentity {
    if (!KIND.equals(kind) || pid <= 0 || pid > Integer.MAX_VALUE || bootId == null
        || !bootId.matches(UUID_PATTERN)
        || startTicks == null || !startTicks.matches("0|[1-9][0-9]{0,19}")
        || new java.math.BigInteger(startTicks).bitLength() > 64)
      throw new IllegalArgumentException("Invalid Linux process identity");
  }

  record Stat(char state, String ticks) {}
  public record Observation(ProcessTerminator.IdentityPresence presence,
      Optional<LinuxProcessIdentity> identity, Optional<String> executable) {}

  static Stat parseStat(String stat) throws IOException {
    int close = stat.lastIndexOf(')');
    if (close < 0 || close + 2 >= stat.length() || stat.charAt(close + 1) != ' ')
      throw new IOException("Invalid /proc stat comm");
    String[] fields = stat.substring(close + 2).strip().split("\\s+");
    if (fields.length < 20 || fields[0].length() != 1 || !fields[19].matches("0|[1-9][0-9]{0,19}"))
      throw new IOException("Invalid /proc stat fields");
    return new Stat(fields[0].charAt(0), fields[19]);
  }

  public static Observation observe(long pid) { return observe(pid, false); }

  private static Observation observe(long pid, boolean retainEndedBirth) {
    if (!io.justsearch.configuration.PlatformPaths.isLinux() || pid <= 0 || pid > Integer.MAX_VALUE) return unknown();
    Path directory = Path.of("/proc", Long.toString(pid));
    try {
      String boot = Files.readString(BOOT_FILE).strip();
      if (!boot.matches(UUID_PATTERN)) return unknown();
      Stat before;
      try { before = parseStat(Files.readString(directory.resolve("stat"))); }
      catch (NoSuchFileException gone) {
        return new Observation(ProcessTerminator.IdentityPresence.GONE, Optional.empty(), Optional.empty());
      }
      if (ended(before) && !retainEndedBirth)
        return new Observation(ProcessTerminator.IdentityPresence.GONE, Optional.empty(), Optional.empty());
      String image = null;
      try { image = Files.readSymbolicLink(directory.resolve("exe")).toString(); }
      catch (IOException unreadable) { /* Identity may still be readable, executable stays unknown. */ }
      Stat after = parseStat(Files.readString(directory.resolve("stat")));
      String afterBoot = Files.readString(BOOT_FILE).strip();
      if (!before.ticks().equals(after.ticks()) || !boot.equals(afterBoot)) return unknown();
      if (ended(after) && !retainEndedBirth)
        return new Observation(ProcessTerminator.IdentityPresence.GONE, Optional.empty(), Optional.empty());
      return new Observation(ended(after) ? ProcessTerminator.IdentityPresence.GONE
          : ProcessTerminator.IdentityPresence.PRESENT,
          Optional.of(new LinuxProcessIdentity(KIND, pid, boot, before.ticks())), Optional.ofNullable(image));
    } catch (IOException | RuntimeException unreadable) { return unknown(); }
  }

  private static boolean ended(Stat stat) {
    return stat.state() == 'Z' || stat.state() == 'X' || stat.state() == 'x';
  }

  private static Observation unknown() {
    return new Observation(ProcessTerminator.IdentityPresence.UNKNOWN, Optional.empty(), Optional.empty());
  }

  public static Optional<LinuxProcessIdentity> read(long pid) { return observe(pid).identity(); }

  /** Capture only through the original spawn's JDK handle, whose Info rejects a reused PID. */
  static Optional<LinuxProcessIdentity> captureSpawned(Process process) {
    try {
      ProcessHandle handle = process.toHandle();
      if (handle.pid() != process.pid() || handle.info().startInstant().isEmpty()) return Optional.empty();
      var observed = observe(process.pid(), true);
      return handle.info().startInstant().isPresent() ? observed.identity() : Optional.empty();
    } catch (RuntimeException unavailable) { return Optional.empty(); }
  }

  static Optional<String> currentBootId() {
    try { return Optional.of(Files.readString(BOOT_FILE).strip()).filter(value -> value.matches(UUID_PATTERN)); }
    catch (IOException | RuntimeException unreadable) { return Optional.empty(); }
  }

  /** In-memory intent/window key; serialized process identities remain structured records. */
  String key() { return KIND + ":" + bootId + ":" + startTicks; }

  /** Parses a serialized identity without observing or assigning a current process birth. */
  public static Optional<LinuxProcessIdentity> fromJson(tools.jackson.databind.JsonNode node) {
    try {
      if (!node.path("kind").isString() || !node.path("pid").isIntegralNumber()
          || !node.path("pid").canConvertToInt() || !node.path("bootId").isString()
          || !node.path("startTicks").isString()) return Optional.empty();
      return Optional.of(new LinuxProcessIdentity(node.path("kind").asString(""), node.path("pid").asLong(0),
          node.path("bootId").asString(""), node.path("startTicks").asString("")));
    } catch (RuntimeException invalid) { return Optional.empty(); }
  }

  /** Only a tuple acquired before the helper opens a pidfd can authorize a recorded-process stop. */
  int signal(String name) throws IOException, InterruptedException {
    Process signal = new ProcessBuilder(BoundChildProcess.helper().toString(),
        "signal", Long.toString(pid), bootId, startTicks, name)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    boolean interrupted = false;
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    try {
      for (;;) {
        try {
          long remaining = deadline - System.nanoTime();
          if (remaining > 0 && signal.waitFor(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) return signal.exitValue();
          throw new IOException("Linux pidfd signal helper timed out");
        } catch (InterruptedException cancelled) { interrupted = true; }
      }
    } finally {
      if (signal.isAlive()) signal.destroyForcibly();
      for (;;) {
        try { signal.waitFor(); break; }
        catch (InterruptedException cancelled) { interrupted = true; }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
