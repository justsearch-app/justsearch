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

  @Test void freshAndRestoredDirectoryArePrivateWithoutChangingParentOrOldContents() throws Exception {
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
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(history));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
  }

  @Test void repairsNestedFilesAndDirectoriesButNeverLinkTargets() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime"));
    var nested = Files.createDirectory(runtime.resolve("instances"));
    var history = Files.writeString(nested.resolve("history"), "old-token");
    var pending = Files.writeString(runtime.resolve("ledger.pending"), "pending");
    for (var directory : java.util.List.of(runtime, nested, elsewhere)) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxr-x"));
    }
    for (var file : java.util.List.of(history, pending)) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    }
    var outside = Files.writeString(elsewhere.resolve("outside"), "keep");
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.createSymbolicLink(runtime.resolve("linked"), elsewhere);
    var parentMode = Files.getPosixFilePermissions(data);
    OwnerOnlyDirectory.ensureOwnerOnlyDirectory(runtime);
    for (var directory : java.util.List.of(runtime, nested)) {
      assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
    }
    for (var file : java.util.List.of(history, pending)) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
    assertEquals("old-token", Files.readString(history));
    assertEquals("pending", Files.readString(pending));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(elsewhere));
    assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(outside));
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

  @Test void deniedChildRepairsWarnOnceForTheFolderAndPreserveEveryFile() throws Exception {
    var runtime = Files.createDirectory(data.resolve("runtime"));
    var first = Files.writeString(runtime.resolve("first"), "first");
    var second = Files.writeString(runtime.resolve("second"), "second");
    var warnings = new ArrayList<String>();
    OwnerOnlyDirectory.Permissions deniedChildren = path -> {
      if (!path.equals(runtime.toAbsolutePath().normalize())) throw new AccessDeniedException(path.toString());
    };
    OwnerOnlyDirectory.ensure(runtime, deniedChildren, warnings::add);
    OwnerOnlyDirectory.ensure(runtime, deniedChildren, warnings::add);
    assertEquals(1, warnings.size());
    assertEquals("first", Files.readString(first));
    assertEquals("second", Files.readString(second));
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
