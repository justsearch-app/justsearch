/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.nio.file.Path;

/** Portable names for conversation, session and run files, checked without filesystem access. */
public final class StoreIds {
  private StoreIds() {}

  public static void requireValid(String id) {
    if (id == null || id.isBlank() || ".".equals(id) || "..".equals(id)
        || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf(':') >= 0
        || id.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid store id: expected a single plain name");
    }
  }

  public static Path resolve(Path root, String id) {
    requireValid(id);
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path child = normalizedRoot.resolve(id).normalize();
    if (Path.of(id).isAbsolute() || !normalizedRoot.equals(child.getParent())) {
      throw new IllegalArgumentException("Invalid store id: expected a direct child of the store");
    }
    return child;
  }
}
