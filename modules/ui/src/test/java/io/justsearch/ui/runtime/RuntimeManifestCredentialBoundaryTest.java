/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeManifestCredentialBoundaryTest {
  @TempDir Path dir;
  @Test
  void everyPublishReplacesReadableManifestAndLeftoverTempWithOwnerOnlyFile() throws Exception {
    assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectories(dir.resolve("runtime"));
    var manifest = runtime.resolve("manifest.json");
    var temp = runtime.resolve("manifest.json.tmp");
    try (var publisher = new RuntimeManifestPublisher(dir)) {
      for (int publish = 0; publish < 3; publish++) {
        for (var path : java.util.List.of(manifest, temp)) {
          Files.writeString(path, "{}");
          Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-r--"));
        }
        if (publish == 0) publisher.publishHead(54321, "private-boot-token");
        else publisher.publishWorkerReady("index-" + publish, java.time.Instant.now());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(manifest));
        assertFalse(Files.exists(temp));
        assertTrue(Files.readString(manifest).contains("private-boot-token"));
      }
    }
  }
  @Test
  void newInstanceHistoryContainsNoCredentialAcrossPublishes() throws Exception {
    try (var publisher = new RuntimeManifestPublisher(dir)) {
      publisher.publishHead(54321, "private-boot-token");
      publisher.publishWorkerReady("index", java.time.Instant.now());
      var history = publisher.instancesRoot().resolve(publisher.instanceId());
      for (String name : java.util.List.of("manifest.json", "manifest.log.ndjson")) {
        String content = Files.readString(history.resolve(name));
        assertFalse(content.contains("private-boot-token"), name);
        assertFalse(content.contains("sessionToken"), name);
        assertTrue(content.contains(publisher.instanceId()), name);
      }
    }
  }
}
