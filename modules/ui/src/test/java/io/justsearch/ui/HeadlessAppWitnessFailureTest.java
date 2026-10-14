/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppWitnessFailureTest {
  @Test void aWitnessFailureInMainFinallyStillReachesTheNormalFatalExit(@TempDir Path dir) throws Exception {
    Path args = dir.resolve("java.args"), log = dir.resolve("probe.log");
    String classpath = System.getProperty("java.class.path").replace('\\', '/');
    String agent = Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString().replace('\\', '/');
    Files.writeString(args, "--enable-native-access=ALL-UNNAMED\n-javaagent:\"" + agent
        + "\"\n-cp \"" + classpath + "\"\n" + HeadlessAppWitnessFailureProbe.class.getName()
        + "\n\"" + dir.toString().replace('\\', '/') + "\"\n");
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "@" + args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    var os = io.justsearch.app.api.runtime.child.ProcessTerminator.forCurrentOs();
    var held = os.open(child); String birth = held.creationFileTime().orElseThrow();
    System.out.println("fixture-start=" + child.pid() + "/" + birth);
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "the fatal path is bounded");
      assertEquals(io.justsearch.app.engine.EngineExit.FATAL_OR_UNCAUGHT, child.exitValue(), Files.readString(log));
      assertTrue(Files.exists(dir.resolve("normal-exit")),
          "main finally must catch the witness refusal and reach System.exit, rather than its uncaught halt: " + Files.readString(log));
      assertTrue(Files.readString(log).contains("fixture witness storage still active"), "the unclean reason remains visible");
    } finally {
      if (child.isAlive()) {
        assertEquals(birth, held.creationFileTime().orElseThrow()); held.terminate(os.ourCode());
      }
      assertTrue(held.awaitExit(Duration.ofSeconds(5))); held.close();
    }
  }
}
