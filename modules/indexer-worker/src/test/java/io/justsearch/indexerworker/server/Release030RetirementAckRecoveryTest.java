/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real child death across verified replay/SQL ACK; each restart checks search and both pointers. */
@Timeout(600)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementAckRecoveryTest {
  @ParameterizedTest(name="acknowledgedHeadResumes({0})")
  @ValueSource(strings={"migrating","switching"})
  void acknowledgedHeadResumes(String fixture,@TempDir Path temporary) {
    run("release030-journal-acknowledged",fixture,temporary);
  }

  @Test
  void committedBeforeAckResumes(@TempDir Path temporary) {
    run("release030-replay-committed","switching",temporary);
  }

  private static void run(String cut,String fixture,Path temporary) {
    try {
      assertDoesNotThrow(() -> new Release030RetirementCrashTest()
          .killedRetirementResumesAndBackupCannotReviveBuild(cut,fixture,temporary),
          "Verified retirement replay must resume after a real process kill at " + cut);
    } finally {
      // Preserve complete child output even when startup/assertions fail before JUnit cleanup.
      try (var paths=Files.walk(temporary)) {
        for (Path log:paths.filter(path->path.getFileName().toString().equals("child.log")).toList()) {
          String output=Files.readString(log);
          System.out.println("RETIREMENT_ACK_CHILD "+cut+" "+fixture+"\n"+output);
          String evidence=System.getenv("VERIFY_EVIDENCE");
          if(evidence!=null) {
            Path saved=Path.of(evidence).resolve("child-"+cut+"-"+fixture+".log");
            for(int serial=2;Files.exists(saved);serial++)saved=Path.of(evidence).resolve("child-"+cut+"-"+fixture+"-"+serial+".log");
            Files.copy(log,saved);
          }
        }
      } catch(Exception diagnosticFailure) {
        System.err.println("Retirement child capture failed: "+diagnosticFailure);
      }
    }
  }
}
