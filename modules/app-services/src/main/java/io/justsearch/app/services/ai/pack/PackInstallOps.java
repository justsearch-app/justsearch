/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import io.justsearch.app.api.InstalledPacksRecord.InstalledFile;
import io.justsearch.app.api.InstalledPacksRecord.InstalledPack;
import io.justsearch.app.api.InstalledPacksRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installation logic for AI Packs (models and runtime).
 *
 * <p>Extracted from {@link AiPackImportService} to separate file-move + record-keeping concerns
 * from orchestration, status management, and settings application.
 */
final class PackInstallOps {
  private static final Logger log = LoggerFactory.getLogger(PackInstallOps.class);

  private final InstalledPacksStore installedPacksStore;
  private final Path aiHome;
  private final Path modelsDir;

  PackInstallOps(InstalledPacksStore installedPacksStore, Path aiHome, Path modelsDir) {
    this.installedPacksStore = installedPacksStore;
    this.aiHome = aiHome;
    this.modelsDir = modelsDir;
  }

  // -------------------- Result types --------------------

  record ModelsInstallResult(Path chatPath, Path embedPath, InstalledPack pack) {}

  // -------------------- Error type --------------------

  static final class PackInstallException extends RuntimeException {
    private final String errorCode;

    PackInstallException(String errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    PackInstallException(String errorCode, String message, Throwable cause) {
      super(message, cause);
      this.errorCode = errorCode;
    }

    String errorCode() {
      return errorCode;
    }
  }

  // -------------------- Shared helpers --------------------

  /**
   * Pre-move downgrade check. Throws {@link PackInstallException} if downgrade is detected and not
   * allowed.
   */
  static void checkDowngrade(
      InstalledPacksStore store,
      String packId,
      String packVersion,
      boolean allowDowngrade) {
    if (allowDowngrade) return;
    InstalledPacksRecord current = store.load();
    if (current.packs == null) return;
    for (InstalledPack p : current.packs) {
      if (p != null
          && PackStagingOps.safe(p.packId).equals(PackStagingOps.safe(packId))) {
        int cmp = InstalledPacksStore.compareSemverLike(packVersion, p.packVersion);
        if (cmp < 0) {
          throw new PackInstallException(
              "PACK_DOWNGRADE_BLOCKED",
              "Refusing silent downgrade for packId="
                  + PackStagingOps.safe(packId)
                  + " from "
                  + PackStagingOps.safe(p.packVersion)
                  + " to "
                  + PackStagingOps.safe(packVersion));
        }
        return;
      }
    }
  }

  // -------------------- Models installation --------------------

  /**
   * Install a models pack from staging. Moves chat/embed model files to the models directory,
   * records the pack, and returns the destination paths for settings application.
   *
   * @throws PackInstallException on any installation failure
   */
  ModelsInstallResult installModels(
      Path stageRoot,
      AiPackManifestV1 manifest,
      String manifestSha,
      boolean allowDowngrade) {
    if (manifest == null) {
      throw new PackInstallException("PACK_MANIFEST_INVALID", "Pack manifest is missing.");
    }

    // Resolve required role -> file mapping.
    String chatFileId = null;
    String embedFileId = null;
    for (AiPackManifestV1.AssetEntry a : manifest.assets) {
      if (a == null) continue;
      if ("model.chat".equalsIgnoreCase(a.role)) {
        chatFileId = a.fileId;
      } else if ("model.embedding".equalsIgnoreCase(a.role)) {
        embedFileId = a.fileId;
      }
    }
    if (chatFileId == null || embedFileId == null) {
      throw new PackInstallException(
          "PACK_ASSET_MISSING_REQUIRED",
          "Pack must include model.chat and model.embedding assets.");
    }

    AiPackManifestV1.FileEntry chat = null;
    AiPackManifestV1.FileEntry embed = null;
    for (AiPackManifestV1.FileEntry f : manifest.files) {
      if (f == null || f.id == null) continue;
      if (f.id.equals(chatFileId)) chat = f;
      if (f.id.equals(embedFileId)) embed = f;
    }
    if (chat == null || embed == null) {
      throw new PackInstallException(
          "PACK_ASSET_UNKNOWN_FILE", "Pack asset references unknown files.");
    }

    // Pre-move downgrade check.
    checkDowngrade(installedPacksStore, manifest.packId, manifest.packVersion, allowDowngrade);

    // Install models into AI Home.
    Path stagedChat = stageRoot.resolve(chat.pathInPack).normalize();
    Path stagedEmbed = stageRoot.resolve(embed.pathInPack).normalize();
    if (!stagedChat.startsWith(stageRoot) || !stagedEmbed.startsWith(stageRoot)) {
      throw new PackInstallException("PACK_PATH_INVALID", "Staged paths invalid.");
    }
    if (!Files.isRegularFile(stagedChat) || !Files.isRegularFile(stagedEmbed)) {
      throw new PackInstallException("PACK_STAGE_FAILED", "Staged model files missing.");
    }

    String chatFilename = Path.of(chat.pathInPack).getFileName().toString();
    String embedFilename = Path.of(embed.pathInPack).getFileName().toString();
    Path destChat = modelsDir.resolve(chatFilename);
    Path destEmbed = modelsDir.resolve(embedFilename);

    try {
      PackStagingOps.moveAtomicBestEffort(stagedChat, destChat);
      PackStagingOps.moveAtomicBestEffort(stagedEmbed, destEmbed);
      try {
        io.justsearch.app.inference.ChatArtifactIdentity.promoted(
            stagedChat, destChat, chat.sizeBytes, chat.sha256);
      } catch (java.io.IOException unavailable) {
        log.debug("Pack chat verification receipt could not follow promotion", unavailable);
      }
    } catch (Exception e) {
      throw new PackInstallException(
          "PACK_INSTALL_FAILED", "Failed to install models: " + e.getMessage(), e);
    }

    // Record installed pack.
    InstalledPack pack = new InstalledPack();
    pack.packId = manifest.packId;
    pack.packVersion = manifest.packVersion;
    pack.kind = "models";
    pack.manifestSha256 = manifestSha;
    pack.files =
        List.of(
            installedFile(
                "model.chat",
                null,
                aiHome.relativize(destChat).toString().replace('\\', '/'),
                chat.sha256,
                chat.sizeBytes),
            installedFile(
                "model.embedding",
                null,
                aiHome.relativize(destEmbed).toString().replace('\\', '/'),
                embed.sha256,
                embed.sizeBytes));
    return new ModelsInstallResult(destChat, destEmbed, pack);
  }

  // -------------------- Runtime installation --------------------

  /**
   * Install a runtime pack from staging. Moves runtime files into variant directories and records
   * the pack.
   *
   * @throws PackInstallException on any installation failure
   */
  void installRuntime(
      Path stageRoot,
      AiPackManifestV1 manifest,
      String manifestSha,
      boolean allowDowngrade) {
    if (manifest == null) {
      throw new PackInstallException("PACK_MANIFEST_INVALID", "Pack manifest is missing.");
    }
    String variantId =
        PackStagingOps.safe(manifest.variantId).trim();
    if (variantId.isBlank()) {
      throw new PackInstallException(
          "PACK_RUNTIME_VARIANT_INVALID", "Runtime pack is missing variantId.");
    }

    // Pre-move downgrade check.
    checkDowngrade(installedPacksStore, manifest.packId, manifest.packVersion, allowDowngrade);

    // Resolve fileId -> file entry (validated earlier).
    Map<String, AiPackManifestV1.FileEntry> byId = new HashMap<>();
    for (AiPackManifestV1.FileEntry f : manifest.files) {
      if (f == null || f.id == null) continue;
      byId.put(f.id.trim(), f);
    }

    // Map fileId -> asset.
    Map<String, AiPackManifestV1.AssetEntry> assetByFileId = new HashMap<>();
    boolean needsOnnxRuntime = false;
    for (AiPackManifestV1.AssetEntry a : manifest.assets) {
      if (a == null || a.fileId == null || a.role == null) continue;
      assetByFileId.put(a.fileId.trim(), a);
      String role = a.role.trim();
      if ("runtime.onnxruntime".equalsIgnoreCase(role)
          || "runtime.onnxruntimeFile".equalsIgnoreCase(role)) {
        needsOnnxRuntime = true;
      }
    }

    // Destination roots: variants/<variantId>/.
    Path llamaVariantDir =
        aiHome
            .resolve("native-bin")
            .resolve("llama-server")
            .resolve("variants")
            .resolve(variantId);
    Path onnxVariantDir =
        aiHome
            .resolve("native-bin")
            .resolve("onnxruntime")
            .resolve("variants")
            .resolve(variantId);
    try {
      Files.createDirectories(llamaVariantDir);
      if (needsOnnxRuntime) {
        Files.createDirectories(onnxVariantDir);
      }
    } catch (Exception e) {
      throw new PackInstallException(
          "PACK_IO_ERROR",
          "Failed to create runtime variant directories: " + e.getMessage(),
          e);
    }

    // Prevent collisions when mapping to destination filenames.
    boolean windows = PackStagingOps.isWindows();
    Map<Path, Set<String>> destNamesByRoot = new HashMap<>();
    for (AiPackManifestV1.FileEntry f : manifest.files) {
      if (f == null || f.pathInPack == null) continue;
      AiPackManifestV1.AssetEntry a = assetByFileId.get(f.id == null ? "" : f.id.trim());
      if (a == null || a.role == null) {
        throw new PackInstallException(
            "PACK_ASSET_INVALID",
            "Runtime pack asset mapping missing for fileId: " + PackStagingOps.safe(f.id));
      }
      Path destRoot = resolveRuntimeDestRoot(a.role.trim(), llamaVariantDir, onnxVariantDir);

      String filename = PackStagingOps.filenameFromPathInPack(f.pathInPack);
      String key = windows ? filename.toLowerCase(Locale.ROOT) : filename;
      Set<String> destNames = destNamesByRoot.computeIfAbsent(destRoot, __ -> new HashSet<>());
      if (!destNames.add(key)) {
        throw new PackInstallException(
            "PACK_RUNTIME_DEST_COLLISION",
            "Runtime pack contains multiple files that would install to the same filename: "
                + filename
                + " (destRoot="
                + aiHome.relativize(destRoot).toString().replace('\\', '/')
                + ")");
      }
    }

    // Install all runtime files from staging into the variant directory.
    for (AiPackManifestV1.FileEntry f : manifest.files) {
      if (f == null || f.pathInPack == null) continue;
      AiPackManifestV1.AssetEntry a = assetByFileId.get(f.id == null ? "" : f.id.trim());
      if (a == null || a.role == null) {
        throw new PackInstallException(
            "PACK_ASSET_INVALID",
            "Runtime pack asset mapping missing for fileId: " + PackStagingOps.safe(f.id));
      }
      Path destRoot = resolveRuntimeDestRoot(a.role.trim(), llamaVariantDir, onnxVariantDir);

      String rel = f.pathInPack.trim();
      Path staged = stageRoot.resolve(rel).normalize();
      if (!staged.startsWith(stageRoot)) {
        throw new PackInstallException("PACK_PATH_INVALID", "Staged paths invalid.");
      }
      if (!Files.isRegularFile(staged)) {
        throw new PackInstallException("PACK_STAGE_FAILED", "Staged runtime files missing.");
      }

      String filename = PackStagingOps.filenameFromPathInPack(rel);
      Path dest = destRoot.resolve(filename);
      try {
        PackStagingOps.moveAtomicBestEffort(staged, dest);
      } catch (Exception e) {
        throw new PackInstallException(
            "PACK_INSTALL_FAILED",
            "Failed to install runtime file: " + filename + " (" + e.getMessage() + ")",
            e);
      }
    }

    // Record installed pack.
    InstalledPack pack = new InstalledPack();
    pack.packId = manifest.packId;
    pack.packVersion = manifest.packVersion;
    pack.kind = "runtime";
    pack.manifestSha256 = manifestSha;
    java.util.ArrayList<InstalledFile> installed = new java.util.ArrayList<>();
    for (AiPackManifestV1.AssetEntry a : manifest.assets) {
      if (a == null || a.fileId == null || a.role == null) continue;
      AiPackManifestV1.FileEntry f = byId.get(a.fileId.trim());
      if (f == null) continue;
      Path destRoot = resolveRuntimeDestRoot(a.role.trim(), llamaVariantDir, onnxVariantDir);
      String filename = PackStagingOps.filenameFromPathInPack(f.pathInPack);
      Path dest = destRoot.resolve(filename);
      installed.add(
          installedFile(
              a.role.trim(),
              variantId,
              aiHome.relativize(dest).toString().replace('\\', '/'),
              f.sha256,
              f.sizeBytes));
    }
    pack.files = installed;

    try {
      installedPacksStore.upsertPack(pack, true); // allowDowngrade=true: already pre-checked
    } catch (Exception e) {
      log.warn("Failed to update installed packs record (best-effort)", e);
    }
  }

  // -------------------- Record persistence --------------------

  /** Best-effort write of an installed pack record. */
  void recordPack(InstalledPack pack) {
    try {
      installedPacksStore.upsertPack(pack, true);
    } catch (Exception e) {
      log.warn("Failed to update installed packs record (best-effort)", e);
    }
  }

  // -------------------- Helpers --------------------

  private static Path resolveRuntimeDestRoot(
      String role, Path llamaVariantDir, Path onnxVariantDir) {
    if ("runtime.onnxruntime".equalsIgnoreCase(role)
        || "runtime.onnxruntimeFile".equalsIgnoreCase(role)) {
      return onnxVariantDir;
    }
    return llamaVariantDir;
  }

  static InstalledFile installedFile(
      String role, String variantId, String destPath, String sha256, long sizeBytes) {
    InstalledFile f = new InstalledFile();
    f.role = role;
    f.variantId = variantId;
    f.destPath = destPath;
    f.sha256 = sha256;
    f.sizeBytes = sizeBytes;
    return f;
  }
}
