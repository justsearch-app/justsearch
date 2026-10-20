/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyDirectoryTest {
  @TempDir Path data;
  @TempDir Path elsewhere;

  @Test void freshAndRestoredDirectoryArePrivateWithoutChangingParentOrOldFiles() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"));
    var parentMode = Files.getPosixFilePermissions(data);
    var runtime = data.resolve("runtime");
    OwnerOnlyDirectory.ensureOwnerOnlyDirectory(runtime);
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(runtime));
    var history = Files.writeString(runtime.resolve("old.json"), "recognizable-old-token");
    Files.setPosixFilePermissions(history, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxrwxr-x"));
    OwnerOnlyDirectory.ensureOwnerOnlyDirectory(runtime);
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(runtime));
    assertEquals("recognizable-old-token", Files.readString(history));
    assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(history));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
  }

  @Test void permissionFailureWarnsOnceAndPreservesContents() throws Exception {
    var runtime = Files.createDirectory(data.resolve("runtime"));
    var old = Files.writeString(runtime.resolve("history"), "old");
    var warnings = new ArrayList<String>();
    OwnerOnlyDirectory.Permissions denied = path -> { throw new AccessDeniedException(path.toString()); };
    OwnerOnlyDirectory.ensure(runtime, denied, warnings::add);
    OwnerOnlyDirectory.ensure(runtime, denied, warnings::add);
    assertEquals(1, warnings.size());
    assertEquals("old", Files.readString(old));
  }

  @Test void unsupportedPermissionsWarnAndContinue() throws Exception {
    var warnings = new ArrayList<String>();
    var runtime = data.resolve("runtime");
    OwnerOnlyDirectory.ensure(runtime, path -> { throw new UnsupportedOperationException("no modes"); }, warnings::add);
    assertTrue(Files.isDirectory(runtime));
    assertEquals(1, warnings.size());
  }

  @Test void linkWarnsOnceAndContinuesWithoutCallingPermissionSetterOrChangingTarget() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var outside = elsewhere;
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwxrwxr-x"));
    var runtime = Files.createSymbolicLink(data.resolve("runtime"), outside);
    var warnings = new ArrayList<String>();
    var parentMode = Files.getPosixFilePermissions(data);
    for (int i = 0; i < 2; i++) {
      assertDoesNotThrow(() -> OwnerOnlyDirectory.ensure(runtime,
          path -> fail("must not repair a link"), warnings::add));
    }
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(1, warnings.size());
    assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(outside));
    assertTrue(Files.isSymbolicLink(runtime));
  }
}
