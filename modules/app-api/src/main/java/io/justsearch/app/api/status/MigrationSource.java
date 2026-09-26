/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Tempdoc 837 §2.3 — the closed vocabulary for WHY an index generation is being rebuilt.
 *
 * <p>The value is persisted in the on-disk generation manifest and read back through
 * {@code IndexGenerationManager.readGenerationSourceBestEffort}. Before this it was free text, and
 * caller-controlled at one boundary: {@code POST /api/indexing/migration/start} forwarded an
 * arbitrary request-body string straight into the manifest.
 *
 * <p><b>Closed on READ, enforced at the one outside-writable boundary — deliberately not an
 * enum-typed write.</b> Two facts make a strict write-side enum wrong: an index built by an older
 * build (or by a test driver, or from a hand-written manifest) will hand back a string outside any
 * vocabulary we define FOREVER, and six existing system/worker test drivers write labels like
 * {@code system_test} / {@code pause_resume_test} / {@code rebuild-1}. So {@link #UNKNOWN} is a
 * first-class member rather than an error: unrecognized input maps onto it and renders the existing
 * generic "the index is being rebuilt" wording. Tests keep writing free strings, and nothing lies.
 *
 * <p>It lives in {@code app-api} beside {@link MigrationGenerationView}, the view that carries the
 * field: the Head's REST boundary must name it (that is where an outside caller writes it), and
 * {@code ui.api} is architecturally forbidden from depending on {@code io.justsearch.ipc} types —
 * the guardrail's own prescribed remedy is "use app-api contracts instead". The worker reaches it
 * through {@code ipc-common}, which already {@code api}-exposes {@code app-api}.
 *
 * <p>This vocabulary REPLACES {@code LifecycleReasonCode.INDEX_REBUILDING} (§2.1/§2.2): that code
 * was emitted only while {@code migrationState ∈ {MIGRATING, SWITCHING}}, which is exactly the
 * window in which the FE verdict is forced to {@code transitioning} and the readiness notice returns
 * null — so no surface ever worded it. A rebuild in progress is a TRANSITION (it self-clears, has
 * progress, needs no user action), and the source is a facet OF that transition, not a second
 * verdict about it.
 */
public enum MigrationSource {
  /** The index was detected corrupt, backed up, and is being rebuilt from source (tempdoc 628). */
  CORRUPT_INDEX_REBUILD("corrupt_index_rebuild"),
  /** The embedding fingerprint changed under the blue/green migrate policy. */
  EMBEDDING_MODEL_CHANGE("embedding_model_change"),
  /** The stored index schema no longer matches the current one. */
  SCHEMA_MISMATCH("schema_mismatch"),
  /** The user invoked {@code core.rebuild-index}. */
  USER_REQUESTED_REBUILD("user_requested_rebuild"),
  /** The user invoked {@code core.bulk-reindex}. */
  USER_REQUESTED_BULK_REINDEX("user_requested_bulk_reindex"),
  /** Install AI accepted a staged model candidate for generation activation. */
  INSTALLER_MODEL_ACTIVATION("installer_model_activation"),
  /** A migration was started with no more specific reason. */
  MANUAL("manual"),
  /**
   * The manifest carried a value this build does not recognize — an older generation, a test
   * driver's label, or a hand-edited manifest. A real member, not an error: the transition is worded
   * generically, which is the honest answer when we do not know why.
   */
  UNKNOWN("unknown");

  private final String wire;

  MigrationSource(String wire) {
    this.wire = wire;
  }

  /** The persisted / wire string. Stable — it is what is already on disk. */
  public String wire() {
    return wire;
  }

  /**
   * The total read-side mapping. Every input — {@code null}, blank, a legacy label, a typo — lands
   * on a member; anything unrecognized lands on {@link #UNKNOWN}. Matching is
   * case-insensitive and trims surrounding whitespace, since the value has been caller-controlled.
   */
  public static MigrationSource fromWire(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
    for (MigrationSource member : values()) {
      if (member.wire.equals(normalized)) {
        return member;
      }
    }
    return UNKNOWN;
  }
}
