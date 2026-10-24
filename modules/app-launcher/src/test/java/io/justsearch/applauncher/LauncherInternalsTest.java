package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LauncherInternalsTest {

  private String previousDataDir;

  @TempDir Path tempDir;

  @BeforeEach
  void captureProperty() {
    previousDataDir = System.getProperty("justsearch.data.dir");
  }

  @AfterEach
  void restoreProperty() {
    if (previousDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", previousDataDir);
    }
  }

  @Test
  void ensureDataDirCreatesDefaultDirectory() throws Exception {
    // The default branch needs an absent data environment; keep that absence local to this fixture.
    Path home = Files.createDirectories(tempDir.resolve("home"));
    List<String> arguments = List.of(
        "-Duser.home=" + home, "-Djustsearch.data.dir=", "-cp",
        System.getProperty("java.class.path"), DefaultDirectoryProbe.class.getName());
    Path argFile = tempDir.resolve("default-directory.args");
    Files.write(argFile, arguments.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .toList());
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    Path output = tempDir.resolve("default-directory.log");
    var builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + argFile)
        .directory(tempDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().remove("JUSTSEARCH_DATA_DIR");
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "default-directory fixture must exit");
      assertEquals(0, child.exitValue(), Files.readString(output));
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
    }
  }

  public static final class DefaultDirectoryProbe {
    public static void main(String[] args) throws Exception {
      System.clearProperty("justsearch.data.dir");
      Launcher launcher = new Launcher();
      Method method = Launcher.class.getDeclaredMethod("ensureDataDir");
      method.setAccessible(true);
      method.invoke(launcher);
      String configured = System.getProperty("justsearch.data.dir");
      Path configuredPath = Path.of(configured);
      assertTrue(configuredPath.endsWith(Path.of("build", "applauncher-data")));
      assertTrue(Files.isDirectory(configuredPath));
    }
  }

  @Test
  void ensureDataDirHonoursExistingProperty() throws Exception {
    Path custom = tempDir.resolve("custom-data");
    System.setProperty("justsearch.data.dir", custom.toString());
    Launcher launcher = new Launcher();
    Method method = Launcher.class.getDeclaredMethod("ensureDataDir");
    method.setAccessible(true);
    method.invoke(launcher);
    assertEquals(custom.toString(), System.getProperty("justsearch.data.dir"));
    // The configured directory is honoured, not replaced, and "ensure" means it now exists.
    assertTrue(Files.isDirectory(custom));
  }

  @Test
  void emitCommandResultPrintsMarkersAndFailures() throws Exception {
    Launcher launcher = new Launcher();
    Method method =
        Launcher.class.getDeclaredMethod("emitCommandResult", LauncherCommands.CommandResult.class);
    method.setAccessible(true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      method.invoke(
          launcher,
          LauncherCommands.CommandResult.success(List.of("OK/MARKER")));
      method.invoke(
          launcher,
          LauncherCommands.CommandResult.failure(
              List.of("FAIL/MARKER"),
              new IllegalStateException("boom")));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("OK/MARKER"));
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("FAIL/MARKER"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("IllegalStateException"));
  }
}
