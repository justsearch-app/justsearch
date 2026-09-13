/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.core.context.EngineContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchRequest.Clause;
import io.justsearch.app.api.SearchRequest.Filters;
import io.justsearch.app.api.SearchResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class LauncherCommands implements Launcher.CommandRunner {
  private final LauncherEnvironment environment;

  LauncherCommands(LauncherEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public CommandResult reindex() {
    try {
      // Tempdoc 519 §5 / Step 4b: route through typed records.
      io.justsearch.app.api.IndexingService indexing =
          environment.HeadAssembly().workers().indexing();
      if (indexing == null) {
        return CommandResult.success(List.of("REINDEX/SKIP reason=UNSUPPORTED"));
      }
      indexing.reindex(io.justsearch.app.services.intent.EngineProvenance.context(EngineContext.ClientKind.CLI, "launcher", java.util.Optional.empty(), java.util.Optional.empty(), io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND));
      return CommandResult.success(List.of("REINDEX/TRIGGERED"));
    } catch (UnsupportedOperationException e) {
      // In Knowledge Server mode (or other headless configurations), indexing may be intentionally unavailable.
      // Treat this as a skip so launcher smoke/evidence tasks remain meaningful.
      return CommandResult.success(List.of("REINDEX/SKIP reason=UNSUPPORTED"));
    } catch (Exception e) {
      return CommandResult.failure(List.of("REINDEX/FAIL"), e);
    }
  }

  @Override
  public CommandResult seed(Path fixturesPath) {
    return CommandResult.success(List.of("SEED/SKIP reason=UNSUPPORTED"));
  }

  @Override
  public CommandResult verify() {
    List<String> markers = new ArrayList<>();
    try {
      Filters filters =
          new Filters(null, Locale.US.toLanguageTag(), new SearchRequest.TimeRange(null, null));
      Clause clause = new Clause("full_text", "default", "launcher verify", List.of());
      SearchRequest request =
          new SearchRequest(10, 0, true, filters, List.of(), List.of(clause), null);
      // Tempdoc 519 §5 / Step 4b: route through typed records.
      io.justsearch.app.api.SearchService search =
          environment.HeadAssembly().workers().search();
      if (search == null) {
        markers.add("VERIFY/SKIP reason=SEARCH_UNAVAILABLE");
        return CommandResult.success(markers);
      }
      SearchResponse response = search.search(request, io.justsearch.app.services.intent.EngineProvenance.context(EngineContext.ClientKind.CLI, "launcher", java.util.Optional.empty(), java.util.Optional.empty(), io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND));
      markers.add("VERIFY/OK hits=" + response.hits().size());
      markers.add("VERIFY/PIPELINE profile=" + io.justsearch.configuration.resolved.ConfigStore.global().get().search().profile());
      return CommandResult.success(markers);
    } catch (Exception e) {
      markers.add("VERIFY/FAIL code=" + e.getClass().getSimpleName());
      return CommandResult.failure(markers, e);
    }
  }

  @Override
  public CommandResult snapshot() {
    return CommandResult.success(List.of("SNAPSHOT/SKIP reason=UNSUPPORTED"));
  }

  record CommandResult(boolean success, List<String> markers, Throwable error) {
    static CommandResult success(List<String> markers) {
      return new CommandResult(true, List.copyOf(markers), null);
    }

    static CommandResult failure(List<String> markers, Throwable error) {
      return new CommandResult(false, List.copyOf(markers), error);
    }
  }
}
