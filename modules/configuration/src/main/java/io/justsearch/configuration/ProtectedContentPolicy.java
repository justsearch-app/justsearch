/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Always-on file protection shared by live admission and offline index upgrade removal. */
public final class ProtectedContentPolicy {
  /** Bump when the always-on rules change, to repeat the upgrade removal. */
  public static final String VERSION = "3";
  private static final Set<String> PROTECTED_NAMES = Set.of(
      ".env", "keystore", "keyring", "wallet.dat", "terraform.tfvars", ".netrc", "_netrc",
      ".pgpass", ".htpasswd", ".npmrc", ".pypirc", ".dockercfg", ".git-credentials",
      "kubeconfig", "master.key", "credentials.xml", "login data", "login data-journal",
      "web data", "cookies", "logins.json", "key3.db", "key4.db", "cert9.db", "signons.sqlite",
      ".bash_history", ".zsh_history", "consolehost_history.txt", ".mysql_history", ".psql_history");
  private static final Set<String> PROTECTED_SUFFIXES = Set.of(
      ".ppk", ".pem", ".key", ".pfx", ".p12", ".pkcs12", ".jks", ".keystore", ".gpg",
      ".pgp", ".asc", ".kdb", ".kdbx", ".psafe3", ".1pux", ".keychain", ".kwallet",
      ".ovpn", ".rdp", ".tfstate", ".tfstate.backup");
  private static final Set<String> CREDENTIAL_DIRECTORIES = Set.of(
      ".ssh", ".aws", ".gnupg", ".docker", ".kube", ".azure", ".password-store",
      "ssh", "aws", "gnupg");
  private static final Set<String> KEY_PREFIXES = Set.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");


  private final Path dataDirectory;
  private final Path helpDirectory;

  public ProtectedContentPolicy(Path dataDirectory, Path helpDirectory) {
    this.dataDirectory = dataDirectory == null ? null : dataDirectory.toAbsolutePath().normalize();
    this.helpDirectory = helpDirectory == null ? null : helpDirectory.toAbsolutePath().normalize();
  }

  public static boolean protectedName(String name) {
    return name.startsWith(".env.") || PROTECTED_NAMES.contains(name) || PROTECTED_SUFFIXES.stream().anyMatch(name::endsWith)
        || KEY_PREFIXES.stream().anyMatch(name::startsWith) || name.startsWith("secring.");
  }

  public static boolean credentialDirectory(String name) {
    return CREDENTIAL_DIRECTORIES.contains(name);
  }

  public enum Verdict { PROTECTED, ORDINARY, UNCERTAIN }
  /** App-owned sources retain the configured data namespace used by transcript IDs. */
  public record Classification(Verdict verdict, Path source) {}

  /** Only unresolved legacy history-shaped sources block completion. Ordinary offline sources
   * keep lexical protection and are never deleted merely because identity resolution failed. */
  public Classification classify(Path path, Path root, String collection) {
    if (protectedPath(path, root, collection)) return new Classification(Verdict.PROTECTED, path);
    if (path == null) return new Classification(Verdict.ORDINARY, null);
    try {
      Path resolved = path.toRealPath();
      Path resolvedRoot = root == null ? resolved : realOrLexical(root);
      Path realData = realOrLexical(dataDirectory);
      var resolvedPolicy = new ProtectedContentPolicy(realData, realOrLexical(helpDirectory));
      Path source = realData != null && resolved.startsWith(realData)
          ? dataDirectory.resolve(realData.relativize(resolved)) : resolved;
      return new Classification(resolvedPolicy.protectedPath(resolved, resolvedRoot, collection)
          ? Verdict.PROTECTED : Verdict.ORDINARY, source);
    } catch (java.io.IOException | SecurityException unavailable) {
      return new Classification(ReadOwnership.legacyHistorySource(path.toString(), collection)
          ? Verdict.UNCERTAIN : Verdict.ORDINARY, path);
    }
  }

  /** Live admission handles unavailable sources separately from policy rejection. */
  public boolean protectedLivePath(Path path, Path root, String collection) {
    return classify(path, root, collection).verdict() == Verdict.PROTECTED;
  }

  private static Path realOrLexical(Path path) {
    if (path == null) return null;
    try { return path.toRealPath(); }
    catch (java.io.IOException unavailable) { return path; }
  }

  /** No filesystem reads: offline sources are classified by the same rules as live admissions. */
  public boolean protectedPath(Path path, Path root, String collection) {
    if (path == null || path.getFileName() == null) return false;
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (protectedName(name)) return true;
    Path absolute = path.toAbsolutePath().normalize();
    Path boundary = root == null ? absolute : root.toAbsolutePath().normalize();
    if (dataDirectory != null && absolute.startsWith(dataDirectory)) {
      String normalizedCollection = collection == null ? "" : collection.trim().toLowerCase(Locale.ROOT);
      Path ownFolder = InternalCollections.AGENT_HISTORY.equals(normalizedCollection)
          ? dataDirectory.resolve("agent-history")
          : InternalCollections.HELP.equals(normalizedCollection) ? helpDirectory : null;
      if (ownFolder == null || !absolute.startsWith(ownFolder)) return true;
    }
    for (Path parent = absolute.getParent(); parent != null; parent = parent.getParent()) {
      if (parent.getFileName() == null) continue;
      String directory = parent.getFileName().toString().toLowerCase(Locale.ROOT);
      if (CREDENTIAL_DIRECTORIES.contains(directory)) return true;
      if (directory.equals("gcloud") && parent.getParent() != null
          && parent.getParent().getFileName() != null
          && Set.of("config", ".config").contains(parent.getParent().getFileName().toString().toLowerCase(Locale.ROOT))) return true;
      if (parent.startsWith(boundary) && !parent.equals(boundary) && directory.startsWith(".")) return true;
    }
    return false;
  }

}
