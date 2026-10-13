/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.runtime.child.ProcessTerminator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Standalone JDK fixture: records the leaf at spawn, and both processes exit via private files. */
public final class SessionWrapperFixture {
  private SessionWrapperFixture() {}

  static List<String> argv(String mode, Path dir) {
    String classpath;
    try {
      classpath = Path.of(SessionWrapperFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          + java.io.File.pathSeparator
          + Path.of(ProcessTerminator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (java.net.URISyntaxException invalid) { throw new IllegalStateException(invalid); }
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
        SessionWrapperFixture.class.getName(), mode, dir.toString());
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[1]);
    if ("wrapper".equals(args[0])) {
      Process leaf = new ProcessBuilder(argv("leaf", dir)).start();
      ProcessTerminator.OsProcess owned = ProcessTerminator.forCurrentOs().open(leaf);
      try {
        Files.writeString(dir.resolve("leaf.identity"), leaf.pid() + "/" + owned.creationFileTime().orElse("unknown"));
        while (!Files.exists(dir.resolve("wrapper.exit"))) Thread.sleep(10);
      } finally { owned.close(); }
    } else {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
      while (!Files.exists(dir.resolve("leaf.exit")) && System.nanoTime() < deadline) Thread.sleep(10);
    }
  }
}
