/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.app.api.AiPackPreflightException;
import io.justsearch.app.api.AiPackPreflightResult;
import io.justsearch.app.api.AiPackImportStatus;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.api.InstalledPacksRecord;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v2: Offline AI Pack import (models-only).
 *
 * <p>Implements the safety posture from {@code docs/explanation/14-ai-pack-spec.md}.
 */
public final class AiPackImportService implements io.justsearch.app.api.AiPackImportService {
  private static final Logger log = LoggerFactory.getLogger(AiPackImportService.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private static final String STATUS_FILE = "pack-import-state.json";

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  private final KnowledgeServerBootstrap knowledgeServer;
  private final EnterprisePolicyService policyService;
  private final PackAllowlistService allowlistService;

  private final Path aiHome;
  private final Path modelsDir;
  private final Path statusPath;
  private final InstalledPacksStore installedPacksStore;
  private final PackInstallOps packInstallOps;

  // Liveness backstop window (575 §17 Face C, polled-state model): a "running" import with no progress
  // for longer than this is treated as a dead owner and reclaimed to terminal on read.
  private static final long STALE_RUNNING_MS = 5 * 60_000L; // 5 min

  private final Object lock = new Object();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AiPackImportStatus status = new AiPackImportStatus();
  private volatile Thread importThread;

  /**
   * Op-lease SPI (tempdoc 617). A pack import writes multi-GB assets into the managed/BYO AI
   * stores on a background thread that outlives its HTTP request, so the request-scoped mutation
   * lease in {@code ApiSecurityFilters} has already been released while the write is still running.
   * Without a lease of its own, {@code POST /api/upgrade/prepare} sees no blocker and the installer
   * can launch mid-write. Defaults to no-op so existing constructors and tests are unaffected.
   */
  private volatile OperationLeaseService operationLeases = OperationLeaseService.noOp();

  /**
   * Late-binds the op-lease SPI. Set by {@code ServicePhase}, which creates the lease service after
   * this service is constructed.
   */
  public void setOperationLeaseService(OperationLeaseService leases) {
    this.operationLeases = leases == null ? OperationLeaseService.noOp() : leases;
  }

  public AiPackImportService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService,
      PackAllowlistService allowlistService) {
    this.onlineAi = Objects.requireNonNull(onlineAi, "onlineAi");
    this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
    this.knowledgeServer = knowledgeServer; // best-effort
    this.policyService = Objects.requireNonNull(policyService, "policyService");
    this.allowlistService = Objects.requireNonNull(allowlistService, "allowlistService");
    this.aiHome = resolveAiHome();
    this.modelsDir = aiHome.resolve("models");
    this.statusPath = aiHome.resolve(STATUS_FILE);
    this.installedPacksStore = new InstalledPacksStore(aiHome);
    this.packInstallOps = new PackInstallOps(installedPacksStore, aiHome, modelsDir);
    loadStatusBestEffort();
  }

  public AiPackImportStatus getStatus() {
    synchronized (lock) {
      reapIfStale();
      return copyStatus(status);
    }
  }

  /**
   * Liveness backstop (tempdoc 575 §17 Face C). Pack import is a <em>polled-state</em> liveness model.
   * If the owner wedges in "running" with no progress past {@link #STALE_RUNNING_MS}, reclaim it to a
   * terminal failed state on read — so the UI never polls a dead "running" forever (the gap this fixes:
   * pack import had no backstop, unlike the worker's recoverStuckJobs reaper).
   */
  private void reapIfStale() {
    if (io.justsearch.app.services.ai.PolledStateLiveness.isStaleRunning(
        status.state, status.updatedAtEpochMs, System.currentTimeMillis(), STALE_RUNNING_MS)) {
      running.set(false);
      fail(
          "STALLED",
          "Pack import stalled — no progress for over "
              + (STALE_RUNNING_MS / 1000)
              + "s; reclaimed by the liveness backstop (575 §17 Face C).",
          null);
    }
  }

  public InstalledPacksRecord getInstalledPacks() {
    return installedPacksStore.load();
  }

  /** Preflight a pack to compute its manifest digest and basic metadata (no install / no writes). */
  public AiPackPreflightResult preflight(Path packPath) {
    Objects.requireNonNull(packPath, "packPath");
    if (!Files.exists(packPath)) {
      throw new AiPackPreflightException(ApiErrorCode.PACK_NOT_FOUND, "Pack path does not exist: " + packPath);
    }
    boolean isDir = Files.isDirectory(packPath);
    boolean isFile = Files.isRegularFile(packPath);
    if (!isDir && !isFile) {
      throw new AiPackPreflightException(
          ApiErrorCode.PACK_INVALID_PATH, "Pack path is not a file or directory: " + packPath);
    }
    return isFile ? preflightZip(packPath) : preflightFolder(packPath);
  }

  public void startImport(Path packPath, boolean allowDowngrade) {
    Objects.requireNonNull(packPath, "packPath");
    synchronized (lock) {
      if (running.get()) {
        throw new IllegalStateException("Pack import already running");
      }
      running.set(true);
      updateState("running", "preflight", "Starting AI Pack import…", null);
    }
    // Register on the CALLING thread, before start(): registering inside the thread leaves a
    // window in which upgrade prepare observes no blocker while the import is about to write.
    // Same race-window closure as BulkReindexHandler.
    OperationLeaseHandle lease =
        operationLeases.register(
            "ai.pack-import",
            OpCriticality.INTERRUPTIBLE_WITH_LOSS,
            3600L,
            Map.of("source", "ai.pack-import", "packPath", packPath.toString()));
    Thread t =
        new Thread(
            () -> {
              boolean ok = false;
              try {
                runImport(packPath, allowDowngrade);
                ok = true;
              } finally {
                running.set(false);
                lease.release(ok ? OpLeaseOutcome.SUCCESS : OpLeaseOutcome.FAILURE);
              }
            },
            "ai-pack-import");
    t.setDaemon(true);
    this.importThread = t;
    try {
      t.start();
    } catch (RuntimeException e) {
      // The thread never ran, so its finally block will not release the lease.
      running.set(false);
      lease.release(OpLeaseOutcome.FAILURE);
      throw e;
    }
  }

  /**
   * Waits for the import thread to terminate.
   *
   * <p>Primarily useful for tests to ensure the thread has fully completed before temp directory
   * cleanup.
   */
  public void awaitThreadCompletion(long timeoutMs) throws InterruptedException {
    Thread t = importThread;
    if (t != null) {
      t.join(timeoutMs);
    }
  }

  // -------------------- Import implementation --------------------

  private void runImport(Path packPath, boolean allowDowngrade) {
    try {
      Files.createDirectories(aiHome);
      Files.createDirectories(modelsDir);
    } catch (Exception e) {
      fail("PACK_IO_ERROR", "Failed to create AI Home directories: " + e.getMessage(), e);
      return;
    }

    if (!Files.exists(packPath)) {
      fail("PACK_NOT_FOUND", "Pack path does not exist: " + packPath, null);
      return;
    }

    boolean isDir = Files.isDirectory(packPath);
    boolean isFile = Files.isRegularFile(packPath);
    if (!isDir && !isFile) {
      fail("PACK_INVALID_PATH", "Pack path is not a file or directory: " + packPath, null);
      return;
    }

    EffectivePolicy effective = policyService.snapshot();

    if (isFile) {
      importZip(packPath, allowDowngrade, effective);
    } else {
      importFolder(packPath, allowDowngrade, effective);
    }
  }

  private AiPackPreflightResult preflightZip(Path zipPath) {
    boolean windows = PackStagingOps.isWindows();
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      Map<String, ZipEntry> entriesByName = new HashMap<>();
      ZipEntry manifestEntry = null;

      var it = zip.entries();
      while (it.hasMoreElements()) {
        ZipEntry e = it.nextElement();
        String name = e.getName();
        if (name == null) continue;
        if (name.contains("\\")) {
          throw new AiPackPreflightException(
              ApiErrorCode.PACK_ZIP_INVALID, "Zip contains backslash path entry: " + name);
        }
        String key = windows ? name.toLowerCase(Locale.ROOT) : name;
        if (entriesByName.putIfAbsent(key, e) != null) {
          throw new AiPackPreflightException(
              ApiErrorCode.PACK_ZIP_DUPLICATE_ENTRY, "Zip contains duplicate entry: " + name);
        }
        if (!e.isDirectory() && name.equalsIgnoreCase(PackStagingOps.MANIFEST_FILE)) {
          manifestEntry = e;
        }
      }

      if (manifestEntry == null) {
        throw new AiPackPreflightException(
            ApiErrorCode.PACK_MANIFEST_MISSING, "Zip is missing " + PackStagingOps.MANIFEST_FILE);
      }

      byte[] manifestBytes;
      try (InputStream in = zip.getInputStream(manifestEntry)) {
        manifestBytes = in.readAllBytes();
      }
      String manifestSha = PackStagingOps.sha256Bytes(manifestBytes);

      try {
        AiPackManifestV1 manifest = MAPPER.readValue(manifestBytes, AiPackManifestV1.class);
        AiPackValidator.ValidationResult vr = PackStagingOps.validateManifest(manifest);
        if (!vr.ok()) {
          throw new AiPackPreflightException(vr.errorCode(), vr.message());
        }
        return new AiPackPreflightResult(
            PackStagingOps.safe(manifest.packId),
            PackStagingOps.safe(manifest.packVersion),
            manifestSha);
      } catch (AiPackPreflightException e) {
        throw e;
      } catch (Exception e) {
        throw new AiPackPreflightException(
            ApiErrorCode.PACK_MANIFEST_INVALID, "Failed to parse pack manifest: " + e.getMessage());
      }
    } catch (AiPackPreflightException e) {
      throw e;
    } catch (Exception e) {
      throw new AiPackPreflightException(
          ApiErrorCode.PACK_IO_ERROR, "Failed to read pack: " + e.getMessage());
    }
  }

  private AiPackPreflightResult preflightFolder(Path packRoot) {
    Path manifestPath = packRoot.resolve(PackStagingOps.MANIFEST_FILE);
    if (!Files.isRegularFile(manifestPath)) {
      throw new AiPackPreflightException(
          ApiErrorCode.PACK_MANIFEST_MISSING,
          "Folder is missing " + PackStagingOps.MANIFEST_FILE + " at " + manifestPath);
    }
    try {
      byte[] manifestBytes = Files.readAllBytes(manifestPath);
      String manifestSha = PackStagingOps.sha256Bytes(manifestBytes);
      try {
        AiPackManifestV1 manifest = MAPPER.readValue(manifestBytes, AiPackManifestV1.class);
        AiPackValidator.ValidationResult vr = PackStagingOps.validateManifest(manifest);
        if (!vr.ok()) {
          throw new AiPackPreflightException(vr.errorCode(), vr.message());
        }
        return new AiPackPreflightResult(
            PackStagingOps.safe(manifest.packId),
            PackStagingOps.safe(manifest.packVersion),
            manifestSha);
      } catch (AiPackPreflightException e) {
        throw e;
      } catch (Exception e) {
        throw new AiPackPreflightException(
            ApiErrorCode.PACK_MANIFEST_INVALID, "Failed to parse pack manifest: " + e.getMessage());
      }
    } catch (AiPackPreflightException e) {
      throw e;
    } catch (Exception e) {
      throw new AiPackPreflightException(
          ApiErrorCode.PACK_IO_ERROR, "Failed to read pack: " + e.getMessage());
    }
  }

  private void importZip(Path zipPath, boolean allowDowngrade, EffectivePolicy effective) {
    updateState("running", "validate", "Reading pack manifest…", null);

    boolean windows = PackStagingOps.isWindows();
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      Map<String, ZipEntry> entriesByName = new HashMap<>();
      ZipEntry manifestEntry = null;

      var it = zip.entries();
      while (it.hasMoreElements()) {
        ZipEntry e = it.nextElement();
        String name = e.getName();
        if (name == null) continue;
        if (name.contains("\\")) {
          fail("PACK_ZIP_INVALID", "Zip contains backslash path entry: " + name, null);
          return;
        }
        String key = windows ? name.toLowerCase(Locale.ROOT) : name;
        if (entriesByName.putIfAbsent(key, e) != null) {
          fail("PACK_ZIP_DUPLICATE_ENTRY", "Zip contains duplicate entry: " + name, null);
          return;
        }
        if (!e.isDirectory() && name.equalsIgnoreCase(PackStagingOps.MANIFEST_FILE)) {
          manifestEntry = e;
        }
      }

      if (manifestEntry == null) {
        fail("PACK_MANIFEST_MISSING", "Zip is missing " + PackStagingOps.MANIFEST_FILE, null);
        return;
      }

      byte[] manifestBytes;
      try (InputStream in = zip.getInputStream(manifestEntry)) {
        manifestBytes = in.readAllBytes();
      }

      // Shared validation pipeline: parse, validate, policy check, size budget.
      PackStagingOps.ValidationResult vr =
          PackStagingOps.parseValidateAndCheckPolicy(manifestBytes, effective, allowlistService);
      if (!vr.ok()) {
        fail(vr.errorCode(), vr.errorMessage(), null);
        return;
      }
      AiPackManifestV1 manifest = vr.manifest();
      String manifestSha = vr.manifestSha();

      status.packId = PackStagingOps.safe(manifest.packId);
      status.packVersion = PackStagingOps.safe(manifest.packVersion);
      status.manifestSha256 = manifestSha;
      status.bytesTotal = vr.totalBytes();
      status.bytesDone = 0;
      touch();

      // Declared payload file set (case-insensitive on Windows).
      Set<String> declared = new HashSet<>();
      for (AiPackManifestV1.FileEntry f : manifest.files) {
        String p = f.pathInPack.trim();
        declared.add(windows ? p.toLowerCase(Locale.ROOT) : p);
      }

      // Fail closed on extra files: any regular file not declared (except the manifest itself).
      for (Map.Entry<String, ZipEntry> e : entriesByName.entrySet()) {
        ZipEntry ze = e.getValue();
        if (ze.isDirectory()) continue;
        String name = ze.getName();
        if (name == null) continue;
        if (name.equalsIgnoreCase(PackStagingOps.MANIFEST_FILE)) continue;
        String k = windows ? name.toLowerCase(Locale.ROOT) : name;
        if (!declared.contains(k)) {
          fail("PACK_EXTRA_FILE", "Pack contains undeclared file: " + name, null);
          return;
        }
      }

      // Stage files with integrity verification.
      Path stageRoot = PackStagingOps.createStageRoot(aiHome, manifest.packId);
      updateState("running", "stage", "Staging pack files…", null);

      for (AiPackManifestV1.FileEntry f : manifest.files) {
        String p = f.pathInPack.trim();
        String k = windows ? p.toLowerCase(Locale.ROOT) : p;
        ZipEntry ze = entriesByName.get(k);
        if (ze == null || ze.isDirectory()) {
          fail("PACK_FILE_MISSING", "Pack is missing declared file: " + p, null);
          return;
        }
        try {
          PackStagingOps.stageFileWithVerification(zip.getInputStream(ze), stageRoot, f);
        } catch (PackStagingOps.PackStagingException e) {
          fail(e.errorCode(), e.getMessage(), e);
          return;
        }
        status.bytesDone += f.sizeBytes;
        touch();
      }

      installFromStaging(stageRoot, manifest, manifestSha, allowDowngrade);
    } catch (PackAbort ignored) {
      return;
    } catch (Exception e) {
      fail("PACK_IMPORT_FAILED", "Pack import failed: " + e.getMessage(), e);
    }
  }

  private void importFolder(Path packRoot, boolean allowDowngrade, EffectivePolicy effective) {
    try {
      updateState("running", "validate", "Reading pack manifest…", null);

      Path manifestPath = packRoot.resolve(PackStagingOps.MANIFEST_FILE);
      if (!Files.isRegularFile(manifestPath)) {
        fail(
            "PACK_MANIFEST_MISSING",
            "Folder is missing " + PackStagingOps.MANIFEST_FILE,
            null);
        return;
      }

      byte[] manifestBytes;
      try {
        manifestBytes = Files.readAllBytes(manifestPath);
      } catch (Exception e) {
        fail("PACK_IO_ERROR", "Failed to read pack manifest: " + e.getMessage(), e);
        return;
      }

      // Shared validation pipeline: parse, validate, policy check, size budget.
      PackStagingOps.ValidationResult vr =
          PackStagingOps.parseValidateAndCheckPolicy(manifestBytes, effective, allowlistService);
      if (!vr.ok()) {
        fail(vr.errorCode(), vr.errorMessage(), null);
        return;
      }
      AiPackManifestV1 manifest = vr.manifest();
      String manifestSha = vr.manifestSha();

      status.packId = PackStagingOps.safe(manifest.packId);
      status.packVersion = PackStagingOps.safe(manifest.packVersion);
      status.manifestSha256 = manifestSha;
      status.bytesTotal = vr.totalBytes();
      status.bytesDone = 0;
      touch();

      // Fail closed on extra files: allow only manifest at root + declared files.
      Set<String> declared = new HashSet<>();
      boolean windows = PackStagingOps.isWindows();
      for (AiPackManifestV1.FileEntry f : manifest.files) {
        String p = f.pathInPack.trim();
        declared.add(windows ? p.toLowerCase(Locale.ROOT) : p);
      }
      try {
        Files.walkFileTree(
            packRoot,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file == null) return FileVisitResult.CONTINUE;
                Path rel = packRoot.relativize(file);
                String relStr = rel.toString().replace('\\', '/');
                if (relStr.equalsIgnoreCase(PackStagingOps.MANIFEST_FILE)) {
                  return FileVisitResult.CONTINUE;
                }
                String key = windows ? relStr.toLowerCase(Locale.ROOT) : relStr;
                if (!declared.contains(key)) {
                  fail("PACK_EXTRA_FILE", "Pack contains undeclared file: " + relStr, null);
                  throw new PackAbort();
                }
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (PackAbort ignored) {
        return;
      } catch (Exception e) {
        fail("PACK_IO_ERROR", "Failed to scan pack folder: " + e.getMessage(), e);
        return;
      }

      // Stage files with integrity verification.
      Path stageRoot = PackStagingOps.createStageRoot(aiHome, manifest.packId);
      updateState("running", "stage", "Staging pack files…", null);

      for (AiPackManifestV1.FileEntry f : manifest.files) {
        String rel = f.pathInPack.trim();
        Path src = packRoot.resolve(rel).normalize();
        if (!src.startsWith(packRoot)) {
          fail("PACK_PATH_INVALID", "Declared file normalizes outside pack root: " + rel, null);
          return;
        }
        if (!PackStagingOps.isSafeRegularFile(src)) {
          fail(
              "PACK_SYMLINK_REJECTED",
              "Pack file is not a regular file (symlink/junction?): " + rel,
              null);
          return;
        }
        try {
          PackStagingOps.stageFileWithVerification(Files.newInputStream(src), stageRoot, f);
        } catch (PackStagingOps.PackStagingException e) {
          fail(e.errorCode(), e.getMessage(), e);
          return;
        }
        status.bytesDone += f.sizeBytes;
        touch();
      }

      installFromStaging(stageRoot, manifest, manifestSha, allowDowngrade);
    } catch (PackAbort ignored) {
      return;
    } catch (Exception e) {
      fail("PACK_IMPORT_FAILED", "Pack import failed: " + e.getMessage(), e);
    }
  }

  private void installFromStaging(
      Path stageRoot,
      AiPackManifestV1 manifest,
      String manifestSha,
      boolean allowDowngrade) {
    updateState("running", "install", "Installing AI Pack…", null);

    if (PackStagingOps.isRuntimePack(manifest)) {
      try {
        packInstallOps.installRuntime(stageRoot, manifest, manifestSha, allowDowngrade);
      } catch (PackInstallOps.PackInstallException e) {
        fail(e.errorCode(), e.getMessage(), e);
        return;
      }
      updateState("completed", "done", "AI Pack installed.", null);
      return;
    }

    PackInstallOps.ModelsInstallResult result;
    try {
      result = packInstallOps.installModels(stageRoot, manifest, manifestSha, allowDowngrade);
    } catch (PackInstallOps.PackInstallException e) {
      fail(e.errorCode(), e.getMessage(), e);
      return;
    }

    updateState("running", "apply_settings", "Applying configuration…", null);
    try {
      applySettings(result.chatPath());
    } catch (Exception e) {
      fail("PACK_APPLY_FAILED", "Failed to apply installed model paths: " + e.getMessage(), e);
      return;
    }

    // Record installed pack AFTER successful settings application (matches original ordering).
    packInstallOps.recordPack(result.pack());

    updateState("running", "restart_worker", "Restarting worker…", null);
    tryRestartWorkerBestEffort();

    updateState("completed", "done", "AI Pack installed.", null);
  }

  // -------------------- Settings + worker restart --------------------

  private void applySettings(Path chatModel) {
    UiSettings s = settingsStore.load();
    s.setLlmModelPath(chatModel.toAbsolutePath().toString());
    settingsStore.save(s);

    // No sysprop write here — same reasoning as AiInstallService.applySettings (883 §C.5c residue,
    // #605 review S1). The save above and the rebuild below deliver this path at ordinal 300, and
    // the sysprop copy only added an ordinal-500 claim that a `.source` marker then had to correct.

    // Rebuild ConfigStore so readers see updated model paths.
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);

    OnlineAiService onlineAi = this.onlineAi;
    if (onlineAi instanceof OnlineAiRuntimeControl control) {
      control.applyRuntimeOverrides(
          s.getLlmModelPath(),
          s.getContextLength(),
          s.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS);
    }
  }

  /**
   * Lane F stage A item A11: there is no worker process to restart. The import used to replace the
   * Worker child process so the imported pack was picked up immediately; the index half runs in
   * this process now, so the equivalent is an Engine restart — the user's action. Logged with the
   * stable code rather than dropped silently, so the import's own log says why the pack is not
   * live yet. Stage A §10, "restart-as-reload".
   */
  private void tryRestartWorkerBestEffort() {
    if (knowledgeServer != null && knowledgeServer.hasClient()) {
      log.info(
          "Pack import complete; an Engine restart is required to load it ({})",
          io.justsearch.app.services.worker.RestartRequiredException.CODE);
    }
  }

  // -------------------- Status helpers --------------------

  private void updateState(String state, String phase, String message, String errorCode) {
    synchronized (lock) {
      status.state = safe(state);
      status.phase = safe(phase);
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      status.updatedAtEpochMs = System.currentTimeMillis();
      if (status.startedAtEpochMs <= 0 && "running".equalsIgnoreCase(state)) {
        status.startedAtEpochMs = System.currentTimeMillis();
      }
      persistStatusBestEffort();
    }
  }

  private void fail(String errorCode, String message, Exception e) {
    log.warn("AI Pack import failed: {} {}", errorCode, message, e);
    synchronized (lock) {
      status.state = "failed";
      status.phase = safe(status.phase);
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      status.updatedAtEpochMs = System.currentTimeMillis();
      persistStatusBestEffort();
    }
  }

  private void touch() {
    synchronized (lock) {
      status.updatedAtEpochMs = System.currentTimeMillis();
      persistStatusBestEffort();
    }
  }

  private void persistStatusBestEffort() {
    try {
      AtomicFileWrites.replace(statusPath, MAPPER.writeValueAsBytes(status));
    } catch (Exception e) {
      log.debug("persistStatusBestEffort failed: {}", e.getMessage());
    }
  }

  private void loadStatusBestEffort() {
    try {
      if (!Files.exists(statusPath)) return;
      AiPackImportStatus loaded = MAPPER.readValue(statusPath.toFile(), AiPackImportStatus.class);
      if (loaded == null) return;
      synchronized (lock) {
        status.state = safe(loaded.state);
        status.phase = safe(loaded.phase);
        status.message = safe(loaded.message);
        status.errorCode = safe(loaded.errorCode);
        status.packId = safe(loaded.packId);
        status.packVersion = safe(loaded.packVersion);
        status.manifestSha256 = safe(loaded.manifestSha256);
        status.bytesTotal = loaded.bytesTotal;
        status.bytesDone = loaded.bytesDone;
        status.startedAtEpochMs = loaded.startedAtEpochMs;
        status.updatedAtEpochMs = loaded.updatedAtEpochMs;
        // No import thread survives a Head restart. The installed-packs manifest and staging
        // directories, not this progress projection, are the durable repair authorities.
        if (!status.state.isBlank() && !"idle".equalsIgnoreCase(status.state)) {
          status.state = "idle";
          status.phase = "";
          status.message = "";
          status.errorCode = "";
          status.bytesTotal = 0;
          status.bytesDone = 0;
          status.startedAtEpochMs = 0;
          status.updatedAtEpochMs = System.currentTimeMillis();
          persistStatusBestEffort();
        }
      }
    } catch (Exception e) {
      log.debug("loadStatusBestEffort failed: {}", e.getMessage());
    }
  }

  private static AiPackImportStatus copyStatus(AiPackImportStatus s) {
    AiPackImportStatus c = new AiPackImportStatus();
    c.state = safe(s.state);
    c.phase = safe(s.phase);
    c.message = safe(s.message);
    c.errorCode = safe(s.errorCode);
    c.packId = safe(s.packId);
    c.packVersion = safe(s.packVersion);
    c.manifestSha256 = safe(s.manifestSha256);
    c.bytesTotal = s.bytesTotal;
    c.bytesDone = s.bytesDone;
    c.startedAtEpochMs = s.startedAtEpochMs;
    c.updatedAtEpochMs = s.updatedAtEpochMs;
    return c;
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  // -------------------- IO helpers --------------------

  private static Path resolveAiHome() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception e) {
      log.debug("resolveAiHome: env lookup failed: {}", e.getMessage());
    }
    try {
      return PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return Path.of(System.getProperty("user.dir"));
    }
  }

  /** Internal control-flow exception to abort nested visitors cleanly. */
  private static final class PackAbort extends RuntimeException {}

}
