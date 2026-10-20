/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/**
 * Protects a directory and its existing entries, without changing its parent or following links.
 * Links and reparse points are left untouched, with one warning per path.
 */
public final class OwnerOnlyDirectory {
  private static final Set<Path> WARNED = ConcurrentHashMap.newKeySet();
  private static final Set<PosixFilePermission> MODE = PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

  private OwnerOnlyDirectory() {}

  public static void ensureOwnerOnlyDirectory(Path directory) throws IOException {
    ensure(directory, OwnerOnlyDirectory::restrict,
        message -> LoggerFactory.getLogger(OwnerOnlyDirectory.class).warn(message));
  }

  @FunctionalInterface
  interface Permissions {
    void restrict(Path directory) throws IOException;
  }

  static void ensure(Path directory, Permissions permissions, Consumer<String> warning) throws IOException {
    Path path = directory.toAbsolutePath().normalize();
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      try {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
          Files.createDirectory(path, PosixFilePermissions.asFileAttribute(MODE));
        } else if (path.getFileSystem().supportedFileAttributeViews().contains("acl")) {
          WindowsOwnerOnlyDirectory.apply(path, true);
        } else {
          Files.createDirectory(path);
        }
      } catch (FileAlreadyExistsException raced) {
        // Validate the winner below, without following a link.
      } catch (UnsupportedOperationException | IOException unsupported) {
        Files.createDirectory(path);
      }
    }
    var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || (attributes.isDirectory() && attributes.isOther())) {
      warnOnce(path, "Skipping owner-only directory repair on a link or reparse point: " + path, warning);
      return;
    }
    if (!attributes.isDirectory()) {
      var failure = new IOException("Refusing owner-only directory repair on a non-directory: " + path);
      warnOnce(path, failure.toString(), warning);
      throw failure;
    }
    repair(path, path, permissions, warning);
  }

  private static void repair(Path root, Path path, Permissions permissions, Consumer<String> warning) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther()) {
        warnOnce(path, "Skipping owner-only directory repair on a link or reparse point: " + path, warning);
        return;
      }
      if (attributes.isRegularFile() && path.getFileSystem().supportedFileAttributeViews().contains("unix")
          && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() > 1) {
        warnOnce(path, "Skipping owner-only directory repair on a hard-linked file: " + path, warning);
        return;
      }
      // Protect a real directory before enumerating it. No traversal is attempted on a link.
      permissions.restrict(path);
      if (attributes.isDirectory()) {
        // Recheck after the setter: it may have encountered a concurrent replacement.
        attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
          warnOnce(path, "Skipping owner-only directory repair on a link or reparse point: " + path, warning);
          return;
        }
        if (attributes.isDirectory()) {
          try (var children = Files.newDirectoryStream(path)) {
            for (Path child : children) repair(root, child, permissions, warning);
          }
        }
      }
    } catch (IOException | DirectoryIteratorException | UnsupportedOperationException | SecurityException failure) {
      warnOnce(root, "Could not protect owner-only directory " + path + ": " + failure, warning);
    }
  }

  private static void warnOnce(Path path, String message, Consumer<String> warning) {
    if (WARNED.add(path)) warning.accept(message);
  }

  private static void restrict(Path path) throws IOException {
    var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      posix.setPermissions(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? MODE : FILE_MODE);
      return;
    }
    if (Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) {
      throw new IOException("No owner-only directory permissions available");
    }
    WindowsOwnerOnlyDirectory.apply(path, false);
  }
}
