package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G1/G2: the Gradle test process and an unmodified ProcessBuilder inherit private profiles. */
@Tag("cross-platform")
final class TestProfileIsolationTest {
  private static final List<String> PROFILE_VARIABLES = List.of(
      "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
      "XDG_CACHE_HOME", "XDG_STATE_HOME", "JUSTSEARCH_HOME", "JUSTSEARCH_DATA_DIR");

  @TempDir Path temporary;

  @Test
  void testJvmUsesTaskLocalProfile() {
    Path profiles = Path.of("build", "test-profile").toAbsolutePath().normalize();
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    assertTrue(home.startsWith(profiles), "user.home must be inside the task build folder: " + home);
    assertEquals("home", home.getFileName().toString());
    for (String key : PROFILE_VARIABLES) {
      String value = System.getenv(key);
      assertTrue(value != null && Path.of(value).toAbsolutePath().normalize().startsWith(home.getParent()),
          key + " must be inside the task profile: " + value);
    }
    assertEquals(home.toString(), System.getenv("HOME"));
    assertEquals(home.toString(), System.getenv("USERPROFILE"));
    assertEquals(System.getProperty("justsearch.data.dir"), System.getenv("JUSTSEARCH_DATA_DIR"));
    assertEquals(System.getProperty("justsearch.home"), System.getenv("JUSTSEARCH_HOME"));
  }

  @Test
  void plainChildInheritsTaskLocalProfile() throws Exception {
    Path source = temporary.resolve("ProfileProbe.java");
    Files.writeString(source, """
        class ProfileProbe {
          public static void main(String[] args) throws Exception {
            var properties = new java.util.Properties();
            for (String key : args) properties.setProperty(key, System.getenv(key) == null ? "" : System.getenv(key));
            properties.store(System.out, null);
          }
        }
        """);
    var command = new java.util.ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
    command.add(source.toString());
    command.addAll(PROFILE_VARIABLES);
    Path output = temporary.resolve("child.properties");
    Process child = new ProcessBuilder(command).redirectOutput(output.toFile())
        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "profile probe must exit");
      assertEquals(0, child.exitValue());
      var properties = new Properties();
      try (var input = Files.newInputStream(output)) { properties.load(input); }
      Path profiles = Path.of("build", "test-profile").toAbsolutePath().normalize();
      for (String key : PROFILE_VARIABLES) {
        String value = properties.getProperty(key);
        assertTrue(value != null && !value.isBlank() && Path.of(value).toAbsolutePath().normalize().startsWith(profiles),
            "child " + key + " must be inside the task build folder: " + value);
        assertEquals(System.getenv(key), value, "child inherits " + key);
      }
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
    }
  }
}
