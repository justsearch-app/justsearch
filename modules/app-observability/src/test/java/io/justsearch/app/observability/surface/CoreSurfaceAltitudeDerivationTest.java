package io.justsearch.app.observability.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Altitude;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceAltitude;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.app.observability.health.ConditionRecoveryIndexCatalog;
import io.justsearch.app.observability.health.HealthResourceCatalog;
import io.justsearch.app.observability.indexing.FailedIndexingJobsResourceCatalog;
import io.justsearch.app.observability.indexing.IndexedRootsResourceCatalog;
import io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 571 §4c — the {@code audit-without-test} guard for altitude derivation: proves
 * {@link SurfaceAltitude#derive} reproduces every core surface's intended altitude from the authority
 * it consumes (Resource {@link Role}s + DiagnosticChannels), with NO altitude declared in the catalog.
 * If this passes, "the wire altitude is a faithful projection of consumption" is a tested property, not
 * a hypothesis.
 */
final class CoreSurfaceAltitudeDerivationTest {

  /** Role index over every Resource any core surface consumes — the derivation input. */
  private static Map<ResourceRef, Role> roleIndex() {
    final List<ResourceCatalog> catalogs =
        List.of(
            new IndexedRootsResourceCatalog(),
            new HealthResourceCatalog(),
            new ConditionRecoveryIndexCatalog(),
            new FailedIndexingJobsResourceCatalog(),
            new ActionLedgerResourceCatalog());
    final Map<ResourceRef, Role> index = new LinkedHashMap<>();
    for (final ResourceCatalog c : catalogs) {
      for (final Resource r : c.definitions()) {
        index.putIfAbsent(r.id(), r.role());
      }
    }
    return index;
  }

  @Test
  @DisplayName("every core surface derives its intended altitude from consumed authority (no catalog declaration)")
  void deriveReproducesEveryCoreSurfaceAltitude() {
    final Map<ResourceRef, Role> roles = roleIndex();
    // The only non-PRODUCT surfaces; everything else derives PRODUCT (the benign default).
    // Tempdoc 571 §11 / 578 — core.system-surface (the hub) has EMPTY consumes ⟹ PRODUCT (composes, does
    // not fuse; post-review honesty fix — it no longer fake-consumes the engine-log channel). Its members
    // (Health/Logs DIAGNOSTIC, Activity TRUST) are separate surfaces that derive their own altitude.
    final Map<String, Altitude> expected =
        Map.of(
            "core.health-surface", Altitude.DIAGNOSTIC,
            "core.logs-surface", Altitude.DIAGNOSTIC,
            "core.activity-surface", Altitude.TRUST);

    final List<Surface> surfaces = new CoreSurfaceCatalog().definitions();
    int diagnostic = 0;
    int trust = 0;
    for (final Surface s : surfaces) {
      final SurfaceAltitude.Derivation d = SurfaceAltitude.derive(s, roles);
      assertFalse(
          d.conflict(),
          () -> "surface " + s.id().value() + " unexpectedly conflicts: signals=" + d.signals());
      final Altitude want = expected.getOrDefault(s.id().value(), Altitude.PRODUCT);
      assertEquals(
          want,
          d.altitude(),
          () -> "surface " + s.id().value() + " should derive " + want + " from its consumption");
      if (d.altitude() == Altitude.DIAGNOSTIC) diagnostic++;
      if (d.altitude() == Altitude.TRUST) trust++;
    }
    // Sanity: the derivation actually exercised the non-trivial branches (not all-PRODUCT by accident).
    assertEquals(2, diagnostic, "expected exactly Health + Logs to derive DIAGNOSTIC");
    assertEquals(1, trust, "expected exactly Activity to derive TRUST");
  }

  @Test
  @DisplayName("a surface consuming two distinct non-PRODUCT authorities is a derivation conflict")
  void twoNonProductAuthoritiesConflict() {
    final Map<ResourceRef, Role> roles =
        Map.of(
            new ResourceRef("core.health-events"), Role.DIAGNOSTIC,
            new ResourceRef("core.action-ledger"), Role.TRUST);
    final Surface contradictory =
        new Surface(
            new SurfaceRef("core.bad-surface"),
            Presentation.of(new I18nKey("x"), new I18nKey("y")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                Set.of(
                    new ResourceRef("core.health-events"), new ResourceRef("core.action-ledger")),
                Set.of(),
                Set.of(),
                Set.of()),
            "jf-bad-surface",
            Provenance.core("1.0"));
    final SurfaceAltitude.Derivation d = SurfaceAltitude.derive(contradictory, roles);
    assertTrue(d.conflict(), "consuming a DIAGNOSTIC + a TRUST Resource must conflict");
    assertEquals(
        Altitude.PRODUCT, d.altitude(), "a conflict falls back to the benign PRODUCT on the wire");
    assertEquals(Set.of(Altitude.DIAGNOSTIC, Altitude.TRUST), d.signals());
  }

  @Test
  @DisplayName(
      "578 §10 U1: members are NOT an input to derive — a host with members + empty consumes stays"
          + " PRODUCT, and members never change a surface's derived altitude")
  void membersDoNotAffectAltitudeDerivation() {
    final Map<ResourceRef, Role> roles =
        Map.of(new ResourceRef("core.engine-log-events"), Role.DIAGNOSTIC);

    // A host that declares members but consumes NOTHING derives PRODUCT — it does not inherit or
    // aggregate its members' altitudes (the load-bearing cross-altitude-composition invariant: a
    // PRODUCT/DIAGNOSTIC host may host a TRUST member without a derivation conflict).
    final Surface pureHost =
        new Surface(
                new SurfaceRef("core.pure-host"),
                Presentation.of(new I18nKey("x"), new I18nKey("y")),
                Audience.USER,
                Placement.RAIL,
                SurfaceConsumes.empty(),
                "jf-pure-host",
                Provenance.core("1.0"))
            .withMembers(
                List.of(
                    new SurfaceRef("core.health-surface"),
                    new SurfaceRef("core.logs-surface"),
                    new SurfaceRef("core.activity-surface")));
    final SurfaceAltitude.Derivation hostD = SurfaceAltitude.derive(pureHost, roles);
    assertFalse(hostD.conflict(), "a host that consumes nothing must not conflict regardless of members");
    assertEquals(
        Altitude.PRODUCT,
        hostD.altitude(),
        "a host with members but empty consumes derives PRODUCT — members are not an altitude input");

    // Adding members to a surface that DOES consume a diagnostic channel must not change its altitude:
    // the derived value is identical with and without members.
    final SurfaceConsumes diagConsumes =
        new SurfaceConsumes(
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(new io.justsearch.agent.api.registry.DiagnosticChannelRef("core.engine-log")));
    final Surface withoutMembers =
        new Surface(
            new SurfaceRef("core.diag-host"),
            Presentation.of(new I18nKey("x"), new I18nKey("y")),
            Audience.USER,
            Placement.RAIL,
            diagConsumes,
            "jf-diag-host",
            Provenance.core("1.0"));
    final Surface withMembers =
        withoutMembers.withMembers(List.of(new SurfaceRef("core.activity-surface")));
    assertEquals(
        SurfaceAltitude.derive(withoutMembers, roles).altitude(),
        SurfaceAltitude.derive(withMembers, roles).altitude(),
        "declaring members must not change a surface's derived altitude");
    assertEquals(Altitude.DIAGNOSTIC, SurfaceAltitude.derive(withMembers, roles).altitude());
  }
}
