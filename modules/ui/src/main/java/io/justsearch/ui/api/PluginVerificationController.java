/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.justsearch.app.services.settings.PluginAllowlistStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice 477 H2.3 — POST /api/plugins/verify endpoint.
 *
 * V1.5.2 posture (§28.W12 update): full Sigstore integration is still
 * gated on {@code dev.sigstore:sigstore-java} dep weight (~30MB) +
 * the absence of any real signed plugin to verify (the
 * "infrastructure-without-customers" failure mode §1 warns against).
 *
 * In the meantime this controller ships an OPERATOR-CONFIGURABLE
 * ALLOWLIST mechanism. The trust state of a plugin is determined by
 * three checks in order:
 *
 *   1. If the artifactSha256 is in the persistent allowlist
 *      (configured via {@link #addToAllowlist}), return
 *      verified=true. This is the equivalent of "operator has
 *      reviewed and trusted this artifact" — explicit, auditable,
 *      no cryptographic chain required.
 *   2. Otherwise return verified=false with a reason explaining
 *      why (no signature, unknown artifact, allowlist-only mode).
 *
 * The allowlist is in-memory today (operator adds via
 * {@code addToAllowlist} at boot) — file-backed persistence is a
 * one-line change when needed but isn't load-bearing for V1 since
 * no plugins exist to allowlist anyway.
 *
 * When V1.5.2 actually wires sigstore-java, the order becomes:
 *   1. Try cryptographic chain via BundleParser.
 *   2. Fall through to the allowlist (operator overrides).
 *
 * Wire shape (request body):
 *   { artifactSha256: string, signature: string | null, url: string }
 *
 * Wire shape (response 200):
 *   { verified: boolean, reason: string }
 *
 * Validation: malformed bodies produce 400. The endpoint never
 * throws or returns 5xx for verification failures — those are
 * `verified: false` with an explanation in `reason`.
 */
public final class PluginVerificationController {
  private static final Logger LOG = LoggerFactory.getLogger(PluginVerificationController.class);

  /** SHA-256 hex digest pattern: 64 lowercase hex chars. */
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

  /** Reason returned when an artifact is not on the operator's allowlist. */
  private static final String DEFAULT_DENY_REASON =
      "Not on operator allowlist. V1 trust posture: explicit allowlist via"
          + " PluginVerificationController#addToAllowlist; cryptographic chain via"
          + " sigstore-java is V1.5.2-tier (dep-weight gated).";

  /** Reason returned when an allowlisted artifact verifies. */
  private static final String ALLOWLIST_HIT_REASON =
      "Operator allowlist hit. Trust derived from explicit allowlist entry"
          + " (not from cryptographic chain).";

  /**
   * In-memory operator allowlist. Each entry is a 64-char lowercase
   * SHA-256 hex of the plugin artifact. §28.W12 ships this; the
   * file-backed persistence is a one-liner when operator workflow
   * needs it.
   */
  private final Set<String> allowlist = Collections.synchronizedSet(new HashSet<>());

  /**
   * Durable backing store for the allowlist; {@code null} means in-memory only (tests / the legacy
   * no-arg path). Tempdoc 560 §28: persistence is what makes an operator approval survive a restart.
   */
  private final PluginAllowlistStore store;

  /** In-memory controller (no persistence). Retained for tests and the legacy no-arg call site. */
  public PluginVerificationController() {
    this(null);
  }

  /**
   * Tempdoc 560 §28 — persistence-backed controller. Loads any operator-approved entries at
   * construction so trusted plugins survive restarts; subsequent approvals/revocations are written
   * through to {@code store}.
   */
  public PluginVerificationController(PluginAllowlistStore store) {
    this.store = store;
    if (store != null) {
      allowlist.addAll(store.load());
    }
  }

  private void persist() {
    if (store != null) {
      store.save(listAllowlist());
    }
  }

  /**
   * §28.W12 — operator-side admission. Adds an artifact's SHA-256 to
   * the trust allowlist. No-op if invalid; throws nothing. Use at
   * boot from operator-configured sources (CLI flag, persisted file,
   * remote allowlist refresh, etc.). Returns true when the entry was
   * actually added (false when the input was malformed or already
   * present). A successful add is persisted through to {@link #store}.
   */
  public boolean addToAllowlist(String artifactSha256) {
    if (artifactSha256 == null) return false;
    String normalized = artifactSha256.toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(normalized).matches()) return false;
    boolean added = allowlist.add(normalized);
    if (added) persist();
    return added;
  }

  /** §28.W12 — removes an entry from the allowlist; a successful removal is persisted. */
  public boolean removeFromAllowlist(String artifactSha256) {
    if (artifactSha256 == null) return false;
    boolean removed = allowlist.remove(artifactSha256.toLowerCase(Locale.ROOT));
    if (removed) persist();
    return removed;
  }

  /** §28.W12 — read-only allowlist snapshot. Useful for admin UIs. */
  public Set<String> listAllowlist() {
    synchronized (allowlist) {
      return Set.copyOf(allowlist);
    }
  }

  /** §28.W12 — test-only: clear the allowlist. */
  public void __resetForTest() {
    allowlist.clear();
  }

  /** POST /api/plugins/verify */
  public void handleVerify(Context ctx) {
    Map<String, Object> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      LOG.debug("plugin verification: malformed JSON body", e);
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(Map.of("error", "malformed JSON body"));
      return;
    }
    if (body == null) {
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(Map.of("error", "request body must be a JSON object"));
      return;
    }
    Object artifactSha256 = body.get("artifactSha256");
    if (!(artifactSha256 instanceof String s) || !SHA256_HEX.matcher(s).matches()) {
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(
          Map.of(
              "error",
              "artifactSha256 must be a 64-character lowercase hex string (SHA-256 of plugin source)"));
      return;
    }
    // url and signature fields are optional; allowlist mode doesn't
    // consume them. V1.5.2 sigstore-java will validate cosign bundle
    // shape via BundleParser.

    String normalized = s.toLowerCase(Locale.ROOT);
    if (allowlist.contains(normalized)) {
      LOG.debug(
          "plugin verification: allowlist hit for artifactSha256={}",
          normalized.substring(0, 12) + "…");
      ctx.json(
          Map.of(
              "verified", true,
              "reason", ALLOWLIST_HIT_REASON));
      return;
    }

    LOG.debug(
        "plugin verification: not-allowlisted artifactSha256={}, signaturePresent={}, url={}",
        normalized.substring(0, 12) + "…",
        body.get("signature") != null,
        body.get("url"));
    ctx.json(
        Map.of(
            "verified", false,
            "reason", DEFAULT_DENY_REASON));
  }

  /**
   * POST /api/plugins/allowlist — operator approves a plugin artifact, adding its SHA-256 to the
   * trust allowlist (persisted). Packaged app requests require window authority; the
   * approval is the explicit, auditable trust ceremony tempdoc 560 §28 relies on (short of Sigstore).
   */
  public void handleApprove(Context ctx) {
    if (!WindowAuthority.require(ctx)) return;
    String sha = extractSha(ctx);
    if (sha == null) return; // extractSha already wrote the 400
    boolean added = addToAllowlist(sha);
    LOG.info(
        "plugin allowlist: operator approved artifactSha256={} (newlyAdded={})",
        sha.substring(0, 12) + "…",
        added);
    ctx.json(
        Map.of(
            "approved",
            true,
            "newlyAdded",
            added,
            "reason",
            added
                ? "Artifact added to the operator trust allowlist; reload the plugin to load it TRUSTED."
                : "Artifact was already on the operator trust allowlist."));
  }

  /** GET /api/plugins/allowlist — read-only snapshot for an operator management surface. */
  public void handleList(Context ctx) {
    ctx.json(Map.of("allowlist", List.copyOf(listAllowlist())));
  }

  /** DELETE /api/plugins/allowlist/{sha} — operator revokes an artifact's trust (persisted). */
  public void handleRevoke(Context ctx) {
    String raw = ctx.pathParam("sha");
    String normalized = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(normalized).matches()) {
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(Map.of("error", "path segment must be a 64-character lowercase hex SHA-256"));
      return;
    }
    boolean removed = removeFromAllowlist(normalized);
    LOG.info(
        "plugin allowlist: operator revoked artifactSha256={} (wasPresent={})",
        normalized.substring(0, 12) + "…",
        removed);
    ctx.json(Map.of("revoked", removed));
  }

  /** Parses + validates {@code artifactSha256} from a JSON body; writes a 400 and returns null on failure. */
  private String extractSha(Context ctx) {
    Map<String, Object> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      LOG.debug("plugin allowlist: malformed JSON body", e);
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(Map.of("error", "malformed JSON body"));
      return null;
    }
    Object artifactSha256 = body == null ? null : body.get("artifactSha256");
    if (!(artifactSha256 instanceof String s)
        || !SHA256_HEX.matcher(s.toLowerCase(Locale.ROOT)).matches()) {
      ctx.status(HttpStatus.BAD_REQUEST);
      ctx.json(
          Map.of(
              "error",
              "artifactSha256 must be a 64-character lowercase hex string (SHA-256 of plugin source)"));
      return null;
    }
    return s.toLowerCase(Locale.ROOT);
  }
}
