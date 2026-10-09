package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TikaOcrRuntimeTest {
  @TempDir Path tempDir;
  private final String originalUserDir = System.getProperty("user.dir");

  @AfterEach
  void clearRuntimeProperties() {
    System.clearProperty("justsearch.home");
    System.clearProperty("justsearch.tesseract.path");
    System.clearProperty("justsearch.tessdata.path");
    if (originalUserDir == null) {
      System.clearProperty("user.dir");
    } else {
      System.setProperty("user.dir", originalUserDir);
    }
    TikaOcrRuntime.resetLanguageCacheForTests();
  }

  @Test
  void resolvesAppOwnedTesseractRuntimeFromJustsearchHome() throws Exception {
    Path root = tempDir.resolve("home");
    Path runtime = root.resolve("native-bin").resolve("tesseract");
    Path executable = writeFakeTesseract(runtime);
    Path tessdata = runtime.resolve("tessdata");
    Files.createDirectories(tessdata);
    Files.writeString(tessdata.resolve("eng.traineddata"), "fixture");
    System.setProperty("justsearch.home", root.toString());
    System.setProperty("justsearch.tessdata.path", tessdata.toString());

    TikaOcrRuntime.RuntimePaths paths = TikaOcrRuntime.resolve();

    assertEquals(executable.toAbsolutePath().normalize(), paths.executable());
    assertEquals(runtime.toAbsolutePath().normalize(), paths.executableDirectory());
    assertEquals(tessdata.toAbsolutePath().normalize(), paths.tessdataDirectory());
  }

  @Test
  void explicitTesseractPathWinsOverAppOwnedRuntime() throws Exception {
    Path homeRuntime = tempDir.resolve("home").resolve("native-bin").resolve("tesseract");
    writeFakeTesseract(homeRuntime);
    Path explicitRuntime = tempDir.resolve("explicit");
    Path explicit = writeFakeTesseract(explicitRuntime);
    System.setProperty("justsearch.home", tempDir.resolve("home").toString());
    System.setProperty("justsearch.tesseract.path", explicit.toString());

    TikaOcrRuntime.RuntimePaths paths = TikaOcrRuntime.resolve();

    assertEquals(explicit.toAbsolutePath().normalize(), paths.executable());
    assertEquals(explicitRuntime.toAbsolutePath().normalize(), paths.executableDirectory());
  }

  @Test
  void invalidExplicitTesseractPathDoesNotFallBackToAppOwnedRuntime() throws Exception {
    Path homeRuntime = tempDir.resolve("home").resolve("native-bin").resolve("tesseract");
    writeFakeTesseract(homeRuntime);
    System.setProperty("justsearch.home", tempDir.resolve("home").toString());
    System.setProperty("justsearch.tesseract.path", tempDir.resolve("missing-tesseract").toString());

    assertEquals(
        TikaOcrRuntime.REASON_ENGINE_MISSING,
        TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 300, 0)));
  }

  @Test
  void invalidExplicitTessdataPathReportsLanguageMissing() throws Exception {
    Path runtime = tempDir.resolve("home").resolve("native-bin").resolve("tesseract");
    writeFakeTesseract(runtime);
    System.setProperty("justsearch.home", tempDir.resolve("home").toString());
    System.setProperty("justsearch.tessdata.path", tempDir.resolve("missing-tessdata").toString());

    assertEquals(
        TikaOcrRuntime.REASON_LANGUAGE_MISSING,
        TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 300, 0)));
  }

  @Test
  void emptyLanguageProbeReportsLanguageMissingWhenLanguageRequested() throws Exception {
    Path runtime = tempDir.resolve("home").resolve("native-bin").resolve("tesseract");
    writeFakeTesseractWithoutLanguages(runtime);
    Files.createDirectories(runtime.resolve("tessdata"));
    System.setProperty("justsearch.home", tempDir.resolve("home").toString());

    assertEquals(
        TikaOcrRuntime.REASON_LANGUAGE_MISSING,
        TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 300, 0)));
  }

  @Test
  void resolvesRuntimeFromInstalledAppRootWhenLaunchedFromBinDirectory() throws Exception {
    Path appRoot = tempDir.resolve("build").resolve("install").resolve("ui");
    Path runtime = appRoot.resolve("native-bin").resolve("tesseract");
    Path executable = writeFakeTesseract(runtime);
    Path bin = appRoot.resolve("bin");
    Files.createDirectories(bin);
    System.setProperty("user.dir", bin.toString());

    TikaOcrRuntime.RuntimePaths paths = TikaOcrRuntime.resolve();

    assertEquals(executable.toAbsolutePath().normalize(), paths.executable());
    assertEquals(runtime.toAbsolutePath().normalize(), paths.executableDirectory());
  }

  @Test
  void resolvesRuntimeFromAncestorRepoRootWhenLaunchedFromModuleDirectory() throws Exception {
    Path repoRoot = tempDir.resolve("repo");
    Path runtime = repoRoot.resolve("native-bin").resolve("tesseract");
    Path executable = writeFakeTesseract(runtime);
    Path moduleDir = repoRoot.resolve("modules").resolve("worker-services");
    Files.createDirectories(moduleDir);
    System.setProperty("user.dir", moduleDir.toString());

    TikaOcrRuntime.RuntimePaths paths = TikaOcrRuntime.resolve();

    assertEquals(executable.toAbsolutePath().normalize(), paths.executable());
    assertEquals(runtime.toAbsolutePath().normalize(), paths.executableDirectory());
  }

  @Test
  void languageProbeUsesPackagedTessdataPrefix() throws Exception {
    Path runtime = tempDir.resolve("home").resolve("native-bin").resolve("tesseract");
    writeFakeTesseract(runtime);
    Files.createDirectories(runtime.resolve("tessdata"));
    System.setProperty("justsearch.home", tempDir.resolve("home").toString());

    assertEquals("", TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 300, 0)));
    assertEquals(
        TikaOcrRuntime.REASON_LANGUAGE_MISSING,
        TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("deu"), 5_000, 1, 4096, 40_000_000, 300, 0)));
  }

  private static Path writeFakeTesseract(Path directory) throws IOException {
    if (isWindows()) return NativeTesseractFixture.write(directory, "", true);
    Files.createDirectories(directory);
    Path executable = directory.resolve(isWindows() ? "tesseract.cmd" : "tesseract");
    String script =
        isWindows()
            ? """
              @echo off
              if "%1"=="--list-langs" (
                echo List of available languages in "%TESSDATA_PREFIX%" ^(1^):
                echo eng
                exit /b 0
              )
              exit /b 0
              """
            : """
              #!/usr/bin/env sh
              if [ "$1" = "--list-langs" ]; then
                echo "List of available languages in $TESSDATA_PREFIX (1):"
                echo "eng"
                exit 0
              fi
              exit 0
              """;
    Files.writeString(executable, script);
    executable.toFile().setExecutable(true, false);
    return executable;
  }

  private static Path writeFakeTesseractWithoutLanguages(Path directory) throws IOException {
    if (isWindows()) return NativeTesseractFixture.write(directory, "", false);
    Files.createDirectories(directory);
    Path executable = directory.resolve(isWindows() ? "tesseract.cmd" : "tesseract");
    String script =
        isWindows()
            ? """
              @echo off
              if "%1"=="--list-langs" (
                echo List of available languages in "%TESSDATA_PREFIX%" ^(0^):
                exit /b 0
              )
              exit /b 0
              """
            : """
              #!/usr/bin/env sh
              if [ "$1" = "--list-langs" ]; then
                echo "List of available languages in $TESSDATA_PREFIX (0):"
                exit 0
              fi
              exit 0
              """;
    Files.writeString(executable, script);
    executable.toFile().setExecutable(true, false);
    return executable;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
