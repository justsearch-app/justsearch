/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Within the owner's "go with items 1 and 2" delegation for the security fix, the orchestrator
 * chose S6: "The removal and later protected skips are visible: the log names the count removed
 * and the skip reason distinguishes protected files from ordinary temp/system skips."
 * P1: "Ordinary files (not protected, not below a dot-directory) are indexed and searchable as
 * before on every arm; `.gitignore` stays exempt."
 * These checks cover the admission part of S6 and the .gitignore part of P1.
 */
final class WorkerProtectedAdmissionReasonTest {
  @TempDir Path root;

  @Test
  void protectedEnvHasDistinctSkipReason() throws Exception {
    Path env = Files.writeString(root.resolve(".env"), "env");
    var authority = new WorkerIngestionAuthority();
    var admission = authority.admit(env, root);
    assertNotNull(admission.outcome(), "protected env must carry a protected skip outcome");
    assertEquals("SKIPPED_PROTECTED", admission.outcome().reasonCode());
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(env, root).action());
    Path system = Files.writeString(root.resolve("Thumbs.db"), "system");
    assertEquals("SKIPPED_TEMP_OR_SYSTEM", authority.admit(system, root).outcome().reasonCode());
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(system, root).action());
  }

  @Test
  void ordinaryGitignoreRemainsAdmitted() throws Exception {
    Path gitignore = Files.writeString(root.resolve(".gitignore"), "ignored.txt");
    assertEquals(SourceAdmissionAction.ADMIT,
        new WorkerIngestionAuthority().admit(gitignore, root).action());
  }
}
