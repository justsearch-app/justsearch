/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.configuration.SystemAccess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pure allowlist composer for the user-copyable diagnostic summary. */
final class DiagnosticSummaryComposer {

  static final int MAX_UTF8_BYTES = 8 * 1024;
  static final int MAX_VALUE_UTF8_BYTES = 256;
  static final String LOCAL_ONLY_NOTE =
      "Generated locally and copied only by the user's action.";

  private static final long MAX_CRASH_REPORT_BYTES = 64L * 1024L;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> CRASH_PROCESSES = Set.of("head", "worker");
  private static final Set<String> GPU_VENDORS = Set.of("NVIDIA", "AMD", "INTEL", "APPLE");
  private static final Set<String> GPU_CAPABILITY_TIERS =
      Set.of("CUDA_FUNCTIONAL", "CUDA_AVAILABLE", "GPU_AVAILABLE");
  private static final Pattern VERSION_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
  private static final Pattern GPU_MODEL = Pattern.compile("[\\p{L}\\p{N} ._+()\\-]+");
  private static final Pattern EXCEPTION_TYPE =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  record PlatformMetadata(String family, String version, String architecture, String jvmVersion) {}

  record LifecycleMetadata(
      LifecycleSnapshotV2.Lifecycle lifecycle, LifecycleSnapshotV2.Components components) {}

  record GpuMetadata(String vendor, String model, String capabilityTier) {}

  record CrashMetadata(Instant timestamp, String process, String exceptionType) {}

  record Inputs(
      String appVersion,
      RuntimeContract runtimeContract,
      PlatformMetadata platform,
      LifecycleMetadata lifecycle,
      GpuMetadata gpu,
      CrashMetadata latestCrash) {}

  String compose(Inputs inputs) {
    Inputs source = inputs == null ? new Inputs(null, null, null, null, null, null) : inputs;
    StringBuilder body = new StringBuilder("JustSearch diagnostic summary\n");

    appendToken(body, "app.version", source.appVersion(), VERSION_TOKEN);
    appendRuntimeContract(body, source.runtimeContract());
    appendPlatform(body, source.platform());
    appendLifecycle(body, source.lifecycle());
    appendGpu(body, source.gpu());
    appendCrash(body, source.latestCrash());

    String note = "note: " + LOCAL_ONLY_NOTE + "\n";
    int bodyBudget = MAX_UTF8_BYTES - utf8Length(note);
    return capUtf8(body.toString(), bodyBudget) + note;
  }

  static PlatformMetadata currentPlatform() {
    String osName = SystemAccess.sysProp("os.name");
    String family = platformFamily(osName);
    return new PlatformMetadata(
        family,
        SystemAccess.sysProp("os.version"),
        SystemAccess.sysProp("os.arch"),
        SystemAccess.sysProp("java.version"));
  }

  static LifecycleMetadata lifecycleFrom(Object snapshot) {
    if (snapshot instanceof StatusResponse status) {
      return new LifecycleMetadata(status.lifecycle(), status.components());
    }
    if (snapshot instanceof LifecycleSnapshotV2 lifecycle) {
      return new LifecycleMetadata(lifecycle.lifecycle(), lifecycle.components());
    }
    return null;
  }

  static GpuMetadata safeGpuFrom(Object snapshot) {
    if (!(snapshot instanceof StatusResponse status)) {
      return null;
    }
    return safeGpuFrom(status.gpu());
  }

  static GpuMetadata safeGpuFrom(GpuStatusView gpu) {
    if (gpu == null || !gpu.available()) {
      return null;
    }
    String tier =
        Boolean.TRUE.equals(gpu.cudaFunctional())
            ? "CUDA_FUNCTIONAL"
            : "GPU_AVAILABLE";
    // The current typed GPU authority exposes no device model. Unknown fields are omitted rather
    // than recovered from a command, driver error, path, or another free-form diagnostic source.
    return new GpuMetadata("NVIDIA", null, tier);
  }

  static CrashMetadata latestCrash(Path crashDir) {
    if (crashDir == null || !Files.isDirectory(crashDir)) {
      return null;
    }
    CrashMetadata latest = null;
    try (DirectoryStream<Path> reports = Files.newDirectoryStream(crashDir, "crash-*.json")) {
      for (Path report : reports) {
        CrashMetadata candidate = readCrash(report);
        if (candidate != null && isLater(candidate, latest)) {
          latest = candidate;
        }
      }
    } catch (IOException ignored) {
      // Optional local metadata: absence, unreadability, and malformed input are all omission.
    }
    return latest;
  }

  private static CrashMetadata readCrash(Path report) {
    try {
      if (!Files.isRegularFile(report)) {
        return null;
      }
      try (InputStream input = Files.newInputStream(report)) {
        return readCrash(input);
      }
    } catch (IOException | RuntimeException ignored) {
      return null;
    }
  }

  /** Parse one already-open report without ever reading beyond the crash-report byte ceiling. */
  static CrashMetadata readCrash(InputStream input) {
    try {
      byte[] json = input.readNBytes(Math.toIntExact(MAX_CRASH_REPORT_BYTES + 1));
      if (json.length > MAX_CRASH_REPORT_BYTES) {
        return null;
      }
      JsonNode root = MAPPER.readTree(json);
      if (root == null || !root.isObject() || !"crash-report.v1".equals(text(root, "schema"))) {
        return null;
      }
      Instant timestamp = Instant.parse(text(root, "timestamp"));
      String process = text(root, "process");
      JsonNode exception = root.get("exception");
      String exceptionType = exception == null ? null : text(exception, "type");
      if (!CRASH_PROCESSES.contains(process)
          || exceptionType == null
          || exceptionType.length() > MAX_VALUE_UTF8_BYTES
          || !EXCEPTION_TYPE.matcher(exceptionType).matches()) {
        return null;
      }
      return new CrashMetadata(timestamp, process, exceptionType);
    } catch (IOException | RuntimeException ignored) {
      return null;
    }
  }

  private static boolean isLater(CrashMetadata candidate, CrashMetadata current) {
    if (current == null) {
      return true;
    }
    int timestampOrder = candidate.timestamp().compareTo(current.timestamp());
    if (timestampOrder != 0) {
      return timestampOrder > 0;
    }
    int processOrder = candidate.process().compareTo(current.process());
    return processOrder > 0
        || (processOrder == 0
            && candidate.exceptionType().compareTo(current.exceptionType()) > 0);
  }

  private static String text(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    return value != null && value.isTextual() ? value.asString() : null;
  }

  private static String platformFamily(String osName) {
    if (osName == null) {
      return null;
    }
    String normalized = osName.toLowerCase(Locale.ROOT);
    if (normalized.contains("win")) {
      return "WINDOWS";
    }
    if (normalized.contains("mac") || normalized.contains("darwin")) {
      return "MACOS";
    }
    if (normalized.contains("linux")) {
      return "LINUX";
    }
    return null;
  }

  private static void appendRuntimeContract(StringBuilder out, RuntimeContract contract) {
    if (contract == null) {
      return;
    }
    appendToken(out, "runtime-contract.version", contract.version(), VERSION_TOKEN);
    RuntimeContract.Constituents constituents = contract.constituents();
    if (constituents == null) {
      return;
    }
    append(out, "runtime-contract.manifest-schema", constituents.manifestSchemaVersion());
    append(out, "runtime-contract.lifecycle-schema", constituents.lifecycleSchemaVersion());
    appendToken(
        out, "runtime-contract.mcp-protocol", constituents.mcpProtocolVersion(), VERSION_TOKEN);
    appendToken(
        out,
        "runtime-contract.mcp-tool-surface",
        constituents.mcpToolSurfaceVersion(),
        VERSION_TOKEN);
  }

  private static void appendPlatform(StringBuilder out, PlatformMetadata platform) {
    if (platform == null) {
      return;
    }
    if (platform.family() != null
        && Set.of("WINDOWS", "MACOS", "LINUX").contains(platform.family())) {
      append(out, "platform.os-family", platform.family());
    }
    appendToken(out, "platform.os-version", platform.version(), VERSION_TOKEN);
    appendToken(out, "platform.architecture", platform.architecture(), VERSION_TOKEN);
    appendToken(out, "platform.jvm-version", platform.jvmVersion(), VERSION_TOKEN);
  }

  private static void appendLifecycle(StringBuilder out, LifecycleMetadata metadata) {
    if (metadata == null) {
      return;
    }
    LifecycleSnapshotV2.Lifecycle lifecycle = metadata.lifecycle();
    if (lifecycle != null) {
      append(out, "lifecycle.overall.state", lifecycle.state().name());
      appendReason(out, "lifecycle.overall.reason", lifecycle.reason_code());
    }
    LifecycleSnapshotV2.Components components = metadata.components();
    if (components == null) {
      return;
    }
    appendComponent(out, "api", components.api());
    appendComponent(out, "index", components.index());
    appendComponent(out, "encoders", components.encoders());
    appendComponent(out, "generative", components.generative());
  }

  private static void appendComponent(
      StringBuilder out, String name, LifecycleSnapshotV2.Component component) {
    if (component == null) {
      return;
    }
    append(out, "lifecycle." + name + ".state", component.state().name());
    appendReason(out, "lifecycle." + name + ".reason", component.reason_code());
  }

  private static void appendReason(StringBuilder out, String key, String reasonCode) {
    if (LifecycleReasonCode.isKnown(reasonCode)) {
      append(out, key, reasonCode);
    }
  }

  private static void appendGpu(StringBuilder out, GpuMetadata gpu) {
    if (gpu == null) {
      return;
    }
    if (gpu.vendor() != null && GPU_VENDORS.contains(gpu.vendor())) {
      append(out, "gpu.vendor", gpu.vendor());
    }
    appendToken(out, "gpu.model", gpu.model(), GPU_MODEL);
    if (gpu.capabilityTier() != null
        && GPU_CAPABILITY_TIERS.contains(gpu.capabilityTier())) {
      append(out, "gpu.capability-tier", gpu.capabilityTier());
    }
  }

  private static void appendCrash(StringBuilder out, CrashMetadata crash) {
    if (crash == null || crash.timestamp() == null) {
      return;
    }
    if (crash.process() == null
        || !CRASH_PROCESSES.contains(crash.process())
        || crash.exceptionType() == null
        || !EXCEPTION_TYPE.matcher(crash.exceptionType()).matches()) {
      return;
    }
    append(out, "latest-crash.timestamp", crash.timestamp().toString());
    append(out, "latest-crash.process", crash.process());
    append(out, "latest-crash.exception-type", crash.exceptionType());
  }

  private static void append(StringBuilder out, String key, int value) {
    append(out, key, Integer.toString(value));
  }

  private static void appendToken(
      StringBuilder out, String key, String rawValue, Pattern allowedPattern) {
    String value = sanitize(rawValue);
    if (value != null && allowedPattern.matcher(value).matches()) {
      out.append(key)
          .append(": ")
          .append(capUtf8(value, MAX_VALUE_UTF8_BYTES))
          .append('\n');
    }
  }

  private static void append(StringBuilder out, String key, String rawValue) {
    String value = sanitize(rawValue);
    if (value == null) {
      return;
    }
    out.append(key)
        .append(": ")
        .append(capUtf8(value, MAX_VALUE_UTF8_BYTES))
        .append('\n');
  }

  private static String sanitize(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    StringBuilder sanitized = new StringBuilder(rawValue.length());
    rawValue.codePoints()
        .forEach(
            codePoint -> {
              int type = Character.getType(codePoint);
              if (Character.isISOControl(codePoint)
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR
                  || type == Character.SURROGATE) {
                sanitized.append(' ');
              } else {
                sanitized.appendCodePoint(codePoint);
              }
            });
    String value = sanitized.toString().trim();
    if (value.isBlank()
        || "unknown".equalsIgnoreCase(value)
        || "unspecified".equalsIgnoreCase(value)
        || "unrecognized".equalsIgnoreCase(value)
        || "n/a".equalsIgnoreCase(value)) {
      return null;
    }
    return value;
  }

  private static String capUtf8(String value, int maxBytes) {
    if (value == null || maxBytes <= 0) {
      return "";
    }
    StringBuilder capped = new StringBuilder(Math.min(value.length(), maxBytes));
    int bytes = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      int encodedBytes = utf8Bytes(codePoint);
      if (bytes + encodedBytes > maxBytes) {
        break;
      }
      capped.appendCodePoint(codePoint);
      bytes += encodedBytes;
      offset += Character.charCount(codePoint);
    }
    return capped.toString();
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static int utf8Bytes(int codePoint) {
    if (codePoint <= 0x7f) {
      return 1;
    }
    if (codePoint <= 0x7ff) {
      return 2;
    }
    if (codePoint <= 0xffff) {
      return 3;
    }
    return 4;
  }
}
