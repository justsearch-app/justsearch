/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.runtime;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Time-axis reader endpoints (tempdoc 501 §13.4.4).
 *
 * <p>The publisher writes per-instance history under {@code
 * <dataDir>/runtime/instances/<instanceId>/}. This controller exposes that
 * substrate for postmortem consumers:
 *
 * <ul>
 *   <li>{@code GET /api/runtime/instances} — list known instance IDs in
 *       reverse chronological order with terminal-snapshot metadata.
 *   <li>{@code GET /api/runtime/instances/{id}} — terminal snapshot for an
 *       instance, plus the full ndjson publish log as a JSON array.
 * </ul>
 *
 * <p>All responses carry the same public projection the canonical
 * {@code /api/runtime/manifest} endpoint serves — credentials never leak to
 * HTTP-class transports per §13.4.5.
 */
public final class RuntimeInstancesController {

  private static final Logger log = LoggerFactory.getLogger(RuntimeInstancesController.class);
  private static final Pattern INSTANCE_ID_PATTERN =
      Pattern.compile("[A-Za-z0-9-]{1,64}");

  /**
   * Tempdoc 501 Phase 36 (F4): pagination defaults. Per-request response
   * is bounded so a long-running instance with a multi-megabyte ndjson
   * log doesn't load the entire file into memory on every postmortem
   * read. Callers paginate with {@code ?fromLine=<n>&limit=<n>}; the
   * response carries {@code totalLines} so the caller can iterate.
   */
  private static final int DEFAULT_LOG_LIMIT = 200;

  private static final int MAX_LOG_LIMIT = 2000;

  private final RuntimeManifestPublisher publisher;
  private final ObjectMapper mapper;

  public RuntimeInstancesController(RuntimeManifestPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.mapper = new ObjectMapper();
  }

  public void handleList(Context ctx) {
    Path root = publisher.instancesRoot();
    List<Map<String, Object>> entries = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (Stream<Path> dirs = Files.list(root)) {
        List<Path> sorted = new ArrayList<>();
        dirs.filter(Files::isDirectory).forEach(sorted::add);
        sorted.sort(
            Comparator.<Path, java.nio.file.attribute.FileTime>comparing(
                    p -> {
                      try {
                        return Files.getLastModifiedTime(p);
                      } catch (IOException e) {
                        return java.nio.file.attribute.FileTime.fromMillis(0L);
                      }
                    })
                .reversed());
        for (Path dir : sorted) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("instanceId", dir.getFileName().toString());
          try {
            row.put(
                "lastModified",
                Files.getLastModifiedTime(dir).toInstant().toString());
          } catch (IOException ignore) {
            row.put("lastModified", null);
          }
          Path snapshot = dir.resolve("manifest.json");
          row.put("hasSnapshot", Files.isRegularFile(snapshot));
          Path logFile = dir.resolve("manifest.log.ndjson");
          row.put("hasLog", Files.isRegularFile(logFile));
          if (Files.isRegularFile(logFile)) {
            try {
              row.put("logLines", countLines(logFile));
            } catch (IOException e) {
              row.put("logLines", null);
            }
          }
          entries.add(row);
        }
      } catch (IOException e) {
        log.warn("instances/ listing failed: {}", e.getMessage(), e);
      }
    }
    Map<String, Object> body = Map.of("instances", entries, "count", entries.size());
    ctx.contentType("application/json").json(body);
  }

  public void handleGetOne(Context ctx) {
    String id = ctx.pathParam("id");
    try {
      io.justsearch.configuration.persistence.StoreIds.requireValid(id);
      if (!INSTANCE_ID_PATTERN.matcher(id).matches()) {
        throw new IllegalArgumentException("Invalid instance id");
      }
    } catch (IllegalArgumentException invalid) {
      ctx.status(400)
          .json(
              Map.of(
                  "error", "invalid instance id",
                  "errorCode", ApiErrorCode.INVALID_REQUEST.name()));
      return;
    }
    Path dir = publisher.instancesRoot().resolve(id);
    if (!Files.isDirectory(dir)) {
      ctx.status(404)
          .json(
              Map.of(
                  "error", "instance not found: " + id,
                  "errorCode", ApiErrorCode.NOT_FOUND.name()));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("instanceId", id);
    Path snapshot = dir.resolve("manifest.json");
    if (Files.isRegularFile(snapshot)) {
      try {
        RuntimeManifest m =
            mapper.readValue(Files.readString(snapshot, StandardCharsets.UTF_8),
                RuntimeManifest.class);
        body.put("snapshot", m.publicProjection());
      } catch (Exception e) {
        body.put("snapshot", null);
        body.put("snapshotError", e.getMessage());
      }
    } else {
      body.put("snapshot", null);
    }
    // Tempdoc 501 Phase 36 (F4): paginated streaming read of the publish
    // log. Loading the whole ndjson into memory was unbounded I/O per
    // request for long-running instances. Now: stream line-by-line,
    // honor fromLine + limit, expose totalLines so callers can iterate.
    int fromLine = parseNonNegativeInt(ctx.queryParam("fromLine"), 0);
    int limit =
        clampPositive(parseNonNegativeInt(ctx.queryParam("limit"), DEFAULT_LOG_LIMIT),
            1, MAX_LOG_LIMIT);

    Path logFile = dir.resolve("manifest.log.ndjson");
    List<RuntimeManifest> publishes = new ArrayList<>();
    long totalLines = 0;
    boolean truncated = false;
    if (Files.isRegularFile(logFile)) {
      try (Stream<String> stream = Files.lines(logFile, StandardCharsets.UTF_8)) {
        for (String line : (Iterable<String>) stream::iterator) {
          if (line.isBlank()) continue;
          if (totalLines >= fromLine && publishes.size() < limit) {
            try {
              publishes.add(
                  mapper.readValue(line, RuntimeManifest.class).publicProjection());
            } catch (Exception e) {
              // skip malformed lines; best-effort postmortem reader.
              log.debug("instance {} log: skipping malformed line: {}", id, e.getMessage());
            }
          }
          totalLines++;
          if (publishes.size() == limit && totalLines > fromLine + limit - 1) {
            // we have a full window AND we've passed it; keep counting
            // totalLines to give the caller the total but stop appending
            truncated = true;
          }
        }
      } catch (IOException e) {
        body.put("logError", e.getMessage());
      }
    }
    body.put("log", publishes);
    body.put("logFromLine", fromLine);
    body.put("logLimit", limit);
    body.put("logTotalLines", totalLines);
    if (truncated) {
      body.put("logTruncated", true);
    }
    // Tempdoc 501 Phase 25: per-instance start log. Carries the
    // publisher's transition events as plain timestamped lines so a
    // postmortem reader has both the manifest series (typed) and the
    // human-readable narrative.
    Path startLog = dir.resolve("start.log");
    if (Files.isRegularFile(startLog)) {
      try {
        body.put(
            "startLog",
            Files.readAllLines(startLog, StandardCharsets.UTF_8));
      } catch (IOException e) {
        body.put("startLogError", e.getMessage());
      }
    }
    ctx.contentType("application/json").json(body);
  }

  private static long countLines(Path file) throws IOException {
    try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
      return stream.filter(s -> !s.isBlank()).count();
    }
  }

  /**
   * Parse a non-negative integer query parameter; return {@code fallback}
   * on null, blank, malformed, or negative input. Defensive shape — bad
   * pagination input must degrade to defaults, not 500.
   */
  private static int parseNonNegativeInt(String raw, int fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      int v = Integer.parseInt(raw.trim());
      return v < 0 ? fallback : v;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int clampPositive(int v, int min, int max) {
    if (v < min) return min;
    return Math.min(v, max);
  }
}
