/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and deserializes the v2 model registry from JSON.
 *
 * <p>The registry JSON uses a flat structure that maps directly to the Layer 1 data model types.
 * This loader handles the JSON ↔ Java mapping, including enum deserialization for {@link
 * ModelPrecision} and {@link ExecutionProvider}.
 */
public final class ModelRegistryLoader {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private ModelRegistryLoader() {}

  /** Loads the registry from a classpath resource. */
  public static ModelRegistry loadFromClasspath(String resourcePath) {
    // Use this class's own loader, not the thread's context classloader: the registry JSON now
    // ships as a resource inside this module (modules/configuration/src/main/resources/ai/), so
    // it is guaranteed to be visible here regardless of which classloader happens to be current
    // on the calling thread.
    try (InputStream is = ModelRegistryLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Resource not found: " + resourcePath);
      }
      return parseJson(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load model registry from classpath: " + resourcePath, e);
    }
  }

  /** Loads the registry from a file path. */
  public static ModelRegistry loadFromFile(Path path) {
    try (InputStream is = Files.newInputStream(path)) {
      return parseJson(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load model registry from file: " + path, e);
    }
  }

  private static ModelRegistry parseJson(InputStream is) {
    RawRegistry raw = JSON.readValue(is, RawRegistry.class);
    if (raw.schemaVersion != 2) {
      throw new IllegalStateException(
          "Unsupported registry schema version: " + raw.schemaVersion + " (expected 2)");
    }
    // Native replacements have distinct identities. Resolve them before dependency lookup,
    // so Windows keeps precisely its existing packages and persisted package ids stay stable.
    var replacements = new java.util.HashMap<String, String>();
    var ids = new java.util.HashSet<String>();
    if (raw.packages != null) {
      for (RawPackage pkg : raw.packages) {
        if (!ids.add(pkg.id)) throw new IllegalStateException("Duplicate package id: " + pkg.id);
        if (pkg.nativeTarget != null) io.justsearch.configuration.NativeTargets.forTarget(pkg.nativeTarget);
        if (pkg.replaces != null && (pkg.replaces.equals(pkg.id) || pkg.nativeTarget == null
            || CapabilityTier.fromId(pkg.tier) != CapabilityTier.RUNTIME)) {
          throw new IllegalStateException("Invalid native runtime replacement: " + pkg.id);
        }
        if (pkg.replaces != null
            && io.justsearch.configuration.NativeTargets.current().targetId().equals(pkg.nativeTarget)) {
          if (replacements.put(pkg.replaces, pkg.id) != null) {
            throw new IllegalStateException("Duplicate native replacement: " + pkg.replaces);
          }
        }
      }
    }
    for (String replaced : replacements.keySet()) {
      if (!ids.contains(replaced)) throw new IllegalStateException("Missing replaced package: " + replaced);
    }
    List<ModelPackage> packages = new ArrayList<>();
    if (raw.packages != null) {
      for (RawPackage rp : raw.packages) {
        if (replacements.containsKey(rp.id)
            || (rp.replaces != null && !replacements.containsValue(rp.id))) continue;
        packages.add(convertPackage(rp, replacements));
      }
    }
    return new ModelRegistry(raw.schemaVersion, raw.purpose, packages);
  }

  private static ModelPackage convertPackage(RawPackage rp, java.util.Map<String, String> replacements) {
    List<ModelVariant> variants = new ArrayList<>();
    if (rp.variants != null) {
      for (RawVariant rv : rp.variants) {
        variants.add(
            new ModelVariant(
                rv.filename,
                ModelPrecision.valueOf(rv.precision),
                ExecutionProvider.valueOf(rv.targetEP),
                rv.sha256,
                rv.sizeBytes,
                rv.downloadUrl));
      }
    }
    List<SupportingFile> supporting = new ArrayList<>();
    if (rp.supportingFiles != null) {
      for (RawSupportingFile rs : rp.supportingFiles) {
        // extract: optional flag (default false) — alpha.15 archive support.
        boolean extract = rs.extract != null && rs.extract;
        // required: optional flag defaulting to TRUE (tempdoc 824 §3.3a). Absent must mean
        // required, so a registry written before the axis existed — and any entry whose author
        // did not classify it — keeps producing the full-strength missing-file verdict.
        boolean required = rs.required == null || rs.required;
        supporting.add(
            new SupportingFile(
                rs.filename, rs.sha256, rs.sizeBytes, rs.downloadUrl, extract, required));
      }
    }
    // requiresCuda: optional flag (default false) — tempdoc 772 Q3. Absent in a pre-772 registry
    // JSON deserializes to null here; without this pass-through the loader would call the 11-arg
    // compat constructor and default requiresCuda=false for EVERY package, silently un-gating the
    // real cuda-runtime package from its CUDA requirement in production. The registry JSON carries
    // "requiresCuda": true on cuda-runtime to preserve today's hardware gating.
    boolean requiresCuda = rp.requiresCuda != null && rp.requiresCuda;
    // devOnly: optional flag (default false) — tempdoc 842. Same silent-failure shape as
    // requiresCuda above: FAIL_ON_UNKNOWN_PROPERTIES is disabled, so dropping this pass-through
    // costs no error and instead puts the dev-only chat-compact package into every user's
    // install plan.
    boolean devOnly = rp.devOnly != null && rp.devOnly;
    // necessity: optional field defaulting to REQUIRED — tempdoc 840 Phase 2, the same fail-closed
    // rule as `required` above and for the same reason. Necessity.fromId returns null for an ABSENT,
    // blank OR unrecognized value, and all three land on REQUIRED here: a package nobody classified
    // (a pre-840 registry, a typo, a necessity name a newer registry uses and this build does not
    // know) must stay mandatory rather than become silently switch-off-able by a user decline.
    // Defaulting the other way would let a registry edit turn `embedding` into an optional extra.
    Necessity necessity = Necessity.fromId(rp.necessity);
    if (necessity == null) {
      necessity = Necessity.REQUIRED;
    }
    // dependsOn: optional, absent ⇒ no declared dependency. Deliberately NOT fail-closed the other
    // way (inventing an edge nobody declared would skip packages the registry wants installed); the
    // H1 invariant it makes checkable is asserted against the shipped registry, not defaulted in.
    List<String> dependsOn = rp.dependsOn == null ? List.of() : rp.dependsOn.stream().map(id -> replacements.getOrDefault(id, id)).toList();
    return new ModelPackage(
        rp.id,
        rp.label,
        rp.description,
        rp.targetDir,
        variants,
        supporting,
        rp.minVramBytes,
        rp.termsUrl,
        rp.installRoot,
        rp.license,
        CapabilityTier.fromId(rp.tier),
        requiresCuda,
        devOnly,
        necessity,
        dependsOn, rp.nativeTarget);
  }

  // Raw deserialization types — match the JSON structure exactly.
  // These are intermediate; the public API returns Layer 1 types.

  private record RawRegistry(int schemaVersion, String purpose, List<RawPackage> packages) {}

  private record RawPackage(
      String id,
      String label,
      String description,
      String targetDir,
      String termsUrl,
      long minVramBytes,
      List<RawVariant> variants,
      List<RawSupportingFile> supportingFiles,
      String installRoot,
      String license,
      String tier,
      Boolean requiresCuda,
      Boolean devOnly,
      String necessity,
      List<String> dependsOn,
      String nativeTarget,
      String replaces) {}

  private record RawVariant(
      String filename,
      String precision,
      String targetEP,
      String sha256,
      long sizeBytes,
      String downloadUrl) {}

  private record RawSupportingFile(
      String filename,
      String sha256,
      long sizeBytes,
      String downloadUrl,
      Boolean extract,
      Boolean required) {}
}
