/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.runtime;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.ui.api.ApiErrorHandler;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * REST controller for {@code GET /api/runtime/manifest} (tempdoc 501 §3.5).
 *
 * <p>Same JSON document the filesystem transport writes to
 * {@code <dataDir>/runtime/manifest.json}, with one explicit redaction: the
 * {@code head.sessionToken} field is stripped from the HTTP-served body. The
 * filesystem manifest is gated by filesystem permissions; the HTTP endpoint is
 * reachable by anything that can resolve the loopback port, so serving the
 * token over it would leak the credential to URL/HTML/extension consumers
 * that have no need for it. Native clients read the credential only from the owner-only
 * canonical runtime manifest on disk.
 *
 * <p>Returns 503 with a structured {@code SERVICE_UNAVAILABLE} body when the
 * publisher has not yet produced a manifest (e.g., during the brief window
 * between HTTP bind and {@link RuntimeManifestPublisher#publishHead}).
 */
public final class RuntimeManifestController {

  private static final Logger log = LoggerFactory.getLogger(RuntimeManifestController.class);

  private final RuntimeManifestPublisher publisher;
  private final ManifestSerializer serializer;

  @FunctionalInterface
  interface ManifestSerializer {
    String serialize(RuntimeManifest manifest) throws Exception;
  }

  public RuntimeManifestController(RuntimeManifestPublisher publisher) {
    this(
        publisher,
        manifest ->
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
  }

  RuntimeManifestController(RuntimeManifestPublisher publisher, ManifestSerializer serializer) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  public void handleGet(Context ctx) {
    RuntimeManifest manifest = publisher.current();
    if (manifest == null) {
      ctx.status(503)
          .json(
              Map.of(
                  "error", "Runtime manifest not yet published",
                  "errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name()));
      return;
    }
    try {
      RuntimeManifest publicView = manifest.publicProjection();
      ctx.contentType("application/json").result(serializer.serialize(publicView));
    } catch (Exception e) {
      log.warn("/api/runtime/manifest serialization failed: {}", e.getMessage(), e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INTERNAL_ERROR, "Runtime manifest serialization failed"));
    }
  }
}
