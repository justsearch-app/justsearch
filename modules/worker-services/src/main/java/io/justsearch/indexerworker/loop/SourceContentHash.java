/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streaming content identity used to bind an extraction to its exact source bytes. */
public final class SourceContentHash {
  private static final int BUFFER_SIZE = 64 * 1024;

  private SourceContentHash() {}

  public static String sha256(Path path) throws IOException {
    return sha256(path, () -> false);
  }

  public static String sha256WithinRoot(Path path, Path root) throws IOException {
    return sha256WithinRoot(path, root, () -> false);
  }

  public static String sha256WithinRoot(Path path, Path root, java.util.function.BooleanSupplier cancelled) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    SourceAccess.requireContained(normalized, root);
    return sha256(normalized, cancelled);
  }

  /** The caller may cancel a bounded admission while a large source is being witnessed. */
  public static String sha256(Path path, java.util.function.BooleanSupplier cancelled) throws IOException {
    if (io.justsearch.indexerworker.ingest.CloudPlaceholderDetector.isCloudPlaceholder(path)) {
      throw new IOException("Cloud-only placeholder must not be opened");
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream input = Files.newInputStream(path)) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (cancelled.getAsBoolean()) {
          throw new java.io.InterruptedIOException("Source witnessing cancelled");
        }
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
