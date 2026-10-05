/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Caches SHA-256 hashes of large files using a sidecar file to avoid re-reading the entire file on
 * every Worker boot.
 *
 * <p>The sidecar file (e.g., {@code model.onnx.sha256}) stores the hash alongside the file's mtime
 * and size. On subsequent reads, if mtime+size match, the cached hash is returned without reading
 * the file (~1ms vs 100-400ms).
 *
 * <p>Writes are atomic (temp file + rename) so a crash during write leaves the old sidecar intact.
 */
public final class Sha256SidecarCache {
  private static final System.Logger log = System.getLogger(Sha256SidecarCache.class.getName());

  private static final int BUFFER_SIZE = 8 * 1024 * 1024;
  private static final String SIDECAR_SUFFIX = ".sha256";

  private Sha256SidecarCache() {}

  /**
   * Returns the SHA-256 hash of the given file, using a sidecar cache to avoid redundant reads.
   *
   * @param file the file to hash
   * @return the 64-character hex SHA-256 hash, or empty if the file does not exist
   */
  public static Optional<String> getOrCompute(Path file) {
    return getOrCompute(file, true);
  }

  /**
   * Hashes current bytes without reading or writing a sidecar when {@code useSidecar} is false.
   * Content-addressed stores use this mode: mtime and size are not a content identity, and the
   * source directory may be read-only.
   */
  public static Optional<String> getOrCompute(Path file, boolean useSidecar) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      long size = Files.size(file);
      long mtimeMs = Files.getLastModifiedTime(file).toMillis();

      Path sidecar = file.resolveSibling(file.getFileName() + SIDECAR_SUFFIX);
      Optional<String> cached = useSidecar ? readSidecar(sidecar, mtimeMs, size) : Optional.empty();
      if (cached.isPresent()) {
        log.log(System.Logger.Level.DEBUG, "Sidecar cache hit for {0} (mtime={1}, size={2})", file.getFileName(), mtimeMs, size);
        return cached;
      }

      long startMs = System.currentTimeMillis();
      String sha256 = computeSha256(file);
      long elapsedMs = System.currentTimeMillis() - startMs;
      log.log(System.Logger.Level.INFO, "Computed SHA-256 for {0} in {1}ms: {2}", file.getFileName(), elapsedMs,
          sha256.substring(0, 16) + "...");

      if (useSidecar) {
        writeSidecar(sidecar, sha256, mtimeMs, size);
      }
      return Optional.of(sha256);
    } catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Failed to compute/cache SHA-256 for " + file, e);
      return Optional.empty();
    }
  }

  /**
   * Reads the sidecar file and validates that mtime+size match the current file.
   *
   * <p>Sidecar format: {@code sha256:<hex> mtime:<epoch-millis> size:<bytes>}
   */
  private static Optional<String> readSidecar(Path sidecar, long expectedMtime, long expectedSize) {
    if (!Files.isRegularFile(sidecar)) {
      return Optional.empty();
    }
    try {
      String content = Files.readString(sidecar).strip();
      String hash = null;
      long mtime = -1;
      long size = -1;
      for (String part : content.split("\\s+")) {
        if (part.startsWith("sha256:")) {
          hash = part.substring("sha256:".length());
        } else if (part.startsWith("mtime:")) {
          mtime = Long.parseLong(part.substring("mtime:".length()));
        } else if (part.startsWith("size:")) {
          size = Long.parseLong(part.substring("size:".length()));
        }
      }
      if (hash != null && hash.length() == 64 && mtime == expectedMtime && size == expectedSize) {
        return Optional.of(hash);
      }
      if (hash != null) {
        log.log(System.Logger.Level.DEBUG, "Sidecar stale: mtime {0}→{1}, size {2}→{3}", mtime, expectedMtime, size, expectedSize);
      }
      return Optional.empty();
    } catch (IOException | NumberFormatException e) {
      log.log(System.Logger.Level.DEBUG, "Failed to read sidecar {0}: {1}", sidecar, e.getMessage());
      return Optional.empty();
    }
  }

  /** Writes the sidecar atomically via temp file + rename. */
  private static void writeSidecar(Path sidecar, String sha256, long mtimeMs, long size) {
    try {
      String content = "sha256:" + sha256 + " mtime:" + mtimeMs + " size:" + size + "\n";
      Path temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
      Files.writeString(temp, content);
      try {
        Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.log(System.Logger.Level.DEBUG, "Failed to write sidecar {0} (non-fatal): {1}", sidecar, e.getMessage());
    }
  }

  private static String computeSha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[BUFFER_SIZE];
      try (InputStream in = Files.newInputStream(file)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
