/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import io.justsearch.configuration.ConfigKey;
import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime projection of the governed configuration apply register.
 *
 * <p>The register is packaged from {@code governance/config-apply.v1.json} and parsed once when
 * this class is initialized. A malformed, incomplete, or unknown register fails closed during
 * initialization; callers never receive a partial classification.
 */
public final class ConfigApplyScopes {

  /** Classpath location populated by this module's {@code processResources} task. */
  public static final String RESOURCE_PATH = "governance/config-apply.v1.json";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> COMPONENTS =
      Set.of("api", "index", "encoders", "generative");
  private static final Set<String> SIMPLE_SCOPES =
      Set.of("hot", "generation-bound", "restart-required");
  private static final Set<String> DECLARED_KEYS = declaredKeys();
  private static final Register REGISTER = loadRegister();

  private ConfigApplyScopes() {}

  /** The validated, immutable register definition. */
  public static Register register() {
    return REGISTER;
  }

  /** Returns the validated apply scope for a declared configuration key. */
  public static ApplyScope scopeFor(String key) {
    Objects.requireNonNull(key, "key");
    ApplyScope scope = REGISTER.scopesByKey().get(key);
    if (scope == null) {
      throw new IllegalArgumentException("No governed apply scope for configuration key: " + key);
    }
    return scope;
  }

  /**
   * Classifies effective value changes between two resolved snapshots.
   *
   * <p>Only {@link ConfigResolution#value()} participates in the comparison. A source ordinal,
   * source name, or source detail change with the same effective value is a no-op. A changed key
   * absent from the governed register fails closed rather than receiving an invented default.
   */
  public static ChangedKeys classify(ResolvedConfig previous, ResolvedConfig current) {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(current, "current");

    Set<String> keys = new TreeSet<>();
    keys.addAll(previous.resolutions().keySet());
    keys.addAll(current.resolutions().keySet());

    Set<String> hot = new TreeSet<>();
    Map<String, Set<String>> component = new TreeMap<>();
    Set<String> generationBound = new TreeSet<>();
    Set<String> restartRequired = new TreeSet<>();
    for (String key : keys) {
      String before = valueOf(previous, key);
      String after = valueOf(current, key);
      if (Objects.equals(before, after)) {
        continue;
      }
      ApplyScope scope = scopeFor(key);
      switch (scope.kind()) {
        case HOT -> hot.add(key);
        case COMPONENT -> component.computeIfAbsent(scope.component(), ignored -> new TreeSet<>()).add(key);
        case GENERATION_BOUND -> generationBound.add(key);
        case RESTART_REQUIRED -> restartRequired.add(key);
      }
    }
    return new ChangedKeys(hot, component, generationBound, restartRequired);
  }

  private static String valueOf(ResolvedConfig config, String key) {
    ConfigResolution resolution = config.resolutions().get(key);
    return resolution == null ? null : resolution.value();
  }

  private static Register loadRegister() {
    try (InputStream input = ConfigApplyScopes.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
      if (input == null) {
        throw new IllegalStateException("Missing packaged apply register: " + RESOURCE_PATH);
      }
      return parseRegister(JSON.readTree(input));
    } catch (IOException | RuntimeException failure) {
      throw new ExceptionInInitializerError(
          new IllegalStateException("Invalid packaged apply register: " + RESOURCE_PATH, failure));
    }
  }

  private static Register parseRegister(JsonNode root) {
    require(root != null && root.isObject(), "register root must be an object");
    require(fields(root).equals(Set.of("schemaVersion", "note", "entries")),
        "register fields must be exactly schemaVersion, note, entries");
    require(root.get("schemaVersion").isInt() && root.get("schemaVersion").asInt() == 1,
        "schemaVersion must be integer 1");
    require(root.get("note").isTextual() && !root.get("note").stringValue().isBlank(),
        "note must be nonblank text");
    require(root.get("entries").isArray(), "entries must be an array");

    Map<String, ApplyScope> scopes = new LinkedHashMap<>();
    String previous = null;
    for (JsonNode entry : root.get("entries")) {
      require(entry.isObject(), "each entry must be an object");
      require(fields(entry).equals(Set.of("key", "applyScope")),
          "entry fields must be exactly key and applyScope");
      JsonNode keyNode = entry.get("key");
      JsonNode scopeNode = entry.get("applyScope");
      require(keyNode.isTextual() && !keyNode.stringValue().isBlank(),
          "entry key must be nonblank text");
      require(scopeNode.isTextual() && !scopeNode.stringValue().isBlank(),
          "entry applyScope must be nonblank text");
      String key = keyNode.stringValue();
      String scopeValue = scopeNode.stringValue();
      require(DECLARED_KEYS.contains(key), "unknown configuration key: " + key);
      require(!scopes.containsKey(key), "duplicate configuration key: " + key);
      require(previous == null || previous.compareTo(key) < 0,
          "entries must be sorted by key: `" + previous + "` must precede `" + key + "`");
      ApplyScope scope = ApplyScope.parse(scopeValue);
      scopes.put(key, scope);
      previous = key;
    }
    Set<String> missing = new TreeSet<>(DECLARED_KEYS);
    missing.removeAll(scopes.keySet());
    require(missing.isEmpty(), "missing configuration keys: " + missing);
    return new Register(1, Collections.unmodifiableMap(new LinkedHashMap<>(scopes)));
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> fields = new LinkedHashSet<>();
    node.propertyStream().map(Map.Entry::getKey).forEach(fields::add);
    return fields;
  }

  private static Set<String> declaredKeys() {
    Set<String> keys = new TreeSet<>();
    Arrays.stream(EnvRegistry.values()).map(EnvRegistry::configKey).forEach(keys::add);
    Arrays.stream(ConfigKey.values()).map(ConfigKey::configKey).forEach(keys::add);
    return Collections.unmodifiableSet(keys);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** A validated apply-register snapshot. */
  public record Register(int schemaVersion, Map<String, ApplyScope> scopesByKey) {
    public Register {
      scopesByKey = Collections.unmodifiableMap(new LinkedHashMap<>(scopesByKey));
    }

    public int size() {
      return scopesByKey.size();
    }
  }

  /** A single apply scope from the governed register. */
  public record ApplyScope(Kind kind, String component, String wireValue) {
    public ApplyScope {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(wireValue, "wireValue");
      if (kind == Kind.COMPONENT) {
        Objects.requireNonNull(component, "component");
      } else if (component != null) {
        throw new IllegalArgumentException("Only component scopes have a component name");
      }
    }

    private static ApplyScope parse(String wireValue) {
      if (SIMPLE_SCOPES.contains(wireValue)) {
        return switch (wireValue) {
          case "hot" -> new ApplyScope(Kind.HOT, null, wireValue);
          case "generation-bound" -> new ApplyScope(Kind.GENERATION_BOUND, null, wireValue);
          case "restart-required" -> new ApplyScope(Kind.RESTART_REQUIRED, null, wireValue);
          default -> throw new AssertionError(wireValue);
        };
      }
      if (wireValue.startsWith("component:")) {
        String component = wireValue.substring("component:".length());
        require(COMPONENTS.contains(component), "invalid apply scope: " + wireValue);
        return new ApplyScope(Kind.COMPONENT, component, wireValue);
      }
      throw new IllegalArgumentException("invalid apply scope: " + wireValue);
    }

    public enum Kind {
      HOT,
      COMPONENT,
      GENERATION_BOUND,
      RESTART_REQUIRED
    }
  }

  /** Immutable changed-key buckets grouped by the governed apply scope. */
  public record ChangedKeys(
      Set<String> hot,
      Map<String, Set<String>> component,
      Set<String> generationBound,
      Set<String> restartRequired) {
    public ChangedKeys {
      hot = immutableSet(hot);
      generationBound = immutableSet(generationBound);
      restartRequired = immutableSet(restartRequired);
      Map<String, Set<String>> copy = new TreeMap<>();
      component.forEach((name, keys) -> copy.put(name, immutableSet(keys)));
      component = Collections.unmodifiableMap(copy);
    }

    public boolean isNoOp() {
      return hot.isEmpty() && component.isEmpty() && generationBound.isEmpty()
          && restartRequired.isEmpty();
    }

    public Set<String> all() {
      Set<String> all = new TreeSet<>(hot);
      component.values().forEach(all::addAll);
      all.addAll(generationBound);
      all.addAll(restartRequired);
      return Collections.unmodifiableSet(all);
    }

    private static Set<String> immutableSet(Set<String> values) {
      return Collections.unmodifiableSet(new TreeSet<>(values));
    }
  }
}
