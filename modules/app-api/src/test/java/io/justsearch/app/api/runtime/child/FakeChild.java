/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A child process for ChildControl tests (Spec B §8), JDK only so it runs from the test classes
 * directory alone. Modes:
 *
 * <ul>
 *   <li>{@code sleep}: runs until killed;
 *   <li>{@code exit:<code>:<ms>}: exits with {@code code} after {@code ms};
 *   <li>{@code eof:<code>}: exits with {@code code} when stdin reaches EOF;
 *   <li>{@code ignore-eof}: reads stdin to EOF, then keeps running;
 *   <li>{@code exit-on-signal-file:<path>:<code>}: exits with {@code code} once {@code path} exists;
 *   <li>{@code print-path}: prints one {@code name=value} line per environment variable named
 *       {@code PATH} in any case, then exits 0.
 * </ul>
 */
public final class FakeChild {
  private FakeChild() {}

  public static void main(String[] args) throws Exception {
    String[] mode = args[0].split(":", 2);
    switch (mode[0]) {
      case "sleep" -> sleepForever();
      case "exit" -> {
        String[] parts = mode[1].split(":");
        Thread.sleep(Long.parseLong(parts[1]));
        System.exit(Integer.parseInt(parts[0]));
      }
      case "eof" -> {
        drain(System.in);
        System.exit(Integer.parseInt(mode[1]));
      }
      case "ignore-eof" -> {
        drain(System.in);
        sleepForever();
      }
      case "exit-on-signal-file" -> {
        int split = mode[1].lastIndexOf(':');
        Path signal = Path.of(mode[1].substring(0, split));
        int code = Integer.parseInt(mode[1].substring(split + 1));
        while (!Files.exists(signal)) Thread.sleep(10);
        System.exit(code);
      }
      case "print-path" -> {
        System.getenv().forEach((name, value) -> {
          if (name.equalsIgnoreCase("PATH")) System.out.println(name + "=" + value);
        });
        System.out.flush();
        System.exit(0);
      }
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  private static void drain(InputStream in) throws IOException {
    byte[] buffer = new byte[256];
    while (in.read(buffer) >= 0) {
      // discard
    }
  }

  private static void sleepForever() throws InterruptedException {
    while (true) Thread.sleep(60_000);
  }

  /** The argv that runs this class in {@code mode}. */
  public static List<String> argv(String mode) {
    List<String> argv = new ArrayList<>();
    argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    argv.add("-Xshare:auto");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-cp");
    argv.add(classesDir());
    argv.add(FakeChild.class.getName());
    argv.add(mode);
    return argv;
  }

  private static String classesDir() {
    try {
      return Path.of(FakeChild.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
