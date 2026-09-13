package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.LoggerNamespaceSelector;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.SubCategory;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SurfaceAreaValidator (slice 449)")
final class SurfaceAreaValidatorTest {

  private final SurfaceAreaValidator validator = new SurfaceAreaValidator();

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private static Presentation presentationOf(String stem) {
    return Presentation.of(
        new I18nKey("registry-surface." + stem + ".label"),
        new I18nKey("registry-surface." + stem + ".description"));
  }

  private static Surface surface(
      String id, Audience audience, Placement placement, TrustTier tier) {
    return surface(id, audience, placement, tier, SurfaceConsumes.empty());
  }

  private static Surface surface(
      String id,
      Audience audience,
      Placement placement,
      TrustTier tier,
      SurfaceConsumes consumes) {
    return new Surface(
        new SurfaceRef(id),
        presentationOf(id.replace('.', '-')),
        audience,
        placement,
        consumes,
        "jf-" + id.replace('.', '-'),
        new Provenance(tier, tier == TrustTier.CORE ? "core" : "test-plugin", "1.0"));
  }

  private static SurfaceCatalog catalogOf(Surface... surfaces) {
    return SurfaceCatalog.of("core", List.of(surfaces));
  }

  private static DiagnosticChannel channel(String id, ConsumerPermission perm) {
    return new DiagnosticChannel(
        new DiagnosticChannelRef(id),
        Presentation.of(
            new I18nKey("registry-diagnostic.x.label"),
            new I18nKey("registry-diagnostic.x.description")),
        Set.of(DataClass.USER_PATHS),
        ProducerKind.IN_PROCESS_LOGBACK,
        DeliveryMode.SSE_STREAM,
        LoggerNamespaceSelector.of(Map.of("io.justsearch.", SubCategory.CORE_DIAGNOSTIC)),
        "/api/x",
        perm,
        Provenance.core("1.0"));
  }

  private static DiagnosticChannelCatalog channelCatalog(DiagnosticChannel... channels) {
    return DiagnosticChannelCatalog.of("core", List.of(channels));
  }

  /** Builds a ResourceCatalog of EVENT_STREAM Resources, each stamped with the given altitude role. */
  private static io.justsearch.agent.api.registry.ResourceCatalog resourceCatalog(
      Map<String, Role> idToRole) {
    List<io.justsearch.agent.api.registry.Resource> defs = new java.util.ArrayList<>();
    idToRole.forEach(
        (id, role) ->
            defs.add(
                new io.justsearch.agent.api.registry.Resource(
                        new ResourceRef(id),
                        Presentation.of(
                            new I18nKey("registry-resource.x.label"),
                            new I18nKey("registry-resource.x.description")),
                        "https://ssot.justsearch/v1/schemas/x.v1.json",
                        io.justsearch.agent.api.registry.Category.EVENT_STREAM,
                        io.justsearch.agent.api.registry.SubscriptionMode.SSE_STREAM,
                        "/api/x",
                        "kind",
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        Provenance.core("1.0"),
                        io.justsearch.agent.api.registry.Privacy.noPaths(),
                        Set.of(),
                        Set.of(),
                        "")
                    .withRole(role)));
    return io.justsearch.agent.api.registry.ResourceCatalog.of("core", defs);
  }

  private static SurfaceConsumes consumesResources(String... resourceIds) {
    Set<ResourceRef> refs = new java.util.HashSet<>();
    for (String id : resourceIds) refs.add(new ResourceRef(id));
    return new SurfaceConsumes(refs, Set.of(), Set.of(), Set.of());
  }

  // ------------------------------------------------------------------
  // Tempdoc 571 §4c — resource cross-ref + altitude-derivation conflict
  // ------------------------------------------------------------------

  @Test
  @DisplayName("571: consuming an unregistered Resource produces a cross-ref finding")
  void unregisteredResourceFinding() {
    Surface s =
        surface(
            "core.x", Audience.USER, Placement.RAIL, TrustTier.CORE, consumesResources("core.ghost"));
    var findings =
        validator.validate(
            catalogOf(s), List.of(), List.of(resourceCatalog(Map.of("core.real", Role.PRODUCT))));
    assertEquals(1, findings.size());
    assertTrue(
        findings.get(0).issue().contains("core.ghost"),
        "expected an unregistered-resource cross-ref finding, got: " + findings);
  }

  @Test
  @DisplayName("571 §4c: consuming two distinct non-PRODUCT authorities is a derivation conflict")
  void altitudeConflictFinding() {
    Surface s =
        surface(
            "core.x",
            Audience.USER,
            Placement.RAIL,
            TrustTier.CORE,
            consumesResources("core.diag", "core.trust"));
    var findings =
        validator.validate(
            catalogOf(s),
            List.of(),
            List.of(resourceCatalog(Map.of("core.diag", Role.DIAGNOSTIC, "core.trust", Role.TRUST))));
    assertTrue(
        findings.stream().anyMatch(f -> f.issue().contains("conflict")),
        "expected an altitude-derivation conflict finding, got: " + findings);
  }

  @Test
  @DisplayName("571 §4c: a single TRUST authority derives cleanly (no conflict, no cross-ref finding)")
  void singleTrustAuthorityClean() {
    Surface s =
        surface(
            "core.activity-surface",
            Audience.USER,
            Placement.RAIL,
            TrustTier.CORE,
            consumesResources("core.action-ledger"));
    var findings =
        validator.validate(
            catalogOf(s),
            List.of(),
            List.of(resourceCatalog(Map.of("core.action-ledger", Role.TRUST))));
    assertTrue(findings.isEmpty(), "a single TRUST authority must validate cleanly, got: " + findings);
  }

  // ------------------------------------------------------------------
  // Basic shape checks
  // ------------------------------------------------------------------

  @Test
  @DisplayName("well-formed CORE-USER-RAIL catalog produces no findings")
  void cleanCatalog() {
    SurfaceCatalog cat = catalogOf(
        surface("core.library-surface", Audience.USER, Placement.RAIL, TrustTier.CORE));
    assertTrue(validator.validate(cat).isEmpty());
  }

  @Test
  @DisplayName("duplicate surface id within catalog produces a finding")
  void duplicateIdFinding() {
    SurfaceCatalog cat = catalogOf(
        surface("core.library-surface", Audience.USER, Placement.RAIL, TrustTier.CORE),
        surface("core.library-surface", Audience.USER, Placement.STAGE, TrustTier.CORE));
    var findings = validator.validate(cat);
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("duplicate"));
  }

  // ------------------------------------------------------------------
  // Audience composition rule (§0 D2)
  // ------------------------------------------------------------------

  @Test
  @DisplayName("audience floor: CORE provenance permits USER")
  void coreProvenancePermitsUser() {
    Surface s = surface("core.x", Audience.USER, Placement.RAIL, TrustTier.CORE);
    assertTrue(validator.validate(catalogOf(s)).isEmpty());
  }

  @Test
  @DisplayName("audience floor: TRUSTED_PLUGIN provenance permits USER")
  void trustedPluginPermitsUser() {
    Surface s = surface("vendor.acme.x", Audience.USER, Placement.RAIL, TrustTier.TRUSTED_PLUGIN);
    assertTrue(validator.validate(catalogOf(s)).isEmpty());
  }

  @Test
  @DisplayName("audience floor: UNTRUSTED_PLUGIN provenance lifts floor to OPERATOR")
  void untrustedPluginLiftsToOperator() {
    Surface s = surface("vendor.x.y", Audience.USER, Placement.RAIL, TrustTier.UNTRUSTED_PLUGIN);
    var findings = validator.validate(catalogOf(s));
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("OPERATOR"),
        "untrusted plugin USER surface must be flagged with OPERATOR floor");
  }

  @Test
  @DisplayName("audience floor: UNTRUSTED_PLUGIN OPERATOR is accepted")
  void untrustedPluginOperatorAccepted() {
    Surface s = surface("vendor.x.y", Audience.OPERATOR, Placement.RAIL, TrustTier.UNTRUSTED_PLUGIN);
    assertTrue(validator.validate(catalogOf(s)).isEmpty());
  }

  // ------------------------------------------------------------------
  // Channel-floor enforcement
  // ------------------------------------------------------------------

  @Test
  @DisplayName("channel floor: consuming OPERATOR_OVERRIDE channel lifts floor to OPERATOR")
  void operatorOverrideChannelLiftsFloor() {
    DiagnosticChannel ch = channel("core.engine-log", ConsumerPermission.OPERATOR_OVERRIDE);
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(ch.id()));
    Surface s = surface("core.engine-log-surface", Audience.USER, Placement.STAGE, TrustTier.CORE, consumes);
    var findings = validator.validate(catalogOf(s), List.of(channelCatalog(ch)));
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("OPERATOR"));
  }

  @Test
  @DisplayName("channel floor: consuming TRUSTED_PLUGIN channel lifts floor to OPERATOR")
  void trustedPluginChannelLiftsFloor() {
    DiagnosticChannel ch = channel("core.x", ConsumerPermission.TRUSTED_PLUGIN);
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(ch.id()));
    Surface s = surface("core.x-surface", Audience.USER, Placement.STAGE, TrustTier.CORE, consumes);
    var findings = validator.validate(catalogOf(s), List.of(channelCatalog(ch)));
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("OPERATOR"));
  }

  @Test
  @DisplayName("channel floor: consuming CORE channel does NOT lift floor")
  void coreChannelDoesNotLiftFloor() {
    DiagnosticChannel ch = channel("core.unrestricted", ConsumerPermission.CORE);
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(ch.id()));
    Surface s = surface("core.x-surface", Audience.USER, Placement.STAGE, TrustTier.CORE, consumes);
    assertTrue(validator.validate(catalogOf(s), List.of(channelCatalog(ch))).isEmpty());
  }

  @Test
  @DisplayName("channel floor: OPERATOR_OVERRIDE channel + OPERATOR audience is accepted")
  void operatorChannelOperatorAudienceAccepted() {
    DiagnosticChannel ch = channel("core.engine-log", ConsumerPermission.OPERATOR_OVERRIDE);
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(ch.id()));
    Surface s = surface(
        "core.engine-log-surface", Audience.OPERATOR, Placement.STAGE, TrustTier.CORE, consumes);
    assertTrue(validator.validate(catalogOf(s), List.of(channelCatalog(ch))).isEmpty());
  }

  @Test
  @DisplayName("§B.A.1: TRUSTED_PLUGIN surface + OPERATOR_OVERRIDE channel + OPERATOR audience accepted (no false-rejection)")
  void noFalseRejectionForSignedPluginOperator() {
    DiagnosticChannel ch = channel("core.engine-log", ConsumerPermission.OPERATOR_OVERRIDE);
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(ch.id()));
    Surface s = surface(
        "vendor.acme.logs", Audience.OPERATOR, Placement.STAGE, TrustTier.TRUSTED_PLUGIN, consumes);
    assertTrue(validator.validate(catalogOf(s), List.of(channelCatalog(ch))).isEmpty(),
        "Signed-plugin surface declaring OPERATOR for OPERATOR_OVERRIDE data should NOT be rejected");
  }

  // ------------------------------------------------------------------
  // Cross-reference resolution
  // ------------------------------------------------------------------

  @Test
  @DisplayName("cross-ref: consuming an unregistered DiagnosticChannel id produces a finding")
  void unregisteredChannelRefFlagged() {
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(new DiagnosticChannelRef("core.does-not-exist")));
    Surface s = surface("core.x-surface", Audience.OPERATOR, Placement.STAGE, TrustTier.CORE, consumes);
    var findings = validator.validate(catalogOf(s), List.of(channelCatalog()));
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("not registered"));
  }

  @Test
  @DisplayName("cross-ref: simpler validate(catalog) overload skips channel cross-ref check")
  void simpleOverloadSkipsCrossRef() {
    SurfaceConsumes consumes = new SurfaceConsumes(
        Set.of(), Set.of(), Set.of(), Set.of(new DiagnosticChannelRef("core.does-not-exist")));
    Surface s = surface("core.x-surface", Audience.OPERATOR, Placement.STAGE, TrustTier.CORE, consumes);
    // No channelCatalogs supplied; cross-ref check is skipped.
    assertTrue(validator.validate(catalogOf(s)).isEmpty());
  }

  // ------------------------------------------------------------------
  // AGENT audience bypass
  // ------------------------------------------------------------------

  @Test
  @DisplayName("AGENT audience bypasses human-audience comparison")
  void agentAudienceBypassesComparison() {
    Surface s = surface(
        "core.agent-tool", Audience.AGENT, Placement.HEADLESS_AGENT_TOOL, TrustTier.UNTRUSTED_PLUGIN);
    // Even with UNTRUSTED_PLUGIN provenance (which would normally lift floor to OPERATOR),
    // AGENT audience bypasses the comparison.
    assertTrue(validator.validate(catalogOf(s)).isEmpty());
  }

  // ------------------------------------------------------------------
  // Helper methods (sanity)
  // ------------------------------------------------------------------

  @Test
  @DisplayName("audienceFloorFromProvenance: explicit mapping per §0 D2")
  void audienceFloorFromProvenanceMapping() {
    assertSame(Audience.USER, SurfaceAreaValidator.audienceFloorFromProvenance(TrustTier.CORE));
    assertSame(Audience.USER,
        SurfaceAreaValidator.audienceFloorFromProvenance(TrustTier.TRUSTED_PLUGIN));
    assertSame(Audience.OPERATOR,
        SurfaceAreaValidator.audienceFloorFromProvenance(TrustTier.UNTRUSTED_PLUGIN));
  }

  @Test
  @DisplayName("compareAudience: USER < OPERATOR < DEVELOPER")
  void audienceOrdering() {
    assertTrue(SurfaceAreaValidator.compareAudience(Audience.USER, Audience.OPERATOR) < 0);
    assertTrue(SurfaceAreaValidator.compareAudience(Audience.OPERATOR, Audience.DEVELOPER) < 0);
    assertTrue(SurfaceAreaValidator.compareAudience(Audience.USER, Audience.DEVELOPER) < 0);
    assertEquals(0, SurfaceAreaValidator.compareAudience(Audience.USER, Audience.USER));
  }

  @Test
  @DisplayName("compareAudience: AGENT throws (excluded from human ordering)")
  void agentExcludedFromOrdering() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> SurfaceAreaValidator.compareAudience(Audience.AGENT, Audience.USER));
  }
}
