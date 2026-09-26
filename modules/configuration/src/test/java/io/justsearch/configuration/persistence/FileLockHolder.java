/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Pure-JDK child process used to hold a real cross-process file lock in tests. */
public final class FileLockHolder {
  private FileLockHolder() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 4) throw new IllegalArgumentException("expected file, mode, ready, release");
    Path file = Path.of(arguments[0]);
    boolean shared = switch (arguments[1]) {
      case "shared" -> true;
      case "exclusive" -> false;
      default -> throw new IllegalArgumentException("unknown lock mode: " + arguments[1]);
    };
    Path ready = Path.of(arguments[2]);
    Path release = Path.of(arguments[3]);

    var options = shared
        ? new StandardOpenOption[] {StandardOpenOption.READ}
        : new StandardOpenOption[] {StandardOpenOption.READ, StandardOpenOption.WRITE};
    try (FileChannel channel = FileChannel.open(file, options);
        FileLock lock = channel.lock(0, Long.MAX_VALUE, shared)) {
      if (!lock.isValid()) throw new IllegalStateException("lock acquisition returned invalid lock");
      Files.writeString(ready, "locked", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      while (!Files.exists(release)) Thread.sleep(10);
    }
  }
}
