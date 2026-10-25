/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.excludes;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An exact filesystem policy entry. Legacy strings always remain globs, whatever their spelling. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LiteralExclusion(
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) Literal literal) {
  public enum Scope { FILE, FOLDER }

  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Literal(
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) int version,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String id,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String path,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) Scope scope) {
    public Literal {
      if (version != 1) throw new IllegalArgumentException("Unsupported exact-path policy version");
      Objects.requireNonNull(id, "id");
      if (!UUID.fromString(id).toString().equals(id)) {
        throw new IllegalArgumentException("Invalid exact-path policy identity");
      }
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(scope, "scope");
      Path parsed = Path.of(path);
      if (!parsed.isAbsolute() || !parsed.normalize().toString().equals(path)) {
        throw new IllegalArgumentException("Exact-path policy requires a normalized absolute path");
      }
    }
  }

  public LiteralExclusion { Objects.requireNonNull(literal, "literal"); }

  /** Called only by the server's accepted forget preparation, never by public settings mutation. */
  public static LiteralExclusion create(Path path, Scope scope) {
    return new LiteralExclusion(new Literal(1, UUID.randomUUID().toString(),
        path.toAbsolutePath().normalize().toString(), scope));
  }

  /** Strictly decode the JSON object arm without claiming a legacy string namespace. */
  public static LiteralExclusion fromEntry(Object entry) {
    if (entry instanceof LiteralExclusion literal) return literal;
    if (!(entry instanceof Map<?, ?> outer) || outer.size() != 1
        || !(outer.get("literal") instanceof Map<?, ?> fields) || fields.size() != 4
        || !(fields.get("version") instanceof Number version)
        || !(version instanceof Integer || version instanceof Long || version instanceof java.math.BigInteger)
        || !version.toString().equals("1")
        || !(fields.get("id") instanceof String id)
        || !(fields.get("path") instanceof String path)
        || !(fields.get("scope") instanceof String scope)) {
      throw new IllegalArgumentException("Invalid exact-path policy entry");
    }
    return new LiteralExclusion(new Literal(1, id, path, Scope.valueOf(scope)));
  }

  /** Same absolute/normalize/separator/case rules as the Worker's filesystem ingestion key. */
  public boolean matches(Path candidate, boolean windows) {
    String expected = key(literal.path(), windows);
    String observed = key(candidate.toAbsolutePath().normalize().toString(), windows);
    if (observed.equals(expected)) return true;
    String separator = windows ? "\\" : "/";
    return literal.scope() == Scope.FOLDER
        && observed.startsWith(expected.endsWith(separator) ? expected : expected + separator);
  }

  private static String key(String path, boolean windows) {
    return windows ? path.replace('/', '\\').toLowerCase(Locale.ROOT) : path;
  }
}
