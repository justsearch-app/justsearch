/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.Mockito;

/** Executes the production early probe with a held harmless process seam; no GPU access. */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
class VramDetectorOwnedBootTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  @Test void earlyNvidiaSmiProbeKeepsItsOwnerReceiptUntilLedgerAttaches() throws Exception {
    var fixtureBuilder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName()).redirectErrorStream(true);
    if (io.justsearch.configuration.PlatformPaths.isLinux()) {
      var executable = directory.resolve(io.justsearch.configuration.NativeTargets.current().executableName("nvidia-smi"));
      java.nio.file.Files.writeString(executable, "#!/bin/sh\nprintf '12345\\n'\n");
      java.nio.file.Files.setPosixFilePermissions(executable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      fixtureBuilder.environment().put("PATH", directory.toString());
    }
    Process fixture = fixtureBuilder.start();
    var held = ProcessTerminator.forCurrentOs().open(fixture);
    Object birth = io.justsearch.configuration.PlatformPaths.isLinux()
        ? held.processIdentity() : held.creationFileTime().orElseThrow();
    try {
      assertTrue(fixture.waitFor(30, TimeUnit.SECONDS), "fixture " + fixture.pid() + "/" + birth);
      String output = new String(fixture.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, fixture.exitValue(), output);
      assertTrue(output.contains("owner.receipt=true"), output);
    } finally {
      if (fixture.isAlive()) held.terminate(1);
      held.close();
    }
  }
  public static class Probe {
    public static void main(String[] args) throws Exception {
      if (io.justsearch.configuration.PlatformPaths.isWindows()) {
        Process finite = new ProcessBuilder("cmd.exe", "/c", "echo 12345").start();
        try (var _ = Mockito.mockConstruction(ProcessBuilder.class, (builder, context) ->
            Mockito.when(builder.start()).thenReturn(finite))) {
          assertEquals(12345L * 1024 * 1024, new VramDetector().getTotalVramBytes());
        } finally { if (finite.isAlive()) finite.destroyForcibly(); }
      } else {
        assertEquals(12345L * 1024 * 1024, new VramDetector().getTotalVramBytes());
      }
      var records = new ArrayList<Map<String, Object>>();
      OwnedHelper.recordTo((type, fields) -> { Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
        record.put("type", type.token()); records.add(record); return true; },
          new EngineIdentity(ProcessHandle.current().pid(),
              ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "boot"));
      boolean receipt = records.stream().anyMatch(r -> "helper-exit".equals(r.get("type"))
          && "REQUESTED".equals(r.get("exitClass")) && r.get("reason").toString().contains("nvidia-smi"));
      System.out.println("owner.receipt=" + receipt);
      assertTrue(receipt, "early production nvidia-smi receipt absent");
    }
  }
}
