/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RuntimeDirectoryPermissionsTest {
  @TempDir Path data;
  @TempDir Path elsewhere;

  @Test void privateFromConstructionIncludingStartLogAndRecreation() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"));
    var mode = Files.getPosixFilePermissions(data);
    var runtime = data.resolve("runtime");
    try (var publisher = new RuntimeManifestPublisher(data)) {
      assertPrivate(runtime);
      try (var paths = Files.walk(runtime)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
      publisher.publishHead(54321, "test-token");
      assertPrivate(runtime);
      assertEquals(PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(runtime.resolve("manifest.json")));
    }
    assertEquals(mode, Files.getPosixFilePermissions(data));
  }

  @Test void repairsRestoredHistoryBeforeReadingOrPublishing() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime"));
    Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxrwxr-x"));
    var old = Files.createDirectories(runtime.resolve("instances/old")).resolve("manifest.log.ndjson");
    Files.writeString(old, "recognizable-old-token");
    Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rw-rw-r--"));
    try (var publisher = new RuntimeManifestPublisher(data)) {
      assertPrivate(runtime);
      publisher.publishHead(54321, "test-token");
      assertEquals("recognizable-old-token", Files.readString(old));
    }
  }

  @Test void linkedRuntimeStillPublishesWithoutChangingTargetOrParentPermissions() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var outside = elsewhere;
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwxrwxr-x"));
    var mode = Files.getPosixFilePermissions(outside);
    Files.createSymbolicLink(data.resolve("runtime"), outside);
    var parentMode = Files.getPosixFilePermissions(data);
    try (var publisher = new RuntimeManifestPublisher(data)) {
      publisher.publishHead(54321, "test-token");
      assertTrue(Files.isRegularFile(outside.resolve("manifest.json")));
    }
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(mode, Files.getPosixFilePermissions(outside));
    assertTrue(Files.isSymbolicLink(data.resolve("runtime")));
  }

  @Test void missingDataDirectoryStillBootstrapsWithoutChangingItsParent() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"));
    var reference = Files.createDirectory(data.resolve("reference"));
    var missing = data.resolve("new-data");
    try (var publisher = new RuntimeManifestPublisher(missing)) {
      publisher.publishHead(54321, "test-token");
      assertPrivate(missing.resolve("runtime"));
      assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(missing));
      assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(data));
    }
  }

  @Test void directoryReparsePointStillPublishesAndWarnsOnceWithoutChangingPermissions() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime")).toAbsolutePath().normalize();
    Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxrwxr-x"));
    var parentMode = Files.getPosixFilePermissions(data);
    // Windows reports a directory junction as both a directory and "other", not a symbolic link.
    var attributes = Mockito.mock(BasicFileAttributes.class);
    Mockito.when(attributes.isDirectory()).thenReturn(true);
    Mockito.when(attributes.isOther()).thenReturn(true);
    var warning = Mockito.mock(Logger.class);
    try (var files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
         var logging = Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.readAttributes(runtime, BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS)).thenReturn(attributes);
      logging.when(() -> LoggerFactory.getLogger(Mockito.<Class<?>>argThat(type -> type != null
          && type.getName().equals("io.justsearch.configuration.persistence.OwnerOnlyDirectory"))))
          .thenReturn(warning);
      try (var publisher = new RuntimeManifestPublisher(data)) {
        publisher.publishHead(54321, "test-token");
        assertTrue(Files.isRegularFile(runtime.resolve("manifest.json")));
      }
      assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(runtime));
      assertEquals(parentMode, Files.getPosixFilePermissions(data));
      Mockito.verify(warning, Mockito.times(1)).warn(Mockito.startsWith("Skipping owner-only directory repair"));
    }
  }

  @Test void failedPermissionSetterWarnsOnceAndDoesNotPreventPublishing() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime")).toAbsolutePath().normalize();
    var marker = Files.writeString(runtime.resolve("old-marker"), "do-not-delete");
    var permissions = Mockito.mock(PosixFileAttributeView.class);
    Mockito.doThrow(new AccessDeniedException(runtime.toString()))
        .when(permissions).setPermissions(Mockito.anySet());
    var warning = Mockito.mock(Logger.class);
    try (var files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
         var logging = Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.getFileAttributeView(runtime, PosixFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS)).thenReturn(permissions);
      logging.when(() -> LoggerFactory.getLogger(Mockito.<Class<?>>argThat(type -> type != null
          && type.getName().equals("io.justsearch.configuration.persistence.OwnerOnlyDirectory"))))
          .thenReturn(warning);
      try (var publisher = new RuntimeManifestPublisher(data)) {
        publisher.publishHead(54321, "warning-test-token");
        publisher.publishWorkerReady("index", Instant.now());
        assertTrue(Files.isRegularFile(runtime.resolve("manifest.json")));
      }
      assertEquals("do-not-delete", Files.readString(marker));
      Mockito.verify(warning, Mockito.times(1)).warn(Mockito.startsWith("Could not protect owner-only directory"));
    }
  }

  private static void assertPrivate(Path runtime) throws Exception {
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(runtime));
  }
}
