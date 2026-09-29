/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import io.justsearch.configuration.model.ModelPrecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves a {@link ModelCapabilities} value for a model directory. Tempdoc 710 Wave 2 Move 1:
 * the single production entry point for model-intrinsic facts — no encoder parses sidecar JSON
 * itself anymore.
 *
 * <p>Per-fact source priority (S-C.R, extended by tempdoc 711 Item 3): manifest {@code
 * capabilities} field → embedded ONNX {@code metadata_props} (reverse-DNS {@code io.justsearch.*}
 * keys stamped at model-build time — {@code scripts/models/_common.py stamp_capabilities}, a
 * projection of the same manifest) → ecosystem files where authoritative → boot-time graph probe
 * (dimension only; precision is sanity-checked, never sourced, against I/O dtype — S-C.R: "no
 * ecosystem field is authoritative for an exported ONNX file's precision") → legacy sidecar
 * (deprecated fallback generation) → no default. When both the manifest and the embedded metadata
 * declare a fact and they disagree, the manifest wins and a WARN names both values — a manifest
 * hand-edited after the model was last stamped is the expected drift case this catches. Every gap
 * that falls through to a fallback (or bottoms out with nothing) is recorded in {@link
 * ModelCapabilities#warnings()} and logged at WARN — mirroring {@code
 * InferenceCompositionRoot.resolveVariant}'s degraded-selection WARN (tempdoc 691 B-5). Under
 * {@code justsearch.models.capability_contract_strict} (default {@code false} until 657 ships
 * manifests in packs), a non-empty warning list is a hard failure for that encoder lane instead —
 * the caller's existing {@code Optional.empty()} composition pattern absorbs it per-lane.
 *
 * <p>Ecosystem readers (S-C.R R-1, verified against the real Alibaba-NLP/gte-multilingual-base HF
 * repo): sentence-transformers {@code 1_Pooling/config.json} — supports BOTH schema generations
 * (older boolean flags {@code pooling_mode_cls_token}/{@code pooling_mode_mean_tokens}, newer
 * string {@code pooling_mode}); {@code sentence_bert_config.json} {@code max_seq_length}
 * cross-checked against {@code config.json} {@code max_position_embeddings} (disagreement → WARN,
 * prefer the smaller value — safest); {@code config.json} {@code hidden_size} for dimension;
 * {@code config_sentence_transformers.json} {@code prompts} for prefixes, IF present (absence is
 * never treated as "no prefix" — verified 404 in practice for every model checked so far).
 * {@code tokenizer_config.json model_max_length} is NEVER read for context length — verified wrong
 * for gte-multilingual-base (32768 vs the real trained 8192).
 */
public final class ModelCapabilityResolver {

  private static final Logger log = LoggerFactory.getLogger(ModelCapabilityResolver.class);

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  // Reverse-DNS metadata_props keys stamped by scripts/models/_common.py stamp_capabilities at
  // model-build time (tempdoc 711 Item 3) — a projection of the manifest's `capabilities` section
  // baked directly into the ONNX file, so the fact travels with the model even if the manifest is
  // hand-edited later without a rebuild.
  private static final String META_POOLING_MODE = "io.justsearch.pooling_mode";
  private static final String META_CONTEXT_LENGTH = "io.justsearch.context_length";
  private static final String META_EMBEDDING_DIMENSION = "io.justsearch.embedding_dimension";
  private static final String META_CPU_PRECISION = "io.justsearch.cpu_precision";
  private static final String META_GPU_PRECISION = "io.justsearch.gpu_precision";
  private static final String META_DOCUMENT_PREFIX = "io.justsearch.document_prefix";
  private static final String META_QUERY_PREFIX = "io.justsearch.query_prefix";

  private ModelCapabilityResolver() {}

  /**
   * Exact conservative union of filesystem candidates the resolver may consult for the requested
   * capability facts. This is intentionally not a dynamic trace of only paths opened on one run:
   * present and absent paths both matter because a later file appearance can change a fallback.
   * Custom manifest references are resolved here, beside the code that consumes them, so callers
   * which freeze model inputs do not maintain a parallel filename inventory.
   */
  public record InputCandidates(
      Path manifest,
      Path sentenceTransformersPooling,
      Path legacyPooling,
      Path sentenceBertConfig,
      Path modelConfig,
      Path sentenceTransformersConfig,
      Path legacyPrefixConfig,
      Path labelConfig) {

    /** Immutable set containing only candidates relevant to {@code requirements}. */
    public Set<Path> forRequirements(CapabilityRequirements requirements) {
      java.util.LinkedHashSet<Path> paths = new java.util.LinkedHashSet<>();
      paths.add(manifest);
      if (requirements.requires(CapabilityRequirements.Fact.POOLING)) {
        paths.add(sentenceTransformersPooling);
        paths.add(legacyPooling);
      }
      if (requirements.requires(CapabilityRequirements.Fact.CONTEXT_LENGTH)) {
        paths.add(sentenceBertConfig);
        paths.add(modelConfig);
      }
      if (requirements.requires(CapabilityRequirements.Fact.DIMENSION)) {
        paths.add(modelConfig);
      }
      if (requirements.requires(CapabilityRequirements.Fact.PREFIXES)) {
        paths.add(sentenceTransformersConfig);
        paths.add(legacyPrefixConfig);
      }
      if (requirements.requires(CapabilityRequirements.Fact.LABELS)) {
        paths.add(labelConfig);
      }
      return Set.copyOf(paths);
    }
  }

  /** Builds the candidate projection shared by resolution and pre-native input capture. */
  public static InputCandidates inputCandidates(Path modelDir, ModelManifest manifest) {
    Path exactDir = modelDir.toAbsolutePath().normalize();
    return new InputCandidates(
        manifestPath(exactDir),
        exactDir.resolve("1_Pooling").resolve("config.json"),
        exactDir.resolve(manifest.poolingConfig()).normalize(),
        exactDir.resolve("sentence_bert_config.json"),
        exactDir.resolve("config.json"),
        exactDir.resolve("config_sentence_transformers.json"),
        exactDir.resolve("prefix_config.json"),
        exactDir.resolve(manifest.labelConfig()).normalize());
  }

  /** Exact manifest candidate used by {@link ModelManifest#loadOrDefault}. */
  public static Path manifestPath(Path modelDir) {
    return modelDir.toAbsolutePath().normalize().resolve("model_manifest.json");
  }

  /**
   * Resolves capabilities for {@code modelDir}. Logs every degraded/undeclared fact at WARN — but
   * only for facts {@code requirements} names; a fact the consuming role never reads is never
   * resolved and never warned about (tempdoc 710 Wave 2 Move 2 — see {@link
   * CapabilityRequirements} for why: an always-fires WARN on a healthy config trains operators to
   * ignore warnings).
   *
   * @param packageId short encoder identifier for log lines (e.g. {@code "embedding"}, {@code
   *     "ner"}) — mirrors {@code InferenceCompositionRoot.resolveVariant}'s {@code packageId}
   * @param modelDir directory containing the manifest, model files, and any ecosystem/legacy
   *     sidecar files
   * @param manifest the already-loaded manifest for {@code modelDir}
   * @param requirements which facts this role reads; every other fact stays at its "undeclared"
   *     sentinel with zero warnings
   * @param strict when {@code true}, a non-empty warning list throws {@link IllegalStateException}
   *     instead of returning a degraded {@link ModelCapabilities} — {@code
   *     justsearch.models.capability_contract_strict}
   * @throws IllegalStateException if {@code strict} and any required fact was undeclared/ambiguous
   */
  public static ModelCapabilities resolve(
      String packageId,
      Path modelDir,
      ModelManifest manifest,
      CapabilityRequirements requirements,
      boolean strict) {
    return resolve(packageId, modelDir, manifest, requirements, strict, null);
  }

  /** Resolves capabilities against an already-selected exact ONNX variant. */
  public static ModelCapabilities resolve(
      String packageId,
      Path modelDir,
      ModelManifest manifest,
      CapabilityRequirements requirements,
      boolean strict,
      Path exactModelPath) {
    List<String> warnings = new ArrayList<>();
    InputCandidates inputs = inputCandidates(modelDir, manifest);

    // Embedded ONNX metadata_props (tempdoc 711 Item 3): one short-lived probe session per
    // resolve() call, not per fact — every resolveX below that consults embedded metadata reads
    // off this same map. Lazily skipped when LABELS is the only requested fact (LABELS is out of
    // scope for the embedded rung), so an NER-only resolve doesn't pay for an unused probe.
    boolean needsEmbeddedMetadata =
        requirements.requires(CapabilityRequirements.Fact.POOLING)
            || requirements.requires(CapabilityRequirements.Fact.CONTEXT_LENGTH)
            || requirements.requires(CapabilityRequirements.Fact.DIMENSION)
            || requirements.requires(CapabilityRequirements.Fact.PRECISION)
            || requirements.requires(CapabilityRequirements.Fact.PREFIXES);
    Map<String, String> embedded =
        needsEmbeddedMetadata
            ? readEmbeddedMetadata(modelDir, manifest, exactModelPath, warnings) : Map.of();

    ModelCapabilities.PoolingMode poolingMode =
        requirements.requires(CapabilityRequirements.Fact.POOLING)
            ? resolvePoolingMode(manifest, inputs, embedded, warnings)
            : ModelCapabilities.PoolingMode.UNKNOWN;
    int contextLength =
        requirements.requires(CapabilityRequirements.Fact.CONTEXT_LENGTH)
            ? resolveContextLength(manifest, inputs, embedded, warnings)
            : 0;
    int dimension = 0;
    if (requirements.requires(CapabilityRequirements.Fact.DIMENSION)) {
      dimension = resolveDimension(manifest, inputs, embedded, warnings);
      if (dimension <= 0) {
        dimension = probeStaticEmbeddingDimension(modelDir, manifest, exactModelPath, warnings);
      }
    }
    ModelPrecision cpuPrecision = null;
    ModelPrecision gpuPrecision = null;
    if (requirements.requires(CapabilityRequirements.Fact.PRECISION)) {
      cpuPrecision =
          resolvePrecision(
              manifest.capabilities().cpuPrecision(),
              embedded.get(META_CPU_PRECISION),
              manifest.cpu(),
              "cpu",
              warnings);
      gpuPrecision =
          resolvePrecision(
              manifest.capabilities().gpuPrecision(),
              embedded.get(META_GPU_PRECISION),
              manifest.gpu(),
              "gpu",
              warnings);
    }
    String[] prefixes =
        requirements.requires(CapabilityRequirements.Fact.PREFIXES)
            ? resolvePrefixes(manifest, inputs, embedded, warnings)
            : new String[] {null, null};
    Map<String, String> labelMapping =
        requirements.requires(CapabilityRequirements.Fact.LABELS)
            ? resolveLabelMapping(modelDir, manifest, inputs, warnings)
            : Map.of();

    for (String warning : warnings) {
      log.warn("{}: model capability degraded — {}", packageId, warning);
    }
    if (strict && !warnings.isEmpty()) {
      throw new IllegalStateException(
          packageId
              + ": capability_contract_strict mode — "
              + warnings.size()
              + " undeclared/ambiguous capability fact(s) for "
              + modelDir
              + ": "
              + warnings);
    }

    return new ModelCapabilities(
        poolingMode,
        contextLength,
        dimension,
        cpuPrecision,
        gpuPrecision,
        prefixes[0],
        prefixes[1],
        labelMapping,
        List.copyOf(warnings));
  }

  // ---------------------------------------------------------------------------
  // Pooling mode
  // ---------------------------------------------------------------------------

  private static ModelCapabilities.PoolingMode resolvePoolingMode(
      ModelManifest manifest, InputCandidates inputs, Map<String, String> embedded,
      List<String> warnings) {
    String declared = manifest.capabilities().poolingMode();
    String embeddedValue = embedded.get(META_POOLING_MODE);
    if (declared != null && !declared.isBlank()) {
      ModelCapabilities.PoolingMode parsed = parsePoolingMode(declared);
      if (parsed != ModelCapabilities.PoolingMode.UNKNOWN) {
        warnIfDisagree(warnings, "pooling_mode", declared, embeddedValue);
        return parsed;
      }
      warnings.add("manifest capabilities.pooling_mode='" + declared + "' unrecognized (expected cls|mean)");
    }

    // Embedded ONNX metadata_props (tempdoc 711 Item 3) — one rung below the manifest, ahead of
    // the ecosystem-file readers: a build-time-stamped projection of the manifest, so a model
    // shipped without its manifest (or with a manifest that predates a rebuild) still carries the
    // fact.
    if (embeddedValue != null && !embeddedValue.isBlank()) {
      ModelCapabilities.PoolingMode parsed = parsePoolingMode(embeddedValue);
      if (parsed != ModelCapabilities.PoolingMode.UNKNOWN) {
        return parsed;
      }
      warnings.add(
          "embedded ONNX metadata_props pooling_mode='" + embeddedValue + "' unrecognized (expected cls|mean)");
    }

    // Sentence-transformers ecosystem: 1_Pooling/config.json, both schema generations.
    Path stPooling = inputs.sentenceTransformersPooling();
    if (Files.exists(stPooling)) {
      try {
        JsonNode root = readJson(stPooling);
        JsonNode modeNode = root.get("pooling_mode");
        if (modeNode != null && modeNode.isTextual()) {
          ModelCapabilities.PoolingMode parsed = parsePoolingMode(modeNode.asText());
          if (parsed != ModelCapabilities.PoolingMode.UNKNOWN) {
            return parsed;
          }
        }
        if (asBoolean(root.get("pooling_mode_cls_token"))) {
          return ModelCapabilities.PoolingMode.CLS;
        }
        if (asBoolean(root.get("pooling_mode_mean_tokens"))) {
          return ModelCapabilities.PoolingMode.MEAN;
        }
      } catch (Exception e) {
        warnings.add("failed to read 1_Pooling/config.json: " + e.getMessage());
      }
    }

    // Legacy sidecar — deprecated generation, kept as a fallback during the migration window
    // (tombstone: removed once packs ship manifests, tempdoc 657).
    Path legacy = inputs.legacyPooling();
    if (Files.exists(legacy)) {
      try {
        String content = Files.readString(legacy);
        if (content.contains("\"cls\"")) {
          return ModelCapabilities.PoolingMode.CLS;
        }
        if (content.contains("\"mean\"")) {
          return ModelCapabilities.PoolingMode.MEAN;
        }
      } catch (Exception e) {
        warnings.add("failed to read legacy " + manifest.poolingConfig() + ": " + e.getMessage());
      }
    }

    warnings.add(
        "pooling mode undeclared (no manifest field, 1_Pooling/config.json, or legacy "
            + manifest.poolingConfig()
            + ")");
    return ModelCapabilities.PoolingMode.UNKNOWN;
  }

  private static ModelCapabilities.PoolingMode parsePoolingMode(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "cls" -> ModelCapabilities.PoolingMode.CLS;
      case "mean" -> ModelCapabilities.PoolingMode.MEAN;
      default -> ModelCapabilities.PoolingMode.UNKNOWN;
    };
  }

  // ---------------------------------------------------------------------------
  // Trained context length
  // ---------------------------------------------------------------------------

  private static int resolveContextLength(
      ModelManifest manifest, InputCandidates inputs, Map<String, String> embedded,
      List<String> warnings) {
    Integer declared = manifest.capabilities().contextLength();
    Integer embeddedValue = parseEmbeddedInt(embedded.get(META_CONTEXT_LENGTH), "context_length", warnings);
    if (declared != null && declared > 0) {
      warnIfDisagree(warnings, "context_length", declared, embeddedValue);
      return declared;
    }
    if (embeddedValue != null && embeddedValue > 0) {
      return embeddedValue;
    }

    Integer stSeqLen = readIntField(inputs.sentenceBertConfig(), "max_seq_length", warnings);
    Integer configMaxPos = readIntField(inputs.modelConfig(), "max_position_embeddings", warnings);

    if (stSeqLen != null && configMaxPos != null) {
      if (!stSeqLen.equals(configMaxPos)) {
        int chosen = Math.min(stSeqLen, configMaxPos);
        warnings.add(
            "context length disagreement: sentence_bert_config.json max_seq_length="
                + stSeqLen
                + " vs config.json max_position_embeddings="
                + configMaxPos
                + " — using the smaller value ("
                + chosen
                + ")");
        return chosen;
      }
      return stSeqLen;
    }
    if (stSeqLen != null) {
      return stSeqLen;
    }
    if (configMaxPos != null) {
      return configMaxPos;
    }

    warnings.add(
        "trained context length undeclared (no manifest field, sentence_bert_config.json"
            + " max_seq_length, or config.json max_position_embeddings — tokenizer_config.json"
            + " model_max_length is deliberately never trusted, S-C.R)");
    return 0;
  }

  // ---------------------------------------------------------------------------
  // Embedding dimension
  // ---------------------------------------------------------------------------

  private static int resolveDimension(
      ModelManifest manifest, InputCandidates inputs, Map<String, String> embedded,
      List<String> warnings) {
    Integer declared = manifest.capabilities().embeddingDimension();
    Integer embeddedValue =
        parseEmbeddedInt(embedded.get(META_EMBEDDING_DIMENSION), "embedding_dimension", warnings);
    if (declared != null && declared > 0) {
      warnIfDisagree(warnings, "embedding_dimension", declared, embeddedValue);
      return declared;
    }
    if (embeddedValue != null && embeddedValue > 0) {
      return embeddedValue;
    }
    Integer hiddenSize = readIntField(inputs.modelConfig(), "hidden_size", warnings);
    if (hiddenSize != null && hiddenSize > 0) {
      return hiddenSize;
    }
    return 0;
  }

  /**
   * Boot-time graph probe (no inference run) for the trailing output-tensor axis, used only when
   * neither the manifest nor {@code config.json} declares the dimension. Dynamic shapes (batch and
   * sequence axes are typically {@code -1}) yield no signal on most outputs; the trailing axis is
   * typically the one statically-sized dimension for an embedding output. Best-effort — any probe
   * failure degrades to the reactive first-inference detection already in {@code
   * OnnxEmbeddingEncoder}, not a hard error.
   */
  private static int probeStaticEmbeddingDimension(
      Path modelDir, ModelManifest manifest, Path exactModelPath, List<String> warnings) {
    try {
      Path modelFile = exactModelPath != null ? exactModelPath
          : manifest.resolveExistingModelFile(modelDir);
      if (!Files.isRegularFile(modelFile)) {
        return 0;
      }
      OrtEnvironment env = OrtEnvironment.getEnvironment();
      OrtSessionAssembler.ProbedNames names = OrtSessionAssembler.probeModelNames(env, modelFile);
      for (String outputName : names.outputs()) {
        Optional<OrtSessionAssembler.ProbedTensorInfo> info =
            OrtSessionAssembler.probeOutputTensorInfo(env, modelFile, outputName);
        if (info.isEmpty()) {
          continue;
        }
        long[] shape = info.get().shape();
        if (shape.length > 0 && shape[shape.length - 1] > 0) {
          return (int) shape[shape.length - 1];
        }
      }
    } catch (OrtException e) {
      warnings.add("boot-probe for embedding dimension failed: " + e.getMessage());
      return 0;
    }
    warnings.add(
        "embedding dimension undeclared (no manifest field, config.json hidden_size, or"
            + " statically-shaped graph output) — falling back to reactive first-inference"
            + " detection");
    return 0;
  }

  // ---------------------------------------------------------------------------
  // Embedded ONNX metadata_props (tempdoc 711 Item 3)
  // ---------------------------------------------------------------------------

  /**
   * Reads the resolved model file's embedded {@code metadata_props} once per {@link #resolve}
   * call via a single short-lived probe session ({@link OrtSessionAssembler#probeCustomMetadata}
   * — mirrors {@link #probeStaticEmbeddingDimension}'s existing-file guard so a manifest-declared
   * file name that doesn't exist on disk degrades to "no embedded metadata" rather than throwing.
   * Every {@code resolveX} method that consults embedded metadata reads off the same returned map
   * — this is the only place a probe session is opened for that purpose.
   */
  private static Map<String, String> readEmbeddedMetadata(
      Path modelDir, ModelManifest manifest, Path exactModelPath, List<String> warnings) {
    try {
      Path modelFile = exactModelPath != null ? exactModelPath
          : manifest.resolveExistingModelFile(modelDir);
      if (!Files.isRegularFile(modelFile)) {
        return Map.of();
      }
      OrtEnvironment env = OrtEnvironment.getEnvironment();
      return OrtSessionAssembler.probeCustomMetadata(env, modelFile);
    } catch (OrtException e) {
      warnings.add("boot-probe for embedded ONNX metadata_props failed: " + e.getMessage());
      return Map.of();
    }
  }

  /** Parses an embedded metadata_props string value as a positive integer; null-safe. */
  private static Integer parseEmbeddedInt(String raw, String factName, List<String> warnings) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      warnings.add(
          "embedded ONNX metadata_props " + factName + "='" + raw + "' is not a valid integer");
      return null;
    }
  }

  /**
   * Records a WARN when both the manifest and the embedded ONNX metadata declare a fact and the
   * values disagree — the manifest value always wins (it's the primary-authored source; the
   * embedded value is a build-time projection of it, so disagreement means the model file wasn't
   * rebuilt after the manifest was last edited). String values ({@code pooling_mode}, {@code
   * *_precision}) compare case-insensitively, trimmed — matching {@link #parsePoolingMode}/{@link
   * #parsePrecision}'s own normalization. Non-string values (context length, dimension) compare
   * via {@link Object#equals}.
   */
  private static void warnIfDisagree(
      List<String> warnings, String factName, Object manifestValue, Object embeddedValue) {
    if (manifestValue == null || embeddedValue == null) {
      return;
    }
    boolean agree =
        (manifestValue instanceof String s1 && embeddedValue instanceof String s2)
            ? s1.trim().equalsIgnoreCase(s2.trim())
            : manifestValue.equals(embeddedValue);
    if (!agree) {
      warnings.add(
          factName
              + " disagreement: manifest='"
              + manifestValue
              + "' vs embedded ONNX metadata_props='"
              + embeddedValue
              + "' — manifest wins");
    }
  }

  /**
   * Same contract as {@link #warnIfDisagree} but for task-instruction prefixes: an exact,
   * case-sensitive, untrimmed comparison — prefix text is literal (e.g. a trailing space in
   * {@code "search_document: "} is meaningful), unlike the enum-like pooling-mode/precision
   * facts.
   */
  private static void warnIfPrefixDisagrees(
      List<String> warnings, String factName, String manifestValue, String embeddedValue) {
    if (manifestValue == null || embeddedValue == null || manifestValue.equals(embeddedValue)) {
      return;
    }
    warnings.add(
        factName
            + " disagreement: manifest='"
            + manifestValue
            + "' vs embedded ONNX metadata_props='"
            + embeddedValue
            + "' — manifest wins");
  }

  // ---------------------------------------------------------------------------
  // Precision (per-variant; sanity-checked, never sourced, from I/O dtype)
  // ---------------------------------------------------------------------------

  private static ModelPrecision resolvePrecision(
      String declared, String embeddedValue, String variantFile, String label, List<String> warnings) {
    if (declared != null && !declared.isBlank()) {
      ModelPrecision parsed = parsePrecision(declared);
      if (parsed != null) {
        warnIfDisagree(warnings, label + "_precision", declared, embeddedValue);
        return parsed;
      }
      warnings.add("manifest capabilities." + label + "_precision='" + declared + "' unrecognized");
    }

    // Embedded ONNX metadata_props (tempdoc 711 Item 3) — one rung below the manifest, ahead of
    // the legacy filename-substring heuristic.
    if (embeddedValue != null && !embeddedValue.isBlank()) {
      ModelPrecision parsed = parsePrecision(embeddedValue);
      if (parsed != null) {
        return parsed;
      }
      warnings.add(
          "embedded ONNX metadata_props " + label + "_precision='" + embeddedValue + "' unrecognized");
    }

    if (variantFile == null || variantFile.isBlank()) {
      return null;
    }
    // Legacy fallback: filename-substring heuristic (tempdoc 710 orphan #4 — narrowed to a
    // fallback with a WARN, not the sole mechanism it was pre-Wave-2).
    String lower = variantFile.toLowerCase(Locale.ROOT);
    ModelPrecision guessed;
    if (lower.contains("int8")) {
      guessed = ModelPrecision.INT8;
    } else if (lower.contains("fp16")) {
      guessed = ModelPrecision.FP16;
    } else {
      guessed = ModelPrecision.FP32;
    }
    warnings.add(
        label
            + " precision undeclared for '"
            + variantFile
            + "' — using legacy filename-substring heuristic: "
            + guessed);
    return guessed;
  }

  private static ModelPrecision parsePrecision(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "fp32" -> ModelPrecision.FP32;
      case "fp16" -> ModelPrecision.FP16;
      case "int8" -> ModelPrecision.INT8;
      case "gguf" -> ModelPrecision.GGUF;
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // Task-instruction prefixes
  // ---------------------------------------------------------------------------

  private static String[] resolvePrefixes(
      ModelManifest manifest, InputCandidates inputs, Map<String, String> embedded,
      List<String> warnings) {
    ModelManifest.Capabilities caps = manifest.capabilities();
    String doc = caps.documentPrefix();
    String query = caps.queryPrefix();

    // Embedded ONNX metadata_props (tempdoc 711 Item 3) — one rung below the manifest. Each
    // prefix is resolved independently (matching the null-vs-empty-string semantics the rest of
    // this method already preserves): a manifest-declared value (including a declared-empty "")
    // always wins over the embedded value, with a WARN on disagreement; a manifest-undeclared
    // (null) prefix picks up the embedded value if present before falling through to the
    // ecosystem-file readers below.
    String embeddedDoc = embedded.get(META_DOCUMENT_PREFIX);
    String embeddedQuery = embedded.get(META_QUERY_PREFIX);
    if (doc != null) {
      warnIfPrefixDisagrees(warnings, "document_prefix", doc, embeddedDoc);
    } else if (embeddedDoc != null) {
      doc = embeddedDoc;
    }
    if (query != null) {
      warnIfPrefixDisagrees(warnings, "query_prefix", query, embeddedQuery);
    } else if (embeddedQuery != null) {
      query = embeddedQuery;
    }

    // Sentence-transformers ecosystem: config_sentence_transformers.json `prompts`, IF present
    // (S-C.R: verified unpopulated in practice for gte-multilingual-base AND
    // multilingual-e5-large — absence is never "no prefix").
    if (doc == null || query == null) {
      Path stPrompts = inputs.sentenceTransformersConfig();
      if (Files.exists(stPrompts)) {
        try {
          JsonNode root = readJson(stPrompts);
          JsonNode prompts = root.get("prompts");
          if (prompts != null && prompts.isObject()) {
            if (doc == null) {
              JsonNode docNode = prompts.get("document");
              if (docNode != null && docNode.isTextual()) {
                doc = docNode.asText();
              }
            }
            if (query == null) {
              JsonNode queryNode = prompts.get("query");
              if (queryNode != null && queryNode.isTextual()) {
                query = queryNode.asText();
              }
            }
          }
        } catch (Exception e) {
          warnings.add("failed to read config_sentence_transformers.json: " + e.getMessage());
        }
      }
    }

    // Legacy sidecar — deprecated generation.
    if (doc == null || query == null) {
      Path legacy = inputs.legacyPrefixConfig();
      if (Files.exists(legacy)) {
        try {
          JsonNode root = readJson(legacy);
          if (doc == null) {
            JsonNode d = root.get("document_prefix");
            if (d != null && d.isTextual()) {
              doc = d.asText();
            }
          }
          if (query == null) {
            JsonNode q = root.get("query_prefix");
            if (q != null && q.isTextual()) {
              query = q.asText();
            }
          }
        } catch (Exception e) {
          warnings.add("failed to read legacy prefix_config.json: " + e.getMessage());
        }
      }
    }

    if (doc == null || query == null) {
      warnings.add(
          "prefix(es) undeclared (no manifest field, config_sentence_transformers.json prompts,"
              + " or legacy prefix_config.json) — caller applies its own documented fallback");
    }
    return new String[] {doc, query};
  }

  // ---------------------------------------------------------------------------
  // Label mapping (NER)
  // ---------------------------------------------------------------------------

  private static Map<String, String> resolveLabelMapping(
      Path modelDir, ModelManifest manifest, InputCandidates inputs, List<String> warnings) {
    Path configFile = inputs.labelConfig();
    if (!Files.exists(configFile)) {
      warnings.add(
          "label config '" + manifest.labelConfig() + "' not found in " + modelDir);
      return Map.of();
    }
    try {
      JsonNode root = readJson(configFile);
      JsonNode id2label = root.get("id2label");
      if (id2label == null || !id2label.isObject()) {
        warnings.add("no id2label object in " + manifest.labelConfig());
        return Map.of();
      }
      Map<String, String> mapping = new LinkedHashMap<>();
      for (var entry : id2label.properties()) {
        mapping.put(entry.getKey(), entry.getValue().asText());
      }
      return mapping;
    } catch (Exception e) {
      warnings.add("failed to read " + manifest.labelConfig() + ": " + e.getMessage());
      return Map.of();
    }
  }

  // ---------------------------------------------------------------------------
  // JSON helpers
  // ---------------------------------------------------------------------------

  private static JsonNode readJson(Path file) {
    return JSON.readTree(file.toFile());
  }

  private static boolean asBoolean(JsonNode node) {
    return node != null && node.isBoolean() && node.asBoolean();
  }

  private static Integer readIntField(Path file, String field, List<String> warnings) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      JsonNode root = readJson(file);
      JsonNode node = root.get(field);
      if (node != null && node.isNumber()) {
        return node.asInt();
      }
      return null;
    } catch (Exception e) {
      warnings.add("failed to read " + file.getFileName() + ": " + e.getMessage());
      return null;
    }
  }
}
