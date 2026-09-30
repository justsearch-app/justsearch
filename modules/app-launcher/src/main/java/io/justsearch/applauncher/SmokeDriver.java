/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchRequest.Clause;
import io.justsearch.app.api.SearchRequest.Filters;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.services.HeadAssembly;
import java.util.function.Function;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SmokeDriver implements Launcher.SmokeDriverHandle {
  private static final Set<String> KNOWN_PROFILE_KEYS =
      Set.of("app", "egress", "llm", "workers", "telemetry", "plugins", "infra", "search", "index");
  private final LauncherEnvironment environment;
  /** Tempdoc 519 §5 endpoint: a simple SearchRequest→SearchResponse function, supplied by
   * the bootstrap in production and by test fixtures in unit tests. Replaces the legacy
   * AppFacade dependency. */
  private final Function<SearchRequest, SearchResponse> searchFn;
  private static volatile Launcher.CommandRunnerFactory commandRunnerFactory = LauncherCommands::new;

  static SmokeDriver create(Launcher.SmokeOptions options) throws Exception {
    return new SmokeDriver(options);
  }

  private SmokeDriver(Launcher.SmokeOptions options) throws Exception {
    this.environment = LauncherEnvironment.create(options.profile());
    HeadAssembly bootstrap = environment.HeadAssembly();
    this.searchFn = bootstrap == null ? null : req -> bootstrap.workers().search().search(req, io.justsearch.app.services.intent.EngineProvenance.context(EngineContext.ClientKind.CLI, "launcher", java.util.Optional.empty(), java.util.Optional.empty(), io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND));
  }

  @Override
  public SmokeResult execute() {
    List<String> diagnostics = new ArrayList<>();
    List<String> failures = new ArrayList<>();

    ResolvedConfig rc = ConfigStore.global().get();
    if (!rc.workerIndexer().enabled()) {
      diagnostics.add("LAUNCHER/WORKER_MISSING kind=indexer");
    }
    if (rc.ai().llmEnabled()) {
      Path llmModelPath = rc.ai().llmModelPath();
      String modelPathStr = llmModelPath != null ? llmModelPath.toString() : null;
      if (modelPathStr == null || modelPathStr.isBlank()) {
        diagnostics.add("LAUNCHER/MODEL_MISSING path=<unset>");
        if (Boolean.parseBoolean(System.getProperty("llm.requireModel", "false"))) {
          failures.add("LAUNCHER/MODEL_MISSING path=<unset>");
          enforceConfigPolicies(failures, rc);
          return new SmokeResult(
              2,
              List.copyOf(diagnostics),
              rc.policy().egressBlockAll(),
              List.copyOf(failures));
        }
      }
    }
    enforceConfigPolicies(failures, rc);
    Launcher.CommandRunner commands;
    try {
      commands = commandRunnerFactory.create(environment);
    } catch (Exception e) {
      failures.add(
          "SMOKE_COMMANDS_ERROR code="
              + e.getClass().getSimpleName()
              + " message="
              + sanitize(e.getMessage()));
      return new SmokeResult(
          2,
          List.copyOf(diagnostics),
          rc.policy().egressBlockAll(),
          List.copyOf(failures));
    }
    try {
      recordCommandResult(commands.reindex(), diagnostics, failures);
    } catch (Exception e) {
      appendCommandFailure("reindex", e, failures);
    }
    SearchResponse response = runSmokeQuery(diagnostics, failures);
    if (response != null && !response.hits().isEmpty()) {
      failures.add("SMOKE_QUERY_NONZERO hits=" + response.hits().size());
    }
    try {
      recordCommandResult(commands.verify(), diagnostics, failures);
    } catch (Exception e) {
      appendCommandFailure("verify", e, failures);
    }
    try {
      recordCommandResult(commands.snapshot(), diagnostics, failures);
    } catch (Exception e) {
      appendCommandFailure("snapshot", e, failures);
    }
    int exitCode = failures.isEmpty() ? 0 : 2;
    return new SmokeResult(
        exitCode,
        List.copyOf(diagnostics),
        rc.policy().egressBlockAll(),
        List.copyOf(failures));
  }

  static void installCommandRunnerFactory(Launcher.CommandRunnerFactory factory) {
    commandRunnerFactory = factory != null ? factory : LauncherCommands::new;
  }

  static void resetFactories() {
    commandRunnerFactory = LauncherCommands::new;
  }

  private void enforceConfigPolicies(List<String> failures, ResolvedConfig rc) {
    boolean egress = rc.policy().egressBlockAll();
    if (!egress) {
      failures.add("CONFIG_EGRESS_GUARD_OFF");
    }
    validateProfileKeys(failures);
  }

  @SuppressWarnings("PMD.UnusedFormalParameter") // diagnostics reserved for verbose output
  private SearchResponse runSmokeQuery(List<String> diagnostics, List<String> failures) {
    try {
      String languageTag = Locale.US.toLanguageTag();
      Filters filters =
          new Filters(null, languageTag, new SearchRequest.TimeRange(null, null));
      Clause clause = new Clause("full_text", "default", "smoke test", List.of());
      SearchRequest request = new SearchRequest(5, 0, true, filters, List.of(), List.of(clause), null);
      if (searchFn == null) {
        failures.add("SMOKE_QUERY_SEARCH_UNAVAILABLE");
        return null;
      }
      return searchFn.apply(request);
    } catch (Exception e) {
      failures.add(
          "SMOKE_QUERY_ERROR code="
              + e.getClass().getSimpleName()
              + " message="
              + sanitize(e.getMessage()));
      Throwable cause = e.getCause();
      if (cause != null) {
        failures.add(
            "SMOKE_QUERY_CAUSE code="
                + cause.getClass().getSimpleName()
                + " message="
                + sanitize(cause.getMessage()));
      }
      e.printStackTrace(System.err);
      return null;
    }
  }

  private void recordCommandResult(
      LauncherCommands.CommandResult result, List<String> diagnostics, List<String> failures) {
    if (result == null) {
      return;
    }
    if (result.success()) {
      diagnostics.addAll(result.markers());
    } else {
      failures.add(String.join(" ", result.markers()));
      if (result.error() != null) {
        result.error().printStackTrace(System.err);
      }
    }
  }

  private void appendCommandFailure(String command, Exception error, List<String> failures) {
    failures.add(
        "SMOKE_COMMAND_ERROR command="
            + command
            + " code="
            + error.getClass().getSimpleName()
            + " message="
            + sanitize(error.getMessage()));
  }

  @Override
  public void close() {
    environment.close();
  }

  private String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "<empty>";
    }
    return value.replaceAll("[\\r\\n]+", " ").trim();
  }

  private void validateProfileKeys(List<String> failures) {
    Path profile = environment.profilePath();
    if (profile == null) {
      failures.add("LAUNCHER/CONFIG_MISSING key=profile_path");
      return;
    }
    if (!java.nio.file.Files.exists(profile)) {
      failures.add("LAUNCHER/CONFIG_MISSING key=" + profile.getFileName());
      return;
    }
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    try {
      JsonNode root = mapper.readTree(profile.toFile());
      for (var entry : root.properties()) {
        if (!KNOWN_PROFILE_KEYS.contains(entry.getKey())) {
          failures.add("CONFIG/UNKNOWN_KEY key=" + entry.getKey());
        }
      }
      if (!root.path("egress").has("block_all")) {
        failures.add("LAUNCHER/CONFIG_MISSING key=egress.block_all");
      }
      if (!root.path("search").path("pipeline").has("profile")) {
        failures.add("LAUNCHER/CONFIG_MISSING key=search.pipeline.profile");
      }
      JsonNode collections = root.path("index").path("collections");
      if (collections.isArray()) {
        for (JsonNode collection : collections) {
          String name = collection.path("name").asText("unknown");
          JsonNode roots = collection.path("roots");
          if (roots.isArray()) {
            for (JsonNode node : roots) {
              if (node.isTextual()) {
                String rootValue = node.asText();
                Path rootPath = Path.of(rootValue);
                if (rootPath.isAbsolute() || rootValue.startsWith("/")) {
                  failures.add("CONFIG_ABSOLUTE_PATH collection=" + name);
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      failures.add("CONFIG/INVALID profile=" + profile.getFileName());
    }
  }
}

record SmokeResult(
    int exitCode,
    List<String> diagnostics,
    boolean egressBlocked,
    List<String> failures) {}
