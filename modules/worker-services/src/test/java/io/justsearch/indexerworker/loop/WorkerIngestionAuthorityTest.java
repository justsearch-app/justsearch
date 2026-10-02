/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerIngestionAuthorityTest {
  @TempDir Path tempDir;

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void queuedAdmissionRechecksExcludedAncestorsAfterPolicyChange() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("notes.txt"),
        "private content");
    var authority = new WorkerIngestionAuthority();
    authority.setRootResolver(ignored -> root);
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(file).action());
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(file).action());
    assertEquals(SourceAdmissionAction.ADMIT,
        authority.admit(Files.writeString(root.resolve("public.txt"), "public")).action());
  }
}
