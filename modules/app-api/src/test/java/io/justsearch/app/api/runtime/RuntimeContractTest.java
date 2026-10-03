/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.mcp.McpContractVersions;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 654 — the Runtime Contract descriptor is a projection over existing version single
 * sources, not a fork. These tests lock that: {@link RuntimeContract#current()} must read each
 * constituent from its canonical constant, so a future bump to any source can't silently desync the
 * advertised contract.
 */
class RuntimeContractTest {

  @Test
  void currentProjectsEachConstituentFromItsSingleSource() {
    RuntimeContract c = RuntimeContract.current();

    assertEquals(RuntimeContract.CURRENT_VERSION, c.version());
    // Each constituent equals the canonical constant — asserting against literals here would
    // defeat the purpose (it would pass even if current() hardcoded a stale value).
    assertEquals(
        RuntimeManifest.CURRENT_SCHEMA_VERSION, c.constituents().manifestSchemaVersion());
    assertEquals(LifecycleSnapshotV2.SCHEMA_VERSION, c.constituents().lifecycleSchemaVersion());
    assertEquals(McpContractVersions.PROTOCOL_VERSION, c.constituents().mcpProtocolVersion());
    assertEquals(
        McpContractVersions.TOOL_SURFACE_VERSION, c.constituents().mcpToolSurfaceVersion());
  }

  @Test
  void publicProjectionIsIdentity() {
    RuntimeContract c = RuntimeContract.current();
    assertSame(c, c.publicProjection(), "contract carries no credentials — public view is identity");
  }

  @Test
  void rejectsBlankVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RuntimeContract("  ", RuntimeContract.current().constituents()));
  }
}
