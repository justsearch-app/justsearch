/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/** Disposable, user-owned ext4 volume. Never mounts or detaches a device it did not create. */
final class LinuxLoopVolume implements AutoCloseable {
  // Names retain the owning process identity even when build output is removed during a run.
  private static final Path IMAGES = Path.of("build/drive-return-images").toAbsolutePath().normalize();
  private static final Pattern IMAGE_NAME = Pattern.compile(
      "drive-return-([0-9]+)-([0-9]+)-[0-9]+(?:-([0-9]+)-([0-9]+)-([0-9]+))?(-decoy)?\\.img");
  private static final List<LinuxLoopVolume> OWNED = new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final Commands COMMANDS = new Commands();
  private static int uid;
  private static boolean prepared;
  private final Path image;
  private final FileIdentity identity;
  private FileIdentity decoyIdentity;
  private String loop;
  private String decoy;
  private Path decoyImage;
  private Path mounted;

  private LinuxLoopVolume(Path image) throws IOException {
    this.image = image;
    identity = recordedIdentity(image);
    checkImage(image, identity);
  }

  private static synchronized void prepare() throws Exception {
    if (COMMANDS.stopping) throw new IOException("Drive test JVM is stopping");
    if (prepared) return;
    // An unavailable udisks session must not sweep anything.
    try { run("udisksctl", "status"); }
    catch (IOException unavailable) {
      Assumptions.abort("udisks user loop mounts unavailable: " + unavailable.getMessage());
    }
    uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
    Files.createDirectories(IMAGES, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(IMAGES) || !IMAGES.toRealPath().equals(IMAGES)
        || !owned(IMAGES)) throw new IOException("Unsafe drive image directory: " + IMAGES);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> COMMANDS.shutdown(() -> {
      // No class or volume monitor: interrupted test threads may still hold either one.
      for (var volume : List.copyOf(OWNED)) {
        try { volume.cleanup(true); }
        catch (Exception failure) { System.err.println("Drive shutdown cleanup: " + failure); }
      }
    }, 20_000), "drive-return-shutdown"));
    sweep(null, false);
    prepared = true;
  }

  static synchronized LinuxLoopVolume create(Path directory) throws Exception {
    prepare();
    var result = new LinuxLoopVolume(createImage(IMAGES, false));
    OWNED.add(result); // Register before any external operation, including a partial attach.
    try {
      try (var file = Files.newByteChannel(result.image, StandardOpenOption.WRITE)) {
        file.position(64L * 1024 * 1024 - 1);
        file.write(ByteBuffer.wrap(new byte[1]));
      }
      Files.createDirectories(directory);
      String owner = Files.getAttribute(directory, "unix:uid") + ":"
          + Files.getAttribute(directory, "unix:gid");
      checkImage(result.image, result.identity);
      run("mkfs.ext4", "-q", "-F", "-E", "root_owner=" + owner, result.image.toString());
      result.attachAndMount(false);
      return result;
    } catch (IOException unavailable) {
      result.close();
      Assumptions.abort("udisks user loop mounts unavailable: " + unavailable.getMessage());
      throw unavailable;
    }
  }

  record FileIdentity(long inode, String device) { }
  record Owner(long pid, long start) { }
  enum OwnerState { ALIVE, DEAD, UNKNOWN }
  enum SweepDecision { LEAVE, DETACH, DETACH_AND_DELETE }

  static Owner owner(Path file) {
    var match = IMAGE_NAME.matcher(file.getFileName().toString());
    if (!match.matches()) return null;
    try { return new Owner(Long.parseLong(match.group(1)), Long.parseLong(match.group(2))); }
    catch (NumberFormatException invalid) { return null; }
  }

  static long processStart(String stat) {
    // comm can contain spaces and ')'; fields after the final ')' begin at field 3.
    return Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]);
  }

  static OwnerState ownerState(Owner owner, Long liveStart, boolean known) {
    if (owner == null || !known) return OwnerState.UNKNOWN;
    return liveStart != null && liveStart == owner.start() ? OwnerState.ALIVE : OwnerState.DEAD;
  }

  private static OwnerState ownerState(Path file) {
    Owner owner = owner(file);
    if (owner == null) return OwnerState.UNKNOWN;
    Path process = Path.of("/proc", Long.toString(owner.pid()));
    try { return ownerState(owner, processStart(Files.readString(process.resolve("stat"))), true); }
    catch (NoSuchFileException absent) { return ownerState(owner, null, Files.notExists(process)); }
    catch (IOException | RuntimeException unknown) { return OwnerState.UNKNOWN; }
  }

  // Pure policy, shared by native sweeping and the device-free regression checks.
  static SweepDecision decide(Path images, Path file, boolean deleted, FileIdentity backing,
      FileIdentity current, boolean safeFile, boolean setupByUs, OwnerState state, boolean tracked) {
    if (!file.isAbsolute() || !file.normalize().equals(file) || !images.equals(file.getParent())
        || owner(file) == null || backing == null || !setupByUs
        || (state != OwnerState.DEAD && !tracked)) return SweepDecision.LEAVE;
    if (deleted) return SweepDecision.DETACH; // Never unlink a reused pathname.
    if (!safeFile || !backing.equals(current)) return SweepDecision.LEAVE;
    return SweepDecision.DETACH_AND_DELETE;
  }

  private static boolean owned(Path file) throws IOException {
    return ((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() == uid;
  }

  private static FileIdentity fileIdentity(Path file) throws IOException {
    var attributes = Files.readAttributes(file, "unix:ino,dev", LinkOption.NOFOLLOW_LINKS);
    long dev = ((Number) attributes.get("dev")).longValue();
    long major = ((dev >>> 8) & 0xfff) | ((dev >>> 32) & 0xfffff000L);
    long minor = (dev & 0xff) | ((dev >>> 12) & 0xffffff00L);
    return new FileIdentity(((Number) attributes.get("ino")).longValue(), major + ":" + minor);
  }

  private static boolean safeFile(Path file) throws IOException {
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && owned(file);
  }

  static Path createImage(Path images, boolean decoy) throws IOException {
    long pid = ProcessHandle.current().pid();
    long start = processStart(Files.readString(Path.of("/proc/self/stat")));
    Path temporary = Files.createTempFile(images, "drive-return-" + pid + "-" + start + "-", ".img");
    FileIdentity identity = fileIdentity(temporary);
    Path file = temporary.resolveSibling(temporary.getFileName().toString().replace(".img",
        "-" + identity.device().replace(':', '-') + "-" + identity.inode() + (decoy ? "-decoy" : "") + ".img"));
    Files.move(temporary, file); // The identity is durable in the name before any loop can attach.
    checkImage(file, identity);
    return file;
  }

  static FileIdentity recordedIdentity(Path file) {
    var match = IMAGE_NAME.matcher(file.getFileName().toString());
    if (!match.matches() || match.group(3) == null) return null;
    try { return new FileIdentity(Long.parseLong(match.group(5)), match.group(3) + ":" + match.group(4)); }
    catch (NumberFormatException invalid) { return null; }
  }

  private static void sweep(LinuxLoopVolume volume, boolean cleanup) throws Exception {
    var referenced = new HashSet<Path>();
    try (var devices = Files.list(Path.of("/sys/block"))) {
      for (Path device : devices.filter(p -> p.getFileName().toString().matches("loop[0-9]+")).toList()) {
        Path backing = device.resolve("loop/backing_file");
        if (!Files.exists(backing)) continue;
        String value = Files.readString(backing).strip();
        boolean deleted = value.endsWith(" (deleted)");
        if (deleted) value = value.substring(0, value.length() - " (deleted)".length());
        if (value.isEmpty()) continue;
        Path file = Path.of(value);
        referenced.add(file);
        if (!IMAGES.equals(file.getParent())) continue;
        boolean tracked = volume != null && (file.equals(volume.image) || file.equals(volume.decoyImage));
        if (volume != null && !tracked) continue;
        OwnerState state = ownerState(file);
        if (state != OwnerState.DEAD && !tracked) {
          System.out.println("Drive return leave (owner " + state + "): " + file);
          continue;
        }
        String loopDevice = "/dev/" + device.getFileName();
        String info = COMMANDS.run(cleanup, "udisksctl", "info", "-b", loopDevice);
        boolean setupByUs = Pattern.compile("(?m)^\\s+SetupByUID:\\s+" + uid + "\\s*$").matcher(info).find();
        FileIdentity identity = recordedIdentity(file);
        if (tracked && !java.util.Objects.equals(identity,
            file.equals(volume.image) ? volume.identity : volume.decoyIdentity)) {
          throw new IOException("Changed backing identity: " + file);
        }
        boolean safe = safeFile(file);
        FileIdentity current = safe ? fileIdentity(file) : null;
        SweepDecision decision = decide(IMAGES, file, deleted, identity, current, safe, setupByUs, state, tracked);
        if (decision == SweepDecision.LEAVE) {
          System.out.println("Drive return leave (unproven association): " + loopDevice + " " + file);
          if (tracked) throw new IOException("Cannot prove owned association: " + file);
          continue;
        }
        System.out.println("Drive return sweep: " + loopDevice + " " + file);
        boolean linked = detach(loopDevice, file, identity, cleanup);
        if (linked && decision == SweepDecision.DETACH_AND_DELETE) deleteImage(file, identity);
      }
    }
    // SIGKILL can leave an image after detach, or before setup ever attached it.
    if (volume == null) {
      try (var files = Files.list(IMAGES)) {
        for (Path file : files.toList()) {
          deleteOrphan(IMAGES, file, ownerState(file), referenced.contains(file));
        }
      }
    }
  }

  static void deleteOrphan(Path images, Path file, OwnerState state, boolean referenced) throws IOException {
    FileIdentity identity = recordedIdentity(file);
    boolean safe = safeFile(file);
    FileIdentity current = safe ? fileIdentity(file) : null;
    if (!referenced && decide(images, file, false, identity, current, safe, true, state, false)
        == SweepDecision.DETACH_AND_DELETE) {
      deleteImage(file, identity);
      System.out.println("Drive return orphan image sweep: " + file);
    }
  }

  private static void deleteImage(Path file, FileIdentity identity) throws IOException {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
    checkImage(file, identity);
    Files.delete(file);
  }

  private static void checkImage(Path file, FileIdentity identity) throws IOException {
    if (identity == null || !safeFile(file) || !identity.equals(fileIdentity(file))) {
      throw new IOException("Refusing to use a changed image: " + file);
    }
  }

  Path mounted() { return mounted; }

  synchronized Path attachAndMount(boolean readOnly) throws Exception {
    if (COMMANDS.stopping) throw new IOException("Drive test JVM is stopping");
    if (loop == null) loop = attach(image, identity);
    var args = new ArrayList<>(List.of("udisksctl", "mount", "--no-user-interaction", "-b", loop));
    if (readOnly) args.addAll(List.of("--options", "ro"));
    String output = run(args.toArray(String[]::new));
    int at = output.indexOf(" at ");
    if (at < 0) throw new IOException("No mount path in udisks response: " + output);
    String path = output.substring(at + 4).strip();
    if (path.endsWith(".")) path = path.substring(0, path.length() - 1);
    mounted = Path.of(path);
    return mounted;
  }

  synchronized void away() throws Exception {
    if (loop != null) {
      detach(loop, image, identity, false);
      loop = null;
      mounted = null;
    }
  }

  private static boolean checkAssociation(String device, Path image, FileIdentity identity) throws Exception {
    Path backing = Path.of("/sys/class/block", Path.of(device).getFileName().toString(), "loop/backing_file");
    String value = Files.readString(backing).strip();
    boolean deleted = value.endsWith(" (deleted)");
    String actual = deleted ? value.substring(0, value.length() - " (deleted)".length()) : value;
    if (!actual.equals(image.toString()) || identity == null) {
      throw new IOException("Refusing to detach a changed association: " + device + " " + actual);
    }
    // A linked d_path identifies the file currently at that path without opening /dev/loopN.
    // The creation identity rejects pathname reuse; the second read closes the read/stat race.
    if (!deleted) checkImage(image, identity);
    if (!value.equals(Files.readString(backing).strip())) {
      throw new IOException("Backing changed while checking association: " + device);
    }
    return !deleted;
  }

  private static boolean detach(String device, Path expectedImage, FileIdentity identity, boolean cleanup) throws Exception {
    checkAssociation(device, expectedImage, identity);
    // Unmount even after a partial mount response, but never ignore a real unmount failure.
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (isMounted(device)) {
      try {
        checkAssociation(device, expectedImage, identity);
        COMMANDS.run(cleanup, "udisksctl", "unmount", "--no-user-interaction", "-b", device);
        break;
      } catch (IOException failure) {
        if (failure.getMessage().contains("UDisks2.Error.NotMounted")) break;
        if (!failure.getMessage().contains("UDisks2.Error.DeviceBusy")
            || System.nanoTime() >= until) throw failure;
        Thread.sleep(100);
      }
    }
    boolean linked = checkAssociation(device, expectedImage, identity);
    COMMANDS.run(cleanup, "udisksctl", "loop-delete", "--no-user-interaction", "-b", device);
    return linked;
  }

  private static boolean isMounted(String device) throws IOException {
    String number = Files.readString(Path.of("/sys/class/block", Path.of(device).getFileName().toString(), "dev")).strip();
    return Files.readAllLines(Path.of("/proc/self/mountinfo")).stream()
        .anyMatch(line -> line.split(" ")[2].equals(number));
  }

  synchronized void label(String value) throws Exception {
    if (loop != null) throw new IllegalStateException("Relabel only a detached volume");
    checkImage(image, identity);
    run("e2label", image.toString(), value);
  }

  synchronized void holdDecoy() throws Exception {
    if (COMMANDS.stopping) throw new IOException("Drive test JVM is stopping");
    if (decoy != null) throw new IllegalStateException("Decoy already held");
    decoyImage = createImage(IMAGES, true);
    decoyIdentity = recordedIdentity(decoyImage);
    checkImage(decoyImage, decoyIdentity);
    Files.write(decoyImage, new byte[4096], StandardOpenOption.WRITE);
    decoy = attach(decoyImage, decoyIdentity);
  }

  synchronized void releaseDecoy() throws Exception {
    if (decoy != null) {
      detach(decoy, decoyImage, decoyIdentity, false);
      decoy = null;
      deleteImage(decoyImage, decoyIdentity);
      decoyIdentity = null;
    }
  }

  private static String attach(Path file, FileIdentity identity) throws Exception {
    checkImage(file, identity);
    String output = run("udisksctl", "loop-setup", "--no-user-interaction", "--file", file.toString());
    var match = Pattern.compile("/dev/loop[0-9]+").matcher(output);
    if (!match.find()) throw new IOException("No loop device in udisks response: " + output);
    return match.group();
  }

  static String run(String... args) throws Exception { return COMMANDS.run(false, args); }

  /** The same command cancellation and monitor-free deadline used by the shutdown hook. */
  static final class Commands {
    private final List<Process> running = new ArrayList<>();
    private volatile boolean stopping;
    private volatile long deadline;

    String run(boolean cleanup, String... args) throws Exception {
      Process process;
      Path outputFile = Files.createTempFile("drive-return-command-", ".log");
      try {
        synchronized (this) {
          if (stopping && !cleanup) throw new IOException("Drive test JVM is stopping");
          if (cleanup && deadline - System.nanoTime() <= 0) throw new IOException("Drive cleanup deadline expired");
          process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
          running.add(process);
        }
        try {
          long wait = cleanup ? Math.min(TimeUnit.SECONDS.toNanos(5), deadline - System.nanoTime())
              : TimeUnit.SECONDS.toNanos(30);
          if (!process.waitFor(Math.max(0, wait), TimeUnit.NANOSECONDS)) throw new IOException("Command timed out: " + args[0]);
          String output = Files.readString(outputFile);
          if (process.exitValue() != 0) throw new IOException(String.join(" ", args) + ": " + output);
          return output;
        } finally {
          kill(process);
          synchronized (this) { running.remove(process); }
        }
      } finally { Files.deleteIfExists(outputFile); }
    }

    private static void kill(Process process) {
      var children = process.descendants().toList();
      children.forEach(ProcessHandle::destroyForcibly);
      // Let a waiting shell reap its children before killing the shell itself.
      if (!children.isEmpty()) {
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }
      if (process.isAlive()) {
        process.destroyForcibly();
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }
    }

    void shutdown(Runnable cleanup, long budgetMillis) {
      List<Process> cancel;
      synchronized (this) {
        stopping = true;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        cancel = List.copyOf(running);
      }
      cancel.forEach(Commands::kill);
      Thread worker = new Thread(cleanup, "drive-return-cleanup");
      worker.setDaemon(true);
      worker.start();
      try { worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      if (worker.isAlive()) {
        worker.interrupt();
        synchronized (this) { cancel = List.copyOf(running); }
        cancel.forEach(Commands::kill);
        System.err.println("Drive shutdown cleanup exceeded its deadline; images retained for recovery");
      }
    }
  }

  private void cleanup(boolean shutdown) throws Exception {
    // Also finds a setup whose command response was interrupted, without acquiring a volume monitor.
    sweep(this, shutdown);
    deleteImage(image, identity);
    if (decoyIdentity != null) deleteImage(decoyImage, decoyIdentity);
    OWNED.remove(this);
  }

  @Override public synchronized void close() throws Exception {
    cleanup(false);
    loop = null;
    decoy = null;
    mounted = null;
  }
}
