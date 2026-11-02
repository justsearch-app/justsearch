/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Metadata-only checks performed before opening an admitted source's content. */
public final class SourceAccess {
  private SourceAccess() {}

  public static void requireSafeAncestors(Path file, Path root) throws IOException {
    if (root == null) return;
    Path source = file.toAbsolutePath().normalize();
    Path boundary = root.toAbsolutePath().normalize();
    if (!source.startsWith(boundary)) throw new IOException("Source is outside its ingestion boundary");
    if (source.equals(boundary)) boundary = source.getParent();
    Path parent = source.getParent();
    Path current = boundary;
    Path realRoot = null;
    for (;;) {
      BasicFileAttributes attrs;
      try {
        attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (java.nio.file.NoSuchFileException absent) {
        return;
      }
      if (current.equals(boundary) && attrs.isSymbolicLink()) {
        throw new IOException("Ingestion boundary is a symbolic link");
      }
      Path real = current.toRealPath();
      if (realRoot == null) realRoot = real;
      if (!real.startsWith(realRoot)) throw new IOException("Source ancestor escaped its ingestion boundary");
      if (current.equals(parent)) return;
      current = current.resolve(boundary.relativize(parent).getName(current.getNameCount() - boundary.getNameCount()));
    }
  }

  public static void requireContained(Path file, Path root) throws IOException {
    Path source = file.toAbsolutePath().normalize();
    Path boundary = root == null ? source : root.toAbsolutePath().normalize();
    if (!source.startsWith(boundary)) throw new IOException("Source is outside its ingestion boundary");
    Path current = boundary;
    BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()) throw new IOException("Ingestion boundary is a symbolic link");
    Path realRoot = boundary.toRealPath();
    for (Path component : boundary.relativize(source)) {
      if (component.toString().isEmpty()) continue;
      current = current.resolve(component);
      attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!current.toRealPath().startsWith(realRoot)) throw new IOException("Source escaped its ingestion boundary");
    }
    if (!attributes.isRegularFile()) throw new IOException("Source is not a regular file");
    if (!file.toAbsolutePath().equals(source) && !file.toRealPath().equals(source.toRealPath())) {
      throw new IOException("Source path resolves differently before normalization");
    }
  }
}
