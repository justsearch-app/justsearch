/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsOwnerOnlyDirectoryTest {
  @Test void creationDescriptorIsProtectedAndGrantsOnlyOwnerAndSystemInheritableFullControl() {
    var parts = WindowsOwnerOnlyDirectory.SDDL.split("\\(");
    assertEquals("D:P", parts[0], "creation must disable parent DACL inheritance");
    assertEquals(3, parts.length, "exactly two explicit access entries");
    assertEquals(List.of("A;OICI;FA;;;OW)", "A;OICI;FA;;;SY)"), List.of(parts[1], parts[2]));
  }

  @Test void repairSetsDaclAndProtectsItWithoutChangingOwnerGroupOrSacl() {
    assertEquals(0x80000004, WindowsOwnerOnlyDirectory.REPAIR_SECURITY_INFORMATION);
  }

  @Test void unavailableNativePermissionsWarnOnceAndPreserveContents(@TempDir Path data) throws Exception {
    assumeTrue(System.getProperty("os.name").equals("Linux"));
    var marker = Files.writeString(data.resolve("old-file"), "keep");
    var mode = Files.getPosixFilePermissions(data);
    var warnings = new ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      OwnerOnlyDirectory.ensure(data, path -> WindowsOwnerOnlyDirectory.apply(path, false), warnings::add);
    }
    assertEquals(1, warnings.size());
    assertEquals(mode, Files.getPosixFilePermissions(data));
    assertEquals("keep", Files.readString(marker));
  }
}
