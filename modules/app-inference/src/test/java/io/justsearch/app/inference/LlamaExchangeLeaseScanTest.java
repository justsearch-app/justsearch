/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * U14 (Spec A §5.2, reconciliation F13): every llama-server exchange on the proxy, agent and token
 * paths runs inside a generation lease, so a drain before a stop or relaunch waits for it. Eval
 * scripts reach the chat model through the Engine's {@code /v1} proxy, never llama-server directly.
 *
 * <p>A source scan: the lease is a parameter of the only request builder for exchanges, lifecycle
 * probes are the only lease-free requests, and every other class that names a llama-server exchange
 * path is accounted for below.
 */
final class LlamaExchangeLeaseScanTest {

  /** llama-server exchange paths (generation and auxiliary), as string literals. */
  private static final Pattern EXCHANGE_PATH = Pattern.compile(
      "\"/(v1/chat/completions|v1/completions|completions?|tokenize|detokenize|apply-template"
          + "|v1/models|embeddings?|infill|slots|rerank)\"");

  /**
   * Main classes outside the lease-checked exchange owners that may name an exchange path, and why
   * none of them is an unleased exchange with the managed server.
   */
  private static final Map<String, String> ACCOUNTED = Map.of(
      "modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java",
      "chat, vision and agent exchanges; leases checked below",
      "modules/app-inference/src/main/java/io/justsearch/app/inference/TokenEndpointOps.java",
      "tokenize and template exchanges; leases checked below",
      "modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java",
      "the /v1 proxy; admission before the upstream send checked below",
      "modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java",
      "registers the proxy routes; sends nothing",
      "modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java",
      "the install self-test talks to its own private llama-server process, never the managed one",
      "modules/system-tests/src/main/java/io/justsearch/systemtests/chaos/ExternalLlamaServerClient.java",
      "a chaos-test client of an external server it starts itself");

  private static final Path ROOT = repoRoot();
  private static final Path INFERENCE =
      ROOT.resolve("modules/app-inference/src/main/java/io/justsearch/app/inference");

  @Test
  void theExchangeRequestBuilderRequiresALease() {
    String helpers = read(INFERENCE.resolve("InferenceHttpHelpers.java"));
    assertTrue(Pattern.compile("static HttpRequest buildJsonPostRequest\\(\\s*GenerationLease lease,")
            .matcher(helpers).find(),
        "buildJsonPostRequest takes the exchange's GenerationLease first");
    assertTrue(helpers.contains("requireNonNull(lease"), "and refuses a null lease");
  }

  @Test
  void everyInferenceExchangePassesItsLeaseAndOnlyProbesGoWithout() {
    List<String> violations = new ArrayList<>();
    int exchanges = 0;
    for (Path file : javaFiles(INFERENCE)) {
      String name = file.getFileName().toString();
      String source = read(file);
      if (!name.equals("InferenceHttpHelpers.java") && source.contains("HttpRequest.newBuilder")) {
        violations.add(name + " builds a request outside InferenceHttpHelpers");
      }
      Matcher post = Pattern.compile("buildJsonPostRequest\\(\\s*([^,\\s)]+)").matcher(source);
      while (post.find()) {
        if (name.equals("InferenceHttpHelpers.java")) continue;
        exchanges++;
        if (!post.group(1).equals("lease")) {
          violations.add(name + " sends an exchange with " + post.group(1) + " instead of its lease");
        }
      }
      Matcher get = Pattern.compile("buildGetRequest\\(\\s*[^,]+,\\s*([^,\\s)]+)").matcher(source);
      while (get.find()) {
        if (name.equals("InferenceHttpHelpers.java")) continue;
        if (!List.of("PATH_HEALTH", "PATH_PROPS").contains(get.group(1))) {
          violations.add(name + " sends lease-free GET " + get.group(1) + "; only probes may");
        }
      }
    }
    assertEquals(List.of(), violations);
    assertTrue(exchanges >= 6, "the scan found the chat, vision, agent and token exchanges: "
        + exchanges);
  }

  @Test
  void theProxyAdmitsEachExchangeBeforeItReachesLlamaServer() {
    String proxy = read(ROOT.resolve(
        "modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java"));
    int admit = proxy.indexOf("admission.admit(");
    int send = proxy.indexOf("client.send(");
    assertTrue(admit > 0 && send > 0, "the proxy admits and sends");
    assertTrue(admit < send, "the lease opens before the upstream send");
    assertEquals(1, count(proxy, "client.send("), "one upstream send, after admission");
    assertTrue(Pattern.compile("\"/v1/chat/completions\"\\)\\s*\\?\\s*GenerationTracker\\.Kind\\.GENERATE")
            .matcher(proxy).find(),
        "a proxied chat completion is a GENERATE lease");
  }

  @Test
  void noOtherMainClassNamesALlamaServerExchange() {
    List<String> unaccounted = new ArrayList<>();
    for (Path file : javaFiles(ROOT.resolve("modules"))) {
      String relative = ROOT.relativize(file).toString().replace('\\', '/');
      if (!relative.matches("modules/[^/]+/src/main/java/.*")) continue;
      if (ACCOUNTED.containsKey(relative)) continue;
      Matcher path = EXCHANGE_PATH.matcher(read(file));
      if (path.find()) unaccounted.add(relative + " names " + path.group());
    }
    assertEquals(List.of(), unaccounted,
        "a new llama-server caller must hold a generation lease (Spec A §5.2)");
  }

  @Test
  void evalScriptsReachTheChatModelThroughTheProxy() {
    Pattern direct = Pattern.compile("8080");
    Pattern llm = Pattern.compile("(?i)llm|llama|--port|judge");
    List<String> violations = new ArrayList<>();
    Path jseval = ROOT.resolve("scripts/jseval/jseval");
    try (Stream<Path> files = Files.walk(jseval)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".py")).toList()) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (direct.matcher(line).find() && llm.matcher(line).find()) {
            violations.add(ROOT.relativize(file).toString().replace('\\', '/') + ":" + (i + 1)
                + ": " + line.strip());
          }
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    assertEquals(List.of(), violations,
        "eval LLM calls default to the Engine's /v1 proxy, not llama-server's own port");
  }

  // ==================== helpers ====================

  private static int count(String text, String needle) {
    int found = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) found++;
    return found;
  }

  private static List<Path> javaFiles(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static Path repoRoot() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
          && Files.isDirectory(dir.resolve("scripts/jseval"))) {
        return dir;
      }
    }
    throw new IllegalStateException("repo root not found above " + Path.of("").toAbsolutePath());
  }
}
