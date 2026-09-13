/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice 3a.1.9 §A.6a — JSON Schema serving for the catalog-driven Resource consumer.
 *
 * <p>The {@code Resource.schema} field declares a public-URL-shaped string
 * ({@code "https://ssot.justsearch/v1/schemas/<name>.v1.json"}) that is documentation,
 * not a real host. The FE schema fetcher rewrites this to {@code /api/schemas/<name>.v1.json}
 * — same-origin, served by this controller from the classpath copy of {@code SSOT/schemas/*}.
 *
 * <p>Mirrors {@link MessageCatalogController}'s eager-load + strong-ETag pattern:
 *
 * <ul>
 *   <li>At construction, scan a hardcoded list of known schemas into an in-memory
 *       {@code name → (bytes, etag)} map. Boot-time errors surface immediately if a
 *       schema is missing from the classpath (mis-configured Gradle Sync task).
 *   <li>Per-request: 404 with structured error envelope when the name is unknown;
 *       304 Not Modified on conditional GET match; otherwise 200 with body + strong ETag.
 * </ul>
 *
 * <p>Schema name validation: the {@code {filename}} path-param must match
 * {@code [a-z0-9-]+\.v1\.json}. Mismatching values get 404 with a "schema-not-found"
 * envelope, NOT a 400 — the controller treats unknown names as a normal not-found case
 * (mirrors the FE's expectation of a single status code regardless of path malformation).
 */
public final class SchemaController {

  private static final Logger log = LoggerFactory.getLogger(SchemaController.class);
  private static final String CACHE_CONTROL = "public, max-age=3600";
  private static final Pattern SCHEMA_NAME_RE = Pattern.compile("^[a-z0-9-]+\\.v1\\.json$");

  /**
   * Hardcoded list of schema names served by this controller. Each name is the literal
   * filename under {@code SSOT/schemas/}. Adding a new schema requires updating this
   * list AND the {@code modules/ui/build.gradle.kts} {@code syncSsotSchemas} task's
   * {@code include(...)}.
   *
   * <p>Boot-time check: every name in this list MUST resolve on the classpath. A
   * missing resource fails fast at controller construction so the operator sees the
   * mis-configuration before serving the first request.
   */
  private static final List<String> SCHEMA_NAMES =
      List.of(
          "health-event.v1.json",
          "indexing-job-view.v1.json",
          "operation-history-entry.v1.json",
          "operation-outcome-view.v1.json",
          "operation.v1.json",
          "prompt.v1.json",
          "resource.v1.json",
          "runtime-context.v1.json",
          // Slice 3a.1.9 §B.B.B D1: closes the dangling-schema-URL
          // defect surfaced by the second-agent review. Four
          // TIMESERIES metric catalogs reference this URL; the file
          // is now authored under SSOT/schemas/ + synced to classpath.
          "timeseries-snapshot.v1.json",
          // Tempdoc 583 §D.3a (+ §C independent review): the OpenAPI export's per-route response
          // $refs point at /api/schemas/<name>, so every schema named in RouteContractPolicy MUST be
          // served here or the $ref dangles (404). These three wire records are referenced by the
          // route manifest's responseSchema; classpath-synced via the `*.v1.json` Sync. The
          // RouteContractPolicyCoverageTest enforces RouteContractPolicy ⊆ servedNames().
          "knowledge-search-response.v1.json",
          "ai-runtime-status-response.v1.json",
          "effective-policy.v1.json",
          // Tempdoc 911 (885 UL.9): the substrate failed-jobs response, referenced by
          // RouteContractPolicy for both /api/indexing-jobs/failed routes.
          "failed-indexing-jobs-response.v1.json",
          // Tempdoc 899 D6: canonical schemas for the six-operation runtime client projection.
          "runtime-manifest-public.v2.json",
          "runtime-ready-response.v1.json",
          "runtime-live-response.v1.json",
          "lifecycle-snapshot.v1.json",
          "api-error-response.v1.json");

  private final Telemetry telemetry;
  private final Map<String, byte[]> bodyByName;
  private final Map<String, String> etagByName;

  public SchemaController(Telemetry telemetry) {
    this.telemetry = telemetry;
    this.bodyByName = new HashMap<>();
    this.etagByName = new HashMap<>();
    for (String name : SCHEMA_NAMES) {
      byte[] body = loadFromClasspath("/SSOT/schemas/" + name);
      bodyByName.put(name, body);
      etagByName.put(name, computeEtag(body));
    }
    log.info("SchemaController loaded {} schemas from classpath", bodyByName.size());
  }

  public void handle(Context ctx) {
    String name = ctx.pathParam("name");
    if (!SCHEMA_NAME_RE.matcher(name).matches() || !bodyByName.containsKey(name)) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Schema not found: " + name,
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String etag = etagByName.get(name);
    String ifNoneMatch = ctx.header("If-None-Match");
    if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
      ctx.header("ETag", etag);
      ctx.header("Cache-Control", CACHE_CONTROL);
      ctx.status(304);
      return;
    }
    ctx.header("ETag", etag);
    ctx.header("Cache-Control", CACHE_CONTROL);
    ctx.contentType("application/schema+json").result(bodyByName.get(name));
  }

  private static byte[] loadFromClasspath(String path) {
    try (InputStream is = SchemaController.class.getResourceAsStream(path)) {
      Objects.requireNonNull(is, "Schema not found on classpath: " + path);
      return is.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load schema " + path, e);
    }
  }

  private static String computeEtag(byte[] body) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(body);
      StringBuilder sb = new StringBuilder(digest.length * 2 + 2);
      sb.append('"');
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16));
        sb.append(Character.forDigit(b & 0xf, 16));
      }
      sb.append('"');
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Read-only view of the schemas this controller serves. Tests use this to assert coverage. */
  public java.util.Set<String> servedNames() {
    return java.util.Collections.unmodifiableSet(bodyByName.keySet());
  }
}
