/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.configuration.excludes.LiteralExclusion;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Server-trusted metadata, retained privately in this forget's accepted preparation. */
public record ForgetPlan(int version, String path, LiteralExclusion.Scope scope,
    LiteralExclusion keepOut, List<WatchedRoot> watchedRoots, Source source) {
  public static final String SCHEMA = "forget-plan.v1";
  public static final String OPERATION_ID = "core.forget-indexed-path";
  public enum Source { FILE_SYSTEM }
  /** Public intent contains no trusted source classification, root witness or policy token. */
  public record Request(String path, LiteralExclusion.Scope scope) {
    public Request {
      Path parsed = Path.of(Objects.requireNonNull(path, "path"));
      if (!parsed.isAbsolute()) throw new IllegalArgumentException("Forget requires an absolute path");
      path = parsed.normalize().toString();
      Objects.requireNonNull(scope, "scope");
    }
  }
  public record WatchedRoot(String pathHash, String path) {
    public WatchedRoot {
      if (pathHash == null || !pathHash.matches("[a-fA-F0-9]{16,128}")) {
        throw new IllegalArgumentException("Invalid watched-root identity");
      }
      Path parsed = Path.of(Objects.requireNonNull(path, "path"));
      if (!parsed.isAbsolute() || !parsed.normalize().toString().equals(path)) {
        throw new IllegalArgumentException("Invalid watched-root path");
      }
    }
  }
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  public ForgetPlan {
    if (version != 1) throw new IllegalArgumentException("Unsupported forget plan");
    requireAbsolute(path);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(source, "source");
    watchedRoots = List.copyOf(watchedRoots);
    if (watchedRoots.size() > 512 || watchedRoots.stream().distinct().count() != watchedRoots.size()) {
      throw new IllegalArgumentException("Invalid watched-root observation");
    }
    boolean required = scope == LiteralExclusion.Scope.FOLDER || !watchedRoots.isEmpty();
    if (required != (keepOut != null) || keepOut != null
        && (!keepOut.literal().path().equals(path) || keepOut.literal().scope() != scope)) {
      throw new IllegalArgumentException("Forget keep-out scope mismatch");
    }
  }

  public boolean keepOutRequired() { return keepOut != null; }

  public OperationPreparation prepare(String argumentsJson) {
    return new OperationPreparation(argumentsJson, SCHEMA, JSON.writeValueAsString(this));
  }

  public static Request request(String argumentsJson) {
    try { return JSON.readValue(argumentsJson, Request.class); }
    catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid forget target"); }
  }

  public static ForgetPlan validate(OperationPreparation preparation) {
    try {
      if (!SCHEMA.equals(preparation.replaySchema())
          || preparation.content() != OperationPreparation.Content.METADATA) {
        throw new IllegalArgumentException("Forget preparation schema mismatch");
      }
      ForgetPlan plan = JSON.readValue(preparation.replayPayloadJson(), ForgetPlan.class);
      Request intent = request(preparation.argumentsJson());
      if (!intent.path().equals(plan.path()) || intent.scope() != plan.scope()) {
        throw new IllegalArgumentException("Forget target differs from accepted public intent");
      }
      return plan;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid forget preparation");
    }
  }

  private static void requireAbsolute(String raw) {
    Path path = Path.of(Objects.requireNonNull(raw, "path"));
    if (!path.isAbsolute() || !path.normalize().toString().equals(raw) || path.getParent() == null) {
      throw new IllegalArgumentException("Forget requires a normalized absolute non-root path");
    }
  }

  @Override public String toString() { return "ForgetPlan[version=" + version + ", scope=" + scope + "]"; }
}
