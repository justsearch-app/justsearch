/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

final class ProtectedIngestionSkipPolicyTest {
  @AfterEach void reset() { IngestionSkipPolicy.resetToDefaults(); }
  @Test
  void fixedRulesSurviveOperatorOverridesAndSingleFileBoundaries() {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(Set.of("custom"), Set.of("foo"), Set.of("vendor")));
    Path root = Path.of("/documents/.notes");
    for (String name : java.util.List.of(".env", "ID_RSA.pub", "secret.PEM", "vault.kdbx", "Login Data", "terraform.tfstate.backup", "credentials.xml")) {
      Path file = root.resolve(name);
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, root), name);
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, file), name);
    }
    assertFalse(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("notes.md"), root));
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve(".hidden/notes.md"), root));
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("vendor/notes.md"), root));
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("custom.md"), root));
  }

  @Test
  void credentialAncestorsAreProtectedAboveEveryBoundary() {
    for (String directory : java.util.List.of(".ssh", ".aws", ".gnupg", ".docker", ".kube", ".azure", ".password-store", ".config/gcloud", "ssh", "aws", "gnupg")) {
      Path root = Path.of("/home/user").resolve(directory);
      Path file = root.resolve("nested/ordinary.txt");
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, root), directory);
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, file.getParent()), directory);
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, file), directory);
    }
  }

  @Test
  void dataDirectoryOnlyAllowsItsOwnReservedProjection() {
    Path data = Path.of("/application/data");
    try {
      IngestionSkipPolicy.installResolved((IngestionSkipPolicy) IngestionSkipPolicy.class
          .getMethod("withDataDirectory", Path.class).invoke(IngestionSkipPolicy.defaultPolicy(), data));
    } catch (NoSuchMethodException baselineHasNoDataFolderRule) {
      IngestionSkipPolicy.resetToDefaults();
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    Path transcript = data.resolve("agent-history/run.md");
    assertTrue(skips(transcript, transcript, "personal"));
    assertTrue(skips(data.resolve("index/content.txt"), data, "agent-history"));
    assertFalse(skips(transcript, transcript, "agent-history"));
    assertTrue(skips(data.resolve("agent-history/secret.key"), data, "agent-history"));
    assertFalse(skips(data.resolve("help/search.md"), data, "justsearch-help"));
  }

  @Test
  void allProtectedNamesAreCaseInsensitiveAndCannotBeDisabled() {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(Set.of("operator"), Set.of("foo"), Set.of("vendor")));
    String names = ".env id_rsa id_rsa.pub id_dsa.old id_ecdsa id_ed25519 test.ppk test.pem test.key test.pfx test.p12 test.pkcs12 test.jks test.keystore keystore keyring secring.skr test.gpg test.pgp test.asc test.kdb test.kdbx test.psafe3 test.1pux test.keychain test.kwallet wallet.dat test.ovpn test.rdp test.tfstate test.tfstate.backup terraform.tfvars .netrc _netrc .pgpass .htpasswd .npmrc .pypirc .dockercfg .git-credentials kubeconfig master.key credentials.xml cookies logins.json key3.db key4.db cert9.db signons.sqlite .bash_history .zsh_history consolehost_history.txt .mysql_history .psql_history";
    var values = new java.util.ArrayList<>(java.util.List.of(names.split(" ")));
    values.addAll(java.util.List.of("login data", "login data-journal", "web data"));
    Path root = Path.of("/documents/.notes");
    for (String name : values) {
      Path file = root.resolve(name.toUpperCase(java.util.Locale.ROOT));
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, root), name);
      assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(file, file), name + " single-file");
    }
    assertFalse(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("ordinary.md"), root));
    assertFalse(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve(".gitignore"), root));
  }

  @Test
  void resolvedAncestorsCannotAliasProtectedLocations(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path data = java.nio.file.Files.createDirectories(directory.resolve("data"));
    java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(data.resolve("agent-history")).resolve("run.md"), "secret");
    Path alias = java.nio.file.Files.createSymbolicLink(directory.resolve("alias"), data);
    IngestionSkipPolicy.installResolved(IngestionSkipPolicy.defaultPolicy().withDataDirectory(data));
    Path throughAlias = alias.resolve("agent-history/run.md");
    assertTrue(skips(throughAlias, throughAlias, "public"));
    Path credentials = java.nio.file.Files.createDirectories(directory.resolve(".aws/nested"));
    java.nio.file.Files.writeString(credentials.resolve("settings.txt"), "secret");
    Path credentialAlias = java.nio.file.Files.createSymbolicLink(directory.resolve("creds"), credentials);
    Path file = credentialAlias.resolve("settings.txt");
    assertTrue(skips(file, file, null));
    Path ordinary = java.nio.file.Files.createDirectories(directory.resolve("ordinary"));
    java.nio.file.Files.writeString(ordinary.resolve("settings.txt"), "ordinary");
    Path ordinaryAlias = java.nio.file.Files.createSymbolicLink(directory.resolve("public"), ordinary);
    assertFalse(skips(ordinaryAlias.resolve("settings.txt"), ordinaryAlias, null));
    // Re-evaluate at publication: the same lexical source can change its target after extraction.
    java.nio.file.Files.delete(ordinaryAlias);
    java.nio.file.Files.createSymbolicLink(ordinaryAlias, credentials);
    assertTrue(skips(ordinaryAlias.resolve("settings.txt"), ordinaryAlias, null));
  }

  private static boolean skips(Path file, Path root, String collection) {
    try { return (boolean) IngestionSkipPolicy.class.getMethod("shouldSkipWithinRoot", Path.class,
        Path.class, String.class).invoke(null, file, root, collection); }
    catch (NoSuchMethodException baselineHasNoCollectionAdmission) {
      return IngestionSkipPolicy.shouldSkipWithinRoot(file, root);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

}
