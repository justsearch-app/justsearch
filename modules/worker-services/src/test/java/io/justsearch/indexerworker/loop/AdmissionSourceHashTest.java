/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AdmissionSourceHashTest {
  @TempDir Path temporary;

  @Test
  void cloudPlaceholdersAreDeferredBeforeAnyContentRead() throws Exception {
    Path file = Files.writeString(temporary.resolve("cloud.txt"), "placeholder");
    try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      hashes.when(() -> SourceContentHash.isCloudPlaceholder(file)).thenReturn(true);
      assertNull(SourceContentHash.admissionIdentity(file, temporary));
      var admission = new WorkerIngestionAuthority().admit(file, temporary);
      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
      assertEquals(IngestionReasonCodes.CLOUD_PLACEHOLDER, admission.outcome().reasonCode());
      hashes.verify(() -> SourceContentHash.sha256(file), never());
    }
  }

  @Test
  void excludedFilesAndLinksProvideNoSourceProof() throws Exception {
    Path excluded = Files.writeString(temporary.resolve(".hidden.txt"), "excluded");
    Path file = Files.writeString(temporary.resolve("regular.txt"), "regular");
    assertNull(SourceContentHash.admissionIdentity(excluded, temporary));
    assertNull(SourceContentHash.admissionIdentity(file, null));
    assertEquals(SourceContentHash.sha256(file), SourceContentHash.admissionIdentity(file, temporary));
  }
}
