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
import java.util.Collection;
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
    ArchiveExtractionState.requireSafeFile(archive);
    String sha;
    try {
      sha = DownloadExecutor.sha256(archive);
    } catch (Exception failure) {
      throw new IOException("Cannot hash archive", failure);
    }
    apply(archive, target, sha, null);
  }

  /**
   * @param listedArchives file names of the archives the current registry extracts into this
   *     destination; receipts of others are retired. Null keeps every receipt in the overlap rule.
   */
  static void apply(
      Path archive, Path target, String verifiedSha256, Collection<String> listedArchives)
      throws IOException {
    // Containment is checked against real paths resolved once here: links at or above either
    // directory are followed, links below them are refused.
    Path archiveRoot = ArchiveExtractionState.destination(archive.toAbsolutePath().normalize().getParent());
    archive = archiveRoot.resolve(archive.getFileName());
    ArchiveExtractionState.requireContained(archiveRoot, archive);
    Files.createDirectories(target);
    Path root = ArchiveExtractionState.destination(target);
    List<ArchiveExtractionState.Entry> inventory;
    Path savedMarker = ArchiveExtractionState.receiptPath(archive).resolveSibling(
        archive.getFileName() + ".ort-marker");
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      inventory = ArchiveExtractionState.entries(zip, root, archive);
      // An entry may not replace another archive the registry keeps in this folder.
      Path folder = archive.getParent();
      for (var item : inventory) {
        Path output = ArchiveExtractionState.outputPath(root, item.name(), archive);
        if (listedArchives != null && listedArchives.stream()
            .anyMatch(name -> folder.resolve(name).equals(output))) {
          throw new IOException("Refusing zip entry that aliases a kept archive: " + item.name());
        }
      }
      ArchiveExtractionState.begin(archive);
      ArchiveExtractionState.requireNoOverlap(archive, root, inventory, listedArchives);
      cleanup(root);
      List<ZipEntry> changed = new ArrayList<>();
      List<ZipEntry> markers = new ArrayList<>();
      for (var item : inventory) {
        ZipEntry entry = zip.getEntry(item.name());
        Path output = ArchiveExtractionState.outputPath(root, entry.getName(), archive);
        ArchiveExtractionState.requireContained(root, output);
        if (isMarker(output)) markers.add(entry);
        if (!matches(output, entry)) changed.add(entry);
        else executable(output, output.getFileName().toString());
      }
      // ORT's marker is the final publication even when a pack carries both marker kinds.
      markers.sort(java.util.Comparator.comparing(e -> e.getName().endsWith("ort-native-version.txt")));
      Path sharedMarker = root.resolve("ort-native-version.txt");
      boolean carriesOrtMarker = markers.stream().anyMatch(e ->
          Path.of(e.getName().replace('\\', '/')).getFileName().toString().equals("ort-native-version.txt"));
      ArchiveExtractionState.requireContained(archiveRoot, savedMarker);
      // Supplemental CUDA archives also invalidate ORT while changing its dependencies. Preserve
      // the marker outside runtime discovery so an interrupted supplemental apply can restore it.
      if (!changed.isEmpty() && !carriesOrtMarker) {
        ArchiveExtractionState.requireContained(root, sharedMarker);
        if (Files.isRegularFile(sharedMarker, LinkOption.NOFOLLOW_LINKS)) {
          AtomicFileWrites.replaceStrict(savedMarker, Files.readAllBytes(sharedMarker));
          moveAside(root, sharedMarker);
        }
      }
      // Even an unchanged version string is invalid while same-version DLL bytes are changing.
      if (!changed.isEmpty()) {
        for (ZipEntry marker : markers) {
          Path output = ArchiveExtractionState.outputPath(root, marker.getName(), archive);
          moveAside(root, output);
        }
      }
      for (ZipEntry entry : changed) {
        Path output = ArchiveExtractionState.outputPath(root, entry.getName(), archive);
        if (!isMarker(output)) write(root, zip, entry, output);
      }
      // Marker publication is last, after every payload entry, including same-version repairs.
      if (!changed.isEmpty()) {
        for (ZipEntry marker : markers) {
          write(root, zip, marker, ArchiveExtractionState.outputPath(root, marker.getName(), archive));
        }
      }
      if (!carriesOrtMarker && Files.isRegularFile(savedMarker, LinkOption.NOFOLLOW_LINKS)) {
        ArchiveExtractionState.requireContained(root, sharedMarker);
        if (!Files.exists(sharedMarker, LinkOption.NOFOLLOW_LINKS)) {
          Path partial = staged(root, ArchiveExtractionState.STAGING);
          Files.write(partial, Files.readAllBytes(savedMarker), StandardOpenOption.CREATE_NEW);
          replace(root, partial, sharedMarker);
        }
      }
    }
    ArchiveExtractionState.complete(archive, verifiedSha256, inventory);
    try { Files.deleteIfExists(savedMarker); } catch (IOException heldOpen) { /* next apply */ }
    cleanup(root);
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

  private static void write(Path root, ZipFile zip, ZipEntry entry, Path output) throws IOException {
    ArchiveExtractionState.requireContained(root, output);
    Files.createDirectories(output.getParent());
    ArchiveExtractionState.requireContained(root, output);
    Path partial = staged(root, ArchiveExtractionState.STAGING);
    try (var input = zip.getInputStream(entry);
        var out = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      input.transferTo(out);
    }
    if (!matches(partial, entry)) throw new IOException("Incomplete ZIP entry: " + entry.getName());
    executable(partial, output.getFileName().toString());
    replace(root, partial, output);
  }

  /** Replaces a file directly inside a destination; links below the destination are refused. */
  static void replace(Path partial, Path output) throws IOException {
    Path root = ArchiveExtractionState.destination(output.toAbsolutePath().normalize().getParent());
    replace(root, root.resolve(partial.getFileName()), root.resolve(output.getFileName()));
    cleanup(root);
  }

  /** Move-aside allows Windows to keep an old loaded DLL mapped without truncating either file. */
  private static void replace(Path root, Path partial, Path output) throws IOException {
    ArchiveExtractionState.requireContained(root, partial);
    ArchiveExtractionState.requireContained(root, output);
    moveAside(root, output);
    ArchiveExtractionState.requireContained(root, output);
    Files.move(partial, output);
  }

  private static void moveAside(Path root, Path output) throws IOException {
    ArchiveExtractionState.requireContained(root, output);
    if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Refusing non-regular archive target: " + output);
    }
    // Unique name in the destination's own folder, on the same volume: Windows can rename a loaded
    // DLL there, and one still held open from an earlier pass cannot block another repair.
    Files.move(output, staged(root, ArchiveExtractionState.OLD));
  }

  /** A new file name in one of the extractor's own folders directly inside {@code root}. */
  private static Path staged(Path root, String folder) throws IOException {
    Path dir = root.resolve(folder);
    ArchiveExtractionState.requireContained(root, dir);
    Files.createDirectories(dir);
    ArchiveExtractionState.requireContained(root, dir);
    return dir.resolve(java.util.UUID.randomUUID().toString());
  }

  /** Empties the extractor's own folders and nothing else; files still held open wait for the next apply. */
  private static void cleanup(Path root) throws IOException {
    for (String folder : List.of(ArchiveExtractionState.STAGING, ArchiveExtractionState.OLD)) {
      Path dir = root.resolve(folder);
      ArchiveExtractionState.requireContained(root, dir);
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
      try (var files = Files.list(dir)) {
        for (Path file : files.toList()) {
          // Deleting the link itself is safe; never traverse or recursively remove a target.
          try { Files.deleteIfExists(file); } catch (IOException heldOpen) { /* next apply */ }
        }
      } catch (IOException unavailable) { /* moved-aside cleanup is best-effort */ }
      try { Files.deleteIfExists(dir); } catch (IOException notEmpty) { /* next apply */ }
    }
  }

  private static void executable(Path file, String name) throws IOException {
    if (!PlatformPaths.isWindows() && name.equals(NativeTargets.current().executableName("llama-server"))) {
      var permissions = Files.getPosixFilePermissions(file);
      permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(file, permissions);
    }
  }
}
