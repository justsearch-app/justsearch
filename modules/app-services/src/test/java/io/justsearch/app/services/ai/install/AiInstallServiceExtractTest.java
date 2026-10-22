package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the alpha.15 zip-extract logic, now in {@link ArchiveExtractor}.
 *
 * <p>Used by the {@code cuda-runtime} package to expand bundled CUDA DLLs (cuFFT et al.) into
 * the cuda12 variant directory after Install AI download. The DLLs are too large for the NSIS
 * installer payload (tempdoc 374 G21), so they ship as a downloaded archive instead.
 */
final class AiInstallServiceExtractTest {

  @TempDir Path tmp;

  @Test
  void extractsAllEntriesFlat() throws IOException {
    Path zipFile = tmp.resolve("cuda-bundle.zip");
    writeZip(zipFile, "cufft64_11.dll", "curand64_10.dll", "nvJitLink_120_0.dll");
    Path target = tmp.resolve("cuda12");
    Files.createDirectories(target);

    ArchiveInstallRepairTest.extract(zipFile, target);

    assertTrue(Files.isRegularFile(target.resolve("cufft64_11.dll")));
    assertTrue(Files.isRegularFile(target.resolve("curand64_10.dll")));
    assertTrue(Files.isRegularFile(target.resolve("nvJitLink_120_0.dll")));
  }

  /** Idempotency — re-extracting over an existing dir doesn't error or overwrite. */
  @Test
  void reExtractIsIdempotent() throws IOException {
    Path zipFile = tmp.resolve("bundle.zip");
    writeZip(zipFile, "cufft64_11.dll");
    Path target = tmp.resolve("cuda12");
    Files.createDirectories(target);

    ArchiveInstallRepairTest.extract(zipFile, target);
    long firstMtime = Files.getLastModifiedTime(target.resolve("cufft64_11.dll")).toMillis();

    ArchiveInstallRepairTest.extract(zipFile, target);
    long secondMtime = Files.getLastModifiedTime(target.resolve("cufft64_11.dll")).toMillis();

    assertEquals(firstMtime, secondMtime, "second extract should not overwrite existing entries");
  }

  /**
   * Zip-slip guard: an entry whose name escapes the target dir (e.g., {@code ../../etc/passwd}
   * or absolute path) must throw, NOT write outside the dir.
   */
  @Test
  void rejectsZipSlipEntry() throws IOException {
    Path zipFile = tmp.resolve("malicious.zip");
    try (var os = Files.newOutputStream(zipFile);
        var zos = new ZipOutputStream(os)) {
      ZipEntry entry = new ZipEntry("../escape.txt");
      zos.putNextEntry(entry);
      zos.write("escape".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }
    Path target = tmp.resolve("safe");
    Files.createDirectories(target);

    IOException ex =
        assertThrows(
            IOException.class, () -> ArchiveInstallRepairTest.extract(zipFile, target));
    assertTrue(
        ex.getMessage().toLowerCase().contains("escape"),
        "error message should reference the directory-escape attempt: " + ex.getMessage());

    Path escaped = tmp.resolve("escape.txt");
    assertFalse(Files.exists(escaped), "zip-slip target file must not have been written");
  }

  /** Missing archive → IOException, not a silent no-op. */
  @Test
  void missingZipThrows() {
    Path missing = tmp.resolve("does-not-exist.zip");
    Path target = tmp.resolve("dst");
    assertThrows(IOException.class, () -> ArchiveInstallRepairTest.extract(missing, target));
  }

  /**
   * Nested zip entry paths (subdirectories within the archive) — not used by the cuda-runtime
   * package today (its zips are flat) but should still work correctly to support future archive
   * shapes.
   */
  @Test
  void preservesNestedEntryPaths() throws IOException {
    Path zipFile = tmp.resolve("nested.zip");
    try (var os = Files.newOutputStream(zipFile);
        var zos = new ZipOutputStream(os)) {
      writeZipEntry(zos, "lib/x64/foo.dll", "foo");
      writeZipEntry(zos, "include/foo.h", "header");
    }
    Path target = tmp.resolve("nest");
    Files.createDirectories(target);

    ArchiveInstallRepairTest.extract(zipFile, target);

    assertTrue(Files.isRegularFile(target.resolve("lib/x64/foo.dll")));
    assertTrue(Files.isRegularFile(target.resolve("include/foo.h")));
  }

  private static void writeZip(Path file, String... entryNames) throws IOException {
    try (OutputStream os = Files.newOutputStream(file);
        ZipOutputStream zos = new ZipOutputStream(os)) {
      for (String name : entryNames) {
        writeZipEntry(zos, name, "stub-content-for-" + name);
      }
    }
  }

  private static void writeZipEntry(ZipOutputStream zos, String name, String content)
      throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zos.putNextEntry(entry);
    zos.write(content.getBytes(StandardCharsets.UTF_8));
    zos.closeEntry();
  }
}
