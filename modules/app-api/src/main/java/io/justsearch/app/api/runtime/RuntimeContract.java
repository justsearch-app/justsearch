/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.app.api.mcp.McpContractVersions;
import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * The JustSearch Runtime Contract descriptor (tempdoc 654).
 *
 * <p>A small, coarse, slow-moving declaration advertised on the runtime manifest so an external
 * agent can target <em>one</em> versioned object. It does not add machinery: it names a
 * {@link #version() contract version} and projects the current versions of the contract's
 * constituent surfaces — the runtime manifest, the health/status lifecycle subset, and the MCP
 * endpoint + curated tool surface. Everything the desktop reference client uses beyond these is
 * <em>not</em> part of the contract (tempdoc 654 §D2/D6).
 *
 * <p><b>Projection, not fork.</b> {@link #current()} reads each constituent version from its
 * existing single source ({@link RuntimeManifest#CURRENT_SCHEMA_VERSION},
 * {@link LifecycleSnapshotV1#SCHEMA_VERSION}, {@link McpContractVersions}); it invents no version
 * of its own except {@link #CURRENT_VERSION}, the coarse umbrella. So the manifest and the MCP
 * {@code initialize} response report the same numbers by construction.
 *
 * <p><b>Versioning stance (§D3/D5).</b> {@link #CURRENT_VERSION} is bumped only on a
 * backward-incompatible change to any constituent (mirroring MCP's own bump-only-on-break rule);
 * additive changes do not bump it. It starts pre-1.0 by the under-promise stance — a scoped 1.0
 * is declared only once "we will not break this" is true.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeContract(String version, Constituents constituents) {

  /**
   * The coarse Runtime Contract umbrella version. Pre-1.0 by the under-promise stance
   * (tempdoc 654 §D3). Bumped only on a break to a constituent, never on an additive change.
   *
   * <p>0.2.0 (tempdoc 770): the first break to a constituent. MCP tool surface {@code 0.5.0}
   * removes default {@code structuredContent} fields from the curated tool set — a
   * public-contract constituent — where {@code 0.2.0}-{@code 0.4.0} had been purely additive.
   * See the changelog in {@code docs/reference/runtime-contract.md}.
   */
  public static final String CURRENT_VERSION = "0.3.0";

  public RuntimeContract {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must be non-blank");
    }
  }

  /**
   * Assemble the descriptor for the currently-running instance by projecting each constituent's
   * canonical version constant. This is the only place the contract's constituent set is composed.
   */
  public static RuntimeContract current() {
    return new RuntimeContract(
        CURRENT_VERSION,
        new Constituents(
            RuntimeManifest.CURRENT_SCHEMA_VERSION,
            LifecycleSnapshotV1.SCHEMA_VERSION,
            McpContractVersions.PROTOCOL_VERSION,
            McpContractVersions.TOOL_SURFACE_VERSION));
  }

  /**
   * Public projection (tempdoc 501 §13.4.5 audience axis): the contract descriptor carries no
   * credentials, so the public view is identity. Same structural commitment as
   * {@link RuntimeManifest.WorkerInfo#publicProjection} — a future credential-class constituent
   * must add its redaction here.
   */
  public RuntimeContract publicProjection() {
    return this;
  }

  /**
   * The pinned versions of the contract's constituent surfaces. Each is a projection of an
   * existing single source (see {@link RuntimeContract#current()}); this record never authors a
   * version, it only reports them together as the compatibility-matrix row for this instance.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Constituents(
      int manifestSchemaVersion,
      int lifecycleSchemaVersion,
      String mcpProtocolVersion,
      String mcpToolSurfaceVersion) {}
}
