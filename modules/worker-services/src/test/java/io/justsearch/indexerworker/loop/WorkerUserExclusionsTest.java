/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerUserExclusionsTest {
  @Test
  void AX1QueuedWorkUsesCurrentPolicyAndEN1BuiltInSkipsRemain() throws Exception {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
      Path file = Files.writeString(root.resolve("private.txt"), "private");
      Path publicFile = Files.writeString(root.resolve("public.txt"), "public");
      Path system = Files.writeString(root.resolve("Thumbs.db"), "system");
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
      var authority = new WorkerIngestionAuthority();
      ConfigStore previous = ConfigStore.globalOrNull();
      ConfigStore installed = new ConfigStore(config("[]"));
      ConfigStore.setGlobal(installed);
      try {
        assertEquals(SourceAdmissionAction.ADMIT, authority.admit(claim).action());
        installed.update(config("[\"[legacy\",\"{broken\",\"private.txt\"]"));
        assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(claim).action(),
            "EX2: a queued file must obey policy saved after enqueue");
        assertEquals(SourceAdmissionAction.ADMIT, authority.admit(publicFile, root).action());
        assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(system, root).action());
      } finally {
        ConfigStore.restoreGlobal(installed, previous);
      }
    }, "AX1: invalid saved entries must never stop this admission path");
  }

  @TempDir Path root;

  @Test
  void EX2QueuedWorkUsesCurrentPolicyAndEN1BuiltInSkipsRemain() throws Exception {
    Path file = Files.writeString(root.resolve("private.txt"), "private");
    Path publicFile = Files.writeString(root.resolve("public.txt"), "public");
    Path system = Files.writeString(root.resolve("Thumbs.db"), "system");
    var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
    var authority = new WorkerIngestionAuthority();
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(config("[]"));
    ConfigStore.setGlobal(installed);
    try {
      assertEquals(SourceAdmissionAction.ADMIT, authority.admit(claim).action());
      installed.update(config("[\"private.txt\"]"));
      assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(claim).action(),
          "EX2: a queued file must obey policy saved after enqueue");
      assertEquals(SourceAdmissionAction.ADMIT, authority.admit(publicFile, root).action());
      assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(system, root).action());
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX3RemovingPatternReadmitsWithoutRestart() throws Exception {
    Path file = Files.writeString(root.resolve("private.txt"), "private");
    var authority = new WorkerIngestionAuthority();
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(config("[\"private.txt\"]"));
    ConfigStore.setGlobal(installed);
    try {
      assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(file, root).action(),
          "EX3: the saved pattern initially excludes the file");
      installed.update(config("[]"));
      assertEquals(SourceAdmissionAction.ADMIT, authority.admit(file, root).action(),
          "EX3: removing policy is visible on the next admission");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EN1BuiltInSkipsAndExplicitExemptionsRemain() throws Exception {
    Path system = Files.writeString(root.resolve("Thumbs.db"), "system");
    Path compiled = Files.writeString(root.resolve("module.pyc"), "compiled");
    Path git = Files.writeString(Files.createDirectory(root.resolve(".git")).resolve("notes.txt"), "git");
    Path env = Files.writeString(root.resolve(".env"), "env");
    var authority = new WorkerIngestionAuthority();
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(system, root).action());
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(compiled, root).action());
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(git, root).action());
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(env, root).action());
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns", patterns).build();
  }
}
