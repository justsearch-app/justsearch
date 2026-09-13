/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.selection.DocumentAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/document/{id}/resolve-address} — translate a typed {@link DocumentAddress}
 * into canonical character offsets per tempdoc 526 §4.2.
 *
 * <p>This is the named operation the substrate exposes for view formats whose
 * display→canonical mapping the FE can't compute locally. v1 supports:
 *
 * <ul>
 *   <li>{@code canonical}: pass-through (already canonical).
 *   <li>{@code display} + {@code viewId=preview-5k}: identity-map (byte-aligned per §12.9 E1).
 *   <li>{@code display} + arbitrary viewId + {@code canonicalHint}: trust the hint.
 *   <li>{@code lines}: fetch doc, walk newlines, return char range.
 * </ul>
 *
 * <p>Other view formats and {@code opaque} addresses respond {@code 400 UNRESOLVABLE_ADDRESS}
 * — the silent-wrongness pattern §3.2 names becomes a loud error.
 */
public final class ResolveAddressController {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveAddressController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

  private final DocumentService documents;

  public ResolveAddressController(DocumentService documents) {
    this.documents = Objects.requireNonNull(documents, "documents");
  }

  /** Bound handler for {@code POST /api/document/{id}/resolve-address}. */
  public void handle(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    String docIdParam = ctx.pathParam("id");
    String body = ctx.body();
    DocumentAddress address;
    try {
      address = MAPPER.readValue(body, DocumentAddress.class);
    } catch (RuntimeException e) {
      writeError(ctx, HttpStatus.BAD_REQUEST, "Invalid DocumentAddress body", "BAD_ADDRESS");
      return;
    }
    if (!docIdParam.equals(address.docId())) {
      writeError(
          ctx,
          HttpStatus.BAD_REQUEST,
          "Path docId does not match address.docId",
          "DOC_ID_MISMATCH");
      return;
    }
    try {
      DocumentAddress.Canonical resolved = resolve(address, engineContext);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("coords", "canonical");
      out.put("docId", resolved.docId());
      out.put("startChar", resolved.startChar());
      out.put("endChar", resolved.endChar());
      ctx.json(out);
    } catch (UnresolvableAddressException e) {
      writeError(ctx, HttpStatus.BAD_REQUEST, e.getMessage(), "UNRESOLVABLE_ADDRESS");
    } catch (RuntimeException e) {
      LOG.warn("resolve-address: unexpected failure for {}", docIdParam, e);
      writeError(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "Internal failure", "INTERNAL");
    }
  }

  private DocumentAddress.Canonical resolve(DocumentAddress address, EngineContext engineContext) {
    return switch (address) {
      case DocumentAddress.Canonical c -> c;
      case DocumentAddress.Display d -> {
        if (d.viewId().startsWith("preview-")) {
          // /api/preview returns a pure substring of canonical content at any
          // maxChars — identity-map across all preview-* viewIds.
          yield new DocumentAddress.Canonical(d.docId(), d.displayStart(), d.displayEnd());
        }
        if (d.canonicalHint() != null) {
          yield new DocumentAddress.Canonical(
              d.docId(), d.canonicalHint().startChar(), d.canonicalHint().endChar());
        }
        throw new UnresolvableAddressException(
            "Unsupported viewId without canonicalHint: " + d.viewId());
      }
      case DocumentAddress.Lines l -> linesToCanonical(l, engineContext);
    };
  }

  private DocumentAddress.Canonical linesToCanonical(DocumentAddress.Lines l, EngineContext engineContext) {
    String content = fetchContent(l.docId(), engineContext);
    if (content == null) {
      throw new UnresolvableAddressException(
          "Cannot resolve lines for missing document: " + l.docId());
    }
    int line = 0;
    int startOffset = l.startLine() == 0 ? 0 : -1;
    int endOffset = content.length();
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
        if (startOffset < 0 && line == l.startLine()) {
          startOffset = i + 1;
        }
        if (line == l.endLine() + 1) {
          endOffset = i;
          break;
        }
      }
    }
    if (startOffset < 0) startOffset = content.length();
    return new DocumentAddress.Canonical(l.docId(), startOffset, endOffset);
  }

  private String fetchContent(String docId, EngineContext engineContext) {
    try {
      DocumentRecord r =
          documents.fetch(docId, engineContext).toCompletableFuture().get(FETCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return r == null ? null : r.content();
    } catch (Exception e) {
      LOG.info("resolve-address: fetch failed for {}: {}", docId, e.getMessage());
      return null;
    }
  }

  private static void writeError(Context ctx, HttpStatus status, String message, String code) {
    ctx.status(status);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", message);
    payload.put("errorCode", code);
    ctx.json(payload);
  }

  private static final class UnresolvableAddressException extends RuntimeException {
    UnresolvableAddressException(String message) {
      super(message);
    }
  }
}
