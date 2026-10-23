/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/** Disposable, user-owned ext4 volume. Never mounts or detaches a device it did not create. */
final class LinuxLoopVolume implements AutoCloseable {
  private final Path image;
  private String loop;
  private String decoy;
  private Path mounted;

  private LinuxLoopVolume(Path image) { this.image = image; }

  static LinuxLoopVolume create(Path directory) throws Exception {
    Files.createDirectories(directory);
    var result = new LinuxLoopVolume(directory.resolve("volume.img"));
    try {
      try (var file = Files.newByteChannel(result.image,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        file.position(64L * 1024 * 1024 - 1);
        file.write(ByteBuffer.wrap(new byte[1]));
      }
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

  Path mounted() { return mounted; }

  Path attachAndMount(boolean readOnly) throws Exception {
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

  void away() throws Exception {
    if (mounted != null) {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (true) {
        try {
          run("udisksctl", "unmount", "--no-user-interaction", "-b", loop);
          break;
        } catch (IOException busy) {
          // A concurrent, strictly held root observation can temporarily keep this mount busy.
          if (!busy.getMessage().contains("UDisks2.Error.DeviceBusy")
              || System.nanoTime() >= until) throw busy;
          Thread.sleep(100);
        }
      }
      mounted = null;
    }
    if (loop != null) {
      run("udisksctl", "loop-delete", "--no-user-interaction", "-b", loop);
      loop = null;
    }
  }

  void label(String value) throws Exception {
    if (loop != null) throw new IllegalStateException("Relabel only a detached volume");
    run("e2label", image.toString(), value);
  }

  void holdDecoy() throws Exception {
    if (decoy != null) throw new IllegalStateException("Decoy already held");
    Path file = image.resolveSibling("decoy.img");
    if (!Files.exists(file)) Files.write(file, new byte[4096]);
    decoy = attach(file);
  }

  void releaseDecoy() throws Exception {
    if (decoy != null) {
      run("udisksctl", "loop-delete", "--no-user-interaction", "-b", decoy);
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
      if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Command timed out: " + args[0]);
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (process.exitValue() != 0) throw new IOException(String.join(" ", args) + ": " + output);
      return output;
    } finally {
      if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
    }
  }

  @Override public void close() throws Exception {
    try { away(); }
    finally { releaseDecoy(); }
  }
}
