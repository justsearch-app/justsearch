package io.justsearch.app.observability.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.SurfaceRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CoreSurfaceCatalog (slice 449 phase 4)")
final class CoreSurfaceCatalogTest {

  @Test
  @DisplayName(
      "Catalog ships 17 entries — 561 retired the standalone Agent surface; 565 §15.C retired"
          + " core.workflow-surface; 569 §19 added the presentation gallery + editor; 583 §D.3b added"
          + " core.api-explorer-surface (the read-only route-manifest dashboard); 629 added"
          + " core.security-surface (Security & Privacy); Search Thread S5b retired the standalone"
          + " Search surface (folded into core.unified-chat-surface); 930 §19.3 F9(d) removed the"
          + " governance dashboard")
  void surfaceCount() {
    SurfaceCatalog catalog = new CoreSurfaceCatalog();
    // Tempdoc 571 §11 / 578 added core.system-surface (the System hub host): 16 → 17. Tempdoc 578
    // Workstream A then RETIRED core.system-self-view (folded into Health as a strip): 17 → 16.
    // Tempdoc 576 §15 added core.governance-surface (DEEPLINK governance dashboard): 16 → 17.
    // Tempdoc 583 §D.3b added core.api-explorer-surface (DEEPLINK route-manifest dashboard): 17 → 18.
    // Tempdoc 629 added core.security-surface (RAIL — encryption control + at-rest status): 18 → 19.
    // Tempdoc 855 §5 item 1 absorbed core.security-surface into Settings as a member (RAIL →
    // DEEPLINK) — no count change, still 19 at this point.
    // Search Thread S5b RETIRED core.search-surface (folded into core.unified-chat-surface): 19 → 18.
    // Tempdoc 930 §19.3 F9(d) REMOVED core.governance-surface with the dashboard: 18 → 17.
    assertEquals(17, catalog.definitions().size());
  }

  @Test
  @DisplayName("Slice 486 F15-narrow: Activity surface declared")
  void activitySurfaceDeclared() {
    var entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.ACTIVITY_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.activity-surface"), entry.id());
    assertEquals(CoreSurfaceCatalog.ACTIVITY_MOUNT_TAG, entry.mountTag());
    assertEquals(1, entry.consumes().resources().size());
    assertTrue(
        entry.consumes().resources().stream()
            .anyMatch(r -> r.value().equals("core.action-ledger")),
        "Activity surface consumes core.action-ledger (tempdoc 571: its real TRUST authority, the "
            + "unified ledger it renders — supersedes the pre-unified core.operation-history stand-in)");
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new CoreSurfaceCatalog().namespace());
  }

  @Test
  @DisplayName("Library entry shape: id + audience + placement + mountTag")
  void librarySurfaceShape() {
    Surface entry = new CoreSurfaceCatalog().definitions().get(0);
    assertEquals(new SurfaceRef("core.library-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    assertSame(Placement.RAIL, entry.placement());
    assertEquals("jf-library-surface", entry.mountTag());
    // Tempdoc 571 §11 / 578 — Library hosts the Browse file-tree as a member (its "Browse" tab).
    assertEquals(List.of(new SurfaceRef("core.browse-surface")), entry.members());
  }

  @Test
  @DisplayName("Library consumes the 5 expected Operations declared via useOperation in React")
  void libraryConsumesOperations() {
    Surface entry = new CoreSurfaceCatalog().definitions().get(0);
    var ops = entry.consumes().operations();
    assertEquals(5, ops.size());
    assertTrue(ops.contains(new OperationRef("core.reindex")));
    assertTrue(ops.contains(new OperationRef("core.add-watched-root")));
    assertTrue(ops.contains(new OperationRef("core.remove-watched-root")));
    assertTrue(ops.contains(new OperationRef("core.preview-excludes")));
    assertTrue(ops.contains(new OperationRef("core.apply-excludes")));
  }

  @Test
  @DisplayName("Library consumes core.indexed-roots Resource (phase 7c) and no prompts / channels")
  void libraryConsumesIndexedRootsResource() {
    Surface entry = new CoreSurfaceCatalog().definitions().get(0);
    // Slice 449 phase 7c — Resource declared after phase 7a built the
    // TABULAR × ONE_SHOT IndexedRootsResourceCatalog.
    assertTrue(
        entry
            .consumes()
            .resources()
            .contains(new io.justsearch.agent.api.registry.ResourceRef("core.indexed-roots")),
        "Library surface must declare core.indexed-roots in consumes.resources");
    assertEquals(1, entry.consumes().resources().size());
    assertTrue(entry.consumes().prompts().isEmpty());
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
  }

  @Test
  @DisplayName("findById resolves the library surface")
  void findByIdResolves() {
    SurfaceCatalog catalog = new CoreSurfaceCatalog();
    assertTrue(
        catalog.findById(CoreSurfaceCatalog.LIBRARY_SURFACE_ID).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("presentation labels point at registry-surface.library-surface.*")
  void presentationKeysDeclared() {
    Surface entry = new CoreSurfaceCatalog().definitions().get(0);
    assertEquals(
        "registry-surface.library-surface.label",
        entry.presentation().labelKey().value());
    assertEquals(
        "registry-surface.library-surface.description",
        entry.presentation().descriptionKey().value());
  }

  @Test
  @DisplayName("Slice 451 phase 9: Help surface entry shape + Operation consumption")
  void helpSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.HELP_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.help-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    // Tempdoc 578 §5.6 Phase 4 — Help is reference content, not a rail workspace surface: DEEPLINK,
    // reached via the dedicated "?" rail affordance (and URL/command-palette), not a rail catalog slot.
    assertSame(Placement.DEEPLINK, entry.placement());
    assertEquals("jf-help-surface", entry.mountTag());
    assertEquals(
        java.util.Set.of(
            new OperationRef("core.export-diagnostics"),
            new OperationRef("core.copy-diagnostic-summary")),
        entry.consumes().operations(),
        "Help consumes both diagnostics handoff operations");
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().prompts().isEmpty());
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
  }

  @Test
  @DisplayName("Slice 452 phase 9: Brain surface entry shape + 9-Operation consumption")
  void brainSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.BRAIN_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.brain-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    assertSame(Placement.RAIL, entry.placement());
    assertEquals("jf-brain-surface", entry.mountTag());
    // Tempdoc 571 §11 / 578 — AI Brain hosts the FE-contributed Memory surface as a tab (cross-source).
    assertEquals(List.of(new SurfaceRef("core.memory-surface")), entry.members());
    var ops = entry.consumes().operations();
    assertEquals(9, ops.size(), "Brain consumes 9 Operations");
    for (String id :
        new String[] {
          "core.start-ai-install",
          "core.cancel-ai-install",
          "core.repair-ai-install",
          "core.preflight-ai-pack",
          "core.import-ai-pack",
          "core.activate-runtime-variant",
          "core.deactivate-runtime-variant",
          "core.switch-inference-mode",
          "core.reload-inference",
        }) {
      assertTrue(ops.contains(new OperationRef(id)), "Brain must consume " + id);
    }
  }

  @Test
  @DisplayName(
      "Tempdoc 561 surface tier: the standalone core.agent-surface is retired; the agent-run"
          + " shape folds into the one interaction window (core.unified-chat-surface)")
  void agentSurfaceRetiredAndFoldedIntoUnifiedWindow() {
    CoreSurfaceCatalog catalog = new CoreSurfaceCatalog();
    // The standalone RAIL agent window no longer exists as its own surface.
    assertTrue(
        catalog.findById(new SurfaceRef("core.agent-surface")).isEmpty(),
        "core.agent-surface must be retired (folded into the one interaction window)");
    // The agent-run shape is now consumed by the one interaction window alongside ask/chat/extract.
    Surface unified =
        catalog.findById(CoreSurfaceCatalog.UNIFIED_CHAT_SURFACE_ID).orElseThrow();
    assertTrue(
        unified.consumes().conversationShapes().contains(new ConversationShapeRef("core.agent-run")),
        "the one interaction window must consume core.agent-run");
  }

  @Test
  @DisplayName(
      "Slice 454 phase 9 / tempdoc 855: Settings surface entry shape (MODAL, consumes"
          + " core.reset-settings)")
  void settingsSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.SETTINGS_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.settings-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    // Tempdoc 855 §11.1 — Settings is the first MODAL surface: it opens as the centered
    // <jf-settings-window> over the current stage surface, never as a rail-mounted stage pane.
    assertSame(Placement.MODAL, entry.placement());
    assertEquals("jf-settings-surface", entry.mountTag());
    // Tempdoc 571 §11 / 578 — Settings ⊇ Appearance: hosts the two theming surfaces as an Appearance tab group.
    // Tempdoc 855 §5 item 1 / §9.3 — Settings ⊇ Security & Privacy: hosts core.security-surface as a
    // member category under Privacy & Trust (absorbed off the rail; deep-links keep working via the
    // member→host alias redirect).
    assertEquals(
        List.of(
            new SurfaceRef("core.presentation-gallery-surface"),
            new SurfaceRef("core.presentation-editor-surface"),
            new SurfaceRef("core.security-surface")),
        entry.members());
    assertEquals(
        java.util.Set.of(new OperationRef("core.reset-settings")),
        entry.consumes().operations());
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().prompts().isEmpty());
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
  }

  @Test
  @DisplayName("Slice 455 phase 9: Browse surface entry shape (no Operations consumed)")
  void browseSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.BROWSE_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.browse-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    // Tempdoc 571 §11 / 578 — Browse is a MEMBER of Library (rendered as its "Browse" tab), so its
    // home is its host: DEEPLINK (off the rail, URL-routable) rather than RAIL.
    assertSame(Placement.DEEPLINK, entry.placement());
    assertEquals("jf-browse-surface", entry.mountTag());
    // Browse uses /api/knowledge/folders + /api/knowledge/folder-files REST.
    assertTrue(entry.consumes().operations().isEmpty());
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().prompts().isEmpty());
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
  }

  @Test
  @DisplayName("Tempdoc 855 §5 item 1: Security surface entry shape (DEEPLINK, member of Settings)")
  void securitySurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.SECURITY_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.security-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    // Tempdoc 855 §5 item 1 / §9.3 — Security is a MEMBER of Settings (its "Security & Privacy"
    // category under Privacy & Trust), so its home is its host: DEEPLINK (off the rail, still
    // URL-routable via the member→host alias redirect) rather than RAIL (was 629's placement).
    assertSame(Placement.DEEPLINK, entry.placement());
    assertEquals("jf-security-surface", entry.mountTag());
    assertTrue(entry.consumes().operations().isEmpty());
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().prompts().isEmpty());
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
  }

  @Test
  @DisplayName("Slice 456 phase 9: Health surface entry shape (consumes 6 quick-action Operations)")
  void healthSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.HEALTH_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.health-surface"), entry.id());
    assertSame(Audience.USER, entry.audience());
    // Tempdoc 571 §11 / 578 — Health is a MEMBER of the System hub: DEEPLINK (off the rail).
    assertSame(Placement.DEEPLINK, entry.placement());
    assertEquals("jf-health-surface", entry.mountTag());
    var ops = entry.consumes().operations();
    assertEquals(6, ops.size());
    for (String id :
        new String[] {
          "core.reindex",
          "core.restart-worker",
          "core.bulk-reindex",
          "core.clear-failed-jobs",
          "core.export-diagnostics",
          "core.index-gc",
        }) {
      assertTrue(ops.contains(new OperationRef(id)), "Health must consume " + id);
    }
  }

  @Test
  @DisplayName("578 §11/post-review: System hub host — RAIL, hosts Health/Logs/Activity, EMPTY consumes (PRODUCT)")
  void systemHubShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.SYSTEM_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.system-surface"), entry.id());
    assertSame(Placement.RAIL, entry.placement());
    assertEquals("jf-system-surface", entry.mountTag());
    // Hosts the three diagnostics/trust surfaces as tabs (declared order).
    assertEquals(
        List.of(
            new SurfaceRef("core.health-surface"),
            new SurfaceRef("core.logs-surface"),
            new SurfaceRef("core.activity-surface")),
        entry.members());
    // Post-review honesty fix (571 §8): the hub COMPOSES members; it declares NO authority it does not
    // itself project. Empty consumes ⟹ derives PRODUCT. The per-member altitude framing lives inside
    // <jf-surface-tabs>. (No fake engine-log consumption → no false consumer hook in SurfaceConsumerIndex.)
    assertTrue(entry.consumes().diagnosticChannels().isEmpty());
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().operations().isEmpty());
  }

  @Test
  @DisplayName("Slice 3a.2.e: Logs surface entry shape (OPERATOR audience; consumes core.engine-log channel)")
  void logsSurfaceShape() {
    Surface entry =
        new CoreSurfaceCatalog().findById(CoreSurfaceCatalog.LOGS_SURFACE_ID).orElseThrow();
    assertEquals(new SurfaceRef("core.logs-surface"), entry.id());
    // OPERATOR audience — raw log tail is operator/dev-mode UX, not end-user.
    assertSame(Audience.OPERATOR, entry.audience());
    // Tempdoc 571 §11 / 578 — Logs is a MEMBER of the System hub: DEEPLINK (off the rail).
    assertSame(Placement.DEEPLINK, entry.placement());
    assertEquals("jf-log-surface", entry.mountTag());
    // Consumes the core.engine-log DiagnosticChannel (slice 448 substrate).
    assertEquals(
        java.util.Set.of(
            new io.justsearch.agent.api.registry.DiagnosticChannelRef("core.engine-log")),
        entry.consumes().diagnosticChannels());
    assertTrue(entry.consumes().operations().isEmpty());
    assertTrue(entry.consumes().resources().isEmpty());
    assertTrue(entry.consumes().prompts().isEmpty());
  }
}
