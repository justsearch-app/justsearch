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

  private static final boolean HAS_DOS_ATTRIBUTES =
      java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("dos");
  private static final int RECALL_ON_DATA_ACCESS = 0x00400000;

  /** Reading a cloud-only file would hydrate it; probe attributes before opening its bytes. */
  public static boolean isCloudPlaceholder(Path file) {
    if (!HAS_DOS_ATTRIBUTES) return false;
    try {
      int attrs = (int) Files.getAttribute(file, "dos:attributes");
      return (attrs & RECALL_ON_DATA_ACCESS) != 0;
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
      // A file whose Windows cloud-placeholder attribute cannot be read is not a placeholder.
      // On some non-Windows JDKs the default FS advertises a "dos" view (so HAS_DOS_ATTRIBUTES
      // is true) yet rejects the raw "attributes" name with IllegalArgumentException
      // ('attributes' not recognized) — treat that as "not a placeholder", same as the other
      // unreadable cases. This keeps the source probe portable across filesystem providers.
      return false;
    }
  }

  /** Best-effort admission proof. Missing evidence never certifies unchanged content. */
  public static String admissionIdentity(Path path, Path root) {
    if (root == null || io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(path, root)
        || isCloudPlaceholder(path)) return null;
    try {
      var before = FileFreshnessSnapshot.capture(path);
      if (!before.regularFile()) return null;
      String hash = sha256(path);
      return before.validateNow() == FileFreshnessSnapshot.SourceValidationResult.FRESH ? hash : null;
    } catch (IOException | SecurityException unreadable) {
      return null;
    }
  }

  public static String sha256(Path path) throws IOException {
    return sha256(path, () -> false);
  }

  /** The caller may cancel a bounded admission while a large source is being witnessed. */
  public static String sha256(Path path, java.util.function.BooleanSupplier cancelled) throws IOException {
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
