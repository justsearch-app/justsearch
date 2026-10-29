/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/** Disposable, user-owned ext4 volume. Never mounts or detaches a device it did not create. */
final class LinuxLoopVolume implements AutoCloseable {
  // Outside JUnit's TempDir: images survive SIGKILL, so the next run can identify their loops.
  private static final Path IMAGES = Path.of("build/drive-return-images").toAbsolutePath().normalize();
  private static final List<LinuxLoopVolume> OWNED = new java.util.concurrent.CopyOnWriteArrayList<>();
  private static FileChannel lockChannel;
  private static FileLock runLock;
  private static int uid;
  private static volatile boolean stopping;
  private final Path image;
  private String loop;
  private String decoy;
  private Path mounted;

  private LinuxLoopVolume(Path image) { this.image = image; }

  private static synchronized void prepare() throws Exception {
    if (stopping) throw new IOException("Drive test JVM is stopping");
    if (runLock != null) return;
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
    lockChannel = FileChannel.open(IMAGES.resolve("run.lock"), StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    runLock = lockChannel.tryLock();
    if (runLock == null) {
      lockChannel.close();
      throw new IOException("Another drive test JVM owns " + IMAGES);
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      stopping = true;
      // Hook return is bounded even if a test thread holds a monitor or a command stalls.
      Thread cleanup = new Thread(() -> {
        synchronized (LinuxLoopVolume.class) {
          for (var volume : List.copyOf(OWNED)) {
            try { volume.close(); }
            catch (Exception failure) { System.err.println("Drive shutdown cleanup: " + failure); }
          }
          try { sweep(); }
          catch (Exception failure) { System.err.println("Drive shutdown sweep: " + failure); }
        }
      }, "drive-return-cleanup");
      cleanup.setDaemon(true);
      cleanup.start();
      try { cleanup.join(20_000); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }, "drive-return-shutdown"));
    sweep();
  }

  static synchronized LinuxLoopVolume create(Path directory) throws Exception {
    prepare();
    var result = new LinuxLoopVolume(Files.createTempFile(IMAGES, "drive-return-", ".img"));
    OWNED.add(result); // Register before any external operation, including a partial attach.
    try {
      try (var file = Files.newByteChannel(result.image, StandardOpenOption.WRITE)) {
        file.position(64L * 1024 * 1024 - 1);
        file.write(ByteBuffer.wrap(new byte[1]));
      }
      Files.createDirectories(directory);
      String owner = Files.getAttribute(directory, "unix:uid") + ":"
          + Files.getAttribute(directory, "unix:gid");
      run("mkfs.ext4", "-q", "-F", "-E", "root_owner=" + owner, result.image.toString());
      result.attachAndMount(false);
      return result;
    } catch (IOException unavailable) {
      result.close();
      Assumptions.abort("udisks user loop mounts unavailable: " + unavailable.getMessage());
      throw unavailable;
    }
  }

  private static boolean owned(Path file) throws IOException {
    return ((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() == uid;
  }

  private static void sweep() throws Exception { sweep(null); }

  private static void sweep(Path onlyImage) throws Exception {
    try (var devices = Files.list(Path.of("/sys/block"))) {
      for (Path device : devices.filter(p -> p.getFileName().toString().matches("loop[0-9]+")).toList()) {
        Path backing = device.resolve("loop/backing_file");
        if (!Files.exists(backing)) continue;
        String value = Files.readString(backing).strip();
        boolean deleted = value.endsWith(" (deleted)");
        if (deleted) value = value.substring(0, value.length() - " (deleted)".length());
        if (value.isEmpty()) continue;
        Path file = Path.of(value);
        if (!file.isAbsolute() || !file.normalize().equals(file) || !IMAGES.equals(file.getParent())
            || !file.getFileName().toString().matches("drive-return-[0-9]+(-decoy)?\\.img")) continue;
        if (onlyImage != null && !file.equals(onlyImage)
            && !file.getFileName().toString().equals(onlyImage.getFileName().toString().replace(".img", "-decoy.img"))) continue;
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
          if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !owned(file)) continue;
        } else if (!deleted) continue;
        String loopDevice = "/dev/" + device.getFileName();
        // SetupByUID also establishes ownership when the backing inode has been deleted.
        String info = run("udisksctl", "info", "-b", loopDevice);
        if (!Pattern.compile("(?m)^\\s+SetupByUID:\\s+" + uid + "\\s*$").matcher(info).find()) continue;
        System.out.println("Drive return sweep: " + loopDevice + " " + file);
        detach(loopDevice, file);
        Files.deleteIfExists(file);
      }
    }
  }

  Path mounted() { return mounted; }

  synchronized Path attachAndMount(boolean readOnly) throws Exception {
    if (stopping) throw new IOException("Drive test JVM is stopping");
    if (loop == null) loop = attach(image);
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
      detach(loop, image);
      loop = null;
      mounted = null;
    }
  }

  private static void detach(String device, Path expectedImage) throws Exception {
    Path backing = Path.of("/sys/class/block", Path.of(device).getFileName().toString(), "loop/backing_file");
    if (!Files.exists(backing)) return;
    String actual = Files.readString(backing).strip().replaceFirst(" \\(deleted\\)$", "");
    if (!actual.equals(expectedImage.toString())) {
      throw new IOException("Refusing to detach a changed association: " + device + " " + actual);
    }
    // Unmount even after a partial mount response, but never ignore a real unmount failure.
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (isMounted(device)) {
      try {
        run("udisksctl", "unmount", "--no-user-interaction", "-b", device);
        break;
      } catch (IOException failure) {
        if (failure.getMessage().contains("UDisks2.Error.NotMounted")) break;
        if (!failure.getMessage().contains("UDisks2.Error.DeviceBusy")
            || System.nanoTime() >= until) throw failure;
        Thread.sleep(100);
      }
    }
    run("udisksctl", "loop-delete", "--no-user-interaction", "-b", device);
  }

  private static boolean isMounted(String device) throws IOException {
    String number = Files.readString(Path.of("/sys/class/block", Path.of(device).getFileName().toString(), "dev")).strip();
    return Files.readAllLines(Path.of("/proc/self/mountinfo")).stream()
        .anyMatch(line -> line.split(" ")[2].equals(number));
  }

  synchronized void label(String value) throws Exception {
    if (loop != null) throw new IllegalStateException("Relabel only a detached volume");
    run("e2label", image.toString(), value);
  }

  synchronized void holdDecoy() throws Exception {
    if (stopping) throw new IOException("Drive test JVM is stopping");
    if (decoy != null) throw new IllegalStateException("Decoy already held");
    Path file = decoyImage();
    if (!Files.exists(file)) Files.write(file, new byte[4096], StandardOpenOption.CREATE_NEW);
    decoy = attach(file);
  }

  private Path decoyImage() {
    return image.resolveSibling(image.getFileName().toString().replace(".img", "-decoy.img"));
  }

  synchronized void releaseDecoy() throws Exception {
    if (decoy != null) {
      detach(decoy, decoyImage());
      decoy = null;
    }
  }

  private static String attach(Path file) throws Exception {
    String output = run("udisksctl", "loop-setup", "--no-user-interaction", "--file", file.toString());
    var match = Pattern.compile("/dev/loop[0-9]+").matcher(output);
    if (!match.find()) throw new IOException("No loop device in udisks response: " + output);
    return match.group();
  }

  static String run(String... args) throws Exception {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Command timed out: " + args[0]);
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (process.exitValue() != 0) throw new IOException(String.join(" ", args) + ": " + output);
      return output;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
      }
    }
  }

  @Override public synchronized void close() throws Exception {
    try { away(); }
    finally { releaseDecoy(); }
    // Recover even a setup that completed before its command response could be parsed.
    sweep(image);
    // Keep images if detach failed: the next JVM can still prove ownership and recover them.
    Files.deleteIfExists(image);
    Files.deleteIfExists(decoyImage());
    // The run lock stays held until process exit, including while its shutdown hook sweeps.
    OWNED.remove(this);
  }
}
