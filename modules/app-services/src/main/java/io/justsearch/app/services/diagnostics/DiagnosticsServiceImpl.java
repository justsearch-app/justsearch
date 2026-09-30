/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.StatusSnapshotProvider;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemAccess;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.DiagnosticFileRetention;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Production implementation of {@link DiagnosticsService}, extracted from
 * {@code DiagnosticsController.exportDiagnostics} as part of tempdoc 519 §9 Block B3 / Step 3.
 *
 * <p>Composes:
 * <ul>
 *   <li>{@link EnterprisePolicyService} (B1) — policy snapshot included in the zip.</li>
 *   <li>{@link GpuCapabilitiesService} (gpu-bridge module) — GPU capability snapshot.</li>
 *   <li>{@link DebugStateProvider} — debug-state snapshot SPI (impl in
 *       {@code modules/ui/.../api/DebugStateController}).</li>
 *   <li>{@link StatusSnapshotProvider} — status snapshot SPI (impl in
 *       {@code modules/ui/.../api/StatusLifecycleHandler}).</li>
 * </ul>
 *
 * <p>The DebugStateProvider / StatusSnapshotProvider SPI extraction follows the §9 module-
 * inversion pattern (B1 precedent): rather than depending on the ui-side concrete classes,
 * this app-services impl depends on app-api interfaces, and the ui classes implement them.
 */
public final class DiagnosticsServiceImpl implements DiagnosticsService {

  private static final Logger log = LoggerFactory.getLogger(DiagnosticsServiceImpl.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long MAX_NDJSON_BYTES = 5L * 1024 * 1024; // 5 MB cap per file
  private static final int MAX_ROTATED_METRICS_FILES = 3;

  // Diagnostics-ZIP path redaction. Quoted JSON/log fields may contain spaces or commas, so a
  // whitespace/comma boundary is not safe; stop only at a quote or line boundary.
  private static final Pattern WINDOWS_PATH =
      Pattern.compile("(?i)[a-z]:\\\\[^\\r\\n\"]+");
  private static final Pattern UNIX_PATH =
      Pattern.compile("/(?:home|usr|var|tmp|opt)[/][^\\r\\n\"]+");

  private final EnterprisePolicyService policyService;
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final Supplier<DebugStateProvider> debugStateProviderSupplier;
  private final Supplier<StatusSnapshotProvider> statusSnapshotProviderSupplier;

  /**
   * §31 Phase 2: SPI providers passed as suppliers so ServicePhase can construct
   * DiagnosticsServiceImpl before the ui-side controllers exist. The suppliers resolve at
   * use-time (export-diagnostics request), by which point the controllers + their SPI impls
   * are wired in via the BootstrapLateBindings AtomicReferences from LocalApiServer.
   */
  public DiagnosticsServiceImpl(
      EnterprisePolicyService policyService,
      GpuCapabilitiesService gpuCapabilitiesService,
      Supplier<DebugStateProvider> debugStateProviderSupplier,
      Supplier<StatusSnapshotProvider> statusSnapshotProviderSupplier) {
    this.policyService = policyService;
    this.gpuCapabilitiesService = gpuCapabilitiesService;
    this.debugStateProviderSupplier = debugStateProviderSupplier;
    this.statusSnapshotProviderSupplier = statusSnapshotProviderSupplier;
  }

  @Override
  public Path exportDiagnostics(EngineContext engineContext) throws Exception {
    return exportDiagnostics(null, engineContext);
  }

  @Override
  public Path exportDiagnostics(String feTelemetryJson, EngineContext engineContext) throws Exception {
    Path aiHome = PlatformPaths.resolveAiHome();
    Path dataDir = PlatformPaths.resolveDataDir();
    Path outDir = aiHome.resolve("diagnostics");
    Files.createDirectories(outDir);
    DiagnosticFileRetention.pruneBefore(
        outDir,
        "justsearch-diagnostics-",
        java.time.Instant.now().minus(java.time.Duration.ofDays(30)));
    String ts =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(ZonedDateTime.now(java.time.ZoneId.systemDefault()));
    Path outZip = outDir.resolve("justsearch-diagnostics-" + ts + ".zip");

    try (OutputStream fos = Files.newOutputStream(outZip);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
      // Core state files (redacted — may contain configured paths)
      addFileRedacted(zos, aiHome.resolve("install-state.json"), "ai/install-state.json");
      addFileRedacted(zos, aiHome.resolve("pack-import-state.json"), "ai/pack-import-state.json");
      addFileRedacted(zos, aiHome.resolve("installed-packs.v1.json"), "ai/installed-packs.v1.json");
      addFileRedacted(
          zos,
          aiHome.resolve("ai").resolve("runtime-activation-state.json"),
          "ai/runtime-activation-state.json");
      addFileRedacted(zos, aiHome.resolve("ui").resolve("settings.json"), "ui/settings.json");

      Path userPolicy = aiHome.resolve("policy.v1.json");
      addFileRedacted(zos, userPolicy, "policy/user-policy.v1.json");
      Path machinePolicy = resolveMachinePolicyPath();
      if (machinePolicy != null) {
        addFileRedacted(zos, machinePolicy, "policy/machine-policy.v1.json");
      }
      if (policyService != null) {
        byte[] eff = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(policyService.snapshot());
        addBytesRedacted(zos, eff, "policy/effective-policy.json");
      }

      if (gpuCapabilitiesService != null) {
        byte[] caps =
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(gpuCapabilitiesService.snapshot());
        addBytesRedacted(zos, caps, "gpu/capabilities.json");
      }

      Path logsDir = aiHome.resolve("logs");
      addDirectoryRedacted(zos, logsDir, "logs");

      addTelemetryFiles(zos, dataDir);
      addCrashReports(zos, dataDir);
      addRuntimeSnapshots(zos, engineContext);

      if (feTelemetryJson != null && !feTelemetryJson.isBlank()) {
        addBytesRedacted(
            zos,
            feTelemetryJson.getBytes(StandardCharsets.UTF_8),
            "frontend/fe-telemetry.json");
      }
    }

    return outZip;
  }

  @Override
  public String buildDiagnosticSummary() {
    DiagnosticSummaryComposer.LifecycleMetadata lifecycle = null;
    DiagnosticSummaryComposer.GpuMetadata gpu = null;
    if (statusSnapshotProviderSupplier != null) {
      try {
        StatusSnapshotProvider provider = statusSnapshotProviderSupplier.get();
        Object statusSnapshot = provider == null ? null : provider.buildStatusSnapshot();
        lifecycle = DiagnosticSummaryComposer.lifecycleFrom(statusSnapshot);
        gpu = DiagnosticSummaryComposer.safeGpuFrom(statusSnapshot);
      } catch (RuntimeException ignored) {
        // Optional typed snapshot: omit it when the Head is still wiring or shutting down.
      }
    }

    DiagnosticSummaryComposer.CrashMetadata crash = null;
    try {
      crash =
          DiagnosticSummaryComposer.latestCrash(PlatformPaths.resolveDataDir().resolve("crashes"));
    } catch (RuntimeException ignored) {
      // Optional local metadata: an unresolved data directory is represented by omission.
    }

    return new DiagnosticSummaryComposer()
        .compose(
            new DiagnosticSummaryComposer.Inputs(
                EnvRegistry.APP_VERSION.get().orElse(null),
                RuntimeContract.current(),
                DiagnosticSummaryComposer.currentPlatform(),
                lifecycle,
                gpu,
                crash));
  }

  private void addTelemetryFiles(ZipOutputStream zos, Path dataDir) {
    Path telemetryDir = dataDir.resolve("telemetry");
    if (!Files.isDirectory(telemetryDir)) return;
    try {
      addFileTailRedacted(zos, telemetryDir.resolve("metrics.ndjson"), "telemetry/metrics.ndjson");
      addFileTailRedacted(zos, telemetryDir.resolve("traces.ndjson"), "telemetry/traces.ndjson");
      addFileTailRedacted(zos, telemetryDir.resolve("metrics-worker.ndjson"), "telemetry/metrics-worker.ndjson");

      List<Path> rotated = new ArrayList<>();
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(telemetryDir, "metrics.*.ndjson")) {
        for (Path p : ds) {
          if (Files.isRegularFile(p)) {
            rotated.add(p);
          }
        }
      }
      rotated.sort(Comparator.comparingLong((Path p) -> {
        try {
          return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
          return 0L;
        }
      }).reversed());
      int count = 0;
      for (Path p : rotated) {
        if (count >= MAX_ROTATED_METRICS_FILES) break;
        addFileTailRedacted(zos, p, "telemetry/" + p.getFileName().toString());
        count++;
      }
    } catch (Exception e) {
      log.warn("Failed to include telemetry files in diagnostics export", e);
    }
  }

  private static void addCrashReports(ZipOutputStream zos, Path dataDir) {
    Path crashDir = dataDir.resolve("crashes");
    if (!Files.isDirectory(crashDir)) return;
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(crashDir, "crash-*.json")) {
      for (Path p : ds) {
        if (Files.isRegularFile(p)) {
          addFileRedacted(zos, p, "crashes/" + p.getFileName().toString());
        }
      }
    } catch (Exception e) {
      log.warn("Failed to include crash reports in diagnostics export", e);
    }
  }

  private void addRuntimeSnapshots(ZipOutputStream zos, EngineContext engineContext) {
    if (debugStateProviderSupplier != null && debugStateProviderSupplier.get() != null) {
      try {
        byte[] data =
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(debugStateProviderSupplier.get().buildDebugState(engineContext));
        addBytesRedacted(zos, data, "runtime/debug-state.json");
      } catch (Exception e) {
        log.warn("Failed to include debug state in diagnostics export", e);
      }
    }
    if (statusSnapshotProviderSupplier != null && statusSnapshotProviderSupplier.get() != null) {
      try {
        byte[] data =
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(statusSnapshotProviderSupplier.get().buildStatusSnapshot());
        addBytesRedacted(zos, data, "runtime/status.json");
      } catch (Exception e) {
        log.warn("Failed to include status in diagnostics export", e);
      }
    }
  }

  static String redactPaths(String text) {
    String result = WINDOWS_PATH.matcher(text).replaceAll("[path]");
    return UNIX_PATH.matcher(result).replaceAll("[path]");
  }

  private static void addFileRedacted(ZipOutputStream zos, Path file, String entryName)
      throws IOException {
    if (file == null || !Files.isRegularFile(file)) return;
    String content = Files.readString(file);
    byte[] redacted = redactPaths(content).getBytes(StandardCharsets.UTF_8);
    ZipEntry ze = new ZipEntry(entryName);
    ze.setTime(Files.getLastModifiedTime(file).toMillis());
    zos.putNextEntry(ze);
    zos.write(redacted);
    zos.closeEntry();
  }

  private static void addFileRaw(ZipOutputStream zos, Path file, String entryName)
      throws IOException {
    if (file == null || !Files.isRegularFile(file)) return;
    ZipEntry ze = new ZipEntry(entryName);
    ze.setTime(Files.getLastModifiedTime(file).toMillis());
    zos.putNextEntry(ze);
    try (var in = Files.newInputStream(file)) {
      in.transferTo(zos);
    }
    zos.closeEntry();
  }

  private static void addFileRedactedStreaming(ZipOutputStream zos, Path file, String entryName)
      throws IOException {
    if (file == null || !Files.isRegularFile(file)) return;
    ZipEntry ze = new ZipEntry(entryName);
    ze.setTime(Files.getLastModifiedTime(file).toMillis());
    zos.putNextEntry(ze);
    try (BufferedReader reader = Files.newBufferedReader(file)) {
      String line;
      while ((line = reader.readLine()) != null) {
        zos.write(redactPaths(line).getBytes(StandardCharsets.UTF_8));
        zos.write('\n');
      }
    }
    zos.closeEntry();
  }

  private static void addFileTailRedacted(ZipOutputStream zos, Path file, String entryName) {
    if (file == null || !Files.isRegularFile(file)) return;
    try {
      long fileSize = Files.size(file);
      ZipEntry ze = new ZipEntry(entryName);
      ze.setTime(Files.getLastModifiedTime(file).toMillis());
      zos.putNextEntry(ze);
      if (fileSize <= MAX_NDJSON_BYTES) {
        String content = Files.readString(file);
        zos.write(redactPaths(content).getBytes(StandardCharsets.UTF_8));
      } else {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
          raf.seek(fileSize - MAX_NDJSON_BYTES);
          int b;
          while ((b = raf.read()) != -1 && b != '\n') {
            // advance past partial line
          }
          long remaining = fileSize - raf.getFilePointer();
          byte[] tailBytes = new byte[(int) remaining];
          raf.readFully(tailBytes);
          String tail = new String(tailBytes, StandardCharsets.UTF_8);
          zos.write(redactPaths(tail).getBytes(StandardCharsets.UTF_8));
        }
      }
      zos.closeEntry();
    } catch (Exception e) {
      log.warn("Failed to include file tail in diagnostics export: {}", file, e);
    }
  }

  private static void addBytesRedacted(ZipOutputStream zos, byte[] bytes, String entryName)
      throws IOException {
    if (bytes == null) bytes = new byte[0];
    String content = new String(bytes, StandardCharsets.UTF_8);
    byte[] redacted = redactPaths(content).getBytes(StandardCharsets.UTF_8);
    ZipEntry ze = new ZipEntry(entryName);
    zos.putNextEntry(ze);
    zos.write(redacted);
    zos.closeEntry();
  }

  private static void addDirectoryRedacted(ZipOutputStream zos, Path dir, String entryPrefix) {
    if (dir == null || !Files.isDirectory(dir)) return;
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (file == null || !Files.isRegularFile(file)) return FileVisitResult.CONTINUE;
              Path rel = dir.relativize(file);
              String name = rel.toString().replace('\\', '/');
              String fullEntry = entryPrefix + "/" + name;
              if (name.endsWith(".gz")) {
                addFileRaw(zos, file, fullEntry);
              } else {
                addFileRedactedStreaming(zos, file, fullEntry);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.warn("Failed to include directory in diagnostics export: {}", dir, e);
    }
  }

  private static Path resolveMachinePolicyPath() {
    if (!PlatformPaths.isWindows()) return null;
    String programData = SystemAccess.envVar("PROGRAMDATA");
    if (programData == null || programData.isBlank()) {
      programData = "C:\\ProgramData";
    }
    return Path.of(programData).resolve("JustSearch").resolve("policy.v1.json");
  }
}
