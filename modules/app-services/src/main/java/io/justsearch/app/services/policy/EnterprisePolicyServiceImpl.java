/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.policy;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicy;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.PlatformPaths;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and merges enterprise policy sources into an effective policy snapshot.
 *
 * <p>Policy sources:
 *
 * <ul>
 *   <li>Machine policy (admin-managed): {@code %PROGRAMDATA%\\JustSearch\\policy.v1.json}</li>
 *   <li>User policy (restrictive-only): {@code <AI_HOME>/policy.v1.json}</li>
 * </ul>
 *
 * <p>Important security rule: user policy is restrictive-only; it may further disable features and
 * further restrict allowlists, but must not broaden them.
 *
 * <p>§31 Phase 1.A: moved from {@code modules/ui/.../policy/EnterprisePolicyService.java} to
 * {@code app-services} as part of dissolving LateBoundServices. The impl had no ui-internal
 * dependencies; the move enables ServicePhase to construct the 5 services that depend on this
 * interface (PolicyService, BrainRuntimeService, BrainInstallService, RuntimeVariantService,
 * EffectiveConfig*-using-services).
 */
public final class EnterprisePolicyServiceImpl
    implements io.justsearch.app.api.EnterprisePolicyService {
  private static final Logger log = LoggerFactory.getLogger(EnterprisePolicyServiceImpl.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static final String APP_PACK_ALLOWLIST_RESOURCE = "ai/pack-allowlist.v1.json";
  private static final ObjectMapper ALLOWLIST_MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
          .build();

  @Override
  public EffectivePolicy snapshot() {
    return snapshot(resolveMachinePolicyPath(), resolveAiHome().resolve("policy.v1.json"));
  }

  EffectivePolicy snapshot(Path machinePath, Path userPath) {

    PolicyLoad machine = loadPolicy(machinePath);
    PolicyLoad user = loadPolicy(userPath);

    // Defaults: permissive (consumer-friendly). Enterprise policy restricts.
    boolean appUpdatesEnabled = true;
    boolean downloadsEnabled = true;
    boolean onlineAiEnabled = true;
    boolean gpuAccelerationEnabled = true;
    boolean disallowExternalInferenceServers = false;

    // If a machine policy file exists but cannot be loaded, fail closed for update and AI toggles.
    // This is a security measure: a corrupt/tampered policy file should not silently allow everything.
    if (machine.present && !machine.loaded) {
      log.error(
          "Machine policy file exists but cannot be loaded: {}. "
              + "Error: {}. "
              + "App updates and AI features are DISABLED until this is resolved. "
              + "To fix: (1) correct the JSON syntax in the policy file, or (2) remove the file to use defaults. "
              + "Check /api/policy/effective for details.",
          machinePath,
          machine.error);
      appUpdatesEnabled = false;
      downloadsEnabled = false;
      onlineAiEnabled = false;
      gpuAccelerationEnabled = false;
      disallowExternalInferenceServers = true;
    }

    // Apply machine booleans (if loaded).
    if (machine.policy != null) {
      appUpdatesEnabled = opt(machine.policy.appUpdatesEnabled, appUpdatesEnabled);
      downloadsEnabled = opt(machine.policy.downloadsEnabled, downloadsEnabled);
      onlineAiEnabled = opt(machine.policy.onlineAiEnabled, onlineAiEnabled);
      gpuAccelerationEnabled = opt(machine.policy.gpuAccelerationEnabled, gpuAccelerationEnabled);
      disallowExternalInferenceServers =
          opt(machine.policy.disallowExternalInferenceServers, disallowExternalInferenceServers);
    }

    // Apply user policy as restrictive-only for booleans:
    // - enabled flags can only be reduced (AND)
    // - disallow flags can only be increased (OR)
    if (user.policy != null) {
      appUpdatesEnabled = appUpdatesEnabled && opt(user.policy.appUpdatesEnabled, true);
      downloadsEnabled = downloadsEnabled && opt(user.policy.downloadsEnabled, true);
      onlineAiEnabled = onlineAiEnabled && opt(user.policy.onlineAiEnabled, true);
      gpuAccelerationEnabled = gpuAccelerationEnabled && opt(user.policy.gpuAccelerationEnabled, true);
      disallowExternalInferenceServers =
          disallowExternalInferenceServers || opt(user.policy.disallowExternalInferenceServers, false);
    }

    ConfigStore cs = ConfigStore.globalOrNull();
    boolean aiDisabledOverride = cs != null && cs.get().ai().disabled();
    if (aiDisabledOverride) {
      downloadsEnabled = false;
      onlineAiEnabled = false;
      gpuAccelerationEnabled = false;
      disallowExternalInferenceServers = true;
    }

    // Allowlists (normalized to lowercase hex).
    // Effective allowlist lists are best-effort outputs for diagnostics; enforcement is elsewhere.
    Set<String> modelAllow = new HashSet<>();

    if (machine.policy != null && machine.policy.allowlists != null) {
      addAllLower(modelAllow, machine.policy.allowlists.modelSha256);
    }

    // Pack allowlist UX signals:
    // - machine policy present => authoritative (deny-all when empty)
    // - else, user allowlist primary when non-empty (power-user)
    // - else, app allowlist
    List<String> appPackAllowlist = loadAppPackAllowlistBestEffort();
    List<String> machinePackAllowlist =
        machine.policy != null && machine.policy.allowlists != null
            ? normalizeSha256List(machine.policy.allowlists.packManifestSha256)
            : List.of();
    List<String> userPackAllowlist =
        user.policy != null && user.policy.allowlists != null
            ? normalizeSha256List(user.policy.allowlists.packManifestSha256)
            : List.of();

    String packAllowlistSource;
    boolean packAllowlistConfigured;
    List<String> effectivePackAllowlist;
    if (machine.present) {
      packAllowlistSource = "machine";
      effectivePackAllowlist = machinePackAllowlist;
      packAllowlistConfigured = !effectivePackAllowlist.isEmpty();
    } else if (!userPackAllowlist.isEmpty()) {
      packAllowlistSource = "user";
      effectivePackAllowlist = userPackAllowlist;
      packAllowlistConfigured = true;
    } else if (!appPackAllowlist.isEmpty()) {
      packAllowlistSource = "app";
      effectivePackAllowlist = appPackAllowlist;
      packAllowlistConfigured = true;
    } else {
      packAllowlistSource = "none";
      effectivePackAllowlist = List.of();
      packAllowlistConfigured = false;
    }

    EffectivePolicy effective =
        new EffectivePolicy(
            appUpdatesEnabled,
            downloadsEnabled,
            onlineAiEnabled,
            gpuAccelerationEnabled,
            disallowExternalInferenceServers,
            new ArrayList<>(modelAllow),
            effectivePackAllowlist,
            packAllowlistSource,
            packAllowlistConfigured,
            new EffectivePolicy.PolicySource(
                machinePath,
                machine.present,
                machine.loaded,
                machine.error,
                machine.policy),
            new EffectivePolicy.PolicySource(
                userPath,
                user.present,
                user.loaded,
                user.error,
                user.policy),
            aiDisabledOverride);

    // v3 policy bridge: propagate key enforcement flags via sysprops (read at runtime).
    // IMPORTANT: Policy can change while the app is running (user policy file edits). If we only set
    // these at startup, enforcement points like "disallow external llama-server adoption" can lag.
    try {
      System.setProperty(
          "policy.gpu_acceleration_enabled",
          String.valueOf(effective.gpuAccelerationEnabled()));
      System.setProperty(
          "justsearch.policy.disallowExternalInferenceServers",
          String.valueOf(effective.disallowExternalInferenceServers()));
    } catch (Exception e) {
      log.debug("Failed to update policy sysprops (best-effort): {}", e.getMessage());
    }

    return effective;
  }

  private static void addAllLower(Set<String> out, List<String> values) {
    if (values == null) return;
    for (String v : values) {
      if (v == null) continue;
      String s = v.trim();
      if (s.isBlank()) continue;
      out.add(s.toLowerCase(Locale.ROOT));
    }
  }

  private static boolean opt(Boolean value, boolean fallback) {
    return value == null ? fallback : value.booleanValue();
  }

  private static List<String> normalizeSha256List(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    Set<String> out = new HashSet<>();
    for (String v : values) {
      String n = normalizeSha256(v);
      if (n != null) out.add(n);
    }
    if (out.isEmpty()) return List.of();
    return new ArrayList<>(out);
  }

  private static String normalizeSha256(String value) {
    if (value == null) return null;
    String s = value.trim().toLowerCase(Locale.ROOT);
    if (s.length() != 64) return null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!ok) return null;
    }
    return s;
  }

  private static List<String> loadAppPackAllowlistBestEffort() {
    try (InputStream in =
        EnterprisePolicyServiceImpl.class
            .getClassLoader()
            .getResourceAsStream(APP_PACK_ALLOWLIST_RESOURCE)) {
      if (in == null) return List.of();
      byte[] bytes = in.readAllBytes();
      AppPackAllowlist parsed = ALLOWLIST_MAPPER.readValue(bytes, AppPackAllowlist.class);
      if (parsed == null || parsed.schemaVersion != 1) return List.of();
      return normalizeSha256List(parsed.allowlistedPackManifestSha256);
    } catch (Exception e) {
      log.warn("Failed to load app pack allowlist; UI may report pack import as disabled", e);
      return List.of();
    }
  }

  private static final class AppPackAllowlist {
    public int schemaVersion = 0;
    public List<String> allowlistedPackManifestSha256;
  }

  private static final class PolicyLoad {
    final boolean present;
    final boolean loaded;
    final String error;
    final EnterprisePolicy policy;

    PolicyLoad(boolean present, boolean loaded, String error, EnterprisePolicy policy) {
      this.present = present;
      this.loaded = loaded;
      this.error = error;
      this.policy = policy;
    }
  }

  private static PolicyLoad loadPolicy(Path path) {
    if (path == null) {
      return new PolicyLoad(false, false, null, null);
    }
    try {
      // Only a missing directory entry is absent. Dangling links, nonregular paths and
      // failures to inspect an existing policy must reach the broken-policy branch.
      try {
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException absent) {
        // The Unix provider also reports ENOTDIR as NoSuchFileException. A broken
        // ancestor is not an absent policy (nor is a dangling ancestor symlink).
        for (Path parent = path.toAbsolutePath().getParent(); parent != null; parent = parent.getParent()) {
          try {
            Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          } catch (NoSuchFileException missingParent) {
            continue;
          }
          if (!Files.readAttributes(parent, BasicFileAttributes.class).isDirectory()) {
            return new PolicyLoad(true, false, "Policy ancestor is not a directory", null);
          }
          break;
        }
        return new PolicyLoad(false, false, null, null);
      }
      if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
        return new PolicyLoad(true, false, "Policy path is not a regular file", null);
      }
      EnterprisePolicy p = MAPPER.readValue(path.toFile(), EnterprisePolicy.class);
      if (p == null || p.schemaVersion != 1) {
        return new PolicyLoad(true, false, "Unsupported policy schemaVersion", null);
      }
      return new PolicyLoad(true, true, null, p);
    } catch (Exception e) {
      log.warn("Failed to load policy at {}", path, e);
      return new PolicyLoad(true, false, e.getMessage(), null);
    }
  }

  private static Path resolveAiHome() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception ignored) {
      // best-effort
    }
    try {
      return PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return Path.of(System.getProperty("user.dir"));
    }
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win");
  }

  private static Path resolveMachinePolicyPath() {
    if (!isWindows()) return null;
    String programData = System.getenv("PROGRAMDATA");
    if (programData == null || programData.isBlank()) {
      programData = "C:\\ProgramData";
    }
    return Path.of(programData).resolve("JustSearch").resolve("policy.v1.json");
  }
}
