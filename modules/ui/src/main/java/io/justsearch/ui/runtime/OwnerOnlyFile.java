/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Creates an empty credential file with explicit permissions before any secret is written. */
final class OwnerOnlyFile {
  private OwnerOnlyFile() {}

  static void createFresh(Path path) throws IOException {
    Files.deleteIfExists(path);
    if (Files.getFileStore(path.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      return;
    }
    Files.createFile(path);
    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (acl == null) throw new IOException("No owner-only file permissions available: " + path);
    var owner = Files.getOwner(path);
    var system = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
    var permissions = EnumSet.allOf(AclEntryPermission.class);
    acl.setAcl(List.of(
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(permissions).build(),
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system).setPermissions(permissions).build()));
  }
}
