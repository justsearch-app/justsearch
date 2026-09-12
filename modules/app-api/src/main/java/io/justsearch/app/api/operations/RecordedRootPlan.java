/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Immutable, filesystem-free root scope prepared for a recorded operation. */
public record RecordedRootPlan(String generation, List<RecordedRootPlan.Root> roots) {
  public static final String SCHEMA = RootPlanReplayProjection.SCHEMA;

  private static final int MAX_ROOTS = 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public RecordedRootPlan {
    generation = validateGeneration(generation);
    Objects.requireNonNull(roots, "roots");
    if (roots.size() > MAX_ROOTS) {
      throw new IllegalArgumentException("A root plan may contain at most 1024 roots");
    }
    roots = List.copyOf(roots);
  }

  /** Render the safe root-plan payload, without its enclosing operation identity. */
  public String toReplayPayload() {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("generation", generation);
    List<Map<String, Object>> encodedRoots = new ArrayList<>();
    for (Root root : roots) encodedRoots.add(root.toReplayMap());
    plan.put("roots", encodedRoots);
    RootPlanReplayProjection.validate(SCHEMA, plan);
    String payload = JSON.writeValueAsString(plan);
    if (payload.length() > RootPlanReplayProjection.MAX_PAYLOAD_CHARS) {
      throw new IllegalArgumentException("Replay projection is too large");
    }
    return payload;
  }

  /** Parse one strict {@value SCHEMA} payload into an immutable plan. */
  public static RecordedRootPlan fromReplayPayload(String payloadJson) {
    return RootPlanReplayProjection.parsePlan(SCHEMA, payloadJson);
  }

  /** Normalize and partition requested roots into one deterministic ownership plan. */
  public static RecordedRootPlan partition(String generation, List<Root> requested) {
    validateGeneration(generation);
    Objects.requireNonNull(requested, "requested");
    if (requested.size() > MAX_ROOTS) {
      throw new IllegalArgumentException("A root plan may contain at most 1024 roots");
    }

    Map<Path, Root> unique = new LinkedHashMap<>();
    for (Root root : requested) {
      Objects.requireNonNull(root, "requested root");
      if (!root.excludedSubtrees().isEmpty()) {
        throw new IllegalArgumentException("Requested roots must not contain excluded subtrees");
      }
      Root prior = unique.putIfAbsent(root.path(), root);
      if (prior != null && !sameIdentityPolicy(prior, root)) {
        throw new IllegalArgumentException("Conflicting policies for the same root path");
      }
    }

    List<Root> ordered = new ArrayList<>(unique.values());
    ordered.sort(Comparator.comparingInt((Root root) -> root.path().getNameCount())
        .thenComparing(root -> root.path().toString()));

    List<Root> retained = new ArrayList<>();
    for (Root candidate : ordered) {
      Root nearest = nearestRetainedAncestor(candidate, retained);
      if (nearest != null && !nearest.singleFile() && sameEffectivePolicy(nearest, candidate)) {
        continue;
      }
      retained.add(candidate);
    }

    Map<Path, Path> parentByPath = new HashMap<>();
    for (Root root : retained) {
      Root parent = nearestRetainedAncestor(root, retained);
      parentByPath.put(root.path(), parent == null ? null : parent.path());
    }
    Map<Path, List<Path>> childrenByParent = new HashMap<>();
    for (Root root : retained) {
      Path parent = parentByPath.get(root.path());
      if (parent != null) {
        childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(root.path());
      }
    }

    List<Root> planned = new ArrayList<>();
    for (Root root : retained) {
      List<Path> children = childrenByParent.getOrDefault(root.path(), List.of());
      planned.add(new Root(root.path(), root.collection(), root.force(), root.singleFile(),
          root.excludePatterns(), children));
    }
    return new RecordedRootPlan(generation, planned);
  }

  private static Root nearestRetainedAncestor(Root candidate, List<Root> retained) {
    Root nearest = null;
    for (Root root : retained) {
      if (candidate.path().equals(root.path()) || !candidate.path().startsWith(root.path())) {
        continue;
      }
      if (nearest == null
          || root.path().getNameCount() > nearest.path().getNameCount()) nearest = root;
    }
    return nearest;
  }

  private static boolean sameEffectivePolicy(Root left, Root right) {
    return Objects.equals(left.collection(), right.collection())
        && left.force() == right.force()
        && left.excludePatterns().equals(right.excludePatterns());
  }

  private static boolean sameIdentityPolicy(Root left, Root right) {
    return sameEffectivePolicy(left, right) && left.singleFile() == right.singleFile();
  }

  private static String validateGeneration(String value) {
    Objects.requireNonNull(value, "generation");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("Invalid replay generation identity");
    }
    return RootPlanReplayProjection.validateGeneration(value);
  }

  public record Root(Path path, String collection, boolean force, boolean singleFile,
      List<String> excludePatterns, List<Path> excludedSubtrees) {
    public Root {
      Objects.requireNonNull(path, "path");
      path = path.toAbsolutePath().normalize();
      if (path.toString().length() > 32768) {
        throw new IllegalArgumentException("Root path is too long");
      }
      Objects.requireNonNull(excludePatterns, "excludePatterns");
      Objects.requireNonNull(excludedSubtrees, "excludedSubtrees");
      if (excludePatterns.size() > MAX_ROOTS || excludedSubtrees.size() > MAX_ROOTS) {
        throw new IllegalArgumentException("Root policy lists may contain at most 1024 entries");
      }
      if (collection != null && (collection.isBlank() || collection.length() > 256)) {
        throw new IllegalArgumentException("Invalid root collection");
      }
      List<String> patterns = new ArrayList<>();
      for (String pattern : excludePatterns) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 4096) {
          throw new IllegalArgumentException("Invalid root exclude pattern");
        }
        patterns.add(pattern);
      }
      List<Path> subtrees = new ArrayList<>();
      for (Path subtree : excludedSubtrees) {
        Objects.requireNonNull(subtree, "excluded subtree");
        Path normalized = subtree.toAbsolutePath().normalize();
        if (normalized.toString().length() > 32768) {
          throw new IllegalArgumentException("Excluded subtree path is too long");
        }
        if (normalized.equals(path) || !normalized.startsWith(path)) {
          throw new IllegalArgumentException("Excluded subtree must be inside its root");
        }
        subtrees.add(normalized);
      }
      excludePatterns = List.copyOf(patterns);
      excludedSubtrees = List.copyOf(subtrees);
    }

    private Map<String, Object> toReplayMap() {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("path", path.toString());
      root.put("collection", collection);
      root.put("force", force);
      root.put("singleFile", singleFile);
      root.put("excludePatterns", excludePatterns);
      List<String> subtrees = new ArrayList<>();
      for (Path subtree : excludedSubtrees) subtrees.add(subtree.toString());
      root.put("excludedSubtrees", subtrees);
      return root;
    }
  }
}
