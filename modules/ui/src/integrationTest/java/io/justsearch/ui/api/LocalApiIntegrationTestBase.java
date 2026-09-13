package io.justsearch.ui.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Integration-test harness for HTTP-level tests against a real {@link LocalApiServer}. */
abstract class LocalApiIntegrationTestBase {
  protected static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir protected Path tmp;

  protected Path aiHome;
  protected LocalApiServer server;
  private final io.justsearch.core.execution.TestEngineExecutors executors = new io.justsearch.core.execution.TestEngineExecutors();
  protected HttpClient client;
  protected String baseUrl;

  private String prevHome;
  private String prevDataDir;
  private String prevApiPort;
  private String prevLlmModelPath;
  private final Map<String, String> extraProps = new LinkedHashMap<>();

  @BeforeEach
  void startServer() throws Exception {
    // Snapshot sysprops we may overwrite so tests are isolated.
    prevHome = System.getProperty("justsearch.home");
    prevDataDir = System.getProperty("justsearch.data.dir");
    prevApiPort = System.getProperty("justsearch.api.port");
    prevLlmModelPath = System.getProperty("justsearch.llm.model_path");

    aiHome = tmp.resolve("ai-home");
    Files.createDirectories(aiHome);

    // Ensure all UI services resolve AI home to this temp directory (no writes to user profile).
    System.setProperty("justsearch.home", aiHome.toAbsolutePath().toString());
    System.setProperty("justsearch.data.dir", aiHome.toAbsolutePath().toString());
    // Force ephemeral port even if a developer has JUSTSEARCH_API_PORT set in their environment.
    System.setProperty("justsearch.api.port", "0");

    // Ensure machine policy (if any) starts absent between tests (only in the sandboxed PROGRAMDATA).
    cleanupMachinePolicySandboxBestEffort();

    // Subclass seam: sysprops + on-disk state that must be in place BEFORE the Head boots (the data
    // dir is already resolved to `aiHome` at this point). Tempdoc 804 §B4.2.
    beforeServerStart();

    // Resolved the way the shipped Head resolves it — the persistence mode is part of the
    // configuration under test, not a harness constant (tempdoc 804 §B1/§B4.2). With no override
    // set this is READ_WRITE, exactly as before.
    UiSettingsStore settingsStore = new UiSettingsStore(UiSettingsStore.PersistenceMode.resolveMode());
    Path indexBase = tmp.resolve("index");
    Files.createDirectories(indexBase);

    server = configureServer(LocalApiServer.builder(executors, settingsStore, indexBase)).build();
    baseUrl = "http://127.0.0.1:" + server.getPort();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      try {
        server.stop();
      } catch (Exception ignored) {
        // best-effort
      } finally {
        server = null;
      }
    }

    executors.close();

    // Best-effort cleanup: remove any machine policy file created during the test, but only inside the sandbox.
    cleanupMachinePolicySandboxBestEffort();

    restoreProp("justsearch.home", prevHome);
    restoreProp("justsearch.data.dir", prevDataDir);
    restoreProp("justsearch.api.port", prevApiPort);
    restoreProp("justsearch.llm.model_path", prevLlmModelPath);
    extraProps.forEach(LocalApiIntegrationTestBase::restoreProp);
    extraProps.clear();
  }

  /**
   * Hook for subclasses that need sysprops or seeded on-disk state in place BEFORE the Head boots
   * (the persistence mode and the prod trust boundary are both read during boot). Set properties
   * with {@link #setPropForTest} so they are restored in teardown.
   */
  protected void beforeServerStart() throws Exception {}

  /** Hook for subclasses that need to customize the server under test (e.g. a session token). */
  protected LocalApiServer.Builder configureServer(LocalApiServer.Builder builder) {
    return builder;
  }

  /** Sets a system property for the duration of the test, recording the previous value. */
  protected void setPropForTest(String key, String value) {
    extraProps.putIfAbsent(key, System.getProperty(key));
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  protected record HttpJsonResponse(int statusCode, JsonNode json, String body) {}

  protected HttpJsonResponse getJson(String path) throws Exception {
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new HttpJsonResponse(resp.statusCode(), parseJsonBestEffort(resp.body()), resp.body());
  }

  protected HttpJsonResponse postJson(String path, Object body) throws Exception {
    return postJson(path, body, Map.of());
  }

  /** POST with extra request headers (e.g. the prod-mode session token). */
  protected HttpJsonResponse postJson(String path, Object body, Map<String, String> headers)
      throws Exception {
    String json = body instanceof String s ? s : MAPPER.writeValueAsString(body);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    headers.forEach(request::header);
    HttpResponse<String> resp = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new HttpJsonResponse(resp.statusCode(), parseJsonBestEffort(resp.body()), resp.body());
  }

  protected JsonNode awaitPackImportDone(Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    String lastBody = "";
    while (System.currentTimeMillis() < deadline) {
      HttpJsonResponse resp = getJson("/api/ai/packs/status");
      lastBody = resp.body;
      JsonNode json = resp.json;
      String state = json.path("state").asText("");
      if (!"running".equalsIgnoreCase(state)) {
        return json;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for pack import to finish. Last status: " + lastBody);
  }

  protected record BuiltPack(Path zipPath, String packId, String packVersion, String manifestSha256) {}

  protected BuiltPack buildZipModelsPack(Path zipPath) throws Exception {
    Files.createDirectories(zipPath.getParent());

    String packId = "justsearch.ai-pack.v2.models.default";
    String packVersion = "2.0.0";

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "%s",
          "packVersion": "%s",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(packId, packVersion, chatSha, chatBytes.length, embedSha, embedBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    writeZip(zipPath, entries);

    return new BuiltPack(zipPath, packId, packVersion, manifestSha);
  }

  protected Path userPolicyPath() {
    return aiHome.resolve("policy.v1.json");
  }

  protected void writeUserPolicyAllowlist(String manifestSha256) throws Exception {
    String json =
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-27T00:00:00Z",
          "allowlists": {
            "packManifestSha256": ["%s"]
          }
        }
        """
            .formatted(manifestSha256);
    Files.writeString(userPolicyPath(), json, StandardCharsets.UTF_8);
  }

  protected Path machinePolicyPathOrNull() {
    String programData = System.getenv("PROGRAMDATA");
    if (programData == null || programData.isBlank()) {
      return null;
    }
    return Path.of(programData).resolve("JustSearch").resolve("policy.v1.json");
  }

  protected boolean isProgramDataSandbox() {
    String programData = System.getenv("PROGRAMDATA");
    if (programData == null || programData.isBlank()) {
      return false;
    }
    String norm = programData.replace('\\', '/').toLowerCase(Locale.ROOT);
    // Safety: we only write/delete machine policy when PROGRAMDATA is our integration-test sandbox.
    return norm.contains("it-programdata");
  }

  protected void cleanupMachinePolicySandboxBestEffort() {
    if (!isProgramDataSandbox()) {
      return;
    }
    Path machine = machinePolicyPathOrNull();
    if (machine == null) {
      return;
    }
    try {
      Files.deleteIfExists(machine);
    } catch (Exception ignored) {
      // best-effort
    }
  }

  protected void writeMachinePolicyWithEmptyPackAllowlist() throws Exception {
    if (!isProgramDataSandbox()) {
      throw new IllegalStateException(
          "Refusing to write machine policy outside sandboxed PROGRAMDATA. "
              + "Run via Gradle :modules:ui:integrationTest which sets PROGRAMDATA to build/it-programdata.");
    }
    Path machine = machinePolicyPathOrNull();
    if (machine == null) {
      throw new IllegalStateException("PROGRAMDATA not set; cannot resolve machine policy path.");
    }
    Files.createDirectories(machine.getParent());
    String json =
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-27T00:00:00Z",
          "allowlists": {
            "packManifestSha256": []
          }
        }
        """;
    Files.writeString(machine, json, StandardCharsets.UTF_8);
  }

  private static JsonNode parseJsonBestEffort(String body) {
    if (body == null) {
      return MAPPER.nullNode();
    }
    try {
      return MAPPER.readTree(body);
    } catch (Exception e) {
      ObjectNode n = MAPPER.createObjectNode();
      n.put("raw", body);
      return n;
    }
  }

  private static void restoreProp(String key, String prev) {
    if (prev == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, prev);
    }
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(bytes);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void writeZip(Path zip, Map<String, byte[]> entries) throws Exception {
    try (OutputStream fos = Files.newOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
      for (var e : entries.entrySet()) {
        ZipEntry ze = new ZipEntry(e.getKey());
        zos.putNextEntry(ze);
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
  }

}
