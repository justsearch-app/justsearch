/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.model.ArchiveExtractionState;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Content-aware archive apply. The ZIP and unfinished apply state survive every local failure. */
final class ArchiveExtractor {
  private ArchiveExtractor() {}

  static void apply(Path archive, Path target) throws IOException {
    if (archive == null || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Archive not found: " + archive);
    }
    ArchiveExtractionState.requireSafePath(archive);
    String sha;
    try {
      sha = DownloadExecutor.sha256(archive);
    } catch (Exception failure) {
      throw new IOException("Cannot hash archive", failure);
    }
    apply(archive, target, sha);
  }

  static void apply(Path archive, Path target, String verifiedSha256) throws IOException {
    ArchiveExtractionState.requireSafePath(archive);
    ArchiveExtractionState.requireSafePath(target);
    Files.createDirectories(target);
    List<ArchiveExtractionState.Entry> inventory;
    Path savedMarker = ArchiveExtractionState.receiptPath(archive).resolveSibling(
        archive.getFileName() + ".ort-marker");
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      inventory = ArchiveExtractionState.entries(zip, target, archive);
      ArchiveExtractionState.begin(archive);
      List<ZipEntry> changed = new ArrayList<>();
      List<ZipEntry> markers = new ArrayList<>();
      for (var item : inventory) {
        ZipEntry entry = zip.getEntry(item.name());
        Path output = ArchiveExtractionState.outputPath(target, entry.getName(), archive);
        ArchiveExtractionState.requireSafePath(output);
        cleanup(output);
        if (isMarker(output)) markers.add(entry);
        if (!matches(output, entry)) changed.add(entry);
        else executable(output, output.getFileName().toString());
      }
      // ORT's marker is the final publication even when a pack carries both marker kinds.
      markers.sort(java.util.Comparator.comparing(e -> e.getName().endsWith("ort-native-version.txt")));
      Path sharedMarker = target.resolve("ort-native-version.txt");
      boolean carriesOrtMarker = markers.stream().anyMatch(e ->
          Path.of(e.getName().replace('\\', '/')).getFileName().toString().equals("ort-native-version.txt"));
      ArchiveExtractionState.requireSafePath(savedMarker);
      // Supplemental CUDA archives also invalidate ORT while changing its dependencies. Preserve
      // the marker outside runtime discovery so an interrupted supplemental apply can restore it.
      if (!changed.isEmpty() && !carriesOrtMarker) {
        ArchiveExtractionState.requireSafePath(sharedMarker);
        if (Files.isRegularFile(sharedMarker, LinkOption.NOFOLLOW_LINKS)) {
          AtomicFileWrites.replaceStrict(savedMarker, Files.readAllBytes(sharedMarker));
          moveAside(sharedMarker);
        }
      }
      // Even an unchanged version string is invalid while same-version DLL bytes are changing.
      if (!changed.isEmpty()) {
        for (ZipEntry marker : markers) {
          Path output = ArchiveExtractionState.outputPath(target, marker.getName(), archive);
          moveAside(output);
        }
      }
      for (ZipEntry entry : changed) {
        Path output = ArchiveExtractionState.outputPath(target, entry.getName(), archive);
        if (!isMarker(output)) write(zip, entry, output);
      }
      // Marker publication is last, after every payload entry, including same-version repairs.
      if (!changed.isEmpty()) {
        for (ZipEntry marker : markers) {
          write(zip, marker, ArchiveExtractionState.outputPath(target, marker.getName(), archive));
        }
      }
      if (!carriesOrtMarker && Files.isRegularFile(savedMarker, LinkOption.NOFOLLOW_LINKS)) {
        ArchiveExtractionState.requireSafePath(sharedMarker);
        if (!Files.exists(sharedMarker, LinkOption.NOFOLLOW_LINKS)) {
          Path partial = partial(sharedMarker);
          ArchiveExtractionState.requireSafePath(partial);
          Files.deleteIfExists(partial);
          Files.write(partial, Files.readAllBytes(savedMarker), StandardOpenOption.CREATE_NEW);
          replace(partial, sharedMarker);
        }
      }
    }
    ArchiveExtractionState.complete(archive, verifiedSha256, inventory);
    try { Files.deleteIfExists(savedMarker); } catch (IOException heldOpen) { /* next apply */ }
  }

  private static boolean isMarker(Path file) {
    String name = file.getFileName().toString();
    return name.equals("ort-native-version.txt") || name.equals("runtime-version.txt");
  }

  private static boolean matches(Path file, ZipEntry entry) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) != entry.getSize()) return false;
    CRC32 crc = new CRC32();
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[1024 * 1024];
      int n;
      while ((n = input.read(buffer)) >= 0) if (n > 0) crc.update(buffer, 0, n);
    }
    return crc.getValue() == entry.getCrc();
  }

  private static void write(ZipFile zip, ZipEntry entry, Path output) throws IOException {
    ArchiveExtractionState.requireSafePath(output);
    Files.createDirectories(output.getParent());
    ArchiveExtractionState.requireSafePath(output);
    Path partial = partial(output);
    ArchiveExtractionState.requireSafePath(partial);
    Files.deleteIfExists(partial);
    try (var input = zip.getInputStream(entry);
        var out = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      input.transferTo(out);
    }
    if (!matches(partial, entry)) throw new IOException("Incomplete ZIP entry: " + entry.getName());
    executable(partial, output.getFileName().toString());
    replace(partial, output);
  }

  /** Move-aside allows Windows to keep an old loaded DLL mapped without truncating either file. */
  static void replace(Path partial, Path output) throws IOException {
    ArchiveExtractionState.requireSafePath(partial);
    ArchiveExtractionState.requireSafePath(output);
    moveAside(output);
    ArchiveExtractionState.requireSafePath(output);
    Files.move(partial, output);
    cleanup(output);
  }

  private static void moveAside(Path output) throws IOException {
    ArchiveExtractionState.requireSafePath(output);
    if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Refusing non-regular archive target: " + output);
    }
    // Unique sibling, so a DLL still held open from an earlier pass cannot block another repair.
    Path old = output.resolveSibling(output.getFileName() + ".archive-old-" + java.util.UUID.randomUUID());
    Files.move(output, old);
  }

  private static Path partial(Path file) {
    return file.resolveSibling(file.getFileName() + ".partial");
  }

  private static void cleanup(Path output) throws IOException {
    ArchiveExtractionState.requireSafePath(output.getParent());
    if (!Files.isDirectory(output.getParent(), LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.list(output.getParent())) {
      for (Path file : files.filter(p -> p.getFileName().toString()
          .startsWith(output.getFileName() + ".archive-old-")).toList()) {
        // Deleting the link itself is safe; never traverse or recursively remove a target.
        try { Files.deleteIfExists(file); } catch (IOException heldOpen) { /* next apply */ }
      }
    } catch (IOException unavailable) { /* moved-aside cleanup is best-effort */ }
    Path partial = partial(output);
    ArchiveExtractionState.requireSafePath(partial);
    Files.deleteIfExists(partial);
  }

  private static void executable(Path file, String name) throws IOException {
    if (!PlatformPaths.isWindows() && name.equals(NativeTargets.current().executableName("llama-server"))) {
      var permissions = Files.getPosixFilePermissions(file);
      permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(file, permissions);
    }
  }
}
