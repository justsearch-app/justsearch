/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EffectivePolicy;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared validation pipeline and file staging logic for AI Pack imports.
 *
 * <p>Extracted from {@link AiPackImportService} to eliminate duplication between the zip and folder
 * import paths.
 */
final class PackStagingOps {
  private static final Logger log = LoggerFactory.getLogger(PackStagingOps.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  // Zip bomb guardrails (implementation constants; conservative defaults).
  static final int MAX_PACK_FILES = 512;
  static final long MAX_PACK_TOTAL_BYTES = 50L * 1024 * 1024 * 1024; // 50 GiB
  static final long MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024 * 1024; // 20 GiB

  static final String MANIFEST_FILE = "pack-manifest.v1.json";

  private PackStagingOps() {}

  // -------------------- Result types --------------------

  /** Result of manifest parsing, validation, and policy check. */
  record ValidationResult(
      boolean ok,
      AiPackManifestV1 manifest,
      String manifestSha,
      long totalBytes,
      String errorCode,
      String errorMessage) {

    static ValidationResult success(AiPackManifestV1 manifest, String sha, long totalBytes) {
      return new ValidationResult(true, manifest, sha, totalBytes, null, null);
    }

    static ValidationResult failure(String errorCode, String message) {
      return new ValidationResult(false, null, null, 0, errorCode, message);
    }
  }

  // -------------------- Error type --------------------

  static final class PackStagingException extends Exception {
    private final String errorCode;

    PackStagingException(String errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    PackStagingException(String errorCode, String message, Throwable cause) {
      super(message, cause);
      this.errorCode = errorCode;
    }

    String errorCode() {
      return errorCode;
    }
  }

  // -------------------- Validation pipeline --------------------

  /**
   * Parse manifest bytes, validate structure, and check policy allowlists.
   *
   * <p>This is the shared validation pipeline used by both zip and folder import paths. It performs:
   *
   * <ol>
   *   <li>JSON parse (handles UTF-8 BOM)
   *   <li>Structural validation via {@link AiPackValidator}
   *   <li>Pack manifest SHA-256 allowlist check
   *   <li>Model file SHA-256 allowlist check (models packs only)
   *   <li>Size budget: max files, max single file, max total
   * </ol>
   */
  static ValidationResult parseValidateAndCheckPolicy(
      byte[] manifestBytes,
      EffectivePolicy effective,
      PackAllowlistService allowlistService) {

    AiPackManifestV1 manifest;
    try {
      manifest = MAPPER.readValue(manifestBytes, AiPackManifestV1.class);
    } catch (Exception e) {
      return ValidationResult.failure(
          "PACK_MANIFEST_INVALID", "Failed to parse pack manifest: " + e.getMessage());
    }

    AiPackValidator.ValidationResult vr = validateManifest(manifest);
    if (!vr.ok()) {
      return ValidationResult.failure(vr.errorCode().name(), vr.message());
    }

    String manifestSha = sha256Bytes(manifestBytes);

    PackAllowlistService.Decision allow =
        allowlistService.evaluatePackManifestSha256(manifestSha, effective);
    if (!allow.allowed()) {
      return ValidationResult.failure(allow.errorCode(), allow.message());
    }

    if (isModelsPack(manifest)
        && !allowlistService.evaluateModelFileAllowlist(manifest, effective)) {
      return ValidationResult.failure(
          "POLICY_MODEL_NOT_ALLOWLISTED",
          "One or more model assets are not allowlisted by policy.");
    }

    // Size budget validation.
    long totalBytes = 0;
    if (manifest.files.size() > MAX_PACK_FILES) {
      return ValidationResult.failure("PACK_TOO_MANY_FILES", "Pack has too many files.");
    }
    for (AiPackManifestV1.FileEntry f : manifest.files) {
      if (f.sizeBytes > MAX_SINGLE_FILE_BYTES) {
        return ValidationResult.failure(
            "PACK_FILE_TOO_LARGE", "Pack file exceeds max size: " + safe(f.pathInPack));
      }
      totalBytes += Math.max(0, f.sizeBytes);
      if (totalBytes > MAX_PACK_TOTAL_BYTES) {
        return ValidationResult.failure("PACK_TOO_LARGE", "Pack exceeds max total size.");
      }
    }

    return ValidationResult.success(manifest, manifestSha, totalBytes);
  }

  // -------------------- File staging --------------------

  /**
   * Stage a single file with SHA-256 integrity verification.
   *
   * <p>Writes to a {@code .tmp} file first, verifies size and digest, then atomically moves to the
   * final destination. The caller is responsible for opening the input stream (zip entry vs
   * filesystem).
   *
   * @throws PackStagingException on path traversal, integrity mismatch, or IO failure
   */
  static void stageFileWithVerification(
      InputStream rawIn, Path stageRoot, AiPackManifestV1.FileEntry fileEntry)
      throws PackStagingException {
    String pathInPack = fileEntry.pathInPack.trim();
    Path dest = stageRoot.resolve(pathInPack).normalize();
    if (!dest.startsWith(stageRoot)) {
      throw new PackStagingException(
          "PACK_PATH_INVALID", "Declared file normalizes outside staging: " + pathInPack);
    }
    try {
      Files.createDirectories(dest.getParent());
    } catch (IOException e) {
      throw new PackStagingException(
          "PACK_STAGE_FAILED", "Failed to create staging directory for " + pathInPack, e);
    }

    Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".tmp");
    try (InputStream in = new BufferedInputStream(rawIn);
        OutputStream out = Files.newOutputStream(tmp)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      long written = copyWithDigestAndLimit(in, out, md, fileEntry.sizeBytes);
      if (written != fileEntry.sizeBytes) {
        throw new PackStagingException(
            "PACK_VERIFY_FAILED",
            "Size mismatch for "
                + pathInPack
                + " expected="
                + fileEntry.sizeBytes
                + " got="
                + written);
      }
      String got = HexFormat.of().formatHex(md.digest());
      if (!got.equalsIgnoreCase(fileEntry.sha256)) {
        throw new PackStagingException(
            "PACK_VERIFY_FAILED", "SHA-256 mismatch for " + pathInPack);
      }
    } catch (PackStagingException e) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      throw e;
    } catch (Exception e) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      throw new PackStagingException(
          "PACK_STAGE_FAILED", "Failed to stage " + pathInPack + ": " + e.getMessage(), e);
    }

    try {
      moveAtomicBestEffort(tmp, dest);
      if (io.justsearch.app.inference.ChatArtifactIdentity.isChatModel(dest)) {
        try {
          io.justsearch.app.inference.ChatArtifactIdentity.rememberWritten(
              dest, fileEntry.sizeBytes, fileEntry.sha256);
        } catch (IOException unavailable) {
          log.debug("Verified pack chat receipt unavailable", unavailable);
        }
      }
    } catch (IOException e) {
      throw new PackStagingException(
          "PACK_STAGE_FAILED", "Failed to stage " + pathInPack + ": " + e.getMessage(), e);
    }
  }

  // -------------------- IO helpers --------------------

  static long copyWithDigestAndLimit(
      InputStream in, OutputStream out, MessageDigest md, long expectedSize) throws IOException {
    byte[] buf = new byte[1024 * 1024];
    long written = 0;
    int r;
    while ((r = in.read(buf)) >= 0) {
      if (r == 0) continue;
      out.write(buf, 0, r);
      md.update(buf, 0, r);
      written += r;
      if (expectedSize > 0 && written > expectedSize) {
        throw new IOException("Exceeded expected size");
      }
    }
    return written;
  }

  static void moveAtomicBestEffort(Path from, Path to) throws IOException {
    try {
      Files.createDirectories(to.getParent());
    } catch (Exception e) {
      log.debug("moveAtomicBestEffort: createDirectories failed: {}", e.getMessage());
    }
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static boolean isSafeRegularFile(Path p) {
    try {
      if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false;
      Path cur = p;
      while (cur != null && cur.getParent() != null) {
        if (Files.isSymbolicLink(cur)) return false;
        cur = cur.getParent();
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static String sha256Bytes(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes);
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static Path createStageRoot(Path aiHome, String packId) {
    String safePackId = (packId == null || packId.isBlank()) ? "unknown" : packId.trim();
    String ts =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()));
    Path root = aiHome.resolve("tmp").resolve("pack-import").resolve(safePackId).resolve(ts);
    try {
      Files.createDirectories(root);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create staging dir: " + root, e);
    }
    return root;
  }

  static String filenameFromPathInPack(String pathInPack) {
    if (pathInPack == null) return "";
    String p = pathInPack.trim();
    int idx = p.lastIndexOf('/');
    return idx >= 0 ? p.substring(idx + 1) : p;
  }

  static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win");
  }

  // -------------------- Validation helpers --------------------

  static AiPackValidator.ValidationResult validateManifest(AiPackManifestV1 manifest) {
    if (manifest == null) {
      return new AiPackValidator.ValidationResult(
          false, ApiErrorCode.PACK_MANIFEST_MISSING, "Pack manifest is missing.");
    }
    String kind = safe(manifest.kind).trim().toLowerCase(Locale.ROOT);
    if ("models".equals(kind)) {
      return AiPackValidator.validateModelsOnly(manifest);
    }
    if ("runtime".equals(kind)) {
      return AiPackValidator.validateRuntimePack(manifest);
    }
    return new AiPackValidator.ValidationResult(
        false, ApiErrorCode.PACK_KIND_UNSUPPORTED, "Unsupported pack kind: " + safe(manifest.kind));
  }

  static boolean isModelsPack(AiPackManifestV1 manifest) {
    return "models".equalsIgnoreCase(safe(manifest != null ? manifest.kind : null).trim());
  }

  static boolean isRuntimePack(AiPackManifestV1 manifest) {
    return "runtime".equalsIgnoreCase(safe(manifest != null ? manifest.kind : null).trim());
  }

  static String safe(String s) {
    return s == null ? "" : s;
  }
}
