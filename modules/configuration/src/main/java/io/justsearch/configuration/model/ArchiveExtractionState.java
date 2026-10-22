/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import tools.jackson.databind.json.JsonMapper;

/** Internal install bookkeeping, kept below a directory runtime discovery does not scan. */
public final class ArchiveExtractionState {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  public static final String DIRECTORY = ".archive-receipts";

  private ArchiveExtractionState() {}

  public record Entry(String name, long size, long crc) {}
  public record Receipt(String sha256, List<Entry> entries) {}

  public static Path receiptPath(Path archive) {
    return archive.toAbsolutePath().getParent().resolve(DIRECTORY)
        .resolve(archive.getFileName() + ".json");
  }

  public static Path pendingPath(Path archive) {
    Path receipt = receiptPath(archive);
    return receipt.resolveSibling(receipt.getFileName() + ".pending");
  }

  /**
   * Central-directory metadata only: status must never hash a multi-GB ZIP. With {@code
   * requireReceipt} false, a healthy install from before receipts counts as applied.
   */
  public static boolean isApplied(Path archive, String expectedSha256, boolean requireReceipt) {
    try {
      Path root = destination(archive.toAbsolutePath().normalize().getParent());
      archive = root.resolve(archive.getFileName());
      requireContained(root, archive);
      requireContained(root, pendingPath(archive));
      if (Files.exists(pendingPath(archive), LinkOption.NOFOLLOW_LINKS)) return false;
      List<Entry> entries;
      try (ZipFile zip = new ZipFile(archive.toFile())) {
        entries = entries(zip, root, archive);
      }
      Path receipt = receiptPath(archive);
      requireContained(root, receipt);
      if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
        Receipt recorded = JSON.readValue(Files.readAllBytes(receipt), Receipt.class);
        if (recorded == null || expectedSha256 == null
            || !expectedSha256.equalsIgnoreCase(recorded.sha256())
            || !entries.equals(recorded.entries())) return false;
      } else if (requireReceipt) {
        return false;
      }
      // Healthy pre-receipt installs stay installed. A pending marker prevents an interrupted
      // first apply from entering this compatibility path.
      for (Entry entry : entries) {
        Path output = outputPath(root, entry.name(), archive);
        requireContained(root, output);
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
            || Files.size(output) != entry.size()) return false;
      }
      return true;
    } catch (IOException | RuntimeException invalid) {
      return false;
    }
  }

  public static List<Entry> entries(ZipFile zip, Path target, Path archive) throws IOException {
    List<Entry> result = new ArrayList<>();
    HashSet<Path> names = new HashSet<>();
    var entries = zip.entries();
    while (entries.hasMoreElements()) {
      var entry = entries.nextElement();
      Path output = outputPath(target, entry.getName(), archive);
      if (entry.isDirectory()) continue;
      if (entry.getSize() < 0 || entry.getCrc() < 0 || !names.add(output)) {
        throw new IOException("Invalid or duplicate archive entry: " + entry.getName());
      }
      result.add(new Entry(entry.getName(), entry.getSize(), entry.getCrc()));
    }
    return List.copyOf(result);
  }

  public static Path outputPath(Path target, String name, Path archive) throws IOException {
    Path root = target.toAbsolutePath().normalize();
    // Reject both ZIP separators on every host, including Windows absolute names on Unix.
    String portable = name.replace('\\', '/');
    Path resolved = root.resolve(portable).normalize();
    if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")
        || !resolved.startsWith(root) || resolved.equals(root)
        || resolved.startsWith(root.resolve(DIRECTORY))
        || resolved.equals(archive.toAbsolutePath().normalize())) {
      throw new IOException("Refusing zip entry that escapes or aliases install state: " + name);
    }
    return resolved;
  }

  /**
   * Real path of an existing destination directory. Links above it (a symlinked home, a Windows
   * junction or 8.3 short name) are the user's or the OS's and are followed; a link at it is refused.
   */
  public static Path destination(Path directory) throws IOException {
    Path absolute = directory.toAbsolutePath().normalize();
    if (isRedirect(absolute)) throw new IOException("Refusing redirected archive path: " + absolute);
    return absolute.toRealPath();
  }

  /** Refuses a link or junction at any existing component from {@code root} down to {@code path}. */
  public static void requireContained(Path root, Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) {
      throw new IOException("Refusing archive path outside its destination: " + path);
    }
    Path cursor = root;
    for (Path part : root.relativize(normalized)) {
      cursor = cursor.resolve(part);
      if (isRedirect(cursor)) throw new IOException("Refusing redirected archive path: " + cursor);
    }
  }

  /** {@link #requireContained} for a file directly inside a destination that may not exist yet. */
  public static void requireSafeFile(Path file) throws IOException {
    Path absolute = file.toAbsolutePath().normalize();
    if (!Files.exists(absolute.getParent(), LinkOption.NOFOLLOW_LINKS)) return;
    Path root = destination(absolute.getParent());
    requireContained(root, root.resolve(absolute.getFileName()));
  }

  private static boolean isRedirect(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
    // Windows junctions and other reparse points read as "other" when links are not followed.
    BasicFileAttributes attrs = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return attrs.isSymbolicLink() || attrs.isOther();
  }

  /**
   * Fails when another archive's receipt in this destination lists one of {@code entries}' outputs
   * with a different CRC, so two archives cannot overwrite each other on alternate runs. With
   * {@code listed} given, the receipt of an archive it does not name (one a registry update
   * renamed) claims nothing and is deleted; its outputs stay.
   */
  public static void requireNoOverlap(
      Path archive, Path target, List<Entry> entries, Collection<String> listed)
      throws IOException {
    Path receipts = receiptPath(archive).getParent();
    requireContained(archive.getParent(), receipts);
    if (!Files.isDirectory(receipts, LinkOption.NOFOLLOW_LINKS)) return;
    Map<Path, Entry> ours = new HashMap<>();
    for (Entry entry : entries) ours.put(outputPath(target, entry.name(), archive), entry);
    String own = receiptPath(archive).getFileName().toString();
    List<Path> others;
    try (var files = Files.list(receipts)) {
      others = files.filter(p -> p.getFileName().toString().endsWith(".json")
          && !p.getFileName().toString().equals(own)).sorted().toList();
    }
    for (Path other : others) {
      requireContained(archive.getParent(), other);
      String otherArchive = other.getFileName().toString().replaceFirst("\\.json$", "");
      if (listed != null && !listed.contains(otherArchive)) {
        if (Files.isRegularFile(other, LinkOption.NOFOLLOW_LINKS)) {
          try { Files.deleteIfExists(other); } catch (IOException heldOpen) { /* next apply */ }
        }
        continue;
      }
      Receipt recorded;
      try {
        recorded = JSON.readValue(Files.readAllBytes(other), Receipt.class);
      } catch (IOException | RuntimeException unreadable) {
        continue; // an unreadable receipt claims nothing
      }
      if (recorded == null || recorded.entries() == null) continue;
      for (Entry theirs : recorded.entries()) {
        Entry mine;
        try {
          mine = ours.get(outputPath(target, theirs.name(), archive));
        } catch (IOException invalid) {
          continue;
        }
        if (mine != null && mine.crc() != theirs.crc()) {
          throw new IOException(archive.getFileName() + " would overwrite " + mine.name()
              + ", which " + otherArchive + " installed with different content");
        }
      }
    }
  }

  /** Callers pass the archive under its real destination ({@link #destination}). */
  public static void begin(Path archive) throws IOException {
    Path pending = pendingPath(archive);
    requireContained(archive.getParent(), pending);
    AtomicFileWrites.replaceStrict(pending, new byte[] {1});
  }

  public static void complete(Path archive, String sha256, List<Entry> entries) throws IOException {
    Path receipt = receiptPath(archive);
    requireContained(archive.getParent(), receipt);
    requireContained(archive.getParent(), pendingPath(archive));
    AtomicFileWrites.replaceStrict(receipt, JSON.writeValueAsBytes(new Receipt(sha256, entries)));
    Files.deleteIfExists(pendingPath(archive));
  }
}
