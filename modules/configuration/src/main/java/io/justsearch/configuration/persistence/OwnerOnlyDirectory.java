/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Protects a single directory, without walking or changing its parent or existing children. */
public final class OwnerOnlyDirectory {
  private static final Set<Path> WARNED = ConcurrentHashMap.newKeySet();
  private static final Set<PosixFilePermission> MODE = PosixFilePermissions.fromString("rwx------");

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
          var owner = path.getFileSystem().getUserPrincipalLookupService()
              .lookupPrincipalByName(System.getProperty("user.name"));
          var entries = acl(path, owner);
          Files.createDirectory(path, new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return entries; }
          });
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
    if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
      var failure = new IOException("Refusing owner-only directory repair on a link or non-directory: " + path);
      warnOnce(path, failure.toString(), warning);
      throw failure;
    }
    try {
      permissions.restrict(path);
    } catch (IOException | UnsupportedOperationException | SecurityException failure) {
      warnOnce(path, "Could not protect owner-only directory " + path + ": " + failure, warning);
    }
  }

  private static void warnOnce(Path path, String message, Consumer<String> warning) {
    if (WARNED.add(path)) warning.accept(message);
  }

  private static void restrict(Path path) throws IOException {
    var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      posix.setPermissions(MODE);
      return;
    }
    var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) throw new IOException("No owner-only directory permissions available");
    view.setAcl(acl(path, view.getOwner()));
  }

  private static List<AclEntry> acl(Path path, UserPrincipal owner) throws IOException {
    var system = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
    return List.of(entry(owner), entry(system));
  }

  private static AclEntry entry(UserPrincipal principal) {
    return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build();
  }
}
