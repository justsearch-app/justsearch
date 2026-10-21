package io.justsearch.app.services.ai.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackAllowlistServiceTest {

  @Test
  void machineAllowlistIsAuthoritativeWhenNonEmpty() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String other = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    EnterprisePolicy machine = new EnterprisePolicy();
    machine.schemaVersion = 1;
    machine.allowlists = new EnterprisePolicy.Allowlists();
    machine.allowlists.packManifestSha256 = List.of(other);

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), true, true, null, machine);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), false, false, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of(want));
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertFalse(d.allowed());
    assertEquals("PACK_NOT_ALLOWLISTED_BY_MACHINE_POLICY", d.errorCode());
  }

  @Test
  void machineAllowlistEmptyDeniesAllWhenMachinePolicyPresent() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    EnterprisePolicy machine = new EnterprisePolicy();
    machine.schemaVersion = 1;
    machine.allowlists = new EnterprisePolicy.Allowlists();
    machine.allowlists.packManifestSha256 = List.of(); // empty => not authoritative

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), true, true, null, machine);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), false, false, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of(want));
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertFalse(d.allowed());
    assertEquals("PACK_NOT_ALLOWLISTED_BY_MACHINE_POLICY", d.errorCode());
  }

  @Test
  void userAllowlistFurtherRestrictsWhenNonEmpty() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String deniedByUser = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    EnterprisePolicy user = new EnterprisePolicy();
    user.schemaVersion = 1;
    user.allowlists = new EnterprisePolicy.Allowlists();
    user.allowlists.packManifestSha256 = List.of(deniedByUser);

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), false, false, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), true, true, null, user);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of(want));
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertFalse(d.allowed());
    assertEquals("PACK_NOT_ALLOWLISTED_BY_USER_POLICY", d.errorCode());
  }

  @Test
  void machineAbsentUserAllowlistCanBePrimaryForPowerUserInstalls() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    EnterprisePolicy user = new EnterprisePolicy();
    user.schemaVersion = 1;
    user.allowlists = new EnterprisePolicy.Allowlists();
    user.allowlists.packManifestSha256 = List.of(want);

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), false, false, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), true, true, null, user);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of());
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertTrue(d.allowed());
  }

  @Test
  void fallsBackToAppAllowlistWhenMachineAbsentAndUserAllowlistEmpty() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    EnterprisePolicy user = new EnterprisePolicy();
    user.schemaVersion = 1;
    user.allowlists = new EnterprisePolicy.Allowlists();
    user.allowlists.packManifestSha256 = List.of(); // empty => not primary

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), false, false, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), true, true, null, user);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of(want));
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertTrue(d.allowed());
  }

  @Test
  void userAllowlistIntersectsWithMachinePolicyWhenMachinePolicyPresent() {
    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    EnterprisePolicy machine = new EnterprisePolicy();
    machine.schemaVersion = 1;
    machine.allowlists = new EnterprisePolicy.Allowlists();
    machine.allowlists.packManifestSha256 = List.of(want);

    EnterprisePolicy user = new EnterprisePolicy();
    user.schemaVersion = 1;
    user.allowlists = new EnterprisePolicy.Allowlists();
    user.allowlists.packManifestSha256 = List.of(); // empty => doesn't further restrict

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\ProgramData\\JustSearch\\policy.v1.json"), true, true, null, machine);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(Path.of("C:\\Users\\x\\policy.v1.json"), true, true, null, user);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);

    PackAllowlistService svc = new PackAllowlistService(Set.of());
    PackAllowlistService.Decision d = svc.evaluatePackManifestSha256(want, eff);
    assertTrue(d.allowed());

    // Now restrict via user policy.
    user.allowlists.packManifestSha256 = List.of("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    EffectivePolicy eff2 =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "none",
            false,
            machineSrc,
            userSrc,
            false);
    PackAllowlistService.Decision d2 = svc.evaluatePackManifestSha256(want, eff2);
    assertFalse(d2.allowed());
    assertEquals("PACK_NOT_ALLOWLISTED_BY_USER_POLICY", d2.errorCode());
  }
}
