package io.justsearch.app.services.policy;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPolicyWriterTest {

  @TempDir Path tmp;

  private static EffectivePolicy basePolicy(Path machinePath, Path userPath) {
    EffectivePolicy.PolicySource machine =
        new EffectivePolicy.PolicySource(machinePath, false, false, null, null);
    EffectivePolicy.PolicySource user =
        new EffectivePolicy.PolicySource(userPath, false, false, null, null);
    return new EffectivePolicy(
        true,
        true,
        true,
        true,
        false,
        List.of(),
        List.of(),
        "none",
        false,
        machine,
        user,
        false);
  }

  @Test
  void createsUserPolicyWhenMissing() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");
    EffectivePolicy eff = basePolicy(machine, user);

    String shaUpper = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    UserPolicyWriter.Result r = UserPolicyWriter.createUserPolicyForPackImportIfMissing(eff, shaUpper);

    assertNotNull(r);
    assertEquals(user, r.path());
    assertTrue(Files.isRegularFile(user));

    var json = new ObjectMapper().readTree(Files.readString(user));
    assertEquals(1, json.get("schemaVersion").asInt());
    assertTrue(json.has("updatedAt"));
    assertTrue(json.has("allowlists"));
    assertTrue(json.get("allowlists").has("packManifestSha256"));
    assertEquals(1, json.get("allowlists").get("packManifestSha256").size());
    assertEquals(
        shaUpper.toLowerCase(Locale.ROOT),
        json.get("allowlists").get("packManifestSha256").get(0).asText());
  }

  @Test
  void rejectsWhenUserPolicyAlreadyExistsEvenIfEffectivePolicySaysNotPresent() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");
    EffectivePolicy eff = basePolicy(machine, user);

    Files.writeString(user, "{ \"schemaVersion\": 1 }");

    UserPolicyWriter.UserPolicyWriteException e =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () ->
                UserPolicyWriter.createUserPolicyForPackImportIfMissing(
                    eff,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertEquals(409, e.httpStatus());
    assertEquals(ApiErrorCode.USER_POLICY_ALREADY_EXISTS, e.errorCode());
  }

  @Test
  void rejectsWhenMachinePolicyPresent() {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(machine, true, true, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(user, false, false, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "machine",
            false,
            machineSrc,
            userSrc,
            false);

    UserPolicyWriter.UserPolicyWriteException e =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () ->
                UserPolicyWriter.createUserPolicyForPackImportIfMissing(
                    eff,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertEquals(409, e.httpStatus());
    assertEquals(ApiErrorCode.MACHINE_POLICY_PRESENT, e.errorCode());
  }

  @Test
  void rejectsInvalidDigest() {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");
    EffectivePolicy eff = basePolicy(machine, user);

    UserPolicyWriter.UserPolicyWriteException e =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () -> UserPolicyWriter.createUserPolicyForPackImportIfMissing(eff, "nope"));
    assertEquals(400, e.httpStatus());
    assertEquals(ApiErrorCode.PACK_MANIFEST_SHA_INVALID, e.errorCode());
  }

  @Test
  void preservesAppAllowlistWhenCreatingUserPolicy() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    String appSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(machine, false, false, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(user, false, false, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(appSha),
            "app",
            true,
            machineSrc,
            userSrc,
            false);

    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    UserPolicyWriter.createUserPolicyForPackImportIfMissing(eff, want);

    var json = new ObjectMapper().readTree(Files.readString(user));
    var list = json.get("allowlists").get("packManifestSha256");
    assertNotNull(list);
    assertEquals(2, list.size());
    assertEquals(appSha, list.get(0).asText());
    assertEquals(want, list.get(1).asText());
  }

  @Test
  void appendsToExistingUserPolicyPreservingOtherFields() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    Files.writeString(
        user,
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-23T00:00:00Z",
          "downloadsEnabled": false,
          "allowlists": {
            "packManifestSha256": [
              "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ]
          },
          "customField": { "note": "preserve me" }
        }
        """);

    EffectivePolicy eff = basePolicy(machine, user);
    String want = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    var r = UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(eff, want);
    assertNotNull(r);
    assertTrue(r.changed());
    assertEquals(2, r.allowlistedCount());

    var json = new ObjectMapper().readTree(Files.readString(user));
    assertEquals(1, json.get("schemaVersion").asInt());
    assertEquals(false, json.get("downloadsEnabled").asBoolean());
    assertTrue(json.has("customField"));
    assertEquals("preserve me", json.get("customField").get("note").asText());

    var list = json.get("allowlists").get("packManifestSha256");
    assertNotNull(list);
    assertEquals(2, list.size());
    assertEquals(
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        list.get(0).asText());
    assertEquals(want.toLowerCase(Locale.ROOT), list.get(1).asText());
  }

  @Test
  void dedupesAndNormalizesShasWhenAppending() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    String shaUpper = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    Files.writeString(
        user,
        """
        {
          "schemaVersion": 1,
          "allowlists": {
            "packManifestSha256": [
              "%s"
            ]
          }
        }
        """.formatted(shaUpper));

    EffectivePolicy eff = basePolicy(machine, user);
    var r = UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(eff, shaUpper.toLowerCase(Locale.ROOT));
    assertNotNull(r);
    assertFalse(r.changed(), "Appending an existing digest should be a no-op");
    assertEquals(1, r.allowlistedCount());
  }

  @Test
  void preservesAppAllowlistWhenExistingUserAllowlistEmptyAndAppending() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    Files.writeString(
        user,
        """
        {
          "schemaVersion": 1,
          "allowlists": {
            "packManifestSha256": []
          }
        }
        """);

    String appSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(machine, false, false, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(user, true, true, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(appSha),
            "app",
            true,
            machineSrc,
            userSrc,
            false);

    String want = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    var r = UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(eff, want);
    assertTrue(r.changed());
    assertEquals(2, r.allowlistedCount());

    var json = new ObjectMapper().readTree(Files.readString(user));
    var list = json.get("allowlists").get("packManifestSha256");
    assertNotNull(list);
    assertEquals(2, list.size());
    assertEquals(appSha, list.get(0).asText());
    assertEquals(want, list.get(1).asText());
  }

  @Test
  void rejectsAppendWhenMachinePolicyPresent() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    Files.writeString(user, "{ \"schemaVersion\": 1 }");

    EffectivePolicy.PolicySource machineSrc =
        new EffectivePolicy.PolicySource(machine, true, true, null, null);
    EffectivePolicy.PolicySource userSrc =
        new EffectivePolicy.PolicySource(user, true, true, null, null);
    EffectivePolicy eff =
        new EffectivePolicy(
            true,
            true,
            true,
            true,
            false,
            List.of(),
            List.of(),
            "machine",
            false,
            machineSrc,
            userSrc,
            false);

    UserPolicyWriter.UserPolicyWriteException e =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () ->
                UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(
                    eff,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertEquals(409, e.httpStatus());
    assertEquals(ApiErrorCode.MACHINE_POLICY_PRESENT, e.errorCode());
  }

  @Test
  void rejectsInvalidJsonOrUnsupportedSchemaWhenAppending() throws Exception {
    Path machine = tmp.resolve("machine-policy.v1.json");
    Path user = tmp.resolve("policy.v1.json");

    Files.writeString(user, "not json");
    EffectivePolicy eff = basePolicy(machine, user);
    UserPolicyWriter.UserPolicyWriteException badJson =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () ->
                UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(
                    eff,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertEquals(400, badJson.httpStatus());
    assertEquals(ApiErrorCode.USER_POLICY_INVALID_JSON, badJson.errorCode());

    Files.writeString(user, "{ \"schemaVersion\": 2 }");
    UserPolicyWriter.UserPolicyWriteException badSchema =
        assertThrows(
            UserPolicyWriter.UserPolicyWriteException.class,
            () ->
                UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(
                    eff,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertEquals(409, badSchema.httpStatus());
    assertEquals(ApiErrorCode.USER_POLICY_UNSUPPORTED_SCHEMA, badSchema.errorCode());
  }
}
