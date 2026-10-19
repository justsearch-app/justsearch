/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Portable names for conversation, session and run files, checked without filesystem access. */
public final class StoreIds {
  private static final Pattern WINDOWS_DEVICE_NAME =
      Pattern.compile("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?", Pattern.DOTALL);

  private StoreIds() {}

  public static void requireValid(String id) {
    if (id == null || id.isBlank() || ".".equals(id) || "..".equals(id)
        || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf(':') >= 0
        || id.endsWith(".") || id.endsWith(" ") || WINDOWS_DEVICE_NAME.matcher(id).matches()
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
