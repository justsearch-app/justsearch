/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.app.api.ApiErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** v2: validates a models-only {@link AiPackManifestV1} (fail closed). */
public final class AiPackValidator {
  private AiPackValidator() {}

  public record ValidationResult(boolean ok, ApiErrorCode errorCode, String message) {
    public static ValidationResult pass() {
      return new ValidationResult(true, null, null);
    }
  }

  public static ValidationResult validateModelsOnly(AiPackManifestV1 m) {
    if (m == null) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_MISSING, "Pack manifest is missing.");
    }
    if (m.schemaVersion != 1) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_SCHEMA_UNSUPPORTED, "Unsupported pack schemaVersion.");
    }
    if (isBlank(m.packId) || isBlank(m.packVersion)) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "packId and packVersion are required.");
    }
    if (!"models".equalsIgnoreCase(safe(m.kind))) {
      return new ValidationResult(false, ApiErrorCode.PACK_KIND_UNSUPPORTED, "v2 only supports models-only packs.");
    }
    if (m.files == null || m.files.isEmpty()) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "Pack manifest has no files.");
    }
    if (m.assets == null || m.assets.isEmpty()) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "Pack manifest has no assets.");
    }

    boolean windows = io.justsearch.configuration.PlatformPaths.isWindows();
    Set<String> fileIds = new HashSet<>();
    Set<String> paths = new HashSet<>();
    Map<String, AiPackManifestV1.FileEntry> byId = new HashMap<>();
    for (AiPackManifestV1.FileEntry f : m.files) {
      if (f == null) continue;
      if (isBlank(f.id) || isBlank(f.pathInPack) || isBlank(f.sha256) || f.sizeBytes <= 0) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_INVALID, "Invalid file entry in pack manifest.");
      }
      String id = f.id.trim();
      if (!fileIds.add(id)) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_DUPLICATE_ID, "Duplicate file id in pack manifest: " + id);
      }
      String p = f.pathInPack.trim();
      ValidationResult pathOk = validatePathInPack(p);
      if (!pathOk.ok) return pathOk;
      String uniq = windows ? p.toLowerCase(Locale.ROOT) : p;
      if (!paths.add(uniq)) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_DUPLICATE_PATH, "Duplicate path in pack manifest: " + p);
      }
      String sha = normalizeSha256(f.sha256);
      if (sha == null) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_SHA_INVALID, "Invalid SHA-256 for file: " + p);
      }
      f.sha256 = sha; // normalize for downstream comparisons/logging
      byId.put(id, f);
    }

    boolean hasChat = false;
    boolean hasEmbed = false;
    Set<String> referencedFileIds = new HashSet<>();
    for (AiPackManifestV1.AssetEntry a : m.assets) {
      if (a == null) continue;
      if (isBlank(a.role) || isBlank(a.fileId)) {
        return new ValidationResult(false, ApiErrorCode.PACK_ASSET_INVALID, "Invalid asset entry in pack manifest.");
      }
      String role = a.role.trim();
      String fileId = a.fileId.trim();
      if (!byId.containsKey(fileId)) {
        return new ValidationResult(
            false, ApiErrorCode.PACK_ASSET_UNKNOWN_FILE, "Asset references unknown fileId: " + fileId);
      }
      referencedFileIds.add(fileId);
      // v2 supports exactly these roles.
      if ("model.chat".equalsIgnoreCase(role)) {
        hasChat = true;
      } else if ("model.embedding".equalsIgnoreCase(role)) {
        hasEmbed = true;
      } else {
        return new ValidationResult(
            false,
            ApiErrorCode.PACK_ASSET_ROLE_UNSUPPORTED,
            "Unsupported asset role for v2: " + role);
      }
    }
    if (!hasChat || !hasEmbed) {
      return new ValidationResult(
          false,
          ApiErrorCode.PACK_ASSET_MISSING_REQUIRED,
          "Pack must include model.chat and model.embedding assets.");
    }
    if (referencedFileIds.size() != byId.size()) {
      return new ValidationResult(
          false,
          ApiErrorCode.PACK_UNUSED_FILES,
          "All files in a v2 pack must be referenced by an asset role (models-only v2 is fail-closed).");
    }

    return ValidationResult.pass();
  }

  /** v3: validates a runtime pack {@link AiPackManifestV1} (fail closed). */
  public static ValidationResult validateRuntimePack(AiPackManifestV1 m) {
    if (m == null) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_MISSING, "Pack manifest is missing.");
    }
    if (m.schemaVersion != 1) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_SCHEMA_UNSUPPORTED, "Unsupported pack schemaVersion.");
    }
    if (isBlank(m.packId) || isBlank(m.packVersion)) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "packId and packVersion are required.");
    }
    if (!"runtime".equalsIgnoreCase(safe(m.kind))) {
      return new ValidationResult(false, ApiErrorCode.PACK_KIND_UNSUPPORTED, "Unsupported pack kind for runtime validation.");
    }
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      return new ValidationResult(false, ApiErrorCode.PACK_KIND_UNSUPPORTED,
          "Runtime packs are Windows-only; configure JUSTSEARCH_SERVER_EXE on Linux.");
    }
    if (isBlank(m.variantId) || !isValidVariantId(m.variantId)) {
      return new ValidationResult(false, ApiErrorCode.PACK_RUNTIME_VARIANT_INVALID, "variantId is required for runtime packs.");
    }
    if (m.files == null || m.files.isEmpty()) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "Pack manifest has no files.");
    }
    if (m.assets == null || m.assets.isEmpty()) {
      return new ValidationResult(false, ApiErrorCode.PACK_MANIFEST_INVALID, "Pack manifest has no assets.");
    }

    boolean windows = io.justsearch.configuration.PlatformPaths.isWindows();
    Set<String> fileIds = new HashSet<>();
    Set<String> paths = new HashSet<>();
    Map<String, AiPackManifestV1.FileEntry> byId = new HashMap<>();
    for (AiPackManifestV1.FileEntry f : m.files) {
      if (f == null) continue;
      if (isBlank(f.id) || isBlank(f.pathInPack) || isBlank(f.sha256) || f.sizeBytes <= 0) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_INVALID, "Invalid file entry in pack manifest.");
      }
      String id = f.id.trim();
      if (!fileIds.add(id)) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_DUPLICATE_ID, "Duplicate file id in pack manifest: " + id);
      }
      String p = f.pathInPack.trim();
      ValidationResult pathOk = validatePathInPack(p);
      if (!pathOk.ok) return pathOk;
      String filename = filenameFromPathInPack(p);
      if (!isAllowedRuntimeFilename(filename)) {
        return new ValidationResult(
            false,
            ApiErrorCode.PACK_RUNTIME_FILE_UNSUPPORTED,
            "Unsupported runtime filename in pack: " + filename);
      }
      String uniq = windows ? p.toLowerCase(Locale.ROOT) : p;
      if (!paths.add(uniq)) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_DUPLICATE_PATH, "Duplicate path in pack manifest: " + p);
      }
      String sha = normalizeSha256(f.sha256);
      if (sha == null) {
        return new ValidationResult(false, ApiErrorCode.PACK_FILE_SHA_INVALID, "Invalid SHA-256 for file: " + p);
      }
      f.sha256 = sha; // normalize for downstream comparisons/logging
      byId.put(id, f);
    }

    boolean hasExe = false;
    Set<String> referencedFileIds = new HashSet<>();
    Set<String> assetFileIds = new HashSet<>();
    for (AiPackManifestV1.AssetEntry a : m.assets) {
      if (a == null) continue;
      if (isBlank(a.role) || isBlank(a.fileId)) {
        return new ValidationResult(false, ApiErrorCode.PACK_ASSET_INVALID, "Invalid asset entry in pack manifest.");
      }
      String role = a.role.trim();
      String fileId = a.fileId.trim();
      if (!byId.containsKey(fileId)) {
        return new ValidationResult(false, ApiErrorCode.PACK_ASSET_UNKNOWN_FILE, "Asset references unknown fileId: " + fileId);
      }
      if (!assetFileIds.add(fileId)) {
        return new ValidationResult(
            false,
            ApiErrorCode.PACK_ASSET_DUPLICATE_FILE,
            "Runtime packs must not reference the same fileId from multiple assets: " + fileId);
      }
      if (!isBlank(a.variantId) && !a.variantId.trim().equalsIgnoreCase(m.variantId.trim())) {
        return new ValidationResult(
            false,
            ApiErrorCode.PACK_RUNTIME_VARIANT_MISMATCH,
            "Asset variantId must match manifest variantId for runtime packs.");
      }

      referencedFileIds.add(fileId);
      if ("runtime.llamaServer".equalsIgnoreCase(role)) {
        hasExe = true;
        String filename = filenameFromPathInPack(byId.get(fileId).pathInPack);
        if (!NativeTargets.current().executableName("llama-server").equalsIgnoreCase(filename)) {
          return new ValidationResult(
              false,
              ApiErrorCode.PACK_RUNTIME_EXE_INVALID,
              "runtime.llamaServer must reference llama-server.exe (got: " + filename + ")");
        }
      } else if ("runtime.runtimeFile".equalsIgnoreCase(role)) {
        // ok
      } else if ("runtime.onnxruntime".equalsIgnoreCase(role) || "runtime.onnxruntimeFile".equalsIgnoreCase(role)) {
        // ok (optional): allows runtime packs to ship a matching ONNX Runtime native variant alongside llama-server.
      } else {
        return new ValidationResult(
            false,
            ApiErrorCode.PACK_ASSET_ROLE_UNSUPPORTED,
            "Unsupported asset role for runtime pack: " + role);
      }
    }
    if (!hasExe) {
      return new ValidationResult(
          false,
          ApiErrorCode.PACK_ASSET_MISSING_REQUIRED,
          "Runtime pack must include a runtime.llamaServer asset.");
    }
    if (referencedFileIds.size() != byId.size()) {
      return new ValidationResult(
          false,
          ApiErrorCode.PACK_UNUSED_FILES,
          "All files in a runtime pack must be referenced by an asset role (fail-closed).");
    }
    return ValidationResult.pass();
  }

  private static ValidationResult validatePathInPack(String pathInPack) {
    String p = pathInPack;
    if (p.contains("\\")) {
      return new ValidationResult(false, ApiErrorCode.PACK_PATH_INVALID, "pathInPack must use '/' separators only.");
    }
    if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
      return new ValidationResult(false, ApiErrorCode.PACK_PATH_INVALID, "pathInPack must be relative.");
    }
    if (!p.startsWith("payload/")) {
      return new ValidationResult(false, ApiErrorCode.PACK_PATH_INVALID, "pathInPack must start with 'payload/'.");
    }
    // reject .. segments (simple, deterministic)
    for (String seg : p.split("/")) {
      if ("..".equals(seg)) {
        return new ValidationResult(false, ApiErrorCode.PACK_PATH_INVALID, "pathInPack must not contain '..' segments.");
      }
      if (seg.isEmpty()) {
        // Avoid weird // paths.
        return new ValidationResult(false, ApiErrorCode.PACK_PATH_INVALID, "pathInPack must not contain empty segments.");
      }
    }
    return ValidationResult.pass();
  }

  private static String normalizeSha256(String value) {
    if (value == null) return null;
    String s = value.trim().toLowerCase(Locale.ROOT);
    if (s.length() != 64) return null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!ok) return null;
    }
    return s;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }


  private static boolean isValidVariantId(String raw) {
    if (raw == null) return false;
    String s = raw.trim();
    if (s.isBlank() || s.length() > 42) return false;
    // Pattern: ^[a-z0-9][a-z0-9._-]{0,40}$ (case-insensitive)
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      if (!ok) return false;
    }
    char first = s.charAt(0);
    return (first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || (first >= '0' && first <= '9');
  }

  private static String filenameFromPathInPack(String pathInPack) {
    if (pathInPack == null) return "";
    String p = pathInPack;
    int idx = p.lastIndexOf('/');
    return idx >= 0 ? p.substring(idx + 1) : p;
  }

  private static boolean isAllowedRuntimeFilename(String filename) {
    if (filename == null) return false;
    String f = filename.trim();
    if (f.isBlank()) return false;
    String lower = f.toLowerCase(Locale.ROOT);
    if (NativeTargets.current().executableName("llama-server").equals(lower)) return true;
    if (lower.endsWith(".dll")) return true;
    if (lower.equals("runtime-version.txt")) return true;
    if (lower.startsWith("license")) return true;
    if (lower.startsWith("notice")) return true;
    return false;
  }
}
