/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExitLedgerDirectoryPermissionsTest {
  @TempDir Path data;

  @Test void engineFirstAppendAndRecreationProtectRuntime() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"));
    var parent = Files.getPosixFilePermissions(data);
    var missing = data.resolve("new-data");
    var dir = LedgerFamily.directory(missing);
    var writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN,
        new LedgerWriterId(LedgerWriterId.Role.ENGINE, 4242L, Instant.now()), Clock.systemUTC());
    assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "first")));
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(missing.resolve("runtime")));
    assertEquals(1, ExitLedgerReader.read(dir, LedgerFamily.CHILDREN).records().size());
    try (var paths = Files.walk(missing.resolve("runtime"))) {
      for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
    }
    assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "recreated")));
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(missing.resolve("runtime")));
    assertEquals(parent, Files.getPosixFilePermissions(data));
  }
}
