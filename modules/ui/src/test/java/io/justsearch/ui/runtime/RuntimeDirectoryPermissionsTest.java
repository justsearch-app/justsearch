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
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(old));
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
    try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data))) {
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
      try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data))) {
        publisher.publishHead(54321, "test-token");
        assertTrue(Files.isRegularFile(runtime.resolve("manifest.json")));
      }
      assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(runtime));
      assertEquals(parentMode, Files.getPosixFilePermissions(data));
      assertDoesNotThrow(() -> Mockito.verify(warning, Mockito.times(1))
          .warn(Mockito.startsWith("Skipping owner-only directory repair")));
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
      assertDoesNotThrow(() -> Mockito.verify(warning, Mockito.times(1))
          .warn(Mockito.startsWith("Could not protect owner-only directory")));
    }
  }

  @Test void repairsExistingDescendantsWhenRootIsAlreadyPrivate() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime"));
    Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
    var nested = Files.createDirectory(runtime.resolve("instances"));
    var history = Files.writeString(nested.resolve("old-history"), "old-token");
    var pending = Files.writeString(runtime.resolve("ledger.pending"), "pending");
    Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("rwxrwxr-x"));
    for (var file : java.util.List.of(history, pending)) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    }
    Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxrwxr-x"));
    var outside = Files.writeString(elsewhere.resolve("outside"), "keep");
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.createSymbolicLink(runtime.resolve("linked"), elsewhere);
    var parentMode = Files.getPosixFilePermissions(data);
    try (var publisher = new RuntimeManifestPublisher(data)) {
      assertAll(
          () -> assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(nested)),
          () -> assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(history)),
          () -> assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(pending)));
      publisher.publishHead(54321, "test-token");
    }
    assertEquals("old-token", Files.readString(history));
    assertEquals("pending", Files.readString(pending));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(PosixFilePermissions.fromString("rwxrwxr-x"), Files.getPosixFilePermissions(elsewhere));
    assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(outside));
  }

  @Test void windowsRepairIncludesExistingFilesAndSkipsEveryReparsePoint() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime")).toAbsolutePath().normalize();
    var nested = Files.createDirectories(runtime.resolve("instances/old"));
    var history = Files.writeString(nested.resolve("manifest.log.ndjson"), "old-token");
    var pending = Files.writeString(runtime.resolve("ledger.pending"), "pending");
    var keystore = Files.writeString(runtime.resolve("keystore.json"), "old-keystore");
    var junction = Files.createDirectory(runtime.resolve("junction"));
    var skipped = Files.writeString(junction.resolve("must-not-repair"), "keep");
    var outside = Files.writeString(elsewhere.resolve("outside-history"), "outside");
    var outsideMode = Files.getPosixFilePermissions(outside);
    var link = Files.createSymbolicLink(runtime.resolve("symlink"), elsewhere);
    var parentMode = Files.getPosixFilePermissions(data);
    var targetMode = Files.getPosixFilePermissions(elsewhere);
    var reparse = Mockito.mock(BasicFileAttributes.class);
    Mockito.when(reparse.isDirectory()).thenReturn(true);
    Mockito.when(reparse.isOther()).thenReturn(true);
    var acl = Mockito.mock(java.nio.file.attribute.AclFileAttributeView.class);
    var repaired = new java.util.HashSet<Path>();
    var warning = Mockito.mock(Logger.class);
    // Compatible harness: the before-state has no native backend class. It still executes
    // the real RuntimeManifestPublisher; an empty repair log then fails the same file assertions.
    Class<?> backend;
    try { backend = Class.forName("io.justsearch.configuration.persistence.WindowsOwnerOnlyDirectory"); }
    catch (ClassNotFoundException beforeState) { backend = null; }
    try (var nativeSecurity = backend == null ? null : Mockito.mockStatic(backend, invocation -> {
           if (invocation.getMethod().getName().equals("apply")) {
             repaired.add(invocation.getArgument(0));
             assertFalse((Boolean) invocation.getArgument(1), "existing entries must be repaired");
             return null;
           }
           return Mockito.CALLS_REAL_METHODS.answer(invocation);
         });
         var files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
         var logging = Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.getFileAttributeView(Mockito.any(Path.class),
          Mockito.eq(PosixFileAttributeView.class), Mockito.eq(LinkOption.NOFOLLOW_LINKS))).thenReturn(null);
      files.when(() -> Files.getFileAttributeView(Mockito.any(Path.class),
          Mockito.eq(java.nio.file.attribute.AclFileAttributeView.class),
          Mockito.eq(LinkOption.NOFOLLOW_LINKS))).thenReturn(acl);
      files.when(() -> Files.readAttributes(junction, BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS)).thenReturn(reparse);
      logging.when(() -> LoggerFactory.getLogger(Mockito.<Class<?>>argThat(type -> type != null
          && type.getName().equals("io.justsearch.configuration.persistence.OwnerOnlyDirectory"))))
          .thenReturn(warning);
      try (var publisher = new RuntimeManifestPublisher(data)) {
        publisher.publishHead(54321, "test-token");
      }
      assertTrue(repaired.containsAll(java.util.Set.of(runtime, runtime.resolve("instances"), nested, history, pending, keystore)),
          "every existing directory, history file and pending file needs the protected DACL");
      assertFalse(repaired.stream().anyMatch(path -> path.startsWith(link)));
      assertTrue(repaired.stream().allMatch(path -> path.startsWith(runtime)), "repair must stay inside the protected root");
      assertFalse(repaired.stream().anyMatch(path -> path.startsWith(junction)));
      assertFalse(repaired.contains(elsewhere));
      Mockito.verify(warning, Mockito.times(2)).warn(Mockito.startsWith("Skipping owner-only directory repair"));
    }
    assertEquals("old-token", Files.readString(history));
    assertEquals("keep", Files.readString(skipped));
    assertEquals("outside", Files.readString(outside));
    assertEquals(outsideMode, Files.getPosixFilePermissions(outside));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(targetMode, Files.getPosixFilePermissions(elsewhere));
  }

  @Test void hardLinkedHistoryKeepsOutsidePermissionsAndWarnsOnceWhilePublishing() throws Exception {
    assumeTrue(data.getFileSystem().supportedFileAttributeViews().contains("unix"));
    var runtime = Files.createDirectory(data.resolve("runtime"));
    var outside = Files.writeString(elsewhere.resolve("history.txt"), "restored-history");
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-rw-r--"));
    var link = Files.createLink(runtime.resolve("x"), outside);
    var parentMode = Files.getPosixFilePermissions(data);
    var targetMode = Files.getPosixFilePermissions(elsewhere);
    var warning = Mockito.mock(Logger.class);
    try (var logging = Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
      logging.when(() -> LoggerFactory.getLogger(Mockito.<Class<?>>argThat(type -> type != null
          && type.getName().equals("io.justsearch.configuration.persistence.OwnerOnlyDirectory"))))
          .thenReturn(warning);
      try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data))) {
        publisher.publishHead(54321, "test-token");
        publisher.publishWorkerReady("index", Instant.now());
        assertPrivate(runtime);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(runtime.resolve("manifest.json")));
      }
      assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(outside));
      Mockito.verify(warning, Mockito.times(1)).warn(Mockito.startsWith("Skipping owner-only directory repair on a hard-linked file"));
    }
    assertTrue(Files.isSameFile(link, outside));
    assertEquals("restored-history", Files.readString(outside));
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(targetMode, Files.getPosixFilePermissions(elsewhere));
  }

  @Test void failingDirectoryIteratorWarnsOnceAndStartupContinuesWithoutDeletingFiles() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var runtime = Files.createDirectory(data.resolve("runtime")).toAbsolutePath().normalize();
    var marker = Files.writeString(runtime.resolve("old-history"), "keep");
    var warning = Mockito.mock(Logger.class);
    var stream = new DirectoryStream<Path>() {
      @Override public java.util.Iterator<Path> iterator() {
        return new java.util.Iterator<>() {
          @Override public boolean hasNext() {
            throw new DirectoryIteratorException(new java.io.IOException("injected iteration failure"));
          }
          @Override public Path next() { throw new java.util.NoSuchElementException(); }
        };
      }
      @Override public void close() {}
    };
    try (var files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
         var logging = Mockito.mockStatic(LoggerFactory.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.newDirectoryStream(runtime)).thenReturn(stream);
      logging.when(() -> LoggerFactory.getLogger(Mockito.<Class<?>>argThat(type -> type != null
          && type.getName().equals("io.justsearch.configuration.persistence.OwnerOnlyDirectory"))))
          .thenReturn(warning);
      try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data))) {
        assertDoesNotThrow(() -> publisher.publishHead(54321, "test-token"));
        assertTrue(Files.isRegularFile(runtime.resolve("manifest.json")));
      }
      Mockito.verify(warning, Mockito.times(1)).warn(Mockito.contains("injected iteration failure"));
    }
    assertEquals("keep", Files.readString(marker));
  }

  private static void assertPrivate(Path runtime) throws Exception {
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(runtime));
  }
}
