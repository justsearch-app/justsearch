/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes the filesystem for a model variant when {@link
 * io.justsearch.configuration.model.InstallContract} is absent — the dev-mode branch that was
 * previously inlined into {@code KnowledgeServer.resolveVariant}.
 *
 * <p>Tempdoc 397 §14.26 T2-A1: centralising this logic here completes §7.6's intent
 * (<em>"dev mode does not require a second path — it requires the resolver to have a fallback"</em>).
 * After T2-A1, every {@link VariantSelection} in the JVM comes from one of two sibling
 * resolver paths — contract-driven via
 * {@link io.justsearch.configuration.model.VariantSelector#select} or filesystem-probed here.
 * The composition root / assembler never distinguishes the two.
 *
 * <p>Probe semantics (ported verbatim from {@code KnowledgeServer.resolveVariant:1071-1104}):
 *
 * <ul>
 *   <li>Returns {@code null} if {@code modelDir} does not exist or is not a directory.
 *   <li>Loads {@link ModelManifest} to resolve CPU + GPU paths (falling back to the convention
 *       {@code model.onnx} / {@code model_fp16.onnx} when no manifest present).
 *   <li>Accepts an {@code .optimized} sidecar in place of the bare file (the ORT
 *       graph-optimisation cache can exist without the original when a build was incremental).
 *   <li>Prefers the GPU file when {@code gpuEnabled} and it exists; falls back to CPU file
 *       with {@link ExecutionProvider#CUDA} when only CPU file is present and GPU is enabled
 *       (the {@link NativeSessionHandle} will attempt a GPU session from the CPU file and
 *       retry-to-CPU on failure) — reported as a <em>degraded</em> selection since tempdoc 691,
 *       mirroring {@code VariantSelector}'s CPU-on-CUDA branch; otherwise CPU file with
 *       {@link ExecutionProvider#CPU}.
 *   <li>Precision detection uses a substring check for {@code "fp16"} in the filename.
 * </ul>
 */
public final class DevModeVariantProbe {

  private static final Logger log = LoggerFactory.getLogger(DevModeVariantProbe.class);

  private DevModeVariantProbe() {}

  /**
   * Probes {@code modelDir} for a loadable ONNX model file; returns a {@link VariantSelection} or
   * {@code null} if nothing loadable is present.
   *
   * @param modelDir the per-encoder model directory (e.g., {@code models/onnx/gte-multilingual-base})
   * @param gpuEnabled whether the caller wants a GPU variant if one is available
   */
  public static VariantSelection probe(Path modelDir, boolean gpuEnabled) {
    if (modelDir == null || !Files.isDirectory(modelDir)) {
      return null;
    }
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path cpuModelFile = manifest.resolveModelPath(modelDir, false);
    Path gpuModelFile = manifest.resolveModelPath(modelDir, true);

    boolean gpuFileExists = gpuEnabled && Files.exists(gpuModelFile);
    boolean cpuFileExists = Files.exists(cpuModelFile);

    // Also check for optimized cache (model.onnx may not exist but model.onnx.optimized does).
    if (!cpuFileExists) {
      cpuFileExists = Files.exists(Path.of(cpuModelFile + ".optimized"));
    }
    if (!gpuFileExists && gpuEnabled) {
      gpuFileExists = Files.exists(Path.of(gpuModelFile + ".optimized"));
    }

    if (gpuFileExists) {
      ModelPrecision precision =
          declaredOrGuessedPrecision(
              manifest.capabilities().gpuPrecision(), gpuModelFile.getFileName().toString());
      return VariantSelection.optimal(gpuModelFile, precision, ExecutionProvider.CUDA);
    }
    if (cpuFileExists) {
      ModelPrecision precision =
          declaredOrGuessedPrecision(
              manifest.capabilities().cpuPrecision(), cpuModelFile.getFileName().toString());
      if (gpuEnabled) {
        // No dedicated GPU model file — use CPU model with CUDA. NativeSessionHandle attempts
        // a GPU session from the CPU model file and retries to CPU on failure. This is a
        // DEGRADED selection, mirroring the contract path (VariantSelector's CPU-on-CUDA
        // branch): the CPU variant may be quantized (INT8), whose QOperator nodes lack CUDA
        // kernels and run at ~10× per-call cost via per-node CPU fallback. Reporting it as
        // optimal hid exactly that for the NER encoder (tempdoc 691 B-5).
        return VariantSelection.degraded(
            cpuModelFile,
            precision,
            ExecutionProvider.CUDA,
            "GPU variant ("
                + gpuModelFile.getFileName()
                + ") not present — running CPU-variant file "
                + cpuModelFile.getFileName()
                + " on CUDA");
      }
      return VariantSelection.optimal(cpuModelFile, precision, ExecutionProvider.CPU);
    }
    return null;
  }

  /**
   * Selects one already-bound generation file without falling back to a sibling CPU/GPU variant.
   * The caller verifies the file's recorded content identity before using this projection.
   */
  public static VariantSelection probeExact(Path modelFile, boolean gpuEnabled) {
    if (modelFile == null || !Files.isRegularFile(modelFile, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    Path modelDir = modelFile.getParent();
    if (modelDir == null) return null;
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path cpuFile = manifest.resolveModelPath(modelDir, false).normalize();
    Path gpuFile = manifest.resolveModelPath(modelDir, true).normalize();
      boolean dedicatedGpuFile = manifest.gpu() != null && !manifest.gpu().isBlank()
          && !gpuFile.equals(cpuFile);
      boolean selectedGpuFile = dedicatedGpuFile && modelFile.normalize().equals(gpuFile);
      boolean selectedCpuFile = modelFile.normalize().equals(cpuFile);
      if (!selectedGpuFile && !selectedCpuFile) {
        log.warn("Exact generation model {} is not declared by its model manifest", modelFile);
        return null;
      }
      String declaredPrecision = selectedGpuFile
        ? manifest.capabilities().gpuPrecision() : manifest.capabilities().cpuPrecision();
    ModelPrecision precision = declaredOrGuessedPrecision(
        declaredPrecision, modelFile.getFileName().toString());
    if (!gpuEnabled && precision == ModelPrecision.FP16) {
      log.warn("Exact generation model {} requires FP16 but CUDA is unavailable", modelFile);
      return null;
    }
    if (gpuEnabled && !selectedGpuFile) {
      return VariantSelection.degraded(modelFile, precision, ExecutionProvider.CUDA,
          "Generation-bound CPU variant selected on CUDA");
    }
    return VariantSelection.optimal(modelFile, precision,
        gpuEnabled ? ExecutionProvider.CUDA : ExecutionProvider.CPU);
  }

  /**
   * Tempdoc 710 Wave 2 Move 1 orphan #4: precision comes from the manifest's declared {@code
   * capabilities.cpu_precision}/{@code gpu_precision} field when present; the filename-substring
   * heuristic (pre-Wave-2 the SOLE mechanism) is now a legacy fallback with a WARN — this probe
   * keeps only file-existence duties, not precision authority. No ecosystem file is authoritative
   * for an exported ONNX file's precision (S-C.R: {@code torch_dtype} describes the checkpoint,
   * not the export), so the fallback is filename convention, not a richer file read.
   */
  private static ModelPrecision declaredOrGuessedPrecision(String declared, String fileName) {
    if (declared != null && !declared.isBlank()) {
      ModelPrecision parsed = parsePrecision(declared);
      if (parsed != null) {
        return parsed;
      }
      log.warn("Unrecognized declared precision '{}' for {} — falling back to filename convention", declared, fileName);
    }
    String lower = fileName.toLowerCase(Locale.ROOT);
    ModelPrecision guessed;
    if (lower.contains("int8")) {
      guessed = ModelPrecision.INT8;
    } else if (lower.contains("fp16")) {
      guessed = ModelPrecision.FP16;
    } else {
      guessed = ModelPrecision.FP32;
    }
    log.warn(
        "Precision undeclared for {} — using legacy filename-substring heuristic: {}",
        fileName,
        guessed);
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
}
