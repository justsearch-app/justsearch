/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** High-water evidence of builds that may have written data, including partially migrated boots. */
public final class DataVersionMarker {
  private static final Logger log = LoggerFactory.getLogger(DataVersionMarker.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern SEMVER = Pattern.compile(
      "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

  private DataVersionMarker() {}

  public record Marker(String appVersion, Map<String, Integer> storeVersions) {
    public Marker {
      validateVersion(appVersion);
      storeVersions = Map.copyOf(storeVersions);
      for (var entry : storeVersions.entrySet()) {
        if (entry.getKey().isBlank() || entry.getValue() < 0) {
          throw new IllegalArgumentException("Invalid store version");
        }
      }
    }
  }

  /** Called under the instance lock, before any store can migrate. A marker never blocks boot. */
  public static Optional<String> recordBoot(Path dataDir, String appVersion,
      Map<String, Integer> supported) {
    Marker current = new Marker(appVersion, supported);
    Path file = dataDir.resolve("data-version.json");
    Marker previous = null;
    boolean loggedFailure = false;
    if (!Files.notExists(file)) {
      try {
        previous = readMarker(file);
      } catch (IOException | RuntimeException failure) {
        log.warn("Cannot read data-version.json; treating it as absent and attempting to record this build", failure);
        loggedFailure = true;
      }
    }
    Optional<String> notice = newerDataNotice(previous, current);
    Map<String, Integer> raised = new TreeMap<>(supported);
    String highestApp = appVersion;
    if (previous != null) {
      previous.storeVersions().forEach((id, version) -> raised.merge(id, version, Math::max));
      if (compareVersions(previous.appVersion(), appVersion) >= 0) highestApp = previous.appVersion();
    }
    try {
      AtomicFileWrites.replaceStrict(file, MAPPER.writeValueAsBytes(new Marker(highestApp, raised)));
    } catch (IOException | RuntimeException failure) {
      // An unreadable marker (including a permissions problem) cannot prevent startup.
      if (!loggedFailure) log.warn("Cannot write data-version.json; boot continues", failure);
    }
    notice.ifPresent(message -> log.warn("{}", message));
    return notice;
  }

  private static Marker readMarker(Path file) throws IOException {
    var root = MAPPER.readTree(Files.readAllBytes(file));
    if (root == null || !root.isObject() || !root.path("appVersion").isTextual()
        || !root.path("storeVersions").isObject()) {
      throw new IOException("Invalid data-version marker fields");
    }
    Map<String, Integer> versions = new TreeMap<>();
    for (var entry : root.path("storeVersions").properties()) {
      var version = entry.getValue();
      if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 0) {
        throw new IOException("Invalid store version in data-version marker");
      }
      versions.put(entry.getKey(), version.intValue());
    }
    // New top-level metadata from future builds must not invalidate readable high-water fields.
    return new Marker(root.path("appVersion").asText(), versions);
  }

  private static Optional<String> newerDataNotice(Marker previous, Marker current) {
    if (previous == null) return Optional.empty();
    List<String> newer = new ArrayList<>();
    if (compareVersions(previous.appVersion(), current.appVersion()) > 0) {
      newer.add("app " + previous.appVersion());
    }
    new TreeMap<>(previous.storeVersions()).forEach((id, version) -> {
      if (version > current.storeVersions().getOrDefault(id, -1)) {
        newer.add(id + " v" + version);
      }
    });
    if (newer.isEmpty()) return Optional.empty();
    return Optional.of("This data directory may contain data from a newer build ("
        + String.join(", ", newer) + "). This build supports app " + current.appVersion()
        + ". Boot continues. Preserve settings.v<old>.bak.json and .corrupt-* files"
        + " (including settings.json.corrupt-* and operations.db.corrupt-*);"
        + " use a compatible newer build before restoring preserved data.");
  }

  static int compareVersions(String left, String right) {
    var a = validateVersion(left);
    var b = validateVersion(right);
    for (int i = 1; i <= 3; i++) {
      int cmp = new BigInteger(a.group(i)).compareTo(new BigInteger(b.group(i)));
      if (cmp != 0) return cmp;
    }
    String ap = a.group(4);
    String bp = b.group(4);
    if (ap == null || bp == null) return ap == bp ? 0 : ap == null ? 1 : -1;
    String[] as = ap.split("\\.");
    String[] bs = bp.split("\\.");
    for (int i = 0; i < Math.min(as.length, bs.length); i++) {
      boolean an = as[i].matches("[0-9]+");
      boolean bn = bs[i].matches("[0-9]+");
      int cmp = an && bn ? new BigInteger(as[i]).compareTo(new BigInteger(bs[i]))
          : an != bn ? (an ? -1 : 1) : as[i].compareTo(bs[i]);
      if (cmp != 0) return cmp;
    }
    return Integer.compare(as.length, bs.length);
  }

  private static java.util.regex.Matcher validateVersion(String version) {
    var matcher = SEMVER.matcher(java.util.Objects.requireNonNull(version, "appVersion"));
    if (!matcher.matches()) throw new IllegalArgumentException("Invalid app version: " + version);
    return matcher;
  }
}
