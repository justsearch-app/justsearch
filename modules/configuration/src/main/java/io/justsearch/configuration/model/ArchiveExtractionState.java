/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipFile;
import tools.jackson.databind.json.JsonMapper;

/** Internal install bookkeeping, kept below a directory runtime discovery does not scan. */
public final class ArchiveExtractionState {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  public static final String DIRECTORY = ".archive-receipts";

  private ArchiveExtractionState() {}

  public record Entry(String name, long size) {}
  public record Receipt(String sha256, List<Entry> entries) {}

  public static Path receiptPath(Path archive) {
    return archive.toAbsolutePath().getParent().resolve(DIRECTORY)
        .resolve(archive.getFileName() + ".json");
  }

  public static Path pendingPath(Path archive) {
    Path receipt = receiptPath(archive);
    return receipt.resolveSibling(receipt.getFileName() + ".pending");
  }

  /** Central-directory metadata only: status must never hash a multi-GB ZIP. */
  public static boolean isApplied(Path archive, String expectedSha256) {
    try {
      requireSafePath(archive);
      requireSafePath(receiptPath(archive));
      if (Files.exists(pendingPath(archive), LinkOption.NOFOLLOW_LINKS)) return false;
      List<Entry> entries;
      try (ZipFile zip = new ZipFile(archive.toFile())) {
        entries = entries(zip, archive.getParent(), archive);
      }
      Path receipt = receiptPath(archive);
      if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
        Receipt recorded = JSON.readValue(Files.readAllBytes(receipt), Receipt.class);
        if (recorded == null || expectedSha256 == null
            || !expectedSha256.equalsIgnoreCase(recorded.sha256())
            || !entries.equals(recorded.entries())) return false;
      }
      // Healthy pre-receipt installs stay installed. A pending marker prevents an interrupted
      // first apply from entering this compatibility path.
      for (Entry entry : entries) {
        Path output = outputPath(archive.getParent(), entry.name(), archive);
        requireSafePath(output);
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
      if (entry.getSize() < 0 || !names.add(output)) {
        throw new IOException("Invalid or duplicate archive entry: " + entry.getName());
      }
      result.add(new Entry(entry.getName(), entry.getSize()));
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

  /** Reject links and Windows junction/reparse redirects before creating, reading or replacing. */
  public static void requireSafePath(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path cursor = absolute.getRoot();
    for (Path part : absolute) {
      cursor = cursor.resolve(part);
      if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
      BasicFileAttributes attrs = Files.readAttributes(
          cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor)) {
        throw new IOException("Refusing redirected archive path: " + cursor);
      }
    }
  }

  public static void begin(Path archive) throws IOException {
    Path pending = pendingPath(archive);
    requireSafePath(pending);
    AtomicFileWrites.replaceStrict(pending, new byte[] {1});
  }

  public static void complete(Path archive, String sha256, List<Entry> entries) throws IOException {
    Path receipt = receiptPath(archive);
    requireSafePath(receipt);
    requireSafePath(pendingPath(archive));
    AtomicFileWrites.replaceStrict(receipt, JSON.writeValueAsBytes(new Receipt(sha256, entries)));
    Files.deleteIfExists(pendingPath(archive));
  }
}
