/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Native names from the same contract bytes consumed by Node and the desktop shell. */
public final class NativeTargets {
  private static final JsonNode SPEC = load();
  private final String targetId;
  private final JsonNode names;

  private NativeTargets(String targetId, JsonNode names) {
    this.targetId = targetId;
    this.names = names;
  }

  public static NativeTargets current() {
    return forPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
  }

  public static NativeTargets forPlatform(String os, String arch) {
    String platform = os.toLowerCase(Locale.ROOT);
    platform = platform.startsWith("windows") || platform.equals("win32") ? "windows" : platform;
    String architecture = arch.toLowerCase(Locale.ROOT);
    if (List.of("amd64", "x64").contains(architecture)) architecture = "x86_64";
    return forTarget(platform + "-" + architecture);
  }

  public static NativeTargets forTarget(String target) {
    JsonNode names = SPEC.path("targets").path(target);
    if (names.isMissingNode()) throw new IllegalArgumentException("Unsupported native target: " + target);
    return new NativeTargets(target, names);
  }

  public String targetId() {
    return targetId;
  }

  public static java.util.Optional<NativeTargets> currentIfSupported() {
    try { return java.util.Optional.of(current()); }
    catch (IllegalArgumentException unsupported) { return java.util.Optional.empty(); }
  }

  public boolean supportsRuntimePacks() { return names.path("capabilities").path("runtimePacks").asBoolean(false); }
  public boolean hasCpuBaselineRuntime() { return names.path("capabilities").path("cpuBaselineRuntime").asBoolean(false); }
  public String relativePath(String id) { return name("paths", id); }

  public String executableName(String id) {
    return name("executables", id);
  }

  public String libraryName(String id) {
    return name("libraries", id);
  }

  public String ortResource(String id) {
    return name("ortResources", id);
  }

  private String name(String category, String id) {
    String value = names.path(category).path(id).asText("");
    if (value.isBlank()) throw new IllegalArgumentException("Unknown native " + category + ": " + id);
    return value;
  }

  private static JsonNode load() {
    try (var input = NativeTargets.class.getResourceAsStream("/contracts/native-targets/spec.v1.json")) {
      if (input == null) throw new IllegalStateException("Native targets contract missing");
      JsonNode spec = JsonMapper.builder().build().readTree(input);
      if (spec.path("schemaVersion").asInt() != 1) throw new IllegalStateException("Unsupported native targets contract");
      return spec;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read native targets contract", e);
    }
  }
}
