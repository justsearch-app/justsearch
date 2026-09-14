/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** Prepared, recorded ingestion operation. */
public final class IngestTool implements OperationHandler {
  private static final int MAX_PATHS = 100;

  private final RecordedIngestionService ingestion;
  private final Function<EngineContext, List<RootBinding>> roots;
  private final Function<EngineContext, String> generation;
  private final Supplier<List<String>> exclusions;

  /** Creates an ingestion handler whose preparation dependencies are supplied by the server. */
  public IngestTool(RecordedIngestionService ingestion,
      Function<EngineContext, List<RootBinding>> roots,
      Function<EngineContext, String> generation,
      Supplier<List<String>> exclusions) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.generation = Objects.requireNonNull(generation, "generation");
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
  }

  /** Direct invocation is not an execution authority for a recorded operation. */
  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    throw new IllegalStateException("Ingest requires an accepted prepared invocation");
  }

  @Override
  public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext engineContext) {
    JsonNode args = parseArguments(argumentsJson);
    List<String> rawPaths = pathsOf(args);
    String requestedCollection = collectionOf(args);

    // These are captured once. A supplier failure is a preparation failure, never an empty-root
    // degradation that could turn an unavailable authorization view into a different operation.
    List<RootBinding> bindings = List.copyOf(Objects.requireNonNull(roots.apply(engineContext), "roots"));
    List<String> patterns = List.copyOf(Objects.requireNonNull(exclusions.get(), "exclusions"));
    List<BrowseTool.RootInfo> rootInfos = rootInfos(bindings);

    List<RecordedRootPlan.Root> requested = new ArrayList<>();
    for (String rawPath : rawPaths) {
      Path input = resolvePath(rawPath, bindings, rootInfos);
      if (input == null) {
        throw badInput("Path could not be resolved: " + rawPath);
      }
      BasicFileAttributes attributes = attributes(input);
      String collection = requestedCollection == null
          ? IngestCollectionPolicy.resolve(null, input, bindings)
          : requestedCollection;
      requested.add(new RecordedRootPlan.Root(input, collection, false, attributes.isRegularFile(),
          patterns, List.of()));

      // A directory preparation records every watched nested root so the deepest label is frozen.
      if (attributes.isDirectory()) {
        for (RootBinding binding : bindings) {
          Path nested = binding.path().toAbsolutePath().normalize();
          if (nested.equals(input) || !nested.startsWith(input)) {
            continue;
          }
          String nestedCollection = requestedCollection == null
              ? IngestCollectionPolicy.resolve(null, nested, bindings)
              : requestedCollection;
          requested.add(new RecordedRootPlan.Root(nested, nestedCollection, false, false,
              patterns, List.of()));
        }
      }
    }

    String targetGeneration = Objects.requireNonNull(generation.apply(engineContext), "generation");
    RecordedRootPlan plan = RecordedRootPlan.partition(targetGeneration, requested);
    if (plan.roots().isEmpty()) {
      throw badInput("At least one valid ingest path is required");
    }
    return new OperationPreparation(argumentsJson, RecordedRootPlan.SCHEMA, plan.toReplayPayload());
  }

  @Override
  public void validatePreparation(OperationPreparation prepared) {
    Objects.requireNonNull(prepared, "prepared");
    JsonNode args = parseArguments(prepared.argumentsJson());
    List<String> paths = pathsOf(args);
    String requestedCollection = collectionOf(args);
    RecordedRootPlan plan = frozenPlan(prepared);
    if (paths.isEmpty() || plan.roots().isEmpty()) {
      throw new IllegalArgumentException("Prepared ingest must contain paths and roots");
    }
    if (requestedCollection != null
        && plan.roots().stream().anyMatch(root -> !requestedCollection.equals(root.collection()))) {
      throw new IllegalArgumentException("Prepared ingest collection does not match its root plan");
    }
  }

  @Override
  public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    RecordedRootPlan plan = frozenPlan(prepared);
    StringBuilder summary = new StringBuilder("Ingest ");
    summary.append(plan.roots().size()).append(" prepared root(s): ");
    int shown = Math.min(6, plan.roots().size());
    for (int i = 0; i < shown; i++) {
      if (i > 0) summary.append(", ");
      String path = plan.roots().get(i).path().toString();
      summary.append(path, 0, Math.min(path.length(), 256));
    }
    if (plan.roots().size() > shown) {
      summary.append(" (+").append(plan.roots().size() - shown).append(" more)");
    }
    return new OperationApprovalPreview(summary.toString());
  }

  @Override
  public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext engineContext, OperationRecordHandle record) {
    validatePreparation(prepared);
    Objects.requireNonNull(record, "Accepted ingest record");
    return ingestion.execute(record, engineContext);
  }

  private static RecordedRootPlan frozenPlan(OperationPreparation prepared) {
    if (prepared.replaySchema() == null
        || !RecordedRootPlan.SCHEMA.equals(prepared.replaySchema())
        || prepared.replayPayloadJson() == null
        || prepared.content() != OperationPreparation.Content.METADATA) {
      throw new IllegalArgumentException("Unsupported ingest preparation");
    }
    RecordedRootPlan plan = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());
    if (plan.roots().isEmpty()
        || plan.roots().stream().anyMatch(root -> root.force())) {
      throw new IllegalArgumentException("Ingest preparation must contain non-forced roots");
    }
    return plan;
  }

  private static JsonNode parseArguments(String argumentsJson) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      if (args == null || !args.isObject()) {
        throw badInput("Ingest arguments must be a JSON object");
      }
      return args;
    } catch (OperationPreparationRefused e) {
      throw e;
    } catch (RuntimeException e) {
      throw badInput("Invalid ingest arguments");
    }
  }

  private static List<String> pathsOf(JsonNode args) {
    JsonNode node = args.get("paths");
    if (node == null || !node.isArray() || node.size() == 0 || node.size() > MAX_PATHS) {
      throw badInput("paths must contain between 1 and 100 entries");
    }
    List<String> paths = new ArrayList<>();
    for (JsonNode value : node) {
      if (!value.isTextual() || value.asString().isBlank()) {
        throw badInput("Each ingest path must be a non-empty string");
      }
      paths.add(value.asString());
    }
    return List.copyOf(paths);
  }

  private static String collectionOf(JsonNode args) {
    JsonNode node = args.get("collection");
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw badInput("collection must be a string");
    }
    try {
      return IngestCollectionPolicy.normalizeRequested(node.asString());
    } catch (IllegalArgumentException e) {
      throw badInput(e.getMessage());
    }
  }

  private static List<BrowseTool.RootInfo> rootInfos(List<RootBinding> bindings) {
    List<BrowseTool.RootInfo> infos = new ArrayList<>();
    for (RootBinding binding : bindings) {
      if (binding == null || binding.path() == null) {
        throw new IllegalStateException("Recorded root binding is incomplete");
      }
      Path path = binding.path().toAbsolutePath().normalize();
      Path fileName = path.getFileName();
      infos.add(new BrowseTool.RootInfo(path.toString(), fileName == null ? path.toString() : fileName.toString()));
    }
    return List.copyOf(infos);
  }

  private static Path resolvePath(String rawPath, List<RootBinding> bindings,
      List<BrowseTool.RootInfo> rootInfos) {
    try {
      Path candidate = Path.of(rawPath);
      if (candidate.isAbsolute()) {
        return candidate.normalize();
      }
      String named = AgentToolPaths.resolveRelativePath(rawPath, rootInfos);
      if (named != null && Files.exists(Path.of(named), LinkOption.NOFOLLOW_LINKS)) {
        return Path.of(named).toAbsolutePath().normalize();
      }
      for (RootBinding binding : bindings) {
        Path underRoot = binding.path().toAbsolutePath().normalize().resolve(candidate).normalize();
        if (underRoot.startsWith(binding.path().toAbsolutePath().normalize())
            && Files.exists(underRoot, LinkOption.NOFOLLOW_LINKS)) {
          return underRoot;
        }
      }
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static BasicFileAttributes attributes(Path input) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(input, BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())
          || !Files.isReadable(input)) {
        throw badInput("Path is not a readable regular file or directory: " + input);
      }
      return attributes;
    } catch (OperationPreparationRefused e) {
      throw e;
    } catch (Exception e) {
      throw badInput("Path is missing or unreadable: " + input);
    }
  }

  private static OperationPreparationRefused badInput(String message) {
    return new OperationPreparationRefused(
        OperationResult.failure(message, "BAD_REQUEST", Map.of(), false));
  }
}
