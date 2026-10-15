/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S7 — an unreadable source whose content is in the index is
 * reported as "indexed, source unreadable", distinct from one that was never indexed and from a
 * missing file. Uses a real sharing violation (a handle opened without sharing).
 */
final class IndexedSourceUnreadableTest {
  @TempDir Path tempDir;

  @Test
  void aLockedSourceWithIndexedContentIsReportedAsIndexedSourceUnreadable() throws Exception {
    assumeTrue(
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
        "share modes are a Windows file-system feature");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path indexed = Files.writeString(root.resolve("indexed.docx"), "indexed earlier");
    Path neverIndexed = Files.writeString(root.resolve("new.docx"), "never indexed");
    WorkerIngestionAuthority authority = new WorkerIngestionAuthority();
    authority.useIndexedSourceProbe(indexed::equals);

    try (FileChannel ignoredA = lock(indexed); FileChannel ignoredB = lock(neverIndexed)) {
      SourceAdmission kept = authority.admit(rooted(indexed, root));
      SourceAdmission fresh = authority.admit(rooted(neverIndexed, root));

      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, kept.action());
      assertEquals(IngestionReasonCodes.INDEXED_SOURCE_UNREADABLE, kept.outcome().reasonCode());
      assertEquals(IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE, kept.outcome().retryPolicy());
      assertEquals(IngestionReasonCodes.SOURCE_UNREADABLE, fresh.outcome().reasonCode());
    }
  }

  private static FileChannel lock(Path file) throws java.io.IOException {
    return FileChannel.open(
        file,
        StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
  }

  private static JobQueue.IndexJob rooted(Path file, Path root) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
  }
}
