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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
  private static final int MAX_EXIT_LEDGER_BYTES = 8 * 1024 * 1024;
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

      Path logsDir = dataDir.resolve("logs");
      addDirectoryRedacted(zos, logsDir, "logs");
      Path aiLogsDir = aiHome.resolve("logs");
      if (Files.isDirectory(aiLogsDir)
          && (!Files.isDirectory(logsDir) || !Files.isSameFile(logsDir, aiLogsDir))) {
        addDirectoryRedacted(zos, aiLogsDir, "ai/logs");
      }

      addExitLedgers(zos, dataDir);
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

  private static void addExitLedgers(ZipOutputStream zos, Path dataDir) throws IOException {
    Path directory = dataDir.resolve("runtime/exit-ledger");
    if (!Files.exists(directory)) return;
    List<String> failures = new ArrayList<>();
    List<Path> retained = new ArrayList<>();
    try (var files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        if (!name.matches("(engine|children)\\.v1(?:\\.(?:[0-9]+|lost))?\\.jsonl")) continue;
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          failures.add(name + ": not a regular ledger file");
          continue;
        }
        retained.add(file);
      }
    } catch (IOException unavailable) { failures.add("ledger directory: collection failed"); }
    retained.sort(Comparator.comparingLong((Path file) -> {
      try { return Files.getLastModifiedTime(file).toMillis(); }
      catch (IOException unavailable) { return Long.MIN_VALUE; }
    }).reversed().thenComparing(file -> file.getFileName().toString()));
    int exported = 0;
    long omitted = 0;
    int skippedLines = 0;
    for (Path file : retained) {
      try {
        LedgerTail tail = exitLedgerTail(file, MAX_EXIT_LEDGER_BYTES - exported);
        addBytesRedacted(zos, tail.bytes(), "runtime/exit-ledger/" + file.getFileName());
        exported += tail.bytes().length;
        omitted += tail.omitted();
        skippedLines += tail.skippedLines();
      } catch (IOException unavailable) { failures.add(file.getFileName() + ": collection failed"); }
    }
    if (omitted > 0) addBytesRedacted(zos,
        ("Truncated exit-ledger export: " + omitted + " source bytes omitted; newest records retained within 8388608 bytes.\n")
            .getBytes(StandardCharsets.UTF_8), "runtime/exit-ledger/truncation.txt");
    if (skippedLines > 0) addBytesRedacted(zos,
        ("Skipped " + skippedLines + " malformed or incomplete lines in the bounded exit-ledger suffixes.\n")
            .getBytes(StandardCharsets.UTF_8), "runtime/exit-ledger/skipped-lines.txt");
    if (!failures.isEmpty()) addBytesRedacted(zos,
        String.join("\n", failures).getBytes(StandardCharsets.UTF_8),
        "runtime/exit-ledger/collection-failures.txt");
  }

  private record LedgerTail(byte[] bytes, long omitted, int skippedLines) {}

  /** Reads only a bounded suffix, drops a partial leading record, then enforces the redacted byte cap. */
  private static LedgerTail exitLedgerTail(Path file, int budget) throws IOException {
    try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
      long size = input.length(), start = Math.max(0, size - budget);
      if (budget == 0) return new LedgerTail(new byte[0], size, 0);
      input.seek(start == 0 ? 0 : start - 1);
      if (start > 0 && input.read() != '\n') {
        byte[] scan = new byte[8192];
        boolean boundary = false;
        while (!boundary && input.getFilePointer() < size) {
          long position = input.getFilePointer();
          int count = input.read(scan, 0, (int) Math.min(scan.length, size - position));
          if (count < 0) break;
          for (int i = 0; i < count; i++) if (scan[i] == '\n') {
            input.seek(position + i + 1); boundary = true; break;
          }
        }
      }
      byte[] raw = new byte[(int) (size - input.getFilePointer())];
      input.readFully(raw);
      List<byte[]> records = new ArrayList<>();
      int used = 0, skippedLines = 0, end = raw.length;
      long sourceKept = 0;
      // A writer may be appending the last record. Only newline-terminated records are complete.
      if (end > 0 && raw[end - 1] != '\n') {
        while (end > 0 && raw[end - 1] != '\n') end--;
        skippedLines++;
      }
      while (end > 0) {
        int startLine = end - 1;
        while (startLine > 0 && raw[startLine - 1] != '\n') startLine--;
        int length = end - startLine - 1;
        if (length > 0 && raw[startLine + length - 1] == '\r') length--;
        final String line;
        try {
          line = StandardCharsets.UTF_8.newDecoder()
              .decode(java.nio.ByteBuffer.wrap(raw, startLine, length)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) {
          skippedLines++; end = startLine; continue;
        }
        byte[] redacted = (redactPaths(line) + "\n").getBytes(StandardCharsets.UTF_8);
        if (used + redacted.length > budget) break;
        records.add(redacted); used += redacted.length;
        sourceKept += end - startLine;
        end = startLine;
      }
      var bytes = new java.io.ByteArrayOutputStream(used);
      for (int i = records.size() - 1; i >= 0; i--) bytes.writeBytes(records.get(i));
      return new LedgerTail(bytes.toByteArray(), size - sourceKept, skippedLines);
    }
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
    // Fatal JVM reports are text. Never collect heap dumps: they can contain indexed content.
    try (var reports = Files.list(crashDir)) {
      List<Path> latest = reports.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.getFileName().toString().matches("hs_err_pid.*\\.log"))
          .sorted(Comparator.comparingLong((Path path) -> {
            try { return Files.getLastModifiedTime(path).toMillis(); }
            catch (IOException ignored) { return 0L; }
          }).reversed().thenComparing(path -> path.getFileName().toString()))
          .limit(3).toList();
      for (Path report : latest) {
        try (InputStream input = Files.newInputStream(report)) {
          byte[] prefix = input.readNBytes(1024 * 1024);
          addBytesRedacted(zos, prefix, "crashes/" + report.getFileName());
        } catch (IOException error) { log.warn("Failed to include JVM crash report {}", report.getFileName(), error); }
      }
    } catch (IOException error) { log.warn("Failed to list JVM crash reports", error); }
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

  private static void addGzipRedacted(ZipOutputStream zos, Path file, String entryName)
      throws IOException {
    if (file == null || !Files.isRegularFile(file)) return;
    // Keep rotations compressed, but apply the same text redaction as active logs. Neither
    // the gzip stream nor its wrapper owns the enclosing diagnostics ZIP.
    try (InputStream input = Files.newInputStream(file);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8))) {
      ZipEntry ze = new ZipEntry(entryName);
      ze.setTime(Files.getLastModifiedTime(file).toMillis());
      zos.putNextEntry(ze);
      try (GZIPOutputStream gzip =
          new GZIPOutputStream(
              new FilterOutputStream(zos) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                  out.write(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                  flush();
                }
              })) {
        String line;
        while ((line = reader.readLine()) != null) {
          gzip.write(redactPaths(line).getBytes(StandardCharsets.UTF_8));
          gzip.write('\n');
        }
      } finally {
        zos.closeEntry();
      }
    }
  }

  private static void addFileRedactedStreaming(ZipOutputStream zos, Path file, String entryName)
      throws IOException {
    if (file == null || !Files.isRegularFile(file)) return;
    ZipEntry ze = new ZipEntry(entryName);
    ze.setTime(Files.getLastModifiedTime(file).toMillis());
    try (BufferedReader reader = Files.newBufferedReader(file)) {
      zos.putNextEntry(ze);
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          zos.write(redactPaths(line).getBytes(StandardCharsets.UTF_8));
          zos.write('\n');
        }
      } finally {
        zos.closeEntry();
      }
    }
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

  private static void addDirectoryRedacted(ZipOutputStream zos, Path dir, String entryPrefix)
      throws IOException {
    if (dir == null || !Files.isDirectory(dir)) return;
    List<Path> files = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (Files.isRegularFile(file)) files.add(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
              notes.add(logEntryName(dir, file, entryPrefix) + ": omitted (unreadable).\n");
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
              if (failure != null) {
                notes.add(logEntryName(dir, directory, entryPrefix)
                    + ": directory collection incomplete.\n");
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      notes.add(entryPrefix + ": directory collection incomplete.\n");
      log.warn("Failed to enumerate directory in diagnostics export: {}", dir, e);
    }
    // Stable ordering also keeps each failed rotation's entry and note reproducible.
    files.sort(Comparator.comparing(file -> logEntryName(dir, file, entryPrefix)));
    for (Path file : files) {
      String fullEntry = logEntryName(dir, file, entryPrefix);
      try {
        if (fullEntry.endsWith(".gz")) {
          addGzipRedacted(zos, file, fullEntry);
        } else {
          addFileRedactedStreaming(zos, file, fullEntry);
        }
      } catch (IOException e) {
        // The file helpers finish any opened ZIP entry before returning a read failure. A
        // decoded prefix remains redacted and recompressed; never copy the original gzip.
        notes.add(fullEntry + ": omitted or truncated (unreadable log).\n");
        log.warn("Failed to include log in diagnostics export: {}", file, e);
      }
    }
    if (!notes.isEmpty()) {
      addBytesRedacted(zos, String.join("", notes).getBytes(StandardCharsets.UTF_8),
          "notes/" + entryPrefix.replace('/', '-') + "-collection.txt");
    }
  }

  private static String logEntryName(Path dir, Path file, String entryPrefix) {
    return entryPrefix + "/" + dir.relativize(file).toString().replace('\\', '/');
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
