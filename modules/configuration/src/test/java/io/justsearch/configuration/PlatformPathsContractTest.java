package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 501 §3.6: cross-language contract for {@link PlatformPaths#resolveDataDir}.
 *
 * <p>Feeds each fixture from {@code contracts/platform-paths/spec.v1.json} into the
 * Java implementation and asserts the resolved path matches the expected value. The
 * Node implementation ({@code scripts/lib/platform-paths.mjs}) has its own contract
 * test in {@code scripts/lib/platform-paths.contract.test.mjs} that consumes the same
 * spec; drift between implementations becomes a failing test in one of the two
 * harnesses.
 *
 * <p>Fixtures are intentionally hermetic — they override platform, env, sysprops, and
 * userHome rather than reading the live environment. The test reads the contract from
 * the {@code contracts/} directory at the repo root.
 */
final class PlatformPathsContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporary;

  @TestFactory
  List<DynamicTest> resolveDataDirMatchesContractSpec() throws IOException {
    JsonNode spec = loadSpec();
    JsonNode fixtures = spec.path("fixtures");
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode fixture : fixtures) {
      String name = fixture.path("name").asText();
      if (!isJavaRunner(fixture)) continue;
      tests.add(
          DynamicTest.dynamicTest(
              "Java PlatformPaths.resolveDataDir — " + name,
              () -> runFixture(fixture)));
    }
    return tests;
  }

  private static boolean isJavaRunner(JsonNode fixture) {
    JsonNode runners = fixture.path("runners");
    if (runners.isMissingNode() || !runners.isArray() || runners.isEmpty()) {
      return true; // default: both runners
    }
    for (JsonNode r : runners) {
      if ("java".equals(r.asText())) return true;
    }
    return false;
  }

  private void runFixture(JsonNode fixture) throws Exception {
    String platform = fixture.path("platform").asText();
    String userHome = fixture.path("userHome").asText();
    Map<String, String> env = jsonMapToString(fixture.path("env"));
    Map<String, String> sysprops = jsonMapToString(fixture.path("sysprops"));
    String expected = fixture.path("expected").asText();

    if (!env.isEmpty() && !sysprops.containsKey("justsearch.data.dir")) {
      // Keep the existing Java/Node fixture split; the Node runner owns env-driven fixtures.
      return;
    }

    // A process boundary lets the fixture remove the task's protective data override without
    // changing the parent JVM's profile. The child uses only the contract's declared sources.
    List<String> arguments = new ArrayList<>();
    arguments.add("-Duser.home=" + userHome);
    sysprops.forEach((key, value) -> arguments.add("-D" + key + "=" + value));
    arguments.add("-cp");
    arguments.add(System.getProperty("java.class.path"));
    arguments.add(PathProbe.class.getName());
    arguments.add(osNameFor(platform));
    Path argFile = Files.createTempFile(temporary, "path-fixture-", ".args");
    Files.write(argFile, arguments.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .toList());
    Path output = Files.createTempFile(temporary, "path-fixture-", ".out");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + argFile)
        .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
    builder.environment().remove("JUSTSEARCH_DATA_DIR");
    builder.environment().remove("LOCALAPPDATA");
    builder.environment().putAll(env);
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "path fixture must exit");
      assertEquals(0, child.exitValue());
      assertEquals(Paths.get(expected).toString().replace('\\', '/'),
          Files.readString(output).strip().replace('\\', '/'),
          "Fixture " + fixture.path("name").asText() + " — Java resolution");
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
    }
  }

  public static final class PathProbe {
    public static void main(String[] args) {
      System.setProperty("os.name", args[0]);
      System.out.println(PlatformPaths.resolveDataDir());
    }
  }

  private static String osNameFor(String contractPlatform) {
    return switch (contractPlatform.toLowerCase(Locale.ROOT)) {
      case "windows" -> "Windows 10";
      case "macos" -> "Mac OS X";
      case "linux" -> "Linux";
      default -> throw new IllegalArgumentException("Unknown platform: " + contractPlatform);
    };
  }

  private static Map<String, String> jsonMapToString(JsonNode node) {
    Map<String, String> out = new HashMap<>();
    node.propertyStream().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
    return out;
  }

  private static JsonNode loadSpec() throws IOException {
    // Walk up from the test working directory (usually the module dir) until we find
    // contracts/platform-paths/spec.v1.json. This is hermetic enough for unit tests
    // and avoids hard-coding a relative path that breaks under different runners.
    Path cur = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 8; i++) {
      Path candidate = cur.resolve("contracts/platform-paths/spec.v1.json");
      if (candidate.toFile().isFile()) {
        try (InputStream in = candidate.toUri().toURL().openStream()) {
          return JSON.readTree(in);
        }
      }
      cur = cur.getParent();
      if (cur == null) break;
    }
    return fail("Could not locate contracts/platform-paths/spec.v1.json from "
        + Paths.get("").toAbsolutePath());
  }
}
