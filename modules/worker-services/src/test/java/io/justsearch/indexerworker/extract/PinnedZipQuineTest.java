/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PinnedZipQuineTest {
  @TempDir Path temporary;

  @Test
  void pinnedSelfReproducingZipRefusesThroughRealChildAndNextFileSucceeds() throws Exception {
    // Russ Cox's 440-byte ZIP quine: https://swtch.com/r.zip
    // Construction/source: https://research.swtch.com/zip (2010-03-18).
    byte[] bytes = Base64.getDecoder().decode(
        "UEsDBBQAAAAIAAgDZDz59IlkSAEAALgBAAAHAAAAci9yLnppcAAlANr/UEsDBBQAAAAIAAgDZDz59IlkSAEAALgBAAAHAAAA"
        + "ci9yLnppcAAvAND/ACUA2v9QSwMEFAAAAAgACANkPPn0iWRIAQAAuAEAAAcAAAByL3IuemlwAC8A0P/CVI5XOQAFAPr/wlSO"
        + "VzkABQD6/wAFAPr/ABQA6//CVI5XOQAFAPr/AAUA+v8AFADr/0KIIcQAABQA6/9CiCHEAAAUAOv/QoghxAAAFADr/0KIIcQA"
        + "ABQA6/9CiCHEAAAAAP//AAAA//8ANADL/0KIIcQAAAAA//8AAAD//wA0AMv/QughXg8AAAD//wrwZmQSYcAV3OigSL9Iryqz"
        + "IMCblQ3EZwRCUwYGBkAABgD5/20BAAAAAELoIV4PAAAA//8K8GZkEmHAFdzooEi/SK8qsyDAm5UNxGcEQlMGBgZAAAYA+f9t"
        + "AQAAAABQSwECFAAUAAAACAAIA2Q8+fSJZEgBAAC4AQAABwAAAAAAAAAAAAAAAAAAAAAAci9yLnppcFBLBQYAAAAAAQABADUA"
        + "AABtAQAAAAA=");
    assertEquals("d152d368ca2da00de987cfe58c868534410ab270773d59d0d7956e671d3adafd",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    Path quine = Files.write(temporary.resolve("r.zip"), bytes);
    try (var zip = new ZipFile(quine.toFile()); var input = zip.getInputStream(zip.getEntry("r/r.zip"))) {
      assertArrayEquals(bytes, input.readAllBytes(), "The pinned fixture must actually reproduce itself");
    }
    var policy = TikaExtractionPolicy.defaults();
    try (var child = new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        ExtractionSandboxCommand.defaultCommand(policy, "256m"), policy,
        OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 500, null)) {
      var failure = assertThrows(ContentExtractor.BudgetExceededException.class,
          () -> child.extract(quine));
      assertEquals("EMBEDDED_DEPTH_LIMIT", failure.reasonCode());
      Path normal = Files.writeString(temporary.resolve("normal.txt"), "normalquokkatoken");
      assertTrue(child.extract(normal).result().content().contains("normalquokkatoken"));
    }
  }
}
